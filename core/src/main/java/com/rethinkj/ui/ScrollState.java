package com.rethinkj.ui;

/**
 * The scroll offsets of a scroll area, held by the app: {@link App} keeps an area's offsets by its
 * identity and forgets them when the area leaves the tree, as the content of a tab that is not
 * active does, or moves into the tree of another app, as a tab dragged into a window of its own.
 * An area built with one of these ({@link El#scrollState}) takes its offsets from there instead
 * and writes them there whenever they change, so that it comes back where it was:
 *
 * <pre>{@code
 * final class Tab { final ScrollState rows = new ScrollState(); }
 *
 * El.col().rows(count, 22, i -> row(i)).scrollState(tab.rows)
 * }</pre>
 *
 * <p>The app keeps one per area, as it keeps a {@link FieldState} per field, and gives it to the
 * same area on every build; two areas of one tree cannot share one. Offsets are clamped to the
 * content as always, and a {@link El#revealRow} or {@link El#reveal} that the area had before it
 * left counts as seen, so that it comes back without scrolling to them.
 */
public final class ScrollState {

  double x;
  double y;
  // The element that has it now, the only one whose scrolling writes here: not the one of an
  // older tree, which may still fade out or wait in another app for that app's rebuild.
  El el;
  // The animator and the pass in which an element took it, to find two areas with one.
  Object owner;
  long pass = -1;
  Object id;
  // What the area last kept in view, for an area that comes back.
  boolean seen;
  int revealRow = -1;
  Object revealKey;

  public ScrollState() {}

  /** The horizontal offset in logical pixels, as the area last had it. */
  public double x() {
    return x;
  }

  /** The vertical offset in logical pixels, as the area last had it. */
  public double y() {
    return y;
  }

  /**
   * Sets the offsets the area takes at the next build of the view, clamped to its content then:
   * {@code scrollTo(0, 0)} for its start, as after a new query result.
   *
   * @throws IllegalArgumentException if an offset is NaN
   */
  public void scrollTo(double x, double y) {
    if (Double.isNaN(x) || Double.isNaN(y)) {
      throw new IllegalArgumentException("scroll offset is NaN");
    }
    this.x = x;
    this.y = y;
  }
}
