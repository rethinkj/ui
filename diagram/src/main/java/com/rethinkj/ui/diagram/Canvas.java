package com.rethinkj.ui.diagram;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.window.WindowLoop;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * <p>{@link Edge Edges} between the nodes' {@link Port ports} lie under the nodes, routed at right
 * angles around them ({@link #canvas(Viewport, List, List, NodeContent, Consumer)}).
 *
 * <p>Dragging empty space and the wheel pan, and Control+wheel zooms by a step for each step of the
 * wheel ({@link WindowLoop#WHEEL_STEP}) around the pointer. Dragging a node reports it moved and
 * {@link Node#pinned pinned} to {@code onMove}, while the pointer moves, and as it was when the
 * drag is cancelled. While the canvas has the focus, {@code +} and {@code -} zoom in and out by a step around its center and
 * {@code 0} fits all nodes in ({@link Viewport#fit}). The canvas fills itself with {@link
 * Theme.Surfaces#window} and clips its nodes. It needs a size of its own or room to grow.
 *
 * <p>A node takes the pointer as a whole: content with a press or drag handler of its own keeps
 * the pointer there, and the node does not move. Content that selects itself on a press takes it
 * with {@link El#onPointerDown}, which leaves the drag to the node. A canvas learns its size when layout gives it a
 * new one; one that shows another viewport in the same place needs another {@link El#key}, such as
 * the diagram's name, or the new viewport learns no size and the canvas shows no nodes.
 *
 * <p>In a tree dump the canvas is {@code role=graphics-document} with the label {@link
 * DiagramTexts#diagram} unless the app gives another, and its zoom as the value ({@link
 * DiagramTexts#zoom}). Each node is {@code role=graphics-object} with its key and label, and the
 * value {@link DiagramTexts#pinned} when it is pinned.
 *
 * <p>{@link #png} saves the whole diagram as an image.
 */
public final class Canvas {

  // The most physical pixels of an image of png(): 16 megapixels, 256 MB while it is drawn.
  private static final double PNG_PIXELS = 1 << 24;

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
   * Where an edge meets a node: at the height of a row inside it, on the side that faces the other
   * end, left or right, which the canvas picks.
   *
   * @param node the key of the node
   * @param y the row's middle, from the node's top at a zoom of 100 %
   */
  public record Port(String node, float y) {

    public Port {
      Objects.requireNonNull(node, "node");
      if (!Float.isFinite(y)) {
        throw new IllegalArgumentException("a port's height must be finite, was " + y);
      }
    }
  }

  /**
   * What an edge shows where it meets a node. The crow's foot set reads from the line towards the
   * node: a bar is one, a circle zero, a crow's foot many.
   */
  public enum Marker {
    /** None: the line ends at the node. */
    NONE,
    /** {@code ||}: exactly one. */
    ONE,
    /** {@code o|}: zero or one. */
    ZERO_OR_ONE,
    /** {@code |<}: one or many. */
    ONE_OR_MANY,
    /** {@code o<}: zero or many. */
    ZERO_OR_MANY,
    /** A plain arrowhead pointing at the node. */
    ARROW
  }

  /** How an edge stands out among the others. */
  public enum Emphasis {
    /** In the theme's strong border color. */
    NORMAL,
    /** In the accent color, wider ({@link EdgeStyle#highlightedWidth}) and on top of the others. */
    HIGHLIGHTED,
    /** In the strong border color mixed towards the canvas's ({@link EdgeStyle#dimming}). */
    DIMMED
  }

  /**
   * A line between the ports of two nodes, or of one node with itself. The canvas routes it with
   * right angles and rounded bends around the nodes in the way, gives each edge a lane of its own
   * beside the others, also where several meet at one port, and loops one from a node to itself on
   * the side where fewer other edges end when both sides are as good.
   *
   * @param key unique among the canvas's nodes and edges; the element of its first run has it
   * @param label what the edge is, for the tree dump and screen readers, and the text of its
   *     tooltip; null for neither
   * @param from where it starts
   * @param to where it ends
   * @param fromMarker what it shows at {@code from}
   * @param toMarker what it shows at {@code to}
   * @param emphasis how it stands out
   * @param description a muted line below the label in its tooltip, null for none
   */
  public record Edge(
      String key,
      String label,
      Port from,
      Port to,
      Marker fromMarker,
      Marker toMarker,
      Emphasis emphasis,
      String description) {

    public Edge {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(fromMarker, "fromMarker");
      Objects.requireNonNull(toMarker, "toMarker");
      Objects.requireNonNull(emphasis, "emphasis");
    }

    /** A {@link Emphasis#NORMAL} edge without a description. */
    public Edge(String key, String label, Port from, Port to, Marker fromMarker, Marker toMarker) {
      this(key, label, from, to, fromMarker, toMarker, Emphasis.NORMAL, null);
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
    // Since fit(), until a pan or zoom: fit again whenever the canvas has another size than the
    // one fitted to (-1 for none yet).
    private boolean fitting;
    private float fittedWidth;
    private float fittedHeight;
    // The node being dragged, which the canvas builds also outside it, or its drag would end.
    private String dragged;
    // Where the dragged node is, and the pointer's distance at the last step of a drag of a node
    // or of the canvas: each step adds its own movement at the zoom of that step, so that a zoom
    // during the drag moves nothing.
    private double draggedX;
    private double draggedY;
    private float draggedDx;
    private float draggedDy;
    // The wheel's turn with Control held that has not zoomed a step yet, in logical pixels.
    private double wheeled;
    // The routes of the last build with edges and what they were routed from, so that a pan, a zoom
    // or a hover does not route them again.
    private List<Node> routedNodes;
    private List<Edge> routedEdges;
    private EdgeStyle routedStyle;
    private List<Routing.Route> routes;

    /** A viewport at 100 % with the diagram's origin at the canvas's top left. */
    public Viewport() {
      // Every field starts at its default.
    }

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
      fitting = false;
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
      int next = Math.clamp((long) step + steps, 0, ZOOMS.size() - 1);
      if (next == step) {
        return; // at that end already: a fit goes on
      }
      step = next;
      float after = zoom();
      left += x / before - x / after;
      top += y / before - y / after;
      fitting = false;
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
     * Pans as little as it takes to show the whole node with a margin of {@link Theme.Spacing#xl},
     * as the keys that move between nodes need; a node larger than the canvas shows from its top
     * left. Before the canvas knows its size it does nothing.
     */
    public void reveal(Node node) {
      if (width <= 0 || height <= 0) {
        return;
      }
      float zoom = zoom();
      double margin = Theme.CURRENT.get().space().xl() / zoom;
      double x = revealed(left, width / zoom, node.x() - margin, node.x() + node.width() + margin);
      double y = revealed(top, height / zoom, node.y() - margin, node.y() + node.height() + margin);
      if (x != left || y != top) {
        panTo(x, y);
      }
    }

    /** Where a view of this extent starts that shows from {@code from} to {@code to}. */
    private static double revealed(double start, double extent, double from, double to) {
      if (to - from > extent || from < start) {
        return from;
      }
      return to > start + extent ? to - extent : start;
    }

    /**
     * Shows all nodes at the nearest zoom at which they fit into the canvas with a margin of
     * {@link Theme.Spacing#xl}, centered; at the furthest zoom where they fit at none. The canvas
     * does it in its next build that knows its size, so that an app can call it before the first,
     * and again whenever its size changes, until the viewport is panned or zoomed.
     */
    public void fit() {
      fitting = true;
      fittedWidth = -1;
    }

    /** The edges' routes among these nodes, routed again only when one of them changed. */
    List<Routing.Route> routes(List<Node> nodes, List<Edge> edges, EdgeStyle style) {
      if (!nodes.equals(routedNodes) || !edges.equals(routedEdges) || !style.equals(routedStyle)) {
        // Kept only once all of it worked, so that a failure leaves the last routes as they were.
        List<Node> copiedNodes = List.copyOf(nodes);
        List<Edge> copiedEdges = List.copyOf(edges);
        List<Routing.Route> routed = Routing.route(copiedNodes, copiedEdges, style);
        routedNodes = copiedNodes;
        routedEdges = copiedEdges;
        routedStyle = style;
        routes = routed;
      }
      return routes;
    }

    void fitIfDue(List<Node> nodes, float margin) {
      // Not while a node is dragged: its drag works at the zoom it began at.
      if (!fitting
          || dragged != null
          || width <= 0
          || height <= 0
          || width == fittedWidth && height == fittedHeight) {
        return;
      }
      fittedWidth = width;
      fittedHeight = height;
      if (nodes.isEmpty()) {
        return;
      }
      double[] bounds = bounds(nodes);
      double minX = bounds[0];
      double minY = bounds[1];
      double maxX = bounds[2];
      double maxY = bounds[3];
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

  /** The left, top, right and bottom edge of the nodes, of which there is at least one. */
  private static double[] bounds(List<Node> nodes) {
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
    return new double[] {minX, minY, maxX, maxY};
  }

  /**
   * The whole diagram as a PNG image, not only the part a canvas shows: cropped to the nodes with a
   * margin of {@link Theme.Spacing#xl}, at a zoom of 100 %, in the theme of {@link Theme#CURRENT}
   * where it is called. Its scale is 2, or 1 where that would give more than 16 megapixels. It is
   * drawn without a window, as {@link Headless#render} draws, and the app decides where the bytes
   * go. A handler runs outside the view's theme, so it gives the theme again:
   *
   * <pre>{@code
   * byte[] image = Theme.CURRENT.with(state.theme, () -> Canvas.png(
   *     state.tables, viewport -> canvas(viewport, state.tables, Tables::card, null)));
   * }</pre>
   *
   * @param nodes the nodes, whose extent is the diagram's; none give an image of the margin alone
   * @param view builds the canvas at the viewport it is given, as the app's view does: everything
   *     it draws is in the image. Its size is set to the image's.
   * @see #png(List, List, Function) for a canvas with edges, which can reach beyond the nodes
   * @throws IllegalArgumentException if the image would have more than 16 megapixels at a scale of
   *     1 as well, which is checked before anything is built
   */
  public static byte[] png(List<Node> nodes, Function<Viewport, El> view) {
    return png(nodes.isEmpty() ? new double[4] : bounds(nodes), view);
  }

  /**
   * As {@link #png(List, Function)}, for a canvas with these edges: cropped to the nodes and to the
   * edges among them with their markers, so that an edge that loops outside the nodes is whole. The
   * edges' sizes are those of {@link EdgeStyle#CURRENT} where it is called.
   */
  public static byte[] png(List<Node> nodes, List<Edge> edges, Function<Viewport, El> view) {
    return png(List.of(), nodes, edges, view);
  }

  /**
   * As {@link #png(List, List, Function)}, for a canvas with these areas under its nodes ({@link
   * #canvas(Viewport, List, List, List, NodeContent, Consumer)}), which the image takes in too.
   */
  public static byte[] png(
      List<Node> areas, List<Node> nodes, List<Edge> edges, Function<Viewport, El> view) {
    List<Node> all = areas.isEmpty() ? nodes : joined(areas, nodes);
    double[] bounds = all.isEmpty() ? new double[4] : bounds(all);
    EdgeStyle style = EdgeStyle.CURRENT.get();
    List<Routing.Route> routes = Routing.route(nodes, edges, style);
    for (int i = 0; i < routes.size(); i++) {
      Routing.Route route = routes.get(i);
      if (route != null) {
        // How far its markers and its line reach beyond its points at most.
        Edge edge = edges.get(i);
        double reach = Math.max(Edges.reach(edge.fromMarker()), Edges.reach(edge.toMarker()))
                * style.marker()
            + (edge.emphasis() == Emphasis.HIGHLIGHTED ? style.highlightedWidth() : style.width())
                / 2;
        for (Routing.Point point : route.points()) {
          bounds[0] = Math.min(bounds[0], point.x() - reach);
          bounds[1] = Math.min(bounds[1], point.y() - reach);
          bounds[2] = Math.max(bounds[2], point.x() + reach);
          bounds[3] = Math.max(bounds[3], point.y() + reach);
        }
      }
    }
    return png(bounds, view);
  }

  /** The diagram from the left, top, right and bottom edge given, with the margin, as a PNG. */
  private static byte[] png(double[] bounds, Function<Viewport, El> view) {
    float margin = Theme.CURRENT.get().space().xl();
    // At least a pixel, as a PNG has.
    float width = (float) Math.max(1, Math.ceil(bounds[2] - bounds[0] + 2 * margin));
    float height = (float) Math.max(1, Math.ceil(bounds[3] - bounds[1] + 2 * margin));
    int scale = pngScale(width, height);
    Viewport viewport = new Viewport();
    viewport.panTo(bounds[0] - margin, bounds[1] - margin);
    viewport.width = width;
    viewport.height = height;
    El canvas =
        view.apply(viewport).size(width, height).minSize(width, height).maxSize(width, height);
    return Png.encode(Headless.render(canvas, width, height, scale).pixels());
  }

  /** 2, or 1 where an image of this logical size would have too many pixels at 2. */
  static int pngScale(double width, double height) {
    for (int scale = 2; scale >= 1; scale--) {
      // In doubles, so that a size too large for an int or a float is too large here too.
      if (width * scale * height * scale <= PNG_PIXELS) {
        return scale;
      }
    }
    throw new IllegalArgumentException("A diagram of "
        + width
        + " by "
        + height
        + " pixels is too large for an image: it has more than 16 megapixels");
  }

  /**
   * @param viewport what part of the diagram it shows, from the app's state
   * @param nodes the nodes, each with a key of its own; later ones lie on top
   * @param content builds what each node shows at the zoom of the moment
   * @param onMove told a node moved and pinned while the user drags it, and the node as it was
   *     when the drag is cancelled, which may be unpinned. It is the node of the view the drag
   *     began in: an app that changes a node during a drag takes only the position and the pin
   *     from it. Null fixes the nodes.
   */
  public static El canvas(
      Viewport viewport, List<Node> nodes, NodeContent content, Consumer<Node> onMove) {
    return canvas(viewport, nodes, List.of(), content, onMove);
  }

  /**
   * A canvas with edges between its nodes, which lie under the nodes, routed again whenever the
   * nodes, the edges or {@link EdgeStyle#CURRENT} change, so that they follow a node that the user
   * drags; a pan, a zoom or a hover reuses the routes. An edge whose node
   * is not among the nodes is not drawn. Hovering an edge with a label shows its tooltip; pressing
   * or dragging it does what it does on empty space. The edges' sizes are those of {@link
   * EdgeStyle#CURRENT}.
   *
   * <p>In a tree dump the first run of each edge is {@code role=graphics-symbol} with its key and
   * label, and the value {@link DiagramTexts#highlighted} or {@link DiagramTexts#dimmed} when it
   * has that emphasis.
   *
   * @param edges the edges; later ones of the same emphasis lie on top
   * @see #canvas(Viewport, List, NodeContent, Consumer)
   */
  public static El canvas(
      Viewport viewport,
      List<Node> nodes,
      List<Edge> edges,
      NodeContent content,
      Consumer<Node> onMove) {
    return canvas(viewport, List.of(), nodes, edges, content, onMove);
  }

  /**
   * A canvas with areas under its edges and nodes, such as a frame around a group of nodes. An
   * area is built as a node is, by {@code content}, and lies where its node says, but the edges do
   * not go around it and the user does not move it: a drag on it pans. {@link Viewport#fit} and
   * {@link #png(List, List, List, Function)} take it in. In a tree dump it is {@code role=group}
   * with its key and label.
   *
   * @param areas the areas, each with a key of its own among the nodes and edges; later ones lie on
   *     top
   * @see #canvas(Viewport, List, List, NodeContent, Consumer)
   */
  public static El canvas(
      Viewport viewport,
      List<Node> areas,
      List<Node> nodes,
      List<Edge> edges,
      NodeContent content,
      Consumer<Node> onMove) {
    Theme theme = Theme.CURRENT.get();
    DiagramTexts texts = DiagramTexts.CURRENT.get();
    viewport.fitIfDue(
        areas.isEmpty() ? nodes : joined(areas, nodes), theme.space().xl());
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
        .onWheel((dx, dy, modifiers, x, y) -> wheel(viewport, dx, dy, modifiers, x, y))
        .onDrag(drag -> pan(viewport, left, top, drag));
    for (Node area : areas) {
      if (inside(area, left, top, right, bottom)) {
        canvas.child(placed(area, left, top, zoom).role("group").child(content.build(area, zoom)));
      }
    }
    if (!edges.isEmpty() && right > left && bottom > top) {
      EdgeStyle style = EdgeStyle.CURRENT.get();
      Edges.add(
          canvas,
          edges,
          viewport.routes(nodes, edges, style),
          new Edges.View(left, top, zoom, viewport.width, viewport.height),
          style);
    }
    for (Node node : nodes) {
      if (!inside(node, left, top, right, bottom) && !node.key().equals(viewport.dragged)) {
        continue;
      }
      El box =
          placed(node, left, top, zoom).role("graphics-object").child(content.build(node, zoom));
      if (node.pinned()) {
        box.value(texts.pinned());
      }
      // A fixed node takes the drag too, or it would pan the canvas.
      box.onDrag(onMove == null ? drag -> {} : drag -> move(viewport, node, onMove, drag));
      canvas.child(box);
    }
    return canvas;
  }

  private static boolean inside(Node node, double left, double top, double right, double bottom) {
    return right > left
        && bottom > top
        && node.x() < right
        && node.x() + node.width() > left
        && node.y() < bottom
        && node.y() + node.height() > top;
  }

  /** The node's element, with its key and label, where the canvas shows it at this zoom. */
  private static El placed(Node node, double left, double top, float zoom) {
    float x = Math.round((node.x() - left) * zoom);
    float y = Math.round((node.y() - top) * zoom);
    return el().key(node.key())
        .label(node.label())
        // Rounded at both edges, so that nodes that touch still touch at every zoom.
        .absolute(x, y)
        .size(
            Math.round((node.x() + node.width() - left) * zoom) - x,
            Math.round((node.y() + node.height() - top) * zoom) - y);
  }

  private static List<Node> joined(List<Node> first, List<Node> second) {
    List<Node> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  /**
   * Zooms by a step for each step of the wheel with Control held, around the pointer, and pans by
   * the wheel's distance without it. The steps of a precise wheel add up until they make one.
   */
  private static boolean wheel(
      Viewport viewport, double dx, double dy, int modifiers, double x, double y) {
    if ((modifiers & Key.CONTROL) == 0) {
      float zoom = viewport.zoom();
      viewport.panTo(viewport.left + dx / zoom, viewport.top + dy / zoom);
      return true;
    }
    viewport.wheeled += dy;
    // Towards the user, as the wheel scrolls down, zooms out.
    int steps = (int) (viewport.wheeled / WindowLoop.WHEEL_STEP);
    if (steps != 0) {
      viewport.wheeled -= steps * (double) WindowLoop.WHEEL_STEP;
      viewport.zoomBy(-steps, (float) x, (float) y);
    }
    return true;
  }

  /** Pans by each step's movement at the zoom of that step; a cancel goes back to the start. */
  private static void pan(Viewport viewport, double left, double top, Drag drag) {
    switch (drag.phase()) {
      case START -> {
        viewport.draggedDx = 0;
        viewport.draggedDy = 0;
      }
      case CANCEL -> {
        viewport.panTo(left, top);
        return;
      }
      default -> {}
    }
    float zoom = viewport.zoom();
    if (drag.dx() != viewport.draggedDx || drag.dy() != viewport.draggedDy) {
      // Only a movement pans: a release in place leaves a fit made during the drag going on.
      viewport.panTo(
          viewport.left - (drag.dx() - viewport.draggedDx) / zoom,
          viewport.top - (drag.dy() - viewport.draggedDy) / zoom);
    }
    viewport.draggedDx = drag.dx();
    viewport.draggedDy = drag.dy();
  }

  private static void move(Viewport viewport, Node node, Consumer<Node> onMove, Drag drag) {
    switch (drag.phase()) {
      case START -> {
        viewport.dragged = node.key();
        viewport.draggedX = node.x();
        viewport.draggedY = node.y();
        viewport.draggedDx = 0;
        viewport.draggedDy = 0;
      }
      case CANCEL -> {
        viewport.dragged = null;
        onMove.accept(node);
        return;
      }
      case END -> viewport.dragged = null;
      default -> {}
    }
    // The zoom of now, not of the view the press came in: a fit or a key may have changed it.
    float zoom = viewport.zoom();
    viewport.draggedX += (drag.dx() - viewport.draggedDx) / zoom;
    viewport.draggedY += (drag.dy() - viewport.draggedDy) / zoom;
    viewport.draggedDx = drag.dx();
    viewport.draggedDy = drag.dy();
    onMove.accept(node.movedTo(viewport.draggedX, viewport.draggedY));
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
