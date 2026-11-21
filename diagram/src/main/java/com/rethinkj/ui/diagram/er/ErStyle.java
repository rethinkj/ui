package com.rethinkj.ui.diagram.er;

import com.rethinkj.ui.Context;

/**
 * The sizes and thresholds of an {@link ErDiagram}; its colors come from the theme. The diagram
 * reads the style of {@link #CURRENT}, {@link #DEFAULT} unless the app gives another: {@code
 * app.provide(ErStyle.CURRENT, state -> style)}. Lengths are in diagram coordinates, logical pixels
 * at a zoom of 100 %.
 *
 * @param width the width of a table
 * @param header the height of a table's header, with its name
 * @param row the height of a column's row
 * @param frame the room between a schema's frame and its tables
 * @param frameLabel the height of the line above a schema's tables with its name
 * @param nudge how far Shift and an arrow key move a table
 * @param dimming how far a table that is not related to the selected one is mixed towards the
 *     canvas's color, from 0 to 1
 * @param skeleton the zoom below which tables show bars for their columns instead of text
 * @param names the zoom below which tables show only their names
 * @param barHeight the height of a column's bar below {@code skeleton}
 * @param barShare the most of a table's inner width a bar takes, from 0 to 1
 * @param barPerCharacter a bar's width per character of the column's name
 */
public record ErStyle(
    float width,
    float header,
    float row,
    float frame,
    float frameLabel,
    float nudge,
    float dimming,
    float skeleton,
    float names,
    float barHeight,
    float barShare,
    float barPerCharacter) {

  /** The module's style. */
  public static final ErStyle DEFAULT =
      new ErStyle(240, 32, 24, 24, 28, 8, 0.6f, 0.6f, 0.5f, 6, 0.6f, 7);

  /** The style a diagram reads, {@link #DEFAULT} unless the app gives another. */
  public static final Context<ErStyle> CURRENT = new Context<>(DEFAULT);

  public ErStyle {
    if (!(width > 0
            && header > 0
            && row > 0
            && frame >= 0
            && frameLabel >= 0
            && nudge > 0
            && barHeight > 0
            && barPerCharacter > 0)
        || !Float.isFinite(
            width + header + row + frame + frameLabel + nudge + barHeight + barPerCharacter)) {
      throw new IllegalArgumentException(
          "a size must be finite, more than 0 for the table's and bars'");
    }
    if (!(dimming >= 0 && dimming <= 1)) {
      throw new IllegalArgumentException("dimming must be from 0 to 1, was " + dimming);
    }
    if (!(barShare >= 0 && barShare <= 1)) {
      throw new IllegalArgumentException("barShare must be from 0 to 1, was " + barShare);
    }
    if (!(names >= 0 && names <= skeleton && Float.isFinite(skeleton))) {
      throw new IllegalArgumentException(
          "the zooms must be finite, names 0 or more and at most skeleton, were "
              + names
              + " and "
              + skeleton);
    }
  }
}
