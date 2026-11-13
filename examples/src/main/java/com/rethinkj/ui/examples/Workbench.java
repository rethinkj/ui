package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.examples.TitleBar.titleBar;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.Split.split;
import static com.rethinkj.ui.kit.Tabs.tabs;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static com.rethinkj.ui.kit.Tree.tree;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
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
import com.rethinkj.ui.window.WindowIcon;
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
 * The frame of a database manager over made-up servers that answer slowly: servers, databases,
 * schemas, tables and columns in a tree on the left, a tab for each table opened from it on the
 * right with the table's rows in a data grid, and a status line below. localhost is connected from
 * the start; opening staging connects to it, which takes three times {@link #latency}, and prod-eu
 * refuses to connect. Opening a table asks for its number of rows, which takes the latency too.
 * Each question runs on a thread of its own ({@link App#background}); meanwhile the tree shows
 * "Connecting…" and the tab "Loading…", and the window moves and scrolls. Closing a tab cancels
 * the question of its table, whose answer then never arrives. The rows are made up from their
 * index; localhost/shop/public/orders has a million. A message in the status line goes after a
 * few seconds on the app's clock ({@link App#after}), and the title bar switches between the dark
 * and the light theme. The taskbar button counts the questions on their way, and asks for
 * attention when an answer arrives while the window is in the background.
 *
 * <p>The work gets the names it asks about, never the nodes: they are the app's state, which only
 * the handlers touch, on the app's thread. Under a script the latency is 0, and the script's
 * {@code await} lets the answers in where it says.
 */
public final class Workbench implements Tree.Model<Workbench.Node> {

  enum Kind { SERVER, DATABASE, SCHEMA, TABLE, COLUMN }

  /** A node of the catalogue, told apart by identity. */
  static final class Node {
    final Kind kind;
    final String name;
    final Node parent;
    List<Node> children; // null until the server answers

    Node(Kind kind, String name, Node parent) {
      this.kind = kind;
      this.name = name;
      this.parent = parent;
      children = kind == Kind.COLUMN ? List.of() : new ArrayList<>();
      if (parent != null) {
        parent.children.add(this);
      }
    }

    String path() {
      return parent == null ? name : parent.path() + "/" + name;
    }
  }

  /** A table in a tab: its number of rows once the server said, its selection and widths. */
  static final class Opened {
    final Node table;
    int rows = -1; // until the server answers
    Work asking; // the question for the rows, cancelled with the tab
    final Selection selection = new Selection(Mode.ROWS);
    final float[] widths;

    Opened(Node table) {
      this.table = table;
      widths = new float[table.children.size()];
      for (int c = 0; c < widths.length; c++) {
        widths[c] = field(table.children.get(c).name).width();
      }
    }
  }

  /** A column as the database describes it: its kind and the name of its type. */
  private record Field(String name, float width, DataType kind, String type) {}

  private static final List<Field> FIELDS = List.of(
      new Field("id", 100, DataType.INTEGER, "bigint"),
      new Field("customer_id", 160, DataType.INTEGER, "integer"),
      new Field("status", 150, DataType.ENUM, "order_status"),
      new Field("amount", 170, DataType.DECIMAL, "numeric(10,2)"),
      new Field("paid", 110, DataType.BOOLEAN, "boolean"),
      new Field("email", 220, DataType.TEXT, "varchar(255)"),
      new Field("note", 160, DataType.TEXT, "text"),
      new Field("created_at", 190, DataType.DATE_TIME, "timestamptz"),
      new Field("updated_at", 190, DataType.DATE_TIME, "timestamptz"),
      new Field("deleted_at", 190, DataType.DATE_TIME, "timestamptz"));
  // The icons of the cells: constants, so that a cell costs no allocation for its icon.
  private static final CellIcon LINK =
      CellIcon.before(Symbol.LINK, Variant.MUTED).tooltip("References customers.id");
  private static final CellIcon PAID = CellIcon.instead(Symbol.CHECKMARK, Variant.SUCCESS);
  private static final CellIcon UNPAID = CellIcon.instead(Symbol.SUBTRACT, Variant.MUTED);
  private static final CellIcon[] MARKS = { // paid, pending, shipped, refunded
    CellIcon.before(Symbol.RECORD, Variant.SUCCESS), CellIcon.before(Symbol.RECORD, Variant.WARNING),
    CellIcon.before(Symbol.RECORD, Variant.NORMAL), CellIcon.before(Symbol.RECORD, Variant.DANGER)};
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
  static final double MESSAGE_SECONDS = 5; // how long a message stays in the status line

  Duration latency = Duration.ofMillis(800); // of every answer of the made-up servers
  final List<Node> servers = new ArrayList<>();
  final Set<Node> open = new HashSet<>();
  final Set<Node> connecting = new HashSet<>(); // servers whose answer is on its way
  Node selected;
  final List<Opened> tables = new ArrayList<>(); // the tabs after the first
  int active; // the index of the active tab, 0 for the first
  Position sidebar = SIDEBAR;
  boolean dark = true;
  String message; // in the status line for a while, or null
  int messages; // told so far
  App<Workbench> app; // runs the work and the clock
  Window window; // the window it runs in, for the taskbar; null under a script

  Workbench() {
    Node localhost = new Node(Kind.SERVER, "localhost", null);
    schema(database(localhost, "postgres"), "public", 0);
    Node shop = database(localhost, "shop");
    schema(shop, "public", 250); // the tables, then their partitions of earlier years
    schema(shop, "audit", 2);
    schema(database(localhost, "analytics"), "public", 6);
    servers.add(localhost);
    for (String name : List.of("staging", "prod-eu")) {
      Node server = new Node(Kind.SERVER, name, null);
      server.children = null; // not connected
      servers.add(server);
    }
    open.addAll(List.of(localhost, shop, shop.children.getFirst()));
  }

  private static Node database(Node server, String name) {
    return new Node(Kind.DATABASE, name, server);
  }

  private static void schema(Node database, String name, int tables) {
    Node schema = new Node(Kind.SCHEMA, name, database);
    for (int i = 0; i < tables; i++) {
      String table = TABLES[i % TABLES.length] + (i < TABLES.length ? "" : "_" + (2026 - i / 25));
      Node node = new Node(Kind.TABLE, table, schema);
      for (int c = 0; c < 3 + Math.floorMod(node.path().hashCode(), 8); c++) {
        new Node(Kind.COLUMN, FIELDS.get(c).name(), node);
      }
    }
  }

  // The made-up servers, on the work's threads: they get names and give names and numbers.

  /** Connects to a server and asks for its databases. */
  static List<String> connect(String server, Duration latency) throws Exception {
    answerAfter(server, latency);
    if (server.equals("prod-eu")) {
      throw new ConnectException("connection refused");
    }
    return List.of("scratch", "shop");
  }

  /** {@code SELECT count(*)} of the table at {@code path}. */
  static int count(String path, Duration latency) throws InterruptedException {
    answerAfter(path, latency);
    return path.equals("localhost/shop/public/orders")
        ? 1_000_000 : Math.floorMod(path.hashCode(), 40) * 997 + 3;
  }

  private static void answerAfter(String path, Duration latency) throws InterruptedException {
    Thread.sleep(path.startsWith("staging") ? latency.multipliedBy(3) : latency);
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

  // Handlers, on the app's thread

  void openOrClose(Node node, boolean opens) {
    if (!opens) {
      open.remove(node);
      return;
    }
    open.add(node);
    if (node.children == null && connecting.add(node)) {
      String server = node.name;
      Duration wait = latency;
      app.background("databases of " + server, () -> connect(server, wait),
          names -> {
            connecting.remove(node);
            node.children = new ArrayList<>();
            names.forEach(name -> schema(database(node, name), "public",
                name.equals("shop") ? TABLES.length : 3));
            tell(server + ": " + names.size() + " databases");
            answered();
          },
          error -> {
            connecting.remove(node);
            open.remove(node); // opening it again asks again
            tell(server + ": " + error.getMessage());
            answered();
          });
      taskbar();
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
    Opened o = new Opened(table);
    String path = table.path();
    Duration wait = latency;
    o.asking = app.background("rows of " + path, () -> count(path, wait),
        rows -> {
          o.rows = rows;
          o.asking = null;
          answered();
        },
        error -> {
          o.asking = null;
          tell(path + ": " + error.getMessage());
          answered();
        });
    tables.add(o);
    active = tables.size();
    taskbar();
  }

  void close(int tab) {
    Opened o = tables.remove(tab - 1);
    if (o.asking != null) {
      o.asking.cancel(); // its answer is not wanted any more
    }
    taskbar();
    if (tab < active || active > tables.size()) {
      active--;
    }
  }

  /** The questions on their way: servers connecting and tables counting their rows. */
  int running() {
    return connecting.size() + (int) tables.stream().filter(o -> o.asking != null).count();
  }

  /** Shows the number of questions on their way on the taskbar button, or nothing for none. */
  void taskbar() {
    if (window == null) {
      return;
    }
    int n = running();
    Theme t = theme();
    WindowIcon badge = WindowIcon.of(size -> AppIcon.count(t, size, t.accent().color(), n));
    window.badge(n == 0 ? null : badge,
        n == 1 ? "1 question running" : n + " questions running");
  }

  /** An answer arrived: the badge counts one less, and a window in the background asks to look. */
  void answered() {
    taskbar();
    if (window != null && !window.isFocused()) {
      window.attention(Window.Attention.UNTIL_FOCUSED);
    }
  }

  /** Shows a message in the status line for {@link #MESSAGE_SECONDS}, on the app's clock. */
  void tell(String text) {
    message = text;
    int told = ++messages;
    app.after(MESSAGE_SECONDS, () -> {
      if (messages == told) { // not replaced meanwhile
        message = null;
      }
    });
  }

  public static void main(String[] args) throws IOException {
    Workbench s = new Workbench();
    App<Workbench> app = new App<>(s, Workbench::view);
    s.app = app;
    // A script's images are stored, so they must not depend on the platform; its await waits for
    // the answers, which need not take long.
    app.textRendering(TextRendering.LINUX);
    s.latency = Duration.ZERO;
    if (Script.handle(args, app)) {
      return;
    }
    s.latency = Duration.ofMillis(800);
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(Workbench.class, "Workbench", 0xFF0F766E,
        Symbol.DATABASE).frameless().minimumSize(640, 400);
    try (Window window = Window.open("Workbench", 1200, 720, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      s.window = window;
      WindowLoop.run(window, app, presenter);
    }
  }

  // The view

  static El view(Workbench s) {
    Theme t = s.theme();
    List<Tab> tabs = new ArrayList<>(List.of(new Tab("Start", false, false)));
    s.tables.forEach(o -> tabs.add(new Tab(o.table.name)));
    El right = tabs(t, tabs, s.active, i -> s.active = i, s::close,
        s.active == 0 ? start(t) : table(t, s, s.tables.get(s.active - 1)));
    El catalogue = col().fill(t.surface().panel()).padding(t.space().s())
        .child(tree(t, s, s.open::contains, s.selected, n -> s.selected = n, s::openOrClose,
            s::activate).key("catalogue").label("Catalogue").grow(1));
    return col().wFull().hFull().fill(t.surface().window())
        .onKey(right.onKey()) // the tabs' keys also while the tree has the focus
        .child(titleBar(t, "Workbench", t.accent().color(),
            toggle(t, s.dark, "Dark", () -> s.dark = !s.dark)))
        .child(split(t, Direction.ROW, s.sidebar, SIDEBAR, p -> s.sidebar = p,
            catalogue, 160, right.key("tabs"), 320).grow(1))
        .child(Label.label(t, Label.Variant.MUTED, s.status()).key("status")
            .padding(t.space().s(), t.space().l()));
  }

  /** The message, or else what the active tab shows. */
  String status() {
    if (message != null) {
      return message;
    }
    if (active == 0) {
      return selected == null ? "" : selected.path();
    }
    Opened o = tables.get(active - 1);
    if (o.rows < 0) {
      return o.table.path() + " · counting the rows";
    }
    long chosen = o.selection.rowCount();
    return String.format(Locale.ROOT, "%s · %,d rows", o.table.path(), o.rows)
        + (chosen == 0 ? "" : chosen == 1
            ? String.format(Locale.ROOT, " · row %,d selected", o.selection.row() + 1)
            : String.format(Locale.ROOT, " · %,d rows selected", chosen));
  }

  private static El start(Theme t) {
    return col().padding(t.space().xxl()).gap(t.space().m())
        .child(Label.label(t, Label.Variant.HEADING, "The frame of a database manager"))
        .child(Label.label(t, Label.Variant.MUTED,
            "Open a table from the catalogue: double-click it, or select it and press Enter."))
        .child(Label.label(t, Label.Variant.MUTED,
            "Control+Tab and Control+Shift+Tab go through the tabs; Control+W closes one."))
        .child(Label.label(t, Label.Variant.MUTED,
            "staging answers slowly, and prod-eu refuses to connect."));
  }

  private static El table(Theme t, Workbench s, Opened o) {
    if (o.rows < 0) {
      return col().padding(t.space().xxl())
          .child(Label.label(t, Label.Variant.MUTED,
              "Loading the rows of " + o.table.path() + "…"));
    }
    List<Column> columns = new ArrayList<>();
    for (int c = 0; c < o.widths.length; c++) {
      Field f = field(o.table.children.get(c).name);
      Column column = new Column(f.name(), o.widths[c], f.kind(), i -> cell(f.name(), i))
          .typeName(f.type());
      columns.add(switch (f.name()) {
        case "id" -> column.icon(Symbol.KEY, Variant.WARNING);
        case "customer_id" -> column.icon(Symbol.LINK).cellIcon(i -> LINK);
        case "status" -> column.cellIcon(i -> MARKS[i * 7 % STATUS.length]);
        case "paid" -> column.cellIcon(i -> i % 4 == 3 ? UNPAID : PAID);
        default -> column;
      });
    }
    return dataGrid(t, columns, o.rows, DataGrid.options()
            .select(o.selection)
            .onResize((c, w) -> o.widths[c] = w)
            .copy(s.app.clipboard()))
        .key("grid-" + o.table.path()).label(o.table.name);
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
      case "paid" -> String.valueOf(row % 4 != 3);
      case "amount" -> String.format(Locale.ROOT, "%d.%02d", row * 389 % 2000, row * 13 % 100);
      case "email" -> NAMES[row % 10] + "." + NAMES[row / 10 % 10] + "@example.com";
      case "note" -> row % 6 == 0 ? "Leave at the door" : null;
      case "created_at" -> created.format(TIME);
      case "updated_at" -> row % 3 == 0 ? null : created.plusMinutes(row % 4320).format(TIME);
      default -> row % 11 == 0 ? created.plusDays(30).format(TIME) : null;
    };
  }
}
