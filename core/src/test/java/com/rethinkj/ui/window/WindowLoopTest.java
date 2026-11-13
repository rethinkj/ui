package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Examples;
import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.WindowPart;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs apps in real windows for a moment; skipped like {@link WindowTest}. */
class WindowLoopTest {

  private static final String TITLE = "rethinkj window loop test";

  @BeforeEach
  void needsWindows() {
    Desktop.assumeWindows();
  }

  @Test
  void aClickOnTheSwitchTogglesItAndTheLoopDrawsUntilTheKnobRests() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
      List<Boolean> drawn = new ArrayList<>();
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(window, app, (scene, width, height, scale) -> {
        drawn.add(app.state().on);
        if (app.state().on && !app.isMoving()) {
          loop.get().stop();
        }
      }));
      // Queued now, delivered once the loop runs and has drawn the first frame.
      Desktop.click(window, 100, 60);
      loop.get().run();

      assertTrue(app.state().on);
      assertFalse(drawn.getFirst(), "the first frame shows the switch off");
      assertTrue(drawn.size() > 3, "the knob moved over several frames: " + drawn);
      assertTrue(window.isOpen(), "the loop leaves the window open");
    }
  }

  @Test
  void keysAndTextFromTheWindowReachTheApp() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      List<String> log = new ArrayList<>();
      App<List<String>> app = new App<>(log, l -> col().wFull().hFull()
          .child(el().key("field").size(50, 20).focusable()
              .onPress(() -> l.add("press"))
              .onText(l::add)));
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
        if (log.size() == 2) {
          loop.get().stop();
        }
      }));
      Thread timeout = Thread.ofPlatform().start(() -> { // ends the loop if the events get lost
        try {
          Thread.sleep(3000);
          loop.get().stop();
        } catch (InterruptedException e) {
          // the loop ended in time
        }
      });
      Desktop.key(window, true, 0x09, 0, false); // Tab focuses the field
      Desktop.key(window, false, 0x09, 0, false);
      Desktop.key(window, true, ' ', 0, false); // Space presses it
      Desktop.key(window, false, ' ', 0, false);
      Desktop.text(window, "ü€");
      loop.get().run();
      timeout.interrupt();

      assertEquals(List.of("press", "ü€"), log);
      assertEquals("field", app.focused().key());
    }
  }

  @Test
  void anIdleLoopDrawsNothingAndStopsFromAnotherThread() throws InterruptedException {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
      AtomicInteger presents = new AtomicInteger();
      WindowLoop loop = new WindowLoop(window, app, (scene, w, h, s) -> presents.incrementAndGet());
      int[] seen = new int[2];
      Thread stopper = Thread.ofPlatform().start(() -> {
        try {
          Thread.sleep(200);
          seen[0] = presents.get();
          Thread.sleep(500);
          seen[1] = presents.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        loop.stop();
      });
      Desktop.motion(window, 10, 10);
      loop.run();
      stopper.join();

      assertTrue(seen[0] >= 1, "the first frame is drawn");
      assertEquals(seen[0], seen[1], "nothing is drawn while nothing changes");
      assertFalse(loop.isRunning());
    }
  }

  @Test
  void theClosePartEndsTheLoop() {
    try (Window window = Window.open(TITLE, 300, 200, Window.options().frameless())) {
      Desktop.settle();
      App<Object> app = new App<>(new Object(), WindowLoopTest::titleBar);
      AtomicInteger presents = new AtomicInteger();
      WindowLoop loop = new WindowLoop(window, app, (scene, w, h, s) -> presents.incrementAndGet());
      Desktop.click(window, 280, 15);
      loop.run();
      assertTrue(presents.get() >= 1);
      assertTrue(window.isOpen());
    }
  }

  @Test
  void whatThePresenterThrowsComesOutOfRun() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
      IllegalStateException thrown = new IllegalStateException("from the presenter");
      WindowLoop loop = new WindowLoop(window, app, (scene, w, h, s) -> {
        throw thrown;
      });
      assertSame(thrown, assertThrows(IllegalStateException.class, loop::run));
      assertFalse(loop.isRunning());
    }
  }

  private static El titleBar(Object state) {
    return col().wFull().hFull()
        .child(el().wFull().h(30).windowPart(WindowPart.DRAG)
            .child(el().absolute(254, 0).size(46, 30).windowPart(WindowPart.CLOSE)));
  }
}
