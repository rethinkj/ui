package com.rethinkj.ui.diagram;

import com.rethinkj.ui.Context;

/**
 * The sizes of a canvas's {@link Canvas.Edge edges}; their colors come from the theme. The canvas
 * reads the style of {@link #CURRENT}, {@link #DEFAULT} unless the app gives another: {@code
 * app.provide(EdgeStyle.CURRENT, state -> style)}.
 *
 * @param stub how far an edge runs straight out of a port before it bends where there is room, in
 *     diagram coordinates; where there is less, as far as its marker and a bend take
 * @param radius the radius of a bend, in diagram coordinates; a bend between two short runs is
 *     tighter
 * @param lane the distance between two edges that run side by side, in diagram coordinates
 * @param marker how long a marker's crow's foot or arrowhead is, in diagram coordinates; its bars
 *     and circle are in proportion
 * @param smallestMarker the smallest a marker is drawn, in logical pixels: zoomed out further, an
 *     edge shows none, as a marker that small cannot be told from the others
 * @param width how wide an edge's line is, in logical pixels at every zoom, at least 1
 * @param highlightedWidth how wide a {@link Canvas.Emphasis#HIGHLIGHTED} edge's line is
 * @param hit how wide the band along an edge is that shows its tooltip, in logical pixels at every
 *     zoom
 * @param dimming how far a {@link Canvas.Emphasis#DIMMED} edge's color is mixed towards the
 *     canvas's, from 0 to 1
 */
public record EdgeStyle(
    float stub,
    float radius,
    float lane,
    float marker,
    float smallestMarker,
    float width,
    float highlightedWidth,
    float hit,
    float dimming) {

  /** The module's style. */
  public static final EdgeStyle DEFAULT = new EdgeStyle(30, 6, 10, 10, 5, 1, 2, 8, 0.6f);

  /** The style a canvas reads, {@link #DEFAULT} unless the app gives another. */
  public static final Context<EdgeStyle> CURRENT = new Context<>(DEFAULT);

  public EdgeStyle {
    if (!(stub >= 0 && radius >= 0 && lane >= 0 && marker >= 0 && smallestMarker >= 0 && hit >= 0)
        || !Float.isFinite(stub + radius + lane + marker + smallestMarker + hit)) {
      throw new IllegalArgumentException("a length must be 0 or more and finite");
    }
    if (!(width >= 1 && highlightedWidth >= 1)
        || !Float.isFinite(width)
        || !Float.isFinite(highlightedWidth)) {
      throw new IllegalArgumentException("a line's width must be 1 or more and finite");
    }
    if (!(dimming >= 0 && dimming <= 1)) {
      throw new IllegalArgumentException("dimming must be from 0 to 1, was " + dimming);
    }
  }
}
