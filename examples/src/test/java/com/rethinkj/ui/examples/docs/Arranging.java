package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.diagram.Canvas.canvas;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.diagram.Canvas;
import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.diagram.LayeredLayout;
import com.rethinkj.ui.diagram.LayeredLayout.Edge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Theme;
import java.util.List;

/** The arrange example of the canvas page: five tables in layers by their foreign keys. */
final class Arranging {

  // docs:start canvas-arrange
  final Canvas.Viewport viewport = new Canvas.Viewport();
  // Each edge goes from a table to the one it references, which comes first.
  final List<Edge> references = List.of(
      new Edge("orders", "customers"),
      new Edge("items", "orders"),
      new Edge("items", "products"),
      new Edge("products", "suppliers"));
  List<Node> tables = List.of(
      new Node("customers", "customers", 0, 0, 160, 72),
      new Node("orders", "orders", 0, 0, 160, 72),
      new Node("items", "order_items", 0, 0, 160, 72),
      new Node("products", "products", 0, 0, 160, 72),
      new Node("suppliers", "suppliers", 0, 0, 160, 72));

  Arranging() {
    arrange();
  }

  static El view(Arranging state) {
    Theme theme = Theme.CURRENT.get();
    return col()
        .wFull()
        .hFull()
        .gap(theme.space().s())
        .padding(theme.space().s())
        .fill(theme.surface().window())
        .child(Button.button(SECONDARY, "Arrange", state::arrange).alignSelf(Style.Align.START))
        .child(canvas(state.viewport, state.tables, Diagrams::table, state::move)
            .wFull()
            .grow(1));
  }

  // Pinned tables stay where the user put them; the others are placed around them.
  void arrange() {
    tables = LayeredLayout.arrange(tables, references);
    viewport.fit();
  }

  void move(Node table) {
    tables = tables.stream()
        .map(other -> other.key().equals(table.key()) ? table : other)
        .toList();
  }
  // docs:end canvas-arrange
}
