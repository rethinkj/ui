package com.rethinkj.ui.layout;

import com.rethinkj.ui.Len;

/**
 * Arithmetic on floats in which NaN stands for "not set", and on available sizes, which are pixels
 * or one of the infinities in {@link com.rethinkj.ui.Measure}.
 */
final class Maybe {

  private Maybe() {}

  static boolean some(float value) {
    return !Float.isNaN(value);
  }

  static float or(float value, float fallback) {
    return Float.isNaN(value) ? fallback : value;
  }

  // In min, max, clamp, the limits are optional; a value that is not set stays that way.

  static float min(float value, float limit) {
    return Float.isNaN(limit) ? value : Math.min(value, limit);
  }

  static float max(float value, float limit) {
    return Float.isNaN(limit) ? value : Math.max(value, limit);
  }

  /** The minimum wins over the maximum, as in CSS. */
  static float clamp(float value, float min, float max) {
    return max(min(value, max), min);
  }

  /** Pixels, or NaN for auto and for a percentage of a size that is not set. */
  static float resolve(Len len, float basis) {
    return switch (len.kind()) {
      case AUTO -> Float.NaN;
      case PX -> len.value();
      case PERCENT -> basis * len.value();
    };
  }

  static float resolveOrZero(Len len, float basis) {
    return or(resolve(len, basis), 0);
  }

  /**
   * One axis of a size property: resolved, or taken through the aspect ratio from the other axis
   * when only that one is set, plus the padding and border that {@code box-sizing: content-box}
   * leaves out of the property.
   *
   * @param ratio width divided by height, NaN to not transfer
   */
  static float size(
      Len len, Len other, float basis, float otherBasis, float ratio, boolean isWidth, float box) {
    float value = resolve(len, basis);
    if (Float.isNaN(value)) {
      float across = resolve(other, otherBasis);
      value = isWidth ? across * ratio : across / ratio;
    }
    return value + box;
  }

  // Available sizes

  static boolean definite(float available) {
    return !Float.isInfinite(available);
  }

  /** The available size in pixels, or NaN when it is min-content or max-content. */
  static float pixels(float available) {
    return Float.isInfinite(available) ? Float.NaN : available;
  }

  static float availableMax(float available, float floor) {
    return definite(available) ? Math.max(available, floor) : available;
  }

  static float availableClamp(float available, float min, float max) {
    return definite(available) ? clamp(available, min, max) : available;
  }
}
