package com.rethinkj.ui;

/**
 * What the window that shows a view is like while the view is built: whether it is active, with
 * the system's keyboard focus, whether it is maximized, and its scale. A title bar of the app's own
 * reads it ({@link #CURRENT}) to look quieter in an inactive window and to show Restore in place of
 * Maximize; a component that measures text while it is built shapes it at the scale ({@link
 * Text#scale(float)}), so that it gets the width layout will. The app gives it to the whole view
 * from what its window last said ({@link App#windowActive}, {@link App#windowMaximized(boolean)},
 * {@link App#resize}) and builds the view again when any of them changes; without a window, as in
 * a script, the window is active and not maximized, at the script's scale.
 *
 * @param active whether the window has the system's keyboard focus
 * @param maximized whether the window is maximized
 * @param scale the window's scale, in physical pixels per logical pixel
 */
public record WindowState(boolean active, boolean maximized, float scale) {

  /** The state of the window that shows the view being built; at a scale of 1 outside an app. */
  public static final Context<WindowState> CURRENT = new Context<>(new WindowState(true, false, 1));
}
