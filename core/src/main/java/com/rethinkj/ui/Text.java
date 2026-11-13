package com.rethinkj.ui;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text of an element made with {@link El#text}: its string and look, and, once layout has
 * placed the element, its {@link #lines} as shaped glyphs, which is what a renderer draws.
 *
 * <p>Each line feed starts a new line. A text that {@link El#wrapText wraps} also breaks where
 * {@link BreakIterator#getLineInstance} allows when a line would be wider than its box; a word
 * wider than the box stays whole. Spaces at the end of a wrapped line take no width. The line
 * height is the font's ascent, descent and line gap, rounded to whole physical pixels.
 *
 * <p>Text is shaped for the scale of the window, which {@link App} and {@link Headless} pass to
 * every text element before layout. Sizes go to layout in logical pixels; lines are in physical
 * pixels, so that a renderer draws the glyphs where they were measured.
 *
 * <p>Not supported yet: bidirectional text, mixed scripts in one line and fallback fonts. A
 * character the font lacks shows as the font's "not defined" glyph.
 */
public final class Text implements Measure {

  /**
   * One line of the text, in physical pixels relative to the top left of the element's content
   * box.
   *
   * @param glyphs the glyphs, with the pen starting at 0 at the left edge
   * @param width the width the line takes, without the spaces at the end of a wrapped line
   * @param baseline the distance from the top of the content box down to the line's baseline
   */
  public record Line(GlyphRun glyphs, float width, float baseline) {}

  // A width a line may exceed its box by: layout converts what it measured back and forth, and
  // widths are on a grid of a quarter pixel.
  private static final float SLACK = 1 / 64f;

  private final String string;
  private Font font;
  private float size = 14;
  private int color = 0xFF000000;
  private int background;
  private boolean wraps;
  private float scale = 1;
  private List<Line> lines = List.of();

  // What every measure shares, made on the first one: per paragraph its glyphs, the first glyph
  // of each character and the break opportunities.
  private String[] paragraphs;
  private GlyphRun[] runs;
  private int[][] glyphAt;
  private int[][] breaks;
  private float lineHeight;
  private float firstBaseline;

  /** A line of the text: characters {@code from} to {@code to} of a paragraph. */
  private record Span(int paragraph, int from, int to, float width) {}

  Text(String string) {
    if (string == null) {
      throw new IllegalArgumentException("string is null; use \"\" for an empty text");
    }
    this.string = string;
  }

  public String string() {
    return string;
  }

  /** The font set with {@link El#font}, or {@link Font#sans} by default. */
  public Font font() {
    return font != null ? font : Font.sans();
  }

  /** The font size in logical pixels. */
  public float size() {
    return size;
  }

  /** The colour as 0xAARRGGBB. */
  public int color() {
    return color;
  }

  /** The background set with {@link El#textBackground}, as 0xAARRGGBB, or 0 for none. */
  public int background() {
    return background;
  }

  public boolean wraps() {
    return wraps;
  }

  /** The scale the text was shaped for, in physical pixels per logical pixel. */
  public float scale() {
    return scale;
  }

  /** The lines layout placed, top to bottom; empty before layout. */
  public List<Line> lines() {
    return lines;
  }

  void font(Font font) {
    this.font = font;
    paragraphs = null;
  }

  void size(float size) {
    if (!(size > 0 && size < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("font size must be above 0, was " + size);
    }
    this.size = size;
    paragraphs = null;
  }

  void color(int argb) {
    this.color = argb;
  }

  void background(int argb) {
    this.background = argb;
  }

  void wraps(boolean wraps) {
    this.wraps = wraps;
    paragraphs = null;
  }

  void scale(float scale) {
    if (scale != this.scale) {
      this.scale = scale;
      paragraphs = null;
    }
  }

  @Override
  public Size measure(
      float knownWidth, float knownHeight, float availableWidth, float availableHeight) {
    List<Span> spans = spans(Float.isNaN(knownWidth) ? availableWidth : knownWidth);
    float widest = 0;
    for (Span span : spans) {
      widest = Math.max(widest, span.width);
    }
    return new Size(widest / scale, spans.size() * lineHeight / scale, firstBaseline / scale);
  }

  @Override
  public void placed(float width, float height) {
    List<Line> placed = new ArrayList<>();
    for (Span span : spans(width)) {
      int[] at = glyphAt[span.paragraph];
      placed.add(new Line(runs[span.paragraph].slice(at[span.from], at[span.to]), span.width,
          firstBaseline + placed.size() * lineHeight));
    }
    lines = List.copyOf(placed);
  }

  /** The lines for a box this wide in logical pixels, or {@link Measure#MAX_CONTENT} or MIN. */
  private List<Span> spans(float width) {
    prepare();
    float limit;
    if (!wraps || width == MAX_CONTENT) {
      limit = Float.POSITIVE_INFINITY;
    } else if (width == MIN_CONTENT) {
      limit = 0; // every opportunity breaks: the widest word
    } else {
      limit = width * scale + SLACK;
    }
    List<Span> spans = new ArrayList<>();
    for (int p = 0; p < paragraphs.length; p++) {
      if (!wraps) {
        spans.add(new Span(p, 0, paragraphs[p].length(), runs[p].width()));
        continue;
      }
      // Greedy: each line takes segments while they fit, and at least one.
      int start = 0;
      int end = 0;
      for (int next : breaks[p]) {
        if (end > start && width(p, start, next) > limit) {
          spans.add(new Span(p, start, end, width(p, start, end)));
          start = end;
        }
        end = next;
      }
      spans.add(new Span(p, start, paragraphs[p].length(), width(p, start, end)));
    }
    return spans;
  }

  /** The width of characters {@code from} to {@code to}, without the white space at the end. */
  private float width(int paragraph, int from, int to) {
    String text = paragraphs[paragraph];
    while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
      to--;
    }
    GlyphRun run = runs[paragraph];
    return run.pen(glyphAt[paragraph][to]) - run.pen(glyphAt[paragraph][from]);
  }

  private void prepare() {
    if (paragraphs != null) {
      return;
    }
    Font font = font();
    float pixels = size * scale;
    lineHeight = Math.round((font.ascent() + font.descent() + font.lineGap()) * pixels);
    firstBaseline = Math.round((font.ascent() + font.lineGap() / 2) * pixels);
    String[] split = string.split("\n", -1);
    runs = new GlyphRun[split.length];
    glyphAt = new int[split.length][];
    breaks = new int[split.length][];
    BreakIterator lineBreaks = BreakIterator.getLineInstance(Locale.ROOT);
    for (int p = 0; p < split.length; p++) {
      String text = split[p];
      GlyphRun run = font.shape(text, size, scale);
      runs[p] = run;
      // The first glyph of each character; clusters only grow, as text is shaped left to right.
      int[] at = new int[text.length() + 1];
      int glyph = 0;
      for (int c = 0; c <= text.length(); c++) {
        while (glyph < run.count() && run.cluster(glyph) < c) {
          glyph++;
        }
        at[c] = glyph;
      }
      glyphAt[p] = at;
      if (wraps) {
        lineBreaks.setText(text);
        List<Integer> found = new ArrayList<>();
        for (int b = lineBreaks.next(); b != BreakIterator.DONE; b = lineBreaks.next()) {
          found.add(b);
        }
        breaks[p] = found.stream().mapToInt(Integer::intValue).toArray();
      }
    }
    paragraphs = split;
  }
}
