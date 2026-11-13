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
import com.rethinkj.ui.text.TextLibraries;
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
    TextLibraries.assumeLoaded();
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
  void aClickAnimatesTheSwitchAndAnIdleWindowPresentsNothing() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      try (GpuPresenter gpu = presenter(window)) {
        App<Lights> app = new App<>(new Lights(), Examples::lights);
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
        // The switch, left of its label.
        Desktop.click(window, 58, 60);
        Desktop.run(loop.get(), "the switch is on and rests");

        assertTrue(app.state().on);
        long animated = gpu.frames();
        assertTrue(animated > 3, "the knob moved over several frames: " + animated);
        assertFalse(gpu.pending());
        // The page once, then at most the glyph that "Lights on" has and "Lights off" lacks; the
        // label's colour springs in the other frames, which upload nothing.
        long uploads = gpu.atlasUploads();
        assertTrue(uploads >= 1 && uploads <= 2, uploads + " uploads in " + animated + " frames");

        // A new loop draws its first frame, then nothing while nothing changes: after a move
        // beside the switch, it waits for events alone.
        long[] seen = {-1};
        int[] idle = new int[1];
        AtomicReference<WindowLoop> next = new AtomicReference<>();
        Desktop.Redraws redraws = new Desktop.Redraws(); // frames the desktop asked for
        next.set(new WindowLoop(window, app, new WindowLoop.Presenter() {
              @Override
              public void present(Scene scene, float width, float height, float scale) {
                redraws.asked();
                gpu.present(scene, width, height, scale);
              }

              @Override
              public boolean pending() {
                return gpu.pending();
              }
            })
            .listener(redraws)
            .waits((millis, holdBack) -> {
              if (millis == -1 && ++idle[0] == 1) {
                Desktop.motion(window, 10, 10);
              } else if (millis == -1 && idle[0] == 2) {
                seen[0] = gpu.frames();
                next.get().stop();
              }
            }));
        Desktop.run(next.get(), "the new loop waits for events alone, twice");
        assertEquals(
            animated + 1 + redraws.count(),
            seen[0],
            "the idle loop's first frame, and none for the move; " + redraws.count() + " redraws");
        assertEquals(uploads, gpu.atlasUploads(), "and no glyph is uploaded");
      }
    }
  }

  @Test
  void aResizedWindowGetsASurfaceOfItsNewSizeAndAMinimizedOneNone() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      try (GpuPresenter gpu = presenter(window)) {
        App<Lights> app = new App<>(new Lights(), Examples::lights);
        app.resize(window.width(), window.height(), window.scale());
        app.frame();
        gpu.present(app.scene(), window.width(), window.height(), window.scale());
        assertEquals(1, gpu.frames());
        assertEquals(1, gpu.atlasUploads(), "the label's page");

        // The label comes to rest 60 and 30 pixels further, whole pixels at 1, 1.25, 1.5 and 2, so
        // its glyphs stay as they are. On its way its spring draws it between pixels, which from
        // 1.5 on, where text lies on quarter pixels, would rasterise its glyphs anew.
        window.resize(320, 180);
        assertTrue(Desktop.pollUntil(() -> window.width() == 320), "resized");
        app.resize(window.width(), window.height(), window.scale());
        app.frame();
        app.advance(1);
        app.frame();
        gpu.present(app.scene(), window.width(), window.height(), window.scale());
        assertEquals(2, gpu.frames());
        assertEquals(1, gpu.atlasUploads(), "nothing new in the atlas");

        window.minimize();
        assertTrue(Desktop.pollUntil(window::isMinimized), "minimized");
        gpu.present(app.scene(), window.width(), window.height(), window.scale());
        assertEquals(2, gpu.frames(), "nothing is drawn into a minimized window");
        assertFalse(gpu.pending(), "and nothing waits: restoring asks for a redraw");

        assertTrue(Desktop.restore(window), "restored");
        gpu.present(app.scene(), window.width(), window.height(), window.scale());
        assertEquals(3, gpu.frames());
      }
    }
  }
}
