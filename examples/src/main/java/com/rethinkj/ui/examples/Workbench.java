package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.examples.TitleBar.titleBar;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.Split.split;
import static com.rethinkj.ui.kit.Tabs.tabs;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static com.rethinkj.ui.kit.Tree.tree;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Icon.Variant;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Split.Direction;
import com.rethinkj.ui.kit.Split.Position;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The frame of a database manager, without a database: made-up servers, databases, schemas,
 * tables and columns in a tree on the left, and on the right a tab for each table opened from it,
 * with the table's rows in a data grid over a status line. Rows are made up from their index;
 * localhost/shop/public/orders has a million. The server staging answers a moment after it is
 * opened, on the app's clock. The title bar switches between the dark and the light theme.
 */
public final class Workbench implements Tree.Model<Workbench.Node> {

  enum Kind { SERVER, DATABASE, SCHEMA, TABLE, COLUMN }

  /** A node of the catalogue, told apart by identity; a table knows how many rows it has. */
  static final class Node {
    final Kind kind;
    final String name;
    final Node parent;
    final int rows;
    List<Node> children; // null until the server answers

    Node(Kind kind, String name, Node parent, int rows) {
      this.kind = kind;
      this.name = name;
      this.parent = parent;
      this.rows = rows;
      children = kind == Kind.COLUMN ? List.of() : new ArrayList<>();
      if (parent != null) {
        parent.children.add(this);
      }
    }

    String path() {
      return parent == null ? name : parent.path() + "/" + name;
    }
  }

  /** A table in a tab: its selected row and the widths of its columns. */
  static final class Opened {
    final Node table;
    int selected = -1;
    final float[] widths;

    Opened(Node table) {
      this.table = table;
      widths = new float[table.children.size()];
      for (int c = 0; c < widths.length; c++) {
        widths[c] = field(table.children.get(c).name).width();
      }
    }
  }

  private record Field(String name, float width, Align align, boolean mono) {}

  private static final List<Field> FIELDS = List.of(
      new Field("id", 80, Align.RIGHT, true), new Field("customer_id", 100, Align.RIGHT, true),
      new Field("status", 90, Align.LEFT, false), new Field("amount", 100, Align.RIGHT, true),
      new Field("currency", 80, Align.LEFT, false), new Field("email", 220, Align.LEFT, false),
      new Field("note", 160, Align.LEFT, false), new Field("created_at", 140, Align.LEFT, true),
      new Field("updated_at", 140, Align.LEFT, true),
      new Field("deleted_at", 140, Align.LEFT, true));
  private static final String[] TABLES = {
    "accounts", "addresses", "audit_log", "carts", "categories", "coupons", "customers",
    "deliveries", "employees", "events", "invoices", "invoice_lines", "orders", "order_items",
    "payments", "prices", "products", "refunds", "reviews", "sessions", "shipments",
    "stock_moves", "suppliers", "users", "warehouses"};
  private static final String[] NAMES = {
    "ada", "grace", "linus", "margaret", "ken", "barbara", "edsger", "frances", "donald", "radia"};
  private static final String[] STATUS = {"paid", "pending", "shipped", "refunded"};
  private static final LocalDateTime START = LocalDateTime.of(2026, 1, 5, 8, 0);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  static final Position SIDEBAR = Position.first(260);

  final List<Node> servers = new ArrayList<>();
  final Set<Node> open = new HashSet<>();
  final Set<Node> asked = new HashSet<>(); // servers that were asked for their databases
  Node selected;
  final List<Opened> tables = new ArrayList<>(); // the tabs after the first
  int active; // the index of the active tab, 0 for the first
  Position sidebar = SIDEBAR;
  boolean dark = true;
  App<Workbench> app; // whose clock brings the answer of staging

  Workbench() {
    Node localhost = new Node(Kind.SERVER, "localhost", null, 0);
    schema(database(localhost, "postgres"), "public", 0);
    Node shop = database(localhost, "shop");
    schema(shop, "public", 250); // the tables, then their partitions of earlier years
    schema(shop, "audit", 2);
    schema(database(localhost, "analytics"), "public", 6);
    Node prod = new Node(Kind.SERVER, "prod-eu", null, 0);
    schema(database(prod, "billing"), "public", 12);
    schema(database(prod, "shop"), "public", TABLES.length);
    Node staging = new Node(Kind.SERVER, "staging", null, 0);
    staging.children = null;
    servers.addAll(List.of(localhost, prod, staging));
    open.addAll(List.of(localhost, shop, shop.children.getFirst()));
  }

  private static Node database(Node server, String name) {
    return new Node(Kind.DATABASE, name, server, 0);
  }

  private static void schema(Node database, String name, int tables) {
    Node schema = new Node(Kind.SCHEMA, name, database, 0);
    for (int i = 0; i < tables; i++) {
      String table = TABLES[i % TABLES.length] + (i < TABLES.length ? "" : "_" + (2026 - i / 25));
      int hash = Math.floorMod((schema.path() + table).hashCode(), 1 << 20);
      boolean big = table.equals("orders") && schema.path().equals("localhost/shop/public");
      Node node = new Node(Kind.TABLE, table, schema, big ? 1_000_000 : hash % 40 * 997 + 3);
      for (int c = 0; c < 3 + hash % 8; c++) {
        new Node(Kind.COLUMN, FIELDS.get(c).name(), node, 0);
      }
    }
  }

  /** The databases of a server, a moment after it was asked. */
  void answer(Node server) {
    server.children = new ArrayList<>();
    schema(database(server, "scratch"), "public", 3);
    schema(database(server, "shop"), "public", TABLES.length);
  }

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  // Tree.Model

  @Override
  public List<Node> roots() {
    return servers;
  }

  @Override
  public List<Node> children(Node node) {
    return node.children;
  }

  @Override
  public boolean isLeaf(Node node) {
    return node.kind == Kind.COLUMN;
  }

  @Override
  public String label(Node node) {
    return node.name;
  }

  @Override
  public El icon(Node node) {
    Theme t = theme();
    return switch (node.kind) {
      case SERVER -> Icon.icon(t, Symbol.SERVER,
          node.children == null ? Variant.DISABLED : Variant.SUCCESS); // connected or not
      case DATABASE -> Icon.icon(t, Symbol.DATABASE, Variant.MUTED);
      case SCHEMA -> Icon.icon(t, Symbol.FOLDER, Variant.MUTED);
      case TABLE -> Icon.icon(t, Symbol.TABLE, Variant.MUTED);
      case COLUMN -> Icon.icon(t, node.name.equals("id") ? Symbol.KEY : Symbol.COLUMN_SINGLE,
          Variant.MUTED);
    };
  }

  @Override
  public String loading(Node node) {
    return "Connecting…";
  }

  // Handlers

  void openOrClose(Node node, boolean opens) {
    if (!opens) {
      open.remove(node);
      return;
    }
    open.add(node);
    if (node.children == null && asked.add(node)) {
      app.after(1.5, () -> answer(node)); // a real app connects on a thread of its own
    }
  }

  /** Opens the table, or the table of a column, in its tab, or chooses its tab. */
  void activate(Node node) {
    Node table = node.kind == Kind.COLUMN ? node.parent : node;
    if (table.kind != Kind.TABLE) {
      return;
    }
    for (int i = 0; i < tables.size(); i++) {
      if (tables.get(i).table == table) {
        active = i + 1;
        return;
      }
    }
    tables.add(new Opened(table));
    active = tables.size();
  }

  void close(int tab) {
    tables.remove(tab - 1);
    if (tab < active || active > tables.size()) {
      active--;
    }
  }

  public static void main(String[] args) throws IOException {
    Workbench s = new Workbench();
    App<Workbench> app = new App<>(s, Workbench::view);
    s.app = app;
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Window.options().frameless().minimumSize(640, 400);
    try (Window window = Window.open("Workbench", 1200, 720, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  // The view

  static El view(Workbench s) {
    Theme t = s.theme();
    List<Tab> tabs = new ArrayList<>(List.of(new Tab("Start", false, false)));
    s.tables.forEach(o -> tabs.add(new Tab(o.table.name)));
    El right = tabs(t, tabs, s.active, i -> s.active = i, s::close,
        s.active == 0 ? start(t) : table(t, s.tables.get(s.active - 1)));
    El catalogue = col().fill(t.surface().panel()).padding(t.space().s())
        .child(tree(t, s, s.open::contains, s.selected, n -> s.selected = n, s::openOrClose,
            s::activate).key("catalogue").label("Catalogue").grow(1));
    return col().wFull().hFull().fill(t.surface().window())
        .onKey(right.onKey()) // the tabs' keys also while the tree has the focus
        .child(titleBar(t, "Workbench", t.accent().color(),
            toggle(t, s.dark, "Dark", () -> s.dark = !s.dark)))
        .child(split(t, Direction.ROW, s.sidebar, SIDEBAR, p -> s.sidebar = p,
            catalogue, 160, right.key("tabs"), 320).grow(1));
  }

  private static El start(Theme t) {
    return col().padding(t.space().xxl()).gap(t.space().m())
        .child(Label.label(t, Label.Variant.HEADING, "The frame of a database manager"))
        .child(Label.label(t, Label.Variant.MUTED,
            "Open a table from the catalogue: double-click it, or select it and press Enter."))
        .child(Label.label(t, Label.Variant.MUTED,
            "Control+Tab and Control+Shift+Tab go through the tabs; Control+W closes one."))
        .child(Label.label(t, Label.Variant.MUTED, "staging answers a moment after it is opened."));
  }

  private static El table(Theme t, Opened o) {
    List<Column> columns = new ArrayList<>();
    for (int c = 0; c < o.widths.length; c++) {
      Field f = field(o.table.children.get(c).name);
      columns.add(new Column(f.name(), o.widths[c], f.align(), f.mono(), i -> cell(f.name(), i)));
    }
    String status = String.format(Locale.ROOT, "%s · %,d rows", o.table.path(), o.table.rows)
        + (o.selected < 0 ? "" : String.format(Locale.ROOT, " · row %,d selected",
            o.selected + 1));
    // No smaller than its content otherwise, which is the grid's rows: min-height: 0.
    return col().minSize(0, 0)
        .child(dataGrid(t, columns, o.table.rows, o.selected, i -> o.selected = i,
            (c, w) -> o.widths[c] = w).key("grid-" + o.table.path()).label(o.table.name).grow(1))
        .child(row().h(t.size().control()).shrink(0).padding(0, t.space().m())
            .align(Style.Align.CENTER)
            .child(Label.label(t, Label.Variant.MUTED, status).key("status")));
  }

  private static Field field(String name) {
    return FIELDS.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
  }

  /** The text of a cell, made up from the column and the row; null is SQL NULL. */
  static String cell(String column, int row) {
    LocalDateTime created = START.plusSeconds(row * 37L);
    return switch (column) {
      case "id" -> String.valueOf(row + 1);
      case "customer_id" -> String.valueOf(1 + row * 7919L % 50_000);
      case "status" -> STATUS[row * 7 % STATUS.length];
      case "amount" -> String.format(Locale.ROOT, "%d.%02d", row * 389 % 2000, row * 13 % 100);
      case "currency" -> row % 5 == 0 ? "USD" : "EUR";
      case "email" -> NAMES[row % 10] + "." + NAMES[row / 10 % 10] + "@example.com";
      case "note" -> row % 6 == 0 ? "Leave at the door" : null;
      case "created_at" -> created.format(TIME);
      case "updated_at" -> row % 3 == 0 ? null : created.plusMinutes(row % 4320).format(TIME);
      default -> row % 11 == 0 ? created.plusDays(30).format(TIME) : null;
    };
  }
}
