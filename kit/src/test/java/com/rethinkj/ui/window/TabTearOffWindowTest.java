package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.natives.Natives;
import com.rethinkj.ui.text.Font;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A tab torn off and docked again in real windows, through one loop, with the pointer's events
 * put into SDL's queue as core's window tests do: the tab leaves the bar of the main window, opens
 * in a window of its own while the button is held, and docks in the main bar where it is
 * released, which closes its window. In the package of the window code, whose SDL bindings it
 * needs; kit's tests run on the class path. Skipped without SDL, a display or the text libraries.
 */
class TabTearOffWindowTest {

  private static final long TIMEOUT_MILLIS = 10_000;

  /** The groups of the tabs a to d; each window shows its group's tabs alone. */
  static final class Docs {
    App<Docs> app;
    final TabGroups<String> groups = new TabGroups<>(List.of("a", "b", "c", "d"), this::open);
    final List<String> log = new ArrayList<>();

    Windows.Handle open(TabGroups.Group<String> group, Window.Options options) {
      log.add("open " + group.activeTab());
      return app.windows()
          .open(group.activeTab(), 300, 160, options, new App<>(this, s -> s.view(group)));
    }

    El view(TabGroups.Group<String> group) {
      return col()
          .wFull()
          .hFull()
          .child(groups
              .tabs(group, Tab::new, s -> s == null ? null : Label.label(s))
              .grow(1));
    }
  }

  @Test
  void aTabTornOffIntoAWindowDocksBackWhereItIsReleased() {
    assumeWindows();
    try (Window a =
        Window.open("rethinkj tab tear-off test", 400, 160, Window.options().position(100, 200))) {
      Docs d = new Docs();
      d.app = new App<>(d, s -> s.view(s.groups.main()));
      WindowLoop loop = new WindowLoop(a, d.app, (scene, w, h, sc) -> {})
          .presenters(w -> (scene, wi, h, sc) -> {});
      settle();
      int[] step = new int[1];
      loop.waits((millis, holdBack) -> {
        if (millis != -1) {
          return;
        }
        switch (step[0]) {
          case 0 -> { // b, 5 pixels inside its left edge, out of the bar and down
            button(a, true, 52, 17);
            motion(a, 52, 40);
            motion(a, 52, 120);
            step[0]++;
          }
          case 1 -> {
            if (d.groups.groups().size()
                == 2) { // its window is open: over the bar, between c and d
              d.log.add("torn " + d.groups.groups().get(1).window().isOpen());
              motion(a, 100, 17);
              button(a, false, 100, 17);
              step[0]++;
            }
          }
          default -> {
            if (d.groups.groups().size() == 1) {
              loop.stop();
            }
          }
        }
      });
      run(loop, () -> "b torn off and docked: " + d.log + " " + d.groups.groups());

      assertEquals(List.of("open b", "torn true"), d.log);
      assertEquals(List.of("a", "c", "b", "d"), d.groups.main().tabs());
      assertEquals(2, d.groups.main().active());
      assertFalse(d.groups.isDragging());
    }
  }

  /**
   * Let go near the bottom right corner of the screen, the torn-off tab's window, which followed
   * the pointer beyond the edges, comes wholly into the work area, frame and all, its close
   * button within reach; also where the step that tears it off comes with the release.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aTabLetGoNearTheEdgeOfTheScreenOpensWhollyInTheWorkArea(boolean withTheRelease) {
    assumeWindows();
    try (Window a =
        Window.open("rethinkj tab tear-off test", 400, 160, Window.options().position(100, 200))) {
      // Asked with a window open: before, SDL knows no video driver.
      assumeTrue(Window.knowsPositions(), "Skipped: the compositor places the windows");
      Docs d = new Docs();
      d.app = new App<>(d, s -> s.view(s.groups.main()));
      WindowLoop loop = new WindowLoop(a, d.app, (scene, w, h, sc) -> {})
          .presenters(w -> (scene, wi, h, sc) -> {});
      settle();
      int[] from = Sdl.displayBounds(Window.displayOf(a.outer()), true);
      // 10 pixels inside the work area's bottom right corner, in the main window's pixels.
      float right = (from[0] + from[2] - 10 - a.x()) / a.unitsPerLogical();
      float bottom = (from[1] + from[3] - 10 - a.y()) / a.unitsPerLogical();
      int[] step = new int[1];
      int[][] torn = new int[2][]; // its frame, and the work area of its display
      loop.waits((millis, holdBack) -> {
        switch (step[0]) {
          case 0 -> { // b, out of the bar and down
            if (millis == -1) {
              button(a, true, 52, 17);
              motion(a, 52, 40);
              motion(a, 52, 120);
              step[0]++;
              if (withTheRelease) { // in the same batch: to the corner, let go
                motion(a, right, bottom);
                button(a, false, right, bottom);
                step[0]++;
              }
            }
          }
          case 1 -> {
            // While its window follows the pointer the loop may poll it, without an idle wait.
            if (d.groups.groups().size() == 2) { // its window is open: to the corner, let go
              motion(a, right, bottom);
              button(a, false, right, bottom);
              step[0]++;
            }
          }
          default -> {
            if (millis == -1) { // the release and its frame are over
              if (d.groups.groups().size() == 2) {
                Window b = d.groups.groups().get(1).window().window();
                Sdl.syncWindow(b.sdlWindow()); // X11 moves it later
                torn[0] = b.outer();
                // Of the display it lies mostly on, which may be one beside the first.
                torn[1] = Sdl.displayBounds(Window.displayOf(torn[0]), true);
              }
              loop.stop();
            }
          }
        }
      });
      run(loop, () -> "b torn off and let go: " + d.log + " " + d.groups.groups());

      assertEquals(2, d.groups.groups().size(), "b stays in a window of its own");
      int[] frame = torn[0];
      int[] in = torn[1];
      String where = Arrays.toString(frame) + " in " + Arrays.toString(in);
      assertTrue(frame[0] >= in[0] && frame[1] >= in[1], where);
      assertTrue(frame[0] + frame[2] <= in[0] + in[2], "its right edge: " + where);
      assertTrue(frame[1] + frame[3] <= in[1] + in[3], "its bottom edge: " + where);
    }
  }

  // Copied from core's test helper Desktop, whose test sources kit's tests do not see.

  private static void assumeWindows() {
    assumeTrue(
        Boolean.getBoolean("windows.tests"),
        "Skipped: opens real windows on the screen; run with -Dwindows.tests=true");
    try {
      Natives.lookup("sdl3");
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: SDL 3 or the text libraries cannot be loaded. " + e.getMessage());
    }
    if (System.getProperty("os.name").equals("Linux")
        && System.getenv("DISPLAY") == null
        && System.getenv("WAYLAND_DISPLAY") == null) {
      assumeTrue(false, "Skipped: no display, neither DISPLAY nor WAYLAND_DISPLAY is set");
    }
    try {
      Window.start();
    } catch (IllegalStateException e) {
      assumeTrue(false, "Skipped: " + e.getMessage());
    } finally {
      Window.stopIfUnused();
    }
  }

  private static void run(WindowLoop loop, java.util.function.Supplier<String> what) {
    AtomicBoolean late = new AtomicBoolean();
    Thread timeout = Thread.ofPlatform().daemon().start(() -> {
      try {
        Thread.sleep(TIMEOUT_MILLIS);
        late.set(true);
        loop.stop();
      } catch (InterruptedException _) {
        // the loop ended in time
      }
    });
    try {
      loop.run();
    } finally {
      timeout.interrupt();
    }
    assertFalse(late.get(), () -> "Not within " + TIMEOUT_MILLIS / 1000 + " s: " + what.get());
  }

  private static void settle() {
    for (int i = 0; i < 10; i++) {
      Window.waitEvents(10);
    }
  }

  private static void button(Window window, boolean down, float x, float y) {
    push(window, down ? Sdl.EVENT_MOUSE_BUTTON_DOWN : Sdl.EVENT_MOUSE_BUTTON_UP, e -> {
      e.set(JAVA_BYTE, Sdl.BUTTON_BUTTON, (byte) Sdl.BUTTON_LEFT);
      e.set(JAVA_BOOLEAN, Sdl.BUTTON_DOWN, down);
      e.set(JAVA_BYTE, Sdl.BUTTON_CLICKS, (byte) 1);
      e.set(JAVA_FLOAT, Sdl.BUTTON_X, x * window.unitsPerLogical());
      e.set(JAVA_FLOAT, Sdl.BUTTON_Y, y * window.unitsPerLogical());
    });
  }

  private static void motion(Window window, float x, float y) {
    push(window, Sdl.EVENT_MOUSE_MOTION, e -> {
      e.set(JAVA_FLOAT, Sdl.MOTION_X, x * window.unitsPerLogical());
      e.set(JAVA_FLOAT, Sdl.MOTION_Y, y * window.unitsPerLogical());
    });
  }

  private static void push(Window window, int type, Consumer<MemorySegment> fill) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment event = arena.allocate(Sdl.EVENT_SIZE, 8);
      event.set(JAVA_INT, Sdl.EVENT_TYPE, type);
      event.set(JAVA_INT, Sdl.EVENT_WINDOW_ID, window.id());
      fill.accept(event);
      Sdl.pushEvent(event);
    }
  }
}
