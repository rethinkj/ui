package com.rethinkj.ui.layout;

import static com.rethinkj.ui.layout.Maybe.availableMax;
import static com.rethinkj.ui.layout.Maybe.or;
import static com.rethinkj.ui.layout.Maybe.pixels;
import static com.rethinkj.ui.layout.Maybe.resolve;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.layout.Node.In;
import com.rethinkj.ui.layout.Node.Out;

/** Lays out a tree of elements with CSS flexbox. */
public final class Flex {

  private Flex() {}

  /**
   * Computes the rectangle of {@code root} and of every element below it and stores each with
   * {@link El#place}. Rectangles are rounded to whole pixels in a way that leaves no gaps between
   * neighbours.
   *
   * <p>The root is sized by its own style and placed at its margin. An element that is, or is
   * inside, {@code display: none} gets an empty rectangle.
   *
   * @param availableWidth the width the root may fill, in pixels: the window's content width.
   *     {@link Measure#MAX_CONTENT} lets the root be as wide as its content.
   * @param availableHeight the same for the height
   */
  public static void layout(El root, float availableWidth, float availableHeight) {
    layout(root, availableWidth, availableHeight, true);
  }

  static void layout(El root, float availableWidth, float availableHeight, boolean round) {
    if (Float.isNaN(availableWidth) || Float.isNaN(availableHeight)) {
      throw new IllegalArgumentException("available size is NaN");
    }
    Node node = new Node(root);
    Style s = node.style;
    float parentWidth = pixels(availableWidth);
    float parentHeight = pixels(availableHeight);
    Edges margin = Edges.margin(s, parentWidth);
    Out out = node.layout(new In(
        true,
        true,
        Node.BOTH,
        Float.NaN,
        Float.NaN,
        true,
        true,
        parentWidth,
        parentHeight,
        availableMax(availableWidth - margin.horizontal(), 0),
        availableMax(availableHeight - margin.vertical(), 0)));
    // shortcut: a root with position: absolute is placed like a relative one, so its insets
    // move it but do not size it. Upgrade when a root needs to stretch between insets.
    node.x =
        margin.left() + or(resolve(s.left, parentWidth), or(-resolve(s.right, parentWidth), 0));
    node.y =
        margin.top() + or(resolve(s.top, parentHeight), or(-resolve(s.bottom, parentHeight), 0));
    node.width = out.width();
    node.height = out.height();
    place(node, 0, 0, round);
  }

  /**
   * Rounds edges, not sizes, and does so in window coordinates. Rounding each width on its own
   * would open one-pixel gaps between neighbours.
   */
  private static void place(Node node, float parentX, float parentY, boolean round) {
    float x = parentX + node.x;
    float y = parentY + node.y;
    if (round) {
      node.el.place(
          round(x) - round(parentX),
          round(y) - round(parentY),
          round(x + node.width) - round(x),
          round(y + node.height) - round(y));
    } else {
      node.el.place(node.x, node.y, node.width, node.height);
    }
    for (Node child : node.children) {
      place(child, x, y, round);
    }
  }

  /** Halves away from zero, unlike {@link Math#round}. */
  private static float round(float value) {
    return (float) (value < 0 ? -Math.floor(0.5 - value) : Math.floor(value + 0.5));
  }
}
