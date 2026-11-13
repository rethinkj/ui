package com.rethinkj.ui.window;

import com.rethinkj.ui.App;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.window.WindowEvent.CloseRequested;
import com.rethinkj.ui.window.WindowEvent.FocusChanged;
import com.rethinkj.ui.window.WindowEvent.KeyDown;
import com.rethinkj.ui.window.WindowEvent.KeyUp;
import com.rethinkj.ui.window.WindowEvent.PointerDown;
import com.rethinkj.ui.window.WindowEvent.PointerLeave;
import com.rethinkj.ui.window.WindowEvent.PointerMove;
import com.rethinkj.ui.window.WindowEvent.PointerUp;
import com.rethinkj.ui.window.WindowEvent.Redraw;
import com.rethinkj.ui.window.WindowEvent.Resized;
import com.rethinkj.ui.window.WindowEvent.ScaleChanged;
import com.rethinkj.ui.window.WindowEvent.TextEditing;
import com.rethinkj.ui.window.WindowEvent.TextInput;
import com.rethinkj.ui.window.WindowEvent.Wheel;
import java.util.Objects;

/**
 * Runs an {@link App} in a {@link Window}: feeds it the window's pointer, wheel, key and text input
 * and its size, with the right button as the secondary one ({@link App#secondaryDown}), lets it build frames, hands each new frame to a {@link Presenter}, which draws it,
 * and shows the {@link App#cursor} the app asks for.
 *
 * <p>A step of the wheel scrolls {@link #WHEEL_STEP}, 48 logical pixels: three lines of 16 pixels, as
 * Windows scrolls three lines per notch unless the user changed it, and within the 40 to 60 pixels
 * that browsers and toolkits scroll per notch on Linux. Touchpads and precise wheels report parts
 * of a step, which scroll that part of the distance. Rolling the wheel towards the user scrolls
 * towards the end, so that the content moves up.
 *
 * <p>The loop draws only when there is something new: when {@link App#frame} says so, when the
 * window asks for a {@link WindowEvent.Redraw}, or when the presenter could not show the last frame
 * ({@link Presenter#pending}). An idle window waits for events and does nothing else, except for
 * waking when the caret of a focused text field blinks ({@link App#nextChange}), which it does only
 * while the window is active ({@link App#windowActive}), when an action the app asked for with
 * {@link App#after} is due, and when another thread {@link App#post posts} a task: the post pushes
 * an event that ends the wait at once, the tasks that arrived until the frame takes them run in
 * that one frame, and a thread that posts nothing costs nothing. While something moves, the loop
 * advances the app's clock by the time that passed and draws a frame per refresh of the display.
 *
 * <p>Input, and a task posted, that arrives less than a refresh after the last frame drawn waits
 * for that refresh, and whatever else arrives meanwhile goes into the same frame: a fast wheel, a
 * drag or a stream of tasks draws one frame per refresh, not one per event. Presenting does not
 * pace the loop where the display does not hold back a frame, as on a machine whose display is
 * virtual, and frames the display replaces before it shows them cost processor and graphics time
 * for nothing. Input or a task after a pause of a refresh or more is drawn at once.
 *
 * <p>The loop also gives the app the system's clipboard ({@link App#clipboard}) and its fonts for
 * characters that the app's fonts lack ({@link App#systemFonts}, unless {@link #systemFonts} turned
 * that off), turns the window's text input on while the focused element takes text and tells it
 * where ({@link App#inputArea}, {@link Window#textInputArea}), and passes on the compositions of
 * input methods.
 *
 * <pre>{@code
 * try (Window window = Window.open("Lights", 480, 320, Window.options().frameless());
 *     GpuPresenter presenter = new GpuPresenter(window)) {
 *   new WindowLoop(window, new App<>(state, View::lights), presenter).run();
 * }
 * }</pre>
 */
public final class WindowLoop {

  /** Logical pixels per step of the wheel; see the class's description. */
  public static final float WHEEL_STEP = 48;

  /** Draws a frame into the window. */
  @FunctionalInterface
  public interface Presenter {
    /**
     * Draws a frame, on the window's thread. During a move or resize on Windows, this is called
     * from inside the system's loop and should not wait long.
     *
     * @param scene what to draw, in logical pixels
     * @param width the logical width the scene was laid out for
     * @param height the logical height
     * @param scale physical pixels per logical pixel
     */
    void present(Scene scene, float width, float height, float scale);

    /**
     * Whether the last frame could not be shown and should be offered again, for example because
     * the window's surface was out of date while the window was resized. The loop then calls
     * {@link #present} with the newest frame after one refresh of the display, even if nothing
     * changed. False unless a presenter says otherwise.
     */
    default boolean pending() {
      return false;
    }
  }

  private final Window window;
  private final App<?> app;
  private final Presenter presenter;
  private WindowListener listener;
  private Waits waits = (millis, holdBack) -> {};
  private volatile boolean running;
  private float width = -1;
  private float height = -1;
  private float scale = -1;
  private long clock;
  private long presented;
  private long started; // when the last frame presented began to be built
  private long frameNanos;
  private boolean systemFonts = true;
  // Input or a posted task arrived that no frame has handled yet; posts set it on their thread.
  private volatile boolean input;

  public WindowLoop(Window window, App<?> app, Presenter presenter) {
    this.window = Objects.requireNonNull(window, "window");
    this.app = Objects.requireNonNull(app, "app");
    this.presenter = Objects.requireNonNull(presenter, "presenter");
  }

  /** Runs {@code app} in {@code window} until the window asks to close. */
  public static void run(Window window, App<?> app, Presenter presenter) {
    new WindowLoop(window, app, presenter).run();
  }

  /**
   * Also gives every event to {@code listener}, before the loop handles it, for what the loop does
   * not, such as the middle button.
   *
   * @return this loop
   */
  public WindowLoop listener(WindowListener listener) {
    this.listener = listener;
    return this;
  }

  /** What the loop does between frames, for tests, which wait for a state instead of a time. */
  @FunctionalInterface
  interface Waits {
    /**
     * Told on the loop's thread right before it waits for events.
     *
     * @param millis the longest the wait may take, or -1 when only an event ends it: nothing
     *     moves, nothing is pending and nothing is due on the app's clock. Events that arrived
     *     while the frame was drawn may already be there and end it at once.
     * @param holdBack whether input or a posted task waits for the next refresh
     */
    void waiting(long millis, boolean holdBack);
  }

  /** Tells {@code waits} of each wait; see {@link Waits}. */
  WindowLoop waits(Waits waits) {
    this.waits = Objects.requireNonNull(waits, "waits");
    return this;
  }

  /**
   * Whether {@link #run} gives the app the system's fonts ({@link App#systemFonts}), as it does
   * unless this turns it off, for an app that has to look the same on every machine.
   *
   * @return this loop
   */
  public WindowLoop systemFonts(boolean on) {
    this.systemFonts = on;
    return this;
  }

  /**
   * Runs until the user asks to close the window or {@link #stop} is called, on the window's
   * thread. Meanwhile the loop is the window's listener, answers its {@link Window#parts} from
   * the app and wakes for the app's posted tasks ({@link App#onPost}); afterwards it does none of
   * that, and tasks posted later wait in the app until it runs frames again. The window stays
   * open; close it afterwards. What a listener, the presenter or a posted task throws ends the
   * loop and comes out of here.
   */
  public void run() {
    if (running) {
      throw new IllegalStateException("The loop runs already");
    }
    running = true;
    app.clipboard(new SystemClipboard());
    app.systemFonts(systemFonts);
    window.listener(this::event);
    window.parts(app::windowPart);
    app.onPost(() -> { // a task from another thread ends the wait below
      input = true;
      Window.wake();
    });
    try {
      clock = System.nanoTime();
      refreshRate();
      while (running && window.isOpen()) {
        long early = holdBack(System.nanoTime() - started, frameNanos, input);
        if (early > 0) { // more input may come before the frame can be shown
          // A post ends this wait early, and the next turn waits for the rest of the refresh.
          long millis = Math.max(1, (early + 999_999) / 1_000_000);
          waits.waiting(millis, true);
          Window.waitEvents(millis);
          continue;
        }
        input = false;
        draw(false);
        if (!running || !window.isOpen()) {
          break;
        }
        double change = app.nextChange() - app.time();
        if (app.isMoving() || presenter.pending()) {
          long left = frameNanos - (System.nanoTime() - presented);
          long millis = Math.max(0, left / 1_000_000);
          waits.waiting(millis, false);
          Window.waitEvents(millis);
        } else if (change < Double.POSITIVE_INFINITY) {
          long millis = Math.max(1, (long) Math.ceil(change * 1000));
          waits.waiting(millis, false);
          Window.waitEvents(millis);
        } else {
          waits.waiting(-1, false);
          Window.waitEvents();
        }
      }
    } finally {
      running = false;
      app.onPost(null);
      if (window.isOpen()) {
        window.listener(null);
        window.parts(null);
      }
    }
  }

  /**
   * How long input waits before the loop draws it, in nanoseconds: until a refresh has passed since
   * the last frame drawn, or 0 to draw at once.
   *
   * @param sinceFrame the time since the building of the last frame drawn began
   * @param input whether input or a posted task arrived since then that no frame has handled
   */
  static long holdBack(long sinceFrame, long frameNanos, boolean input) {
    return input ? Math.max(frameNanos - sinceFrame, 0) : 0;
  }

  /** Ends {@link #run} after the event it handles now. This may be called on any thread. */
  public void stop() {
    running = false;
    Window.wake();
  }

  public boolean isRunning() {
    return running;
  }

  private void event(WindowEvent event) {
    WindowListener current = listener;
    if (current != null) {
      current.event(event);
    }
    if (event instanceof PointerDown || event instanceof PointerUp || event instanceof PointerMove
        || event instanceof PointerLeave || event instanceof Wheel || event instanceof KeyDown
        || event instanceof KeyUp || event instanceof TextInput || event instanceof TextEditing) {
      input = true;
    }
    switch (event) {
      case PointerDown down when down.button() == PointerButton.LEFT ->
          app.pointerDown(down.x(), down.y(), down.clicks(), down.modifiers());
      case PointerUp up when up.button() == PointerButton.LEFT -> app.pointerUp(up.x(), up.y());
      case PointerDown down when down.button() == PointerButton.RIGHT ->
          app.secondaryDown(down.x(), down.y());
      case PointerUp up when up.button() == PointerButton.RIGHT -> app.secondaryUp(up.x(), up.y());
      case PointerMove move -> app.pointerMove(move.x(), move.y());
      case PointerLeave leave -> app.pointerLeave();
      // The window's dy is positive away from the user, the app's towards the end.
      case Wheel wheel ->
          app.wheel(wheel.x(), wheel.y(), wheel.dx() * WHEEL_STEP, -wheel.dy() * WHEEL_STEP);
      case KeyDown down -> app.keyDown(down.key());
      case KeyUp up -> app.keyUp(up.key());
      case TextInput text -> app.textInput(text.text());
      case TextEditing editing -> app.textComposition(editing.text(), editing.cursor());
      case FocusChanged focus -> app.windowActive(focus.focused());
      case Resized resized -> resize();
      case ScaleChanged changed -> {
        resize();
        refreshRate();
      }
      case Redraw redraw -> draw(!redraw.live());
      case CloseRequested close -> running = false;
      default -> {}
    }
  }

  /** Builds a frame and presents it if it is new, if {@code always}, or if one is pending. */
  private void draw(boolean always) {
    resize();
    long now = System.nanoTime();
    app.advance(Math.max(0, now - clock) / 1e9);
    clock = now;
    if (app.frame() || always || presenter.pending()) {
      presenter.present(app.scene(), width, height, scale);
      presented = System.nanoTime();
      started = now;
    }
    window.cursor(app.cursor());
    App.InputArea area = app.inputArea();
    if (area == null) {
      window.stopTextInput();
    } else {
      window.textInputArea(
          area.x(), area.y(), area.width(), area.height(), area.cursor(), area.password());
    }
  }

  private void resize() {
    float w = window.width();
    float h = window.height();
    float s = window.scale();
    if (w != width || h != height || s != scale) {
      width = w;
      height = h;
      scale = s;
      app.resize(w, h, s);
    }
  }

  private void refreshRate() {
    float hertz = window.refreshRate();
    frameNanos = (long) (1e9 / (hertz >= 20 && hertz <= 1000 ? hertz : 60));
  }
}
