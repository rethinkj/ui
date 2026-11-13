package com.rethinkj.ui.text;

import java.util.Arrays;

/**
 * Shaped text: the glyphs of a string in one font at one size, with their positions. Made by
 * {@link Font#shape}; immutable.
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

  GlyphRun(Font font, float pixelSize, int[] glyphs, int[] clusters, float[] pen, float[] dx,
      float[] dy) {
    this.font = font;
    this.pixelSize = pixelSize;
    this.glyphs = glyphs;
    this.clusters = clusters;
    this.pen = pen;
    this.dx = dx;
    this.dy = dy;
  }

  public Font font() {
    return font;
  }

  /** The size shaped at, in physical pixels: the size in logical pixels times the scale. */
  public float pixelSize() {
    return pixelSize;
  }

  /** The number of glyphs, which differs from the number of characters where they combine. */
  public int count() {
    return glyphs.length;
  }

  /** The glyph id in the font; 0 is the font's "not defined" glyph, drawn for missing ones. */
  public int glyph(int i) {
    return glyphs[i];
  }

  /**
   * The index in the shaped string of the first UTF-16 unit this glyph comes from. Clusters never
   * decrease: text is shaped left to right.
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
        Arrays.copyOfRange(dy, from, to));
  }
}
