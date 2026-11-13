package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.gpu.Device;
import com.rethinkj.ui.gpu.DeviceLoss;
import com.rethinkj.ui.gpu.GpuException;
import com.rethinkj.ui.gpu.RenderPipeline;
import com.rethinkj.ui.gpu.TextureFormat;
import com.rethinkj.ui.natives.Natives;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import com.rethinkj.ui.window.Windows;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The presenters of a thread's windows on one device: a second surface format gets a pipeline of
 * its own, an image goes to the GPU once for all windows, and a lost device ends the windows' loop,
 * after which the presenters close in any order. Real windows; skipped without
 * SDL, wgpu-native or an adapter that can present to a window.
 */
@Timeout(60)
class SharedDeviceTest {

  private static final String TITLE = "rethinkj shared device test";

  /** A picture that the windows show, the same object in each. */
  private static final Image PICTURE = Image.of(picture());

  @BeforeEach
  void needsWindows() {
    assumeTrue(Boolean.getBoolean("windows.tests"),
        "Skipped: opens real windows on the screen; run with -Dwindows.tests=true");
    try {
      Natives.lookup("sdl3");
      Natives.lookup("wgpu_native");
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: " + e.getMessage());
    }
    if (System.getProperty("os.name").equals("Linux") && System.getenv("DISPLAY") == null
        && System.getenv("WAYLAND_DISPLAY") == null) {
      assumeTrue(false, "Skipped: no display");
    }
  }

  private static Pixels picture() {
    byte[] rgba = new byte[16 * 16 * 4];
    for (int i = 0; i < 16 * 16; i++) {
      rgba[i * 4] = (byte) (i * 7);
      rgba[i * 4 + 1] = (byte) (255 - i);
      rgba[i * 4 + 2] = (byte) 0x80;
      rgba[i * 4 + 3] = (byte) 0xFF;
    }
    return new Pixels(16, 16, rgba);
  }

  private static El view(Integer count) {
    return row().key("count-" + count).wFull().hFull().fill(0xFF203040)
        .child(El.image(PICTURE).w(32).h(32));
  }

  private static GpuPresenter presenter(Window window, List<TextureFormat> formats) {
    try {
      return new GpuPresenter(window, formats);
    } catch (GpuException e) {
      if (e.kind() == GpuException.Kind.ADAPTER_UNAVAILABLE) {
        assumeTrue(false, "Skipped: no adapter can present to the window. " + e.wgpuMessage());
      }
      throw e;
    }
  }

  private static GpuPresenter presenter(Window window) {
    return presenter(window, List.of(TextureFormat.BGRA8_UNORM_SRGB,
        TextureFormat.RGBA8_UNORM_SRGB));
  }

  /** Builds the app's frame and presents it. */
  private static void draw(Window window, App<Integer> app, GpuPresenter gpu) {
    Window.pollEvents();
    app.resize(window.width(), window.height(), window.scale());
    app.frame();
    gpu.present(app.scene(), window.width(), window.height(), window.scale());
  }

  @Test
  void aSecondSurfaceFormatGetsAPipelineOfItsOwnAndAnImageGoesUpOnce() {
    long devices = Device.created();
    long pipelines = RenderPipeline.created();
    try (Window a = Window.open(TITLE, 120, 80); Window b = Window.open(TITLE + " B", 120, 80);
        GpuPresenter first = presenter(a)) {
      GpuPresenter second;
      try {
        second = presenter(b, List.of(TextureFormat.RGBA8_UNORM_SRGB));
      } catch (IllegalStateException e) {
        assumeTrue(false, "Skipped: the surface offers no RGBA8_UNORM_SRGB. " + e.getMessage());
        return;
      }
      try (second) {
        App<Integer> appA = new App<>(0, SharedDeviceTest::view);
        App<Integer> appB = new App<>(0, SharedDeviceTest::view);
        draw(a, appA, first);
        draw(b, appB, second);
        assertEquals(1, first.frames());
        assertEquals(1, second.frames());
        assertSame(first.device(), second.device(), "one device");
        assertEquals("RGBA8_UNORM_SRGB", second.surfaceFormat());
        assertEquals(first.surfaceFormat().equals("RGBA8_UNORM_SRGB") ? 1 : 2, first.pipelines(),
            "a pipeline per format");
        assertEquals(first.pipelines(), RenderPipeline.created() - pipelines);
        assertEquals(1, first.imageUploads(), "the picture went to the GPU for the first window");
        assertEquals(0, second.imageUploads(), "and the second window draws it from there");
      }
    }
    assertEquals(1, Device.created() - devices);
  }

  @Test
  void aLostDeviceStopsEveryWindowAndThePresentersCloseInAnyOrder() {
    long devices = Device.created();
    Device lost;
    try (Window a = Window.open(TITLE, 120, 80); Window b = Window.open(TITLE + " B", 120, 80);
        Window c = Window.open(TITLE + " C", 120, 80); GpuPresenter pa = presenter(a);
        GpuPresenter pb = presenter(b); GpuPresenter pc = presenter(c)) {
      App<Integer> appA = new App<>(0, SharedDeviceTest::view);
      App<Integer> appB = new App<>(0, SharedDeviceTest::view);
      App<Integer> appC = new App<>(0, SharedDeviceTest::view);
      draw(a, appA, pa);
      draw(b, appB, pb);
      draw(c, appC, pc);
      lost = pa.device();

      DeviceLoss.lose(lost, "lost for the test");
      for (Window w : List.of(b, a, c)) {
        GpuPresenter p = w == a ? pa : w == b ? pb : pc;
        GpuException e = assertThrows(GpuException.class,
            () -> draw(w, new App<>(1, SharedDeviceTest::view), p), w.title());
        assertEquals(GpuException.Kind.DEVICE_LOST, e.kind());
        assertTrue(e.getMessage().contains("lost for the test"), e.getMessage());
        assertEquals(1, p.frames(), "nothing more was presented");
      }
      pb.close(); // the middle one first, then the first; the last at the end of the block
      pa.close();
      assertFalse(lost.isClosed(), "the third presenter still holds the device");
    }
    assertTrue(lost.isClosed(), "closed with the last presenter");
    assertEquals(1, Device.created() - devices, "no other device was made meanwhile");

    try (Window d = Window.open(TITLE + " D", 120, 80); GpuPresenter pd = presenter(d)) {
      draw(d, new App<>(0, SharedDeviceTest::view), pd);
      assertEquals(1, pd.frames(), "a window opened afterwards draws");
      assertNotSame(lost, pd.device());
    }
    assertEquals(2, Device.created() - devices, "on a device of its own");
  }

  @Test
  void aLostDeviceEndsTheLoopWithItsException() {
    try (Window a = Window.open(TITLE, 120, 80); GpuPresenter pa = presenter(a)) {
      App<Integer> app = new App<>(0, SharedDeviceTest::view);
      WindowLoop loop = new WindowLoop(a, app, pa);
      Windows.Handle b = loop.open(TITLE + " B", 120, 80, Window.options(),
          new App<>(0, SharedDeviceTest::view));
      app.post(() -> DeviceLoss.lose(pa.device(), "lost in the loop"));
      Thread stop = Thread.ofPlatform().daemon().start(() -> {
        try {
          Thread.sleep(10_000);
          loop.stop();
        } catch (InterruptedException e) {
          // ended in time
        }
      });
      GpuException e = assertThrows(GpuException.class, loop::run);
      stop.interrupt();
      assertEquals(GpuException.Kind.DEVICE_LOST, e.kind());
      assertFalse(b.isOpen(), "the loop closed the window it opened, with its presenter");
    }
  }
}
