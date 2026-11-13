package com.rethinkj.ui;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>The text of an {@link El#editable} element is a field. A single-line field is one line,
 * whatever the string holds, measured as 0 wide so that the field's width comes from its style,
 * centred vertically in its content box, and shown with its composition and placeholder. Its
 * caret, selection and horizontal scroll come from {@link App}, which keeps them per element; the
 * methods below that return them are what a renderer draws.
 *
 * <p>A {@link El#multiline multi-line} field has a line per line feed of its string, wrapped or
 * not, from the top of its content box, and is a scroll area of its own: {@link #lines} holds only
 * the rows in view and a few around them, moved up by the vertical scroll, and only their lines
 * are shaped, whatever the length of the text. A tab advances to the next multiple of {@link
 * El#tabSize} spaces. The field's {@link Editor} keeps where the lines start ({@link Lines}), so
 * that a new tree finds a line without looking through the string.
 *
 * <p>A {@link El#masked masked} field shows one {@link #MASK} per grapheme of its string, and of
 * the composition in it, and shapes only that: {@link #string} returns the mask, and the
 * positions of the methods for {@link Editor} stay indexes into the real string, which grapheme
 * {@code n} of it maps to mask character {@code n}.
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
   * @param baseline the distance from the top of the content box down to the line's baseline; in
   *     a multi-line field after its vertical scroll, so negative for a line above the box
   * @param colors the colour of each glyph as 0xAARRGGBB, where a {@link Highlighter} coloured
   *     the line; null when every glyph has the text's colour
   * @param x where the pen starts, from the left edge of the content box: 0, or further right in
   *     text {@link El#textAlign aligned} to the centre or the end, on the grid of glyph positions
   */
  public record Line(GlyphRun glyphs, float width, float baseline, int[] colors, float x) {

    /** A line at the left edge whose glyphs all have the text's colour. */
    public Line(GlyphRun glyphs, float width, float baseline) {
      this(glyphs, width, baseline, null, 0);
    }
  }

  /**
   * A rectangle in physical pixels relative to the top left of the content box, in the same
   * coordinates as the glyphs of {@link #lines} once they are moved left by {@link #scroll}.
   */
  public record Box(float x, float y, float width, float height) {}

  /**
   * A rectangle drawn behind the glyphs of a multi-line field, before its selection: the
   * background of a line or a range, or the line under a range, from a {@link Highlighter} or a
   * {@link Decorator}.
   *
   * @param box where, as the selection's rectangles are
   * @param color as 0xAARRGGBB
   */
  public record Mark(Box box, int color) {}

  /** What ends a line that {@link El#ellipsis} shortens. */
  public static final String ELLIPSIS = "…";

  /** What a {@link El#masked} field shows for each grapheme of its string: a bullet, U+2022. */
  public static final int MASK = 0x2022;

  /** The colour of the selection until {@link El#selectionColor} changes it, as 0xAARRGGBB. */
  public static final int SELECTION = 0x663B82F6;

  // A width a line may exceed its box by: layout converts what it measured back and forth, and
  // widths are on a grid of a quarter pixel.
  private static final float SLACK = 1 / 64f;

  // The rows a multi-line field lays out above and below its content box: they cover its padding
  // and a scroll of a few rows without a new layout.
  private static final int MARGIN_ROWS = 3;

  // What a multi-line field estimates the advance of a character from, until it has shaped a line.
  private static final String SAMPLE = "SELECT name, id FROM customers WHERE id = 42 the quick fox";

  private final String string;
  private String composed; // a field's string with the composition in it, or null for none
  private boolean field;
  private boolean unfocused; // a field without the focus, which shows its start
  private boolean multiline;
  private boolean masked;
  private int mask = MASK;
  private String placeholder;
  private int placeholderColor;
  private String[] features = {};
  private int caretColor;
  private int selectionColor = SELECTION;
  private int tabSize = 4;
  private Style.Align align = Style.Align.START;
  private float[] lineX = {}; // per line placed: where its pen starts, after the alignment
  private Highlighter highlighter;
  private Object highlightKey;
  private Decorator decorator;
  private List<Mark> marks = List.of();
  private int visibleLines = 1;
  // What a field draws besides its glyphs; see Editor#place.
  private float scroll;
  private List<Box> selection = List.of();
  private Box caret;
  private boolean caretShown;
  private Box composition;
  private int underlineFrom; // a range El#underline marks, from == to for none
  private int underlineTo;
  private Font font;
  private float size = 14;
  private int color = 0xFF000000;
  // The colour the view gave, which a highlighter's glyphs without a colour of their own are
  // shaped with; `color` is the one shown, which motion moves towards it.
  private int viewColor = 0xFF000000;
  private int background;
  private boolean wraps;
  private boolean ellipsis;
  private boolean shortened; // a line of the last placed() ends in the ellipsis
  private Font shaping; // the font with the fallbacks it shaped with
  private float scale = 1;
  private boolean systemFonts;
  private List<Line> lines = List.of();

  // What every measure shares, made on the first one: the paragraphs with their glyphs, the first
  // glyph of each character and the break opportunities. A multi-line field shapes its lines on
  // demand instead (see below).
  private boolean prepared;
  private Para[] paras;
  private int[] starts; // where each paragraph starts in the shown string
  private boolean placeholderShown;
  // A masked field's grapheme boundaries in its string with the composition, from 0 to the end;
  // the glyphs show the mask character once per grapheme. Null when not masked.
  private int[] maskBounds;
  private float lineHeight;
  private float firstBaseline;
  // From the last placed(): the lines, the content size in physical pixels and how far a field's
  // lines are moved down to centre them.
  private List<Span> placedSpans = List.of();
  private boolean laidOut;
  private float placedWidth;
  private float placedHeight;
  private float offsetY;

  // A multi-line field: the string as it edits it, with line feeds only, and its lines, from the
  // editor or made here; the shown string and its lines, which a composition or the placeholder
  // changes; the lines shaped so far, by index; the scroll in physical pixels, and the rows laid
  // out for it.
  private String edited;
  private Lines editedLines;
  private Lines composedLines;
  private String shown;
  private Lines shownLines;
  private final Map<Integer, Para> shapedLines = new HashMap<>();
  private float tabStop;
  private float advance; // of an average character, for the estimate of rows
  private float viewX;
  private float viewY;
  private boolean viewStale = true;
  private List<Row> rows = List.of();
  private int firstInView;
  private int lastInView;

  /** A line of the text: characters {@code from} to {@code to} of a paragraph. */
  private record Span(int paragraph, int from, int to, float width) {}

  /**
   * Where a line of a multi-line field begins, for its gutter: its first row's top, in physical
   * pixels from the top of the content box, after the scroll.
   */
  record Start(int line, float top) {}

  /** A row of a multi-line field as laid out: row {@code row} of line {@code line}. */
  private record Row(int line, int row, Para para, double top) {}

  /** What the rows of wrapped lines depend on: another value breaks them anew. */
  private record Wrapping(float width, Font font, float size, float scale, List<String> features,
      int tabSize, boolean systemFonts) {}

  Text(String string) {
    if (string == null) {
      throw new IllegalArgumentException("string is null; use \"\" for an empty text");
    }
    this.string = string;
  }

  /**
   * The string as the element shows it: the one {@link El#text} got, except for a {@link
   * El#masked masked} field, whose string is one mask character per grapheme of the app's, and a
   * {@link El#multiline multi-line} field, whose carriage returns are line feeds. The tree dump,
   * selectors and error messages print this.
   */
  public String string() {
    return masked ? mask(string) : multiline ? edited() : string;
  }

  /** For {@link Editor}: the string the view gave the element, also of a masked field. */
  String value() {
    return string;
  }

  /** Whether this is a field that shows a mask instead of its string; see {@link El#masked}. */
  public boolean masked() {
    return masked;
  }

  /** Whether this is a multi-line field; see {@link El#multiline}. */
  public boolean multiline() {
    return multiline;
  }

  /** The text as a masked field shows it: one mask character per grapheme. */
  String mask(String text) {
    return Character.toString(mask).repeat(graphemes(text, 0, text.length()));
  }

  /**
   * For the tree dump: a position of a masked field's string as a position of its mask, which
   * counts graphemes; the position itself otherwise.
   */
  int maskIndex(String text, int index) {
    return masked ? graphemes(text, 0, index) * Character.charCount(mask) : index;
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

  /**
   * The lines layout placed, top to bottom; empty before layout. A multi-line field has the rows
   * in view and a few around them.
   */
  public List<Line> lines() {
    if (multiline && laidOut && viewStale) {
      layOut();
    }
    return lines;
  }

  /** Whether a {@link Highlighter} colours the text; see {@link El#highlight}. */
  public boolean highlighted() {
    return highlighter != null;
  }

  /** The OpenType features set with {@link El#fontFeatures}, applied when the text is shaped. */
  public List<String> features() {
    return List.of(features);
  }

  /** Whether this is the text of an {@link El#editable} element, a field. */
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
   * other text. The glyphs of {@link #lines} are drawn this much further left. A multi-line field
   * moves by its horizontal scroll offset, on the grid of glyph positions.
   */
  public float scroll() {
    return multiline ? viewX : scroll;
  }

  /**
   * The selected part of a field while it has the focus, one rectangle per line; else empty. A
   * multi-line field has those of the rows in view; on a row whose end is selected, its line feed
   * or the end of a wrapped row, the rectangle reaches the right edge of the content box.
   */
  public List<Box> selection() {
    return selection;
  }

  /**
   * What a multi-line field draws behind its selection on the rows of {@link #lines}: the
   * backgrounds of lines, then those of ranges, then the lines under ranges, each in the order
   * the lines and their {@link Spans} have them; empty for other text.
   */
  public List<Mark> marks() {
    lines();
    return marks;
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

  private void invalidate() {
    prepared = false;
    paras = null;
    shapedLines.clear();
    viewStale = true;
  }

  void font(Font font) {
    this.font = font;
    invalidate();
  }

  void features(String[] features) {
    this.features = features.clone();
    invalidate();
  }

  void field(boolean field) {
    this.field = field;
    invalidate();
  }

  /** Whether this single-line field lacks the focus, so that {@link El#ellipsis} shortens it. */
  void unfocused(boolean unfocused) {
    this.unfocused = unfocused;
  }

  void multiline(boolean multiline) {
    this.multiline = multiline;
    invalidate();
  }

  void masked(boolean masked) {
    this.masked = masked;
    invalidate();
  }

  void mask(int character) {
    String one = Character.isValidCodePoint(character) ? Character.toString(character) : "";
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(one + one);
    // Two of them must be two graphemes: no combining mark, joiner or half of a flag.
    if (one.isEmpty() || Character.isWhitespace(character) || Character.isISOControl(character)
        || !chars.isBoundary(one.length())) {
      throw new IllegalArgumentException(
          "The mask of a field must be a visible character that stands alone, such as '*' or"
              + " U+25CF; U+" + Integer.toHexString(character).toUpperCase(Locale.ROOT)
              + " is not.");
    }
    this.mask = character;
    invalidate();
  }

  void placeholder(String text, int argb) {
    this.placeholder = text;
    this.placeholderColor = argb;
    invalidate();
  }

  void caretColor(int argb) {
    this.caretColor = argb;
  }

  void selectionColor(int argb) {
    this.selectionColor = argb;
  }

  void tabSize(int columns) {
    this.tabSize = columns;
    invalidate();
  }

  int tabSize() {
    return tabSize;
  }

  Decorator decorator() {
    return decorator;
  }

  void decorator(Decorator decorator) {
    this.decorator = decorator;
    viewStale = true;
  }

  void align(Style.Align align) {
    this.align = align;
  }

  /** How lines are aligned in the content box; see {@link El#textAlign}. */
  public Style.Align align() {
    return align;
  }

  void highlight(Object key, Highlighter highlighter) {
    this.highlightKey = key;
    this.highlighter = highlighter;
    invalidate();
  }

  void visibleLines(int lines) {
    this.visibleLines = lines;
  }

  /** For {@link Editor}: shows a field's string with a composition in it, or as it is with null. */
  void compose(String shown) {
    if (!Objects.equals(shown, composed)) {
      composed = shown;
      invalidate();
    }
  }

  /**
   * For {@link Editor}: shows a multi-line field's string with a composition in it, with the lines
   * of that string; null for neither.
   */
  void compose(String shown, Lines lines) {
    if (shown != composed || lines != composedLines) {
      composed = shown;
      composedLines = lines;
      invalidate();
    }
  }

  /**
   * For {@link Editor}: shows a placed field's string without its composition, in lines placed
   * again in the same box, for a field that leaves the tree.
   */
  void dropComposition() {
    if (composed != null) {
      compose(null, null);
      placed(placedWidth / scale, placedHeight / scale);
    }
  }

  /**
   * For {@link Editor}: the string of a multi-line field as it edits it, with line feeds only, and
   * its lines, which the editor keeps across trees.
   */
  void edited(String text, Lines lines) {
    if (text != edited || lines != editedLines) {
      edited = text;
      editedLines = lines;
      invalidate();
    }
  }

  /** The string of a multi-line field with line feeds only. */
  private String edited() {
    if (edited == null) {
      edited = Editor.multiLine(string);
    }
    return edited;
  }

  /**
   * For {@link Editor}: shows the selection, caret and composition of a field, as rectangles in
   * the coordinates of {@link Box}.
   */
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
    invalidate();
  }

  void color(int argb) {
    this.color = argb;
    this.viewColor = argb;
  }

  /** The colour shown while motion moves it towards the view's; see {@link #viewColor}. */
  void shownColor(int argb) {
    this.color = argb;
  }

  void background(int argb) {
    this.background = argb;
  }

  void wraps(boolean wraps) {
    this.wraps = wraps;
    invalidate();
  }

  void ellipsis(boolean on) {
    this.ellipsis = on;
  }

  void scale(float scale) {
    if (scale != this.scale) {
      this.scale = scale;
      invalidate();
    }
  }

  /** For {@link App}: whether characters the fonts lack come from the system's fonts. */
  void systemFonts(boolean on) {
    if (on != systemFonts) {
      systemFonts = on;
      invalidate();
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
      if (multiline && visibleLines == 0) { // El#fitLines: every row at the width
        float width = Float.isNaN(knownWidth) ? availableWidth : knownWidth;
        return new Size(0, fittedRows(width) * lineHeight / scale, firstBaseline / scale);
      }
      int high = multiline ? visibleLines : 1;
      return new Size(0, high * lineHeight / scale, firstBaseline / scale);
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
    if (multiline) {
      prepare();
      laidOut = true;
      placedWidth = width * scale;
      placedHeight = height * scale;
      shortened = false;
      viewStale = true;
      return;
    }
    List<Span> spans = spans(width);
    placedSpans = spans;
    laidOut = true;
    placedWidth = width * scale;
    placedHeight = height * scale;
    offsetY = field ? Math.round((placedHeight - spans.size() * lineHeight) / 2) : 0;
    List<Line> placed = new ArrayList<>();
    shortened = false;
    lineX = new float[spans.size()];
    for (Span span : spans) {
      float baseline = offsetY + firstBaseline + placed.size() * lineHeight;
      Line line;
      if (ellipsis && (!field || unfocused) && span.width > placedWidth + SLACK) {
        line = shorten(span, baseline);
        shortened = true;
      } else {
        Para para = paras[span.paragraph];
        line = new Line(para.slice(span.from, span.to), span.width, baseline,
            para.colors(span.from, span.to), 0);
      }
      float x = aligned(line.width());
      lineX[placed.size()] = x;
      placed.add(x == 0 ? line : new Line(line.glyphs(), line.width(), baseline, line.colors(), x));
    }
    lines = List.copyOf(placed);
    if (underlineTo > underlineFrom && !field && !spans.isEmpty()) {
      int line = lineOf(underlineFrom);
      float from = x(underlineFrom);
      composition = new Box(from, baseline(line)
          + Math.round(font().descent() * size * scale / 2), x(underlineTo) - from,
          Math.max(1, Math.round(scale)));
    }
  }

  /**
   * Where a line this wide starts in the content box as the alignment puts it, on the grid of
   * glyph positions; 0 in a field, which scrolls instead.
   */
  private float aligned(float width) {
    float share = switch (align) {
      case CENTER -> 0.5f;
      case END, FLEX_END -> 1;
      default -> 0;
    };
    if (share == 0 || field) {
      return 0;
    }
    double grid = scale >= 1.5 && !font().wholePixels() ? 4 : 1;
    return (float) (Math.floor(Math.max(placedWidth - width, 0) * share * grid) / grid);
  }

  void underline(int from, int to) {
    underlineFrom = from;
    underlineTo = to;
  }

  /**
   * A line that is wider than the box, shortened to the most graphemes that fit with {@link
   * #ELLIPSIS} after them, shaped anew so that the ellipsis kerns with what it follows. Where not
   * even the ellipsis fits, it is the ellipsis alone, which the box clips.
   */
  private Line shorten(Span span, float baseline) {
    // shortcut: a right-to-left run is one cluster (see Font#shape), so the line ends before it
    // rather than inside it; cut inside it when right-to-left text needs it.
    Para para = paras[span.paragraph];
    String text = para.text;
    float room = placedWidth + SLACK - shaping.shape(ELLIPSIS, size, scale, features).width();
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(text);
    List<Integer> fits = new ArrayList<>();
    fits.add(span.from);
    for (int b = chars.following(span.from); b != BreakIterator.DONE && b <= span.to;
        b = chars.next()) {
      if (para.width(span.from, b) > room) {
        break;
      }
      fits.add(b);
    }
    for (int i = fits.size() - 1; ; i--) {
      String kept = text.substring(span.from, fits.get(i)).stripTrailing();
      // The ranges of the kept part stay; the ellipsis has the text's colour and font.
      Para shortened = shape(kept + ELLIPSIS, para.spans, para.offset + span.from, kept.length());
      GlyphRun run = shortened.run;
      if (run.width() <= placedWidth + SLACK || i == 0) {
        return new Line(run, run.width(), baseline, shortened.colors, 0);
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
    for (int p = 0; p < paras.length; p++) {
      Para para = paras[p];
      if (!wraps) {
        spans.add(new Span(p, 0, para.text.length(), para.run.width()));
        continue;
      }
      int[] rowStarts = para.breakInto(limit);
      for (int r = 0; r < rowStarts.length; r++) {
        int to = r + 1 < rowStarts.length ? rowStarts[r + 1] : para.text.length();
        spans.add(new Span(p, rowStarts[r], to, para.rowWidths[r]));
      }
    }
    return spans;
  }

  private void prepare() {
    if (prepared) {
      return;
    }
    Font font = font();
    float pixels = size * scale;
    lineHeight = Math.round((font.ascent() + font.descent() + font.lineGap()) * pixels);
    firstBaseline = Math.round((font.ascent() + font.lineGap() / 2) * pixels);
    shaping = systemFonts ? font.withSystemFonts() : font;
    prepared = true;
    if (multiline) {
      prepareLines();
      return;
    }
    String shown = composed != null ? composed : string;
    placeholderShown = field && shown.isEmpty() && placeholder != null;
    maskBounds = null;
    if (placeholderShown) {
      shown = placeholder;
    } else if (masked) {
      // Only the mask is shaped, so that nothing of the string reaches the fonts and their caches.
      List<Integer> bounds = new ArrayList<>();
      bounds.add(0);
      BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
      chars.setText(shown);
      for (int b = chars.following(0); b != BreakIterator.DONE; b = chars.next()) {
        bounds.add(b);
      }
      maskBounds = bounds.stream().mapToInt(Integer::intValue).toArray();
      shown = Character.toString(mask).repeat(maskBounds.length - 1);
    }
    // A field is one line: a line feed in it shows as the font's glyph for it.
    String[] split = field ? new String[] {shown} : shown.split("\n", -1);
    starts = new int[split.length];
    for (int p = 1; p < split.length; p++) {
      starts[p] = starts[p - 1] + split[p - 1].length() + 1;
    }
    // The whole string is one line for the highlighter; each paragraph takes its part.
    Spans whole = null;
    if (highlighter != null && !placeholderShown && !masked) {
      whole = new Spans(shown.length(), false);
      highlighter.line(shown, null, whole);
    }
    paras = new Para[split.length];
    for (int p = 0; p < split.length; p++) {
      paras[p] = shape(split[p], whole, starts[p], split[p].length());
    }
  }

  /**
   * Shapes a paragraph, with tab stops in a multi-line field, and with the colours and fonts of
   * {@code spans}, whose position {@code offset} is the paragraph's start, as far as {@code limit}
   * in the paragraph: what lies after it, an ellipsis, has the text's.
   */
  private Para shape(String text, Spans spans, int offset, int limit) {
    boolean tabbed = multiline && text.indexOf('\t') >= 0;
    String shaped = tabbed ? text.replace('\t', ' ') : text;
    GlyphRun run;
    if (spans != null && spans.has(Spans.FONT)) {
      run = shapeFonts(shaped, spans, offset, limit);
    } else {
      run = shaping.shape(shaped, size, scale, features);
    }
    if (tabbed) {
      int[] tabs = new int[text.length()];
      int n = 0;
      for (int i = text.indexOf('\t'); i >= 0; i = text.indexOf('\t', i + 1)) {
        tabs[n++] = i;
      }
      run = run.tabbed(Arrays.copyOf(tabs, n), tabStop);
    }
    int[] colors = null;
    if (spans != null && spans.has(Spans.COLOR)) {
      int[] byChar = new int[text.length()];
      Arrays.fill(byChar, viewColor);
      for (int i = 0; i < spans.count; i++) {
        if (spans.kinds[i] == Spans.COLOR) {
          Arrays.fill(byChar, Math.clamp(spans.froms[i] - offset, 0, limit),
              Math.clamp(spans.tos[i] - offset, 0, limit), spans.colors[i]);
        }
      }
      colors = new int[run.count()];
      for (int g = 0; g < colors.length; g++) {
        int c = run.cluster(g);
        colors[g] = c < byChar.length ? byChar[c] : viewColor;
      }
    }
    int[] breaks = null;
    if (wraps) {
      BreakIterator lineBreaks = BreakIterator.getLineInstance(Locale.ROOT);
      lineBreaks.setText(text);
      List<Integer> found = new ArrayList<>();
      for (int b = lineBreaks.next(); b != BreakIterator.DONE; b = lineBreaks.next()) {
        found.add(b);
      }
      breaks = found.stream().mapToInt(Integer::intValue).toArray();
    }
    return new Para(text, run, breaks, colors, spans, offset);
  }

  /**
   * Shapes a string whose ranges have fonts of their own: each stretch of one font on its own,
   * the text's font with its fallbacks where no range sets one, then joined.
   */
  private GlyphRun shapeFonts(String text, Spans spans, int offset, int limit) {
    Font[] byChar = new Font[text.length()];
    for (int i = 0; i < spans.count; i++) {
      if (spans.kinds[i] == Spans.FONT) {
        Arrays.fill(byChar, Math.clamp(spans.froms[i] - offset, 0, limit),
            Math.clamp(spans.tos[i] - offset, 0, limit), spans.fonts[i]);
      }
    }
    List<GlyphRun> parts = new ArrayList<>();
    List<Integer> from = new ArrayList<>();
    for (int start = 0, end; start < text.length() || parts.isEmpty(); start = end) {
      Font font = start < text.length() ? byChar[start] : null;
      end = start + 1;
      while (end < text.length() && byChar[end] == font) {
        end++;
      }
      end = Math.min(end, text.length());
      Font shaper = font == null ? shaping : systemFonts ? font.withSystemFonts() : font;
      parts.add(shaper.shape(text.substring(start, end), size, scale, features));
      from.add(start);
    }
    return GlyphRun.concat(parts, from.stream().mapToInt(Integer::intValue).toArray());
  }

  /**
   * A paragraph shaped: its glyphs, the first glyph of each of its characters, where its lines may
   * break, and the rows the last width broke it into.
   */
  private static final class Para {
    final String text;
    final GlyphRun run;
    final int[] colors; // per glyph, or null for the text's colour
    final Spans spans; // what the highlighter said about it, or null
    final int offset; // where the paragraph starts in the positions of spans
    final int[] glyphAt;
    final int[] breaks; // null without wrapping
    float limit = Float.NaN; // the width rowStarts are for
    int[] rowStarts = {0};
    float[] rowWidths;

    Para(String text, GlyphRun run, int[] breaks, int[] colors, Spans spans, int offset) {
      this.text = text;
      this.run = run;
      this.breaks = breaks;
      this.colors = colors;
      this.spans = spans;
      this.offset = offset;
      // The first glyph of each character; clusters only grow, see GlyphRun#cluster.
      int[] at = new int[text.length() + 1];
      int glyph = 0;
      for (int c = 0; c <= text.length(); c++) {
        while (glyph < run.count() && run.cluster(glyph) < c) {
          glyph++;
        }
        at[c] = glyph;
      }
      this.glyphAt = at;
      this.rowWidths = new float[] {run.width()};
    }

    /**
     * Breaks into rows no wider than {@code limit} where it can, greedily: each row takes segments
     * while they fit, and at least one. Returns where each row starts.
     */
    int[] breakInto(float limit) {
      if (breaks == null || limit == Float.POSITIVE_INFINITY) {
        rowStarts = new int[] {0};
        rowWidths = new float[] {breaks == null ? run.width() : width(0, text.length())};
        this.limit = limit;
        return rowStarts;
      }
      if (limit == this.limit) {
        return rowStarts;
      }
      List<Integer> found = new ArrayList<>();
      List<Float> widths = new ArrayList<>();
      int start = 0;
      int end = 0;
      for (int next : breaks) {
        if (end > start && width(start, next) > limit) {
          found.add(start);
          widths.add(width(start, end));
          start = end;
        }
        end = next;
      }
      found.add(start);
      widths.add(width(start, end));
      rowStarts = found.stream().mapToInt(Integer::intValue).toArray();
      rowWidths = new float[widths.size()];
      for (int i = 0; i < rowWidths.length; i++) {
        rowWidths[i] = widths.get(i);
      }
      this.limit = limit;
      return rowStarts;
    }

    int rowCount() {
      return rowStarts.length;
    }

    int rowStart(int row) {
      return rowStarts[row];
    }

    /** Where a row ends: the start of the next, or the end of the paragraph. */
    int rowEnd(int row) {
      return row + 1 < rowStarts.length ? rowStarts[row + 1] : text.length();
    }

    /** The row that position {@code at} is on; at a wrap, the later one. */
    int rowAt(int at) {
      int found = Arrays.binarySearch(rowStarts, at);
      return found >= 0 ? found : Math.max(-found - 2, 0);
    }

    GlyphRun slice(int from, int to) {
      return run.slice(glyphAt[from], glyphAt[to]);
    }

    /** The colours of the glyphs of characters {@code from} to {@code to}, or null. */
    int[] colors(int from, int to) {
      return colors == null ? null : Arrays.copyOfRange(colors, glyphAt[from], glyphAt[to]);
    }

    /** The width of characters {@code from} to {@code to}, without the white space at the end. */
    float width(int from, int to) {
      while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
        to--;
      }
      return run.pen(glyphAt[to]) - run.pen(glyphAt[from]);
    }

    /** The x of position {@code at}, from the start of the glyphs. */
    float pen(int at) {
      int g = glyphAt[at];
      if (at == text.length() || g < run.count() && run.cluster(g) == at) {
        return run.pen(g);
      }
      if (g == 0) {
        return 0; // no glyph starts before it
      }
      // Inside the cluster of glyph g - 1: glyphs first to g, characters start to end.
      int start = run.cluster(g - 1);
      int first = glyphAt[start];
      int end = g < run.count() ? run.cluster(g) : text.length();
      int graphemes = graphemes(text, start, end);
      float from = run.pen(first);
      return graphemes == 0 ? from
          : from + (run.pen(g) - from) * graphemes(text, start, at) / graphemes;
    }

    /** The grapheme boundaries from {@code from} to {@code to}, with {@code to}. */
    List<Integer> boundaries(int from, int to) {
      BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
      chars.setText(text);
      List<Integer> found = new ArrayList<>();
      found.add(from);
      for (int b = chars.following(from); b != BreakIterator.DONE && b <= to; b = chars.next()) {
        found.add(b);
      }
      return found;
    }
  }

  // Positions in the shown string, for Editor. All in physical pixels from the top left of the
  // content box, before the scroll; they need a layout first. The shown string is the string with
  // the composition in it, also in a masked field, whose glyphs are those of its mask: toGlyphs
  // and fromGlyphs map between the two, and the private methods work on the glyphs' text.

  /** A position of the shown string as a position in the text of the glyphs. */
  private int toGlyphs(int index) {
    if (maskBounds == null) {
      return index;
    }
    int found = Arrays.binarySearch(maskBounds, index);
    return Math.max(0, found >= 0 ? found : -found - 2) * Character.charCount(mask);
  }

  /** A position in the text of the glyphs, at a grapheme boundary, in the shown string. */
  private int fromGlyphs(int index) {
    return maskBounds == null ? index
        : maskBounds[Math.min(index / Character.charCount(mask), maskBounds.length - 1)];
  }

  /** The content box's width in physical pixels, as layout placed it. */
  float contentWidth() {
    return placedWidth;
  }

  /** The content box's height in physical pixels, as layout placed it. */
  float contentHeight() {
    return placedHeight;
  }

  /** Whether layout has placed the text; an element with {@code display: none} is not. */
  boolean laidOut() {
    return laidOut;
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
    return line(toGlyphs(index));
  }

  private int line(int index) {
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
    return fromGlyphs(starts[span.paragraph] + span.from);
  }

  int lineEnd(int line) {
    Span span = placedSpans.get(line);
    return fromGlyphs(starts[span.paragraph] + span.to);
  }

  /**
   * Where a caret at position {@code index} of the shown string stands on its line. Positions
   * between clusters are the pen position of the glyph that starts the next one, so kerning is
   * included; a position inside a cluster, such as between the two letters of a ligature, lies
   * inside its glyphs in proportion to the graphemes before it.
   */
  float x(int index) {
    if (multiline) {
      return caretX(index);
    }
    return glyphX(toGlyphs(index));
  }

  private float glyphX(int index) {
    if (placedSpans.isEmpty()) {
      return 0;
    }
    int line = line(index);
    Span span = placedSpans.get(line);
    int p = span.paragraph;
    int at = Math.clamp(index - starts[p], span.from, span.to);
    return (line < lineX.length ? lineX[line] : 0) + paras[p].pen(at) - paras[p].pen(span.from);
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
    if (multiline) {
      int[] at = rowAt(y);
      return nearestOnRow(at[0], at[1], x);
    }
    if (placedSpans.isEmpty()) {
      return 0;
    }
    List<Integer> bounds = boundaries(lineAt(y));
    int best = bounds.getFirst();
    float distance = Float.POSITIVE_INFINITY;
    for (int b : bounds) {
      float d = Math.abs(glyphX(b) - x);
      if (d < distance) {
        distance = d;
        best = b;
      }
    }
    return fromGlyphs(best);
  }

  /**
   * The start of the grapheme of the shown string under a point: the last one that starts left of
   * it, the first one left of the line.
   */
  int hit(float x, float y) {
    if (multiline) {
      int[] at = rowAt(y);
      Para para = para(at[0]);
      int start = shownLines().start(at[0]);
      List<Integer> bounds = rowBounds(para, at[1]);
      int found = bounds.getFirst();
      float left = para.pen(para.rowStart(at[1]));
      for (int i = 0; i < bounds.size() - 1; i++) {
        if (para.pen(bounds.get(i)) - left <= x) {
          found = bounds.get(i);
        }
      }
      return start + found;
    }
    if (placedSpans.isEmpty()) {
      return 0;
    }
    int line = lineAt(y);
    List<Integer> bounds = boundaries(line);
    int found = bounds.getFirst();
    for (int i = 0; i < bounds.size() - 1; i++) {
      if (glyphX(bounds.get(i)) <= x) {
        found = bounds.get(i);
      }
    }
    return fromGlyphs(found);
  }

  /** The grapheme boundaries of a line, from its start to its end. */
  private List<Integer> boundaries(int line) {
    Span span = placedSpans.get(line);
    int start = starts[span.paragraph];
    List<Integer> found = new ArrayList<>();
    for (int b : paras[span.paragraph].boundaries(span.from, span.to)) {
      found.add(start + b);
    }
    return found;
  }

  // ---- A multi-line field: lines shaped on demand, rows laid out for the scroll

  /** Makes the shown string and its lines, and what the layout of rows needs from the font. */
  private void prepareLines() {
    String text = composed != null ? composed : edited();
    Lines lines = composed != null ? composedLines : editedLines;
    placeholderShown = text.isEmpty() && placeholder != null;
    if (placeholderShown) {
      text = Editor.multiLine(placeholder);
      lines = null;
    }
    if (lines == null) {
      lines = Lines.of(text);
      if (!placeholderShown && composed == null) {
        editedLines = lines;
      }
    }
    shown = text;
    shownLines = lines;
    tabStop = Math.max(tabSize, 1) * shaping.shape(" ", size, scale, features).width();
    GlyphRun sample = shaping.shape(SAMPLE, size, scale, features);
    advance = sample.width() / SAMPLE.length();
  }

  private Lines shownLines() {
    prepare();
    wrapping();
    return shownLines;
  }

  /** Tells the lines what their rows are counted for: wrapping at the width, or none. */
  private void wrapping() {
    if (!wraps) {
      shownLines.wrap(null, 1);
      return;
    }
    if (!laidOut) {
      return; // the rows are for the width layout gives, which the next placed() tells
    }
    shownLines.wrap(new Wrapping(placedWidth, font(), size, scale, List.of(features), tabSize,
        systemFonts), Math.max(placedWidth / Math.max(advance, 1), 1));
  }

  /** Line {@code line} of the shown string shaped, with its rows at the width; measured once. */
  private Para para(int line) {
    Lines lines = shownLines();
    Para para = shapedLines.get(line);
    if (para == null) {
      if (shapedLines.size() >= 4096) {
        shapedLines.clear(); // keys that walk far in one tree: keep what this tree shapes bounded
      }
      Spans spans = highlighter == null || placeholderShown ? null
          : lines.spans(line, shown, highlighter, highlightKey);
      String text = shown.substring(lines.start(line), lines.end(line));
      para = shape(text, spans, 0, text.length());
      shapedLines.put(line, para);
      lines.shaped++;
    }
    float limit = wraps && laidOut ? placedWidth + SLACK : Float.POSITIVE_INFINITY;
    if (limit != para.limit) { // new, or shaped before layout placed the text at another width
      para.breakInto(limit);
      if (lines.wraps()) {
        lines.rows(line, para.rowCount());
      } else {
        lines.measured(line, para.run.width());
      }
    }
    return para;
  }

  /**
   * For {@link El#fitLines}: the rows of every line at a content box this wide, in logical pixels
   * or {@link Measure#MAX_CONTENT} or MIN, each line shaped once.
   */
  private int fittedRows(float width) {
    Lines lines = shownLines();
    if (!wraps || width == MAX_CONTENT || Float.isInfinite(width) || Float.isNaN(width)) {
      return lines.count();
    }
    float limit = width == MIN_CONTENT ? 0 : width * scale + SLACK;
    int rows = 0;
    for (int line = 0; line < lines.count(); line++) {
      para(line); // shaped, and its rows counted at the placed width once that is known
      Para para = shapedLines.get(line);
      if (para != null) {
        float was = para.limit; // what para() counted the line's rows at, which stays
        para.breakInto(limit);
        rows += para.rowCount();
        para.breakInto(was);
      } else {
        rows++;
      }
    }
    return rows;
  }

  /** The number of lines of the shown string. */
  int lineTotal() {
    return shownLines().count();
  }

  /** For {@link Editor}: the lines of the shown string. */
  Lines shownIndex() {
    return shownLines();
  }

  /**
   * For {@link El}: the scroll offsets of a multi-line field in logical pixels, which move its
   * rows: vertically on whole physical pixels, horizontally on the grid of glyph positions.
   */
  void scrolled(double x, double y) {
    double grid = scale >= 1.5 && !font().wholePixels() ? 4 : 1;
    float newX = (float) (Math.round(x * scale * grid) / grid);
    float newY = (float) Math.round(y * scale);
    if (newX != viewX || newY != viewY) {
      viewX = newX;
      viewY = newY;
      viewStale = true;
    }
  }

  /** The vertical scroll of a multi-line field in physical pixels; 0 for other text. */
  float scrollY() {
    return multiline ? viewY : 0;
  }

  /** The height of all rows of a multi-line field, in logical pixels, measured or estimated. */
  double extentHeight() {
    return shownLines().totalRows() * (double) lineHeight / scale;
  }

  /**
   * The width of a multi-line field's text in logical pixels, as far as layout has shaped it, with
   * room for the caret at the end of the widest line; the content box's width while it wraps.
   */
  double extentWidth(float caretRoom) {
    if (wraps) {
      return placedWidth / scale;
    }
    return (shownLines().widest() + caretRoom) / scale;
  }

  /**
   * Lays out the rows of a multi-line field in view and {@link #MARGIN_ROWS} around them, shaping
   * the lines they are on unless this tree has shaped them already.
   *
   * @return how far the line at the top of the view moved down because rows above it were measured
   *     for the first time, in physical pixels; the editor scrolls as far, so that what is in view
   *     stays where it is
   */
  float layOut() {
    Lines lines = shownLines();
    viewStale = false;
    double lh = lineHeight;
    int anchor = lines.lineOfRow((long) Math.floor(viewY / lh));
    long anchorRows = lines.rowsBefore(anchor);
    double from = viewY - MARGIN_ROWS * lh;
    double to = viewY + placedHeight + MARGIN_ROWS * lh;
    long firstRow = Math.max(0, (long) Math.floor(from / lh));
    int line = lines.lineOfRow(firstRow);
    long skip = firstRow - lines.rowsBefore(line);
    List<Line> laid = new ArrayList<>();
    List<Row> placed = new ArrayList<>();
    firstInView = -1;
    lastInView = -1;
    for (; line < lines.count(); line++, skip = 0) {
      Para para = para(line);
      double lineTop = lines.rowsBefore(line) * lh;
      if (lineTop >= to) {
        break;
      }
      for (int r = (int) Math.min(skip, para.rowCount()); r < para.rowCount(); r++) {
        double top = lineTop + r * lh;
        if (top >= to) {
          break;
        }
        laid.add(new Line(para.slice(para.rowStart(r), para.rowEnd(r)), para.rowWidths[r],
            (float) (top - viewY + firstBaseline), para.colors(para.rowStart(r), para.rowEnd(r)),
            0));
        placed.add(new Row(line, r, para, top));
        if (top < viewY + placedHeight && top + lh > viewY) {
          firstInView = firstInView < 0 ? line : firstInView;
          lastInView = line;
        }
      }
    }
    this.lines = List.copyOf(laid);
    this.rows = placed;
    marks = marks(placed);
    return (float) ((lines.rowsBefore(anchor) - anchorRows) * lh);
  }

  /** The marks of the rows laid out, from the highlighter's spans and the decorator's. */
  private List<Mark> marks(List<Row> placed) {
    if (placeholderShown || decorator == null && (highlighter == null || placed.stream()
        .noneMatch(row -> row.para.spans != null && row.para.spans.count > 0))) {
      return List.of();
    }
    Lines lines = shownLines();
    List<Mark> lineMarks = new ArrayList<>();
    List<Mark> ranges = new ArrayList<>();
    List<Mark> unders = new ArrayList<>();
    Map<Integer, Spans> decorated = new HashMap<>();
    float thick = Math.max(1, Math.round(scale));
    float under = firstBaseline + Math.round(font().descent() * size * scale / 2);
    float strike = firstBaseline - Math.round(size * scale * 0.3f) - Math.round(thick / 2);
    for (Row row : placed) {
      Para para = row.para;
      Spans decor = null;
      if (decorator != null) {
        decor = decorated.computeIfAbsent(row.line, line -> {
          Spans out = new Spans(para.text.length(), false);
          decorator.line(line, lines.start(line), para.text, out);
          return out;
        });
      }
      float top = (float) (row.top - viewY);
      float left = para.pen(para.rowStart(row.row));
      int from = para.rowStart(row.row);
      int to = para.rowEnd(row.row);
      for (Spans spans : new Spans[] {para.spans, decor}) {
        if (spans == null) {
          continue;
        }
        if (spans.lineBackground >>> 24 != 0) {
          lineMarks.add(new Mark(new Box(0, top, placedWidth, lineHeight), spans.lineBackground));
        }
        for (int i = 0; i < spans.count; i++) {
          int kind = spans.kinds[i];
          int a = Math.max(spans.froms[i], from);
          int b = Math.min(spans.tos[i], to);
          if (kind != Spans.BACKGROUND && kind != Spans.UNDERLINE && kind != Spans.STRIKE
              || b <= a) {
            continue;
          }
          float x0 = para.pen(a) - left - viewX;
          float x1 = para.pen(b) - left - viewX;
          if (kind == Spans.BACKGROUND) {
            ranges.add(new Mark(new Box(x0, top, x1 - x0, lineHeight), spans.colors[i]));
          } else {
            float y = kind == Spans.UNDERLINE ? under : strike;
            unders.add(new Mark(new Box(x0, top + y, x1 - x0, thick), spans.colors[i]));
          }
        }
      }
    }
    lineMarks.addAll(ranges);
    lineMarks.addAll(unders);
    return List.copyOf(lineMarks);
  }

  /** The lines whose first row is laid out, with where it is, top to bottom. */
  List<Start> lineStarts() {
    lines();
    List<Start> starts = new ArrayList<>();
    for (Row row : rows) {
      if (row.row == 0) {
        starts.add(new Start(row.line, (float) (row.top - viewY)));
      }
    }
    return starts;
  }

  /** Whether characters the fonts lack come from the system's fonts. */
  boolean systemFonts() {
    return systemFonts;
  }

  /** For the dump: the lines with a row in the content box, from 0, and -1 for none. */
  int firstLineInView() {
    lines();
    return firstInView;
  }

  int lastLineInView() {
    lines();
    return lastInView;
  }

  /** The row of the whole text at a height, as {line, row}, clamped to the text. */
  private int[] rowAt(double y) {
    Lines lines = shownLines();
    double lh = lineHeight;
    long row = Math.clamp((long) Math.floor(y / lh), 0, Math.max(lines.totalRows() - 1, 0));
    int line = lines.lineOfRow(row);
    Para para = para(line);
    long within = row - lines.rowsBefore(line);
    if (within < 0) { // measuring the line made the rows before it fewer
      within = 0;
    }
    return new int[] {line, (int) Math.min(within, para.rowCount() - 1)};
  }

  /** The row a position of the shown string is on, as {line, row}; at a wrap, the later one. */
  private int[] rowOf(int index) {
    Lines lines = shownLines();
    int line = lines.line(Math.clamp(index, 0, lines.length()));
    return new int[] {line, para(line).rowAt(index - lines.start(line))};
  }

  /** The positions a caret can take on a row: a wrapped row ends before its last grapheme. */
  private static List<Integer> rowBounds(Para para, int row) {
    List<Integer> bounds = para.boundaries(para.rowStart(row), para.rowEnd(row));
    if (row + 1 < para.rowCount() && bounds.size() > 1) {
      bounds.removeLast(); // that position starts the next row
    }
    return bounds;
  }

  private float caretX(int index) {
    int[] at = rowOf(index);
    Para para = para(at[0]);
    int offset = index - shownLines().start(at[0]);
    return para.pen(offset) - para.pen(para.rowStart(at[1]));
  }

  /** The top of the row a position is on, in physical pixels from the top of the text. */
  double caretTop(int index) {
    int[] at = rowOf(index);
    return (shownLines().rowsBefore(at[0]) + at[1]) * (double) lineHeight;
  }

  /** The position nearest to {@code x} on row {@code row} of line {@code line}. */
  private int nearestOnRow(int line, int row, float x) {
    Para para = para(line);
    float left = para.pen(para.rowStart(row));
    int best = para.rowStart(row);
    float distance = Float.POSITIVE_INFINITY;
    for (int b : rowBounds(para, row)) {
      float d = Math.abs(para.pen(b) - left - x);
      if (d < distance) {
        distance = d;
        best = b;
      }
    }
    return shownLines().start(line) + best;
  }

  /**
   * The position {@code rows} visual rows above (negative) or below a position, nearest to {@code
   * x} on its row; before the first row the start of the text, after the last its end.
   */
  int moveRows(int index, int rows, float x) {
    int[] at = rowOf(index);
    int line = at[0];
    int row = at[1];
    Lines lines = shownLines();
    for (int step = rows; step != 0; step -= Integer.signum(step)) {
      if (step > 0) {
        if (row + 1 < para(line).rowCount()) {
          row++;
        } else if (line + 1 < lines.count()) {
          line++;
          row = 0;
        } else {
          return lines.length();
        }
      } else if (row > 0) {
        row--;
      } else if (line > 0) {
        line--;
        row = para(line).rowCount() - 1;
      } else {
        return 0;
      }
    }
    return nearestOnRow(line, row, x);
  }

  /** The start of the row a position is on. */
  int rowStart(int index) {
    int[] at = rowOf(index);
    return shownLines().start(at[0]) + para(at[0]).rowStart(at[1]);
  }

  /** The last position a caret takes on the row of a position: before the wrap, or the line end. */
  int rowEnd(int index) {
    int[] at = rowOf(index);
    List<Integer> bounds = rowBounds(para(at[0]), at[1]);
    return shownLines().start(at[0]) + bounds.getLast();
  }

  /** Whether a position starts the first row of its line, which a wrapped line is not after. */
  boolean startsLine(int index) {
    int[] at = rowOf(index);
    return at[1] == 0;
  }

  /** The rows in view and the margin that cover the content box, for the editor's look. */
  int visibleRows() {
    return Math.max(1, (int) Math.floor(placedHeight / lineHeight()));
  }

  /**
   * The selection from {@code start} to {@code end} of the shown string, one rectangle per row of
   * the layout it touches, moved by the scroll; a row whose end is selected reaches to {@code
   * edge}, the right edge of the content box before the scroll.
   */
  List<Box> selectionBoxes(int start, int end, float edge) {
    lines();
    List<Box> boxes = new ArrayList<>();
    Lines lines = shownLines();
    for (Row row : rows) {
      Para para = row.para;
      int lineStart = lines.start(row.line);
      int from = lineStart + para.rowStart(row.row);
      int to = lineStart + para.rowEnd(row.row);
      boolean lastRow = row.row + 1 == para.rowCount();
      // A line's last row is followed by its line feed, a wrapped row by the next row's start.
      if (start == end || end <= from || (lastRow ? start > to : start >= to)) {
        continue;
      }
      boolean endSelected = lastRow ? end > to : end >= to;
      float left = para.pen(para.rowStart(row.row));
      float x0 = para.pen(Math.max(start, from) - lineStart) - left;
      float x1 = endSelected
          ? Math.max(edge, para.pen(to - lineStart) - left)
          : para.pen(Math.min(end, to) - lineStart) - left;
      if (x1 > x0) {
        boxes.add(new Box(x0 - viewX, (float) (row.top - viewY), x1 - x0, lineHeight));
      }
    }
    return List.copyOf(boxes);
  }

  /** The top of the row a position is on, moved by the scroll: where its caret is drawn. */
  float shownTop(int index) {
    return (float) (caretTop(index) - viewY);
  }

  /** The baseline of the row a position is on, moved by the scroll. */
  float shownBaseline(int index) {
    return shownTop(index) + firstBaseline;
  }

  /** The scroll the rows were laid out for, horizontally, in physical pixels. */
  float viewX() {
    return viewX;
  }

  float viewY() {
    return viewY;
  }
}
