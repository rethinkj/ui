package com.rethinkj.ui;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
 * <p>The text of an {@link El#editable} element is a field: one line, whatever the string holds,
 * measured as 0 wide so that the field's width comes from its style, centred vertically in its
 * content box, and shown with its composition and placeholder. Its caret, selection and
 * horizontal scroll come from {@link App}, which keeps them per element; the methods below that
 * return them are what a renderer draws.
 *
 * <p>Characters the font lacks come from its fallback fonts ({@link Font#withFallback}), and in a
 * window from the system's ({@link App#systemFonts}); a line holds glyphs of several fonts, on one
 * baseline, with the font's line height. A run of right-to-left text is shaped right to left but
 * is one cluster (see {@link Font#shape}): a line does not break inside it, and a caret inside it
 * stands in proportion to the graphemes before it, from its left edge. Lines are not reordered as
 * bidirectional text would be.
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

  /**
   * A rectangle in physical pixels relative to the top left of the content box, in the same
   * coordinates as the glyphs of {@link #lines} once they are moved left by {@link #scroll}.
   */
  public record Box(float x, float y, float width, float height) {}

  /** What ends a line that {@link El#ellipsis} shortens. */
  public static final String ELLIPSIS = "\u2026";

  /** The colour of the selection until {@link El#selectionColor} changes it, as 0xAARRGGBB. */
  public static final int SELECTION = 0x663B82F6;

  // A width a line may exceed its box by: layout converts what it measured back and forth, and
  // widths are on a grid of a quarter pixel.
  private static final float SLACK = 1 / 64f;

  private final String string;
  private String composed; // a field's string with the composition in it, or null for none
  private boolean field;
  private String placeholder;
  private int placeholderColor;
  private String[] features = {};
  private int caretColor;
  private int selectionColor = SELECTION;
  // What a field draws besides its glyphs; see Editor#place.
  private float scroll;
  private List<Box> selection = List.of();
  private Box caret;
  private boolean caretShown;
  private Box composition;
  private Font font;
  private float size = 14;
  private int color = 0xFF000000;
  private int background;
  private boolean wraps;
  private boolean ellipsis;
  private boolean shortened; // a line of the last placed() ends in the ellipsis
  private Font shaping; // the font with the fallbacks it shaped with
  private float scale = 1;
  private boolean systemFonts;
  private List<Line> lines = List.of();

  // What every measure shares, made on the first one: per paragraph its glyphs, the first glyph
  // of each character and the break opportunities.
  private String[] paragraphs;
  private int[] starts; // where each paragraph starts in the shown string
  private boolean placeholderShown;
  private GlyphRun[] runs;
  private int[][] glyphAt;
  private int[][] breaks;
  private float lineHeight;
  private float firstBaseline;
  // From the last placed(): the lines, the content size in physical pixels and how far a field's
  // lines are moved down to centre them.
  private List<Span> placedSpans = List.of();
  private float placedWidth;
  private float placedHeight;
  private float offsetY;

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

  /** Whether a line wider than the box ends in {@link #ELLIPSIS}; see {@link El#ellipsis}. */
  public boolean ellipsis() {
    return ellipsis;
  }

  /** Whether layout shortened a line to fit the box, so that it ends in {@link #ELLIPSIS}. */
  public boolean shortened() {
    return shortened;
  }

  /** The scale the text was shaped for, in physical pixels per logical pixel. */
  public float scale() {
    return scale;
  }

  /** The lines layout placed, top to bottom; empty before layout. */
  public List<Line> lines() {
    return lines;
  }

  /** The OpenType features set with {@link El#fontFeatures}, applied when the text is shaped. */
  public List<String> features() {
    return List.of(features);
  }

  /** Whether this is the text of an {@link El#editable} element, a single-line field. */
  public boolean isField() {
    return field;
  }

  /**
   * Whether the lines show the {@link El#placeholder} instead of the string, which is empty; then
   * they are drawn in {@link #placeholderColor}.
   */
  public boolean placeholderShown() {
    prepare();
    return placeholderShown;
  }

  /** The colour of the placeholder, as 0xAARRGGBB. */
  public int placeholderColor() {
    return placeholderColor;
  }

  /** The caret's colour, as 0xAARRGGBB: the text colour unless {@link El#caretColor} set one. */
  public int caretColor() {
    return caretColor != 0 ? caretColor : color;
  }

  /** The colour of the selection, drawn behind the text, as 0xAARRGGBB. */
  public int selectionColor() {
    return selectionColor;
  }

  /**
   * How far a field's text is moved left so that its caret is in view, in physical pixels; 0 for
   * other text. The glyphs of {@link #lines} are drawn this much further left.
   */
  public float scroll() {
    return scroll;
  }

  /** The selected part of a field while it has the focus, one rectangle per line; else empty. */
  public List<Box> selection() {
    return selection;
  }

  /**
   * The caret of a focused field, a thin rectangle the height of a line, or null while it is off:
   * it blinks, and only the focused field has one.
   */
  public Box caret() {
    return caretShown ? caret : null;
  }

  /**
   * The line under the composition of an input method in progress, drawn in the text colour, or
   * null when there is none.
   */
  public Box composition() {
    return composition;
  }

  void font(Font font) {
    this.font = font;
    paragraphs = null;
  }

  void features(String[] features) {
    this.features = features.clone();
    paragraphs = null;
  }

  void field(boolean field) {
    this.field = field;
    paragraphs = null;
  }

  void placeholder(String text, int argb) {
    this.placeholder = text;
    this.placeholderColor = argb;
    paragraphs = null;
  }

  void caretColor(int argb) {
    this.caretColor = argb;
  }

  void selectionColor(int argb) {
    this.selectionColor = argb;
  }

  /** For {@link Editor}: shows a field's string with a composition in it, or as it is with null. */
  void compose(String shown) {
    if (!Objects.equals(shown, composed)) {
      composed = shown;
      paragraphs = null;
    }
  }

  /** For {@link Editor}: what a field draws besides its glyphs. */
  void decorate(float scroll, List<Box> selection, Box caret, Box composition) {
    this.scroll = scroll;
    this.selection = selection;
    this.caret = caret;
    this.composition = composition;
  }

  /** For {@link App}, which blinks the caret. */
  void showCaret(boolean shown) {
    caretShown = shown;
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

  void ellipsis(boolean on) {
    this.ellipsis = on;
  }

  void scale(float scale) {
    if (scale != this.scale) {
      this.scale = scale;
      paragraphs = null;
    }
  }

  /** For {@link App}: whether characters the fonts lack come from the system's fonts. */
  void systemFonts(boolean on) {
    if (on != systemFonts) {
      systemFonts = on;
      paragraphs = null;
    }
  }

  @Override
  public Size measure(
      float knownWidth, float knownHeight, float availableWidth, float availableHeight) {
    if (field) {
      // shortcut: the baseline is that of a line at the top; a field's line is centred in its
      // content box, which measure does not know yet. Pass the box's height when a field has to
      // line up with text beside it by baseline.
      prepare();
      return new Size(0, lineHeight / scale, firstBaseline / scale);
    }
    List<Span> spans = spans(Float.isNaN(knownWidth) ? availableWidth : knownWidth);
    float widest = 0;
    for (Span span : spans) {
      widest = Math.max(widest, span.width);
    }
    return new Size(widest / scale, spans.size() * lineHeight / scale, firstBaseline / scale);
  }

  @Override
  public void placed(float width, float height) {
    List<Span> spans = spans(width);
    placedSpans = spans;
    placedWidth = width * scale;
    placedHeight = height * scale;
    offsetY = field ? Math.round((placedHeight - spans.size() * lineHeight) / 2) : 0;
    List<Line> placed = new ArrayList<>();
    shortened = false;
    for (Span span : spans) {
      float baseline = offsetY + firstBaseline + placed.size() * lineHeight;
      if (ellipsis && !field && span.width > placedWidth + SLACK) {
        placed.add(shorten(span, baseline));
        shortened = true;
        continue;
      }
      int[] at = glyphAt[span.paragraph];
      placed.add(new Line(runs[span.paragraph].slice(at[span.from], at[span.to]), span.width,
          baseline));
    }
    lines = List.copyOf(placed);
  }

  /**
   * A line that is wider than the box, shortened to the most graphemes that fit with {@link
   * #ELLIPSIS} after them, shaped anew so that the ellipsis kerns with what it follows. Where not
   * even the ellipsis fits, it is the ellipsis alone, which the box clips.
   */
  private Line shorten(Span span, float baseline) {
    // shortcut: a right-to-left run is one cluster (see Font#shape), so the line ends before it
    // rather than inside it; cut inside it when right-to-left text needs it.
    String text = paragraphs[span.paragraph];
    float room = placedWidth + SLACK - shaping.shape(ELLIPSIS, size, scale, features).width();
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(text);
    List<Integer> fits = new ArrayList<>();
    fits.add(span.from);
    for (int b = chars.following(span.from); b != BreakIterator.DONE && b <= span.to;
        b = chars.next()) {
      if (width(span.paragraph, span.from, b) > room) {
        break;
      }
      fits.add(b);
    }
    for (int i = fits.size() - 1; ; i--) {
      String shown = text.substring(span.from, fits.get(i)).stripTrailing() + ELLIPSIS;
      GlyphRun run = shaping.shape(shown, size, scale, features);
      if (run.width() <= placedWidth + SLACK || i == 0) {
        return new Line(run, run.width(), baseline);
      }
    }
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
    String shown = composed != null ? composed : string;
    placeholderShown = field && shown.isEmpty() && placeholder != null;
    if (placeholderShown) {
      shown = placeholder;
    }
    // A field is one line: a line feed in it shows as the font's glyph for it.
    String[] split = field ? new String[] {shown} : shown.split("\n", -1);
    starts = new int[split.length];
    for (int p = 1; p < split.length; p++) {
      starts[p] = starts[p - 1] + split[p - 1].length() + 1;
    }
    runs = new GlyphRun[split.length];
    glyphAt = new int[split.length][];
    breaks = new int[split.length][];
    BreakIterator lineBreaks = BreakIterator.getLineInstance(Locale.ROOT);
    shaping = systemFonts ? font.withSystemFonts() : font;
    for (int p = 0; p < split.length; p++) {
      String text = split[p];
      GlyphRun run = shaping.shape(text, size, scale, features);
      runs[p] = run;
      // The first glyph of each character; clusters only grow, see GlyphRun#cluster.
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

  // Positions in the shown string, for Editor. All in physical pixels from the top left of the
  // content box, before the scroll; they need a layout first.

  /** The content box's width in physical pixels, as layout placed it. */
  float contentWidth() {
    return placedWidth;
  }

  float lineHeight() {
    prepare();
    return lineHeight;
  }

  /** The number of lines placed; 0 before layout. */
  int lineCount() {
    return placedSpans.size();
  }

  /** The line that position {@code index} of the shown string is on; at a wrap, the later one. */
  int lineOf(int index) {
    for (int l = placedSpans.size() - 1; l > 0; l--) {
      Span span = placedSpans.get(l);
      if (starts[span.paragraph] + span.from <= index) {
        return l;
      }
    }
    return 0;
  }

  /** The top of a line. */
  float lineTop(int line) {
    return offsetY + line * lineHeight;
  }

  /** The baseline of a line. */
  float baseline(int line) {
    return lineTop(line) + firstBaseline;
  }

  /** The width of a line's glyphs, without the spaces at the end of a wrapped line. */
  float lineWidth(int line) {
    return placedSpans.isEmpty() ? 0 : placedSpans.get(line).width;
  }

  /** The first and the last position of a line in the shown string. */
  int lineStart(int line) {
    Span span = placedSpans.get(line);
    return starts[span.paragraph] + span.from;
  }

  int lineEnd(int line) {
    Span span = placedSpans.get(line);
    return starts[span.paragraph] + span.to;
  }

  /**
   * Where a caret at position {@code index} of the shown string stands on its line. Positions
   * between clusters are the pen position of the glyph that starts the next one, so kerning is
   * included; a position inside a cluster, such as between the two letters of a ligature, lies
   * inside its glyphs in proportion to the graphemes before it.
   */
  float x(int index) {
    if (placedSpans.isEmpty()) {
      return 0;
    }
    Span span = placedSpans.get(lineOf(index));
    int p = span.paragraph;
    int at = Math.clamp(index - starts[p], span.from, span.to);
    return pen(p, at) - pen(p, span.from);
  }

  /** The x of position {@code at} of a paragraph, from the start of its glyphs. */
  private float pen(int p, int at) {
    GlyphRun run = runs[p];
    String text = paragraphs[p];
    int g = glyphAt[p][at];
    if (at == text.length() || g < run.count() && run.cluster(g) == at) {
      return run.pen(g);
    }
    if (g == 0) {
      return 0; // no glyph starts before it
    }
    // Inside the cluster of glyph g - 1: glyphs first to g, characters start to end.
    int start = run.cluster(g - 1);
    int first = glyphAt[p][start];
    int end = g < run.count() ? run.cluster(g) : text.length();
    int graphemes = graphemes(text, start, end);
    float from = run.pen(first);
    return graphemes == 0 ? from
        : from + (run.pen(g) - from) * graphemes(text, start, at) / graphemes;
  }

  /** The number of grapheme boundaries after {@code from} up to and with {@code to}. */
  private static int graphemes(String text, int from, int to) {
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(text);
    int count = 0;
    for (int b = chars.following(from); b != BreakIterator.DONE && b <= to; b = chars.next()) {
      count++;
    }
    return count;
  }

  /** The line at a height, clamped to the first and the last. */
  int lineAt(float y) {
    if (placedSpans.isEmpty()) {
      return 0;
    }
    prepare();
    return Math.clamp((int) Math.floor((y - offsetY) / lineHeight), 0, placedSpans.size() - 1);
  }

  /** The grapheme boundary of the shown string nearest to a point. */
  int nearest(float x, float y) {
    if (placedSpans.isEmpty()) {
      return 0;
    }
    int line = lineAt(y);
    int best = lineStart(line);
    float distance = Float.POSITIVE_INFINITY;
    for (int b : boundaries(line)) {
      float d = Math.abs(x(b) - x);
      if (d < distance) {
        distance = d;
        best = b;
      }
    }
    return best;
  }

  /**
   * The start of the grapheme of the shown string under a point: the last one that starts left of
   * it, the first one left of the line.
   */
  int hit(float x, float y) {
    if (placedSpans.isEmpty()) {
      return 0;
    }
    int line = lineAt(y);
    List<Integer> bounds = boundaries(line);
    int found = bounds.getFirst();
    for (int i = 0; i < bounds.size() - 1; i++) {
      if (x(bounds.get(i)) <= x) {
        found = bounds.get(i);
      }
    }
    return found;
  }

  /** The grapheme boundaries of a line, from its start to its end. */
  private List<Integer> boundaries(int line) {
    Span span = placedSpans.get(line);
    String text = paragraphs[span.paragraph];
    int start = starts[span.paragraph];
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(text);
    List<Integer> found = new ArrayList<>();
    found.add(start + span.from);
    for (int b = chars.following(span.from); b != BreakIterator.DONE && b <= span.to;
        b = chars.next()) {
      found.add(start + b);
    }
    return found;
  }
}
