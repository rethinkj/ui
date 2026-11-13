package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import com.rethinkj.ui.render.Scene;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One loop with several real windows: shared state, windows that handlers open and close, close
 * requests, a drag from one window that drops in another, pacing and posted tasks. Skipped like
 * {@link WindowTest}; the windows are small, and the input is put into SDL's queue.
 */
class SeveralWindowsTest {

  private static final String TITLE = "rethinkj several windows test";

  @BeforeEach
  void needsWindows() {
    Desktop.assumeWindows();
  }

  /** What both windows show: a count, and a log of what happened. */
  static final class Shared {
    int count;
    Windows.Handle opened;
    final List<String> log = new ArrayList<>();
  }

  /** A presenter that logs what it presents and when it closes. */
  static final class Recorder implements WindowLoop.Presenter, AutoCloseable {
    final String name;
    final App<?> app;
    final List<String> log;
    final Window window;
    final Desktop.Redraws redraws;

    Recorder(String name, App<?> app, Window window, List<String> log, Desktop.Redraws redraws) {
      this.name = name;
      this.app = app;
      this.window = window;
      this.log = log;
      this.redraws = redraws;
    }

    @Override
    public void present(Scene scene, float width, float height, float scale) {
      if (redraws == null || !redraws.asked()) {
        log.add(name + " " + app.tree().key());
      }
    }

    @Override
    public void close() {
      log.add(name + " closed, window " + (window.isOpen() ? "open" : "closed"));
    }
  }

  private static El counter(Shared s, String name) {
    return row().key(name + "-" + s.count).wFull().hFull().onPress(() -> s.count++);
  }

  @Test
  void aPressInOneWindowChangesTheOtherInTheSamePassAndAnIdleLoopDrawsNothing() {
    try (Window a = Window.open(TITLE, 120, 80)) {
      Shared s = new Shared();
      App<Shared> appA = new App<>(s, x -> counter(x, "a"));
      App<Shared> appB = new App<>(s, x -> counter(x, "b"));
      List<String> log = new ArrayList<>();
      Desktop.Redraws redraws = new Desktop.Redraws();
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(a, appA, new Recorder("A", appA, a, log, redraws))
          .presenters(w -> new Recorder("B", appB, w, log, redraws)).listener(redraws));
      Windows.Handle b = loop.get().open(TITLE + " B", 120, 80, Window.options(), appB);
      Desktop.settle();
      int[] idle = new int[1];
      loop.get().waits((millis, holdBack) -> {
        if (millis != -1) {
          return;
        }
        log.add("idle");
        if (s.count == 0 && ++idle[0] == 1) {
          Desktop.click(a, 60, 40);
        } else if (s.count == 1 && log.contains("B b-1") && !log.contains("moved")) {
          log.add("moved");
          Desktop.motion(b.window(), 10, 10); // changes nothing: no frame in either window
        } else if (log.contains("moved")) {
          loop.get().stop();
        }
      });
      Desktop.run(loop.get(), () -> "the press shows in both windows: " + log);

      int pass = log.indexOf("A a-1");
      assertTrue(pass > 0, log::toString);
      assertEquals(List.of("A a-1", "B b-1", "idle", "moved", "idle"),
          log.subList(pass, log.size() - 1), "B shows it in the same pass, then nothing: " + log);
      assertFalse(b.isOpen(), "the loop closed the window it opened");
      assertTrue(log.contains("B closed, window open"), "the presenter before its window");
      assertTrue(a.isOpen(), "the first window is its owner's");
    }
  }

  /** The state of an app whose first window opens a second, which closes both. */
  static final class Opener {
    App<Opener> app;
    Windows.Handle opened;
    final List<String> log = new ArrayList<>();

    El first() {
      return row().key(opened == null ? "a-0" : "a-1").wFull().hFull().onPress(() -> {
        App<Opener> other = new App<>(this, Opener::second);
        opened = app.windows().open(TITLE + " B", 100, 60, Window.options(), other);
      });
    }

    El second() {
      return row().key("b").wFull().hFull().onPress(() -> {
        log.add("closing all");
        for (Windows.Handle w : app.windows().all()) {
          w.close();
        }
      });
    }
  }

  @Test
  void handlersOpenAndCloseWindowsAndTheLoopEndsWithTheLast() {
    try (Window a = Window.open(TITLE, 120, 80)) {
      Opener s = new Opener();
      s.app = new App<>(s, Opener::first);
      List<String> log = s.log;
      WindowLoop loop = new WindowLoop(a, s.app, new Recorder("A", s.app, a, log, null))
          .presenters(w -> new WindowLoop.Presenter() {
        boolean pressed;

        @Override
        public void present(Scene scene, float width, float height, float scale) {
          log.add("B " + s.opened.app().tree().key());
          if (!pressed) {
            pressed = true;
            Desktop.click(w, 50, 30); // closes both
          }
        }
      });
      Desktop.settle();
      Desktop.click(a, 60, 40);
      Desktop.run(loop, () -> "a press opens B, a press in B closes both: " + log);

      assertTrue(log.contains("B b"), log::toString);
      assertTrue(log.contains("closing all"), log::toString);
      assertFalse(s.opened.isOpen());
      assertFalse(s.opened.window().isOpen(), "the loop closed it");
      assertFalse(a.isOpen(), "a handler's close closes the first window too");
      assertTrue(log.contains("A closed, window open"), "its presenter first: " + log);
    }
  }

  @Test
  void aCloseRequestRunsTheHandlerWhichMayVeto() {
    try (Window a = Window.open(TITLE, 120, 80)) {
      Shared s = new Shared();
      App<Shared> appA = new App<>(s, x -> counter(x, "a"));
      App<Shared> appB = new App<>(s, x -> counter(x, "b"));
      List<String> log = new ArrayList<>();
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(a, appA, (scene, w, h, sc) -> {})
          .presenters(w -> (scene, wi, h, sc) -> {}));
      Windows.Handle b = loop.get().open(TITLE + " B", 100, 60, Window.options(), appB);
      b.onCloseRequest(() -> {
        log.add("asked " + s.count);
        if (s.count++ > 0) {
          b.close(); // the second request: the dialog said yes
        }
      });
      Desktop.settle();
      int[] idle = new int[1];
      loop.get().waits((millis, holdBack) -> {
        if (millis != -1) {
          return;
        }
        idle[0]++;
        if (idle[0] == 1) {
          Desktop.windowEvent(b.window(), Sdl.EVENT_WINDOW_CLOSE_REQUESTED, 0, 0);
        } else if (log.size() == 1 && b.isOpen() && idle[0] < 100) {
          Desktop.windowEvent(b.window(), Sdl.EVENT_WINDOW_CLOSE_REQUESTED, 0, 0);
        } else if (!b.isOpen()) {
          // The first window's request has no handler, and it is the last: the loop ends.
          Desktop.windowEvent(a, Sdl.EVENT_WINDOW_CLOSE_REQUESTED, 0, 0);
        }
      });
      Desktop.run(loop.get(), () -> "B vetoes, then closes, then A ends the loop: " + log);

      assertEquals(List.of("asked 0", "asked 1"), log);
      assertFalse(b.isOpen());
      assertTrue(a.isOpen(), "the last window's request ends the loop and leaves it open");
    }
  }

  /** A chip in window A dragged onto window B, whose drop handler takes it. */
  @Test
  void aDragFromOneWindowIsOverAndDropsInAnother() {
    try (Window a = Window.open(TITLE, 160, 100, Window.options().position(100, 200))) {
      Shared s = new Shared();
      List<Drag> drags = new ArrayList<>();
      App<Shared> appA = new App<>(s, x -> row().wFull().hFull()
          .child(el().key("chip").size(40, 40).dragPayload("chip").onDrag(drags::add)));
      App<Shared> appB = new App<>(s, x -> row().wFull().hFull()
          .child(el().key("target").size(100, 60).onDrop(d -> {
            x.log.add(d.phase() + " " + d.payload() + " " + Math.round(d.x()));
            if (d.phase() == Drop.Phase.DROP) {
              x.count++;
            }
          })));
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(a, appA, (scene, w, h, sc) -> {})
          .presenters(w -> (scene, wi, h, sc) -> {}));
      Windows.Handle b = loop.get().open(TITLE + " B", 160, 100,
          Window.options().position(400, 200), appB);
      Desktop.settle();
      int[] idle = new int[1];
      float[] bx = new float[1];
      loop.get().waits((millis, holdBack) -> {
        if (millis != -1 || idle[0]++ > 0) {
          if (s.count == 1) {
            loop.get().stop();
          }
          return;
        }
        // Where B's point 30,20 lies in A's coordinates, from where the windows really are.
        bx[0] = b.x() + 30 * b.screenScale();
        float u = a.unitsPerLogical();
        float x = (b.x() - a.x()) / u + 30;
        float y = (b.y() - a.y()) / u + 20;
        Desktop.button(a, true, Sdl.BUTTON_LEFT, 20, 20, 1);
        Desktop.motion(a, 30, 20);
        Desktop.motion(a, x, y);
        Desktop.button(a, false, Sdl.BUTTON_LEFT, x, y, 1);
      });
      Desktop.run(loop.get(), () -> "the chip is dropped in B: " + s.log + ", " + drags);

      assertEquals(List.of("OVER chip 30", "DROP chip 30"), s.log);
      Drag end = drags.getLast();
      assertEquals(Drag.Phase.END, end.phase());
      assertTrue(end.outside());
      assertEquals(b, end.window());
      assertEquals(30, end.windowX(), 0.5, "at the centre of the pixel at 30");
      assertEquals(bx[0], end.screenX(), 0.5 * b.screenScale(), "and so on the screen");
      assertNotNull(drags.getFirst().window(), "over A itself at first");
      assertEquals(a, drags.getFirst().window().window());
    }
  }

  /**
   * SDL gives the pointer to a window that opens under it during a press, as one that follows the
   * pointer does: what that window reports until the release goes to the window the press began
   * in, at the same point of the screen.
   */
  @Test
  void thePressedWindowKeepsThePointerThatAnotherWindowReports() {
    try (Window a = Window.open(TITLE, 160, 100, Window.options().position(100, 200))) {
      List<Drag> drags = new ArrayList<>();
      List<String> pressesB = new ArrayList<>();
      App<Object> appA = new App<>(new Object(), x -> row().wFull().hFull()
          .child(el().key("chip").size(40, 40).onDrag(drags::add)));
      App<Object> appB = new App<>(new Object(), x -> row().wFull().hFull()
          .onPress(() -> pressesB.add("press")).onDrag(d -> pressesB.add("drag")));
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(a, appA, (scene, w, h, sc) -> {})
          .presenters(w -> (scene, wi, h, sc) -> {}));
      Windows.Handle b = loop.get().open(TITLE + " B", 160, 100,
          Window.options().position(400, 200), appB);
      Desktop.settle();
      float[] expected = new float[2];
      int[] idle = new int[1];
      loop.get().waits((millis, holdBack) -> {
        if (millis != -1) {
          return;
        }
        if (idle[0]++ == 0) {
          float u = a.unitsPerLogical();
          expected[0] = (b.x() + 30 * b.screenScale() - a.x()) / u;
          expected[1] = (b.y() + 20 * b.screenScale() - a.y()) / u;
          Desktop.button(a, true, Sdl.BUTTON_LEFT, 20, 20, 1);
          Desktop.motion(a, 30, 20);
          Desktop.motion(b.window(), 30, 20); // as SDL reports it once B lies under the pointer
          Desktop.button(b.window(), false, Sdl.BUTTON_LEFT, 30, 20, 1);
        } else if (!drags.isEmpty() && drags.getLast().phase() == Drag.Phase.END) {
          loop.get().stop();
        }
      });
      Desktop.run(loop.get(), () -> "the drag ends in A: " + drags);

      assertEquals(List.of(Drag.Phase.START, Drag.Phase.MOVE, Drag.Phase.END),
          drags.stream().map(Drag::phase).toList());
      assertEquals(expected[0] - 20, drags.getLast().dx(), 0.01);
      assertEquals(expected[1] - 20, drags.getLast().dy(), 0.01);
      assertEquals(b, drags.getLast().window());
      assertEquals(List.of(), pressesB, "B saw nothing of it");
    }
  }

  /**
   * Input to one window right after its frame waits for that window's refresh, while input to a
   * window whose last frame is older is drawn at once, before that wait. The apps have states of
   * their own, so that one's key draws nothing in the other.
   *
   * <p>A key to A after a pause is drawn at once; right after that frame, a key goes to A and one
   * to B. A busy machine can take a refresh before the loop looks, and then nothing waits: the
   * test goes round again.
   */
  @Test
  void eachWindowIsPacedByItsOwnFrames() {
    try (Window a = Window.open(TITLE, 120, 80)) {
      int[] keysA = new int[1];
      int[] keysB = new int[1];
      App<int[]> appA = new App<>(keysA, k -> el().key("a-" + k[0]).wFull().hFull()
          .onKey(key -> ++k[0] > 0));
      App<int[]> appB = new App<>(keysB, k -> el().key("b-" + k[0]).wFull().hFull()
          .onKey(key -> ++k[0] > 0));
      List<String> log = new ArrayList<>();
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      AtomicReference<Windows.Handle> b = new AtomicReference<>();
      int[] round = new int[1]; // A's count that the round's first key brings
      loop.set(new WindowLoop(a, appA, (scene, w, h, sc) -> {
        log.add("A " + keysA[0]);
        if (keysA[0] == round[0] && round[0] > 0 && !log.contains("sent " + round[0])) {
          log.add("sent " + round[0]);
          Desktop.key(a, true, 'k', 0, false);
          Desktop.key(b.get().window(), true, 'k', 0, false);
        }
      }).presenters(w -> (scene, wi, h, sc) -> log.add("B " + keysB[0])));
      b.set(loop.get().open(TITLE + " B", 100, 60, Window.options(), appB));
      Desktop.settle();
      loop.get().waits((millis, holdBack) -> {
        if (holdBack) {
          log.add("hold");
        } else if (millis == -1 && !log.getLast().startsWith("sent")) { // else the keys come
          int sent = log.lastIndexOf("sent " + round[0]);
          if (sent >= 0) {
            List<String> after = log.subList(sent, log.size());
            int drawn = after.indexOf("B " + keysB[0]);
            int hold = after.indexOf("hold");
            if (hold > 0 && drawn > 0 && drawn < hold) {
              loop.get().stop(); // B's key was drawn before the wait for A's refresh
              return;
            }
          }
          round[0] = keysA[0] + 1; // idle: a new round
          Desktop.key(a, true, 'k', 0, false);
        }
      });
      Desktop.run(loop.get(), () -> "B's input is drawn while A's waits: " + log);
    }
  }

  /**
   * A burst of tasks posted to two apps while the loop draws is one frame in each window, and one
   * post to an idle loop wakes it for both.
   */
  @Test
  void tasksPostedToTwoAppsAreOneFrameEach() {
    try (Window a = Window.open(TITLE, 120, 80)) {
      int[] counts = new int[2];
      App<Object> appA = new App<>(new Object(), c -> el().key("a-" + counts[0]).wFull().hFull());
      App<Object> appB = new App<>(new Object(), c -> el().key("b-" + counts[1]).wFull().hFull());
      List<String> log = new ArrayList<>();
      Desktop.Redraws redraws = new Desktop.Redraws();
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      loop.set(new WindowLoop(a, appA, (scene, w, h, sc) -> {
        if (!redraws.asked()) {
          log.add("A " + counts[0]);
        }
      }).listener(redraws).presenters(w -> (scene, wi, h, sc) -> {
        if (!redraws.asked()) {
          log.add("B " + counts[1]);
        }
      }));
      loop.get().open(TITLE + " B", 100, 60, Window.options(), appB);
      Desktop.settle();
      int[] idle = new int[1];
      loop.get().waits((millis, holdBack) -> {
        if (millis != -1) {
          return;
        }
        if (idle[0]++ == 0) {
          Thread poster = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 500; i++) {
              appA.post(() -> counts[0]++);
              appB.post(() -> counts[1]++);
            }
          });
          try {
            poster.join(); // all posted before the loop waits: one wake-up for the burst
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
        } else if (counts[0] == 500 && counts[1] == 500) {
          loop.get().stop();
        }
      });
      Desktop.run(loop.get(), () -> "both apps take their tasks: " + log);

      List<String> after = log.subList(log.indexOf("B 0") + 1, log.size());
      assertEquals(List.of("A 500", "B 500"), after, log::toString);
    }
  }
}
