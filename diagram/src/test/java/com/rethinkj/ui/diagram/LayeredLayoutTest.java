package com.rethinkj.ui.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.diagram.LayeredLayout.Direction;
import com.rethinkj.ui.diagram.LayeredLayout.Edge;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@link LayeredLayout}: a chain in both directions, a cycle, a self-reference, crossings, two
 * clusters, pinned nodes, and that the same input gives the same output.
 */
class LayeredLayoutTest {

  private static Node node(String key) {
    return new Node(key, key, 0, 0, 100, 40);
  }

  private static Node at(List<Node> nodes, String key) {
    return nodes.stream().filter(n -> n.key().equals(key)).findFirst().orElseThrow();
  }

  private static void assertApart(List<Node> nodes) {
    for (int a = 0; a < nodes.size(); a++) {
      for (int b = a + 1; b < nodes.size(); b++) {
        Node s = nodes.get(a);
        Node t = nodes.get(b);
        boolean overlap = s.x() < t.x() + t.width()
            && t.x() < s.x() + s.width()
            && s.y() < t.y() + t.height()
            && t.y() < s.y() + s.height();
        assertTrue(!overlap, s + " overlaps " + t);
      }
    }
  }

  @Test
  void aChainRunsFromTheNodeReferredTo() {
    List<Node> nodes = List.of(node("items"), node("orders"), node("customers"));
    List<Edge> edges = List.of(
        new Edge("items", "orders"),
        new Edge("orders", "customers"),
        new Edge("orders", "filtered-away"));

    List<Node> across = LayeredLayout.arrange(nodes, edges);
    assertEquals(
        List.of("items", "orders", "customers"), across.stream().map(Node::key).toList());
    assertEquals(0, at(across, "customers").x());
    assertEquals(180, at(across, "orders").x());
    assertEquals(360, at(across, "items").x());
    assertEquals(0, at(across, "items").y());
    assertEquals(0, at(across, "customers").y());

    List<Node> down = LayeredLayout.arrange(
        nodes, edges, LayeredLayout.options().direction(Direction.TOP_TO_BOTTOM).gaps(20, 10, 40));
    assertEquals(0, at(down, "customers").y());
    assertEquals(60, at(down, "orders").y());
    assertEquals(120, at(down, "items").y());
    assertEquals(0, at(down, "items").x());
  }

  @Test
  void aCycleAndASelfReferenceStillGiveLayers() {
    List<Node> nodes = List.of(node("a"), node("b"), node("c"), node("d"));
    List<Edge> edges = List.of(
        new Edge("a", "b"),
        new Edge("b", "c"),
        new Edge("c", "a"),
        new Edge("d", "d"),
        new Edge("d", "a"));

    List<Node> arranged = LayeredLayout.arrange(nodes, edges);
    assertEquals(
        3, arranged.stream().limit(3).mapToDouble(Node::x).distinct().count(), "a layer each");
    assertTrue(at(arranged, "d").x() > at(arranged, "a").x(), "d refers to a");
    assertApart(arranged);
  }

  @Test
  void theOrderOfALayerAvoidsCrossings() {
    // The children come in the other order than their parents.
    List<Node> nodes =
        List.of(node("root"), node("left"), node("right"), node("of-right"), node("of-left"));
    List<Edge> edges = List.of(
        new Edge("left", "root"),
        new Edge("right", "root"),
        new Edge("of-left", "left"),
        new Edge("of-right", "right"));

    List<Node> arranged = LayeredLayout.arrange(nodes, edges);
    assertEquals(at(arranged, "left").y(), at(arranged, "of-left").y(), 1e-6);
    assertEquals(at(arranged, "right").y(), at(arranged, "of-right").y(), 1e-6);
    assertNotEquals(at(arranged, "left").y(), at(arranged, "right").y());
  }

  @Test
  void clustersAreLaidOutApart() {
    List<Node> nodes = List.of(
        node("sales.orders"), node("stock.items"), node("sales.customers"), node("stock.products"));
    List<Edge> edges = List.of(
        new Edge("sales.orders", "sales.customers"),
        new Edge("stock.items", "stock.products"),
        new Edge("sales.orders", "stock.products"));

    LayeredLayout.Options bySchema = LayeredLayout.options()
        .cluster(node -> node.key().substring(0, node.key().indexOf('.')));
    List<Node> arranged = LayeredLayout.arrange(nodes, edges, bySchema);
    // Each cluster is one layered row, without the edge between them; the second is below the
    // first, a cluster gap after it.
    assertEquals(0, at(arranged, "sales.customers").x());
    assertEquals(180, at(arranged, "sales.orders").x());
    assertEquals(0, at(arranged, "sales.orders").y());
    assertEquals(0, at(arranged, "stock.products").x());
    assertEquals(180, at(arranged, "stock.items").x());
    assertEquals(40 + 96, at(arranged, "stock.items").y());

    // An edge between clusters joins nothing: two stock nodes that only a sales node refers to
    // are packed side by side, not laid out in a layer.
    List<Node> apart = LayeredLayout.arrange(
        List.of(
            new Node("sales.orders", null, 0, 0, 100, 100),
            new Node("stock.a", null, 0, 0, 100, 100),
            new Node("stock.b", null, 0, 0, 100, 100)),
        List.of(new Edge("sales.orders", "stock.a"), new Edge("sales.orders", "stock.b")),
        bySchema);
    assertEquals(at(apart, "stock.a").y(), at(apart, "stock.b").y());
    assertEquals(132, at(apart, "stock.b").x() - at(apart, "stock.a").x());

    // With a pin in each, each cluster follows its own.
    List<Node> pinned = new ArrayList<>(nodes);
    pinned.set(2, at(nodes, "sales.customers").movedTo(0, 0));
    pinned.set(3, at(nodes, "stock.products").movedTo(460, 0));
    List<Node> around = LayeredLayout.arrange(pinned, edges, bySchema);
    assertEquals(180, at(around, "sales.orders").x());
    assertEquals(640, at(around, "stock.items").x());
    assertEquals(0, at(around, "stock.items").y());
  }

  @Test
  void pinnedNodesStayAndTheOthersMoveAround() {
    List<Node> nodes = new ArrayList<>();
    List<Edge> edges = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      nodes.add(node("child" + i));
      edges.add(new Edge("child" + i, "parent"));
    }
    nodes.add(node("parent"));
    List<Node> free = LayeredLayout.arrange(nodes, edges);

    // Pinned where the layout puts it, a node changes nothing.
    List<Node> pinnedInPlace = new ArrayList<>(free);
    pinnedInPlace.set(2, free.get(2).movedTo(free.get(2).x(), free.get(2).y()));
    List<Node> again = LayeredLayout.arrange(pinnedInPlace, edges);
    assertSame(pinnedInPlace.get(2), again.get(2));
    for (int i = 0; i < nodes.size(); i++) {
      assertEquals(free.get(i).x(), again.get(i).x());
      assertEquals(free.get(i).y(), again.get(i).y());
    }

    // The parent pinned far away takes the layout with it; a child pinned onto the others' column
    // pushes those it would overlap down.
    List<Node> pinned = new ArrayList<>(nodes);
    Node parent = at(free, "parent").movedTo(1000, 500);
    pinned.set(6, parent);
    Node child = free.get(0).movedTo(1000 + 180, 515);
    pinned.set(0, child);
    List<Node> arranged = LayeredLayout.arrange(pinned, edges);
    assertSame(parent, arranged.get(6));
    assertSame(child, arranged.get(0));
    assertApart(arranged);
    for (int i = 1; i < 6; i++) {
      assertEquals(1180, arranged.get(i).x(), "still in the parent's next layer");
    }
  }

  @Test
  void theSameInputGivesTheSameOutput() {
    List<Node> nodes = new ArrayList<>();
    List<Edge> edges = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      nodes.add(
          new Node("t" + i, null, i * 37, i * 13, 80 + i % 3 * 30, 40 + i % 4 * 20, i % 11 == 5));
      edges.add(new Edge("t" + i, "t" + (i * 7 + 3) % 40));
      edges.add(new Edge("t" + i, "t" + (i / 2)));
    }

    List<Node> arranged = LayeredLayout.arrange(nodes, edges);
    assertEquals(arranged, LayeredLayout.arrange(List.copyOf(nodes), List.copyOf(edges)));
    // Only pins go in: arranging again gives the same.
    assertEquals(arranged, LayeredLayout.arrange(arranged, edges));
    assertApart(arranged);
    assertThrows(
        IllegalArgumentException.class,
        () -> LayeredLayout.arrange(List.of(node("a"), node("a")), List.of()));
  }
}
