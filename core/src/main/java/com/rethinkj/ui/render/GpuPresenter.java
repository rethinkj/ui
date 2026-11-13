package com.rethinkj.ui.render;

import com.rethinkj.ui.gpu.Adapter;
import com.rethinkj.ui.gpu.Device;
import com.rethinkj.ui.gpu.Instance;
import com.rethinkj.ui.gpu.PowerPreference;
import com.rethinkj.ui.gpu.Surface;
import com.rethinkj.ui.gpu.SurfaceFrame;
import com.rethinkj.ui.gpu.SurfaceSource;
import com.rethinkj.ui.gpu.TextureFormat;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowHandle;
import com.rethinkj.ui.window.WindowLoop;
import java.util.List;
import java.util.Objects;

/**
 * Shows the frames of a {@link WindowLoop} in a {@link Window}, drawn by the {@link GpuRenderer}.
 * It owns a wgpu instance on the platform's backend (Direct3D 12 on Windows, Vulkan on Linux), the
 * window's surface and a device that can present to it.
 *
 * <pre>{@code
 * try (Window window = Window.open("Lights", 480, 320);
 *     GpuPresenter presenter = new GpuPresenter(window)) {
 *   WindowLoop.run(window, new App<>(state, View::lights), presenter);
 * }
 * }</pre>
 *
 * <p>The surface has an sRGB format, {@code BGRA8_UNORM_SRGB} where the system offers it, so that
 * blending happens in linear light; is opaque to the compositor where it can be; and presents in
 * step with the display ({@code FIFO}). It is configured with the window's size in physical pixels
 * and configured again when that changes, the scale included. While the window is minimized or
 * has no size, nothing is drawn: the window asks for a redraw when it is restored.
 *
 * <p>When the surface reports that it is out of date, as it may several times in a row while the
 * window is resized, the frame stays {@link #pending}: the loop offers the newest frame again a
 * refresh later, and the presenter then reads the window's size again and configures the surface
 * anew. Nothing is submitted for a frame that cannot be shown, and the loop calls the presenter
 * only when there is something new. Text is drawn from the scene's glyph atlas, of which only
 * what changed since the last frame drawn goes to the GPU ({@link #atlasUploads}); the presenter
 * is meant to be the only renderer of its app's atlas.
 *
 * <p>Like the window, the presenter belongs to the thread that opened the window. Close it before
 * the window.
 */
public final class GpuPresenter implements WindowLoop.Presenter, AutoCloseable {

  /** The formats the surface may have, the most wanted first: sRGB, to blend in linear light. */
  private static final List<TextureFormat> FORMATS =
      List.of(TextureFormat.BGRA8_UNORM_SRGB, TextureFormat.RGBA8_UNORM_SRGB);

  private final Window window;
  private final Instance instance;
  private final Surface surface;
  private final Device device;
  private final GpuRenderer renderer;
  private final TextureFormat format;
  private final Surface.AlphaMode alphaMode;
  private boolean pending;
  /** True when the surface asked to be configured again, even if the size is the same. */
  private boolean stale = true;
  private long frames;
  private boolean closed;

  /**
   * @throws UnsatisfiedLinkError if wgpu-native cannot be loaded
   * @throws com.rethinkj.ui.gpu.GpuException if there is no adapter that can present to the window
   * @throws IllegalStateException if the surface offers no sRGB format
   */
  public GpuPresenter(Window window) {
    this.window = Objects.requireNonNull(window, "window");
    WindowHandle handle = window.handle();
    instance = Instance.create();
    try {
      surface = instance.createSurface("window", source(handle));
      Adapter adapter = instance.requestAdapter(PowerPreference.HIGH_PERFORMANCE, surface);
      Adapter.Info info = adapter.info();
      device = adapter.requestDevice("window");
      adapter.close();
      Surface.Capabilities capabilities = surface.capabilities(device);
      format = FORMATS.stream().filter(capabilities.formats()::contains).findFirst()
          .orElseThrow(() -> new IllegalStateException("The window's surface offers no sRGB"
              + " format, only " + capabilities.formats() + "; the renderer blends in linear"
              + " light and needs one of " + FORMATS + "."));
      alphaMode = capabilities.alphaModes().contains(Surface.AlphaMode.OPAQUE)
          ? Surface.AlphaMode.OPAQUE : Surface.AlphaMode.AUTO;
      renderer = new GpuRenderer(device, info);
    } catch (RuntimeException | Error e) {
      instance.close();
      throw e;
    }
  }

  /** The surface's source for the window's handles. */
  private static SurfaceSource source(WindowHandle handle) {
    return switch (handle) {
      case WindowHandle.Win32(var hwnd, var hinstance) ->
          new SurfaceSource.WindowsHwnd(hinstance.address(), hwnd.address());
      case WindowHandle.Xlib(var display, long window) ->
          new SurfaceSource.XlibWindow(display.address(), window);
      case WindowHandle.Wayland(var display, var wlSurface) ->
          new SurfaceSource.WaylandSurface(display.address(), wlSurface.address());
    };
  }

  @Override
  public void present(Scene scene, float width, float height, float scale) {
    checkOpen();
    pending = false;
    int pixelWidth = window.pixelWidth();
    int pixelHeight = window.pixelHeight();
    if (pixelWidth <= 0 || pixelHeight <= 0 || window.isMinimized()) {
      return; // nothing to draw into; the window asks for a redraw when it is restored
    }
    Surface.Configuration configuration = surface.configuration();
    if (configuration == null || stale || configuration.width() != pixelWidth
        || configuration.height() != pixelHeight) {
      surface.configure(device, new Surface.Configuration(format, pixelWidth, pixelHeight,
          Surface.PresentMode.FIFO, alphaMode));
      stale = false;
    }
    SurfaceFrame frame = surface.acquireFrame();
    if (frame == null) {
      switch (surface.lastStatus()) {
        // The surface tried once more after configuring itself with the old size; the window's
        // size is read again when the loop offers the frame the next time.
        case OUTDATED, LOST -> {
          stale = true;
          pending = true;
        }
        case TIMEOUT -> pending = true;
        // Hidden: the window asks for a redraw when it shows again.
        case OCCLUDED -> {}
        default -> throw new IllegalStateException("The window's surface gave no frame to draw"
            + " into: " + surface.lastStatus());
      }
      return;
    }
    renderer.draw(scene, frame.view(), scale);
    frame.present();
    frames++;
    if (frame.isSuboptimal()) {
      stale = true;
    }
  }

  @Override
  public boolean pending() {
    return pending;
  }

  /** How many frames were drawn and presented. */
  public long frames() {
    return frames;
  }

  /**
   * How many times a region of a glyph atlas page went to the GPU: each page whole when it first
   * appears, then only what changed in it, and nothing in frames whose text needs no new glyphs.
   */
  public long atlasUploads() {
    return renderer.atlasWrites();
  }

  /** What draws, as wgpu describes the adapter. */
  public String adapter() {
    return renderer.adapter();
  }

  /** The format of the window's surface, such as {@code BGRA8_UNORM_SRGB}. */
  public String surfaceFormat() {
    return format.name();
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("The GPU presenter is closed.");
    }
  }

  /** Releases the surface, the device and the instance. Closing it again does nothing. */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      instance.close();
    }
  }
}
