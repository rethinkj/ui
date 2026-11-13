package com.rethinkj.ui.render;

import com.rethinkj.ui.Image;
import com.rethinkj.ui.gpu.Adapter;
import com.rethinkj.ui.gpu.BindGroup;
import com.rethinkj.ui.gpu.BindGroupLayout;
import com.rethinkj.ui.gpu.Device;
import com.rethinkj.ui.gpu.Instance;
import com.rethinkj.ui.gpu.PowerPreference;
import com.rethinkj.ui.gpu.RenderPipeline;
import com.rethinkj.ui.gpu.ShaderModule;
import com.rethinkj.ui.gpu.ShaderStage;
import com.rethinkj.ui.gpu.Surface;
import com.rethinkj.ui.gpu.Texture;
import com.rethinkj.ui.gpu.TextureFormat;
import com.rethinkj.ui.gpu.TextureUsage;
import com.rethinkj.ui.gpu.TextureView;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the {@link GpuRenderer}s on one device share: the wgpu instance, the device, the shader
 * module, the bind group layouts, a pipeline per target format, compiled when a target of that
 * format is first drawn into, and the images on the GPU. Making a device takes 60 to 240 ms and
 * compiling the pipeline over 300 ms on Direct3D 12, so the windows of a thread share one
 * context ({@link #forWindows}): a window that opens during a drag shows its first frame without
 * them.
 *
 * <p>The context counts its users and closes the instance, and with it everything made on it,
 * when the last lets go. It belongs to the thread that created it, as every object of the GPU
 * layer does.
 *
 * <p>A lost device is not made anew: on Direct3D 12 a window's surface cannot be configured for
 * a new device while the lost one holds its swapchain (DXGI refuses a second swapchain for the
 * window, and wgpu-native ends the process when configuring fails), and releasing a lost device
 * may end the process too. Every use of the device then throws a {@link
 * com.rethinkj.ui.gpu.GpuException} of kind {@code DEVICE_LOST}, which ends the windows' loop;
 * closing the renderers and the context afterwards releases what can be released.
 */
final class GpuContext {

  /** The context of each thread's windows, while one of them is open. */
  private static final ThreadLocal<GpuContext> WINDOWS = new ThreadLocal<>();

  /** An image's texture on the GPU, with its view, its bind group and the bytes its levels take. */
  record Resident(Texture texture, TextureView view, BindGroup group, long bytes) {
    void close() {
      group.close();
      view.close();
      texture.close();
    }
  }

  private final String wgsl;
  private final boolean windows;
  private final Instance instance;
  private int users = 1;
  private boolean closed;
  private Device device;
  private Adapter.Info info;
  private ShaderModule module;
  private BindGroupLayout layout;
  private BindGroupLayout imageLayout;
  private Resident blank;
  private final Map<TextureFormat, RenderPipeline> pipelines =
      new EnumMap<>(TextureFormat.class);
  /** The images on the GPU, the one drawn least recently first. */
  private final LinkedHashMap<Image, Resident> images = new LinkedHashMap<>(16, 0.75f, true);
  private long imageBytes;
  /** The renderers on the device, whose last scenes' images are not given up. */
  private final List<GpuRenderer> renderers = new ArrayList<>();

  private GpuContext(String wgsl, boolean windows) {
    this.wgsl = wgsl;
    this.windows = windows;
    this.instance = Instance.create();
  }

  /**
   * The context of this thread's windows: the one that is open, with one more user, or a new one
   * with an instance and no device yet.
   *
   * @throws UnsatisfiedLinkError if wgpu-native cannot be loaded
   */
  static GpuContext forWindows() {
    GpuContext context = WINDOWS.get();
    if (context != null) {
      context.users++;
      return context;
    }
    context = new GpuContext(GpuRenderer.WGSL, true);
    WINDOWS.set(context);
    return context;
  }

  /**
   * A context of its own, with a device on the fastest adapter, for a renderer that draws
   * without a window.
   */
  static GpuContext alone(String wgsl) {
    GpuContext context = new GpuContext(wgsl, false);
    try {
      context.device(null);
      return context;
    } catch (RuntimeException | Error e) {
      context.release();
      throw e;
    }
  }

  Instance instance() {
    checkOpen();
    return instance;
  }

  /**
   * {@link #device} for a window that joins the context: the device that the other windows share
   * must be able to present to its surface.
   *
   * @throws IllegalStateException if the device's adapter cannot present to {@code surface}
   */
  Device deviceFor(Surface surface) {
    Device device = device(surface);
    if (surface.capabilities(device).formats().isEmpty()) {
      throw new IllegalStateException("The window's surface cannot be presented to by "
          + info + ", which draws the other windows of this thread.");
    }
    return device;
  }

  /**
   * The device, made on the first call on an adapter that can present to {@code surface}, with
   * the shader and layouts on it.
   *
   * @param surface a surface the adapter must be able to present to, or null
   * @throws com.rethinkj.ui.gpu.GpuException if no adapter is there or wgpu refuses a device
   */
  Device device(Surface surface) {
    checkOpen();
    if (device != null) {
      return device;
    }
    Adapter adapter = instance.requestAdapter(PowerPreference.HIGH_PERFORMANCE, surface);
    Device made;
    try {
      info = adapter.info();
      made = adapter.requestDevice(windows ? "windows" : "renderer");
    } finally {
      adapter.close(); // the device keeps what it needs of its adapter
    }
    try {
      module = made.createShaderModule("scene", wgsl);
      layout = made.createBindGroupLayout("scene",
          BindGroupLayout.Entry.uniform(0, ShaderStage.VERTEX, ShaderStage.FRAGMENT),
          BindGroupLayout.Entry.readOnlyStorage(1, ShaderStage.VERTEX, ShaderStage.FRAGMENT),
          BindGroupLayout.Entry.readOnlyStorage(2, ShaderStage.VERTEX, ShaderStage.FRAGMENT),
          BindGroupLayout.Entry.textureArray(3, ShaderStage.FRAGMENT));
      imageLayout = made.createBindGroupLayout("image",
          BindGroupLayout.Entry.texture(0, ShaderStage.FRAGMENT));
      Texture texture = made.createTexture("no image", 1, 1, GpuRenderer.IMAGE_FORMAT,
          TextureUsage.TEXTURE_BINDING);
      TextureView view = texture.createView("no image");
      blank = new Resident(texture, view,
          made.createBindGroup("no image", imageLayout, BindGroup.Entry.texture(0, view)), 0);
    } catch (RuntimeException | Error e) {
      made.close();
      throw e;
    }
    device = made;
    return device;
  }

  /** What draws, as wgpu describes the adapter; null before the first device. */
  Adapter.Info info() {
    return info;
  }

  ShaderModule module() {
    return module;
  }

  BindGroupLayout layout() {
    return layout;
  }

  BindGroupLayout imageLayout() {
    return imageLayout;
  }

  /** The image bind group for where no image has been drawn yet: one texel, never read. */
  BindGroup blank() {
    return blank.group();
  }

  /** The pipeline for targets of {@code format}, compiled the first time it is asked for. */
  RenderPipeline pipeline(TextureFormat format) {
    RenderPipeline pipeline = pipelines.get(format);
    if (pipeline == null) {
      if (format != TextureFormat.RGBA8_UNORM_SRGB && format != TextureFormat.BGRA8_UNORM_SRGB) {
        throw new IllegalArgumentException("Cannot draw into a target of format " + format
            + ": the renderer blends in linear light and needs an sRGB format.");
      }
      pipeline = device.createRenderPipeline(new RenderPipeline.Descriptor("scene " + format,
          List.of(layout, imageLayout), module, "vs_main", List.of(), "fs_main",
          List.of(new RenderPipeline.ColorTarget(format, RenderPipeline.Blend.PREMULTIPLIED_ALPHA)),
          RenderPipeline.Topology.TRIANGLE_STRIP));
      pipelines.put(format, pipeline);
    }
    return pipeline;
  }

  /** How many pipelines the context holds, one per target format drawn into. */
  int pipelines() {
    return pipelines.size();
  }

  void add(GpuRenderer renderer) {
    renderers.add(renderer);
  }

  void remove(GpuRenderer renderer) {
    renderers.remove(renderer);
  }

  /**
   * Uploads the images of {@code drawn} that are not on the GPU yet, each once, after giving up as
   * many of those drawn least recently as {@code memory} needs: never one of {@code drawn}, nor
   * one that the last scene of another renderer on the device shows. Images that cannot be given
   * up stay, however much they take.
   *
   * @return how many images went to the GPU
   */
  int writeImages(List<Image> drawn, long memory, GpuRenderer by) {
    Set<Image> current = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Image> missing = new ArrayList<>();
    long needed = 0;
    for (Image image : drawn) {
      current.add(image);
      if (images.get(image) == null && !missing.contains(image)) { // get: drawn now, the newest
        missing.add(image);
        needed += residentBytes(image);
      }
    }
    Iterator<Map.Entry<Image, Resident>> oldest = images.entrySet().iterator();
    while (imageBytes + needed > memory && oldest.hasNext()) {
      Map.Entry<Image, Resident> entry = oldest.next();
      if (current.contains(entry.getKey()) || shownElsewhere(entry.getKey(), by)) {
        continue;
      }
      entry.getValue().close();
      imageBytes -= entry.getValue().bytes();
      oldest.remove();
    }
    for (Image image : missing) {
      Resident resident = upload(image);
      images.put(image, resident);
      imageBytes += resident.bytes();
    }
    return missing.size();
  }

  private boolean shownElsewhere(Image image, GpuRenderer by) {
    for (GpuRenderer renderer : renderers) {
      if (renderer != by && renderer.shows(image)) {
        return true;
      }
    }
    return false;
  }

  /** The bind group of an image that {@link #writeImages} put on the GPU. */
  BindGroup imageGroup(Image image) {
    return images.get(image).group();
  }

  /** How many images are on the GPU, and the bytes they take. */
  int imagesResident() {
    return images.size();
  }

  long imageBytes() {
    return imageBytes;
  }

  /** The first level of an image that the device's largest texture holds. */
  private int firstLevel(Image image) {
    int limit = device.limits().maxTextureDimension2D();
    int first = 0;
    while (first < image.levels() - 1
        && (image.levelWidth(first) > limit || image.levelHeight(first) > limit)) {
      first++;
    }
    return first;
  }

  /** The bytes an image takes on the GPU: its levels from the first one that fits. */
  private long residentBytes(Image image) {
    long bytes = 0;
    for (int k = firstLevel(image); k < image.levels(); k++) {
      bytes += image.level(k).length;
    }
    return bytes;
  }

  /**
   * A texture with the image's levels, from the first that fits the device's largest texture: a
   * larger image loses its largest levels, which the shader then takes from the first it has.
   */
  private Resident upload(Image image) {
    int first = firstLevel(image);
    int levels = image.levels() - first;
    Texture texture = device.createTexture("image", image.levelWidth(first),
        image.levelHeight(first), levels, GpuRenderer.IMAGE_FORMAT, TextureUsage.TEXTURE_BINDING,
        TextureUsage.COPY_DST);
    try {
      long bytes = 0;
      for (int k = 0; k < levels; k++) {
        int width = image.levelWidth(first + k);
        byte[] texels = image.level(first + k);
        device.queue().writeTexture(texture, k, 0, 0, 0, width, image.levelHeight(first + k),
            MemorySegment.ofArray(texels), 0, width * 4);
        bytes += texels.length;
      }
      TextureView view = texture.createView("image");
      try {
        BindGroup group = device.createBindGroup("image", imageLayout,
            BindGroup.Entry.texture(0, view));
        return new Resident(texture, view, group, bytes);
      } catch (RuntimeException | Error e) {
        view.close();
        throw e;
      }
    } catch (RuntimeException | Error e) {
      texture.close();
      throw e;
    }
  }

  /**
   * One user less; the last closes the instance and everything made on it, newest first. Closing
   * a closed context does nothing.
   */
  void release() {
    if (closed) {
      return;
    }
    if (--users > 0) {
      return;
    }
    closed = true;
    if (windows && WINDOWS.get() == this) {
      WINDOWS.remove();
    }
    instance.close();
  }

  boolean isClosed() {
    return closed;
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("The GPU context is closed.");
    }
  }
}
