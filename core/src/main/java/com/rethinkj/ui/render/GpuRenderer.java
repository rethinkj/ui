package com.rethinkj.ui.render;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

import com.rethinkj.ui.gpu.Adapter;
import com.rethinkj.ui.gpu.BindGroup;
import com.rethinkj.ui.gpu.BindGroupLayout;
import com.rethinkj.ui.gpu.Buffer;
import com.rethinkj.ui.gpu.BufferUsage;
import com.rethinkj.ui.gpu.Color;
import com.rethinkj.ui.gpu.CommandEncoder;
import com.rethinkj.ui.gpu.Device;
import com.rethinkj.ui.gpu.Instance;
import com.rethinkj.ui.gpu.RenderPass;
import com.rethinkj.ui.gpu.RenderPipeline;
import com.rethinkj.ui.gpu.ShaderModule;
import com.rethinkj.ui.gpu.ShaderStage;
import com.rethinkj.ui.gpu.Texture;
import com.rethinkj.ui.gpu.TextureFormat;
import com.rethinkj.ui.gpu.TextureUsage;
import com.rethinkj.ui.gpu.TextureView;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a {@link Scene} on the GPU, through wgpu, with the formulas of {@link CpuRenderer}: the
 * same signed distance, the same coverage ramp one physical pixel wide, the border inside the
 * rectangle, the clip's coverage multiplied in, and blending in linear light. The result matches
 * the CPU renderer's within a small tolerance per channel, not bit for bit.
 *
 * <p>The instances and the clip table go to the GPU as they are, as two read-only storage
 * buffers that the shader reads as {@code array<Instance>} and {@code array<Clip>} in the layout
 * {@link Scene} documents; one instanced draw call draws all instances in order, one quad each.
 * The colours stay linear and premultiplied: the shader returns premultiplied linear colour, the
 * pipeline blends with {@code one, one-minus-src-alpha}, and the target has an sRGB format, so
 * the GPU blends in linear light and encodes to sRGB as it stores.
 *
 * <p>Buffers grow with the scenes and are kept for the next frame; once their size suffices,
 * drawing allocates no GPU memory beyond what every WebGPU frame needs (a command encoder, a pass
 * and a command buffer). Like everything of the GPU layer, a renderer belongs to the thread that
 * created it.
 *
 * <pre>{@code
 * try (GpuRenderer gpu = GpuRenderer.create()) {
 *   Pixels pixels = gpu.render(Scene.of(root), 400, 240, 2);
 * }
 * }</pre>
 */
public final class GpuRenderer implements AutoCloseable {

  /**
   * The shader. Every line of the fragment shader has its counterpart in {@link CpuRenderer}; keep
   * the two in step.
   */
  static final String WGSL = """
      struct Globals {
        // The target's size in physical pixels.
        size: vec2f,
        // Physical pixels per logical pixel.
        scale: f32,
        unused: f32,
      }

      // The layouts of Scene's Javadoc: 64 bytes per instance, 32 per clip.
      struct Instance {
        rect: vec4f,
        radius: f32,
        border_width: f32,
        clip: u32,
        unused: f32,
        fill: vec4f,
        border: vec4f,
      }

      struct Clip {
        rect: vec4f,
        radius: f32,
      }

      @group(0) @binding(0) var<uniform> globals: Globals;
      @group(0) @binding(1) var<storage, read> instances: array<Instance>;
      @group(0) @binding(2) var<storage, read> clips: array<Clip>;

      struct Varyings {
        @builtin(position) position: vec4f,
        @location(0) @interpolate(flat) index: u32,
      }

      // A quad over the instance's rectangle and its clip's, one physical pixel larger on every
      // side: the smoothed edge reaches half a pixel beyond the shape.
      @vertex
      fn vs_main(@builtin(vertex_index) vertex: u32,
          @builtin(instance_index) index: u32) -> Varyings {
        let instance = instances[index];
        let clip = clips[instance.clip];
        let pad = 1.0 / globals.scale;
        let low = max(instance.rect.xy, clip.rect.xy) - pad;
        let high = min(instance.rect.xy + instance.rect.zw, clip.rect.xy + clip.rect.zw) + pad;
        let corner = vec2f(f32(vertex & 1u), f32(vertex >> 1u));
        var pixel = mix(low, high, corner) * globals.scale;
        // Nothing to draw: an empty rectangle, or one its clip leaves nothing of.
        if !(instance.rect.z > 0.0 && instance.rect.w > 0.0 && all(high > low)) {
          pixel = vec2f(0.0);
        }
        var out: Varyings;
        out.position = vec4f(pixel / globals.size * vec2f(2.0, -2.0) + vec2f(-1.0, 1.0), 0.0, 1.0);
        out.index = index;
        return out;
      }

      // Signed distance to a rounded rectangle centred on the origin; negative inside.
      fn distance(p: vec2f, half_size: vec2f, radius: f32) -> f32 {
        let q = abs(p) - half_size + radius;
        return min(max(q.x, q.y), 0.0) + length(max(q, vec2f(0.0))) - radius;
      }

      // How much of a pixel the shape covers: a ramp one physical pixel wide across the edge.
      fn coverage(distance: f32) -> f32 {
        return clamp(0.5 - distance * globals.scale, 0.0, 1.0);
      }

      @fragment
      fn fs_main(in: Varyings) -> @location(0) vec4f {
        let instance = instances[in.index];
        let half_size = instance.rect.zw / 2.0;
        let center = instance.rect.xy + half_size;
        let radius = clamp(instance.radius, 0.0, min(half_size.x, half_size.y));
        let border = max(instance.border_width, 0.0);
        // The pixel's centre, in logical pixels; the position is at the centre already.
        let point = in.position.xy / globals.scale;
        let p = point - center;
        var cover = coverage(distance(p, half_size, radius));
        // Entry 0 covers everything; it is skipped like on the CPU.
        if instance.clip != 0u {
          let clip = clips[instance.clip];
          let clip_half = clip.rect.zw / 2.0;
          let clip_radius = clamp(clip.radius, 0.0, min(clip_half.x, clip_half.y));
          cover *= coverage(distance(point - (clip.rect.xy + clip_half), clip_half, clip_radius));
        }
        if cover <= 0.0 {
          discard;
        }
        // The border lies inside the rectangle; what remains for the fill may be empty.
        var fill_share = 1.0;
        if border > 0.0 {
          let inner = half_size - border;
          fill_share = 0.0;
          if inner.x > 0.0 && inner.y > 0.0 {
            fill_share = coverage(distance(p, inner, max(radius - border, 0.0)));
          }
        }
        // Premultiplied: the blend state adds the destination times one minus this alpha.
        return (instance.border + (instance.fill - instance.border) * fill_share) * cover;
      }
      """;

  /** The format of the textures {@link #render} draws into. */
  static final TextureFormat TEXTURE_FORMAT = TextureFormat.RGBA8_UNORM_SRGB;

  /** Bytes of the uniform block: size, scale and padding, four floats. */
  private static final int GLOBALS_BYTES = 4 * Float.BYTES;
  /** Room for this many instances and clips at first; both double when a scene needs more. */
  private static final long FIRST_INSTANCES = 256;
  private static final long FIRST_CLIPS = 16;
  /** {@code copyTextureToBuffer} needs rows that are a multiple of this many bytes. */
  private static final int ROW_ALIGNMENT = 256;

  /** The instance the renderer created and closes, or null if the device is someone else's. */
  private final Instance owned;
  private final Device device;
  private final Adapter.Info adapter;
  private final ShaderModule module;
  private final BindGroupLayout layout;
  private final Map<TextureFormat, RenderPipeline> pipelines = new EnumMap<>(TextureFormat.class);
  private final Buffer globals;
  private final MemorySegment globalsData = Arena.ofAuto().allocate(GLOBALS_BYTES, Float.BYTES);
  private Buffer instances;
  private Buffer clips;
  private BindGroup group;
  /** What {@link #render} draws into and reads back from; replaced when the size changes. */
  private Texture texture;
  private TextureView textureView;
  private Buffer readback;
  private boolean closed;

  /**
   * A renderer with a device of its own, on the platform's backend: Direct3D 12 on Windows, Vulkan
   * on Linux.
   *
   * @throws UnsatisfiedLinkError if wgpu-native cannot be loaded, for example because the natives
   *     module of the platform is not on the class path
   * @throws com.rethinkj.ui.gpu.GpuException if there is no adapter or wgpu refuses a device
   */
  public static GpuRenderer create() {
    return create(WGSL);
  }

  /** With another shader, for the test that checks that a broken formula is noticed. */
  static GpuRenderer create(String wgsl) {
    Instance instance = Instance.create();
    try {
      Adapter adapter = instance.requestAdapter();
      Adapter.Info info = adapter.info();
      Device device = adapter.requestDevice("renderer");
      // The device keeps what it needs of its adapter.
      adapter.close();
      return new GpuRenderer(instance, device, info, wgsl);
    } catch (RuntimeException | Error e) {
      instance.close();
      throw e;
    }
  }

  /**
   * A renderer on someone else's device, such as the one a window's surface is configured with.
   * Closing the renderer closes what it created on the device, not the device.
   */
  GpuRenderer(Device device, Adapter.Info adapter) {
    this(null, device, adapter, WGSL);
  }

  private GpuRenderer(Instance owned, Device device, Adapter.Info adapter, String wgsl) {
    this.owned = owned;
    this.device = device;
    this.adapter = adapter;
    module = device.createShaderModule("scene", wgsl);
    layout = device.createBindGroupLayout("scene",
        BindGroupLayout.Entry.uniform(0, ShaderStage.VERTEX, ShaderStage.FRAGMENT),
        BindGroupLayout.Entry.readOnlyStorage(1, ShaderStage.VERTEX, ShaderStage.FRAGMENT),
        BindGroupLayout.Entry.readOnlyStorage(2, ShaderStage.VERTEX, ShaderStage.FRAGMENT));
    globals = device.createBuffer("scene globals", GLOBALS_BYTES, BufferUsage.UNIFORM,
        BufferUsage.COPY_DST);
    instances = storage("scene instances", FIRST_INSTANCES * Scene.STRIDE);
    clips = storage("scene clips", FIRST_CLIPS * Scene.CLIP_STRIDE);
    group = bindGroup();
  }

  /** What the renderer draws with, as wgpu describes the adapter. */
  public String adapter() {
    return adapter.toString();
  }

  /**
   * Draws {@code scene} into a new image, as {@link CpuRenderer#render} does: the image starts
   * fully transparent, and comes back in sRGB with straight alpha. Where the image is translucent,
   * the colour is divided by alpha after reading back, so a pixel with little alpha has coarse
   * colour; an opaque pixel is exactly what the GPU stored.
   *
   * @param width the width of the image in physical pixels
   * @param height the height of the image in physical pixels
   * @param scale physical pixels per logical pixel
   */
  public Pixels render(Scene scene, int width, int height, float scale) {
    return straight(renderStored(scene, width, height, scale), width, height);
  }

  /** The image of {@link #renderStored}, in sRGB with straight alpha. */
  static Pixels straight(byte[] stored, int width, int height) {
    byte[] rgba = new byte[width * height * 4];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int from = y * bytesPerRow(width) + x * 4;
        int to = (y * width + x) * 4;
        int alpha = stored[from + 3] & 0xFF;
        if (alpha == 0) {
          continue; // as the CPU renderer leaves it: all zero
        }
        for (int c = 0; c < 3; c++) {
          int value = stored[from + c] & 0xFF;
          rgba[to + c] = alpha == 255 ? (byte) value
              : (byte) CpuRenderer.encode(Math.clamp(Scene.linear(value) * 255.0 / alpha, 0, 1));
        }
        rgba[to + 3] = (byte) alpha;
      }
    }
    return new Pixels(width, height, rgba);
  }

  /**
   * Draws {@code scene} into the renderer's texture and returns the bytes as the GPU stored them:
   * sRGB-encoded premultiplied colour and linear alpha, {@link #bytesPerRow} bytes a row.
   */
  byte[] renderStored(Scene scene, int width, int height, float scale) {
    checkOpen();
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Cannot render an image of " + width + "x" + height
          + " pixels.");
    }
    if (texture == null || texture.width() != width || texture.height() != height) {
      if (texture != null) {
        readback.close();
        textureView.close();
        texture.close();
      }
      texture = device.createTexture("scene image", width, height, TEXTURE_FORMAT,
          TextureUsage.RENDER_ATTACHMENT, TextureUsage.COPY_SRC);
      textureView = texture.createView("scene image");
      readback = device.createBuffer("scene readback", (long) bytesPerRow(width) * height,
          BufferUsage.MAP_READ, BufferUsage.COPY_DST);
    }
    upload(scene, width, height, scale);
    try (CommandEncoder encoder = device.createCommandEncoder("scene image")) {
      record(encoder, textureView, scene.count());
      encoder.copyTextureToBuffer(texture, readback, 0, bytesPerRow(width));
      device.queue().submit(encoder.finish());
    }
    return readback.read(0, (int) readback.size());
  }

  /** The bytes a row of an image of this width takes in {@link #renderStored}. */
  static int bytesPerRow(int width) {
    return Math.ceilDiv(width * 4, ROW_ALIGNMENT) * ROW_ALIGNMENT;
  }

  /**
   * Uploads {@code scene} and submits the commands that clear {@code target} to transparent and
   * draw the scene into it. The target's format must be an sRGB format, or blending does not
   * happen in linear light.
   *
   * @param scale physical pixels per logical pixel; the target's size is in physical pixels
   */
  void draw(Scene scene, TextureView target, float scale) {
    checkOpen();
    upload(scene, target.width(), target.height(), scale);
    try (CommandEncoder encoder = device.createCommandEncoder("scene")) {
      record(encoder, target, scene.count());
      device.queue().submit(encoder.finish());
    }
  }

  /** Writes the globals, the instances and the clip table, growing the buffers as needed. */
  private void upload(Scene scene, int width, int height, float scale) {
    if (!(scale > 0 && Float.isFinite(scale))) {
      throw new IllegalArgumentException("The scale is " + scale + "; it must be above 0.");
    }
    MemorySegment data = scene.data();
    MemorySegment table = scene.clips();
    if (data.byteSize() > instances.size() || table.byteSize() > clips.size()) {
      // Checked before anything is closed, so that the renderer can still draw a smaller scene.
      checkFits("instances", data.byteSize());
      checkFits("clips", table.byteSize());
      group.close();
      if (data.byteSize() > instances.size()) {
        instances.close();
        instances = storage("scene instances", grown(instances.size(), data.byteSize()));
      }
      if (table.byteSize() > clips.size()) {
        clips.close();
        clips = storage("scene clips", grown(clips.size(), table.byteSize()));
      }
      group = bindGroup();
    }
    globalsData.set(JAVA_FLOAT, 0, width);
    globalsData.set(JAVA_FLOAT, 4, height);
    globalsData.set(JAVA_FLOAT, 8, scale);
    device.queue().writeBuffer(globals, 0, globalsData);
    device.queue().writeBuffer(instances, 0, data);
    device.queue().writeBuffer(clips, 0, table);
  }

  /** Records the pass that clears {@code target} and draws {@code count} instances. */
  private void record(CommandEncoder encoder, TextureView target, int count) {
    RenderPipeline pipeline = pipeline(target.format());
    try (RenderPass pass = encoder.beginRenderPass("scene", target, new Color(0, 0, 0, 0))) {
      if (count > 0) {
        pass.setPipeline(pipeline);
        pass.setBindGroup(0, group);
        // One quad per instance, a triangle strip of four vertices: instances in order.
        pass.draw(4, count, 0, 0);
      }
    }
  }

  private RenderPipeline pipeline(TextureFormat format) {
    RenderPipeline pipeline = pipelines.get(format);
    if (pipeline == null) {
      if (format != TextureFormat.RGBA8_UNORM_SRGB && format != TextureFormat.BGRA8_UNORM_SRGB) {
        throw new IllegalArgumentException("Cannot draw into a target of format " + format
            + ": the renderer blends in linear light and needs an sRGB format.");
      }
      pipeline = device.createRenderPipeline(new RenderPipeline.Descriptor("scene " + format,
          List.of(layout), module, "vs_main", List.of(), "fs_main",
          List.of(new RenderPipeline.ColorTarget(format, RenderPipeline.Blend.PREMULTIPLIED_ALPHA)),
          RenderPipeline.Topology.TRIANGLE_STRIP));
      pipelines.put(format, pipeline);
    }
    return pipeline;
  }

  private void checkFits(String what, long size) {
    long limit = storageLimit();
    if (size > limit) {
      throw new IllegalArgumentException("The scene's " + what + " take " + size + " bytes, more"
          + " than the device's limit of " + limit + " bytes for a storage buffer.");
    }
  }

  private Buffer storage(String label, long size) {
    return device.createBuffer(label, size, BufferUsage.STORAGE, BufferUsage.COPY_DST);
  }

  /** Doubles {@code size} until it holds {@code needed}, or just {@code needed} if too much. */
  private long grown(long size, long needed) {
    long grown = size;
    while (grown < needed) {
      grown *= 2;
    }
    return grown <= storageLimit() ? grown : needed;
  }

  private long storageLimit() {
    return Math.min(
        device.limits().maxStorageBufferBindingSize(), device.limits().maxBufferSize());
  }

  private BindGroup bindGroup() {
    return device.createBindGroup("scene", layout,
        BindGroup.Entry.buffer(0, globals),
        BindGroup.Entry.buffer(1, instances),
        BindGroup.Entry.buffer(2, clips));
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("The GPU renderer is closed.");
    }
  }

  /**
   * Closes what the renderer created and, if it created its own device with {@link #create}, the
   * device too. Closing it again does nothing.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (owned != null) {
      owned.close();
      return;
    }
    if (texture != null) {
      readback.close();
      textureView.close();
      texture.close();
    }
    for (RenderPipeline pipeline : pipelines.values()) {
      pipeline.close();
    }
    group.close();
    clips.close();
    instances.close();
    globals.close();
    layout.close();
    module.close();
  }
}
