package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Predicate;

/**
 * One element of the view tree: a box with a style, a look and children. Views build a fresh tree
 * with chained calls:
 *
 * <pre>{@code
 * col().center().gap(16).fill(Colors.rgb(0x101014))
 *     .child(el().key("switch").size(38, 22).radius(11).fill(Colors.rgb(0x3B82F6)));
 * }</pre>
 */
public final class El {

  /** How many rows a {@link #rows virtual list} builds beyond its visible part, on each side. */
  public static final int OVERSCAN = 3;

  /** What {@link #wantedRows} gives when no rows are wanted. */
  static final int[] NO_ROWS = {};

  static final int SELECTED = 1;
  static final int DISABLED = 2;
  static final int CHECKED = 4; // with NOT_CHECKED: mixed
  static final int NOT_CHECKED = 8;
  static final int EXPANDED = 16;
  static final int COLLAPSED = 32;
  static final int ASCENDING = 64;
  static final int DESCENDING = 128;
  static final int CURRENT = 256;
  static final int INVALID = 512;
  static final int GRABBED = 1024;
  static final int BUSY = 2048;
  static final int MODIFIED = 4096;

  private final Style style = new Style();
  private final List<El> children = new ArrayList<>();
  private String key;
  private String role;
  private String label;
  int states; // the bits below, set by the view; see #dump
  int level;
  int position; // in its set, from 1; 0 when not set
  int setSize;
  private String value;
  private String errorMessage;
  private String warningMessage;
  private int fill;
  private float radius; // the top left corner's
  private float radiusTopRight;
  private float radiusBottomRight;
  private float radiusBottomLeft;
  private int borderColor;
  private int[] borderColors; // null while every side has borderColor
  private List<Shadow> shadows = List.of();
  private Background background;
  private List<Segment> segments = List.of();
  private Measure measure;
  private Text text;
  private float lineHeight; // as a multiple of the font size, 0 when unset; see #lineHeight
  float resolvedLineHeight; // this element's or inherited, set by scaleText for rows and gutter
  private Image image;
  private Image.Fit fit = Image.Fit.FILL;
  private int revealRow = -1;
  private boolean focusable;
  private Integer focusRing;
  private float focusRingGap;
  private ScrollState scrollState;
  // Sent to its vertical end until its last rows are measured, as ScrollState.toEnd without one.
  private boolean toEnd;
  boolean focused;
  private Spring motion;
  private float opacity = 1;
  private float targetOpacity = 1; // as the view set it, which motion springs towards
  private float arcStart;
  private float arcSweep = 1;
  private boolean exiting;
  private boolean moving;
  private WindowPart windowPart;
  private Consumer<El> hoverLook;
  private Consumer<El> focusLook;
  private Object autofocus; // see #autofocus(Object)
  private boolean takesFocus; // see #takeFocus
  private Consumer<El> focusWithinLook; // see #whenFocusWithin
  private Consumer<El> pressLook;
  private Cursor cursor;
  boolean hovered;
  private boolean scrollArea;
  private Scrollbars scrollbars;
  // The bars as the last frame shows them, in window coordinates; null where there is none.
  private Scrollbars.Bar barX;
  private Scrollbars.Bar barY;
  private boolean placed;
  private float x;
  private float y;
  private float width;
  private float height;
  // The edges layout resolved, in logical pixels: 4 padding, 4 border, 4 margin by Side.ordinal().
  private float[] edges;
  // The scroll offset of this element, how far it may go, and how far its children are moved by
  // it. The offset is a double so that a virtual list can be billions of pixels tall; the shift is
  // small, because rows are placed near the offset.
  private double scrollX;
  private double scrollY;
  private double maxScrollX;
  private double maxScrollY;
  // How far the offsets reach past the content, for bars over its end and a selection scope's
  // coveredTop over its start; see measureScrolls.
  private float coverX;
  private float coverY;
  private float coverTop;
  private float shiftX;
  private float shiftY;
  private Rows rows; // the state of a virtual list, null unless rows(...) made this element one
  private Field field; // the state of a text field, null unless editable(...) made this one
  private Handlers handlers; // null until a handler is set
  // A row: whether the last makeRows of its list kept it as it was, and for rows of one height,
  // where layout put its top in the list's content.
  private boolean kept;
  private double contentTop;
  private boolean fixedX;
  private El across; // the nearest scroll area around that scrolls sideways; see #fix
  private Object revealKey; // see #reveal
  private float revealX;
  private float revealY;
  private float revealWidth;
  private float revealHeight;
  private List<El> overlays; // null for none; apart from the children
  private Layer layer; // where this element goes as an overlay, null until a call says
  private List<El> shownOverlays = List.of(); // of a root: those placed above it, bottom first
  private Consumer<El> restLook;
  private double restDelay;
  private Object dismissGroup; // see #dismissGroup
  private boolean keepsFocus; // see #keepFocus
  private boolean inert; // see #inert
  private boolean holdsFocus; // see #holdFocus
  private boolean selectionScope; // see #selectionScope
  private Decorator scopeDecorator; // see #decorate on a selection scope
  private float scopeCoveredTop; // see #coveredTop on a selection scope
  private Consumer<El> focusRestLook; // see #whenFocusVisibleFor
  private double focusRestDelay;
  private boolean skipsTab; // see #skipTab
  private boolean defaultFocus; // see #defaultFocus
  private ObjDoubleConsumer<El> cycle; // see #cycle
  private double cycleSeconds;
  double cycleSince; // for Animator: the app's time at which this identity got its cycle
  // What layout and motion gave this element in this frame, which the cycle's look starts from.
  private boolean cycleBased;
  private float baseX;
  private float baseY;
  private float baseWidth;
  private float baseHeight;
  private float baseOpacity;
  private float baseTargetOpacity;
  private int baseFill;
  private int rangeFrom = -1; // see #range: characters of the parent's text, -1 for none
  private int rangeTo;
  private List<Text.Box> rangeBoxes; // the rows of the range, from this element's corner
  private FieldPart fieldPartKey; // non-null on a field's post-layout part; see Animator
  private boolean overflowsBlock; // a block its builder did not clip: wider children show

  /** Which kind of post-layout child of a multi-line field an element is. */
  enum PartKind {
    RAIL,
    GUTTER,
    FOLD,
    LINE_END,
    BLOCK,
    HINT
  }

  /** Identity part of a field's post-layout child, stable across rebuilds and scrolling. */
  record FieldPart(PartKind kind, Object key) {}

  private static final FieldPart RAIL_PART = new FieldPart(PartKind.RAIL, "rail");
  private static final FieldPart GUTTER_PART = new FieldPart(PartKind.GUTTER, "gutter");

  /** Told a position in logical pixels of the window, as {@link #onSecondaryPress} is. */
  @FunctionalInterface
  public interface PointHandler {
    void accept(float x, float y);
  }

  /**
   * Told how a press came about: the number of presses in quick succession, 1 for a single one,
   * and the modifiers held, a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link
   * Key#ALT} and {@link Key#META}.
   */
  @FunctionalInterface
  public interface PressHandler {
    void press(int clicks, int modifiers);
  }

  /**
   * Told how a press came about, as {@link PressHandler} is, and where: {@code x} and {@code y}
   * are where the pointer was in the element's content, in logical pixels from the left and the
   * top of its border box as the last frame shows it, plus its own scroll offset, as {@link
   * Drag#x} and {@link Drag#y} are; a slider puts its knob there, a color field reads its color
   * there. Enter and Space give the middle of the border box.
   */
  @FunctionalInterface
  public interface PressAtHandler {
    void press(int clicks, int modifiers, double x, double y);
  }

  /**
   * Told how the wheel turned over an element, as {@link #onWheel} is: by {@code deltaX} and {@code
   * deltaY} in logical pixels, positive towards the end as {@link App#wheel(float, float, float,
   * float, int)} counts them, with these modifiers held, a combination of {@link Key#SHIFT}, {@link
   * Key#CONTROL}, {@link Key#ALT} and {@link Key#META}, and the pointer at {@code x, y} in the
   * element's content, as {@link PressAtHandler} is told. In a window, a mouse's wheel that turns
   * up and down with Shift held comes sideways, in {@code deltaX}, as it scrolls; a script and
   * {@link App#wheel} give the deltas as they are.
   */
  @FunctionalInterface
  public interface WheelHandler {
    /** @return whether it took the wheel; if not, the wheel goes on as without this handler */
    boolean wheel(double deltaX, double deltaY, int modifiers, double x, double y);
  }

  /**
   * Builds a row of a {@link #rows(int, float, RowBuilder) virtual list} that knows which part of
   * it is in view sideways.
   */
  @FunctionalInterface
  public interface RowBuilder {
    /**
     * @param index the row's index, from 0
     * @param from where the part of the row to build begins, in logical pixels from the left of
     *     the list's content box: the part in view and half as much again on each side
     * @param to where that part ends
     */
    El row(int index, float from, float to);
  }

  /**
   * Builds the cell of a line in the {@link #gutter} of a multi-line field: its number, a marker,
   * a fold arrow.
   */
  @FunctionalInterface
  public interface Gutter {
    El cell(Line line);

    /**
     * A line of the field, as its cell is built for it.
     *
     * @param index the line's index, from 0; its number is one more
     * @param start where the line starts in the field's string, as the positions of {@link
     *     Decorator#line} count
     * @param end where it ends, before its line feed
     * @param caret whether the caret is on this line
     */
    record Line(int index, int start, int end, boolean caret) {}
  }

  /**
   * A folded range of lines in a {@link #multiline} field ({@link #folds}): line {@code first}
   * stays shown with a placeholder mark at its end, and lines {@code first + 1} through {@code
   * last} are hidden.
   *
   * @param first the shown first line, from 0
   * @param last the last hidden line, strictly greater than {@code first}
   */
  public record Fold(int first, int last) {
    public Fold {
      if (first < 0 || last <= first) {
        throw new IllegalArgumentException(
            "fold lines must have 0 <= first < last, were " + first + " and " + last);
      }
    }
  }

  /**
   * A block between lines in a {@link #multiline} field ({@link #blocks}): either {@link
   * Placement#INSERT inserted} before line {@code first} without hiding text (where {@code first}
   * equal to the line count places the block after the last line), or {@link Placement#REPLACE
   * replacing} lines {@code first} through {@code last} inclusive, hiding every line of that range
   * including {@code first}.
   *
   * <p>The block's height comes from its element laid out at the field's content width, unless
   * {@link #lines(int)} fixes it to an exact number of text lines.
   *
   * @param placement whether the block inserts space before a line or replaces a whole-line range
   * @param first the line index, from 0 (up to the line count for {@link Placement#INSERT})
   * @param last equal to {@code first} for {@link Placement#INSERT}; the last hidden line, at least
   *     {@code first}, for {@link Placement#REPLACE}
   * @param lines exact height in text lines, or 0 to measure the block's element
   */
  public record Block(Placement placement, int first, int last, int lines) {

    /** How a {@link Block} relates to the lines of the field's string. */
    public enum Placement {
      /** Inserted before {@link #first}, hiding no lines. */
      INSERT,
      /** Replaces lines {@link #first} through {@link #last} inclusive, hiding them all. */
      REPLACE
    }

    public Block {
      Objects.requireNonNull(placement, "placement");
      if (first < 0) {
        throw new IllegalArgumentException("block first line must be 0 or more, was " + first);
      }
      if (placement == Placement.INSERT && last != first) {
        throw new IllegalArgumentException(
            "insert block must have last == first, were " + first + " and " + last);
      }
      if (placement == Placement.REPLACE && last < first) {
        throw new IllegalArgumentException(
            "replace block must have 0 <= first <= last, were " + first + " and " + last);
      }
      if (lines < 0) {
        throw new IllegalArgumentException("block lines must be 0 or more, was " + lines);
      }
    }

    /** A block inserted before line {@code line} (or after the last line when equal to line count). */
    public static Block insert(int line) {
      return new Block(Placement.INSERT, line, line, 0);
    }

    /** A block replacing lines {@code first} through {@code last} inclusive. */
    public static Block replace(int first, int last) {
      return new Block(Placement.REPLACE, first, last, 0);
    }

    /** A copy of this block with an exact height of {@code lines} text lines, or 0 to measure. */
    public Block lines(int lines) {
      return new Block(placement, first, last, lines);
    }
  }

  /**
   * An inline hint placed inside a line of a {@link #multiline} field ({@link #hints}), such as a
   * parameter name before an argument or an inferred type after a variable name. Hints are not
   * part of the field's string: they reserve width during shaping and wrapping, the caret stops on
   * both sides of them, and copy ignores them.
   *
   * @param at UTF-16 offset into the field's string, 0 or more
   * @param affinity whether the hint belongs to the text before {@code at} ({@link
   *     Affinity#BEFORE}) or after it ({@link Affinity#AFTER})
   * @param text what the hint says, for the builder and the tree dump
   */
  public record Hint(int at, Affinity affinity, String text) {

    /** Which side of its offset a {@link Hint} belongs to. */
    public enum Affinity {
      /** Belongs to the text before {@link #at}, such as an inferred type after a name. */
      BEFORE,
      /** Belongs to the text after {@link #at}, such as a parameter name before an argument. */
      AFTER
    }

    public Hint {
      if (at < 0) {
        throw new IllegalArgumentException("hint offset must be 0 or more, was " + at);
      }
      Objects.requireNonNull(affinity, "affinity");
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * A pair of brackets that can fold ({@link #bracketFolds}): the lines it folds, from the line of
   * its opening bracket through the line before its closing one, and where the two brackets are
   * in the string as the field shows it.
   */
  public record BracketFold(Fold lines, int open, int close) {
    public BracketFold {
      Objects.requireNonNull(lines, "lines");
    }
  }

  /**
   * The pairs of brackets that can fold ({@link #bracketFolds}), sorted by their first line, with
   * the widest pair where several start on one line. When a highlighter's per-frame line budget
   * stops the scan early, {@code complete} is {@code false} and only the lines before {@code
   * scannedLines} are known; the next frame goes on from there.
   */
  public record BracketFolds(List<BracketFold> folds, int scannedLines, boolean complete) {
    public BracketFolds {
      folds = List.copyOf(Objects.requireNonNull(folds, "folds"));
    }
  }

  /**
   * Told the caret and the other end of the selection of a text field, as indexes into its string;
   * equal when nothing is selected.
   */
  @FunctionalInterface
  public interface SelectHandler {
    void select(int caret, int anchor);
  }

  /**
   * Told the character position under the pointer in a {@link #multiline} field and the modifiers
   * held ({@link #onTextPointerMove}, {@link #onTextHoverFor}): {@code position} is a UTF-16
   * offset into the field's string, or -1 when the pointer is not over text, and {@code modifiers}
   * is a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT} and {@link
   * Key#META}.
   */
  @FunctionalInterface
  public interface TextPointerHandler {
    void accept(int position, int modifiers);
  }

  /**
   * Told the first and the last row, both included, that a virtual list has built ({@link
   * #onRowsBuilt}) or has wholly in view ({@link #onRowsInView}).
   */
  @FunctionalInterface
  public interface RowsHandler {
    void accept(int first, int last);
  }

  /** Told the size of an element's border box as layout gave it, in logical pixels. */
  @FunctionalInterface
  public interface SizeHandler {
    void accept(float width, float height);
  }

  /** Where an overlay goes and how it takes input, and after placement what it is beside. */
  static final class Layer {
    Side side = Side.BOTTOM;
    Style.Align align = Style.Align.START;
    float gap;
    float pointX = Float.NaN;
    float pointY = Float.NaN;
    boolean centered;
    boolean atCaret; // see El#atCaret
    int rangeFrom = -1; // see El#atRange; -1 when not anchored to a range
    int rangeTo;
    boolean whenShortened; // see El#whenShortened
    Side edge; // see El#atWindowEdge; null for beside the anchor
    boolean modal;
    int scrim;
    Runnable onDismiss;
    Object id; // its identity, which Animator sets
    long opened; // when it first appeared, which Animator counts
    boolean flipped; // the last placement put it on the other side of its anchor
    boolean avoidsAnchor; // a delayed look attached it: it goes across rather than cover it
    El anchor; // as the last placement found it, in window coordinates
    float anchorX;
    float anchorY;
  }

  /** The state of a {@link #rows virtual list}: its rows, and the index of its first child. */
  private static final class Rows {
    int rowCount;
    float rowHeight;
    RowHeights heights; // rows as high as their content; null for rows of rowHeight
    int heightsVersion; // heights.version() when this element was built
    Object[] versions; // the rows' versions in heights when this element was built, or null
    RowBuilder rowBuilder;
    boolean spans; // the row function takes the part of the rows in view sideways
    float spanFrom; // the part rows are built for, in their coordinates
    float spanTo;
    float builtFrom; // the part the rows that exist were built for
    float builtTo;
    int firstRow;
    boolean rowsBuilt; // for this tree; a rebuild of the view starts without rows
    // What measureScrolls passed this list, relative to its border box, for rows built later.
    float barLeft = Float.POSITIVE_INFINITY;
    float barTop = Float.POSITIVE_INFINITY;
    float clipRight = Float.POSITIVE_INFINITY;
    float clipBottom = Float.POSITIVE_INFINITY;
    float coverBottom = Float.NEGATIVE_INFINITY;
  }

  /** The state of a text field, which {@link #editable} makes, beyond its {@link Text}. */
  private static final class Field {
    Gutter gutter;
    El gutterRail;
    El gutterColumn;
    FieldMenu fieldMenu;
    float gutterLeft = Float.NaN; // where the gutter starts, the padding before it
    float gutterWidth;
    float gutterScale; // the scale gutterWidth was measured at
    Object gutterShown; // what the cells were built for: their lines, tops and the caret
    List<Fold> folds = List.of();
    Consumer<Fold> onUnfold;
    Function<Fold, El> foldMark;
    IntFunction<List<El>> lineEnds;
    List<El> lineEndElements = List.of();
    Object lineEndsShown; // what the line-end elements were built for: their ends and scroll
    float foldsExtent; // farthest right edge of placed fold marks, in logical content coordinates
    List<Block> blocks = List.of();
    Function<Block, El> blockBuilder;
    Function<Block, El> blockGutter;
    // Laid out block gutter cells without looks, by block, ordinal, height and scale: scrolling
    // only moves them.
    Map<List<Object>, El> blockGutterCells = Map.of();
    Consumer<Block> onReveal;
    List<El> blockElements = List.of();
    Object blocksShown; // what the block elements were built for: their placed bands and scroll
    Map<BlockKey, El> blockCache;
    float blockCacheWidth = Float.NaN;
    float blockCacheScale = Float.NaN;
    List<Hint> hints = List.of();
    Function<Hint, El> hintBuilder;
    List<El> hintElements = List.of();
    Object hintsShown; // what the hint elements were built for: their placements and scroll
    Map<HintKey, El> hintCache;
    // The ordinal of the block or hint at its anchor that has each explicit key; see claimPartKey.
    final Map<FieldPart, Integer> partOrdinals = new HashMap<>();
    float hintCacheScale = Float.NaN;
    float hintCacheLineHeight = Float.NaN;
    boolean
        partsBuilt; // gutter cells, line ends, blocks or hints were built since App applied looks
    Map<El, Set<String>> partLooks; // the looks App applied to parts of this tree, by part
    boolean bracketFoldsPending; // bracketFolds asked before Lines was attached
    boolean readOnly;
    int maxLength = -1;
    int tabIndents; // 0: Tab moves the focus; 1: it indents with tabs; 2: with spaces
    boolean codeWords;
    FieldState fieldState;
    Editor editor;
    float coveredTop; // see El#coveredTop
    List<Scrollbars.Mark> scrollMarks; // null until El#scrollMarks adds marks
    List<Scrollbars.Tick> ticks = List.of(); // as the last frame placed them
    int[] tickTargets; // the shown position of each tick's first mark
  }

  /** The handlers of input and of what the app tells an element. */
  private static final class Handlers {
    private static final Handlers NONE = new Handlers(); // never written

    PressAtHandler onPress;
    PressAtHandler onPointerDown;
    Runnable onAnyPointerDown;
    Consumer<Key> onAnyKeyDown;
    Runnable onPressOutside;
    PointHandler onSecondaryPress;
    Consumer<Drag> onDrag;
    Object dragPayload;
    Consumer<Drop> onDrop;
    Consumer<Drop> onFileDrop;
    Predicate<Key> onKey;
    Predicate<Key> onKeyUp;
    WheelHandler onWheel;
    Runnable onUserScroll;
    Consumer<String> onText;
    Consumer<String> onTypeAhead;
    Runnable onFocusIn;
    Consumer<String> onEdit;
    SelectHandler onSelect;
    SizeHandler onResize;
    RowsHandler onRowsBuilt;
    RowsHandler onRowsInView;
    Runnable onHoverFor;
    double hoverDelay;
    Consumer<Boolean> onHover;
    TextPointerHandler onTextPointerMove;
    TextPointerHandler onTextHoverFor;
    double textHoverDelay;
    Runnable onShownFor; // see El#onShownFor
    double shownDelay;
    boolean shownPauseOnHover; // see El#pauseShownOnHover

    /**
     * The handlers {@link #sameInput} compares, copied from {@code handlers}, which may be null for
     * none, to compare with later.
     */
    static Handlers copy(Handlers handlers) {
      if (handlers == null) {
        return NONE;
      }
      Handlers copy = new Handlers();
      copy.onPress = handlers.onPress;
      copy.onPointerDown = handlers.onPointerDown;
      copy.onAnyPointerDown = handlers.onAnyPointerDown;
      copy.onAnyKeyDown = handlers.onAnyKeyDown;
      copy.onPressOutside = handlers.onPressOutside;
      copy.onDrag = handlers.onDrag;
      copy.onKey = handlers.onKey;
      copy.onKeyUp = handlers.onKeyUp;
      copy.onWheel = handlers.onWheel;
      copy.onUserScroll = handlers.onUserScroll;
      copy.onText = handlers.onText;
      copy.onTypeAhead = handlers.onTypeAhead;
      copy.onFocusIn = handlers.onFocusIn;
      copy.onEdit = handlers.onEdit;
      copy.onHover = handlers.onHover;
      copy.onTextPointerMove = handlers.onTextPointerMove;
      copy.onTextHoverFor = handlers.onTextHoverFor;
      return copy;
    }

    /**
     * Whether {@code after}, which may be null for none, has the handlers of input that {@code
     * before} has: those a look must leave alone.
     */
    boolean sameInput(Handlers after) {
      Handlers a = after == null ? NONE : after;
      return onPress == a.onPress
          && onPointerDown == a.onPointerDown
          && onAnyPointerDown == a.onAnyPointerDown
          && onAnyKeyDown == a.onAnyKeyDown
          && onPressOutside == a.onPressOutside
          && onDrag == a.onDrag
          && onKey == a.onKey
          && onKeyUp == a.onKeyUp
          && onWheel == a.onWheel
          && onUserScroll == a.onUserScroll
          && onText == a.onText
          && onTypeAhead == a.onTypeAhead
          && onFocusIn == a.onFocusIn
          && onEdit == a.onEdit
          && onHover == a.onHover
          && onTextPointerMove == a.onTextPointerMove
          && onTextHoverFor == a.onTextHoverFor;
    }
  }

  private El() {}

  private Rows rows() {
    if (rows == null) {
      rows = new Rows();
    }
    return rows;
  }

  private Handlers handlers() {
    if (handlers == null) {
      handlers = new Handlers();
    }
    return handlers;
  }

  /** A box whose children flow in a row, as in CSS. */
  public static El el() {
    return new El();
  }

  public static El row() {
    return new El();
  }

  public static El col() {
    El el = new El();
    el.style.direction = Style.Direction.COLUMN;
    return el;
  }

  /**
   * A text element: a leaf that layout sizes to its shaped text, in {@link Font#sans} at 14
   * logical pixels in black, on one line per line feed unless it {@link #wrapText wraps}. {@link
   * Text} says how lines break.
   */
  public static El text(String string) {
    El el = new El();
    el.text = new Text(string);
    el.measure = el.text;
    return el;
  }

  /**
   * An image element ({@link Image}): a leaf of role {@code img} that layout sizes to the image in
   * logical pixels, its size in pixels divided by its {@link Image#scale}, but no wider than the
   * width there is for it, as CSS's {@code max-width: 100%} for images: a large image in a narrow
   * column is as wide as the column and keeps its aspect ratio. A width or a height from the style
   * scales it, and with only one of them set the other follows the image's aspect ratio (the
   * style's {@link Style#aspectRatio}); with both, {@link #fit} says how it fills the box. The image fills the content box and is clipped to the element's rounded corners; the
   * element's fill, border, shadows, opacity and motion apply as to any element. Give it a {@link
   * #label}, the text that stands for the image, which the tree's dump shows.
   */
  public static El image(Image image) {
    El el = new El();
    el.image = Objects.requireNonNull(image, "image");
    float width = image.width() / image.scale();
    float height = image.height() / image.scale();
    el.style.aspectRatio = width / height;
    el.measure = (knownWidth, knownHeight, availableWidth, availableHeight) -> {
      if (!Float.isNaN(knownWidth) && !Float.isNaN(knownHeight)) {
        return new Measure.Size(knownWidth, knownHeight);
      }
      if (!Float.isNaN(knownWidth)) {
        return new Measure.Size(knownWidth, knownWidth * height / width);
      }
      if (!Float.isNaN(knownHeight)) {
        return new Measure.Size(knownHeight * width / height, knownHeight);
      }
      if (availableWidth >= 0 && availableWidth < width) { // a number, and less than the image
        return new Measure.Size(availableWidth, availableWidth * height / width);
      }
      return new Measure.Size(width, height);
    };
    return el;
  }

  /** How the {@link #image} fills the content box when both its width and height are set. */
  public El fit(Image.Fit fit) {
    this.fit = Objects.requireNonNull(fit, "fit");
    return this;
  }

  // Identity and accessibility

  /** Identifies this element among its siblings across rebuilds of the tree. */
  public El key(String key) {
    this.key = key;
    return this;
  }

  public El role(String role) {
    this.role = role;
    return this;
  }

  public El label(String label) {
    this.label = label;
    return this;
  }

  // States, as WAI-ARIA names them. They say what the app's state is, for the tree dump, scripts
  // and assistive technology; they change nothing about how the element looks or takes input.

  /** Whether this is selected, as the selected row of a grid, node of a tree or tab of a bar. */
  public El selected(boolean selected) {
    return state(SELECTED, selected ? SELECTED : 0);
  }

  /**
   * Whether this is the current item of its set, as WAI-ARIA's {@code aria-current}: the cell a
   * table's cursor is on, which keys move and a press puts there, among the selected ones.
   */
  public El current(boolean current) {
    return state(CURRENT, current ? CURRENT : 0);
  }

  /**
   * Whether this is busy, as WAI-ARIA's {@code aria-busy}: a button whose action runs, which takes
   * no press until it is done, or a part whose content is on its way.
   */
  public El busy(boolean busy) {
    return state(BUSY, busy ? BUSY : 0);
  }

  /**
   * Whether this is being dragged, as WAI-ARIA's {@code aria-grabbed}: a tab on its way to another
   * place in its bar or to another bar.
   */
  public El grabbed(boolean grabbed) {
    return state(GRABBED, grabbed ? GRABBED : 0);
  }

  /**
   * Whether what this element stands for has changes that are not saved, as a tab of a document
   * whose edits are not written yet (not a WAI-ARIA state).
   */
  public El modified(boolean modified) {
    return state(MODIFIED, modified ? MODIFIED : 0);
  }

  /** Whether the view set {@link #selected(boolean)}. */
  boolean isSelected() {
    return (states & SELECTED) != 0;
  }

  /** Whether the view set {@link #current(boolean)}. */
  boolean isCurrent() {
    return (states & CURRENT) != 0;
  }

  /** Whether this checkbox is checked or this switch ({@code role=switch}) is on. */
  public El checked(boolean checked) {
    return state(CHECKED | NOT_CHECKED, checked ? CHECKED : NOT_CHECKED);
  }

  /** Neither checked nor clear, as a checkbox over a group of which some are checked. */
  public El checkedMixed() {
    return state(CHECKED | NOT_CHECKED, CHECKED | NOT_CHECKED);
  }

  /** Whether what this element opens, such as a tree node's children, is shown. */
  public El expanded(boolean expanded) {
    return state(EXPANDED | COLLAPSED, expanded ? EXPANDED : COLLAPSED);
  }

  /**
   * Whether this element is disabled. It only says so: a disabled element takes no input when the
   * view gives it no handlers and does not make it {@link #focusable}, as the kit's components do.
   * A text field is disabled by {@link #editable editable(null)} and needs no call.
   */
  public El disabled(boolean disabled) {
    return state(DISABLED, disabled ? DISABLED : 0);
  }

  /**
   * Whether the value of this element is not accepted, as WAI-ARIA's {@code aria-invalid}: a field
   * of a form whose error says why.
   */
  public El invalid(boolean invalid) {
    return state(INVALID, invalid ? INVALID : 0);
  }

  /**
   * What is wrong with the value of this element, as WAI-ARIA's {@code aria-errormessage}: the
   * message of a field whose text is not a valid value, which a form shows below it. A tree dump
   * writes it after the value, as {@code error="Not a number"}; null for none. It changes nothing
   * about how the element looks or takes input.
   */
  public El errorMessage(String message) {
    this.errorMessage = message;
    return this;
  }

  /** What {@link #errorMessage(String)} set, or null. */
  public String errorMessage() {
    return errorMessage;
  }

  /**
   * What to think twice about in the value of this element, which is valid: the warning a form
   * shows below a field. A tree dump writes it after the error, as {@code warning="Only 1 day
   * old"}; null for none. It changes nothing about how the element looks or takes input, and it
   * does not make the element {@link #invalid}.
   */
  public El warningMessage(String message) {
    this.warningMessage = message;
    return this;
  }

  /** What {@link #warningMessage(String)} set, or null. */
  public String warningMessage() {
    return warningMessage;
  }

  /** The column this header sorts by, upwards. */
  public El sortAscending() {
    return state(ASCENDING | DESCENDING, ASCENDING);
  }

  /** The column this header sorts by, downwards. */
  public El sortDescending() {
    return state(ASCENDING | DESCENDING, DESCENDING);
  }

  private El state(int bits, int set) {
    states = states & ~bits | set;
    return this;
  }

  /** The depth of a node in a tree, 1 at the top. */
  public El level(int level) {
    if (level < 1) {
      throw new IllegalArgumentException("A level is 1 or more, was " + level);
    }
    this.level = level;
    return this;
  }

  /**
   * The position of this element among its siblings in a set, from 1, and the size of the set, as
   * a node among the children of its parent. The rows of a {@link #rows virtual list} have their
   * index plus 1 among all rows unless the row function sets another.
   */
  public El position(int position, int setSize) {
    if (position < 1 || position > setSize) {
      throw new IllegalArgumentException(
          "A position is from 1 to the size of the set, was " + position + " of " + setSize);
    }
    this.position = position;
    this.setSize = setSize;
    return this;
  }

  /**
   * The value of an element that has one beyond its label, as text: where a separator between
   * two parts is, say. A text field's value is its string, which needs no call.
   */
  public El value(String value) {
    this.value = value;
    return this;
  }

  // Size

  public El w(float pixels) {
    style.width = Len.px(pixels);
    return this;
  }

  public El h(float pixels) {
    style.height = Len.px(pixels);
    return this;
  }

  public El size(float width, float height) {
    return w(width).h(height);
  }

  public El wFull() {
    style.width = Len.percent(1);
    return this;
  }

  public El hFull() {
    style.height = Len.percent(1);
    return this;
  }

  public El minSize(float width, float height) {
    style.minWidth = Len.px(width);
    style.minHeight = Len.px(height);
    return this;
  }

  public El maxSize(float width, float height) {
    style.maxWidth = Len.px(width);
    style.maxHeight = Len.px(height);
    return this;
  }

  // Spacing

  public El padding(float all) {
    return padding(all, all, all, all);
  }

  public El padding(float vertical, float horizontal) {
    return padding(vertical, horizontal, vertical, horizontal);
  }

  public El padding(float top, float right, float bottom, float left) {
    style.paddingTop = Len.px(top);
    style.paddingRight = Len.px(right);
    style.paddingBottom = Len.px(bottom);
    style.paddingLeft = Len.px(left);
    return this;
  }

  public El margin(float all) {
    return margin(all, all, all, all);
  }

  public El margin(float top, float right, float bottom, float left) {
    style.marginTop = Len.px(top);
    style.marginRight = Len.px(right);
    style.marginBottom = Len.px(bottom);
    style.marginLeft = Len.px(left);
    return this;
  }

  /** Space between children, along both axes. */
  public El gap(float pixels) {
    style.rowGap = Len.px(pixels);
    style.columnGap = Len.px(pixels);
    return this;
  }

  // Flow

  public El wrap() {
    style.wrap = Style.Wrap.WRAP;
    return this;
  }

  /** Places the children along the direction they flow in. */
  public El justify(Style.Content justify) {
    style.justifyContent = justify;
    return this;
  }

  /** Places the children across the direction they flow in. */
  public El align(Style.Align align) {
    style.alignItems = align;
    return this;
  }

  public El alignSelf(Style.Align align) {
    style.alignSelf = align;
    return this;
  }

  /** Centers the children on both axes. */
  public El center() {
    return justify(Style.Content.CENTER).align(Style.Align.CENTER);
  }

  public El grow(float factor) {
    style.grow = factor;
    return this;
  }

  public El shrink(float factor) {
    style.shrink = factor;
    return this;
  }

  public El basis(float pixels) {
    style.basis = Len.px(pixels);
    return this;
  }

  /** Takes this element out of the flow and places it relative to its parent's top left. */
  public El absolute(float left, float top) {
    style.position = Style.Position.ABSOLUTE;
    style.left = Len.px(left);
    style.top = Len.px(top);
    return this;
  }

  /**
   * Places this {@link #absolute} element against the sibling with this key instead of its
   * parent: along the parent's main axis its offsets and percentages refer to that sibling's
   * border box, as a line under the active one of a row of tabs. With {@link #motion} it moves
   * from one sibling to the next when the key changes. Without such a sibling in the flow it is
   * placed in its parent as before.
   *
   * @param key the sibling's {@link #key}, or null for none
   */
  public El follow(String key) {
    style.follow = key;
    return this;
  }

  // Look

  /** @param argb the color as 0xAARRGGBB */
  public El fill(int argb) {
    this.fill = argb;
    return this;
  }

  /** Rounds every corner of the border box with this radius. */
  public El radius(float pixels) {
    return radius(pixels, pixels, pixels, pixels);
  }

  /**
   * Rounds each corner with its own radius, in the order of CSS's {@code border-radius}. A radius
   * larger than half the shorter side is drawn as that half. Fill, border, clip, shadows and the
   * focus ring follow the corners, and so does what takes a press.
   */
  public El radius(float topLeft, float topRight, float bottomRight, float bottomLeft) {
    this.radius = topLeft;
    this.radiusTopRight = topRight;
    this.radiusBottomRight = bottomRight;
    this.radiusBottomLeft = bottomLeft;
    return this;
  }

  /** A border as wide on every side. @param argb the color as 0xAARRGGBB */
  public El border(float width, int argb) {
    return border(width, width, width, width, argb);
  }

  /**
   * A border of its own width on each side, in one color, in the order of CSS's {@code
   * border-width}; a side of width 0 has none, so {@code border(0, 0, 1, 0, c)} is a line along the
   * bottom. The widths take room in the layout as CSS's do. Where a rounded corner joins two sides
   * of different widths, its inner edge is the outer one moved in by each side's width, as in CSS.
   *
   * @param argb the color as 0xAARRGGBB
   */
  public El border(float top, float right, float bottom, float left, int argb) {
    style.borderTop = Len.px(top);
    style.borderRight = Len.px(right);
    style.borderBottom = Len.px(bottom);
    style.borderLeft = Len.px(left);
    this.borderColor = argb;
    this.borderColors = null;
    return this;
  }

  /**
   * Gives each side of the border its own color, in the order of CSS's {@code border-color}, as
   * a field's strong line along the bottom of a quiet frame: {@code border(1,
   * frame).borderColors(frame, frame, line, frame)}. Call it after {@link #border}, which sets one
   * color again. A rounded corner takes the color of the top or the bottom side in that side's
   * rows, and of the left or the right side above or below them. With {@link #motion} the sides in the top's color spring as
   * one border color does; the others change at once.
   *
   * @param top the color as 0xAARRGGBB, also {@link #borderColor()}
   */
  public El borderColors(int top, int right, int bottom, int left) {
    this.borderColor = top;
    boolean one = top == right && top == bottom && top == left;
    this.borderColors = one ? null : new int[] {top, right, bottom, left};
    return this;
  }

  /**
   * Draws shadows under this element, as CSS's {@code box-shadow} does: each is the element's
   * rounded rectangle moved, grown and blurred (see {@link Shadow}), drawn right before the
   * element, so above everything drawn before it, in the clips of the element and at its opacity.
   * Of several shadows the first lies on top, as in CSS; a tight one and a wide one together look
   * lifted. A shadow takes no room in the layout and no input, and it shows also
   * under a translucent fill. It does not spring with {@link #motion}; a look that changes it
   * changes it at once.
   *
   * @param shadows the shadows, replacing those set before; none for no shadow
   */
  public El shadow(Shadow... shadows) {
    return shadow(List.of(shadows));
  }

  /** As {@link #shadow(Shadow...)}, from a list such as a theme's. */
  public El shadow(List<Shadow> shadows) {
    this.shadows = List.copyOf(shadows);
    return this;
  }

  /**
   * Paints a background over this element's {@link #fill} (see {@link Background}): where the fill
   * shows, within the inner edge of its border, and under its image, segments, text and children.
   * Opacity and clips apply as for the fill. A background takes no room in the layout and no input,
   * and it does not spring with {@link #motion}; a look that changes it changes it at once. An
   * element over a {@link #range} of its parent's text draws only its fill.
   *
   * @param background the background, replacing the one set before, or null for none
   */
  public El background(Background background) {
    this.background = background;
    return this;
  }

  /**
   * Makes this element and everything below it translucent: 0 is invisible, 1 (the default) is
   * opaque. An element at 0 draws nothing and takes no presses. With {@link #motion} it springs
   * like the other values.
   */
  public El opacity(float opacity) {
    if (!(opacity >= 0 && opacity <= 1)) {
      throw new IllegalArgumentException("opacity must be from 0 to 1, was " + opacity);
    }
    this.opacity = opacity;
    this.targetOpacity = opacity;
    return this;
  }

  /**
   * Draws this element's own fill and border only within an arc around its center: from {@code
   * start}, in turns clockwise from the top, over {@code sweep} turns. A round element with a
   * border becomes a ring cut off square at both ends, as a {@code Progress} ring, one with a fill
   * a pie. A sweep of 0 draws neither, 1, the default, both whole. The arc's edges are smoothed
   * like any other. Its shadows, image, children, clip and focus ring stay whole, and so does what
   * takes a press. With {@link #motion} the sweep springs like the other values; the start
   * changes at once.
   *
   * @param start where the arc begins, in turns: 0 at the top, 0.25 at the right
   * @param sweep how much of a turn it covers, from 0 to 1
   */
  public El arc(float start, float sweep) {
    if (!Float.isFinite(start)) {
      throw new IllegalArgumentException("an arc's start must be finite, was " + start);
    }
    if (!(sweep >= 0 && sweep <= 1)) {
      throw new IllegalArgumentException("an arc's sweep must be from 0 to 1, was " + sweep);
    }
    this.arcStart = start;
    this.arcSweep = sweep;
    return this;
  }

  /** Where the {@link #arc} begins, in turns clockwise from the top; 0 without one. */
  public float arcStart() {
    return arcStart;
  }

  /** How much of a turn the {@link #arc} covers; 1, the whole element, without one. */
  public float arcSweep() {
    return arcSweep;
  }

  /**
   * Draws straight lines over this element's fill and image, in logical pixels from the top left
   * corner of its border box, in the order given: the edges of a diagram, the marks of a chart.
   * They come before its text and children, which lie on top, and are clipped as its children
   * are, so with {@link #clip} they stay inside its box. They take no presses; what takes a press
   * is still the element's box. A line along the pixel grid is sharpest with its edges on physical
   * pixel boundaries: at scale 1, one 1 pixel wide at {@code y = 10.5}, not at 10.
   */
  public El segments(Segment... segments) {
    this.segments = List.of(segments);
    return this;
  }

  /** As {@link #segments(Segment...)}, from a list. */
  public El segments(List<Segment> segments) {
    this.segments = List.copyOf(segments);
    return this;
  }

  /** The lines this element draws ({@link #segments(Segment...)}); empty without any. */
  public List<Segment> segments() {
    return segments;
  }

  /** For {@link Animator}: the opacity of the moment while this element springs to its own. */
  void shownOpacity(float opacity) {
    this.opacity = opacity;
  }

  /**
   * For {@link App}: the opacity the view gave this element, which {@link #opacity} springs
   * towards; an element fading in has 0 there and more here.
   */
  float targetOpacity() {
    return targetOpacity;
  }

  /**
   * Draws the children only inside this element's rounded rectangle: its border box with its
   * {@link #radius}. The edge of the clip is smoothed like any other edge, and what is clipped away
   * takes no input. This sets {@code overflow: clip}, which leaves the layout as it is; any
   * overflow other than visible clips.
   */
  public El clip() {
    style.overflowX = Style.Overflow.CLIP;
    style.overflowY = Style.Overflow.CLIP;
    return this;
  }

  /**
   * Makes this element a vertical scroll area: it clips its children like {@link #clip}, the wheel
   * moves them up and down, and {@link App} keeps the offset across rebuilds. Horizontally it only
   * clips. This sets {@code overflow-y: scroll} and {@code overflow-x: hidden}, so that the area
   * can be smaller than its content.
   *
   * <p>Unlike in CSS flexbox, the children do not shrink to fit along the direction they flow in
   * when the area scrolls that way: they keep their natural size, as in a block container.
   */
  public El scroll() {
    style.overflowX = Style.Overflow.HIDDEN;
    style.overflowY = Style.Overflow.SCROLL;
    scrollArea = true;
    return this;
  }

  /**
   * Keeps the offsets of this scroll area ({@link #scroll}, {@link #scrollBoth}, {@link #rows}) in
   * {@code state}, which the app holds, instead of by the area's identity, so that the area keeps
   * where it was scrolled to while it is out of the tree or in the tree of another app, as a tab's
   * grid in a window of its own. Give the same object to the same area on every build, and each
   * area one of its own; null, the default, keeps the offsets by identity.
   *
   * @throws IllegalStateException when the view is built, if two areas of the tree have one
   */
  public El scrollState(ScrollState state) {
    this.scrollState = state;
    return this;
  }

  ScrollState scrollState() {
    return scrollState;
  }

  /**
   * Whether this area was sent to its vertical end, with {@link #scrollTo} to {@link
   * Double#POSITIVE_INFINITY}, until its last rows are measured: its {@link ScrollState}'s while
   * it has the state, else its own, which a rebuild carries over.
   */
  boolean toEnd() {
    return scrollState == null ? toEnd : scrollState.el == this && scrollState.toEnd;
  }

  /** Sets what {@link #toEnd()} says, as long as this area has its state, if it has one. */
  void toEnd(boolean end) {
    if (scrollState == null) {
      toEnd = end;
    } else if (scrollState.el == this) {
      scrollState.toEnd = end;
    }
  }

  /** Makes this element a scroll area like {@link #scroll}, along both axes. */
  public El scrollBoth() {
    scroll();
    style.overflowX = Style.Overflow.SCROLL;
    return this;
  }

  /**
   * Gives this scroll area ({@link #scroll}, {@link #scrollBoth}, {@link #rows}) scrollbars that
   * look as {@code look} says. After layout, {@link App} places a bar along the right edge while
   * the content is taller than the area and one along the bottom while it is wider; an axis whose
   * content fits has none, and where both show, each stops short of the corner. A bar lies over
   * the content and takes no room in the layout. It stays where it is while the area scrolls, at
   * the edges of the part of the area that its ancestors' clips leave visible, short of the bars
   * of scroll areas around it, and it is clipped like the area's children, to its rounded corners.
   *
   * <p>So that the last row and the last column can come out from under a bar that crosses them,
   * the area scrolls further by the bar's strip, its thickness and both margins: down past the bar
   * along its bottom, its own or that of an area around it whose bottom it shares, as the rows of
   * a data grid scroll past the grid's bar, and right past a bar along its right edge. A list of
   * rows always reaches that far, any other area only along an axis on which its content does not
   * fit anyway. Keeping a row or a rectangle in view ({@link #revealRow}, {@link #reveal}) and
   * scrolling the focused element into view show it clear of these bars.
   *
   * <p>The thumb's length is the share of the content in view, at least {@link
   * Scrollbars#minThumb}, and its place the offset. Dragging the thumb scrolls in proportion,
   * from the first pixel, from the start to the very end of a list of a million rows; a press on
   * the track beside it scrolls one page, the area's height or width, towards the pointer. Such a
   * press is the bar's alone: it is no press or drag of the content or of an ancestor, selects no
   * text and leaves the focus where it is, and while the thumb is dragged, the area counts as the
   * element under the pointer. The wheel scrolls as before. In a {@link #dump tree dump} the
   * area's line shows each thumb's rectangle.
   *
   * @param look the size and colors of the bars, or null for none, the default
   */
  public El scrollbars(Scrollbars look) {
    this.scrollbars = look;
    return this;
  }

  /**
   * Marks ranges of this {@link #multiline} field's text along its vertical {@link #scrollbars
   * bar}, as an overview of where they are: each a tick across the track, under the thumb, whose
   * share of the track above it is the share of the text above the range's first line, folds and
   * {@link #blocks} counted as shown. Each call adds to the marks of the calls before, so that a
   * code editor's diagnostics and its find bar's matches show together. A press on a tick scrolls
   * its line into the middle of the view, and is no press of the field. A field whose text fits
   * has no bar and shows no marks.
   *
   * @param marks the marks to add
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El scrollMarks(List<Scrollbars.Mark> marks) {
    Text t = editableOnly("scrollMarks");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "scrollMarks is for multi-line fields: call it after multiline().");
    }
    Objects.requireNonNull(marks, "marks");
    if (marks.isEmpty()) {
      return this;
    }
    if (field.scrollMarks == null) {
      field.scrollMarks = new ArrayList<>(marks.size());
    }
    for (Scrollbars.Mark mark : marks) {
      field.scrollMarks.add(Objects.requireNonNull(mark, "mark"));
    }
    return this;
  }

  /**
   * Makes this element a virtual list: a vertical scroll area as {@link #scroll} makes, whose
   * content is {@code count} rows of the same height, of which only those in or near its visible
   * part exist as elements. A million rows cost no more per frame than a dozen.
   *
   * <pre>{@code
   * col().key("orders").grow(1).rows(orders.size(), 28, index -> orderRow(orders.get(index)))
   * }</pre>
   *
   * <p>{@link App} calls {@code row} with a row's index, from 0, for the rows inside the list's
   * visible part, which is its border box inside the window, and {@link #OVERSCAN} more on each
   * side: after every rebuild of the view, and when scrolling shows a row that does not exist yet.
   * That builds the rows that do not exist yet, but not the view, and keeps those that do where it
   * can; scrolling within the rows that exist builds nothing. {@code row} runs on the
   * app's thread during {@link App#frame}, so it has to be cheap, and like the view it only reads
   * the state and returns a new element each time. Each row
   * is laid out on its own, with the list's inner width and {@code rowHeight}, at {@code index *
   * rowHeight} below the top of the list's content box; the size, margin and position the row's
   * element sets are replaced.
   *
   * <p>A row is identified by its index, also when its element has a {@link #key}: what the app
   * keeps of a row, such as its movement, a press on it and the caret, selection and undo history
   * of a text field in it ({@link #editable}), belongs to the index and is dropped when the row
   * leaves the rows that exist. The focus is kept: a focused row that scrolls out stays focused,
   * keys go to the list and its ancestors meanwhile, and the row is focused again when it comes
   * back; a focused text field keeps its editing state with it, but ends a composition. Tab and Shift+Tab enter the list at the element that last had the focus
   * in it, else at the first row in view (the last for Shift+Tab). Rows neither fade in nor out,
   * and they move with the list at once, also with {@link #motion}.
   *
   * <p>The list has no other children: {@link #child} fails on it. Its content has no width of its
   * own, so give the list a width or let its parent stretch it, and give it a height or let it
   * {@link #grow}: like any scroll area it is as tall as its content otherwise, up to {@link
   * #LAYOUT_LIMIT}, beyond which it scrolls. Layout works in floats, which are exact to the pixel
   * only that far; the rows themselves are placed from the first one that exists and can reach
   * any offset. In a column that grows itself, the column also needs a minimum height of 0 (as
   * {@code min-height: 0} in CSS), or the rows, up to that limit, are its minimum height.
   *
   * @param count the number of rows, 0 or more
   * @param rowHeight the height of every row in logical pixels, above 0
   * @param row builds the element of the row with this index
   * @see App#scrollToRow
   */
  public El rows(int count, float rowHeight, IntFunction<El> row) {
    Objects.requireNonNull(row, "row");
    rows(count, rowHeight, (index, from, to) -> row.apply(index));
    rows.spans = false;
    return this;
  }

  /**
   * Makes this element a virtual list as {@link #rows(int, float, IntFunction)} does, whose rows
   * are built for the part of them in view sideways too, as a table builds only the cells of the
   * columns in view. The row function gets that part, widened by half its width on each side, in
   * the coordinates of the list's content box; the list is clipped by a scroll area around it that
   * scrolls sideways, or by the window. To read the text of every column, as {@link
   * App#scopeText} does, it gets the part from 0 to {@link Float#POSITIVE_INFINITY}: build only
   * the columns there are. When scrolling sideways shows a part beyond it, {@link
   * App} builds the rows anew, without running the view, as it does when scrolling shows a row
   * that does not exist yet. Give each column's cell, and each element that holds cells, a {@link
   * #key} so a selection across a {@link #selectionScope} stays on its columns as the list scrolls
   * sideways.
   */
  public El rows(int count, float rowHeight, RowBuilder row) {
    if (count < 0) {
      throw new IllegalArgumentException("count must be 0 or more, was " + count);
    }
    if (!(rowHeight > 0 && rowHeight < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("rowHeight must be above 0 and finite, was " + rowHeight);
    }
    if (!children.isEmpty()) {
      throw new IllegalStateException(
          "rows makes the rows of this element; it cannot have children of its own as well.");
    }
    scroll();
    RowBuilder builder = Context.captured(Objects.requireNonNull(row, "row"));
    Rows list = rows();
    list.rowCount = count;
    list.rowHeight = rowHeight;
    list.rowBuilder = builder;
    list.spans = true;
    // Layout sees all rows as one leaf, as tall as they are together, but no taller than its floats
    // add up to the pixel: 22 000 000 pixels of rows and 23 above them came to 22 000 024, and a
    // list that shrank into 224 pixels was 200 high instead of 201. The offsets of scrolling are
    // doubles and reach the last row anyway.
    this.measure = (knownWidth, knownHeight, availableWidth, availableHeight) ->
        new Measure.Size(0, (float) Math.min(rowsTotal(), LAYOUT_LIMIT));
    return this;
  }

  /**
   * Makes this element a virtual list as {@link #rows(int, float, IntFunction)} does, whose rows
   * are each as high as their content at the list's inner width: the blocks of a long document.
   * A row's height is estimated until the list builds it, then measured by its layout and kept in
   * {@code heights}, which the app keeps in its state and passes on every build. When rows above
   * the first one in view turn out higher or lower than estimated, the list scrolls by as much,
   * so that what is in view stays where it is; the thumb moves a little instead. {@link
   * App#scrollToRow} goes to where a row starts as far as the heights are known.
   *
   * @param heights the heights of the rows, which it holds for as many rows as {@code count}
   */
  public El rows(int count, RowHeights heights, IntFunction<El> row) {
    rows(count, 1, row);
    rows.heights = Objects.requireNonNull(heights, "heights");
    heights.count(count);
    rows.heightsVersion = heights.version();
    rows.versions = heights.versions(); // in this view's context, with its row function
    return this;
  }

  /**
   * Translates {@code y} from {@code before} when both lists share the same keyed {@link
   * RowHeights} and its rows changed once between them.
   */
  double movedScrollY(El before, double y) {
    if (rows == null
        || rows.heights == null
        || before == null
        || before.rows == null
        || before.rows.heights != rows.heights
        || before.rows.heightsVersion != rows.heightsVersion - 1) {
      return y;
    }
    double top = before.rowsTop(); // the offset in the rows' coordinates, the padding kept
    double moved = rows.heights.moved(Math.max(0, y - top));
    // Rows that come above a list scrolled into its top padding put its first row at the top.
    return y < top && moved == 0 ? y : top + moved;
  }

  /** The {@link RowHeights} of this virtual list, or null. */
  RowHeights rowHeights() {
    return rows == null ? null : rows.heights;
  }

  /** The versions of this list's rows in its {@link RowHeights} when it was built, or null. */
  Object[] rowVersions() {
    return rows == null ? null : rows.versions;
  }

  /** The version of this list's keyed {@link RowHeights} when it was built, 0 without one. */
  int rowsVersion() {
    return rows == null || rows.heights == null ? 0 : rows.heightsVersion;
  }

  /** The key of a row of this list's keyed {@link RowHeights}, or null. */
  Object rowKey(int row) {
    return rows == null || rows.heights == null ? null : rows.heights.key(row);
  }

  /**
   * Where the row with this key is now; for null, the key of one past the last row, the count.
   * -1 when it is gone.
   */
  int rowWithKey(Object key) {
    return key == null ? rows.rowCount : rows.heights == null ? -1 : rows.heights.indexOf(key);
  }

  /** Where a row starts below the top of the content box. */
  private double rowTop(int index) {
    return rows.heights == null ? index * (double) rows.rowHeight : rows.heights.top(index);
  }

  private double rowSize(int index) {
    return rows.heights == null
        ? rows.rowHeight
        : index >= 0 && index < rows.rowCount ? rows.heights.height(index) : 0;
  }

  /** The row at this distance below the top of the content box, or past the last. */
  private long rowAt(double y) {
    return rows.heights == null ? (long) Math.floor(y / rows.rowHeight) : rows.heights.at(y);
  }

  /** The first row that starts at or below this distance below the top of the content box. */
  private long rowAfter(double y) {
    if (rows.heights == null) {
      return (long) Math.ceil(y / rows.rowHeight);
    }
    int at = rows.heights.at(y);
    return rows.heights.top(at) < y ? at + 1L : at;
  }

  private double rowsTotal() {
    return rows.heights == null ? rows.rowCount * (double) rows.rowHeight : rows.heights.total();
  }

  /** For {@link App#scrollToRow}: the offset that puts this row at the top of the list. */
  double rowOffset(int index) {
    return rows == null ? 0 : rowTop(index);
  }

  /**
   * The most a {@link #rows virtual list} contributes to layout as its content's height, 2^20
   * logical pixels, where a float still has 3 bits below the pixel, so that sums with the sizes
   * around the list stay exact: a list without a height of its own is at most this tall and
   * scrolls beyond.
   */
  public static final float LAYOUT_LIMIT = 1 << 20;

  /**
   * Keeps a row of this {@link #rows virtual list} in view: whenever a rebuild of the view gives
   * the list another row here than the tree before, or the list is new, {@link App} scrolls it
   * just far enough to show that row whole. In between the list scrolls freely. Meant for the
   * selected row, so that the list follows a selection that the keyboard moves.
   *
   * <p>Any other scroll area ({@link #scroll}, {@link #scrollBoth}) keeps the child with this
   * index in view the same way, along the axes it scrolls, as a row of tabs keeps the active
   * one; children that are leaving do not count.
   *
   * @param index the row, from 0; -1, the default, or one beyond the last keeps none in view
   */
  public El revealRow(int index) {
    this.revealRow = index;
    return this;
  }

  /**
   * Keeps a part of this scroll area's content in view: whenever a rebuild of the view gives the
   * area another {@code key} here than the tree before, or the area is new, {@link App} scrolls it
   * just far enough to show the rectangle whole along the axes it scrolls, or its start where it
   * is larger. In between the area scrolls freely, and the same key shows nothing again even where
   * the rectangle moved. A table keeps the column of its cursor in view this way, with the
   * column's index as the key.
   *
   * @param key what the rectangle shows, compared with {@link Object#equals}; null, the default,
   *     keeps nothing in view
   * @param x the left of the rectangle in the content's coordinates, logical pixels from the left
   *     of the area's border box at an offset of 0
   */
  public El reveal(Object key, float x, float y, float width, float height) {
    this.revealKey = key;
    this.revealX = x;
    this.revealY = y;
    this.revealWidth = width;
    this.revealHeight = height;
    return this;
  }

  /**
   * Tells {@code handler} which rows this {@link #rows virtual list} has built, the first and the
   * last, whenever a frame has built others than before: after scrolling beyond them or a change
   * of their number, not on every rebuild of the view. The rows that exist are those whose cells
   * a table asks for, so an app that fetches its rows in pages starts fetching here. It runs as
   * the handlers of input do: after the input of the frame that built the rows, or in the next
   * {@link App#frame} when the rebuild at the end of one built them, which {@link
   * App#nextChange} then says is due at once; with an {@link #onRowsInView} handler too, as
   * that one. A list without rows tells nothing.
   *
   * @param handler told the rows, or null for none
   */
  public El onRowsBuilt(RowsHandler handler) {
    handlers().onRowsBuilt = handler;
    return this;
  }

  /**
   * Tells {@code handler} the first and the last row of this {@link #rows virtual list} that are
   * wholly in view, inside its own border box, whenever a frame shows others than it told: after scrolling, by less than the
   * rows it has built ({@link #onRowsBuilt}) too, after a change of its size or of the number of
   * rows, and when the list first shows; not on every frame or rebuild of the view. A row partly
   * under a bar along the bottom ({@link #coverY}) is not whole. With -1 and -1 where no row is
   * whole or the list has none. It runs as the handlers of input do, in the {@link App#frame}
   * after the one that showed the rows, which {@link App#nextChange} then says is due at
   * once. The view built after it shows the rows in view, as a status line "Rows 201–223" or
   * buttons that page through the rows. A list with both handlers runs its {@link #onRowsBuilt}
   * one at that time too, and both as one input, followed by one rebuild.
   *
   * @param handler told the rows, or null for none
   */
  public El onRowsInView(RowsHandler handler) {
    handlers().onRowsInView = handler;
    return this;
  }

  /**
   * Tells the app the size layout gave this element, as a browser's {@code ResizeObserver} does:
   * in the first build that has it and whenever a rebuild gives it another size than it told,
   * so that a view can show what fits, as a toolbar that moves the buttons it has no room for
   * into a menu. It runs as the handlers of input do, right after the layout that gave the size,
   * and the view built from what it changed is laid out again, which may tell it again, up to
   * three times before the {@link App#frame} ends: the frame that brings a new size shows the view
   * that fits it. A view that has not settled by then goes on in the next frame, as does what the
   * handler asks for, such as {@link App#focus(El)}. Not told inside the rows of a virtual list,
   * nor for the sizes motion passes through.
   *
   * @param handler told the width and height, or null for none
   */
  public El onResize(SizeHandler handler) {
    handlers().onResize = handler;
    return this;
  }

  SizeHandler onResize() {
    return handlers == null ? null : handlers.onResize;
  }

  /**
   * Keeps this element in place sideways while the nearest scroll area around it that scrolls
   * sideways ({@link #scrollBoth}) scrolls: it moves up and down with its parent, never sideways,
   * as the frozen columns of a table. Layout places it as usual; the offset of that area is then
   * left out of its position, for drawing and for input. It is drawn in tree order, so the
   * elements it stays above come before it among its siblings, and it needs a fill of its own to
   * hide what scrolls under it.
   */
  public El fixedX() {
    this.fixedX = true;
    return this;
  }

  // Text, for elements made with text()

  public El font(Font font) {
    textOnly("font").font(font);
    return this;
  }

  /** The font size in logical pixels. */
  public El fontSize(float pixels) {
    textOnly("fontSize").size(pixels);
    return this;
  }

  /**
   * The height of one line of text as a multiple of the font size, inherited by descendant text
   * elements; without it, each text uses its font's own height. The difference from the font's
   * height is split evenly above and below the glyphs, so the baseline moves by half of it.
   *
   * @param multiple a positive finite multiple of the font size, such as {@code 1.5f}
   */
  public El lineHeight(float multiple) {
    if (!(multiple > 0 && multiple < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("line height must be above 0, was " + multiple);
    }
    this.lineHeight = multiple;
    if (text != null) {
      text.lineHeight(multiple);
    }
    return this;
  }

  /** @param argb the text color as 0xAARRGGBB */
  public El color(int argb) {
    textOnly("color").color(argb);
    return this;
  }

  /**
   * States the color behind this text, for when the scene cannot tell. Glyph edges are
   * corrected differently for text darker and lighter than its background (see {@link
   * com.rethinkj.ui.text.TextRendering}); the scene takes the background from the fills of the
   * text element and its ancestors, and misses what lies behind it otherwise, such as an earlier
   * sibling, an absolutely placed box or an image.
   *
   * @param argb the color as 0xAARRGGBB, taken as opaque; 0, the default, lets the scene decide
   */
  public El textBackground(int argb) {
    textOnly("textBackground").background(argb);
    return this;
  }

  /**
   * Ends a line of this text that is wider than its box with {@link Text#ELLIPSIS}: as many
   * graphemes as fit with it, without the spaces before it; a box too narrow for the ellipsis
   * alone shows nothing of the line. Layout sizes the text as before; give
   * the element a width or let it shrink below its text ({@code minSize(0, 0)}), as a table cell
   * does. The tree dump shows the whole string, and {@link Text#shortened} says whether a line was
   * shortened. A single-line text field is shortened while it does not have the focus, when it
   * shows its start anyway, as a read-only value that can still be selected and copied; with the
   * focus it scrolls as fields do. Multi-line fields are not shortened.
   */
  public El ellipsis() {
    textOnly("ellipsis").ellipsis(true);
    return this;
  }

  /**
   * Underlines characters {@code from} to {@code to} of the text, in the text's color, as a menu
   * marks the letter that opens it with Alt. The line lies where an input method's composition is
   * underlined. Not for a text field, whose composition has the line.
   */
  public El underline(int from, int to) {
    Text t = textOnly("underline");
    if (from < 0 || to < from || to > t.string().length()) {
      throw new IllegalArgumentException(
          "underline " + from + " to " + to + " is not inside \"" + t.string() + "\"");
    }
    t.underline(from, to);
    return this;
  }

  /**
   * Lays this element over characters {@code from} to {@code to} of its parent's text, a {@link
   * #multiline} field, instead of where layout would put it: a link in a paragraph. Its rectangle
   * is the bounding box of the rows the range takes, as the tree dump shows it, but it is hit,
   * filled and ringed only on those rows: one rectangle per row from the range's first character
   * to its last, which {@link #rangeBoxes} lists. Its fill lies behind the parent's glyphs. It takes
   * presses, the pointer's cursor, hover looks and, when {@link #focusable}, the focus like any
   * other element; a press on it is its own, not the field's. A script's selector presses the
   * middle of its first row. The range is clamped to the text.
   */
  public El range(int from, int to) {
    if (from < 0 || to < from) {
      throw new IllegalArgumentException("range " + from + " to " + to + " is not a range");
    }
    rangeFrom = from;
    rangeTo = to;
    return this;
  }

  /**
   * The rows of a {@link #range}, in logical pixels from this element's top left corner, as the
   * last layout placed them; null for an element without a range, empty while none is placed.
   */
  public List<Text.Box> rangeBoxes() {
    return rangeFrom < 0 ? null : rangeBoxes == null ? List.of() : rangeBoxes;
  }

  /**
   * Places the children with a {@link #range} over the rows of this field's text, once its rows
   * are laid out: after layout, and after the field's editor has scrolled them.
   */
  void placeRanges() {
    if (text == null || !text.multiline() || !text.laidOut() || children.isEmpty()) {
      return;
    }
    float scale = text.scale();
    float left = border(Side.LEFT) + padding(Side.LEFT) + (float) scrollX;
    float top = border(Side.TOP) + padding(Side.TOP) + (float) scrollY;
    int length = text.string().length();
    for (El child : children) {
      if (child.rangeFrom < 0) {
        continue;
      }
      List<Text.Box> rows = text.selectionBoxes(
          Math.min(child.rangeFrom, length), Math.min(child.rangeTo, length), 0);
      if (rows.isEmpty()) {
        child.rangeBoxes = List.of();
        child.place(left, top, 0, 0);
        continue;
      }
      float x0 = Float.POSITIVE_INFINITY;
      float y0 = Float.POSITIVE_INFINITY;
      float x1 = Float.NEGATIVE_INFINITY;
      float y1 = Float.NEGATIVE_INFINITY;
      for (Text.Box row : rows) {
        x0 = Math.min(x0, row.x());
        y0 = Math.min(y0, row.y());
        x1 = Math.max(x1, row.x() + row.width());
        y1 = Math.max(y1, row.y() + row.height());
      }
      List<Text.Box> boxes = new ArrayList<>(rows.size());
      for (Text.Box row : rows) {
        boxes.add(new Text.Box(
            (row.x() - x0) / scale,
            (row.y() - y0) / scale,
            row.width() / scale,
            row.height() / scale));
      }
      child.rangeBoxes = List.copyOf(boxes);
      child.place(left + x0 / scale, top + y0 / scale, (x1 - x0) / scale, (y1 - y0) / scale);
    }
  }

  /**
   * Lets the text break into more lines where it is wider than its box: where {@link
   * java.text.BreakIterator#getLineInstance} allows, and inside a word, path or URL wider than the
   * whole box, between graphemes.
   */
  public El wrapText() {
    return wrapText(true);
  }

  /**
   * Whether the text breaks into more lines where it is wider than its box, as {@link #wrapText()}
   * says; it does not until this says so. A {@link #multiline} field that wraps scrolls only up
   * and down, one that does not also sideways; a single-line field that wraps measures as wide as
   * its widest line and as high as its lines, up to {@link #maxLines}.
   */
  public El wrapText(boolean wraps) {
    textOnly("wrapText").wraps(wraps);
    if (text.multiline()) {
      style.overflowX = wraps ? Style.Overflow.HIDDEN : Style.Overflow.SCROLL;
    }
    return this;
  }

  /**
   * Shows at most {@code count} lines of this text. With {@link #ellipsis} the last line ends in
   * an ellipsis; without it the text ends with that line. A single-line field that {@link
   * #wrapText wraps} is as high as its lines up to this many; while it has the focus its last line
   * runs on without the ellipsis and scrolls with the caret. The tree dump shows the whole string.
   *
   * <p>On a {@link #multiline} field whose style gives it no height, layout measures it as high as
   * its rows between {@link #lines} and {@code count}, and it scrolls once its text has more rows.
   * When {@code count} is smaller than {@link #lines}, {@link #lines} wins; {@link #fitLines} wins
   * over {@code maxLines}.
   */
  public El maxLines(int count) {
    if (count < 1) {
      throw new IllegalArgumentException("maxLines must be 1 or more, was " + count);
    }
    textOnly("maxLines").maxLines(count);
    return this;
  }

  /**
   * Shapes the text with these OpenType features, in HarfBuzz's syntax, after the defaults of
   * {@link Font#shape}: {@code "+calt"} turns on the ligatures of fonts such as JetBrains Mono.
   */
  public El fontFeatures(String... features) {
    textOnly("fontFeatures").features(features);
    return this;
  }

  /**
   * Aligns each line of this text element in its content box: at the start (the left, the
   * default), in the center or at the end, as CSS's {@code text-align} does, for a text of one
   * line and for one that {@link #wrapText wraps}, also where a line ends in an {@link #ellipsis}:
   * an empty state's centered message, a column of numbers aligned at the right. Each line on its
   * own, on the grid of glyph positions. A {@link #multiline} field that {@link #wrapText wraps}
   * aligns each of its rows the same way, with its caret, selection, hit testing, marks and {@link
   * #range range} elements at the aligned positions; other text fields, which scroll their lines
   * sideways, start at the left. Its {@link #lineEnds line-end elements} follow the aligned end of
   * a line, where a line at the end leaves them no room.
   *
   * @param align {@link Style.Align#START}, {@link Style.Align#CENTER} or {@link Style.Align#END}
   */
  public El textAlign(Style.Align align) {
    Text t = textOnly("textAlign");
    if (align != Style.Align.START && align != Style.Align.CENTER && align != Style.Align.END) {
      throw new IllegalArgumentException(
          "textAlign takes START, CENTER or END, as CSS's text-align does; was " + align);
    }
    t.align(align);
    return this;
  }

  /**
   * Colors parts of this text element's string, and sets them in other fonts of its size, as a
   * highlighter of code does: {@code highlighter} says per line which ranges have which color or
   * font ({@link Spans}). A {@link #multiline} field asks only about the lines it shapes and keeps
   * the answers across rebuilds while {@code key} stays equal, so a view that passes the same key
   * with the same highlighter costs nothing; another key asks again. Each line gets the state the
   * line before ended in, as a tokenizer needs inside a block comment or a string; see {@link
   * Highlighter} for what that costs. Other text, a single-line field among them, is one line, asked
   * whenever the view is built. A {@link #masked} field and a placeholder are not highlighted.
   *
   * <p>Colors need no shaping, and carets, selection and wrapping do not change with them; a
   * range in another font is shaped on its own, and they follow its glyphs.
   *
   * @param key compared with {@link Object#equals}: a new key when the highlighting changes, such
   *     as another language or theme; not null
   * @param highlighter the function, or null for none
   */
  public El highlight(Object key, Highlighter highlighter) {
    textOnly("highlight").highlight(Objects.requireNonNull(key, "key"), highlighter);
    return this;
  }

  /**
   * The bracket of this text's string just before {@code caret}, or else just after it, and the
   * one that matches it, as indexes into the string as the element shows it: empty where there is
   * none, and the one bracket where its partner is missing or more than 100 000 characters away.
   * Brackets are {@code ( ) [ ] { }}, and nesting counts per kind. Where the {@link #highlight
   * highlighter} {@link Highlighter#marksBrackets marks brackets}, only those it marks ({@link
   * Spans#bracket}) count, so that one in a string or a comment pairs with none; otherwise every
   * bracket of the string does.
   *
   * <p>The marks come from the highlighting of a {@link #multiline} field with a {@link
   * #fieldState} whose field showed this string: the highlighter is asked only about lines it was
   * not asked about yet, as far as the search reaches. Before the field has highlighted down to
   * the bracket's line, as at its first build, every bracket counts.
   */
  public int[] matchingBracket(int caret) {
    Text t = textOnly("matchingBracket");
    FieldState state = fieldState();
    Editor editor = state == null ? null : state.editor;
    Lines lines = editor == null ? null : editor.linesOf(t.value());
    String text =
        lines != null ? editor.text() : t.multiline() ? Editor.multiLine(t.value()) : t.value();
    return Brackets.matching(text, caret, lines, t.highlighter(), t.highlightKey());
  }

  /**
   * The pairs of this text's brackets ({@code ( ) [ ] { }}, nesting per kind) that can fold: each
   * pair whose opening and closing brackets lie at least two lines apart, with the {@link Fold}
   * from the opening line through the line before the closing bracket, so the closing line stays
   * visible. Where the {@link #highlight highlighter} {@link Highlighter#marksBrackets marks
   * brackets}, only marked brackets count; otherwise every bracket of the string does.
   *
   * <p>For a {@link #multiline} field with a {@link #fieldState}, the scan reads the highlighter's
   * line states kept by the field, respects its per-frame line budget (returning an incomplete
   * {@link BracketFolds} that resumes on the next frame), and caches the complete result until the
   * text or highlight key changes.
   */
  public BracketFolds bracketFolds() {
    Text t = textOnly("bracketFolds");
    FieldState state = fieldState();
    Editor editor = state == null ? null : state.editor;
    Lines lines = editor == null ? null : editor.linesOf(t.value());
    Highlighter highlighter = t.highlighter();
    boolean marked = highlighter != null && highlighter.marksBrackets();
    if (lines == null && marked && state != null && t.multiline()) {
      if (field != null) {
        field.bracketFoldsPending = true;
      }
      return new BracketFolds(List.of(), 0, false);
    }
    String text =
        lines != null ? editor.text() : t.multiline() ? Editor.multiLine(t.value()) : t.value();
    return Brackets.folds(text, lines, highlighter, t.highlightKey());
  }

  /** For {@link App}: whether {@link #bracketFolds} waited for this field's lines to attach. */
  boolean takeBracketFoldsPending() {
    if (field == null || !field.bracketFoldsPending) {
      return false;
    }
    field.bracketFoldsPending = false;
    return true;
  }

  /**
   * Draws backgrounds and underlines behind the lines of a {@link #multiline} field or a {@link
   * #selectionScope selection scope} that are in view, before its selection: the line of the caret,
   * matches of a search, a pair of brackets, an error. The decorator is asked about each line in
   * view and a few around it whenever the field lays out its rows, keeps nothing, and so may read
   * anything of the app's state; its colors and fonts are ignored, which are a {@link #highlight
   * highlighter}'s.
   *
   * <p>On a {@link #selectionScope}, the decorator's line indices and character offsets are in the
   * scope's combined text ({@link App#scopeText}): core passes each built read-only field (including
   * virtual list rows built later on scroll) its part, translated to the field's own offsets and
   * merged with the field's own decorator.
   *
   * @param decorator the function, or null for none
   * @throws IllegalStateException if this is neither a multi-line field nor a selection scope
   */
  public El decorate(Decorator decorator) {
    if (selectionScope) {
      this.scopeDecorator = decorator;
      return this;
    }
    Text t = editableOnly("decorate");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "decorate is for multi-line fields or selection scopes: call it after multiline() or"
              + " selectionScope().");
    }
    t.decorator(decorator);
    return this;
  }

  /**
   * The decorator {@link #decorate} gave, or null; a component that adds its own marks calls it
   * too, as a find bar does with an editor's or a selection scope's.
   */
  public Decorator decorator() {
    return selectionScope ? scopeDecorator : text == null ? null : text.decorator();
  }

  /**
   * Adds a decorator to the one this field or {@link #selectionScope selection scope} has, which
   * marks its lines first: where marks of one kind overlap, the added one's win, as {@link Spans}
   * says. A view that marks more in a component that decorates itself, such as a code editor,
   * keeps the component's marks with it; {@link #decorate} replaces them.
   *
   * @param decorator the function; not null
   * @throws IllegalStateException if this is neither a multi-line field nor a selection scope
   */
  public El alsoDecorate(Decorator decorator) {
    Objects.requireNonNull(decorator, "decorator");
    Decorator own = decorator();
    return decorate(
        own == null
            ? decorator
            : (line, start, string, out) -> {
              own.line(line, start, string, out);
              decorator.line(line, start, string, out);
            });
  }

  /**
   * Draws a thin rectangular outline behind the text of a {@link #multiline} field around
   * characters {@code from} to {@code to} in {@code argb}: horizontally from the smallest start
   * column of the range's non-blank lines (starting at {@code from} on its first line) to after its
   * widest line (ending at {@code to} on its last line), and vertically from the top of its first
   * visible line to the bottom of its last, following scroll, {@link #folds} and {@link #blocks}
   * without changing layout. Lines of the range that have not been in view yet widen it once they
   * come into view. {@code from >= to}, a transparent color or a field that {@link #wrapText wraps}
   * draws nothing. The range is clamped to the text.
   *
   * @param from start offset in the field's shown text
   * @param to end offset in the field's shown text
   * @param argb color as 0xAARRGGBB, or 0 for none
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El outline(int from, int to, int argb) {
    Text t = editableOnly("outline");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "outline is for multi-line fields: call it after multiline().");
    }
    t.outline(from, to, argb);
    return this;
  }

  // Editing, for elements made with text()

  /**
   * Makes this text element a single-line text field, or with {@link #multiline} one of several
   * lines, whose string belongs to the app: the view
   * builds it with the current value, and every edit hands the new string to {@code onEdit},
   * which stores it in the state; the next rebuild shows it. A handler may also refuse or change
   * an edit, and the field then shows the app's string, with the caret where it was before a
   * refused edit and otherwise as near to where the edit left it as the change allows.
   *
   * <p>{@link App} keeps the caret, the selection, the horizontal scroll, the undo history and the
   * composition of an input method by the element's identity, as it keeps scroll offsets. The
   * field takes the focus ({@link #focusable}), is a {@code textbox} unless it has a {@link #role},
   * shows the {@link Cursor#TEXT} cursor unless it has a {@link #cursor}, and edits with the keys
   * of Windows and Linux: arrows, Home and End, with Shift to select and Control by words,
   * Backspace and Delete, Control+A, C, X, V, Z and Y, and Control+Shift+Z. Typed text comes
   * from {@link App#textInput}. Enter, Escape and Tab go on to the {@link #onKey} handlers, of
   * this element first, which also see every key before the field does and can take it. A press
   * places the caret, a drag selects, a double press selects a word, a triple press everything,
   * and Shift with a press extends the selection. A press in the field is the field's, even
   * inside an element with an {@link #onPress} or {@link #onDrag} handler, and while it selects
   * the field counts as the element under the pointer, as a dragged element does.
   *
   * <p>The field is as tall as a line of its text, centered in its content box, and takes its
   * width from its style, as layout measures it 0 wide; the text scrolls sideways inside its
   * content box and is clipped to it. Line breaks in typed or pasted text become spaces; {@link
   * #multiline} makes a field that keeps them.
   *
   * @param onEdit receives the new string after each edit; null disables the field, which then
   *     shows its string or placeholder, takes neither focus nor input, and lets presses go to its
   *     ancestors
   */
  public El editable(Consumer<String> onEdit) {
    textOnly("editable").field(true);
    if (field == null) {
      field = new Field();
    }
    handlers().onEdit = onEdit;
    return this;
  }

  /**
   * Makes an {@link #editable} field one of several lines, as the editor of a query: its string
   * holds a line feed ({@code \n}) for each line break, and the field is a scroll area of its own
   * ({@link #scroll}) that shows its lines from the top of its content box, wrapped at its width
   * with {@link #wrapText}, or not, and then scrolling sideways too, with bars if it has {@link
   * #scrollbars}. The wheel scrolls it while it can, and its ancestors beyond. Layout measures it
   * 0 wide and {@link #lines} lines high (or between {@link #lines} and {@link #maxLines} when
   * {@link #maxLines} is set; with {@link #fitLines} it is as high as all its rows, and with
   * {@link #wrapText} as well as wide as its widest row), as a single-line field is one line high;
   * the style usually gives it its size.
   *
   * <p>The keys of a single-line field work across lines, and these besides: Enter breaks the
   * line (the field takes it, so an ancestor's Enter does not run; Control+Enter goes on to the
   * {@link #onKey} handlers, so that an app can run a query with it); Up and Down move by a row on
   * screen and Page Up and Page Down by the rows in view (in a {@link #readOnly} field that shows
   * all its rows with {@link #fitLines} they go on without Shift, so that the scroll area around
   * it pages), keeping the caret's x from where they began; Home goes to the start of the row, and
   * a second Home to the first character of the line that is not a space or a tab and back; End to
   * the end of the row; Control+Home and Control+End to the ends of the text; all with Shift to
   * select. A third press selects the line with its line feed, a fourth the whole text. Tab moves
   * the focus, unless {@link #tabIndents} says otherwise. Carriage returns, with or without a line
   * feed after them, and the separators U+2028 and U+2029 become line feeds, in typed and pasted
   * text and in the app's string, which the field shows and edits so, handing the app the string
   * with line feeds at the first edit.
   *
   * <p>Only the rows in view are laid out and only their lines shaped, and the field keeps where
   * its lines start across rebuilds and edits, so that a frame costs the same for a text of 100 000
   * lines as for one of ten. An edit still replaces the app's string, which costs in proportion to
   * its length.
   *
   * @throws IllegalStateException if the field is {@link #masked}: a password has one line
   */
  public El multiline() {
    Text t = editableOnly("multiline");
    if (t.masked()) {
      throw new IllegalStateException(
          "A masked field cannot be multiline(): a password has one line. Leave out multiline()"
              + " or masked().");
    }
    t.multiline(true);
    scrollArea = true;
    style.overflowY = Style.Overflow.SCROLL;
    style.overflowX = t.wraps() ? Style.Overflow.HIDDEN : Style.Overflow.SCROLL;
    return this;
  }

  /**
   * Gives a {@link #multiline} field a gutter at its left, inside its border and padding, as the
   * line numbers of a code editor: a column of cells that {@code cells} builds, one per line, at
   * the line's first row, for the lines in view and a few around them only, as {@link #rows} builds
   * rows. It scrolls up and down with the text and stays in place sideways; the text starts after
   * it, so the gutter is part of the field's box, inside its frame and focus ring. The cells are
   * built again when the lines in view, their places or the caret's line change, also without a
   * rebuild of the view; each is a line high, and as wide as the cell of the last line, the widest
   * number, laid out at its natural width when the tree is built, unless the cells set a width.
   * Presses on a cell reach its handlers; a press elsewhere in the gutter focuses the field. In a
   * {@code .tree.txt} the gutter is {@code el #gutter}, inside the field's line, with the cells
   * keyed {@code line-<index>} unless they have keys.
   *
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El gutter(Gutter cells) {
    return gutter(cells, null);
  }

  /**
   * A {@link #gutter(Gutter)} on a rail: {@code rail}, an element of the view such as a band with
   * a line along its right edge, lies behind the cells from the field's left border to the
   * gutter's end, as high as the field inside its border, also above the first line and below the
   * last; it does not scroll. Its right border lies after the gutter, beside the cells rather than
   * under them, and its right margin is the gap between it and the text. In a {@code .tree.txt} it
   * comes before the gutter, as {@code el #rail} unless it has a key.
   *
   * @param rail the rail, or null for none
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El gutter(Gutter cells, El rail) {
    Text t = editableOnly("gutter");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "gutter is for multi-line fields: call it after multiline().");
    }
    field.gutter = Context.captured(Objects.requireNonNull(cells, "cells"));
    field.gutterRail = rail;
    if (rail != null && rail.key == null) {
      rail.key("rail");
    }
    return this;
  }

  /** For {@link App}: whether this field has a gutter, whose cells are built after layout. */
  boolean hasGutter() {
    return field != null && field.gutter != null;
  }

  private record BlockKey(Block block, int ordinal) {}

  private record HintKey(Hint hint, int ordinal) {}

  /**
   * For {@link App}: whether this field has parts (gutter cells, line-end elements, blocks or
   * inline hints) built after layout.
   */
  boolean hasFieldParts() {
    return field != null
        && (field.gutter != null
            || !field.folds.isEmpty()
            || !field.blocks.isEmpty()
            || !field.hints.isEmpty()
            || field.lineEnds != null);
  }

  /**
   * For {@link App}: whether gutter cells, line-end elements or blocks were built since it last
   * asked.
   */
  boolean takePartsBuilt() {
    if (field == null) {
      return false;
    }
    boolean built = field.partsBuilt;
    field.partsBuilt = false;
    return built;
  }

  /**
   * For {@link App}: the names of the looks it applied to {@code part}, a part built for this
   * tree; a part built anew has none.
   */
  Set<String> partLooks(El part) {
    if (field.partLooks == null) {
      // Weak: El keeps identity equality, and scrolling builds gutter cells anew under a pointer
      // that stays, while a hint the shaped text holds may come back with its look.
      field.partLooks = new WeakHashMap<>();
    }
    return field.partLooks.computeIfAbsent(part, built -> new HashSet<>());
  }

  /**
   * For {@link App}: drops the hints and gutter cells cached for this tree, after a look changed
   * one. Blocks keep theirs with the look, so that one a look made higher keeps the height the
   * rows below make room for until the next build; hints keep theirs when {@code keepHints} is
   * true, so a line reshaped after a hint's width changed measures the hint with its look.
   */
  void dropPartCaches(boolean keepHints) {
    if (field != null) {
      if (!keepHints) {
        field.hintCache = null;
      }
      field.blockGutterCells = Map.of();
    }
  }

  /**
   * For {@link App}: forgets the cached block {@code part}, whose look no longer applies, so that
   * the field builds it anew.
   *
   * @return whether it was cached
   */
  boolean forgetBlock(El part) {
    if (field == null || field.blockCache == null || !field.blockCache.values().remove(part)) {
      return false;
    }
    text.restale();
    return true;
  }

  /**
   * Rejects an explicit key that another block at the same line, or hint at the same offset, of
   * this tree already has: the key is the part's identity, also while the other is out of view.
   */
  private void claimPartKey(El built, int ordinal, String anchor) {
    if (built.key != null
        && field.partOrdinals.putIfAbsent(built.fieldPartKey, ordinal) instanceof Integer other
        && other != ordinal) {
      throw new IllegalStateException(
          "Two " + anchor + " have the key '" + built.key + "'; give each a distinct key");
    }
  }

  /** For {@link Animator} and {@link App}: this element's post-layout field part key, or null. */
  FieldPart fieldPartKey() {
    return fieldPartKey;
  }

  /** For {@link App}: whether {@code child} is this field's gutter column. */
  boolean isGutterColumn(El child) {
    return field != null && child == field.gutterColumn;
  }

  /**
   * For {@link App}: lays out {@code part}, a post-layout part of this field, again after a look
   * or newly attached editor changed it, keeping its place in the field.
   *
   * @return whether a block's height changed, so that the field must place its rows again
   */
  boolean relayoutPart(El part, Set<El> looked, float scale, boolean systemFonts) {
    FieldPart key = part.fieldPartKey;
    if (field == null || key == null) {
      return false;
    }
    switch (key.kind()) {
      case GUTTER -> {
        for (El cell : part.children) {
          if (looked.isEmpty() || containsAny(cell, looked)) {
            float cx = cell.x;
            float cy = cell.y;
            float cw = cell.width;
            float ch = cell.height;
            scaleText(cell, scale, systemFonts, resolvedLineHeight);
            Flex.layout(cell, cw, ch);
            cell.place(cx, cy, cw, ch);
          }
        }
      }
      case RAIL -> {
        float rx = part.x;
        float ry = part.y;
        float rw = part.width;
        float rh = part.height;
        scaleText(part, scale, systemFonts, resolvedLineHeight);
        Flex.layout(part, rw, rh);
        part.place(rx, ry, rw, rh);
      }
      case BLOCK -> {
        float bx = part.x;
        float by = part.y;
        float bh = part.height;
        float contentWidth = text.contentWidth() / scale;
        boolean exact = false; // a block of a number of rows keeps their band
        for (Map.Entry<BlockKey, El> cached : field.blockCache == null
            ? Set.<Map.Entry<BlockKey, El>>of()
            : field.blockCache.entrySet()) {
          exact |= cached.getValue() == part && cached.getKey().block().lines() > 0;
        }
        scaleText(part, scale, systemFonts, resolvedLineHeight);
        if (exact) {
          part.style.height = Len.px(bh);
          Flex.layout(part, contentWidth, bh);
        } else {
          Flex.layout(part, contentWidth, Measure.MAX_CONTENT);
        }
        float blockW = contentWidth;
        if (!text.wraps()) { // as measureBlock: wider children show unless the builder clipped it
          float[] extent = {part.width, 0};
          for (El child : part.overflowsBlock ? part.children : List.<El>of()) {
            if (child.placed && !child.exiting) {
              extent[0] = Math.max(extent[0], child.x + child.width);
              child.overflow(child.x, child.y, extent);
            }
          }
          blockW = Math.max(contentWidth, extent[0]);
        }
        boolean grew = !exact && part.height != bh;
        part.place(bx, by, blockW, exact ? bh : part.height); // the band Text keeps, unrounded
        if (grew) {
          text.restale(); // the rows below make room for it, measured from the block cache
          return true;
        }
      }
      case FOLD, LINE_END -> {
        if (!field.lineEndElements.contains(part)) {
          return false; // an earlier element on the same line already pushed it out of view
        }
        float px = part.x;
        float py = part.y;
        float oldW = part.width;
        float oldH = part.height;
        float rowHeight = text.lineHeight() / scale;
        scaleText(part, scale, systemFonts, resolvedLineHeight);
        Flex.layout(part, Float.POSITIVE_INFINITY, rowHeight);
        part.place(px, py + (oldH - part.height) / 2f, part.width, part.height);
        if (part.width != oldW) { // those after it on its line move, and may come into view
          Map<FieldPart, El> kept = new HashMap<>();
          for (El placed : field.lineEndElements) {
            kept.put(placed.fieldPartKey, placed);
          }
          boolean built = field.partsBuilt; // App goes on with the parts it has
          field.lineEndsShown = null;
          placeLineEnds(kept);
          field.partsBuilt = built;
        }
      }
      case HINT -> {
        float px = part.x;
        float py = part.y;
        float oldH = part.height;
        float rowHeight = text.lineHeight() / scale;
        scaleText(part, scale, systemFonts, resolvedLineHeight);
        Flex.layout(part, Float.POSITIVE_INFINITY, rowHeight);
        part.place(px, py + (oldH - part.height) / 2f, part.width, part.height);
        if (text.reshapeHint(part)) {
          field.hintsShown = null;
          return true;
        }
      }
    }
    return false;
  }

  private static boolean containsAny(El el, Set<El> targets) {
    if (targets.contains(el)) {
      return true;
    }
    for (El child : el.children) {
      if (containsAny(child, targets)) {
        return true;
      }
    }
    if (el.overlays != null) {
      for (El overlay : el.overlays) {
        if (containsAny(overlay, targets)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Hides ranges of lines in a {@link #multiline} field, keeping each range's {@link Fold#first
   * first} line shown with a default ellipsis mark ({@code …}) at its end and hiding lines {@code
   * first + 1} through {@link Fold#last last}. A press on the mark, an edit whose range touches a
   * hidden line, or a caret or selection end placed on a hidden line by the app, find or undo asks
   * {@code unfold} to open the fold; the field keeps no fold state of its own, and the app keeps
   * the ranges current with its string.
   *
   * <p>Ranges may come in any order; overlapping or nested ranges hide every line any range hides
   * with the outermost range owning the visible mark, and ranges past the text are clamped or
   * ignored. In a {@code .tree.txt} each visible mark is keyed {@code fold-<first>} unless it has
   * a key.
   *
   * @param folds the folded line ranges, empty for none
   * @param unfold called when a fold should open; may be null
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El folds(List<Fold> folds, Consumer<Fold> unfold) {
    return folds(folds, unfold, null);
  }

  /**
   * Like {@link #folds(List, Consumer)}, with {@code mark} building the placeholder element placed
   * after the end of each visible fold's first line (its last row, vertically centered in the row),
   * for folds in view only. Core wires a press on the mark to call {@code unfold} with that fold,
   * so the mark needs no press handler of its own.
   *
   * @param mark builds the placeholder element for a visible fold, or null for the default ellipsis
   *     mark in the field's text color at reduced opacity
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El folds(List<Fold> folds, Consumer<Fold> unfold, Function<Fold, El> mark) {
    Text t = editableOnly("folds");
    if (!t.multiline()) {
      throw new IllegalStateException("folds is for multi-line fields: call it after multiline().");
    }
    field.folds = List.copyOf(Objects.requireNonNull(folds, "folds"));
    field.onUnfold = unfold;
    field.foldMark = Context.captured(mark);
    t.folds(field.folds);
    return this;
  }

  /** For {@link Editor}: the folded line ranges of this field, or empty. */
  List<Fold> folds() {
    return field == null ? List.of() : field.folds;
  }

  /** For {@link Editor}: the unfold handler of this field, or null. */
  Consumer<Fold> onUnfold() {
    return field == null ? null : field.onUnfold;
  }

  /**
   * Places elements between lines of a {@link #multiline} field: {@link Block.Placement#INSERT}
   * blocks insert vertical space before their line (or after the last line when {@code first} equals
   * the line count) without hiding text, and {@link Block.Placement#REPLACE} blocks hide every line
   * of {@code first..last} inclusive and show their element in place of them. The app owns the list
   * and keeps its line indices current with the string; the field keeps no block state of its own.
   *
   * <p>Elements are built only while in view (and the layout margin around it), laid out at the
   * field's content width, and cached in the field's line index across rebuilds until the width,
   * scale or font changes. In a {@code .tree.txt} each block element is keyed {@code block-<first>}
   * unless it has a key, and {@code block-<first>-<n>} for the {@code n}-th ({@code n >= 1})
   * further block at the same line. Several {@code INSERT} blocks before the same line stack in
   * list order, and their explicit keys must differ; {@code REPLACE} blocks must not overlap each
   * other. Both {@code block} and {@code reveal} receive the {@code Block} instances from {@code blocks} itself, so callers may tell
   * equal blocks apart by identity. A fold that hides a block's
   * line hides the block as well, and while the field shows its placeholder it shows only {@link
   * Block.Placement#INSERT} blocks at line 0.
   *
   * <p>Presses on interactive elements inside a block reach those elements without moving the
   * focus, and editable fields, drags and focusable controls inside a block work like elsewhere in
   * the tree (Tab and Shift+Tab visit focusable controls in order). A press elsewhere in the block
   * band places the caret as a click at the start of the following line would. An edit
   * touching a replaced range, or a caret or selection end placed inside one by find, undo/redo,
   * {@link App#select} or {@link FieldState#select}, calls {@code reveal}.
   *
   * @param blocks the blocks to show, empty for none
   * @param block builds the element for each block in view
   * @param reveal called when a replaced block should reveal its source lines; may be null
   * @throws IllegalStateException if this is not a multi-line field
   * @throws IllegalArgumentException if two {@link Block.Placement#REPLACE} blocks overlap
   */
  public El blocks(List<Block> blocks, Function<Block, El> block, Consumer<Block> reveal) {
    Text t = editableOnly("blocks");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "blocks is for multi-line fields: call it after multiline().");
    }
    List<Block> copy = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    Objects.requireNonNull(block, "block");
    List<Block> replaces = null;
    for (Block b : copy) {
      if (b.placement() == Block.Placement.REPLACE) {
        if (replaces == null) {
          replaces = new ArrayList<>();
        }
        replaces.add(b);
      }
    }
    if (replaces != null && replaces.size() > 1) {
      replaces.sort((a, b) -> {
        int c = Integer.compare(a.first(), b.first());
        return c != 0 ? c : Integer.compare(a.last(), b.last());
      });
      for (int i = 1; i < replaces.size(); i++) {
        Block prev = replaces.get(i - 1);
        Block curr = replaces.get(i);
        if (curr.first() <= prev.last()) {
          throw new IllegalArgumentException(
              "replace blocks must not overlap: " + prev + " and " + curr);
        }
      }
    }
    field.blocks = copy;
    field.blockBuilder = Context.captured(block);
    field.onReveal = reveal;
    t.blocks(field.blocks, this::measureBlock);
    return this;
  }

  /** For {@link Editor}: the blocks of this field, or empty. */
  List<Block> blocks() {
    return field == null ? List.of() : field.blocks;
  }

  /** For {@link Editor}: the reveal handler of this field, or null. */
  Consumer<Block> onReveal() {
    return field == null ? null : field.onReveal;
  }

  /**
   * Builds gutter cells for visible {@link #blocks line blocks} in this {@link #multiline} field's
   * {@link #gutter}, spanning each block's height in the gutter column. Returning {@code null} for
   * a block leaves {@link Block.Placement#INSERT} blocks without a gutter cell and lets {@link
   * Block.Placement#REPLACE} blocks keep their normal line cell; returning a non-null element for
   * a {@code REPLACE} block replaces that line cell. Passing {@code null} clears the handler.
   *
   * <p>In a {@code .tree.txt} a block gutter cell without a key is keyed {@code
   * block-gutter-<first>}, and {@code block-gutter-<first>-<n>} for the {@code n}-th ({@code n >=
   * 1}) further block at the same line. {@code cells} receives the {@code Block} instances from
   * {@link #blocks}, so callers may tell equal blocks apart by identity.
   *
   * @param cells builds the gutter element for each placed block, or null to clear
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El blockGutter(Function<Block, El> cells) {
    Text t = editableOnly("blockGutter");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "blockGutter is for multi-line fields: call it after multiline().");
    }
    field.blockGutter = Context.captured(cells);
    field.blockGutterCells = Map.of();
    field.gutterShown = null;
    return this;
  }

  /**
   * Whether this element and those in it look the same whatever the pointer, the focus and the
   * clock do.
   */
  private boolean looksStill() {
    if (hoverLook != null
        || focusLook != null
        || focusWithinLook != null
        || pressLook != null
        || restLook != null
        || focusRestLook != null
        || cycle != null) {
      return false;
    }
    for (El child : children) {
      if (!child.looksStill()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Places elements after the end of lines of a {@link #multiline} field, such as inline blame or
   * a diagnostic's message. Core calls {@code elements} with the line index (from 0) of each shown
   * line whose last row is in view, and only for those, each time it places them; returning null or
   * an empty list places none for that line. While the field shows its placeholder it shows no
   * line-end elements.
   *
   * <p>On each line, elements are laid out left to right after the line's end (and after any
   * visible {@link #folds fold} mark on that line), vertically centered in the row with a gap
   * between elements. In a {@code .tree.txt} an element without a key is keyed {@code end-<line>},
   * and {@code end-<line>-<n>} for the {@code n}-th ({@code n >= 1}) further element on the same
   * line.
   *
   * <p>A press on an element runs its own press handler without moving the caret or the focus;
   * focusable elements inside a line-end element are reachable by Tab, and editable fields take
   * focus and typing. The elements do not widen the field: one that reaches past the content box
   * is cut off at its edge.
   *
   * @param elements builds the elements for a shown line index, or null for none
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El lineEnds(IntFunction<List<El>> elements) {
    Text t = editableOnly("lineEnds");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "lineEnds is for multi-line fields: call it after multiline().");
    }
    field.lineEnds = Context.captured(elements);
    return this;
  }

  /**
   * Places inline hint elements inside the lines of a {@link #multiline} field, such as parameter
   * name hints before arguments or inferred types after variable names. The app owns the list and
   * keeps its offsets current with the string; the field keeps no hint state of its own.
   *
   * <p>Core builds each hint's element with {@code hint} (capturing active {@link Context} values)
   * when its line is shaped, reserves its width in the line during shaping and wrapping, and
   * places elements only for rows in view, vertically centered in the row. Hints past the end of
   * the string or in folded/replaced lines do not show, and while the field shows its placeholder
   * it shows no hints. In a {@code .tree.txt} a hint element without a key is keyed {@code
   * hint-<at>}, and {@code hint-<at>-<n>} for the {@code n}-th ({@code n >= 1}) further hint at
   * the same offset; explicit keys of hints at the same offset must differ.
   *
   * <p>Hints are not part of the field's string: copy and cut ignore them, and {@link
   * #textPositionAt} returns -1 over a hint. Horizontal stepping with Left/Right stops on both
   * sides of a hint (and between a {@link Hint.Affinity#BEFORE} group and an {@link
   * Hint.Affinity#AFTER} group at the same offset); word moves, Home/End, vertical moves and edits
   * use the default stop between the two groups. A {@link Hint.Affinity#BEFORE} hint that ends a
   * wrapped row has no stop on its left, since the caret at its offset shows at the start of the
   * next row. A press on an interactive hint runs its own press handler without moving the caret
   * or focus; a press on a non-interactive hint places the caret at that offset at the nearer stop,
   * or, on a hint that ends a wrapped row, at the nearest position of that row. A focusable hint
   * takes the focus, and Tab and Shift+Tab reach it in order.
   *
   * @param hints the inline hints to show, empty for none
   * @param hint builds the element for each hint
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El hints(List<Hint> hints, Function<Hint, El> hint) {
    Text t = editableOnly("hints");
    if (!t.multiline()) {
      throw new IllegalStateException("hints is for multi-line fields: call it after multiline().");
    }
    List<Hint> copy = List.copyOf(Objects.requireNonNull(hints, "hints"));
    Objects.requireNonNull(hint, "hint");
    field.hints = copy;
    field.hintBuilder = Context.captured(hint);
    t.hints(field.hints, this::measureHint);
    return this;
  }

  /**
   * For {@link App}: whether {@code child} is one of this field's placed line blocks, {@link
   * #lineEnds line-end elements} or {@link #hints inline hints} (not fold marks), which clip to
   * the content box.
   */
  boolean isFieldPart(El child) {
    return field != null && text.clipsToContent(child);
  }

  /** For {@link App}: whether {@code child} is one of this field's placed line blocks. */
  boolean isFieldBlock(El child) {
    return field != null && !field.blockElements.isEmpty() && field.blockElements.contains(child);
  }

  /** For {@link App}: whether {@code child} is one of this field's placed inline hints. */
  boolean isFieldHint(El child) {
    return field != null && !field.hintElements.isEmpty() && field.hintElements.contains(child);
  }

  /** Builds and lays out one inline hint element at its natural width, caching it for this tree. */
  private El measureHint(Hint hint, int ordinal, float scale, float lineHeight) {
    if (field.hintCache == null
        || field.hintCacheScale != scale
        || field.hintCacheLineHeight != lineHeight) {
      field.hintCache = new HashMap<>();
      field.hintCacheScale = scale;
      field.hintCacheLineHeight = lineHeight;
    }
    HintKey key = new HintKey(hint, ordinal);
    El built = field.hintCache.get(key);
    if (built != null) {
      return built;
    }
    built = field.hintBuilder.apply(hint);
    if (built == null) {
      throw new IllegalStateException("The hint function returned null for hint " + hint);
    }
    // By its own key, else its place among the hints at its offset.
    built.fieldPartKey =
        new FieldPart(PartKind.HINT, List.of(hint.at(), built.key != null ? built.key : ordinal));
    claimPartKey(built, ordinal, "hints at offset " + hint.at());
    if (built.key == null) {
      built.key(ordinal == 0 ? "hint-" + hint.at() : "hint-" + hint.at() + "-" + ordinal);
    }
    if (built.text == null && built.label == null && !hint.text().isEmpty()) {
      built.label(hint.text());
    }
    built.keepFocus();
    scaleText(built, scale, text.systemFonts(), resolvedLineHeight);
    Flex.layout(built, Float.POSITIVE_INFINITY, lineHeight / scale);
    field.hintCache.put(key, built);
    return built;
  }

  /** Builds and lays out one block element at the field's content width, caching it for this tree. */
  private El measureBlock(
      Block block, int ordinal, float contentWidth, float scale, float lineHeight) {
    if (field.blockCache == null
        || field.blockCacheWidth != contentWidth
        || field.blockCacheScale != scale) {
      field.blockCache = new HashMap<>();
      field.blockCacheWidth = contentWidth;
      field.blockCacheScale = scale;
    }
    BlockKey key = new BlockKey(block, ordinal);
    El built = field.blockCache.get(key);
    if (built != null) {
      return built;
    }
    built = field.blockBuilder.apply(block);
    if (built == null) {
      throw new IllegalStateException("The block function returned null for block " + block);
    }
    // By its own key, else its place among the blocks at its line.
    built.fieldPartKey = new FieldPart(
        PartKind.BLOCK,
        List.of(block.placement(), block.first(), built.key != null ? built.key : ordinal));
    claimPartKey(built, ordinal, "blocks at line " + block.first());
    if (built.key == null) {
      built.key(ordinal == 0 ? "block-" + block.first() : "block-" + block.first() + "-" + ordinal);
    }
    built.keepFocus(); // focusing the field would scroll to the caret before the press ends
    if (text.wraps()) {
      built.style.width = Len.px(contentWidth);
    } else if (built.style.width.kind() == Len.Kind.AUTO
        && built.style.minWidth.pixelsOrZero() < contentWidth) {
      built.style.minWidth = Len.px(contentWidth);
    }
    scaleText(built, scale, text.systemFonts(), resolvedLineHeight);
    if (block.lines() > 0) {
      float exactHeight = block.lines() * lineHeight / scale;
      built.style.height = Len.px(exactHeight);
      Flex.layout(built, contentWidth, exactHeight);
    } else {
      Flex.layout(built, contentWidth, Measure.MAX_CONTENT);
    }
    if (!text.wraps()) {
      // Unclipped children wider than the view (such as deeply indented rows) stay reachable when
      // scrolled sideways; Scene clips the block to the field's content box.
      float[] extent = {built.width, 0};
      built.overflow(0, 0, extent);
      if (extent[0] > built.width) {
        built.place(built.x, built.y, extent[0], built.height);
      }
    }
    built.overflowsBlock = built.style.overflowX == Style.Overflow.VISIBLE
        && built.style.overflowY == Style.Overflow.VISIBLE;
    built.clip(); // a block draws in its band only
    field.blockCache.put(key, built);
    return built;
  }

  /** The gutter's cell of line {@code line} through {@code lastLine}. */
  private El gutterCell(int line, int lastLine, boolean caret) {
    Lines lines = text.shownIndex();
    return field.gutter.cell(new Gutter.Line(line, lines.start(line), lines.end(lastLine), caret));
  }

  /**
   * For {@link #scaleText}: measures the gutter's width from the cell of the last line at the
   * text's scale and moves the text after it, by widening the left padding.
   */
  private void measureGutter() {
    float scale = text.scale();
    if (field.gutterScale == scale) {
      return;
    }
    if (Float.isNaN(field.gutterLeft)) {
      field.gutterLeft = padding(Side.LEFT);
    }
    int lastLine = text.lineTotal() - 1;
    El last = gutterCell(lastLine, lastLine, false);
    scaleText(last, scale, text.systemFonts(), resolvedLineHeight);
    Flex.layout(last, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
    field.gutterWidth = (float) Math.ceil(last.width);
    field.gutterScale = scale;
    float after = field.gutterRail == null
        ? 0
        : field.gutterRail.style.borderRight.pixelsOrZero()
            + field.gutterRail.style.marginRight.pixelsOrZero();
    style.paddingLeft = Len.px(field.gutterLeft + field.gutterWidth + after);
    field.gutterShown = null;
  }

  /**
   * For {@link Editor}, once the rows of a multi-line field are laid out and scrolled: builds the
   * cells of its gutter for the lines laid out, unless they are built for these lines, places and
   * caret line, and places them.
   *
   * @return whether the cells were built
   */
  boolean placeGutter(int caretLine) {
    if (field == null || field.gutter == null || !text.laidOut() || field.gutterScale == 0) {
      return false;
    }
    List<Text.Start> starts = text.lineStarts();
    List<Text.PlacedBlock> placed = field.blockGutter == null ? List.of() : text.placedBlocks();
    float scale = text.scale();
    float viewY = field.blockGutter == null ? 0 : text.viewY();
    List<Object> shown = List.of(starts, placed, viewY, caretLine, scrollX, scrollY, width, height);
    if (shown.equals(field.gutterShown)) {
      return false;
    }
    field.gutterShown = shown;
    field.partsBuilt = true;
    float rowHeight = text.lineHeight() / scale;
    float top = border(Side.TOP) + padding(Side.TOP);
    El column = el().key("gutter").clip();
    column.fieldPartKey = GUTTER_PART;
    List<El> blockCells = null;
    java.util.Set<Integer> replacedInGutter = null;
    Map<List<Object>, El> cells = placed.isEmpty() ? Map.of() : new HashMap<>();
    if (!placed.isEmpty()) {
      for (Text.PlacedBlock pb : placed) {
        int first = pb.block().first();
        int ordinal = pb.ordinal();
        float blockY = (float) ((pb.top() - viewY) / scale);
        float blockH = (float) (pb.height() / scale);
        List<Object> id = List.of(pb.block(), ordinal, blockH, scale, field.gutterWidth);
        El cell = field.blockGutterCells.get(id);
        if (cell == null && !field.blockGutterCells.containsKey(id)) {
          cell = field.blockGutter.apply(pb.block());
          if (cell != null) {
            if (cell.key == null) {
              cell.key(
                  ordinal == 0 ? "block-gutter-" + first : "block-gutter-" + first + "-" + ordinal);
            }
            cell.style.width = Len.px(field.gutterWidth);
            cell.style.height = Len.px(blockH);
            if (blockH < rowHeight) {
              cell.clip(); // as a line's cell: it draws in its band only
            }
            scaleText(cell, scale, text.systemFonts(), resolvedLineHeight);
            Flex.layout(cell, field.gutterWidth, blockH);
          }
        }
        if (cell == null || cell.looksStill()) { // a look applied by the pointer would stay
          cells.put(id, cell);
        }
        if (cell == null) {
          continue;
        }
        cell.fieldPartKey =
            new FieldPart(PartKind.GUTTER, List.of(pb.block().placement(), first, ordinal));
        cell.place(0, blockY, field.gutterWidth, blockH);
        if (blockCells == null) {
          blockCells = new ArrayList<>();
        }
        blockCells.add(cell);
        if (pb.block().placement() == Block.Placement.REPLACE) {
          if (replacedInGutter == null) {
            replacedInGutter = new java.util.HashSet<>();
          }
          replacedInGutter.add(first);
        }
      }
    }
    for (Text.Start start : starts) {
      if (replacedInGutter != null && replacedInGutter.contains(start.line())) {
        continue;
      }
      boolean caret = caretLine >= start.line() && caretLine <= start.lastLine();
      El cell = gutterCell(start.line(), start.lastLine(), caret);
      if (cell == null) {
        throw new IllegalStateException(
            "The gutter's function returned null for line " + start.line());
      }
      if (cell.key == null) {
        cell.key("line-" + start.line());
      }
      cell.fieldPartKey =
          new FieldPart(PartKind.GUTTER, start.line()); // the view's keys may repeat
      float cellHeight = start.height() / scale;
      cell.style.width = Len.px(field.gutterWidth);
      cell.style.height = Len.px(cellHeight);
      if (cellHeight < rowHeight) {
        cell.clip();
      }
      scaleText(cell, scale, text.systemFonts(), resolvedLineHeight);
      Flex.layout(cell, field.gutterWidth, cellHeight);
      cell.place(0, start.top() / scale, field.gutterWidth, cellHeight);
      column.children.add(cell);
    }
    if (blockCells != null) {
      column.children.addAll(blockCells);
    }
    field.blockGutterCells = cells;
    // The column stays where the content box is: the scroll that shifts it is added back.
    column.place(
        border(Side.LEFT) + field.gutterLeft + (float) scrollX,
        top + (float) scrollY,
        field.gutterWidth,
        text.contentHeight() / scale);
    if (field.gutterRail != null) {
      children.remove(field.gutterRail);
    }
    if (field.gutterColumn != null) {
      children.remove(field.gutterColumn);
    }
    field.gutterColumn = column;
    int at = 0;
    if (field.gutterRail != null) {
      float borderTop = border(Side.TOP);
      float railWidth =
          field.gutterLeft + field.gutterWidth + field.gutterRail.style.borderRight.pixelsOrZero();
      float railHeight = height - borderTop - border(Side.BOTTOM);
      scaleText(field.gutterRail, scale, text.systemFonts(), resolvedLineHeight);
      Flex.layout(field.gutterRail, railWidth, railHeight);
      field.gutterRail.fieldPartKey = RAIL_PART;
      field.gutterRail.place(
          (float) scrollX + border(Side.LEFT), (float) scrollY + borderTop, railWidth, railHeight);
      children.add(at++, field.gutterRail);
    }
    children.add(at, column);
    setScroll(scrollX, scrollY);
    text.shownIndex().gutterCells += starts.size();
    return true;
  }

  private static final float FOLD_GAP = 4;
  private static final float DEFAULT_FOLD_OPACITY = 0.6f;

  private El defaultFoldMark() {
    return El.text(Text.ELLIPSIS)
        .font(text.font())
        .fontSize(text.size())
        .color(text.color())
        .opacity(DEFAULT_FOLD_OPACITY);
  }

  /**
   * For {@link Editor}, once the rows of a multi-line field are laid out and scrolled: builds the
   * fold marks and {@link #lineEnds line-end elements} for the shown lines whose last row is in
   * view, unless they are built for these ends and places, and places them left to right after each
   * line's end.
   *
   * @return whether the line-end elements were built
   */
  boolean placeLineEnds() {
    return placeLineEnds(Map.of());
  }

  /** Like {@link #placeLineEnds()}, reusing the elements in {@code kept}, with their looks. */
  private boolean placeLineEnds(Map<FieldPart, El> kept) {
    if (field == null || (field.folds.isEmpty() && field.lineEnds == null) || !text.laidOut()) {
      if (field != null && !field.lineEndElements.isEmpty()) {
        children.removeAll(field.lineEndElements);
        field.lineEndElements = List.of();
        text.lineEndElements(List.of());
        field.foldsExtent = 0;
        field.lineEndsShown = null;
      }
      return false;
    }
    List<Text.LineEnd> ends = text.lineEnds(field.lineEnds == null);
    float scale = text.scale();
    List<Object> shown = List.of(ends, scrollX, scrollY, width, height);
    if (shown.equals(field.lineEndsShown)) {
      return false;
    }
    field.lineEndsShown = shown;
    field.partsBuilt = true;
    float rowHeight = text.lineHeight() / scale;
    float contentLeft = border(Side.LEFT) + padding(Side.LEFT);
    float contentTop = border(Side.TOP) + padding(Side.TOP);
    float contentWidth = text.contentWidth() / scale;
    List<El> built = new ArrayList<>(ends.size());
    List<El> clipped = new ArrayList<>(); // the app's elements, not the fold marks
    float maxRight = 0;
    for (Text.LineEnd end : ends) {
      float cursor = end.endX() / scale;
      float rowTop = end.top() / scale;
      Fold fold = end.fold();
      if (fold != null) {
        FieldPart foldKey = new FieldPart(PartKind.FOLD, fold.first());
        El mark = kept.get(foldKey);
        if (mark == null) {
          mark = field.foldMark != null ? field.foldMark.apply(fold) : defaultFoldMark();
          if (mark == null) {
            throw new IllegalStateException(
                "The fold mark function returned null for fold " + fold);
          }
          if (mark.key == null) {
            mark.key("fold-" + fold.first());
          }
          mark.fieldPartKey = foldKey;
          if (field.onUnfold != null) {
            PressAtHandler existing = mark.onPress();
            Consumer<Fold> onUnfold = field.onUnfold;
            mark.onPress((clicks, modifiers, x, y) -> {
              if (existing != null) {
                existing.press(clicks, modifiers, x, y);
              }
              onUnfold.accept(fold);
            });
            if (mark.cursor == null) {
              mark.cursor(Cursor.POINTER);
            }
            mark.keepFocus(); // focusing the field would scroll to the caret before the press ends
          }
          scaleText(mark, scale, text.systemFonts(), resolvedLineHeight);
          Flex.layout(mark, Float.POSITIVE_INFINITY, rowHeight);
        }
        float markX = cursor + FOLD_GAP + mark.margin(Side.LEFT);
        float markY = rowTop + (rowHeight - mark.height) / 2f;
        float markRight = markX + mark.width + mark.margin(Side.RIGHT);
        maxRight = Math.max(maxRight, markRight + (float) scrollX);
        if (text.wraps()) {
          markX = Math.clamp(markX, 0, Math.max(0, contentWidth - mark.width));
          cursor = markX + mark.width + mark.margin(Side.RIGHT);
        } else {
          cursor = markRight;
          if (markX < 0 || markX >= contentWidth) { // partly left, it would cover the gutter
            mark = null;
          }
        }
        if (mark != null) {
          mark.place(
              contentLeft + markX + (float) scrollX,
              contentTop + markY + (float) scrollY,
              mark.width,
              mark.height);
          built.add(mark);
        }
      }
      if (field.lineEnds == null) {
        continue;
      }
      int line = end.line();
      List<El> extras = field.lineEnds.apply(line);
      if (extras == null || extras.isEmpty()) {
        continue;
      }
      int ordinal = 0;
      Map<String, Integer> keyed = null; // how often each key came before, also out of view
      for (El item : extras) {
        if (item == null) {
          throw new IllegalStateException(
              "The lineEnds function returned a null element for line " + line);
        }
        // By its own key and how often it came before on its line, else its place on the line.
        Object part = ordinal;
        if (item.key != null) {
          keyed = keyed == null ? new HashMap<>() : keyed;
          int before = keyed.merge(item.key, 1, Integer::sum) - 1;
          part = before == 0 ? item.key : List.of(item.key, before);
        }
        FieldPart partKey = new FieldPart(PartKind.LINE_END, List.of(line, part));
        El placed = kept.get(partKey);
        if (placed != null) {
          item = placed;
        } else {
          item.fieldPartKey = partKey;
          if (item.key == null) {
            item.key(ordinal == 0 ? "end-" + line : "end-" + line + "-" + ordinal);
          }
          item.keepFocus();
          scaleText(item, scale, text.systemFonts(), resolvedLineHeight);
          Flex.layout(item, Float.POSITIVE_INFINITY, rowHeight);
        }
        ordinal++;
        float itemX = cursor + FOLD_GAP + item.margin(Side.LEFT);
        float itemY = rowTop + (rowHeight - item.height) / 2f;
        cursor = itemX + item.width + item.margin(Side.RIGHT);
        if (itemX >= contentWidth || itemX + item.width <= 0) {
          continue; // out of view; one partly in view is cut off at the content box
        }
        item.place(
            contentLeft + itemX + (float) scrollX,
            contentTop + itemY + (float) scrollY,
            item.width,
            item.height);
        built.add(item);
        clipped.add(item);
      }
    }
    // Placed again for a look: where they were among the children, which Tab follows.
    int at = kept.isEmpty() || field.lineEndElements.isEmpty()
        ? -1
        : children.indexOf(field.lineEndElements.getFirst());
    if (!field.lineEndElements.isEmpty()) {
      children.removeAll(field.lineEndElements);
    }
    field.lineEndElements = List.copyOf(built);
    children.addAll(at < 0 ? children.size() : at, field.lineEndElements);
    text.lineEndElements(List.copyOf(clipped));
    field.foldsExtent = maxRight;
    measureScroll();
    setScroll(scrollX, scrollY);
    return true;
  }

  /**
   * For {@link Editor}, once the rows and blocks of a multi-line field are laid out and scrolled:
   * places the built elements of the blocks in view (and the layout margin) at their vertical
   * bands, spanning the field's content width.
   *
   * @return whether the block elements were updated
   */
  boolean placeBlocks() {
    if (field == null || field.blocks.isEmpty() || !text.laidOut()) {
      if (field != null && !field.blockElements.isEmpty()) {
        children.removeAll(field.blockElements);
        field.blockElements = List.of();
        field.blocksShown = null;
      }
      return false;
    }
    List<Text.PlacedBlock> placed = text.placedBlocks();
    float scale = text.scale();
    float viewX = text.wraps() ? 0 : text.viewX();
    float viewY = text.viewY();
    List<Object> shown = List.of(placed, scrollX, scrollY, viewX, viewY, width, height);
    if (shown.equals(field.blocksShown)) {
      return false;
    }
    field.blocksShown = shown;
    field.partsBuilt = true;
    float contentLeft = border(Side.LEFT) + padding(Side.LEFT);
    float contentTop = border(Side.TOP) + padding(Side.TOP);
    float contentWidth = text.contentWidth() / scale;
    List<El> elements = new ArrayList<>(placed.size());
    for (Text.PlacedBlock pb : placed) {
      El blockEl = pb.element();
      if (blockEl == null) {
        continue;
      }
      float blockX = contentLeft - viewX / scale + (float) scrollX;
      float blockY = (float) ((pb.top() - viewY) / scale);
      float blockW = text.wraps() ? contentWidth : Math.max(contentWidth, blockEl.laidOutWidth());
      float blockH = (float) (pb.height() / scale);
      blockEl.place(blockX, contentTop + blockY + (float) scrollY, blockW, blockH);
      elements.add(blockEl);
    }
    if (!field.blockElements.isEmpty()) {
      children.removeAll(field.blockElements);
    }
    field.blockElements = List.copyOf(elements);
    children.addAll(field.blockElements);
    if (field.blockCache != null) {
      field.blockCache.values().retainAll(elements); // blocks scrolled out of view are built anew
    }
    setScroll(scrollX, scrollY);
    return true;
  }

  /**
   * For {@link Editor}, once the rows of a multi-line field are laid out and scrolled: places the
   * built elements of inline {@link #hints} on rows in view, vertically centered in each row.
   *
   * @return whether the hint elements were updated
   */
  boolean placeHints() {
    if (field == null || field.hints.isEmpty() || !text.laidOut()) {
      if (field != null && !field.hintElements.isEmpty()) {
        children.removeAll(field.hintElements);
        field.hintElements = List.of();
        text.hintElements(List.of());
        field.hintsShown = null;
      }
      return false;
    }
    List<Text.PlacedHint> placed = text.placedHints();
    float scale = text.scale();
    List<Object> shown = List.of(placed, scrollX, scrollY, width, height);
    if (shown.equals(field.hintsShown)) {
      return false;
    }
    field.hintsShown = shown;
    field.partsBuilt = true;
    float rowHeight = text.lineHeight() / scale;
    float contentLeft = border(Side.LEFT) + padding(Side.LEFT);
    float contentTop = border(Side.TOP) + padding(Side.TOP);
    List<El> elements = new ArrayList<>(placed.size());
    for (Text.PlacedHint ph : placed) {
      El hintEl = ph.element();
      float hx = ph.x() / scale + hintEl.margin(Side.LEFT);
      float hy = ph.top() / scale + (rowHeight - hintEl.height) / 2f;
      hintEl.place(
          contentLeft + hx + (float) scrollX,
          contentTop + hy + (float) scrollY,
          hintEl.width,
          hintEl.height);
      elements.add(hintEl);
    }
    if (!field.hintElements.isEmpty()) {
      children.removeAll(field.hintElements);
    }
    field.hintElements = List.copyOf(elements);
    children.addAll(field.hintElements);
    text.hintElements(field.hintElements);
    scrollTo(scrollX, scrollY); // gives the new children their scroll shift
    return true;
  }

  /**
   * How many lines high layout measures a {@link #multiline} field whose style gives it no height,
   * as the {@code rows} of an HTML {@code textarea}; 1 unless set. With {@link #maxLines}, this is
   * the smallest number of lines and the field grows with its rows up to {@link #maxLines} before
   * scrolling. The lines have the font's line height at the window's scale, so that exactly this
   * many fit.
   */
  public El lines(int count) {
    Text t = editableOnly("lines");
    if (count < 1) {
      throw new IllegalArgumentException("lines must be 1 or more, was " + count);
    }
    t.visibleLines(count);
    return this;
  }

  /**
   * Makes a {@link #multiline} field exactly as high as all its rows at the width layout gives it,
   * however many there are, so that it never scrolls up and down: a paragraph of a document that
   * a read-only field shows for selecting and copying. When the field also {@link #wrapText wraps},
   * layout measures it as wide as its widest row at the width it is given (with room for the
   * caret), as plain text and a wrapping single-line field do; without wrapping it measures 0 wide.
   * A field with room for all its rows draws its glyphs uncut by its box when it wraps, as text
   * does, and a {@link #readOnly} one lets Page Up and Page Down without Shift go on to the scroll
   * area around it instead of moving the caret. Unlike {@link #lines} every row is
   * shaped and laid out, so it is for texts of a few screens at most; for a field that grows up to
   * a limit and then scrolls, use {@link #lines} with {@link #maxLines}. {@code fitLines} wins over
   * {@link #maxLines} until {@link #lines} sets a count again.
   */
  public El fitLines() {
    Text t = editableOnly("fitLines");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "fitLines is for multi-line fields: call it after multiline().");
    }
    t.visibleLines(0);
    return this;
  }

  /**
   * How far down from its top something lies over a {@link #multiline} field or a {@link
   * #selectionScope}, in logical pixels, such as a find bar: as far as that reaches into its
   * content box (or the content box of the scope's vertical scroll area), it can scroll above its
   * first row, and a selection the app makes ({@link App#select}) scrolls into view below it. The
   * thumb of its {@link #scrollbars} reaches the top of the track there. 0 unless set.
   */
  public El coveredTop(float height) {
    if (height < 0 || Float.isNaN(height)) {
      throw new IllegalArgumentException("coveredTop must be 0 or more, was " + height);
    }
    if (selectionScope) {
      scopeCoveredTop = height;
      return this;
    }
    editableOnly("coveredTop");
    field.coveredTop = height;
    return this;
  }

  /** How far down from its top something lies over this selection scope; see coveredTop. */
  float scopeCoveredTop() {
    return scopeCoveredTop;
  }

  /**
   * How far a multi-line field or a covered selection scope's vertical scroll area can scroll
   * above its first row; see {@link #coveredTop}.
   */
  float coveredContent() {
    if (field != null && field.coveredTop > 0 && text != null && text.multiline()) {
      float covered = field.coveredTop - border(Side.TOP) - padding(Side.TOP);
      return Math.max(covered > 0 ? covered : 0, coverTop); // 0 also for a NaN padding
    }
    return coverTop;
  }

  /** The least vertical offset: 0, or less when this area can scroll above its first row. */
  private double minScrollY() {
    float covered = coveredContent();
    return covered > 0 ? -covered : 0; // never -0.0, which a test of the top would see
  }

  /**
   * How far apart the tab stops of a {@link #multiline} field are, in widths of a space of its font,
   * 4 unless set: a tab advances to the next stop. Also how many spaces {@link #tabIndents} indents
   * by.
   */
  public El tabSize(int spaces) {
    Text t = editableOnly("tabSize");
    if (spaces < 1) {
      throw new IllegalArgumentException("tabSize must be 1 or more, was " + spaces);
    }
    t.tabSize(spaces);
    return this;
  }

  /**
   * Makes Tab and Shift+Tab indent in a {@link #multiline} field, as in a code editor, instead of
   * moving the focus. Tab puts a tab, or with {@code spaces} spaces up to the next tab stop, in
   * place of a selection inside a line; with a selection across lines it indents each of them but
   * empty ones by a tab or {@link #tabSize} spaces. Shift+Tab takes as much from the start of the
   * caret's line or of each line of the selection. Enter starts the new line with the spaces and
   * tabs that start the caret's line, up to the caret, or with a selection those of the line where
   * it starts. The keyboard can still leave the field: Escape and then Tab move the focus, and
   * Control+M switches Tab between moving the focus and indenting until it is pressed again. A
   * read-only field leaves Tab to the focus.
   */
  public El tabIndents(boolean spaces) {
    editableOnly("tabIndents");
    field.tabIndents = spaces ? 2 : 1;
    return this;
  }

  /**
   * Tells {@code handler} where the caret and the selection of an {@link #editable} field are,
   * whenever they change: by keys, presses, edits, {@link App#edit} and {@link App#select}, and
   * when the field is new or the app's string moved them. The handler runs after the input that
   * moved them, as a handler of input does, and the next input sees the view built from what it
   * stored: the line of the caret for {@link #decorate}, the word before it for completions. After
   * an edit, it runs once the app answered the edit with the next tree, also when the selection
   * stayed, so the positions are always those of the app's string: after a refused edit those it
   * went back to. The build that answers the edit still has what the handler stored before it;
   * the view is built once more after the handler. A selection that a build applied or moved, a
   * {@link FieldState}'s or the app's answer to an edit, is told before the next input. A field in
   * a row of a virtual list that layout left out of the tree is not told; it is told once its row
   * is back, also when scrolling brought it. For the caret's rectangle, place an overlay {@link
   * #atCaret}.
   *
   * <p>On a {@link #selectionScope} it is told the scope's selection as indexes into {@link
   * App#scopeText}, as {@link App#select} takes them, whenever they change: by presses, {@link
   * App#select}, and rows inserted or removed above it, which it follows by their keys. After a
   * press elsewhere ends the selection, it still follows where the selection was until another
   * selection starts or its row or field is gone, in rows only by their keys.
   */
  public El onSelect(SelectHandler handler) {
    if (!selectionScope) {
      editableOnly("onSelect");
    }
    handlers().onSelect = handler;
    return this;
  }

  /** The handler {@link #onSelect} gave, or null; a component that adds its own calls it too. */
  public SelectHandler onSelect() {
    return handlers == null ? null : handlers.onSelect;
  }

  /**
   * Gives an {@link #editable} field a menu of its own that a secondary press, the Menu key and
   * Shift+F10 open, unless the field has an {@link #onSecondaryPress} handler, which goes first:
   * the kit's fields have one with Cut, Copy, Paste and Select all. A secondary press on the field
   * then opens it rather than a menu of an element around it. A field in the rows of a virtual
   * list ({@link #rows}) opens none and leaves the press to those around it.
   *
   * @param menu builds the menu while it is open, or null for none
   */
  public El fieldMenu(FieldMenu menu) {
    editableOnly("fieldMenu");
    field.fieldMenu = Context.captured(menu);
    return this;
  }

  /** The menu {@link #fieldMenu} gave, or null; a component that adds items builds on it. */
  public FieldMenu fieldMenu() {
    return field == null ? null : field.fieldMenu;
  }

  /**
   * Keeps the editing state of an {@link #editable} field in {@code state}, which the app holds,
   * instead of by the field's identity, so that caret, selection, scroll, undo history and the
   * mode of Tab survive the field leaving the tree, as the content of a tab that is not active
   * does. Give the same object to the same field on every build, and each field one of its own.
   *
   * @throws IllegalStateException when the view is built, if two fields of the tree have one
   */
  public El fieldState(FieldState state) {
    editableOnly("fieldState");
    field.fieldState = state;
    return this;
  }

  FieldState fieldState() {
    return field == null ? null : field.fieldState;
  }

  /**
   * Makes the keys and presses that go by words in an {@link #editable} field take the words of
   * code: a word is a run of letters, digits, marks and {@code _}, and of the characters the
   * {@link #highlight highlighter}'s language adds ({@link Highlighter#wordCharacters}), and
   * everything else separates words, so that a double press on {@code orders} in {@code shop.public.orders} selects {@code orders}
   * and Control+Backspace after it deletes only that. Other fields take words as text has them,
   * where a point between letters does not end a word.
   */
  public El codeWords() {
    editableOnly("codeWords");
    field.codeWords = true;
    return this;
  }

  boolean isCodeWords() {
    return field != null && field.codeWords;
  }

  /**
   * Makes an {@link #editable} field read-only: it takes the focus, and its text can be selected
   * and copied, but not changed. It shows a caret only while some of its text is selected.
   */
  public El readOnly() {
    editableOnly("readOnly");
    field.readOnly = true;
    return this;
  }

  /**
   * Limits the length of what typing and pasting can make of an {@link #editable} field's string,
   * in Unicode code points, as databases count the characters of a {@code varchar}. Inserted
   * text is cut at the last whole grapheme that fits; a longer string from the app stays as it
   * is.
   */
  public El maxLength(int codePoints) {
    editableOnly("maxLength");
    if (codePoints < 0) {
      throw new IllegalArgumentException("maxLength must be 0 or more, was " + codePoints);
    }
    field.maxLength = codePoints;
    return this;
  }

  /** Masks an {@link #editable} field, as {@link #masked(boolean) masked(true)} does. */
  public El masked() {
    return masked(true);
  }

  /**
   * Whether an {@link #editable} field shows its string as one mask character per grapheme, a
   * bullet unless {@link #maskCharacter} sets another, as a field for a password does. The string
   * still belongs to the app, and the caret and the selection stop between its graphemes, each at
   * its mask character. Copy and cut do nothing, paste works, and the keys and presses that go by
   * words take the whole string as one word. Nothing outside the app's state and the field's
   * editing state holds the string: the tree dump, selectors and error messages see the mask
   * ({@link Text#string}), the fonts shape only the mask, and an input method's composition is
   * masked too. The dump shows the field's state {@code password}, and the caret and the anchor
   * as positions in the mask; {@link App#inputArea} tells the window, which asks the system for
   * a password's input.
   *
   * <p>The view sets this on every build. An app shows the string by building the field also
   * {@link #revealed revealed(true)}, which keeps it out of the tree dump all the same; building it
   * with {@code masked(false)} makes it an ordinary field, whose string the dump shows. Caret,
   * selection, scroll and undo history stay either way, as they belong to the field's identity.
   */
  public El masked(boolean masked) {
    Text t = editableOnly("masked");
    if (masked && t.multiline()) {
      throw new IllegalStateException(
          "A multiline() field cannot be masked: a password has one line. Leave out multiline()"
              + " or masked().");
    }
    t.masked(masked);
    return this;
  }

  /**
   * Whether a {@link #masked} field shows its string on the screen, as a password field whose user
   * asked to see what they typed: it is drawn, hit and selected by the real string, copy and cut
   * work and the keys and presses that go by words find its words, as in an ordinary field, and
   * {@link App#inputArea} no longer asks the system for a password's input. Everything else that
   * is text about the tree sees the mask as while it is hidden: the tree dump, with the state
   * {@code revealed} after {@code password}, selectors and error messages ({@link Text#string}).
   * The view sets this on every build; caret, selection, scroll and undo history stay when it
   * changes. A field that is not masked ignores it.
   */
  public El revealed(boolean revealed) {
    editableOnly("revealed").revealed(revealed);
    return this;
  }

  /**
   * The character a {@link #masked} field shows for each grapheme of its string, {@link Text#MASK}
   * unless set: one that stands alone, such as {@code '*'}, and that the field's font has.
   */
  public El maskCharacter(int codePoint) {
    editableOnly("maskCharacter").mask(codePoint);
    return this;
  }

  /**
   * The text an {@link #editable} field shows while its string is empty, in its own color.
   *
   * @param argb the color as 0xAARRGGBB
   */
  public El placeholder(String text, int argb) {
    editableOnly("placeholder").placeholder(text, argb);
    return this;
  }

  /**
   * @param argb the color of an {@link #editable} field's caret as 0xAARRGGBB; 0 for the text's
   */
  public El caretColor(int argb) {
    editableOnly("caretColor").caretColor(argb);
    return this;
  }

  /**
   * @param argb the color drawn behind the selected text of an {@link #editable} field, as
   *     0xAARRGGBB, usually translucent; {@link Text#SELECTION} unless set
   */
  public El selectionColor(int argb) {
    editableOnly("selectionColor").selectionColor(argb);
    return this;
  }

  private Text editableOnly(String call) {
    if (field == null) {
      throw new IllegalStateException(
          call + " is for text fields, which El.text(\"...\").editable(onEdit) makes; this element"
              + " is not editable.");
    }
    return text;
  }

  private Text textOnly(String call) {
    if (text == null) {
      throw new IllegalStateException(
          call + " is for text elements, which El.text(\"...\") makes; this element has no text.");
    }
    return text;
  }

  // Behavior

  /**
   * Runs {@code handler} when the pointer is pressed and released on this element or on a
   * descendant that has no press or drag handler of its own, without moving into a {@link #onDrag
   * drag} in between. Handlers run between frames, never while a view is built, and are the only
   * place where an app changes its state.
   */
  public El onPress(Runnable handler) {
    handlers().onPress = handler == null ? null : (clicks, modifiers, x, y) -> handler.run();
    return this;
  }

  /**
   * Like {@link #onPress(Runnable)}, told the number of presses in quick succession at about the
   * same place that this one completes, as the window counts them: 1 for a single press, 2 for the
   * second of a double press, and so on. Enter and Space give 1.
   */
  public El onPress(IntConsumer handler) {
    handlers().onPress =
        handler == null ? null : (clicks, modifiers, x, y) -> handler.accept(clicks);
    return this;
  }

  /**
   * Like {@link #onPress(IntConsumer)}, also told the modifiers held when the pointer went down, as
   * a table sorts by a second column with Shift. Enter and Space give 1 and no modifiers.
   */
  public El onPress(PressHandler handler) {
    handlers().onPress =
        handler == null ? null : (clicks, modifiers, x, y) -> handler.press(clicks, modifiers);
    return this;
  }

  /**
   * Like {@link #onPress(PressHandler)}, also told where the pointer was released, in this
   * element's content; see {@link PressAtHandler}.
   */
  public El onPress(PressAtHandler handler) {
    handlers().onPress = handler;
    return this;
  }

  /**
   * Runs {@code handler} as soon as the pointer's primary button goes down on this element or on a
   * descendant without such a handler of its own, with the presses in quick succession and the
   * modifiers held, as a table selects a cell before the pointer moves on to select more. It does
   * not make this element the target of the press: {@link #onPress} and {@link #onDrag} of this
   * element or of an ancestor still get the press and the drag that follow. A press on a
   * scrollbar or outside an open overlay runs no such handler; a press in a text field runs only
   * the field's own handler, after the field places its caret, and never an ancestor's. Like
   * {@link #onPress}, it runs in {@link App#frame}.
   *
   * @param handler told how, or null for none
   */
  public El onPointerDown(PressHandler handler) {
    handlers().onPointerDown =
        handler == null ? null : (clicks, modifiers, x, y) -> handler.press(clicks, modifiers);
    return this;
  }

  /**
   * Like {@link #onPointerDown(PressHandler)}, also told where the pointer went down, in this
   * element's content; see {@link PressAtHandler}.
   */
  public El onPointerDown(PressAtHandler handler) {
    handlers().onPointerDown = handler;
    return this;
  }

  /**
   * Runs {@code handler} whenever a button of the pointer goes down anywhere in the window, while
   * this element is in the tree or in a shown overlay: on any element, in a text field, on a
   * scrollbar, inside an overlay or outside it, before the press does anything else. It takes
   * nothing from the press, which goes on as without it. A menu bar uses it to tell Alt pressed and
   * released alone from Alt held during a press, which gives it no keys. Presses that the system
   * takes, such as on a part of a frameless window's frame ({@link #windowPart}), do not reach
   * the app. Like {@link #onPress}, it runs in {@link App#frame}.
   *
   * @param handler the handler, or null for none
   */
  public El onAnyPointerDown(Runnable handler) {
    handlers().onAnyPointerDown = handler;
    return this;
  }

  /**
   * Runs {@code handler} with every key that goes down in the window, while this element is in
   * the tree or in a shown overlay, before any element's {@link #onKey} or a field sees it, and
   * taking nothing from it: also a key that the focused element takes. A menu bar uses it to tell
   * Alt pressed and released alone from Alt held with a key that something else took, as Alt+C
   * that a find bar takes. Like {@link #onKey}, it runs in {@link App#frame}.
   *
   * @param handler the handler, or null for none
   */
  public El onAnyKeyDown(Consumer<Key> handler) {
    handlers().onAnyKeyDown = handler;
    return this;
  }

  /**
   * Runs {@code handler} when a button of the pointer goes down outside this element: on no element
   * of it or inside it, also on an overlay, before the press does anything else, while this element
   * is in the tree or in a shown overlay. Like {@link #onAnyPointerDown}, it takes nothing from the
   * press, which goes on as without it: a press on another cell ends the editing of one and then
   * selects the other. An overlay attached inside this element counts as outside it. Like {@link
   * #onPress}, it runs in {@link App#frame}.
   *
   * @param handler the handler, or null for none
   */
  public El onPressOutside(Runnable handler) {
    handlers().onPressOutside = handler;
    return this;
  }

  /**
   * Runs {@code handler} when the secondary button, usually the right one, is pressed and released
   * on this element or on a descendant that has no such handler of its own, nor a {@link
   * #fieldMenu} outside the rows of a virtual list, with the position of
   * the release in logical pixels of the window: where a context menu opens ({@link #atPoint}).
   * The Menu key and Shift+F10 that no key handler takes run it too, for the focused element or
   * its nearest ancestor that has one, unless a field's menu is nearer, at the bottom left of the
   * focused element. Such a press
   * focuses as a press of the primary button does; it does not drag, select text or press.
   *
   * @param handler told where, or null for none
   */
  public El onSecondaryPress(PointHandler handler) {
    handlers().onSecondaryPress = handler;
    return this;
  }

  /**
   * Lets this element follow a drag: the pointer goes down on it, or on a descendant without a
   * press or drag handler of its own that is not a text field that takes input ({@link
   * #editable}, where the movement selects text), and moves more than {@link Drag#THRESHOLD} with
   * the button held. {@code handler} gets {@link Drag.Phase#START} then, {@link Drag.Phase#MOVE} for each
   * movement after that, also while the pointer is outside the element or the window, and {@link
   * Drag.Phase#END} when the button is released, each with the distance from where the pointer
   * went down. A drag is not a press: {@link #onPress} runs only when the pointer is released
   * before it moved that far.
   *
   * <p>All steps go to the handler that the element had when the pointer went down, so that it
   * can work from the values of that view, as in {@code onDrag(drag -> resize(width +
   * drag.dx()))}. It gets {@link Drag.Phase#CANCEL} instead of the next step when the element has
   * left the tree, and when the pointer goes down again before a release. Like {@link #onPress}, it
   * runs in {@link App#frame}; the wheel scrolls during a drag as before.
   */
  public El onDrag(Consumer<Drag> handler) {
    handlers().onDrag = handler;
    return this;
  }

  /**
   * What a drag of this element carries to the {@link #onDrop} handlers it passes over, in its own
   * window and in the others of its runner ({@link com.rethinkj.ui.window.Windows}): a note, a
   * tab, a row. Only a drag with a payload reaches drop handlers; the element needs an {@link
   * #onDrag} handler, which gets each step after the drop handlers have.
   *
   * @param payload the payload, or null for none
   */
  public El dragPayload(Object payload) {
    handlers().dragPayload = payload;
    return this;
  }

  /**
   * Lets this element take what drags carry ({@link #dragPayload}): while the pointer of such a
   * drag is over it or inside it, and no element inside it has a drop handler of its own there,
   * {@code handler} gets {@link Drop.Phase#OVER} on each step, {@link Drop.Phase#LEAVE} when the
   * pointer leaves it or the drag ends elsewhere, and {@link Drop.Phase#DROP} when the button is
   * released over it. The same handler serves a drag within its window, as to reorder rows or
   * tabs, and one that comes from another window of the runner. It runs in the frame of the drag's
   * own window, before the drag's handler; its app builds its view again in the same pass.
   */
  public El onDrop(Consumer<Drop> handler) {
    handlers().onDrop = handler;
    return this;
  }

  /**
   * Lets this element take what another application drags onto the window: files from a file
   * manager, or text that no text field under the pointer takes (a field takes dropped text as a
   * paste at the point). While such a drag is over the element or inside it, and no element inside
   * it has a handler of its own there, {@code handler} gets {@link Drop.Phase#OVER} on each move,
   * the time to show that the element takes it; {@link Drop.Phase#LEAVE} when it moves on, leaves
   * the window or is given up; and {@link Drop.Phase#DROP} at the release, with the files as a
   * {@code List<java.nio.file.Path>} or the text as a {@code String} for its payload. While it is
   * over, the payload is null: the systems say what is dropped only at the release. The drops of
   * {@link #dragPayload} within the app go to {@link #onDrop} instead.
   */
  public El onFileDrop(Consumer<Drop> handler) {
    handlers().onFileDrop = handler;
    return this;
  }

  /**
   * Lets this element take the keyboard focus, which {@link App} keeps across rebuilds: Tab and
   * Shift+Tab move it through the focusable elements in tree order, and a pointer press on this
   * element or inside it gives it focus. The focused element gets text ({@link #onText}) and keys
   * first ({@link #onKey}); with an {@link #onPress} handler, Enter and Space press it.
   */
  public El focusable() {
    this.focusable = true;
    return this;
  }

  /**
   * Sets the color of the ring drawn around this element while it has the focus from the
   * keyboard or the app, as 0xAARRGGBB; 0 draws none. An element that sets none gets the app's
   * ({@link App#focusRing}). A view that draws parts of itself in different themes gives each of
   * its focusable elements the ring of its theme here.
   */
  public El focusRing(int argb) {
    this.focusRing = argb;
    return this;
  }

  /**
   * Draws the focus ring this many pixels away from the border box, so that what lies behind the
   * element shows between the two: a ring in the accent beside a button filled with the accent.
   * The default is 0, the ring against the box. Where a clip or the window's edge would cut the
   * ring, it lies inside the box instead, with a strip this wide in the color behind the element
   * just inside the ring, where the element's content leaves room and that color is known.
   */
  public El focusRingGap(float pixels) {
    this.focusRingGap = pixels;
    return this;
  }

  /**
   * Gives {@code handler} the keys that go down while this element or one below it has the focus,
   * repeats included, before its ancestors get them. The handler returns whether it handled the
   * key; if it did, nobody else sees it. Without focus, keys go to the root element only. While a
   * focused field has an input method's composition in progress, the field takes every key except
   * Tab before any key handler runs. Enter and Space press a focused element with an {@link
   * #onPress} handler after its own key handler and before its ancestors', and Tab and Shift+Tab
   * that nobody handles move the focus. Handlers run in {@link App#frame}, like {@link #onPress}.
   */
  public El onKey(Predicate<Key> handler) {
    handlers().onKey = handler;
    return this;
  }

  /**
   * Adds a key handler that is asked before the one this element has: the keys it does not handle
   * go on to that one, as before. A view that adds a key, such as Control+Enter, to a component
   * that handles keys of its own, such as a code editor, keeps them with it; {@link #onKey}
   * replaces them.
   *
   * @param handler returns whether it handled the key; not null
   */
  public El onKeyFirst(Predicate<Key> handler) {
    Objects.requireNonNull(handler, "handler");
    Predicate<Key> own = onKey();
    handlers().onKey = own == null ? handler : handler.or(own);
    return this;
  }

  /**
   * Gives {@code handler} the wheel turned over this element or one below it before it scrolls,
   * as a diagram zooms around the pointer with Control+wheel and pans without it. The handler
   * returns whether it took the wheel; if it did, nothing scrolls, and if not, the wheel goes on to
   * the handlers around it and scrolls as without them. A scroll area inside this element that can
   * move with the wheel takes it first, without asking the handler; this element's own scrolling
   * comes after its handler. Like {@link #onPress}, it runs in {@link App#frame}.
   *
   * @param handler told how, or null for none
   */
  public El onWheel(WheelHandler handler) {
    handlers().onWheel = handler;
    return this;
  }

  /**
   * Tells {@code handler} that the user moved the view of this scroll area or of a scroll area
   * inside it: by the wheel, by a scrollbar (dragging its thumb, pressing its track or a tick), by
   * PageUp, PageDown, Home or End outside a text field, by Home, End or an arrow key without Shift in
   * a read-only multi-line one, or by a key after which an area shows something else it keeps in view ({@link
   * #revealRow}, {@link #reveal}, the focus). A text field's own keys move its caret, which {@link
   * #onSelect} tells. Not when layout, a rebuild without such
   * input, {@link App#scrollTo}, {@link App#scrollToRow} or a {@link ScrollState} moves it, nor
   * when the input moved nothing, as a wheel at the end. As a {@code DataGrid}'s rows are an area
   * inside the element {@code dataGrid} returns, a handler on that element hears them. Like {@link
   * #onPress}, it runs in {@link App#frame}, after the input that moved an area, the innermost
   * handler first, and the view is built again from what it changes.
   *
   * @param handler the handler, or null for none
   */
  public El onUserScroll(Runnable handler) {
    handlers().onUserScroll = handler;
    return this;
  }

  /** Like {@link #onKey}, for keys that are released. Released keys do nothing by default. */
  public El onKeyUp(Predicate<Key> handler) {
    handlers().onKeyUp = handler;
    return this;
  }

  /**
   * Gives {@code handler} the text typed or composed with an input method while this element has
   * the focus. Text goes to the focused element only, not to its ancestors.
   */
  public El onText(Consumer<String> handler) {
    handlers().onText = handler;
    return this;
  }

  /**
   * Gives {@code handler} the text typed so far while this element has the focus, for a list or a
   * tree that goes to the item whose name starts with it: each character typed within {@link
   * App#TYPE_AHEAD} seconds of the app's clock after the one before adds to the same text, and a
   * longer pause, Escape or a move of the focus starts a new one. A space with nothing typed
   * before it is not text. It gets the characters of keys, but no input method composes for it
   * ({@link App#inputArea}). An {@link #onText} handler of the same element takes the text in its
   * place.
   */
  public El onTypeAhead(Consumer<String> handler) {
    handlers().onTypeAhead = handler;
    return this;
  }

  /**
   * Runs {@code handler} when the keyboard focus moves from outside this element into it or into an
   * element below it, whether by a pointer press, Tab, {@link App#focus}, {@link #autofocus} or an
   * overlay opening or closing. Moves of the focus within this element do not run it again; once
   * the focus leaves this element and comes back, it runs again. Like {@link #onPress}, it runs in
   * {@link App#frame}, and the view is built again from what it changes.
   *
   * @param handler the handler, or null for none
   */
  public El onFocusIn(Runnable handler) {
    handlers().onFocusIn = handler;
    return this;
  }

  /**
   * Lets this element spring to new values: when a rebuild of the view gives an element that was
   * already there a new rectangle, fill, border color, radius, opacity, text color or {@link #arc}
   * sweep, {@link App} moves it there with {@code spring} instead of at once. Children are placed
   * relative to their parent and move with it.
   *
   * <p>An element that appears after the app's first frame fades in from opacity 0, in the place
   * layout gives it; it takes the focus from that frame on, and presses once it shows. One with a
   * {@link #key} that a rebuild leaves out fades out where it was, with everything below it, and
   * is then removed. Until then it stays in the tree, marked {@link #isExiting}, and takes no part
   * in layout or input. If it comes back before, it goes on from where it is.
   *
   * @param spring how to move, or null to change at once
   */
  public El motion(Spring spring) {
    this.motion = spring;
    return this;
  }

  /**
   * Marks this element as a part of the window's frame, for a window without a system frame that
   * draws its own title bar: a drag area, or the minimize, maximize or close button. Its
   * descendants belong to the same part unless they are marked themselves or have a press handler.
   *
   * @param part the part, or null for none
   */
  public El windowPart(WindowPart part) {
    this.windowPart = part;
    return this;
  }

  // Looks of the moment

  /**
   * Changes how this element looks while the pointer is over it or over an element inside it, as
   * CSS's {@code :hover} does: {@code el().fill(GRAY).whenHovered(element ->
   * element.fill(LIGHT_GRAY))}. After each rebuild of the view and before layout, {@link App}
   * calls {@code look} with the element. With {@link #motion} it springs to the changed values and
   * back like after any other change.
   *
   * <p>A look may call what changes the element's appearance ({@link #fill}, {@link #radius},
   * {@link #border}, {@link #opacity}, {@link #cursor}) and its layout properties (size, spacing,
   * flow and {@link #style}). It must leave the children, the key, the handlers and {@link
   * #focusable} as the view built them; the frame in which it changes them throws an {@link
   * IllegalStateException}. When more than one look applies, the hovered one goes first, then
   * {@link #whenFocused}, {@link #whenFocusWithin} and then {@link #whenPressed}, so that the last
   * one wins.
   *
   * <p>Only elements with a look cause a rebuild when the pointer reaches or leaves them: moving
   * the pointer over plain elements rebuilds nothing. In the rows of a virtual list ({@link #rows})
   * the look moves from row to row as the list builds rows anew, without a rebuild of the view.
   *
   * @param look changes the element, or null for none
   */
  public El whenHovered(Consumer<El> look) {
    this.hoverLook = Context.captured(look);
    return this;
  }

  /**
   * Changes how this element looks while it has the keyboard focus, as CSS's {@code :focus} does:
   * also when a press gave it the focus, which draws no ring. See {@link #whenHovered} for what a
   * look may change.
   *
   * @param look changes the element, or null for none
   */
  public El whenFocused(Consumer<El> look) {
    this.focusLook = Context.captured(look);
    return this;
  }

  /**
   * Changes how this element looks while it or an element inside it has the keyboard focus, as
   * CSS's {@code :focus-within} does: a field's frame with a button inside keeps its focused look
   * while the button has the focus, and a row can stand out while one of its controls has it. It
   * comes after {@link #whenFocused} and before {@link #whenPressed}. See {@link #whenHovered} for
   * what a look may change.
   *
   * @param look changes the element, or null for none
   */
  public El whenFocusWithin(Consumer<El> look) {
    this.focusWithinLook = Context.captured(look);
    return this;
  }

  /**
   * Changes how this element looks while it is pressed: from a pointer down that goes to it, as
   * for {@link #onPress}, to the next pointer up, wherever that is, as CSS's {@code :active} does.
   * Only an element with a press or drag handler is pressed, and a text field that takes input
   * ({@link #editable}) while a press selects in it, which the elements around it do not count as
   * a press of theirs. Enter and Space show this look too when they press it while it has the
   * focus: from the key going down until it is up and {@link App#KEY_PRESS} seconds have passed
   * since it went down, and not at all when it goes down and up in the same frame. See {@link
   * #whenHovered} for what a look may change.
   *
   * @param look changes the element, or null for none
   */
  public El whenPressed(Consumer<El> look) {
    this.pressLook = Context.captured(look);
    return this;
  }

  /**
   * Changes how this element looks once the pointer has rested over it, or over elements inside
   * it, for {@code seconds} of the app's clock, until the pointer leaves it or presses: a tooltip,
   * which the look attaches as an {@link #overlay}. A press keeps the look away until the pointer
   * has left the element and come back. Once such a look has shown overlays and gone because the
   * pointer left, the look of an element the pointer comes to within that element's {@code
   * seconds} shows at once, as the tooltips along a toolbar do; a press ends that. {@link
   * App#nextChange} says when the look is due, so an
   * idle window wakes for it and presents nothing while it waits. Besides what {@link
   * #whenHovered} may change, such a look may attach overlays; it comes after the hover look.
   *
   * @param look changes the element, or null for none
   */
  public El whenHoveredFor(double seconds, Consumer<El> look) {
    this.restDelay = delay(seconds);
    this.restLook = Context.captured(look);
    return this;
  }

  /**
   * Changes how this element looks once it has had the keyboard focus with a ring for {@code
   * seconds} of the app's clock, as CSS's {@code :focus-visible} after a delay: the keyboard or the
   * app moved the focus there, not a press. The look goes when the focus leaves or a key goes down
   * that does not move the focus, and comes back only when the focus comes back. A tooltip uses
   * it with the same look as {@link #whenHoveredFor}, so that the keyboard shows it too; both
   * looks apply when both are due, the hovered one first. {@link App#nextChange} says when it is
   * due, as for {@link #whenHoveredFor}. Not in the rows of a virtual list.
   *
   * @param look changes the element, or null for none
   */
  public El whenFocusVisibleFor(double seconds, Consumer<El> look) {
    this.focusRestDelay = delay(seconds);
    this.focusRestLook = Context.captured(look);
    return this;
  }

  /**
   * Changes how this element looks in every frame while it is shown, by where the app's clock
   * stands in a period of {@code seconds}: a bar that runs while the app does not know how long
   * something takes, or the dots of a spinner. The look gets this element and the phase, from 0
   * at the start of the period towards 1, counted from the frame in which the element got this
   * cycle with its identity, so that rebuilding the view and scrolling a list go on where it was;
   * {@link Headless#render} without an {@link App} shows phase 0.
   *
   * <p>The look runs after layout and after motion has written the values of the moment, on the
   * element as they left it, so that it needs no layout: it may move and size the element within
   * its parent with {@link #place} (from the rectangle it finds, which layout gave it: a piece
   * placed over its whole track draws a part of it), and change {@link #fill} and {@link
   * #opacity}. Nothing else; it must not keep what it finds from one frame to the next. Each
   * frame finds the element as layout and motion left it again.
   *
   * <p>While an element with a cycle is shown, {@link App#isMoving} is true, {@link App#frame}
   * returns true and {@link App#nextChange} is the current time, so a window draws a frame per
   * refresh of its display and no more; an element that is clipped away entirely, outside the
   * window, under an ancestor at opacity 0 or in a row of a virtual list that is not built costs
   * nothing, and a window without one stays idle. A frame with cycles walks the tree once more,
   * without allocating, and runs their looks. When a rebuild takes the cycle away from an element
   * with {@link #motion} that keeps its identity, it springs from what the look showed last to
   * where layout puts it, so a bar that learns its value does not jump.
   *
   * @param seconds the length of the period, more than 0
   * @param look changes the element by the phase, or null for none
   */
  public El cycle(double seconds, ObjDoubleConsumer<El> look) {
    if (!(seconds > 0 && seconds < Double.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "a cycle needs a period of more than 0 seconds, was " + seconds);
    }
    this.cycleSeconds = seconds;
    this.cycle = Context.captured(look);
    return this;
  }

  /** The width layout or motion gave this element, not the one a {@link #cycle} shows. */
  float laidOutWidth() {
    return cycleBased ? baseWidth : width;
  }

  /** The height layout or motion gave this element, not the one a {@link #cycle} shows. */
  float laidOutHeight() {
    return cycleBased ? baseHeight : height;
  }

  /** The look of {@link #cycle}, or null. */
  ObjDoubleConsumer<El> cycle() {
    return cycle;
  }

  /**
   * Runs the looks of {@link #cycle} at {@code time} in a tree that layout has placed, on the
   * elements that are shown, and returns whether there was any. {@code shownOnly} false runs them
   * all, as {@link Headless#render} does at time 0.
   */
  static boolean cycles(El root, double time, float width, float height, boolean shownOnly) {
    float far = Float.POSITIVE_INFINITY;
    float r = shownOnly ? width : far;
    float b = shownOnly ? height : far;
    float l = shownOnly ? 0 : -far;
    boolean any = root.cycles(time, 0, 0, l, l, r, b);
    for (El overlay : root.shownOverlays()) { // in window coordinates
      any |= overlay.cycles(time, 0, 0, l, l, r, b);
    }
    return any;
  }

  private boolean cycles(
      double time,
      float parentX,
      float parentY,
      float clipLeft,
      float clipTop,
      float clipRight,
      float clipBottom) {
    if (!placed) {
      return false;
    }
    boolean any = false;
    if (cycle != null) {
      if (cycleBased) { // back to what layout and motion gave it
        x = baseX;
        y = baseY;
        width = baseWidth;
        height = baseHeight;
        opacity = baseOpacity;
        targetOpacity = baseTargetOpacity;
        fill = baseFill;
      } else {
        baseX = x;
        baseY = y;
        baseWidth = width;
        baseHeight = height;
        baseOpacity = opacity;
        baseTargetOpacity = targetOpacity;
        baseFill = fill;
        cycleBased = true;
      }
      float left = parentX + x();
      float top = parentY + y();
      if (left < clipRight
          && left + width > clipLeft
          && top < clipBottom
          && top + height > clipTop) {
        double turns = (time - cycleSince) / cycleSeconds;
        cycle.accept(this, Math.clamp(turns - Math.floor(turns), 0.0, 1.0));
        cycleBased = true; // its own place() is not a new layout
        any = true;
      }
    }
    if (opacity == 0) {
      return any;
    }
    float left = parentX + x();
    float top = parentY + y();
    if (style.overflowX != Style.Overflow.VISIBLE || style.overflowY != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(left, clipLeft);
      clipTop = Math.max(top, clipTop);
      clipRight = Math.min(left + width, clipRight);
      clipBottom = Math.min(top + height, clipBottom);
    }
    for (El child : children) {
      any |= child.cycles(time, left, top, clipLeft, clipTop, clipRight, clipBottom);
    }
    return any;
  }

  /**
   * Runs {@code handler} once the pointer has rested over this element, or over elements inside
   * it, for {@code seconds} of the app's clock; once per stay, and not after a press until the
   * pointer has left and come back. With 0 it runs in the frame that finds the pointer there. Like
   * {@link #onPress}, it runs in {@link App#frame}, which then builds the view again; a menu
   * follows the pointer with it and opens a submenu after a delay.
   *
   * @param handler runs once the pointer has stayed, or null for none
   */
  public El onHoverFor(double seconds, Runnable handler) {
    handlers().hoverDelay = delay(seconds);
    handlers().onHoverFor = handler;
    return this;
  }

  /**
   * Runs {@code handler} with {@code true} when the pointer enters this element (or an element
   * inside it) and with {@code false} when it leaves, in {@link App#frame}, which then builds the
   * view again.
   *
   * @param handler told whether the pointer is over this element, or null for none
   */
  public El onHover(Consumer<Boolean> handler) {
    handlers().onHover = handler;
    return this;
  }

  /**
   * The UTF-16 offset into the string of a laid-out {@link #multiline} field under the point
   * {@code (x, y)}, in logical pixels from the top left of this element's border box; -1 before
   * layout, outside the field's content box (including its gutter, padding and border), past the
   * end of a line's text, on an empty line, over a {@link #blocks line block}, over an inline
   * {@link #hints hint}, or while showing its placeholder. Accounts for scroll, scale, folds and
   * active IME composition.
   *
   * @throws IllegalStateException if this is not a multi-line field
   */
  public int textPositionAt(float x, float y) {
    Text t = editableOnly("textPositionAt");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "textPositionAt is for multi-line fields: call it after multiline().");
    }
    if (!t.laidOut()) {
      return -1;
    }
    float contentLeft = border(Side.LEFT) + padding(Side.LEFT);
    float contentTop = border(Side.TOP) + padding(Side.TOP);
    float contentRight = width - border(Side.RIGHT) - padding(Side.RIGHT);
    float contentBottom = height - border(Side.BOTTOM) - padding(Side.BOTTOM);
    if (x < contentLeft || x >= contentRight || y < contentTop || y >= contentBottom) {
      return -1;
    }
    float tx = (x - contentLeft) * t.scale() + t.scroll();
    float ty = (y - contentTop) * t.scale() + t.scrollY();
    int shown = t.positionAt(tx, ty);
    if (shown < 0) {
      return -1;
    }
    return field.editor != null ? field.editor.fromShown(shown) : shown;
  }

  /**
   * Runs {@code handler} in {@link App#frame} whenever the character position under the pointer in
   * this {@link #multiline} field changes, or the held modifiers change while the pointer is over
   * a character; with -1 when the pointer moves off the text (past a line's end, onto a block, the
   * gutter, a fold mark, a scrollbar, an overlay, or outside the field). While a press or a drag
   * holds the pointer, as in selecting, it waits and reports where the pointer is after release.
   *
   * @param handler told the position and held modifiers, or null for none
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El onTextPointerMove(TextPointerHandler handler) {
    Text t = editableOnly("onTextPointerMove");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "onTextPointerMove is for multi-line fields: call it after multiline().");
    }
    handlers().onTextPointerMove = handler;
    return this;
  }

  /**
   * Runs {@code handler} once the pointer has rested on one character of this {@link #multiline}
   * field for {@code seconds} of the app's clock, with that character's UTF-16 offset and the
   * modifiers held: once per stay at that character, and not after a press until the pointer moves
   * to another character or leaves and comes back. {@link App#nextChange} reports when it is due,
   * so an idle window wakes for it without drawing frames meanwhile.
   *
   * @param handler told the position and held modifiers, or null for none
   * @throws IllegalStateException if this is not a multi-line field
   */
  public El onTextHoverFor(double seconds, TextPointerHandler handler) {
    Text t = editableOnly("onTextHoverFor");
    if (!t.multiline()) {
      throw new IllegalStateException(
          "onTextHoverFor is for multi-line fields: call it after multiline().");
    }
    handlers().textHoverDelay = delay(seconds);
    handlers().onTextHoverFor = handler;
    return this;
  }

  private static double delay(double seconds) {
    if (!(seconds >= 0 && seconds < Double.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "seconds must be 0 or a positive finite number, was " + seconds);
    }
    return seconds;
  }

  // Overlays

  /**
   * Shows {@code overlay} above everything else, beside this element, while this element is in
   * the tree and shows: after the whole tree, outside the clips of this element's ancestors. A
   * component returns its anchor with what it opens attached, and the app's state says whether it
   * is open: {@code button.overlay(state.menuOpen ? menu : null)}. The overlay is an ordinary
   * tree, with layout, text, scrolling, motion and presence; it gets its own clips, takes input
   * before the tree below it, and keys that it does not take go on to this element and its
   * ancestors. Its identity comes from this element and its key or index among this element's
   * overlays, so that one with a key and {@link #motion} fades in when it opens and out when it
   * closes.
   *
   * <p>After layout, {@link App} lays the overlay out on its own, at most as large as the window
   * less its {@link #margin margins}, and places its margin box as {@link #side}, {@link #atPoint},
   * {@link #centerInWindow} or {@link #atWindowEdge} say, beside this element's rectangle as shown,
   * moved by the scroll offsets above it; its margins stay outside its box, so they are neither
   * drawn nor hit, and it follows the element from frame to frame. It shows only while this element
   * does: not while this element is clipped away entirely, invisible or leaving. Overlays are drawn
   * in the order they opened, a submenu above its menu, each over everything opened before.
   *
   * <p>A press outside the overlays that are open calls the {@link #onDismiss} handlers of those
   * above the point, the newest first, and goes no further: a menu closes, and what lies under the
   * press does nothing. A press inside an overlay below them goes on there. The wheel outside a
   * non-modal overlay calls its dismiss handler and scrolls what is under the pointer. A {@link
   * #modal} overlay takes all pointer input, the focus and the keys, as long as it is open. An
   * overlay that opens moves the focus to its first focusable element without a ring, and when it
   * closes, the focus goes back to where it was before, if that element is still there.
   *
   * @param overlay the root of the overlay, or null to add none
   */
  public El overlay(El overlay) {
    if (overlay != null) {
      if (overlays == null) {
        overlays = new ArrayList<>(1);
      }
      overlays.add(overlay);
      overlay.layer();
    }
    return this;
  }

  /**
   * Places this overlay beside its anchor: on {@code side} of the anchor's rectangle, {@code gap}
   * pixels away, aligned with it along that side as {@code align} says: {@code START}, {@code
   * CENTER} or {@code END}, or {@code STRETCH} for {@code START} and at least as wide as the anchor
   * (as tall, beside it). Where it does not fit into the window there, it goes to the other side,
   * and along the side to the other end; where it fits neither way, it moves into the window as
   * far as it needs. With a negative {@code gap}, placing the overlay inside its anchor, it does
   * not flip to the other side when it does not fit, and moves into the window instead. In a {@link
   * #modal} overlay, such as a dialog, it keeps inside that overlay
   * along the side as well: at {@code START} or {@code END} it takes the end that fits into it, else it moves into it as far as
   * the window lets it, over its middle where it is wider. The default is below the anchor at its
   * start, 0 away.
   */
  public El side(Side side, Style.Align align, float gap) {
    Layer l = layer();
    l.side = Objects.requireNonNull(side, "side");
    l.align = Objects.requireNonNull(align, "align");
    l.gap = gap;
    return this;
  }

  /**
   * Places this overlay at a point of the window in logical pixels, such as where the pointer was
   * pressed for a context menu, instead of beside its anchor's rectangle: as {@link #side} says,
   * with the point as a rectangle of no size.
   */
  public El atPoint(float x, float y) {
    Layer l = layer();
    l.pointX = x;
    l.pointY = y;
    return this;
  }

  /**
   * Places this overlay beside the caret of its anchor, a text field ({@link #editable}), instead
   * of beside the anchor's rectangle: as {@link #side} says, with the caret, a line high, as the
   * rectangle; below it and starting at it by default, as a list of completions. It follows the
   * caret as the field scrolls and the caret moves, also without a rebuild of the view. Usually
   * with {@link #keepFocus}, so that the field keeps the keys; its {@link #onKey} sees them first
   * and can take Up, Down, Enter and Escape for the overlay.
   *
   * <pre>{@code
   * El editor = El.text(state.sql).editable(value -> state.sql = value).multiline()
   *     .onSelect((caret, anchor) -> state.caret = caret);
   * editor.overlay(state.completing ? completions(state).atCaret().keepFocus() : null);
   * }</pre>
   */
  public El atCaret() {
    layer().atCaret = true;
    return this;
  }

  /**
   * Places this overlay beside characters {@code from} to {@code to} of its anchor, a {@link
   * #multiline} text field, instead of beside the anchor's rectangle: as {@link #side} says, with
   * the first visible row of the range as the rectangle; below it and starting at it by default,
   * as hover documentation or a diagnostic tooltip. It follows the range as the field scrolls,
   * also without a rebuild of the view, and is not shown while the range is scrolled out of view
   * or hidden in a fold or replaced block.
   *
   * @param from the first position, a UTF-16 offset into the field's string from 0
   * @param to the exclusive end position, at least {@code from}
   * @throws IllegalArgumentException if {@code from < 0} or {@code to < from}
   */
  public El atRange(int from, int to) {
    if (from < 0 || to < from) {
      throw new IllegalArgumentException("range " + from + " to " + to + " is not a range");
    }
    Layer l = layer();
    l.rangeFrom = from;
    l.rangeTo = to;
    return this;
  }

  /**
   * Shows this overlay only while a text of its anchor, or of an element inside the anchor, ends
   * in an ellipsis because it did not fit ({@link #ellipsis}, {@link Text#shortened}): a tooltip
   * with the whole text of a label that was cut. Layout decides that after the looks have run,
   * so a look that attaches the overlay cannot know it.
   */
  public El whenShortened() {
    layer().whenShortened = true;
    return this;
  }

  /** Places this overlay in the middle of the window, as a dialog. */
  public El centerInWindow() {
    layer().centered = true;
    return this;
  }

  /**
   * Places this overlay inside the window against one of its edges, {@code gap} pixels away from
   * it, and along that edge as {@code align} says: {@code START}, {@code CENTER} or {@code END},
   * the same distance from the corners (a notification at the bottom in the middle), or {@code
   * STRETCH} to run the whole length of the edge less {@code gap} at both ends (a drawer along a
   * side). Across that edge it is at most as large as the window less the gap, so that a shorter
   * window shrinks what can shrink in it.
   */
  public El atWindowEdge(Side edge, Style.Align align, float gap) {
    Layer l = layer();
    l.edge = Objects.requireNonNull(edge, "edge");
    l.align = Objects.requireNonNull(align, "align");
    l.gap = gap;
    return this;
  }

  /**
   * Runs {@code handler} once this element has been shown for {@code seconds} of the app's clock,
   * once per appearance: a notification, or a toast inside a stack overlay, closes itself after a
   * while. Like {@link #onHoverFor}, it runs in {@link App#frame}, which then builds the view
   * again; {@link App#nextChange} says when it is due, so an idle window waits for it without
   * drawing frames. The time starts when the element is first shown, also where a delayed look
   * attaches an overlay ({@link #whenHoveredFor}, {@link #whenFocusVisibleFor}) and in a row of a
   * virtual list once the list builds the row, and ends when it leaves, is exiting, is made
   * invisible by the view with an element above it, loses its handler or its row leaves the rows
   * that exist. With {@link #pauseShownOnHover}, the time stops while the pointer is over the
   * element.
   *
   * @param handler runs once the time has come, or null for none
   */
  public El onShownFor(double seconds, Runnable handler) {
    handlers().shownDelay = delay(seconds);
    handlers().onShownFor = handler;
    return this;
  }

  /**
   * Pauses this element's {@link #onShownFor} timer while the pointer is over it or over an
   * element inside it, so that a notification does not close while the user reads it or aims at
   * its action. Once the pointer leaves, the remaining time goes on, and {@link App#nextChange}
   * reflects both states without drawing frames meanwhile.
   */
  public El pauseShownOnHover() {
    handlers().shownPauseOnHover = true;
    return this;
  }

  /** The handler of {@link #onShownFor}, or null. */
  Runnable onShownFor() {
    return handlers == null ? null : handlers.onShownFor;
  }

  double shownDelay() {
    return handlers == null ? 0 : handlers.shownDelay;
  }

  boolean pausesShownOnHover() {
    return handlers != null && handlers.shownPauseOnHover;
  }

  /**
   * Makes this overlay modal while it is open: input outside it reaches nothing below it. A press
   * there calls its {@link #onDismiss} handler and nothing else, the wheel there does nothing, the
   * focus stays inside it, Tab and Shift+Tab go around inside it, and keys go up from the focused
   * element as far as this overlay. Overlays that open above it work as usual.
   */
  public El modal() {
    layer().modal = true;
    return this;
  }

  /**
   * Fills the whole window under this overlay, over everything drawn before it, as under a modal
   * dialog. It fades with the overlay's opacity.
   *
   * @param argb the color as 0xAARRGGBB, usually translucent; 0, the default, for none
   */
  public El scrim(int argb) {
    layer().scrim = argb;
    return this;
  }

  /**
   * Tells the app that this overlay should close: a press outside it, or, when it is not modal,
   * the wheel outside it and Tab or Shift+Tab moving the focus out of it. The app closes it by changing its state, so that the next view leaves
   * it out; nothing closes by itself. Escape is the app's too, with an {@link #onKey} handler.
   *
   * @param handler runs in {@link App#frame}, or null for none
   */
  public El onDismiss(Runnable handler) {
    layer().onDismiss = handler;
    return this;
  }

  /**
   * Puts this overlay, or this element and what is inside it, into a group for dismissing: a press
   * or the wheel on an element of the group is not outside the overlays of the same group. They
   * are not dismissed, and the press goes on to the element as if it had landed in an overlay below
   * them, whose handler decides what happens. A menu bar puts its titles and their menus into one
   * group, so that a press on another title switches menus with one click and a press on the open
   * menu's title closes it. Groups are equal by {@link Object#equals}; the app's state object of
   * the component is a good one.
   *
   * @param group the group, or null for none
   */
  public El dismissGroup(Object group) {
    this.dismissGroup = group;
    return this;
  }

  /**
   * Makes this element leave the keyboard focus where it is. A press on it or inside it does not
   * move the focus, also not to a focusable element inside it, except into a text field; as an
   * overlay it opens without taking the focus, and closes without moving it. Tab still reaches the
   * focusable elements inside it in the usual order: an overlay's right after its anchor. A menu
   * bar's titles keep the focus in the editor whose menus they open, and a notification with an
   * Undo button opens without taking the focus from what the user types.
   *
   * <p>On a {@link #focusable} element it makes one that only the keyboard focuses: Tab reaches
   * it, and Enter and Space press it there, but a press or a drag on it leaves the focus in the
   * editor or grid it acts on. The kit's buttons, toggles, checkboxes, selects, tab bar and split
   * dividers are made so.
   */
  public El keepFocus() {
    this.keepsFocus = true;
    return this;
  }

  /**
   * Makes this element, everything inside it and the overlays attached inside it content that
   * only shows, such as a tooltip's with links or a scroll area: presses, the wheel and the pointer
   * go to what lies beneath them, neither Tab nor a press focuses anything in them, also not as
   * an overlay opens, and their window-wide key and pointer handlers do not run. They still show,
   * and a tree dump lists them; an inert overlay neither dismisses nor is modal. It is not for
   * elements that take input of their own: a focus holder, a scroll area being dragged, or the
   * handlers of the root and of the focus path are not kept from it.
   *
   * <p>shortcut: an overlay attached inside inert content becomes inert as it is placed, after
   * layout; one that opens with a focusable element in the frame its inert anchor appears may
   * still take the focus. Mark such overlays inert in the view, or carry inertness into the
   * build, if one needs that.
   */
  public El inert() {
    this.inert = true;
    return this;
  }

  /**
   * Makes Tab and Shift+Tab pass this focusable element by, while a press and {@link App#focus}
   * still focus it: the paragraphs of a document, read-only fields for selecting and copying,
   * between the links that Tab reaches. An overlay that opens focuses it only where nothing in it
   * that Tab reaches takes the focus, unless it is {@link #autofocus} or {@link #defaultFocus}; of
   * such elements, one inside the overlay comes before the overlay itself.
   */
  public El skipTab() {
    this.skipsTab = true;
    return this;
  }

  boolean skipsTab() {
    return skipsTab;
  }

  /**
   * Makes this element hold the keyboard focus while the view marks it so, as an open overlay
   * does: the build that first marks it moves the focus to it, or to its first focusable element,
   * without a ring, and the first build that no longer marks it gives the focus back to where it
   * was before, with the ring it had, if the focus is still inside it or nowhere. A press outside
   * it calls its {@link #onDismiss} handler and goes no further, as a press outside a menu does.
   * Overlays that open inside it take the focus from it and give it back, the newest first. A menu
   * bar holds the focus this way while the keyboard has it, from Alt or F10 until Escape.
   */
  public El holdFocus() {
    this.holdsFocus = true;
    return this;
  }

  /**
   * Groups the {@link #readOnly read-only} text fields inside this element into one selection, in
   * tree order: dragging from one field into another selects across them, Shift+press extends from
   * the anchor across fields, Control+C copies the selected text of all of them in order with a
   * line break between fields, and a press elsewhere clears it. Nested scopes count as the
   * outermost one. In a {@link #rows virtual list}, the selection spans rows out of view as well,
   * survives rows that leave and return, copies every row it covers, and scrolls the list while a
   * drag is held near or past its top or bottom edge; a {@link #masked masked} field never joins.
   *
   * <p>A scope can also be treated as one text by {@link App#scopeText(El)}, {@link App#select(El,
   * int, int)} and {@link #decorate(Decorator)}, for features such as finding across all its
   * fields.
   */
  public El selectionScope() {
    this.selectionScope = true;
    return this;
  }

  /**
   * Makes this element take the keyboard focus when it appears: in the build of the view that
   * first has it, the first such element in tree order that can take the focus gets it, with a
   * ring if the focus had one, and scrolled into view; later builds leave the focus alone. In a
   * row of a virtual list ({@link #rows}) it does so when the list builds the row, again when the
   * row comes back after it left the rows that exist. The
   * editor that a key or a double press lays over a cell takes the focus this way. Inside an
   * overlay it names the element that the overlay's opening focuses instead of its first
   * focusable one: a dialog's second field. Where the focused element disappears, it is where the
   * focus goes among the elements around it (see {@link App#focused}). While an overlay opens and
   * takes the focus, the build leaves elements outside it alone. The same as {@code
   * autofocus(Boolean.TRUE)}.
   */
  public El autofocus() {
    return autofocus(Boolean.TRUE);
  }

  /**
   * Like {@link #autofocus()}, and also takes the focus again whenever a build of the view gives
   * this element another {@code key} than the tree before, as {@link #reveal} keeps a part in
   * view: the first field with an error takes the focus after each Save with {@code
   * autofocus(state.saves)}, the count of Save presses in the state. Not in the rows of a virtual
   * list, whose rows appear whenever they scroll into view.
   *
   * @param key compared with {@link Object#equals}; null, the default, takes no focus
   */
  public El autofocus(Object key) {
    this.autofocus = key;
    AUTOFOCUSES.incrementAndGet();
    return this;
  }

  /**
   * Like {@link #autofocus(Object)}, and it wins: the build that gives this element a new {@code
   * key} moves the focus to it also where {@link #autofocus} elements, overlays that open and the
   * rows of virtual lists ask for the focus in that build, so that the input that waits for this
   * tree finds it there. A tab group's bar takes the focus so after a split, which may show a
   * tab whose content asks for it. Not for the rows of a virtual list nor inside an overlay,
   * where how it ranks among the elements that ask for the focus is not defined.
   *
   * @param key compared with {@link Object#equals}; null takes no focus
   */
  public El takeFocus(Object key) {
    this.takesFocus = true;
    return autofocus(key);
  }

  boolean takesFocus() {
    return takesFocus;
  }

  /**
   * Makes this focusable element the one the focus goes to when it moves into an element around
   * it, unless the focus was inside that element before: {@link App#focus} of a tab's page lands
   * on its grid rather than on the Refresh button above it, and so does the focus that finds a
   * place when the focused element leaves (see {@link App#focused}). Unlike {@link #autofocus}, it
   * does not take the focus by itself, and it goes before an {@link #autofocus} element there.
   * Where {@code App.focus} finds the element that had the focus last inside, that one wins. Of
   * several inside, the one the fewest levels below the element focused into goes first, the first
   * in tree order among equals: a sidebar's list marked so gets the focus when the sidebar is
   * focused into, and the page's grid, marked so too, when the page is. An overlay that opens
   * focuses the first marked so in tree order, unless an {@link #autofocus} element in it asks.
   */
  public El defaultFocus() {
    this.defaultFocus = true;
    return this;
  }

  boolean isDefaultFocus() {
    return defaultFocus;
  }

  // How often autofocus was asked for: App looks through the rows a list built for it only when
  // the building asked, as most rows never do.
  private static final java.util.concurrent.atomic.AtomicInteger AUTOFOCUSES =
      new java.util.concurrent.atomic.AtomicInteger();

  /** How often {@link #autofocus(Object)} has been called, in any thread. */
  static int autofocuses() {
    return AUTOFOCUSES.get();
  }

  private Layer layer() {
    if (layer == null) {
      layer = new Layer();
    }
    return layer;
  }

  /**
   * Sets the shape of the pointer over this element and over the elements inside it that set none;
   * {@link App#cursor} reports it and a window shows it.
   *
   * @param cursor the shape, or null for the parent's, which is {@link Cursor#DEFAULT} at the root
   */
  public El cursor(Cursor cursor) {
    this.cursor = cursor;
    return this;
  }

  // Tree

  /** Adds a child after the ones there are; null adds none, so {@code cond ? part : null} composes. */
  public El child(El child) {
    noRows();
    if (child != null) {
      children.add(child);
    }
    return this;
  }

  /** Adds these children in order, leaving out the ones that are null. */
  public El children(El... children) {
    noRows();
    for (El child : children) {
      child(child);
    }
    return this;
  }

  private void noRows() {
    if (rows != null) {
      throw new IllegalStateException(
          "This element is a list made with rows(...), which builds its children itself.");
    }
  }

  /** Sizes the content when this element has no children. This is the hook for text. */
  public El measure(Measure measure) {
    this.measure = measure;
    return this;
  }

  // Reading: for layout, rendering and tests

  /** The layout properties, for everything the chained methods do not cover. */
  public Style style() {
    return style;
  }

  public List<El> children() {
    return children;
  }

  public String key() {
    return key;
  }

  /**
   * The role set with {@link #role}; without one, {@code textbox} for an {@link #editable} field
   * and {@code img} for an {@link #image}.
   */
  public String role() {
    if (role == null) {
      return field != null ? "textbox" : image != null ? "img" : null;
    }
    return role;
  }

  public String label() {
    return label;
  }

  /** The {@link #value(String) value}, or null. */
  String value() {
    return value;
  }

  public int fill() {
    return fill;
  }

  /** The top left corner's radius, which is every corner's after {@link #radius(float)}. */
  public float radius() {
    return radius;
  }

  /** The radii of the corners: top left, top right, bottom right, bottom left. */
  public float[] radii() {
    return new float[] {radius, radiusTopRight, radiusBottomRight, radiusBottomLeft};
  }

  /** The radius of corner 0..3: top left, top right, bottom right, bottom left. */
  float cornerRadius(int corner) {
    return switch (corner) {
      case 0 -> radius;
      case 1 -> radiusTopRight;
      case 2 -> radiusBottomRight;
      case 3 -> radiusBottomLeft;
      default -> throw new IndexOutOfBoundsException(corner);
    };
  }

  /** The shadows under this element, the topmost first; empty for none. */
  public List<Shadow> shadows() {
    return shadows;
  }

  /** The background painted over this element's fill ({@link #background(Background)}), or null. */
  public Background background() {
    return background;
  }

  /** The border's color; of the top side when {@link #borderColors} gave the sides their own. */
  public int borderColor() {
    return borderColor;
  }

  /**
   * The color of each side, top, right, bottom, left, as {@link #borderColors} gave them, where a
   * side in the top's color has {@link #borderColor()} of the moment, which motion springs; null
   * while every side has that color.
   */
  public int[] borderColors() {
    if (borderColors == null) {
      return null;
    }
    int[] colors = borderColors.clone();
    for (int side = 0; side < 4; side++) {
      if (colors[side] == borderColors[0]) {
        colors[side] = borderColor;
      }
    }
    return colors;
  }

  /**
   * The press handler, which takes the presses, the modifiers and the position, whichever of
   * them the handler given to {@link #onPress} takes; null for none.
   */
  public PressAtHandler onPress() {
    return handlers == null ? null : handlers.onPress;
  }

  /** The handler of {@link #onPointerDown}, or null. */
  public PressAtHandler onPointerDown() {
    return handlers == null ? null : handlers.onPointerDown;
  }

  /** The handler of {@link #onAnyPointerDown}, or null. */
  public Runnable onAnyPointerDown() {
    return handlers == null ? null : handlers.onAnyPointerDown;
  }

  /** The handler of {@link #onAnyKeyDown}, or null. */
  public Consumer<Key> onAnyKeyDown() {
    return handlers == null ? null : handlers.onAnyKeyDown;
  }

  /** The handler of {@link #onPressOutside}, or null. */
  public Runnable onPressOutside() {
    return handlers == null ? null : handlers.onPressOutside;
  }

  /** The handler of {@link #onFocusIn}, or null. */
  public Runnable onFocusIn() {
    return handlers == null ? null : handlers.onFocusIn;
  }

  public Consumer<Drag> onDrag() {
    return handlers == null ? null : handlers.onDrag;
  }

  /** The payload of {@link #dragPayload}, or null. */
  public Object dragPayload() {
    return handlers == null ? null : handlers.dragPayload;
  }

  /** The handler of {@link #onDrop}, or null. */
  public Consumer<Drop> onDrop() {
    return handlers == null ? null : handlers.onDrop;
  }

  /** The handler of {@link #onFileDrop}, or null. */
  public Consumer<Drop> onFileDrop() {
    return handlers == null ? null : handlers.onFileDrop;
  }

  public PointHandler onSecondaryPress() {
    return handlers == null ? null : handlers.onSecondaryPress;
  }

  Consumer<El> restLook() {
    return restLook;
  }

  Consumer<El> focusRestLook() {
    return focusRestLook;
  }

  double focusRestDelay() {
    return focusRestDelay;
  }

  double restDelay() {
    return restDelay;
  }

  Runnable onHoverFor() {
    return handlers == null ? null : handlers.onHoverFor;
  }

  double hoverDelay() {
    return handlers == null ? 0 : handlers.hoverDelay;
  }

  Consumer<Boolean> onHover() {
    return handlers == null ? null : handlers.onHover;
  }

  TextPointerHandler onTextPointerMove() {
    return handlers == null ? null : handlers.onTextPointerMove;
  }

  TextPointerHandler onTextHoverFor() {
    return handlers == null ? null : handlers.onTextHoverFor;
  }

  double textHoverDelay() {
    return handlers == null ? 0 : handlers.textHoverDelay;
  }

  /** The overlays attached to this element with {@link #overlay}, in that order. */
  public List<El> overlays() {
    return overlays == null ? List.of() : overlays;
  }

  /** For {@link Animator}, which puts an overlay back that is leaving, and takes it out. */
  void addOverlay(El overlay) {
    overlay(overlay);
  }

  void removeOverlay(El overlay) {
    if (overlays != null) {
      overlays.remove(overlay);
    }
  }

  /** Whether {@code el} is one of this element's overlays, the very element. */
  boolean hasOverlay(El el) {
    if (overlays != null) {
      for (El overlay : overlays) {
        if (overlay == el) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Of the root of a tree that a frame has placed: the overlays shown above the tree, in the order
   * they are drawn, the topmost last, nested ones included. Their positions are in window
   * coordinates. Empty before the first placement and for a tree without overlays.
   */
  public List<El> shownOverlays() {
    return shownOverlays;
  }

  void shownOverlays(List<El> overlays) {
    this.shownOverlays = overlays;
  }

  /** Whether {@link #modal} made this overlay modal. */
  public boolean isModal() {
    return layer != null && layer.modal;
  }

  /** The fill under this overlay set with {@link #scrim}, 0 for none. */
  public int scrim() {
    return layer == null ? 0 : layer.scrim;
  }

  /** The handler set with {@link #onDismiss}, or null. */
  public Runnable onDismiss() {
    return layer == null ? null : layer.onDismiss;
  }

  /** The group set with {@link #dismissGroup}, or null. */
  public Object dismissGroup() {
    return dismissGroup;
  }

  /** Whether {@link #keepFocus} was called. */
  public boolean keepsFocus() {
    return keepsFocus;
  }

  /** Whether {@link #inert} was called. */
  public boolean isInert() {
    return inert;
  }

  /** Whether {@link #holdFocus} was called. */
  public boolean holdsFocus() {
    return holdsFocus;
  }

  /** Whether {@link #selectionScope} was called. */
  public boolean isSelectionScope() {
    return selectionScope;
  }

  /** For placement and {@link Animator}: where this overlay goes, null if no call said. */
  Layer placement() {
    return layer;
  }

  /** The row a {@link #revealRow virtual list} or the child a scroll area keeps in view, or -1. */
  public int revealRow() {
    return revealRow;
  }

  public Spring motion() {
    return motion;
  }

  /**
   * Whether this element takes the keyboard focus: it is {@link #focusable}, or an {@link
   * #editable} field that is enabled or read-only.
   */
  public boolean isFocusable() {
    return focusable || field != null && (onEdit() != null || field.readOnly);
  }

  /**
   * Whether this element or one inside it can take the focus, as an overlay that opens looks for
   * it: one that {@link #isFocusable takes it} and is not at or below an element at opacity 0,
   * with {@code display: none} or {@link #inert}. Overlays are left out.
   */
  public boolean hasFocusable() {
    return has(false);
  }

  /**
   * Whether this element or one inside it can take the focus as {@link #hasFocusable} says, and
   * Tab reaches it: it does not {@link #skipTab}. A dialog whose content has none, as read-only
   * values only, opens on its default button.
   */
  public boolean hasTabStop() {
    return has(true);
  }

  private boolean has(boolean tabStop) {
    if (hidesFocus()) {
      return false;
    }
    if (isFocusable() && !(tabStop && skipsTab)) {
      return true;
    }
    for (El child : children) {
      if (child.has(tabStop)) {
        return true;
      }
    }
    return false;
  }

  /** Whether this element keeps itself and what is inside it from the focus as it opens. */
  boolean hidesFocus() {
    return inert || opacity() == 0 || style().display == Style.Display.NONE;
  }

  /** The color set with {@link #focusRing(int)}, or null if none was and the app's applies. */
  public Integer focusRing() {
    return focusRing;
  }

  /** The gap set with {@link #focusRingGap}. */
  public float focusRingGap() {
    return focusRingGap;
  }

  public Predicate<Key> onKey() {
    return handlers == null ? null : handlers.onKey;
  }

  public Predicate<Key> onKeyUp() {
    return handlers == null ? null : handlers.onKeyUp;
  }

  public WheelHandler onWheel() {
    return handlers == null ? null : handlers.onWheel;
  }

  public Runnable onUserScroll() {
    return handlers == null ? null : handlers.onUserScroll;
  }

  public Consumer<String> onText() {
    return handlers == null ? null : handlers.onText;
  }

  public Consumer<String> onTypeAhead() {
    return handlers == null ? null : handlers.onTypeAhead;
  }

  /** Whether {@link #editable} made this a text field, enabled or not. */
  public boolean isEditable() {
    return field != null;
  }

  /** The handler of an {@link #editable} field, null when it is disabled. */
  public Consumer<String> onEdit() {
    return handlers == null ? null : handlers.onEdit;
  }

  public boolean isReadOnly() {
    return field != null && field.readOnly;
  }

  /** The {@link #maxLength} in code points, or -1 for none. */
  public int maxLength() {
    return field == null ? -1 : field.maxLength;
  }

  /** Whether Tab and Shift+Tab indent in this field; see {@link #tabIndents}. */
  public boolean tabIndents() {
    return field != null && field.tabIndents != 0;
  }

  /** Whether {@link #tabIndents} indents with spaces rather than tabs. */
  boolean tabSpaces() {
    return field != null && field.tabIndents == 2;
  }

  /**
   * Whether the app has yet to hear where this {@link #editable} field's caret and selection are:
   * an edit of the field waits for the app's answer, or its {@link #onSelect} handler has yet to
   * hear the selection after it. An element of an earlier tree answers for the field it became.
   * For a component that reads the caret the app stored, such as completions after an edit; false
   * for a field that was never in a tree, or that left it for good without a {@link FieldState}.
   */
  public boolean isSelectionPending() {
    Editor editor = editor();
    return editor != null && editor.selectionPending();
  }

  /** For {@link App}: the editing state of a field, which it keeps across rebuilds. */
  Editor editor() {
    return field == null ? null : field.editor;
  }

  void editor(Editor editor) {
    field.editor = editor;
  }

  /** Whether this is a field that takes input: editable, and enabled or read-only. */
  boolean takesEdits() {
    return field != null && isFocusable();
  }

  public WindowPart windowPart() {
    return windowPart;
  }

  /** The cursor set with {@link #cursor}; {@link Cursor#TEXT} for a field that takes input. */
  public Cursor cursor() {
    return cursor == null && takesEdits() ? Cursor.TEXT : cursor;
  }

  Consumer<El> hoverLook() {
    return hoverLook;
  }

  Consumer<El> focusLook() {
    return focusLook;
  }

  Consumer<El> focusWithinLook() {
    return focusWithinLook;
  }

  /** The key of {@link #autofocus(Object)}, or null. */
  Object autofocusKey() {
    return autofocus;
  }

  Consumer<El> pressLook() {
    return pressLook;
  }

  public Measure measure() {
    return measure;
  }

  /** The text of an element made with {@link #text(String)}, with its lines after layout. */
  public Text text() {
    return text;
  }

  /** The image of an element made with {@link #image(Image)}, or null. */
  public Image image() {
    return image;
  }

  /** How its image fills the content box; {@link Image.Fit#FILL} unless {@link #fit} says. */
  public Image.Fit fit() {
    return fit;
  }

  /** For {@link Headless} and tests: text is shaped for the scale, without system fonts. */
  static void scaleText(El el, float scale) {
    scaleText(el, scale, false, 0);
  }

  /**
   * For {@link App} and {@link Headless}: text is shaped for the scale of the window, and falls
   * back to the system's fonts if {@code systemFonts}.
   */
  static void scaleText(El el, float scale, boolean systemFonts) {
    scaleText(el, scale, systemFonts, 0);
  }

  static void scaleText(El el, float scale, boolean systemFonts, float lineHeight) {
    float resolved = el.lineHeight > 0 ? el.lineHeight : lineHeight;
    el.resolvedLineHeight = resolved;
    if (el.text != null) {
      el.text.scale(scale);
      el.text.systemFonts(systemFonts);
      el.text.lineHeight(resolved);
      if (el.hasGutter()) {
        el.measureGutter();
      }
    }
    for (El child : el.children) {
      scaleText(child, scale, systemFonts, resolved);
    }
    for (El overlay : el.overlays()) {
      scaleText(overlay, scale, systemFonts, 0);
    }
  }

  public float opacity() {
    return opacity;
  }

  /**
   * Whether {@link #scroll} or {@link #scrollBoth} made this a scroll area, whose children layout
   * does not shrink along the direction they flow in when that axis has {@code overflow: scroll}.
   */
  public boolean isScrollArea() {
    return scrollArea;
  }

  /** The look of this scroll area's bars, null for none; see {@link #scrollbars(Scrollbars)}. */
  public Scrollbars scrollbars() {
    return scrollbars;
  }

  /** The bar along the bottom as the last frame shows it, or null when there is none. */
  public Scrollbars.Bar horizontalBar() {
    return barX;
  }

  /** The bar along the right edge as the last frame shows it, or null when there is none. */
  public Scrollbars.Bar verticalBar() {
    return barY;
  }

  /** The vertical scrollbar marks added to this field ({@link #scrollMarks}), or an empty list. */
  public List<Scrollbars.Mark> scrollMarks() {
    return field == null || field.scrollMarks == null ? List.of() : List.copyOf(field.scrollMarks);
  }

  /**
   * The ticks of this field's {@link #scrollMarks} along its {@link #verticalBar} as the last frame
   * placed them, empty without a vertical bar.
   */
  public List<Scrollbars.Tick> verticalTicks() {
    return field == null ? List.of() : field.ticks;
  }

  /** Whether {@link #rows} made this a virtual list, whose children are the rows that exist. */
  public boolean isVirtualList() {
    return rows != null;
  }

  /** Whether this virtual list builds its rows for the part of them in view sideways. */
  boolean spansRows() {
    return rows != null && rows.spans;
  }

  /** The number of rows of a {@link #rows virtual list}, 0 for any other element. */
  public int rowCount() {
    return rows == null ? 0 : rows.rowCount;
  }

  /** The height of each row of a {@link #rows virtual list} in logical pixels. */
  public float rowHeight() {
    return rows == null ? 0 : rows.rowHeight;
  }

  /**
   * The index of the row that is the first child of a {@link #rows virtual list}: the children
   * are the rows from this one on, as many as exist.
   */
  public int firstRow() {
    return rows == null ? 0 : rows.firstRow;
  }

  /** Whether the row with this index exists as a child of this virtual list. */
  boolean hasRow(int index) {
    int first = firstRow();
    return index >= first && index < first + children.size();
  }

  /**
   * The horizontal offset of this scroll area in logical pixels: how far its content is moved to
   * the left, 0 at the start. The positions of the children already take it into account. {@link
   * App#scrollTo} changes it.
   */
  public double scrollX() {
    return scrollX;
  }

  /**
   * The vertical offset of this scroll area: how far its content is moved up, 0 at the top, less
   * for a field or covered scope scrolled above its first row ({@link #coveredTop}). It is a
   * double, exact for the integers of a list of rows billions of pixels tall.
   */
  public double scrollY() {
    return scrollY;
  }

  /** Whether the offset along one axis can still move in the direction of {@code delta}. */
  boolean canScroll(boolean horizontal, float delta) {
    double offset = horizontal ? scrollX : scrollY;
    double min = horizontal ? 0 : minScrollY();
    return delta > 0 ? offset < (horizontal ? maxScrollX : maxScrollY) : delta < 0 && offset > min;
  }

  /**
   * Measures how far this element can scroll along the axes with {@code overflow: scroll}: to
   * where its farthest child ends, with its margin and this element's end padding and border.
   * Called right after layout, before anything springs. A virtual list reaches to the end of its
   * last row, whether it exists or not.
   */
  void measureScroll() {
    if (text != null && text.multiline()) {
      // A field: its rows as far as they are known, in its content box as layout placed it.
      float scale = text.scale();
      double room = Math.max(1, Math.round(scale)) + Editor.MARGIN * scale; // the caret at the end
      double extent = Math.max(
          text.extentWidth((float) room), field == null || text.wraps() ? 0 : field.foldsExtent);
      double wide = extent - text.contentWidth() / scale;
      double tall = text.extentHeight() - text.contentHeight() / scale;
      maxScrollX = style.overflowX == Style.Overflow.SCROLL && wide > SLACK ? wide : 0;
      maxScrollY = tall > SLACK ? tall : 0;
      return;
    }
    if (rows != null) {
      maxScrollX = 0;
      maxScrollY = Math.max(
          rowsTop() + rowsTotal() + padding(Side.BOTTOM) + border(Side.BOTTOM) + coverY - height,
          0);
      return;
    }
    boolean scrolls =
        style.overflowX == Style.Overflow.SCROLL || style.overflowY == Style.Overflow.SCROLL;
    float[] extent = new float[2]; // right and bottom of the children, with their margins
    // What sticks out of the children: unlike them it gets no end padding (CSS Overflow 3, 2.2).
    float[] out = new float[2];
    for (El child : children) {
      if (child.placed && !child.exiting) {
        extent[0] = Math.max(extent[0], child.x + child.width + child.margin(Side.RIGHT));
        extent[1] = Math.max(extent[1], child.y + child.height + child.margin(Side.BOTTOM));
        if (scrolls) {
          child.overflow(child.x, child.y, out);
        }
      }
    }
    float right = Math.max(extent[0] + padding(Side.RIGHT), out[0]);
    float bottom = Math.max(extent[1] + padding(Side.BOTTOM), out[1]);
    double overX = right + border(Side.RIGHT) - width;
    double overY = bottom + border(Side.BOTTOM) - height;
    maxScrollX = style.overflowX == Style.Overflow.SCROLL && overX > 0 ? overX + coverX : 0;
    maxScrollY = style.overflowY == Style.Overflow.SCROLL && overY > 0 ? overY + coverY : 0;
  }

  /**
   * Widens {@code extent}, the right and bottom edge of a scroll area's content, by what sticks out
   * of this element, which lies at {@code (x, y)} in that content: its descendants up to those that
   * clip, as visible overflow passes through them (CSS Overflow 3, section 2.2).
   */
  private void overflow(float x, float y, float[] extent) {
    if (style.overflowX != Style.Overflow.VISIBLE || style.overflowY != Style.Overflow.VISIBLE) {
      return; // it clips what is inside it, as the scene does
    }
    for (El child : children) {
      if (child.placed && !child.exiting) {
        float childX = x + child.x;
        float childY = y + child.y;
        extent[0] = Math.max(extent[0], childX + child.width);
        extent[1] = Math.max(extent[1], childY + child.height);
        child.overflow(childX, childY, extent);
      }
    }
  }

  // Less than this is no overflow: what layout converted back and forth.
  private static final double SLACK = 1 / 64.0;

  /**
   * Measures how far the scroll areas below {@code root} can scroll, after layout and before any
   * offset, as {@link #measureScroll} does, and lets the areas with {@link #scrollbars} scroll
   * their content out from under the bars that lie over its end. A bar along the bottom covers the
   * bottom of its area by its strip, {@link Scrollbars#thickness} and twice {@link
   * Scrollbars#margin}, and so does a bar of an area around it whose bottom it shares, as the bar
   * of a data grid lies over the bottom of its rows; likewise along the right edge. An area that
   * scrolls along the other axis then reaches as much further: a list of rows always, since its
   * rows are its content, any other area only where its content does not fit anyway, since a child
   * that is stretched to fill it would otherwise scroll by the strip. A bar of an area inside an
   * area moves with that area's content and lies over nothing of the area's end.
   */
  static void measureScrolls(El root) {
    float far = Float.POSITIVE_INFINITY;
    root.measureScrolls(0, 0, far, far, far, far, Float.NEGATIVE_INFINITY);
  }

  /**
   * @param barLeft the left edge of the strips of vertical bars of areas around this element that
   *     lie at a fixed place over its parent's content, in layout coordinates from the root, or
   *     infinity for none
   * @param barTop likewise the top edge of the strips of horizontal bars
   * @param clipRight the right edge of the padding boxes those bars lie in
   * @param clipBottom the bottom edge of those padding boxes
   * @param coverBottom the bottom edge of an enclosing selection scope's {@link #coveredTop}, in
   *     layout coordinates from the root, or negative infinity for none
   */
  private void measureScrolls(
      float parentX,
      float parentY,
      float barLeft,
      float barTop,
      float clipRight,
      float clipBottom,
      float coverBottom) {
    coverX = 0;
    coverY = 0;
    coverTop = 0;
    if (!placed) {
      return;
    }
    measureScroll();
    float x = parentX + this.x;
    float y = parentY + this.y;
    if (rows != null) { // what it got, for measureRowScrolls
      rows.barLeft = barLeft - x;
      rows.barTop = barTop - y;
      rows.clipRight = clipRight - x;
      rows.clipBottom = clipBottom - y;
      rows.coverBottom = coverBottom - y;
    }
    if (selectionScope && scopeCoveredTop > 0) {
      coverBottom = Math.max(coverBottom, y + scopeCoveredTop);
    }
    if (coverBottom > Float.NEGATIVE_INFINITY
        && scrollArea
        && style.overflowY == Style.Overflow.SCROLL) {
      float covered = coverBottom - (y + border(Side.TOP) + padding(Side.TOP));
      coverTop = covered > 0 ? covered : 0; // 0 also for a NaN padding
      coverBottom = Float.NEGATIVE_INFINITY; // inner scroll areas move with this one's content
    }
    float right = x + width - border(Side.RIGHT);
    float bottom = y + height - border(Side.BOTTOM);
    boolean wide = maxScrollX > 0;
    boolean tall = maxScrollY > 0;
    if (scrollbars != null) {
      float strip = scrollbars.strip();
      if (style.overflowY == Style.Overflow.SCROLL && (tall || rows != null)) {
        coverY = Math.max(wide ? strip : 0, Math.min(bottom, clipBottom) - barTop);
      }
      if (wide) {
        coverX = Math.max(tall ? strip : 0, Math.min(right, clipRight) - barLeft);
      }
      measureScroll();
      tall = maxScrollY > 0;
    }
    // The content moves under the bars of the areas around along an axis this one scrolls.
    if (tall) {
      barTop = Float.POSITIVE_INFINITY;
      clipBottom = Float.POSITIVE_INFINITY;
    }
    if (wide) {
      barLeft = Float.POSITIVE_INFINITY;
      clipRight = Float.POSITIVE_INFINITY;
    }
    // This one's bars at a fixed place over the content, along an axis it does not scroll.
    if (scrollbars != null) {
      float strip = scrollbars.strip();
      if (wide && !tall) {
        barTop = Math.min(barTop, bottom - strip);
        clipBottom = Math.min(clipBottom, bottom);
      }
      if (tall && !wide) {
        barLeft = Math.min(barLeft, right - strip);
        clipRight = Math.min(clipRight, right);
      }
    }
    for (El child : children) {
      child.measureScrolls(x, y, barLeft, barTop, clipRight, clipBottom, coverBottom);
    }
    if (overlays != null) { // each laid out as a root of its own, under no bar of this tree
      for (El overlay : overlays) {
        measureScrolls(overlay);
      }
    }
  }

  /**
   * Measures this virtual list and its rows again as {@link #measureScrolls} does, with what that
   * passed it, for rows built after it ran, whose heights may also have changed the list's bars.
   */
  void measureRowScrolls() {
    if (rows == null) {
      return;
    }
    measureScrolls(
        -x, -y, rows.barLeft, rows.barTop, rows.clipRight, rows.clipBottom, rows.coverBottom);
    for (El row : children) {
      if (row.kept) { // visit clamps only the rows built new
        row.clampScrolls();
      }
    }
  }

  /**
   * Clamps the offsets of the scroll areas from this one down to what they measure now, but not
   * one the app moved since the last build ({@link ScrollState#scrollTo}), which that build takes.
   */
  private void clampScrolls() {
    if (scrollArea && (scrollState == null || scrollState.el != this || !scrollState.placed)) {
      setScroll(scrollX, scrollY);
    }
    for (El child : children) {
      child.clampScrolls();
    }
  }

  /**
   * How much of the right edge of this scroll area the strips of vertical bars cover, which its
   * content can scroll past; see {@link #measureScrolls}.
   */
  float coverX() {
    return coverX;
  }

  /** For {@link App}: how far this scroll area scrolls sideways, in logical pixels. */
  double maxScrollX() {
    return maxScrollX;
  }

  /**
   * How much of the bottom of this area, where it scrolls up and down, the strips that layout
   * keeps for horizontal bars cover, its own or those of the areas around it, in logical pixels
   * as the last layout placed them; 0 where none does, the area does not scroll up and down, or
   * it has no {@link #scrollbars} of its own. The content scrolls that much further, so that its
   * end shows above them, and a row under them is not wholly in view.
   */
  public float coverY() {
    return coverY;
  }

  /**
   * Sets the scroll offset, clamped to what {@link #measureScroll} found, and shifts the children
   * by it; moves any coupled partner ({@link ScrollState#scrollWith}) when the offset changed.
   *
   * @return whether the offset changed
   */
  boolean scrollTo(double x, double y) {
    double oldX = scrollX;
    double oldY = scrollY;
    boolean changed = setScroll(x, y);
    if (changed && scrollState != null && scrollState.el == this) {
      scrollState.follow(this, scrollX != oldX, scrollY != oldY);
    }
    if (changed && y == Double.POSITIVE_INFINITY) {
      toEnd(true); // rows measured on the way keep it at the end, as ScrollState.scrollToEnd does
    }
    return changed;
  }

  /**
   * Sets the scroll offset as {@link #scrollTo} does, without moving a coupled partner: for
   * restoring a kept offset on rebuild, shifting newly built children, keeping a line in place
   * after layout, and applying a coupled offset itself.
   */
  boolean setScroll(double x, double y) {
    double newX = Math.clamp(x, 0, maxScrollX);
    double newY = Math.clamp(y, minScrollY(), maxScrollY);
    boolean changed = newX != scrollX || newY != scrollY;
    scrollX = newX;
    scrollY = newY;
    if (scrollState != null && scrollState.el == this) {
      scrollState.x = newX;
      scrollState.y = newY;
      scrollState.atEnd = newY >= maxScrollY - SLACK;
      scrollState.toEnd &= scrollState.atEnd; // scrolled away before its rows were measured
    } else if (scrollState == null) {
      toEnd &= newY >= maxScrollY - SLACK;
    }
    if (text != null && text.multiline()) {
      text.scrolled(newX, newY);
    }
    // Rows are placed from the first one that exists, so the offset moves them from there.
    float shiftY = (float) (rows == null ? newY : newY - rowTop(rows.firstRow));
    for (El child : children) {
      child.shiftX = (float) newX;
      child.shiftY = shiftY;
    }
    return changed;
  }

  // Scrollbars, for App and Headless

  /**
   * Places the bars of the scroll areas with {@link #scrollbars} below {@code root}, which layout
   * and scrolling have placed, in window coordinates and in their color at rest. Each lies along
   * the edges of its area's padding box, within the clips of its ancestors and out of the strips
   * of their bars.
   */
  static void placeBars(El root) {
    float far = Float.POSITIVE_INFINITY;
    root.placeBars(0, 0, -far, -far, far, far);
    for (El overlay : root.shownOverlays) { // free of the clips around its anchor
      overlay.placeBars(0, 0, -far, -far, far, far);
    }
  }

  private void placeBars(
      float parentX, float parentY, float left, float top, float right, float bottom) {
    barX = null;
    barY = null;
    if (field != null) {
      field.ticks = List.of();
    }
    if (!placed) {
      return;
    }
    float x = parentX + x();
    float y = parentY + y();
    Style s = style;
    if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
      left = Math.max(left, x);
      top = Math.max(top, y);
      right = Math.min(right, x + width);
      bottom = Math.min(bottom, y + height);
      if (scrollbars != null) {
        float t = scrollbars.thickness();
        float m = scrollbars.margin();
        // The padding box within the clips; a bar is placed only where it fits.
        float boxLeft = Math.max(left, x + border(Side.LEFT));
        float boxTop = Math.max(top, y + border(Side.TOP));
        float boxRight = Math.min(right, x + width - border(Side.RIGHT));
        float boxBottom = Math.min(bottom, y + height - border(Side.BOTTOM));
        boolean wide = s.overflowX == Style.Overflow.SCROLL && maxScrollX > 0;
        boolean tall = s.overflowY == Style.Overflow.SCROLL && maxScrollY > 0;
        if (wide && boxBottom - boxTop >= t + 2 * m) {
          barX = bar(false, boxBottom - m - t, boxLeft + m, boxRight - m - (tall ? t + m : 0));
        }
        if (tall && boxRight - boxLeft >= t + 2 * m) {
          barY =
              bar(true, boxRight - m - t, boxTop + m, boxBottom - m - (barX != null ? t + m : 0));
          if (barY != null && field != null && field.scrollMarks != null) {
            placeTicks();
          }
        }
        if (barX != null) { // the bars of areas inside stay out of these strips
          bottom = Math.min(bottom, barX.trackY() - m);
        }
        if (barY != null) {
          right = Math.min(right, barY.trackX() - m);
        }
      }
    }
    for (El child : children) {
      child.placeBars(x, y, left, top, right, bottom);
    }
  }

  /**
   * Places the ticks of a field's marks along {@link #barY}, where the share of the track above a
   * tick is the share of the text above its first line, as the thumb's place is the offset's.
   */
  private void placeTicks() {
    if (!text.laidOut()) {
      return;
    }
    double min = minScrollY();
    double total = text.contentHeight() / text.scale() + maxScrollY - min;
    float top = barY.trackY();
    float track = barY.trackHeight();
    float least = Math.min(Math.max(scrollbars.thickness() / 2, 1), track); // never snaps to 0
    List<Scrollbars.Mark> marks = field.scrollMarks;
    marks.sort(Comparator.comparingInt(Scrollbars.Mark::from)); // a later tick lies on top
    List<Scrollbars.Tick> ticks = new ArrayList<>();
    int[] targets = new int[marks.size()];
    double[] span = new double[3];
    Editor editor = field.editor;
    for (Scrollbars.Mark mark : marks) {
      int from = editor == null ? mark.from() : editor.toShown(mark.from());
      int to = editor == null ? mark.to() : editor.toShown(mark.to());
      if (mark.color() >>> 24 == 0 || !text.markSpan(from, to, span)) {
        continue;
      }
      float height = Math.clamp((float) (track * (span[1] - span[0]) / total), least, track);
      float y = top + Math.clamp((float) (track * (span[0] - min) / total), 0, track - height);
      Scrollbars.Tick last = ticks.isEmpty() ? null : ticks.getLast();
      if (last != null && last.color() == mark.color() && y >= last.y() && y < last.y() + 1) {
        // One tick for the marks that start within a pixel, such as matches in adjacent lines.
        float bottom = Math.max(last.y() + last.height(), y + height);
        ticks.set(ticks.size() - 1, new Scrollbars.Tick(last.y(), bottom - last.y(), last.color()));
        continue;
      }
      targets[ticks.size()] = from;
      ticks.add(new Scrollbars.Tick(y, height, mark.color()));
    }
    field.ticks = List.copyOf(ticks); // read-only: the targets stay paired with the ticks
    field.tickTargets = targets;
  }

  /**
   * The shown position of the first mark of the tick under {@code y}, in window coordinates, the
   * one drawn last where ticks overlap; -1 for none.
   */
  int tickAt(float y) {
    List<Scrollbars.Tick> ticks = verticalTicks();
    for (int i = ticks.size() - 1; i >= 0; i--) {
      Scrollbars.Tick tick = ticks.get(i);
      if (y >= tick.y() && y <= tick.y() + tick.height()) {
        return field.tickTargets[i];
      }
    }
    return -1;
  }

  /**
   * The vertical offset that shows the line of shown position {@code at} in the middle of the part
   * of this field not covered at its top, as far as its rows are measured.
   */
  double centerOffset(int at) {
    double[] span = new double[3];
    text.markSpan(at, at, span);
    double view = text.contentHeight() / text.scale();
    return (span[0] + span[2]) / 2 - (Math.min(coveredContent(), view) + view) / 2;
  }

  /**
   * A bar whose track runs from {@code start} to {@code end} along its axis, at {@code across} on
   * the other; null when the track has no length.
   */
  private Scrollbars.Bar bar(boolean vertical, float across, float start, float end) {
    float track = end - start;
    if (!(track > 0)) {
      return null;
    }
    double view = vertical ? height : width;
    double min = vertical ? minScrollY() : 0; // a field can scroll above its first row
    double range = (vertical ? maxScrollY : maxScrollX) - min;
    double offset = (vertical ? scrollY : scrollX) - min;
    float thumb = (float)
        Math.clamp(track * view / (view + range), Math.min(scrollbars.minThumb(), track), track);
    float at = start + (float) ((track - thumb) * (offset / range));
    float t = scrollbars.thickness();
    int color = scrollbars.thumb();
    return vertical
        ? new Scrollbars.Bar(across, start, t, track, across, at, t, thumb, color)
        : new Scrollbars.Bar(start, across, track, t, at, across, thumb, t, color);
  }

  /**
   * The bar whose strip along the edge, its track and {@link Scrollbars#margin} around it, holds
   * this point of the window; the vertical one first, null for neither.
   */
  Scrollbars.Bar barAt(float x, float y) {
    for (Scrollbars.Bar bar : new Scrollbars.Bar[] {barY, barX}) {
      float m = scrollbars == null ? 0 : scrollbars.margin();
      if (bar != null
          && x >= bar.trackX() - m
          && x < bar.trackX() + bar.trackWidth() + m
          && y >= bar.trackY() - m
          && y < bar.trackY() + bar.trackHeight() + m) {
        return bar;
      }
    }
    return null;
  }

  /** For {@link App}, which shows a bar hovered or dragged in another color. */
  void barColor(boolean vertical, int argb) {
    if (vertical && barY != null) {
      barY = barY.color(argb);
    } else if (!vertical && barX != null) {
      barX = barX.color(argb);
    }
  }

  /**
   * Scrolls so that the thumb of a bar of the last frame starts at {@code start}, in window
   * coordinates along its axis: the offset is in proportion to where the thumb is between the ends
   * of its track, so that both ends reach the ends of the content exactly: for a field covered at
   * the top, the start of the track is as far above its first row as it can scroll. The offset
   * moves only the way the pointer does: rows measured on the way change where the thumb is.
   *
   * @param direction how the pointer moved along the axis: 1 towards the end, -1, or 0
   * @return whether the offset changed
   */
  boolean dragThumb(boolean vertical, float start, float direction) {
    Scrollbars.Bar bar = vertical ? barY : barX;
    if (bar == null) {
      return false;
    }
    double free =
        vertical ? bar.trackHeight() - bar.thumbHeight() : bar.trackWidth() - bar.thumbWidth();
    double along = start - (vertical ? bar.trackY() : bar.trackX());
    double share = free > 0 ? Math.clamp(along / free, 0.0, 1.0) : 0;
    double min = vertical ? minScrollY() : 0;
    double max = vertical ? maxScrollY : maxScrollX;
    // Down to near the track's end, where a pointer at a fractional scale may stop: the end it asks
    // for, while the list's last rows are estimated.
    boolean end = vertical
        && direction > 0
        && free > 0
        && orEnd(max) == Double.POSITIVE_INFINITY
        && along >= free - Math.min(1, free / 4);
    double to = end ? max : min + share * (max - min);
    double from = vertical ? scrollY : scrollX;
    if (direction == 0 || Math.signum(to - from) == -direction) {
      return false;
    }
    if (!vertical) {
      return scrollTo(to, scrollY);
    }
    return scrollTo(scrollX, end ? Double.POSITIVE_INFINITY : orEnd(to));
  }

  /** Scrolls one page, the height or width of the area, towards the end or, with -1, the start. */
  boolean page(boolean vertical, int direction) {
    return vertical
        ? scrollTo(scrollX, orEnd(scrollY + direction * (double) height))
        : scrollTo(scrollX + direction * (double) width, scrollY);
  }

  /**
   * A vertical offset the user moves to, as the end itself when it reaches the estimated end of a
   * virtual list whose last row is not built yet: once measured, that row keeps the list at its
   * end, as after End.
   */
  double orEnd(double y) {
    boolean estimated = rows != null && rows.heights != null && !hasRow(rows.rowCount - 1);
    return estimated && y >= maxScrollY ? Double.POSITIVE_INFINITY : y;
  }

  // Virtual lists, for App and Headless. A row's index times rowHeight is exact in a double: a
  // float's 24 significant bits times an index below 2^29 fit into its 53.

  /** The top of the content box, where row 0 begins, relative to the border box. */
  private float rowsTop() {
    return border(Side.TOP) + padding(Side.TOP);
  }

  /**
   * The rows this virtual list should have, as {@code {from, to}} with {@code to} exclusive, or
   * none (an empty array) when it has them: those in its visible part, which is its border box inside the window,
   * and {@link #OVERSCAN} more on each side. A list that has every row in view keeps its rows, so
   * that scrolling by less than the overscan builds nothing.
   *
   * @param top the top of the border box in window coordinates
   */
  int[] wantedRows(float top, float windowHeight) {
    return wantedRows(
        0,
        top,
        Float.NEGATIVE_INFINITY,
        Float.POSITIVE_INFINITY,
        Float.POSITIVE_INFINITY,
        windowHeight);
  }

  /**
   * Like {@link #wantedRows(float, float)}, with the horizontal place of the list too, for a list
   * whose rows are built for the part in view sideways: its left in window coordinates, and the
   * clip of its ancestors from {@code clipLeft} to {@code clipRight}. Such a list also wants new
   * rows when that part is no longer inside the one its rows were built for.
   */
  int[] wantedRows(
      float left,
      float top,
      float clipLeft,
      float clipRight,
      float windowWidth,
      float windowHeight) {
    return wantedRows(left, top, clipLeft, clipRight, windowWidth, windowHeight, false);
  }

  /**
   * Like {@link #wantedRows(float, float, float, float, float, float)}.
   *
   * @param anew whether to want the rows that a list built anew would, also when it has them;
   *     then none where those are built for another part sideways than the rows it has
   */
  int[] wantedRows(
      float left,
      float top,
      float clipLeft,
      float clipRight,
      float windowWidth,
      float windowHeight,
      boolean anew) {
    if (rows == null) {
      return NO_ROWS;
    }
    double row0 = top + rowsTop() - scrollY; // where row 0 is in the window
    double from = Math.max(top, 0);
    double to = Math.max(Math.min(top + height, windowHeight), from);
    int first = Math.clamp(rowAt(from - row0), 0, rows.rowCount);
    int end = Math.clamp(rowAfter(to - row0), first, rows.rowCount);
    float shown = 0;
    float shownTo = 0;
    if (rows.spans) { // the part in view sideways, in the coordinates of the content box
      float origin = left + border(Side.LEFT) + padding(Side.LEFT);
      shown = Math.max(Math.max(Math.max(left, clipLeft), 0) - origin, 0);
      shownTo = Math.max(Math.min(Math.min(left + width, clipRight), windowWidth) - origin, shown);
    }
    float margin = (shownTo - shown) / 2;
    float start = Math.max(shown - margin, 0);
    boolean has = first >= rows.firstRow
        && end <= rows.firstRow + children.size()
        && (!rows.spans || shown >= rows.spanFrom && shownTo <= rows.spanTo);
    // Anew, none for another part sideways: only a rebuild of the view builds those cells.
    boolean sideways = rows.spans && (start != rows.spanFrom || shownTo + margin != rows.spanTo);
    // Rows laid out at another width, as while the list springs to a new one, are built anew.
    boolean resized =
        !children.isEmpty() && !Len.px(rowWidth()).equals(children.getFirst().style.width);
    if (rows.rowsBuilt && !resized && (anew ? sideways : has)) {
      return NO_ROWS;
    }
    if (rows.spans) {
      rows.spanFrom = start;
      rows.spanTo = shownTo + margin;
    }
    return new int[] {
      Math.max(first - OVERSCAN, 0), (int) Math.min(end + (long) OVERSCAN, rows.rowCount)
    };
  }

  /**
   * Gives this virtual list the rows from {@code from} to {@code to}, exclusive. A row it has
   * already stays as it is, unless {@code anew} holds for its index, it or an element inside it
   * has a {@link #cycle} or the rows were built for another part sideways or laid out at another
   * width; its row function builds the others.
   * {@link #layoutRows} lays out the new rows and moves the kept ones.
   */
  void makeRows(int from, int to, IntPredicate anew) {
    List<El> had = List.copyOf(children);
    int hadFrom = rows.firstRow;
    boolean keeps = rows.rowsBuilt
        && !had.isEmpty()
        && rows.spanFrom == rows.builtFrom
        && rows.spanTo == rows.builtTo
        && Len.px(rowWidth()).equals(had.getFirst().style.width);
    children.clear();
    for (int i = from; i < to; i++) {
      El row = keeps && i >= hadFrom && i - hadFrom < had.size() ? had.get(i - hadFrom) : null;
      // A cycle starts from what layout gave its element, which placing it again would lose.
      if (row != null && !anew.test(i) && !row.hasCycle()) {
        row.kept = true;
        children.add(row);
      } else {
        children.add(newRow(i));
      }
    }
    rows.firstRow = from;
    rows.builtFrom = rows.spanFrom;
    rows.builtTo = rows.spanTo;
    rows.rowsBuilt = true;
  }

  /**
   * Builds the row that is the child with this index anew, where the one it replaces is shown;
   * {@link #layoutRow} lays it out.
   */
  void remakeRow(int index) {
    El row = newRow(rows.firstRow + index);
    El old = children.set(index, row);
    row.shiftX = old.shiftX;
    row.shiftY = old.shiftY;
  }

  private El newRow(int index) {
    El row = rows.rowBuilder.row(index, rows.spanFrom, rows.spanTo);
    if (row == null) {
      throw new IllegalStateException("The row function of a list returned null for row " + index);
    }
    if (row.position == 0) {
      row.position(index + 1, rows.rowCount);
    }
    fix(row, style.overflowX == Style.Overflow.SCROLL ? this : fixedX ? null : across);
    row.kept = false;
    return row;
  }

  /** Whether this element or one inside it, its overlays included, has a {@link #cycle}. */
  private boolean hasCycle() {
    if (cycle != null) {
      return true;
    }
    for (El child : children) {
      if (child.hasCycle()) {
        return true;
      }
    }
    if (overlays != null) {
      for (El overlay : overlays) {
        if (overlay.hasCycle()) {
          return true;
        }
      }
    }
    return false;
  }

  /** Whether this row of a virtual list was kept by the list's last {@link #makeRows} as it was. */
  boolean isKept() {
    return kept;
  }

  /**
   * Returns the built row with this index if it exists, or builds it with the row function without
   * keeping it, for reading text across rows that are out of view. When this list builds only the
   * columns in view sideways, builds the row without a horizontal span limit so every column is
   * present, also before the list's first layout.
   */
  El rowElement(int index) {
    if (rows == null || index < 0 || index >= rows.rowCount) {
      return null;
    }
    if (rows.spans) {
      return rows.rowBuilder.row(index, 0, Float.POSITIVE_INFINITY);
    }
    if (hasRow(index)) {
      return children.get(index - rows.firstRow);
    }
    return rows.rowBuilder.row(index, rows.spanFrom, rows.spanTo);
  }

  /**
   * Whether a row from {@code from} to {@code to}, exclusive, as the row function builds it now,
   * has an {@link #onAnyKeyDown} handler or a virtual list of its own, whose rows could have one.
   * The rows are not kept: this tells {@link App} whether a key needs the rows built, without
   * their layout and looks.
   */
  boolean rowsHaveKeyHooks(int from, int to) {
    for (int i = from; i < to; i++) {
      El row = rows.rowBuilder.row(i, rows.spanFrom, rows.spanTo);
      if (row != null && keyHooks(row)) {
        return true;
      }
    }
    return false;
  }

  private static boolean keyHooks(El el) {
    if (el.onAnyKeyDown() != null || el.rows != null) {
      return true;
    }
    for (El child : el.children) {
      if (keyHooks(child)) {
        return true;
      }
    }
    if (el.overlays != null) {
      for (El overlay : el.overlays) {
        if (keyHooks(overlay)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Links the elements below {@code root} that are {@link #fixedX} and the virtual lists to the
   * nearest scroll area around them that scrolls sideways, for {@link App} and {@link Headless}
   * after a view is built; the rows a list builds are linked as they are built.
   */
  static void fix(El root) {
    fix(root, null);
  }

  private static void fix(El el, El across) {
    el.across = el.fixedX || el.rows != null ? across : null;
    // What lies inside a fixed element stays with it: the offset of the area counts once.
    El inside = el.scrollArea && el.style.overflowX == Style.Overflow.SCROLL
        ? el
        : el.fixedX ? null : across;
    for (El child : el.children) {
      fix(child, inside);
    }
    if (el.overlays != null) {
      for (El overlay : el.overlays) {
        fix(overlay, null);
      }
    }
  }

  /** The handler of {@link #onRowsBuilt}, or null. */
  RowsHandler onRowsBuilt() {
    return handlers == null ? null : handlers.onRowsBuilt;
  }

  /** The handler of {@link #onRowsInView}, or null. */
  RowsHandler onRowsInView() {
    return handlers == null ? null : handlers.onRowsInView;
  }

  /** The key of {@link #reveal}, or null. */
  Object revealKey() {
    return revealKey;
  }

  /**
   * Scrolls just far enough to show the rectangle of {@link #reveal}, along the axes it scrolls,
   * clear of the bars over the area's ends.
   */
  void revealArea() {
    double dx = style.overflowX == Style.Overflow.SCROLL
        ? ScrollState.nearest(revealX - scrollX, revealWidth, width - coverX)
        : 0;
    double dy = style.overflowY == Style.Overflow.SCROLL
        ? ScrollState.nearest(revealY - scrollY, revealHeight, height - coverY)
        : 0;
    scrollTo(scrollX + dx, scrollY + dy);
  }

  /**
   * Whether this area follows its vertical end, or was sent there since the last build, and was at
   * it before the current layout.
   */
  private boolean followsEnd() {
    return scrollState == null
        ? toEnd
        : scrollState.el == this
            && (scrollState.followEnd || scrollState.toEnd)
            && scrollState.atEnd;
  }

  /**
   * Lays out each row that {@link #makeRows} built on its own, with the list's inner width and the
   * row height, and places it below the previous one; a row it kept only moves to its place.
   */
  void layoutRows(float scale, boolean systemFonts) {
    // Rows that turn out higher or lower above the one at the top move it: the offset follows.
    boolean follow = followsEnd();
    int anchor =
        rows.heights == null ? 0 : (int) Math.clamp(rowAt(scrollY - rowsTop()), 0, rows.rowCount);
    double before = rows.heights == null ? 0 : rowTop(anchor);
    for (int i = 0; i < children.size(); i++) {
      El row = children.get(i);
      if (row.kept) { // laid out before; placed from the first row that exists, as layoutRow does
        double top = rows.heights == null ? row.contentTop : rowsTop() + rowTop(rows.firstRow + i);
        row.place(rowsLeft(), (float) (top - rowTop(rows.firstRow)), rowWidth(), row.height);
      } else {
        placeRow(i, scale, systemFonts);
      }
    }
    if (rows.heights != null) {
      measureScroll();
      scrollY = follow ? maxScrollY : scrollY + rowTop(anchor) - before; // clamped below
    }
    setScroll(scrollX, scrollY); // shifts the new rows
  }

  /** Whether this is a virtual list whose rows are as high as their content. */
  boolean measuresRows() {
    return rows.heights != null;
  }

  /** The left of the rows, the left of the content box. */
  private float rowsLeft() {
    return border(Side.LEFT) + padding(Side.LEFT);
  }

  /**
   * The width of the rows of a virtual list ({@link #rows(int, float, IntFunction)}): the width
   * of the content box as layout gave it, not as a {@link #cycle} shows it, without the
   * scrollbar's.
   */
  public float rowWidth() {
    return Math.max(
        laidOutWidth()
            - rowsLeft()
            - padding(Side.RIGHT)
            - border(Side.RIGHT)
            - style.scrollbarWidth,
        0);
  }

  /** Lays out the row that is the child with this index, as {@link #layoutRows} does. */
  void layoutRow(int index, float scale, boolean systemFonts) {
    boolean follow = followsEnd();
    int anchor =
        rows.heights == null ? 0 : (int) Math.clamp(rowAt(scrollY - rowsTop()), 0, rows.rowCount);
    double before = rows.heights == null ? 0 : rowTop(anchor);
    if (placeRow(index, scale, systemFonts)) {
      measureScroll();
      setScroll(scrollX, follow ? maxScrollY : scrollY + rowTop(anchor) - before);
    }
  }

  /** Lays out a temporary row element as {@link #placeRow} does, without adding it to the list. */
  void layoutRowElement(El row, float scale, boolean systemFonts) {
    float inner = rowWidth();
    row.style.width = Len.px(inner);
    row.style.height = Len.px(rows.rowHeight);
    scaleText(row, scale, systemFonts, resolvedLineHeight);
    Flex.layout(row, inner, rows.rowHeight);
    row.place(rowsLeft(), 0, inner, row.height);
  }

  /**
   * Lays out the row that is the child with this index and places it; returns whether a {@link
   * RowHeights} measurement changed its height.
   */
  private boolean placeRow(int index, float scale, boolean systemFonts) {
    float left = rowsLeft();
    float inner = rowWidth();
    El row = children.get(index);
    row.kept = false; // laid out anew
    row.style.width = Len.px(inner);
    if (rows.heights != null) { // as high as its content or its own height, measured and kept
      scaleText(row, scale, systemFonts, resolvedLineHeight);
      Flex.layout(row, inner, Measure.MAX_CONTENT);
      float high = row.height;
      boolean moved = rows.heights.measured(rows.firstRow + index, high) != 0;
      // From the first row that exists, as rows of one height are; the scroll shift adds the rest.
      double first = rowTop(rows.firstRow);
      row.place(left, (float) (rowsTop() + rowTop(rows.firstRow + index) - first), inner, high);
      for (int i = index + 1; moved && i < children.size(); i++) { // those below follow
        El below = children.get(i);
        below.place(
            left, (float) (rowsTop() + rowTop(rows.firstRow + i) - first), inner, below.height);
      }
      return moved;
    }
    row.style.height = Len.px(rows.rowHeight);
    scaleText(row, scale, systemFonts, resolvedLineHeight);
    // Rounded where the row lies in the content, so that rows of a fractional height share their
    // edges with their neighbors, what fills them included; placed from the first row that exists.
    double top = rowsTop() + (rows.firstRow + index) * (double) rows.rowHeight;
    double whole = Math.floor(top);
    Flex.layout(row, inner, rows.rowHeight, 0, (float) (top - whole));
    row.contentTop = whole + row.y;
    row.place(left, (float) (row.contentTop - rowTop(rows.firstRow)), inner, row.height);
    return false;
  }

  /**
   * The offset that shows the row with this index whole with the least scrolling from the current
   * offset, clear of the bars over the list's bottom, or its top if it is taller than the rest.
   */
  double offsetShowing(int index) {
    double top = rowsTop() + rowTop(index) - scrollY;
    return scrollY + ScrollState.nearest(top, rowSize(index), height - coverY);
  }

  /** Where this row lies in the border box at the current offset, as {@code {top, height}}. */
  float[] rowBox(int index) {
    return new float[] {(float) (rowsTop() + rowTop(index) - scrollY), (float) rowSize(index)};
  }

  /**
   * Scrolls this scroll area just far enough to show the child with this index whole, counting
   * the children that are not leaving and clear of the bars over the area's ends, or its start if
   * it is larger; nothing if there is none.
   */
  void revealChild(int index) {
    for (El child : children) {
      if (!child.exiting && index-- == 0) {
        scrollTo(
            scrollX + ScrollState.nearest(child.x - scrollX, child.width, width - coverX),
            scrollY + ScrollState.nearest(child.y - scrollY, child.height, height - coverY));
        return;
      }
    }
  }

  /**
   * The first row that is whole inside the list's border box, or the last one for {@code last};
   * where none is whole, the first one in view. 0 for a list without rows.
   */
  int rowInView(boolean last) {
    double from = scrollY - rowsTop(); // the content at the top of the border box
    long top = rowAfter(from);
    long bottom = rowAt(from + height - coverY) - (rows.heights == null ? 1 : 0);
    if (rows.heights != null
        && rowTop((int) Math.min(bottom + 1, rows.rowCount)) > from + height - coverY) {
      bottom--; // the row at the bottom edge is not whole
    }
    if (top > bottom) {
      top = bottom = rowAt(from);
    }
    return Math.clamp(last ? bottom : top, 0, Math.max(rows.rowCount - 1, 0));
  }

  /**
   * The first and the last row wholly inside the list's border box above what a bar along its
   * bottom covers, the first in the high 32 bits, or -1 and -1 where none is: for {@link
   * #onRowsInView}.
   */
  long rowsInView() {
    if (rows == null) {
      return -1L; // -1 and -1
    }
    double from = scrollY - rowsTop(); // the content at the top of the border box
    long first = Math.max(rowAfter(from), 0); // below a padding taller than a row too
    long last = Math.min(rowAt(from + (height - coverY)) - 1, rows.rowCount - 1L);
    if (last < first) { // also without rows
      first = last = -1;
    }
    return first << 32 | last & 0xFFFFFFFFL;
  }

  /** Whether this element is fading out after a rebuild left it out; see {@link #motion}. */
  public boolean isExiting() {
    return exiting;
  }

  /**
   * Whether a spring ({@link #motion}) is moving this element's box in the frame shown: its
   * position or size is between where it was and where layout put it. The scene draws such a box,
   * and everything inside it, where it is, between physical pixels, and snaps the box to them once
   * it has come to rest.
   */
  public boolean isMoving() {
    return moving;
  }

  /** For {@link Animator}, as it writes the values of the moment. */
  void moving(boolean moving) {
    this.moving = moving;
  }

  /** For {@link App}, when this element starts to leave. */
  void exit() {
    this.exiting = true;
  }

  /** For {@link App}, which marks the focused element for {@link #dump}. */
  void focused(boolean focused) {
    this.focused = focused;
  }

  /** For {@link App}, which marks the element a press under the pointer would go to. */
  void hovered(boolean hovered) {
    this.hovered = hovered;
  }

  /**
   * For {@link App}: runs a look of {@link #whenHovered}, {@link #whenFocused}, {@link
   * #whenFocusWithin} or {@link #whenPressed}, named by {@code name}, and checks that it left alone what a look must not
   * change.
   */
  void apply(Consumer<El> look, String name) {
    List<El> before = new ArrayList<>(children);
    String keyBefore = key;
    Handlers handlersBefore = Handlers.copy(handlers);
    boolean wasFocusable = focusable;
    look.accept(this);
    String changed = !children.equals(before)
        ? "children"
        : !Objects.equals(key, keyBefore)
            ? "key"
            : !handlersBefore.sameInput(handlers)
                ? "handlers"
                : focusable != wasFocusable ? "focusable()" : null;
    if (changed != null) {
      StringBuilder line = new StringBuilder();
      TreeDumper.describe(line, this, 0, 0);
      throw new IllegalStateException(
          "The " + name + " look of " + line.toString().strip() + " changed its " + changed
              + ". A look may change only how its element looks and its layout properties; build"
              + " the children, the key and the handlers in the view.");
    }
  }

  /** For {@link App}, which shows the border color of the moment. */
  void borderColor(int argb) {
    this.borderColor = argb;
  }

  /**
   * Called by layout: the resolved padding, border and margin in logical pixels, each four values
   * indexed by {@link Side#ordinal()}.
   */
  public void placeEdges(float[] padding, float[] border, float[] margin) {
    if (padding.length != 4 || border.length != 4 || margin.length != 4) {
      throw new IllegalArgumentException("each edge array must have 4 values");
    }
    if (edges == null) {
      edges = new float[12];
    }
    System.arraycopy(padding, 0, edges, 0, 4);
    System.arraycopy(border, 0, edges, 4, 4);
    System.arraycopy(margin, 0, edges, 8, 4);
  }

  /**
   * The padding on {@code side} in logical pixels: a percentage as the last layout resolved it,
   * anything else as the style gives it now.
   */
  public float padding(Side side) {
    return edge(
        switch (side) {
          case TOP -> style.paddingTop;
          case BOTTOM -> style.paddingBottom;
          case LEFT -> style.paddingLeft;
          case RIGHT -> style.paddingRight;
        },
        side.ordinal());
  }

  /** The border width on {@code side} in logical pixels, as {@link #padding} gives padding. */
  public float border(Side side) {
    return edge(
        switch (side) {
          case TOP -> style.borderTop;
          case BOTTOM -> style.borderBottom;
          case LEFT -> style.borderLeft;
          case RIGHT -> style.borderRight;
        },
        4 + side.ordinal());
  }

  /** The margin on {@code side} in logical pixels, as {@link #padding} gives padding. */
  float margin(Side side) {
    return edge(
        switch (side) {
          case TOP -> style.marginTop;
          case BOTTOM -> style.marginBottom;
          case LEFT -> style.marginLeft;
          case RIGHT -> style.marginRight;
        },
        8 + side.ordinal());
  }

  // Pixels are read live, since a look may change them after layout; only layout knows a
  // percentage.
  private float edge(Len len, int index) {
    return len.kind() == Len.Kind.PERCENT && edges != null ? edges[index] : len.pixelsOrZero();
  }

  /**
   * Called by layout: the border box, with the position relative to the parent's border box as
   * layout sees it, without the parent's scroll offset.
   */
  public void place(float x, float y, float width, float height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.placed = true;
    this.cycleBased = false; // layout or motion placed it anew
    placeRanges(); // layout measured and placed the text before its box
  }

  /** False until layout has placed this element; elements with {@code display: none} stay so. */
  public boolean isPlaced() {
    return placed;
  }

  /**
   * The position relative to the parent's border box as shown: the parent's scroll offset is
   * already subtracted.
   */
  public float x() {
    return x - shiftX + (across == null || !fixedX ? 0 : (float) across.scrollX);
  }

  public float y() {
    return y - shiftY;
  }

  /** For {@link Animator}: the x passed to {@link #place}, without the parent's scroll shift. */
  float placedX() {
    return x;
  }

  /** For {@link Animator}: the y passed to {@link #place}, without the parent's scroll shift. */
  float placedY() {
    return y;
  }

  public float width() {
    return width;
  }

  public float height() {
    return height;
  }

  /**
   * The tree as text, one element per line, children indented by two spaces. After layout each
   * line ends with the element's rectangle in window coordinates, as {@code x,y widthxheight}, as
   * shown, that is, moved by the scroll offsets above it. The states the view set follow, those
   * that are set, in this order: {@code disabled} (also a field that {@link #editable
   * editable(null)} disables), {@code readonly} (a {@link #readOnly} field), {@code password} (a
   * {@link #masked} field, as UI Automation's {@code IsPassword}), {@code revealed} (one that is
   * {@link #revealed}; no WAI-ARIA name), {@code invalid}, {@code busy},
   * {@code selected},
   * {@code current}, {@code grabbed}, {@code modified} (not a WAI-ARIA state), {@code checked}, {@code checked=false} or {@code checked=mixed}, {@code expanded} or {@code
   * expanded=false}, {@code level=2}, {@code pos=3/10} (the position in the set and its size),
   * {@code sort=ascending} or {@code sort=descending}, {@code value="..."} and the {@link
   * #errorMessage} as {@code error="..."} and the {@link #warningMessage} as {@code
   * warning="..."}, as in {@code el
   * #row-3 role=treeitem label="public" 0,66 400x22 selected expanded level=3 pos=1/2}. A scroll
   * area that is scrolled adds its
   * offset, as {@code scroll x,y}. A virtual list ({@link #rows}) adds, before that, its rows that
   * exist and how many it has, as {@code rows 40-56/1000000}, or {@code rows -/1000000} when none
   * exist. A scroll area with {@link #scrollbars} adds after that the thumb of its bar along the
   * bottom and of its bar along the right edge, where it has them, as rectangles in window
   * coordinates: {@code thumb-x 18,190 120x6 thumb-y 218,40 6x24}. The line of the element that a
   * press under the pointer would go to, which is a scroll area while the pointer is over one of
   * its bars or drags a thumb, ends with {@code
   * hovered}, that of the element that has the keyboard focus with {@code
   * focused}, and that of an element that is leaving with {@code exiting}. A text element's line
   * starts with {@code text} instead of {@code el} and shows its string in quotes after the label;
   * a field's {@link #placeholder} comes before it as {@code placeholder="..."} unless it is
   * empty or the label too. An {@link #image}'s line starts with {@code image}.
   * A focused {@link #editable} field adds its caret after {@code focused} as {@code caret 3}, then
   * the other end of a selection as {@code anchor 0}, and the composition of an input method in
   * progress as {@code composing "..."}. A {@link #masked} field shows its mask as its string, also
   * while {@link #revealed},
   * the caret and the anchor as positions in it and the composition masked. A {@link #multiline}
   * field adds, before its scroll offset, the lines with a row in view and how many it has, as
   * {@code lines 12-20/100000}, from 1, and after its caret the caret's line and column, as
   * {@code caret 57 (3:12)}, both from 1. A string longer than {@link TreeDumper#QUOTED} UTF-16 units shows
   * only its start and its end, with its length (see {@link TreeDumper#quoteText}).
   *
   * <p>The {@link #shownOverlays overlays} follow the tree, in the order they are drawn, each
   * under a line that says where it is placed and beside what, followed by its tree indented by
   * two spaces, in window coordinates: {@code overlay bottom start gap 4 on #file role=button
   * label="File" 8,8 60x24}, {@code overlay at 120,80 bottom start on #tables role=list 16,40
   * 200x160} or {@code overlay center modal scrim on 0,0 400x300}; the anchor has its key, role,
   * label and rectangle as shown. Tests and agents read this, so the format only changes
   * deliberately.
   */
  public String dump() {
    return TreeDumper.dump(this);
  }
}
