package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Examples;
import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.render.Scene;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
  void pointerMovesTheWheelAndLeavingReachTheAppAndTheCursorFollows() {
    try (Window window = Window.open(TITLE, 240, 200)) {
      Desktop.settle();
      int hover = 0xFF202028;
      App<Object> app = new App<>(new Object(), s ->
          Examples.list().whenHovered(e -> e.fill(hover)).cursor(Cursor.TEXT));
      Desktop.motion(window, 120, 100);
      Desktop.wheel(window, 0, -1, 120, 100); // a step towards the user scrolls down
      Desktop.wheel(window, 0, -0.25f, 120, 100); // a quarter step, as from a touchpad
      runUntil(window, app, () -> app.tree().fill() == hover && list(app).scrollY() > 50);

      assertEquals(1.25f * WindowLoop.WHEEL_STEP, list(app).scrollY());
      assertEquals(Cursor.TEXT, window.cursor());
      assertEquals(Cursor.TEXT, Window.cursorShown(), "SDL shows it");

      Desktop.wheel(window, 0, 0.5f, 120, 100); // half a step away from the user: back up
      runUntil(window, app, () -> list(app).scrollY() < 60);
      assertEquals(0.75f * WindowLoop.WHEEL_STEP, list(app).scrollY());

      Desktop.windowEvent(window, Sdl.EVENT_WINDOW_MOUSE_LEAVE, 0, 0);
      runUntil(window, app, () -> app.tree().fill() != hover);
      assertNull(app.hovered());
      assertEquals(Cursor.DEFAULT, Window.cursorShown());
    }
  }

  /** Runs the loop until {@code done} after a frame was drawn, for at most three seconds. */
  private static void runUntil(Window window, App<?> app, BooleanSupplier done) {
    AtomicReference<WindowLoop> loop = new AtomicReference<>();
    loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
      if (done.getAsBoolean()) {
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
    loop.get().run();
    timeout.interrupt();
  }

  private static El list(App<?> app) {
    return app.tree().children().getFirst();
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
      assertTrue(app.systemFonts(), "a window falls back to the system's fonts");
    }
  }

  @Test
  void aFrameThePresenterCouldNotShowIsOfferedAgainUntilItIsShown() throws InterruptedException {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      App<Lights> app = new App<>(new Lights(), Examples::lights);
      AtomicInteger presents = new AtomicInteger();
      WindowLoop.Presenter presenter = new WindowLoop.Presenter() {
        @Override
        public void present(Scene scene, float w, float h, float s) {
          presents.incrementAndGet();
        }

        /** The first three frames cannot be shown, as for a surface that is out of date. */
        @Override
        public boolean pending() {
          return presents.get() < 4;
        }
      };
      WindowLoop loop = new WindowLoop(window, app, presenter);
      Thread stopper = Thread.ofPlatform().start(() -> {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        loop.stop();
      });
      loop.run();
      stopper.join();

      assertEquals(4, presents.get(), "offered three more times, then nothing changes");
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
      loop.systemFonts(false).run();
      assertTrue(presents.get() >= 1);
      assertTrue(window.isOpen());
      assertFalse(app.systemFonts(), "unless the loop is told not to");
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

  /**
   * A task posted on another thread ends the wait of an idle loop at once, and a burst of tasks
   * that arrives while the loop is busy is drawn in one frame. The loop presents nothing while the
   * thread posts nothing.
   */
  @Test
  void aPostWakesTheWaitingLoopAndABurstIsOneFrame() throws InterruptedException {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      int[] count = new int[1];
      App<int[]> app = new App<>(count, c -> el().wFull().hFull().fill(0xFF000000 | c[0]));
      List<Integer> presented = new CopyOnWriteArrayList<>(); // the count each frame shows
      CountDownLatch slow = new CountDownLatch(1);
      long[] at = new long[3]; // posted, the task ran, its frame presented
      WindowLoop loop = new WindowLoop(window, app, (scene, w, h, s) -> {
        presented.add(count[0]);
        if (count[0] == 1) { // the task's frame, slow, so that the burst arrives meanwhile
          at[2] = System.nanoTime();
          slow.countDown();
          sleep(100);
        }
      });
      int[] idle = new int[2];
      int[] burst = new int[1];
      Thread poster = Thread.ofPlatform().start(() -> {
        sleep(300);
        idle[0] = presented.size();
        sleep(300); // the loop waits, and this thread posts nothing
        idle[1] = presented.size();
        at[0] = System.nanoTime();
        app.post(() -> {
          at[1] = System.nanoTime();
          count[0] = 1;
        });
        try {
          slow.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        for (int i = 0; i < 1000; i++) {
          app.post(() -> count[0]++);
        }
        sleep(400);
        burst[0] = presented.size();
        sleep(300);
        loop.stop();
      });
      loop.run();
      poster.join();

      assertTrue(idle[0] >= 1, "the first frame is drawn");
      assertEquals(idle[0], idle[1], "nothing is drawn while no task comes");
      assertEquals(List.of(1, 1001), presented.subList(idle[1], presented.size()),
          "one frame for the task and one for the burst, then nothing");
      assertEquals(burst[0], presented.size(), "nothing after the burst's frame");
      double ran = (at[1] - at[0]) / 1e6;
      double shown = (at[2] - at[0]) / 1e6;
      System.out.printf(Locale.ROOT, "WindowLoopTest: a post ran after %.2f ms and was presented"
          + " after %.2f ms%n", ran, shown);
      assertTrue(ran < 100, "the task ran after " + ran + " ms");
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static El titleBar(Object state) {
    return col().wFull().hFull()
        .child(el().wFull().h(30).windowPart(WindowPart.DRAG)
            .child(el().absolute(254, 0).size(46, 30).windowPart(WindowPart.CLOSE)));
  }
}
