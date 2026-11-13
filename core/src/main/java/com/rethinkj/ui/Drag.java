package com.rethinkj.ui;

import com.rethinkj.ui.window.Windows;

/**
 * One step of a drag that an element follows with {@link El#onDrag}: the pointer went down on the
 * element and moved more than {@link #THRESHOLD} logical pixels from there along either axis with
 * the button held.
 *
 * @param phase which step this is
 * @param dx how far the pointer is from where it went down, in logical pixels, positive to the
 *     right
 * @param dy the same downwards
 * @param x where the pointer is in the element's content, in logical pixels: from the left of its
 *     border box as the last frame shows it, plus its own horizontal scroll offset; so in a scroll
 *     area, which content is under the pointer, also when the area scrolled during the drag. The
 *     wheel's scrolling of the frame that runs this step comes after it. NaN once the element has
 *     left the tree: for a cancel, and for the steps of a drag with a {@link El#dragPayload
 *     payload}, which goes on without it.
 * @param y the same downwards; a double, exact in a list of rows millions of pixels tall
 * @param screenX where the pointer is on the screen, in the units of {@link
 *     com.rethinkj.ui.window.Window#moveTo}: physical pixels on Windows and X11, on Wayland its
 *     place in the window, whose position no client knows; see {@link Windows}. The window's
 *     position plus the pointer's place in it, as the event that moved it says: a window that
 *     follows the pointer is moved there, less where it is held, times {@link
 *     Windows.Handle#screenScale}. Without a runner ({@link App#windows}), the place in the window
 * @param screenY the same downwards
 * @param outside whether the pointer is outside the window the drag began in
 * @param window the window of the runner that lies under the pointer, the drag's own included,
 *     or null: outside every window, over one that is no {@link Windows.Handle#dropTarget drop
 *     target}, and without a runner
 * @param windowX where the pointer is in {@code window}, in its logical pixels; NaN without one
 * @param windowY the same downwards
 */
public record Drag(Phase phase, float dx, float dy, double x, double y, float screenX,
    float screenY, boolean outside, Windows.Handle window, float windowX, float windowY) {

  /** A step that knows nothing of the screen, as in a test of a drag handler. */
  public Drag(Phase phase, float dx, float dy, double x, double y) {
    this(phase, dx, dy, x, y, Float.NaN, Float.NaN, false, null, Float.NaN, Float.NaN);
  }

  /**
   * How far the pointer has to move from where it went down, along either axis, before a press
   * becomes a drag, in logical pixels. A shorter movement leaves it a press.
   */
  public static final float THRESHOLD = 4;

  public enum Phase {
    /** The pointer has just moved beyond the threshold. The distance already counts. */
    START,
    /** The pointer moved on. */
    MOVE,
    /** The button was released: the drag is done, with this distance. */
    END,
    /**
     * The drag was given up before the button was released: its element left the tree, unless
     * the drag carries a {@link El#dragPayload payload}, Escape was pressed, or the pointer went down again
     * without a release in between. Undo what it changed.
     */
    CANCEL
  }
}
