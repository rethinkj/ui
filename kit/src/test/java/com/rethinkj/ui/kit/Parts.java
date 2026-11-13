package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Badge.Kind;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;

/**
 * Views of the small parts, for their tests: badges in every kind and look, a breadcrumb, a
 * toolbar, properties and a popover. The state is the fields of this class, which the handlers
 * change.
 */
final class Parts {

  final Theme t;
  final Clipboard clipboard = Clipboard.inMemory();
  final Breadcrumb.State crumbs = new Breadcrumb.State();
  final Toolbar.State tools = new Toolbar.State();
  final Menu.State run = new Menu.State();
  final Popover.State filter = new Popover.State();
  final List<String> path = new ArrayList<>(
      List.of("localhost", "shop", "public", "orders_by_customer_and_month"));
  final List<String> filters = new ArrayList<>(List.of("status = paid", "total > 100"));
  final List<String> log = new ArrayList<>();
  boolean wrap;
  String column = "status";

  Parts(Theme t) {
    this.t = t;
  }

  App<Parts> app(java.util.function.Function<Parts, El> view) {
    App<Parts> app = new App<>(this, view);
    app.focusRing(t.accent().edge());
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private El page(El... content) {
    return col().wFull().hFull().padding(t.space().l()).gap(t.space().l())
        .fill(t.surface().window()).children(content);
  }

  /** Every kind quiet and strong, with an icon, at both heights, and in a selected row. */
  El badges() {
    El sheet = col().gap(t.space().m());
    for (Kind kind : Kind.values()) {
      String name = kind.name().toLowerCase(java.util.Locale.ROOT);
      sheet.child(row().gap(t.space().s()).align(Style.Align.CENTER).children(
          Badge.badge(t, kind, name),
          Badge.badge(t, name, Badge.options().kind(kind).strong()),
          Badge.badge(t, name, Badge.options().kind(kind).icon(Symbol.KEY)),
          Badge.badge(t, name, Badge.options().kind(kind).toolbar()),
          Badge.badge(t, name, Badge.options().kind(kind).toolbar().strong()
              .icon(Symbol.CHECKMARK))));
    }
    // Rows of a grid at its height, at rest and selected, with badges in them.
    Theme.Grid g = t.grid();
    El grid = col().children(
        gridRow(g.row(), "id"), gridRow(g.selected(), "customer_id"),
        gridRow(g.selectedInactive(), "created_at"));
    El filters = row().gap(t.space().s()).align(Style.Align.CENTER);
    for (String f : this.filters) {
      filters.child(Badge.badge(t, f, Badge.options().toolbar().icon(Symbol.FILTER)
          .onRemove(() -> this.filters.remove(f))).key("filter-" + f));
    }
    filters.child(Badge.badge(t, "Add filter", Badge.options().toolbar().kind(Kind.ACCENT)
        .icon(Symbol.ADD).onPress(() -> log.add("add"))).key("add"));
    El avatars = row().gap(t.space().s()).align(Style.Align.CENTER);
    for (String server : List.of("localhost", "staging", "prod-eu", "analytics", "replica 2")) {
      avatars.child(Avatar.avatar(t, server));
    }
    avatars.child(Avatar.avatar(t, "localhost", Symbol.SERVER, 20))
        .child(Avatar.avatar(t, "prod-eu", null, 32));
    return page(Card.card(t, sheet), Card.card(t, grid, filters, avatars));
  }

  private El gridRow(int fill, String name) {
    return row().h(t.grid().rowHeight()).fill(fill).padding(0, t.space().m()).gap(t.space().s())
        .align(Style.Align.CENTER).children(
            label(t, Label.Variant.MONO, name).w(110),
            Badge.badge(t, "bigint"),
            Badge.badge(t, Kind.ACCENT, "PK"),
            Badge.badge(t, Kind.WARNING, "NOT NULL"),
            Badge.badge(t, "12", Badge.options().strong()));
  }

  /** A breadcrumb and a toolbar in a card's head, over properties, with a popover's form. */
  El detail() {
    El filterButton = Button.button(t, Button.Variant.SECONDARY, Symbol.FILTER, "Filter",
        filter::toggle).key("filter");
    El form = Popover.form(t, filterButton, filter, "Filter rows",
        col().gap(t.space().m()).children(
            Form.form(t, Form.Labels.ABOVE,
                Form.field("Column", TextField.textField(t, column, "Column", v -> column = v)
                    .key("column")))),
        List.of(Button.button(t, Button.Variant.SECONDARY, "Cancel", filter::close),
            Button.button(t, Button.Variant.PRIMARY, "Apply", () -> {
              log.add("apply " + column);
              filter.close();
            }).key("apply")));
    El head = col().gap(t.space().s()).children(
        Breadcrumb.breadcrumb(t, crumbs, path, i -> {
          log.add("crumb " + path.get(i));
          path.subList(i + 1, path.size()).clear();
        }).key("crumbs"),
        row().gap(t.space().s()).align(Style.Align.CENTER).children(
            Toolbar.toolbar(t, tools, "Table", List.of(
                Toolbar.button(Symbol.ARROW_CLOCKWISE, "Refresh", () -> log.add("refresh")),
                Toolbar.split(Button.Variant.PRIMARY, Symbol.PLAY, "Run", () -> log.add("run"),
                    run, List.of(Menu.item("Run selection", () -> log.add("run selection")),
                        Menu.item("Explain", () -> log.add("explain")))),
                Toolbar.separator(),
                Toolbar.toggle(Symbol.TEXT_T, "Wrap values", wrap, on -> wrap = on),
                Toolbar.button(Symbol.COPY, "Copy", () -> log.add("copy")),
                Toolbar.spacer(),
                Toolbar.text(Symbol.ARROW_EXPORT_LTR, "Export", () -> log.add("export"))))
                .key("tools"),
            form));
    El props = Properties.groups(t, List.of(
        Properties.group("Table", List.of(
            Properties.property("Schema", "public"),
            Properties.property("Rows", "1,000,000"),
            Properties.property("Owner", "shop_admin"),
            Properties.property("Comment",
                "Every order a customer placed, by month, for the reports of the sales team"))),
        Properties.group("Storage", List.of(
            Properties.property("Size", "128 MB"),
            Properties.property("Tablespace", "pg_default").mono()))), clipboard);
    return page(Card.card(t, head), Card.card(t, props).grow(1));
  }
}
