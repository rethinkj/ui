package com.rethinkj.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Examples;
import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.gpu.GpuException;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.render.Scene;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The GPU renderer in real windows, for a moment. Skipped like {@link WindowTest}, and without
 * wgpu-native or an adapter that can present to the window.
 */
// A click that misses its element would leave the loop waiting for ever.
@Timeout(60)
class GpuPresenterTest {

  private static final String TITLE = "rethinkj GPU presenter test";

  @BeforeEach
  void needsWindows() {
    Desktop.assumeWindows();
  }

  /** A presenter for the window, or the test is skipped with the reason. */
  private static GpuPresenter presenter(Window window) {
    try {
      return new GpuPresenter(window);
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: wgpu-native cannot be loaded. " + e.getMessage());
    } catch (GpuException e) {
      if (e.kind() == GpuException.Kind.ADAPTER_UNAVAILABLE) {
        assumeTrue(false, "Skipped: no adapter can present to the window. " + e.wgpuMessage());
      }
      throw e;
    }
    throw new AssertionError("unreachable");
  }

  @Test
  void aClickAnimatesTheSwitchAndAnIdleWindowPresentsNothing() throws InterruptedException {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      try (GpuPresenter gpu = presenter(window)) {
        App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
        AtomicReference<WindowLoop> loop = new AtomicReference<>();
        loop.set(new WindowLoop(window, app, new WindowLoop.Presenter() {
          @Override
          public void present(Scene scene, float width, float height, float scale) {
            gpu.present(scene, width, height, scale);
            if (app.state().on && !app.isMoving()) {
              loop.get().stop();
            }
          }

          @Override
          public boolean pending() {
            return gpu.pending();
          }
        }));
        Desktop.click(window, 100, 60);
        loop.get().run();

        assertTrue(app.state().on);
        long animated = gpu.frames();
        assertTrue(animated > 3, "the knob moved over several frames: " + animated);
        assertFalse(gpu.pending());

        // A new loop draws its first frame, then nothing while nothing changes.
        WindowLoop idle = new WindowLoop(window, app, gpu);
        long[] seen = new long[2];
        Thread stopper = Thread.ofPlatform().start(() -> {
          try {
            Thread.sleep(200);
            seen[0] = gpu.frames();
            Thread.sleep(500);
            seen[1] = gpu.frames();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          idle.stop();
        });
        Desktop.motion(window, 10, 10);
        idle.run();
        stopper.join();
        assertEquals(animated + 1, seen[0], "the idle loop's first frame");
        assertEquals(seen[0], seen[1], "nothing is presented while nothing changes");
      }
    }
  }

  @Test
  void aResizedWindowGetsASurfaceOfItsNewSizeAndAMinimizedOneNone() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      try (GpuPresenter gpu = presenter(window)) {
        App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
        app.resize(window.width(), window.height(), window.scale());
        app.frame();
        gpu.present(app.scene(), window.width(), window.height(), window.scale());
        assertEquals(1, gpu.frames());

        window.resize(320, 180);
        assertTrue(Desktop.pollUntil(() -> window.width() == 320), "resized");
        app.resize(window.width(), window.height(), window.scale());
        app.frame();
        gpu.present(app.scene(), window.width(), window.height(), window.scale());
        assertEquals(2, gpu.frames());

        window.minimize();
        assertTrue(Desktop.pollUntil(window::isMinimized), "minimized");
        gpu.present(app.scene(), window.width(), window.height(), window.scale());
        assertEquals(2, gpu.frames(), "nothing is drawn into a minimized window");
        assertFalse(gpu.pending(), "and nothing waits: restoring asks for a redraw");

        window.restore();
        assertTrue(Desktop.pollUntil(() -> !window.isMinimized()), "restored");
        gpu.present(app.scene(), window.width(), window.height(), window.scale());
        assertEquals(3, gpu.frames());
      }
    }
  }
}
