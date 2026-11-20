package com.rethinkj.ui.diagram;

import com.rethinkj.ui.diagram.Canvas.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Arranges the nodes of a diagram in layers, so that the edges between them run one way: a
 * layered (Sugiyama) layout. It is a plain function from nodes with their sizes, edges and pins to
 * nodes at new places, which the app keeps as it keeps a drag's:
 *
 * <pre>{@code
 * state.tables = LayeredLayout.arrange(state.tables, state.references);
 * state.viewport.fit();
 * }</pre>
 *
 * <p>An {@link Edge} goes from a node to one it refers to, such as a table's foreign key to the
 * table it references, and the one referred to comes first: left of it, or above it {@link
 * Direction#TOP_TO_BOTTOM top to bottom}. The layout breaks cycles and puts each node in the layer
 * after the longest chain of nodes it refers to; a node that refers to none but that others refer
 * to moves up to the layer just before the first of those. It orders the nodes of each layer so
 * that few edges cross, and places them so that most edges run straight. Nodes that no edge
 * connects with the others are packed in rows beside them; so are the {@link Options#cluster
 * clusters}, each laid out on its own.
 *
 * <p>A {@link Node#pinned pinned} node stays where it is, and the others are placed around it: each
 * cluster is moved so that its pinned nodes lie where it would put them, and a node that would
 * overlap one moves past it along its layer, down from left to right and right from top to bottom.
 * Unpinned nodes keep clear of every other node, while pinned ones stay put even on top of each
 * other, so that where pins bring clusters together, the clusters' areas can overlap. A layout
 * without pins starts at the diagram's origin. The places of unpinned nodes going in do not matter,
 * so that arranging twice gives the same as once, and the same input always gives the same output.
 */
public final class LayeredLayout {

  // The width to height that the rows of packed parts and clusters aim for.
  private static final double ASPECT = 1.6;
  // How many sweeps order the layers and how many rounds place them: fewer for a part with many
  // vertices, so that the work stays bounded, but never fewer than the least.
  private static final int ORDER_SWEEPS = 12;
  private static final int LEAST_SWEEPS = 4;
  private static final int PLACE_ROUNDS = 8;
  private static final int LEAST_ROUNDS = 2;
  private static final int VISITS = 16_000;
  // The longest layer that sweeps sort by insertion.
  private static final int SHORT_LAYER = 64;
  // How hard a part of a long edge holds its line against a node, and how little a node without
  // neighbors on that side holds its place.
  private static final double BEND_WEIGHT = 2;
  private static final double FREE_WEIGHT = 1e-3;
  private static final double EPSILON = 1e-6;
  // A placement round that moves no vertex further than this ends the rounds.
  private static final double SETTLED = 0.01;

  private LayeredLayout() {}

  /** Which way the layers follow each other. */
  public enum Direction {
    /** Layers are columns, the nodes referred to on the left: for tables. */
    LEFT_TO_RIGHT,
    /** Layers are rows, the nodes referred to on top: for trees. */
    TOP_TO_BOTTOM
  }

  /**
   * An edge from a node to one it refers to, which comes first. Edges to a key that is not among
   * the nodes, such as one an app filters away, and edges from a node to itself are left out.
   *
   * @param from the key of the node that refers
   * @param to the key of the node it refers to
   */
  public record Edge(String from, String to) {

    public Edge {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }
  }

  /** Which way, how far apart and in what clusters; {@link #options()} is left to right. */
  public static final class Options {
    private Direction direction = Direction.LEFT_TO_RIGHT;
    private float layerGap = 80;
    private float nodeGap = 32;
    private float clusterGap = 96;
    private Function<Node, String> cluster = node -> null;

    private Options() {}

    /** Which way the layers follow each other: {@link Direction#LEFT_TO_RIGHT} by default. */
    public Options direction(Direction direction) {
      this.direction = Objects.requireNonNull(direction, "direction");
      return this;
    }

    /**
     * The room between nodes, in diagram coordinates: 80 between layers, 32 between the nodes of a
     * layer and between parts that no edge connects, and 96 between clusters.
     */
    public Options gaps(float layer, float node, float cluster) {
      if (!(layer >= 0 && layer < Float.POSITIVE_INFINITY)
          || !(node >= 0 && node < Float.POSITIVE_INFINITY)
          || !(cluster >= 0 && cluster < Float.POSITIVE_INFINITY)) {
        throw new IllegalArgumentException(
            "a gap must be 0 or more and finite, was " + layer + ", " + node + ", " + cluster);
      }
      this.layerGap = layer;
      this.nodeGap = node;
      this.clusterGap = cluster;
      return this;
    }

    /**
     * The cluster of each node, such as a table's schema: each cluster is laid out on its own, only
     * with the edges inside it, and the clusters are packed in rows in the order they first occur
     * among the nodes. Null, from all nodes by default, is a cluster too. It must not depend on a
     * node's place, or arranging again moves nodes from one cluster to another.
     */
    public Options cluster(Function<Node, String> cluster) {
      this.cluster = Objects.requireNonNull(cluster, "cluster");
      return this;
    }
  }

  public static Options options() {
    return new Options();
  }

  /** As {@link #arrange(List, List, Options)}, left to right in one cluster. */
  public static List<Node> arrange(List<Node> nodes, List<Edge> edges) {
    return arrange(nodes, edges, options());
  }

  /**
   * The nodes, in the same order, at the places the layout gives them; pinned ones as they are.
   *
   * @param nodes each with a key of its own
   * @param edges from a node to one it refers to
   * @throws IllegalArgumentException if two nodes have the same key
   */
  public static List<Node> arrange(List<Node> nodes, List<Edge> edges, Options options) {
    Objects.requireNonNull(options, "options");
    Diagram diagram = new Diagram(List.copyOf(nodes), List.copyOf(edges), options);
    return diagram.arrange();
  }

  /** A box in diagram coordinates. */
  private record Box(double x, double y, double width, double height) {

    Box at(double x, double y) {
      return new Box(x, y, width, height);
    }
  }

  /** The nodes and edges of one call, and the boxes it gives them. */
  private static final class Diagram {
    final List<Node> nodes;
    final Options options;
    final boolean across;
    final Map<String, Integer> index = new HashMap<>();
    // The cluster of each node, and each cluster's nodes in the order they come.
    final int[] clusterOf;
    final List<List<Integer>> clusters = new ArrayList<>();
    // Edges inside a cluster, from the node referred to to the one referring.
    final List<int[]> arcs = new ArrayList<>();
    final Box[] boxes;

    Diagram(List<Node> nodes, List<Edge> edges, Options options) {
      this.nodes = nodes;
      this.options = options;
      this.across = options.direction == Direction.LEFT_TO_RIGHT;
      this.clusterOf = new int[nodes.size()];
      this.boxes = new Box[nodes.size()];
      Map<String, Integer> clusterIndex = new LinkedHashMap<>();
      for (int i = 0; i < nodes.size(); i++) {
        Node node = nodes.get(i);
        if (index.put(node.key(), i) != null) {
          throw new IllegalArgumentException("two nodes have the key " + node.key());
        }
        int cluster = clusterIndex.computeIfAbsent(options.cluster.apply(node), name -> {
          clusters.add(new ArrayList<>());
          return clusters.size() - 1;
        });
        clusterOf[i] = cluster;
        clusters.get(cluster).add(i);
      }
      for (Edge edge : edges) {
        Integer from = index.get(edge.from());
        Integer to = index.get(edge.to());
        if (from != null && to != null && !from.equals(to) && clusterOf[from] == clusterOf[to]) {
          arcs.add(new int[] {to, from});
        }
      }
    }

    List<Node> arrange() {
      List<Box> packed = new ArrayList<>();
      List<Box[]> laid = new ArrayList<>();
      for (List<Integer> cluster : clusters) {
        Box[] parts = layOutCluster(cluster);
        laid.add(parts);
        packed.add(bounds(parts));
      }
      List<Box> places = pack(packed, options.clusterGap);
      for (int c = 0; c < clusters.size(); c++) {
        List<Integer> cluster = clusters.get(c);
        Box place = places.get(c);
        for (int k = 0; k < cluster.size(); k++) {
          Box box = laid.get(c)[k];
          boxes[cluster.get(k)] = box.at(box.x() + place.x(), box.y() + place.y());
        }
      }
      keepPins();
      List<Node> arranged = new ArrayList<>(nodes.size());
      for (int i = 0; i < nodes.size(); i++) {
        Node node = nodes.get(i);
        arranged.add(
            node.pinned()
                ? node
                : new Node(
                    node.key(),
                    node.label(),
                    boxes[i].x(),
                    boxes[i].y(),
                    node.width(),
                    node.height(),
                    false));
      }
      return arranged;
    }

    /** The cluster's nodes, in its order, laid out from the origin: each part, then packed. */
    private Box[] layOutCluster(List<Integer> cluster) {
      List<List<Integer>> parts = connectedParts(cluster);
      List<Box[]> laid = new ArrayList<>();
      List<Box> sizes = new ArrayList<>();
      for (List<Integer> part : parts) {
        Box[] boxesOfPart = new Part(this, part).layOut();
        laid.add(boxesOfPart);
        sizes.add(bounds(boxesOfPart));
      }
      List<Box> places = pack(sizes, options.nodeGap);
      Map<Integer, Box> byNode = new HashMap<>();
      for (int p = 0; p < parts.size(); p++) {
        for (int k = 0; k < parts.get(p).size(); k++) {
          Box box = laid.get(p)[k];
          byNode.put(
              parts.get(p).get(k),
              box.at(box.x() + places.get(p).x(), box.y() + places.get(p).y()));
        }
      }
      Box[] result = new Box[cluster.size()];
      for (int k = 0; k < cluster.size(); k++) {
        result[k] = byNode.get(cluster.get(k));
      }
      return result;
    }

    /**
     * The parts of a cluster that edges connect, each in the order its nodes come: the larger
     * first, those of a size in the order of their first node.
     */
    private List<List<Integer>> connectedParts(List<Integer> cluster) {
      int[] root = new int[nodes.size()];
      for (int node : cluster) {
        root[node] = node;
      }
      for (int[] arc : arcs) {
        if (clusterOf[arc[0]] == clusterOf[cluster.get(0)]) {
          int a = find(root, arc[0]);
          int b = find(root, arc[1]);
          root[Math.max(a, b)] = Math.min(a, b);
        }
      }
      Map<Integer, List<Integer>> parts = new LinkedHashMap<>();
      for (int node : cluster) {
        parts.computeIfAbsent(find(root, node), r -> new ArrayList<>()).add(node);
      }
      List<List<Integer>> ordered = new ArrayList<>(parts.values());
      ordered.sort(Comparator.comparingInt((List<Integer> part) -> part.size()).reversed());
      return ordered;
    }

    private static int find(int[] root, int node) {
      int at = node;
      while (root[at] != at) {
        root[at] = root[root[at]];
        at = root[at];
      }
      return at;
    }

    /**
     * Moves each cluster so that its pinned nodes lie where it put them, by the median of their
     * offsets, and a cluster without any by the median of all; puts the pinned nodes back where
     * they are, and moves each unpinned node that overlaps one, or one placed before it, past it.
     */
    private void keepPins() {
      List<Integer> pinned = new ArrayList<>();
      for (int i = 0; i < nodes.size(); i++) {
        if (nodes.get(i).pinned()) {
          pinned.add(i);
        }
      }
      if (pinned.isEmpty()) {
        return;
      }
      double[] all = shift(pinned);
      double[][] shifts = new double[clusters.size()][];
      for (int c = 0; c < clusters.size(); c++) {
        List<Integer> own = new ArrayList<>();
        for (int i : clusters.get(c)) {
          if (nodes.get(i).pinned()) {
            own.add(i);
          }
        }
        shifts[c] = own.isEmpty() ? all : shift(own);
      }
      List<Box> obstacles = new ArrayList<>();
      List<Integer> free = new ArrayList<>();
      for (int i = 0; i < nodes.size(); i++) {
        Node node = nodes.get(i);
        if (node.pinned()) {
          boxes[i] = new Box(node.x(), node.y(), node.width(), node.height());
          obstacles.add(boxes[i]);
        } else {
          double[] shift = shifts[clusterOf[i]];
          boxes[i] = boxes[i].at(boxes[i].x() + shift[0], boxes[i].y() + shift[1]);
          free.add(i);
        }
      }
      free.sort(Comparator.comparingDouble((Integer i) -> cross(boxes[i]))
          .thenComparingDouble(i -> along(boxes[i]))
          .thenComparingInt(i -> i));
      for (int i : free) {
        boxes[i] = clear(boxes[i], obstacles);
        obstacles.add(boxes[i]);
      }
    }

    /** The median offset of these pinned nodes from where the layout put them. */
    private double[] shift(List<Integer> pinned) {
      double[] dx = new double[pinned.size()];
      double[] dy = new double[pinned.size()];
      for (int k = 0; k < pinned.size(); k++) {
        int i = pinned.get(k);
        dx[k] = nodes.get(i).x() - boxes[i].x();
        dy[k] = nodes.get(i).y() - boxes[i].y();
      }
      return new double[] {median(dx), median(dy)};
    }

    /** The box moved along the cross axis until no obstacle is nearer than the smallest gap. */
    private Box clear(Box box, List<Box> obstacles) {
      // A little less than the smallest gap, for the rounding of the layout, but never an overlap.
      double margin = Math.max(
          0, Math.min(options.nodeGap, Math.min(options.layerGap, options.clusterGap)) - EPSILON);
      Box at = box;
      boolean moved = true;
      while (moved) {
        moved = false;
        for (Box obstacle : obstacles) {
          if (near(at, obstacle, margin)) {
            double to = cross(obstacle) + crossSize(obstacle) + options.nodeGap;
            at = across ? at.at(at.x(), to) : at.at(to, at.y());
            moved = true;
          }
        }
      }
      return at;
    }

    private static boolean near(Box a, Box b, double margin) {
      return a.x() < b.x() + b.width() + margin
          && b.x() < a.x() + a.width() + margin
          && a.y() < b.y() + b.height() + margin
          && b.y() < a.y() + a.height() + margin;
    }

    /** Where a box lies along the layers, the axis they follow each other on. */
    private double along(Box box) {
      return across ? box.x() : box.y();
    }

    private double cross(Box box) {
      return across ? box.y() : box.x();
    }

    private double crossSize(Box box) {
      return across ? box.height() : box.width();
    }

    double mainSize(int node) {
      return across ? nodes.get(node).width() : nodes.get(node).height();
    }

    double crossSize(int node) {
      return across ? nodes.get(node).height() : nodes.get(node).width();
    }

    Box box(double main, double cross, int node) {
      Node n = nodes.get(node);
      return across
          ? new Box(main, cross, n.width(), n.height())
          : new Box(cross, main, n.width(), n.height());
    }
  }

  /** One connected part of a cluster, laid out in layers from the origin. */
  private static final class Part {
    final Diagram diagram;
    final List<Integer> members;
    final int size;
    // Per vertex, the part's nodes first and then the bends of edges that span layers: its layer,
    // its neighbors in the layers before and after it, and its size along the layer.
    final List<Integer> layering = new ArrayList<>();
    final List<List<Integer>> joinedBefore = new ArrayList<>();
    final List<List<Integer>> joinedAfter = new ArrayList<>();
    // The shared bends of the long arcs from a node, by layer after it, and to a node, by layer
    // before it.
    final Map<Integer, List<Integer>> bendsAfter = new HashMap<>();
    final Map<Integer, List<Integer>> bendsBefore = new HashMap<>();
    int[] layerOf;
    int[][] before;
    int[][] beforeWeight;
    int[][] after;
    int[][] afterWeight;
    double[] extent;
    int[][] layers;
    int[] position;
    double[] center;

    Part(Diagram diagram, List<Integer> members) {
      this.diagram = diagram;
      this.members = members;
      this.size = members.size();
    }

    /** The members' boxes, in their order. */
    Box[] layOut() {
      List<int[]> arcs = acyclic(localArcs());
      int[] layer = layers(arcs);
      for (int v = 0; v < size; v++) {
        addVertex(layer[v]);
      }
      connectAll(arcs);
      freeze();
      order();
      placeAcross();
      return boxes();
    }

    private List<int[]> localArcs() {
      Map<Integer, Integer> local = new HashMap<>();
      for (int k = 0; k < size; k++) {
        local.put(members.get(k), k);
      }
      List<int[]> arcs = new ArrayList<>();
      for (int[] arc : diagram.arcs) {
        Integer from = local.get(arc[0]);
        Integer to = local.get(arc[1]);
        if (from != null && to != null) {
          arcs.add(new int[] {from, to});
        }
      }
      return arcs;
    }

    /** The arcs with those turned around that close a cycle in a depth-first search. */
    private List<int[]> acyclic(List<int[]> arcs) {
      List<List<Integer>> out = new ArrayList<>();
      for (int v = 0; v < size; v++) {
        out.add(new ArrayList<>());
      }
      for (int a = 0; a < arcs.size(); a++) {
        out.get(arcs.get(a)[0]).add(a);
      }
      boolean[] turned = new boolean[arcs.size()];
      int[] state = new int[size]; // 0 unseen, 1 on the path, 2 done
      int[] next = new int[size];
      ArrayDeque<Integer> path = new ArrayDeque<>();
      for (int start = 0; start < size; start++) {
        if (state[start] != 0) {
          continue;
        }
        state[start] = 1;
        path.push(start);
        while (!path.isEmpty()) {
          int v = path.peek();
          if (next[v] == out.get(v).size()) {
            state[v] = 2;
            path.pop();
            continue;
          }
          int a = out.get(v).get(next[v]++);
          int w = arcs.get(a)[1];
          if (state[w] == 1) {
            turned[a] = true;
          } else if (state[w] == 0) {
            state[w] = 1;
            path.push(w);
          }
        }
      }
      List<int[]> result = new ArrayList<>(arcs.size());
      for (int a = 0; a < arcs.size(); a++) {
        int[] arc = arcs.get(a);
        result.add(turned[a] ? new int[] {arc[1], arc[0]} : arc);
      }
      return result;
    }

    /**
     * Each node's layer after the longest chain before it; a node that only others refer to moves
     * up to the layer before the first of those.
     */
    private int[] layers(List<int[]> arcs) {
      int[] layer = new int[size];
      int[] waiting = new int[size];
      List<List<Integer>> out = new ArrayList<>();
      for (int v = 0; v < size; v++) {
        out.add(new ArrayList<>());
      }
      for (int[] arc : arcs) {
        out.get(arc[0]).add(arc[1]);
        waiting[arc[1]]++;
      }
      ArrayDeque<Integer> ready = new ArrayDeque<>();
      for (int v = 0; v < size; v++) {
        if (waiting[v] == 0) {
          ready.add(v);
        }
      }
      while (!ready.isEmpty()) {
        int v = ready.poll();
        for (int w : out.get(v)) {
          layer[w] = Math.max(layer[w], layer[v] + 1);
          if (--waiting[w] == 0) {
            ready.add(w);
          }
        }
      }
      boolean[] referring = new boolean[size];
      for (int[] arc : arcs) {
        referring[arc[1]] = true;
      }
      for (int v = 0; v < size; v++) {
        if (!referring[v] && !out.get(v).isEmpty()) {
          int first = Integer.MAX_VALUE;
          for (int w : out.get(v)) {
            first = Math.min(first, layer[w]);
          }
          layer[v] = first - 1;
        }
      }
      return layer;
    }

    private int addVertex(int layer) {
      layering.add(layer);
      joinedBefore.add(new ArrayList<>());
      joinedAfter.add(new ArrayList<>());
      return layering.size() - 1;
    }

    /**
     * Connects each arc's ends through a bend in each layer between them. The long arcs of a node
     * share their bends, on the end with more of them, so that a node that many others refer to
     * from far away, or that refers to many, costs a bend per layer, not one per edge and layer.
     */
    private void connectAll(List<int[]> arcs) {
      int[] longOut = new int[size];
      int[] longIn = new int[size];
      for (int[] arc : arcs) {
        if (layering.get(arc[1]) - layering.get(arc[0]) > 1) {
          longOut[arc[0]]++;
          longIn[arc[1]]++;
        }
      }
      for (int[] arc : arcs) {
        if (longOut[arc[0]] >= longIn[arc[1]]) {
          connectDown(arc[0], arc[1]);
        } else {
          connectUp(arc[0], arc[1]);
        }
      }
    }

    private void connectDown(int from, int to) {
      List<Integer> shared = bendsAfter.computeIfAbsent(from, vertex -> new ArrayList<>());
      int at = from;
      int span = layering.get(to) - layering.get(from) - 1;
      for (int k = 0; k < span; k++) {
        if (k == shared.size()) {
          shared.add(addVertex(layering.get(from) + 1 + k));
        }
        link(at, shared.get(k));
        at = shared.get(k);
      }
      link(at, to);
    }

    private void connectUp(int from, int to) {
      List<Integer> shared = bendsBefore.computeIfAbsent(to, vertex -> new ArrayList<>());
      int at = to;
      int span = layering.get(to) - layering.get(from) - 1;
      for (int k = 0; k < span; k++) {
        if (k == shared.size()) {
          shared.add(addVertex(layering.get(to) - 1 - k));
        }
        link(shared.get(k), at);
        at = shared.get(k);
      }
      link(from, at);
    }

    /** Links two vertices once more: once for each edge that runs between them. */
    private void link(int from, int to) {
      joinedAfter.get(from).add(to);
      joinedBefore.get(to).add(from);
    }

    /** The vertices in arrays, for the sweeps, each neighbor once with its number of edges. */
    private void freeze() {
      int count = layering.size();
      layerOf = new int[count];
      before = new int[count][];
      beforeWeight = new int[count][];
      after = new int[count][];
      afterWeight = new int[count][];
      extent = new double[count];
      for (int v = 0; v < count; v++) {
        layerOf[v] = layering.get(v);
        int[][] joined = counted(joinedBefore.get(v));
        before[v] = joined[0];
        beforeWeight[v] = joined[1];
        joined = counted(joinedAfter.get(v));
        after[v] = joined[0];
        afterWeight[v] = joined[1];
        extent[v] = isBend(v) ? 0 : diagram.crossSize(members.get(v));
      }
    }

    /** The distinct vertices, in order, and how often each comes. */
    private static int[][] counted(List<Integer> joined) {
      int[] sorted = new int[joined.size()];
      for (int k = 0; k < sorted.length; k++) {
        sorted[k] = joined.get(k);
      }
      Arrays.sort(sorted);
      int distinct = 0;
      int[] counts = new int[sorted.length];
      for (int k = 0; k < sorted.length; k++) {
        if (k == 0 || sorted[k] != sorted[k - 1]) {
          sorted[distinct++] = sorted[k];
        }
        counts[distinct - 1]++;
      }
      return new int[][] {Arrays.copyOf(sorted, distinct), Arrays.copyOf(counts, distinct)};
    }

    private boolean isBend(int v) {
      return v >= size;
    }

    /**
     * Orders each layer by the barycenters of the neighbors, sweeping down and up, and keeps the
     * order with the fewest crossings.
     */
    private void order() {
      int count = 0;
      for (int layer : layerOf) {
        count = Math.max(count, layer + 1);
      }
      int[] filled = new int[count];
      for (int layer : layerOf) {
        filled[layer]++;
      }
      layers = new int[count][];
      for (int l = 0; l < count; l++) {
        layers[l] = new int[filled[l]];
        filled[l] = 0;
      }
      for (int v = 0; v < layerOf.length; v++) {
        layers[layerOf[v]][filled[layerOf[v]]++] = v;
      }
      position = new int[layerOf.length];
      number();
      int[][] best = copy(layers);
      long fewest = crossings();
      // Until no crossings are left: a sweep that brings no fewer can still lead to fewer later.
      int sweeps = Math.clamp(2L * VISITS / layerOf.length, LEAST_SWEEPS, ORDER_SWEEPS);
      for (int sweep = 0; sweep < sweeps && fewest > 0; sweep++) {
        boolean down = sweep % 2 == 0;
        for (int k = 1; k < count; k++) {
          int l = down ? k : count - 1 - k;
          sortByBarycenter(layers[l], down ? before : after, down ? beforeWeight : afterWeight);
        }
        long crossings = crossings();
        if (crossings < fewest) {
          fewest = crossings;
          best = copy(layers);
        }
      }
      layers = best;
      number();
    }

    private void number() {
      for (int[] layer : layers) {
        for (int k = 0; k < layer.length; k++) {
          position[layer[k]] = k;
        }
      }
    }

    private static int[][] copy(int[][] layers) {
      int[][] copy = new int[layers.length][];
      for (int l = 0; l < layers.length; l++) {
        copy[l] = layers[l].clone();
      }
      return copy;
    }

    /**
     * Sorts a layer by the mean place of the neighbors, each counted once for each edge to it; one
     * without any keeps its place.
     */
    private void sortByBarycenter(int[] layer, int[][] neighbors, int[][] weights) {
      double[] key = new double[layer.length];
      int[] sorted = new int[layer.length];
      for (int k = 0; k < layer.length; k++) {
        sorted[k] = k;
        int[] of = neighbors[layer[k]];
        int[] weight = weights[layer[k]];
        double sum = 0;
        int edges = 0;
        for (int n = 0; n < of.length; n++) {
          sum += (double) position[of[n]] * weight[n];
          edges += weight[n];
        }
        key[k] = edges == 0 ? k : sum / edges;
      }
      sortStable(sorted, key);
      int[] was = layer.clone();
      for (int k = 0; k < layer.length; k++) {
        layer[k] = was[sorted[k]];
        position[layer[k]] = k;
      }
    }

    /**
     * Sorts the indices by their keys, keeping the order of equal ones: by insertion for a short
     * layer, which comes nearly sorted from the sweep before, and by merging for a long one.
     */
    private static void sortStable(int[] sorted, double[] key) {
      if (sorted.length > SHORT_LAYER) {
        Integer[] boxed = new Integer[sorted.length];
        for (int k = 0; k < sorted.length; k++) {
          boxed[k] = sorted[k];
        }
        Arrays.sort(boxed, Comparator.comparingDouble(k -> key[k]));
        for (int k = 0; k < sorted.length; k++) {
          sorted[k] = boxed[k];
        }
        return;
      }
      for (int k = 1; k < sorted.length; k++) {
        int moving = sorted[k];
        int at = k;
        while (at > 0 && key[sorted[at - 1]] > key[moving]) {
          sorted[at] = sorted[at - 1];
          at--;
        }
        sorted[at] = moving;
      }
    }

    /**
     * The crossings of edges between each two layers, a segment that several edges share counted
     * once for each: with the segments in the order of their upper ends, then their lower ends,
     * the pairs whose lower ends come the other way round, counted in a Fenwick tree over the
     * lower layer.
     */
    private long crossings() {
      long total = 0;
      for (int l = 0; l + 1 < layers.length; l++) {
        long[] tree = new long[layers[l + 1].length + 1];
        long seen = 0;
        for (int v : layers[l]) {
          int[] to = after[v];
          if (to.length == 1) {
            total += afterWeight[v][0] * (seen - count(tree, position[to[0]]));
            add(tree, position[to[0]], afterWeight[v][0]);
            seen += afterWeight[v][0];
            continue;
          }
          // Each segment's lower end in the high half, its number of edges in the low half.
          long[] ends = new long[to.length];
          for (int k = 0; k < ends.length; k++) {
            ends[k] = ((long) position[to[k]] << 32) | afterWeight[v][k];
          }
          Arrays.sort(ends);
          for (long segment : ends) {
            int end = (int) (segment >>> 32);
            long edges = segment & 0xFFFF_FFFFL;
            total += edges * (seen - count(tree, end));
            add(tree, end, edges);
            seen += edges;
          }
        }
      }
      return total;
    }

    /** The edges counted so far that end at this place or before it. */
    private static long count(long[] tree, int place) {
      long sum = 0;
      for (int i = place + 1; i > 0; i -= i & -i) {
        sum += tree[i];
      }
      return sum;
    }

    private static void add(long[] tree, int place, long edges) {
      for (int i = place + 1; i < tree.length; i += i & -i) {
        tree[i] += edges;
      }
    }

    /**
     * Places the vertices of each layer along the layer near the median of their neighbors, in
     * rounds down and up the layers, so that most edges run straight.
     */
    private void placeAcross() {
      center = new double[layerOf.length];
      for (int[] layer : layers) {
        double at = 0;
        for (int k = 0; k < layer.length; k++) {
          if (k > 0) {
            at += separation(layer[k - 1], layer[k]);
          }
          center[layer[k]] = at;
        }
      }
      // Until a round moves nothing by more than a hundredth of a pixel.
      double moved = Double.POSITIVE_INFINITY;
      int rounds = Math.clamp(VISITS / layerOf.length, LEAST_ROUNDS, PLACE_ROUNDS);
      for (int round = 0; round < rounds && moved > SETTLED; round++) {
        moved = 0;
        for (int l = 1; l < layers.length; l++) {
          moved = Math.max(moved, placeLayer(layers[l], before));
        }
        for (int l = layers.length - 2; l >= 0; l--) {
          moved = Math.max(moved, placeLayer(layers[l], after));
        }
      }
    }

    private double separation(int a, int b) {
      double gap = isBend(a) || isBend(b) ? diagram.options.nodeGap / 2 : diagram.options.nodeGap;
      return (extent[a] + extent[b]) / 2 + gap;
    }

    /**
     * The centers nearest to the neighbors' medians, weighted, that keep the layer's order and
     * separations: pool adjacent violators over the centers less their offsets.
     *
     * @return how far the vertex that moved furthest moved
     */
    private double placeLayer(int[] layer, int[][] neighbors) {
      int count = layer.length;
      double[] offset = new double[count];
      double[] target = new double[count];
      double[] weight = new double[count];
      for (int k = 0; k < count; k++) {
        int v = layer[k];
        if (k > 0) {
          offset[k] = offset[k - 1] + separation(layer[k - 1], v);
        }
        int[] of = neighbors[v];
        target[k] = (of.length == 0 ? center[v] : median(of)) - offset[k];
        weight[k] = weight(v, of);
      }
      int[] start = new int[count];
      double[] sumWeight = new double[count];
      double[] sumTarget = new double[count];
      int blocks = 0;
      for (int k = 0; k < count; k++) {
        start[blocks] = k;
        sumWeight[blocks] = weight[k];
        sumTarget[blocks] = weight[k] * target[k];
        blocks++;
        while (blocks > 1
            && sumTarget[blocks - 2] / sumWeight[blocks - 2]
                > sumTarget[blocks - 1] / sumWeight[blocks - 1]) {
          sumWeight[blocks - 2] += sumWeight[blocks - 1];
          sumTarget[blocks - 2] += sumTarget[blocks - 1];
          blocks--;
        }
      }
      double moved = 0;
      for (int b = 0; b < blocks; b++) {
        int end = b + 1 < blocks ? start[b + 1] : count;
        double at = sumTarget[b] / sumWeight[b];
        for (int k = start[b]; k < end; k++) {
          moved = Math.max(moved, Math.abs(at + offset[k] - center[layer[k]]));
          center[layer[k]] = at + offset[k];
        }
      }
      return moved;
    }

    private double weight(int v, int[] neighbors) {
      if (neighbors.length == 0) {
        return FREE_WEIGHT;
      }
      return isBend(v) ? BEND_WEIGHT : 1;
    }

    private double median(int[] vertices) {
      if (vertices.length == 1) {
        return center[vertices[0]];
      }
      double[] centers = new double[vertices.length];
      for (int k = 0; k < centers.length; k++) {
        centers[k] = center[vertices[k]];
      }
      Arrays.sort(centers);
      int middle = centers.length / 2;
      return centers.length % 2 == 1
          ? centers[middle]
          : (centers[middle - 1] + centers[middle]) / 2;
    }

    /** The members' boxes: each layer a band as deep as its deepest node, centered in it. */
    private Box[] boxes() {
      double[] depth = new double[layers.length];
      double low = Double.POSITIVE_INFINITY;
      for (int v = 0; v < size; v++) {
        int l = layerOf[v];
        depth[l] = Math.max(depth[l], diagram.mainSize(members.get(v)));
        low = Math.min(low, center[v] - extent[v] / 2);
      }
      double[] band = new double[layers.length];
      for (int l = 1; l < layers.length; l++) {
        band[l] = band[l - 1] + depth[l - 1] + diagram.options.layerGap;
      }
      Box[] result = new Box[size];
      for (int v = 0; v < size; v++) {
        int node = members.get(v);
        int l = layerOf[v];
        double main = band[l] + (depth[l] - diagram.mainSize(node)) / 2;
        result[v] = diagram.box(main, center[v] - extent[v] / 2 - low, node);
      }
      return result;
    }
  }

  /** The smallest box around these, from the origin; they start at the origin. */
  private static Box bounds(Box[] boxes) {
    double right = 0;
    double bottom = 0;
    for (Box box : boxes) {
      right = Math.max(right, box.x() + box.width());
      bottom = Math.max(bottom, box.y() + box.height());
    }
    return new Box(0, 0, right, bottom);
  }

  /** Places boxes in rows, left to right, wrapping so that the whole is about {@link #ASPECT}. */
  private static List<Box> pack(List<Box> sizes, double gap) {
    double area = 0;
    double widest = 0;
    for (Box size : sizes) {
      area += (size.width() + gap) * (size.height() + gap);
      widest = Math.max(widest, size.width());
    }
    double limit = Math.max(widest, Math.sqrt(area * ASPECT));
    List<Box> places = new ArrayList<>(sizes.size());
    double x = 0;
    double y = 0;
    double row = 0;
    for (Box size : sizes) {
      if (x > 0 && x + size.width() > limit) {
        x = 0;
        y += row + gap;
        row = 0;
      }
      places.add(size.at(x, y));
      x += size.width() + gap;
      row = Math.max(row, size.height());
    }
    return places;
  }

  private static double median(double[] values) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[(sorted.length - 1) / 2];
  }
}
