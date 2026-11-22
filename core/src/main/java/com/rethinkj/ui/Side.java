package com.rethinkj.ui;

/**
 * A side of an element's rectangle: which edge {@link El#padding(Side)} and {@link El#border(Side)}
 * return, and where an overlay goes beside its anchor ({@link El#side}).
 */
public enum Side {
  TOP,
  BOTTOM,
  LEFT,
  RIGHT
}
