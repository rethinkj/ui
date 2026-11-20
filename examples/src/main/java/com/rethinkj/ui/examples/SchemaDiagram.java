package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.text;
import static com.rethinkj.ui.diagram.Canvas.canvas;
import static com.rethinkj.ui.kit.TitleBar.titleBar;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.diagram.Canvas;
import com.rethinkj.ui.diagram.Canvas.Node;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tables of a shop's schema on the diagram module's canvas, in a card under a title bar.
 * Dragging empty space pans, dragging a table moves and pins it; with the canvas focused, {@code
 * +} and {@code -} zoom and {@code 0} fits all tables in. Zoomed out, the tables show only their
 * names.
 */
public final class SchemaDiagram {

  private static final Map<String, List<String>> COLUMNS = Map.of(
      "customers", List.of("id", "name", "email", "region"),
      "orders", List.of("id", "customer_id", "created_at", "total"),
      "order_items", List.of("order_id", "product_id", "quantity"),
      "products", List.of("id", "name", "price"),
      "suppliers", List.of("id", "name"));

  final Canvas.Viewport viewport = new Canvas.Viewport();
  final List<Node> tables = new ArrayList<>(List.of(
      table("customers", 0, 0),
      table("orders", 240, 40),
      table("order_items", 480, 0),
      table("products", 720, 40),
      table("suppliers", 960, 0)));

  SchemaDiagram() {
    viewport.fit();
  }

  public static void main(String[] args) throws IOException {
    App<SchemaDiagram> app = new App<>(new SchemaDiagram(), SchemaDiagram::view);
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(
            SchemaDiagram.class, "Schema", Identity.TEAL, Symbol.TABLE)
        .frameless()
        .minimumSize(360, 240);
    try (Window window = Window.open("Schema", 880, 480, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  static El view(SchemaDiagram state) {
    Theme theme = Theme.CURRENT.get();
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(titleBar(AppIcon.appIcon(16, Identity.TEAL, Symbol.TABLE), "Schema"))
        .child(Card.flush(canvas(state.viewport, state.tables, SchemaDiagram::card, state::move)
                .label("Schema of the shop")
                .grow(1))
            .grow(1)
            .margin(0, theme.space().m(), theme.space().m(), theme.space().m()));
  }

  private static Node table(String name, double x, double y) {
    return new Node(name, name, x, y, 180, 40 + 24 * COLUMNS.get(name).size());
  }

  // A table at the zoom's size: its name, and its columns from 75 % on.
  static El card(Node table, float zoom) {
    Theme theme = Theme.CURRENT.get();
    float pad = theme.space().m() * zoom;
    El card = col()
        .wFull()
        .hFull()
        .padding(pad)
        .gap(theme.space().s() * zoom)
        .fill(theme.surface().panel())
        .radius(theme.radius().large() * zoom)
        .border(theme.size().stroke(), theme.border().divider())
        .child(text(table.label())
            .font(theme.type().heading().font().get())
            .fontSize(theme.type().heading().size() * zoom)
            .color(theme.text().primary()));
    if (zoom < 0.75f) {
      return card.justify(Style.Content.CENTER);
    }
    for (String column : COLUMNS.get(table.key())) {
      card.child(text(column)
          .font(theme.type().mono().font().get())
          .fontSize(theme.type().mono().size() * zoom)
          .color(theme.text().muted()));
    }
    return card;
  }

  void move(Node table) {
    tables.replaceAll(other -> other.key().equals(table.key()) ? table : other);
  }
}
