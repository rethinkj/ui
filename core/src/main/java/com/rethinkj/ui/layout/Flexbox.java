/*
 * The algorithm is a port of Taffy's flexbox implementation (src/compute/flexbox.rs and
 * src/compute/common/alignment.rs in https://github.com/DioxusLabs/taffy), which is under the MIT
 * licence:
 *
 * Copyright (c) 2018 Visly Inc.
 * Copyright (c) 2026 Taffy Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.rethinkj.ui.layout;

import static com.rethinkj.ui.Measure.MAX_CONTENT;
import static com.rethinkj.ui.Measure.MIN_CONTENT;
import static com.rethinkj.ui.layout.Maybe.availableClamp;
import static com.rethinkj.ui.layout.Maybe.availableMax;
import static com.rethinkj.ui.layout.Maybe.clamp;
import static com.rethinkj.ui.layout.Maybe.definite;
import static com.rethinkj.ui.layout.Maybe.max;
import static com.rethinkj.ui.layout.Maybe.min;
import static com.rethinkj.ui.layout.Maybe.or;
import static com.rethinkj.ui.layout.Maybe.pixels;
import static com.rethinkj.ui.layout.Maybe.resolve;
import static com.rethinkj.ui.layout.Maybe.resolveOrZero;
import static com.rethinkj.ui.layout.Maybe.size;
import static com.rethinkj.ui.layout.Maybe.some;
import static com.rethinkj.ui.layout.Node.BOTH;
import static com.rethinkj.ui.layout.Node.HORIZONTAL;
import static com.rethinkj.ui.layout.Node.VERTICAL;

import com.rethinkj.ui.Style;
import com.rethinkj.ui.Style.Align;
import com.rethinkj.ui.Style.Content;
import com.rethinkj.ui.layout.Node.In;
import com.rethinkj.ui.layout.Node.Out;
import java.util.ArrayList;
import java.util.List;

/**
 * One run of the flexbox algorithm for one container and its direct children, following the steps
 * of <a href="https://www.w3.org/TR/css-flexbox-1/#layout-algorithm">CSS Flexbox, section 9</a>.
 *
 * <p>Names ending in M and C are sizes along the main and the cross axis. "Start" is left or top
 * whatever the direction; reversed directions change the order of placement, not these names.
 */
final class Flexbox {

  private final class Item {
    final Node node;
    final Style s;

    /** Width divided by height, or NaN. */
    final float ratio;

    /** Padding plus border. */
    final float edgesM;

    final float edgesC;

    /** What {@code box-sizing: content-box} leaves out of the main size properties. */
    final float boxM;

    final float sizeM;
    final float sizeC;
    final float minM;
    final float minC;
    final float maxM;
    final float maxC;

    // Minimum and maximum, each taken through the aspect ratio from the other axis when unset.
    final float ratioMinM;
    final float ratioMinC;
    final float ratioMaxM;
    final float ratioMaxC;

    final Align align;
    final boolean crossSizeIsAuto;
    final boolean scrollsMain;
    final boolean scrolls;
    final boolean scrollsVertically;

    /** The offset of {@code position: relative}. */
    final float insetM;

    final float insetC;

    float marginStartM;
    float marginEndM;
    float marginStartC;
    float marginEndC;
    final boolean autoStartM;
    final boolean autoEndM;
    final boolean autoStartC;
    final boolean autoEndC;

    boolean basisIsDefinite;
    float basis;
    float innerBasis;

    /** The minimum main size, including the automatic one that comes from the content. */
    float minimumM;

    float hypotheticalM;
    float hypotheticalOuterM;
    float hypotheticalC;
    float hypotheticalOuterC;
    float targetM;
    float targetC;
    float outerTargetM;
    float outerTargetC;
    boolean frozen;
    float violation;
    float contentFlexFraction;
    float baseline;
    float offsetM;
    float offsetC;

    Item(Node node) {
      this.node = node;
      this.s = node.style;
      float innerWidth = width(innerM, innerC);
      float innerHeight = height(innerM, innerC);
      // A main size that comes from the container's own content is no basis for percentages.
      float basisM = knownMainIsDefinite ? innerM : Float.NaN;
      float basisWidth = width(basisM, innerC);
      float basisHeight = height(basisM, innerC);

      Edges padding = Edges.padding(s, innerWidth);
      Edges border = Edges.border(s, innerWidth);
      float edgesWidth = padding.horizontal() + border.horizontal();
      float edgesHeight = padding.vertical() + border.vertical();
      boolean contentBox = s.boxSizing == Style.BoxSizing.CONTENT_BOX;
      float boxWidth = contentBox ? edgesWidth : 0;
      float boxHeight = contentBox ? edgesHeight : 0;
      edgesM = main(edgesWidth, edgesHeight);
      edgesC = cross(edgesWidth, edgesHeight);
      boxM = main(boxWidth, boxHeight);
      ratio = s.aspectRatio;

      float w = size(s.width, s.height, basisWidth, basisHeight, ratio, true, boxWidth);
      float h = size(s.height, s.width, basisHeight, basisWidth, ratio, false, boxHeight);
      float minWidth = resolve(s.minWidth, basisWidth) + boxWidth;
      float minHeight = resolve(s.minHeight, basisHeight) + boxHeight;
      float maxWidth = resolve(s.maxWidth, basisWidth) + boxWidth;
      float maxHeight = resolve(s.maxHeight, basisHeight) + boxHeight;
      sizeM = main(w, h);
      sizeC = cross(w, h);
      minM = main(minWidth, minHeight);
      minC = cross(minWidth, minHeight);
      maxM = main(maxWidth, maxHeight);
      maxC = cross(maxWidth, maxHeight);
      ratioMinM = or(minM, mainFromCross(minC, ratio));
      ratioMinC = or(minC, crossFromMain(minM, ratio));
      ratioMaxM = or(maxM, mainFromCross(maxC, ratio));
      ratioMaxC = or(maxC, crossFromMain(maxM, ratio));

      align = s.alignSelf != null ? s.alignSelf : alignItems;
      crossSizeIsAuto = (isRow ? s.height : s.width).isAuto();
      boolean scrollsX = scrolls(s.overflowX);
      scrollsVertically = scrolls(s.overflowY);
      scrollsMain = isRow ? scrollsX : scrollsVertically;
      scrolls = scrollsX || scrollsVertically;

      float insetX = or(resolve(s.left, innerWidth), or(-resolve(s.right, innerWidth), 0));
      float insetY = or(resolve(s.top, innerHeight), or(-resolve(s.bottom, innerHeight), 0));
      insetM = main(insetX, insetY);
      insetC = cross(insetX, insetY);

      Edges margin = Edges.margin(s, innerWidth);
      marginStartM = isRow ? margin.left() : margin.top();
      marginEndM = isRow ? margin.right() : margin.bottom();
      marginStartC = isRow ? margin.top() : margin.left();
      marginEndC = isRow ? margin.bottom() : margin.right();
      autoStartM = (isRow ? s.marginLeft : s.marginTop).isAuto();
      autoEndM = (isRow ? s.marginRight : s.marginBottom).isAuto();
      autoStartC = (isRow ? s.marginTop : s.marginLeft).isAuto();
      autoEndC = (isRow ? s.marginBottom : s.marginRight).isAuto();
    }

    float marginM() {
      return marginStartM + marginEndM;
    }

    float marginC() {
      return marginStartC + marginEndC;
    }

    /** Whether the item takes the cross size of its line. */
    boolean stretches() {
      return align == Align.STRETCH && crossSizeIsAuto && !autoStartC && !autoEndC;
    }

    boolean alignsBaseline() {
      return align == Align.BASELINE && !autoStartC && !autoEndC;
    }

    /** The cross size to lay the item out with before the lines have a size. */
    float knownC() {
      return clamp(sizeC, ratioMinC, ratioMaxC);
    }

    boolean mainIsDefinite() {
      return hasDefiniteMain || basisIsDefinite;
    }

    /**
     * A stretched cross size is definite, and so is a width that was fitted into a definite space.
     * A height that follows from the content is not.
     */
    boolean crossIsDefinite() {
      return stretches() || some(sizeC) || (!isRow && crossSpaceIsDefinite);
    }
  }

  private static final class Line {
    final int start;
    final int end;
    float cross;
    float offset;

    Line(int start, int end) {
      this.start = start;
      this.end = end;
    }

    int count() {
      return end - start;
    }
  }

  private final Node node;
  private final In in;
  private final Style s;

  private final boolean isRow;
  private final boolean isReverse;
  private final boolean isWrap;
  private final boolean isWrapReverse;
  private final Align alignItems;
  private final Content alignContent;
  private final Content justifyContent;

  private final Edges padding;
  private final Edges border;

  /** Width and height of padding plus border. */
  private final float edgesWidth;

  private final float edgesHeight;
  private final float minWidth;
  private final float minHeight;
  private final float maxWidth;
  private final float maxHeight;

  private float minM;
  private float minC;
  private float maxM;
  private float maxC;

  /** From the border box to the content box: padding, border and the room for scrollbars. */
  private float insetStartM;

  private float insetEndM;
  private float insetStartC;
  private float insetEndC;
  private float scrollbarM;
  private float scrollbarC;
  private float gapM;
  private float gapC;

  /** The border-box size as far as it is known before the items are looked at, else NaN. */
  private float knownM;

  private float knownC;

  /** The content-box size as far as it is known, else NaN. The main size is filled in later. */
  private float innerM;

  private float innerC;
  private boolean knownMainIsDefinite;
  private boolean hasDefiniteMain;
  private boolean hasDefiniteCross;
  private boolean crossSpaceIsDefinite;

  /** The space for the items. */
  private float availableM;

  private float availableC;

  // The result
  private float containerM;
  private float containerC;
  private float innerContainerM;
  private float innerContainerC;

  private List<Item> items;
  private List<Line> lines;

  Flexbox(Node node, In in) {
    this.node = node;
    this.in = in;
    this.s = node.style;
    isRow = s.direction == Style.Direction.ROW || s.direction == Style.Direction.ROW_REVERSE;
    isReverse =
        s.direction == Style.Direction.ROW_REVERSE || s.direction == Style.Direction.COLUMN_REVERSE;
    isWrap = s.wrap != Style.Wrap.NO_WRAP;
    isWrapReverse = s.wrap == Style.Wrap.WRAP_REVERSE;
    alignItems = s.alignItems != null ? s.alignItems : Align.STRETCH;
    alignContent = s.alignContent != null ? s.alignContent : Content.STRETCH;
    justifyContent = s.justifyContent != null ? s.justifyContent : Content.FLEX_START;

    float parentWidth = in.parentWidth();
    float parentHeight = in.parentHeight();
    padding = Edges.padding(s, parentWidth);
    border = Edges.border(s, parentWidth);
    edgesWidth = padding.horizontal() + border.horizontal();
    edgesHeight = padding.vertical() + border.vertical();
    boolean contentBox = s.boxSizing == Style.BoxSizing.CONTENT_BOX;
    float boxWidth = contentBox ? edgesWidth : 0;
    float boxHeight = contentBox ? edgesHeight : 0;
    float ratio = s.aspectRatio;
    minWidth = size(s.minWidth, s.minHeight, parentWidth, parentHeight, ratio, true, boxWidth);
    minHeight = size(s.minHeight, s.minWidth, parentHeight, parentWidth, ratio, false, boxHeight);
    maxWidth = size(s.maxWidth, s.maxHeight, parentWidth, parentHeight, ratio, true, boxWidth);
    maxHeight = size(s.maxHeight, s.maxWidth, parentHeight, parentWidth, ratio, false, boxHeight);
  }

  Out layout() {
    float styleWidth = Float.NaN;
    float styleHeight = Float.NaN;
    if (in.inherent()) {
      float parentWidth = in.parentWidth();
      float parentHeight = in.parentHeight();
      boolean contentBox = s.boxSizing == Style.BoxSizing.CONTENT_BOX;
      styleWidth =
          clamp(
              size(
                  s.width,
                  s.height,
                  parentWidth,
                  parentHeight,
                  s.aspectRatio,
                  true,
                  contentBox ? edgesWidth : 0),
              minWidth,
              maxWidth);
      styleHeight =
          clamp(
              size(
                  s.height,
                  s.width,
                  parentHeight,
                  parentWidth,
                  s.aspectRatio,
                  false,
                  contentBox ? edgesHeight : 0),
              minHeight,
              maxHeight);
    }
    // A maximum at or below the minimum fixes the size.
    float knownWidth =
        or(
            in.knownWidth(),
            max(or(maxWidth <= minWidth ? minWidth : Float.NaN, styleWidth), edgesWidth));
    float knownHeight =
        or(
            in.knownHeight(),
            max(or(maxHeight <= minHeight ? minHeight : Float.NaN, styleHeight), edgesHeight));

    if (!in.perform()) {
      if (some(knownWidth) && some(knownHeight)) {
        return new Out(knownWidth, knownHeight, Float.NaN);
      }
      if (in.axis() == HORIZONTAL && some(knownWidth)) {
        return new Out(knownWidth, 0, Float.NaN);
      }
    }

    constants(knownWidth, knownHeight);

    // 9.2 Line length determination
    items = new ArrayList<>(node.children.length);
    for (Node child : node.children) {
      if (child.style.position != Style.Position.ABSOLUTE
          && child.style.display != Style.Display.NONE) {
        items.add(new Item(child));
      }
    }
    availableM =
        some(knownM)
            ? Math.max(knownM - insetStartM - insetEndM, 0)
            : availableMax(
                main(in.availableWidth(), in.availableHeight()) - insetStartM - insetEndM, 0);
    availableC =
        some(knownC)
            ? Math.max(knownC - insetStartC - insetEndC, 0)
            : availableMax(
                cross(in.availableWidth(), in.availableHeight()) - insetStartC - insetEndC, 0);
    flexBaseSizes();

    // 9.3 Main size determination
    collectLines();
    if (some(innerM)) {
      innerContainerM = innerM;
      containerM = innerM + insetStartM + insetEndM;
    } else {
      containerMainSize();
      innerM = innerContainerM;
      // A percentage gap refers to the main size, which is only known now.
      gapM = resolveOrZero(isRow ? s.columnGap : s.rowGap, innerContainerM);
    }
    if (!in.perform() && in.axis() == (isRow ? HORIZONTAL : VERTICAL)) {
      return new Out(width(containerM, 0), height(containerM, 0), Float.NaN);
    }
    for (Line line : lines) {
      resolveFlexibleLengths(line);
    }

    // 9.4 Cross size determination
    hypotheticalCrossSizes();
    childBaselines();
    lineCrossSizes();
    stretchLines();
    usedCrossSizes();

    // 9.5 Main-axis alignment, 9.6 cross-axis alignment
    distributeFreeSpace();
    crossAxisMarginsAndAlignment();
    float linesCross = containerCrossSize();
    if (!in.perform()) {
      return new Out(width(containerM, containerC), height(containerM, containerC), Float.NaN);
    }
    alignLines(linesCross);
    placeItems();
    for (Node child : node.children) {
      if (child.style.position == Style.Position.ABSOLUTE
          && child.style.display != Style.Display.NONE) {
        placeAbsolute(child);
      }
    }
    return new Out(width(containerM, containerC), height(containerM, containerC), firstBaseline());
  }

  private void constants(float knownWidth, float knownHeight) {
    minM = main(minWidth, minHeight);
    minC = cross(minWidth, minHeight);
    maxM = main(maxWidth, maxHeight);
    maxC = cross(maxWidth, maxHeight);

    // A box that scrolls vertically keeps room for a vertical scrollbar, which takes up width.
    float scrollbarWidth = s.overflowY == Style.Overflow.SCROLL ? s.scrollbarWidth : 0;
    float scrollbarHeight = s.overflowX == Style.Overflow.SCROLL ? s.scrollbarWidth : 0;
    float left = padding.left() + border.left();
    float top = padding.top() + border.top();
    float right = padding.right() + border.right() + scrollbarWidth;
    float bottom = padding.bottom() + border.bottom() + scrollbarHeight;
    insetStartM = isRow ? left : top;
    insetEndM = isRow ? right : bottom;
    insetStartC = isRow ? top : left;
    insetEndC = isRow ? bottom : right;
    scrollbarM = main(scrollbarWidth, scrollbarHeight);
    scrollbarC = cross(scrollbarWidth, scrollbarHeight);

    knownM = main(knownWidth, knownHeight);
    knownC = cross(knownWidth, knownHeight);
    innerM = knownM - insetStartM - insetEndM;
    innerC = knownC - insetStartC - insetEndC;
    knownMainIsDefinite = isRow ? in.widthDefinite() : in.heightDefinite();
    boolean knownCrossIsDefinite = isRow ? in.heightDefinite() : in.widthDefinite();
    hasDefiniteMain = knownMainIsDefinite && some(knownM);
    hasDefiniteCross = knownCrossIsDefinite && some(knownC);
    crossSpaceIsDefinite =
        hasDefiniteCross || definite(cross(in.availableWidth(), in.availableHeight()));

    float innerWidth = or(width(innerM, innerC), 0);
    float innerHeight = or(height(innerM, innerC), 0);
    float columnGap = resolveOrZero(s.columnGap, innerWidth);
    float rowGap = resolveOrZero(s.rowGap, innerHeight);
    gapM = main(columnGap, rowGap);
    gapC = cross(columnGap, rowGap);
  }

  /** Step 3: the flex base size and the hypothetical main size of each item. */
  private void flexBaseSizes() {
    float basisM = knownMainIsDefinite ? innerM : Float.NaN;
    for (Item item : items) {
      // The space across for sizing the item, without its margins.
      float spaceC;
      if (definite(availableC)) {
        spaceC =
            clamp(
                Math.max(or(innerC, availableC) - item.marginC(), 0),
                item.ratioMinC,
                item.ratioMaxC);
      } else if (availableC == MIN_CONTENT) {
        spaceC = or(item.ratioMinC, MIN_CONTENT);
      } else {
        spaceC = or(item.ratioMaxC, MAX_CONTENT);
      }

      boolean crossIsDefinite = some(item.sizeC);
      float knownItemC = item.knownC();
      if (item.align == Align.STRETCH && !item.autoStartC && !item.autoEndC && !some(knownItemC)) {
        knownItemC = pixels(spaceC);
        crossIsDefinite = !isWrap && hasDefiniteCross && some(innerC);
      }

      // A: a definite flex basis, or the main size when the basis is auto.
      float basis =
          or(
              resolve(item.s.basis, basisM) + item.boxM,
              item.s.basis.isAuto() ? item.sizeM : Float.NaN);
      if (some(basis)) {
        item.basisIsDefinite = true;
      } else if (crossIsDefinite && some(item.ratio) && some(knownItemC)) {
        // B: from the definite cross size through the aspect ratio.
        item.basisIsDefinite = true;
        basis = mainFromCross(knownItemC, item.ratio);
      } else {
        // C and E: the size of the content.
        basis =
            measureMain(
                item,
                knownItemC,
                Float.NaN,
                innerC,
                availableM == MIN_CONTENT ? MIN_CONTENT : MAX_CONTENT,
                spaceC);
      }
      // The specification leaves the content box unfloored here; browsers floor it.
      item.basis = Math.max(basis, item.edgesM);
      item.innerBasis = item.basis - item.edgesM;

      // 4.5: the automatic minimum size is the min-content size, unless the item scrolls.
      float styleMin = or(item.minM, item.scrollsMain ? 0 : Float.NaN);
      if (some(styleMin)) {
        item.minimumM = styleMin;
      } else {
        float minContent = measureMain(item, knownItemC, Float.NaN, innerC, MIN_CONTENT, spaceC);
        item.minimumM = Math.max(min(min(minContent, item.sizeM), item.ratioMaxM), item.edgesM);
      }

      float hypotheticalMin = Math.max(max(item.minimumM, item.ratioMinM), item.edgesM);
      item.hypotheticalM = clamp(item.basis, hypotheticalMin, item.ratioMaxM);
      item.hypotheticalOuterM = item.hypotheticalM + item.marginM();
    }
  }

  /** Step 5: collect the items into lines. */
  private void collectLines() {
    lines = new ArrayList<>(1);
    int count = items.size();
    // Wrapping needs a definite main size.
    if (!isWrap || !knownMainIsDefinite) {
      lines.add(new Line(0, count));
      return;
    }
    float space;
    if (some(maxM)) {
      space = or(pixels(availableM), maxM);
      if (!hasDefiniteMain) {
        space = Math.min(space, maxM);
      }
      space = max(space, minM);
    } else if (!isRow && !hasDefiniteMain && definite(availableM)) {
      // The automatic height of a column follows from its content; the space an ancestor hands
      // down does not make it wrap.
      space = MAX_CONTENT;
    } else {
      space = availableM;
    }

    if (space == MAX_CONTENT) {
      lines.add(new Line(0, count));
    } else if (space == MIN_CONTENT) {
      for (int i = 0; i < count; i++) {
        lines.add(new Line(i, i + 1));
      }
    } else {
      int start = 0;
      while (start < count) {
        float length = 0;
        int end = start;
        for (; end < count; end++) {
          length += items.get(end).hypotheticalOuterM + (end == start ? 0 : gapM);
          if (length > space && end != start) {
            break;
          }
        }
        lines.add(new Line(start, end));
        start = end;
      }
    }
  }

  /** Step 4, for a container whose main size follows from its items. */
  private void containerMainSize() {
    float insetM = insetStartM + insetEndM;
    float outer;
    if (definite(availableM) || (availableM == MIN_CONTENT && isWrap)) {
      float longest = lines.isEmpty() ? 0 : Float.NEGATIVE_INFINITY;
      for (Line line : lines) {
        float length = 0;
        for (int i = line.start; i < line.end; i++) {
          Item item = items.get(i);
          length += Math.max(max(item.basis, item.minM) + item.marginM(), item.edgesM);
        }
        longest = Math.max(longest, length + gaps(gapM, line.count()));
      }
      outer = longest + insetM;
      if (definite(availableM) && lines.size() > 1) {
        outer = Math.max(outer, availableM);
      }
    } else {
      outer = intrinsicMainSize() + insetM;
    }
    outer = Math.max(clamp(outer, minM, maxM), insetM - scrollbarM);
    containerM = outer;
    innerContainerM = Math.max(outer - insetM, 0);
  }

  /** <a href="https://www.w3.org/TR/css-flexbox-1/#intrinsic-main-sizes">9.9.1</a> */
  private float intrinsicMainSize() {
    float mainSize = 0;
    for (Line line : lines) {
      float sum = 0;
      float largest = 0;
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        float margin = item.marginM();
        float preferred = item.sizeM;
        // Rows clamp against the larger of flex basis and width, which is what browsers do;
        // in columns a height must not inflate the size beyond the flex basis.
        float clampingBasis = isRow ? max(item.basis, preferred) : item.basis;
        float basisMin = item.s.shrink == 0 ? clampingBasis : Float.NaN;
        float basisMax = item.s.grow == 0 ? clampingBasis : Float.NaN;
        float minMain =
            Math.max(or(or(max(item.minM, basisMin), basisMin), item.minimumM), item.minimumM);
        float maxMain = or(or(min(item.maxM, basisMax), basisMax), Float.POSITIVE_INFINITY);

        float contribution;
        if (some(preferred) && (maxMain <= minMain || maxMain <= preferred)) {
          contribution = Math.max(Math.min(preferred, maxMain), minMain) + margin;
        } else if (maxMain <= minMain) {
          contribution = minMain + margin;
        } else if (item.scrolls) {
          contribution = item.basis + margin;
        } else {
          float inner;
          if (some(preferred)) {
            inner = Math.max(preferred, item.edgesM);
          } else {
            float spaceC = availableC;
            if (definite(spaceC)) {
              spaceC = Math.max(or(innerC, spaceC) - item.marginC(), 0);
            }
            spaceC = availableClamp(spaceC, item.minC, item.maxC);
            float knownItemC = item.knownC();
            if (item.align == Align.STRETCH && !some(knownItemC)) {
              knownItemC = pixels(spaceC);
            }
            float measured = measureMain(item, knownItemC, innerM, innerC, availableM, spaceC);
            inner = max(measured, mainFromCross(knownItemC, item.ratio));
          }
          contribution =
              clamp((isRow ? inner : Math.max(inner, item.basis)) + margin, item.minM, item.maxM);
        }

        float difference = contribution - margin - item.basis;
        if (difference > 0) {
          item.contentFlexFraction = difference / Math.max(1, item.s.grow);
        } else if (difference < 0) {
          item.contentFlexFraction = difference / (Math.max(1, item.s.shrink) * item.innerBasis);
        } else {
          item.contentFlexFraction = 0;
        }

        // The specification scales by the largest fraction in the line. Browsers do not.
        float fraction = item.contentFlexFraction;
        float flexed = 0;
        if (fraction > 0) {
          flexed = Math.max(1, item.s.grow) * fraction;
        } else if (fraction < 0) {
          float scaledShrink = Math.max(1, item.s.shrink) * item.innerBasis;
          flexed = scaledShrink == 0 ? 0 : scaledShrink * fraction;
        }
        float itemSize = item.basis + flexed + margin;
        // A wrapping row is at least as wide as its widest item, as in Chrome.
        if (isWrap && isRow) {
          largest = Math.max(largest, itemSize);
        }
        sum += itemSize;
      }
      mainSize = Math.max(mainSize, Math.max(largest, sum + gaps(gapM, line.count())));
    }
    return mainSize;
  }

  /** Step 6: <a href="https://www.w3.org/TR/css-flexbox-1/#resolve-flexible-lengths">9.7</a> */
  private void resolveFlexibleLengths(Line line) {
    float totalGap = gaps(gapM, line.count());
    float space = or(innerM, 0);

    float hypothetical = 0;
    for (int i = line.start; i < line.end; i++) {
      hypothetical += items.get(i).hypotheticalOuterM;
    }
    float usedFlexFactor = totalGap + hypothetical;
    boolean growing = usedFlexFactor < space;
    boolean shrinking = usedFlexFactor > space;
    boolean exact = !growing && !shrinking;

    // Freeze the items that cannot flex.
    for (int i = line.start; i < line.end; i++) {
      Item item = items.get(i);
      item.targetM = item.hypotheticalM;
      if (exact
          || (item.s.grow == 0 && item.s.shrink == 0)
          || (growing && item.basis > item.hypotheticalM)
          || (shrinking && item.basis < item.hypotheticalM)) {
        item.frozen = true;
        item.outerTargetM = item.targetM + item.marginM();
      }
    }
    if (exact) {
      return;
    }

    float used = 0;
    for (int i = line.start; i < line.end; i++) {
      Item item = items.get(i);
      used += item.frozen ? item.outerTargetM : item.basis + item.marginM();
    }
    float initialFreeSpace = or(innerM - (totalGap + used), 0);

    while (true) {
      boolean allFrozen = true;
      float itemsSpace = 0;
      float sumGrow = 0;
      float sumShrink = 0;
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        if (item.frozen) {
          itemsSpace += item.outerTargetM;
        } else {
          allFrozen = false;
          itemsSpace += item.basis + item.marginM();
          sumGrow += item.s.grow;
          sumShrink += item.s.shrink;
        }
      }
      if (allFrozen) {
        break;
      }

      // Flex factors that sum to less than one take only that share of the free space.
      float remaining = innerM - (totalGap + itemsSpace);
      float freeSpace;
      if (growing && sumGrow < 1) {
        freeSpace = min(initialFreeSpace * sumGrow - totalGap, remaining);
      } else if (shrinking && sumShrink < 1) {
        freeSpace = max(initialFreeSpace * sumShrink - totalGap, remaining);
      } else {
        freeSpace = or(remaining, usedFlexFactor - (totalGap + itemsSpace));
      }

      if (freeSpace != 0 && Float.isFinite(freeSpace) && Math.abs(freeSpace) >= Float.MIN_NORMAL) {
        if (growing && sumGrow > 0) {
          for (int i = line.start; i < line.end; i++) {
            Item item = items.get(i);
            if (!item.frozen) {
              item.targetM = item.basis + freeSpace * (item.s.grow / sumGrow);
            }
          }
        } else if (shrinking && sumShrink > 0) {
          float sumScaledShrink = 0;
          for (int i = line.start; i < line.end; i++) {
            Item item = items.get(i);
            if (!item.frozen) {
              sumScaledShrink += item.innerBasis * item.s.shrink;
            }
          }
          if (sumScaledShrink > 0) {
            for (int i = line.start; i < line.end; i++) {
              Item item = items.get(i);
              if (!item.frozen) {
                float scaledShrink = item.innerBasis * item.s.shrink;
                item.targetM = item.basis + freeSpace * (scaledShrink / sumScaledShrink);
              }
            }
          }
        }
      }

      // Clamp, and freeze the items that the clamping moved against the direction of the total.
      float totalViolation = 0;
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        if (!item.frozen) {
          float clamped = Math.max(clamp(item.targetM, item.minimumM, item.maxM), 0);
          item.violation = clamped - item.targetM;
          item.targetM = clamped;
          item.outerTargetM = clamped + item.marginM();
          totalViolation += item.violation;
        }
      }
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        if (!item.frozen) {
          if (totalViolation > 0) {
            item.frozen = item.violation > 0;
          } else if (totalViolation < 0) {
            item.frozen = item.violation < 0;
          } else {
            item.frozen = true;
          }
        }
      }
    }
  }

  /** Step 7: lay each item out with its main size to find its cross size. */
  private void hypotheticalCrossSizes() {
    // The cross size of a single line in a container of known cross size does not depend on
    // the items, and neither does that of the items stretched to it.
    boolean lineCrossIsKnown = !isWrap && some(knownC);
    for (Item item : items) {
      if (lineCrossIsKnown && item.stretches() && !item.alignsBaseline()) {
        continue;
      }
      float cross = max(item.knownC(), item.edgesC);
      if (!some(cross)) {
        float spaceM = Math.max(containerM - item.marginM(), 0);
        float spaceC = availableC;
        if (definite(spaceC)) {
          spaceC =
              Math.max(
                  clamp(Math.max(spaceC - item.marginC(), 0), item.ratioMinC, item.ratioMaxC),
                  item.edgesC);
        }
        Out out =
            child(
                item,
                false,
                isRow ? VERTICAL : HORIZONTAL,
                false,
                item.targetM,
                Float.NaN,
                innerM,
                innerC,
                spaceM,
                spaceC);
        cross =
            Math.max(
                clamp(cross(out.width(), out.height()), item.ratioMinC, item.ratioMaxC),
                item.edgesC);
      }
      item.hypotheticalC = cross;
      item.hypotheticalOuterC = cross + item.marginC();
    }
  }

  /** Baselines exist in rows only, and are only needed where two or more items share one. */
  private void childBaselines() {
    if (!isRow) {
      return;
    }
    for (Line line : lines) {
      int aligned = 0;
      for (int i = line.start; i < line.end; i++) {
        if (items.get(i).alignsBaseline()) {
          aligned++;
        }
      }
      if (aligned <= 1) {
        continue;
      }
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        if (!item.alignsBaseline()) {
          continue;
        }
        Out out =
            child(
                item,
                true,
                BOTH,
                false,
                item.targetM,
                item.hypotheticalC,
                innerM,
                innerC,
                availableMax(containerM - item.marginM(), 0),
                availableMax(or(knownC, availableC) - item.marginC(), 0));
        item.baseline = baseline(out, item) + item.marginStartC;
      }
    }
  }

  /** Step 8: the cross size of each line. */
  private void lineCrossSizes() {
    if (!isWrap && some(knownC)) {
      lines.get(0).cross = Math.max(clamp(knownC, minC, maxC) - insetStartC - insetEndC, 0);
      return;
    }
    for (Line line : lines) {
      float maxBaseline = 0;
      for (int i = line.start; i < line.end; i++) {
        maxBaseline = Math.max(maxBaseline, items.get(i).baseline);
      }
      float cross = 0;
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        cross =
            Math.max(
                cross,
                item.alignsBaseline()
                    ? maxBaseline - item.baseline + item.hypotheticalOuterC
                    : item.hypotheticalOuterC);
      }
      line.cross = cross;
    }
    if (!isWrap) {
      float insetC = insetStartC + insetEndC;
      Line line = lines.get(0);
      line.cross = clamp(line.cross, minC - insetC, maxC - insetC);
    }
  }

  /** Step 9: {@code align-content: stretch} shares leftover cross space among the lines. */
  private void stretchLines() {
    if (alignContent != Content.STRETCH) {
      return;
    }
    float inner = or(Math.max(clamp(or(knownC, minC), minC, maxC) - insetStartC - insetEndC, 0), 0);
    float total = gaps(gapC, lines.size());
    for (Line line : lines) {
      total += line.cross;
    }
    if (total < inner) {
      float addition = (inner - total) / lines.size();
      for (Line line : lines) {
        line.cross += addition;
      }
    }
  }

  /** Step 11: the used cross size of each item. */
  private void usedCrossSizes() {
    for (Line line : lines) {
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        // Here the maximum does not transfer through the aspect ratio; browsers agree on that.
        item.targetC =
            item.stretches()
                ? clamp(Math.max(line.cross - item.marginC(), 0), item.minC, item.maxC)
                : item.hypotheticalC;
        item.outerTargetC = item.targetC + item.marginC();
      }
    }
  }

  /** Step 12: main-axis auto margins and {@code justify-content}. */
  private void distributeFreeSpace() {
    for (Line line : lines) {
      float used = 0;
      int autoMargins = 0;
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        used += item.outerTargetM;
        autoMargins += (item.autoStartM ? 1 : 0) + (item.autoEndM ? 1 : 0);
      }
      float freeSpace = innerContainerM - (gaps(gapM, line.count()) + used);
      if (freeSpace > 0 && autoMargins > 0) {
        float margin = freeSpace / autoMargins;
        for (int i = line.start; i < line.end; i++) {
          Item item = items.get(i);
          if (item.autoStartM) {
            item.marginStartM = margin;
          }
          if (item.autoEndM) {
            item.marginEndM = margin;
          }
        }
        freeSpace = 0;
      }

      int count = line.count();
      Content mode = fallback(freeSpace, count, justifyContent);
      for (int n = 0; n < count; n++) {
        Item item = items.get(isReverse ? line.end - 1 - n : line.start + n);
        item.offsetM = alignmentOffset(freeSpace, count, gapM, mode, isReverse, n == 0);
      }
    }
  }

  /** Steps 13 and 14: cross-axis auto margins, then {@code align-self}. */
  private void crossAxisMarginsAndAlignment() {
    for (Line line : lines) {
      float maxBaseline = 0;
      float maxBelowBaseline = 0;
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        maxBaseline = Math.max(maxBaseline, item.baseline);
        if (item.alignsBaseline()) {
          maxBelowBaseline = Math.max(maxBelowBaseline, item.outerTargetC - item.baseline);
        }
      }
      for (int i = line.start; i < line.end; i++) {
        Item item = items.get(i);
        float freeSpace = line.cross - item.outerTargetC;
        if (item.autoStartC && item.autoEndC) {
          item.marginStartC = freeSpace / 2;
          item.marginEndC = freeSpace / 2;
        } else if (item.autoStartC) {
          item.marginStartC = freeSpace;
        } else if (item.autoEndC) {
          item.marginEndC = freeSpace;
        } else {
          item.offsetC =
              switch (item.align) {
                case START -> 0;
                case END -> freeSpace;
                case FLEX_START, STRETCH -> isWrapReverse ? freeSpace : 0;
                case FLEX_END -> isWrapReverse ? 0 : freeSpace;
                case CENTER -> freeSpace / 2;
                case BASELINE -> {
                  if (!isRow) {
                    yield isWrapReverse ? freeSpace : 0;
                  }
                  // wrap-reverse flips the cross axis, so the group sits at the bottom.
                  yield isWrapReverse
                      ? line.cross - maxBelowBaseline - item.baseline
                      : maxBaseline - item.baseline;
                }
              };
        }
      }
    }
  }

  /** Step 15. Returns the sum of the lines' cross sizes. */
  private float containerCrossSize() {
    float linesCross = 0;
    for (Line line : lines) {
      linesCross += line.cross;
    }
    float insetC = insetStartC + insetEndC;
    float outer =
        Math.max(
            clamp(or(knownC, linesCross + gaps(gapC, lines.size()) + insetC), minC, maxC),
            insetC - scrollbarC);
    containerC = outer;
    innerContainerC = Math.max(outer - insetC, 0);
    return linesCross;
  }

  /** Step 16: {@code align-content}. */
  private void alignLines(float linesCross) {
    int count = lines.size();
    float freeSpace = innerContainerC - linesCross - gaps(gapC, count);
    Content mode = fallback(freeSpace, count, alignContent);
    for (int n = 0; n < count; n++) {
      Line line = lines.get(isWrapReverse ? count - 1 - n : n);
      line.offset = alignmentOffset(freeSpace, count, gapC, mode, isWrapReverse, n == 0);
    }
  }

  /** The final layout of every item, which also places what is inside it. */
  private void placeItems() {
    float offsetC = insetStartC;
    for (int n = 0; n < lines.size(); n++) {
      Line line = lines.get(isWrapReverse ? lines.size() - 1 - n : n);
      float offsetM = insetStartM;
      for (int k = 0; k < line.count(); k++) {
        Item item = items.get(isReverse ? line.end - 1 - k : line.start + k);
        Out out =
            child(
                item,
                true,
                BOTH,
                false,
                item.targetM,
                item.targetC,
                innerM,
                innerC,
                Math.max(containerM - item.marginM(), 0),
                Math.max(containerC - item.marginC(), 0));
        float startM = offsetM + item.offsetM + item.marginStartM;
        float startC = offsetC + item.offsetC + line.offset + item.marginStartC;
        // From here on the baseline is measured from the top of the container.
        item.baseline = (isRow ? startC : startM) + baseline(out, item);

        Node child = item.node;
        child.x = width(startM + item.insetM, startC + item.insetC);
        child.y = height(startM + item.insetM, startC + item.insetC);
        child.width = out.width();
        child.height = out.height();
        offsetM += item.offsetM + item.marginM() + main(out.width(), out.height());
      }
      offsetC += line.offset + line.cross;
    }
  }

  /**
   * An absolutely positioned child. Without insets it goes where it would sit as the only item of
   * the container, which {@code justify-content} and {@code align-self} decide.
   */
  private void placeAbsolute(Node child) {
    int edgeM =
        switch (justifyContent) {
          case START -> Absolute.START;
          case END -> Absolute.END;
          case FLEX_START, STRETCH, SPACE_BETWEEN -> isReverse ? Absolute.END : Absolute.START;
          case FLEX_END -> isReverse ? Absolute.START : Absolute.END;
          case CENTER, SPACE_AROUND, SPACE_EVENLY -> Absolute.CENTER;
        };
    Align self = child.style.alignSelf != null ? child.style.alignSelf : alignItems;
    int edgeC =
        switch (self) {
          case START, BASELINE -> Absolute.START;
          case END -> Absolute.END;
          case FLEX_START, STRETCH -> isWrapReverse ? Absolute.END : Absolute.START;
          case FLEX_END -> isWrapReverse ? Absolute.START : Absolute.END;
          case CENTER -> Absolute.CENTER;
        };

    // Insets and percentages refer to the padding box.
    float left = border.left();
    float top = border.top();
    float right = border.right() + (s.overflowY == Style.Overflow.SCROLL ? s.scrollbarWidth : 0);
    float bottom = border.bottom() + (s.overflowX == Style.Overflow.SCROLL ? s.scrollbarWidth : 0);
    float width = width(containerM, containerC);
    float height = height(containerM, containerC);

    Absolute.Axis horizontal = new Absolute.Axis();
    Absolute.Axis vertical = new Absolute.Axis();
    Absolute.Axis axisM = isRow ? horizontal : vertical;
    Absolute.Axis axisC = isRow ? vertical : horizontal;
    axisM.staticStart = insetStartM;
    axisM.staticEnd = containerM - insetEndM;
    axisM.staticEdge = edgeM;
    axisC.staticStart = insetStartC;
    axisC.staticEnd = containerC - insetEndC;
    axisC.staticEdge = edgeC;
    horizontal.scrolls = scrolls(s.overflowX);
    vertical.scrolls = scrolls(s.overflowY);
    Absolute.layout(
        child, width - left - right, height - top - bottom, left, top, horizontal, vertical);
  }

  /** 8.5: the first baseline of the container, from its first line. */
  private float firstBaseline() {
    if (lines.isEmpty()) {
      return Float.NaN;
    }
    Line line = lines.get(isWrapReverse ? lines.size() - 1 : 0);
    if (line.count() == 0) {
      return Float.NaN;
    }
    if (!isRow) {
      return items.get(isReverse ? line.end - 1 : line.start).baseline;
    }
    for (int i = line.start; i < line.end; i++) {
      if (items.get(i).alignsBaseline()) {
        return items.get(i).baseline;
      }
    }
    return items.get(line.start).baseline;
  }

  // Alignment, shared by justify-content and align-content

  /** What a distributing keyword falls back to when there is nothing to distribute. */
  private static Content fallback(float freeSpace, int count, Content mode) {
    boolean safe = false;
    if (count <= 1 || freeSpace <= 0) {
      switch (mode) {
        case STRETCH -> mode = Content.FLEX_START;
        case SPACE_BETWEEN -> {
          mode = Content.FLEX_START;
          safe = true;
        }
        case SPACE_AROUND, SPACE_EVENLY -> {
          mode = Content.CENTER;
          safe = true;
        }
        default -> {}
      }
    }
    // The fallbacks of the space-* keywords must not push content out at the start.
    return freeSpace <= 0 && safe ? Content.START : mode;
  }

  /** The distance from the previous item, or from the start for the first one. */
  private static float alignmentOffset(
      float freeSpace, int count, float gap, Content mode, boolean reversed, boolean first) {
    if (first) {
      return switch (mode) {
        case START, STRETCH, SPACE_BETWEEN -> 0;
        case FLEX_START -> reversed ? freeSpace : 0;
        case END -> freeSpace;
        case FLEX_END -> reversed ? 0 : freeSpace;
        case CENTER -> freeSpace / 2;
        case SPACE_AROUND -> freeSpace >= 0 ? freeSpace / count / 2 : freeSpace / 2;
        case SPACE_EVENLY -> freeSpace >= 0 ? freeSpace / (count + 1) : freeSpace / 2;
      };
    }
    float space = Math.max(freeSpace, 0);
    return gap
        + switch (mode) {
          case SPACE_BETWEEN -> space / (count - 1);
          case SPACE_AROUND -> space / count;
          case SPACE_EVENLY -> space / (count + 1);
          default -> 0;
        };
  }

  // Helpers

  private Out child(
      Item item,
      boolean perform,
      int axis,
      boolean definite,
      float knownM,
      float knownC,
      float parentM,
      float parentC,
      float spaceM,
      float spaceC) {
    boolean definiteM = definite || item.mainIsDefinite();
    boolean definiteC = definite || item.crossIsDefinite();
    return item.node.layout(
        new In(
            perform,
            false,
            axis,
            width(knownM, knownC),
            height(knownM, knownC),
            isRow ? definiteM : definiteC,
            isRow ? definiteC : definiteM,
            width(parentM, parentC),
            height(parentM, parentC),
            width(spaceM, spaceC),
            height(spaceM, spaceC)));
  }

  /** The main size of an item's content under the given constraints. */
  private float measureMain(
      Item item, float knownC, float parentM, float parentC, float spaceM, float spaceC) {
    Out out =
        child(
            item,
            false,
            isRow ? HORIZONTAL : VERTICAL,
            true,
            Float.NaN,
            knownC,
            parentM,
            parentC,
            spaceM,
            spaceC);
    return main(out.width(), out.height());
  }

  /**
   * The first baseline of an item, from the top of its border box. A box without one uses its
   * bottom edge. A scroll container's baseline is kept inside its box.
   */
  private static float baseline(Out out, Item item) {
    float height = out.height();
    float baseline = or(out.baseline(), height);
    return item.scrollsVertically ? Math.max(Math.min(baseline, height), 0) : baseline;
  }

  static boolean scrolls(Style.Overflow overflow) {
    return overflow == Style.Overflow.HIDDEN || overflow == Style.Overflow.SCROLL;
  }

  private static float gaps(float gap, int count) {
    return count <= 1 ? 0 : gap * (count - 1);
  }

  private float mainFromCross(float cross, float ratio) {
    return isRow ? cross * ratio : cross / ratio;
  }

  private float crossFromMain(float main, float ratio) {
    return isRow ? main / ratio : main * ratio;
  }

  private float main(float width, float height) {
    return isRow ? width : height;
  }

  private float cross(float width, float height) {
    return isRow ? height : width;
  }

  // The same selection, read the other way round.

  private float width(float main, float cross) {
    return isRow ? main : cross;
  }

  private float height(float main, float cross) {
    return isRow ? cross : main;
  }
}
