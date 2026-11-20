package com.rethinkj.ui;

/**
 * A straight line that an element draws ({@link El#segments}): from {@code x1, y1} to {@code x2,
 * y2}, in logical pixels from the top left corner of the element's border box, {@code width}
 * wide and with round ends, so it is a capsule. Its edges are smoothed like any other, and a line
 * thinner than a pixel takes as much of it as it lies over.
 *
 * @param width how wide the line is, more than 0
 * @param color the color as 0xAARRGGBB
 */
public record Segment(float x1, float y1, float x2, float y2, float width, int color) {

  public Segment {
    if (!(Float.isFinite(x1) && Float.isFinite(y1) && Float.isFinite(x2) && Float.isFinite(y2))) {
      throw new IllegalArgumentException(
          "A segment's ends must be finite, were " + x1 + "," + y1 + " and " + x2 + "," + y2);
    }
    if (!(width > 0 && width < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("A segment's width must be more than 0, was " + width);
    }
  }
}
