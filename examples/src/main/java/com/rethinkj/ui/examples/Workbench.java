package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Tone.DANGER;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.CodeEditor.codeEditor;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.TitleBar.titleBar;
import static com.rethinkj.ui.kit.TitleBar.withMenus;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static com.rethinkj.ui.kit.Tree.tree;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.Find;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Icon.Variant;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Split.Direction;
import com.rethinkj.ui.kit.Split.Position;
import com.rethinkj.ui.kit.Sql;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowIcon;
import com.rethinkj.ui.window.WindowLoop;
import com.rethinkj.ui.window.Windows;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The frame of a database manager over made-up servers that answer slowly: servers, databases,
 * schemas, tables and columns in a tree on the left, a tab for each table opened from it on the
 * right with the table's rows in a data grid, and a status line in the footer of the tabs' card. localhost is connected from
 * the start; opening staging connects to it, which takes three times {@link #latency}, and prod-eu
 * refuses to connect. Opening a table asks for its number of rows, which takes the latency too.
 * Each question runs on a thread of its own ({@link App#background}); meanwhile the tree shows
 * "Connecting…" and the tab "Loading…", and the window moves and scrolls. Closing a tab cancels
 * the question of its table, whose answer then never arrives. The rows are made up from their
 * index; localhost/shop/public/orders has a million. A message in the status line goes after a
 * few seconds on the app's clock ({@link App#after}), and the title bar switches between the dark
 * and the light theme. A right click on a node selects it and opens its menu: open it, copy its
 * name, and on a table "Drop table…", which asks in a dialog for the table's name and then drops
 * nothing, as nothing here is real. Above a table's rows, a query area ({@link
 * com.rethinkj.ui.kit.TextField#codeArea}) holds its statement; Control+Enter runs it, which asks
 * for the number of rows again, at most as many as its {@code LIMIT} says, on a thread of its own.
 * The taskbar button counts the questions on their way, and asks for attention when an answer
 * arrives while the windows are in the background.
 *
 * <p>The tabs move and tear off ({@link TabGroups}): a tab dragged out of the bar gets a window
 * of its own, the workbench in small, with its title bar, the tabs and the tab's status line, and
 * goes back into any bar it is dropped on; its menu does the same without dragging. A tab keeps
 * its statement with caret, selection, scroll and history ({@link CodeEditor.State}), its
 * completions and its selection in the grid in its own state, so that it comes to another window
 * as it was, and an answer on its way arrives in whichever window the tab is in then. Closing a
 * tab's window puts its tabs back into the first window; closing the first closes them all.
 *
 * <p>The work gets the names it asks about, never the nodes: they are the app's state, which only
 * the handlers touch, on the app's thread. Under a script the latency is 0, and the script's
 * {@code await} lets the answers in where it says.
 */
public final class Workbench implements Tree.Model<Workbench.Node> {

  enum Kind {
    SERVER,
    DATABASE,
    SCHEMA,
    TABLE,
    COLUMN
  }

  /** A node of the catalog, told apart by identity. */
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
    final Node table; // null for the Start tab
    int rows = -1; // until the server answers
    int answered = -1; // the rows of the last answer, which a statement that fails leaves shown
    Work asking; // the question for the rows, cancelled with the tab or by the next one
    String query; // the statement in the query area
    final CodeEditor.State editor = new CodeEditor.State(); // its caret, history and diagnostics
    final Completion.State completion = new Completion.State();
    String failure; // what the server said was wrong with the statement, until the next edit
    final Selection selection = new Selection(Mode.CELLS);
    final DataGrid.Commands gridCommands = new DataGrid.Commands();
    final float[] widths;
    final TableRows data; // the rows shown, as the server has them and the filters keep them
    final GridChanges changes; // the edits of the rows until Commit
    final DataGrid.Layout layout; // the columns shown, in their order
    List<Filter> filters = List.of();
    final Menu.State gridMenu = new Menu.State();
    Work committing; // the changes on their way to the server
    boolean inGrid; // the grid had the focus last rather than the query: what Edit acts on

    /** The Start tab, which says how to open a table. */
    Opened() {
      table = null;
      query = "";
      widths = new float[0];
      data = null;
      changes = null;
      layout = null;
    }

    Opened(Node table) {
      this(table, new TableRows.Stored());
    }

    /** A table's tab, over what the server has written to it. */
    Opened(Node table, TableRows.Stored stored) {
      this.table = table;
      data = new TableRows(stored);
      changes = new GridChanges(data);
      layout =
          new DataGrid.Layout(table.children.stream().map(column -> column.name).toList());
      query = "SELECT *\nFROM " + table.parent.parent.name + "." + table.parent.name + "."
          + table.name + ";";
      widths = new float[table.children.size()];
      for (int column = 0; column < widths.length; column++) {
        widths[column] = field(table.children.get(column).name).width();
      }
    }

    /** Keeps the rows that pass these filters of the columns, from the first. */
    void filter(List<Filter> next) {
      filters = next;
      data.filters = next;
      data.refresh();
      selection.reset();
    }

    /** The title of its tab, also of a window it has alone. */
    String title() {
      return table == null ? "Start" : table.name;
    }

    @Override
    public String toString() {
      return title();
    }
  }

  /** A column as the database describes it: its kind and the name of its type. */
  record Field(String name, float width, DataType kind, String type) {}

  static final List<Field> FIELDS = List.of(
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
    CellIcon.before(Symbol.RECORD, Variant.SUCCESS),
        CellIcon.before(Symbol.RECORD, Variant.WARNING),
    CellIcon.before(Symbol.RECORD, Variant.NORMAL), CellIcon.before(Symbol.RECORD, Variant.DANGER)
  };
  private static final String[] TABLES = {
    "accounts", "addresses", "audit_log", "carts", "categories", "coupons", "customers",
    "deliveries", "employees", "events", "invoices", "invoice_lines", "orders", "order_items",
    "payments", "prices", "products", "refunds", "reviews", "sessions", "shipments",
    "stock_moves", "suppliers", "users", "warehouses"
  };
  private static final String[] NAMES = {
    "ada", "grace", "linus", "margaret", "ken", "barbara", "edsger", "frances", "donald", "radia"
  };
  private static final String[] STATUS = {"paid", "pending", "shipped", "refunded"};
  private static final LocalDateTime START = LocalDateTime.of(2026, 1, 5, 8, 0);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  static final Position SIDEBAR = Position.first(260);
  static final int MARK = Identity.TEAL; // the app's color, in its icon
  static final float TAB_WIDTH = 760; // a tab's window
  static final float TAB_HEIGHT = 480;
  static final double MESSAGE_SECONDS = 5; // how long a message stays in the status line
  // What the made-up server reads of a statement: how many rows it asks for.
  private static final Pattern LIMIT =
      Pattern.compile("\\bLIMIT\\s+(\\d{1,9})\\b", Pattern.CASE_INSENSITIVE);
  // And which table: the name after FROM, of one to three parts.
  private static final Pattern FROM =
      Pattern.compile("\\bFROM\\s+(\\w+(?:\\.\\w+){0,2})", Pattern.CASE_INSENSITIVE);
  private static final int SUGGESTIONS = 50; // the most completions for a word

  Duration latency = Duration.ofMillis(800); // of every answer of the made-up servers
  final List<Node> servers = new ArrayList<>();
  final Set<Node> open = new HashSet<>();
  final Set<Node> connecting = new HashSet<>(); // servers whose answer is on its way
  final Set<Node> refused = new HashSet<>(); // servers whose last answer was a failure
  Node selected;
  final Opened start = new Opened();
  // docs:start workbench-groups
  // The tabs of every window: the first window's, Start among them, and those torn off.
  final TabGroups<Opened> groups = new TabGroups<>(List.of(start), this::openWindow)
      .onClosed(this::closed)
      .onCloseTab(this::closeTab);
  // docs:end workbench-groups
  Position sidebar = SIDEBAR;
  boolean dark = true;
  String message; // in the status line for a while, or null
  int messages; // told so far
  App<Workbench> app; // runs the work and the clock
  final Menu.State menu = new Menu.State(); // the tree's context menu, for the selected node
  Node dropping; // the table the dialog asks about, or null while it is closed
  String confirm = ""; // the name typed into the dialog
  Window window; // the first window, for the taskbar; null under a script
  Path placementFile; // where main keeps the first window's placement; null under a script
  final MenuBar.State bar = new MenuBar.State();
  // The command palette of each window, and what it finds after @, made anew when a server
  // connects.
  final Map<TabGroups.Group<Opened>, CommandPalette.State> palettes = new IdentityHashMap<>();
  private List<FilterList.Entry> catalog;
  private int connected; // servers that answered so far, which the catalog is made for
  private int cataloged = -1;
  // What the made-up servers have written to their tables, by path, which every tab of a table
  // shows.
  final Map<String, TableRows.Stored> stored = new HashMap<>();
  Question question; // the dialog that asks about changes not committed, or null
  Opened previewing; // the tab whose changes the preview shows, or null
  final CodeEditor.State preview = new CodeEditor.State();

  /**
   * What the dialog about changes not committed asks before something goes on in a window.
   *
   * @param doing what goes on, as in "before you close the tab"
   */
  record Question(TabGroups.Group<Opened> group, List<Opened> tabs, String doing, Runnable then) {}

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
    for (int index = 0; index < tables; index++) {
      String table =
          TABLES[index % TABLES.length] + (index < TABLES.length ? "" : "_" + (2026 - index / 25));
      Node node = new Node(Kind.TABLE, table, schema);
      for (int column = 0; column < 3 + Math.floorMod(node.path().hashCode(), 8); column++) {
        new Node(Kind.COLUMN, FIELDS.get(column).name(), node);
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

  /** What a made-up server answers to a statement about a table it does not have. */
  static final class NoSuchTable extends Exception {
    NoSuchTable(String name) {
      super("relation \"" + name + "\" does not exist");
    }
  }

  /**
   * {@code SELECT count(*)} of the table at {@code path}, which the statement called {@code name},
   * on a server whose tables are at {@code tables}.
   */
  static int count(String path, String name, Set<String> tables, Duration latency)
      throws InterruptedException, NoSuchTable {
    answerAfter(path, latency);
    if (!tables.contains(path)) {
      throw new NoSuchTable(name);
    }
    return count(path, Duration.ZERO);
  }

  /** {@code SELECT count(*)} of the table at {@code path}. */
  static int count(String path, Duration latency) throws InterruptedException {
    answerAfter(path, latency);
    return path.equals("localhost/shop/public/orders")
        ? 1_000_000
        : Math.floorMod(path.hashCode(), 40) * 997 + 3;
  }

  private static void answerAfter(String path, Duration latency) throws InterruptedException {
    Thread.sleep(path.startsWith("staging") ? latency.multipliedBy(3) : latency);
  }

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  // Tree.Model

  // docs:start workbench-model
  @Override
  public List<Node> roots() {
    return servers;
  }

  @Override
  public Tree.Children<Node> children(Node node) {
    return node.children == null ? Tree.loading("Connecting…") : Tree.ready(node.children);
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
    return switch (node.kind) {
      case SERVER ->
        Icon.icon(
            Symbol.SERVER,
            node.children == null ? Variant.DISABLED : Variant.SUCCESS); // connected or not
      case DATABASE -> Icon.icon(Symbol.DATABASE, Variant.MUTED);
      case SCHEMA -> Icon.icon(Symbol.FOLDER, Variant.MUTED);
      case TABLE -> Icon.icon(Symbol.TABLE, Variant.MUTED);
      case COLUMN ->
        Icon.icon(node.name.equals("id") ? Symbol.KEY : Symbol.COLUMN_SINGLE, Variant.MUTED);
    };
  }
  // docs:end workbench-model

  /** A column's type after its name. */
  @Override
  public String detail(Node node) {
    if (node.kind != Kind.COLUMN) {
      return null;
    }
    return FIELDS.stream()
        .filter(field -> field.name().equals(node.name))
        .map(Field::type)
        .findFirst()
        .orElse(null);
  }

  // Handlers, on the app's thread

  void selected(Node node) {
    selected = node;
  }

  // docs:start workbench-connect
  void openOrClose(Node node, boolean opens) {
    if (!opens) {
      open.remove(node);
      return;
    }
    open.add(node);
    if (node.children == null && connecting.add(node)) {
      refused.remove(node);
      String server = node.name;
      Duration wait = latency;
      app.background(
          "databases of " + server,
          () -> connect(server, wait),
          names -> connectedTo(node, names),
          error -> refusedBy(node, error));
      taskbar();
    }
  }

  void connectedTo(Node server, List<String> names) {
    connecting.remove(server);
    server.children = new ArrayList<>();
    names.forEach(
        name -> schema(database(server, name), "public", name.equals("shop") ? TABLES.length : 3));
    connected++;
    tell(server.name + ": " + names.size() + " databases");
    answered();
  }

  void refusedBy(Node server, Exception error) {
    connecting.remove(server);
    refused.add(server);
    open.remove(server); // opening it again asks again
    tell(server.name + ": " + error.getMessage());
    answered();
  }
  // docs:end workbench-connect

  /** Opens the table, or the table of a column, in its tab, or chooses its tab. */
  void activate(Node node) {
    Node table = node.kind == Kind.COLUMN ? node.parent : node;
    if (table.kind != Kind.TABLE) {
      return;
    }
    for (TabGroups.Group<Opened> group : groups.groups()) { // in whichever window it is
      for (int index = 0; index < group.tabs().size(); index++) {
        if (group.tabs().get(index).table == table) {
          groups.select(group, index);
          if (group.window() != null) {
            group.window().raise();
          }
          return;
        }
      }
    }
    openTab(groups.main(), table);
  }

  private void openTab(TabGroups.Group<Opened> group, Node table) {
    Opened opened =
        new Opened(table, stored.computeIfAbsent(table.path(), path -> new TableRows.Stored()));
    opened.editor.markers(List.of(
        new CodeEditor.Marker(0, Symbol.PLAY, Variant.ACCENT, "Run statement", () -> run(opened))));
    ask(opened, Integer.MAX_VALUE, null, 0, 0);
    groups.add(group, group.tabs().size(), opened);
    taskbar(); // again, now that the tab counts
  }

  /** The tables open in tabs, in every window. */
  List<Opened> tables() {
    List<Opened> all = new ArrayList<>();
    for (TabGroups.Group<Opened> group : groups.groups()) {
      group.tabs().stream().filter(opened -> opened.table != null).forEach(all::add);
    }
    return all;
  }

  /** The active tab of a window if it shows a table, else null. */
  static Opened shown(TabGroups.Group<Opened> group) {
    Opened opened = group.activeTab();
    return opened == null || opened.table == null ? null : opened;
  }

  /** The table File's New query opens another tab of: the active tab's, else the selected. */
  Node queryTable(TabGroups.Group<Opened> group) {
    if (shown(group) != null) {
      return shown(group).table;
    }
    if (selected == null || selected.kind != Kind.TABLE && selected.kind != Kind.COLUMN) {
      return null;
    }
    return selected.kind == Kind.COLUMN ? selected.parent : selected;
  }

  /**
   * Control+Enter in a table's query area: its statement, as far as the server reads it, once the
   * changes of the tab's rows are committed or discarded.
   */
  void run(Opened opened) {
    opened.completion.close();
    unsaved(groups.groupOf(opened), List.of(opened), "run the query", () -> runQuery(opened));
  }

  private void runQuery(Opened opened) {
    Matcher limit = LIMIT.matcher(opened.query);
    int most = limit.find() ? Integer.parseInt(limit.group(1)) : Integer.MAX_VALUE;
    Matcher from = FROM.matcher(opened.query);
    if (!from.find()) {
      ask(opened, most, null, 0, 0);
      return;
    }
    ask(opened, most, from.group(1), from.start(1), from.end(1));
  }

  /**
   * Asks for the number of rows of a tab's table, at most {@code limit}: of the table its statement
   * names at {@code from} to {@code to}, or of the tab's own table for {@code name} null.
   */
  private void ask(Opened opened, int limit, String name, int from, int to) {
    if (opened.asking != null) {
      opened.asking.cancel(); // the answer asked for before is not wanted any more
    }
    opened.rows = -1;
    opened.selection.reset();
    opened.failure = null;
    opened.editor.noDiagnostics();
    String statement = opened.query;
    Node server = opened.table;
    while (server.parent != null) {
      server = server.parent;
    }
    // The work gets names: the path the statement means and the tables of the server.
    String path = name == null ? opened.table.path() : resolve(opened.table, name);
    Set<String> tables = new HashSet<>();
    tables(server, tables);
    Duration wait = latency;
    opened.asking = app.background(
        "rows of " + path,
        () -> Math.min(count(path, name, tables, wait), limit),
        rows -> {
          opened.rows = rows;
          opened.answered = rows;
          opened.asking = null;
          opened.data.base = rows;
          opened.data.refresh();
          answered();
        },
        error -> {
          opened.asking = null;
          opened.rows = opened.answered;
          if (error instanceof NoSuchTable) {
            opened.failure = error.getMessage();
            // The name, underlined while the statement is the one that ran.
            opened.editor.diagnostics(
                statement,
                List.of(new CodeEditor.Diagnostic(
                    from, to, CodeEditor.Severity.ERROR, error.getMessage())));
          } else {
            tell(path + ": " + error.getMessage());
          }
          answered();
        });
    taskbar();
  }

  /** The path of a table that a statement in the tab of {@code table} names. */
  static String resolve(Node table, String name) {
    String[] parts = name.split("\\.");
    Node schema = table.parent;
    Node database = schema.parent;
    String server = database.parent.name;
    return switch (parts.length) {
      case 1 -> server + "/" + database.name + "/" + schema.name + "/" + parts[0];
      case 2 -> server + "/" + database.name + "/" + parts[0] + "/" + parts[1];
      default -> server + "/" + parts[0] + "/" + parts[1] + "/" + parts[2];
    };
  }

  private static void tables(Node node, Set<String> into) {
    if (node.kind == Kind.TABLE) {
      into.add(node.path());
    } else if (node.children != null && node.kind != Kind.COLUMN) {
      node.children.forEach(child -> tables(child, into));
    }
  }

  /**
   * The completions for the start of a word in the query of a tab: the columns of its table, the
   * tables and columns of the connected servers and the keywords of SQL whose names start with
   * it, in that order, each name once.
   */
  // docs:start workbench-suggest
  List<Completion.Entry> suggest(Opened opened, String word) {
    String start = word.toLowerCase(Locale.ROOT);
    Map<String, Completion.Entry> found = new LinkedHashMap<>();
    for (Node column : opened.table.children) {
      if (column.name.toLowerCase(Locale.ROOT).startsWith(start)) {
        found.put(column.name, new Completion.Entry(column.name, Symbol.COLUMN_SINGLE, "column"));
      }
    }
    List<Node> columns = new ArrayList<>();
    for (Node server : servers) {
      collect(server, start, found, columns);
    }
    for (Node column : columns) {
      found.putIfAbsent(
          column.name, new Completion.Entry(column.name, Symbol.COLUMN_SINGLE, "column"));
    }
    for (String keyword : Sql.keywords().stream().sorted().toList()) {
      if (keyword.toLowerCase(Locale.ROOT).startsWith(start)) {
        found.putIfAbsent(keyword, new Completion.Entry(keyword, null, "keyword"));
      }
    }
    List<Completion.Entry> entries = new ArrayList<>(found.values());
    entries.removeIf(entry -> entry.text().equalsIgnoreCase(word)); // nothing to complete
    return entries.subList(0, Math.min(entries.size(), SUGGESTIONS));
  }
  // docs:end workbench-suggest

  private static void collect(
      Node node, String start, Map<String, Completion.Entry> tables, List<Node> columns) {
    if (node.children == null) {
      return; // not connected
    }
    for (Node child : node.children) {
      boolean matches = child.name.toLowerCase(Locale.ROOT).startsWith(start);
      if (child.kind == Kind.TABLE) {
        if (matches) {
          tables.putIfAbsent(child.name, new Completion.Entry(child.name, Symbol.TABLE, "table"));
        }
        for (Node column : child.children) {
          if (column.name.toLowerCase(Locale.ROOT).startsWith(start)) {
            columns.add(column);
          }
        }
      } else {
        collect(child, start, tables, columns);
      }
    }
  }

  /** The command palette of a window. */
  CommandPalette.State palette(TabGroups.Group<Opened> group) {
    return palettes.computeIfAbsent(group, forGroup -> new CommandPalette.State());
  }

  /**
   * What a window's palette finds: the commands of the menus and Change theme, then after {@code
   * @} the tables and columns of the connected servers, and after {@code :} a line of the active
   * tab's query.
   */
  El commandPalette(TabGroups.Group<Opened> group) {
    Theme theme = Theme.CURRENT.get();
    CommandPalette.State palette = palette(group);
    if (!palette.isOpen()) {
      return null;
    }
    List<FilterList.Entry> commands = new ArrayList<>(CommandPalette.commands(menus(group)));
    commands.add(FilterList.entry("Change theme…", () -> changeTheme(palette)));
    return CommandPalette.palette(
        palette,
        commands,
        List.of(
            new CommandPalette.Source("@", "to open a table", FilterList.Entries.of(catalog())),
            new CommandPalette.Source(
                ":", "to go to a line", FilterList.Entries.by(text -> line(shown(group), text)))));
  }

  /** The themes, each shown as the keys or the pointer reach it, the one before on Escape. */
  private void changeTheme(CommandPalette.State palette) {
    boolean before = dark;
    palette.ask(CommandPalette.Stage.choose(
            "Choose a theme",
            List.of(
                mark(FilterList.entry("Dark", () -> dark = true), dark),
                mark(FilterList.entry("Light", () -> dark = false), !dark)),
            dark ? 0 : 1)
        .preview(entry -> dark = entry.text().equals("Dark"), () -> dark = before));
  }

  private static FilterList.Entry mark(FilterList.Entry entry, boolean current) {
    return current ? entry.symbol(Menu.CHECKED) : entry;
  }

  /** The tables and columns of the connected servers, made anew when another one connects. */
  List<FilterList.Entry> catalog() {
    if (cataloged != connected) {
      List<FilterList.Entry> entries = new ArrayList<>();
      for (Node server : servers) {
        catalog(server, entries);
      }
      catalog = List.copyOf(entries);
      cataloged = connected;
    }
    return catalog;
  }

  private void catalog(Node node, List<FilterList.Entry> into) {
    if (node.children == null) {
      return; // not connected
    }
    for (Node child : node.children) {
      if (child.kind != Kind.TABLE) {
        catalog(child, into);
        continue;
      }
      String path = node.path().replace('/', '.');
      into.add(FilterList.entry(child.name, () -> activate(child))
          .symbol(Symbol.TABLE)
          .detail(path));
      for (Node column : child.children) {
        into.add(FilterList.entry(child.name + "." + column.name, () -> activate(column))
            .symbol(Symbol.COLUMN_SINGLE)
            .detail(path));
      }
    }
  }

  /** What {@code :} finds in a tab: the line typed, as the editor's Go to line takes it. */
  private static List<FilterList.Entry> line(Opened opened, String text) {
    if (opened == null) {
      return List.of(FilterList.entry("Open a table to go to a line of its query", null));
    }
    int lines =
        (int) opened.query.chars().filter(character -> character == '\n').count() + 1;
    String typed = text.strip();
    if (typed.isEmpty()) {
      return List.of(
          FilterList.entry("Type a line, 1 to " + lines + ", and a column after :", null));
    }
    if (!typed.matches("\\d{1,9}(:\\d{1,9})?")) {
      return List.of(FilterList.entry("Not a line: " + typed, null));
    }
    return List.of(
        FilterList.entry("Go to line " + typed, () -> Find.goToLine(opened.editor.find(), typed))
            .symbol(Symbol.ARROW_RIGHT)
            .detail(lines == 1 ? "of 1 line" : "of " + lines + " lines"));
  }

  /** A tab was closed, in any window. */
  void closed(Opened opened) {
    if (opened.asking != null) {
      opened.asking.cancel(); // its answer is not wanted any more
    }
    if (opened.committing != null) {
      opened.committing.cancel();
    }
    taskbar();
  }

  /** The user closes a tab: at once, or once its changes are committed or discarded. */
  void closeTab(TabGroups.Group<Opened> group, int index) {
    Opened opened = group.tabs().get(index);
    unsaved(group, List.of(opened), "close the tab", () -> {
      TabGroups.Group<Opened> now = groups.groupOf(opened); // where it is once the answer comes
      if (now != null) {
        groups.close(now, now.tabs().indexOf(opened));
      }
    });
  }

  /** The user closes the first window, or chooses Exit: once every change is settled. */
  void exit() {
    unsaved(groups.main(), tables(), "exit", this::quit);
  }

  /**
   * Runs {@code then} at once when none of {@code tabs} has changes not committed, else asks in
   * the window of {@code group} whether to commit or discard them first.
   */
  void unsaved(TabGroups.Group<Opened> group, List<Opened> tabs, String doing, Runnable then) {
    for (Opened opened : tabs) {
      if (!edited(opened)) {
        return;
      }
    }
    List<Opened> changed = tabs.stream()
        .filter(opened -> opened.table != null && !opened.changes.isEmpty())
        .toList();
    if (changed.isEmpty()) {
      then.run();
    } else {
      question = new Question(group == null ? groups.main() : group, changed, doing, then);
    }
  }

  /**
   * Takes over what the open editor of a tab's grid holds, as a press outside it would: false
   * when the grid refused it, whose editor stays open with the message, and then the tab is
   * chosen so that it shows, and what was about to happen does not.
   */
  boolean edited(Opened opened) {
    if (opened.table == null || opened.selection.commitEdit()) {
      return true;
    }
    TabGroups.Group<Opened> group = groups.groupOf(opened);
    if (group != null) {
      groups.select(group, group.tabs().indexOf(opened));
    }
    tell(opened.table.name + ": " + "the value being edited was refused");
    return false;
  }

  /** The dialog's Discard: the changes go, and what waited goes on. */
  void discard() {
    Question asked = question;
    question = null;
    asked.tabs().forEach(opened -> opened.changes.clear());
    asked.then().run();
  }

  /** The dialog's Commit: what waited goes on once the server took every tab's changes. */
  void commitAsked() {
    Question asked = question;
    question = null;
    int[] left = {asked.tabs().size()};
    for (Opened opened : asked.tabs()) {
      commit(opened, () -> {
        if (--left[0] == 0) {
          asked.then().run();
        }
      });
    }
  }

  /**
   * Sends the changes of a tab's rows to its server, which writes them, after which {@code
   * then} runs, or refuses them where one breaks a rule: the cells it names fail with its message,
   * and the changes stay.
   */
  void commit(Opened opened, Runnable then) {
    if (opened.table == null
        || opened.committing != null
        || !edited(opened)
        || opened.changes.isEmpty()) {
      return;
    }
    List<GridChanges.Change> changes = opened.changes.changes();
    // The work gets values: those the updates replace, which the server's rules look at.
    Map<Long, Map<Integer, String>> was = new HashMap<>();
    for (GridChanges.Change change : changes) {
      if (change.kind() == GridChanges.Kind.UPDATE) {
        Map<Integer, String> values = new HashMap<>();
        change
            .values()
            .keySet()
            .forEach(column -> values.put(column, opened.data.stored.value(change.key(), column)));
        was.put(change.key(), values);
      }
    }
    String path = opened.table.path();
    String name = opened.table.name;
    int count = opened.changes.count();
    Duration wait = latency;
    opened.committing = app.background(
        "commit to " + path,
        () -> TableRows.write(path, changes, was, wait),
        refused -> {
          opened.committing = null;
          if (refused.isEmpty()) {
            opened.data.stored.apply(changes);
            opened.changes.clear();
            for (Opened other : tables()) { // every tab of the table shows what was written
              if (other.data.stored == opened.data.stored) {
                other.data.refresh();
              }
            }
            tell(name + ": " + (count == 1 ? "1 change" : count + " changes") + " committed");
            if (then != null) {
              then.run();
            }
          } else {
            refused.forEach(refusal ->
                opened.changes.error(refusal.change(), refusal.column(), refusal.message()));
            tell(name + ": " + refused.getFirst().message());
          }
          answered();
        },
        error -> {
          opened.committing = null;
          tell(path + ": " + error.getMessage());
          answered();
        });
    taskbar();
  }

  /** The changes not committed in every tab. */
  int changed() {
    return tables().stream().mapToInt(opened -> opened.changes.count()).sum();
  }

  /** Chooses the next tab with changes after the first window's active one, in any window. */
  void nextChanged() {
    List<TabGroups.Group<Opened>> all = groups.groups();
    List<int[]> places = new ArrayList<>(); // group, tab
    for (int groupIndex = 0; groupIndex < all.size(); groupIndex++) {
      for (int tab = 0; tab < all.get(groupIndex).tabs().size(); tab++) {
        places.add(new int[] {groupIndex, tab});
      }
    }
    int from = groups.main().active();
    for (int step = 1; step <= places.size(); step++) {
      int[] at = places.get((from + step) % places.size());
      TabGroups.Group<Opened> group = all.get(at[0]);
      Opened opened = group.tabs().get(at[1]);
      if (opened.table != null && !opened.changes.isEmpty()) {
        groups.select(group, at[1]);
        if (group.window() != null) {
          group.window().raise();
        }
        return;
      }
    }
  }

  /**
   * Opens the window of a tab torn off or moved to a new window: the workbench in small, with the
   * app's icon and a view of its own over the same state.
   */
  // docs:start workbench-window
  Windows.Handle openWindow(TabGroups.Group<Opened> group, Window.Options options) {
    App<Workbench> window =
        new App<>(this, state -> state.windowView(group)).provide(Theme.CURRENT, Workbench::theme);
    window.textRendering(app.textRendering());
    Windows windows = app.windows();
    windows.of(app).onCloseRequest(this::exit); // closing the first window closes them all
    return windows.open(
        group.activeTab().title(),
        TAB_WIDTH,
        TAB_HEIGHT,
        options.frameless().minimumSize(480, 320).icon(Identity.icon(MARK, Symbol.DATABASE)),
        window);
  }
  // docs:end workbench-window

  /** The app of a group's window, where its fields and handlers are. */
  @SuppressWarnings("unchecked") // every window runs an app of this state
  App<Workbench> appOf(TabGroups.Group<Opened> group) {
    return group.window() == null ? app : (App<Workbench>) group.window().app();
  }

  /**
   * Where the first window's placement is kept between runs: a line in the user's settings,
   * {@code %APPDATA%\rethinkj-ui-examples\Workbench\window.txt} on Windows and {@code
   * $XDG_CONFIG_HOME/rethinkj-ui-examples/Workbench/window.txt} ({@code ~/.config}) elsewhere.
   * Which file is the app's choice; the library only reads and writes the line.
   */
  static Path placementFile() {
    String appData = System.getenv("APPDATA");
    String config = System.getenv("XDG_CONFIG_HOME");
    Path base = File.separatorChar == '\\' && appData != null
        ? Path.of(appData)
        : config != null && !config.isEmpty()
            ? Path.of(config)
            : Path.of(System.getProperty("user.home"), ".config");
    return base.resolve("rethinkj-ui-examples").resolve("Workbench").resolve("window.txt");
  }

  /** The placement kept last, or null the first time or when the line cannot be read. */
  static Window.Placement savedPlacement(Path file) {
    try {
      return Window.Placement.parse(Files.readString(file));
    } catch (IOException | IllegalArgumentException error) {
      return null;
    }
  }

  /**
   * Keeps where the first window is, for the next start, while it is open; only when {@link
   * #main} opened it, never under a script or a test.
   */
  void keepPlacement() {
    if (placementFile == null || window == null || !window.isOpen()) {
      return;
    }
    try {
      Files.createDirectories(placementFile.getParent());
      Files.writeString(placementFile, window.placement() + "\n");
    } catch (IOException error) {
      System.err.println("Workbench: the window's placement cannot be kept: " + error.getMessage());
    }
  }

  /** Closes every window, which ends the app, and gives up the questions on their way. */
  void quit() {
    keepPlacement();
    for (Opened opened : tables()) {
      if (opened.asking != null) {
        opened.asking.cancel();
      }
      if (opened.committing != null) {
        opened.committing.cancel();
      }
    }
    for (Windows.Handle open : app.windows().all()) {
      open.close();
    }
  }

  /** The items of the tree's context menu, for the selected node. */
  List<Menu.Item> menuItems() {
    Node node = selected;
    if (node == null) {
      return List.of();
    }
    Runnable opens = node.kind == Kind.TABLE || node.kind == Kind.COLUMN
        ? () -> activate(node)
        : open.contains(node) ? null : () -> openOrClose(node, true);
    List<Menu.Item> items =
        new ArrayList<>(List.of(Menu.item("Open", opens), Menu.item("Copy name", () -> {
          app.clipboard().write(node.name);
          tell("Copied " + node.name);
        })));
    if (node.kind == Kind.TABLE) {
      items.add(Menu.separator());
      items.add(Menu.item("Drop table…", () -> {
        dropping = node;
        confirm = "";
      }));
    }
    return items;
  }

  void drop() {
    tell(dropping.path() + " was not dropped: nothing here is real");
    dropping = null;
  }

  /** The questions on their way: servers connecting and tables counting their rows. */
  int running() {
    return connecting.size()
        + (int) tables().stream()
            .filter(opened -> opened.asking != null || opened.committing != null)
            .count();
  }

  /**
   * Shows the number of questions on their way on the taskbar button, with a bar that runs while
   * there are any, or nothing for none.
   */
  void taskbar() {
    if (window == null) {
      return;
    }
    int count = running();
    Theme theme = theme();
    WindowIcon badge = WindowIcon.of(size -> AppIcon.count(size, theme.accent().color(), count));
    window.badge(
        count == 0 ? null : badge,
        count == 1 ? "1 question running" : count + " questions running");
    window.progress(count == 0 ? Window.Progress.NONE : Window.Progress.INDETERMINATE, 0);
  }

  /**
   * An answer arrived: the badge counts one less, and while no window of the app has the focus,
   * the first asks to look.
   */
  void answered() {
    taskbar();
    if (window != null
        && app.windows().all().stream()
            .noneMatch(handle -> handle.window() != null && handle.window().isFocused())) {
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
    Workbench state = new Workbench();
    App<Workbench> app = new App<>(state, Workbench::view).provide(Theme.CURRENT, Workbench::theme);
    state.app = app;
    // A script's images are stored, so they must not depend on the platform; its await waits for
    // the answers, which need not take long.
    app.textRendering(TextRendering.LINUX);
    state.latency = Duration.ZERO;
    if (Script.handle(args, app)) {
      return;
    }
    state.latency = Duration.ofMillis(800);
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(Workbench.class, "Workbench", MARK, Symbol.DATABASE)
        .frameless()
        .minimumSize(640, 400);
    state.placementFile = placementFile();
    Window.Placement was = savedPlacement(state.placementFile);
    if (was != null) {
      options.placement(was); // where the first window was when the app last ended
    }
    try (Window window = Window.open("Workbench", 1200, 720, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      state.window = window;
      new WindowLoop(window, app, presenter).run();
      state.keepPlacement(); // the first window alone ended the loop and is still open
    }
  }

  // The view

  /** The first window's view: the catalog beside the tabs of the main group. */
  static El view(Workbench state) {
    Theme theme = Theme.CURRENT.get();
    El right = state.tabs(state.groups.main());
    // docs:start workbench-tree
    El tree = tree(
            state,
            new Tree.Options<Node>()
                .open(state.open::contains, state::openOrClose)
                .select(state.selected, state::selected)
                .onActivate(state::activate))
        .key("catalog")
        .label("Catalog");
    // docs:end workbench-tree
    El catalog = Card.flush(
        Card.toolbar(
            "Connections",
            null,
            Button.icon(
                GHOST,
                Symbol.ADD,
                "Add connection",
                () -> state.tell("Adding a connection is not part of this example"))),
        Card.inset(Menu.contextMenu(tree, state.menu, state.menuItems())));
    List<Menu.Item> menus = state.menus(state.groups.main());
    El root = col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        // The menus' keys, then the tabs' keys also while the tree has the focus.
        .onKey(or(
            MenuBar.keys(state.bar, menus)
                .or(CommandPalette.keys(state.palette(state.groups.main()))),
            right.onKey()))
        .onKeyUp(MenuBar.keysUp(state.bar, menus))
        .overlay(state.commandPalette(state.groups.main()))
        .child(withMenus(
            "Workbench",
            theme.accent().color(),
            MenuBar.menuBar(state.bar, menus),
            Identity.maximized(state.app),
            toggle(state.dark, "Dark", () -> state.dark = !state.dark)))
        .child(Split.cards(
                Direction.ROW,
                state.sidebar,
                SIDEBAR,
                position -> state.sidebar = position,
                catalog,
                160,
                right.key("tabs"),
                320)
            .grow(1)
            .padding(0, theme.space().m(), theme.space().m(), theme.space().m()))
        .child(state.statusBar());
    if (state.dropping != null) {
      String name = state.dropping.name;
      El drop = button(
              PRIMARY,
              "Drop",
              state::drop,
              Button.options().enabled(state.confirm.equals(name)).tone(Button.Tone.DANGER))
          .key("drop");
      root.overlay(Dialog.dialog(
          "Drop table",
          col()
              .gap(theme.space().m())
              .children(
                  Label.label("Drop " + state.dropping.path() + " and all its rows?"),
                  Label.label(Label.Variant.MUTED, "Type the table's name to confirm."),
                  textField(state.confirm, name, value -> state.confirm = value)
                      .key("confirm")
                      .label("Table name")),
          List.of(button(SECONDARY, "Cancel", () -> state.dropping = null).key("cancel"), drop),
          drop, // Enter in the field drops once the name is typed
          () -> state.dropping = null));
    }
    // Closing the window asks about the changes not committed first, then closes them all.
    Windows.Handle first = state.app == null ? null : state.app.windows().of(state.app);
    if (first != null) {
      first.onCloseRequest(state::exit);
    }
    return root.overlay(state.question(state.groups.main()))
        .overlay(state.preview(state.groups.main()));
  }

  /** The dialog that asks about changes not committed, in its window, or null. */
  El question(TabGroups.Group<Opened> group) {
    Theme theme = Theme.CURRENT.get();
    Question asked = question;
    if (asked == null || asked.group() != group) {
      return null;
    }
    int count = asked.tabs().stream().mapToInt(opened -> opened.changes.count()).sum();
    String what = asked.tabs().size() == 1
        ? asked.tabs().getFirst().table.name + " has "
            + (count == 1 ? "1 change" : count + " changes")
        : asked.tabs().stream()
                .map(opened -> opened.table.name + " (" + opened.changes.count() + ")")
                .collect(java.util.stream.Collectors.joining(", "))
            + " have changes";
    El commit = button(PRIMARY, "Commit", this::commitAsked).key("commit-changes");
    return Dialog.dialog(
        "Changes not committed",
        col()
            .gap(theme.space().m())
            .children(
                Label.label(what + " that the server does not have yet.").key("unsaved"),
                Label.label(
                    Label.Variant.MUTED,
                    "Commit them before you " + asked.doing() + ", or discard them?")),
        List.of(
            button(SECONDARY, "Cancel", () -> question = null).key("keep"),
            button(SECONDARY, "Discard", this::discard, Button.options().tone(DANGER))
                .key("discard"),
            commit),
        commit,
        () -> question = null);
  }

  /** The statements that Commit sends for a tab's changes, in a dialog of its window, or null. */
  El preview(TabGroups.Group<Opened> group) {
    Opened opened = previewing;
    if (opened == null || groups.groupOf(opened) != group) {
      return null;
    }
    Node schema = opened.table.parent;
    String sql = TableRows.sql(
        schema.parent.name + "." + schema.name + "." + opened.table.name,
        opened.changes.changes(),
        key -> opened.data.stored.value(key, 0));
    El editor = codeEditor(sql, preview, Sql.of(), value -> {})
        .readOnly()
        .key("statements")
        .label("Statements")
        .w(560)
        .lines(8);
    boolean free = !opened.changes.isEmpty() && opened.committing == null;
    El commit = button(
            PRIMARY,
            Symbol.SAVE,
            "Commit",
            false,
            !free
                ? null
                : () -> {
                  previewing = null;
                  commit(opened, null);
                })
        .key("commit-preview");
    return Dialog.dialog(
        "Changes to " + opened.table.name,
        editor,
        List.of(
            button(SECONDARY, "Close", () -> previewing = null).key("close-preview"),
            button(SECONDARY, Symbol.COPY, "Copy", false, () -> {
                  appOf(group).clipboard().write(sql);
                  tell("Copied the statements");
                })
                .key("copy-preview"),
            commit),
        commit,
        () -> previewing = null);
  }

  /**
   * The bar along the bottom of the first window: each server with how its connection stands (a
   * press selects it in the catalog), and the questions on their way.
   */
  private El statusBar() {
    List<El> servers = new ArrayList<>();
    for (Node server : roots()) {
      StatusBar.State state = connecting.contains(server)
          ? StatusBar.State.BUSY
          : refused.contains(server)
              ? StatusBar.State.ERROR
              : server.children == null ? StatusBar.State.IDLE : StatusBar.State.OK;
      servers.add(StatusBar.status(state, server.name, () -> selected = server)
          .key("server-" + server.name));
    }
    int running = running();
    int changed = changed();
    // The changes not committed, in every tab, while there are: a press goes to the next tab.
    El changes = changed == 0
        ? null
        : StatusBar.item(
                Badge.Kind.ACCENT,
                Symbol.EDIT,
                changed == 1 ? "1 change" : changed + " changes",
                this::nextChanged)
            .key("changes");
    return StatusBar.statusBar(
        List.of(StatusBar.group("Servers", servers.toArray(El[]::new))),
        java.util.Arrays.asList(
            changes,
            StatusBar.text(
                    running == 0
                        ? "Idle"
                        : running == 1 ? "1 question running" : running + " questions running")
                .key("running")));
  }

  /**
   * The view of a tab's window, the workbench in small: the title bar with the active tab's
   * title over the group's tabs, without the catalog, the theme's switch and the menu bar,
   * whose key combinations work here too, for this window's tabs.
   */
  El windowView(TabGroups.Group<Opened> group) {
    Theme theme = Theme.CURRENT.get();
    El tabs = tabs(group);
    Opened active = group.activeTab();
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .onKey(
            or(Menu.shortcuts(menus(group)).or(CommandPalette.keys(palette(group))), tabs.onKey()))
        .overlay(commandPalette(group))
        .overlay(question(group))
        .overlay(preview(group))
        .child(titleBar(
            active == null ? "Workbench" : active.title(),
            theme.accent().color(),
            Identity.maximized(group.window() == null ? null : group.window().app())))
        // No minimum height from the tabs, whose grid would otherwise make it as high as its rows.
        .child(col()
            .grow(1)
            .minSize(0, 0)
            .padding(0, theme.space().m(), theme.space().m(), theme.space().m())
            .child(tabs.key("tabs").grow(1)));
  }

  /** The menus' keys, then the tabs' keys, which an empty group has none of. */
  private static Predicate<Key> or(Predicate<Key> menus, Predicate<Key> tabs) {
    return tabs == null ? menus : menus.or(tabs);
  }

  /** The tabs of a window, each the tab's content over a footer with its status. */
  private El tabs(TabGroups.Group<Opened> group) {
    boolean main = group == groups.main();
    return groups.tabs(
        group,
        opened -> opened.table == null
            ? new Tab("Start", false, false)
            : new Tab(opened.table.name).icon(Symbol.TABLE),
        opened -> {
          // The tab's content over a footer with the status, which shows on every tab, and where
          // the caret of the tab's query is.
          Opened shown = opened == null || opened.table == null ? null : opened;
          return col()
              .grow(1)
              .child(shown == null ? start() : table(this, shown, appOf(group)))
              .child(Card.footer(
                  Label.label(Label.Variant.MUTED, status(shown, main))
                      .key("status")
                      .ellipsis(),
                  shown == null
                      ? new El[0]
                      : new El[] {
                        Label.label(Label.Variant.MUTED, position(shown)).key("position")
                      }));
        });
  }

  /** The menus of the title bar, for the tabs of a window. */
  List<Menu.Item> menus(TabGroups.Group<Opened> group) {
    Opened opened = shown(group);
    Node table = queryTable(group);
    return List.of(
        Menu.submenu(
            "&File",
            List.of(
                Menu.item("New query", table == null ? null : () -> openTab(group, table))
                    .icon(Symbol.ADD)
                    .shortcut(Key.CONTROL, "N"),
                Menu.item(
                        "Close tab", opened == null ? null : () -> closeTab(group, group.active()))
                    .shortcut(Key.CONTROL, "W"),
                Menu.separator(),
                Menu.item("Exit", this::exit))),
        Menu.submenu("&Edit", edits(opened, appOf(group))),
        Menu.submenu(
            "&View",
            List.of(
                Menu.item("Command palette…", palette(group)::open)
                    .icon(Symbol.SEARCH)
                    .shortcut("Ctrl+Shift+P"),
                Menu.item("Go to table…", () -> palette(group).open("@"))
                    .icon(Symbol.TABLE)
                    .shortcut(Key.CONTROL, "P"),
                Menu.separator(),
                Menu.item("Dark theme", () -> dark = true).checked(dark),
                Menu.item("Light theme", () -> dark = false).checked(!dark))),
        Menu.submenu(
            "&Query",
            List.of(
                Menu.item("Run", opened == null ? null : () -> run(opened))
                    .icon(Symbol.PLAY)
                    .shortcut(Key.CONTROL, "Enter"),
                Menu.separator(),
                Menu.item("Commit", !committable(opened) ? null : () -> commit(opened, null))
                    .icon(Symbol.SAVE)
                    .shortcut(Key.CONTROL, "S"),
                Menu.item("Revert", !settled(opened) ? null : opened.changes::clear)
                    .icon(Symbol.ARROW_UNDO),
                Menu.item("Preview changes…", !settled(opened) ? null : () -> previewing = opened)
                    .icon(Symbol.CODE))));
  }

  /** Whether Commit has something to write: changes, or a value in an open editor. */
  private static boolean committable(Opened opened) {
    return opened != null
        && opened.table != null
        && opened.committing == null
        && (!opened.changes.isEmpty() || opened.selection.isEditing());
  }

  /** Whether a tab shows a table with changes that are not on their way to the server. */
  private static boolean settled(Opened opened) {
    return opened != null && !opened.changes.isEmpty() && opened.committing == null;
  }

  /**
   * Edit's items, for the query of the active tab: what the app can do with {@link App#edit},
   * {@link App#select}, {@link App#undo}, {@link App#redo} and the clipboard.
   */
  private List<Menu.Item> edits(Opened opened, App<Workbench> app) {
    List<Menu.Item> items = new ArrayList<>(queryEdits(opened, app));
    if (opened == null || !opened.inGrid || opened.rows < 0) {
      return items;
    }
    // The grid had the focus: the history of its changes and its clipboard commands.
    boolean free = opened.committing == null;
    items.set(
        0,
        Menu.item("Undo", free && opened.changes.canUndo() ? opened.changes::undo : null)
            .icon(Symbol.ARROW_UNDO)
            .shortcut("Ctrl+Z"));
    items.set(
        1,
        Menu.item("Redo", free && opened.changes.canRedo() ? opened.changes::redo : null)
            .icon(Symbol.ARROW_REDO)
            .shortcut("Ctrl+Shift+Z"));
    items.set(3, Menu.item("Cut", null).icon(Symbol.CUT).shortcut(Key.CONTROL, "X"));
    items.set(
        4,
        Menu.item(
                "Copy",
                opened.gridCommands.canRun(DataGrid.Command.COPY)
                    ? () -> opened.gridCommands.run(DataGrid.Command.COPY)
                    : null)
            .icon(Symbol.COPY)
            .shortcut(Key.CONTROL, "C"));
    items.set(
        5,
        Menu.item(
                "Paste",
                free && opened.gridCommands.canRun(DataGrid.Command.PASTE)
                    ? () -> opened.gridCommands.run(DataGrid.Command.PASTE)
                    : null)
            .icon(Symbol.CLIPBOARD_PASTE)
            .shortcut(Key.CONTROL, "V"));
    items.set(
        7,
        Menu.item(
                "Select all",
                opened.gridCommands.canRun(DataGrid.Command.SELECT_ALL)
                    ? () -> opened.gridCommands.run(DataGrid.Command.SELECT_ALL)
                    : null)
            .shortcut(Key.CONTROL, "A"));
    return items;
  }

  private List<Menu.Item> queryEdits(Opened opened, App<Workbench> app) {
    int from = opened == null ? 0 : Math.min(opened.editor.caret(), opened.editor.anchor());
    int to = opened == null
        ? 0
        : Math.min(Math.max(opened.editor.caret(), opened.editor.anchor()), opened.query.length());
    boolean some = opened != null && from < to;
    return List.of(
        Menu.item("Undo", opened == null ? null : () -> app.undo(query(opened, app)))
            .icon(Symbol.ARROW_UNDO)
            .shortcut("Ctrl+Z"),
        Menu.item("Redo", opened == null ? null : () -> app.redo(query(opened, app)))
            .icon(Symbol.ARROW_REDO)
            .shortcut("Ctrl+Shift+Z"),
        Menu.separator(),
        Menu.item(
                "Cut",
                !some
                    ? null
                    : () -> {
                      app.clipboard().write(opened.query.substring(from, to));
                      app.edit(query(opened, app), from, to, "", from);
                    })
            .icon(Symbol.CUT)
            .shortcut(Key.CONTROL, "X"),
        Menu.item(
                "Copy",
                !some ? null : () -> app.clipboard().write(opened.query.substring(from, to)))
            .icon(Symbol.COPY)
            .shortcut(Key.CONTROL, "C"),
        Menu.item(
                "Paste",
                opened == null
                    ? null
                    : () -> {
                      String text = app.clipboard().read();
                      if (text != null && !text.isEmpty()) {
                        app.edit(query(opened, app), from, to, text, from + text.length());
                      }
                    })
            .icon(Symbol.CLIPBOARD_PASTE)
            .shortcut(Key.CONTROL, "V"),
        Menu.separator(),
        Menu.item(
                "Select all",
                opened == null
                    ? null
                    : () -> app.select(query(opened, app), opened.query.length(), 0))
            .shortcut(Key.CONTROL, "A"),
        Menu.separator(),
        Menu.item("Find…", opened == null ? null : () -> Find.open(opened.editor.find()))
            .icon(Symbol.SEARCH)
            .shortcut(Key.CONTROL, "F"),
        Menu.item("Replace…", opened == null ? null : () -> Find.openReplace(opened.editor.find()))
            .shortcut(Key.CONTROL, "H"),
        Menu.item("Find next", opened == null ? null : () -> Find.next(opened.editor.find()))
            .shortcut(0, "F3"),
        Menu.item("Go to line…", opened == null ? null : () -> Find.goToLine(opened.editor.find()))
            .shortcut(Key.CONTROL, "G"),
        Menu.separator(),
        // Shown, not bound at the root: they act on the editor, which takes them while it has
        // the focus, and not on a grid that has it.
        command(app, opened, "Select next occurrence", CodeEditor.Command.SELECT_NEXT, "Ctrl+D"),
        command(app, opened, "Duplicate lines", CodeEditor.Command.DUPLICATE_LINES, "Ctrl+Shift+D"),
        command(app, opened, "Move lines up", CodeEditor.Command.MOVE_LINES_UP, "Alt+Up"),
        command(app, opened, "Move lines down", CodeEditor.Command.MOVE_LINES_DOWN, "Alt+Down"),
        command(app, opened, "Delete lines", CodeEditor.Command.DELETE_LINES, "Ctrl+Shift+K"),
        command(app, opened, "Toggle comment", CodeEditor.Command.TOGGLE_COMMENT, "Ctrl+/"));
  }

  private static Menu.Item command(
      App<Workbench> app, Opened opened, String label, CodeEditor.Command command, String keys) {
    return Menu.item(
            label, opened == null ? null : () -> CodeEditor.run(app, opened.editor, command))
        .shortcut(keys);
  }

  /** The query field of a tab in the tree of the window it is in. */
  private static El query(Opened opened, App<Workbench> app) {
    return find(app.tree(), "query-" + opened.table.path());
  }

  /** The element with this key in the tree of the last frame, or null. */
  private static El find(El el, String key) {
    if (el == null || key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** Where the caret of a tab's query is, as editors say it: line and column, from 1. */
  static String position(Opened opened) {
    int caret = Math.min(opened.editor.caret(), opened.query.length());
    int line = 1;
    for (int index = opened.query.indexOf('\n');
        index >= 0 && index < caret;
        index = opened.query.indexOf('\n', index + 1)) {
      line++;
    }
    int column = caret - (opened.query.lastIndexOf('\n', caret - 1) + 1) + 1;
    return "Ln " + line + ", Col " + column;
  }

  /**
   * What the status line of a window says: in the first a message, or else what the tab shows,
   * null for Start, which the first fills with the selected node.
   */
  String status(Opened opened, boolean main) {
    if (main && message != null) {
      return message;
    }
    if (opened == null) {
      return !main || selected == null ? "" : selected.path();
    }
    if (opened.failure != null) {
      return opened.table.path() + " · " + opened.failure;
    }
    if (opened.rows < 0) {
      return opened.table.path() + " · counting the rows";
    }
    long chosen = opened.selection.rowCount();
    return String.format(
            Locale.ROOT, "%s · %,d rows", opened.table.path(), opened.changes.rowCount())
        + (chosen == 0
            ? ""
            : chosen == 1
                ? String.format(Locale.ROOT, " · row %,d selected", opened.selection.row() + 1)
                : String.format(Locale.ROOT, " · %,d rows selected", chosen));
  }

  private static El start() {
    Theme theme = Theme.CURRENT.get();
    return col()
        .grow(1)
        .padding(theme.space().xxl())
        .gap(theme.space().m())
        .child(Label.label(Label.Variant.HEADING, "The frame of a database manager"))
        .child(Label.label(
            Label.Variant.MUTED,
            "Open a table from the catalog: double-click it, or select it and press Enter."))
        .child(Label.label(
            Label.Variant.MUTED,
            "Control+Tab and Control+Shift+Tab go through the tabs; Control+W closes one."))
        .child(Label.label(
            Label.Variant.MUTED, "staging answers slowly, and prod-eu refuses to connect."))
        .child(
            Label.label(Label.Variant.MUTED, "Edits of a table's rows wait for Commit, Control+S."))
        .child(Label.label(
            Label.Variant.MUTED,
            "The servers refuse an amount below zero, and a paid order going back to pending."));
  }

  // docs:start workbench-editor-handlers
  void editQuery(Opened opened, String query) {
    opened.query = query;
    opened.failure = null; // the next edit takes the error away
  }

  /** Control+Enter runs the query; any other key goes on to the editor. */
  boolean runKey(Opened opened, Key key) {
    boolean enter = key.name().equals("Enter") && key.modifiers() == Key.CONTROL;
    if (enter) {
      run(opened);
    }
    return enter;
  }
  // docs:end workbench-editor-handlers

  private static El table(Workbench state, Opened opened, App<Workbench> app) {
    Theme theme = Theme.CURRENT.get();
    // docs:start workbench-editor
    El area = codeEditor(
            app, opened.query, opened.editor, Sql.of(), text -> state.editQuery(opened, text))
        .key("query-" + opened.table.path())
        .label("Query")
        .wFull()
        .shrink(0);
    area.onKeyFirst(key -> state.runKey(opened, key)); // then the editor's own keys
    Completion.complete(
        app, area, opened.editor.caret(), opened.completion, word -> state.suggest(opened, word));
    // docs:end workbench-editor
    El query = col()
        .padding(theme.space().m(), theme.space().l())
        .shrink(0)
        .child(area)
        .whenFocusWithin(element -> opened.inGrid = false);
    if (opened.asking != null
        || opened.committing
            != null) { // a line runs over the toolbar's hairline while the server answers
      query.child(Progress.line().absolute(0, -theme.size().stroke()).key("running"));
    }
    if (opened.rows < 0) {
      return col()
          .grow(1)
          .minSize(0, 0)
          .children(
              toolbar(state, opened),
              Divider.divider(),
              query,
              Divider.divider(),
              col()
                  .padding(theme.space().xxl())
                  .child(Label.label(
                      Label.Variant.MUTED, "Loading the rows of " + opened.table.path() + "…")));
    }
    // The columns edit by their type, but the key; while the server writes, none edits.
    boolean edits = opened.committing == null;
    GridChanges changes = opened.changes;
    List<Column> columns = new ArrayList<>();
    for (int index = 0; index < opened.widths.length; index++) {
      Field field = field(opened.table.children.get(index).name);
      int at = index;
      Column column = new Column(
              field.name(), opened.widths[index], field.kind(), changes.cell(index))
          .typeName(field.type());
      if (edits && index > 0) {
        column = column.editable();
      }
      columns.add(
          switch (field.name()) {
            case "id" -> column.icon(Symbol.KEY, Variant.WARNING);
            case "customer_id" -> column.icon(Symbol.LINK).cellIcon(row -> LINK);
            case "status" ->
              column
                  .cellIcon(row -> {
                    String status = changes.cell(row, at);
                    int which = status == null ? -1 : List.of(STATUS).indexOf(status);
                    return which < 0 ? null : MARKS[which];
                  })
                  .choices(List.of(STATUS));
            case "paid" ->
              column.cellIcon(row -> {
                String paid = changes.cell(row, at);
                return paid == null ? null : paid.equals("true") ? PAID : UNPAID;
              });
            case "note", "updated_at", "deleted_at" -> column.nullable();
            default -> column;
          });
    }
    DataGrid.Options options = DataGrid.options()
        .select(opened.selection)
        .commands(opened.gridCommands)
        .onResize((column, width) -> opened.widths[column] = width)
        .copy(state.app.clipboard())
        .contextMenu(opened.gridMenu, List.of())
        .columns(opened.layout)
        .filters(opened.filters, opened::filter)
        .filterable(column -> TableRows.FILTERABLE.contains(column));
    if (edits) {
      options.changes(changes);
    } else { // what the rows hold, without editing them
      options.status(changes::rowStatus, changes::cellStatus).errors(changes::errorText);
    }
    El grid = dataGrid(columns, changes.rowCount(), options)
        .key("grid-" + opened.table.path())
        .label(opened.table.name)
        .whenFocusWithin(element -> opened.inGrid = true);
    El bar = DataGrid.filterBar(columns, opened.filters, opened::filter);
    El tab = col().grow(1).minSize(0, 0).child(toolbar(state, opened)).child(Card.filters(bar));
    return tab.children(
        Divider.divider(), query, Divider.divider(), grid.grow(1).minSize(0, 0));
  }

  /** The table's name, its number of rows and the button that runs the query. */
  private static El toolbar(Workbench state, Opened opened) {
    String rows = opened.rows < 0
        ? "counting the rows"
        : String.format(Locale.ROOT, "%,d rows", opened.changes.rowCount());
    int changed = opened.changes.count();
    if (changed == 0) {
      return Card.toolbar(
          opened.table.name,
          rows,
          Button.button(PRIMARY, Symbol.PLAY, "Run", opened.asking != null, () -> state.run(opened))
              .key("run"));
    }
    // With changes, Commit is the primary action, and Run a secondary one.
    boolean writing = opened.committing != null;
    return Card.toolbar(
        opened.table.name,
        rows,
        button(
                GHOST,
                "Revert",
                opened.changes::clear,
                Button.options().icon(Symbol.ARROW_UNDO).enabled(!writing).tone(DANGER))
            .key("revert"),
        Button.button(
                PRIMARY,
                Symbol.SAVE,
                "Commit (" + changed + ")",
                writing,
                () -> state.commit(opened, null))
            .key("commit"),
        Button.button(SECONDARY, Symbol.PLAY, "Run", opened.asking != null, () -> state.run(opened))
            .key("run"));
  }

  private static Field field(String name) {
    return FIELDS.stream()
        .filter(field -> field.name().equals(name))
        .findFirst()
        .orElseThrow();
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
