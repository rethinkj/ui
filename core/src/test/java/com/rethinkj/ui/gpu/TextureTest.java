package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Textures as shader inputs: texels written from memory into a region of a layer, bound as a view
 * and read back exactly with {@code textureLoad}; and the arguments that would end the process in
 * wgpu-native. Skipped without the natives module or an adapter.
 */
class TextureTest {

  /** Three layers of 16 by 8 side by side, and below them a plain texture of 4 by 4. */
  private static final int WIDTH = 48;
  private static final int HEIGHT = 12;
  private static final int BYTES_PER_ROW = 256;

  private static final String WGSL = """
      @group(0) @binding(0) var layers: texture_2d_array<f32>;
      @group(0) @binding(1) var plain: texture_2d<f32>;

      @vertex
      fn vs_main(@builtin(vertex_index) vertex: u32) -> @builtin(position) vec4f {
        let corner = vec2f(f32(vertex & 1u), f32(vertex >> 1u));
        return vec4f(corner * vec2f(2.0, -2.0) + vec2f(-1.0, 1.0), 0.0, 1.0);
      }

      // The texel of each pixel in red: layer x / 16 above, the plain texture below.
      @fragment
      fn fs_main(@builtin(position) position: vec4f) -> @location(0) vec4f {
        let p = vec2i(floor(position.xy));
        var value = 0.0;
        if p.y < 8 {
          value = textureLoad(layers, vec2i(p.x % 16, p.y), p.x / 16, 0).r;
        } else if p.x < 4 {
          value = textureLoad(plain, vec2i(p.x, p.y - 8), 0).r;
        }
        return vec4f(value, 0.0, 0.0, 1.0);
      }
      """;

  @AfterEach
  void closeInstances() {
    Gpus.closeAll();
  }

  @Test
  void aShaderReadsTheTexelsWrittenIntoRegionsOfLayersExactly() {
    try (Device device = Gpus.device("renderer");
        Arena arena = Arena.ofConfined()) {
      Texture array = device.createTextureArray("layers", 16, 8, 3, TextureFormat.R8_UNORM,
          TextureUsage.TEXTURE_BINDING, TextureUsage.COPY_DST);
      assertEquals(3, array.layers());
      assertTrue(array.isArray());
      Texture plain = device.createTexture("plain", 4, 4, TextureFormat.R8_UNORM,
          TextureUsage.TEXTURE_BINDING, TextureUsage.COPY_DST);
      assertEquals(1, plain.layers());
      assertFalse(plain.isArray());

      // An image of 40 by 12 in native memory, of which a region of 9 by 5 from 4,3 goes to 2,1
      // of layer 1: written in place, with the image's rows and an offset.
      MemorySegment image = arena.allocate(40 * 12);
      for (int i = 0; i < image.byteSize(); i++) {
        image.set(JAVA_BYTE, i, (byte) pattern(i % 40, i / 40));
      }
      device.queue().writeTexture(array, 2, 1, 1, 9, 5, image.asReadOnly(), 3 * 40 + 4, 40);
      // Layer 2 whole, from the heap; then nothing, as a rectangle of width 0.
      byte[] whole = new byte[16 * 8];
      for (int i = 0; i < whole.length; i++) {
        whole[i] = (byte) (255 - i);
      }
      device.queue().writeTexture(array, 0, 0, 2, 16, 8, MemorySegment.ofArray(whole), 0, 16);
      device.queue().writeTexture(array, 3, 3, 0, 0, 5, image, 0, 40);
      byte[] small = {0, 1, 2, 3, 4, (byte) 128, (byte) 254, (byte) 255, 9, 8, 7, 6, 5, 4, 3, 2};
      device.queue().writeTexture(plain, 0, 0, 0, 4, 4, MemorySegment.ofArray(small), 0, 4);

      byte[] pixels = draw(device, array.createView("layers"), plain.createView("plain"));

      for (int y = 0; y < 8; y++) {
        for (int x = 0; x < WIDTH; x++) {
          int layer = x / 16;
          int tx = x % 16;
          int expected = switch (layer) {
            // Never written: a texture starts zeroed.
            case 0 -> 0;
            case 1 -> tx >= 2 && tx < 11 && y >= 1 && y < 6 ? pattern(tx - 2 + 4, y - 1 + 3) : 0;
            default -> 255 - (y * 16 + tx);
          };
          assertEquals(expected, red(pixels, x, y), "layer " + layer + ", texel " + tx + "," + y);
        }
      }
      for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
          assertEquals(small[y * 4 + x] & 0xFF, red(pixels, x, 8 + y), "plain " + x + "," + y);
        }
      }
    }
  }

  @Test
  void aTextureArrayOfOneLayerIsStillAnArray() {
    try (Device device = Gpus.device("renderer")) {
      Texture one = device.createTextureArray("one layer", 16, 8, 1, TextureFormat.R8_UNORM,
          TextureUsage.TEXTURE_BINDING, TextureUsage.COPY_DST);
      Texture plain = device.createTexture("plain", 4, 4, TextureFormat.R8_UNORM,
          TextureUsage.TEXTURE_BINDING);
      device.queue().writeTexture(one, 15, 7, 0, 1, 1, MemorySegment.ofArray(new byte[] {77}), 0,
          1);
      byte[] pixels = draw(device, one.createView("one layer"), plain.createView("plain"));
      assertEquals(77, red(pixels, 15, 7));
      assertEquals(0, red(pixels, 14, 7));
    }
  }

  @Test
  void argumentsThatEndTheProcessInWgpuNativeAreRefusedFirst() {
    try (Device device = Gpus.device("renderer")) {
      int maxLayers = device.limits().maxTextureArrayLayers();
      assertTrue(maxLayers >= 256, "WebGPU's least limit: " + maxLayers);
      assertThrows(IllegalArgumentException.class, () -> device.createTextureArray("none", 16, 16,
          0, TextureFormat.R8_UNORM, TextureUsage.TEXTURE_BINDING));
      assertThrows(IllegalArgumentException.class, () -> device.createTextureArray("too many",
          16, 16, maxLayers + 1, TextureFormat.R8_UNORM, TextureUsage.TEXTURE_BINDING));

      Texture texture = device.createTextureArray("layers", 16, 8, 2, TextureFormat.R8_UNORM,
          TextureUsage.TEXTURE_BINDING, TextureUsage.COPY_DST);
      MemorySegment data = MemorySegment.ofArray(new byte[16 * 8]);
      Queue queue = device.queue();
      // Outside the layer, a layer too many, negative.
      assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(texture, 8, 0, 0, 9, 1, data, 0, 16));
      assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(texture, 0, 0, 2, 1, 1, data, 0, 16));
      assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(texture, -1, 0, 0, 1, 1, data, 0, 16));
      // A row of 0 bytes ends the process; one shorter than the rectangle's is refused too.
      IllegalArgumentException rows = assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(texture, 0, 0, 0, 4, 2, data, 0, 0));
      assertTrue(rows.getMessage().contains("a row needs at least 4 bytes"), rows.getMessage());
      assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(texture, 0, 0, 0, 4, 2, data, 0, 3));
      // The last row would end beyond the data.
      assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(texture, 0, 0, 0, 16, 8, data, 1, 16));
      // Mip levels: no more than the size allows, written only within their own size.
      assertThrows(IllegalArgumentException.class, () -> device.createTexture("no levels", 16, 8,
          0, TextureFormat.RGBA8_UNORM, TextureUsage.TEXTURE_BINDING));
      assertThrows(IllegalArgumentException.class, () -> device.createTexture("too many levels",
          16, 8, 6, TextureFormat.RGBA8_UNORM, TextureUsage.TEXTURE_BINDING));
      Texture levels = device.createTexture("levels", 16, 8, 5, TextureFormat.RGBA8_UNORM,
          TextureUsage.TEXTURE_BINDING, TextureUsage.COPY_DST);
      assertEquals(4, levels.width(2));
      assertEquals(1, levels.height(4));
      MemorySegment texels = MemorySegment.ofArray(new byte[16 * 8 * 4]);
      queue.writeTexture(levels, 2, 0, 0, 0, 4, 2, texels, 0, 16);
      queue.writeTexture(levels, 4, 0, 0, 0, 1, 1, texels, 0, 4);
      assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(levels, 2, 0, 0, 0, 5, 2, texels, 0, 20));
      assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(levels, 5, 0, 0, 0, 1, 1, texels, 0, 4));
      Texture readOnly = device.createTexture("not a copy target", 4, 4, TextureFormat.R8_UNORM,
          TextureUsage.TEXTURE_BINDING);
      assertThrows(IllegalArgumentException.class,
          () -> queue.writeTexture(readOnly, 0, 0, 0, 1, 1, data, 0, 16));

      // A bind group entry with neither a buffer nor a view ends the process; so does a layout
      // entry for a texture without a view dimension, which the layer always sets.
      assertThrows(IllegalArgumentException.class, () -> new BindGroup.Entry(0, null, 0, -1,
          null));
      Buffer buffer = device.createBuffer("uniform", 16, BufferUsage.UNIFORM);
      TextureView view = texture.createView("layers");
      assertThrows(IllegalArgumentException.class, () -> new BindGroup.Entry(0, buffer, 0, -1,
          view));
      assertThrows(IllegalArgumentException.class, () -> new BindGroupLayout.Entry(0,
          BindGroupLayout.TextureType.TEXTURE_2D, Set.of(ShaderStage.FRAGMENT), 16));

      // Mistakes that wgpu reports as validation errors: exceptions, and the device goes on.
      BindGroupLayout layout = device.createBindGroupLayout("textures",
          BindGroupLayout.Entry.textureArray(0, ShaderStage.FRAGMENT),
          BindGroupLayout.Entry.texture(1, ShaderStage.FRAGMENT));
      assertEquals(BindGroupLayout.TextureType.TEXTURE_2D_ARRAY, layout.entries().get(0).type());
      GpuException swapped = assertThrows(GpuException.class, () -> device.createBindGroup(
          "swapped", layout, BindGroup.Entry.texture(0, readOnly.createView("plain")),
          BindGroup.Entry.texture(1, view)));
      assertEquals(GpuException.Kind.VALIDATION, swapped.kind());
      Texture unbindable = device.createTexture("render target", 4, 4, TextureFormat.R8_UNORM,
          TextureUsage.RENDER_ATTACHMENT);
      GpuException usage = assertThrows(GpuException.class, () -> device.createBindGroup(
          "no binding usage", layout, BindGroup.Entry.texture(0, view),
          BindGroup.Entry.texture(1, unbindable.createView("render target"))));
      assertEquals(GpuException.Kind.VALIDATION, usage.kind());
      GpuException kind = assertThrows(GpuException.class, () -> device.createBindGroup(
          "buffer for a texture", layout, BindGroup.Entry.buffer(0, buffer),
          BindGroup.Entry.texture(1, readOnly.createView("plain"))));
      assertEquals(GpuException.Kind.VALIDATION, kind.kind());
      device.createBindGroup("textures", layout, BindGroup.Entry.texture(0, view),
          BindGroup.Entry.texture(1, readOnly.createView("plain"))).close();
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static int pattern(int x, int y) {
    return (x * 7 + y * 13 + 1) & 0xFF;
  }

  /** Draws the shader over a target of WIDTH by HEIGHT and reads it back. */
  private static byte[] draw(Device device, TextureView layers, TextureView plain) {
    ShaderModule module = device.createShaderModule("texels", WGSL);
    BindGroupLayout layout = device.createBindGroupLayout("textures",
        BindGroupLayout.Entry.textureArray(0, ShaderStage.FRAGMENT),
        BindGroupLayout.Entry.texture(1, ShaderStage.FRAGMENT));
    BindGroup group = device.createBindGroup("textures", layout,
        BindGroup.Entry.texture(0, layers), BindGroup.Entry.texture(1, plain));
    RenderPipeline pipeline = device.createRenderPipeline(new RenderPipeline.Descriptor("texels",
        List.of(layout), module, "vs_main", List.of(), "fs_main",
        List.of(new RenderPipeline.ColorTarget(TextureFormat.RGBA8_UNORM, null)),
        RenderPipeline.Topology.TRIANGLE_STRIP));
    Texture target = device.createTexture("target", WIDTH, HEIGHT, TextureFormat.RGBA8_UNORM,
        TextureUsage.RENDER_ATTACHMENT, TextureUsage.COPY_SRC);
    Buffer readback = device.createBuffer("readback", (long) BYTES_PER_ROW * HEIGHT,
        BufferUsage.MAP_READ, BufferUsage.COPY_DST);
    try (CommandEncoder encoder = device.createCommandEncoder("texels")) {
      try (RenderPass pass = encoder.beginRenderPass("texels", target.createView("target"),
          Color.BLACK)) {
        pass.setPipeline(pipeline);
        pass.setBindGroup(0, group);
        pass.draw(4, 1, 0, 0);
      }
      encoder.copyTextureToBuffer(target, readback, 0, BYTES_PER_ROW);
      device.queue().submit(encoder.finish());
    }
    return readback.read(0, BYTES_PER_ROW * HEIGHT);
  }

  private static int red(byte[] pixels, int x, int y) {
    return pixels[y * BYTES_PER_ROW + x * 4] & 0xFF;
  }
}
