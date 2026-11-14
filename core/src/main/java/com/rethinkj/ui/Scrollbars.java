package com.rethinkj.ui;

/**
 * How the scrollbars of a scroll area look, which {@link El#scrollbars} gives it: a bar along the
 * right edge while the content is taller than the area, and one along the bottom while it is
 * wider. Each bar is a thumb in a track without a fill of its own, drawn over the content.
 *
 * @param thickness the width of a vertical bar and the height of a horizontal one, in logical
 *     pixels, above 0
 * @param margin the space between a bar and the edges of the area, and between the end of one bar
 *     and the other; the strip of {@code thickness + 2 * margin} along the edge takes the presses
 * @param radius the corner radius of the thumb
 * @param minThumb the shortest a thumb gets, however long the content, unless the track is shorter
 * @param thumb the colour of the thumb at rest, as 0xAARRGGBB
 * @param thumbHovered its colour while the pointer is over it
 * @param thumbDragged its colour while it is dragged
 */
public record Scrollbars(
    float thickness,
    float margin,
    float radius,
    float minThumb,
    int thumb,
    int thumbHovered,
    int thumbDragged) {

  public Scrollbars {
    if (!(thickness > 0 && thickness < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("thickness must be above 0 and finite, was " + thickness);
    }
    if (!(margin >= 0 && margin < Float.POSITIVE_INFINITY)
        || !(radius >= 0 && radius < Float.POSITIVE_INFINITY)
        || !(minThumb >= 0 && minThumb < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("margin, radius and minThumb must be 0 or more and"
          + " finite, were " + margin + ", " + radius + ", " + minThumb);
    }
  }

  /** The width of the strip along an edge that a bar and its margins occupy, in logical pixels. */
  public float strip() {
    return thickness + 2 * margin;
  }

  /**
   * A bar as the last frame shows it, in logical pixels of the window: the track, along which the
   * thumb moves, and the thumb, whose length is the share of the content in view and whose place
   * is the offset. {@link El#horizontalBar} and {@link El#verticalBar} give it.
   *
   * @param color the thumb's colour of the moment: at rest, hovered or dragged
   */
  public record Bar(
      float trackX,
      float trackY,
      float trackWidth,
      float trackHeight,
      float thumbX,
      float thumbY,
      float thumbWidth,
      float thumbHeight,
      int color) {

    Bar color(int argb) {
      return new Bar(
          trackX, trackY, trackWidth, trackHeight, thumbX, thumbY, thumbWidth, thumbHeight, argb);
    }
  }
}
