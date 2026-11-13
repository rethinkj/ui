package com.rethinkj.ui.text;

import java.util.Objects;

/**
 * How glyphs are rasterised and blended: the hinting, and the exponents of the coverage correction
 * that makes text blended in linear light look as heavy as the platform's own text.
 *
 * <p>Glyphs are greyscale bitmaps of coverage {@code a} from 0 to 1, blended in linear light.
 * Before blending, the coverage of text darker than its background becomes {@code 1 - (1 -
 * a)^darkGamma}, which thickens it, and that of text lighter than its background {@code
 * a^lightGamma}, which thins it. Without the correction, dark text on a light background looks too
 * thin and light text on a dark background too heavy.
 *
 * <p>{@link #platform} is the default of {@link com.rethinkj.ui.App} and {@link
 * com.rethinkj.ui.Headless}; tests pin one of the constants so that their images do not depend on
 * the machine.
 *
 * @param hinting how outlines are fitted to the pixel grid
 * @param darkGamma the exponent for text darker than its background, 1 or more
 * @param lightGamma the exponent for text lighter than its background, 1 or more
 */
public record TextRendering(Hinting hinting, float darkGamma, float lightGamma) {

  /** How outlines are fitted to the pixel grid before they are rasterised. */
  public enum Hinting {
    /**
     * FreeType's auto-hinter, vertically only: stems and heights snap to whole pixels, widths and
     * shapes stay as designed. What desktops on Linux do by default.
     */
    LIGHT,
    /**
     * The font's own TrueType instructions, run by FreeType's v40 interpreter, which, like
     * DirectWrite, applies them vertically only. Fonts without instructions get the auto-hinter.
     */
    NATIVE
  }

  /** Light auto-hinting and 1.8 for both exponents, close to text on Linux desktops. */
  public static final TextRendering LINUX = new TextRendering(Hinting.LIGHT, 1.8f, 1.8f);

  /** The font's hints, 1.8 for dark text and 1.4 for light text, close to DirectWrite. */
  public static final TextRendering WINDOWS = new TextRendering(Hinting.NATIVE, 1.8f, 1.4f);

  public TextRendering {
    Objects.requireNonNull(hinting, "hinting");
    if (!(darkGamma >= 1 && darkGamma < Float.POSITIVE_INFINITY)
        || !(lightGamma >= 1 && lightGamma < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "the exponents must be finite and 1 or more, were " + darkGamma + " and " + lightGamma);
    }
  }

  /** {@link #WINDOWS} on Windows, {@link #LINUX} everywhere else. */
  public static TextRendering platform() {
    return System.getProperty("os.name", "").startsWith("Windows") ? WINDOWS : LINUX;
  }
}
