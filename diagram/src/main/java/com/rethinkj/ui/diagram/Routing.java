package com.rethinkj.ui.diagram;

import com.rethinkj.ui.diagram.Canvas.Edge;
import com.rethinkj.ui.diagram.Canvas.Marker;
import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.diagram.Canvas.Port;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Routes edges between the ports of nodes: a pure function from the nodes' boxes and the edges'
 * ports to orthogonal lines, in diagram coordinates.
 *
 * <p>Each edge leaves its nodes on the left or right side at its ports' heights, moved apart by a
 * marker's height where edges with different markers meet at one port, or less where that would
 * leave the node, and runs straight for {@link EdgeStyle#stub} before it bends, or where there is
 * less room, as far as its marker and a bend take. Of the shapes that allows, it takes the one
 * that crosses the fewest nodes, then the shortest, counting each bend as a stub's length: a
 * straight line between ports at one height with room for both markers, a Z through the gap
 * between facing sides, a C around the outer sides of two nodes above each other or of one node
 * with itself, and where each of these crosses a node, a detour with four bends along a row
 * above or below a node, its legs moved past the nodes they would cross: a stub past them, or
 * half a lane where only that is free. A C needs its ends at least a {@link EdgeStyle#marker}
 * apart, so that its markers do not lie on each other: one from a port to the same port goes
 * around its node. Where a C on either side costs as much, it goes on the side of its nodes where
 * fewer other edges end, on the right where as many do. A straight edge between the same two
 * points as one before it bumps out, below and above in turn, a lane further for every two, or on
 * the other side where that crosses fewer nodes; where both cross more nodes than the line, or the
 * gap has no room for its bends, it stays on the line.
 *
 * <p>Then the runs between two bends that lie closer than {@link EdgeStyle#lane} beside each other
 * and overlap, also only at an end, as edges that meet at one port do, are spread a lane apart:
 * centered on where they wanted to be, ordered by their shapes and with neighbors swapped where
 * that crosses less, each within its bounds: clear of its markers and bends and of the nodes
 * beside it. Where the bounds are too narrow for that, they lie as far apart as the tightest
 * allows. A route that spreading would make cross a node it did not cross keeps its place. Runs
 * that end at a port are not moved, so edges that meet at a port join there.
 *
 * <p>Limits: the router does not search all paths, so an edge can still cross a node where the
 * shapes above find no free way, and where the room beside a group of runs is narrower than the
 * group, they lie closer than a lane.
 */
final class Routing {

  private static final double HIT = 1e9; // what crossing a node costs, beyond any length
  private static final double CRAMPED = 1e6; // what markers on each other cost, beyond lengths
  private static final double EPSILON = 1e-6;

  private Routing() {}

  /** A point of a route, in diagram coordinates. */
  record Point(double x, double y) {}

  /**
   * An edge's line from its {@code from} port to its {@code to} port: the ports, where it meets
   * the nodes' sides, and its bends between them; each run is horizontal or vertical.
   */
  record Route(List<Point> points) {}

  /**
   * Routes the edges among the nodes.
   *
   * @return a route for each edge in their order; null for one whose node is not among the nodes
   */
  static List<Route> route(List<Node> nodes, List<Edge> edges, EdgeStyle style) {
    Map<String, Node> byKey = new HashMap<>();
    for (Node node : nodes) {
      byKey.putIfAbsent(node.key(), node);
    }
    Obstacles obstacles = new Obstacles(nodes, style.stub());
    double[][] fanned = fanned(byKey, edges, style);
    Path[] paths = new Path[edges.size()];
    Path[] others = new Path[edges.size()]; // the C on the other side, where it is as good
    Map<List<Double>, Integer> straights = new HashMap<>(); // per line, how many lie on it
    for (int i = 0; i < paths.length; i++) {
      Edge edge = edges.get(i);
      Node from = byKey.get(edge.from().node());
      Node to = byKey.get(edge.to().node());
      if (from == null || to == null) {
        continue;
      }
      double y1 = from.y() + edge.from().y() + fanned[i][0];
      double y2 = to.y() + edge.to().y() + fanned[i][1];
      double[] reach = {
        Edges.reach(edge.fromMarker()) * style.marker(),
        Edges.reach(edge.toMarker()) * style.marker()
      };
      // A detour is looked for only where no route through one channel is free and roomy.
      Path best = null;
      for (int[] sides : SIDES) {
        best = cheaper(
            best, best(from, y1, sides[0], to, y2, sides[1], reach, obstacles, style, false));
      }
      boolean detours = best == null || best.cost >= CRAMPED; // crosses a node, or cramped
      if (detours) {
        for (int[] sides : SIDES) {
          best = cheaper(
              best, best(from, y1, sides[0], to, y2, sides[1], reach, obstacles, style, true));
        }
      }
      if (best.xs.length == 2) {
        List<Double> line =
            List.of(Math.min(best.xs[0], best.xs[1]), Math.max(best.xs[0], best.xs[1]), y1);
        int before = straights.merge(line, 1, Integer::sum) - 1;
        if (before > 0) {
          // Below and above in turn, a lane further out for every two, or on the other side where
          // that crosses fewer nodes; on the line where both cross more.
          int out = (before + 1) / 2 * (before % 2 == 1 ? 1 : -1);
          Path bumped = cheaper(
              bump(best, out, reach, obstacles, style), bump(best, -out, reach, obstacles, style));
          if (bumped != null && bumped.hits <= best.hits) {
            best = bumped;
          }
        }
      }
      paths[i] = best;
      if (best.sameSide()) {
        int side = -best.fromSide;
        Path other = best(from, y1, side, to, y2, side, reach, obstacles, style, detours);
        if (other != null && other.sameSide() && Math.abs(other.cost - best.cost) <= EPSILON) {
          others[i] = other;
        }
      }
    }
    freeSides(paths, others, edges);
    // Spreading never makes a route cross a node it did not: such a route keeps its place.
    double[][][] placed = new double[paths.length][][];
    for (int i = 0; i < paths.length; i++) {
      if (paths[i] != null) {
        placed[i] = new double[][] {paths[i].xs.clone(), paths[i].ys.clone()};
      }
    }
    new Lanes(paths, nodes, style.lane()).spread();
    for (int i = 0; i < paths.length; i++) {
      if (paths[i] != null) {
        int hits = paths[i].hits;
        paths[i].price(obstacles, style.stub());
        if (paths[i].hits > hits) {
          System.arraycopy(placed[i][0], 0, paths[i].xs, 0, paths[i].xs.length);
          System.arraycopy(placed[i][1], 0, paths[i].ys, 0, paths[i].ys.length);
          paths[i].price(obstacles, style.stub());
        }
      }
    }
    List<Route> routes = new ArrayList<>(paths.length);
    for (Path path : paths) {
      routes.add(path == null ? null : path.route());
    }
    return routes;
  }

  /**
   * How far each edge's ends move along their nodes' sides, from and to: where edges with
   * different markers meet at one port, each kind of marker gets a place of its own, a marker's
   * height apart around the port and in the order of where their other ends lie, so that each
   * shows its own; edges with the same marker still join.
   */
  private static double[][] fanned(Map<String, Node> byKey, List<Edge> edges, EdgeStyle style) {
    double[][] fanned = new double[edges.size()][2];
    Map<List<Object>, List<int[]>> ports = new LinkedHashMap<>(); // per port: edge, end
    for (int i = 0; i < edges.size(); i++) {
      Edge edge = edges.get(i);
      if (!byKey.containsKey(edge.from().node()) || !byKey.containsKey(edge.to().node())) {
        continue; // not drawn
      }
      ports
          .computeIfAbsent(List.of(edge.from().node(), edge.from().y()), k -> new ArrayList<>())
          .add(new int[] {i, 0});
      ports
          .computeIfAbsent(List.of(edge.to().node(), edge.to().y()), k -> new ArrayList<>())
          .add(new int[] {i, 1});
    }
    for (Map.Entry<List<Object>, List<int[]>> port : ports.entrySet()) {
      List<int[]> ends = port.getValue();
      Map<Marker, double[]> others = new EnumMap<>(Marker.class); // sum and count of their y
      for (int[] end : ends) {
        Edge edge = edges.get(end[0]);
        Port other = end[1] == 0 ? edge.to() : edge.from();
        Node node = byKey.get(other.node());
        double[] where = others.computeIfAbsent(marker(edge, end[1]), k -> new double[2]);
        where[0] += node == null ? 0 : node.y() + other.y();
        where[1]++;
      }
      if (others.size() < 2) {
        continue;
      }
      List<Marker> order = new ArrayList<>(others.keySet()); // in their order, then by place
      order.sort(Comparator.comparingDouble(m -> others.get(m)[0] / others.get(m)[1]));
      // A marker's height apart, or closer where that would put an end off its node or onto its
      // corner.
      double apart = style.marker();
      Node node = byKey.get(port.getKey().get(0));
      if (node != null) {
        double y = (Float) port.getKey().get(1);
        double room = Math.max(0, Math.min(y, node.height() - y) - style.marker() / 4);
        apart = Math.min(apart, 2 * room / (order.size() - 1));
      }
      for (int[] end : ends) {
        int place = order.indexOf(marker(edges.get(end[0]), end[1]));
        fanned[end[0]][end[1]] = (place - (order.size() - 1) / 2.0) * apart;
      }
    }
    return fanned;
  }

  private static Marker marker(Edge edge, int end) {
    return end == 0 ? edge.fromMarker() : edge.toMarker();
  }

  // The sides an edge leaves its from and to nodes on, right (1) or left (-1), in the order that
  // decides between routes that cost as much.
  private static final int[][] SIDES = {{1, -1}, {-1, 1}, {1, 1}, {-1, -1}};

  /**
   * Puts each C that is as good on either side on the side of its nodes where fewer other edges
   * end, in the edges' order, on the right where as many do.
   */
  private static void freeSides(Path[] paths, Path[] others, List<Edge> edges) {
    Map<String, int[]> ends = new HashMap<>(); // per node: ends on the left, ends on the right
    for (int i = 0; i < paths.length; i++) {
      if (paths[i] != null && others[i] == null) {
        count(ends, edges.get(i), paths[i]);
      }
    }
    for (int i = 0; i < paths.length; i++) {
      if (others[i] == null) {
        continue;
      }
      Edge edge = edges.get(i);
      Path onRight = paths[i].fromSide > 0 ? paths[i] : others[i];
      Path onLeft = onRight == paths[i] ? others[i] : paths[i];
      paths[i] = ends(ends, edge, -1) < ends(ends, edge, 1) ? onLeft : onRight;
      count(ends, edge, paths[i]);
    }
  }

  private static void count(Map<String, int[]> ends, Edge edge, Path path) {
    ends.computeIfAbsent(edge.from().node(), k -> new int[2])[path.fromSide > 0 ? 1 : 0]++;
    ends.computeIfAbsent(edge.to().node(), k -> new int[2])[path.toSide > 0 ? 1 : 0]++;
  }

  private static int ends(Map<String, int[]> ends, Edge edge, int side) {
    int index = side > 0 ? 1 : 0;
    int[] from = ends.get(edge.from().node());
    int count = from == null ? 0 : from[index];
    if (!edge.to().node().equals(edge.from().node())) {
      int[] to = ends.get(edge.to().node());
      count += to == null ? 0 : to[index];
    }
    return count;
  }

  /**
   * The best route that leaves {@code from} on side {@code fromSide} and enters {@code to} on
   * side {@code toSide}, or null for none: straight, or through a channel in the middle of where
   * it may lie, or where that crosses a node, beside one of the nodes there; and with {@code
   * detours}, also along a row above or below a node.
   */
  private static Path best(
      Node from,
      double y1,
      int fromSide,
      Node to,
      double y2,
      int toSide,
      double[] reach,
      Obstacles obstacles,
      EdgeStyle style,
      boolean detours) {
    double stub = style.stub();
    double px = fromSide > 0 ? from.x() + from.width() : from.x();
    double qx = toSide > 0 ? to.x() + to.width() : to.x();
    if (fromSide == toSide && Math.abs(y1 - y2) < Math.max(style.marker() - EPSILON, EPSILON)) {
      return null; // it would come back on itself
    }
    Path best = null;
    double gap = fromSide * (qx - px);
    if (y1 == y2 && fromSide == -toSide && gap > 0) {
      best = new Path(fromSide, toSide, new double[] {px, qx}, new double[] {y1, y2});
      best.price(obstacles, stub);
      if (gap < reach[0] + reach[1] + style.marker() / 2) {
        best.cost += CRAMPED; // its markers would lie on each other: any other free way first
      }
    }
    // Where the channel may lie: far enough from each port for its marker and a bend; and where it
    // would like to, a stub from each.
    double need1 = reach[0] + style.radius();
    double need2 = reach[1] + style.radius();
    double lo = Math.max(
        fromSide > 0 ? px + need1 : Double.NEGATIVE_INFINITY,
        toSide > 0 ? qx + need2 : Double.NEGATIVE_INFINITY);
    double hi = Math.min(
        fromSide < 0 ? px - need1 : Double.POSITIVE_INFINITY,
        toSide < 0 ? qx - need2 : Double.POSITIVE_INFINITY);
    double wantLo = Math.max(
        fromSide > 0 ? px + Math.max(stub, need1) : Double.NEGATIVE_INFINITY,
        toSide > 0 ? qx + Math.max(stub, need2) : Double.NEGATIVE_INFINITY);
    double wantHi = Math.min(
        fromSide < 0 ? px - Math.max(stub, need1) : Double.POSITIVE_INFINITY,
        toSide < 0 ? qx - Math.max(stub, need2) : Double.POSITIVE_INFINITY);
    if (wantLo > wantHi) {
      wantLo = lo;
      wantHi = hi;
    }
    // Between facing ports at one height a channel is no shape of its own, only a straight line.
    if (lo <= hi && !(y1 == y2 && fromSide == -toSide)) {
      double middle = Math.clamp(
          Double.isInfinite(wantLo)
              ? wantHi
              : Double.isInfinite(wantHi) ? wantLo : (wantLo + wantHi) / 2,
          lo,
          hi);
      Path through = channel(fromSide, px, y1, toSide, qx, y2, middle, lo, hi, obstacles, stub);
      best = cheaper(best, through);
      if (through.hits > 0) {
        // Of those that cost as much, the one nearest the middle.
        double top = Math.min(y1, y2);
        double bottom = Math.max(y1, y2);
        Path beside = null;
        double distance = Double.POSITIVE_INFINITY;
        TreeSet<Double> xs = new TreeSet<>(); // each once, in order
        for (Node node : obstacles.nodes) {
          if (node.y() < bottom && node.y() + node.height() > top) { // beside the channel
            xs.add(node.x() - stub);
            xs.add(node.x() + node.width() + stub);
          }
        }
        for (double x : xs.subSet(lo, true, hi, true)) {
          Path path = channel(fromSide, px, y1, toSide, qx, y2, x, lo, hi, obstacles, stub);
          double off = Math.abs(x - middle);
          if (beside == null
              || path.cost < beside.cost - EPSILON
              || path.cost <= beside.cost + EPSILON && off < distance) {
            beside = path;
            distance = off;
          }
        }
        best = cheaper(best, beside);
      }
    }
    if (detours) {
      // The rows by the least a detour along them costs, its legs at the stubs and crossing
      // nothing; past the first that costs no more than the best so far, none can be better.
      double least = Math.abs(px + fromSide * stub - (qx + toSide * stub)) + 6 * stub;
      double[] rows = obstacles.rows;
      Integer[] order = new Integer[rows.length];
      double[] bound = new double[rows.length];
      for (int i = 0; i < rows.length; i++) {
        order[i] = i;
        bound[i] = least + Math.abs(rows[i] - y1) + Math.abs(rows[i] - y2);
      }
      Arrays.sort(order, (a, b) -> Double.compare(bound[a], bound[b])); // stable: then by row
      for (int i : order) {
        if (best != null && bound[i] >= best.cost - EPSILON) {
          break;
        }
        best = cheaper(
            best,
            detour(fromSide, px, y1, toSide, qx, y2, rows[i], need1, need2, obstacles, style));
      }
    }
    return best;
  }

  /**
   * A detour along the row at {@code y}: out of each port by a stub, or as far beyond as it takes
   * to pass the nodes its legs would cross.
   */
  private static Path detour(
      int fromSide,
      double px,
      double y1,
      int toSide,
      double qx,
      double y2,
      double y,
      double need1,
      double need2,
      Obstacles obstacles,
      EdgeStyle style) {
    double stub = style.stub();
    double c1 = px + fromSide * Math.max(stub, need1);
    double c2 = qx + toSide * Math.max(stub, need2);
    Path path = new Path(
        fromSide, toSide, new double[] {px, c1, c1, c2, c2, qx}, new double[] {y1, y1, y, y, y2, y2
        });
    if (fromSide == -toSide && fromSide * (qx - px) > 0 && fromSide * (c2 - c1) < 0) {
      return null; // between facing sides too close for its legs: it would run back over itself
    }
    path.bounds(1, side(fromSide, px, need1));
    path.bounds(3, side(toSide, qx, need2));
    // Each round moves a leg past one more node, outwards, so it ends.
    for (int round = 0; round <= obstacles.nodes.size(); round++) {
      boolean moved = past(path, 1, fromSide, obstacles, style);
      moved |= past(path, 3, toSide, obstacles, style);
      if (!moved) {
        break;
      }
    }
    if (fromSide == -toSide && fromSide * (qx - px) > 0) {
      // Between facing sides, its legs stay in order with room between them, also when spread.
      if (fromSide * (path.xs[3] - path.xs[1]) <= 0) {
        return null; // passing the nodes in the way turned them around
      }
      apart(path, px + fromSide * need1, qx - fromSide * need2, style.lane());
    }
    path.price(obstacles, stub);
    return path;
  }

  /**
   * Moves the vertical run {@code run} of a detour past the first node that it or the run between
   * it and its port crosses, on the port's side: a stub beyond it, or where that crosses another
   * node and half a lane does not, half a lane; whether it moved.
   */
  private static boolean past(Path path, int run, int side, Obstacles obstacles, EdgeStyle style) {
    int first = crossed(path, run, obstacles);
    if (first < 0) {
      return false;
    }
    Node node = obstacles.nodes.get(first);
    double edge = side > 0 ? node.x() + node.width() : node.x();
    double beyond = edge + side * style.stub();
    if (side * (beyond - path.xs[run]) <= 0) {
      return false; // not past it outwards
    }
    double at = path.xs[run];
    path.move(run, beyond);
    double close = edge + side * style.lane() / 2;
    if (crossed(path, run, obstacles) >= 0 && side * (close - at) > 0) {
      path.move(run, close);
      if (crossed(path, run, obstacles) >= 0) {
        path.move(run, beyond); // neither is free: on past the next node from there
      }
    }
    return true;
  }

  /** The first node that a detour's leg {@code run}, or the run from its port, crosses; -1. */
  private static int crossed(Path path, int run, Obstacles obstacles) {
    int port = run == 1 ? 0 : 5;
    int leg = obstacles.first(path.xs[run], path.ys[run], path.xs[run + 1], path.ys[run + 1]);
    int out = obstacles.first(path.xs[port], path.ys[port], path.xs[run], path.ys[port]);
    return leg < 0 ? out : out < 0 ? leg : Math.min(leg, out);
  }

  /**
   * A straight route that bumps out by {@code count} lanes between its stubs, below where it is
   * positive and above where it is negative, as an edge on a line that others lie on already; or
   * null where the gap has no room for both markers and bends. Its legs stay on their halves of the
   * gap, so that spreading keeps them in order.
   */
  private static Path bump(
      Path straight, int count, double[] reach, Obstacles obstacles, EdgeStyle style) {
    double stub = style.stub();
    double px = straight.xs[0];
    double qx = straight.xs[1];
    double y = straight.ys[0];
    int side = straight.fromSide;
    double need1 = reach[0] + style.radius();
    double need2 = reach[1] + style.radius();
    double room = Math.abs(qx - px) - need1 - need2;
    if (room <= 0) {
      return null;
    }
    double leg1 = Math.max(stub, need1);
    double leg2 = Math.max(stub, need2);
    double c1 = px + side * Math.min(leg1, need1 + room / 3);
    double c2 = qx - side * Math.min(leg2, need2 + room / 3);
    double m = y + count * style.lane();
    Path path = new Path(
        side, straight.toSide, new double[] {px, c1, c1, c2, c2, qx}, new double[] {y, y, m, m, y, y
        });
    path.bounds(
        2,
        count > 0
            ? new double[] {y + style.lane(), Double.POSITIVE_INFINITY}
            : new double[] {Double.NEGATIVE_INFINITY, y - style.lane()});
    path.price(obstacles, stub);
    apart(path, px + side * need1, qx - side * need2, style.lane());
    return path;
  }

  /**
   * Bounds the legs of a route with four bends between facing sides to their own sides of the
   * middle between them, with room between, so that spreading keeps them in order and apart: the
   * first from {@code near1}, the second up to {@code near2}.
   */
  private static void apart(Path path, double near1, double near2, double lane) {
    double c1 = path.xs[1];
    double c2 = path.xs[3];
    double middle = (c1 + c2) / 2;
    double room = Math.min(lane, Math.abs(c2 - c1)) / 4;
    double side = Math.signum(c2 - c1);
    path.bounds(1, ordered(near1, middle - side * room));
    path.bounds(3, ordered(near2, middle + side * room));
  }

  private static double[] ordered(double a, double b) {
    return new double[] {Math.min(a, b), Math.max(a, b)};
  }

  /** The one that costs less, the first where both cost as much. */
  private static Path cheaper(Path first, Path second) {
    if (second == null) {
      return first;
    }
    return first == null || second.cost < first.cost - EPSILON ? second : first;
  }

  /** Where a channel next to a stub that leaves {@code x} on {@code side} may lie. */
  private static double[] side(int side, double x, double stub) {
    return side > 0
        ? new double[] {x + stub, Double.POSITIVE_INFINITY}
        : new double[] {Double.NEGATIVE_INFINITY, x - stub};
  }

  /** A Z or C through a vertical channel at {@code x}. */
  private static Path channel(
      int fromSide,
      double px,
      double y1,
      int toSide,
      double qx,
      double y2,
      double x,
      double lo,
      double hi,
      Obstacles obstacles,
      double stub) {
    Path path =
        new Path(fromSide, toSide, new double[] {px, x, x, qx}, new double[] {y1, y1, y2, y2});
    path.bounds(1, new double[] {lo, hi});
    path.price(obstacles, stub);
    return path;
  }

  /**
   * A route while it is chosen and spread: its points and, for each run between two bends, where
   * that run may lie.
   */
  static final class Path {
    final int fromSide;
    final int toSide;
    final double[] xs;
    final double[] ys;
    final double[][] bounds;
    int hits;
    double cost;

    Path(int fromSide, int toSide, double[] xs, double[] ys) {
      this.fromSide = fromSide;
      this.toSide = toSide;
      this.xs = xs;
      this.ys = ys;
      this.bounds = new double[xs.length - 1][];
    }

    /** Whether it is a C: out of one side and back into the same side through one channel. */
    boolean sameSide() {
      return fromSide == toSide && xs.length == 4;
    }

    void bounds(int run, double[] bounds) {
      this.bounds[run] = bounds;
    }

    /** Counts the nodes it crosses and prices it: by those, then by length and bends. */
    void price(Obstacles obstacles, double stub) {
      hits = 0;
      double length = 0;
      for (int i = 0; i + 1 < xs.length; i++) {
        length += Math.abs(xs[i + 1] - xs[i]) + Math.abs(ys[i + 1] - ys[i]);
        hits += obstacles.count(xs[i], ys[i], xs[i + 1], ys[i + 1]);
      }
      cost = hits * HIT + length + (xs.length - 2) * stub;
    }

    /** Whether a run is vertical: a route leaves its port horizontally and turns at each bend. */
    boolean vertical(int run) {
      return run % 2 == 1;
    }

    /** Where a run lies across its direction: its x when vertical, its y when horizontal. */
    double position(int run) {
      return vertical(run) ? xs[run] : ys[run];
    }

    void move(int run, double position) {
      if (vertical(run)) {
        xs[run] = position;
        xs[run + 1] = position;
      } else {
        ys[run] = position;
        ys[run + 1] = position;
      }
    }

    /** How far a run reaches along its direction: from, to. */
    double[] span(int run) {
      double[] along = vertical(run) ? ys : xs;
      return new double[] {
        Math.min(along[run], along[run + 1]), Math.max(along[run], along[run + 1])
      };
    }

    /**
     * Whether the run that meets this one at its end at {@code point} leaves towards lower
     * coordinates across this one: to the left of a vertical run, above a horizontal one.
     */
    boolean towardsLow(int run, int point) {
      int other = point == run ? point - 1 : point + 1;
      return vertical(run) ? xs[other] < xs[point] : ys[other] < ys[point];
    }

    Route route() {
      List<Point> points = new ArrayList<>(xs.length);
      for (int i = 0; i < xs.length; i++) {
        points.add(new Point(xs[i], ys[i]));
      }
      return new Route(List.copyOf(points));
    }
  }

  /** How often the runs of two paths cross each other, not counting where they only touch. */
  static int crossings(Path a, Path b) {
    if (a == b) {
      return 0;
    }
    int count = 0;
    for (int i = 0; i + 1 < a.xs.length; i++) {
      for (int j = 0; j + 1 < b.xs.length; j++) {
        if (a.vertical(i) == b.vertical(j)) {
          continue;
        }
        Path v = a.vertical(i) ? a : b;
        int vi = v == a ? i : j;
        Path h = v == a ? b : a;
        int hi = v == a ? j : i;
        double[] along = v.span(vi);
        double[] across = h.span(hi);
        double x = v.xs[vi];
        double y = h.ys[hi];
        if (x > across[0] + EPSILON
            && x < across[1] - EPSILON
            && y > along[0] + EPSILON
            && y < along[1] - EPSILON) {
          count++;
        }
      }
    }
    return count;
  }

  /** Whether the run from one point to the other passes through the inside of the node. */
  private static boolean crosses(double x1, double y1, double x2, double y2, Node node) {
    double left = node.x();
    double right = node.x() + node.width();
    double top = node.y();
    double bottom = node.y() + node.height();
    return Math.max(x1, x2) > left
        && Math.min(x1, x2) < right
        && Math.max(y1, y2) > top
        && Math.min(y1, y2) < bottom;
  }

  /**
   * The nodes, filed by the cells of a grid about as large as a node, so that a run is checked
   * only against the nodes near it; and the rows a detour may take, above and below each node.
   */
  static final class Obstacles {
    // The cells a node or a run may cover before it is checked against everything instead.
    private static final int MOST = 64;

    final List<Node> nodes;
    final double[] rows; // each once, in order
    private final double cell;
    private final Map<Long, List<Integer>> cells = new HashMap<>();
    private final List<Integer> large = new ArrayList<>();
    private final int[] seen; // the visit that last saw each node
    private int visit;

    Obstacles(List<Node> nodes, double stub) {
      this.nodes = nodes;
      TreeSet<Double> ys = new TreeSet<>();
      double size = 0;
      for (Node node : nodes) {
        ys.add(node.y() - stub);
        ys.add(node.y() + node.height() + stub);
        size += Math.max(node.width(), node.height());
      }
      rows = ys.stream().mapToDouble(Double::doubleValue).toArray();
      cell = Math.max(1, nodes.isEmpty() ? 1 : size / nodes.size());
      seen = new int[nodes.size()];
      for (int i = 0; i < nodes.size(); i++) {
        Node node = nodes.get(i);
        double left = cell(node.x());
        double right = cell(node.x() + node.width());
        double top = cell(node.y());
        double bottom = cell(node.y() + node.height());
        if (!filed(left, right, top, bottom, MOST)) {
          large.add(i);
          continue;
        }
        for (long x = (long) left; x <= (long) right; x++) {
          for (long y = (long) top; y <= (long) bottom; y++) {
            cells.computeIfAbsent(key(x, y), k -> new ArrayList<>()).add(i);
          }
        }
      }
    }

    /** How many nodes the run from one point to the other crosses. */
    int count(double x1, double y1, double x2, double y2) {
      int count = 0;
      for (int i : near(x1, y1, x2, y2)) {
        if (crosses(x1, y1, x2, y2, nodes.get(i))) {
          count++;
        }
      }
      return count;
    }

    /** The index of the first node, in the nodes' order, that the run crosses; -1 for none. */
    int first(double x1, double y1, double x2, double y2) {
      int first = -1;
      for (int i : near(x1, y1, x2, y2)) {
        if ((first < 0 || i < first) && crosses(x1, y1, x2, y2, nodes.get(i))) {
          first = i;
        }
      }
      return first;
    }

    /** The nodes in the cells the run covers, each once. */
    private List<Integer> near(double x1, double y1, double x2, double y2) {
      double left = cell(Math.min(x1, x2));
      double right = cell(Math.max(x1, x2));
      double top = cell(Math.min(y1, y2));
      double bottom = cell(Math.max(y1, y2));
      if (!filed(left, right, top, bottom, Math.max(MOST, cells.size()))) {
        List<Integer> all = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
          all.add(i);
        }
        return all;
      }
      List<Integer> near = new ArrayList<>(large);
      visit++;
      for (long x = (long) left; x <= (long) right; x++) {
        for (long y = (long) top; y <= (long) bottom; y++) {
          for (int i : cells.getOrDefault(key(x, y), List.of())) {
            if (seen[i] != visit) {
              seen[i] = visit;
              near.add(i);
            }
          }
        }
      }
      return near;
    }

    /** The index of the cell a coordinate lies in, as a whole number. */
    private double cell(double coordinate) {
      return Math.floor(coordinate / cell);
    }

    /**
     * Whether the cells from {@code left} to {@code right} and {@code top} to {@code bottom}
     * are at most {@code most} and lie where a cell's key can tell them apart.
     */
    private static boolean filed(double left, double right, double top, double bottom, int most) {
      return Math.max(Math.abs(left), Math.abs(right)) < 1 << 30
          && Math.max(Math.abs(top), Math.abs(bottom)) < 1 << 30
          && (right - left + 1) * (bottom - top + 1) <= most;
    }

    private static long key(long x, long y) {
      return x << 32 ^ (y & 0xFFFFFFFFL);
    }
  }

  /** Spreads the runs between bends that lie on each other into lanes of their own. */
  private static final class Lanes {
    private final Path[] paths;
    private final double lane;
    // Every run between two bends: its path and its index there.
    private final List<int[]> runs = new ArrayList<>();
    private final double[] preferred;
    private final double[][] bounds; // where each may lie: its path's bounds, clear of nodes
    private final double[][] key; // the order runs take in a lane, by sortKey
    private final int[] group; // the group of each run, by its index in runs
    private final List<List<Integer>> members = new ArrayList<>();

    Lanes(Path[] paths, List<Node> nodes, double lane) {
      this.paths = paths;
      this.lane = lane;
      for (int p = 0; p < paths.length; p++) {
        if (paths[p] != null) {
          for (int run = 1; run + 2 < paths[p].xs.length; run++) {
            runs.add(new int[] {p, run});
          }
        }
      }
      int count = runs.size();
      preferred = new double[count];
      bounds = new double[count][];
      key = new double[count][];
      group = new int[count];
      for (int i = 0; i < count; i++) {
        preferred[i] = position(i);
        bounds[i] = bounds(i, nodes);
        key[i] = sortKey(i);
        group[i] = i;
        members.add(new ArrayList<>(List.of(i)));
      }
    }

    void spread() {
      if (lane <= 0) {
        return;
      }
      // Each merge joins two groups and a pass without one ends it, so there are at most as many
      // passes as runs.
      boolean merged = true;
      while (merged) {
        merged = false;
        for (int i = 0; i < runs.size(); i++) {
          for (int j = i + 1; j < runs.size(); j++) {
            if (group[i] != group[j] && near(i, j)) {
              int keep = group[i];
              int gone = group[j];
              for (int k : members.get(gone)) {
                group[k] = keep;
              }
              members.get(keep).addAll(members.get(gone));
              members.get(gone).clear();
              place(members.get(keep));
              merged = true;
            }
          }
        }
      }
    }

    private boolean near(int i, int j) {
      Path a = path(i);
      Path b = path(j);
      if (a == b) {
        return false; // a route's own runs need no lanes apart
      }
      int ra = runs.get(i)[1];
      int rb = runs.get(j)[1];
      if (a.vertical(ra) != b.vertical(rb)
          || Math.abs(a.position(ra) - b.position(rb)) >= lane - EPSILON) {
        return false;
      }
      double[] sa = a.span(ra);
      double[] sb = b.span(rb);
      return sa[0] <= sb[1] + EPSILON && sb[0] <= sa[1] + EPSILON;
    }

    /**
     * The order of a run in a lane, so that two runs cross only where either order would: by the
     * sides its ends turn to, then by where those ends lie. A Z whose start, its end at the lower
     * coordinate along it, turns low and whose end turns high, lies the further towards high the
     * lower its start; the other Z the further the higher its end; and of two C the longer lies
     * outside the shorter.
     */
    private double[] sortKey(int i) {
      Path path = path(i);
      int run = runs.get(i)[1];
      double[] along = path.vertical(run) ? path.ys : path.xs;
      boolean forward = along[run] <= along[run + 1];
      int startPoint = forward ? run : run + 1;
      int endPoint = forward ? run + 1 : run;
      boolean startLow = path.towardsLow(run, startPoint);
      boolean endLow = path.towardsLow(run, endPoint);
      double start = along[startPoint];
      double end = along[endPoint];
      if (!startLow && !endLow) {
        return new double[] {0, start - end, start, i}; // a C to the high side: longest lowest
      }
      if (startLow && !endLow) {
        return new double[] {1, -start, -end, i};
      }
      if (!startLow) {
        return new double[] {2, end, start, i};
      }
      return new double[] {3, end - start, start, i}; // a C to the low side: longest highest
    }

    /**
     * Places a group's runs in order around the middle of where they wanted to be, each within its
     * own bounds, a lane apart, or as far apart as the tightest of their bounds allows.
     */
    private void place(List<Integer> group) {
      List<Integer> order = new ArrayList<>(group);
      order.sort((i, j) -> Arrays.compare(key[i], key[j]));
      // By where they may lie instead, where that keeps them further apart than their shapes'
      // order.
      List<Integer> byBounds = new ArrayList<>(order);
      byBounds.sort((i, j) -> {
        int by = Double.compare(bounds[i][0], bounds[j][0]);
        by = by != 0 ? by : Double.compare(bounds[i][1], bounds[j][1]);
        return by != 0 ? by : Arrays.compare(key[i], key[j]);
      });
      double spacing = spacing(order);
      double other = spacing(byBounds);
      if (other > spacing + EPSILON) {
        order = byBounds;
        spacing = other;
      }
      int n = order.size();
      boolean possible = spacing >= 0;
      spacing = Math.max(spacing, 0);
      // Where each may lie at the earliest and at the latest with that spacing before and after.
      double[] earliest = new double[n];
      double[] latest = new double[n];
      for (int k = 0; k < n; k++) {
        double[] own = bounds[order.get(k)];
        earliest[k] = k == 0 ? own[0] : Math.max(own[0], earliest[k - 1] + spacing);
      }
      for (int k = n - 1; k >= 0; k--) {
        double[] own = bounds[order.get(k)];
        latest[k] = k == n - 1 ? own[1] : Math.min(own[1], latest[k + 1] - spacing);
      }
      double center = 0;
      for (int i : order) {
        center += preferred[i];
      }
      center /= n;
      double[] at = new double[n];
      for (int k = 0; k < n; k++) {
        double target = center + (k - (n - 1) / 2.0) * spacing;
        double[] own = bounds[order.get(k)];
        at[k] = possible
            ? Math.clamp(target, earliest[k], Math.max(earliest[k], latest[k]))
            : Math.clamp(target, own[0], Math.max(own[0], own[1]));
        move(order.get(k), at[k]);
      }
      // The order of different shapes may still cross where the other does not: swap neighbors
      // that then cross each other less, where both fit.
      boolean better = true;
      for (int pass = 0; better && pass < n; pass++) {
        better = false;
        for (int k = 0; k + 1 < n; k++) {
          int a = order.get(k);
          int b = order.get(k + 1);
          if (!fits(a, at[k + 1]) || !fits(b, at[k])) {
            continue;
          }
          int before = crossings(path(a), path(b));
          move(a, at[k + 1]);
          move(b, at[k]);
          if (crossings(path(a), path(b)) < before) {
            order.set(k, b);
            order.set(k + 1, a);
            better = true;
          } else {
            move(a, at[k]);
            move(b, at[k + 1]);
          }
        }
      }
    }

    /**
     * How far apart the runs can lie in this order within their bounds, up to a lane; -1 where
     * their bounds do not allow the order at all.
     */
    private double spacing(List<Integer> order) {
      if (!fits(order, 0)) {
        return -1;
      }
      if (fits(order, lane)) {
        return lane;
      }
      double low = 0;
      double high = lane;
      for (int step = 0; step < 32; step++) {
        double middle = (low + high) / 2;
        if (fits(order, middle)) {
          low = middle;
        } else {
          high = middle;
        }
      }
      return low;
    }

    /** Whether the runs fit their bounds in this order, at least {@code spacing} apart. */
    private boolean fits(List<Integer> order, double spacing) {
      double at = Double.NEGATIVE_INFINITY;
      for (int i : order) {
        at = Math.max(bounds[i][0], at + spacing);
        if (at > bounds[i][1] + EPSILON) {
          return false;
        }
      }
      return true;
    }

    private boolean fits(int i, double position) {
      return position >= bounds[i][0] - EPSILON && position <= bounds[i][1] + EPSILON;
    }

    private void move(int i, double position) {
      int[] run = runs.get(i);
      paths[run[0]].move(run[1], position);
    }

    private Path path(int i) {
      return paths[runs.get(i)[0]];
    }

    private double position(int i) {
      return path(i).position(runs.get(i)[1]);
    }

    /**
     * Where a run may lie: within its path's bounds, and between the nearest nodes beside it,
     * half a lane clear of them, but never so that it excludes where it lies now.
     */
    private double[] bounds(int i, List<Node> nodes) {
      Path path = path(i);
      int run = runs.get(i)[1];
      double[] own = path.bounds[run];
      double lo = own == null ? Double.NEGATIVE_INFINITY : own[0];
      double hi = own == null ? Double.POSITIVE_INFINITY : own[1];
      boolean vertical = path.vertical(run);
      double at = path.position(run);
      double[] span = path.span(run);
      double clear = Double.NEGATIVE_INFINITY;
      double until = Double.POSITIVE_INFINITY;
      for (Node node : nodes) {
        double from = vertical ? node.y() : node.x();
        double to = from + (vertical ? node.height() : node.width());
        if (from >= span[1] && from > span[0] || to <= span[0] && to < span[1]) {
          continue; // not beside the run
        }
        double near = vertical ? node.x() : node.y();
        double far = near + (vertical ? node.width() : node.height());
        if (far <= at) {
          clear = Math.max(clear, far + lane / 2);
        } else if (near >= at) {
          until = Math.min(until, near - lane / 2);
        }
      }
      double from = Math.min(clear, at);
      double to = Math.max(until, at);
      return new double[] {Math.max(lo, from), Math.min(hi, to)};
    }
  }
}
