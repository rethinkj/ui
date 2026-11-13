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
   * at most as large as the window, and with {@link Style.Align#STRETCH} at least as wide or tall
   * as its anchor. Overlays that are leaving keep their layout.
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
      s.maxWidth = limit(s.maxWidth, width);
      s.maxHeight = limit(s.maxHeight, height);
      El.Layer l = overlay.placement();
      if (l.align == Style.Align.STRETCH && !l.centered && Float.isNaN(l.pointX)) {
        boolean across = l.side == Side.TOP || l.side == Side.BOTTOM;
        if (across) {
          s.minWidth = Len.px(Math.max(px(s.minWidth), el.width()));
        } else {
          s.minHeight = Len.px(Math.max(px(s.minHeight), el.height()));
        }
      }
      Flex.layout(overlay, width, height);
      layout(overlay, width, height);
    }
  }

  private static Len limit(Len max, float window) {
    return max.kind() == Len.Kind.PX && max.value() <= window ? max : Len.px(window);
  }

  private static float px(Len len) {
    return len.kind() == Len.Kind.PX ? len.value() : 0;
  }

  /**
   * Places the overlays of a tree that layout and scrolling have placed, and gives its root the
   * list of those shown, in the order they opened ({@link El#shownOverlays}): an overlay shows
   * while its anchor does, not while the anchor or an element above it is leaving or invisible,
   * or the clips above the anchor leave nothing of its rectangle.
   */
  static void place(El root, float width, float height) {
    List<El> shown = new ArrayList<>();
    float far = Float.POSITIVE_INFINITY;
    place(root, 0, 0, -far, -far, far, far, width, height, shown);
    shown.sort(Comparator.comparingLong(overlay -> overlay.placement().opened)); // stable
    root.shownOverlays(List.copyOf(shown));
  }

  private static void place(
      El el, float parentX, float parentY, float clipLeft, float clipTop, float clipRight,
      float clipBottom, float width, float height, List<El> shown) {
    if (!el.isPlaced() || el.opacity() == 0 || el.isExiting()) {
      return;
    }
    float left = parentX + el.x();
    float top = parentY + el.y();
    float right = left + el.width();
    float bottom = top + el.height();
    if (left < clipRight && right > clipLeft && top < clipBottom && bottom > clipTop) {
      float far = Float.POSITIVE_INFINITY;
      for (El overlay : el.overlays()) {
        place(overlay, el, left, top, width, height);
        shown.add(overlay);
        // Its overlays follow it, free of the clips around its anchor.
        place(overlay, 0, 0, -far, -far, far, far, width, height, shown);
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
      place(child, left, top, clipLeft, clipTop, clipRight, clipBottom, width, height, shown);
    }
  }

  /** Places one overlay of {@code anchor}, whose border box is at {@code x, y} in the window. */
  private static void place(El overlay, El anchor, float x, float y, float width, float height) {
    El.Layer l = overlay.placement();
    l.anchor = anchor;
    l.anchorX = x;
    l.anchorY = y;
    float w = overlay.width();
    float h = overlay.height();
    float left;
    float top;
    if (l.centered) {
      left = (width - w) / 2;
      top = (height - h) / 2;
    } else {
      float ax = Float.isNaN(l.pointX) ? x : l.pointX;
      float ay = Float.isNaN(l.pointX) ? y : l.pointY;
      float aw = Float.isNaN(l.pointX) ? anchor.width() : 0;
      float ah = Float.isNaN(l.pointX) ? anchor.height() : 0;
      if (l.side == Side.TOP || l.side == Side.BOTTOM) {
        top = beside(l.side == Side.BOTTOM, ay, ah, h, l.gap, height);
        left = along(l.align, ax, aw, w, width);
      } else {
        left = beside(l.side == Side.RIGHT, ax, aw, w, l.gap, width);
        top = along(l.align, ay, ah, h, height);
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
  static float beside(boolean after, float start, float extent, float size, float gap,
      float window) {
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
   * end of it, else moved into the window.
   */
  static float along(Style.Align align, float start, float extent, float size, float window) {
    float end = start + extent - size;
    float first = switch (align) {
      case END, FLEX_END -> end;
      case CENTER -> start + (extent - size) / 2;
      default -> start;
    };
    if (fits(first, size, window)) {
      return first;
    }
    float second = first == end ? start : end;
    if (align != Style.Align.CENTER && fits(second, size, window)) {
      return second;
    }
    return Math.clamp(first, 0, Math.max(window - size, 0));
  }

  private static boolean fits(float start, float size, float window) {
    return start >= 0 && start + size <= window;
  }
}
