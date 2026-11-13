/*
 * Ported from Taffy's out-of-flow positioning (src/compute/oof.rs in
 * https://github.com/DioxusLabs/taffy, MIT): Copyright (c) 2018 Visly Inc.,
 * Copyright (c) 2026 Taffy Authors. The licence text is at the top of Flexbox.java.
 */
package com.rethinkj.ui.layout;

import static com.rethinkj.ui.layout.Maybe.clamp;
import static com.rethinkj.ui.layout.Maybe.or;
import static com.rethinkj.ui.layout.Maybe.resolve;
import static com.rethinkj.ui.layout.Maybe.size;
import static com.rethinkj.ui.layout.Maybe.some;
import static com.rethinkj.ui.layout.Node.BOTH;

import com.rethinkj.ui.Style;
import com.rethinkj.ui.layout.Node.In;
import com.rethinkj.ui.layout.Node.Out;

/**
 * Sizes and places a {@code position: absolute} element in its parent's padding box, following <a
 * href="https://www.w3.org/TR/css-position-3/#abspos-layout">CSS Positioned Layout, section 4</a>.
 */
final class Absolute {

  static final int START = 0;
  static final int CENTER = 1;
  static final int END = 2;

  private Absolute() {}

  /** One axis of the problem. Coordinates are relative to the start of the padding box. */
  static final class Axis {
    /**
     * Where the element goes when both insets are auto: aligned to {@link #staticEdge} of this
     * range. The caller gives it relative to the parent's border box.
     */
    float staticStart;

    float staticEnd;
    int staticEdge;

    /** Whether the parent scrolls along this axis, which lets an element overflow at the end. */
    boolean scrolls;

    private float size;
    private float insetStart;
    private float insetEnd;

    /** NaN for auto. */
    private float marginStart;

    private float marginEnd;
    private boolean inline;
    private Style.Align align;

    /** The padding box, shrunk by the insets. */
    private float boxStart;

    private float boxEnd;

    private void shrinkBox() {
      boolean weakerIsStart = false;
      if (some(insetStart) && some(insetEnd)) {
        boxStart = insetStart;
        boxEnd = size - insetEnd;
      } else if (some(insetStart)) {
        boxStart = insetStart;
        boxEnd = size;
      } else if (some(insetEnd)) {
        boxStart = 0;
        boxEnd = size - insetEnd;
        weakerIsStart = true;
      } else if (staticEdge == START) {
        boxStart = staticStart;
        boxEnd = size;
      } else if (staticEdge == END) {
        boxStart = 0;
        boxEnd = staticEnd;
      } else {
        // Centred on the static range, as far as the nearer edge of the padding box allows.
        float center = (staticStart + staticEnd) / 2;
        float toEnd = size - center;
        if (Math.abs(center) <= Math.abs(toEnd)) {
          boxStart = 0;
          boxEnd = 2 * center;
        } else {
          boxStart = size - 2 * toEnd;
          boxEnd = size;
        }
      }
      // Insets that cross each other: the weaker one gives way.
      if (boxEnd < boxStart) {
        if (weakerIsStart) {
          boxStart = boxEnd;
        } else {
          boxEnd = boxStart;
        }
      }
    }

    /** The border-box size that fills the shrunk box; auto margins count as zero. */
    private float stretchSize() {
      return Math.max(boxEnd - boxStart - or(marginStart, 0) - or(marginEnd, 0), 0);
    }

    /** An auto size fills the box when both insets are set and nothing aligns the element. */
    private boolean stretches() {
      return some(insetStart) && some(insetEnd) && (align == null || align == Style.Align.STRETCH);
    }

    /** Auto margins take up the free space, but only between two insets. */
    private void resolveMargins(float borderBox) {
      boolean autoStart = !some(marginStart);
      boolean autoEnd = !some(marginEnd);
      if (!autoStart && !autoEnd) {
        return;
      }
      float freeSpace = boxEnd - boxStart - borderBox;
      if (!(some(insetStart) && some(insetEnd))) {
        marginStart = or(marginStart, 0);
        marginEnd = or(marginEnd, 0);
      } else if (!autoStart) {
        marginEnd = freeSpace - marginStart;
      } else if (!autoEnd) {
        marginStart = freeSpace - marginEnd;
      } else if (freeSpace < 0 && inline) {
        // Horizontally, an element that is too wide overflows at the end only.
        marginStart = 0;
        marginEnd = freeSpace;
      } else {
        marginStart = freeSpace / 2;
        marginEnd = freeSpace / 2;
      }
    }

    /** The start of the border box. Call after {@link #resolveMargins}. */
    private float position(float borderBox, boolean hadAutoMargin) {
      float marginBox = borderBox + marginStart + marginEnd;
      if (!some(insetStart) && !some(insetEnd)) {
        return align(staticStart, staticEnd, marginBox, staticEdge) + marginStart;
      }
      if (!some(insetEnd)) {
        return boxStart + marginStart;
      }
      if (!some(insetStart)) {
        return boxEnd - borderBox - marginEnd;
      }
      if (hadAutoMargin) {
        return boxStart + marginStart;
      }
      int edge = align == null
          ? START
          : switch (align) {
            case CENTER -> CENTER;
            case END, FLEX_END -> END;
            case START, FLEX_START, BASELINE, STRETCH -> START;
          };
      float start = align(boxStart, boxEnd, marginBox, edge);
      if (marginBox > boxEnd - boxStart && align != null) {
        // Too large for the box: stay inside the parent as far as possible, and never go out at
        // the start. https://www.w3.org/TR/css-align-3/#auto-safety-position
        float limitStart = Math.min(boxStart, 0);
        float limitEnd = scrolls ? Float.POSITIVE_INFINITY : Math.max(boxEnd, size);
        start = marginBox <= limitEnd - limitStart
            ? Math.max(Math.min(start, limitEnd - marginBox), limitStart)
            : limitStart;
      }
      return start + marginStart;
    }

    private static float align(float start, float end, float size, int edge) {
      return switch (edge) {
        case START -> start;
        case END -> end - size;
        default -> (start + end - size) / 2;
      };
    }
  }

  /**
   * @param areaWidth the size of the parent's padding box, which insets and percentages refer to
   * @param areaX its position in the parent's border box
   */
  static void layout(
      Node node,
      float areaWidth,
      float areaHeight,
      float areaX,
      float areaY,
      Axis horizontal,
      Axis vertical) {
    Style s = node.style;
    Edges padding = Edges.padding(s, areaWidth);
    Edges border = Edges.border(s, areaWidth);
    float edgesWidth = padding.horizontal() + border.horizontal();
    float edgesHeight = padding.vertical() + border.vertical();
    boolean contentBox = s.boxSizing == Style.BoxSizing.CONTENT_BOX;
    float boxWidth = contentBox ? edgesWidth : 0;
    float boxHeight = contentBox ? edgesHeight : 0;
    float ratio = s.aspectRatio;
    // Limits go through the ratio only to an axis whose size is not set (CSS Sizing 4, 4.4).
    float ratioWidth = some(resolve(s.width, areaWidth)) ? Float.NaN : ratio;
    float ratioHeight = some(resolve(s.height, areaHeight)) ? Float.NaN : ratio;

    float minWidth = Math.max(
        or(size(s.minWidth, s.minHeight, areaWidth, areaHeight, ratioWidth, true, boxWidth), 0),
        edgesWidth);
    float minHeight = Math.max(
        or(size(s.minHeight, s.minWidth, areaHeight, areaWidth, ratioHeight, false, boxHeight), 0),
        edgesHeight);
    float maxWidth =
        size(s.maxWidth, s.maxHeight, areaWidth, areaHeight, ratioWidth, true, boxWidth);
    float maxHeight =
        size(s.maxHeight, s.maxWidth, areaHeight, areaWidth, ratioHeight, false, boxHeight);
    float width = clamp(
        size(s.width, s.height, areaWidth, areaHeight, ratio, true, boxWidth), minWidth, maxWidth);
    float height = clamp(
        size(s.height, s.width, areaHeight, areaWidth, ratio, false, boxHeight),
        minHeight,
        maxHeight);

    horizontal.size = areaWidth;
    horizontal.staticStart -= areaX;
    horizontal.staticEnd -= areaX;
    horizontal.insetStart = resolve(s.left, areaWidth);
    horizontal.insetEnd = resolve(s.right, areaWidth);
    horizontal.marginStart = resolve(s.marginLeft, areaWidth);
    horizontal.marginEnd = resolve(s.marginRight, areaWidth);
    horizontal.inline = true;
    horizontal.shrinkBox();
    vertical.size = areaHeight;
    vertical.staticStart -= areaY;
    vertical.staticEnd -= areaY;
    vertical.insetStart = resolve(s.top, areaHeight);
    vertical.insetEnd = resolve(s.bottom, areaHeight);
    vertical.marginStart = resolve(s.marginTop, areaWidth);
    vertical.marginEnd = resolve(s.marginBottom, areaWidth);
    vertical.align = s.alignSelf;
    vertical.shrinkBox();

    // An auto size between two insets fills the space. Width first, so that the aspect ratio
    // can give the height.
    boolean stretchWidth = !some(width) && horizontal.stretches();
    boolean stretchHeight = !some(height) && vertical.stretches();
    if (stretchWidth) {
      width = horizontal.stretchSize();
      height = clamp(or(height, width / ratio), minHeight, maxHeight);
      width = clamp(width, minWidth, maxWidth);
    }
    if (!some(height) && stretchHeight) {
      height = vertical.stretchSize();
      width = clamp(or(width, height * ratio), minWidth, maxWidth);
      height = clamp(height, minHeight, maxHeight);
    }

    float availableWidth = clamp(horizontal.stretchSize(), minWidth, maxWidth);
    float availableHeight = clamp(vertical.stretchSize(), minHeight, maxHeight);
    if (!(some(width) && some(height))) {
      // Otherwise the content decides: shrink to fit.
      Out measured = node.layout(new In(
          false,
          false,
          BOTH,
          width,
          height,
          true,
          true,
          areaWidth,
          areaHeight,
          availableWidth,
          availableHeight));
      width = or(width, measured.width());
      height = or(height, measured.height());
    }
    width = clamp(width, minWidth, maxWidth);
    height = clamp(height, minHeight, maxHeight);
    node.layout(new In(
        true,
        false,
        BOTH,
        width,
        height,
        true,
        true,
        areaWidth,
        areaHeight,
        availableWidth,
        availableHeight));

    boolean autoMarginX = !some(horizontal.marginStart) || !some(horizontal.marginEnd);
    boolean autoMarginY = !some(vertical.marginStart) || !some(vertical.marginEnd);
    horizontal.resolveMargins(width);
    vertical.resolveMargins(height);
    node.x = horizontal.position(width, autoMarginX) + areaX;
    node.y = vertical.position(height, autoMarginY) + areaY;
    node.width = width;
    node.height = height;
  }
}
