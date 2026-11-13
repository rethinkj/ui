package com.rethinkj.ui;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import java.util.Objects;

/**
 * A picture to show with {@link El#image}: pixels in sRGB with straight alpha, prepared once for
 * every renderer and shared by every element that shows it. An image is a value that does not
 * change; keep it in the app's state, decoded off the app's thread ({@link App#background}), and
 * hand the same object to the view on every build, so that the GPU uploads it once: the renderers
 * know an image by its identity.
 *
 * <p>An image has the size of its pixels and a {@link #scale}, the physical pixels per logical
 * pixel it was made for: an image of 200 by 100 pixels at scale 2 is 100 by 50 logical pixels,
 * and a window at scale 2 draws it one pixel to one pixel.
 *
 * <p>What the renderers read are its {@link #levels levels}: level 0 is the image, each further
 * level half as large on each side, rounded down and at least 1, down to 1 by 1, as GPUs count mip
 * levels. A texel is four bytes: red, green and blue premultiplied by alpha in linear light and
 * encoded to 8-bit sRGB, and alpha, linear, from 0 to 255. That is how an sRGB render target
 * stores colour: 8 bits keep dark colours apart, renderers filter the texels decoded, in linear
 * light, and an opaque pixel's bytes are the image's own. Each level is the one
 * before it averaged over the area each texel covers, in linear light with premultiplied alpha,
 * so that translucent edges do not darken. They are computed when the image is made.
 *
 * <p>An image larger than {@link #MAX_SIZE} on a side is halved until it fits, and its scale
 * halved with it, so that its logical size stays.
 */
public final class Image {

  /**
   * How an image fills its element's content box, as CSS's {@code object-fit}. The element keeps
   * the image's aspect ratio when the view sets only one of its width and height, so these differ
   * only where both are set.
   */
  public enum Fit {
    /** Stretched to the box, both ways. */
    FILL,
    /** As large as fits whole in the box, centred, keeping its aspect ratio. */
    CONTAIN,
    /** As small as covers the box, centred and cut by it, keeping its aspect ratio. */
    COVER
  }

  /** The largest side of an image in pixels; a larger one is halved until it fits. */
  public static final int MAX_SIZE = 16384;

  private static final double[] LINEAR = new double[256];
  /** The linear values halfway between two 8-bit sRGB values, for encoding: see {@link #encode}. */
  private static final double[] THRESHOLDS = new double[256];

  static {
    for (int i = 0; i < 256; i++) {
      LINEAR[i] = linear(i / 255.0);
      THRESHOLDS[i] = i == 0 ? Double.NEGATIVE_INFINITY : linear((i - 0.5) / 255.0);
    }
  }

  private final byte[][] levels;
  private final int width;
  private final int height;
  private final float scale;

  private Image(byte[][] levels, int width, int height, float scale) {
    this.levels = levels;
    this.width = width;
    this.height = height;
    this.scale = scale;
  }

  /** An image of these pixels, one pixel to one logical pixel. */
  public static Image of(Pixels pixels) {
    return of(pixels, 1);
  }

  /**
   * An image of these pixels, made for {@code scale} physical pixels per logical pixel: at scale
   * 2 its logical size is half its size in pixels. The pixels are read now and not kept.
   *
   * @throws IllegalArgumentException if the image has no pixels or the scale is not above 0
   */
  public static Image of(Pixels pixels, float scale) {
    Objects.requireNonNull(pixels, "pixels");
    if (pixels.width() <= 0 || pixels.height() <= 0) {
      throw new IllegalArgumentException("An image needs pixels; this one is " + pixels.width()
          + "x" + pixels.height() + ".");
    }
    if (!(scale > 0 && Float.isFinite(scale))) {
      throw new IllegalArgumentException("The scale is " + scale + "; it must be above 0.");
    }
    int w = pixels.width();
    int h = pixels.height();
    byte[] level = premultiplied(pixels.rgba());
    while (w > MAX_SIZE || h > MAX_SIZE) {
      int nw = Math.max(1, w >> 1);
      int nh = Math.max(1, h >> 1);
      level = halve(level, w, h, nw, nh);
      w = nw;
      h = nh;
      scale /= 2;
    }
    int count = 32 - Integer.numberOfLeadingZeros(Math.max(w, h));
    byte[][] levels = new byte[count][];
    levels[0] = level;
    for (int k = 1; k < count; k++) {
      levels[k] = halve(levels[k - 1], Math.max(1, w >> (k - 1)), Math.max(1, h >> (k - 1)),
          Math.max(1, w >> k), Math.max(1, h >> k));
    }
    return new Image(levels, w, h, scale);
  }

  /** An image read from a PNG file, one pixel to one logical pixel. */
  public static Image decode(byte[] bytes) {
    return decode(bytes, 1);
  }

  /**
   * An image read from a PNG file, made for {@code scale}; see {@link Png#decode} for what it
   * reads.
   *
   * @throws IllegalArgumentException if the bytes are not one, or cannot be read, saying why
   */
  public static Image decode(byte[] bytes, float scale) {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
        && bytes[3] == 'G') {
      return of(Png.decode(bytes), scale);
    }
    throw new IllegalArgumentException("Not a PNG file: it starts with " + start(bytes)
        + "; PNG starts with 89 50 4E 47.");
  }

  private static String start(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < Math.min(4, bytes.length); i++) {
      out.append(i == 0 ? "" : " ").append(String.format("%02X", bytes[i] & 0xFF));
    }
    return bytes.length == 0 ? "nothing" : out.toString();
  }

  /** The width of level 0 in pixels. */
  public int width() {
    return width;
  }

  /** The height of level 0 in pixels. */
  public int height() {
    return height;
  }

  /** The physical pixels per logical pixel the image is made for. */
  public float scale() {
    return scale;
  }

  /** The number of levels, from the image down to 1 by 1. */
  public int levels() {
    return levels.length;
  }

  /** The width of level {@code k}: the image's halved {@code k} times, rounded down, at least 1. */
  public int levelWidth(int k) {
    return Math.max(1, width >> k);
  }

  /** The height of level {@code k}. */
  public int levelHeight(int k) {
    return Math.max(1, height >> k);
  }

  /**
   * The texels of level {@code k}, four bytes each, rows from the top, as the class comment says.
   * The array is the image's own: read it, never change it.
   */
  public byte[] level(int k) {
    return levels[k];
  }

  /** The bytes all levels take. */
  public long bytes() {
    long bytes = 0;
    for (byte[] level : levels) {
      bytes += level.length;
    }
    return bytes;
  }

  @Override
  public String toString() {
    return "Image " + width + "x" + height + (scale == 1 ? "" : " at scale " + scale);
  }

  private static double linear(double c) {
    return c <= 0.04045 ? c / 12.92 : StrictMath.pow((c + 0.055) / 1.055, 2.4);
  }

  /**
   * A linear value from 0 to 1 as 8-bit sRGB, rounded to the nearest: the number of values whose
   * lower threshold it reaches.
   */
  private static int encode(double linear) {
    int low = 0;
    int high = 255;
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (linear >= THRESHOLDS[middle]) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return low;
  }

  /** Straight sRGB as the texels of a level; opaque pixels keep their bytes. */
  private static byte[] premultiplied(byte[] rgba) {
    byte[] out = rgba.clone();
    for (int at = 0; at < out.length; at += 4) {
      int alpha = out[at + 3] & 0xFF;
      if (alpha != 255) {
        for (int c = 0; c < 3; c++) {
          out[at + c] = (byte) (alpha == 0 ? 0 : encode(LINEAR[out[at + c] & 0xFF] * alpha / 255));
        }
      }
    }
    return out;
  }

  /**
   * A level from the one before it: each texel the average of the area it covers there, in
   * linear light with premultiplied alpha. Where a side halves exactly that is two texels; where
   * it was odd, up to three, the outer ones in part.
   */
  private static byte[] halve(byte[] from, int width, int height, int toWidth, int toHeight) {
    int[][] columns = taps(width, toWidth);
    double[][] columnWeights = weights(width, toWidth);
    int[][] rows = taps(height, toHeight);
    double[][] rowWeights = weights(height, toHeight);
    byte[] out = new byte[toWidth * toHeight * 4];
    double[] sum = new double[4];
    for (int j = 0; j < toHeight; j++) {
      for (int i = 0; i < toWidth; i++) {
        java.util.Arrays.fill(sum, 0);
        for (int b = 0; b < rows[j].length; b++) {
          int row = rows[j][b] * width;
          for (int a = 0; a < columns[i].length; a++) {
            double weight = rowWeights[j][b] * columnWeights[i][a];
            int at = (row + columns[i][a]) * 4;
            for (int c = 0; c < 3; c++) {
              sum[c] += LINEAR[from[at + c] & 0xFF] * weight;
            }
            sum[3] += (from[at + 3] & 0xFF) * weight;
          }
        }
        int at = (j * toWidth + i) * 4;
        for (int c = 0; c < 3; c++) {
          out[at + c] = (byte) encode(sum[c]);
        }
        out[at + 3] = (byte) Math.clamp(Math.round(sum[3]), 0, 255);
      }
    }
    return out;
  }

  /** The texels of a side of {@code size} that each of {@code to} texels covers. */
  private static int[][] taps(int size, int to) {
    int[][] taps = new int[to][];
    for (int i = 0; i < to; i++) {
      int first = (int) ((long) i * size / to);
      int end = (int) Math.ceilDiv((long) (i + 1) * size, to);
      taps[i] = new int[end - first];
      for (int t = 0; t < taps[i].length; t++) {
        taps[i][t] = first + t;
      }
    }
    return taps;
  }

  /** How much of each tap of {@link #taps} the texel covers, adding up to 1. */
  private static double[][] weights(int size, int to) {
    double[][] weights = new double[to][];
    double span = (double) size / to;
    for (int i = 0; i < to; i++) {
      double start = i * span;
      double end = (i + 1) * span;
      int first = (int) ((long) i * size / to);
      int count = (int) Math.ceilDiv((long) (i + 1) * size, to) - first;
      weights[i] = new double[count];
      for (int t = 0; t < count; t++) {
        double from = Math.max(start, first + t);
        double until = Math.min(end, first + t + 1);
        weights[i][t] = (until - from) / span;
      }
    }
    return weights;
  }
}
