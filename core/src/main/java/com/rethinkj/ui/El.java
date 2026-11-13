package com.rethinkj.ui;

import com.rethinkj.ui.motion.Spring;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
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

  private final Style style = new Style();
  private final List<El> children = new ArrayList<>();
  private String key;
  private String role;
  private String label;
  private int fill;
  private float radius;
  private int borderColor;
  private Measure measure;
  private Runnable onPress;
  private boolean focusable;
  private Predicate<Key> onKey;
  private Predicate<Key> onKeyUp;
  private Consumer<String> onText;
  private boolean focused;
  private Spring motion;
  private float opacity = 1;
  private boolean exiting;
  private WindowPart windowPart;
  private boolean scrollArea;
  private boolean placed;
  private float x;
  private float y;
  private float width;
  private float height;
  // The scroll offset of this element, how far it may go, and the offset of its parent.
  private float scrollX;
  private float scrollY;
  private float maxScrollX;
  private float maxScrollY;
  private float shiftX;
  private float shiftY;

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

  // Behaviour

  /**
   * Runs {@code handler} when the pointer is pressed and released on this element or on a
   * descendant that has no handler of its own. Handlers run between frames, never while a view is
   * built, and are the only place where an app changes its state.
   */
  public El onPress(Runnable handler) {
    this.onPress = handler;
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
   * already there a new rectangle, fill, border colour, radius or opacity, {@link App} moves it
   * there with {@code spring} instead of at once. Children are placed relative to their parent and
   * move with it.
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

  // Tree

  public El child(El child) {
    children.add(child);
    return this;
  }

  public El children(El... children) {
    Collections.addAll(this.children, children);
    return this;
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

  public String role() {
    return role;
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

  public int borderColor() {
    return borderColor;
  }

  public Runnable onPress() {
    return onPress;
  }

  public Spring motion() {
    return motion;
  }

  public boolean isFocusable() {
    return focusable;
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

  public WindowPart windowPart() {
    return windowPart;
  }

  public Measure measure() {
    return measure;
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

  /**
   * The horizontal offset of this scroll area in logical pixels: how far its content is moved to
   * the left, 0 at the start. The positions of the children already take it into account. {@link
   * App#scrollTo} changes it.
   */
  public float scrollX() {
    return scrollX;
  }

  /** The vertical offset of this scroll area: how far its content is moved up, 0 at the top. */
  public float scrollY() {
    return scrollY;
  }

  /** Whether the offset along one axis can still move in the direction of {@code delta}. */
  boolean canScroll(boolean horizontal, float delta) {
    float offset = horizontal ? scrollX : scrollY;
    return delta > 0 ? offset < (horizontal ? maxScrollX : maxScrollY) : delta < 0 && offset > 0;
  }

  /**
   * Measures how far this element can scroll along the axes with {@code overflow: scroll}: to
   * where its farthest child ends, with its margin and this element's end padding and border.
   * Called right after layout, before anything springs.
   */
  void measureScroll() {
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
  boolean scrollTo(float x, float y) {
    float newX = Math.clamp(x, 0, maxScrollX);
    float newY = Math.clamp(y, 0, maxScrollY);
    boolean changed = newX != scrollX || newY != scrollY;
    scrollX = newX;
    scrollY = newY;
    for (El child : children) {
      child.shiftX = newX;
      child.shiftY = newY;
    }
    return changed;
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
   * offset, as {@code scroll x,y}, the line of the element that has the keyboard focus ends with
   * {@code focused}, and that of an element that is leaving with {@code exiting}. Tests and agents
   * read this, so the format only changes deliberately.
   */
  public String dump() {
    StringBuilder out = new StringBuilder();
    dump(out, 0, 0, 0);
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
    out.append("el");
    if (key != null) {
      out.append(" #").append(key);
    }
    if (role != null) {
      out.append(" role=").append(role);
    }
    if (label != null) {
      out.append(" label=").append(quote(label));
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
    if (scrollX != 0 || scrollY != 0) {
      number(out.append(" scroll "), scrollX).append(',');
      number(out, scrollY);
    }
    if (focused) {
      out.append(" focused");
    }
    if (exiting) {
      out.append(" exiting");
    }
    out.append('\n');
  }

  /** The text in double quotes, with a backslash before each quote and backslash in it. */
  static String quote(String text) {
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  // Not String.format: its decimal separator depends on the locale.
  private static StringBuilder number(StringBuilder out, float value) {
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
