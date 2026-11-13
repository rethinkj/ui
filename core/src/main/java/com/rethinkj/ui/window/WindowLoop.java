package com.rethinkj.ui.window;

import com.rethinkj.ui.App;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.window.WindowEvent.CloseRequested;
import com.rethinkj.ui.window.WindowEvent.KeyDown;
import com.rethinkj.ui.window.WindowEvent.KeyUp;
import com.rethinkj.ui.window.WindowEvent.PointerDown;
import com.rethinkj.ui.window.WindowEvent.PointerUp;
import com.rethinkj.ui.window.WindowEvent.Redraw;
import com.rethinkj.ui.window.WindowEvent.Resized;
import com.rethinkj.ui.window.WindowEvent.ScaleChanged;
import com.rethinkj.ui.window.WindowEvent.TextInput;
import java.util.Objects;

/**
 * Runs an {@link App} in a {@link Window}: feeds it the window's pointer, key and text input and
 * its size, lets it build frames, and hands each new frame to a {@link Presenter}, which draws it.
 *
 * <p>The loop draws only when there is something new: when {@link App#frame} says so, when the
 * window asks for a {@link WindowEvent.Redraw}, or when the presenter could not show the last frame
 * ({@link Presenter#pending}). An idle window waits for events and does nothing else. While
 * something moves, the loop advances the app's clock by the time that passed and draws a frame per
 * refresh of the display.
 *
 * <pre>{@code
 * try (Window window = Window.open("Lights", 480, 320, Window.options().frameless());
 *     GpuPresenter presenter = new GpuPresenter(window)) {
 *   new WindowLoop(window, new App<>(state, View::lights), presenter).run();
 * }
 * }</pre>
 */
public final class WindowLoop {

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
  private volatile boolean running;
  private float width = -1;
  private float height = -1;
  private float scale = -1;
  private long clock;
  private long presented;
  private long frameNanos;

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
   * not, such as the wheel.
   *
   * @return this loop
   */
  public WindowLoop listener(WindowListener listener) {
    this.listener = listener;
    return this;
  }

  /**
   * Runs until the user asks to close the window or {@link #stop} is called, on the window's
   * thread. Meanwhile the loop is the window's listener and answers its {@link Window#parts} from
   * the app; afterwards the window has neither. The window stays open; close it afterwards. What a
   * listener or the presenter throws ends the loop and comes out of here.
   */
  public void run() {
    if (running) {
      throw new IllegalStateException("The loop runs already");
    }
    running = true;
    window.listener(this::event);
    window.parts(app::windowPart);
    try {
      clock = System.nanoTime();
      refreshRate();
      while (running && window.isOpen()) {
        draw(false);
        if (!running || !window.isOpen()) {
          break;
        }
        if (app.isMoving() || presenter.pending()) {
          long left = frameNanos - (System.nanoTime() - presented);
          Window.waitEvents(Math.max(0, left / 1_000_000));
        } else {
          Window.waitEvents();
        }
      }
    } finally {
      running = false;
      if (window.isOpen()) {
        window.listener(null);
        window.parts(null);
      }
    }
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
    switch (event) {
      case PointerDown down when down.button() == PointerButton.LEFT ->
          app.pointerDown(down.x(), down.y());
      case PointerUp up when up.button() == PointerButton.LEFT -> app.pointerUp(up.x(), up.y());
      case KeyDown down -> app.keyDown(down.key());
      case KeyUp up -> app.keyUp(up.key());
      case TextInput text -> app.textInput(text.text());
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
