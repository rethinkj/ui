package com.rethinkj.ui.layout;

import static com.rethinkj.ui.layout.Maybe.clamp;
import static com.rethinkj.ui.layout.Maybe.definite;
import static com.rethinkj.ui.layout.Maybe.or;
import static com.rethinkj.ui.layout.Maybe.size;
import static com.rethinkj.ui.layout.Maybe.some;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An element during one layout run: its results so far, and the sizes already computed for it.
 * Without that cache every level of the tree would multiply the work, because a flex container
 * measures each child several times.
 */
final class Node {

  static final int BOTH = 0;
  static final int HORIZONTAL = 1;
  static final int VERTICAL = 2;

  /**
   * What a parent asks of a child.
   *
   * @param perform true to also place the descendants, false to only compute the size
   * @param inherent true when the node applies its own {@code width} and {@code height}; a flex
   *     container resolves those for its items and passes false
   * @param axis the axis the size is wanted for; the other one may come back as garbage
   * @param knownWidth the border-box width the node has to take, or NaN
   * @param widthDefinite false when {@code knownWidth} derives from the node's own content, so that
   *     percentages of it stay unresolved
   * @param parentWidth what percentages refer to, or NaN
   * @param availableWidth the space for the border box, in pixels or min-content or max-content
   */
  record In(
      boolean perform,
      boolean inherent,
      int axis,
      float knownWidth,
      float knownHeight,
      boolean widthDefinite,
      boolean heightDefinite,
      float parentWidth,
      float parentHeight,
      float availableWidth,
      float availableHeight) {}

  /**
   * @param baseline distance from the top of the border box to the first baseline, or NaN
   */
  record Out(float width, float height, float baseline) {}

  final El el;
  final Style style;
  final Node[] children;

  /** The border box, relative to the parent's border box, before rounding. */
  float x;

  float y;
  float width;
  float height;

  private In performed;
  private Out performedOut;
  private Map<In, Out> measured;

  Node(El el) {
    this.el = el;
    this.style = el.style();
    // A text element is a leaf: what it holds besides its text, a field's gutter, is placed apart.
    List<El> elements = el.text() != null ? List.of() : el.children();
    this.children = new Node[elements.size()];
    for (int i = 0; i < children.length; i++) {
      children[i] = new Node(elements.get(i));
    }
  }

  Out layout(In in) {
    if (style.display == Style.Display.NONE) {
      // Nothing below is laid out, so the subtree keeps its zero rectangles.
      return new Out(0, 0, Float.NaN);
    }
    if (!(in.widthDefinite && in.heightDefinite)) {
      in =
          new In(
              in.perform,
              in.inherent,
              in.axis,
              in.knownWidth,
              in.knownHeight,
              in.widthDefinite || Float.isNaN(in.knownWidth),
              in.heightDefinite || Float.isNaN(in.knownHeight),
              in.parentWidth,
              in.parentHeight,
              in.availableWidth,
              in.availableHeight);
    }
    // Only the latest full layout is kept: its inputs are the ones the descendants were placed for.
    if (in.perform) {
      if (in.equals(performed)) {
        return performedOut;
      }
    } else if (measured != null) {
      Out out = measured.get(in);
      if (out != null) {
        return out;
      }
    }
    Out out = children.length == 0 ? leaf(in) : new Flexbox(this, in).layout();
    if (in.perform) {
      performed = in;
      performedOut = out;
    } else {
      if (measured == null) {
        measured = new HashMap<>();
      }
      measured.put(in, out);
    }
    return out;
  }

  /*
   * Ported from Taffy's compute_leaf_layout (https://github.com/DioxusLabs/taffy, MIT):
   * Copyright (c) 2018 Visly Inc., Copyright (c) 2026 Taffy Authors.
   */
  private Out leaf(In in) {
    Style s = style;
    float parentWidth = in.parentWidth;
    float parentHeight = in.parentHeight;
    Edges padding = Edges.padding(s, parentWidth);
    Edges border = Edges.border(s, parentWidth);
    float edgesWidth = padding.horizontal() + border.horizontal();
    float edgesHeight = padding.vertical() + border.vertical();

    float nodeWidth = in.knownWidth;
    float nodeHeight = in.knownHeight;
    float minWidth = Float.NaN;
    float minHeight = Float.NaN;
    float maxWidth = Float.NaN;
    float maxHeight = Float.NaN;
    float ratio = Float.NaN;
    if (in.inherent) {
      boolean contentBox = s.boxSizing == Style.BoxSizing.CONTENT_BOX;
      float boxWidth = contentBox ? edgesWidth : 0;
      float boxHeight = contentBox ? edgesHeight : 0;
      ratio = s.aspectRatio;
      nodeWidth =
          or(nodeWidth, size(s.width, s.height, parentWidth, parentHeight, ratio, true, boxWidth));
      nodeHeight =
          or(
              nodeHeight,
              size(s.height, s.width, parentHeight, parentWidth, ratio, false, boxHeight));
      minWidth = size(s.minWidth, s.minHeight, parentWidth, parentHeight, ratio, true, boxWidth);
      minHeight = size(s.minHeight, s.minWidth, parentHeight, parentWidth, ratio, false, boxHeight);
      maxWidth = Maybe.resolve(s.maxWidth, parentWidth) + boxWidth;
      maxHeight = Maybe.resolve(s.maxHeight, parentHeight) + boxHeight;
    }

    if (!in.perform && some(nodeWidth) && some(nodeHeight)) {
      return new Out(
          Math.max(clamp(nodeWidth, minWidth, maxWidth), edgesWidth),
          Math.max(clamp(nodeHeight, minHeight, maxHeight), edgesHeight),
          Float.NaN);
    }

    // A box that scrolls vertically keeps room for a vertical scrollbar, which takes up width.
    float insetWidth = edgesWidth + (s.overflowY == Style.Overflow.SCROLL ? s.scrollbarWidth : 0);
    float insetHeight = edgesHeight + (s.overflowX == Style.Overflow.SCROLL ? s.scrollbarWidth : 0);

    float contentWidth = 0;
    float contentHeight = 0;
    float baseline = Float.NaN;
    Measure measure = el.measure();
    if (measure != null) {
      float availableWidth = or(nodeWidth, in.availableWidth);
      if (definite(availableWidth)) {
        availableWidth = clamp(availableWidth, minWidth, maxWidth) - insetWidth;
      }
      float availableHeight = or(nodeHeight, in.availableHeight);
      if (definite(availableHeight)) {
        availableHeight = clamp(availableHeight, minHeight, maxHeight) - insetHeight;
      }
      // A full layout measures against the available size only, so the content reports its
      // baseline for the size it really gets.
      float knownWidth = in.perform ? Float.NaN : Math.max(in.knownWidth - insetWidth, 0);
      float knownHeight = in.perform ? Float.NaN : Math.max(in.knownHeight - insetHeight, 0);
      Measure.Size content =
          measure.measure(knownWidth, knownHeight, availableWidth, availableHeight);
      contentWidth = content.width();
      contentHeight = content.height();
      baseline = padding.top() + border.top() + content.baseline();
    }

    float width = clamp(or(nodeWidth, contentWidth + insetWidth), minWidth, maxWidth);
    float height = clamp(or(nodeHeight, contentHeight + insetHeight), minHeight, maxHeight);
    height = Math.max(height, some(ratio) ? width / ratio : 0);
    Out out = new Out(Math.max(width, edgesWidth), Math.max(height, edgesHeight), baseline);
    if (measure != null && in.perform) {
      measure.placed(
          Math.max(out.width() - insetWidth, 0), Math.max(out.height() - insetHeight, 0));
    }
    return out;
  }
}
