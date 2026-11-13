package com.rethinkj.ui;

/**
 * A shadow under an element ({@link El#shadow}), as one layer of CSS's {@code box-shadow}: the
 * element's rounded rectangle moved by {@code x, y}, grown by {@code spread} on every side and
 * blurred with a Gaussian whose standard deviation is half of {@code blur}. All in logical pixels.
 *
 * @param x the offset to the right
 * @param y the offset down
 * @param blur the blur radius, 0 or more; 0 for a sharp edge
 * @param spread how far the shape grows on every side before the blur, or shrinks if negative
 * @param color the colour as 0xAARRGGBB, usually black with an alpha well below 1
 */
public record Shadow(float x, float y, float blur, float spread, int color) {

  public Shadow {
    if (!(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(spread))) {
      throw new IllegalArgumentException(
          "A shadow's offset and spread must be finite, were " + x + ", " + y + " and " + spread);
    }
    if (!(blur >= 0 && blur < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("A shadow's blur must be 0 or more, was " + blur);
    }
  }
}
