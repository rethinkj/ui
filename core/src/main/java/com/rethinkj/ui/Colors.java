package com.rethinkj.ui;

/**
 * Colors as the rest of the library has them, one {@code int} each, 0xAARRGGBB in 8-bit sRGB with
 * straight alpha: how to write one without counting hex digits, read its channels, and the color
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
 * light, as the renderers blend, so {@link #over} gives the color a translucent fill drawn over an
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

  /** The opaque color of 24 bits, 0xRRGGBB, as {@code rgb(0x3D6FE6)}. */
  public static int rgb(int rgb) {
    if ((rgb & 0xFF000000) != 0) {
      throw new IllegalArgumentException(String.format(
          "rgb takes 24 bits, 0xRRGGBB; 0x%08X has an alpha: write it as it is or with alpha()",
          rgb));
    }
    return 0xFF000000 | rgb;
  }

  /** The opaque color of these channels, each from 0 to 255. */
  public static int rgb(int red, int green, int blue) {
    return argb(255, red, green, blue);
  }

  /** The color of these channels, each from 0 to 255. */
  public static int argb(int alpha, int red, int green, int blue) {
    return channel("alpha", alpha) << 24
        | channel("red", red) << 16
        | channel("green", green) << 8
        | channel("blue", blue);
  }

  /**
   * The color with an alpha of {@code share}, from 0 to 1, in place of its own:
   * {@code round(share * 255)}, halves up, so 0.4 is 0x66, 0.35 is 0x59 and 0.3 is 0x4D.
   */
  public static int alpha(int argb, float share) {
    if (!(share >= 0 && share <= 1)) {
      throw new IllegalArgumentException("an alpha from 0 to 1, not " + share);
    }
    return (int) Math.round((double) share * 255) << 24 | argb & 0xFFFFFF;
  }

  /** The alpha of a color, from 0 (transparent) to 255 (opaque). */
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
   * The color written as {@code #RRGGBB} (opaque) or {@code #AARRGGBB}, as {@link #toHex} writes
   * it; the {@code #} may be left out, case does not count.
   *
   * @throws IllegalArgumentException for anything else, with what a color looks like
   */
  public static int hex(String text) {
    String digits = text.startsWith("#") ? text.substring(1) : text;
    if ((digits.length() == 6 || digits.length() == 8)
        && digits.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
      int value = Integer.parseUnsignedInt(digits, 16);
      return digits.length() == 6 ? 0xFF000000 | value : value;
    }
    throw new IllegalArgumentException(
        "\"" + text + "\" is no color: write #RRGGBB, as #3D6FE6, or #AARRGGBB, as #663D6FE6");
  }

  /** The color as {@code #RRGGBB} when it is opaque, else as {@code #AARRGGBB}, in capitals. */
  public static String toHex(int argb) {
    return alpha(argb) == 255
        ? String.format("#%06X", argb & 0xFFFFFF)
        : String.format("#%08X", argb);
  }

  /**
   * The opaque color {@code overlay} gives when laid over the opaque color {@code base}, mixed in
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
   * The color {@code amount} of the way from {@code from} to {@code to}, from 0 to 1, mixed in
   * linear light as {@link #over} does, alpha too: {@code mix(from, to, amount)} of two opaque
   * colors is {@code over(from, alpha(to, amount))} without rounding {@code amount} to 8 bits.
   */
  public static int mix(int from, int to, double amount) {
    if (!(amount >= 0 && amount <= 1)) {
      throw new IllegalArgumentException("a share from 0 to 1, not " + amount);
    }
    int alpha = (int) Math.round(alpha(from) + (alpha(to) - alpha(from)) * amount);
    int argb = alpha;
    for (int shift = 16; shift >= 0; shift -= 8) {
      double mixed = linear((from >>> shift & 0xFF) / 255.0) * (1 - amount)
          + linear((to >>> shift & 0xFF) / 255.0) * amount;
      argb = argb << 8 | (int) Math.round(srgb(mixed) * 255);
    }
    return argb;
  }

  /** WCAG's relative luminance, from 0 for black to 1 for white; the alpha is ignored. */
  public static double luminance(int argb) {
    return 0.2126 * linear(red(argb) / 255.0)
        + 0.7152 * linear(green(argb) / 255.0)
        + 0.0722 * linear(blue(argb) / 255.0);
  }

  /**
   * WCAG's contrast ratio between two colors, from 1 to 21, the same either way round; alphas are
   * ignored, so lay a translucent color {@link #over} what it lies on first.
   */
  public static double contrast(int first, int second) {
    double la = luminance(first);
    double lb = luminance(second);
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
    return linear <= 0.0031308 ? linear * 12.92 : 1.055 * StrictMath.pow(linear, 1 / 2.4) - 0.055;
  }

  private static int channel(String name, int value) {
    if (value < 0 || value > 255) {
      throw new IllegalArgumentException(name + " from 0 to 255, not " + value);
    }
    return value;
  }
}
