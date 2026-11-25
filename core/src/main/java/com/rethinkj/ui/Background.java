package com.rethinkj.ui;

import java.util.Objects;

/**
 * A background painted over an element's {@link El#fill} ({@link El#background(Background)}): a
 * two-color linear or elliptical radial gradient, or an {@link Image} ({@link #image}), where the
 * fill shows, within the inner edge of the element's border and its rounded corners, under its
 * {@link El#image}, segments, text and children.
 *
 * <p>For gradients, positions and radii are {@link Len#px} in logical pixels or {@link
 * Len#percent} of the element's padding box (inside its border), where {@code 0} is the top-left
 * corner and {@code Len.percent(1)} is the full width or height. Progress {@code t} from {@code 0}
 * to {@code 1} is eased with smoothstep {@code t * t * (3 - 2 * t)}, and the two colors are mixed
 * premultiplied in linear light, with the element's opacity applied.
 *
 * <p>Text drawn over a background does not use the element's flat fill as its luminance hint and
 * gets the glyph correction for an unknown backdrop unless {@link El#textBackground} names one.
 */
public sealed interface Background
    permits Background.Linear, Background.Radial, Background.Picture {

  /**
   * A linear gradient from {@code (x1, y1)} in {@code from} to {@code (x2, y2)} in {@code to}.
   *
   * <p>Each pixel projects orthogonally onto the line from {@code (x1, y1)} to {@code (x2, y2)},
   * clamped to {@code [0, 1]} and eased with smoothstep. If both points coincide, the whole area
   * is painted with {@code from}.
   *
   * @param x1 horizontal start position in pixels or percent of the padding box
   * @param y1 vertical start position in pixels or percent of the padding box
   * @param x2 horizontal end position in pixels or percent of the padding box
   * @param y2 vertical end position in pixels or percent of the padding box
   * @param from the start color as 0xAARRGGBB
   * @param to the end color as 0xAARRGGBB
   */
  static Background linear(Len x1, Len y1, Len x2, Len y2, int from, int to) {
    return new Linear(x1, y1, x2, y2, from, to);
  }

  /**
   * An elliptical radial gradient centered at {@code (x, y)} with horizontal radius {@code radiusX}
   * and vertical radius {@code radiusY}, fading from {@code inner} at the center to {@code outer}
   * at and beyond the ellipse.
   *
   * <p>The elliptical distance {@code hypot((px - x) / radiusX, (py - y) / radiusY)} is clamped to
   * {@code [0, 1]} and eased with smoothstep, so the fade reaches {@code outer} smoothly with no
   * visible rim.
   *
   * @param x horizontal center position in pixels or percent of the padding box
   * @param y vertical center position in pixels or percent of the padding box
   * @param radiusX horizontal radius in positive pixels or percent of the padding box width
   * @param radiusY vertical radius in positive pixels or percent of the padding box height
   * @param inner the center color as 0xAARRGGBB
   * @param outer the rim color as 0xAARRGGBB
   */
  static Background radial(Len x, Len y, Len radiusX, Len radiusY, int inner, int outer) {
    return new Radial(x, y, radiusX, radiusY, inner, outer);
  }

  /**
   * An image painted behind an element's content, centered in its padding box (inside its border)
   * and fitted with {@code fit}. Transparent pixels and parts the fit leaves uncovered show the
   * element's {@link El#fill}.
   *
   * <p>Unlike {@link El#image}, an image background has no intrinsic size and no effect on layout
   * or hit testing. It is clipped to the padding box and the border's inner corners, with the
   * element's opacity applied, and text drawn over it does not use the flat fill as its luminance
   * hint unless {@link El#textBackground} names one.
   *
   * @param image the image to paint
   * @param fit how the image fits the element's padding box
   */
  static Background image(Image image, Image.Fit fit) {
    return new Picture(image, fit);
  }

  /**
   * A two-color linear gradient ({@link #linear}).
   *
   * @param x1 horizontal start position in pixels or percent of the padding box
   * @param y1 vertical start position in pixels or percent of the padding box
   * @param x2 horizontal end position in pixels or percent of the padding box
   * @param y2 vertical end position in pixels or percent of the padding box
   * @param from the start color as 0xAARRGGBB
   * @param to the end color as 0xAARRGGBB
   */
  record Linear(Len x1, Len y1, Len x2, Len y2, int from, int to) implements Background {

    public Linear {
      position("x1", x1);
      position("y1", y1);
      position("x2", x2);
      position("y2", y2);
    }
  }

  /**
   * A two-color elliptical radial gradient ({@link #radial}).
   *
   * @param x horizontal center position in pixels or percent of the padding box
   * @param y vertical center position in pixels or percent of the padding box
   * @param radiusX horizontal radius in positive pixels or percent of the padding box width
   * @param radiusY vertical radius in positive pixels or percent of the padding box height
   * @param inner the center color as 0xAARRGGBB
   * @param outer the rim color as 0xAARRGGBB
   */
  record Radial(Len x, Len y, Len radiusX, Len radiusY, int inner, int outer)
      implements Background {

    public Radial {
      position("x", x);
      position("y", y);
      radius("radiusX", radiusX);
      radius("radiusY", radiusY);
    }
  }

  /**
   * An image background ({@link #image}).
   *
   * @param image the image to paint
   * @param fit how the image fits the element's padding box
   */
  record Picture(Image image, Image.Fit fit) implements Background {

    public Picture {
      Objects.requireNonNull(image, "image");
      Objects.requireNonNull(fit, "fit");
    }
  }

  private static void position(String name, Len len) {
    Objects.requireNonNull(len, name);
    if ((len.kind() != Len.Kind.PX && len.kind() != Len.Kind.PERCENT)
        || !Float.isFinite(len.value())) {
      throw new IllegalArgumentException(
          "A background's " + name + " must be finite pixels or a percent, was " + len);
    }
  }

  private static void radius(String name, Len len) {
    Objects.requireNonNull(len, name);
    if ((len.kind() != Len.Kind.PX && len.kind() != Len.Kind.PERCENT)
        || !(len.value() > 0 && len.value() < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "A background's " + name + " must be positive pixels or a percent, was " + len);
    }
  }
}
