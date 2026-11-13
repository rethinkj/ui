package com.rethinkj.ui;

/**
 * What an element is to a window that draws its own frame, set with {@link El#windowPart}. The
 * window asks {@link App#windowPart} for the part under the pointer and lets the system move,
 * minimize, maximize or close it.
 */
public enum WindowPart {
  /**
   * Ordinary content, which gets the pointer. Marks an element inside a drag area that should not
   * drag the window, such as a search field in a title bar.
   */
  CONTENT,
  /**
   * Content that takes presses itself: {@link App#windowPart} says so where a press would go to
   * an element with a press or drag handler, to a text field or to a scrollbar. The resize margin
   * of a frameless window, which lies inside the window, narrows to {@link
   * com.rethinkj.ui.window.Window#CONTROL_MARGIN} over it, as over the minimize, maximize and close
   * parts, so that a scrollbar or a button at the window's edge can be grabbed at its outer pixels
   * while the rest of the edge resizes as before. A view may mark an element with it too.
   */
  CONTROL,
  /**
   * Moves the window when dragged, like a system title bar. On Windows a double click maximizes
   * it; on Linux it does not, because the press goes to the window manager as the start of a move.
   */
  DRAG,
  /** Minimizes the window when clicked. */
  MINIMIZE,
  /**
   * Maximizes the window when clicked, or restores it when it is maximized. On Windows 11, resting
   * the pointer on it shows the snap layouts.
   */
  MAXIMIZE,
  /** Asks the window to close when clicked, as the system's close button does. */
  CLOSE
}
