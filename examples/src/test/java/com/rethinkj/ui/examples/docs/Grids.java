package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.GridTable;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tree;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/** The examples of the pages about the data grid and the tree. */
final class Grids {

  static final String[] CUSTOMERS = {"Ada", "Brendan", "Chen", "Dagny", "Emeka", "Farah", "Gus"};
  static final String[] STATUSES = {"paid", "pending", "shipped", "refunded"};
  static final int COUNT = 1000;

  App<?> app; // set by whoever makes the app: the grid copies to its clipboard
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final DataGrid.Selection picked = new DataGrid.Selection(DataGrid.Mode.ROWS);
  final DataGrid.Commands gridCommands = new DataGrid.Commands();
  final Menu.State gridMenu = new Menu.State();
  List<DataGrid.SortKey> sort = List.of();
  final float[] widths = {80, 120, 100, 100};
  final float[] fitted = {80, 80, 80, 80}; // the widths of the fitted grid, until its fit
  final DataGrid.Fit fit = new DataGrid.Fit();
  final DataGrid.Layout layout = new DataGrid.Layout(List.of("id", "customer", "amount", "status"));
  List<DataGrid.Filter> filters = List.of();
  // The rows of the edited grid, which Commit writes the changes into.
  final List<String[]> rows = new ArrayList<>(IntStream.range(0, 20)
      .mapToObj(row ->
          IntStream.range(0, 4).mapToObj(column -> value(row, column)).toArray(String[]::new))
      .toList());
  GridChanges changes = new GridChanges(rows.size(), (row, column) -> rows.get(row)[column]);
  int nextId = 1020; // the id the next new order gets
  final Set<String> open = new HashSet<>(List.of("shop", "public"));
  final Set<Long> schemaOpen = new HashSet<>(List.of(1L, 10L));
  final DataGrid.Selection schemaSelection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  List<DataGrid.SortKey> schemaSort = List.of();
  Node selected;
  String said = "";
  final Tree.Dragging dragging = new Tree.Dragging(); // what the tree keeps of a drag

  void resizeFitted(int column, float width) {
    fitted[column] = width;
  }

  void sort(List<DataGrid.SortKey> value) {
    sort = value;
  }

  void resize(int column, float width) {
    widths[column] = width;
  }

  void filters(List<DataGrid.Filter> value) {
    filters = value;
  }

  void clearFilters() {
    filters = List.of();
  }

  static List<DataGrid.Selection> modes() {
    // docs:start grid-modes
    var rows = new DataGrid.Selection(DataGrid.Mode.ROWS); // whole rows, as results are read
    var cells = new DataGrid.Selection(DataGrid.Mode.CELLS); // cells, and rows by their numbers
    // docs:end grid-modes
    return List.of(rows, cells);
  }

  /** The made-up orders: the value at a row and a column. */
  static String value(int row, int column) {
    return switch (column) {
      case 0 -> String.valueOf(1000 + row);
      case 1 -> CUSTOMERS[row % CUSTOMERS.length];
      case 2 -> String.format(Locale.ROOT, "%d.%02d", row * 37 % 500, row * 13 % 100);
      default -> STATUSES[row % STATUSES.length];
    };
  }

  static El basic(Grids state) {
    // docs:start grid
    List<Column> columns = List.of(
        new Column("id", 80, DataType.INTEGER, row -> value(row, 0))
            .typeName("bigint")
            .icon(Symbol.KEY, Icon.Variant.WARNING),
        new Column("customer", 120, DataType.TEXT, row -> value(row, 1)),
        new Column("amount", 100, DataType.DECIMAL, row -> value(row, 2)).typeName("numeric"),
        new Column("status", 100, DataType.TEXT, row -> value(row, 3)));
    El grid = DataGrid.dataGrid(
            columns,
            COUNT,
            DataGrid.options()
                .select(state.selection) // the app keeps the selection
                .commands(state.gridCommands) // an external menu's handle, kept in the app's state
                .rowNumbers()
                .frozen(1) // numbers and the id stay at the left
                .copy(state.app.clipboard())) // Control+C copies tab-separated text
        .key("orders")
        .label("Orders")
        .grow(1);
    // docs:end grid
    return Frame.of(Card.flush(grid).wFull().grow(1));
  }

  static Menu.Item gridCopy(DataGrid.Commands commands) {
    // docs:start grid-command
    Menu.Item copy = Menu.item(
        "Copy",
        commands.canRun(DataGrid.Command.COPY) ? () -> commands.run(DataGrid.Command.COPY) : null);
    // docs:end grid-command
    return copy;
  }

  static El fitted(Grids state) {
    // docs:start grid-fit
    List<Column> columns = List.of(
        new Column("id", state.fitted[0], DataType.INTEGER, row -> value(row, 0)),
        new Column("customer", state.fitted[1], DataType.TEXT, row -> value(row, 1))
            .detail(row -> (row * 7 % 30 + 1) + " orders"), // muted, after the name
        new Column("amount", state.fitted[2], DataType.DECIMAL, row -> value(row, 2))
            .detail(row -> "EUR"), // before the number, which stays at the right
        new Column("status", state.fitted[3], DataType.TEXT, row -> value(row, 3)));
    El grid = DataGrid.dataGrid(
            columns,
            COUNT,
            DataGrid.options()
                .select(state.selection)
                .onResize(state::resizeFitted) // the widths are the app's
                .fitColumns(state.fit)) // once; state.fit.reset() with a new result fits again
        .key("orders")
        .label("Orders")
        .grow(1);
    // docs:end grid-fit
    return Frame.of(Card.flush(grid).wFull().grow(1));
  }

  static Column placedAt(List<OffsetDateTime> moments) {
    // docs:start grid-tooltip
    ZoneId local = ZoneId.of("Europe/Berlin");
    Column placed = new Column(
            "placed_at",
            200,
            DataType.DATE_TIME,
            row -> moments.get(row).atZoneSameInstant(local).toOffsetDateTime().toString())
        .cellTooltip(row -> moments.get(row).toString()); // as the database returns it
    // docs:end grid-tooltip
    return placed;
  }

  // docs:start grid-open-handler
  void open(int row) {
    said = "Opened order " + value(row, 0);
  }
  // docs:end grid-open-handler

  static El opened(Grids state) {
    // docs:start grid-open
    List<Column> columns = List.of(
        new Column("id", 80, DataType.INTEGER, row -> value(row, 0)),
        new Column("customer", 120, DataType.TEXT, row -> value(row, 1)));
    El grid = DataGrid.dataGrid(
            columns,
            COUNT,
            DataGrid.options()
                .select(state.picked) // the grid takes the focus and its keys
                .onActivate(state::open)) // Enter, a double press
        .key("orders")
        .label("Orders")
        .grow(1);
    // docs:end grid-open
    return Frame.of(Card.flush(grid).wFull().grow(1), Label.label(state.said).key("said"));
  }

  static El sorted(Grids state) {
    // docs:start grid-sort
    int[] order = state.order();
    List<Column> all = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      int column = index;
      all.add(new Column(
          title(index),
          state.widths[index],
          index == 2 ? DataType.DECIMAL : DataType.TEXT,
          row -> value(order[row], column)));
    }
    El grid = DataGrid.dataGrid(
            all,
            COUNT,
            DataGrid.options()
                .select(state.selection)
                .sort(state.sort, state::sort) // the app sorts, see order()
                .onResize(state::resize)
                .columns(state.layout) // order and hidden columns
                .contextMenu(state.gridMenu, List.of()))
        .key("orders")
        .label("Orders")
        .grow(1);
    El toolbar = Card.toolbar("orders", COUNT + " rows", DataGrid.columnsButton(state.layout));
    // docs:end grid-sort
    return Frame.of(Card.flush(toolbar, grid).wFull().grow(1));
  }

  private static String title(int column) {
    return List.of("id", "customer", "amount", "status").get(column);
  }

  // docs:start grid-order
  /** The rows in the order of the sort keys, the first key first: what each row of the grid shows. */
  int[] order() {
    Comparator<Integer> by = (left, right) -> 0;
    for (DataGrid.SortKey key : sort) {
      Comparator<Integer> next = key.column() == 2
          ? Comparator.comparingDouble(row -> Double.parseDouble(value(row, 2)))
          : Comparator.comparing(row -> value(row, key.column()));
      by = by.thenComparing(key.descending() ? next.reversed() : next);
    }
    by = by.thenComparingInt(row -> row); // rows equal in every key keep their order
    return IntStream.range(0, COUNT).boxed().sorted(by).mapToInt(row -> row).toArray();
  }
  // docs:end grid-order

  static El edited(Grids state) {
    // docs:start grid-edit
    List<Column> columns = List.of(
        new Column("id", 80, DataType.INTEGER, state.changes.cell(0)),
        new Column("customer", 120, DataType.TEXT, state.changes.cell(1)).editable(),
        new Column("amount", 100, DataType.DECIMAL, state.changes.cell(2))
            .editable()
            .nullable(),
        new Column("status", 100, DataType.ENUM, state.changes.cell(3))
            .editable()
            .choices(List.of(STATUSES)));
    El grid = DataGrid.dataGrid(
            columns,
            state.changes.rowCount(),
            DataGrid.options()
                .select(state.selection)
                .changes(state.changes)) // edits, pastes, new and deleted rows, undo
        .key("orders")
        .label("Orders")
        .grow(1);
    int count = state.changes.count();
    El commit = Button.button(
        PRIMARY,
        count == 0 ? "Commit" : "Commit (" + count + ")",
        count == 0 ? null : state::commit);
    El revert = Button.button(SECONDARY, "Revert", count == 0 ? null : state.changes::clear);
    // docs:end grid-edit
    return Frame.of(
        Card.flush(Card.toolbar("orders", null, revert, commit), grid).wFull().grow(1));
  }

  // docs:start grid-edit-commit
  /** The Commit button: writes the changes into the rows. */
  void commit() {
    if (!selection.commitEdit()) {
      return; // the open editor refused its value: it stays open, the rows stay put
    }
    for (GridChanges.Change change : changes.changes()) { // the deletes first
      int row = (int) change.key(); // the row's index, for an insert below 0
      switch (change.kind()) {
        case DELETE -> rows.set(row, null); // removed after the updates
        case UPDATE -> change.values().forEach((column, value) -> rows.get(row)[column] = value);
        case INSERT -> {
          String[] order = new String[4];
          change.values().forEach((column, value) -> order[column] = value);
          order[0] = String.valueOf(nextId++); // as a database gives the key
          rows.add(order); // at the end: a database decides where it goes
        }
      }
    }
    rows.removeIf(Objects::isNull);
    // The rows hold the changes now: new GridChanges over them, with their new count.
    changes = new GridChanges(rows.size(), (row, column) -> rows.get(row)[column]);
    selection.clear(); // deleted rows moved the indexes the selection points at
  }
  // docs:end grid-edit-commit

  // docs:start typed-rows
  enum Status {
    PAID,
    PENDING,
    SHIPPED;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  static final Format<Status> STATUS = Format.choice(List.of(Status.values()), Status::label);

  record Order(long id, String customer, BigDecimal amount, LocalDate due, Status status) {
    Order withCustomer(String customer) {
      return new Order(id, customer, amount, due, status);
    }

    Order withAmount(BigDecimal amount) {
      return new Order(id, customer, amount, due, status);
    }

    Order withDue(LocalDate due) {
      return new Order(id, customer, amount, due, status);
    }

    Order withStatus(Status status) {
      return new Order(id, customer, amount, due, status);
    }
  }

  // The app's rows, records it replaces when one changes.
  final List<Order> orders = IntStream.range(0, COUNT)
      .mapToObj(row -> new Order(
          1000 + row,
          CUSTOMERS[row % CUSTOMERS.length],
          BigDecimal.valueOf(row * 3713L % 50_000, 2),
          row % 5 == 0 ? null : LocalDate.of(2026, 11, 1).plusDays(row % 60),
          Status.values()[row % 3]))
      .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
  // docs:end typed-rows
  List<DataGrid.SortKey> typedSort = List.of();

  // docs:start grid-typed-commit
  void sortOrders(GridTable<Order> table, List<Column> columns, List<DataGrid.SortKey> keys) {
    typedSort = keys;
    orders.sort(table.comparator(columns, keys)); // the app sorts its rows
  }

  String commitOrder(int row, Order order) {
    if (order.customer().isBlank()) {
      return "A customer is required"; // refused: the editor stays open
    }
    orders.set(row, order); // the new record from the wither
    return null;
  }
  // docs:end grid-typed-commit

  static El typed(Grids state) {
    // docs:start grid-typed
    // The rows in the order of the grid's sort keys, by their typed values.
    List<Order> rows = state.orders;
    GridTable<Order> table = DataGrid.table(rows::get);
    List<Column> columns = List.of(
        table.column("id", 80, Format.LONG, Order::id), // no wither: not editable
        table.column("customer", 120, Format.TEXT, Order::customer, Order::withCustomer),
        table.column("amount", 100, Format.DECIMAL, Order::amount, Order::withAmount),
        table.column("due", 110, Format.DATE.optional(), Order::due, Order::withDue),
        table.column("status", 90, STATUS, Order::status, Order::withStatus));
    El grid = DataGrid.dataGrid(
            columns,
            rows.size(),
            DataGrid.options()
                .select(state.selection)
                .sort(state.typedSort, keys -> state.sortOrders(table, columns, keys))
                .onCommit(table, state::commitOrder))
        .key("orders")
        .label("Orders")
        .grow(1);
    // docs:end grid-typed
    return Frame.of(Card.flush(grid).wFull().grow(1));
  }

  // docs:start grid-pojo
  /** A row of the app's with getters and setters. */
  static final class Account {
    private String owner;
    private BigDecimal balance;

    String getOwner() {
      return owner;
    }

    void setOwner(String owner) {
      this.owner = owner;
    }

    BigDecimal getBalance() {
      return balance;
    }

    void setBalance(BigDecimal balance) {
      this.balance = balance;
    }
  }

  static List<Column> accounts(GridTable<Account> table) {
    return List.of(
        table.column("owner", 120, Format.TEXT, Account::getOwner, Account::setOwner),
        table.column("balance", 100, Format.DECIMAL, Account::getBalance, Account::setBalance));
  }
  // docs:end grid-pojo

  // docs:start grid-typed-changes
  /** Writes what a GridChanges collected over the orders, as typed values. */
  static void write(GridChanges changes, GridTable<Order> table, List<Column> columns) {
    for (GridChanges.RowChange<Order> change : changes.changes(table, columns)) {
      switch (change.kind()) {
        case UPDATE -> {
          for (GridChanges.ValueChange<Order> value : change.values().values()) {
            if (value.after() instanceof BigDecimal amount) {
              System.out.println("amount " + value.before() + " -> " + amount);
            }
          }
          Order updated = change.apply(change.row()); // through the withers
          System.out.println("UPDATE " + updated);
        }
        case INSERT ->
          System.out.println("INSERT " + change.apply(new Order(0, "", null, null, null)));
        case DELETE -> System.out.println("DELETE " + change.row());
      }
    }
    changes.clear();
  }
  // docs:end grid-typed-changes

  static El filtered(Grids state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start grid-filter
    int[] rows = IntStream.range(0, COUNT)
        .filter(row ->
            state.filters.stream().allMatch(filter -> filter.matches(value(row, filter.column()))))
        .toArray();
    List<Column> columns = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      int column = index;
      columns.add(new Column(
          title(index),
          110,
          index == 2 ? DataType.DECIMAL : DataType.TEXT,
          row -> value(rows[row], column)));
    }
    El grid = DataGrid.dataGrid(
            columns,
            rows.length,
            DataGrid.options()
                .select(state.selection)
                .filters(state.filters, state::filters)) // the grid filters nothing itself
        .key("orders")
        .label("Orders")
        .grow(1);
    El bar = DataGrid.filterBar(columns, state.filters, state::filters); // null without any
    // docs:end grid-filter
    return Frame.of(Card.flush(
            Card.toolbar("orders", rows.length + " rows"),
            bar == null
                ? null
                : row()
                    .padding(
                        0, theme.space().l(), theme.space().m(), theme.space().l())
                    .child(bar),
            grid)
        .wFull()
        .grow(1));
  }

  static El empty(Grids state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start grid-empty
    int[] rows = IntStream.range(0, COUNT)
        .filter(row ->
            state.filters.stream().allMatch(filter -> filter.matches(value(row, filter.column()))))
        .toArray();
    List<Column> columns = List.of(
        new Column("id", 80, DataType.INTEGER, row -> value(rows[row], 0)),
        new Column("customer", 120, DataType.TEXT, row -> value(rows[row], 1)),
        new Column("amount", 100, DataType.DECIMAL, row -> value(rows[row], 2)),
        new Column("status", 100, DataType.TEXT, row -> value(rows[row], 3)));
    El empty = EmptyState.emptyState(
        Symbol.SEARCH,
        "No orders match",
        "Try another filter or clear the active ones.",
        Button.button(SECONDARY, "Clear filters", state::clearFilters));
    El grid = DataGrid.dataGrid(
            columns,
            rows.length,
            DataGrid.options()
                .select(state.selection)
                .filters(state.filters, state::filters)
                .empty(empty)) // shown centered under the header when there are no rows
        .key("orders")
        .label("Orders")
        .grow(1);
    // docs:end grid-empty
    El bar = DataGrid.filterBar(columns, state.filters, state::filters);
    return Frame.of(Card.flush(
            Card.toolbar("orders", rows.length + " rows"),
            bar == null
                ? null
                : row()
                    .padding(
                        0, theme.space().l(), theme.space().m(), theme.space().l())
                    .child(bar),
            grid)
        .wFull()
        .grow(1));
  }

  // docs:start tree-model
  record Node(String name, Symbol icon, List<Node> children) {
    Node(String name, Symbol icon) {
      this(name, icon, null); // a leaf
    }
  }

  static final Node SHOP = new Node(
      "shop",
      Symbol.DATABASE,
      List.of(
          new Node(
              "public",
              Symbol.FOLDER,
              List.of(new Node("orders", Symbol.TABLE), new Node("customers", Symbol.TABLE))),
          new Node("audit", Symbol.FOLDER, List.of(new Node("events", Symbol.TABLE)))));

  /** What the tree asks of the app's nodes. */
  static Tree.Model<Node> model() {
    return new Tree.Model<>() {
      @Override
      public List<Node> roots() {
        return List.of(SHOP);
      }

      @Override
      public Tree.Children<Node> children(Node node) { // asked for the open nodes alone
        return Tree.ready(node.children()); // Tree.loading() while not known yet
      }

      @Override
      public boolean isLeaf(Node node) {
        return node.children() == null;
      }

      @Override
      public String label(Node node) {
        return node.name();
      }

      @Override
      public El icon(Node node) {
        return Icon.icon(node.icon(), Icon.Variant.MUTED);
      }

      @Override
      public String detail(Node node) { // muted after the label; null for none
        int tables = node.icon() == Symbol.FOLDER ? node.children().size() : 0;
        return tables == 0 ? null : tables + (tables == 1 ? " table" : " tables");
      }

      @Override
      public boolean muted(Node node) { // the label in muted text, as the system's schemas
        return node.name().startsWith("pg_");
      }
    };
  }
  // docs:end tree-model

  // docs:start tree-failure
  /**
   * What {@link Tree.Model#children} returns for an open node whose children could not be read:
   * the row below the node says why, with an action that asks again.
   */
  static Tree.Children<Node> refused(String why, Runnable tryAgain) {
    return Tree.failed("Cannot read the tables: " + why, "Try again", tryAgain);
  }
  // docs:end tree-failure

  // docs:start tree-handlers
  void selected(Node value) {
    selected = value;
  }

  void expand(Node node, boolean open) {
    if (open) {
      this.open.add(node.name());
    } else {
      this.open.remove(node.name());
    }
  }

  void activate(Node node) {
    said = "open " + node.name();
  }
  // docs:end tree-handlers

  // docs:start tree-drag
  static boolean movable(Node node) { // the tables
    return node.icon() == Symbol.TABLE;
  }

  /** A table goes before or after another table, or into a schema. */
  static boolean fits(Tree.Move<Node> move) {
    Symbol target = move.target().icon();
    return move.place() == Tree.Place.INTO ? target == Symbol.FOLDER : target == Symbol.TABLE;
  }

  void move(Tree.Move<Node> move) {
    // The app takes the node out of its list and puts it before, after or into the target.
    said =
        "move " + move.node().name() + " " + move.place() + " " + move.target().name();
    selected = move.node(); // the rows follow it to where it went
  }
  // docs:end tree-drag

  static El tree(Grids state) {
    // docs:start tree
    El tree = Tree.tree(
            model(),
            new Tree.Options<Node>()
                .open(node -> state.open.contains(node.name()), state::expand) // open or close
                .select(state.selected, state::selected) // the selection
                .onActivate(state::activate) // Enter, a double press
                .drag(state.dragging, Grids::movable, Grids::fits, state::move)) // drag and drop
        .key("schema")
        .label("Schema")
        .grow(1);
    // docs:end tree
    return Frame.of(Card.inset(tree).w(260).grow(1));
  }

  // docs:start grid-tree-model
  record SchemaNode(
      long id, String name, Symbol icon, long rows, long megabytes, List<SchemaNode> children) {
    SchemaNode(long id, String name, long rows, long megabytes) {
      this(id, name, Symbol.TABLE, rows, megabytes, null); // a leaf
    }
  }

  static final SchemaNode OVERVIEW = new SchemaNode(
      1,
      "shop",
      Symbol.DATABASE,
      5_620_000,
      1_434,
      List.of(
          new SchemaNode(
              10,
              "public",
              Symbol.FOLDER,
              1_420_000,
              354,
              List.of(
                  new SchemaNode(11, "orders", 1_000_000, 248),
                  new SchemaNode(12, "customers", 50_000, 18),
                  new SchemaNode(13, "invoices", 320_000, 64))),
          new SchemaNode(
              20,
              "audit",
              Symbol.FOLDER,
              4_200_000,
              1_080,
              List.of(new SchemaNode(21, "events", 4_200_000, 1_080)))));

  Tree.Model<SchemaNode> schemaModel() {
    return new Tree.Model<>() {
      @Override
      public List<SchemaNode> roots() {
        return List.of(OVERVIEW);
      }

      @Override
      public Tree.Children<SchemaNode> children(SchemaNode node) {
        return Tree.ready(sortSiblings(node.children())); // sort within siblings
      }

      @Override
      public boolean isLeaf(SchemaNode node) {
        return node.children() == null;
      }

      @Override
      public String label(SchemaNode node) {
        return node.name();
      }
    };
  }

  List<SchemaNode> sortSiblings(List<SchemaNode> nodes) {
    if (schemaSort.isEmpty()) {
      return nodes;
    }
    Comparator<SchemaNode> by = (left, right) -> 0;
    for (DataGrid.SortKey key : schemaSort) {
      Comparator<SchemaNode> next = switch (key.column()) {
        case 0 -> Comparator.comparing(SchemaNode::name);
        case 1 -> Comparator.comparingLong(SchemaNode::rows);
        default -> Comparator.comparingLong(SchemaNode::megabytes);
      };
      by = by.thenComparing(key.descending() ? next.reversed() : next);
    }
    return nodes.stream().sorted(by).toList();
  }

  void toggleSchema(SchemaNode node, boolean shouldOpen) {
    if (shouldOpen) {
      schemaOpen.add(node.id());
    } else {
      schemaOpen.remove(node.id());
    }
  }

  void sortSchema(List<DataGrid.SortKey> keys) {
    schemaSort = keys;
  }
  // docs:end grid-tree-model

  static El treeRows(Grids state) {
    // docs:start grid-tree
    Tree.Rows<SchemaNode> rows =
        Tree.rows(state.schemaModel(), node -> state.schemaOpen.contains(node.id()));
    List<Column> columns = List.of(
        new Column("name", 220, DataType.TEXT, row -> rows.node(row).name())
            .cellIcon(row -> DataGrid.CellIcon.before(rows.node(row).icon(), Icon.Variant.MUTED)),
        new Column(
            "rows",
            110,
            DataType.INTEGER,
            row -> String.format(Locale.ROOT, "%,d", rows.node(row).rows())),
        new Column(
            "size",
            90,
            DataType.INTEGER,
            row -> String.format(Locale.ROOT, "%,d MB", rows.node(row).megabytes())));
    El grid = DataGrid.dataGrid(
            columns,
            rows.count(),
            DataGrid.options()
                .tree(rows, state::toggleSchema) // tree in the first column
                .select(state.schemaSelection)
                .rowKey(row -> rows.node(row).id()) // keep selection on its node across toggles
                .sort(state.schemaSort, state::sortSchema)
                .copy(state.app.clipboard()))
        .key("schema")
        .label("Schema")
        .grow(1);
    // docs:end grid-tree
    return Frame.of(Card.flush(Card.toolbar("Schemas", rows.count() + " rows"), grid)
        .wFull()
        .grow(1));
  }
}
