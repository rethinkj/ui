package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lays out and places the overlays of a tree ({@link El#overlay}), for {@link App} and {@link
 * Headless}. Each overlay is laid out as a root of its own, with the window as the room it has,
 * and placed after the tree it is attached to, in window coordinates, beside its anchor's
 * rectangle as shown.
 */
final class Overlays {

  private Overlays() {}

  /**
   * Lays out the overlays below {@code el}, which layout has placed, and those inside them: each
   * at most as large as the window less its margins, and one at the window's edge less its gap
   * from that edge (and at both ends along it with {@link Style.Align#STRETCH}), and with {@link
   * Style.Align#STRETCH} at least as wide or tall as its anchor or that edge. Overlays that are
   * leaving keep their layout.
   */
  static void layout(El el, float width, float height) {
    for (El child : el.children()) {
      layout(child, width, height);
    }
    for (El overlay : el.overlays()) {
      if (overlay.isExiting()) {
        continue;
      }
      Style s = overlay.style();
      El.Layer l = overlay.placement();
      // Its margins keep it that far from the window's edges, and one at an edge its gap too.
      boolean atTopOrBottom = l.edge == Side.TOP || l.edge == Side.BOTTOM;
      boolean stretchedEdge = l.edge != null && !l.centered && l.align == Style.Align.STRETCH;
      float gapX = l.edge == null ? 0 : !atTopOrBottom ? l.gap : stretchedEdge ? 2 * l.gap : 0;
      float gapY = l.edge == null ? 0 : atTopOrBottom ? l.gap : stretchedEdge ? 2 * l.gap : 0;
      float roomX =
          Math.max(width - s.marginLeft.pixelsOrZero() - s.marginRight.pixelsOrZero() - gapX, 0);
      float roomY =
          Math.max(height - s.marginTop.pixelsOrZero() - s.marginBottom.pixelsOrZero() - gapY, 0);
      s.maxWidth = limit(s.maxWidth, roomX);
      s.maxHeight = limit(s.maxHeight, roomY);
      if (stretchedEdge) { // exactly the room along the edge, anew in each layout
        if (atTopOrBottom) {
          s.minWidth = s.maxWidth = Len.px(roomX);
        } else {
          s.minHeight = s.maxHeight = Len.px(roomY);
        }
      } else if (l.align == Style.Align.STRETCH
          && !l.centered
          && l.edge == null
          && Float.isNaN(l.pointX)) {
        boolean across = l.side == Side.TOP || l.side == Side.BOTTOM;
        if (across) {
          s.minWidth = Len.px(Math.max(s.minWidth.pixelsOrZero(), el.width()));
        } else {
          s.minHeight = Len.px(Math.max(s.minHeight.pixelsOrZero(), el.height()));
        }
      }
      Flex.layout(overlay, width, height);
      layout(overlay, width, height);
    }
  }

  private static Len limit(Len max, float window) {
    window = Math.max(window, 0);
    return max.kind() == Len.Kind.PX && max.value() <= window ? max : Len.px(window);
  }

  /**
   * Places the overlays of a tree that layout and scrolling have placed, and gives its root the
   * list of those shown, in the order they opened ({@link El#shownOverlays}): an overlay shows
   * while its anchor does, not while the anchor or an element above it is leaving or made
   * invisible by the view, the clips above the anchor leave nothing of its rectangle or it lies
   * wholly outside the window, and not while the view makes the overlay itself invisible. An
   * anchor or overlay that fades in counts from its first frame. One with {@link
   * El#whenShortened} shows only while a text of its anchor is cut. One attached in {@link
   * El#inert} content is made inert too.
   */
  static boolean place(El root, float width, float height) {
    List<El> shown = new ArrayList<>();
    boolean[] fieldAnchored = {false};
    float far = Float.POSITIVE_INFINITY;
    place(root, 0, 0, -far, -far, far, far, width, height, shown, fieldAnchored, null, null, false);
    shown.sort(Comparator.comparingLong(overlay -> overlay.placement().opened)); // stable
    root.shownOverlays(List.copyOf(shown));
    return fieldAnchored[0];
  }

  /**
   * @param within the placement of the overlay {@code el} is in, null in the tree below
   * @param dialog the nearest {@link El#modal} overlay {@code el} is in, null in the tree below
   * @param inert whether {@code el} is in {@link El#inert} content
   */
  private static void place(
      El el,
      float parentX,
      float parentY,
      float clipLeft,
      float clipTop,
      float clipRight,
      float clipBottom,
      float width,
      float height,
      List<El> shown,
      boolean[] fieldAnchored,
      El.Layer within,
      El dialog,
      boolean inert) {
    // As for the focus, one that fades in counts from the frame that adds it: the submenu that a
    // key opens in the frame its menu appeared in shows, and takes the focus.
    if (!el.isPlaced() || el.targetOpacity() == 0 || el.isExiting()) {
      return;
    }
    float left = parentX + el.x();
    float top = parentY + el.y();
    float right = left + el.width();
    float bottom = top + el.height();
    if (left < clipRight
        && right > clipLeft
        && top < clipBottom
        && bottom > clipTop
        && overlaps(left, right, width)
        && overlaps(top, bottom, height)) {
      float far = Float.POSITIVE_INFINITY;
      for (El overlay : el.overlays()) {
        El.Layer l = overlay.placement();
        if (overlay.targetOpacity() == 0 || l.whenShortened && !shortened(el)) {
          continue; // not shown, so it neither blocks as a modal one does nor takes the focus
        }
        if (l.atCaret || l.rangeFrom >= 0) {
          fieldAnchored[0] = true;
        }
        if (l.rangeFrom >= 0
            && (el.editor() == null || el.editor().rangeBox(l.rangeFrom, l.rangeTo) == null)) {
          continue; // range is hidden or scrolled out of view
        }
        place(overlay, el, left, top, width, height, within, dialog);
        if (inert || el.isInert()) {
          overlay.inert();
        }
        shown.add(overlay);
        // Its overlays follow it, free of the clips around its anchor.
        El in = l.modal ? overlay : dialog;
        place(
            overlay, 0, 0, -far, -far, far, far, width, height, shown, fieldAnchored, l, in, false);
      }
    }
    Style s = el.style();
    if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(left, clipLeft);
      clipTop = Math.max(top, clipTop);
      clipRight = Math.min(right, clipRight);
      clipBottom = Math.min(bottom, clipBottom);
      if (!(clipRight > clipLeft && clipBottom > clipTop)) {
        return;
      }
    }
    for (El child : el.children()) {
      place(
          child,
          left,
          top,
          clipLeft,
          clipTop,
          clipRight,
          clipBottom,
          width,
          height,
          shown,
          fieldAnchored,
          within,
          dialog,
          inert || el.isInert());
    }
  }

  /** Whether layout cut a text of the element, or of one inside it, to end in an ellipsis. */
  private static boolean shortened(El el) {
    if (el.text() != null && el.text().shortened()) {
      return true;
    }
    for (El child : el.children()) {
      if (shortened(child)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a side of an anchor from {@code start} to {@code end} lies in the window's side from 0
   * to {@code size}: in part, or, for an anchor of no extent there, such as a marker, on it with
   * its edges included.
   */
  private static boolean overlaps(float start, float end, float size) {
    return end > start ? start < size && end > 0 : start >= 0 && start <= size;
  }

  /**
   * Places one overlay of {@code anchor}, whose border box is at {@code x, y} in the window. An
   * overlay inside one that went to the other side of its anchor than it asked tries that other
   * side first too when it asks the same side, so that submenus go on the way the first that
   * flipped at the window's edge went. Along the side it keeps inside {@code dialog}, the nearest
   * modal overlay it is in, as far as it can.
   */
  private static void place(
      El overlay,
      El anchor,
      float x,
      float y,
      float width,
      float height,
      El.Layer within,
      El dialog) {
    El.Layer l = overlay.placement();
    l.flipped = false;
    l.anchor = anchor;
    l.anchorX = x;
    l.anchorY = y;
    float w = overlay.laidOutWidth(); // placing it anew ends what its cycle shows
    float h = overlay.laidOutHeight();
    float left;
    float top;
    if (l.centered) {
      left = (width - w) / 2;
      top = (height - h) / 2;
    } else if (l.edge != null) {
      boolean across = l.edge == Side.TOP || l.edge == Side.BOTTOM;
      float along = edgeAlong(l.align, across ? w : h, across ? width : height, l.gap);
      float off = l.edge == Side.TOP || l.edge == Side.LEFT
          ? l.gap
          : (across ? height - h : width - w) - l.gap;
      left = across ? along : off;
      top = across ? off : along;
    } else {
      float ax = Float.isNaN(l.pointX) ? x : l.pointX;
      float ay = Float.isNaN(l.pointX) ? y : l.pointY;
      float aw = Float.isNaN(l.pointX) ? anchor.width() : 0;
      float ah = Float.isNaN(l.pointX) ? anchor.height() : 0;
      float[] box = l.atCaret && anchor.editor() != null
          ? anchor.editor().caretBox()
          : l.rangeFrom >= 0 && anchor.editor() != null
              ? anchor.editor().rangeBox(l.rangeFrom, l.rangeTo)
              : null;
      if (box != null) {
        ax = x + box[0];
        ay = y + box[1];
        aw = box[2];
        ah = box[3];
      }
      // A tooltip keeps its gap from the window's edges too; a menu may lie flush with them.
      float edge = l.avoidsAnchor ? l.gap : 0;
      boolean after = l.side == Side.BOTTOM || l.side == Side.RIGHT;
      boolean turn = within != null && within.flipped && within.side == l.side;
      // Only an anchor wholly inside the dialog keeps it there, not a submenu's item below it.
      boolean inDialog = dialog != null
          && ax >= dialog.x()
          && ay >= dialog.y()
          && ax + aw <= dialog.x() + dialog.width()
          && ay + ah <= dialog.y() + dialog.height();
      float far = Float.POSITIVE_INFINITY;
      float dialogLeft = inDialog ? dialog.x() : -far;
      float dialogTop = inDialog ? dialog.y() : -far;
      float dialogRight = inDialog ? dialog.x() + dialog.width() : far;
      float dialogBottom = inDialog ? dialog.y() + dialog.height() : far;
      if (l.side == Side.TOP || l.side == Side.BOTTOM) {
        top = beside(after != turn, ay, ah, h, l.gap, height);
        left = along(l.align, ax, aw, w, width, edge, dialogLeft, dialogRight);
        l.flipped = after ? top < ay : top >= ay + ah;
      } else {
        left = beside(after != turn, ax, aw, w, l.gap, width);
        top = along(l.align, ay, ah, h, height, edge, dialogTop, dialogBottom);
        l.flipped = after ? left < ax : left >= ax + aw;
      }
      // A tooltip that would cover its anchor, under the pointer that shows it, would take the
      // hover from the anchor and hide again: it goes to a side across if one has room.
      if (l.avoidsAnchor && Float.isNaN(l.pointX) && overlaps(left, top, w, h, ax, ay, aw, ah)) {
        boolean down = l.side == Side.TOP || l.side == Side.BOTTOM;
        float acrossLeft = down
            ? beside(true, ax, aw, w, l.gap, width)
            : along(l.align, ax, aw, w, width, edge, dialogLeft, dialogRight);
        float acrossTop = down
            ? along(l.align, ay, ah, h, height, edge, dialogTop, dialogBottom)
            : beside(true, ay, ah, h, l.gap, height);
        if (!overlaps(acrossLeft, acrossTop, w, h, ax, ay, aw, ah)) {
          left = acrossLeft;
          top = acrossTop;
        }
      }
    }
    // On whole pixels, as layout places everything else.
    overlay.place(Math.round(left), Math.round(top), w, h);
  }

  /**
   * Where an overlay of size {@code size} starts across the side it is on: beside the anchor, on
   * the other side if it does not fit, else moved into the window.
   *
   * @param after whether it goes after the anchor: below it or to its right
   */
  static float beside(
      boolean after, float start, float extent, float size, float gap, float window) {
    float below = start + extent + gap;
    float above = start - gap - size;
    float first = after ? below : above;
    float second = after ? above : below;
    if (fits(first, size, window)) {
      return first;
    }
    if (fits(second, size, window)) {
      return second;
    }
    return Math.clamp(first, 0, Math.max(window - size, 0));
  }

  /**
   * Where an overlay starts along the side it is on: aligned with the anchor, else at the other
   * end of it, else moved into the window; each {@code margin} away from the window's edges where
   * there is room for that. For an anchor inside a dialog from {@code from} to {@code to}, it
   * takes the first of those ends that fits into the dialog too, else it moves into the dialog as
   * far as the window lets it, and over its middle where it is wider than the dialog.
   */
  static float along(
      Style.Align align,
      float start,
      float extent,
      float size,
      float window,
      float margin,
      float from,
      float to) {
    float end = start + extent - size;
    float first = switch (align) {
      case END, FLEX_END -> end;
      case CENTER -> start + (extent - size) / 2;
      default -> start;
    };
    margin = Math.clamp(margin, 0, Math.max(window - size, 0) / 2);
    float second = first == end ? start : end;
    boolean firstFits = fits(first - margin, size + 2 * margin, window);
    boolean secondFits =
        align != Style.Align.CENTER && fits(second - margin, size + 2 * margin, window);
    if (firstFits && fits(first - from, size, to - from)) {
      return first;
    }
    if (secondFits && fits(second - from, size, to - from)) {
      return second;
    }
    // Without a dialog, from and to are infinite: every end fits into it, and this is first.
    float moved = to - from >= size ? Math.clamp(first, from, to - size) : (from + to - size) / 2;
    return Math.clamp(moved, margin, Math.max(window - size - margin, margin));
  }

  /** Where an overlay of this size starts along an edge of the window. */
  private static float edgeAlong(Style.Align align, float size, float window, float gap) {
    return switch (align) {
      case END, FLEX_END -> window - size - gap;
      case CENTER -> (window - size) / 2;
      default -> gap;
    };
  }

  private static boolean overlaps(
      float x, float y, float w, float h, float ax, float ay, float aw, float ah) {
    return x < ax + aw && ax < x + w && y < ay + ah && ay < y + h;
  }

  private static boolean fits(float start, float size, float window) {
    return start >= 0 && start + size <= window;
  }
}
