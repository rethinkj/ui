package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Scene;
import java.util.ArrayDeque;
import java.util.List;
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
 * <p>This class needs no window. A windowed runner feeds it the window's input and draws its
 * {@link #scene}; tests and agents feed it input themselves and look at {@link #render}.
 *
 * @param <S> the type of the state
 */
public final class App<S> {

  private final S state;
  private final Function<S, El> view;
  private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
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
   * Runs the handlers queued by input and, if anything may have changed, builds and lays out the
   * view again. Then it writes the values of the moment into the elements that move.
   *
   * @return whether there is something new to draw: the tree was rebuilt or something moved
   */
  public boolean frame() {
    while (!queued.isEmpty()) {
      queued.poll().run();
      stale = true;
    }
    if (stale) {
      root = view.apply(state);
      Flex.layout(root, width, height);
      animator.update(root, time);
      stale = false;
      return true;
    }
    if (!animator.isMoving()) {
      return false;
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
    return target(x, y, el -> el.onPress() != null);
  }

  /** The topmost element under the point, or its nearest ancestor that is {@code wanted}. */
  private Target target(float x, float y, Predicate<El> wanted) {
    if (root == null || !contains(root, 0, 0, x, y)) {
      return null;
    }
    El el = root;
    float left = root.x();
    float top = root.y();
    StringBuilder path = new StringBuilder();
    Target target = null;
    while (true) {
      if (wanted.test(el)) {
        target = new Target(el, path.toString());
      }
      // shortcut: a child that sticks out of its parent is not hit outside the parent's box.
      El next = null;
      List<El> children = el.children();
      // Later children are drawn on top, so they are asked first.
      for (int i = children.size() - 1; i >= 0 && next == null; i--) {
        if (contains(children.get(i), left, top, x, y)) {
          next = children.get(i);
          path.append('/').append(i);
        }
      }
      if (next == null) {
        return target;
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
