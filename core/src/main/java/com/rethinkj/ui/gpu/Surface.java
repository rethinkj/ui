package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.rethinkj.ui.gpu.Structs.SurfaceCapabilities;
import com.rethinkj.ui.gpu.Structs.SurfaceConfiguration;
import com.rethinkj.ui.gpu.Structs.SurfaceSourceWaylandSurface;
import com.rethinkj.ui.gpu.Structs.SurfaceSourceWindowsHwnd;
import com.rethinkj.ui.gpu.Structs.SurfaceSourceXlibWindow;
import com.rethinkj.ui.gpu.Structs.SurfaceTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * What a window shows: configured with a device, it hands out one {@link SurfaceFrame} at a time
 * to draw into and present.
 *
 * <p>wgpu-native ends the process when configuring fails, when a texture is requested while the
 * last one is still held, and when an unconfigured surface is asked for one. So {@link #configure}
 * checks the configuration against the surface's capabilities and the device's limits first, a
 * size of zero included, and the surface gives back its frame before it is configured again,
 * unconfigured or asked for the next one.
 */
public final class Surface extends GpuObject {

  /** {@code WGPUPresentMode}. */
  public enum PresentMode {
    /** Waits for the display's refresh; always supported. */
    FIFO(1),
    FIFO_RELAXED(2),
    IMMEDIATE(3),
    MAILBOX(4);

    final int value;

    PresentMode(int value) {
      this.value = value;
    }

    static PresentMode of(int value) {
      for (PresentMode mode : values()) {
        if (mode.value == value) {
          return mode;
        }
      }
      return null;
    }
  }

  /** {@code WGPUCompositeAlphaMode}: how the compositor treats the frame's alpha. */
  public enum AlphaMode {
    /** Lets wgpu choose among the supported modes. */
    AUTO(0),
    OPAQUE(1),
    PREMULTIPLIED(2),
    UNPREMULTIPLIED(3),
    INHERIT(4);

    final int value;

    AlphaMode(int value) {
      this.value = value;
    }

    static AlphaMode of(int value) {
      for (AlphaMode mode : values()) {
        if (mode.value == value) {
          return mode;
        }
      }
      return null;
    }
  }

  /** What the surface supports with a device's adapter. Formats not in the enum are left out. */
  public record Capabilities(
      Set<TextureUsage> usages,
      List<TextureFormat> formats,
      List<PresentMode> presentModes,
      List<AlphaMode> alphaModes) {}

  /**
   * How to configure a surface. The frames' usage is always {@link
   * TextureUsage#RENDER_ATTACHMENT}.
   *
   * @param width the window's width in pixels, not 0
   * @param height the window's height in pixels, not 0
   */
  public record Configuration(
      TextureFormat format, int width, int height, PresentMode presentMode, AlphaMode alphaMode) {
    public Configuration {
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(presentMode, "presentMode");
      Objects.requireNonNull(alphaMode, "alphaMode");
      if (width <= 0 || height <= 0) {
        // wgpu-native ends the process for a width or height of 0.
        throw new IllegalArgumentException("A surface cannot be configured to " + width + "x"
            + height + ". A minimised window has no size: skip drawing until it has one.");
      }
    }
  }

  /** {@code WGPUSurfaceGetCurrentTextureStatus}: the outcome of the last {@link #acquireFrame}. */
  public enum FrameStatus {
    OPTIMAL,
    SUBOPTIMAL,
    TIMEOUT,
    OUTDATED,
    LOST,
    OCCLUDED,
    ERROR
  }

  private Device device;
  private Configuration configuration;
  private SurfaceFrame frame;
  private FrameStatus lastStatus;

  Surface(Instance instance, String label, MemorySegment handle) {
    super(instance, instance, label, handle);
  }

  /** What the surface supports on the adapter of {@code device}. */
  public Capabilities capabilities(Device device) {
    MemorySegment surface = handle();
    if (device.instance != instance) {
      throw new IllegalArgumentException(device + " belongs to another instance than " + this);
    }
    device.usable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment caps = arena.allocate(SurfaceCapabilities.LAYOUT);
      if (Wgpu.surfaceGetCapabilities(surface, device.adapterHandle(), caps)
          != Wgpu.STATUS_SUCCESS) {
        throw new GpuException(
            GpuException.Kind.VALIDATION,
            "getting the capabilities of surface",
            label(),
            "wgpuSurfaceGetCapabilities failed: the adapter of " + device
                + " cannot present to this surface.");
      }
      try {
        return new Capabilities(
            TextureUsage.of(caps.get(JAVA_LONG, SurfaceCapabilities.USAGES)),
            values(
                caps,
                SurfaceCapabilities.FORMAT_COUNT,
                SurfaceCapabilities.FORMATS,
                TextureFormat::of),
            values(
                caps,
                SurfaceCapabilities.PRESENT_MODE_COUNT,
                SurfaceCapabilities.PRESENT_MODES,
                PresentMode::of),
            values(
                caps,
                SurfaceCapabilities.ALPHA_MODE_COUNT,
                SurfaceCapabilities.ALPHA_MODES,
                AlphaMode::of));
      } finally {
        Wgpu.surfaceCapabilitiesFreeMembers(caps);
      }
    }
  }

  /**
   * Configures the surface for {@code device}, or again with a new size. Gives back a frame that
   * is still held first.
   *
   * @throws IllegalArgumentException if the size exceeds the device's limit, or the surface does
   *     not support the format, present mode or alpha mode
   */
  public void configure(Device device, Configuration configuration) {
    MemorySegment surface = handle();
    MemorySegment deviceHandle = device.usable();
    device.checkSize(this.toString(), configuration.width(), configuration.height());
    Capabilities capabilities = capabilities(device);
    if (!capabilities.formats().contains(configuration.format())) {
      throw unsupported(device, "format " + configuration.format(), capabilities.formats());
    }
    if (!capabilities.presentModes().contains(configuration.presentMode())) {
      throw unsupported(
          device, "present mode " + configuration.presentMode(), capabilities.presentModes());
    }
    if (configuration.alphaMode() != AlphaMode.AUTO
        && !capabilities.alphaModes().contains(configuration.alphaMode())) {
      throw unsupported(
          device, "alpha mode " + configuration.alphaMode(), capabilities.alphaModes());
    }
    if (!capabilities.usages().contains(TextureUsage.RENDER_ATTACHMENT)) {
      throw unsupported(device, "usage RENDER_ATTACHMENT", capabilities.usages());
    }
    giveBackFrame("given back before configuring " + this + " again");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment config = SurfaceConfiguration.allocate(arena);
      config.set(ADDRESS, SurfaceConfiguration.DEVICE, deviceHandle);
      config.set(JAVA_INT, SurfaceConfiguration.FORMAT, configuration.format().value);
      config.set(JAVA_INT, SurfaceConfiguration.WIDTH, configuration.width());
      config.set(JAVA_INT, SurfaceConfiguration.HEIGHT, configuration.height());
      config.set(JAVA_INT, SurfaceConfiguration.ALPHA_MODE, configuration.alphaMode().value);
      config.set(JAVA_INT, SurfaceConfiguration.PRESENT_MODE, configuration.presentMode().value);
      Wgpu.surfaceConfigure(surface, config);
    }
    if (this.device != null && this.device != device) {
      this.device.unconfigured(this);
    }
    this.device = device;
    this.configuration = configuration;
    device.configured(this);
  }

  /** The configuration, or null while the surface is not configured. */
  public Configuration configuration() {
    checkOpen();
    return configuration;
  }

  /** Gives back a frame that is still held and unconfigures the surface. */
  public void unconfigure() {
    MemorySegment surface = handle();
    giveBackFrame("given back when " + this + " was unconfigured");
    if (configuration != null) {
      Wgpu.surfaceUnconfigure(surface);
      device.unconfigured(this);
      device = null;
      configuration = null;
    }
  }

  /**
   * The texture to draw the next frame into. Closes the previous frame, which must have been
   * presented or closed. If the surface reports that it is outdated or lost, as after a resize,
   * it is configured again with the same configuration and asked once more.
   *
   * @return the frame, or null if the surface has none to give this time; {@link #lastStatus}
   *     says why. Skip the frame then, and configure again with the window's new size if the
   *     status is {@link FrameStatus#OUTDATED} or {@link FrameStatus#LOST}.
   * @throws IllegalStateException if the surface is not configured or the last frame is still
   *     held
   */
  public SurfaceFrame acquireFrame() {
    MemorySegment surface = handle();
    if (configuration == null) {
      throw new IllegalStateException(this + " is not configured; configure it first.");
    }
    device.usable();
    if (frame != null) {
      if (frame.isHeld()) {
        throw new IllegalStateException(
            frame + " is still held; present or close it before" + " acquiring the next frame.");
      }
      // Released before the next texture is requested: wgpu-native would otherwise give the new
      // texture back when the old one is released.
      frame.closeAs("closed when the next frame was acquired");
      frame = null;
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment result = arena.allocate(SurfaceTexture.LAYOUT);
        Wgpu.surfaceGetCurrentTexture(surface, result);
        MemorySegment texture = result.get(ADDRESS, SurfaceTexture.TEXTURE);
        lastStatus = status(result.get(JAVA_INT, SurfaceTexture.STATUS));
        boolean success = lastStatus == FrameStatus.OPTIMAL || lastStatus == FrameStatus.SUBOPTIMAL;
        if (success && !texture.equals(MemorySegment.NULL)) {
          frame = new SurfaceFrame(
              this,
              device,
              label() + " frame",
              texture,
              configuration.width(),
              configuration.height(),
              configuration.format(),
              lastStatus == FrameStatus.SUBOPTIMAL);
          return frame;
        }
        if (!texture.equals(MemorySegment.NULL)) {
          Wgpu.textureRelease(texture);
        }
        if (lastStatus != FrameStatus.OUTDATED && lastStatus != FrameStatus.LOST) {
          return null;
        }
      }
      if (attempt == 0) {
        configure(device, configuration);
      }
    }
    return null;
  }

  /** The outcome of the last {@link #acquireFrame}, or null before the first. */
  public FrameStatus lastStatus() {
    return lastStatus;
  }

  void frameClosed(SurfaceFrame closed) {
    if (frame == closed) {
      frame = null;
    }
  }

  private void giveBackFrame(String how) {
    if (frame != null) {
      frame.closeAs(how);
      frame = null;
    }
  }

  @Override
  void beforeClose() {
    unconfigure();
  }

  @Override
  String kind() {
    return "Surface";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.surfaceRelease(handle);
  }

  private IllegalArgumentException unsupported(Device device, String what, Object supported) {
    return new IllegalArgumentException(this + " does not support " + what + " with " + device
        + "; it supports " + supported + ".");
  }

  private static FrameStatus status(int value) {
    return switch (value) {
      case Wgpu.SURFACE_TEXTURE_SUCCESS_OPTIMAL -> FrameStatus.OPTIMAL;
      case Wgpu.SURFACE_TEXTURE_SUCCESS_SUBOPTIMAL -> FrameStatus.SUBOPTIMAL;
      case Wgpu.SURFACE_TEXTURE_TIMEOUT -> FrameStatus.TIMEOUT;
      case Wgpu.SURFACE_TEXTURE_OUTDATED -> FrameStatus.OUTDATED;
      case Wgpu.SURFACE_TEXTURE_LOST -> FrameStatus.LOST;
      case Wgpu.SURFACE_TEXTURE_OCCLUDED -> FrameStatus.OCCLUDED;
      default -> FrameStatus.ERROR;
    };
  }

  /** The values of an array in the capabilities; values the enum does not know are left out. */
  private static <T> List<T> values(
      MemorySegment caps, long countOffset, long arrayOffset, IntFunction<T> of) {
    long count = caps.get(JAVA_LONG, countOffset);
    MemorySegment array = caps.get(ADDRESS, arrayOffset);
    List<T> values = new ArrayList<>();
    if (count > 0 && !array.equals(MemorySegment.NULL)) {
      for (int value : array.reinterpret(count * JAVA_INT.byteSize()).toArray(JAVA_INT)) {
        T known = of.apply(value);
        if (known != null) {
          values.add(known);
        }
      }
    }
    return List.copyOf(values);
  }

  static void requireHandle(long handle, String name) {
    if (handle == 0) {
      throw new IllegalArgumentException(name + " is 0; a surface needs the window's handle.");
    }
  }

  /** The {@code WGPUSurfaceSource*} struct for a surface descriptor's chain. */
  static MemorySegment chain(SurfaceSource source, Arena arena) {
    return switch (source) {
      case SurfaceSource.WindowsHwnd(long hinstance, long hwnd) -> {
        MemorySegment s = arena.allocate(SurfaceSourceWindowsHwnd.LAYOUT);
        s.set(JAVA_INT, Structs.CHAIN_STYPE, Wgpu.STYPE_SURFACE_SOURCE_WINDOWS_HWND);
        s.set(ADDRESS, SurfaceSourceWindowsHwnd.HINSTANCE, MemorySegment.ofAddress(hinstance));
        s.set(ADDRESS, SurfaceSourceWindowsHwnd.HWND, MemorySegment.ofAddress(hwnd));
        yield s;
      }
      case SurfaceSource.XlibWindow(long display, long window) -> {
        MemorySegment s = arena.allocate(SurfaceSourceXlibWindow.LAYOUT);
        s.set(JAVA_INT, Structs.CHAIN_STYPE, Wgpu.STYPE_SURFACE_SOURCE_XLIB_WINDOW);
        s.set(ADDRESS, SurfaceSourceXlibWindow.DISPLAY, MemorySegment.ofAddress(display));
        s.set(JAVA_LONG, SurfaceSourceXlibWindow.WINDOW, window);
        yield s;
      }
      case SurfaceSource.WaylandSurface(long display, long surface) -> {
        MemorySegment s = arena.allocate(SurfaceSourceWaylandSurface.LAYOUT);
        s.set(JAVA_INT, Structs.CHAIN_STYPE, Wgpu.STYPE_SURFACE_SOURCE_WAYLAND_SURFACE);
        s.set(ADDRESS, SurfaceSourceWaylandSurface.DISPLAY, MemorySegment.ofAddress(display));
        s.set(ADDRESS, SurfaceSourceWaylandSurface.SURFACE, MemorySegment.ofAddress(surface));
        yield s;
      }
    };
  }
}
