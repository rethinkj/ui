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
