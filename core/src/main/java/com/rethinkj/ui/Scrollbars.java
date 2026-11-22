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
 * @param thumb the color of the thumb at rest, as 0xAARRGGBB
 * @param thumbHovered its color while the pointer is over it
 * @param thumbDragged its color while it is dragged
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
   * @param color the thumb's color of the moment: at rest, hovered or dragged
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

  /**
   * An overview mark along a multi-line field's vertical scrollbar ({@link El#scrollMarks}): a
   * colored tick at the relative vertical position of characters {@code from} to {@code to} of the
   * field's text, such as a diagnostic or a find match. A press on a tick scrolls its first line
   * into the middle of the view.
   *
   * @param from the start offset in the field's text, 0 or more
   * @param to the end offset, {@code from} or more
   * @param color the tick's color, as 0xAARRGGBB
   */
  public record Mark(int from, int to, int color) {
    public Mark {
      if (from < 0 || to < from) {
        throw new IllegalArgumentException(
            "mark range must have 0 <= from <= to, were " + from + " and " + to);
      }
    }
  }

  /**
   * The tick of one or more {@link Mark}s as the last frame shows it along a vertical bar ({@link
   * El#verticalTicks}), across the bar's track: marks of one color close enough to overlap share
   * one.
   *
   * @param y its top, in logical pixels of the window
   * @param height its length along the track, at least half the bar's thickness
   * @param color its color, as 0xAARRGGBB
   */
  public record Tick(float y, float height, int color) {}
}
