package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Scene;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

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
 * <p>This class needs no window. A windowed runner feeds it the window's input and draws its
 * {@link #scene}; tests and agents feed it input themselves and look at {@link #render}.
 *
 * @param <S> the type of the state
 */
public final class App<S> {

  private final S state;
  private final Function<S, El> view;
  private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
  private final ArrayDeque<BooleanSupplier> scrolls = new ArrayDeque<>();
  private final Animator animator = new Animator();
  private double time;
  private float width;
  private float height;
  private float scale = 1;
  private boolean stale = true;
  private El root;
  private String pressed;

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

  /** The pointer's button went down at this position, in logical pixels. */
  public void pointerDown(float x, float y) {
    Target target = target(x, y);
    pressed = target == null ? null : target.path;
  }

  /** The button was released. A press counts when it began on the same element. */
  public void pointerUp(float x, float y) {
    Target target = target(x, y);
    if (target != null && target.path.equals(pressed)) {
      queued.add(target.el.onPress());
    }
    pressed = null;
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
    while (!queued.isEmpty()) {
      queued.poll().run();
      stale = true;
    }
    // After the handlers, so that one can scroll the tree it saw; a rebuild keeps the offsets.
    boolean scrolled = false;
    while (!scrolls.isEmpty()) {
      scrolled |= scrolls.poll().getAsBoolean();
    }
    if (stale) {
      root = view.apply(state);
      Flex.layout(root, width, height);
      animator.update(root, time);
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

  /** The tree of the last frame. */
  public El tree() {
    return root;
  }

  /** What the last frame draws. */
  public Scene scene() {
    return Scene.of(root);
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
    List<El> hit = hit(x, y);
    StringBuilder path = new StringBuilder();
    Target target = null;
    for (int i = 0; i < hit.size(); i++) {
      El el = hit.get(i);
      if (i > 0) {
        path.append('/').append(hit.get(i - 1).children().indexOf(el));
      }
      if (el.onPress() != null) {
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
