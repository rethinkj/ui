package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.text;
import static com.rethinkj.ui.diagram.Canvas.canvas;

import com.rethinkj.ui.El;
import com.rethinkj.ui.diagram.Canvas;
import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
import java.util.List;

/** The example of the canvas page: four tables on a canvas, fitted in, one of them moved. */
final class Diagrams {

  // docs:start canvas
  final Canvas.Viewport viewport = new Canvas.Viewport();
  final List<Node> tables = new ArrayList<>(List.of(
      new Node("customers", "customers", 0, 0, 160, 72),
      new Node("orders", "orders", 240, 40, 160, 72),
      new Node("products", "products", 480, 0, 160, 72),
      new Node("items", "order_items", 480, 140, 160, 72, true)));

  Diagrams() {
    viewport.fit(); // once the canvas knows its size
  }

  static El view(Diagrams state) {
    return canvas(state.viewport, state.tables, Diagrams::table, state::move)
        .wFull()
        .hFull();
  }

  // Built at the size the zoom gives the node; zoomed out, only the name.
  static El table(Node table, float zoom) {
    Theme theme = Theme.CURRENT.get();
    El card = col()
        .wFull()
        .hFull()
        .padding(theme.space().m() * zoom)
        .gap(theme.space().xs() * zoom)
        .fill(theme.surface().panel())
        .radius(theme.radius().large() * zoom)
        .border(theme.size().stroke(), theme.border().divider())
        .child(text(table.label())
            .font(theme.type().heading().font().get())
            .fontSize(theme.type().heading().size() * zoom)
            .color(theme.text().primary()));
    if (zoom >= 0.75f) {
      card.child(text("id, name, created_at")
          .fontSize(theme.type().small().size() * zoom)
          .color(theme.text().muted()));
    }
    return card;
  }

  // The canvas reports the table moved and pinned; the app keeps it.
  void move(Node table) {
    tables.replaceAll(other -> other.key().equals(table.key()) ? table : other);
  }
  // docs:end canvas
}
