package com.rethinkj.ui.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.diagram.Canvas.Edge;
import com.rethinkj.ui.diagram.Canvas.Marker;
import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.diagram.Canvas.Port;
import com.rethinkj.ui.diagram.Routing.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The routes of edges: lanes of their own at one port and clear of nodes, edges on one line kept
 * apart, a loop on the free side, nodes avoided.
 */
class RoutingTest {

  // A stub of 24 and lanes 10 apart.
  private static final EdgeStyle STYLE = new EdgeStyle(24, 6, 10, 8, 4, 1, 2, 8, 0.6f);

  private static Edge edge(String key, String from, float fromY, String to, float toY) {
    return new Edge(key, null, new Port(from, fromY), new Port(to, toY), Marker.NONE, Marker.NONE);
  }

  @Test
  void edgesToOnePortTakeLanesOfTheirOwnAndJoinThere() {
    List<Node> nodes =
        List.of(new Node("a", null, 0, 0, 100, 100), new Node("b", null, 300, 0, 100, 100));
    List<Routing.Route> routes = Routing.route(
        nodes, List.of(edge("first", "a", 20, "b", 50), edge("second", "a", 80, "b", 50)), STYLE);

    // Out of a's right side and into b's left, through the gap between the stubs, 124 to 276,
    // a lane either side of its middle.
    assertEquals(
        List.of(new Point(100, 20), new Point(195, 20), new Point(195, 50), new Point(300, 50)),
        routes.get(0).points());
    assertEquals(
        List.of(new Point(100, 80), new Point(205, 80), new Point(205, 50), new Point(300, 50)),
        routes.get(1).points());

    // With different markers at the port, a marker's height apart around it, each with its own.
    routes = Routing.route(
        nodes,
        List.of(
            new Edge("first", null, new Port("a", 20), new Port("b", 50), Marker.NONE, Marker.ONE),
            new Edge(
                "second",
                null,
                new Port("a", 80),
                new Port("b", 50),
                Marker.NONE,
                Marker.ZERO_OR_ONE)),
        STYLE);
    assertEquals(new Point(300, 46), routes.get(0).points().getLast());
    assertEquals(new Point(300, 54), routes.get(1).points().getLast());

    // A small node half a lane beside the second lane, at 205: the second stays where it was, no
    // nearer to it, and the first moves to keep a lane between them.
    List<Node> blocked = List.of(nodes.get(0), nodes.get(1), new Node("c", null, 205, 60, 8, 10));
    routes = Routing.route(
        blocked, List.of(edge("first", "a", 20, "b", 50), edge("second", "a", 80, "b", 50)), STYLE);
    assertEquals(190, routes.get(0).points().get(1).x());
    assertEquals(200, routes.get(1).points().get(1).x());
  }

  @Test
  void edgesBetweenTheSamePointsStayApart() {
    List<Node> nodes =
        List.of(new Node("a", null, 0, 0, 100, 100), new Node("b", null, 300, 0, 100, 100));

    // The second bumps out below by a lane between its stubs.
    List<Routing.Route> routes = Routing.route(
        nodes, List.of(edge("first", "a", 50, "b", 50), edge("second", "a", 50, "b", 50)), STYLE);
    assertEquals(List.of(new Point(100, 50), new Point(300, 50)), routes.get(0).points());
    assertEquals(
        List.of(
            new Point(100, 50),
            new Point(124, 50),
            new Point(124, 60),
            new Point(276, 60),
            new Point(276, 50),
            new Point(300, 50)),
        routes.get(1).points());
    // Where a node lies a lane below the line: a lane above it.
    List<Node> blocked = List.of(nodes.get(0), nodes.get(1), new Node("c", null, 180, 55, 40, 20));
    routes = Routing.route(
        blocked, List.of(edge("first", "a", 50, "b", 50), edge("second", "a", 50, "b", 50)), STYLE);
    assertEquals(
        List.of(
            new Point(100, 50),
            new Point(124, 50),
            new Point(124, 40),
            new Point(276, 40),
            new Point(276, 50),
            new Point(300, 50)),
        routes.get(1).points());
    // From a port to the same port: around the node, over its top.
    assertEquals(
        List.of(
            new Point(100, 50),
            new Point(124, 50),
            new Point(124, -24),
            new Point(-24, -24),
            new Point(-24, 50),
            new Point(0, 50)),
        Routing.route(nodes, List.of(edge("self", "a", 50, "a", 50)), STYLE)
            .getFirst()
            .points());
  }

  @Test
  void manyEdgesBetweenTheSamePointsInANarrowGapKeepTheirLegsApart() {
    List<Node> nodes =
        List.of(new Node("a", null, 0, 0, 100, 28), new Node("b", null, 160, 0, 100, 28));
    List<Edge> keys = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      keys.add(new Edge(
          "fk" + i,
          null,
          new Port("a", 14),
          new Port("b", 14),
          Marker.ZERO_OR_MANY,
          Marker.ZERO_OR_ONE));
    }

    // The three that bump out nest, each with legs of its own, though the gap has room for no lane.
    Set<Double> legs = new HashSet<>();
    for (Routing.Route route : Routing.route(nodes, keys, STYLE).subList(1, 4)) {
      legs.add(route.points().get(1).x());
      legs.add(route.points().get(3).x());
    }
    assertEquals(6, legs.size(), legs.toString());
  }

  @Test
  void aBumpKeepsOffAnotherEdgesLine() {
    List<Node> nodes =
        List.of(new Node("a", null, 0, 0, 120, 28), new Node("b", null, 240, 0, 120, 28));
    List<Edge> keys = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      keys.add(new Edge(
          "fk" + i,
          null,
          new Port("a", 14),
          new Port("b", 14),
          Marker.ZERO_OR_MANY,
          Marker.ZERO_OR_ONE));
    }
    keys.add(new Edge("other", null, new Port("a", 14), new Port("b", 14), Marker.ONE, Marker.ONE));
    List<Routing.Route> routes = Routing.route(nodes, keys, STYLE);

    // The other markers end 8 above the three: the bumps go a lane below, then two, not onto it.
    assertEquals(10, routes.get(3).points().getFirst().y());
    assertEquals(28, routes.get(1).points().get(2).y());
    assertEquals(38, routes.get(2).points().get(2).y());
  }

  @Test
  void anEdgeTooShortForItsMarkersGoesAround() {
    List<Node> nodes =
        List.of(new Node("a", null, 0, 0, 100, 40), new Node("b", null, 130, 0, 100, 40));
    Edge edge =
        new Edge("e", null, new Port("a", 20), new Port("b", 20), Marker.ZERO_OR_MANY, Marker.ONE);

    // 30 apart, with markers of 8 reaching 16.8 and 10: out of a's right side into the gap as far
    // toward its middle as the marker and bend allow, over b and into its far side.
    double leg =
        100.0 + (double) (Edges.reach(Marker.ZERO_OR_MANY) * STYLE.marker()) + STYLE.radius();
    assertEquals(
        List.of(
            new Point(100, 20),
            new Point(leg, 20),
            new Point(leg, -24),
            new Point(254, -24),
            new Point(254, 20),
            new Point(230, 20)),
        Routing.route(nodes, List.of(edge), STYLE).getFirst().points());
  }

  @Test
  void aSelfReferenceLoopsOnTheFreeSide() {
    List<Node> nodes =
        List.of(new Node("a", null, 0, 0, 100, 100), new Node("b", null, 300, 0, 100, 100));
    Edge loop = edge("parent", "a", 50, "a", 80);

    // Alone, on the right.
    assertEquals(
        List.of(new Point(100, 50), new Point(124, 50), new Point(124, 80), new Point(100, 80)),
        Routing.route(nodes, List.of(loop), STYLE).getFirst().points());
    // With an edge to b on the right, on the left; the other edge runs straight.
    List<Routing.Route> routes =
        Routing.route(nodes, List.of(loop, edge("out", "a", 20, "b", 20)), STYLE);
    assertEquals(
        List.of(new Point(0, 50), new Point(-24, 50), new Point(-24, 80), new Point(0, 80)),
        routes.get(0).points());
    assertEquals(List.of(new Point(100, 20), new Point(300, 20)), routes.get(1).points());
  }

  @Test
  void anEdgeGoesAroundANodeInTheWay() {
    List<Node> nodes = List.of(
        new Node("a", null, 0, 0, 100, 40),
        new Node("between", null, 200, 60, 100, 80),
        new Node("b", null, 400, 160, 100, 40));
    Routing.Route route =
        Routing.route(nodes, List.of(edge("e", "a", 20, "b", 20)), STYLE).getFirst();

    // Not through the gap's middle, 250, which crosses the node in between, but a stub before it.
    assertEquals(
        List.of(new Point(100, 20), new Point(176, 20), new Point(176, 180), new Point(400, 180)),
        route.points());
  }

  @Test
  void aDetourMovesItsLegsPastTheNodesInTheWay() {
    List<Node> nodes = List.of(
        new Node("a", null, 0, 80, 100, 40),
        new Node("above", null, -40, 55, 180, 24),
        new Node("below", null, -40, 121, 180, 24),
        new Node("between", null, 230, 80, 40, 40),
        new Node("b", null, 400, 80, 100, 40));
    Routing.Route route =
        Routing.route(nodes, List.of(edge("e", "a", 20, "b", 20)), STYLE).getFirst();

    // Straight on would cross the node between; the row above a, past the node above it.
    assertEquals(
        List.of(
            new Point(100, 100),
            new Point(164, 100),
            new Point(164, 56),
            new Point(376, 56),
            new Point(376, 100),
            new Point(400, 100)),
        route.points());

    // With a node a stub past the node above: the leg centered in the narrow gap between them.
    List<Node> narrow = new ArrayList<>(nodes);
    narrow.add(new Node("narrow", null, 155, 90, 30, 20));
    route = Routing.route(narrow, List.of(edge("e", "a", 20, "b", 20)), STYLE).getFirst();
    assertEquals(147.5, route.points().get(1).x());
  }

  @Test
  void anEdgeKeepsClearOfNeighboringNodesInANarrowGap() {
    EdgeStyle style = EdgeStyle.DEFAULT; // stub 30, lane 10
    // Two nodes stacked at the layout's minimum gap, 32: a detour between them lies midway, not
    // 2 px from one of them.
    List<Node> stacked = List.of(
        new Node("from", null, 0, 100, 240, 128),
        new Node("top", null, 320, 0, 240, 176),
        new Node("bottom", null, 320, 208, 240, 152),
        new Node("to", null, 640, 100, 240, 128));
    Routing.Route route =
        Routing.route(stacked, List.of(edge("e", "from", 60, "to", 60)), style).getFirst();
    assertEquals(192, route.points().get(2).y(), 1e-6);

    // A port aligned with a passing node's bottom border (as with collapsed cards) detours into
    // the gap instead of running along the border.
    List<Node> collapsed = List.of(
        new Node("from", null, 0, 8, 240, 32),
        new Node("top", null, 320, 0, 240, 32),
        new Node("bottom", null, 320, 64, 240, 32),
        new Node("to", null, 640, 21, 240, 32));
    route = Routing.route(collapsed, List.of(edge("e", "from", 16, "to", 11.5f)), style)
        .getFirst();
    assertEquals(48, route.points().get(2).y(), 1e-6);

    // Side-by-side nodes at the layout's minimum gap, 32: a detour's vertical leg and a self-loop's
    // channel center in the gap instead of running 2 px beside the neighbor.
    List<Node> sideBySide = List.of(
        new Node("from", null, 0, 0, 240, 128),
        new Node("beside", null, 272, 0, 240, 128),
        new Node("to", null, 544, 0, 240, 128));
    route = Routing.route(sideBySide, List.of(edge("e", "from", 60, "to", 60)), style)
        .getFirst();
    assertEquals(256, route.points().get(1).x(), 1e-6);

    Routing.Route loop = Routing.route(
            sideBySide, List.of(edge("self", "from", 40, "from", 80)), style)
        .getFirst();
    double loopX = loop.points().get(1).x();
    assertTrue(loopX <= 0 - style.lane() / 2
        || loopX >= 240 + style.lane() / 2 && loopX <= 272 - style.lane() / 2);

    // A second edge between the same ports bumps out where its legs too keep clear of the cards
    // between: into the gap below the line, not around the upper card 2 px beside it.
    List<Node> between = List.of(
        new Node("a", null, 0, 175, 240, 32),
        new Node("b", null, 544, 175, 240, 32),
        new Node("top", null, 272, 146, 240, 32),
        new Node("bottom", null, 272, 210, 240, 32));
    List<Edge> twice = List.of(
        new Edge("one", null, new Port("a", 16), new Port("b", 16), Marker.ONE, Marker.ONE),
        new Edge("two", null, new Port("a", 16), new Port("b", 16), Marker.ONE, Marker.ONE),
        new Edge(
            "other",
            null,
            new Port("a", 16),
            new Port("b", 16),
            Marker.ONE_OR_MANY,
            Marker.ZERO_OR_ONE));
    assertEquals(
        206, Routing.route(between, twice, style).get(1).points().get(2).y(), 1e-6);
  }
}
