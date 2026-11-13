package com.rethinkj.ui;

/**
 * Colours as the rest of the library has them, one {@code int} each, 0xAARRGGBB in 8-bit sRGB with
 * straight alpha: how to write one without counting hex digits, read its channels, and the colour
 * arithmetic the library does in one place.
 *
 * <pre>{@code
 * int blue = Colors.rgb(0x3D6FE6);                  // 0xFF3D6FE6
 * int selection = Colors.alpha(blue, 0.4f);         // 0x663D6FE6
 * int hovered = Colors.over(panel, selection);      // as the renderer blends it
 * double ratio = Colors.contrast(Colors.WHITE, blue); // 4.55, WCAG's ratio
 * }</pre>
 *
 * <p>The formulas: an sRGB channel {@code c} from 0 to 1 is {@code c / 12.92} in linear light up to
 * 0.04045 and {@code ((c + 0.055) / 1.055)^2.4} above, and back {@code 12.92 l} up to 0.0031308 and
 * {@code 1.055 l^(1/2.4) - 0.055} above ({@link #linear}, {@link #srgb}). Mixing happens in linear
 * light, as the renderers blend, so {@link #over} gives the colour a translucent fill drawn over an
 * opaque one shows. Relative luminance is WCAG's {@code 0.2126 R + 0.7152 G + 0.0722 B} of the
 * linear channels, the contrast ratio {@code (L1 + 0.05) / (L2 + 0.05)} with the lighter first,
 * and lightness CIELAB's L*: {@code 116 Y^(1/3) - 16}, or {@code Y * 24389 / 27} up to {@code Y =
 * 216 / 24389}. Powers and roots are {@link StrictMath}'s, so every platform gets the same bits.
 */
public final class Colors {

  /** Opaque black. */
  public static final int BLACK = 0xFF000000;

  /** Opaque white. */
  public static final int WHITE = 0xFFFFFFFF;

  /** Nothing: black at alpha 0. */
  public static final int TRANSPARENT = 0;

  private Colors() {}

  /** The opaque colour of 24 bits, 0xRRGGBB, as {@code rgb(0x3D6FE6)}. */
  public static int rgb(int rgb) {
    if ((rgb & 0xFF000000) != 0) {
      throw new IllegalArgumentException(String.format(
          "rgb takes 24 bits, 0xRRGGBB; 0x%08X has an alpha: write it as it is or with alpha()",
          rgb));
    }
    return 0xFF000000 | rgb;
  }

  /** The opaque colour of these channels, each from 0 to 255. */
  public static int rgb(int red, int green, int blue) {
    return argb(255, red, green, blue);
  }

  /** The colour of these channels, each from 0 to 255. */
  public static int argb(int alpha, int red, int green, int blue) {
    return channel("alpha", alpha) << 24 | channel("red", red) << 16
        | channel("green", green) << 8 | channel("blue", blue);
  }

  /**
   * The colour with an alpha of {@code share}, from 0 to 1, in place of its own:
   * {@code round(share * 255)}, halves up, so 0.4 is 0x66, 0.35 is 0x59 and 0.3 is 0x4D.
   */
  public static int alpha(int argb, float share) {
    if (!(share >= 0 && share <= 1)) {
      throw new IllegalArgumentException("an alpha from 0 to 1, not " + share);
    }
    return (int) Math.round((double) share * 255) << 24 | argb & 0xFFFFFF;
  }

  /** The alpha of a colour, from 0 (transparent) to 255 (opaque). */
  public static int alpha(int argb) {
    return argb >>> 24;
  }

  /** The red channel, from 0 to 255. */
  public static int red(int argb) {
    return argb >>> 16 & 0xFF;
  }

  /** The green channel, from 0 to 255. */
  public static int green(int argb) {
    return argb >>> 8 & 0xFF;
  }

  /** The blue channel, from 0 to 255. */
  public static int blue(int argb) {
    return argb & 0xFF;
  }

  /**
   * The colour written as {@code #RRGGBB} (opaque) or {@code #AARRGGBB}, as {@link #toHex} writes
   * it; the {@code #} may be left out, case does not count.
   *
   * @throws IllegalArgumentException for anything else, with what a colour looks like
   */
  public static int hex(String text) {
    String digits = text.startsWith("#") ? text.substring(1) : text;
    if ((digits.length() == 6 || digits.length() == 8)
        && digits.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
      int value = Integer.parseUnsignedInt(digits, 16);
      return digits.length() == 6 ? 0xFF000000 | value : value;
    }
    throw new IllegalArgumentException("\"" + text
        + "\" is no colour: write #RRGGBB, as #3D6FE6, or #AARRGGBB, as #663D6FE6");
  }

  /** The colour as {@code #RRGGBB} when it is opaque, else as {@code #AARRGGBB}, in capitals. */
  public static String toHex(int argb) {
    return alpha(argb) == 255
        ? String.format("#%06X", argb & 0xFFFFFF)
        : String.format("#%08X", argb);
  }

  /**
   * The opaque colour {@code overlay} gives when laid over the opaque colour {@code base}, mixed in
   * linear light as the renderer blends a translucent fill: each channel {@code srgb(linear(o) a +
   * linear(b) (1 - a))}, rounded to the nearest of 256. The base's alpha is ignored.
   */
  public static int over(int base, int overlay) {
    double alpha = alpha(overlay) / 255.0;
    int argb = 0xFF;
    for (int shift = 16; shift >= 0; shift -= 8) {
      double mixed = linear((overlay >>> shift & 0xFF) / 255.0) * alpha
          + linear((base >>> shift & 0xFF) / 255.0) * (1 - alpha);
      argb = argb << 8 | (int) Math.round(srgb(mixed) * 255);
    }
    return argb;
  }

  /**
   * The colour {@code t} of the way from {@code a} to {@code b}, from 0 to 1, mixed in linear
   * light as {@link #over} does, alpha too: {@code mix(a, b, t)} of two opaque colours is {@code
   * over(a, alpha(b, t))} without rounding {@code t} to 8 bits.
   */
  public static int mix(int a, int b, double t) {
    if (!(t >= 0 && t <= 1)) {
      throw new IllegalArgumentException("a share from 0 to 1, not " + t);
    }
    int alpha = (int) Math.round(alpha(a) + (alpha(b) - alpha(a)) * t);
    int argb = alpha;
    for (int shift = 16; shift >= 0; shift -= 8) {
      double mixed = linear((a >>> shift & 0xFF) / 255.0) * (1 - t)
          + linear((b >>> shift & 0xFF) / 255.0) * t;
      argb = argb << 8 | (int) Math.round(srgb(mixed) * 255);
    }
    return argb;
  }

  /** WCAG's relative luminance, from 0 for black to 1 for white; the alpha is ignored. */
  public static double luminance(int argb) {
    return 0.2126 * linear(red(argb) / 255.0) + 0.7152 * linear(green(argb) / 255.0)
        + 0.0722 * linear(blue(argb) / 255.0);
  }

  /**
   * WCAG's contrast ratio between two colours, from 1 to 21, the same either way round; alphas are
   * ignored, so lay a translucent colour {@link #over} what it lies on first.
   */
  public static double contrast(int a, int b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  /** CIELAB's lightness L*, from 0 for black to 100 for white; the alpha is ignored. */
  public static double lightness(int argb) {
    double y = luminance(argb);
    return y > 216.0 / 24389 ? 116 * StrictMath.cbrt(y) - 16 : y * 24389 / 27;
  }

  /** An sRGB value from 0 to 1 in linear light, from 0 to 1. */
  public static double linear(double srgb) {
    return srgb <= 0.04045 ? srgb / 12.92 : StrictMath.pow((srgb + 0.055) / 1.055, 2.4);
  }

  /** A value in linear light from 0 to 1 as sRGB, from 0 to 1, not rounded. */
  public static double srgb(double linear) {
    return linear <= 0.0031308
        ? linear * 12.92
        : 1.055 * StrictMath.pow(linear, 1 / 2.4) - 0.055;
  }

  private static int channel(String name, int value) {
    if (value < 0 || value > 255) {
      throw new IllegalArgumentException(name + " from 0 to 255, not " + value);
    }
    return value;
  }
}
