package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A running app: a state object and a view function that builds the element tree from it.
 *
 * <p>The view only reads the state. Input finds the element it lands on and queues that element's
 * handler; {@link #frame} runs the queued handlers, which may change the state, and then builds
 * and lays out the view again. Without input nothing changes but movements under way.
 *
 * <p>The view only states where things end up. When a rebuild gives an element with {@link
 * El#motion} new values, the app springs it there over the following frames, without running the
 * view again. Time is the app's own clock, which only {@link #advance} moves; the tree always holds
 * the values at that time, so drawing and hit testing see what is on screen.
 *
 * <p>The app also keeps the offset of each scroll area ({@link El#scroll}), by the same identity
 * that matches elements across rebuilds, and writes it into the positions of the area's children.
 * The wheel and {@link #scrollTo} change it.
 *
 * <p>Keys and text go to the element that has the keyboard focus, which the app keeps by the same
 * identity: an element marked {@link El#focusable} gets it from Tab and Shift+Tab, from a pointer
 * press on it or inside it, or from {@link #focus}. Keys then go up through its ancestors until a
 * handler takes them ({@link El#onKey}); text goes to it alone ({@link El#onText}). When the
 * keyboard or the app moved the focus, the scene draws a ring around the focused element, and
 * scroll areas around it scroll it into view.
 *
 * <p>This class needs no window. A windowed runner feeds it the window's input and draws its
 * {@link #scene}; tests and agents feed it input themselves and look at {@link #render}.
 *
 * @param <S> the type of the state
 */
public final class App<S> {

  /** The colour of the focus ring until {@link #focusRing} changes it, as 0xAARRGGBB. */
  public static final int FOCUS_RING = 0xFF93C5FD;

  private final S state;
  private final Function<S, El> view;
  // Each returns whether it may have changed something that the next rebuild shows.
  private final ArrayDeque<BooleanSupplier> queued = new ArrayDeque<>();
  private final ArrayDeque<BooleanSupplier> scrolls = new ArrayDeque<>();
  private final Animator animator = new Animator();
  private double time;
  private float width;
  private float height;
  private float scale = 1;
  private boolean stale = true;
  private El root;
  private String pressed;
  private El focused; // in the current tree
  private Object focusId; // its identity, to find it in the next tree
  private boolean ring; // the keyboard or the app moved the focus: draw the ring
  private boolean reveal; // scroll the focused element into view after the next rebuild
  private int ringColour = FOCUS_RING;
  private final Atlas atlas = new Atlas();
  private TextRendering textRendering = TextRendering.platform();

  public App(S state, Function<S, El> view) {
    this.state = state;
    this.view = view;
  }

  public S state() {
    return state;
  }

  /**
   * Sets the size of the window in logical pixels and its scale in physical pixels per logical
   * pixel.
   */
  public void resize(float width, float height, float scale) {
    this.width = width;
    this.height = height;
    this.scale = scale;
    stale = true;
  }

  /**
   * The pointer's button went down at this position, in logical pixels. This gives the focus to the
   * element there, or to the nearest one around it, that is {@link El#focusable}, without a ring;
   * where there is none, it clears the focus.
   */
  public void pointerDown(float x, float y) {
    Target target = target(x, y);
    pressed = target == null ? null : target.path;
    Target focus = target(x, y, El::isFocusable);
    El el = focus == null ? null : focus.el;
    queued.add(() -> focus(el, false));
  }

  /** The button was released. A press counts when it began on the same element. */
  public void pointerUp(float x, float y) {
    Target target = target(x, y);
    if (target != null && target.path.equals(pressed)) {
      Runnable handler = target.el.onPress();
      queued.add(() -> {
        handler.run();
        return true;
      });
    }
    pressed = null;
  }

  /**
   * A key went down or repeats. The next {@link #frame} gives it to the {@link El#onKey} handlers
   * of the focused element and then of its ancestors, until one returns true; without focus only
   * the root's handler sees it. If none does, Enter and Space without modifiers press the focused
   * element if it has an {@link El#onPress} handler, which takes their repeats without pressing
   * again, and Tab moves the focus to the next focusable element in tree order, Shift+Tab to the
   * previous one, around at the ends. Unlike in a browser, a press handler on the focused element
   * takes Enter and Space before its ancestors' key handlers see them.
   */
  public void keyDown(Key key) {
    Objects.requireNonNull(key, "key");
    queued.add(() -> {
      List<El> path = focusPath();
      boolean called = false;
      for (El el : path.reversed()) {
        if (el.onKey() != null) {
          called = true;
          if (el.onKey().test(key)) {
            return true;
          }
        }
        if (el == focused
            && el.onPress() != null
            && key.modifiers() == 0
            && (key.name().equals("Enter") || key.name().equals("Space"))) {
          if (!key.repeat()) {
            el.onPress().run();
          }
          return true;
        }
      }
      if (key.name().equals("Tab") && (key.modifiers() & ~Key.SHIFT) == 0) {
        List<El> order = focusables();
        if (!order.isEmpty()) {
          int step = key.modifiers() == Key.SHIFT ? -1 : 1;
          int at = order.indexOf(focused);
          int next = at >= 0 ? at + step : step > 0 ? 0 : -1;
          return focus(order.get(Math.floorMod(next, order.size())), true);
        }
      }
      return called;
    });
  }

  /**
   * A key was released. The next {@link #frame} gives it to the {@link El#onKeyUp} handlers, in
   * the order of {@link #keyDown}; it does nothing else.
   */
  public void keyUp(Key key) {
    Objects.requireNonNull(key, "key");
    queued.add(() -> {
      boolean called = false;
      for (El el : focusPath().reversed()) {
        if (el.onKeyUp() != null) {
          called = true;
          if (el.onKeyUp().test(key)) {
            return true;
          }
        }
      }
      return called;
    });
  }

  /**
   * Text was typed or composed with an input method. The next {@link #frame} gives it to the {@link
   * El#onText} handler of the focused element; without one it is dropped.
   */
  public void textInput(String text) {
    Objects.requireNonNull(text, "text");
    queued.add(() -> {
      if (focused == null || focused.onText() == null) {
        return false;
      }
      focused.onText().accept(text);
      return true;
    });
  }

  /** The element of the last frame's {@link #tree} that has the keyboard focus, or null. */
  public El focused() {
    return focused;
  }

  /**
   * Gives the keyboard focus to an element of the last frame's {@link #tree} as Tab would, with a
   * ring and scrolled into view, or clears it with null. The next {@link #frame} shows it; a
   * handler may call this too, and the frame that runs it focuses. The app keeps the focus on the
   * element of later trees that has the same identity, and clears it once there is none, or when
   * that element is no longer focusable or takes no input.
   *
   * @throws IllegalArgumentException if the element is not {@link El#focusable}, not in the last
   *     frame's tree, or takes no input there: it is leaving, at opacity 0, or clipped away
   */
  public void focus(El el) {
    if (el != null && !focusables().contains(el)) {
      throw new IllegalArgumentException(
          "This element cannot take the focus: it is not focusable(), not in the last frame's tree"
              + " or takes no input there");
    }
    queued.add(() -> focus(el, true));
  }

  /**
   * Sets the colour of the focus ring, as 0xAARRGGBB; 0 draws none. The ring is 2 pixels wide and
   * lies just outside the element's border box, with its corners.
   */
  public void focusRing(int argb) {
    ringColour = argb;
    stale = true;
  }

  /**
   * Sets how text is rasterised and blended, {@link TextRendering#platform} until this is called.
   * Tests pin it, so that their images are the same on every platform.
   */
  public void textRendering(TextRendering rendering) {
    textRendering = Objects.requireNonNull(rendering, "rendering");
    stale = true;
  }

  /**
   * Focuses the element of the current tree that {@link #focusables} has, or none.
   *
   * @param ring whether the keyboard or the app moved the focus, not the pointer
   * @return whether a rebuild is due even if the focus ends where it was: to scroll it into view
   */
  private boolean focus(El el, boolean ring) {
    if (focused != null) {
      focused.focused(false);
    }
    focused = el;
    focusId = el == null ? null : animator.identity(el);
    this.ring = ring && el != null;
    reveal = this.ring;
    if (el != null) {
      el.focused(true);
    }
    return ring;
  }

  /**
   * Finds the focused element in a new tree by its identity, or clears the focus when it is gone or
   * can no longer take it, and scrolls it into view if that is due.
   */
  private void refocus() {
    if (focused == null) {
      return;
    }
    focused.focused(false);
    El el = animator.element(focusId);
    if (el == null || !focusables().contains(el)) {
      focused = null;
      focusId = null;
      ring = false;
      reveal = false;
      return;
    }
    focused = el;
    el.focused(true);
    if (reveal) {
      reveal = false;
      reveal(el);
    }
  }

  /** The focused element and its ancestors, from the root; only the root without focus. */
  private List<El> focusPath() {
    List<El> path = new ArrayList<>();
    if (root != null && (focused == null || !pathTo(root, focused, path))) {
      path.add(root);
    }
    return path;
  }

  /** Appends the path from {@code el} down to {@code target} if {@code target} is below it. */
  private static boolean pathTo(El el, El target, List<El> path) {
    path.add(el);
    if (el == target) {
      return true;
    }
    for (El child : el.children()) {
      if (pathTo(child, target, path)) {
        return true;
      }
    }
    path.removeLast();
    return false;
  }

  /**
   * Scrolls the scroll areas around {@code el}, the innermost first, just far enough to show its
   * border box whole inside theirs, or its start if it is larger.
   */
  private void reveal(El el) {
    List<El> path = new ArrayList<>();
    pathTo(root, el, path);
    for (int i = path.size() - 2; i >= 0; i--) {
      El area = path.get(i);
      if (!area.isScrollArea()) {
        continue;
      }
      float areaX = 0;
      float areaY = 0;
      for (int j = 0; j <= i; j++) {
        areaX += path.get(j).x();
        areaY += path.get(j).y();
      }
      float x = areaX;
      float y = areaY;
      for (int j = i + 1; j < path.size(); j++) {
        x += path.get(j).x();
        y += path.get(j).y();
      }
      area.scrollTo(
          area.scrollX() + nearest(x, el.width(), areaX, area.width()),
          area.scrollY() + nearest(y, el.height(), areaY, area.height()));
    }
  }

  /** The least distance that moves a span into a view, or its start onto the view's start. */
  private static float nearest(float start, float size, float viewStart, float viewSize) {
    float before = start - viewStart;
    float after = start + size - (viewStart + viewSize);
    return before < 0 ? before : after > 0 ? Math.min(after, before) : 0;
  }

  /**
   * The elements Tab moves through, in tree order: those that are focusable, take input and are not
   * clipped away for good. Along an axis an ancestor scrolls, an element outside its visible part
   * counts, since focusing it scrolls it into view.
   */
  List<El> focusables() {
    List<El> found = new ArrayList<>();
    if (root != null) {
      float far = Float.POSITIVE_INFINITY;
      focusables(root, 0, 0, -far, -far, far, far, found);
    }
    return found;
  }

  private static void focusables(
      El el, float parentX, float parentY, float clipLeft, float clipTop, float clipRight,
      float clipBottom, List<El> found) {
    if (!el.isPlaced() || el.opacity() == 0 || el.isExiting()) {
      return;
    }
    float left = parentX + el.x();
    float top = parentY + el.y();
    float right = left + el.width();
    float bottom = top + el.height();
    if (el.isFocusable()
        && left < clipRight && right > clipLeft && top < clipBottom && bottom > clipTop) {
      found.add(el);
    }
    Style s = el.style();
    if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(left, clipLeft);
      clipTop = Math.max(top, clipTop);
      clipRight = Math.min(right, clipRight);
      clipBottom = Math.min(bottom, clipBottom);
      if (!(clipRight > clipLeft && clipBottom > clipTop)) {
        return; // nothing below shows
      }
      float far = Float.POSITIVE_INFINITY;
      if (s.overflowX == Style.Overflow.SCROLL) {
        clipLeft = -far;
        clipRight = far;
      }
      if (s.overflowY == Style.Overflow.SCROLL) {
        clipTop = -far;
        clipBottom = far;
      }
    }
    for (El child : el.children()) {
      focusables(child, left, top, clipLeft, clipTop, clipRight, clipBottom, found);
    }
  }

  /**
   * For {@link Script}: focuses as {@link #focus} does the element at this point, or the nearest
   * focusable one around it, and returns it; null if there is none.
   */
  El focusAt(float x, float y) {
    Target target = target(x, y, El::isFocusable);
    if (target == null) {
      return null;
    }
    focus(target.el);
    return target.el;
  }

  /**
   * The wheel turned with the pointer at this position, by these distances in logical pixels.
   * Positive values scroll towards the end, so that the content moves up or to the left, as {@code
   * deltaY} of a browser's wheel event; SDL counts the other way. Along each axis the innermost
   * scroll area under the pointer that can still move that way takes the whole distance, clamped
   * to its content; when none can, nothing happens. The next {@link #frame} shows it.
   */
  public void wheel(float x, float y, float deltaX, float deltaY) {
    if (!Float.isFinite(deltaX) || !Float.isFinite(deltaY)) {
      throw new IllegalArgumentException("wheel deltas must be finite, were " + deltaX + ", "
          + deltaY);
    }
    List<El> areas = hit(x, y).reversed();
    scrolls.add(() -> scroll(areas, true, deltaX) | scroll(areas, false, deltaY)); // not ||
  }

  private static boolean scroll(List<El> areas, boolean horizontal, float delta) {
    for (El area : areas) {
      if (area.canScroll(horizontal, delta)) {
        return horizontal
            ? area.scrollTo(area.scrollX() + delta, area.scrollY())
            : area.scrollTo(area.scrollX(), area.scrollY() + delta);
      }
    }
    return false;
  }

  /**
   * Scrolls a scroll area of the last frame's {@link #tree} to this offset from its start, in
   * logical pixels: to the top with 0, 0, for example after new data arrived, or to the end with
   * {@link Float#POSITIVE_INFINITY}. The next {@link #frame} shows it, clamped to the content;
   * until then {@link El#scrollX} and {@link El#scrollY} keep the old offset. A handler may call
   * this too; the frame that runs it scrolls.
   */
  public void scrollTo(El area, float x, float y) {
    if (Float.isNaN(x) || Float.isNaN(y)) {
      throw new IllegalArgumentException("scroll offset is NaN");
    }
    scrolls.add(() -> area.scrollTo(x, y));
  }

  /**
   * Moves the app's clock forward. A windowed runner calls this once per frame with the time since
   * the last one; tests step it by hand. Nothing else reads a clock.
   */
  public void advance(double seconds) {
    if (!(seconds >= 0 && seconds < Double.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "seconds must be 0 or a positive finite number, was " + seconds);
    }
    time += seconds;
  }

  /** The app's clock in seconds, 0 at the start. */
  public double time() {
    return time;
  }

  /**
   * Runs the handlers queued by input, scrolls as the wheel and {@link #scrollTo} asked and, if
   * anything may have changed, builds and lays out the view again. Then it writes the values of
   * the moment into the elements that move.
   *
   * @return whether there is something new to draw: the tree was rebuilt, something moved or
   *     scrolled
   */
  public boolean frame() {
    El wasFocused = focused;
    boolean hadRing = ring;
    while (!queued.isEmpty()) {
      stale |= queued.poll().getAsBoolean();
    }
    stale |= focused != wasFocused || ring != hadRing;
    // After the handlers, so that one can scroll the tree it saw; a rebuild keeps the offsets.
    boolean scrolled = false;
    while (!scrolls.isEmpty()) {
      scrolled |= scrolls.poll().getAsBoolean();
    }
    if (stale) {
      root = view.apply(state);
      El.scaleText(root, scale);
      Flex.layout(root, width, height);
      animator.update(root, time);
      refocus();
      stale = false;
      return true;
    }
    if (!animator.isMoving()) {
      return scrolled;
    }
    animator.present(time);
    return true;
  }

  /**
   * Whether something had not reached its target in the last frame, so that the next frame draws
   * something new after {@link #advance} even without input. Once this is false the app is idle.
   */
  public boolean isMoving() {
    return animator.isMoving();
  }

  /**
   * The part of a self-drawn window frame at this position in logical pixels, which a window asks
   * while the pointer moves. The rule is the one presses follow: the topmost element under the
   * point decides, or its nearest ancestor that has a {@link El#windowPart} or a press handler. An
   * element with a handler and no part is content, so controls inside a title bar stay clickable.
   *
   * @return the part, {@link WindowPart#CONTENT} where nothing is marked
   */
  public WindowPart windowPart(float x, float y) {
    Target target = target(x, y, el -> el.windowPart() != null || el.onPress() != null);
    if (target == null || target.el.windowPart() == null) {
      return WindowPart.CONTENT;
    }
    return target.el.windowPart();
  }

  /** The tree of the last frame. */
  public El tree() {
    return root;
  }

  /**
   * What the last frame draws, with the focus ring if there is one. Its glyphs are in the app's
   * atlas, which keeps them from one scene to the next; a scene's glyphs stay there until the
   * next call.
   */
  public Scene scene() {
    return Scene.of(root, ring ? focused : null, ringColour, atlas, textRendering);
  }

  /** The last frame drawn on the CPU, with its tree as text. */
  public Headless.Frame render() {
    return new Headless.Frame(
        CpuRenderer.render(scene(), Math.round(width * scale), Math.round(height * scale), scale),
        root.dump());
  }

  private record Target(El el, String path) {}

  /**
   * The element a press at this position goes to: the topmost element under the point, or its
   * nearest ancestor with a handler. The path names it by child indexes, because the next frame
   * builds new elements.
   */
  private Target target(float x, float y) {
    return target(x, y, el -> el.onPress() != null);
  }

  /** The topmost element under the point, or its nearest ancestor that is {@code wanted}. */
  private Target target(float x, float y, Predicate<El> wanted) {
    List<El> hit = hit(x, y);
    StringBuilder path = new StringBuilder();
    Target target = null;
    for (int i = 0; i < hit.size(); i++) {
      El el = hit.get(i);
      if (i > 0) {
        path.append('/').append(hit.get(i - 1).children().indexOf(el));
      }
      if (wanted.test(el)) {
        target = new Target(el, path.toString());
      }
    }
    return target;
  }

  /** The elements under the point, from the root down to the topmost one; empty if none. */
  private List<El> hit(float x, float y) {
    List<El> hit = new ArrayList<>();
    if (root == null || !contains(root, 0, 0, x, y)) {
      return hit;
    }
    El el = root;
    float left = root.x();
    float top = root.y();
    while (true) {
      hit.add(el);
      // shortcut: a child that sticks out of its parent is not hit outside the parent's box. That
      // is also what clipping needs, so keep it for elements that clip when this changes.
      El next = null;
      List<El> children = el.children();
      // Later children are drawn on top, so they are asked first.
      for (int i = children.size() - 1; i >= 0 && next == null; i--) {
        if (contains(children.get(i), left, top, x, y)) {
          next = children.get(i);
        }
      }
      if (next == null) {
        return hit;
      }
      left += next.x();
      top += next.y();
      el = next;
    }
  }

  /**
   * Whether the point lies inside the element's rounded rectangle and the element takes input:
   * neither invisible nor leaving.
   */
  private static boolean contains(El el, float parentX, float parentY, float x, float y) {
    if (!el.isPlaced() || el.opacity() == 0 || el.isExiting()) {
      return false;
    }
    float halfW = el.width() / 2;
    float halfH = el.height() / 2;
    float radius = Math.clamp(el.radius(), 0, Math.min(halfW, halfH));
    float qx = Math.abs(x - (parentX + el.x() + halfW)) - halfW + radius;
    float qy = Math.abs(y - (parentY + el.y() + halfH)) - halfH + radius;
    if (qx <= 0 || qy <= 0) {
      return Math.max(qx, qy) <= radius;
    }
    return qx * qx + qy * qy <= radius * radius;
  }
}
