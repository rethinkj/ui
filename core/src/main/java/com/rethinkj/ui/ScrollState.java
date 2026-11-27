package com.rethinkj.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
 * El.col().rows(count, 22, index -> row(index)).scrollState(tab.rows)
 * }</pre>
 *
 * <p>The app keeps one per area, as it keeps a {@link FieldState} per field, and gives it to the
 * same area on every build; two areas of one tree cannot share one. Offsets are clamped to the
 * content as always, and a {@link El#revealRow} or {@link El#reveal} that the area had before it
 * left counts as seen, so that it comes back without scrolling to them. Two areas can follow each
 * other's scrolling with {@link #scrollWith}, and an area can stay at its vertical end as content
 * grows with {@link #followEnd}.
 */
public final class ScrollState {

  /**
   * A pair of 0-based line indices that scroll together between two coupled multi-line fields
   * ({@link #scrollWith}): {@code line} in this area aligns with {@code otherLine} in the partner.
   *
   * @param line 0-based line index in this area
   * @param otherLine 0-based line index in the partner area
   */
  public record Anchor(int line, int otherLine) {
    public Anchor {
      if (line < 0 || otherLine < 0) {
        throw new IllegalArgumentException(
            "anchor lines must be 0 or more, were " + line + " and " + otherLine);
      }
    }
  }

  private static final Comparator<Anchor> BY_LINE =
      Comparator.comparingInt(Anchor::line).thenComparingInt(Anchor::otherLine);

  double x;
  double y;
  boolean followEnd;
  boolean atStart = true;
  boolean atEnd = true;
  // The answers atStart() and atEnd() gave since the view was last built again for them.
  private boolean toldStart;
  private boolean toldBelow;
  private boolean toldEnd;
  private boolean toldAbove;
  private int topRow;
  // The row topRow() last gave the view, or -1 when not asked since the last change.
  private int toldTopRow = -1;
  // Asked for its end by scrollToEnd until its rows are measured: those rows keep it there.
  boolean toEnd;
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
  // Bidirectional scroll coupling: partner and sorted anchors from this area's lines to partner's.
  ScrollState partner;
  List<Anchor> anchors = List.of();
  // True after scrollTo() so the next build moves the partner once both areas are attached.
  boolean followPending;
  // The app's scrollTo since the last build: the offset is the app's, not one to keep rows at.
  boolean placed;
  // The axes along which this area moved its partner since the app last settled them: once both
  // fields are laid out and measured what came into view, the partner follows again.
  boolean followX;
  boolean followY;

  public ScrollState() {
    // scrolled to the top left, with nothing to keep in view: an area takes it over at its build
  }

  /** The least distance that moves a span into a view from 0 to {@code view}, or its start to 0. */
  static double nearest(double start, double size, float view) {
    return nearestEdge(start, start + size - view);
  }

  /** The scroll delta given the signed distances of the two edges from their viewport edges. */
  static double nearestEdge(double before, double after) {
    return before < 0 ? before : after > 0 ? Math.min(after, before) : 0;
  }

  /** The horizontal offset in logical pixels, as the area last had it. */
  public double x() {
    return x;
  }

  /** The vertical offset in logical pixels, as the area last had it. */
  public double y() {
    return y;
  }

  /**
   * Keeps the area at its vertical end as its content grows (new children or rows, or a taller
   * last row) while it is already at its end, as a chat transcript or log does. Scrolling away
   * from the end stops following; reaching the end again, or calling {@link #scrollToEnd}, resumes
   * it.
   *
   * @return this scroll state
   */
  public ScrollState followEnd() {
    followEnd = true;
    return this;
  }

  /**
   * Whether the area's vertical offset was at or above its top when last laid out (also {@code
   * true} when the content fits). A view that asks is built again in the frame in which layout
   * or scrolling changes the answer, as with {@link #atEnd}.
   */
  public boolean atStart() {
    toldStart |= atStart;
    toldBelow |= !atStart;
    return atStart;
  }

  /**
   * Whether the area's vertical offset was at its bottom end when last laid out (also {@code true}
   * when the content fits and nothing scrolls). A view that asks is built again in the frame in
   * which layout or scrolling changes the answer, so that a "Jump to newest" button shows exactly
   * while the area is away from its end.
   */
  public boolean atEnd() {
    toldEnd |= atEnd;
    toldAbove |= !atEnd;
    return atEnd;
  }

  /** Whether the view has asked for one of the answers this state tracks. */
  boolean observed() {
    return toldTopRow >= 0 || toldStart || toldBelow || toldEnd || toldAbove;
  }

  /** Forgets answers that scrolling or layout has changed and asks for another build. */
  boolean answersChanged(int row) {
    boolean changed = false;
    if (atStart ? toldBelow : toldStart) {
      toldStart = false;
      toldBelow = false;
      changed = true;
    }
    if (atEnd ? toldAbove : toldEnd) {
      toldEnd = false;
      toldAbove = false;
      changed = true;
    }
    topRow = row;
    if (toldTopRow >= 0 && toldTopRow != topRow) {
      toldTopRow = -1;
      changed = true;
    }
    return changed;
  }

  /**
   * The row of a {@link El#rows virtual list} at the top of its content box when last laid out:
   * the last row that starts at or above the vertical offset (clamped to {@code 0..rowCount-1}),
   * or 0 for an area that is not a virtual list, has no rows, or has not been laid out yet. A view
   * that asks is built again in the frame in which layout or scrolling changes the answer, as with
   * {@link #atEnd}.
   */
  public int topRow() {
    toldTopRow = topRow;
    return topRow;
  }

  /**
   * Moves the area to its vertical end at the next build of the view, keeping its horizontal
   * offset. When {@link #followEnd} is on, following resumes from there.
   */
  public void scrollToEnd() {
    scrollTo(x, Double.POSITIVE_INFINITY);
  }

  /**
   * Sets the offsets the area takes at the next build of the view, clamped to its content then:
   * {@code scrollTo(0, 0)} for its start, as after a new query result. When this state is coupled
   * with another ({@link #scrollWith}), the partner follows on that build.
   *
   * @throws IllegalArgumentException if an offset is NaN
   */
  public void scrollTo(double x, double y) {
    if (Double.isNaN(x) || Double.isNaN(y)) {
      throw new IllegalArgumentException("scroll offset is NaN");
    }
    // The partner follows along the axes the app moved, at the build.
    boolean movedX = x != this.x;
    boolean movedY = y != this.y;
    followX |= movedX;
    followY |= movedY;
    followPending = followX || followY;
    placed = true;
    this.x = x;
    this.y = y;
    toEnd = y == Double.POSITIVE_INFINITY;
    atStart = y <= 0 || atStart && !movedY;
    atEnd = toEnd || atEnd && !movedY; // sideways keeps following
    if (partner != null) {
      partner.followX &= !movedX;
      partner.followY &= !movedY;
      partner.followPending &= partner.followX || partner.followY;
    }
  }

  /**
   * Couples scrolling between this area and {@code other} both ways: scrolling either one moves
   * the other, and calling this on either one is enough. Calling it again replaces the anchors;
   * passing {@code null} for {@code other} ends the coupling for both.
   *
   * <p>For two {@link El#multiline multi-line} fields, vertical scrolling maps the leader's top
   * fractional line (counting blocks between lines) piecewise linearly between surrounding {@code
   * anchors}, shifted by the first or last anchor's difference outside them (or line {@code n} to
   * line {@code n} when {@code anchors} is empty), and converts back to the follower's pixel offset
   * by its own line tops. Horizontally both keep the same offset. {@link El.Block#paired Paired}
   * blocks with equal pair keys take the larger of their heights in both fields in the same frame,
   * growing and shrinking together; a block that is not yet measured takes its partner's height
   * once measured, or uses the same single-line estimate on both sides. A partner not in the
   * current tree is left alone.
   *
   * @param other the partner scroll state, or {@code null} to uncouple
   * @param anchors line pairs aligning this area's lines with {@code other}'s; empty (or {@code
   *     null}) maps line {@code n} to line {@code n}
   * @throws IllegalArgumentException if {@code other} is this scroll state itself, or if two
   *     anchors do not both go further down their fields: each line and each other line at most
   *     once, in the same order
   */
  public void scrollWith(ScrollState other, List<Anchor> anchors) {
    if (other == this) {
      throw new IllegalArgumentException("a ScrollState cannot scroll with itself");
    }
    List<Anchor> forth = sorted(anchors == null ? List.of() : anchors);
    if (partner != other) {
      uncouple();
      if (other != null) {
        other.uncouple();
      }
    }
    if (other == null) {
      return;
    }
    partner = other;
    this.anchors = forth;
    other.partner = this;
    other.anchors = forth.stream()
        .map(anchor -> new Anchor(anchor.otherLine(), anchor.line()))
        .toList();
  }

  /** Ends the coupling of this area and its partner, with any follow either had pending. */
  private void uncouple() {
    if (partner == null) {
      return; // a follow the app asked for before the first pairing goes to the new partner
    }
    partner.partner = null;
    partner.anchors = List.of();
    partner.dropFollow();
    partner = null;
    anchors = List.of();
    dropFollow();
  }

  private void dropFollow() {
    followPending = false;
    followX = false;
    followY = false;
  }

  /** The anchors by line, checked to go down both fields in the same order. */
  private static List<Anchor> sorted(List<Anchor> anchors) {
    List<Anchor> list = new ArrayList<>(List.copyOf(anchors));
    list.sort(BY_LINE);
    for (int i = 1; i < list.size(); i++) {
      Anchor before = list.get(i - 1);
      Anchor anchor = list.get(i);
      if (anchor.line() <= before.line() || anchor.otherLine() <= before.otherLine()) {
        throw new IllegalArgumentException(
            "anchors must go down both fields, line by line, were " + before + " and " + anchor);
      }
    }
    return List.copyOf(list);
  }

  /**
   * Moves the coupled partner in the same tree to follow {@code leader} along the axes it moved,
   * and those it moved since the app last settled them, without looping back.
   */
  void follow(El leader, boolean movedX, boolean movedY) {
    if (partner == null) {
      return;
    }
    followX |= movedX;
    followY |= movedY;
    partner.followX &= !movedX; // this one leads along the axes it moved
    partner.followY &= !movedY;
    if (owner instanceof Animator animator && animator.defer(this)) {
      return;
    }
    if (!inSameTree(partner)) {
      followX = false;
      followY = false;
      return;
    }
    El target = partner.el;
    Text from = leader.text();
    Text to = target.text();
    boolean byLines = from != null && from.multiline() && to != null && to.multiline();
    double tx = followX ? leader.scrollX() : target.scrollX();
    double ty = target.scrollY();
    if (followY && !byLines) {
      ty = leader.scrollY();
    } else if (followY && from.laidOut() && to.laidOut()) {
      ty = to.lineToOffset(mapLine(from.offsetToLine(leader.scrollY()), anchors));
    }
    target.setScroll(tx, ty);
    if (!byLines) { // offsets in pixels need no second look after layout
      followX = false;
      followY = false;
    }
  }

  /**
   * Follows once more along the axes still to settle, now that layout measured the lines and
   * widths that came into view in both fields.
   *
   * @return whether the partner moved
   */
  boolean refollow(El leader) {
    El target = partner == null ? null : partner.el;
    if (target == null || !(followX || followY)) {
      return false;
    }
    double x = target.scrollX();
    double y = target.scrollY();
    follow(leader, false, false);
    return target.scrollX() != x || target.scrollY() != y;
  }

  /** The coupled partner element in the same tree, or null if uncoupled or not shown. */
  El partnerElement() {
    return partner != null && inSameTree(partner) ? partner.el : null;
  }

  private boolean inSameTree(ScrollState other) {
    return other.owner == owner
        && other.pass == pass
        && other.el != null
        && !other.el.isExiting()
        && owner instanceof Animator animator
        && animator.element(other.id) == other.el;
  }

  /**
   * Maps a fractional line in the leader to the corresponding fractional line in the follower:
   * piecewise linear between surrounding anchors, shifted by the first or last anchor's difference
   * outside them, and identity when there are no anchors.
   */
  static double mapLine(double line, List<Anchor> anchors) {
    if (anchors.isEmpty()) {
      return line;
    }
    Anchor first = anchors.getFirst();
    if (line <= first.line()) {
      return line + (first.otherLine() - first.line());
    }
    Anchor last = anchors.getLast();
    if (line >= last.line()) {
      return line + (last.otherLine() - last.line());
    }
    int lo = 0;
    int hi = anchors.size() - 1;
    while (lo + 1 < hi) {
      int mid = (lo + hi) >>> 1;
      if (anchors.get(mid).line() < line) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    Anchor a = anchors.get(lo);
    Anchor b = anchors.get(lo + 1);
    double t = (line - a.line()) / (b.line() - a.line());
    return a.otherLine() + t * (b.otherLine() - a.otherLine());
  }
}
