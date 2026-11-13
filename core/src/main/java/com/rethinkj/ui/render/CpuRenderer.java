package com.rethinkj.ui.render;

import com.rethinkj.ui.text.Atlas;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws a {@link Scene} on the CPU. This is the reference the GPU renderer is checked against, so
 * its formulas are written to be obviously right, each as the shader has it, and its output is the
 * same bit for bit on every platform: plain IEEE arithmetic, and {@code StrictMath} wherever
 * {@code Math} is allowed to differ.
 *
 * <p>It is fast by not computing what is already known, never by computing it another way, so
 * that it draws the same bits as a loop that evaluates every formula at every pixel of every
 * instance: the pixels a rounded rectangle or a clip covers whole are found without evaluating
 * it at each ({@link Box}); a glyph's corrected coverage is computed once per texel value, what
 * a shadow's blur takes from x once per column and from y once per row, and the encoding of a
 * pixel to sRGB once per value of its floats. {@code CpuRendererEquivalenceTest} holds it to that
 * loop.
 */
public final class CpuRenderer {

  private CpuRenderer() {}

  /**
   * @param width the width of the image in physical pixels
   * @param height the height of the image in physical pixels
   * @param scale physical pixels per logical pixel
   */
  public static Pixels render(Scene scene, int width, int height, float scale) {
    return encode(renderLinear(scene, width, height, scale), width, height);
  }

  /**
   * The image before it is encoded: linear light, premultiplied alpha, four floats per pixel in the
   * order red, green, blue, alpha. What an sRGB render target holds is this, encoded channel by
   * channel.
   */
  static float[] renderLinear(Scene scene, int width, int height, float scale) {
    return renderLinear(scene, width, height, scale, null);
  }

  /**
   * Sees every blend before it happens, for the test that bounds how far the GPU's blending may
   * lie from this renderer's. It changes nothing.
   */
  interface Blends {
    /**
     * @param frame the image so far, with the pixel as it is before the blend
     * @param at the index of the pixel's red value in {@code frame}
     * @param alpha the alpha of the source, which keeps {@code 1 - alpha} of the pixel
     */
    void blend(float[] frame, int at, double alpha);
  }

  /** As {@link #renderLinear(Scene, int, int, float)}, telling {@code blends} of each blend. */
  static float[] renderLinear(Scene scene, int width, int height, float scale, Blends blends) {
    // Starts fully transparent.
    float[] frame = new float[width * height * 4];
    Scratch scratch = new Scratch(width);
    for (int i = 0; i < scene.count(); i++) {
      int kind = scene.getInt(i, Scene.KIND);
      if (kind == Scene.GLYPH) {
        glyph(scene, i, frame, width, height, scale, blends, scratch);
      } else if (kind == Scene.SHADOW) {
        shadow(scene, i, frame, width, height, scale, blends, scratch);
      } else {
        rect(scene, i, frame, width, height, scale, blends, scratch);
      }
    }
    return frame;
  }

  /** Room for one row of values per pixel, and the glyph corrections of one frame. */
  private static final class Scratch {
    final double[] coverage;
    final double[] fillShare;
    final double[] x;
    final double[] wide;
    final double[] narrow;
    final double[][] squareX;
    final Map<Correction, double[]> corrections = new HashMap<>();

    Scratch(int width) {
      coverage = new double[width];
      fillShare = new double[width];
      x = new double[width];
      wide = new double[width];
      narrow = new double[width];
      squareX = new double[4][width];
    }
  }

  private static void rect(Scene scene, int i, float[] frame, int width, int height, double scale,
      Blends blends, Scratch scratch) {
    double w = scene.get(i, Scene.WIDTH);
    double h = scene.get(i, Scene.HEIGHT);
    if (!(w > 0 && h > 0)) {
      return;
    }
    double halfW = w / 2;
    double halfH = h / 2;
    double centerX = scene.get(i, Scene.X) + halfW;
    double centerY = scene.get(i, Scene.Y) + halfH;
    double[] radii = radii(scene, i, halfW, halfH);
    double[] borders = borders(scene, i);
    boolean border = borders[0] > 0 || borders[1] > 0 || borders[2] > 0 || borders[3] > 0;
    Box shape = new Box(centerX, centerY, halfW, halfH, radii, radii);
    Box inner = border ? inner(centerX, centerY, halfW, halfH, radii, borders, scale) : null;

    // One extra pixel on each side: the smoothed edge reaches half a pixel beyond the shape.
    int left = Math.max((int) Math.floor((centerX - halfW) * scale) - 1, 0);
    int top = Math.max((int) Math.floor((centerY - halfH) * scale) - 1, 0);
    int right = Math.min((int) Math.ceil((centerX + halfW) * scale) + 1, width);
    int bottom = Math.min((int) Math.ceil((centerY + halfH) * scale) + 1, height);

    // The clip's coverage multiplies the instance's. Entry 0 covers everything, so it is skipped.
    Box clip = Box.clip(scene, scene.clipOf(i));
    if (clip != null) {
      left = Math.max(left, (int) Math.floor((clip.centerX - clip.halfW) * scale) - 1);
      top = Math.max(top, (int) Math.floor((clip.centerY - clip.halfH) * scale) - 1);
      right = Math.min(right, (int) Math.ceil((clip.centerX + clip.halfW) * scale) + 1);
      bottom = Math.min(bottom, (int) Math.ceil((clip.centerY + clip.halfH) * scale) + 1);
    }
    if (left >= right || top >= bottom) {
      return; // also where a bound has overflowed, so right - 1 below cannot
    }
    boolean clipWhole = clip != null && clip.coversWhole(left, top, right - 1, bottom - 1, scale);
    double[] fill = colour(scene, i, Scene.FILL);
    double[] edge = colour(scene, i, Scene.BORDER);
    // The source of a pixel covered whole: as in blend below, with coverage 1 and a fill share
    // of 0, in the border, or 1.
    double[][] whole = new double[2][4];
    for (int share = 0; share <= 1; share++) {
      for (int c = 0; c < 4; c++) {
        whole[share][c] = mix(fill[c], edge[c], share) * 1;
      }
    }
    double[] coverages = scratch.coverage;
    double[] fillShares = scratch.fillShare;

    // Elliptic corners of the fill are measured at every pixel: Box's argument is for circles.
    boolean runs = inner == null || inner.circular();
    for (int py = top; py < bottom; py++) {
      double y = (py + 0.5) / scale;
      // A row that the fill reaches nowhere, as along a border at the top or the bottom, has
      // runs of pixels in the border colour; any other its runs in the fill.
      int share = border && (inner == null || inner.missesRow(y, left, right, scale)) ? 0 : 1;
      // Measures from the left until a pixel is covered whole, by the shape and the clip, with
      // that fill share, then from the right until one is: the pixels between are covered whole
      // too (see Box), and only the measured ones need the formulas.
      int first = left;
      while (first < right && (!measure(first, y, scale, shape, border, inner, clip, clipWhole,
          coverages, fillShares, share) || !runs)) {
        first++;
      }
      int last = right - 1;
      while (last > first && !measure(last, y, scale, shape, border, inner, clip, clipWhole,
          coverages, fillShares, share)) {
        last--;
      }
      int row = py * width;
      for (int px = left; px < first; px++) {
        blend(frame, (row + px) * 4, fill, edge, coverages[px], fillShares[px], blends);
      }
      double[] source = whole[share];
      for (int px = first; px <= last; px++) {
        blend(frame, (row + px) * 4, source[0], source[1], source[2], source[3], blends);
      }
      for (int px = Math.max(last + 1, first); px < right; px++) {
        blend(frame, (row + px) * 4, fill, edge, coverages[px], fillShares[px], blends);
      }
    }
  }

  /**
   * The radii of the instance's corners, top left, top right, bottom right, bottom left, each
   * clamped to half the shorter side.
   */
  private static double[] radii(Scene scene, int i, double halfW, double halfH) {
    double most = Math.min(halfW, halfH);
    return new double[] {Math.clamp(scene.get(i, Scene.RADIUS), 0, most),
        Math.clamp(scene.get(i, Scene.RADIUS_TOP_RIGHT), 0, most),
        Math.clamp(scene.get(i, Scene.RADIUS_BOTTOM_RIGHT), 0, most),
        Math.clamp(scene.get(i, Scene.RADIUS_BOTTOM_LEFT), 0, most)};
  }

  /** The widths of the instance's borders, top, right, bottom, left, 0 or more. */
  private static double[] borders(Scene scene, int i) {
    return new double[] {Math.max(scene.get(i, Scene.BORDER_WIDTH), 0),
        Math.max(scene.get(i, Scene.BORDER_RIGHT), 0),
        Math.max(scene.get(i, Scene.BORDER_BOTTOM), 0),
        Math.max(scene.get(i, Scene.BORDER_LEFT), 0)};
  }

  /**
   * Where a rectangle with a border shows its fill: moved in by each side's border, or out by a
   * physical pixel on a side without one, with corners whose radii are the outer ones less the
   * borders on either side, as {@link Scene} says; null when nothing is left. A corner between
   * two sides without a border keeps its radius, so square corners stay square.
   */
  private static Box inner(double centerX, double centerY, double halfW, double halfH,
      double[] radii, double[] borders, double scale) {
    double[] in = new double[4];
    for (int s = 0; s < 4; s++) {
      in[s] = borders[s] > 0 ? borders[s] : -1 / scale;
    }
    double innerHalfW = halfW - (in[3] + in[1]) / 2;
    double innerHalfH = halfH - (in[0] + in[2]) / 2;
    if (!(innerHalfW > 0 && innerHalfH > 0)) {
      return null;
    }
    double[] across = new double[4];
    double[] down = new double[4];
    for (int c = 0; c < 4; c++) {
      across[c] = Math.min(Math.max(radii[c] - borders[c == 0 || c == 3 ? 3 : 1], 0),
          innerHalfW);
      down[c] = Math.min(Math.max(radii[c] - borders[c < 2 ? 0 : 2], 0), innerHalfH);
    }
    return new Box(centerX + (in[3] - in[1]) / 2, centerY + (in[0] - in[2]) / 2, innerHalfW,
        innerHalfH, across, down);
  }

  /** Blends the rectangle's colour over a pixel it covers this much of. */
  private static void blend(float[] frame, int at, double[] fill, double[] edge, double coverage,
      double fillShare, Blends blends) {
    if (coverage <= 0) {
      return;
    }
    blend(frame, at, mix(fill[0], edge[0], fillShare) * coverage,
        mix(fill[1], edge[1], fillShare) * coverage, mix(fill[2], edge[2], fillShare) * coverage,
        mix(fill[3], edge[3], fillShare) * coverage, blends);
  }

  /**
   * The coverage and the fill share of pixel {@code px} in the row whose centres lie at {@code y},
   * into {@code coverages[px]} and {@code fillShares[px]}; whether the coverage is exactly 1 and
   * the fill share exactly {@code share}.
   */
  private static boolean measure(int px, double y, double scale, Box shape, boolean border,
      Box inner, Box clip, boolean clipWhole, double[] coverages, double[] fillShares,
      int share) {
    // Distances are measured from the pixel's centre, in logical pixels.
    double x = (px + 0.5) / scale;
    double coverage = shape.coverage(x, y, scale);
    if (clip != null) {
      coverage *= clipWhole ? 1 : clip.coverage(x, y, scale);
    }
    double fillShare = 1;
    if (border) {
      fillShare = inner != null ? inner.coverage(x, y, scale) : 0;
    }
    coverages[px] = coverage;
    fillShares[px] = fillShare;
    return coverage == 1 && fillShare == share && scale > 0;
  }

  /** Draws a glyph's bitmap one texel to one physical pixel, with its coverage corrected. */
  private static void glyph(Scene scene, int i, float[] frame, int width, int height,
      double scale, Blends blends, Scratch scratch) {
    int atlas = scene.getInt(i, Scene.ATLAS);
    int size = scene.getInt(i, Scene.ATLAS_SIZE);
    Atlas.Page page = scene.pages().get(scene.getInt(i, Scene.PAGE));
    int left = (int) Math.round(scene.get(i, Scene.X) * scale);
    int top = (int) Math.round(scene.get(i, Scene.Y) * scale);
    double[] corrected = corrections(scene, i, scratch);
    double[] colour = colour(scene, i, Scene.COLOUR);
    Box clip = Box.clip(scene, scene.getInt(i, Scene.CLIP));
    // The pixels the loop below draws into; left + tx never wraps into the image.
    boolean clipWhole = clip != null && clip.coversWhole(
        Math.max(left, 0), Math.max(top, 0),
        (int) Math.min((long) left + (size & 0xFFFF) - 1, width - 1),
        (int) Math.min((long) top + (size >>> 16) - 1, height - 1), scale);
    for (int ty = 0; ty < size >>> 16; ty++) {
      int py = top + ty;
      for (int tx = 0; tx < (size & 0xFFFF); tx++) {
        int px = left + tx;
        if (px < 0 || py < 0 || px >= width || py >= height) {
          continue;
        }
        int a = page.coverage((atlas & 0xFFFF) + tx, (atlas >>> 16) + ty);
        if (a == 0) {
          continue;
        }
        double coverage = corrected[a];
        if (clip != null) {
          coverage *= clipWhole ? 1 : clip.coverage((px + 0.5) / scale, (py + 0.5) / scale, scale);
        }
        if (coverage <= 0) {
          continue;
        }
        blend(frame, (py * width + px) * 4, colour[0] * coverage, colour[1] * coverage,
            colour[2] * coverage, colour[3] * coverage, blends);
      }
    }
  }

  /** A glyph's correction, by the bits of its three floats. */
  private record Correction(int lightShare, int darkGamma, int lightGamma) {}

  /**
   * The corrected coverage of each texel value from 0 to 255 for the glyph's correction, computed
   * once per frame and correction.
   */
  private static double[] corrections(Scene scene, int i, Scratch scratch) {
    float share = scene.get(i, Scene.LIGHT_SHARE);
    float darkGamma = scene.get(i, Scene.DARK_GAMMA);
    float lightGamma = scene.get(i, Scene.LIGHT_GAMMA);
    return scratch.corrections.computeIfAbsent(
        new Correction(Float.floatToRawIntBits(share), Float.floatToRawIntBits(darkGamma),
            Float.floatToRawIntBits(lightGamma)),
        key -> {
          double[] corrected = new double[256];
          for (int a = 1; a < 256; a++) {
            corrected[a] = corrected(a / 255.0, darkGamma, lightGamma, share);
          }
          return corrected;
        });
  }

  /** A texel's coverage {@code a}, from 0 to 1, corrected as {@link Scene} says. */
  private static double corrected(double a, double darkGamma, double lightGamma, double share) {
    double dark = 1 - StrictMath.pow(1 - a, darkGamma);
    double light = StrictMath.pow(a, lightGamma);
    return dark + (light - dark) * share;
  }

  /** Draws a shadow: its shape blurred, over the shape grown by three deviations. */
  private static void shadow(Scene scene, int i, float[] frame, int width, int height,
      double scale, Blends blends, Scratch scratch) {
    double w = scene.get(i, Scene.WIDTH);
    double h = scene.get(i, Scene.HEIGHT);
    if (!(w > 0 && h > 0)) {
      return;
    }
    double halfW = w / 2;
    double halfH = h / 2;
    double centerX = scene.get(i, Scene.X) + halfW;
    double centerY = scene.get(i, Scene.Y) + halfH;
    double[] radii = radii(scene, i, halfW, halfH);
    double radius = radii[0];
    double sigma = deviation(scene.get(i, Scene.SIGMA), scale);
    // The blur reaches three deviations beyond the shape, and one pixel more as for a rectangle.
    // shortcut: what lies beyond, at most 0.14 % of the colour, is cut off, which a light glow on
    // black shows as a faint edge; reach four deviations if glows need it.
    double reachW = halfW + 3 * sigma;
    double reachH = halfH + 3 * sigma;
    int left = Math.max((int) Math.floor((centerX - reachW) * scale) - 1, 0);
    int top = Math.max((int) Math.floor((centerY - reachH) * scale) - 1, 0);
    int right = Math.min((int) Math.ceil((centerX + reachW) * scale) + 1, width);
    int bottom = Math.min((int) Math.ceil((centerY + reachH) * scale) + 1, height);
    int clipIndex = scene.clipOf(i);
    if (clipIndex != 0) {
      double clipX = scene.clipGet(clipIndex, Scene.X);
      double clipY = scene.clipGet(clipIndex, Scene.Y);
      left = Math.max(left, (int) Math.floor(clipX * scale) - 1);
      top = Math.max(top, (int) Math.floor(clipY * scale) - 1);
      right = Math.min(right,
          (int) Math.ceil((clipX + scene.clipGet(clipIndex, Scene.WIDTH)) * scale) + 1);
      bottom = Math.min(bottom,
          (int) Math.ceil((clipY + scene.clipGet(clipIndex, Scene.HEIGHT)) * scale) + 1);
    }
    if (left >= right || top >= bottom) {
      return; // also where a bound has overflowed, so right - 1 below cannot
    }
    Box clip = Box.clip(scene, clipIndex);
    boolean clipWhole = clip != null && clip.coversWhole(left, top, right - 1, bottom - 1, scale);
    double[] colour = colour(scene, i, Scene.FILL);
    if (!(radii[1] == radius && radii[2] == radius && radii[3] == radius)) {
      corners(centerX, centerY, halfW, halfH, radii, sigma, left, top, right, bottom, clip,
          clipWhole, colour, frame, width, scale, blends, scratch);
      return;
    }
    double straightW = halfW - radius;
    double straightH = halfH - radius;
    // The blur across x depends on the column alone, and across y on the row alone.
    double[] xs = scratch.x;
    double[] wide = scratch.wide;
    double[] narrow = scratch.narrow;
    for (int px = left; px < right; px++) {
      xs[px] = (px + 0.5) / scale - centerX;
      wide[px] = across(xs[px], halfW, sigma);
      narrow[px] = across(xs[px], straightW, sigma);
    }
    for (int py = top; py < bottom; py++) {
      double y = (py + 0.5) / scale - centerY;
      double tall = across(y, halfH, sigma);
      double flat = across(y, straightH, sigma);
      double[] lower = radius > 0 ? slices(y - straightH, radius, sigma) : null;
      double[] upper = radius > 0 ? slices(-y - straightH, radius, sigma) : null;
      for (int px = left; px < right; px++) {
        double coverage = shadow(xs[px], straightW, radius, sigma, wide[px], narrow[px], tall,
            flat, lower, upper);
        if (clip != null) {
          coverage *= clipWhole ? 1 : clip.coverage((px + 0.5) / scale, (py + 0.5) / scale, scale);
        }
        if (coverage <= 0) {
          continue;
        }
        blend(frame, (py * width + px) * 4, colour[0] * coverage, colour[1] * coverage,
            colour[2] * coverage, colour[3] * coverage, blends);
      }
    }
  }

  /**
   * Draws a shadow whose corners have different radii: the box, less each rounded corner's square,
   * plus its quarter disc. What depends on x alone is computed once per column, what depends on y
   * once per row.
   */
  private static void corners(double centerX, double centerY, double halfW, double halfH,
      double[] radii, double sigma, int left, int top, int right, int bottom, Box clip,
      boolean clipWhole, double[] colour, float[] frame, int width, double scale, Blends blends,
      Scratch scratch) {
    double[] xs = scratch.x;
    double[] wide = scratch.wide;
    double[][] squareX = scratch.squareX;
    for (int px = left; px < right; px++) {
      xs[px] = (px + 0.5) / scale - centerX;
      wide[px] = across(xs[px], halfW, sigma);
      for (int c = 0; c < 4; c++) {
        double side = c == 1 || c == 2 ? xs[px] : -xs[px];
        squareX[c][px] = below(halfW - side, sigma) - below(halfW - radii[c] - side, sigma);
      }
    }
    double[] squareY = new double[4];
    double[][] slices = new double[4][];
    for (int py = top; py < bottom; py++) {
      double y = (py + 0.5) / scale - centerY;
      double tall = across(y, halfH, sigma);
      for (int c = 0; c < 4; c++) {
        double side = c >= 2 ? y : -y;
        double straightH = halfH - radii[c];
        squareY[c] = below(halfH - side, sigma) - below(straightH - side, sigma);
        slices[c] = radii[c] > 0 ? slices(side - straightH, radii[c], sigma) : null;
      }
      for (int px = left; px < right; px++) {
        double coverage = wide[px] * tall;
        for (int c = 0; c < 4; c++) {
          if (radii[c] > 0) {
            double side = c == 1 || c == 2 ? xs[px] : -xs[px];
            coverage += quarter(side - (halfW - radii[c]), slices[c], radii[c], sigma)
                - squareX[c][px] * squareY[c];
          }
        }
        coverage = Math.min(coverage, 1);
        if (clip != null) {
          coverage *= clipWhole ? 1 : clip.coverage((px + 0.5) / scale, (py + 0.5) / scale, scale);
        }
        if (coverage <= 0) {
          continue;
        }
        blend(frame, (py * width + px) * 4, colour[0] * coverage, colour[1] * coverage,
            colour[2] * coverage, colour[3] * coverage, blends);
      }
    }
  }

  /**
   * As {@link #shadow(double, double, double, double, double, double)} with a radius per corner,
   * top left, top right, bottom right, bottom left, each at most half the shorter side.
   */
  static double shadow(double x, double y, double halfW, double halfH, double[] radii,
      double sigma) {
    if (radii[1] == radii[0] && radii[2] == radii[0] && radii[3] == radii[0]) {
      return shadow(x, y, halfW, halfH, radii[0], sigma);
    }
    double coverage = across(x, halfW, sigma) * across(y, halfH, sigma);
    for (int c = 0; c < 4; c++) {
      double r = radii[c];
      if (r > 0) {
        double sideX = c == 1 || c == 2 ? x : -x;
        double sideY = c >= 2 ? y : -y;
        double straightH = halfH - r;
        coverage += quarter(sideX - (halfW - r), slices(sideY - straightH, r, sigma), r, sigma)
            - (below(halfW - sideX, sigma) - below(halfW - r - sideX, sigma))
            * (below(halfH - sideY, sigma) - below(straightH - sideY, sigma));
      }
    }
    return Math.min(coverage, 1);
  }

  /**
   * A shadow's deviation with the pixel's added: a box one physical pixel wide has the variance
   * 1/12 of a Gaussian.
   */
  static double deviation(double sigma, double scale) {
    return StrictMath.sqrt(sigma * sigma + 1 / (12 * scale * scale));
  }

  /** Slices of a quarter disc in {@link #quarter}. */
  private static final int SLICES = 8;

  /**
   * How much of a Gaussian of deviation {@code sigma} around the point {@code x, y} lies in a
   * rounded rectangle centred on the origin: two crossed boxes, whose blur is exact, and the
   * quarter discs at their corners.
   */
  static double shadow(double x, double y, double halfW, double halfH, double radius,
      double sigma) {
    double straightW = halfW - radius;
    double straightH = halfH - radius;
    return shadow(x, straightW, radius, sigma, across(x, halfW, sigma),
        across(x, straightW, sigma), across(y, halfH, sigma), across(y, straightH, sigma),
        radius > 0 ? slices(y - straightH, radius, sigma) : null,
        radius > 0 ? slices(-y - straightH, radius, sigma) : null);
  }

  /**
   * As {@link #shadow(double, double, double, double, double, double)}, with what depends on x
   * alone given ({@code wide}, {@code narrow}) and what depends on y alone ({@code tall}, {@code
   * flat}, and the {@link #slices} at {@code y - (h - r)} and {@code -y - (h - r)}), so that a
   * renderer computes those once per column and once per row.
   */
  private static double shadow(double x, double straightW, double radius, double sigma,
      double wide, double narrow, double tall, double flat, double[] lower, double[] upper) {
    double coverage = wide * flat + narrow * tall - narrow * flat;
    if (radius > 0) {
      coverage += quarter(x - straightW, lower, radius, sigma)
          + quarter(-x - straightW, lower, radius, sigma)
          + quarter(x - straightW, upper, radius, sigma)
          + quarter(-x - straightW, upper, radius, sigma);
    }
    return Math.min(coverage, 1);
  }

  /** The Gaussian's mass from {@code -half} to {@code half}, seen from {@code t}. */
  private static double across(double t, double half, double sigma) {
    return below(t + half, sigma) - below(t - half, sigma);
  }

  /**
   * The blur of the quarter disc of radius {@code r} at the origin towards {@code +x, +y}, seen
   * from {@code u, v}: within three deviations of {@code v}, in slices of equal height, each the
   * Gaussian's exact mass across the slice times its mass along the disc at the slice's middle.
   * What depends on {@code v} alone comes as its {@link #slices}.
   */
  private static double quarter(double u, double[] slices, double r, double sigma) {
    double reach = 3 * sigma;
    if (slices == null || u < -reach || u > r + reach) {
      return 0;
    }
    double inside = below(-u, sigma);
    double sum = 0;
    for (int i = 0; i < SLICES; i++) {
      sum += (below(slices[2 * i] - u, sigma) - inside) * slices[2 * i + 1];
    }
    return sum;
  }

  /**
   * What {@link #quarter} needs of {@code v}, which it shares with a row of pixels: for each slice,
   * the disc's extent at its middle and the Gaussian's mass across it, {@code (P(top - v) -
   * P(bottom - v))}; or null when no part of the disc's height lies within three deviations.
   */
  private static double[] slices(double v, double r, double sigma) {
    double reach = 3 * sigma;
    double low = Math.max(v - reach, 0);
    double high = Math.min(v + reach, r);
    if (!(high > low)) {
      return null;
    }
    double step = (high - low) / SLICES;
    double before = below(low - v, sigma);
    double[] slices = new double[2 * SLICES];
    for (int i = 1; i <= SLICES; i++) {
      double top = low + step * i;
      double after = below(top - v, sigma);
      double middle = top - step / 2;
      slices[2 * i - 2] = StrictMath.sqrt(Math.max(r * r - middle * middle, 0));
      slices[2 * i - 1] = after - before;
      before = after;
    }
    return slices;
  }

  /** How much of a Gaussian of deviation {@code sigma} around 0 lies below {@code t}. */
  private static double below(double t, double sigma) {
    return 0.5 + 0.5 * erf(t * (0.7071067811865476 / sigma));
  }

  /**
   * The error function as Abramowitz and Stegun's 7.1.28 has it, within 3e-7: arithmetic only, so
   * that the shader computes the same.
   */
  static double erf(double x) {
    double a = Math.min(Math.abs(x), 4);
    double p = 1 + a * (0.0705230784 + a * (0.0422820123 + a * (0.0092705272
        + a * (0.0001520143 + a * (0.0002765672 + a * 0.0000430638)))));
    p *= p;
    p *= p;
    p *= p;
    p *= p;
    return Math.signum(x) * (1 - 1 / p);
  }

  /**
   * A rounded rectangle, an instance's shape or a clip, by its centre, half size and the radii of
   * its corners in logical pixels, top left, top right, bottom right, bottom left, across ({@code
   * rx}) and from top to bottom ({@code ry}); the corner on a point's side of the centre ({@code x
   * > 0} right, {@code y > 0} below) gives its distance. Only the fill of a border whose sides
   * differ has corners whose two radii differ.
   *
   * <p>The pixels it covers whole, with a coverage of exactly 1, can be found without computing
   * the coverage of each: in a row of pixels they are one run without gaps, and a box of pixels
   * whose four corners they include lies among them. That holds for the coverage as computed in
   * doubles, not only for the exact one, because every step of it is monotonic and IEEE rounding
   * keeps that: the offset {@code x} of the pixel's centre {@code (px + 0.5) / scale} from the
   * centre grows with {@code px}; {@link CpuRenderer#distance} grows with {@code |x|}, as each of
   * its additions, maxima, minima, squares of values of 0 or more and its square root does; and
   * {@link CpuRenderer#coverage}, {@code 0.5 - distance * scale} clamped, shrinks as the distance
   * grows when {@code scale > 0}. A pixel between two others of its row has an {@code |x|} no
   * larger than the larger of theirs, so a coverage no smaller than the smaller of theirs: 1 if
   * both have 1. The same holds for {@code |y|} in a column, so in a box the corner farthest from
   * the centre on both axes has the smallest coverage. With a radius per corner that holds in
   * each quadrant, and a pixel between two others of its row lies on the side of one of them with
   * an {@code |x|} no larger than its, as the corner of a box on its side does on both axes. A
   * product of coverages, each from 0 to 1, is 1 only where each is, so the pixels that several
   * boxes all cover whole are a run too. A value that is not a number makes the coverage NaN, which
   * is never 1, and a scale of 0 or less is taken for no shortcut. This is shown for circular
   * corners only ({@link #circular}); a rectangle whose fill has elliptic ones is measured at every
   * pixel.
   */
  private record Box(double centerX, double centerY, double halfW, double halfH, double[] rx,
      double[] ry) {

    /** The entry of the clip table, or null for entry 0, which covers everything. */
    static Box clip(Scene scene, int clip) {
      if (clip == 0) {
        return null;
      }
      double halfW = scene.clipGet(clip, Scene.WIDTH) / 2;
      double halfH = scene.clipGet(clip, Scene.HEIGHT) / 2;
      double[] radii = new double[4];
      for (int c = 0; c < 4; c++) {
        radii[c] = Math.clamp(scene.clipGet(clip, Scene.RADIUS + 4 * c), 0,
            Math.min(halfW, halfH));
      }
      return new Box(scene.clipGet(clip, Scene.X) + halfW, scene.clipGet(clip, Scene.Y) + halfH,
          halfW, halfH, radii, radii);
    }

    /** Whether each corner is a quarter of a circle. */
    boolean circular() {
      for (int c = 0; c < 4; c++) {
        if (rx[c] != ry[c]) {
          return false;
        }
      }
      return true;
    }

    /** How much of the pixel centred at {@code x, y}, in logical pixels, the box covers. */
    double coverage(double x, double y, double scale) {
      double dx = x - centerX;
      double dy = y - centerY;
      int c = corner(dx, dy);
      return CpuRenderer.coverage(distance(dx, dy, halfW, halfH, rx[c], ry[c]), scale);
    }

    /**
     * Whether it covers none of the pixels from {@code left} to {@code right - 1} in the row whose
     * centres lie at {@code y}: in a row, the coverage is largest at the pixels nearest the centre
     * on either side (see above), which are among the four around where the centre falls.
     */
    boolean missesRow(double y, int left, int right, double scale) {
      // Clamped before the cast, so that a centre far outside cannot overflow the loop below.
      int middle = (int) Math.clamp(Math.floor(centerX * scale - 0.5), left - 1, right);
      for (int px = middle - 1; px <= middle + 2; px++) {
        if (coverage((Math.clamp(px, left, right - 1) + 0.5) / scale, y, scale) != 0) {
          return false;
        }
      }
      return true;
    }

    /** Whether it covers every pixel from {@code left, top} to {@code right, bottom} whole. */
    boolean coversWhole(int left, int top, int right, int bottom, double scale) {
      return scale > 0 && left <= right && top <= bottom
          && whole(left, top, scale) && whole(right, top, scale)
          && whole(left, bottom, scale) && whole(right, bottom, scale);
    }

    private boolean whole(int px, int py, double scale) {
      return coverage((px + 0.5) / scale, (py + 0.5) / scale, scale) == 1;
    }
  }

  /** The four channels of the colour at {@code field} of instance {@code i}. */
  private static double[] colour(Scene scene, int i, int field) {
    double[] colour = new double[4];
    for (int c = 0; c < 4; c++) {
      colour[c] = scene.get(i, field + c * 4);
    }
    return colour;
  }

  /** One channel of the instance's colour: the border colour, or the fill where the fill shows. */
  private static double mix(double fill, double edge, double fillShare) {
    return edge + (fill - edge) * fillShare;
  }

  /**
   * Blends a premultiplied source over the pixel at {@code at}: it becomes {@code source + target *
   * (1 - alpha)}, channel by channel.
   */
  private static void blend(float[] frame, int at, double red, double green, double blue,
      double alpha, Blends blends) {
    // Alpha is the last channel, so it is still the old value while the colours are blended.
    if (blends != null) {
      blends.blend(frame, at, alpha);
    }
    frame[at] = (float) (red + frame[at] * (1 - alpha));
    frame[at + 1] = (float) (green + frame[at + 1] * (1 - alpha));
    frame[at + 2] = (float) (blue + frame[at + 2] * (1 - alpha));
    frame[at + 3] = (float) (alpha + frame[at + 3] * (1 - alpha));
  }

  /** The corner on the side of {@code x, y}: 0 top left, 1 top right, 2 bottom right, 3 bottom left. */
  private static int corner(double x, double y) {
    return x > 0 ? y > 0 ? 2 : 1 : y > 0 ? 3 : 0;
  }

  /**
   * Signed distance to a rounded rectangle centred on the origin whose corner on the point's side
   * has the radius {@code rx} across and {@code ry} from top to bottom; negative inside. An elliptic
   * corner's is the first-order distance, as {@link Scene} says.
   */
  private static double distance(double x, double y, double halfW, double halfH, double rx,
      double ry) {
    if (rx == ry) {
      return distance(x, y, halfW, halfH, rx);
    }
    double ex = Math.abs(x) - halfW;
    double ey = Math.abs(y) - halfH;
    double u = ex + rx;
    double v = ey + ry;
    if (rx > 0 && ry > 0 && u > 0 && v > 0) {
      u /= rx;
      v /= ry;
      double k = StrictMath.sqrt(u * u + v * v);
      double gx = u / rx;
      double gy = v / ry;
      return (k - 1) * k / StrictMath.sqrt(gx * gx + gy * gy);
    }
    double outsideX = Math.max(ex, 0);
    double outsideY = Math.max(ey, 0);
    return Math.min(Math.max(ex, ey), 0)
        + StrictMath.sqrt(outsideX * outsideX + outsideY * outsideY);
  }

  /** Signed distance to a rounded rectangle centred on the origin; negative inside. */
  private static double distance(double x, double y, double halfW, double halfH, double radius) {
    double qx = Math.abs(x) - halfW + radius;
    double qy = Math.abs(y) - halfH + radius;
    double outsideX = Math.max(qx, 0);
    double outsideY = Math.max(qy, 0);
    return Math.min(Math.max(qx, qy), 0)
        + StrictMath.sqrt(outsideX * outsideX + outsideY * outsideY)
        - radius;
  }

  /** How much of a pixel the shape covers: a ramp one physical pixel wide across the edge. */
  private static double coverage(double distance, double scale) {
    return Math.clamp(0.5 - distance * scale, 0, 1);
  }

  /** A linear value from 0 to 1 as 8-bit sRGB, from 0 to 255. */
  static int encode(double linear) {
    double srgb =
        linear <= 0.0031308 ? linear * 12.92 : 1.055 * StrictMath.pow(linear, 1 / 2.4) - 0.055;
    return (int) Math.round(srgb * 255);
  }

  /**
   * The image in 8-bit sRGB with straight alpha. A pixel's bytes depend on its four floats alone,
   * so they are kept by the floats' bits, and a pixel with the same bits as one before it, as most
   * are, is encoded once; so is each value of a channel ({@link Encoder}). A pixel whose slot is
   * taken replaces it.
   */
  static Pixels encode(float[] frame, int width, int height) {
    byte[] rgba = new byte[frame.length];
    int bits = 12;
    // Every slot starts with the bits of a transparent pixel, all 0, whose bytes are all 0.
    int[] keys = new int[4 << bits];
    int[] values = new int[1 << bits];
    Encoder encoder = new Encoder();
    for (int at = 0; at < frame.length; at += 4) {
      int red = Float.floatToRawIntBits(frame[at]);
      int green = Float.floatToRawIntBits(frame[at + 1]);
      int blue = Float.floatToRawIntBits(frame[at + 2]);
      int alpha = Float.floatToRawIntBits(frame[at + 3]);
      int slot = ((red * 31 + green) * 31 + blue) * 31 + alpha;
      slot = (slot * 0x9E3779B9) >>> (32 - bits);
      int key = slot * 4;
      if (keys[key] != red || keys[key + 1] != green || keys[key + 2] != blue
          || keys[key + 3] != alpha) {
        keys[key] = red;
        keys[key + 1] = green;
        keys[key + 2] = blue;
        keys[key + 3] = alpha;
        values[slot] = encode(frame, at, encoder);
      }
      int value = values[slot];
      rgba[at] = (byte) value;
      rgba[at + 1] = (byte) (value >>> 8);
      rgba[at + 2] = (byte) (value >>> 16);
      rgba[at + 3] = (byte) (value >>> 24);
    }
    return new Pixels(width, height, rgba);
  }

  /** The bytes of the pixel at {@code at}, red in the lowest. */
  private static int encode(float[] frame, int at, Encoder encoder) {
    double alpha = Math.clamp(frame[at + 3], 0, 1);
    if (alpha == 0) {
      return 0;
    }
    int bytes = (int) Math.round(alpha * 255) << 24;
    for (int c = 0; c < 3; c++) {
      bytes |= (encoder.encode(Math.clamp(frame[at + c] / alpha, 0, 1)) & 0xFF) << 8 * c;
    }
    return bytes;
  }

  /**
   * {@link #encode(double)}, remembered by the bits of the value, as a pixel's bytes are: the
   * channels of a grey are one value, and a gradient repeats its values along a row or a column.
   */
  private static final class Encoder {
    private static final int BITS = 12;
    // Every slot starts with the bits of 0.0, which encodes to 0.
    private final long[] keys = new long[1 << BITS];
    private final int[] values = new int[1 << BITS];

    int encode(double linear) {
      long bits = Double.doubleToRawLongBits(linear);
      int slot = (int) ((bits * 0x9E3779B97F4A7C15L) >>> (64 - BITS));
      if (keys[slot] != bits) {
        keys[slot] = bits;
        values[slot] = CpuRenderer.encode(linear);
      }
      return values[slot];
    }
  }
}
