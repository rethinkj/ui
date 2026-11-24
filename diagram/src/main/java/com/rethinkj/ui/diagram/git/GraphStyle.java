package com.rethinkj.ui.diagram.git;

import com.rethinkj.ui.Context;

/**
 * The sizes and curve sampling of a commit graph cell ({@link GraphCell}); its lane colors come
 * from the theme ({@link com.rethinkj.ui.kit.Theme#lanes}). Cells read the style of {@link
 * #CURRENT}, {@link #DEFAULT} unless the app gives another: {@code
 * app.provide(GraphStyle.CURRENT, state -> style)}. Lengths are in logical pixels.
 *
 * @param rowHeight the height of a row
 * @param laneWidth the distance between adjacent lane centers
 * @param inset the horizontal distance from the cell's edge to the outermost lane center; the cell
 *     width is {@code 2 * inset + (graph.width() - 1) * laneWidth}
 * @param stroke the width of a lane's line
 * @param dotRadius the radius of an ordinary commit's filled dot
 * @param mergeRadius the outer radius of a merge commit's hollow ring
 * @param mergeWidth the stroke width of a merge commit's ring, less than {@code mergeRadius}
 * @param headRadius the outer radius of the HEAD commit's dot
 * @param headHoleRadius the radius of the hole inside the HEAD commit's dot, less than {@code
 *     headRadius}
 * @param halo the width of the gap in the row's background color around a commit dot
 * @param selectionRadius the outer radius of the ring drawn around a selected commit
 * @param selectionWidth the stroke width of the selection ring, less than {@code selectionRadius}
 * @param fading how far a faded lane's color is mixed towards the row's background, from 0 to 1
 * @param curveSegments the number of line segments sampled for each half of an S-curve, at least 1
 */
public record GraphStyle(
    float rowHeight,
    float laneWidth,
    float inset,
    float stroke,
    float dotRadius,
    float mergeRadius,
    float mergeWidth,
    float headRadius,
    float headHoleRadius,
    float halo,
    float selectionRadius,
    float selectionWidth,
    float fading,
    int curveSegments) {

  /** The module's style. */
  public static final GraphStyle DEFAULT =
      new GraphStyle(32, 16, 16, 2, 4, 6, 2, 6, 2.4f, 2, 10, 1.5f, 0.6f, 6);

  /** The style a graph cell reads, {@link #DEFAULT} unless the app gives another. */
  public static final Context<GraphStyle> CURRENT = new Context<>(DEFAULT);

  public GraphStyle {
    if (!(rowHeight > 0
            && laneWidth > 0
            && inset >= 0
            && stroke > 0
            && dotRadius > 0
            && mergeRadius > 0
            && mergeWidth > 0
            && headRadius > 0
            && headHoleRadius >= 0
            && halo >= 0
            && selectionRadius > 0
            && selectionWidth > 0)
        || !Float.isFinite(rowHeight
            + laneWidth
            + inset
            + stroke
            + dotRadius
            + mergeRadius
            + mergeWidth
            + headRadius
            + headHoleRadius
            + halo
            + selectionRadius
            + selectionWidth)) {
      throw new IllegalArgumentException(
          "sizes must be finite and positive (inset, headHoleRadius and halo may be 0)");
    }
    if (!(mergeWidth < mergeRadius)) {
      throw new IllegalArgumentException(
          "mergeWidth must be less than mergeRadius, were " + mergeWidth + " and " + mergeRadius);
    }
    if (!(headHoleRadius < headRadius)) {
      throw new IllegalArgumentException("headHoleRadius must be less than headRadius, were "
          + headHoleRadius
          + " and "
          + headRadius);
    }
    if (!(selectionWidth < selectionRadius)) {
      throw new IllegalArgumentException("selectionWidth must be less than selectionRadius, were "
          + selectionWidth
          + " and "
          + selectionRadius);
    }
    if (!(fading >= 0 && fading <= 1)) {
      throw new IllegalArgumentException("fading must be from 0 to 1, was " + fading);
    }
    if (curveSegments < 1) {
      throw new IllegalArgumentException("curveSegments must be at least 1, was " + curveSegments);
    }
  }
}
