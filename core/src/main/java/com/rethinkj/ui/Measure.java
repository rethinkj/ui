package com.rethinkj.ui;

/**
 * Sizes the content of an element that has no children, such as text. Layout calls it, possibly
 * several times with different constraints, for the element it is set on with {@link
 * El#measure(Measure)}.
 */
@FunctionalInterface
public interface Measure {

  /** An available size: as wide or tall as the content wants to be. */
  float MAX_CONTENT = Float.POSITIVE_INFINITY;

  /** An available size: as narrow or short as the content can be, taking every line break. */
  float MIN_CONTENT = Float.NEGATIVE_INFINITY;

  /**
   * All sizes are those of the content box, without padding and border.
   *
   * @param knownWidth the width the content has to take, or NaN when layout has not decided it
   * @param knownHeight the height the content has to take, or NaN
   * @param availableWidth the width the content may fill, in pixels, or {@link #MAX_CONTENT} or
   *     {@link #MIN_CONTENT}
   * @param availableHeight the same for the height
   */
  Size measure(float knownWidth, float knownHeight, float availableWidth, float availableHeight);

  /**
   * @param baseline the distance from the top of the content to its first baseline, or NaN when it
   *     has none; then the bottom edge is used for baseline alignment
   */
  record Size(float width, float height, float baseline) {

    public Size(float width, float height) {
      this(width, height, Float.NaN);
    }
  }
}
