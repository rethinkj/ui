package com.rethinkj.ui;

/**
 * What the window that shows a view is like while the view is built: whether it is active, with
 * the system's keyboard focus, and whether it is maximized. A title bar of the app's own reads it
 * ({@link #CURRENT}) to look quieter in an inactive window and to show Restore in place of
 * Maximize. The app gives it to the whole view from what its window last said ({@link
 * App#windowActive}, {@link App#windowMaximized(boolean)}) and builds the view again when either
 * changes; without a window, as in a script, the window is active and not maximized.
 *
 * @param active whether the window has the system's keyboard focus
 * @param maximized whether the window is maximized
 */
public record WindowState(boolean active, boolean maximized) {

  /** The state of the window that shows the view being built. */
  public static final Context<WindowState> CURRENT = new Context<>(new WindowState(true, false));
}
