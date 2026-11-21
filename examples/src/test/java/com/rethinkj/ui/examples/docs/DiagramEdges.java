package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.text;
import static com.rethinkj.ui.diagram.Canvas.canvas;

import com.rethinkj.ui.El;
import com.rethinkj.ui.diagram.Canvas;
import com.rethinkj.ui.diagram.Canvas.Edge;
import com.rethinkj.ui.diagram.Canvas.Emphasis;
import com.rethinkj.ui.diagram.Canvas.Marker;
import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.diagram.Canvas.Port;
import com.rethinkj.ui.kit.Theme;
import java.util.List;
import java.util.Map;

/** The example of the canvas page's edges: three tables and two foreign keys between them. */
final class DiagramEdges {

  private static final Map<String, List<String>> COLUMNS = Map.of(
      "customer", List.of("id", "name"),
      "order", List.of("id", "customer_id", "billing_id"),
      "address", List.of("id", "city"));

  // docs:start canvas-edges
  static final float HEADER = 32;
  static final float ROW = 24;

  final Canvas.Viewport viewport = new Canvas.Viewport();
  final List<Node> tables = List.of(
      new Node("customer", "customer", 0, 24, 160, HEADER + 2 * ROW),
      new Node("order", "order", 240, 0, 160, HEADER + 3 * ROW),
      new Node("address", "address", 0, 150, 160, HEADER + 2 * ROW));
  final List<Edge> foreignKeys = List.of(
      new Edge(
          "order-customer",
          "order_customer_fk",
          column("order", 1),
          column("customer", 0),
          Marker.ZERO_OR_MANY,
          Marker.ONE),
      new Edge(
          "order-billing",
          "order_billing_fk",
          column("order", 2),
          column("address", 0),
          Marker.ZERO_OR_MANY,
          Marker.ZERO_OR_ONE,
          Emphasis.HIGHLIGHTED,
          "ON DELETE SET NULL"));

  // Where an edge meets a table: the middle of a column's row, at 100 %.
  static Port column(String table, int index) {
    return new Port(table, HEADER + index * ROW + ROW / 2);
  }

  static El view(DiagramEdges state) {
    return canvas(state.viewport, state.tables, state.foreignKeys, DiagramEdges::table, null)
        .wFull()
        .hFull();
  }
  // docs:end canvas-edges

  DiagramEdges() {
    viewport.fit();
  }

  // A card with the table's name and a row per column, each as high as the ports assume.
  static El table(Node table, float zoom) {
    Theme theme = Theme.CURRENT.get();
    El card = col()
        .wFull()
        .hFull()
        .fill(theme.surface().panel())
        .radius(theme.radius().large() * zoom)
        .border(theme.size().stroke(), theme.border().divider())
        .child(text(table.label())
            .font(theme.type().heading().font().get())
            .fontSize(theme.type().heading().size() * zoom)
            .color(theme.text().primary())
            .h(HEADER * zoom)
            .padding(theme.space().s() * zoom, theme.space().m() * zoom));
    for (String column : COLUMNS.get(table.key())) {
      card.child(text(column)
          .fontSize(theme.type().small().size() * zoom)
          .color(theme.text().muted())
          .h(ROW * zoom)
          .padding(theme.space().xs() * zoom, theme.space().m() * zoom));
    }
    return card;
  }
}
