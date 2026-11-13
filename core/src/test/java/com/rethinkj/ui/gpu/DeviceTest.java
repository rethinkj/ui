package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The safe layer on a real adapter: requests, errors as exceptions, an off-screen draw read back,
 * and the cases where wgpu-native would end the process. Skipped without the natives module or an
 * adapter.
 */
class DeviceTest {

  private static final int WIDTH = 64;
  private static final int HEIGHT = 32;
  /** 64 RGBA8 texels a row: exactly the 256 bytes a copy row must be a multiple of. */
  private static final int BYTES_PER_ROW = 256;

  @AfterEach
  void closeInstances() {
    Gpus.closeAll();
  }

  @Test
  void requestsAnAdapterAndADeviceOnThePlatformsBackend() {
    try (Instance instance = Gpus.instance(Backend.forPlatform())) {
      Adapter adapter = Gpus.adapter(instance);
      Adapter.Info info = adapter.info();
      System.out.println("wgpu-native " + Instance.version() + ", adapter: " + info);
      assertEquals("29.0.1.1", Instance.version());
      assertEquals(Backend.forPlatform(), info.backend());
      assertTrue(
          adapter.limits().maxTextureDimension2D() >= 2048, adapter.limits().toString());

      Device device = adapter.requestDevice("renderer");
      assertTrue(device.limits().maxTextureDimension2D() >= 2048);
      assertEquals(false, device.isLost());
      assertEquals(List.of(adapter, device), instance.children());
    }
  }

  @Test
  void aWgslErrorIsAnExceptionWithLineColumnAndLabelAndTheDeviceLivesOn() {
    try (Device device = Gpus.device("renderer")) {
      String broken = Quads.WGSL
          .replace("vec4f(pixel / globals.size", "vec3f(pixel / globals.size")
          .replace(", 0.0, 1.0);", ", 0.0);");
      GpuException e =
          assertThrows(GpuException.class, () -> device.createShaderModule("broken quads", broken));

      assertEquals(GpuException.Kind.VALIDATION, e.kind());
      assertEquals("broken quads", e.label());
      assertTrue(e.wgpuMessage().contains("label = 'broken quads'"), e.wgpuMessage());
      // naga's source excerpt: label, line and column of the assignment, 23:3 here.
      int line = lineOf(broken, "out.position = vec3f");
      int column = broken.lines().toList().get(line - 1).indexOf("out.position") + 1;
      assertTrue(e.wgpuMessage().contains("broken quads:" + line + ":" + column), e.wgpuMessage());
      // Box-drawing characters arrive as they are: the text is decoded as UTF-8.
      assertTrue(e.wgpuMessage().contains("┌─"), e.wgpuMessage());
      assertTrue(
          e.getMessage()
              .startsWith("Validation error while creating shader module 'broken quads':\n"),
          e.getMessage());

      device.createShaderModule("quads", Quads.WGSL).close();
      assertEquals(List.of(), device.children());
    }
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void drawsInstancedQuadsOffScreenAndReadsThePixelsBack(Backend backend) {
    try (Instance instance = Gpus.instance(backend)) {
      Device device = Gpus.adapter(instance).requestDevice("renderer");
      Quads quads = new Quads(device, 2);
      RenderPipeline pipeline = quads.pipeline("quads", TextureFormat.RGBA8_UNORM, "vs_main");
      Texture target = device.createTexture(
          "target",
          WIDTH,
          HEIGHT,
          TextureFormat.RGBA8_UNORM,
          TextureUsage.RENDER_ATTACHMENT,
          TextureUsage.COPY_SRC);
      TextureView view = target.createView("target");
      Buffer readback = device.createBuffer(
          "readback", (long) BYTES_PER_ROW * HEIGHT, BufferUsage.MAP_READ, BufferUsage.COPY_DST);

      quads.upload(WIDTH, HEIGHT, new float[] {
        8, 4, 16, 8, 1, 0, 0, 1, // opaque red
        40, 16, 16, 12, 0, 0.5f, 0, 0.5f, // half-transparent green, premultiplied
      });
      try (CommandEncoder encoder = device.createCommandEncoder("frame")) {
        quads.draw(encoder, view, new Color(0, 0, 1, 1), pipeline, 2);
        encoder.copyTextureToBuffer(target, readback, 0, BYTES_PER_ROW);
        device.queue().submit(encoder.finish());
      }
      byte[] pixels = readback.read(0, BYTES_PER_ROW * HEIGHT);

      int blue = rgba(0, 0, 255, 255);
      int red = rgba(255, 0, 0, 255);
      assertPixel(blue, pixels, 0, 0);
      assertPixel(blue, pixels, WIDTH - 1, HEIGHT - 1);
      // The red quad covers x 8 to 23 and y 4 to 11: pixel centres inside [8, 24) x [4, 12).
      assertPixel(red, pixels, 8, 4);
      assertPixel(red, pixels, 23, 11);
      assertPixel(red, pixels, 16, 8);
      assertPixel(blue, pixels, 7, 4);
      assertPixel(blue, pixels, 24, 11);
      assertPixel(blue, pixels, 8, 12);
      // Green at half coverage over blue: (0, 0.5, 0) + (0, 0, 1) * (1 - 0.5).
      int[] blended = channels(pixels, 48, 22);
      assertEquals(0, blended[0]);
      assertEquals(128, blended[1], 1.0);
      assertEquals(128, blended[2], 1.0);
      assertEquals(255, blended[3]);
    }
  }

  @Test
  void drawsQuadsThatTheShadersReadFromAReadOnlyStorageBuffer() {
    String wgsl = """
        struct Quad {
          rect: vec4f,
          color: vec4f,
        }

        @group(0) @binding(0) var<storage, read> quads: array<Quad>;

        struct Out {
          @builtin(position) position: vec4f,
          @location(0) @interpolate(flat) index: u32,
        }

        @vertex
        fn vs_main(@builtin(vertex_index) vertex: u32, @builtin(instance_index) index: u32)
            -> Out {
          let quad = quads[index];
          let pixel = quad.rect.xy + vec2f(f32(vertex & 1u), f32(vertex >> 1u)) * quad.rect.zw;
          var out: Out;
          out.position = vec4f(pixel / vec2f(64.0, 32.0) * vec2f(2.0, -2.0) + vec2f(-1.0, 1.0),
              0.0, 1.0);
          out.index = index;
          return out;
        }

        @fragment
        fn fs_main(in: Out) -> @location(0) vec4f {
          return quads[in.index].color;
        }
        """;
    try (Device device = Gpus.device("renderer")) {
      assertTrue(
          device.limits().maxStorageBufferBindingSize() >= 128L << 20,
          device.limits().toString());
      assertTrue(device.limits().maxStorageBuffersPerShaderStage() >= 8);
      assertTrue(Long.bitCount(device.limits().minStorageBufferOffsetAlignment()) == 1);

      ShaderModule module = device.createShaderModule("storage quads", wgsl);
      BindGroupLayout layout = device.createBindGroupLayout(
          "quads",
          BindGroupLayout.Entry.readOnlyStorage(0, ShaderStage.VERTEX, ShaderStage.FRAGMENT));
      assertEquals(
          BindGroupLayout.BufferType.READ_ONLY_STORAGE, layout.entries().get(0).type());
      Buffer quads =
          device.createBuffer("quads", 2 * 32, BufferUsage.STORAGE, BufferUsage.COPY_DST);
      device.queue().writeBuffer(quads, 0, MemorySegment.ofArray(new float[] {
        8, 4, 16, 8, 1, 0, 0, 1, // red
        40, 16, 16, 12, 0, 1, 0, 1, // green
      }));
      BindGroup group = device.createBindGroup("quads", layout, BindGroup.Entry.buffer(0, quads));
      RenderPipeline pipeline = device.createRenderPipeline(new RenderPipeline.Descriptor(
          "storage quads",
          List.of(layout),
          module,
          "vs_main",
          List.of(),
          "fs_main",
          List.of(new RenderPipeline.ColorTarget(TextureFormat.RGBA8_UNORM, null)),
          RenderPipeline.Topology.TRIANGLE_STRIP));
      Texture target = device.createTexture(
          "target",
          WIDTH,
          HEIGHT,
          TextureFormat.RGBA8_UNORM,
          TextureUsage.RENDER_ATTACHMENT,
          TextureUsage.COPY_SRC);
      Buffer readback = device.createBuffer(
          "readback", (long) BYTES_PER_ROW * HEIGHT, BufferUsage.MAP_READ, BufferUsage.COPY_DST);
      try (CommandEncoder encoder = device.createCommandEncoder("frame")) {
        try (RenderPass pass =
            encoder.beginRenderPass("quads", target.createView("target"), Color.BLACK)) {
          pass.setPipeline(pipeline);
          pass.setBindGroup(0, group);
          pass.draw(4, 2, 0, 0);
        }
        encoder.copyTextureToBuffer(target, readback, 0, BYTES_PER_ROW);
        device.queue().submit(encoder.finish());
      }
      byte[] pixels = readback.read(0, BYTES_PER_ROW * HEIGHT);
      assertPixel(rgba(255, 0, 0, 255), pixels, 8, 4);
      assertPixel(rgba(255, 0, 0, 255), pixels, 23, 11);
      assertPixel(rgba(0, 255, 0, 255), pixels, 40, 16);
      assertPixel(rgba(0, 0, 0, 255), pixels, 24, 11);

      // A buffer without STORAGE cannot be bound there: an exception, and the device goes on.
      Buffer uniform = device.createBuffer("uniform", 64, BufferUsage.UNIFORM);
      GpuException e = assertThrows(
          GpuException.class,
          () -> device.createBindGroup("wrong", layout, BindGroup.Entry.buffer(0, uniform)));
      assertEquals(GpuException.Kind.VALIDATION, e.kind());
      assertEquals("wrong", e.label());
      device
          .createBindGroup("quads again", layout, BindGroup.Entry.buffer(0, quads))
          .close();
    }
  }

  @Test
  void commandsWithARecordingErrorAreNeverSubmitted() {
    // Submitting such a command buffer ends the process in wgpu-native.
    try (Device device = Gpus.device("renderer")) {
      Quads quads = new Quads(device, 1);
      RenderPipeline wrongFormat =
          quads.pipeline("rgba16f quads", TextureFormat.RGBA16_FLOAT, "vs_main");
      Texture target = device.createTexture(
          "target", WIDTH, HEIGHT, TextureFormat.RGBA8_UNORM, TextureUsage.RENDER_ATTACHMENT);
      TextureView view = target.createView("target");
      quads.upload(WIDTH, HEIGHT, new float[] {0, 0, 8, 8, 1, 1, 1, 1});

      CommandEncoder encoder = device.createCommandEncoder("broken frame");
      quads.draw(encoder, view, Color.BLACK, wrongFormat, 1);
      GpuException e = assertThrows(GpuException.class, encoder::finish);
      assertEquals(GpuException.Kind.VALIDATION, e.kind());
      assertEquals("broken frame", e.label());
      assertTrue(e.wgpuMessage().contains("Rgba16Float"), e.wgpuMessage());
      encoder.close();

      // The device goes on: a correct frame submits.
      RenderPipeline pipeline = quads.pipeline("quads", TextureFormat.RGBA8_UNORM, "vs_main");
      try (CommandEncoder next = device.createCommandEncoder("frame")) {
        quads.draw(next, view, Color.BLACK, pipeline, 1);
        CommandBuffer commands = next.finish();
        device.queue().submit(commands);
        // Submitting twice ends the process in wgpu-native.
        IllegalStateException twice =
            assertThrows(IllegalStateException.class, () -> device.queue().submit(commands));
        assertEquals(
            "Command buffer 'frame' is submitted; it cannot be used any more.", twice.getMessage());
      }
      device.waitIdle();
    }
  }

  @Test
  void aPipelineErrorNamesThePipeline() {
    try (Device device = Gpus.device("renderer")) {
      Quads quads = new Quads(device, 1);
      GpuException e = assertThrows(
          GpuException.class, () -> quads.pipeline("typo", TextureFormat.RGBA8_UNORM, "vs_mian"));
      assertEquals("typo", e.label());
      assertTrue(e.wgpuMessage().contains("vs_mian"), e.wgpuMessage());
      assertTrue(
          e.getMessage().startsWith("Validation error while creating render pipeline"),
          e.getMessage());
    }
  }

  @Test
  void anErrorOutsideAnyScopeReachesTheCallbackAndTheNextCall() {
    // Without the uncaptured-error callback wgpu-native would end the process here.
    try (Device device = Gpus.device("renderer");
        Arena arena = Arena.ofConfined()) {
      MemorySegment source = arena.allocate(Structs.ShaderSourceWgsl.LAYOUT);
      source.set(JAVA_INT, Structs.CHAIN_STYPE, Wgpu.STYPE_SHADER_SOURCE_WGSL);
      Structs.string(source, Structs.ShaderSourceWgsl.CODE, "fn broken( {", arena);
      MemorySegment descriptor = arena.allocate(Structs.ShaderModuleDescriptor.LAYOUT);
      descriptor.set(ADDRESS, Structs.ShaderModuleDescriptor.NEXT_IN_CHAIN, source);
      Structs.string(descriptor, Structs.ShaderModuleDescriptor.LABEL, "unscoped", arena);
      Wgpu.shaderModuleRelease(Wgpu.deviceCreateShaderModule(device.handle(), descriptor));

      GpuException e = assertThrows(
          GpuException.class, () -> device.createBuffer("next", 16, BufferUsage.UNIFORM));
      assertEquals(GpuException.Kind.VALIDATION, e.kind());
      assertTrue(e.wgpuMessage().contains("label = 'unscoped'"), e.wgpuMessage());
      // Reported once; the device is usable again.
      device.createBuffer("next", 16, BufferUsage.UNIFORM).close();
    }
  }

  @Test
  void anExceptionInsideACallbackReachesTheCallerAndTheJvmLivesOn() throws Throwable {
    try (Device device = Gpus.device("renderer");
        Arena arena = Arena.ofConfined()) {
      // Calls the uncaptured-error upcall as wgpu would, with a message too long for a Java
      // string: the upcall throws inside, which would end the JVM if it got out.
      MethodHandle upcall = Linker.nativeLinker()
          .downcallHandle(Callbacks.UNCAPTURED_ERROR, Wgpu.UNCAPTURED_ERROR_CALLBACK);
      MemorySegment message = arena.allocate(Structs.STRING_VIEW);
      message.set(ADDRESS, 0, arena.allocate(8));
      message.set(JAVA_LONG, 8, Long.MAX_VALUE / 2);
      upcall.invokeExact(
          MemorySegment.NULL,
          Wgpu.ERROR_TYPE_VALIDATION,
          message,
          MemorySegment.ofAddress(device.eventsId),
          MemorySegment.NULL);

      IllegalStateException e = assertThrows(
          IllegalStateException.class, () -> device.createBuffer("next", 16, BufferUsage.UNIFORM));
      assertTrue(e.getMessage().startsWith("A wgpu callback failed"), e.getMessage());
      assertInstanceOf(RuntimeException.class, e.getCause());
      device.createBuffer("next", 16, BufferUsage.UNIFORM).close();
    }
  }

  @Test
  void argumentsThatEndTheProcessInWgpuNativeAreRefusedFirst() {
    try (Device device = Gpus.device("renderer")) {
      Quads quads = new Quads(device, 1);
      Texture target = device.createTexture(
          "target", WIDTH, HEIGHT, TextureFormat.RGBA8_UNORM, TextureUsage.RENDER_ATTACHMENT);
      TextureView view = target.createView("target");

      assertThrows(
          IllegalArgumentException.class,
          () -> device.createTexture(
              "empty", 0, 16, TextureFormat.RGBA8_UNORM, TextureUsage.RENDER_ATTACHMENT));
      int tooLarge = device.limits().maxTextureDimension2D() + 1;
      assertThrows(
          IllegalArgumentException.class,
          () -> device.createTexture(
              "huge", tooLarge, 16, TextureFormat.RGBA8_UNORM, TextureUsage.RENDER_ATTACHMENT));

      try (CommandEncoder encoder = device.createCommandEncoder("frame")) {
        // One encoder at a time: its error scopes stay open until it finishes.
        assertThrows(IllegalStateException.class, () -> device.createCommandEncoder("second"));
        try (RenderPass pass = encoder.beginRenderPass("pass", view, Color.BLACK)) {
          // A vertex buffer range of size 0 ends the process.
          assertThrows(
              IllegalArgumentException.class, () -> pass.setVertexBuffer(0, quads.instances, 0, 0));
          // So would recording into the encoder while its pass is open, as an error at finish.
          assertThrows(
              IllegalStateException.class, () -> encoder.beginRenderPass("second", view, null));
        }
        // A buffer that cannot be mapped is not mapped.
        assertThrows(IllegalArgumentException.class, () -> quads.instances.read(0, 16));
        device.queue().submit(encoder.finish());
      }
      device.waitIdle();
    }
  }

  @Test
  void rangesThatOverflowOrAreEmptyAreRefused() {
    try (Device device = Gpus.device("renderer")) {
      Buffer target = device.createBuffer("target", 16, BufferUsage.COPY_DST);
      MemorySegment data = MemorySegment.ofArray(new byte[16]);
      // An offset near the largest long, whose sum with the size wraps around.
      assertThrows(
          IllegalArgumentException.class,
          () -> device.queue().writeBuffer(target, Long.MAX_VALUE - 7, data));
      Buffer vertices = device.createBuffer("vertices", 16, BufferUsage.VERTEX);
      Texture texture = device.createTexture(
          "target", WIDTH, HEIGHT, TextureFormat.RGBA8_UNORM, TextureUsage.RENDER_ATTACHMENT);
      TextureView view = texture.createView("target");
      try (CommandEncoder encoder = device.createCommandEncoder("frame")) {
        try (RenderPass pass = encoder.beginRenderPass("pass", view, Color.BLACK)) {
          assertThrows(
              IllegalArgumentException.class,
              () -> pass.setVertexBuffer(0, vertices, Long.MAX_VALUE - 7, 16));
        }
        device.queue().submit(encoder.finish());
      }
      // The rest of a buffer from its end on is empty.
      Buffer uniform = device.createBuffer("uniform", 256, BufferUsage.UNIFORM);
      BindGroupLayout layout = device.createBindGroupLayout(
          "rest", BindGroupLayout.Entry.uniform(0, ShaderStage.VERTEX));
      assertThrows(
          RuntimeException.class,
          () -> device.createBindGroup(
              "rest", layout, new BindGroup.Entry(0, uniform, 256, -1, null)));
      // No source at all is an empty module, not a NULL that wgpu-native would end the process on.
      device.createShaderModule("empty", null).close();
      device.waitIdle();
    }
  }

  @Test
  void closingReleasesChildrenBeforeParentsAndNewestFirst() {
    List<String> released = new ArrayList<>();
    Instance instance = Gpus.instance(Backend.forPlatform());
    instance.onRelease = object -> released.add(object.toString());
    Adapter adapter = Gpus.adapter(instance);
    Device device = adapter.requestDevice("renderer");
    ShaderModule module = device.createShaderModule("quads", Quads.WGSL);
    Buffer buffer = device.createBuffer("globals", 16, BufferUsage.UNIFORM);
    Texture texture = device.createTexture(
        "target", 8, 8, TextureFormat.RGBA8_UNORM, TextureUsage.RENDER_ATTACHMENT);
    texture.createView("target");
    BindGroupLayout layout = device.createBindGroupLayout(
        "globals", BindGroupLayout.Entry.uniform(0, ShaderStage.VERTEX));
    device.createBindGroup("globals", layout, BindGroup.Entry.buffer(0, buffer));
    CommandEncoder encoder = device.createCommandEncoder("frame");
    encoder.beginRenderPass("pass", texture.createView("second view"), Color.BLACK);

    module.close();
    assertEquals(List.of("Shader module 'quads'"), released);
    released.clear();

    instance.close();
    assertEquals(
        List.of(
            "Render pass 'pass'",
            "Command encoder 'frame'",
            "Bind group 'globals'",
            "Bind group layout 'globals'",
            "Texture view 'second view'",
            "Texture view 'target'",
            "Texture 'target'",
            "Buffer 'globals'",
            "Device 'renderer'",
            "Adapter '" + Backend.forPlatform() + " adapter'",
            "Instance '" + Backend.forPlatform().name().toLowerCase(Locale.ROOT) + "'"),
        released);
  }

  @Test
  void closingTwiceOrUsingAClosedObjectFailsWithAClearMessage() {
    Device device = Gpus.device("renderer");
    Buffer buffer = device.createBuffer("globals", 16, BufferUsage.UNIFORM, BufferUsage.COPY_DST);
    device.close();

    IllegalStateException twice = assertThrows(IllegalStateException.class, device::close);
    assertEquals("Device 'renderer' is already closed.", twice.getMessage());
    IllegalStateException used = assertThrows(
        IllegalStateException.class, () -> device.createBuffer("more", 16, BufferUsage.UNIFORM));
    assertEquals("Device 'renderer' is closed; it cannot be used any more.", used.getMessage());
    IllegalStateException child =
        assertThrows(IllegalStateException.class, () -> buffer.read(0, 16));
    assertEquals(
        "Buffer 'globals' is closed with its device 'renderer'; it cannot be used any" + " more.",
        child.getMessage());
    assertTrue(buffer.isClosed());

    Instance instance = device.instance;
    instance.close();
    assertThrows(IllegalStateException.class, instance::requestAdapter);
    assertThrows(IllegalStateException.class, instance::close);
  }

  @Test
  void objectsBelongToTheThreadOfTheirInstance() throws Exception {
    try (Device device = Gpus.device("renderer")) {
      CompletableFuture<Buffer> elsewhere = CompletableFuture.supplyAsync(
          () -> device.createBuffer("elsewhere", 16, BufferUsage.UNIFORM));
      ExecutionException e = assertThrows(ExecutionException.class, elsewhere::get);
      assertInstanceOf(WrongThreadException.class, e.getCause());
      assertSame(device, device.instance.children().get(1));
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static int lineOf(String text, String part) {
    List<String> lines = text.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(part)) {
        return i + 1;
      }
    }
    throw new AssertionError(part);
  }

  private static int rgba(int r, int g, int b, int a) {
    return r << 24 | g << 16 | b << 8 | a;
  }

  private static int[] channels(byte[] pixels, int x, int y) {
    int at = y * BYTES_PER_ROW + x * 4;
    return new int[] {
      pixels[at] & 0xFF, pixels[at + 1] & 0xFF, pixels[at + 2] & 0xFF, pixels[at + 3] & 0xFF
    };
  }

  private static void assertPixel(int expected, byte[] pixels, int x, int y) {
    int[] c = channels(pixels, x, y);
    assertEquals(
        String.format("%08X", expected),
        String.format("%08X", rgba(c[0], c[1], c[2], c[3])),
        "pixel " + x + "," + y);
  }
}
