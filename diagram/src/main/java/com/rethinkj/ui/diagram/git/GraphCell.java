package com.rethinkj.ui.diagram.git;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Segment;
import com.rethinkj.ui.diagram.git.CommitGraph.Line;
import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Draws one row's cell of a {@link CommitGraph}, clipped to its row box so a virtual list that
 * builds only visible rows draws a continuous graph.
 *
 * <p>Each cell draws the lower half of the lines in {@link CommitGraph#above(int)} (from the row's
 * top edge to its vertical center) and the upper half of the lines in {@link
 * CommitGraph#below(int)} (from the center to the bottom edge). Lane changes follow a cubic S-curve
 * sampled into {@link Segment}s that meet the neighbor row cleanly at the edge. The commit dot lies
 * on top: a filled disk for an ordinary commit, a hollow ring for a merge (two or more outgoing
 * lines from the commit's column), a larger disk with a hole for HEAD, and an extra outer ring when
 * selected. Colors come from {@link Theme#CURRENT}'s {@link Theme#lanes()} and sizes from {@link
 * GraphStyle#CURRENT}.
 */
public final class GraphCell {

  private GraphCell() {}

  /**
   * Builds the graph cell for one row of {@code graph}.
   *
   * @param graph the lane layout of the commit history
   * @param row the zero-based row index
   * @param head whether this commit is HEAD
   * @param selected whether this row is selected
   * @param background the row's opaque background color (normal, hovered or selected), used for dot
   *     halos, holes and fading
   * @param faded tests a lane's chain index ({@link CommitGraph#color(int)}, {@link Line#color()})
   *     for whether that lane is faded by mixing towards {@code background}; {@code null} for none
   * @return a fixed-size element for the row's graph column
   */
  public static El cell(
      CommitGraph graph,
      int row,
      boolean head,
      boolean selected,
      int background,
      IntPredicate faded) {
    Objects.requireNonNull(graph, "graph");
    int col = graph.column(row);
    int chain = graph.color(row);
    List<Line> above = graph.above(row);
    List<Line> below = graph.below(row);

    Theme theme = Theme.CURRENT.get();
    GraphStyle style = GraphStyle.CURRENT.get();
    List<Integer> palette = theme.lanes().colors();

    float rowHeight = style.rowHeight();
    float centerY = rowHeight / 2;
    float cellWidth = 2 * style.inset() + (graph.width() - 1) * style.laneWidth();

    List<Segment> segments = new ArrayList<>();
    if (faded == null) {
      addHalf(segments, above, true, style, palette, background, null, false);
      addHalf(segments, below, false, style, palette, background, null, false);
    } else {
      // Draw faded lines underneath active ones while keeping straight lines before curves.
      addHalf(segments, above, true, style, palette, background, faded, true);
      addHalf(segments, below, false, style, palette, background, faded, true);
      addHalf(segments, above, true, style, palette, background, faded, false);
      addHalf(segments, below, false, style, palette, background, faded, false);
    }

    El cell = el().size(cellWidth, rowHeight).shrink(0).clip();
    if (!segments.isEmpty()) {
      cell.segments(segments);
    }

    float cx = laneX(col, style);
    int dotColor = laneColor(chain, palette, background, style.fading(), faded);
    float halo = style.halo();

    if (head) {
      if (halo > 0) {
        cell.child(circle(cx, centerY, style.headRadius() + halo).fill(background));
      }
      El dot = circle(cx, centerY, style.headRadius());
      if (style.headHoleRadius() > 0) {
        dot.fill(background).border(style.headRadius() - style.headHoleRadius(), dotColor);
      } else {
        dot.fill(dotColor);
      }
      cell.child(dot);
    } else if (isMerge(col, below)) {
      if (halo > 0) {
        cell.child(circle(cx, centerY, style.mergeRadius() + halo).fill(background));
      }
      cell.child(circle(cx, centerY, style.mergeRadius())
          .fill(background)
          .border(style.mergeWidth(), dotColor));
    } else if (halo > 0) {
      cell.child(
          circle(cx, centerY, style.dotRadius() + halo).fill(dotColor).border(halo, background));
    } else {
      cell.child(circle(cx, centerY, style.dotRadius()).fill(dotColor));
    }

    if (selected) {
      cell.child(
          circle(cx, centerY, style.selectionRadius()).border(style.selectionWidth(), dotColor));
    }

    return cell;
  }

  private static void addHalf(
      List<Segment> out,
      List<Line> lines,
      boolean upperHalfOfRow,
      GraphStyle style,
      List<Integer> palette,
      int background,
      IntPredicate faded,
      boolean wantFaded) {
    float rowHeight = style.rowHeight();
    float centerY = rowHeight / 2;
    float stroke = style.stroke();
    int n = style.curveSegments();
    // Extend tilted S-curves past u = 0.5 by enough steps to cover half the stroke width so the
    // row's clip cuts the full stroke cleanly at the edge without a notch.
    int extra = Math.min(n, Math.max(1, (int) Math.ceil(stroke * n / rowHeight)));

    for (Line line : lines) {
      boolean isFaded = faded != null && faded.test(line.color());
      if (faded != null && isFaded != wantFaded) {
        continue;
      }
      int color = laneColor(line.color(), palette, background, style.fading(), isFaded);
      float xFrom = laneX(line.from(), style);
      float xTo = laneX(line.to(), style);
      if (line.from() == line.to()) {
        float y1 = upperHalfOfRow ? 0 : centerY;
        float y2 = upperHalfOfRow ? centerY : rowHeight;
        out.add(new Segment(xFrom, y1, xFrom, y2, stroke, color));
      } else {
        int start = upperHalfOfRow ? n - extra : 0;
        int end = upperHalfOfRow ? 2 * n : n + extra;
        float yOffset = upperHalfOfRow ? -0.5f : 0.5f;
        float total = 2f * n;
        float u0 = start / total;
        float px = curveX(xFrom, xTo, u0);
        float py = (u0 + yOffset) * rowHeight;
        for (int i = start + 1; i <= end; i++) {
          float u = i / total;
          float nx = curveX(xFrom, xTo, u);
          float ny = (u + yOffset) * rowHeight;
          out.add(new Segment(px, py, nx, ny, stroke, color));
          px = nx;
          py = ny;
        }
      }
    }
  }

  private static float laneX(int lane, GraphStyle style) {
    return style.inset() + lane * style.laneWidth();
  }

  private static float curveX(float xFrom, float xTo, float u) {
    float s = u * u * (3 - 2 * u);
    return xFrom + (xTo - xFrom) * s;
  }

  private static boolean isMerge(int col, List<Line> below) {
    int parents = 0;
    for (Line line : below) {
      if (line.from() == col && ++parents >= 2) {
        return true;
      }
    }
    return false;
  }

  private static int laneColor(
      int chain, List<Integer> palette, int background, float fading, IntPredicate faded) {
    return laneColor(chain, palette, background, fading, faded != null && faded.test(chain));
  }

  private static int laneColor(
      int chain, List<Integer> palette, int background, float fading, boolean isFaded) {
    int color = palette.get(Math.floorMod(chain, palette.size()));
    return isFaded ? Colors.mix(color, background, fading) : color;
  }

  private static El circle(float cx, float cy, float radius) {
    // Flex rounds each edge (c - r, c + r) to whole logical pixels via floor(v + 0.5). When c is
    // whole and r ends in .5 (such as selectionRadius = 9.5), both edges round up and shift the
    // center by +0.5; snapping r to match c's parity keeps every dot and ring centered on (cx, cy).
    float rx = symmetricRadius(cx, radius);
    float ry = symmetricRadius(cy, radius);
    return el().inert().absolute(cx - rx, cy - ry).size(2 * rx, 2 * ry).radius(Math.max(rx, ry));
  }

  private static float symmetricRadius(float center, float radius) {
    float half = Math.floorMod(Math.round(center * 2), 2) == 0 ? 0 : 0.5f;
    float snapped = Math.round(radius - half) + half;
    return snapped > 0 ? snapped : (half > 0 ? half : 1);
  }
}
