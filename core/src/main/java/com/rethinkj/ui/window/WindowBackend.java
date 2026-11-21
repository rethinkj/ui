package com.rethinkj.ui.window;

/**
 * What a {@link Window} does on its system beyond SDL, and the state it keeps for that: one per
 * window, made in {@code Window.setUp} from its {@link WindowHandle}. Each hook does nothing unless
 * the system needs it; a video driver without a handle gets {@link #NONE}.
 */
sealed interface WindowBackend
    permits Win32Backend, X11Backend, WaylandBackend, WindowBackend.None {

  /** The backend of a window without a handle, and of every window until it has its own. */
  WindowBackend NONE = new None();

  /** What {@link #handedPress} says where the system makes double clicks on a drag area itself. */
  long NO_PRESS = Long.MIN_VALUE;

  /** The backend of a window with this handle; it touches nothing native until {@link #setUp}. */
  static WindowBackend of(Window window, WindowHandle handle) {
    return switch (handle) {
      case WindowHandle.Win32 win -> new Win32Backend(window, win.hwnd());
      case WindowHandle.Xlib(var display, long xwindow) -> new X11Backend(display, xwindow);
      case WindowHandle.Wayland _ -> new WaylandBackend();
      case null -> NONE;
    };
  }

  /** As the window is set up, before its icon, while it is still hidden. */
  default void setUp() {}

  /** The window is frameless and SDL's hit test is set. */
  default void frameless() {}

  /** Before the window shows, and before it is maximized for its placement. */
  default void cloak() {}

  /** Before a window that opens under the pointer shows. */
  default void noOpeningAnimation() {}

  /** SDL reported a move of the pointer in the client area. */
  default void pointerInClient() {}

  /** Whether the pointer is on a frameless window's maximize part; SDL's leave is dropped then. */
  default boolean pointerOnMaximize() {
    return false;
  }

  /**
   * Whether the system makes the characters of keys itself, as Windows does ({@code WM_CHAR},
   * {@link Window#systemChar}), and makes a space of Control+Space.
   */
  default boolean systemChars() {
    return false;
  }

  /**
   * The text of a character that the system made of a key ({@link Window#systemChar}), or null if
   * there is none yet or it is dropped.
   *
   * @param drop whether the window drops it, since text input is on or the window is closed
   */
  default String charText(char c, boolean drop) {
    return drop ? null : String.valueOf(c);
  }

  /**
   * The system's time of the last press, for a left press on a drag area that SDL handed to the
   * window manager, which makes no double click of it, so that the window does; else {@link
   * #NO_PRESS}.
   */
  default long handedPress() {
    return NO_PRESS;
  }

  /** The window's display scale changed. */
  default void scaleChanged(float scale) {}

  /** Whether the system may move the window some time after it was asked to, and not say so. */
  default boolean movesLater() {
    return false;
  }

  /** Whether the system has the window maximized or minimized, maybe before SDL has the flag. */
  default boolean maximizedOrMinimized() {
    return false;
  }

  /** The size of the icon's base image ({@link Window#icon(WindowIcon)}). */
  default int iconSize(float scale) {
    return 64;
  }

  /** The sizes of the icon's alternate images, larger than the base. */
  default int[] iconAlternates() {
    return new int[0];
  }

  /** After SDL has the window's icon. */
  default void icon(WindowIcon icon, float scale) {}

  /** See {@link Window#progress}; the value is from 0 to 1. */
  default boolean progress(Window.Progress state, float value) {
    return false;
  }

  /** See {@link Window#badge}. */
  default boolean badge(WindowIcon badge, String description, float scale) {
    return false;
  }

  /** See {@link Window#frameDark}. */
  default boolean frameDark(boolean dark) {
    return false;
  }

  /** See {@link Window#background}. */
  default void background(int argb) {}

  /** After {@link #reveal}, as the app has drawn a frame; see {@link Window#drawn}. */
  default void drawn() {}

  /**
   * See {@link Window#reveal}.
   *
   * @param open whether the window is open
   * @return false if the window stays hidden
   */
  default boolean reveal(boolean open) {
    return true;
  }

  /**
   * As the window closes, before SDL's window is destroyed.
   *
   * @return whether the window waits hidden for file dialogs it asked for that have not appeared
   *     yet, closing each as it appears ({@link #closeDialogs}), before it is destroyed
   */
  default boolean detach() {
    return false;
  }

  /** Closes the file dialogs the window owns, which answer as cancelled. */
  default void closeDialogs() {}

  /** After SDL's window is destroyed. */
  default void destroyed() {}

  /** A window without a handle: nothing beyond SDL. */
  record None() implements WindowBackend {}
}
