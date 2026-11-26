package com.rethinkj.ui.text;

import java.util.Arrays;
import java.util.List;

/**
 * Shaped text: the glyphs of a string at one size, with their positions, each glyph from the font
 * that was shaped or from one of its fallbacks ({@link #font(int)}). Made by {@link Font#shape};
 * immutable.
 *
 * <p>Everything is in physical pixels, on the grid {@link Font#shape} rounds to: quarter pixels at
 * every scale, or whole pixels at every scale for a font with {@link Font#wholePixels}. x grows to
 * the right from the pen's start, y grows downwards from the baseline. Measuring with {@link #width}
 * and drawing at {@link #x} and {@link #y} use the same rounded numbers, so text takes exactly the
 * room it was measured for.
 */
public final class GlyphRun {

  private final Font font;
  private final float pixelSize;
  private final int[] glyphs;
  private final int[] clusters;
  /** The pen before each glyph; one more than glyphs, the last is the width. */
  private final float[] pen;

  private final float[] dx;
  private final float[] dy;
  private final Font[] fonts; // per glyph, or null when all are font's

  GlyphRun(
      Font font,
      float pixelSize,
      int[] glyphs,
      int[] clusters,
      float[] pen,
      float[] dx,
      float[] dy,
      Font[] fonts) {
    this.font = font;
    this.pixelSize = pixelSize;
    this.glyphs = glyphs;
    this.clusters = clusters;
    this.pen = pen;
    this.dx = dx;
    this.dy = dy;
    this.fonts = fonts;
  }

  /** Runs one after the other, as one run of {@code font}; their glyphs keep their fonts. */
  static GlyphRun join(Font font, float pixelSize, List<GlyphRun> parts) {
    return join(font, pixelSize, parts, null);
  }

  /** {@link #join}, with the clusters of part {@code i} moved by {@code starts[i]}; null for none. */
  private static GlyphRun join(Font font, float pixelSize, List<GlyphRun> parts, int[] starts) {
    if (starts == null && parts.size() == 1 && parts.getFirst().font == font) {
      return parts.getFirst();
    }
    int n = parts.stream().mapToInt(GlyphRun::count).sum();
    int[] glyphs = new int[n];
    int[] clusters = new int[n];
    float[] pen = new float[n + 1];
    float[] dx = new float[n];
    float[] dy = new float[n];
    Font[] fonts = new Font[n];
    int at = 0;
    for (int p = 0; p < parts.size(); p++) {
      GlyphRun part = parts.get(p);
      int count = part.count();
      System.arraycopy(part.glyphs, 0, glyphs, at, count);
      for (int i = 0; i < count; i++) {
        clusters[at + i] = part.clusters[i] + (starts == null ? 0 : starts[p]);
      }
      System.arraycopy(part.dx, 0, dx, at, count);
      System.arraycopy(part.dy, 0, dy, at, count);
      for (int i = 0; i < count; i++) {
        fonts[at + i] = part.font(i);
        pen[at + i + 1] = pen[at] + part.pen[i + 1];
      }
      at += count;
    }
    return new GlyphRun(font, pixelSize, glyphs, clusters, pen, dx, dy, fonts);
  }

  /**
   * Runs of consecutive parts of one string, each shaped on its own, as one run: part {@code i}
   * starts at index {@code starts[i]} of the string, so its clusters move by as much, and its pen
   * follows on from the part before. The glyphs keep their fonts; {@link #font()} is the first
   * part's. A text with ranges in another font, such as a bold keyword, is shaped so.
   */
  public static GlyphRun concat(List<GlyphRun> parts, int[] starts) {
    if (parts.size() != starts.length || parts.isEmpty()) {
      throw new IllegalArgumentException("one start per part, and at least one part");
    }
    if (parts.size() == 1 && starts[0] == 0) {
      return parts.getFirst();
    }
    return join(parts.getFirst().font, parts.getFirst().pixelSize, parts, starts);
  }

  /** The font that was shaped, whose fallbacks may draw some glyphs: see {@link #font(int)}. */
  public Font font() {
    return font;
  }

  /** The font that draws glyph {@code index}: the one shaped, or one of its fallbacks. */
  public Font font(int index) {
    return fonts == null ? font : fonts[index];
  }

  /** The size shaped at, in physical pixels: the size in logical pixels times the scale. */
  public float pixelSize() {
    return pixelSize;
  }

  /** The number of glyphs, which differs from the number of characters where they combine. */
  public int count() {
    return glyphs.length;
  }

  /** The glyph id in its {@link #font(int) font}; 0 is the "not defined" glyph. */
  public int glyph(int index) {
    return glyphs[index];
  }

  /**
   * The index in the shaped string of the first UTF-16 unit this glyph comes from. Clusters never
   * decrease: runs follow each other left to right, and all glyphs of a run of right-to-left text
   * have the index of its first character.
   */
  public int cluster(int index) {
    return clusters[index];
  }

  /** Where glyph {@code index}'s origin goes. */
  public float x(int index) {
    return pen[index] + dx[index];
  }

  /** How far glyph {@code index}'s origin sits below the baseline; usually 0. */
  public float y(int index) {
    return dy[index];
  }

  public float advance(int index) {
    return pen[index + 1] - pen[index];
  }

  /**
   * The sum of the advances of the glyphs before {@code index}; {@code pen(count())} is the width.
   */
  public float pen(int index) {
    return pen[index];
  }

  /** The sum of all advances. */
  public float width() {
    return pen[glyphs.length];
  }

  /**
   * This run with inline gaps reserved at character positions, for {@link
   * com.rethinkj.ui.El#hints inline hints}: {@code positions} holds strictly increasing UTF-16
   * indexes in {@code 0..textLength}, {@code before[i]} is the width in physical pixels belonging
   * to the text before {@code positions[i]}, and {@code after[i]} is the width belonging to the
   * text after it.
   *
   * <p>{@code before} widths are added to the advance of the last glyph before the position;
   * {@code after} widths are added to both {@code dx} and the advance of the first glyph at the
   * position. A gap at 0 behaves as {@code after}, a gap at {@code textLength} behaves as {@code
   * before}, and a gap inside a multi-character cluster (a ligature or a right-to-left run) moves
   * to that cluster's start. Returns this run unchanged when it has no glyphs or {@code positions}
   * is empty.
   */
  public GlyphRun withGaps(int textLength, int[] positions, float[] before, float[] after) {
    int n = glyphs.length;
    if (n == 0 || positions.length == 0) {
      return this;
    }
    float[] beforeAtGlyph = new float[n + 1];
    float[] afterAtGlyph = new float[n];
    int g = 0;
    for (int k = 0; k < positions.length; k++) {
      int pos = positions[k];
      float b = before[k];
      float a = after[k];
      if (b <= 0 && a <= 0) {
        continue;
      }
      int target;
      if (pos <= 0) {
        target = 0;
      } else if (pos >= textLength) {
        target = n;
      } else {
        while (g < n && clusters[g] < pos) {
          g++;
        }
        if (g < n && clusters[g] == pos) {
          target = g;
        } else if (g == 0) {
          target = 0;
        } else {
          int c = clusters[g - 1];
          target = g - 1;
          while (target > 0 && clusters[target - 1] == c) {
            target--;
          }
        }
      }
      if (target == 0) {
        afterAtGlyph[0] += b + a;
      } else if (target == n) {
        beforeAtGlyph[n] += b + a;
      } else {
        beforeAtGlyph[target] += b;
        afterAtGlyph[target] += a;
      }
    }
    float[] movedPen = new float[n + 1];
    float[] movedDx = dx.clone();
    float shift = 0;
    for (int i = 0; i < n; i++) {
      shift += beforeAtGlyph[i];
      movedPen[i] = pen[i] + shift;
      if (afterAtGlyph[i] > 0) {
        movedDx[i] += afterAtGlyph[i];
        shift += afterAtGlyph[i];
      }
    }
    shift += beforeAtGlyph[n];
    movedPen[n] = pen[n] + shift;
    return new GlyphRun(font, pixelSize, glyphs, clusters, movedPen, movedDx, dy, fonts);
  }

  /**
   * This run with tab stops, which shaping knows nothing of: the first glyph of each character
   * whose index is in {@code tabs}, in increasing order, advances to the next multiple of {@code
   * stop} from the start of the pen, and the glyphs after it move along. The string is shaped with
   * a space in place of each tab, so that each tab has a glyph that draws nothing.
   */
  public GlyphRun tabbed(int[] tabs, float stop) {
    return tabbed(tabs, stop, null, null);
  }

  /**
   * Like {@link #tabbed(int[], float)}, keeping the inline gaps of {@link #withGaps}: {@code
   * leading[i]} sits before tab {@code i} and {@code trailing[i]} right after it, or null for none.
   */
  public GlyphRun tabbed(int[] tabs, float stop, float[] leading, float[] trailing) {
    int n = glyphs.length;
    float[] moved = new float[n + 1];
    float shift = 0;
    int t = 0;
    for (int i = 0; i < n; i++) {
      moved[i] = pen[i] + shift;
      while (t < tabs.length && tabs[t] < clusters[i]) {
        t++; // a tab inside a cluster of right-to-left text, which has no glyph of its own
      }
      if (t < tabs.length && tabs[t] == clusters[i] && (i == 0 || clusters[i - 1] != clusters[i])) {
        float tabStart = moved[i] + (leading == null ? 0 : leading[t]);
        float next = (float) ((Math.floor(tabStart / stop) + 1) * stop);
        float afterTab = trailing == null ? 0 : trailing[t];
        shift += (next + afterTab) - moved[i] - (pen[i + 1] - pen[i]);
        t++;
      }
    }
    moved[n] = pen[n] + shift;
    return new GlyphRun(font, pixelSize, glyphs, clusters, moved, dx, dy, fonts);
  }

  /** Glyphs {@code from} to {@code to}, exclusive, moved left so that the pen starts at 0. */
  public GlyphRun slice(int from, int to) {
    float start = pen[from];
    float[] moved = Arrays.copyOfRange(pen, from, to + 1);
    for (int i = 0; i < moved.length; i++) {
      moved[i] -= start;
    }
    return new GlyphRun(
        font,
        pixelSize,
        Arrays.copyOfRange(glyphs, from, to),
        Arrays.copyOfRange(clusters, from, to),
        moved,
        Arrays.copyOfRange(dx, from, to),
        Arrays.copyOfRange(dy, from, to),
        fonts == null ? null : Arrays.copyOfRange(fonts, from, to));
  }
}
