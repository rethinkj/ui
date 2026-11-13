package com.rethinkj.ui.text;

import java.util.Arrays;
import java.util.List;

/**
 * Shaped text: the glyphs of a string at one size, with their positions, each glyph from the font
 * that was shaped or from one of its fallbacks ({@link #font(int)}). Made by {@link Font#shape};
 * immutable.
 *
 * <p>Everything is in physical pixels, on the grid {@link Font#shape} rounds to: whole pixels
 * below scale 1.5, quarter pixels from 1.5 on. x grows to the right from the pen's start, y grows
 * downwards from the baseline. Measuring with {@link #width} and drawing at {@link #x} and {@link
 * #y} use the same rounded numbers, so text takes exactly the room it was measured for.
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

  GlyphRun(Font font, float pixelSize, int[] glyphs, int[] clusters, float[] pen, float[] dx,
      float[] dy, Font[] fonts) {
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
    if (parts.size() == 1 && parts.getFirst().font == font) {
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
    for (GlyphRun part : parts) {
      int count = part.count();
      System.arraycopy(part.glyphs, 0, glyphs, at, count);
      System.arraycopy(part.clusters, 0, clusters, at, count);
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

  /** The font that was shaped, whose fallbacks may draw some glyphs: see {@link #font(int)}. */
  public Font font() {
    return font;
  }

  /** The font that draws glyph {@code i}: the one shaped, or one of its fallbacks. */
  public Font font(int i) {
    return fonts == null ? font : fonts[i];
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
  public int glyph(int i) {
    return glyphs[i];
  }

  /**
   * The index in the shaped string of the first UTF-16 unit this glyph comes from. Clusters never
   * decrease: runs follow each other left to right, and all glyphs of a run of right-to-left text
   * have the index of its first character.
   */
  public int cluster(int i) {
    return clusters[i];
  }

  /** Where glyph {@code i}'s origin goes. */
  public float x(int i) {
    return pen[i] + dx[i];
  }

  /** How far glyph {@code i}'s origin sits below the baseline; usually 0. */
  public float y(int i) {
    return dy[i];
  }

  public float advance(int i) {
    return pen[i + 1] - pen[i];
  }

  /** The sum of the advances of the glyphs before {@code i}; {@code pen(count())} is the width. */
  public float pen(int i) {
    return pen[i];
  }

  /** The sum of all advances. */
  public float width() {
    return pen[glyphs.length];
  }

  /** Glyphs {@code from} to {@code to}, exclusive, moved left so that the pen starts at 0. */
  public GlyphRun slice(int from, int to) {
    float start = pen[from];
    float[] moved = Arrays.copyOfRange(pen, from, to + 1);
    for (int i = 0; i < moved.length; i++) {
      moved[i] -= start;
    }
    return new GlyphRun(font, pixelSize, Arrays.copyOfRange(glyphs, from, to),
        Arrays.copyOfRange(clusters, from, to), moved, Arrays.copyOfRange(dx, from, to),
        Arrays.copyOfRange(dy, from, to),
        fonts == null ? null : Arrays.copyOfRange(fonts, from, to));
  }
}
