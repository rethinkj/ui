package com.rethinkj.ui;

/**
 * The shape of the pointer over an element, set with {@link El#cursor}. {@link App#cursor} says
 * which one the element under the pointer wants, and a window shows the system's cursor of that
 * shape. The names are those of CSS.
 */
public enum Cursor {
  /** The system's usual pointer, an arrow. */
  DEFAULT,
  /** A hand pointing at something to press, such as a link or a button. */
  POINTER,
  /** An I-beam over text that can be selected or edited. */
  TEXT,
  /** What the pointer is over cannot be used now. */
  NOT_ALLOWED,
  /** Resizes or moves something left and right, such as a column's edge. */
  EW_RESIZE,
  /** Resizes or moves something up and down, such as a row's edge. */
  NS_RESIZE,
  /** Resizes from the top left or the bottom right corner. */
  NWSE_RESIZE,
  /** Resizes from the top right or the bottom left corner. */
  NESW_RESIZE
}
