package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.diagram.er.ErDiagram.erDiagram;
import static com.rethinkj.ui.kit.TitleBar.titleBar;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.diagram.er.Column;
import com.rethinkj.ui.diagram.er.ErDiagram;
import com.rethinkj.ui.diagram.er.ForeignKey;
import com.rethinkj.ui.diagram.er.Schema;
import com.rethinkj.ui.diagram.er.Table;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The entity relationship diagram of a shop's two schemas, in a card under a title bar: nine
 * tables with a self-reference, a junction table, two foreign keys between the same two tables and
 * one into the other schema. Dragging a table moves and pins it, the wheel pans and Control+wheel
 * zooms; with the canvas focused, the arrow keys move between tables.
 */
public final class SchemaDiagram {

  /** The shop's schemas, as an app fills them from DatabaseMetaData. */
  public static final List<Schema> SHOP = List.of(
      new Schema(
          "public",
          List.of(
              new Table(
                  "customer",
                  List.of(
                      primaryKey("id", "bigint"),
                      new Column("email", "text", false, false, true, null),
                      new Column("name", "text", false),
                      new Column("created_at", "timestamptz", false, false, false, "now()")),
                  List.of()),
              // docs:start er-schema
              new Table(
                  "address",
                  List.of(
                      primaryKey("id", "bigint"),
                      new Column("customer_id", "bigint", false),
                      new Column("line1", "text", false),
                      new Column("city", "text", false),
                      new Column("postal_code", "text", true),
                      new Column("country", "char(2)", false)),
                  List.of(new ForeignKey("address_customer_fk", "customer_id", "customer", "id"))),
              // docs:end er-schema
              new Table(
                  "order",
                  List.of(
                      primaryKey("id", "bigint"),
                      new Column("customer_id", "bigint", false),
                      new Column("shipping_address_id", "bigint", false),
                      new Column("billing_address_id", "bigint", true),
                      new Column("status", "order_status", false, false, false, "'new'"),
                      new Column("created_at", "timestamptz", false)),
                  List.of(
                      new ForeignKey("order_customer_fk", "customer_id", "customer", "id"),
                      new ForeignKey(
                          "order_shipping_address_fk", "shipping_address_id", "address", "id"),
                      new ForeignKey(
                          "order_billing_address_fk",
                          List.of("billing_address_id"),
                          null,
                          "address",
                          List.of("id"),
                          "SET NULL"))),
              new Table(
                  "order_item",
                  List.of(
                      primaryKey("order_id", "bigint"),
                      primaryKey("product_id", "bigint"),
                      new Column("quantity", "integer", false),
                      new Column("unit_price", "numeric(10,2)", false)),
                  List.of(
                      new ForeignKey(
                          "order_item_order_fk",
                          List.of("order_id"),
                          null,
                          "order",
                          List.of("id"),
                          "CASCADE"),
                      new ForeignKey("order_item_product_fk", "product_id", "product", "id"))),
              new Table(
                  "product",
                  List.of(
                      primaryKey("id", "bigint"),
                      new Column("category_id", "bigint", true),
                      new Column("sku", "text", false, false, true, null),
                      new Column("name", "text", false),
                      new Column("price", "numeric(10,2)", false)),
                  List.of(new ForeignKey("product_category_fk", "category_id", "category", "id"))),
              new Table(
                  "category",
                  List.of(
                      primaryKey("id", "bigint"),
                      new Column("parent_id", "bigint", true),
                      new Column("name", "text", false)),
                  List.of(new ForeignKey("category_parent_fk", "parent_id", "category", "id"))),
              new Table(
                  "tag",
                  List.of(
                      primaryKey("id", "bigint"),
                      new Column("name", "text", false, false, true, null)),
                  List.of()),
              new Table(
                  "product_tag",
                  List.of(primaryKey("product_id", "bigint"), primaryKey("tag_id", "bigint")),
                  List.of(
                      new ForeignKey(
                          "product_tag_product_fk",
                          List.of("product_id"),
                          null,
                          "product",
                          List.of("id"),
                          "CASCADE"),
                      new ForeignKey(
                          "product_tag_tag_fk",
                          List.of("tag_id"),
                          null,
                          "tag",
                          List.of("id"),
                          "CASCADE"))))),
      new Schema(
          "billing",
          List.of(new Table(
              "invoice",
              List.of(
                  primaryKey("id", "bigint"),
                  new Column("order_id", "bigint", false, false, true, null),
                  new Column("number", "text", false, false, true, null),
                  new Column("issued_at", "date", false),
                  new Column("total", "numeric(12,2)", false)),
              List.of(new ForeignKey(
                  "invoice_order_fk",
                  List.of("order_id"),
                  "public",
                  "order",
                  List.of("id"),
                  "RESTRICT"))))));

  // docs:start er-diagram
  final ErDiagram.State diagram = new ErDiagram.State();
  Map<String, ErDiagram.Pin> pins = Map.of();

  public static El view(SchemaDiagram state) {
    return erDiagram(state.diagram, SHOP, state::savePins);
  }

  // Where the app keeps the tables the user moved, to give them back with diagram.pins(...).
  void savePins(Map<String, ErDiagram.Pin> pins) {
    this.pins = pins;
  }
  // docs:end er-diagram

  private static Column primaryKey(String name, String type) {
    return new Column(name, type, false, true, false, null);
  }

  public static void main(String[] args) throws IOException {
    App<SchemaDiagram> app = new App<>(new SchemaDiagram(), SchemaDiagram::window);
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(
            SchemaDiagram.class, "Schema", Identity.TEAL, Symbol.TABLE)
        .frameless()
        .minimumSize(480, 320);
    try (Window window = Window.open("Schema", 1100, 680, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  public static El window(SchemaDiagram state) {
    Theme theme = Theme.CURRENT.get();
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(titleBar(AppIcon.appIcon(16, Identity.TEAL, Symbol.TABLE), "Schema"))
        .child(Card.flush(view(state).grow(1))
            .grow(1)
            .margin(0, theme.space().m(), theme.space().m(), theme.space().m()));
  }
}
