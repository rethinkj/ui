package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * One element of the view tree: a box with a style, a look and children. Views build a fresh tree
 * with chained calls:
 *
 * <pre>{@code
 * col().center().gap(16).fill(0xFF101014)
 *     .child(el().key("switch").size(38, 22).radius(11).fill(0xFF3B82F6));
 * }</pre>
 */
public final class El {

  /** How many rows a {@link #rows virtual list} builds beyond its visible part, on each side. */
  public static final int OVERSCAN = 3;

  private final Style style = new Style();
  private final List<El> children = new ArrayList<>();
  private String key;
  private String role;
  private String label;
  private int fill;
  private float radius;
  private int borderColor;
  private List<Shadow> shadows = List.of();
  private Measure measure;
  private Text text;
  private IntConsumer onPress;
  private Consumer<Drag> onDrag;
  private int revealRow = -1;
  private boolean focusable;
  private Integer focusRing;
  private Predicate<Key> onKey;
  private Predicate<Key> onKeyUp;
  private Consumer<String> onText;
  private boolean editable;
  private Consumer<String> onEdit;
  private boolean readOnly;
  private int maxLength = -1;
  private Editor editor;
  private boolean focused;
  private Spring motion;
  private float opacity = 1;
  private boolean exiting;
  private WindowPart windowPart;
  private Consumer<El> hoverLook;
  private Consumer<El> focusLook;
  private Consumer<El> pressLook;
  private Cursor cursor;
  private boolean hovered;
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
  // The scroll offset of this element, how far it may go, and how far its children are moved by
  // it. The offset is a double so that a virtual list can be billions of pixels tall; the shift is
  // small, because rows are placed near the offset.
  private double scrollX;
  private double scrollY;
  private double maxScrollX;
  private double maxScrollY;
  private float shiftX;
  private float shiftY;
  // A virtual list: its rows, and the index of the row that is its first child.
  private int rowCount;
  private float rowHeight;
  private IntFunction<El> rowBuilder;
  private int firstRow;
  private boolean rowsBuilt; // for this tree; a rebuild of the view starts without rows
  private List<El> overlays; // null for none; apart from the children
  private Layer layer; // where this element goes as an overlay, null until a call says
  private List<El> shownOverlays = List.of(); // of a root: those placed above it, bottom first
  private PointHandler onSecondaryPress;
  private Consumer<El> restLook;
  private double restDelay;
  private Runnable onHoverFor;
  private double hoverDelay;

  /** Told a position in logical pixels of the window, as {@link #onSecondaryPress} is. */
  @FunctionalInterface
  public interface PointHandler {
    void accept(float x, float y);
  }

  /** Where an overlay goes and how it takes input, and after placement what it is beside. */
  static final class Layer {
    Side side = Side.BOTTOM;
    Style.Align align = Style.Align.START;
    float gap;
    float pointX = Float.NaN;
    float pointY = Float.NaN;
    boolean centered;
    boolean modal;
    int scrim;
    Runnable onDismiss;
    Object id; // its identity, which Animator sets
    long opened; // when it first appeared, which Animator counts
    El anchor; // as the last placement found it, in window coordinates
    float anchorX;
    float anchorY;
  }

  private El() {}

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

  // Look

  /** @param argb the colour as 0xAARRGGBB */
  public El fill(int argb) {
    this.fill = argb;
    return this;
  }

  public El radius(float pixels) {
    this.radius = pixels;
    return this;
  }

  /** @param argb the colour as 0xAARRGGBB */
  public El border(float width, int argb) {
    Len len = Len.px(width);
    style.borderTop = len;
    style.borderRight = len;
    style.borderBottom = len;
    style.borderLeft = len;
    this.borderColor = argb;
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
   * Makes this element and everything below it translucent: 0 is invisible, 1 (the default) is
   * opaque. An element at 0 draws nothing and takes no presses. With {@link #motion} it springs
   * like the other values.
   */
  public El opacity(float opacity) {
    if (!(opacity >= 0 && opacity <= 1)) {
      throw new IllegalArgumentException("opacity must be from 0 to 1, was " + opacity);
    }
    this.opacity = opacity;
    return this;
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
   * <p>The thumb's length is the share of the content in view, at least {@link
   * Scrollbars#minThumb}, and its place the offset. Dragging the thumb scrolls in proportion,
   * from the first pixel, from the start to the very end of a list of a million rows; a press on
   * the track beside it scrolls one page, the area's height or width, towards the pointer. Such a
   * press is the bar's alone: it is no press or drag of the content or of an ancestor, selects no
   * text and leaves the focus where it is, and while the thumb is dragged, the area counts as the
   * element under the pointer. The wheel scrolls as before. In a {@link #dump tree dump} the
   * area's line shows each thumb's rectangle.
   *
   * @param look the size and colours of the bars, or null for none, the default
   */
  public El scrollbars(Scrollbars look) {
    this.scrollbars = look;
    return this;
  }

  /**
   * Makes this element a virtual list: a vertical scroll area as {@link #scroll} makes, whose
   * content is {@code count} rows of the same height, of which only those in or near its visible
   * part exist as elements. A million rows cost no more per frame than a dozen.
   *
   * <pre>{@code
   * col().key("orders").grow(1).rows(orders.size(), 28, i -> orderRow(orders.get(i)))
   * }</pre>
   *
   * <p>{@link App} calls {@code row} with a row's index, from 0, for the rows inside the list's
   * visible part, which is its border box inside the window, and {@link #OVERSCAN} more on each
   * side: after every rebuild of the view, and when scrolling shows a row that does not exist yet.
   * That builds the list's rows anew, but not the view; scrolling within the rows that exist
   * builds nothing. {@code row} runs on the app's thread during {@link App#frame}, so it has to be
   * cheap, and like the view it only reads the state and returns a new element each time. Each row
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
   * #LAYOUT_LIMIT}. In a column that grows itself, the column also needs a minimum height of 0
   * (as {@code min-height: 0} in CSS), or the rows, up to that limit, are its minimum height.
   *
   * @param count the number of rows, 0 or more
   * @param rowHeight the height of every row in logical pixels, above 0
   * @param row builds the element of the row with this index
   * @see App#scrollToRow
   */
  public El rows(int count, float rowHeight, IntFunction<El> row) {
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
    this.rowCount = count;
    this.rowHeight = rowHeight;
    this.rowBuilder = Objects.requireNonNull(row, "row");
    // Layout sees all rows as one leaf, as tall as they are together up to LAYOUT_LIMIT: layout
    // works in floats, which beyond 2^24 pixels miss whole pixels when the list shrinks to fit.
    // The offsets of scrolling are doubles and reach the last row anyway.
    this.measure = (knownWidth, knownHeight, availableWidth, availableHeight) ->
        new Measure.Size(0, (float) Math.min(count * (double) rowHeight, LAYOUT_LIMIT));
    return this;
  }

  /**
   * The most a {@link #rows virtual list} contributes to layout as its content's height, 2^20
   * logical pixels: a list without a height of its own is at most this tall and scrolls beyond.
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

  /** @param argb the text colour as 0xAARRGGBB */
  public El color(int argb) {
    textOnly("color").color(argb);
    return this;
  }

  /**
   * States the colour behind this text, for when the scene cannot tell. Glyph edges are
   * corrected differently for text darker and lighter than its background (see {@link
   * com.rethinkj.ui.text.TextRendering}); the scene takes the background from the fills of the
   * text element and its ancestors, and misses what lies behind it otherwise, such as an earlier
   * sibling, an absolutely placed box or an image.
   *
   * @param argb the colour as 0xAARRGGBB, taken as opaque; 0, the default, lets the scene decide
   */
  public El textBackground(int argb) {
    textOnly("textBackground").background(argb);
    return this;
  }

  /** Lets the text break into more lines where it is wider than its box. */
  public El wrapText() {
    textOnly("wrapText").wraps(true);
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

  // Editing, for elements made with text()

  /**
   * Makes this text element a single-line text field whose string belongs to the app: the view
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
   * <p>The field is as tall as a line of its text, centred in its content box, and takes its
   * width from its style, as layout measures it 0 wide; the text scrolls sideways inside its
   * content box and is clipped to it. Line breaks in typed or pasted text become spaces.
   *
   * @param onEdit receives the new string after each edit; null disables the field, which then
   *     shows its string or placeholder, takes neither focus nor input, and lets presses go to its
   *     ancestors
   */
  public El editable(Consumer<String> onEdit) {
    textOnly("editable").field(true);
    this.editable = true;
    this.onEdit = onEdit;
    return this;
  }

  /**
   * Makes an {@link #editable} field read-only: it takes the focus, and its text can be selected
   * and copied, but not changed.
   */
  public El readOnly() {
    editableOnly("readOnly");
    this.readOnly = true;
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
    this.maxLength = codePoints;
    return this;
  }

  /**
   * The text an {@link #editable} field shows while its string is empty, in its own colour.
   *
   * @param argb the colour as 0xAARRGGBB
   */
  public El placeholder(String text, int argb) {
    editableOnly("placeholder").placeholder(text, argb);
    return this;
  }

  /**
   * @param argb the colour of an {@link #editable} field's caret as 0xAARRGGBB; 0 for the text's
   */
  public El caretColor(int argb) {
    editableOnly("caretColor").caretColor(argb);
    return this;
  }

  /**
   * @param argb the colour drawn behind the selected text of an {@link #editable} field, as
   *     0xAARRGGBB, usually translucent; {@link Text#SELECTION} unless set
   */
  public El selectionColor(int argb) {
    editableOnly("selectionColor").selectionColor(argb);
    return this;
  }

  private Text editableOnly(String call) {
    if (!editable) {
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

  // Behaviour

  /**
   * Runs {@code handler} when the pointer is pressed and released on this element or on a
   * descendant that has no press or drag handler of its own, without moving into a {@link #onDrag
   * drag} in between. Handlers run between frames, never while a view is built, and are the only
   * place where an app changes its state.
   */
  public El onPress(Runnable handler) {
    this.onPress = handler == null ? null : clicks -> handler.run();
    return this;
  }

  /**
   * Like {@link #onPress(Runnable)}, told the number of presses in quick succession at about the
   * same place that this one completes, as the window counts them: 1 for a single press, 2 for the
   * second of a double press, and so on. Enter and Space give 1.
   */
  public El onPress(IntConsumer handler) {
    this.onPress = handler;
    return this;
  }


  /**
   * Runs {@code handler} when the secondary button, usually the right one, is pressed and released
   * on this element or on a descendant that has no such handler of its own, with the position of
   * the release in logical pixels of the window: where a context menu opens ({@link #atPoint}).
   * The Menu key and Shift+F10 that no key handler takes run it too, for the focused element or
   * its nearest ancestor that has one, at the bottom left of the focused element. Such a press
   * focuses as a press of the primary button does; it does not drag, select text or press.
   *
   * @param handler told where, or null for none
   */
  public El onSecondaryPress(PointHandler handler) {
    this.onSecondaryPress = handler;
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
   * can work from the values of that view, as in {@code onDrag(d -> resize(width + d.dx()))}. It
   * gets {@link Drag.Phase#CANCEL} instead of the next step when the element has left the tree,
   * and when the pointer goes down again before a release. Like {@link #onPress}, it runs in
   * {@link App#frame}; the wheel scrolls during a drag as before.
   */
  public El onDrag(Consumer<Drag> handler) {
    this.onDrag = handler;
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
   * Sets the colour of the ring drawn around this element while it has the focus from the
   * keyboard or the app, as 0xAARRGGBB; 0 draws none. An element that sets none gets the app's
   * ({@link App#focusRing}). A view that draws parts of itself in different themes gives each of
   * its focusable elements the ring of its theme here.
   */
  public El focusRing(int argb) {
    this.focusRing = argb;
    return this;
  }

  /**
   * Gives {@code handler} the keys that go down while this element or one below it has the focus,
   * repeats included, before its ancestors get them. The handler returns whether it handled the
   * key; if it did, nobody else sees it. Without focus, keys go to the root element only. Enter
   * and Space press a focused element with an {@link #onPress} handler after its own key handler
   * and before its ancestors', and Tab and Shift+Tab that nobody handles move the focus. Handlers
   * run in {@link App#frame}, like {@link #onPress}.
   */
  public El onKey(Predicate<Key> handler) {
    this.onKey = handler;
    return this;
  }

  /** Like {@link #onKey}, for keys that are released. Released keys do nothing by default. */
  public El onKeyUp(Predicate<Key> handler) {
    this.onKeyUp = handler;
    return this;
  }

  /**
   * Gives {@code handler} the text typed or composed with an input method while this element has
   * the focus. Text goes to the focused element only, not to its ancestors.
   */
  public El onText(Consumer<String> handler) {
    this.onText = handler;
    return this;
  }

  /**
   * Lets this element spring to new values: when a rebuild of the view gives an element that was
   * already there a new rectangle, fill, border colour, radius, opacity or text colour, {@link App}
   * moves it there with {@code spring} instead of at once. Children are placed relative to their
   * parent and move with it.
   *
   * <p>An element that appears after the app's first frame fades in from opacity 0, in the place
   * layout gives it. One with a {@link #key} that a rebuild leaves out fades out where it was, with
   * everything below it, and is then removed. Until then it stays in the tree, marked {@link
   * #isExiting}, and takes no part in layout or input. If it comes back before, it goes on from
   * where it is.
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
   * CSS's {@code :hover} does: {@code el().fill(GREY).whenHovered(e -> e.fill(LIGHT_GREY))}. After
   * each rebuild of the view and before layout, {@link App} calls {@code look} with the element.
   * With {@link #motion} it springs to the changed values and back like after any other change.
   *
   * <p>A look may call what changes the element's appearance ({@link #fill}, {@link #radius},
   * {@link #border}, {@link #opacity}, {@link #cursor}) and its layout properties (size, spacing,
   * flow and {@link #style}). It must leave the children, the key, the handlers and {@link
   * #focusable} as the view built them; the frame in which it changes them throws an {@link
   * IllegalStateException}. When more than one look applies, the hovered one goes first, then
   * {@link #whenFocused} and then {@link #whenPressed}, so that the last one wins.
   *
   * <p>Only elements with a look cause a rebuild when the pointer reaches or leaves them: moving
   * the pointer over plain elements rebuilds nothing.
   *
   * @param look changes the element, or null for none
   */
  public El whenHovered(Consumer<El> look) {
    this.hoverLook = look;
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
    this.focusLook = look;
    return this;
  }

  /**
   * Changes how this element looks while the pointer presses it: from a pointer down that goes to
   * it, as for {@link #onPress}, to the next pointer up, wherever that is. Only an element with a
   * press or drag handler is pressed, and Enter and Space do not show this look. See {@link
   * #whenHovered} for what a look may change.
   *
   * @param look changes the element, or null for none
   */
  public El whenPressed(Consumer<El> look) {
    this.pressLook = look;
    return this;
  }

  /**
   * Changes how this element looks once the pointer has rested over it, or over elements inside
   * it, for {@code seconds} of the app's clock, until the pointer leaves it or presses: a tooltip,
   * which the look attaches as an {@link #overlay}. A press keeps the look away until the pointer
   * has left the element and come back. {@link App#nextChange} says when the look is due, so an
   * idle window wakes for it and presents nothing while it waits. Besides what {@link
   * #whenHovered} may change, such a look may attach overlays; it comes after the hover look.
   *
   * @param look changes the element, or null for none
   */
  public El whenHoveredFor(double seconds, Consumer<El> look) {
    this.restDelay = delay(seconds);
    this.restLook = look;
    return this;
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
    this.hoverDelay = delay(seconds);
    this.onHoverFor = handler;
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
   * is open: {@code button.overlay(s.menuOpen ? menu : null)}. The overlay is an ordinary tree,
   * with layout, text, scrolling, motion and presence; it gets its own clips, takes input before
   * the tree below it, and keys that it does not take go on to this element and its ancestors.
   * Its identity comes from this element and its key or index among this element's overlays, so
   * that one with a key and {@link #motion} fades in when it opens and out when it closes.
   *
   * <p>After layout, {@link App} lays the overlay out on its own, at most as large as the window,
   * and places it as {@link #side}, {@link #atPoint} or {@link #centerInWindow} say, beside this
   * element's rectangle as shown, moved by the scroll offsets above it; it follows the element
   * from frame to frame. It shows only while this element does: not while this element is clipped
   * away entirely, invisible or leaving. Overlays are drawn in the order they opened, a submenu
   * above its menu, each over everything opened before.
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
   * far as it needs. The default is below the anchor at its start, 0 away.
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

  /** Places this overlay in the middle of the window, as a dialog. */
  public El centerInWindow() {
    layer().centered = true;
    return this;
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
   * @param argb the colour as 0xAARRGGBB, usually translucent; 0, the default, for none
   */
  public El scrim(int argb) {
    layer().scrim = argb;
    return this;
  }

  /**
   * Tells the app that this overlay should close: a press outside it, or the wheel outside it
   * when it is not modal. The app closes it by changing its state, so that the next view leaves
   * it out; nothing closes by itself. Escape is the app's too, with an {@link #onKey} handler.
   *
   * @param handler runs in {@link App#frame}, or null for none
   */
  public El onDismiss(Runnable handler) {
    layer().onDismiss = handler;
    return this;
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

  public El child(El child) {
    noRows();
    children.add(child);
    return this;
  }

  public El children(El... children) {
    noRows();
    Collections.addAll(this.children, children);
    return this;
  }

  private void noRows() {
    if (rowBuilder != null) {
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

  /** The role set with {@link #role}; {@code textbox} for an {@link #editable} field without. */
  public String role() {
    return role == null && editable ? "textbox" : role;
  }

  public String label() {
    return label;
  }

  public int fill() {
    return fill;
  }

  public float radius() {
    return radius;
  }

  /** The shadows under this element, the topmost first; empty for none. */
  public List<Shadow> shadows() {
    return shadows;
  }

  public int borderColor() {
    return borderColor;
  }

  /** The press handler, which takes the number of presses; see {@link #onPress(IntConsumer)}. */
  public IntConsumer onPress() {
    return onPress;
  }

  public Consumer<Drag> onDrag() {
    return onDrag;
  }

  public PointHandler onSecondaryPress() {
    return onSecondaryPress;
  }

  Consumer<El> restLook() {
    return restLook;
  }

  double restDelay() {
    return restDelay;
  }

  Runnable onHoverFor() {
    return onHoverFor;
  }

  double hoverDelay() {
    return hoverDelay;
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
    return focusable || editable && (onEdit != null || readOnly);
  }

  /** The colour set with {@link #focusRing(int)}, or null if none was and the app's applies. */
  public Integer focusRing() {
    return focusRing;
  }

  public Predicate<Key> onKey() {
    return onKey;
  }

  public Predicate<Key> onKeyUp() {
    return onKeyUp;
  }

  public Consumer<String> onText() {
    return onText;
  }

  /** Whether {@link #editable} made this a text field, enabled or not. */
  public boolean isEditable() {
    return editable;
  }

  /** The handler of an {@link #editable} field, null when it is disabled. */
  public Consumer<String> onEdit() {
    return onEdit;
  }

  public boolean isReadOnly() {
    return readOnly;
  }

  /** The {@link #maxLength} in code points, or -1 for none. */
  public int maxLength() {
    return maxLength;
  }

  /** For {@link App}: the editing state of a field, which it keeps across rebuilds. */
  Editor editor() {
    return editor;
  }

  void editor(Editor editor) {
    this.editor = editor;
  }

  /** Whether this is a field that takes input: editable, and enabled or read-only. */
  boolean takesEdits() {
    return editable && isFocusable();
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

  /** For {@link Headless} and tests: text is shaped for the scale, without system fonts. */
  static void scaleText(El el, float scale) {
    scaleText(el, scale, false);
  }

  /**
   * For {@link App} and {@link Headless}: text is shaped for the scale of the window, and falls
   * back to the system's fonts if {@code systemFonts}.
   */
  static void scaleText(El el, float scale, boolean systemFonts) {
    if (el.text != null) {
      el.text.scale(scale);
      el.text.systemFonts(systemFonts);
    }
    for (El child : el.children) {
      scaleText(child, scale, systemFonts);
    }
    for (El overlay : el.overlays()) {
      scaleText(overlay, scale);
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

  /** Whether {@link #rows} made this a virtual list, whose children are the rows that exist. */
  public boolean isVirtualList() {
    return rowBuilder != null;
  }

  /** The number of rows of a {@link #rows virtual list}, 0 for any other element. */
  public int rowCount() {
    return rowCount;
  }

  /** The height of each row of a {@link #rows virtual list} in logical pixels. */
  public float rowHeight() {
    return rowHeight;
  }

  /**
   * The index of the row that is the first child of a {@link #rows virtual list}: the children
   * are the rows from this one on, as many as exist.
   */
  public int firstRow() {
    return firstRow;
  }

  /** Whether the row with this index exists as a child of this virtual list. */
  boolean hasRow(int index) {
    return index >= firstRow && index < firstRow + children.size();
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
   * The vertical offset of this scroll area: how far its content is moved up, 0 at the top. It is
   * a double, exact for the integers of a list of rows billions of pixels tall.
   */
  public double scrollY() {
    return scrollY;
  }

  /** Whether the offset along one axis can still move in the direction of {@code delta}. */
  boolean canScroll(boolean horizontal, float delta) {
    double offset = horizontal ? scrollX : scrollY;
    return delta > 0 ? offset < (horizontal ? maxScrollX : maxScrollY) : delta < 0 && offset > 0;
  }

  /**
   * Measures how far this element can scroll along the axes with {@code overflow: scroll}: to
   * where its farthest child ends, with its margin and this element's end padding and border.
   * Called right after layout, before anything springs. A virtual list reaches to the end of its
   * last row, whether it exists or not.
   */
  void measureScroll() {
    if (rowBuilder != null) {
      maxScrollX = 0;
      maxScrollY = Math.max(rowsTop() + rowCount * (double) rowHeight + px(style.paddingBottom)
          + px(style.borderBottom) - height, 0);
      return;
    }
    float right = 0;
    float bottom = 0;
    for (El child : children) {
      if (child.placed && !child.exiting) {
        right = Math.max(right, child.x + child.width + px(child.style.marginRight));
        bottom = Math.max(bottom, child.y + child.height + px(child.style.marginBottom));
      }
    }
    // shortcut: only the children's rectangles count, not what sticks out of them, and padding,
    // border and margin only in pixels, not in percent; measure the layout's content size when
    // nested content or percentages need it.
    maxScrollX =
        style.overflowX == Style.Overflow.SCROLL
            ? Math.max(right + px(style.paddingRight) + px(style.borderRight) - width, 0)
            : 0;
    maxScrollY =
        style.overflowY == Style.Overflow.SCROLL
            ? Math.max(bottom + px(style.paddingBottom) + px(style.borderBottom) - height, 0)
            : 0;
  }

  private static float px(Len len) {
    return len.kind() == Len.Kind.PX ? len.value() : 0;
  }

  /**
   * Sets the scroll offset, clamped to what {@link #measureScroll} found, and shifts the children
   * by it.
   *
   * @return whether the offset changed
   */
  boolean scrollTo(double x, double y) {
    double newX = Math.clamp(x, 0, maxScrollX);
    double newY = Math.clamp(y, 0, maxScrollY);
    boolean changed = newX != scrollX || newY != scrollY;
    scrollX = newX;
    scrollY = newY;
    // Rows are placed from the first one that exists, so the offset moves them from there.
    float shiftY = (float) (rowBuilder == null ? newY : newY - firstRow * (double) rowHeight);
    for (El child : children) {
      child.shiftX = (float) newX;
      child.shiftY = shiftY;
    }
    return changed;
  }

  // Scrollbars, for App and Headless

  /**
   * Places the bars of the scroll areas with {@link #scrollbars} below {@code root}, which layout
   * and scrolling have placed, in window coordinates and in their colour at rest. Each lies along
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
        float boxLeft = Math.max(left, x + px(s.borderLeft));
        float boxTop = Math.max(top, y + px(s.borderTop));
        float boxRight = Math.min(right, x + width - px(s.borderRight));
        float boxBottom = Math.min(bottom, y + height - px(s.borderBottom));
        boolean wide = s.overflowX == Style.Overflow.SCROLL && maxScrollX > 0;
        boolean tall = s.overflowY == Style.Overflow.SCROLL && maxScrollY > 0;
        if (wide && boxBottom - boxTop >= t + 2 * m) {
          barX = bar(false, boxBottom - m - t, boxLeft + m, boxRight - m - (tall ? t + m : 0));
        }
        if (tall && boxRight - boxLeft >= t + 2 * m) {
          barY = bar(true, boxRight - m - t, boxTop + m, boxBottom - m - (barX != null ? t + m : 0));
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
   * A bar whose track runs from {@code start} to {@code end} along its axis, at {@code across} on
   * the other; null when the track has no length.
   */
  private Scrollbars.Bar bar(boolean vertical, float across, float start, float end) {
    float track = end - start;
    if (!(track > 0)) {
      return null;
    }
    double view = vertical ? height : width;
    double max = vertical ? maxScrollY : maxScrollX;
    double offset = vertical ? scrollY : scrollX;
    float thumb = (float) Math.min(Math.max(track * view / (view + max), scrollbars.minThumb()),
        track);
    float at = start + (float) ((track - thumb) * (offset / max));
    float t = scrollbars.thickness();
    int colour = scrollbars.thumb();
    return vertical
        ? new Scrollbars.Bar(across, start, t, track, across, at, t, thumb, colour)
        : new Scrollbars.Bar(start, across, track, t, at, across, thumb, t, colour);
  }

  /**
   * The bar whose strip along the edge, its track and {@link Scrollbars#margin} around it, holds
   * this point of the window; the vertical one first, null for neither.
   */
  Scrollbars.Bar barAt(float x, float y) {
    for (Scrollbars.Bar bar : new Scrollbars.Bar[] {barY, barX}) {
      float m = scrollbars == null ? 0 : scrollbars.margin();
      if (bar != null && x >= bar.trackX() - m && x < bar.trackX() + bar.trackWidth() + m
          && y >= bar.trackY() - m && y < bar.trackY() + bar.trackHeight() + m) {
        return bar;
      }
    }
    return null;
  }

  /** For {@link App}, which shows a bar hovered or dragged in another colour. */
  void barColour(boolean vertical, int argb) {
    if (vertical && barY != null) {
      barY = barY.color(argb);
    } else if (!vertical && barX != null) {
      barX = barX.color(argb);
    }
  }

  /**
   * Scrolls so that the thumb of a bar of the last frame starts at {@code start}, in window
   * coordinates along its axis: the offset is in proportion to where the thumb is between the ends
   * of its track, so that both ends reach the ends of the content exactly.
   *
   * @return whether the offset changed
   */
  boolean dragThumb(boolean vertical, float start) {
    Scrollbars.Bar bar = vertical ? barY : barX;
    if (bar == null) {
      return false;
    }
    double free = vertical ? bar.trackHeight() - bar.thumbHeight()
        : bar.trackWidth() - bar.thumbWidth();
    double share = free > 0
        ? Math.clamp((start - (vertical ? bar.trackY() : bar.trackX())) / free, 0.0, 1.0) : 0;
    return vertical
        ? scrollTo(scrollX, share * maxScrollY)
        : scrollTo(share * maxScrollX, scrollY);
  }

  /** Scrolls one page, the height or width of the area, towards the end or, with -1, the start. */
  boolean page(boolean vertical, int direction) {
    return vertical
        ? scrollTo(scrollX, scrollY + direction * (double) height)
        : scrollTo(scrollX + direction * (double) width, scrollY);
  }

  // Virtual lists, for App and Headless. A row's index times rowHeight is exact in a double: a
  // float's 24 significant bits times an index below 2^29 fit into its 53.

  /** The top of the content box, where row 0 begins, relative to the border box. */
  private float rowsTop() {
    return px(style.borderTop) + px(style.paddingTop);
  }

  /**
   * The rows this virtual list should have, as {@code {from, to}} with {@code to} exclusive, or
   * null when it has them: those in its visible part, which is its border box inside the window,
   * and {@link #OVERSCAN} more on each side. A list that has every row in view keeps its rows, so
   * that scrolling by less than the overscan builds nothing.
   *
   * @param top the top of the border box in window coordinates
   */
  int[] wantedRows(float top, float windowHeight) {
    if (rowBuilder == null) {
      return null;
    }
    double row0 = top + rowsTop() - scrollY; // where row 0 is in the window
    double from = Math.max(top, 0);
    double to = Math.max(Math.min(top + height, windowHeight), from);
    int first = Math.clamp((long) Math.floor((from - row0) / rowHeight), 0, rowCount);
    int end = Math.clamp((long) Math.ceil((to - row0) / rowHeight), first, rowCount);
    if (rowsBuilt && first >= firstRow && end <= firstRow + children.size()) {
      return null;
    }
    return new int[] {
      Math.max(first - OVERSCAN, 0), (int) Math.min(end + (long) OVERSCAN, rowCount)
    };
  }

  /**
   * Replaces the rows of this virtual list by those from {@code from} to {@code to}, exclusive,
   * as its row function builds them. {@link #layoutRows} lays them out.
   */
  void makeRows(int from, int to) {
    children.clear();
    for (int i = from; i < to; i++) {
      El row = rowBuilder.apply(i);
      if (row == null) {
        throw new IllegalStateException("The row function of a list returned null for row " + i);
      }
      children.add(row);
    }
    firstRow = from;
    rowsBuilt = true;
  }

  /**
   * Lays out each row that {@link #makeRows} made on its own, with the list's inner width and the
   * row height, and places it below the previous one.
   */
  void layoutRows(float scale, boolean systemFonts) {
    float left = px(style.borderLeft) + px(style.paddingLeft);
    float inner =
        Math.max(width - left - px(style.paddingRight) - px(style.borderRight)
            - style.scrollbarWidth, 0);
    for (int i = 0; i < children.size(); i++) {
      El row = children.get(i);
      row.style.width = Len.px(inner);
      row.style.height = Len.px(rowHeight);
      scaleText(row, scale, systemFonts);
      Flex.layout(row, inner, rowHeight);
      row.place(left, (float) (rowsTop() + i * (double) rowHeight), inner, rowHeight);
    }
    scrollTo(scrollX, scrollY); // shifts the new rows
  }

  /**
   * The offset that shows the row with this index whole with the least scrolling from the current
   * offset, or its top if it is taller than the list.
   */
  double offsetShowing(int index) {
    double top = rowsTop() + index * (double) rowHeight - scrollY;
    double after = top + rowHeight - height;
    return scrollY + (top < 0 ? top : after > 0 ? Math.min(after, top) : 0);
  }

  /**
   * Scrolls this scroll area just far enough to show the child with this index whole, counting
   * the children that are not leaving, or its start if it is larger; nothing if there is none.
   */
  void revealChild(int index) {
    for (El child : children) {
      if (!child.exiting && index-- == 0) {
        scrollTo(scrollX + nearest(child.x - scrollX, child.width, width),
            scrollY + nearest(child.y - scrollY, child.height, height));
        return;
      }
    }
  }

  /** The least distance that moves a span into a view from 0 to {@code view}, or its start to 0. */
  private static double nearest(double start, float size, float view) {
    double after = start + size - view;
    return start < 0 ? start : after > 0 ? Math.min(after, start) : 0;
  }

  /**
   * The first row that is whole inside the list's border box, or the last one for {@code last};
   * where none is whole, the first one in view. 0 for a list without rows.
   */
  int rowInView(boolean last) {
    double from = scrollY - rowsTop(); // the content at the top of the border box
    long top = (long) Math.ceil(from / rowHeight);
    long bottom = (long) Math.floor((from + height) / rowHeight) - 1;
    if (top > bottom) {
      top = bottom = (long) Math.floor(from / rowHeight);
    }
    return Math.clamp(last ? bottom : top, 0, Math.max(rowCount - 1, 0));
  }

  /** Whether this element is fading out after a rebuild left it out; see {@link #motion}. */
  public boolean isExiting() {
    return exiting;
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
   * For {@link App}: runs a look of {@link #whenHovered}, {@link #whenFocused} or {@link
   * #whenPressed}, named by {@code name}, and checks that it left alone what a look must not
   * change.
   */
  void apply(Consumer<El> look, String name) {
    List<El> before = new ArrayList<>(children);
    String keyBefore = key;
    IntConsumer press = onPress;
    Consumer<Drag> drag = onDrag;
    Predicate<Key> down = onKey;
    Predicate<Key> up = onKeyUp;
    Consumer<String> text = onText;
    Consumer<String> edit = onEdit;
    boolean wasFocusable = focusable;
    look.accept(this);
    String changed =
        !children.equals(before) ? "children"
            : !Objects.equals(key, keyBefore) ? "key"
            : onPress != press || onDrag != drag || onKey != down
                || onKeyUp != up
                || onText != text || onEdit != edit ? "handlers"
            : focusable != wasFocusable ? "focusable()"
            : null;
    if (changed != null) {
      StringBuilder line = new StringBuilder();
      describe(line, 0, 0);
      throw new IllegalStateException(
          "The " + name + " look of " + line.toString().strip() + " changed its " + changed
              + ". A look may change only how its element looks and its layout properties; build"
              + " the children, the key and the handlers in the view.");
    }
  }

  /** For {@link App}, which shows the border colour of the moment. */
  void borderColor(int argb) {
    this.borderColor = argb;
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
    return x - shiftX;
  }

  public float y() {
    return y - shiftY;
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
   * shown, that is, moved by the scroll offsets above it. A scroll area that is scrolled adds its
   * offset, as {@code scroll x,y}. A virtual list ({@link #rows}) adds, before that, its rows that
   * exist and how many it has, as {@code rows 40-56/1000000}, or {@code rows -/1000000} when none
   * exist. A scroll area with {@link #scrollbars} adds after that the thumb of its bar along the
   * bottom and of its bar along the right edge, where it has them, as rectangles in window
   * coordinates: {@code thumb-x 18,190 120x6 thumb-y 218,40 6x24}. The line of the element that a
   * press under the pointer would go to, which is a scroll area while the pointer is over one of
   * its bars or drags a thumb, ends with {@code
   * hovered}, that of the element that has the keyboard focus with {@code
   * focused}, and that of an element that is leaving with {@code exiting}. A text element's line
   * starts with {@code text} instead of {@code el} and shows its string in quotes after the label.
   * A focused {@link #editable} field adds its caret after {@code focused} as {@code caret 3}, then
   * the other end of a selection as {@code anchor 0}, and the composition of an input method in
   * progress as {@code composing "..."}.
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
    StringBuilder out = new StringBuilder();
    dump(out, 0, 0, 0);
    for (El overlay : shownOverlays) {
      Layer l = overlay.layer;
      out.append("overlay");
      if (l.centered) {
        out.append(" center");
      } else {
        if (!Float.isNaN(l.pointX)) {
          number(out.append(" at "), l.pointX).append(',');
          number(out, l.pointY);
        }
        out.append(' ').append(l.side.name().toLowerCase(Locale.ROOT)).append(' ')
            .append(l.align.name().toLowerCase(Locale.ROOT));
        if (l.gap != 0) {
          number(out.append(" gap "), l.gap);
        }
      }
      if (l.modal) {
        out.append(" modal");
      }
      if (l.scrim >>> 24 != 0) {
        out.append(" scrim");
      }
      out.append(" on");
      El a = l.anchor;
      if (a.key != null) {
        out.append(" #").append(a.key);
      }
      if (a.role() != null) {
        out.append(" role=").append(a.role());
      }
      if (a.label != null) {
        out.append(" label=").append(quote(a.label));
      }
      number(out.append(' '), l.anchorX).append(',');
      number(out, l.anchorY).append(' ');
      number(out, a.width).append('x');
      number(out, a.height).append('\n');
      overlay.dump(out, 1, 0, 0);
    }
    return out.toString();
  }

  private void dump(StringBuilder out, int depth, float parentX, float parentY) {
    float left = parentX + x();
    float top = parentY + y();
    describe(out.repeat("  ", depth), left, top);
    for (El child : children) {
      child.dump(out, depth + 1, left, top);
    }
  }

  /** One line of {@link #dump}, for this element with its border box at this window position. */
  void describe(StringBuilder out, float left, float top) {
    out.append(text != null ? "text" : "el");
    if (key != null) {
      out.append(" #").append(key);
    }
    if (role() != null) {
      out.append(" role=").append(role());
    }
    if (label != null) {
      out.append(" label=").append(quote(label));
    }
    if (text != null) {
      out.append(' ').append(quote(text.string()));
    }
    if (windowPart != null) {
      out.append(" window=").append(windowPart.name().toLowerCase(Locale.ROOT));
    }
    if (placed) {
      out.append(' ');
      number(out, left).append(',');
      number(out, top).append(' ');
      number(out, width).append('x');
      number(out, height);
    }
    if (rowBuilder != null) {
      out.append(" rows ");
      if (children.isEmpty()) {
        out.append('-');
      } else {
        out.append(firstRow).append('-').append(firstRow + children.size() - 1);
      }
      out.append('/').append(rowCount);
    }
    if (scrollX != 0 || scrollY != 0) {
      number(out.append(" scroll "), scrollX).append(',');
      number(out, scrollY);
    }
    if (barX != null) {
      thumb(out.append(" thumb-x "), barX);
    }
    if (barY != null) {
      thumb(out.append(" thumb-y "), barY);
    }
    if (hovered) {
      out.append(" hovered");
    }
    if (focused) {
      out.append(" focused");
      if (editor != null) {
        out.append(" caret ").append(editor.caret());
        if (editor.anchor() != editor.caret()) {
          out.append(" anchor ").append(editor.anchor());
        }
        if (!editor.composition().isEmpty()) {
          out.append(" composing ").append(quote(editor.composition()));
        }
      }
    }
    if (exiting) {
      out.append(" exiting");
    }
    out.append('\n');
  }

  private static void thumb(StringBuilder out, Scrollbars.Bar bar) {
    number(out, bar.thumbX()).append(',');
    number(out, bar.thumbY()).append(' ');
    number(out, bar.thumbWidth()).append('x');
    number(out, bar.thumbHeight());
  }

  /**
   * The text in double quotes, with a backslash before each quote and backslash in it, line feeds
   * as {@code \n}, so that it stays on one line, and a character of a private use area, such as an
   * icon, as <code>&#92;u{E0EB}</code>, which would otherwise show as nothing or a box.
   */
  static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    text.codePoints().forEach(c -> {
      if (c == '\\' || c == '"') {
        out.append('\\').append((char) c);
      } else if (c == '\n') {
        out.append("\\n");
      } else if (Character.getType(c) == Character.PRIVATE_USE) {
        out.append("\\u{").append(Integer.toHexString(c).toUpperCase(Locale.ROOT)).append('}');
      } else {
        out.appendCodePoint(c);
      }
    });
    return out.append('"').toString();
  }

  // Not String.format: its decimal separator depends on the locale.
  private static StringBuilder number(StringBuilder out, double value) {
    long hundredths = Math.round(value * 100.0);
    if (hundredths < 0) {
      out.append('-');
      hundredths = -hundredths;
    }
    out.append(hundredths / 100);
    long fraction = hundredths % 100;
    if (fraction != 0) {
      out.append('.').append(fraction / 10);
      if (fraction % 10 != 0) {
        out.append(fraction % 10);
      }
    }
    return out;
  }
}
