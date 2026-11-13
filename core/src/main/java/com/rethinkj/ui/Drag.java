package com.rethinkj.ui;

/**
 * One step of a drag that an element follows with {@link El#onDrag}: the pointer went down on the
 * element and moved more than {@link #THRESHOLD} logical pixels from there along either axis with
 * the button held.
 *
 * @param phase which step this is
 * @param dx how far the pointer is from where it went down, in logical pixels, positive to the
 *     right
 * @param dy the same downwards
 */
public record Drag(Phase phase, float dx, float dy) {

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
     * The drag was given up before the button was released: its element left the tree, or the
     * pointer went down again without a release in between. Undo what it changed.
     */
    CANCEL
  }
}
