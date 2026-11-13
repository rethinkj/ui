package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.natives.Natives;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * For tests that open windows: skips them where that is not possible, and puts synthetic events
 * into SDL's queue, which reach the window the way real input does.
 */
final class Desktop {

  /**
   * How long a test waits for a state before it fails: far longer than anything takes on a busy
   * machine, so that only a state that never comes ends a test this way.
   */
  static final long TIMEOUT_MILLIS = 10_000;

  private Desktop() {}

  /** Skips the test unless SDL loads and can open windows here. */
  static void assumeWindows() {
    try {
      Natives.lookup("sdl3");
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: SDL 3 cannot be loaded. " + e.getMessage());
    }
    if (System.getProperty("os.name").equals("Linux") && System.getenv("DISPLAY") == null
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

  /**
   * Runs the loop until it stops, and fails with {@code what} did not happen if that takes {@link
   * #TIMEOUT_MILLIS}.
   */
  static void run(WindowLoop loop, String what) {
    run(loop, () -> what);
  }

  /** Like {@link #run(WindowLoop, String)}, with a message made when the test fails. */
  static void run(WindowLoop loop, Supplier<String> what) {
    AtomicBoolean late = new AtomicBoolean();
    Thread timeout = Thread.ofPlatform().daemon().start(() -> {
      try {
        Thread.sleep(TIMEOUT_MILLIS);
        late.set(true);
        loop.stop();
      } catch (InterruptedException e) {
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

  /** Runs a loop until {@code done} after a frame was presented; see {@link #run}. */
  static void runUntil(Window window, App<?> app, BooleanSupplier done, String what) {
    AtomicReference<WindowLoop> loop = new AtomicReference<>();
    loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
      if (done.getAsBoolean()) {
        loop.get().stop();
      }
    }));
    run(loop.get(), what);
  }

  /**
   * Tells a presenter whether the frame it presents is one that the desktop asked for: the loop
   * presents a frame for each {@link WindowEvent.Redraw}, which comes when the window is exposed,
   * as while it maps late on a busy machine. Give it to the loop as its listener.
   */
  static final class Redraws implements WindowListener {
    private boolean asked;
    private int count;

    @Override
    public void event(WindowEvent event) {
      asked = event instanceof WindowEvent.Redraw;
    }

    /** Whether the frame that is presented now is a redraw's; call it once per frame. */
    boolean asked() {
      boolean was = asked;
      asked = false;
      if (was) {
        count++;
      }
      return was;
    }

    /** The frames presented for redraws. */
    int count() {
      return count;
    }
  }

  /**
   * Restores a minimized window and delivers events until it is restored, asking again every two
   * seconds, as a window manager under load may drop a request; false after {@link
   * #TIMEOUT_MILLIS}.
   */
  static boolean restore(Window window) {
    for (int i = 0; i < TIMEOUT_MILLIS / 2000; i++) {
      window.restore();
      if (pollUntil(() -> !window.isMinimized(), 2000)) {
        return true;
      }
    }
    return false;
  }

  /** Delivers events until {@code done}, for at most {@link #TIMEOUT_MILLIS}. */
  static boolean pollUntil(BooleanSupplier done) {
    return pollUntil(done, TIMEOUT_MILLIS);
  }

  private static boolean pollUntil(BooleanSupplier done, long millis) {
    long end = System.nanoTime() + millis * 1_000_000;
    while (true) {
      Window.waitEvents(10);
      if (done.getAsBoolean()) {
        return true;
      }
      if (System.nanoTime() > end) {
        return false;
      }
    }
  }

  /** Delivers what the window's opening caused, so that it does not mix with what a test sends. */
  static void settle() {
    for (int i = 0; i < 10; i++) {
      Window.waitEvents(10);
    }
  }

  static void key(Window window, boolean down, int keycode, int sdlModifiers, boolean repeat) {
    push(window, down ? Sdl.EVENT_KEY_DOWN : Sdl.EVENT_KEY_UP, e -> {
      e.set(JAVA_INT, Sdl.KEY_KEY, keycode);
      e.set(JAVA_SHORT, Sdl.KEY_MOD, (short) sdlModifiers);
      e.set(JAVA_BOOLEAN, Sdl.KEY_DOWN, down);
      e.set(JAVA_BOOLEAN, Sdl.KEY_REPEAT, repeat);
    });
  }

  /** A button event at a position in logical pixels. */
  static void button(Window window, boolean down, int button, float x, float y, int clicks) {
    push(window, down ? Sdl.EVENT_MOUSE_BUTTON_DOWN : Sdl.EVENT_MOUSE_BUTTON_UP, e -> {
      e.set(JAVA_BYTE, Sdl.BUTTON_BUTTON, (byte) button);
      e.set(JAVA_BOOLEAN, Sdl.BUTTON_DOWN, down);
      e.set(JAVA_BYTE, Sdl.BUTTON_CLICKS, (byte) clicks);
      e.set(JAVA_FLOAT, Sdl.BUTTON_X, x * window.unitsPerLogical());
      e.set(JAVA_FLOAT, Sdl.BUTTON_Y, y * window.unitsPerLogical());
    });
  }

  /** A click with the left button at a position in logical pixels. */
  static void click(Window window, float x, float y) {
    button(window, true, Sdl.BUTTON_LEFT, x, y, 1);
    button(window, false, Sdl.BUTTON_LEFT, x, y, 1);
  }

  static void motion(Window window, float x, float y) {
    push(window, Sdl.EVENT_MOUSE_MOTION, e -> {
      e.set(JAVA_FLOAT, Sdl.MOTION_X, x * window.unitsPerLogical());
      e.set(JAVA_FLOAT, Sdl.MOTION_Y, y * window.unitsPerLogical());
    });
  }

  static void wheel(Window window, float dx, float dy, float x, float y) {
    push(window, Sdl.EVENT_MOUSE_WHEEL, e -> {
      e.set(JAVA_FLOAT, Sdl.WHEEL_X, dx);
      e.set(JAVA_FLOAT, Sdl.WHEEL_Y, dy);
      e.set(JAVA_FLOAT, Sdl.WHEEL_MOUSE_X, x * window.unitsPerLogical());
      e.set(JAVA_FLOAT, Sdl.WHEEL_MOUSE_Y, y * window.unitsPerLogical());
    });
  }

  /** Text input; the text is copied while SDL calls the event watch, inside this call. */
  static void text(Window window, String text) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment utf8 = arena.allocateFrom(text);
      push(window, Sdl.EVENT_TEXT_INPUT, e -> e.set(ADDRESS, Sdl.TEXT_TEXT, utf8));
    }
  }

  /** An input method's composition, with its cursor in characters or -1. */
  static void editing(Window window, String text, int start) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment utf8 = arena.allocateFrom(text);
      push(window, Sdl.EVENT_TEXT_EDITING, e -> {
        e.set(ADDRESS, Sdl.EDITING_TEXT, utf8);
        e.set(JAVA_INT, Sdl.EDITING_START, start);
        e.set(JAVA_INT, Sdl.EDITING_LENGTH, start < 0 ? -1 : 0);
      });
    }
  }

  static void windowEvent(Window window, int type, int data1, int data2) {
    push(window, type, e -> {
      e.set(JAVA_INT, Sdl.WINDOW_DATA1, data1);
      e.set(JAVA_INT, Sdl.WINDOW_DATA2, data2);
    });
  }

  /** An event of the whole app, of no window, such as the system theme's change. */
  static void systemEvent(int type) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment event = arena.allocate(Sdl.EVENT_SIZE, 8);
      event.set(JAVA_INT, Sdl.EVENT_TYPE, type);
      Sdl.pushEvent(event);
    }
  }

  private static void push(Window window, int type, java.util.function.Consumer<MemorySegment> fill) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment event = arena.allocate(Sdl.EVENT_SIZE, 8);
      event.set(JAVA_INT, Sdl.EVENT_TYPE, type);
      event.set(JAVA_INT, Sdl.EVENT_WINDOW_ID, window.id());
      fill.accept(event);
      Sdl.pushEvent(event);
    }
  }
}
