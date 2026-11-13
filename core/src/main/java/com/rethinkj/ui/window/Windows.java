package com.rethinkj.ui.window;

import com.rethinkj.ui.App;
import java.util.List;

/**
 * The windows of whatever runs an app: a {@link WindowLoop} on the desktop, or a {@link
 * com.rethinkj.ui.Script} without a window. An app reaches it with {@link App#windows()}, usually
 * through the state, and opens and closes windows from its handlers, on the app's thread, without
 * knowing how they are drawn:
 *
 * <pre>{@code
 * void tearOff(Note note) { // a handler
 *   App<Notes> app = new App<>(this, s -> s.noteView(note)); // the same state, another view
 *   Windows.Handle window = this.app.windows().open(note.title, 280, 200,
 *       Window.options().position(x, y), app);
 *   window.onCloseRequest(() -> { dock(note); window.close(); });
 * }
 * }</pre>
 *
 * <p>Each window runs an {@link App} of its own, with its own view, focus, hover, scroll offsets,
 * animations and clock; apps that show one state are made with the same state object. When a
 * handler, a posted task or an action of {@link App#after} of one app of a runner has run, the
 * runner {@link App#invalidate invalidates} its other apps, which build their views again in the
 * same pass: what a press in one window changes shows in the others with the same frame.
 *
 * <p>Positions on the screen are in the units {@link Window#moveTo} takes: physical pixels on
 * Windows and X11, where a window's {@link Handle#screenScale} is its scale; on Wayland a client
 * knows no positions, and they are all 0. A script's screen is made up, in physical pixels as on
 * Windows.
 */
public interface Windows {

  /** The windows of an app that no runner runs: none, and none opens. */
  Windows NONE = new Windows() {
    @Override
    public Handle open(String title, float width, float height, Window.Options options,
        App<?> app) {
      throw new IllegalStateException("No runner runs this app, so it cannot open a window. Run"
          + " it in a WindowLoop or a Script, which set App.windows().");
    }

    @Override
    public List<Handle> all() {
      return List.of();
    }

    @Override
    public Handle of(App<?> app) {
      return null;
    }

    @Override
    public Place place(App<?> app, float x, float y) {
      return null;
    }
  };

  /**
   * Opens a window that runs {@code app}, and shows it. Call it on the app's thread, from a
   * handler or between frames; the window's first frame comes in the same pass of the runner. The
   * runner gives {@code app} its clipboard, its fonts and this object, as to its first app.
   *
   * @param width the width of the content in logical pixels
   * @param height its height
   * @param options how it opens, as for {@link Window#open}: frameless, icon, minimum size, and
   *     its position on the screen ({@link Window.Options#position})
   * @throws IllegalStateException where nothing opens windows: an app that no runner runs
   */
  Handle open(String title, float width, float height, Window.Options options, App<?> app);

  /** The open windows, in the order they opened, the runner's first window first. */
  List<Handle> all();

  /** The open window that runs {@code app}, or null. */
  Handle of(App<?> app);

  /**
   * Where a point of the window that runs {@code app} lies on the screen, and which window, if
   * any, lies there, for {@link App}'s drags; null if no open window runs {@code app}. The window
   * there is the topmost of the runner's windows that are {@link Handle#dropTarget drop targets}
   * and whose content holds the point: the runner keeps them in the order they last opened, were
   * raised or took the keyboard focus, as the system cannot say which lies on top.
   *
   * @param x the point in logical pixels of {@code app}'s window, also outside it
   */
  Place place(App<?> app, float x, float y);

  /**
   * A point on the screen and the window there.
   *
   * @param screenX the point in screen units, see {@link Windows}
   * @param window the window there, or null
   * @param x the point in logical pixels of {@code window}; NaN without one
   */
  record Place(float screenX, float screenY, Handle window, float x, float y) {}

  /** An open window of a runner; on the app's thread. */
  interface Handle {

    /** The app the window runs. */
    App<?> app();

    String title();

    /** Whether the window is open: false from {@link #close} on. */
    boolean isOpen();

    /**
     * Closes the window, after the frame that runs now: the runner presents nothing more in it,
     * closes its presenter and then the window. The runner ends when its last window closes.
     */
    void close();

    /** Brings the window to the front and gives it the keyboard focus, if the system lets it. */
    void raise();

    /**
     * Moves the content's top left corner to this point of the screen; on Wayland nothing moves.
     * A window that follows the pointer is moved by its drag handler on each step, to {@link
     * com.rethinkj.ui.Drag#screenX} less where it holds the window, times {@link #screenScale}.
     */
    void moveTo(int x, int y);

    /** The position of the content's top left corner on the screen. */
    int x();

    /** See {@link #x()}. */
    int y();

    /** The width of the content in logical pixels. */
    float width();

    /** The height of the content in logical pixels. */
    float height();

    /** Screen units per logical pixel of this window: its scale on Windows and X11, else 1. */
    float screenScale();

    /**
     * What a request to close the window does: the close button, Alt+F4, the taskbar, a script's
     * {@code close}. With a handler, the request runs it as a task of the window's app, and only
     * the handler closes the window, now or later, as after a dialog that asks to save; not
     * closing it is a veto. Without one, the request closes the window, or, when it is the last
     * of a {@link WindowLoop}, ends the loop and leaves the window open for its owner, as a loop
     * of one window always did.
     *
     * @param handler the handler, or null for that default
     */
    void onCloseRequest(Runnable handler);

    /**
     * Whether a drag's pointer finds this window ({@link #place}): true unless this turns it
     * off, as for a window that follows the pointer and would hide every window under it.
     */
    void dropTarget(boolean on);

    /** The window, for what only a real one does, such as its taskbar button; null in a script. */
    Window window();
  }
}
