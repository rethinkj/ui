package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.examples.TitleBar.titleBar;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.Label.Variant.MUTED;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Split.split;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Icon.Variant;
import com.rethinkj.ui.kit.Split.Direction;
import com.rethinkj.ui.kit.Split.Position;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The schema browser of a database tool over made-up servers that answer slowly: a tree of
 * servers, databases, schemas, tables and columns beside the rows of the selected table. The tree
 * asks for a node's children when it is opened, and selecting a table asks for its rows, each on
 * a thread of its own ({@link App#background}); every answer takes {@link #latency}, staging's
 * three times as long, and prod-eu refuses to connect. Meanwhile the tree shows "Loading…" where
 * the children will be, and the window stays responsive: it moves, the tree and the rows scroll,
 * other nodes open. Selecting another table cancels the rows asked for before, whose answer then
 * never arrives.
 *
 * <p>The work gets the names it asks about, never the nodes: they are the app's state, which only
 * the handlers touch, on the app's thread. Under a script the latency is 0, and the script's
 * {@code await} lets the answers in where it says.
 */
public final class SchemaBrowser {

  private static final String[] KINDS = {"servers", "databases", "schemas", "tables", "columns"};
  private static final List<String> DATABASES = List.of("analytics", "postgres", "shop");
  private static final List<String> SCHEMAS = List.of("audit", "public");
  private static final List<String> TABLES = List.of(
      "accounts", "addresses", "carts", "categories", "coupons", "customers", "deliveries",
      "discounts", "employees", "invoices", "inventory", "orders", "order_items", "payments",
      "prices", "products", "refunds", "reviews", "sessions", "shipments", "suppliers", "taxes",
      "users", "warehouses");
  private static final List<String> COLUMNS = List.of(
      "id", "name", "status", "amount", "created_at", "updated_at", "note");
  private static final String[] NAMES = {"Ada", "Grace", "Linus", "Margaret", "Ken", "Barbara"};
  private static final String[] STATUS = {"paid", "pending", "shipped"};
  private static final LocalDateTime START = LocalDateTime.of(2026, 1, 5, 8, 0);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  static final int ROWS = 1000;

  /** A node of the catalog: a server, database, schema, table or column, by its level. */
  static final class Node {
    final int level;
    final String name;
    final Node parent;
    List<Node> children; // null until they have arrived

    Node(int level, String name, Node parent) {
      this.level = level;
      this.name = name;
      this.parent = parent;
    }

    String path() {
      return parent == null ? name : parent.path() + "/" + name;
    }
  }

  /** What a query for a table's rows answers: the names of its columns and the rows. */
  record Rows(List<String> columns, List<String[]> rows) {}

  Duration latency = Duration.ofMillis(800); // of every answer of the made-up servers
  App<SchemaBrowser> app; // runs the work
  final List<Node> roots = new ArrayList<>();
  final Set<Node> open = new HashSet<>();
  final Set<Node> loading = new HashSet<>(); // whose children are on their way
  Node selected;
  Position split = Position.first(240);
  Node table; // whose rows are shown, or on their way
  Rows rows; // null while they are on their way
  Work asking; // the query for them, cancelled when another table is selected
  final Selection selection = new Selection(Mode.ROWS);
  String status = "Open a server to see its databases.";

  SchemaBrowser() {
    for (String server : List.of("localhost", "staging", "prod-eu")) {
      roots.add(new Node(0, server, null));
    }
  }

  // The made-up servers, on the work's threads: they get names and give names and text.

  /** What the server asks for the children of the node at {@code path} on this level. */
  static List<String> children(String path, int level, Duration latency) throws Exception {
    answerAfter(path, latency);
    if (path.startsWith("prod-eu")) {
      throw new ConnectException("connection refused");
    }
    return switch (level) {
      case 1 -> DATABASES;
      case 2 -> SCHEMAS;
      case 3 -> TABLES;
      default -> columns(path.substring(path.lastIndexOf('/') + 1));
    };
  }

  /** {@code SELECT * FROM <table> LIMIT 1000} on the made-up server. */
  static Rows query(String path, Duration latency) throws Exception {
    answerAfter(path, latency);
    List<String> columns = columns(path.substring(path.lastIndexOf('/') + 1));
    List<String[]> rows = new ArrayList<>();
    for (int i = 0; i < ROWS; i++) {
      String[] cells = new String[columns.size()];
      for (int c = 0; c < cells.length; c++) {
        cells[c] = cell(columns.get(c), i);
      }
      rows.add(cells);
    }
    return new Rows(columns, rows);
  }

  private static void answerAfter(String path, Duration latency) throws InterruptedException {
    Thread.sleep(path.startsWith("staging") ? latency.multipliedBy(3) : latency);
  }

  static List<String> columns(String table) {
    return COLUMNS.subList(0, 3 + Math.floorMod(table.hashCode(), COLUMNS.size() - 2));
  }

  static String cell(String column, int i) {
    return switch (column) {
      case "id" -> String.valueOf(i + 1);
      case "name" -> NAMES[i % NAMES.length];
      case "status" -> STATUS[i * 7 % STATUS.length];
      case "amount" -> String.format(Locale.ROOT, "%d.%02d", i * 37 % 900, i * 13 % 100);
      case "created_at" -> START.plusMinutes(i * 97L).format(TIME);
      case "updated_at" -> i % 3 == 0 ? null : START.plusMinutes(i * 131L).format(TIME);
      default -> i % 4 == 0 ? "Call before delivery" : null;
    };
  }

  // Handlers, on the app's thread

  void toggle(Node node, boolean opens) {
    if (!opens) {
      open.remove(node);
      return;
    }
    open.add(node);
    if (node.children == null && loading.add(node)) {
      String path = node.path();
      int level = node.level + 1;
      Duration wait = latency;
      app.background(KINDS[level] + " of " + path, () -> children(path, level, wait),
          names -> {
            loading.remove(node);
            node.children = names.stream().map(name -> new Node(level, name, node)).toList();
            status = path + ": " + names.size() + " " + KINDS[level];
          },
          error -> {
            loading.remove(node);
            open.remove(node); // opening it again asks again
            status = path + ": " + error.getMessage();
          });
    }
  }

  void select(Node node) {
    selected = node;
    if (node.level != 3 || node == table) {
      return;
    }
    if (asking != null) {
      asking.cancel(); // the rows of the table selected before are not wanted any more
    }
    table = node;
    rows = null;
    selection.clear();
    String path = node.path();
    Duration wait = latency;
    asking = app.background("rows of " + path, () -> query(path, wait),
        answer -> {
          rows = answer;
          asking = null;
          status = node.name + ": " + answer.rows().size() + " rows";
        },
        error -> {
          asking = null;
          status = node.name + ": " + error.getMessage();
        });
  }

  public static void main(String[] args) throws IOException {
    SchemaBrowser browser = new SchemaBrowser();
    App<SchemaBrowser> app = new App<>(browser, SchemaBrowser::view);
    browser.app = app;
    // A script's images are stored, so they must not depend on the platform; its await waits for
    // the answers, which need not take long.
    app.textRendering(TextRendering.LINUX);
    browser.latency = Duration.ZERO;
    if (Script.handle(args, app)) {
      return;
    }
    browser.latency = Duration.ofMillis(800);
    app.textRendering(TextRendering.platform());
    Window.Options options = Window.options().frameless().minimumSize(480, 320);
    try (Window window = Window.open("Schema", 900, 560, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  // The view

  static El view(SchemaBrowser s) {
    Theme t = Theme.DARK;
    El tree = Tree.tree(t, new Model(t, s), s.open::contains, s.selected, s::select, s::toggle,
        null).key("schema").label("Schema").grow(1);
    El schema = col().fill(t.surface().panel()).padding(t.space().m()).child(tree);
    El content = col().padding(t.space().l()).gap(t.space().m());
    if (s.table == null) {
      content.child(label(t, MUTED, "Select a table to see its rows."));
    } else if (s.rows == null) {
      content.child(label(t, MUTED, "Loading the rows of " + s.table.path() + "…"));
    } else {
      Rows r = s.rows;
      List<Column> columns = new ArrayList<>();
      for (int c = 0; c < r.columns().size(); c++) {
        String name = r.columns().get(c);
        boolean text = name.equals("name") || name.equals("status") || name.equals("note");
        int index = c;
        columns.add(new Column(name, name.endsWith("_at") ? 140 : name.equals("note") ? 180 : 90,
            text ? Align.LEFT : Align.RIGHT, !text, false, i -> r.rows().get(i)[index]));
      }
      content.child(label(t, s.table.path()))
          .child(dataGrid(t, columns, r.rows().size(), DataGrid.options().select(s.selection))
              .key("rows").label("Rows").grow(1));
    }
    return col().wFull().hFull().fill(t.surface().window())
        .child(titleBar(t, "Schema", t.accent().color()))
        // No smaller than its content otherwise, which is as tall as its rows: min-height: 0.
        .child(split(t, Direction.ROW, s.split, Position.first(240), p -> s.split = p,
            schema, 160, content, 240).key("split").grow(1).minSize(0, 0))
        .child(label(t, MUTED, s.status).key("status").padding(t.space().s(), t.space().l()));
  }

  /** The catalog as the tree asks for it, with an icon per level. */
  private record Model(Theme t, SchemaBrowser s) implements Tree.Model<Node> {

    @Override
    public List<Node> roots() {
      return s.roots;
    }

    @Override
    public List<Node> children(Node node) {
      return node.children;
    }

    @Override
    public boolean isLeaf(Node node) {
      return node.level == 4;
    }

    @Override
    public String label(Node node) {
      return node.name;
    }

    @Override
    public El icon(Node node) {
      return switch (node.level) {
        // The colour says whether the server answered.
        case 0 -> Icon.icon(t, Symbol.SERVER,
            node.children == null ? Variant.DISABLED : Variant.SUCCESS);
        case 1 -> Icon.icon(t, Symbol.DATABASE, Variant.MUTED);
        case 2 -> Icon.icon(t, Symbol.FOLDER, Variant.MUTED);
        case 3 -> Icon.icon(t, Symbol.TABLE, Variant.MUTED);
        default -> Icon.icon(t, Symbol.COLUMN_SINGLE, Variant.MUTED);
      };
    }
  }
}
