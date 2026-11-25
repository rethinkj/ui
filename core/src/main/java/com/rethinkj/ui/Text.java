package com.rethinkj.ui;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The text of an element made with {@link El#text}: its string and look, and, once layout has
 * placed the element, its {@link #lines} as shaped glyphs, which is what a renderer draws.
 *
 * <p>Each line feed starts a new line. A text that {@link El#wrapText wraps} also breaks where
 * {@link BreakIterator#getLineInstance} allows when a line would be wider than its box. A piece
 * that no such place breaks and that is wider than the whole box, such as a path or a URL, starts
 * a line of its own and breaks inside, between graphemes, as late as fits, as CSS's {@code
 * overflow-wrap: break-word} does; a cluster wider than the box stays whole. Layout's min-content
 * width is still the widest piece whole. Spaces at the end of a wrapped line take no width. The
 * line height is the font's ascent, descent and line gap, rounded to whole physical pixels.
 *
 * <p>Text is shaped for the scale of the window, which {@link App} and {@link Headless} pass to
 * every text element before layout. Sizes go to layout in logical pixels; lines are in physical
 * pixels, so that a renderer draws the glyphs where they were measured. A view that measures text
 * while it is built sets the scale itself, from {@link WindowState#scale}.
 *
 * <p>The text of an {@link El#editable} element is a field. A single-line field is one line,
 * whatever the string holds, measured as 0 wide so that the field's width comes from its style;
 * one that wraps measures as other text does, as wide as its widest line and as high as its lines.
 * Its lines are centered vertically in its content box and shown with its composition and
 * placeholder. Its caret, selection and horizontal scroll come from {@link App}, which keeps
 * them per element; the methods below that return them are what a renderer draws.
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
   * @param colors the color of each glyph as 0xAARRGGBB, where a {@link Highlighter} colored
   *     the line; null when every glyph has the text's color
   * @param x where the pen starts, from the left edge of the content box: 0, or further right in
   *     text {@link El#textAlign aligned} to the center or the end, on the grid of glyph positions
   */
  public record Line(GlyphRun glyphs, float width, float baseline, int[] colors, float x) {

    /** A line at the left edge whose glyphs all have the text's color. */
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
   * A mark drawn behind the glyphs of a multi-line field, before its selection: the background of a
   * line or a range, a straight line under or through a range, or a wavy line ({@link
   * Spans#squiggle}), from a {@link Highlighter} or a {@link Decorator}.
   *
   * @param box where, as the selection's rectangles are; a squiggle's reaches from the
   *     underline to the bottom of its row
   * @param color as 0xAARRGGBB
   * @param squiggle whether the mark is drawn as a wavy line rather than a rectangle
   */
  public record Mark(Box box, int color, boolean squiggle) {

    /** A rectangle mark. */
    public Mark(Box box, int color) {
      this(box, color, false);
    }
  }

  /** What ends a line that {@link El#ellipsis} shortens. */
  public static final String ELLIPSIS = "…";

  /** What a {@link El#masked} field shows for each grapheme of its string: a bullet, U+2022. */
  public static final int MASK = 0x2022;

  /** The color of the selection until {@link El#selectionColor} changes it, as 0xAARRGGBB. */
  public static final int SELECTION = Colors.alpha(Colors.rgb(0x3B82F6), 0.4f);

  // A width a line may exceed its box by: layout converts what it measured back and forth, and
  // widths are on a grid of a quarter pixel.
  private static final float SLACK = 1 / 64f;

  // The rows a multi-line field lays out above and below its content box: they cover its padding
  // and a scroll of a few rows without a new layout.
  private static final int MARGIN_ROWS = 3;

  // What a multi-line field estimates the advance of a character from, until it has shaped a line.
  private static final String SAMPLE = "SELECT name, id FROM customers WHERE id = 42 the quick fox";

  // A non-wrapping line of a multi-line field longer than this is split into chunks and shaped
  // lazily, only where the view, caret or pointer reaches it.
  private static final int CHUNK_SIZE = 2048;
  private static final int CHUNK_WINDOW = 128;

  // The shaped lines a text keeps in one tree, and their characters: measuring and laying out each
  // walk every line of a long wrapping text, so a text within both finds its lines shaped once.
  private static final int SHAPED_LINES = 16_384;
  private static final int SHAPED_CHARACTERS = 1 << 20;

  private final String string;
  private String composed; // a field's string with the composition in it, or null for none
  private boolean field;
  private boolean unfocused; // a field without the focus, which shows its start
  private boolean multiline;
  private boolean masked;
  private boolean revealed; // a masked field that draws its string, see El#revealed
  private int mask = MASK;
  private String placeholder;
  private int placeholderColor;
  private String[] features = {};
  private int caretColor;
  private int selectionColor = SELECTION;
  private int tabSize = 4;
  private Style.Align align = Style.Align.START;
  private float[] lineX = {}; // per line placed: where its pen starts, after the alignment
  // Per line placed that the ellipsis shortened: where in its paragraph what it shows ends, and
  // where its ellipsis starts from its pen; lineEnd is infinite for a line not shortened.
  private int[] lineShown = {};
  private float[] lineEnd = {};
  private Highlighter highlighter;
  private Object highlightKey;
  private Decorator decorator;
  private Decorator scopeDecorator;
  private int outlineFrom;
  private int outlineTo;
  private int outlineColor;
  private List<Mark> marks = List.of();
  // The decorator's underlines and squiggles in the lines laid out, as pairs of string positions,
  // for the dump.
  private int[] underlined = {};
  private int[] squiggled = {};
  private int visibleLines = 1;
  private int maxLines; // 0 for no limit, see El#maxLines
  // What a field draws besides its glyphs; see Editor#place.
  private float scroll;
  private List<Box> selection = List.of();
  private List<Box> blockSelection = List.of();
  private Box caret;
  private boolean caretShown;
  private Box composition;
  private int underlineFrom; // a range El#underline marks, from == to for none
  private int underlineTo;
  private Font font;
  private float size = 14;
  private float lineHeightFactor; // multiple of size, 0 for the font's own height
  private int color = Colors.BLACK;
  // The color the view gave, which a highlighter's glyphs without a color of their own are
  // shaped with; `color` is the one shown, which motion moves towards it.
  private int viewColor = Colors.BLACK;
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
  // lines are moved down to center them.
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
  private long shapedLineCharacters; // put into shapedLines since it was last emptied
  private float tabStop;
  private float advance; // of an average character, for the estimate of rows
  private float viewX;
  private float viewY;
  private boolean viewStale = true;
  private boolean chunksMoved; // whether the last layOut measured a chunk of a long line
  private boolean highlightPending;
  private List<El.Fold> folds = List.of();
  private List<El.Block> blocks = List.of();
  private BlockMeasurer blockMeasurer;
  private List<El.Hint> hints = List.of();
  private HintMeasurer hintMeasurer;
  private static final SortedHint[] NO_HINTS = {};
  private SortedHint[] sortedHints; // sorted hints in bounds, lazily built when !hints.isEmpty()
  private List<Row> rows = List.of();
  private List<PlacedBlock> placedBlocks = List.of();
  private List<El> lineEndElements = List.of(); // see #clipsToContent
  private List<El> hintElements = List.of(); // see #clipsToContent
  private int firstInView;
  private int lastInView;

  /**
   * A line of the text: characters {@code from} to {@code to} of a paragraph. A line that {@link
   * El#maxLines} cut runs to the end of its paragraph; {@code width} is still its row's, which
   * is what it measures as, and {@link #shownWidth} what its glyphs take.
   */
  private record Span(int paragraph, int from, int to, float width, boolean cut) {}

  /**
   * Where a line of a multi-line field begins, for its gutter: its first row's (or replaced
   * block's) top and height, in physical pixels from the top of the content box, after the scroll.
   */
  record Start(int line, int lastLine, float top, float height) {}

  /**
   * Where a shown line's last row ends, for its fold mark and line-end elements: {@code endX} and
   * {@code top} in physical pixels from the top left of the content box, after the scroll.
   */
  record LineEnd(int line, El.Fold fold, float endX, float top) {}

  /** A line block placed during {@link #layOut}, in physical pixels from the top of the text. */
  record PlacedBlock(El.Block block, int ordinal, El element, double top, double height) {}

  /** Builds and lays out one line block element for measurement inside {@link #layOut}. */
  @FunctionalInterface
  interface BlockMeasurer {
    El measure(El.Block block, int ordinal, float contentWidth, float scale, float lineHeight);
  }

  /** Which visual stop the caret stands on at an offset with inline hints ({@link El#hints}). */
  enum HintStop {
    /** Left edge of all hints at the offset ({@code pen(at) - beforeGap(at)}). */
    LEFT,
    /** Between BEFORE and AFTER hints ({@code pen(at)}, where typed text goes). */
    DEFAULT,
    /** Right edge of all hints at the offset ({@code pen(at) + afterGap(at)}). */
    RIGHT
  }

  /** A caret position returned by hit testing: string offset and which stop at that offset. */
  record CaretPos(int index, HintStop stop) {}

  /** An inline hint in view, with its physical x and row top relative to the content box. */
  record PlacedHint(El.Hint hint, int ordinal, El element, float x, float top) {}

  /** Builds and lays out one inline hint element for width measurement during shaping. */
  @FunctionalInterface
  interface HintMeasurer {
    El measure(El.Hint hint, int ordinal, float scale, float lineHeight);
  }

  private record SortedHint(El.Hint hint, int ordinal) {}

  private record MeasuredHint(El.Hint hint, int ordinal, int localAt, El element, float width) {}

  private record HintSlot(
      El.Hint hint,
      int ordinal,
      int resolvedAt,
      boolean isBefore,
      El element,
      float relX,
      float width) {}

  /** Inline hint widths and slots resolved for one shaped paragraph or chunk. */
  private static final class LineHints {
    final int[] positions;
    final float[] before;
    final float[] after;
    final List<HintSlot> slots;
    final float emptyWidth;

    LineHints(
        int[] positions, float[] before, float[] after, List<HintSlot> slots, float emptyWidth) {
      this.positions = positions;
      this.before = before;
      this.after = after;
      this.slots = slots;
      this.emptyWidth = emptyWidth;
    }

    float beforeGap(int at) {
      int i = Arrays.binarySearch(positions, at);
      return i >= 0 ? before[i] : 0;
    }

    float afterGap(int at) {
      int i = Arrays.binarySearch(positions, at);
      return i >= 0 ? after[i] : 0;
    }
  }

  /** A row of a multi-line field as laid out: row {@code row} of line {@code line}. */
  private record Row(int line, int row, Para para, double top) {}

  /**
   * What the rows of wrapped lines depend on: another value breaks them anew. Without wrapping, and
   * a width of 0, what the widths of lines depend on.
   */
  private record Wrapping(
      float width,
      Font font,
      float size,
      float scale,
      List<String> features,
      int tabSize,
      boolean systemFonts) {}

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

  /**
   * Whether this is a field for a password, whose string only drawing sees; see {@link El#masked}.
   * Also while it is {@link #revealed}.
   */
  public boolean masked() {
    return masked;
  }

  /**
   * Whether this is a {@link #masked} field that draws its string instead of the mask; see {@link
   * El#revealed}. {@link #string} is still the mask.
   */
  public boolean revealed() {
    return masked && revealed;
  }

  /** Whether the glyphs show the mask: a {@link #masked} field that is not {@link #revealed}. */
  boolean hidden() {
    return masked && !revealed;
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

  /** The color as 0xAARRGGBB. */
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

  /**
   * Whether layout shortened a line to fit the box, so that it ends in {@link #ELLIPSIS}, or is
   * empty where not even that fits.
   */
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

  /** Whether a {@link Highlighter} colors the text; see {@link El#highlight}. */
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

  /** The {@link El#placeholder} of a field, or null if it has none. */
  public String placeholder() {
    return placeholder;
  }

  /** The color of the placeholder, as 0xAARRGGBB. */
  public int placeholderColor() {
    return placeholderColor;
  }

  /** The caret's color, as 0xAARRGGBB: the text color unless {@link El#caretColor} set one. */
  public int caretColor() {
    return caretColor != 0 ? caretColor : color;
  }

  /** The color of the selection, drawn behind the text, as 0xAARRGGBB. */
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
   * Selection tint rectangles over blocks between lines ({@link El#blocks}) covered by the
   * selection, drawn over the block elements in the content box; empty when none are covered.
   */
  public List<Box> blockSelection() {
    return blockSelection;
  }

  /**
   * Whether {@code child} is one of this field's placed line blocks ({@link El#blocks}), line-end
   * elements ({@link El#lineEnds}) or inline hints ({@link El#hints}), which draw clipped to the
   * content box.
   */
  public boolean clipsToContent(El child) {
    for (PlacedBlock pb : placedBlocks) {
      if (pb.element == child) {
        return true;
      }
    }
    return lineEndElements.contains(child) || hintElements.contains(child);
  }

  /** For {@link El}: the line-end elements it placed, without the fold marks. */
  void lineEndElements(List<El> elements) {
    lineEndElements = elements;
  }

  /** For {@link El}: the inline hint elements it placed. */
  void hintElements(List<El> elements) {
    hintElements = elements;
  }

  /** Whether this multi-line field currently has inline hints ({@link El#hints}) to show. */
  public boolean hasHints() {
    return !hints.isEmpty() && !placeholderShown;
  }

  /**
   * What a text draws behind its glyphs (and behind a multi-line field's selection) on the rows of
   * {@link #lines}: the backgrounds of lines, then those of ranges, then any {@link El#outline},
   * then the lines under ranges, each in the order the lines and their {@link Spans} have them.
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
   * The line under the composition of an input method in progress, drawn in the text color, or
   * null when there is none.
   */
  public Box composition() {
    return composition;
  }

  private void invalidate() {
    prepared = false;
    paras = null;
    shapedLines.clear();
    shapedLineCharacters = 0;
    sortedHints = null;
    viewStale = true;
    highlightPending = false;
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

  void revealed(boolean revealed) {
    this.revealed = revealed;
    invalidate();
  }

  void mask(int character) {
    String one = Character.isValidCodePoint(character) ? Character.toString(character) : "";
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(one + one);
    // Two of them must be two graphemes: no combining mark, joiner or half of a flag.
    if (one.isEmpty()
        || Character.isWhitespace(character)
        || Character.isISOControl(character)
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

  /** How far apart the tab stops are, in widths of a space ({@link El#tabSize}). */
  public int tabSize() {
    return tabSize;
  }

  Decorator decorator() {
    return decorator;
  }

  void decorator(Decorator decorator) {
    this.decorator = decorator;
    viewStale = true;
  }

  void scopeDecorator(Decorator decorator) {
    if (!Objects.equals(this.scopeDecorator, decorator)) {
      this.scopeDecorator = decorator;
      viewStale = true;
    }
  }

  void outline(int from, int to, int argb) {
    this.outlineFrom = from;
    this.outlineTo = to;
    this.outlineColor = argb;
    viewStale = true;
  }

  private boolean hasOutline() {
    return outlineFrom < outlineTo && outlineColor >>> 24 != 0;
  }

  void align(Style.Align align) {
    this.align = align;
  }

  /** How lines are aligned in the content box; see {@link El#textAlign}. */
  public Style.Align align() {
    return align;
  }

  Highlighter highlighter() {
    return highlighter;
  }

  /**
   * The characters the {@link El#highlight highlighter}'s language adds to the words of code
   * ({@link Highlighter#wordCharacters}); none without a highlighter.
   */
  public String wordCharacters() {
    return highlighter == null ? "" : highlighter.wordCharacters();
  }

  Object highlightKey() {
    return highlightKey;
  }

  void highlight(Object key, Highlighter highlighter) {
    this.highlightKey = key;
    this.highlighter = highlighter;
    invalidate();
  }

  void visibleLines(int lines) {
    this.visibleLines = lines;
  }

  /**
   * Whether this is a multi-line field as high as all its rows ({@link El#fitLines}) whose content
   * box has room for all of them as layout placed it, so that it never scrolls up and down.
   */
  public boolean showsAllRows() {
    if (!multiline || visibleLines != 0) {
      return false;
    }
    lines(); // lays out the rows in view, so that they are counted, not estimated
    return shownLines().totalHeight(lineHeight) <= placedHeight + scale / 64; // El's scroll slack
  }

  void maxLines(int lines) {
    this.maxLines = lines;
  }

  int maxLines() {
    return maxLines;
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
    decorate(scroll, selection, caret, composition, List.of());
  }

  void decorate(
      float scroll, List<Box> selection, Box caret, Box composition, List<Box> blockSelection) {
    this.scroll = scroll;
    this.selection = selection;
    this.caret = caret;
    this.composition = composition;
    this.blockSelection = blockSelection;
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

  /** For {@link El}: the line height as a multiple of the font size, or 0 for the font's own. */
  void lineHeight(float factor) {
    if (factor != this.lineHeightFactor) {
      this.lineHeightFactor = factor;
      invalidate();
    }
  }

  void color(int argb) {
    this.color = argb;
    this.viewColor = argb;
  }

  /** The color shown while motion moves it towards the view's; see {@link #viewColor}. */
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

  /**
   * Shapes the text for a scale, so that {@link #measure} gives what layout will at that scale, as
   * a component that measures text while the view is built needs ({@link WindowState#scale}). {@link
   * App} and {@link Headless} set it again before layout; on text already laid out, this changes
   * what is drawn until then.
   */
  public void scale(float scale) {
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
    // A single-line field that wraps measures as text does, as wide as its widest line, so that a
    // box sized to its content gives it room for one line and only wraps it where it has to.
    if (field && (multiline || !wraps)) {
      // shortcut: the baseline is that of a line at the top; a field's line is centered in its
      // content box, which measure does not know yet. Pass the box's height when a field has to
      // line up with text beside it by baseline.
      prepare();
      if (multiline && visibleLines == 0) { // El#fitLines: every row at the width
        float width = Float.isNaN(knownWidth) ? availableWidth : knownWidth;
        return fittedSize(width);
      }
      if (multiline && maxLines > visibleLines) { // El#maxLines: grows between lines and maxLines
        float width = Float.isNaN(knownWidth) ? availableWidth : knownWidth;
        int rows = Math.max(visibleLines, fittedRows(width, maxLines));
        return new Size(0, rows * lineHeight / scale, firstBaseline / scale);
      }
      return new Size(
          0, (multiline ? visibleLines : 1) * lineHeight / scale, firstBaseline / scale);
    }
    List<Span> spans = spans(Float.isNaN(knownWidth) ? availableWidth : knownWidth);
    float widest = 0;
    for (Span span : spans) {
      widest = Math.max(widest, span.width);
    }
    if (field) { // room for the caret at the line's end, so that the focus does not scroll it
      widest += Editor.caretWidth(scale) + SLACK; // layout gives back a hair less at some scales
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
    lineShown = new int[spans.size()];
    lineEnd = new float[spans.size()];
    for (Span span : spans) {
      lineEnd[placed.size()] = Float.POSITIVE_INFINITY;
      float baseline = offsetY + firstBaseline + placed.size() * lineHeight;
      Line line;
      if (ellipsis && (!field || unfocused) && (span.cut || span.width > placedWidth + SLACK)) {
        line = shorten(span, baseline, placed.size());
        shortened = true;
      } else {
        Para para = paras[span.paragraph];
        line = new Line(
            para.slice(span.from, span.to),
            shownWidth(span),
            baseline,
            para.colors(span.from, span.to),
            0);
      }
      float x = aligned(line.width());
      lineX[placed.size()] = x;
      placed.add(x == 0 ? line : new Line(line.glyphs(), line.width(), baseline, line.colors(), x));
    }
    lines = List.copyOf(placed);
    marks = !field && highlighter != null && !placeholderShown && !masked && !spans.isEmpty()
        ? singleLineMarks(spans)
        : List.of();
    if (underlineTo > underlineFrom && !field && !spans.isEmpty()) {
      int line = lineOf(underlineFrom);
      // Only under what the line shows: not under characters the ellipsis cut, nor under the `…`,
      // which the shortened line kerns anew with the last character it keeps.
      boolean cut = lineEnd[line] != Float.POSITIVE_INFINITY;
      int shown = cut ? fromGlyphs(starts[spans.get(line).paragraph] + lineShown[line]) : -1;
      int to = cut ? Math.min(underlineTo, shown) : underlineTo;
      float from = x(underlineFrom);
      float end = cut && to == shown ? lineX[line] + lineEnd[line] : x(to);
      composition = to <= underlineFrom || lines.get(line).glyphs().count() == 0
          ? null
          : new Box(
              from,
              baseline(line) + Math.round(font().descent() * size * scale / 2),
              end - from,
              Math.max(1, Math.round(scale)));
    }
  }

  /** Range backgrounds from the highlighter on non-field text, clipped to shortened lines. */
  private List<Mark> singleLineMarks(List<Span> spans) {
    List<Mark> out = null;
    for (int l = 0; l < spans.size(); l++) {
      Span span = spans.get(l);
      Para para = paras[span.paragraph];
      Spans sp = para.spans;
      if (sp == null || !sp.has(Spans.BACKGROUND)) {
        continue;
      }
      boolean cut = lineEnd[l] != Float.POSITIVE_INFINITY;
      int visTo = cut ? lineShown[l] : span.to;
      if (visTo <= span.from) {
        continue;
      }
      int pStart = para.offset;
      float base = lineX[l] - para.pen(span.from);
      float top = lineTop(l);
      for (int i = 0; i < sp.count; i++) {
        if (sp.kinds[i] != Spans.BACKGROUND) {
          continue;
        }
        int a = Math.max(sp.froms[i] - pStart, span.from);
        int b = Math.min(sp.tos[i] - pStart, visTo);
        if (b <= a) {
          continue;
        }
        float x0 = base + para.pen(a);
        float x1 = cut && b == visTo ? lineX[l] + lineEnd[l] : base + para.pen(b);
        if (x1 > x0) {
          if (out == null) {
            out = new ArrayList<>();
          }
          out.add(new Mark(new Box(x0, top, x1 - x0, lineHeight), sp.colors[i]));
        }
      }
    }
    return out == null ? List.of() : List.copyOf(out);
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
   * even the ellipsis fits, the line is empty: nothing of a text draws outside its box.
   */
  private Line shorten(Span span, float baseline, int index) {
    // shortcut: a right-to-left run is one cluster (see Font#shape), so the line ends before it
    // rather than inside it; cut inside it when right-to-left text needs it.
    Para para = paras[span.paragraph];
    String text = para.text;
    float room =
        placedWidth + SLACK - shaping.shape(ELLIPSIS, size, scale, features).width();
    BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
    chars.setText(text);
    List<Integer> fits = new ArrayList<>();
    fits.add(span.from);
    for (int b = chars.following(span.from);
        b != BreakIterator.DONE && b <= span.to;
        b = chars.next()) {
      if (para.width(span.from, b) > room) {
        break;
      }
      fits.add(b);
    }
    for (int i = fits.size() - 1; ; i--) {
      String kept = text.substring(span.from, fits.get(i)).stripTrailing();
      // The ranges of the kept part stay; the ellipsis has the text's color and font.
      Para shortened = shape(kept + ELLIPSIS, para.spans, para.offset + span.from, kept.length());
      GlyphRun run = shortened.run;
      if (run.width() <= placedWidth + SLACK) {
        lineShown[index] = span.from + kept.length();
        lineEnd[index] = shortened.pen(kept.length());
        return new Line(run, run.width(), baseline, shortened.colors, 0);
      }
      if (i == 0) {
        lineShown[index] = span.from;
        lineEnd[index] = 0;
        return new Line(run.slice(0, 0), 0, baseline);
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
        spans.add(new Span(p, 0, para.text.length(), para.run.width(), false));
        continue;
      }
      int[] rowStarts = para.breakInto(limit);
      for (int r = 0; r < rowStarts.length; r++) {
        int to = r + 1 < rowStarts.length ? rowStarts[r + 1] : para.text.length();
        spans.add(new Span(p, rowStarts[r], to, para.rowWidths[r], false));
      }
    }
    if (maxLines > 0 && spans.size() > maxLines) {
      Span last = spans.get(maxLines - 1);
      spans = new ArrayList<>(spans.subList(0, maxLines - 1));
      // Text that neither shortens nor scrolls has nowhere to show the rest: it ends with the row.
      boolean runsOn = ellipsis || field;
      int end = runsOn ? paras[last.paragraph].text.length() : last.to;
      spans.add(new Span(last.paragraph, last.from, end, last.width, runsOn));
    }
    return spans;
  }

  private void prepare() {
    if (prepared) {
      return;
    }
    Font font = font();
    float pixels = size * scale;
    float fontLineHeight = Math.round((font.ascent() + font.descent() + font.lineGap()) * pixels);
    float fontBaseline = Math.round((font.ascent() + font.lineGap() / 2) * pixels);
    if (lineHeightFactor > 0) {
      lineHeight = Math.max(1, Math.round(lineHeightFactor * pixels));
      firstBaseline = fontBaseline + Math.round((lineHeight - fontLineHeight) / 2);
    } else {
      lineHeight = fontLineHeight;
      firstBaseline = fontBaseline;
    }
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
    } else if (hidden()) {
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
   * Shapes a paragraph, with tab stops in a multi-line field, and with the colors and fonts of
   * {@code spans}, whose position {@code offset} is the paragraph's start, as far as {@code limit}
   * in the paragraph: what lies after it, an ellipsis, has the text's.
   */
  private Para shape(String text, Spans spans, int offset, int limit) {
    return shape(text, spans, offset, limit, List.of());
  }

  private Para shape(String text, Spans spans, int offset, int limit, List<MeasuredHint> segHints) {
    boolean tabbed = multiline && text.indexOf('\t') >= 0;
    String shaped = tabbed ? text.replace('\t', ' ') : text;
    GlyphRun run;
    if (spans != null && spans.has(Spans.FONT)) {
      run = shapeFonts(shaped, spans, offset, limit);
    } else {
      run = shaping.shape(shaped, size, scale, features);
    }
    LineHints lineHints = null;
    if (!segHints.isEmpty()) {
      lineHints = resolveHints(text, run, segHints);
      // Apply hints before tabs so tab stops stay on the grid after a hint.
      run = run.withGaps(text.length(), lineHints.positions, lineHints.before, lineHints.after);
    }
    if (tabbed) {
      int[] tabs = new int[text.length()];
      int n = 0;
      for (int i = text.indexOf('\t'); i >= 0; i = text.indexOf('\t', i + 1)) {
        tabs[n++] = i;
      }
      int[] trimmed = Arrays.copyOf(tabs, n);
      if (lineHints == null) {
        run = run.tabbed(trimmed, tabStop);
      } else {
        float[] leading = new float[n];
        float[] trailing = new float[n];
        for (int i = 0; i < n; i++) {
          leading[i] = lineHints.afterGap(trimmed[i]);
          trailing[i] = lineHints.beforeGap(trimmed[i] + 1);
        }
        run = run.tabbed(trimmed, tabStop, leading, trailing);
      }
    }
    int[] colors = null;
    if (spans != null && spans.has(Spans.COLOR)) {
      int[] byChar = new int[text.length()];
      Arrays.fill(byChar, viewColor);
      for (int i = 0; i < spans.count; i++) {
        if (spans.kinds[i] == Spans.COLOR
            && spans.tos[i] > offset
            && spans.froms[i] < offset + limit) {
          Arrays.fill(
              byChar,
              Math.clamp((long) spans.froms[i] - offset, 0, limit),
              Math.clamp((long) spans.tos[i] - offset, 0, limit),
              spans.colors[i]);
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
    return new Para(text, run, breaks, colors, spans, offset, lineHints);
  }

  /**
   * Resolves a segment's measured hints against the shaped run's clusters: BEFORE at 0 behaves as
   * AFTER, AFTER at the end behaves as BEFORE, and a hint inside a multi-character cluster moves
   * to the cluster's start.
   */
  private static LineHints resolveHints(String text, GlyphRun run, List<MeasuredHint> segHints) {
    int len = text.length();
    int n = run.count();
    if (len == 0 || n == 0) {
      List<HintSlot> slots = new ArrayList<>(segHints.size());
      float x = 0;
      for (MeasuredHint mh : segHints) {
        slots.add(new HintSlot(mh.hint, mh.ordinal, 0, false, mh.element, x, mh.width));
        x += mh.width;
      }
      return new LineHints(new int[] {0}, new float[] {0}, new float[] {x}, slots, x);
    }
    // Resolve each hint's offset to a cluster start (or len), and its effective side.
    int count = segHints.size();
    int[] resolved = new int[count];
    boolean[] isBefore = new boolean[count];
    int g = 0;
    for (int i = 0; i < count; i++) {
      MeasuredHint mh = segHints.get(i);
      int pos = Math.clamp(mh.localAt, 0, len);
      int r;
      if (pos <= 0) {
        r = 0;
      } else if (pos >= len) {
        r = len;
      } else {
        while (g < n && run.cluster(g) < pos) {
          g++;
        }
        if (g < n && run.cluster(g) == pos) {
          r = pos;
        } else if (g == 0) {
          r = 0;
        } else {
          // shortcut: a right-to-left run is shaped as one cluster (see #199, #445), so a hint
          // inside a ligature or right-to-left run moves to the cluster's start.
          r = run.cluster(g - 1);
        }
      }
      resolved[i] = r;
      if (r == 0) {
        isBefore[i] = false; // BEFORE at 0 behaves as AFTER
      } else if (r == len) {
        isBefore[i] = true; // AFTER at the end behaves as BEFORE
      } else {
        isBefore[i] = mh.hint.affinity() == El.Hint.Affinity.BEFORE;
      }
    }
    // Order by resolved offset, BEFORE before AFTER at each offset, preserving original order.
    Integer[] order = new Integer[count];
    for (int i = 0; i < count; i++) {
      order[i] = i;
    }
    Arrays.sort(order, (a, b) -> {
      int c = Integer.compare(resolved[a], resolved[b]);
      if (c != 0) {
        return c;
      }
      if (isBefore[a] != isBefore[b]) {
        return isBefore[a] ? -1 : 1;
      }
      return Integer.compare(a, b);
    });
    int[] positions = new int[count];
    float[] before = new float[count];
    float[] after = new float[count];
    int unique = 0;
    for (int idx : order) {
      int r = resolved[idx];
      if (unique == 0 || positions[unique - 1] != r) {
        positions[unique] = r;
        unique++;
      }
      float w = segHints.get(idx).width;
      if (isBefore[idx]) {
        before[unique - 1] += w;
      } else {
        after[unique - 1] += w;
      }
    }
    positions = Arrays.copyOf(positions, unique);
    before = Arrays.copyOf(before, unique);
    after = Arrays.copyOf(after, unique);
    List<HintSlot> slots = new ArrayList<>(count);
    int pIdx = 0;
    float beforeCursor = 0;
    float afterCursor = 0;
    int prevR = -1;
    for (int idx : order) {
      int r = resolved[idx];
      if (r != prevR) {
        while (positions[pIdx] < r) {
          pIdx++;
        }
        beforeCursor = -before[pIdx];
        afterCursor = 0;
        prevR = r;
      }
      MeasuredHint mh = segHints.get(idx);
      float relX;
      if (isBefore[idx]) {
        relX = beforeCursor;
        beforeCursor += mh.width;
      } else {
        relX = afterCursor;
        afterCursor += mh.width;
      }
      slots.add(new HintSlot(mh.hint, mh.ordinal, r, isBefore[idx], mh.element, relX, mh.width));
    }
    return new LineHints(positions, before, after, slots, 0);
  }

  /**
   * Shapes a string whose ranges have fonts of their own: each stretch of one font on its own,
   * the text's font with its fallbacks where no range sets one, then joined.
   */
  private GlyphRun shapeFonts(String text, Spans spans, int offset, int limit) {
    Font[] byChar = new Font[text.length()];
    for (int i = 0; i < spans.count; i++) {
      if (spans.kinds[i] == Spans.FONT
          && spans.tos[i] > offset
          && spans.froms[i] < offset + limit) {
        Arrays.fill(
            byChar,
            Math.clamp((long) spans.froms[i] - offset, 0, limit),
            Math.clamp((long) spans.tos[i] - offset, 0, limit),
            spans.fonts[i]);
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
   * Splits a long non-wrapping line into chunks of about {@link #CHUNK_SIZE} characters, returning
   * where each starts and the line's end. A chunk ends where graphemes surely break, after a space
   * or punctuation where one is near; only where none is, {@link BreakIterator} looks, given the
   * chunk alone since it reads all it is given, which starts at a boundary.
   */
  private static int[] splitChunks(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int pos = 0; text.length() - pos > CHUNK_SIZE; ) {
      int target = pos + CHUNK_SIZE;
      int cut = -1;
      int sure = -1;
      for (int i = target; i > target - CHUNK_WINDOW && cut < 0; i--) {
        if (graphemeBreak(text, i) && !(rtlNear(text, i - 1, -1) && rtlNear(text, i, 1))) {
          sure = sure < 0 ? i : sure;
          char before = text.charAt(i - 1);
          cut = Character.isWhitespace(before) || ",;)]}".indexOf(before) >= 0 ? i : -1;
        }
      }
      if (cut < 0 && sure >= 0) {
        cut = sure;
      } else if (cut < 0) { // a long run of marks, emoji or right-to-left letters
        // shortcut: a cut inside a right-to-left run shows its two parts in the order of the text,
        // left to right; order the chunks of such a run if long right-to-left passages need it.
        BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
        chars.setText(text.substring(pos, Math.min(text.length(), target + CHUNK_WINDOW)));
        int b = chars.preceding(CHUNK_SIZE + 1);
        if (b <= 0) { // one grapheme longer than the window: the chunk ends with it
          chars.setText(text.substring(pos));
          b = chars.following(CHUNK_SIZE);
        }
        cut = pos + b;
      }
      if (cut >= text.length()) {
        break;
      }
      starts.add(cut);
      pos = cut;
    }
    starts.add(text.length());
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * Whether the nearest letter from {@code from} on, going by {@code step}, within {@link
   * #CHUNK_WINDOW}, is right to left: a cut between two such letters would be inside a
   * right-to-left run, which {@link Font#shape} orders as a whole.
   */
  private static boolean rtlNear(String text, int from, int step) {
    for (int i = from;
        i >= 0 && i < text.length() && Math.abs(i - from) < CHUNK_WINDOW;
        i += step) {
      if (Character.isLetter(text.charAt(i))) {
        byte direction = Character.getDirectionality(text.charAt(i));
        return direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
            || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
      }
    }
    return false;
  }

  /**
   * Whether graphemes break at {@code at} whatever the text around it, so that the text on either
   * side can be segmented alone: no rule of Unicode's grapheme clusters joins the two characters
   * there, which are no mark, format or control character, surrogate, Hangul jamo, modifier letter
   * or one of the few letters that join (UAX #29).
   */
  static boolean graphemeBreak(String text, int at) {
    return at > 0 && at < text.length() && plain(text.charAt(at - 1)) && plain(text.charAt(at));
  }

  private static boolean plain(char c) {
    return switch (Character.getType(c)) {
      case Character.NON_SPACING_MARK,
          Character.ENCLOSING_MARK,
          Character.COMBINING_SPACING_MARK,
          Character.FORMAT,
          Character.CONTROL,
          Character.SURROGATE,
          Character.PRIVATE_USE,
          Character.UNASSIGNED,
          Character.MODIFIER_LETTER,
          Character.LINE_SEPARATOR,
          Character.PARAGRAPH_SEPARATOR -> false;
      default ->
        !(c >= 0x1100 && c <= 0x11FF
            || c >= 0xA960 && c <= 0xA97F
            || c >= 0xD7B0 && c <= 0xD7FF
            || c == 0x0D4E
            || c == 0x0E33
            || c == 0x0EB3);
    };
  }

  /**
   * Lazily shaped chunks of one long non-wrapping line of a multi-line field. Unshaped chunks count
   * as their length times {@link #advance}, rounded to the glyph grid; once shaped, a chunk's
   * exact width shifts the x of the chunks after it and updates {@link Lines#measured}.
   */
  private final class Chunks {
    final int lineIndex;
    final int lineStart;
    final String text;
    final Spans spans;
    final int[] bounds; // length n + 1, bounds[0] = 0, bounds[n] = text.length()
    final Para[] parts; // length n, lazily shaped
    final float[] widths; // length n, estimated until shaped, kept by the lines
    final double[] xs; // length n + 1, prefix sums of widths
    final boolean hasHints;

    Chunks(int lineIndex, int lineStart, String text, Spans spans, int[] bounds) {
      this.lineIndex = lineIndex;
      this.lineStart = lineStart;
      this.text = text;
      this.spans = spans;
      this.bounds = bounds;
      this.hasHints = lineHasHints(lineStart, lineStart + text.length());
      int n = bounds.length - 1;
      this.parts = new Para[n];
      this.xs = new double[n + 1];
      // What earlier trees of the view measured stays, so that a rebuild moves nothing.
      float[] kept = shownLines.chunkWidths(lineIndex, spans);
      if (kept == null || kept.length != n) {
        kept = new float[n];
        double grid = scale >= 1.5 && !font().wholePixels() ? 4 : 1;
        for (int i = 0; i < n; i++) {
          kept[i] = (float) (Math.round((bounds[i + 1] - bounds[i]) * advance * grid) / grid);
        }
        shownLines.chunkWidths(lineIndex, spans, kept);
      }
      this.widths = kept;
      for (int i = 0; i < n; i++) {
        xs[i + 1] = xs[i] + widths[i];
      }
    }

    float totalWidth() {
      return (float) xs[parts.length];
    }

    Para part(int i) {
      Para p = parts[i];
      if (p != null) {
        return p;
      }
      int from = bounds[i];
      int to = bounds[i + 1];
      String sub = text.substring(from, to);
      List<MeasuredHint> segHints = hasHints
          ? segmentHints(lineStart + from, lineStart + to, i == 0, i == parts.length - 1)
          : List.<MeasuredHint>of();
      // shortcut: tab stops in a chunked line are relative to the chunk's start rather than the
      // line's; pass the chunk's x modulo tabStop to GlyphRun#tabbed if long tabbed lines need
      // exact stops.
      p = shape(sub, spans, from, sub.length(), segHints);
      parts[i] = p;
      shownLines.shapedCharacters += sub.length();
      float actual = p.run.width();
      float delta = actual - widths[i];
      if (delta != 0) {
        chunksMoved = true;
        widths[i] = actual;
        for (int j = i + 1; j < xs.length; j++) {
          xs[j] += delta;
        }
        shownLines.measured(lineIndex, (float) xs[parts.length]);
      }
      return p;
    }

    float beforeGap(int at) {
      if (!hasHints || at <= 0) {
        return 0;
      }
      if (at >= text.length()) {
        Para last = part(parts.length - 1);
        return last.beforeGap(last.text.length());
      }
      int i = chunkOfOffset(at);
      int local = at - bounds[i];
      if (local == 0) {
        Para prev = part(i - 1);
        return prev.beforeGap(prev.text.length());
      }
      return part(i).beforeGap(local);
    }

    float afterGap(int at) {
      if (!hasHints || at >= text.length()) {
        return 0;
      }
      int i = chunkOfOffset(at);
      return part(i).afterGap(at - bounds[i]);
    }

    void emitHints(float top, float viewX, float placedWidth, List<PlacedHint> out) {
      if (!hasHints) {
        return;
      }
      int n = parts.length;
      if (xs[n] <= viewX || viewX + placedWidth <= 0) {
        return;
      }
      for (int i = chunkOfX(viewX); i < n && xs[i] < viewX + placedWidth; i++) {
        Para p = part(i);
        if (p.hints == null) {
          continue;
        }
        for (HintSlot slot : p.hints.slots) {
          float hx = (float) (xs[i] + p.pen(slot.resolvedAt) + slot.relX - viewX);
          if (hx + slot.width <= 0 || hx >= placedWidth) {
            continue;
          }
          out.add(new PlacedHint(slot.hint, slot.ordinal, slot.element, hx, top));
        }
      }
    }

    int chunkOfOffset(int at) {
      int n = parts.length;
      if (at <= 0) {
        return 0;
      }
      if (at >= text.length()) {
        return n - 1;
      }
      int found = Arrays.binarySearch(bounds, at);
      return found >= 0 ? found : -found - 2;
    }

    int chunkOfX(double x) {
      int n = parts.length;
      if (x <= 0) {
        return 0;
      }
      if (x >= xs[n]) {
        return n - 1;
      }
      int lo = 0;
      int hi = n - 1;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (xs[mid] <= x) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      return lo;
    }

    /** Shapes the chunk under {@code x}, advancing if shaping made it narrower than estimated. */
    int chunkAtX(double x) {
      int i = chunkOfX(x);
      while (true) {
        part(i);
        if (x >= xs[i + 1] && i + 1 < parts.length) {
          i++;
        } else {
          return i;
        }
      }
    }

    float pen(int at) {
      if (at <= 0) {
        return 0;
      }
      if (at >= text.length()) {
        part(parts.length - 1);
        return (float) xs[parts.length];
      }
      int i = chunkOfOffset(at);
      int local = at - bounds[i];
      if (local == 0) {
        return (float) xs[i];
      }
      Para p = part(i);
      return (float) xs[i] + p.pen(local);
    }

    /**
     * Where position {@code at} lies, as {@link #pen}, without shaping a chunk: in one not shaped
     * yet, as far into its estimated width as {@code at} is into its characters.
     */
    float penEstimate(int at) {
      int i = chunkOfOffset(Math.clamp(at, 0, text.length() - 1));
      int local = Math.clamp(at - bounds[i], 0, bounds[i + 1] - bounds[i]);
      if (parts[i] != null) {
        return (float) xs[i] + parts[i].pen(local);
      }
      return (float) (xs[i] + widths[i] * local / (bounds[i + 1] - bounds[i]));
    }

    /** Whether the chunk that position {@code at} is in has been shaped. */
    boolean shapedAt(int at) {
      return parts[chunkOfOffset(Math.clamp(at, 0, text.length() - 1))] != null;
    }

    void emitLines(float baseline, double viewLeft, double viewRight, List<Line> out) {
      int n = parts.length;
      if (xs[n] <= viewLeft || viewRight <= 0) {
        return;
      }
      for (int i = chunkOfX(viewLeft); i < n && xs[i] < viewRight; i++) {
        Para p = part(i);
        if (xs[i + 1] > viewLeft) {
          out.add(new Line(p.run, p.run.width(), baseline, p.colors, (float) xs[i]));
        }
      }
    }

    CaretPos nearestCaret(float x, boolean stops) {
      int i = chunkAtX(x);
      Para p = part(i);
      float localX = (float) (x - xs[i]);
      int best = 0;
      HintStop bestStop = HintStop.DEFAULT;
      float distance = Float.POSITIVE_INFINITY;
      for (int b : p.boundaries(0, p.text.length())) {
        float cx = p.pen(b);
        if (stops && p.hints != null) {
          float before = p.beforeGap(b);
          if (before > 0) {
            float d = Math.abs(cx - before - localX);
            if (d < distance) {
              distance = d;
              best = b;
              bestStop = HintStop.LEFT;
            }
          }
        }
        float d = Math.abs(cx - localX);
        if (d < distance) {
          distance = d;
          best = b;
          bestStop = HintStop.DEFAULT;
        }
        if (stops && p.hints != null) {
          float after = p.afterGap(b);
          if (after > 0) {
            float da = Math.abs(cx + after - localX);
            if (da < distance) {
              distance = da;
              best = b;
              bestStop = HintStop.RIGHT;
            }
          }
        }
      }
      return new CaretPos(bounds[i] + best, bestStop);
    }

    int hit(float x) {
      int i = chunkAtX(x);
      Para p = part(i);
      float localX = (float) (x - xs[i]);
      List<Integer> graphemes = p.boundaries(0, p.text.length());
      int found = graphemes.getFirst();
      for (int k = 0; k < graphemes.size() - 1; k++) {
        if (p.pen(graphemes.get(k)) <= localX) {
          found = graphemes.get(k);
        }
      }
      return bounds[i] + found;
    }

    int positionAt(float x) {
      if (x < 0) {
        return -1;
      }
      int i = chunkAtX(x);
      Para p = part(i);
      float localX = (float) (x - xs[i]);
      if (localX < 0 || localX >= p.run.width()) {
        return -1;
      }
      List<Integer> graphemes = p.boundaries(0, p.text.length());
      if (p.hints != null) {
        for (int k = 0; k < graphemes.size() - 1; k++) {
          int b0 = graphemes.get(k);
          int b1 = graphemes.get(k + 1);
          float g0 = p.pen(b0) + p.afterGap(b0);
          float g1 = p.pen(b1) - p.beforeGap(b1);
          if (localX < g0) {
            return -1;
          }
          if (localX < g1) {
            return bounds[i] + b0;
          }
        }
        return -1;
      }
      int found = -1;
      for (int k = 0; k < graphemes.size() - 1; k++) {
        if (p.pen(graphemes.get(k)) <= localX) {
          found = graphemes.get(k);
        } else {
          break;
        }
      }
      return found < 0 ? -1 : bounds[i] + found;
    }
  }

  /**
   * A paragraph shaped: its glyphs, the first glyph of each of its characters, where its lines may
   * break, and the rows the last width broke it into.
   */
  private static final class Para {
    final String text;
    final GlyphRun run;
    final int[] colors; // per glyph, or null for the text's color
    final Spans spans; // what the highlighter said about it, or null
    final int offset; // where the paragraph starts in the positions of spans
    final int[] glyphAt;
    final int[] breaks; // null without wrapping
    final Chunks chunks; // non-null only for a long non-wrapping line of a multi-line field
    final LineHints hints; // non-null only when this paragraph has inline hints
    float limit = Float.NaN; // the width rowStarts are for
    int[] rowStarts = {0};
    float[] rowWidths;

    Para(
        String text,
        GlyphRun run,
        int[] breaks,
        int[] colors,
        Spans spans,
        int offset,
        LineHints hints) {
      this.text = text;
      this.run = run;
      this.colors = colors;
      this.spans = spans;
      this.offset = offset;
      this.chunks = null;
      this.hints = hints;
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
      // A line breaks only where a cluster starts: never inside a ligature or a right-to-left run.
      this.breaks = breaks == null
          ? null
          : Arrays.stream(breaks)
              .filter(b -> b == text.length() || at[b] < run.count() && run.cluster(at[b]) == b)
              .toArray();
      this.rowWidths = new float[] {totalWidth()};
    }

    Para(String text, Spans spans, Chunks chunks) {
      this.text = text;
      this.run = null;
      this.colors = null;
      this.spans = spans;
      this.offset = 0;
      this.glyphAt = null;
      this.breaks = null;
      this.chunks = chunks;
      this.hints = null;
      this.rowWidths = new float[] {chunks.totalWidth()};
    }

    boolean hasHints() {
      return hints != null || (chunks != null && chunks.hasHints);
    }

    float beforeGap(int at) {
      if (chunks != null) {
        return chunks.beforeGap(at);
      }
      return hints == null ? 0 : hints.beforeGap(at);
    }

    float afterGap(int at) {
      if (chunks != null) {
        return chunks.afterGap(at);
      }
      return hints == null ? 0 : hints.afterGap(at);
    }

    float totalWidth() {
      if (chunks != null) {
        return chunks.totalWidth();
      }
      return hints != null && text.isEmpty() ? hints.emptyWidth : run.width();
    }

    /**
     * Breaks into rows no wider than {@code limit} where it can, greedily: each row takes segments
     * while they fit, and at least one. A segment wider than a whole row breaks inside it, as
     * late as fits, where a grapheme and a cluster start (CSS's {@code overflow-wrap:
     * break-word}); a cluster wider than the row stays whole on a row of its own. A limit of 0 is
     * the min-content width: every segment on a row of its own, whole. Returns where each row
     * starts.
     */
    int[] breakInto(float limit) {
      if (chunks != null) {
        rowStarts = new int[] {0};
        rowWidths = new float[] {chunks.totalWidth()};
        this.limit = limit;
        return rowStarts;
      }
      if (breaks == null || limit == Float.POSITIVE_INFINITY) {
        rowStarts = new int[] {0};
        rowWidths = new float[] {breaks == null ? totalWidth() : width(0, text.length())};
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
      BreakIterator chars = null;
      for (int next : breaks) {
        if (end > start && width(start, next) > limit) {
          found.add(start);
          widths.add(width(start, end));
          start = end;
        }
        if (limit > 0 && width(start, next) > limit) { // one segment wider than the row
          if (chars == null) {
            chars = BreakIterator.getCharacterInstance(Locale.ROOT);
            chars.setText(text);
          }
          int fits = start; // the last place the row may end, or start while there is none
          for (int b = chars.following(start);
              b != BreakIterator.DONE && b <= next && width(start, next) > limit; ) {
            if (b < next
                && (glyphAt[b] == run.count()
                    || run.cluster(glyphAt[b]) != b
                    || Character.isWhitespace(text.charAt(b)))) {
              // Inside a cluster, a ligature or a right-to-left run, or before the spaces that
              // end the segment, which stay at the end of its row.
              b = chars.next();
            } else if (fits == start || width(start, b) <= limit) {
              fits = b;
              b = chars.next();
            } else { // b does not fit: the row ends at fits, and b is tried on the next
              found.add(start);
              widths.add(width(start, fits));
              start = fits;
            }
          }
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

    /** The colors of the glyphs of characters {@code from} to {@code to}, or null. */
    int[] colors(int from, int to) {
      return colors == null ? null : Arrays.copyOfRange(colors, glyphAt[from], glyphAt[to]);
    }

    /** The width of characters {@code from} to {@code to}, without the white space at the end. */
    float width(int from, int to) {
      if (text.isEmpty() && hints != null) {
        return hints.emptyWidth;
      }
      while (to > from
          && Character.isWhitespace(text.charAt(to - 1))
          && (hints == null || hints.beforeGap(to) == 0 && hints.afterGap(to - 1) == 0)) {
        to--;
      }
      return run.pen(glyphAt[to]) - run.pen(glyphAt[from]);
    }

    /** The x of position {@code at}, from the start of the glyphs. */
    float pen(int at) {
      if (chunks != null) {
        return chunks.pen(at);
      }
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
      float from = run.pen(first) + afterGap(start);
      float to = run.pen(g) - beforeGap(end);
      return graphemes == 0 ? from : from + (to - from) * graphemes(text, start, at) / graphemes;
    }

    /**
     * The grapheme boundaries from {@code from} to {@code to}, both boundaries, with {@code to}.
     * Only that part is read: the iterator reads all it is given when set, and a row of a long
     * line is short.
     */
    List<Integer> boundaries(int from, int to) {
      BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
      chars.setText(text.substring(from, to));
      List<Integer> found = new ArrayList<>();
      found.add(from);
      for (int b = chars.following(0); b != BreakIterator.DONE; b = chars.next()) {
        found.add(from + b);
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
    return maskBounds == null
        ? index
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
    return placedSpans.isEmpty() ? 0 : shownWidth(placedSpans.get(line));
  }

  /** How wide a line's glyphs are when it is not shortened: a cut line's run past its row. */
  private float shownWidth(Span span) {
    return span.cut ? paras[span.paragraph].width(span.from, span.to) : span.width;
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
    return x(index, HintStop.DEFAULT);
  }

  /** Like {@link #x(int)}, at the given visual {@link HintStop} when inline hints sit at it. */
  float x(int index, HintStop stop) {
    if (multiline) {
      return caretX(index, stop);
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
    int at = Math.clamp((long) index - starts[p], span.from, span.to);
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
    return nearestCaret(x, y).index();
  }

  /**
   * The grapheme boundary and {@link HintStop} nearest to a point: a press over an inline hint's
   * gap lands on the nearer stop at that hint's offset.
   */
  CaretPos nearestCaret(float x, float y) {
    if (multiline) {
      Lines lines = shownLines();
      if (lines.hasBlocks()) {
        int slot = lines.slotAt(y, lineHeight);
        if (inBlockBand(lines, slot, y)) {
          return new CaretPos(blockClickPosition(lines, slot), HintStop.DEFAULT);
        }
        return nearestCaretOnRow(slot, rowInLine(lines, slot, y), x, true);
      }
      int[] at = rowAt(y);
      return at[0] < 0
          ? new CaretPos(0, HintStop.DEFAULT)
          : nearestCaretOnRow(at[0], at[1], x, true);
    }
    if (placedSpans.isEmpty()) {
      return new CaretPos(0, HintStop.DEFAULT);
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
    return new CaretPos(fromGlyphs(best), HintStop.DEFAULT);
  }

  /**
   * The start of the grapheme of the shown string under a point: the last one that starts left of
   * it, the first one left of the line.
   */
  int hit(float x, float y) {
    if (multiline) {
      Lines lines = shownLines();
      if (lines.hasBlocks()) {
        int slot = lines.slotAt(y, lineHeight);
        if (inBlockBand(lines, slot, y)) {
          return blockClickPosition(lines, slot);
        }
        return hitOnRow(slot, rowInLine(lines, slot, y), x);
      }
      int[] at = rowAt(y);
      return at[0] < 0 ? 0 : hitOnRow(at[0], at[1], x);
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

  private int hitOnRow(int line, int row, float x) {
    Para para = para(line);
    int start = shownLines().start(line);
    if (para.chunks != null) {
      return start + para.chunks.hit(x);
    }
    List<Integer> bounds = rowBounds(para, row);
    int found = bounds.getFirst();
    float left = para.pen(para.rowStart(row));
    for (int i = 0; i < bounds.size() - 1; i++) {
      if (para.pen(bounds.get(i)) - left <= x) {
        found = bounds.get(i);
      }
    }
    return start + found;
  }

  /**
   * The start of the grapheme of the shown string strictly under a point in a multi-line field, in
   * physical pixels from the top left of the text (after scroll), or -1 if the point is outside
   * the text, past the end of a line's text, on an empty line or over a block band.
   */
  int positionAt(float x, float y) {
    if (!multiline || !laidOut || placeholderShown || x < 0 || y < 0) {
      return -1;
    }
    Lines lines = shownLines();
    if (lines.shownCount() == 0) {
      return -1;
    }
    double lh = lineHeight;
    if (y >= lines.totalHeight(lh)) {
      return -1;
    }
    int line;
    int row;
    if (lines.hasBlocks()) {
      int slot = lines.slotAt(y, lh);
      if (inBlockBand(lines, slot, y)) {
        return -1;
      }
      Para para = para(slot);
      long within = (long) Math.floor((y - lines.lineTop(slot, lh)) / lh);
      if (within < 0 || within >= para.rowCount()) {
        return -1;
      }
      line = slot;
      row = (int) within;
    } else {
      long rowIndex = (long) Math.floor(y / lh);
      if (rowIndex < 0 || rowIndex >= lines.totalRows()) {
        return -1;
      }
      line = lines.lineOfRow(rowIndex);
      if (line < 0 || lines.isHidden(line)) {
        return -1;
      }
      Para para = para(line);
      long within = rowIndex - lines.rowsBefore(line);
      if (within < 0 || within >= para.rowCount()) {
        return -1;
      }
      row = (int) within;
    }
    return positionOnRow(line, row, x);
  }

  private int positionOnRow(int line, int row, float x) {
    Para para = para(line);
    if (para.text.isEmpty()) {
      return -1;
    }
    int start = shownLines().start(line);
    if (para.chunks != null) {
      int local = para.chunks.positionAt(x);
      return local < 0 ? -1 : start + local;
    }
    int rowStart = para.rowStart(row);
    int rowEnd = para.rowEnd(row);
    if (rowStart >= rowEnd) {
      return -1;
    }
    float left = para.pen(rowStart);
    float right = para.pen(rowEnd) - left;
    if (x >= right) {
      return -1;
    }
    List<Integer> bounds = para.boundaries(rowStart, rowEnd);
    if (para.hints != null) {
      for (int i = 0; i < bounds.size() - 1; i++) {
        int b0 = bounds.get(i);
        int b1 = bounds.get(i + 1);
        float g0 = para.pen(b0) + para.afterGap(b0) - left;
        float g1 = para.pen(b1) - para.beforeGap(b1) - left;
        if (x < g0) {
          return -1;
        }
        if (x < g1) {
          return start + b0;
        }
      }
      return -1;
    }
    int found = -1;
    for (int i = 0; i < bounds.size() - 1; i++) {
      if (para.pen(bounds.get(i)) - left <= x) {
        found = bounds.get(i);
      } else {
        break;
      }
    }
    return found < 0 ? -1 : start + found;
  }

  private boolean inBlockBand(Lines lines, int slot, double y) {
    return slot >= lines.count()
        || lines.isHidden(slot)
        || lines.hasBlockAt(slot) && y < lines.lineTop(slot, lineHeight);
  }

  private int blockClickPosition(Lines lines, int slot) {
    if (slot < lines.count() && !lines.isHidden(slot)) {
      return lines.start(slot);
    }
    int next = slot < lines.count() ? lines.nextVisible(slot) : -1;
    if (next >= 0) {
      return lines.start(next);
    }
    int prev = lines.visibleAtOrBefore(Math.min(slot, lines.count() - 1));
    return prev >= 0 ? lines.end(prev) : 0;
  }

  private int rowInLine(Lines lines, int line, double y) {
    Para para = para(line);
    long within =
        Math.max(0, (long) Math.floor((y - lines.lineTop(line, lineHeight)) / lineHeight));
    return (int) Math.min(within, (long) para.rowCount() - 1);
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

  /** For {@link El}: the folded line ranges of a multi-line field. */
  void folds(List<El.Fold> folds) {
    List<El.Fold> next = folds == null ? List.of() : folds;
    if (!next.equals(this.folds)) {
      this.folds = next;
      viewStale = true;
    }
  }

  /** For {@link El}: the blocks between lines of a multi-line field and their measurer. */
  void blocks(List<El.Block> blocks, BlockMeasurer measurer) {
    List<El.Block> next = blocks == null ? List.of() : blocks;
    this.blockMeasurer = measurer;
    if (!next.equals(this.blocks)) {
      viewStale = true;
    }
    this.blocks = next;
  }

  /** For {@link El}: the inline hints of a multi-line field and their measurer. */
  void hints(List<El.Hint> hints, HintMeasurer measurer) {
    List<El.Hint> next = hints == null ? List.of() : hints;
    this.hintMeasurer = measurer;
    if (!next.equals(this.hints)) {
      this.hints = next;
      sortedHints = null;
      invalidate();
    }
  }

  /** Hints within the shown string, sorted by offset and BEFORE before AFTER. */
  private SortedHint[] sortedHints() {
    if (hints.isEmpty() || placeholderShown) {
      return NO_HINTS;
    }
    if (sortedHints != null) {
      return sortedHints;
    }
    // shortcut: hint offsets are taken as positions in the shown string, so while an input method
    // composes, hints after the composition sit off by its length; map them through it if needed.
    int len = shown.length();
    List<El.Hint> ordered = new ArrayList<>(hints.size());
    for (El.Hint h : hints) {
      if (h.at() <= len) {
        ordered.add(h);
      }
    }
    if (ordered.isEmpty()) {
      sortedHints = new SortedHint[0];
      return sortedHints;
    }
    ordered.sort((a, b) -> {
      int c = Integer.compare(a.at(), b.at());
      return c != 0 ? c : Integer.compare(a.affinity().ordinal(), b.affinity().ordinal());
    });
    SortedHint[] out = new SortedHint[ordered.size()];
    int prevAt = -1;
    int ordinal = 0;
    for (int i = 0; i < ordered.size(); i++) {
      El.Hint h = ordered.get(i);
      if (h.at() != prevAt) {
        prevAt = h.at();
        ordinal = 0;
      } else {
        ordinal++;
      }
      out[i] = new SortedHint(h, ordinal);
    }
    sortedHints = out;
    return out;
  }

  /** First index in {@code all} with {@code at >= target}. */
  private static int lowerBound(SortedHint[] all, int target) {
    int lo = 0;
    int hi = all.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (all[mid].hint.at() < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private boolean lineHasHints(int lineStart, int lineEnd) {
    SortedHint[] all = sortedHints();
    if (all.length == 0) {
      return false;
    }
    int i = lowerBound(all, lineStart);
    return i < all.length && all[i].hint.at() <= lineEnd;
  }

  /**
   * Builds and measures the hints belonging to {@code [segStart, segEnd]} in {@code shown}, empty
   * when none belong to it. At an internal chunk boundary, BEFORE goes to the chunk before
   * and AFTER to the chunk after.
   */
  private List<MeasuredHint> segmentHints(
      int segStart, int segEnd, boolean isLineStart, boolean isLineEnd) {
    SortedHint[] all = sortedHints();
    if (all.length == 0 || hintMeasurer == null) {
      return List.of();
    }
    int i = lowerBound(all, segStart);
    if (i >= all.length || all[i].hint.at() > segEnd) {
      return List.of();
    }
    List<MeasuredHint> out = new ArrayList<>();
    for (; i < all.length && all[i].hint.at() <= segEnd; i++) {
      SortedHint sh = all[i];
      int at = sh.hint.at();
      boolean include;
      if (at > segStart && at < segEnd) {
        include = true;
      } else if (at == segStart) {
        include = isLineStart || sh.hint.affinity() == El.Hint.Affinity.AFTER;
      } else {
        include = isLineEnd || sh.hint.affinity() == El.Hint.Affinity.BEFORE;
      }
      if (!include) {
        continue;
      }
      El built = hintMeasurer.measure(sh.hint, sh.ordinal, scale, lineHeight);
      out.add(new MeasuredHint(sh.hint, sh.ordinal, at - segStart, built, hintWidth(built)));
    }
    return out;
  }

  /** Physical width reserved for one measured hint element, matching {@link #segmentHints}. */
  private float hintWidth(El built) {
    double grid = scale >= 1.5 && !font().wholePixels() ? 4 : 1;
    float logical = built.margin(Side.LEFT) + built.width() + built.margin(Side.RIGHT);
    return (float) Math.max(0, Math.ceil(logical * scale * grid - 1e-4) / grid);
  }

  /**
   * For {@link El}: drops the shaped line containing {@code hintEl} and marks the view stale when
   * a look changed its reserved width, so the next layout reshapes that line with the new width.
   */
  boolean reshapeHint(El hintEl) {
    if (!hasHints() || rows.isEmpty()) {
      return false;
    }
    float width = hintWidth(hintEl);
    int lastLine = -1;
    for (Row row : rows) {
      if (row.line == lastLine) {
        continue;
      }
      lastLine = row.line;
      Para para = row.para;
      if (para.chunks != null) {
        for (Para chunk : para.chunks.parts) {
          if (chunk != null && chunk.hints != null) {
            for (HintSlot slot : chunk.hints.slots) {
              if (slot.element == hintEl) {
                if (width == slot.width) {
                  return false;
                }
                shapedLines.remove(row.line);
                viewStale = true;
                return true;
              }
            }
          }
        }
        continue;
      }
      if (para.hints == null) {
        continue;
      }
      for (HintSlot slot : para.hints.slots) {
        if (slot.element == hintEl) {
          if (width == slot.width) {
            return false;
          }
          shapedLines.remove(row.line);
          viewStale = true;
          return true;
        }
      }
    }
    return false;
  }

  /** For {@link El}: the blocks placed by the last {@link #layOut}. */
  List<PlacedBlock> placedBlocks() {
    lines();
    return placedBlocks;
  }

  /** The blocks to show: while the placeholder shows, only inserts before line 0. */
  private List<El.Block> activeBlocks() {
    return placeholderShown
        ? blocks.stream()
            .filter(b -> b.placement() == El.Block.Placement.INSERT && b.first() == 0)
            .toList()
        : blocks;
  }

  private Lines shownLines() {
    prepare();
    shownLines.folds(placeholderShown ? List.of() : folds);
    List<El.Block> active = activeBlocks();
    shownLines.blocks(active);
    shownLines.hints(placeholderShown ? List.of() : hints);
    wrapping();
    if (!active.isEmpty()) {
      shownLines.blocksLayout(
          laidOut
              ? new Wrapping(
                  placedWidth, font(), size, scale, List.of(features), tabSize, systemFonts)
              : null,
          lineHeight);
    }
    return shownLines;
  }

  /** Tells the lines what their rows are counted for: wrapping at the width, or none. */
  private void wrapping() {
    if (!wraps) {
      shownLines.wrap(null, 1);
      shownLines.measuredFor(
          new Wrapping(0, font(), size, scale, List.of(features), tabSize, systemFonts));
      return;
    }
    if (!laidOut) {
      return; // the rows are for the width layout gives, which the next placed() tells
    }
    shownLines.wrap(
        new Wrapping(placedWidth, font(), size, scale, List.of(features), tabSize, systemFonts),
        Math.max(placedWidth / Math.max(advance, 1), 1));
  }

  /** Line {@code line} of the shown string shaped, with its rows at the width; measured once. */
  private Para para(int line) {
    Lines lines = shownLines();
    Para para = shapedLines.get(line);
    if (para == null) {
      if (shapedLines.size() >= SHAPED_LINES || shapedLineCharacters >= SHAPED_CHARACTERS) {
        shapedLines.clear(); // keys that walk far in one tree: keep what this tree shapes bounded
        shapedLineCharacters = 0;
      }
      Spans spans = highlighter == null || placeholderShown
          ? null
          : lines.spans(line, shown, highlighter, highlightKey);
      int lineStart = lines.start(line);
      int lineEnd = lines.end(line);
      String text = shown.substring(lineStart, lineEnd);
      if (!wraps && text.length() > CHUNK_SIZE) {
        para = new Para(text, spans, new Chunks(line, lineStart, text, spans, splitChunks(text)));
      } else {
        List<MeasuredHint> segHints =
            hasHints() ? segmentHints(lineStart, lineEnd, true, true) : List.<MeasuredHint>of();
        para = shape(text, spans, 0, text.length(), segHints);
        lines.shapedCharacters += text.length();
      }
      shapedLines.put(line, para);
      shapedLineCharacters += lineEnd - lineStart;
      lines.shaped++;
    }
    float limit = wraps && laidOut ? placedWidth + SLACK : Float.POSITIVE_INFINITY;
    if (limit != para.limit) { // new, or shaped before layout placed the text at another width
      para.breakInto(limit);
      if (lines.wraps()) {
        if (laidOut) { // before layout, the rows the lines keep are those of the last width
          lines.rows(line, para.rowCount());
        }
      } else {
        lines.measured(line, para.totalWidth());
      }
    }
    return para;
  }

  /**
   * For {@link El#fitLines}: the width and height of every row and block at a content box this
   * wide, in logical pixels. When wrapping, the width is the widest row with room for the caret, as
   * a wrapping single-line field measures; without wrapping it is 0.
   */
  private Size fittedSize(float width) {
    Lines lines = shownLines();
    float widest = 0;
    int rows;
    if (!wraps) {
      rows = lines.shownCount();
    } else {
      float limit;
      if (width == MAX_CONTENT || Float.isNaN(width)) {
        limit = Float.POSITIVE_INFINITY;
      } else if (width == MIN_CONTENT) {
        limit = 0;
      } else {
        limit = Math.max(0, width) * scale + SLACK;
      }
      rows = 0;
      for (int line = lines.firstVisible();
          line >= 0 && line < lines.count();
          line = lines.nextVisible(line)) {
        Para para = para(line);
        float was = para.limit;
        if (limit != was) {
          para.breakInto(limit);
        }
        rows += para.rowCount();
        for (float rowWidth : para.rowWidths) {
          widest = Math.max(widest, rowWidth);
        }
        if (limit != was) {
          para.breakInto(was);
        }
      }
      widest += Editor.caretWidth(scale) + SLACK; // layout gives back a hair less at some scales
    }
    float height = rows * lineHeight;
    if (lines.hasBlocks()) {
      if (blockMeasurer != null && width > 0 && width != MAX_CONTENT && Float.isFinite(width)) {
        for (int slot = lines.nextBlockSlot(-1); slot >= 0; slot = lines.nextBlockSlot(slot)) {
          int si = lines.blockSlotIndex(slot);
          int start = lines.blockSlotStart(si);
          int end = lines.blockSlotEnd(si);
          for (int bi = start; bi < end; bi++) {
            El.Block b = lines.activeBlock(bi);
            if (b.lines() == 0) {
              El built = blockMeasurer.measure(b, bi - start, width, scale, lineHeight);
              lines.blockHeight(
                  bi, Math.max(0, Math.round(Math.ceil(built.height() * scale - 1e-4))));
            }
          }
        }
      }
      height += lines.totalBlockPixels();
    }
    return new Size(widest / scale, height / scale, firstBaseline / scale);
  }

  /**
   * For {@link El#fitLines} and {@link El#maxLines}: the rows of lines at a content box this wide,
   * in logical pixels or {@link Measure#MAX_CONTENT} or MIN, stopping once {@code maxRows} are
   * counted so that long texts stay cheap.
   */
  private int fittedRows(float width, int maxRows) {
    Lines lines = shownLines();
    if (lines.shownCount() >= maxRows
        || !wraps
        || width == MAX_CONTENT
        || Float.isInfinite(width)
        || Float.isNaN(width)) {
      return Math.min(lines.shownCount(), maxRows); // at least one row per shown line
    }
    float limit = width == MIN_CONTENT ? 0 : width * scale + SLACK;
    int rows = 0;
    for (int line = lines.firstVisible();
        line >= 0 && line < lines.count() && rows < maxRows;
        line = lines.nextVisible(line)) {
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
    return Math.min(rows, maxRows);
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

  /** For {@link El}: lays out the view again, as a block in it changed its height. */
  void restale() {
    viewStale = true;
  }

  /** Whether {@link #scrolled} moved the view since the last {@link #layOut}. */
  boolean viewStale() {
    return viewStale;
  }

  /**
   * The vertical scroll offset {@code y} in logical pixels as a fractional 0-based line index of
   * this multi-line text: the line whose top lies at or before {@code y}, plus the fraction of the
   * distance to the next line's top (blocks between lines counted in that distance).
   */
  double offsetToLine(double y) {
    Lines ls = shownLines();
    int count = ls.count();
    double lh = lineHeight;
    double py = y * scale;
    double first = ls.lineTop(0, lh);
    if (py < first) {
      return (py - first) / lh;
    }
    double end = ls.lineTop(count, lh);
    if (py >= end) {
      return count + (py - end) / lh;
    }
    int slot = ls.slotAt(py, lh);
    int line = py < ls.lineTop(slot, lh) ? slot - 1 : slot;
    double top = ls.lineTop(line, lh);
    double next = ls.lineTop(line + 1, lh);
    return next > top ? line + (py - top) / (next - top) : line;
  }

  /**
   * The vertical scroll offset in logical pixels for a fractional 0-based line index of this
   * multi-line text, as last laid out: line {@code n} is the top of that line, and the fraction
   * runs on through the {@link El#blocks blocks} inserted after it. Below 0 and beyond the last
   * line it goes on by whole line heights, so {@code -k} is the top of a block of {@code k} lines
   * before the first line.
   */
  public double lineToOffset(double line) {
    Lines ls = shownLines();
    int count = ls.count();
    double lh = lineHeight;
    double py;
    if (line < 0) {
      py = ls.lineTop(0, lh) + line * lh;
    } else if (line >= count) {
      py = ls.lineTop(count, lh) + (line - count) * lh;
    } else {
      int whole = (int) Math.floor(line);
      double top = ls.lineTop(whole, lh);
      double next = ls.lineTop(whole + 1, lh);
      py = top + (line - whole) * (next - top);
    }
    return py / scale;
  }

  /** The vertical scroll of a multi-line field in physical pixels; 0 for other text. */
  float scrollY() {
    return multiline ? viewY : 0;
  }

  /** The height of all rows and blocks of a multi-line field, in logical pixels. */
  double extentHeight() {
    return shownLines().totalHeight(lineHeight) / scale;
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
   * Lays out the rows (and any blocks) of a multi-line field in view and {@link #MARGIN_ROWS}
   * around them, shaping the lines they are on unless this tree has shaped them already.
   *
   * @return how far the line or block at the top of the view moved down because rows or blocks
   *     above it were measured for the first time, in physical pixels; the editor scrolls as far,
   *     so that what is in view stays where it is
   */
  float layOut() {
    Lines lines = shownLines();
    viewStale = false;
    chunksMoved = false;
    if (!lines.hasBlocks()) {
      placedBlocks = List.of();
      return layOutRowsOnly(lines);
    }
    return layOutWithBlocks(lines);
  }

  private float layOutRowsOnly(Lines lines) {
    double lh = lineHeight;
    int anchor = lines.lineOfRow((long) Math.floor(viewY / lh));
    long anchorRows = anchor >= 0 ? lines.rowsBefore(anchor) : 0;
    double from = viewY - MARGIN_ROWS * lh;
    double to = viewY + placedHeight + MARGIN_ROWS * lh;
    long firstRow = Math.max(0, (long) Math.floor(from / lh));
    int line = lines.lineOfRow(firstRow);
    long skip = line >= 0 ? firstRow - lines.rowsBefore(line) : 0;
    List<Line> laid = new ArrayList<>();
    List<Row> placed = new ArrayList<>();
    firstInView = -1;
    lastInView = -1;
    float marginX = Math.max(4 * advance, 32 * scale);
    double viewLeft = Math.max(0, viewX - marginX);
    double viewRight = viewX + placedWidth + marginX;
    boolean pending = false;
    for (; line >= 0 && line < lines.count(); line = lines.nextVisible(line), skip = 0) {
      Para para = para(line);
      double lineTop = lines.rowsBefore(line) * lh;
      if (lineTop >= to) {
        break;
      }
      pending |= emitLine(line, para, lineTop, skip, to, lh, viewLeft, viewRight, laid, placed);
    }
    if (!wraps) {
      for (Row row : placed) {
        lines.measured(row.line, row.para.totalWidth());
      }
    }
    this.lines = List.copyOf(laid);
    this.rows = placed;
    highlightPending = pending;
    marks = marks(placed);
    return anchor >= 0 ? (float) ((lines.rowsBefore(anchor) - anchorRows) * lh) : 0;
  }

  private float layOutWithBlocks(Lines lines) {
    double lh = lineHeight;
    int anchorSlot = lines.slotAt(viewY, lh);
    boolean anchorInBlock = anchorSlot >= lines.count()
        || lines.isHidden(anchorSlot)
        || (lines.hasBlockAt(anchorSlot) && viewY < lines.lineTop(anchorSlot, lh));
    int anchorBlock = anchorInBlock ? lines.blockAtY(anchorSlot, viewY, lh) : -1;
    double anchorTopBefore = anchorBlock >= 0
        ? lines.blockTop(anchorBlock, lh)
        : anchorInBlock ? lines.slotTop(anchorSlot, lh) : lines.lineTop(anchorSlot, lh);
    double from = viewY - MARGIN_ROWS * lh;
    double to = viewY + placedHeight + MARGIN_ROWS * lh;
    int slot = from <= 0 ? lines.nextSlot(-1) : lines.slotAt(from, lh);
    long skip = 0;
    if (slot >= 0 && slot < lines.count() && !lines.isHidden(slot)) {
      double lt = lines.lineTop(slot, lh);
      if (from > lt) {
        skip = (long) Math.floor((from - lt) / lh);
      }
    }
    List<Line> laid = new ArrayList<>();
    List<Row> placed = new ArrayList<>();
    List<PlacedBlock> blocksPlaced = new ArrayList<>();
    firstInView = -1;
    lastInView = -1;
    float marginX = Math.max(4 * advance, 32 * scale);
    double viewLeft = Math.max(0, viewX - marginX);
    double viewRight = viewX + placedWidth + marginX;
    float contentWidthLogical = placedWidth / scale;
    boolean pending = false;
    for (; slot >= 0; slot = lines.nextSlot(slot), skip = 0) {
      double slotTop = lines.slotTop(slot, lh);
      if (slotTop >= to) {
        break;
      }
      int si = lines.blockSlotIndex(slot);
      if (si >= 0) {
        int bStart = lines.blockSlotStart(si);
        int bEnd = lines.blockSlotEnd(si);
        double blockY = slotTop;
        for (int bi = bStart; bi < bEnd; bi++) {
          El.Block block = lines.activeBlock(bi);
          double bh = lines.blockHeight(bi);
          if ((blockY + bh > from || (bh == 0 && blockY >= from)) && blockY < to) {
            El built = null;
            if (blockMeasurer != null) {
              built =
                  blockMeasurer.measure(block, bi - bStart, contentWidthLogical, scale, lineHeight);
              if (block.lines() == 0) {
                long measuredPx = Math.max(0, Math.round(Math.ceil(built.height() * scale - 1e-4)));
                lines.blockHeight(bi, measuredPx);
              }
            }
            bh = lines.blockHeight(bi);
            if ((blockY + bh > from || (bh == 0 && blockY >= from)) && blockY < to) {
              blocksPlaced.add(new PlacedBlock(block, bi - bStart, built, blockY, bh));
            }
          }
          blockY += bh;
          if (blockY >= to) {
            break;
          }
        }
        if (blockY >= to) {
          break;
        }
      }
      if (slot < lines.count() && !lines.isHidden(slot)) {
        Para para = para(slot);
        double lineTop = lines.lineTop(slot, lh);
        if (lineTop >= to) {
          break;
        }
        pending |= emitLine(slot, para, lineTop, skip, to, lh, viewLeft, viewRight, laid, placed);
      }
    }
    if (!wraps) {
      for (Row row : placed) {
        lines.measured(row.line, row.para.totalWidth());
      }
    }
    this.lines = List.copyOf(laid);
    this.rows = placed;
    this.placedBlocks = List.copyOf(blocksPlaced);
    highlightPending = pending;
    marks = marks(placed);
    double anchorTopAfter = anchorBlock >= 0
        ? lines.blockTop(anchorBlock, lh)
        : anchorInBlock ? lines.slotTop(anchorSlot, lh) : lines.lineTop(anchorSlot, lh);
    return (float) (anchorTopAfter - anchorTopBefore);
  }

  private boolean emitLine(
      int line,
      Para para,
      double lineTop,
      long skip,
      double to,
      double lh,
      double viewLeft,
      double viewRight,
      List<Line> laid,
      List<Row> placed) {
    boolean pending = false;
    if (para.chunks != null) {
      if (skip == 0) {
        pending = highlighter != null && !placeholderShown && para.spans == null;
        float baseline = (float) (lineTop - viewY + firstBaseline);
        para.chunks.emitLines(baseline, viewLeft, viewRight, laid);
        placed.add(new Row(line, 0, para, lineTop));
        if (lineTop < viewY + placedHeight && lineTop + lh > viewY) {
          firstInView = firstInView < 0 ? line : firstInView;
          lastInView = line;
        }
      }
      return pending;
    }
    for (int r = (int) Math.min(skip, para.rowCount()); r < para.rowCount(); r++) {
      double top = lineTop + r * lh;
      if (top >= to) {
        break;
      }
      pending |= highlighter != null && !placeholderShown && para.spans == null;
      laid.add(new Line(
          para.slice(para.rowStart(r), para.rowEnd(r)),
          para.rowWidths[r],
          (float) (top - viewY + firstBaseline),
          para.colors(para.rowStart(r), para.rowEnd(r)),
          0));
      placed.add(new Row(line, r, para, top));
      if (top < viewY + placedHeight && top + lh > viewY) {
        firstInView = firstInView < 0 ? line : firstInView;
        lastInView = line;
      }
    }
    return pending;
  }

  /** Whether the last {@link #layOut} measured a chunk of a long line, which moved what follows. */
  boolean chunksMoved() {
    return chunksMoved;
  }

  /**
   * Shapes the chunks of long lines that positions of the shown string are in, so that measuring
   * them after the rows are laid out moves nothing that is placed already.
   */
  void measureChunks(int... positions) {
    Lines lines = shownLines();
    for (int at : positions) {
      int line = lines.line(Math.clamp(at, 0, lines.length()));
      if (!lines.isHidden(line) && !wraps && lines.end(line) - lines.start(line) > CHUNK_SIZE) {
        Para p = para(line);
        int local = at - lines.start(line);
        p.pen(local);
        if (hasHints() && p.chunks != null) {
          p.chunks.beforeGap(local);
        }
      }
    }
  }

  /** Whether any laid-out line of this multi-line field was shaped plain waiting for its spans. */
  boolean highlightPending() {
    return highlightPending;
  }

  /**
   * Advances highlighting towards the laid-out lines that were shaped plain when a previous frame's
   * budget ran out, dropping any that now have their spans so the field lays them out again.
   *
   * @return whether any laid-out line got its spans and needs a new layout
   */
  boolean advanceHighlight() {
    if (!highlightPending || !multiline || !laidOut || highlighter == null || placeholderShown) {
      return false;
    }
    Lines lines = shownLines();
    boolean gained = false;
    boolean stillPending = false;
    int last = -1;
    for (Row row : rows) {
      if (row.line == last) {
        continue;
      }
      last = row.line;
      if (row.para.spans != null) {
        continue;
      }
      Spans spans = lines.spans(row.line, shown, highlighter, highlightKey);
      if (spans != null) {
        shapedLines.remove(row.line);
        gained = true;
      } else {
        stillPending = true;
      }
    }
    if (gained) {
      viewStale = true;
    }
    highlightPending = stillPending;
    return gained;
  }

  /** The marks of the rows laid out, from the highlighter's spans, decorator and outline. */
  private List<Mark> marks(List<Row> placed) {
    underlined = new int[0];
    squiggled = new int[0];
    boolean outlined = hasOutline();
    if (placeholderShown
        || !outlined
            && decorator == null
            && scopeDecorator == null
            && (highlighter == null
                || placed.stream()
                    .noneMatch(row -> row.para.spans != null
                        && (row.para.spans.count > 0
                            || row.para.spans.lineBackgrounds.length > 0)))) {
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
      if (decorator != null || scopeDecorator != null) {
        decor = decorated.computeIfAbsent(row.line, line -> {
          Spans out = new Spans(para.text.length(), false);
          if (decorator != null) {
            decorator.line(line, lines.start(line), para.text, out);
          }
          if (scopeDecorator != null) {
            scopeDecorator.line(line, lines.start(line), para.text, out);
          }
          return out;
        });
      }
      float top = (float) (row.top - viewY);
      float left = para.pen(para.rowStart(row.row));
      int from = para.rowStart(row.row);
      int to = para.rowEnd(row.row);
      Chunks chunks = para.chunks;
      int visFirstChunk = 0;
      int visLastChunk = 0;
      int visFrom = from;
      int visTo = to;
      boolean visibleHorizontally = true;
      if (chunks != null) {
        if (viewX >= chunks.xs[chunks.parts.length] || viewX + placedWidth <= 0) {
          visibleHorizontally = false;
        } else {
          visFirstChunk = chunks.chunkOfX(Math.max(0, viewX));
          visLastChunk = chunks.chunkOfX(viewX + placedWidth);
          visFrom = chunks.bounds[visFirstChunk];
          visTo = chunks.bounds[visLastChunk + 1];
        }
      }
      for (Spans spans : new Spans[] {para.spans, decor}) {
        if (spans == null) {
          continue;
        }
        for (int argb : spans.lineBackgrounds) {
          lineMarks.add(new Mark(new Box(0, top, placedWidth, lineHeight), argb));
        }
        if (!visibleHorizontally) {
          continue;
        }
        for (int i = 0; i < spans.count; i++) {
          int kind = spans.kinds[i];
          int a = Math.max(spans.froms[i], from);
          int b = Math.min(spans.tos[i], to);
          if (kind == Spans.COLOR || kind == Spans.FONT || b <= a) {
            continue;
          }
          if (chunks != null
              && (kind == Spans.GUIDE ? a < visFrom || a >= visTo : b <= visFrom || a >= visTo)) {
            continue; // outside the chunks in view, which are all that is shaped
          }
          float x0 = chunks != null && a < visFrom
              ? (float) (chunks.xs[visFirstChunk] - viewX)
              : para.pen(a) + (kind == Spans.GUIDE ? 0 : para.afterGap(a)) - left - viewX;
          float x1 = chunks != null && b > visTo
              ? (float) (chunks.xs[visLastChunk + 1] - viewX)
              : para.pen(b) - (kind == Spans.GUIDE ? 0 : para.beforeGap(b)) - left - viewX;
          switch (kind) {
            case Spans.BACKGROUND ->
              ranges.add(new Mark(new Box(x0, top, x1 - x0, lineHeight), spans.colors[i]));
            case Spans.GUIDE ->
              ranges.add(
                  new Mark(new Box(Math.round(x0), top, thick, lineHeight), spans.colors[i]));
            case Spans.SQUIGGLE -> {
              // A later squiggle wins where it lies: cut out of this one, so that the edges of the
              // two waves do not mix their colors. From the underline to the row's bottom: the
              // room the wave may take.
              for (int[] piece : uncovered(spans, i, a, b, spans == decor ? null : decor)) {
                float p0 = para.pen(piece[0]) + para.afterGap(piece[0]) - left - viewX;
                float p1 = para.pen(piece[1]) - para.beforeGap(piece[1]) - left - viewX;
                if (p1 > p0) {
                  unders.add(new Mark(
                      new Box(p0, top + under, p1 - p0, lineHeight - under),
                      spans.colors[i],
                      true));
                }
              }
            }
            default -> {
              float y = kind == Spans.UNDERLINE ? under : strike;
              if (x1 > x0) {
                unders.add(new Mark(new Box(x0, top + y, x1 - x0, thick), spans.colors[i]));
              }
            }
          }
        }
      }
    }
    lineMarks.addAll(ranges);
    if (outlined) {
      addOutlineMarks(placed, lines, thick, lineMarks);
    }
    lineMarks.addAll(unders);
    underlined = ranges(decorated, lines, Spans.UNDERLINE);
    squiggled = ranges(decorated, lines, Spans.SQUIGGLE);
    return List.copyOf(lineMarks);
  }

  /**
   * Appends up to four thin border rectangles around {@link #outlineFrom}..{@link #outlineTo} in
   * {@link #outlineColor}: horizontally from the smallest start column of the range's laid-out
   * non-blank rows to after its widest, and vertically from its first visible line's top to its
   * last visible line's bottom.
   */
  private void addOutlineMarks(List<Row> placed, Lines lines, float thick, List<Mark> out) {
    int a = Math.clamp(outlineFrom, 0, lines.length());
    int b = Math.clamp(outlineTo, a, lines.length());
    if (b <= a || wraps) {
      return;
    }
    int firstLine = lines.line(a);
    int lastLine = lines.line(b - 1);
    int vFirst = lines.visibleAtOrAfter(firstLine);
    int vLast = lines.visibleAtOrBefore(lastLine);
    if (vFirst < 0 || vLast < 0 || vFirst > vLast) {
      return;
    }
    double lh = lineHeight;
    double top = lines.lineTop(vFirst, lh);
    double bottom = lines.slotTop(vLast + 1, lh);
    if (bottom - viewY <= 0 || top - viewY >= placedHeight) {
      return;
    }
    // shortcut: lines of the block never shaped, never in view, do not widen it yet; it grows as
    // they come into view, as the width of the text does (Lines#widest).
    Map<Integer, Lines.Extent> extents =
        lines.outlineExtents(highlighter == null ? null : Optional.ofNullable(highlightKey));
    for (Row row : placed) {
      if (row.line < vFirst || row.line > vLast) {
        continue;
      }
      String text = row.para.text;
      int from = outlinePartFrom(lines, row.line, firstLine, a);
      int to = outlinePartTo(lines, row.line, lastLine, b);
      int segFrom = from;
      int segTo = to < 0 ? text.length() : to;
      while (segFrom < segTo && isOutlineBlank(text.charAt(segFrom))) {
        segFrom++;
      }
      while (segTo > segFrom && isOutlineBlank(text.charAt(segTo - 1))) {
        segTo--;
      }
      if (segFrom < segTo) {
        // A long line's chunks are not shaped here: that would move glyphs already placed.
        // An estimate does not replace what was measured of the same part of the line.
        Para para = row.para;
        Lines.Extent kept = extents.get(row.line);
        if (para.chunks == null) {
          float left = para.pen(0);
          extents.put(
              row.line,
              new Lines.Extent(from, to, para.pen(segFrom) - left, para.pen(segTo) - left));
        } else if (para.chunks.shapedAt(segFrom) && para.chunks.shapedAt(segTo)
            || kept == null
            || kept.from() != from
            || kept.to() != to) {
          extents.put(
              row.line,
              new Lines.Extent(
                  from, to, para.chunks.penEstimate(segFrom), para.chunks.penEstimate(segTo)));
        }
      } else {
        extents.remove(row.line);
      }
    }
    float minX = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    // Whichever is fewer: the lines of the block, or those measured.
    Iterable<Integer> measured = vLast - vFirst < extents.size()
        ? IntStream.rangeClosed(vFirst, vLast).boxed()::iterator
        : extents.keySet();
    for (int line : measured) {
      Lines.Extent extent = extents.get(line);
      if (extent != null
          && line >= vFirst
          && line <= vLast
          && !lines.isHidden(line)
          && extent.from() == outlinePartFrom(lines, line, firstLine, a)
          && extent.to() == outlinePartTo(lines, line, lastLine, b)) {
        minX = Math.min(minX, extent.left());
        maxX = Math.max(maxX, extent.right());
      }
    }
    if (maxX <= minX) {
      return;
    }
    float x0 = minX > 0 ? Math.round(minX - viewX) - thick : Math.round(minX - viewX);
    float x1 = Math.round(maxX - viewX) + thick;
    long y0 = Math.round(top - viewY);
    long y1 = Math.round(bottom - viewY);
    if (x1 <= 0 || x0 >= placedWidth || y1 <= y0) {
      return;
    }
    float visX0 = Math.max(x0, 0);
    float visX1 = Math.min(x1, placedWidth);
    if (visX1 > visX0) {
      if (y0 + thick > 0 && y0 < placedHeight) {
        out.add(new Mark(new Box(visX0, y0, visX1 - visX0, thick), outlineColor));
      }
      if (y1 > 0 && y1 - thick < placedHeight) {
        out.add(new Mark(new Box(visX0, y1 - thick, visX1 - visX0, thick), outlineColor));
      }
    }
    double innerTop = Math.max(y0 + thick, 0.0);
    double innerBottom = Math.min(y1 - thick, placedHeight);
    if (innerBottom > innerTop) {
      float iy = (float) innerTop;
      float ih = (float) (innerBottom - innerTop);
      if (x0 + thick > 0 && x0 < placedWidth) {
        out.add(new Mark(new Box(x0, iy, thick, ih), outlineColor));
      }
      if (x1 > 0 && x1 - thick < placedWidth) {
        out.add(new Mark(new Box(x1 - thick, iy, thick, ih), outlineColor));
      }
    }
  }

  /** Where the outline's part of {@code line} starts in it. */
  private static int outlinePartFrom(Lines lines, int line, int firstLine, int from) {
    return line == firstLine ? from - lines.start(line) : 0;
  }

  /** Where the outline's part of {@code line} ends in it, or -1 at the line's end. */
  private static int outlinePartTo(Lines lines, int line, int lastLine, int to) {
    return line == lastLine && to < lines.end(line) ? to - lines.start(line) : -1;
  }

  private static boolean isOutlineBlank(char c) {
    return c == ' ' || c == '\t';
  }

  /**
   * Where squiggle {@code i} of {@code spans} lies from {@code a} to {@code b} under no later one:
   * none after it in {@code spans}, none in {@code next}, the decorator's after a highlighter's.
   */
  private static List<int[]> uncovered(Spans spans, int i, int a, int b, Spans next) {
    List<int[]> pieces = cut(List.of(new int[] {a, b}), spans, i + 1);
    return next == null ? pieces : cut(pieces, next, 0);
  }

  /** {@code pieces} without the squiggles of {@code later} from {@code first} on. */
  private static List<int[]> cut(List<int[]> pieces, Spans later, int first) {
    for (int j = first; j < later.count && !pieces.isEmpty(); j++) {
      int from = later.froms[j];
      int to = later.tos[j];
      if (later.kinds[j] != Spans.SQUIGGLE
          || to <= pieces.getFirst()[0]
          || from >= pieces.getLast()[1]) {
        continue;
      }
      List<int[]> left = new ArrayList<>();
      for (int[] piece : pieces) {
        if (to <= piece[0] || from >= piece[1]) {
          left.add(piece);
          continue;
        }
        if (from > piece[0]) {
          left.add(new int[] {piece[0], from});
        }
        if (to < piece[1]) {
          left.add(new int[] {to, piece[1]});
        }
      }
      pieces = left;
    }
    return pieces;
  }

  /**
   * The ranges of {@code kind} in the decorated lines, in order, those that touch across a line
   * feed joined.
   */
  private static int[] ranges(Map<Integer, Spans> decorated, Lines lines, int kind) {
    List<int[]> all = new ArrayList<>();
    decorated.forEach((line, spans) -> {
      for (int i = 0; i < spans.count; i++) {
        if (spans.kinds[i] == kind) {
          int start = lines.start(line);
          all.add(new int[] {start + spans.froms[i], start + spans.tos[i]});
        }
      }
    });
    all.sort(Comparator.comparingInt(range -> range[0]));
    int[] out = new int[all.size() * 2];
    int n = 0;
    for (int[] range : all) {
      int gap = range[0] - (n > 0 ? out[n - 1] : 0);
      // Joined where they touch, or where only the line feed before range's line is between.
      if (n > 0 && (gap <= 0 || gap == 1 && lines.start(lines.line(range[0])) == range[0])) {
        out[n - 1] = Math.max(out[n - 1], range[1]);
      } else {
        out[n++] = range[0];
        out[n++] = range[1];
      }
    }
    return Arrays.copyOf(out, n);
  }

  /**
   * For the dump: what the {@link El#decorate decorator} underlines in the lines laid out, as
   * pairs of positions of the string, from and to.
   */
  int[] underlined() {
    return underlined;
  }

  /**
   * For the dump: what the {@link El#decorate decorator} squiggles in the lines laid out, as pairs
   * of positions of the string, from and to.
   */
  int[] squiggled() {
    return squiggled;
  }

  /** The lines whose first row (or replaced block) is laid out, with where it is, top to bottom. */
  List<Start> lineStarts() {
    lines();
    List<Start> starts = new ArrayList<>();
    for (Row row : rows) {
      if (row.row == 0) {
        starts.add(new Start(row.line, row.line, (float) (row.top - viewY), lineHeight));
      }
    }
    if (!placedBlocks.isEmpty()) {
      Lines ls = shownLines();
      boolean added = false;
      for (PlacedBlock pb : placedBlocks) {
        if (pb.block.placement() == El.Block.Placement.REPLACE) {
          int last = Math.min(pb.block.last(), ls.count() - 1);
          starts.add(new Start(pb.block.first(), last, (float) (pb.top - viewY), (float)
              Math.min(lineHeight, pb.height)));
          added = true;
        }
      }
      if (added) {
        starts.sort(Comparator.comparingInt(Start::line));
      }
    }
    return starts;
  }

  /**
   * The shown lines whose last row is in view, with their visible fold (or null), where that row
   * ends and its top, top to bottom. When {@code foldsOnly} is true, only lines that start a
   * visible fold are returned.
   */
  List<LineEnd> lineEnds(boolean foldsOnly) {
    lines();
    Lines lines = shownLines();
    if (placeholderShown || rows.isEmpty() || (foldsOnly && !lines.hasFolds())) {
      return List.of();
    }
    List<LineEnd> ends = new ArrayList<>();
    for (Row row : rows) {
      if (row.row + 1 != row.para.rowCount()) {
        continue;
      }
      El.Fold fold = lines.hasFolds() ? lines.foldAt(row.line) : null;
      if (foldsOnly && fold == null) {
        continue;
      }
      double top = row.top - viewY;
      if (top + lineHeight <= 0 || top >= placedHeight) {
        continue;
      }
      Para para = row.para;
      float endX = (para.chunks != null
              ? para.totalWidth()
              : para.pen(para.text.length())
                  + para.afterGap(para.text.length())
                  - para.pen(para.rowStart(row.row)))
          - viewX;
      ends.add(new LineEnd(row.line, fold, endX, (float) top));
    }
    return ends;
  }

  /**
   * For {@link El}: the inline hints on rows currently in view, with their physical x and row top
   * relative to the top left of the content box after scroll.
   */
  List<PlacedHint> placedHints() {
    lines();
    if (!hasHints() || rows.isEmpty()) {
      return List.of();
    }
    List<PlacedHint> out = new ArrayList<>();
    for (Row row : rows) {
      double top = row.top - viewY;
      if (top + lineHeight <= 0 || top >= placedHeight) {
        continue;
      }
      Para para = row.para;
      if (para.chunks != null) {
        para.chunks.emitHints((float) top, viewX, placedWidth, out);
        continue;
      }
      if (para.hints == null) {
        continue;
      }
      int rowStart = para.rowStart(row.row);
      int rowEnd = para.rowEnd(row.row);
      float left = para.pen(rowStart);
      for (HintSlot slot : para.hints.slots) {
        int pos = slot.resolvedAt;
        boolean onRow;
        if (pos > rowStart && pos < rowEnd) {
          onRow = true;
        } else if (pos == rowStart) {
          onRow = !slot.isBefore || (row.row == 0 && para.text.isEmpty());
        } else if (pos == rowEnd) {
          onRow = slot.isBefore;
        } else {
          onRow = false;
        }
        if (!onRow) {
          continue;
        }
        float hx = para.pen(pos) + slot.relX - left - viewX;
        if (hx + slot.width <= 0 || hx >= placedWidth) {
          continue;
        }
        out.add(new PlacedHint(slot.hint, slot.ordinal, slot.element, hx, (float) top));
      }
    }
    return out;
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
    if (line < 0) {
      return new int[] {-1, 0};
    }
    Para para = para(line);
    long within = row - lines.rowsBefore(line);
    if (within < 0) { // measuring the line made the rows before it fewer
      within = 0;
    }
    return new int[] {line, (int) Math.min(within, (long) para.rowCount() - 1)};
  }

  /** The row a position of the shown string is on, as {line, row}; at a wrap, the later one. */
  private int[] rowOf(int index) {
    Lines lines = shownLines();
    int clamped = Math.clamp(index, 0, lines.length());
    int line = lines.line(clamped);
    if (lines.isHidden(line)) {
      int visible = lines.visibleAtOrBefore(line);
      if (visible >= 0) {
        return new int[] {visible, para(visible).rowCount() - 1};
      }
      int after = lines.visibleAtOrAfter(line);
      if (after >= 0) {
        return new int[] {after, 0};
      }
      return new int[] {-1, 0};
    }
    return new int[] {line, para(line).rowAt(clamped - lines.start(line))};
  }

  /** The positions a caret can take on a row: a wrapped row ends before its last grapheme. */
  private static List<Integer> rowBounds(Para para, int row) {
    List<Integer> bounds = para.boundaries(para.rowStart(row), para.rowEnd(row));
    if (row + 1 < para.rowCount() && bounds.size() > 1) {
      bounds.removeLast(); // that position starts the next row
    }
    return bounds;
  }

  /**
   * Width of BEFORE inline hints at {@code index}, in physical pixels; 0 for none, and 0 where
   * {@code index} starts a wrapped row: such hints end the row before, and the caret at {@code
   * index} shows on its own row, so it has no stop left of them.
   */
  float beforeGap(int index) {
    if (!hasHints()) {
      return 0;
    }
    Lines lines = shownLines();
    int clamped = Math.clamp(index, 0, lines.length());
    int line = lines.line(clamped);
    if (lines.isHidden(line)) {
      return 0;
    }
    Para para = para(line);
    int offset = clamped - lines.start(line);
    int row = para.rowAt(offset);
    return row > 0 && offset == para.rowStart(row) ? 0 : para.beforeGap(offset);
  }

  /** Width of AFTER inline hints at {@code index}, in physical pixels; 0 for none. */
  float afterGap(int index) {
    if (!hasHints()) {
      return 0;
    }
    Lines lines = shownLines();
    int clamped = Math.clamp(index, 0, lines.length());
    int line = lines.line(clamped);
    if (lines.isHidden(line)) {
      return 0;
    }
    return para(line).afterGap(clamped - lines.start(line));
  }

  private float caretX(int index, HintStop stop) {
    Lines lines = shownLines();
    int clamped = Math.clamp(index, 0, lines.length());
    int line = lines.line(clamped);
    if (lines.isHidden(line)) {
      int visible = lines.visibleAtOrBefore(line);
      if (visible >= 0) {
        Para para = para(visible);
        int last = para.rowCount() - 1;
        return para.pen(para.text.length()) - para.pen(para.rowStart(last));
      }
      return 0;
    }
    int[] at = rowOf(clamped);
    if (at[0] < 0) {
      return 0;
    }
    Para para = para(at[0]);
    int offset = clamped - lines.start(at[0]);
    if (stop == HintStop.LEFT
        && para.hasHints()
        && !(at[1] > 0 && offset == para.rowStart(at[1]))) {
      float before = para.beforeGap(offset);
      if (before > 0) {
        return para.pen(offset) - before - para.pen(para.rowStart(at[1]));
      }
    } else if (stop == HintStop.RIGHT && para.hasHints()) {
      float after = para.afterGap(offset);
      if (after > 0) {
        return para.pen(offset) + after - para.pen(para.rowStart(at[1]));
      }
    }
    return para.pen(offset) - para.pen(para.rowStart(at[1]));
  }

  /** The top of the row a position is on, in physical pixels from the top of the text. */
  double caretTop(int index) {
    Lines lines = shownLines();
    int[] at = rowOf(index);
    if (at[0] < 0) {
      int line = lines.line(Math.clamp(index, 0, lines.length()));
      return lines.slotTop(line, lineHeight);
    }
    return lines.lineTop(at[0], lineHeight) + at[1] * (double) lineHeight;
  }

  /**
   * The vertical span {@code {top, bottom, firstBottom}} in logical pixels from the top of the text
   * for characters {@code [from, to]} of the shown string, accounting for folds and line blocks
   * without shaping unshaped lines.
   *
   * @return false while the field shows its placeholder
   */
  boolean markSpan(int from, int to, double[] out) {
    Lines lines = shownLines();
    if (placeholderShown) {
      return false;
    }
    int a = Math.clamp(from, 0, lines.length());
    int b = Math.clamp(to, a, lines.length());
    int first = lines.line(a);
    int last = lines.line(b > a ? b - 1 : a);
    lines.lineSpan(first, lineHeight, out);
    double top = out[0];
    double firstBottom = out[1];
    double bottom = firstBottom;
    if (last != first) {
      lines.lineSpan(last, lineHeight, out);
      bottom = Math.max(bottom, out[1]);
    }
    out[0] = top / scale;
    out[1] = bottom / scale;
    out[2] = firstBottom / scale;
    return true;
  }

  /** The position nearest to {@code x} on row {@code row} of line {@code line}. */
  private int nearestOnRow(int line, int row, float x) {
    return nearestCaretOnRow(line, row, x, false).index();
  }

  /**
   * The position nearest to {@code x} on a row, with its stop; among the default stops only unless
   * {@code stops}, for moves that land on the default stop.
   */
  private CaretPos nearestCaretOnRow(int line, int row, float x, boolean stops) {
    Para para = para(line);
    int lineStart = shownLines().start(line);
    if (para.chunks != null) {
      CaretPos local = para.chunks.nearestCaret(x, stops);
      return new CaretPos(lineStart + local.index(), local.stop());
    }
    int rowStart = para.rowStart(row);
    int rowEnd = para.rowEnd(row);
    float left = para.pen(rowStart);
    int best = rowStart;
    HintStop bestStop = HintStop.DEFAULT;
    float distance = Float.POSITIVE_INFINITY;
    List<Integer> bounds = rowBounds(para, row);
    boolean wrapped = row + 1 < para.rowCount();
    for (int b : bounds) {
      float cx = para.pen(b) - left;
      if (stops && para.hints != null && !(row > 0 && b == rowStart)) {
        float before = para.beforeGap(b);
        if (before > 0) {
          float d = Math.abs(cx - before - x);
          if (d < distance) {
            distance = d;
            best = b;
            bestStop = HintStop.LEFT;
          }
        }
      }
      float d = Math.abs(cx - x);
      if (d < distance) {
        distance = d;
        best = b;
        bestStop = HintStop.DEFAULT;
      }
      if (stops && para.hints != null && !(wrapped && b == rowEnd)) {
        float after = para.afterGap(b);
        if (after > 0) {
          float da = Math.abs(cx + after - x);
          if (da < distance) {
            distance = da;
            best = b;
            bestStop = HintStop.RIGHT;
          }
        }
      }
    }
    return new CaretPos(lineStart + best, bestStop);
  }

  /**
   * The position {@code rows} visual rows above (negative) or below a position, nearest to {@code
   * x} on its row; before the first row the start of the first visible line, after the last its
   * end.
   */
  int moveRows(int index, int rows, float x) {
    int[] at = rowOf(index);
    int line = at[0];
    if (line < 0) {
      return index;
    }
    int row = at[1];
    Lines lines = shownLines();
    for (int step = rows; step != 0; step -= Integer.signum(step)) {
      if (step > 0) {
        if (row + 1 < para(line).rowCount()) {
          row++;
        } else {
          int next = lines.nextVisible(line);
          if (next >= 0) {
            line = next;
            row = 0;
          } else {
            return lines.end(line);
          }
        }
      } else if (row > 0) {
        row--;
      } else {
        int prev = lines.prevVisible(line);
        if (prev >= 0) {
          line = prev;
          row = para(line).rowCount() - 1;
        } else {
          return lines.start(line);
        }
      }
    }
    return nearestOnRow(line, row, x);
  }

  /**
   * PageUp/PageDown in a field with blocks: moves by one page of visual height ({@link
   * #visibleRows} lines) to the visible row nearest {@code x}, stepping over any block in the way.
   */
  int movePage(int index, int direction, float x) {
    Lines lines = shownLines();
    int[] at = rowOf(index);
    if (at[0] < 0) {
      return index;
    }
    double currentY = caretTop(index);
    double targetY = currentY + direction * visibleRows() * (double) lineHeight;
    int slot = lines.slotAt(targetY, lineHeight);
    int targetLine;
    int targetRow;
    if (inBlockBand(lines, slot, targetY)) {
      if (direction > 0) {
        targetLine = slot < lines.count() && !lines.isHidden(slot)
            ? slot
            : slot < lines.count() ? lines.nextVisible(slot) : -1;
        if (targetLine < 0) {
          int last = lines.visibleAtOrBefore(lines.count() - 1);
          return last >= 0 ? lines.end(last) : index;
        }
        targetRow = 0;
      } else {
        // the line before the band: a band above a line is not on that line
        targetLine = slot > 0 ? lines.visibleAtOrBefore(Math.min(slot, lines.count()) - 1) : -1;
        if (targetLine < 0) {
          int first = lines.firstVisible();
          return first >= 0 ? lines.start(first) : index;
        }
        targetRow = para(targetLine).rowCount() - 1;
      }
    } else {
      targetLine = slot;
      targetRow = rowInLine(lines, slot, targetY);
    }
    if (targetLine == at[0] && targetRow == at[1]) {
      return moveRows(index, direction, x);
    }
    return nearestOnRow(targetLine, targetRow, x);
  }

  /** The start of the row a position is on. */
  int rowStart(int index) {
    int[] at = rowOf(index);
    return at[0] < 0 ? 0 : shownLines().start(at[0]) + para(at[0]).rowStart(at[1]);
  }

  /** The last position a caret takes on the row of a position: before the wrap, or the line end. */
  int rowEnd(int index) {
    int[] at = rowOf(index);
    if (at[0] < 0) {
      return 0;
    }
    Para para = para(at[0]);
    if (at[1] + 1 == para.rowCount()) {
      return shownLines().start(at[0]) + para.text.length();
    }
    List<Integer> bounds = rowBounds(para, at[1]);
    return shownLines().start(at[0]) + bounds.getLast();
  }

  /** Whether a position starts the first row of its line, which a wrapped line is not after. */
  boolean startsLine(int index) {
    int[] at = rowOf(index);
    return at[0] < 0 || at[1] == 0;
  }

  /** The rows in view and the margin that cover the content box, for the editor's look. */
  int visibleRows() {
    return Math.max(1, (int) Math.floor(placedHeight / lineHeight()));
  }

  /**
   * Selection tint boxes over laid-out line blocks covered by {@code [start, end)}, in physical
   * pixels relative to the top left of the content box after vertical scroll.
   */
  List<Box> blockSelectionBoxes(int start, int end) {
    lines();
    if (start == end || placedBlocks.isEmpty()) {
      return List.of();
    }
    Lines lines = shownLines();
    List<Box> boxes = new ArrayList<>();
    for (PlacedBlock pb : placedBlocks) {
      if (pb.height <= 0) {
        continue;
      }
      El.Block b = pb.block;
      boolean covered;
      if (b.placement() == El.Block.Placement.REPLACE) {
        int from = lines.start(b.first());
        int last = Math.min(b.last(), lines.count() - 1);
        int to = last + 1 < lines.count() ? lines.start(last + 1) : lines.length();
        covered = from < to ? start < to && end > from : start < from && end >= from;
      } else {
        int line = b.first();
        covered = line > 0
            && line < lines.count()
            && start < lines.start(line)
            && end >= lines.start(line);
      }
      if (covered) {
        boxes.add(new Box(0, (float) (pb.top - viewY), placedWidth, (float) pb.height));
      }
    }
    return List.copyOf(boxes);
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
      int localStart = Math.max(start, from) - lineStart;
      float x0 = para.pen(localStart) + (start >= from ? para.afterGap(localStart) : 0) - left;
      float x1;
      if (endSelected && !(!lastRow && end == to && para.beforeGap(to - lineStart) > 0)) {
        x1 = Math.max(
            edge,
            (para.chunks != null
                    ? para.totalWidth()
                    : para.pen(to - lineStart) + (lastRow ? para.afterGap(to - lineStart) : 0))
                - left);
      } else {
        int localEnd = Math.min(end, to) - lineStart;
        x1 = para.pen(localEnd) - para.beforeGap(localEnd) - left;
      }
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
