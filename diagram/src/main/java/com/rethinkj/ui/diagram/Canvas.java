package com.rethinkj.ui.diagram;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Theme;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A pannable, zoomable area on which nodes, any elements, lie at positions in diagram
 * coordinates. The app keeps the nodes and a {@link Viewport}, and changes a node when the canvas
 * reports that the user moved it:
 *
 * <pre>{@code
 * canvas(state.viewport, state.tables, Tables::card, state::move).grow(1);
 * }</pre>
 *
 * <p>Diagram coordinates are logical pixels at a zoom of 100 %. The viewport says which diagram
 * point lies at the canvas's top left and the zoom, one of {@link Viewport#ZOOMS}. The canvas
 * places each node with its size multiplied by the zoom and builds its content at that size
 * ({@link NodeContent}), with no transform, so that text stays sharp at every zoom. It builds only
 * the nodes that lie inside it, so that a large diagram stays fast; in the first build, before
 * layout gave it a size, that is none.
 *
 * <p>Dragging empty space pans. Dragging a node reports it moved and {@link Node#pinned pinned} to
 * {@code onMove}, while the pointer moves, and as it was when the drag is cancelled. While the
 * canvas has the focus, {@code +} and {@code -} zoom in and out by a step around its center and
 * {@code 0} fits all nodes in ({@link Viewport#fit}). The canvas fills itself with {@link
 * Theme.Surfaces#window} and clips its nodes. It needs a size of its own or room to grow.
 *
 * <p>In a tree dump the canvas is {@code role=graphics-document} with the label {@link
 * DiagramTexts#diagram} unless the app gives another, and its zoom as the value ({@link
 * DiagramTexts#zoom}). Each node is {@code role=graphics-object} with its key and label, and the
 * value {@link DiagramTexts#pinned} when it is pinned.
 */
public final class Canvas {

  private Canvas() {}

  /**
   * A node of a diagram: where it is and how big, in diagram coordinates. The app builds what it
   * shows with a {@link NodeContent}.
   *
   * @param key unique among the canvas's nodes; the node's element has it as its {@link El#key}
   * @param label what the node is, for the tree dump and screen readers; null for none
   * @param x the left edge
   * @param y the top edge
   * @param width the width at a zoom of 100 %
   * @param height the height at a zoom of 100 %
   * @param pinned whether the user moved it, so that a layout leaves it where it is. The canvas
   *     only reports it; the app keeps it.
   */
  public record Node(
      String key, String label, double x, double y, float width, float height, boolean pinned) {

    public Node {
      Objects.requireNonNull(key, "key");
      if (!Double.isFinite(x) || !Double.isFinite(y)) {
        throw new IllegalArgumentException("a position must be finite, was " + x + ", " + y);
      }
      if (!(width >= 0 && width < Float.POSITIVE_INFINITY)
          || !(height >= 0 && height < Float.POSITIVE_INFINITY)) {
        throw new IllegalArgumentException(
            "a size must be 0 or more and finite, was " + width + "x" + height);
      }
    }

    /** An unpinned node. */
    public Node(String key, String label, double x, double y, float width, float height) {
      this(key, label, x, y, width, height, false);
    }

    /** This node at another place, pinned there, as a drag reports it. */
    public Node movedTo(double x, double y) {
      return new Node(key, label, x, y, width, height, true);
    }
  }

  /**
   * Builds what a node shows, at the size the zoom gives it: its {@link Node#width} and {@link
   * Node#height} times {@code zoom}. It is the canvas's level of detail: a node can show less when
   * zoomed out, such as only its name.
   */
  @FunctionalInterface
  public interface NodeContent {
    El build(Node node, float zoom);
  }

  /**
   * What part of the diagram a canvas shows: the diagram point at its top left and the zoom. The
   * app keeps one per canvas in its state and may change it, as the canvas does on input.
   */
  public static final class Viewport {

    /** The zooms the canvas has, from the furthest out to the nearest in. */
    public static final List<Float> ZOOMS = List.of(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f);

    private static final int ACTUAL = ZOOMS.indexOf(1f);

    private double left;
    private double top;
    private int step = ACTUAL;
    // The canvas's size in the last layout; 0 before the first.
    private float width;
    private float height;
    private boolean fitDue;
    // The node being dragged, which the canvas builds also outside it, or its drag would end.
    private String dragged;

    /** A viewport at 100 % with the diagram's origin at the canvas's top left. */
    public Viewport() {}

    /** The diagram coordinate at the canvas's left edge. */
    public double left() {
      return left;
    }

    /** The diagram coordinate at the canvas's top edge. */
    public double top() {
      return top;
    }

    /** The zoom, one of {@link #ZOOMS}. */
    public float zoom() {
      return ZOOMS.get(step);
    }

    /** Shows the diagram from this point at the canvas's top left, at the same zoom. */
    public void panTo(double left, double top) {
      if (!Double.isFinite(left) || !Double.isFinite(top)) {
        throw new IllegalArgumentException("a position must be finite, was " + left + ", " + top);
      }
      this.left = left;
      this.top = top;
    }

    /**
     * Zooms by this many steps of {@link #ZOOMS}, in when positive, and keeps the diagram point
     * under {@code x}, {@code y} in place: the pointer's place in the canvas for the wheel, its
     * center for the keys. Past either end it stays at that end.
     *
     * @param x from the canvas's left, in logical pixels
     * @param y from its top
     */
    public void zoomBy(int steps, float x, float y) {
      float before = zoom();
      step = Math.clamp((long) step + steps, 0, ZOOMS.size() - 1);
      float after = zoom();
      left += x / before - x / after;
      top += y / before - y / after;
    }

    /** Zooms in by a step around the canvas's center. */
    public void zoomIn() {
      zoomBy(1, width / 2, height / 2);
    }

    /** Zooms out by a step around the canvas's center. */
    public void zoomOut() {
      zoomBy(-1, width / 2, height / 2);
    }

    /**
     * Shows all nodes at the nearest zoom at which they fit into the canvas with a margin of
     * {@link Theme.Spacing#xl}, centered; at the furthest zoom where they fit at none. The canvas
     * does it in its next build that knows its size, so that an app can call it before the first.
     */
    public void fit() {
      fitDue = true;
    }

    void fitIfDue(List<Node> nodes, float margin) {
      if (!fitDue || width <= 0 || height <= 0) {
        return;
      }
      fitDue = false;
      if (nodes.isEmpty()) {
        return;
      }
      double minX = Double.POSITIVE_INFINITY;
      double minY = Double.POSITIVE_INFINITY;
      double maxX = Double.NEGATIVE_INFINITY;
      double maxY = Double.NEGATIVE_INFINITY;
      for (Node node : nodes) {
        minX = Math.min(minX, node.x());
        minY = Math.min(minY, node.y());
        maxX = Math.max(maxX, node.x() + node.width());
        maxY = Math.max(maxY, node.y() + node.height());
      }
      step = 0;
      for (int i = ZOOMS.size() - 1; i > 0; i--) {
        float zoom = ZOOMS.get(i);
        if ((maxX - minX) * zoom + 2 * margin <= width
            && (maxY - minY) * zoom + 2 * margin <= height) {
          step = i;
          break;
        }
      }
      left = (minX + maxX) / 2 - width / 2 / zoom();
      top = (minY + maxY) / 2 - height / 2 / zoom();
    }
  }

  /**
   * @param viewport what part of the diagram it shows, from the app's state
   * @param nodes the nodes, each with a key of its own; later ones lie on top
   * @param content builds what each node shows at the zoom of the moment
   * @param onMove told a node moved and pinned while the user drags it, and the node as it was
   *     when the drag is cancelled; null fixes the nodes, while the canvas still pans
   */
  public static El canvas(
      Viewport viewport, List<Node> nodes, NodeContent content, Consumer<Node> onMove) {
    Theme theme = Theme.CURRENT.get();
    DiagramTexts texts = DiagramTexts.CURRENT.get();
    viewport.fitIfDue(nodes, theme.space().xl());
    float zoom = viewport.zoom();
    double left = viewport.left;
    double top = viewport.top;
    double right = left + viewport.width / zoom;
    double bottom = top + viewport.height / zoom;
    El canvas = el().role("graphics-document")
        .label(texts.diagram())
        .value(texts.zoom(Math.round(zoom * 100)))
        .fill(theme.surface().window())
        .clip()
        .focusable()
        .focusRing(theme.accent().edge())
        .onResize((width, height) -> {
          viewport.width = width;
          viewport.height = height;
        })
        .onKey(key -> key(viewport, key))
        .onDrag(drag -> {
          // From where the view the drag began in showed the diagram, to undo a cancel.
          float by = drag.phase() == Drag.Phase.CANCEL ? 0 : 1;
          viewport.panTo(left - by * drag.dx() / zoom, top - by * drag.dy() / zoom);
        });
    for (Node node : nodes) {
      boolean inside = node.x() < right
          && node.x() + node.width() > left
          && node.y() < bottom
          && node.y() + node.height() > top;
      if (!inside && !node.key().equals(viewport.dragged)) {
        continue;
      }
      El box = el().key(node.key())
          .role("graphics-object")
          .label(node.label())
          .absolute(Math.round((node.x() - left) * zoom), Math.round((node.y() - top) * zoom))
          .size(Math.round(node.width() * zoom), Math.round(node.height() * zoom))
          .child(content.build(node, zoom));
      if (node.pinned()) {
        box.value(texts.pinned());
      }
      if (onMove != null) {
        box.onDrag(drag -> move(viewport, node, zoom, onMove, drag));
      }
      canvas.child(box);
    }
    return canvas;
  }

  private static void move(
      Viewport viewport, Node node, float zoom, Consumer<Node> onMove, Drag drag) {
    switch (drag.phase()) {
      case START -> viewport.dragged = node.key();
      case END, CANCEL -> viewport.dragged = null;
      default -> {}
    }
    onMove.accept(
        drag.phase() == Drag.Phase.CANCEL
            ? node
            : node.movedTo(node.x() + drag.dx() / zoom, node.y() + drag.dy() / zoom));
  }

  private static boolean key(Viewport viewport, Key key) {
    if ((key.modifiers() & ~Key.SHIFT) != 0) {
      return false;
    }
    switch (key.name()) {
      case "+", "=", "NumpadAdd" -> viewport.zoomIn();
      case "-", "NumpadSubtract" -> viewport.zoomOut();
      case "0", "Numpad0" -> viewport.fit();
      default -> {
        return false;
      }
    }
    return true;
  }
}
