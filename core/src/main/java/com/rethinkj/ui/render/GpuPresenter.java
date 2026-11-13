package com.rethinkj.ui.render;

import com.rethinkj.ui.gpu.Device;
import com.rethinkj.ui.gpu.GpuException;
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
 * Shows the frames of a {@link WindowLoop} in a {@link Window}, drawn by the {@link GpuRenderer},
 * on the platform's backend (Direct3D 12 on Windows, Vulkan on Linux).
 *
 * <pre>{@code
 * try (Window window = Window.open("Lights", 480, 320);
 *     GpuPresenter presenter = new GpuPresenter(window)) {
 *   WindowLoop.run(window, new App<>(state, View::lights), presenter);
 * }
 * }</pre>
 *
 * <p>The presenters of one thread share one wgpu instance, adapter and device, with the shader,
 * a render pipeline per surface format and the images on the GPU: the first presenter makes them,
 * on an adapter that can present to its window, and they are closed with the last presenter. A
 * window that opens next to others, as one torn off during a drag, shows its first frame without
 * making a device (60 to 240 ms) or compiling the pipeline (over 300 ms on Direct3D 12). What
 * belongs to one window stays its own: the surface and its configuration, the buffers of the
 * instances and clips, and the texture of its app's glyph atlas.
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
 * <p>When the device is lost (a driver update or reset, the GPU removed), presenting throws a
 * {@link GpuException} of kind {@code DEVICE_LOST} with wgpu's reason, in every window, which ends
 * the loop; the presenters close as usual afterwards, in any order. The windows do not go on with
 * a new device: on Direct3D 12 a window's surface cannot be configured for another device while
 * the lost one holds its swapchain (DXGI refuses a second one, "window is in use", and wgpu-native
 * ends the process when configuring fails), and releasing a lost device may end the process too.
 * An app that wants to go on opens new windows, whose presenters make a new device once the old
 * ones are closed.
 *
 * <p>Like the window, the presenter belongs to the thread that opened the window. Close it before
 * the window.
 */
public final class GpuPresenter implements WindowLoop.Presenter, AutoCloseable {

  /** The formats the surface may have, the most wanted first: sRGB, to blend in linear light. */
  private static final List<TextureFormat> FORMATS =
      List.of(TextureFormat.BGRA8_UNORM_SRGB, TextureFormat.RGBA8_UNORM_SRGB);

  private final Window window;
  private final List<TextureFormat> formats;
  private final GpuContext context;
  private final Surface surface;
  private final GpuRenderer renderer;
  private TextureFormat format;
  private Surface.AlphaMode alphaMode;
  private boolean pending;
  /** True when the surface asked to be configured again, even if the size is the same. */
  private boolean stale = true;
  private long frames;
  private boolean closed;

  /**
   * @throws UnsatisfiedLinkError if wgpu-native cannot be loaded
   * @throws com.rethinkj.ui.gpu.GpuException if there is no adapter that can present to the window
   * @throws IllegalStateException if the surface offers no sRGB format, or the device that the
   *     thread's other windows share cannot present to this window
   */
  public GpuPresenter(Window window) {
    this(window, FORMATS);
  }

  /** With the surface formats to choose from, the most wanted first; for the tests. */
  GpuPresenter(Window window, List<TextureFormat> formats) {
    this.window = Objects.requireNonNull(window, "window");
    this.formats = List.copyOf(formats);
    WindowHandle handle = window.handle();
    context = GpuContext.forWindows();
    Surface made = null;
    try {
      made = context.instance().createSurface("window", source(handle));
      choose(context.deviceFor(made), made);
      renderer = new GpuRenderer(context);
    } catch (RuntimeException | Error e) {
      if (made != null && !made.isClosed()) {
        made.close();
      }
      context.release();
      throw e;
    }
    surface = made;
  }

  /** Chooses the surface's format and alpha mode for the adapter of {@code device}. */
  private void choose(Device device, Surface surface) {
    Surface.Capabilities capabilities = surface.capabilities(device);
    format = formats.stream().filter(capabilities.formats()::contains).findFirst()
        .orElseThrow(() -> new IllegalStateException("The window's surface offers no sRGB"
            + " format, only " + capabilities.formats() + "; the renderer blends in linear"
            + " light and needs one of " + formats + "."));
    alphaMode = capabilities.alphaModes().contains(Surface.AlphaMode.OPAQUE)
        ? Surface.AlphaMode.OPAQUE : Surface.AlphaMode.AUTO;
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
    Device device = context.device(surface);
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

  /**
   * How many images went to the GPU for this window: each when it is first drawn on the device,
   * which the presenters of a thread share, and again only when it is drawn after the renderer
   * gave it up to stay within the scene's {@link Scene#imageMemory}.
   */
  public long imageUploads() {
    return renderer.imageUploads();
  }

  /** What draws, as wgpu describes the adapter. */
  public String adapter() {
    return renderer.adapter();
  }

  /** The format of the window's surface, such as {@code BGRA8_UNORM_SRGB}. */
  public String surfaceFormat() {
    return format.name();
  }

  /** The device that draws now, shared with the thread's other presenters; for the tests. */
  Device device() {
    return context.device(surface);
  }

  /** How many pipelines the shared context holds, one per surface format; for the tests. */
  int pipelines() {
    return context.pipelines();
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("The GPU presenter is closed.");
    }
  }

  /**
   * Releases the window's surface and what the renderer made for it; the last presenter of the
   * thread also releases the device and the instance. Closing it again does nothing.
   */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      try {
        renderer.close();
        if (!surface.isClosed()) {
          surface.close();
        }
      } finally {
        context.release();
      }
    }
  }
}
