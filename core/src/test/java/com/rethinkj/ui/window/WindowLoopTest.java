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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
      Desktop.run(loop.get(), "the switch is on and rests");

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
      Desktop.key(window, true, 0x09, 0, false); // Tab focuses the field
      Desktop.key(window, false, 0x09, 0, false);
      Desktop.key(window, true, ' ', 0, false); // Space presses it
      Desktop.key(window, false, ' ', 0, false);
      Desktop.text(window, "ü€");
      Desktop.run(loop.get(), () -> "the press and the text, " + log + " arrived");

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
      Desktop.runUntil(window, app, () -> app.tree().fill() == hover && list(app).scrollY() > 50,
          "the list is hovered and scrolled");

      assertEquals(1.25f * WindowLoop.WHEEL_STEP, list(app).scrollY());
      assertEquals(Cursor.TEXT, window.cursor());
      assertEquals(Cursor.TEXT, Window.cursorShown(), "SDL shows it");

      Desktop.wheel(window, 0, 0.5f, 120, 100); // half a step away from the user: back up
      Desktop.runUntil(window, app, () -> list(app).scrollY() < 60, "the list scrolls back");
      assertEquals(0.75f * WindowLoop.WHEEL_STEP, list(app).scrollY());

      Desktop.windowEvent(window, Sdl.EVENT_WINDOW_MOUSE_LEAVE, 0, 0);
      Desktop.runUntil(window, app, () -> app.tree().fill() != hover, "the hover ends");
      assertNull(app.hovered());
      assertEquals(Cursor.DEFAULT, Window.cursorShown());
    }
  }

  private static El list(App<?> app) {
    return app.tree().children().getFirst();
  }

  /**
   * After its first frame the loop waits for events alone, also after input that changes nothing,
   * and nothing is drawn meanwhile; another thread stops it there.
   */
  @Test
  void anIdleLoopDrawsNothingAndStopsFromAnotherThread() throws InterruptedException {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
      AtomicInteger presents = new AtomicInteger(); // but those of redraws the desktop asked for
      Desktop.Redraws redraws = new Desktop.Redraws();
      int[] idle = new int[1];
      int[] seen = {-1}; // the frames presented when the loop went idle after the move
      CountDownLatch waiting = new CountDownLatch(1);
      WindowLoop loop = new WindowLoop(window, app, (scene, w, h, s) -> {
        if (!redraws.asked()) {
          presents.incrementAndGet();
        }
      }).listener(redraws).waits((millis, holdBack) -> {
            if (millis == -1 && ++idle[0] == 1) {
              Desktop.motion(window, 10, 10); // beside the switch: nothing changes
            } else if (millis == -1 && idle[0] == 2) {
              seen[0] = presents.get();
              waiting.countDown();
            }
          });
      Thread stopper = Thread.ofPlatform().start(() -> {
        try {
          if (waiting.await(Desktop.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            loop.stop();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      Desktop.run(loop, "the loop waits for events alone, twice");
      stopper.join();

      assertEquals(1, seen[0], "the first frame, and none for the move");
      assertEquals(1, presents.get(), "nothing after it");
      assertFalse(loop.isRunning());
      assertTrue(app.systemFonts(), "a window falls back to the system's fonts");
    }
  }

  /**
   * A presenter that shows nothing and never waits, as where the display holds nothing back, and
   * input that never stops: each frame drawn brings three more wheel steps, which scroll down and
   * up so that each frame has something new. The loop starts to build a frame no sooner than a
   * refresh after the last, so forty frames take at least 37 refreshes. A busy machine makes the
   * frames later, never earlier.
   */
  @Test
  void inputFasterThanTheDisplayIsDrawnOncePerRefresh() {
    try (Window window = Window.open(TITLE, 240, 200)) {
      Desktop.settle();
      App<Object> app = new App<>(new Object(), s -> Examples.list());
      List<Long> times = new ArrayList<>();
      int[] steps = new int[1];
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
        times.add(System.nanoTime());
        for (int i = 0; i < 3; i++) {
          Desktop.wheel(window, 0, steps[0]++ % 2 == 0 ? -0.1f : 0.1f, 120, 100);
        }
        if (times.size() == 40) {
          loop.get().stop();
        }
      }));
      Desktop.run(loop.get(), () -> "forty frames, " + times.size() + " drawn");

      float hertz = window.refreshRate();
      double refresh = 1e9 / (hertz >= 20 && hertz <= 1000 ? hertz : 60);
      // From the third frame on, when the first wheel steps are under way. The present of a frame
      // comes after its building began, by less than a refresh unless the machine stalls.
      double refreshes = (times.getLast() - times.get(2)) / refresh;
      assertTrue(times.size() - 3 <= refreshes + 2,
          times.size() - 3 + " frames after the third in " + refreshes + " refreshes of "
              + refresh / 1e6 + " ms");
    }
  }

  @Test
  void inputWaitsForTheRefreshAfterTheLastFrameAndInputAfterAPauseNot() {
    long refresh = 16_000_000;
    assertEquals(10_000_000, WindowLoop.holdBack(6_000_000, refresh, true));
    assertEquals(0, WindowLoop.holdBack(16_000_000, refresh, true), "a refresh has passed");
    assertEquals(0, WindowLoop.holdBack(500_000_000, refresh, true), "after a pause");
    assertEquals(0, WindowLoop.holdBack(6_000_000, refresh, false), "no input: nothing waits");
  }

  @Test
  void aFrameThePresenterCouldNotShowIsOfferedAgainUntilItIsShown() {
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
      int[] idle = {-1}; // the frames presented when the loop first waited for events alone
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(window, app, presenter).waits((millis, holdBack) -> {
        if (millis == -1) {
          idle[0] = presents.get();
          loop.get().stop();
        }
      }));
      Desktop.run(loop.get(), () -> "the loop waits for events alone, after " + presents
          + " frames");

      assertEquals(4, idle[0], "offered three more times, then nothing changes");
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
      Desktop.run(loop.systemFonts(false), "the close part ends the loop");
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
   * A task posted on another thread ends the wait of a loop that waits for events alone, and a
   * burst of tasks that arrives while the loop is busy with a frame is drawn in one frame. The loop
   * presents nothing while no task comes, and waits for events alone again after the burst.
   *
   * <p>The desktop may wake the loop too, with a change of the system's focus when another window
   * opens: a task that ran after such an event proves nothing, and the test posts another.
   */
  @Test
  void aPostWakesTheWaitingLoopAndABurstIsOneFrame() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      int[] count = new int[1];
      App<int[]> app = new App<>(count, c -> el().wFull().hFull().fill(0xFF000000 | c[0]));
      List<Integer> presented = new ArrayList<>(); // the count each frame shows
      int[] idle = {-1}; // the frames presented when the loop first waited for events alone
      int[] events = new int[1]; // events delivered since the last post
      int[] posts = new int[2]; // posted, run
      long[] at = new long[2]; // posted, the task ran
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
        if (!presented.isEmpty() && presented.getLast() == count[0]) {
          return; // nothing new: the desktop asked for it, with a redraw or a resize
        }
        presented.add(count[0]);
        if (count[0] == 1) { // the task's frame: the burst comes before it is done
          other(() -> {
            for (int i = 0; i < 1000; i++) {
              app.post(() -> count[0]++);
            }
          });
        }
      }).listener(event -> events[0]++).waits((millis, holdBack) -> {
        if (millis != -1) {
          return;
        }
        if (idle[0] == -1) {
          idle[0] = presented.size();
        }
        if (count[0] == 0 && posts[0] == posts[1]) { // none waits: post one
          posts[0]++;
          events[0] = 0;
          Thread.ofPlatform().start(() -> { // posts while the loop waits, or just before
            at[0] = System.nanoTime();
            app.post(() -> {
              posts[1]++;
              if (events[0] == 0) { // the post woke the loop, nothing else did
                at[1] = System.nanoTime();
                count[0] = 1;
              }
            });
          });
        } else if (count[0] == 1001) {
          loop.get().stop();
        }
      }));
      Desktop.run(loop.get(), () -> "the task and the burst are drawn and the loop waits; "
          + (count[0] == 0 ? posts[1] + " of " + posts[0] + " posts ran, after other events"
              : "the frames show " + presented));

      assertEquals(1, idle[0], "the first frame, then nothing while no task comes");
      assertEquals(List.of(0, 1, 1001), presented,
          "one frame for the task and one for the burst, then nothing");
      System.out.printf(Locale.ROOT, "WindowLoopTest: a post to a waiting loop ran after %.2f ms,"
          + " %d posts%n", (at[1] - at[0]) / 1e6, posts[0]);
    }
  }

  /**
   * Input right after a frame waits for the refresh, and a task posted meanwhile ends that wait
   * early but goes into the frame of that refresh: not into one of its own before it, not later.
   * Then nothing more is drawn.
   *
   * <p>The task is posted from the loop's own thread, just before the wait, which ends the wait
   * as a post from another thread does ({@link #aPostWakesTheWaitingLoopAndABurstIsOneFrame}), but
   * at a known time. A machine so busy that less than 5 ms of the refresh is left by then cannot
   * show the difference, and the test goes round again with another key; so it does when the
   * frame took a whole refresh, after which the key is due at once. Without the hold-back, every
   * round is such a round, and the test fails when its time is up.
   */
  @Test
  void aTaskPostedWhileInputWaitsForTheRefreshIsDrawnInThatRefresh() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      int[] state = new int[2]; // set by the tasks, and the keys that reached the app
      App<int[]> app = new App<>(state, c -> el().wFull().hFull()
          .fill(0xFF000000 | (c[0] & 0xFF) << 8 | c[1])
          .onKey(key -> ++c[1] > 0));
      List<String> log = new ArrayList<>(); // frames as task,keys; the waits that hold back
      long[] at = new long[2]; // the wait that held the key back, the frame after the post
      long[] millis = new long[1]; // how long that wait was to take
      int[] round = new int[1];
      boolean[] sent = new boolean[3]; // the key of this round, a wait that held it, the post
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      Desktop.Redraws redraws = new Desktop.Redraws();
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
        log.add((redraws.asked() ? "redraw " : "") + state[0] + "," + state[1]);
        if (sent[2]) {
          at[1] = System.nanoTime();
        } else if (round[0] > 0 && state[0] == round[0] && !sent[0]) { // input after its frame
          sent[0] = true;
          log.add("key");
          Desktop.key(window, true, 'k', 0, false);
          Desktop.key(window, false, 'k', 0, false);
        }
      }).listener(redraws).waits((wait, holdBack) -> {
        boolean spoiled = sent[0] && log.subList(log.lastIndexOf("key"), log.size()).stream()
            .anyMatch(e -> e.startsWith("redraw")); // the desktop asked for a frame meanwhile
        if (holdBack && sent[0]) {
          log.add("hold " + wait);
          sent[1] = true;
          if (!sent[2] && wait >= 5) {
            sent[2] = true;
            millis[0] = wait;
            at[0] = System.nanoTime();
            app.post(() -> state[0] = -1);
            log.add("post");
          }
        } else if (wait != -1) {
          return;
        } else if (sent[2] && !spoiled) {
          if (!log.getLast().equals("-1," + round[0])) {
            throw new AssertionError("the task was not drawn with the key: " + log);
          }
          loop.get().stop();
        } else if (!sent[0] || state[1] == round[0]) { // idle: a new round
          round[0]++;
          sent[0] = false;
          sent[1] = false;
          sent[2] = false;
          app.post(() -> state[0] = round[0]);
        }
      }));
      Desktop.run(loop.get(), () -> "a key held back for the refresh, and a task posted"
          + " meanwhile drawn with it; " + log);

      List<String> after = log.subList(log.lastIndexOf("post") + 1, log.size());
      assertEquals(List.of("-1," + round[0]), after.stream().filter(e -> e.contains(","))
          .toList(), "one frame for the key and the task, and no other after the post: " + log);
      double waited = (at[1] - at[0]) / 1e6;
      System.out.printf(Locale.ROOT, "WindowLoopTest: a task posted as input waited %d ms for the"
          + " refresh was drawn %.2f ms later, in round %d%n", millis[0], waited, round[0]);
      // The wait was to take millis[0], rounded up from what was left of the refresh. The loop
      // either waits again for the rest, or a stall of the machine used that rest up meanwhile.
      assertTrue(after.stream().anyMatch(e -> e.startsWith("hold")) || waited >= millis[0] - 2,
          "the frame waited for the refresh: " + waited + " ms, " + log);
    }
  }

  /** Runs {@code task} on a thread of its own and waits for it. */
  private static void other(Runnable task) {
    try {
      Thread.ofPlatform().start(task).join();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  private static El titleBar(Object state) {
    return col().wFull().hFull()
        .child(el().wFull().h(30).windowPart(WindowPart.DRAG)
            .child(el().absolute(254, 0).size(46, 30).windowPart(WindowPart.CLOSE)));
  }
}
