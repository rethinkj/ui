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
import com.rethinkj.ui.text.Atlas;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a {@link Scene} on the GPU, through wgpu, with the formulas of {@link CpuRenderer}: for
 * rectangles the same signed distance, the same coverage ramp one physical pixel wide and the
 * border inside the rectangle; for glyphs the same texel for each physical pixel and the same
 * coverage correction; for both the clip's coverage multiplied in, and blending in linear light.
 * The result matches the CPU renderer's within a small tolerance per channel, not bit for bit.
 *
 * <p>The instances and the clip table go to the GPU as they are, as two read-only storage
 * buffers that the shader reads as {@code array<Instance>} and {@code array<Clip>} in the layout
 * {@link Scene} documents; one instanced draw call draws all instances in order, rectangles and
 * glyphs alike, one quad each. The colours stay linear and premultiplied: the shader returns
 * premultiplied linear colour, the pipeline blends with {@code one, one-minus-src-alpha}, and the
 * target has an sRGB format, so the GPU blends in linear light and encodes to sRGB as it stores.
 *
 * <p>The atlas pages of the scene ({@link Scene#pages}) are the layers of one {@code r8unorm}
 * texture array, page {@code i} in layer {@code i}, which the shader reads with {@code
 * textureLoad}: exact texels, no sampler, no filtering. One array rather than a texture per page
 * keeps the single draw call, since WGSL cannot pick one of several bound textures by an index
 * that varies per instance. Before each draw the renderer writes into each layer what {@link
 * com.rethinkj.ui.text.Atlas.Page#takeChanged} reports for its page, and nothing for a page that
 * did not change, so a frame without new glyphs uploads nothing; a page the renderer has not seen
 * in its layer before is written whole, once. The renderer is meant to be the only one that takes
 * the changes of an atlas's pages. When a scene has more pages than the array has layers, or pages
 * of another size, the array is replaced by one with the next power of two of layers, and every
 * page is written whole again.
 *
 * <p>Buffers and the atlas texture grow with the scenes and are kept for the next frame; once
 * their size suffices, drawing allocates no GPU memory beyond what every WebGPU frame needs (a
 * command encoder, a pass and a command buffer). Like everything of the GPU layer, a renderer
 * belongs to the thread that created it.
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

      // The layouts of Scene's Javadoc: 64 bytes per instance, 32 per clip. An instance is a
      // rounded rectangle or a glyph. The slots in which the two differ are declared as u32 and
      // bitcast to f32 where they hold floats: the bits of a float survive that, while a small
      // integer read as a float is a subnormal, which a GPU may flush to zero.
      struct Instance {
        rect: vec4f,
        // A rectangle's radius and border width, f32; a glyph's atlas and atlas_size.
        shape: vec2u,
        clip: u32,
        kind: u32,
        // A rectangle's fill; a glyph's colour.
        fill: vec4f,
        // A rectangle's border colour, f32; a glyph's page, and as f32 its light_share,
        // dark_gamma and light_gamma.
        more: vec4u,
      }

      const GLYPH: u32 = 1u;

      struct Clip {
        rect: vec4f,
        radius: f32,
      }

      @group(0) @binding(0) var<uniform> globals: Globals;
      @group(0) @binding(1) var<storage, read> instances: array<Instance>;
      @group(0) @binding(2) var<storage, read> clips: array<Clip>;
      // The atlas pages, one layer each: 8-bit coverage, read texel by texel, never filtered.
      @group(0) @binding(3) var atlas: texture_2d_array<f32>;

      struct Varyings {
        @builtin(position) position: vec4f,
        @location(0) @interpolate(flat) index: u32,
      }

      // The physical pixel of a glyph's top left texel: x * scale and y * scale, rounded half up.
      fn glyph_origin(instance: Instance) -> vec2f {
        return floor(instance.rect.xy * globals.scale + 0.5);
      }

      // A glyph's size in texels, which is its size in physical pixels.
      fn glyph_size(instance: Instance) -> vec2f {
        return vec2f(f32(instance.shape.y & 0xFFFFu), f32(instance.shape.y >> 16u));
      }

      // A quad, in physical pixels, within the instance's clip and one pixel beyond it. For a
      // rectangle, over the rectangle and one pixel larger on every side: the smoothed edge
      // reaches half a pixel beyond the shape. For a glyph, exactly over its texels.
      @vertex
      fn vs_main(@builtin(vertex_index) vertex: u32,
          @builtin(instance_index) index: u32) -> Varyings {
        let instance = instances[index];
        let clip = clips[instance.clip];
        var low: vec2f;
        var high: vec2f;
        var empty = false;
        if instance.kind == GLYPH {
          let origin = glyph_origin(instance);
          low = max(origin, clip.rect.xy * globals.scale - 1.0);
          high = min(origin + glyph_size(instance),
              (clip.rect.xy + clip.rect.zw) * globals.scale + 1.0);
        } else {
          let pad = 1.0 / globals.scale;
          low = (max(instance.rect.xy, clip.rect.xy) - pad) * globals.scale;
          high = (min(instance.rect.xy + instance.rect.zw, clip.rect.xy + clip.rect.zw) + pad)
              * globals.scale;
          empty = !(instance.rect.z > 0.0 && instance.rect.w > 0.0);
        }
        let corner = vec2f(f32(vertex & 1u), f32(vertex >> 1u));
        var pixel = mix(low, high, corner);
        // Nothing to draw: an empty rectangle, or one its clip leaves nothing of.
        if empty || !all(high > low) {
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

      // How much of the pixel centred at point, in logical pixels, the clip lets through.
      fn clip_coverage(index: u32, point: vec2f) -> f32 {
        let clip = clips[index];
        let half_size = clip.rect.zw / 2.0;
        let radius = clamp(clip.radius, 0.0, min(half_size.x, half_size.y));
        return coverage(distance(point - (clip.rect.xy + half_size), half_size, radius));
      }

      @fragment
      fn fs_main(in: Varyings) -> @location(0) vec4f {
        let instance = instances[in.index];
        // The pixel's centre, in logical pixels; the position is at the centre already.
        let point = in.position.xy / globals.scale;
        if instance.kind == GLYPH {
          // One texel to one physical pixel: the texel of this pixel, read as it is.
          let texel = vec2i(floor(in.position.xy) - glyph_origin(instance));
          if any(texel < vec2i(0)) || any(texel >= vec2i(glyph_size(instance))) {
            discard;
          }
          let corner = vec2i(vec2u(instance.shape.x & 0xFFFFu, instance.shape.x >> 16u));
          let a = textureLoad(atlas, corner + texel, instance.more.x, 0).r;
          if a == 0.0 {
            discard;
          }
          let dark = 1.0 - pow(1.0 - a, bitcast<f32>(instance.more.z));
          let light = pow(a, bitcast<f32>(instance.more.w));
          var glyph_cover = dark + (light - dark) * bitcast<f32>(instance.more.y);
          if instance.clip != 0u {
            glyph_cover *= clip_coverage(instance.clip, point);
          }
          if glyph_cover <= 0.0 {
            discard;
          }
          return instance.fill * glyph_cover;
        }
        let half_size = instance.rect.zw / 2.0;
        let center = instance.rect.xy + half_size;
        let radius = clamp(bitcast<f32>(instance.shape.x), 0.0, min(half_size.x, half_size.y));
        let border = max(bitcast<f32>(instance.shape.y), 0.0);
        let p = point - center;
        var cover = coverage(distance(p, half_size, radius));
        // Entry 0 covers everything; it is skipped like on the CPU.
        if instance.clip != 0u {
          cover *= clip_coverage(instance.clip, point);
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
        let edge = bitcast<vec4f>(instance.more);
        return (edge + (instance.fill - edge) * fill_share) * cover;
      }
      """;

  /** The format of the textures {@link #render} draws into. */
  static final TextureFormat TEXTURE_FORMAT = TextureFormat.RGBA8_UNORM_SRGB;

  /** The atlas texture's format: a texel is a page's byte, read as a float from 0 to 1. */
  private static final TextureFormat ATLAS_FORMAT = TextureFormat.R8_UNORM;

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
  /**
   * The atlas pages, one layer each, in a texture array as large as a page: before the first page
   * one layer of 1 by 1, since the shader needs a texture bound. Replaced, larger, when a scene
   * has more pages than layers, or pages of another size.
   */
  private Texture atlas;
  private TextureView atlasView;
  /**
   * The page whose texels each layer holds, by index; null where that is not known, so that the
   * layer is written whole the next time.
   */
  private Atlas.Page[] resident;
  /** Writes into the atlas texture, and the texels they carried, for the tests. */
  private long atlasWrites;
  private long atlasTexels;
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
        BindGroupLayout.Entry.readOnlyStorage(2, ShaderStage.VERTEX, ShaderStage.FRAGMENT),
        BindGroupLayout.Entry.textureArray(3, ShaderStage.FRAGMENT));
    globals = device.createBuffer("scene globals", GLOBALS_BYTES, BufferUsage.UNIFORM,
        BufferUsage.COPY_DST);
    instances = storage("scene instances", FIRST_INSTANCES * Scene.STRIDE);
    clips = storage("scene clips", FIRST_CLIPS * Scene.CLIP_STRIDE);
    atlas(1, 1);
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

  /**
   * Writes the globals, the instances, the clip table and what changed in the atlas pages, growing
   * the buffers and the atlas texture as needed.
   */
  private void upload(Scene scene, int width, int height, float scale) {
    if (!(scale > 0 && Float.isFinite(scale))) {
      throw new IllegalArgumentException("The scale is " + scale + "; it must be above 0.");
    }
    MemorySegment data = scene.data();
    MemorySegment table = scene.clips();
    List<Atlas.Page> pages = scene.pages();
    boolean grow = data.byteSize() > instances.size() || table.byteSize() > clips.size();
    boolean newAtlas = !pages.isEmpty()
        && (pages.getFirst().size() != atlas.width() || pages.size() > atlas.layers());
    if (grow || newAtlas) {
      // Checked before anything is closed, so that the renderer can still draw a smaller scene.
      checkFits("instances", data.byteSize());
      checkFits("clips", table.byteSize());
      if (newAtlas) {
        // Created before the old one is closed, for the same reason.
        atlas(pages.getFirst().size(), layers(pages.size()));
      }
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
    writePages(pages);
  }

  /**
   * Writes into each page's layer what changed in the page since the renderer last took it: the
   * region of {@link Atlas.Page#takeChanged}, which is the whole page after the atlas emptied it,
   * and nothing at all for a page that did not change. A page that the layer does not hold yet is
   * written whole, once: what the layer held before belongs to another page or to nothing, and
   * what the page held before its changed region is not known.
   */
  private void writePages(List<Atlas.Page> pages) {
    for (int i = 0; i < pages.size(); i++) {
      Atlas.Page page = pages.get(i);
      Atlas.Region region = page.takeChanged();
      if (resident[i] != page) {
        region = new Atlas.Region(0, 0, page.size(), page.size());
      }
      if (region.isEmpty()) {
        continue;
      }
      resident[i] = null; // the layer is not known should the write fail
      device.queue().writeTexture(atlas, region.x(), region.y(), i, region.width(),
          region.height(), page.pixels(), (long) region.y() * page.size() + region.x(),
          page.size());
      resident[i] = page;
      atlasWrites++;
      atlasTexels += (long) region.width() * region.height();
    }
  }

  /** The layers of an atlas texture for this many pages: the next power of two, within limits. */
  private int layers(int pages) {
    int limit = device.limits().maxTextureArrayLayers();
    if (pages > limit) {
      throw new IllegalArgumentException("The scene's glyphs lie in " + pages + " atlas pages,"
          + " more than the device's limit of " + limit + " layers of a texture array.");
    }
    int layers = Integer.highestOneBit(pages);
    return Math.min(layers < pages ? layers * 2 : layers, limit);
  }

  /**
   * Replaces the atlas texture with an empty one of {@code layers} pages of {@code size} texels;
   * the bind group has to be made anew. Every layer is written whole the next time.
   */
  private void atlas(int size, int layers) {
    Texture texture = device.createTextureArray("glyph atlas", size, size, layers, ATLAS_FORMAT,
        TextureUsage.TEXTURE_BINDING, TextureUsage.COPY_DST);
    TextureView view;
    try {
      view = texture.createView("glyph atlas");
    } catch (RuntimeException | Error e) {
      texture.close();
      throw e;
    }
    if (atlas != null) {
      atlasView.close();
      atlas.close();
    }
    atlas = texture;
    atlasView = view;
    resident = new Atlas.Page[layers];
  }

  /** How many times a region of an atlas page was written to the GPU. */
  long atlasWrites() {
    return atlasWrites;
  }

  /** How many texels those writes carried. */
  long atlasTexels() {
    return atlasTexels;
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
        BindGroup.Entry.buffer(2, clips),
        BindGroup.Entry.texture(3, atlasView));
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
    atlasView.close();
    atlas.close();
    clips.close();
    instances.close();
    globals.close();
    layout.close();
    module.close();
  }
}
