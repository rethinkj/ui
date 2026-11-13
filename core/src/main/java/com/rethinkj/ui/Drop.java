package com.rethinkj.ui;

/**
 * What an element with {@link El#onDrop} learns of a drag that carries a payload ({@link
 * El#dragPayload}): that its pointer is over the element, has left it, or was released over it.
 * The element may lie in the window the drag began in or in another window of the same runner
 * ({@link com.rethinkj.ui.window.Windows}), and is hit tested there as a press would be, overlays
 * first; the innermost element with a drop handler under the pointer gets the steps. What another
 * application drags onto the window, files or text, comes the same way to {@link El#onFileDrop}.
 *
 * @param phase which step this is
 * @param payload what the drag carries, as its element named it when the pointer went down; for
 *     {@link El#onFileDrop}, the files as a {@code List<java.nio.file.Path>} or the text as a
 *     {@code String} with {@link Phase#DROP}, and null before
 * @param x where the pointer is in the element's content, as {@link Drag#x}: from the left of its
 *     border box as the last frame of its window shows it, plus its own horizontal scroll offset;
 *     NaN for {@link Phase#LEAVE}
 * @param y the same downwards
 */
public record Drop(Phase phase, Object payload, double x, double y) {

  public enum Phase {
    /**
     * The pointer moved over the element, or onto it: the time to show where the payload would
     * go. Comes on every step of the drag while the pointer stays there.
     */
    OVER,
    /**
     * The pointer left the element, the drag was cancelled, or it ended outside: hide what {@link
     * #OVER} showed.
     */
    LEAVE,
    /**
     * The button was released over the element: take the payload. No {@link #LEAVE} follows. The
     * drag's own handler gets {@link Drag.Phase#END} after this, and can tell from the state
     * whether the payload was taken.
     */
    DROP
  }
}
