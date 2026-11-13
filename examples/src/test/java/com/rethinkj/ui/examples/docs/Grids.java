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
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.GridTable;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tree;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

/** The examples of the pages about the data grid and the tree. */
final class Grids {

  static final String[] CUSTOMERS = {"Ada", "Brendan", "Chen", "Dagny", "Emeka", "Farah", "Gus"};
  static final String[] STATUSES = {"paid", "pending", "shipped", "refunded"};
  static final int COUNT = 1000;

  final Theme t = Theme.DARK;
  App<?> app; // set by whoever makes the app: the grid copies to its clipboard
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final Menu.State gridMenu = new Menu.State();
  List<DataGrid.SortKey> sort = List.of();
  final float[] widths = {80, 120, 100, 100};
  final DataGrid.Layout layout = new DataGrid.Layout(List.of("id", "customer", "amount", "status"));
  List<DataGrid.Filter> filters = List.of();
  final GridChanges changes = new GridChanges(COUNT, Grids::value);
  final Set<String> open = new HashSet<>(List.of("shop", "public"));
  Node selected;
  String said = "";

  static List<DataGrid.Selection> modes() {
    // docs:start grid-modes
    var rows = new DataGrid.Selection(DataGrid.Mode.ROWS); // whole rows, as results are read
    var cells = new DataGrid.Selection(DataGrid.Mode.CELLS); // cells, and rows by their numbers
    // docs:end grid-modes
    return List.of(rows, cells);
  }

  /** The made-up orders: row {@code i}, column {@code c}. */
  static String value(int i, int c) {
    return switch (c) {
      case 0 -> String.valueOf(1000 + i);
      case 1 -> CUSTOMERS[i % CUSTOMERS.length];
      case 2 -> String.format(Locale.ROOT, "%d.%02d", i * 37 % 500, i * 13 % 100);
      default -> STATUSES[i % STATUSES.length];
    };
  }

  static El basic(Grids s) {
    Theme t = s.t;
    // docs:start grid
    List<Column> columns = List.of(
        new Column("id", 80, DataType.INTEGER, i -> value(i, 0))
            .typeName("bigint")
            .icon(Symbol.KEY, Icon.Variant.WARNING),
        new Column("customer", 120, DataType.TEXT, i -> value(i, 1)),
        new Column("amount", 100, DataType.DECIMAL, i -> value(i, 2)).typeName("numeric"),
        new Column("status", 100, DataType.TEXT, i -> value(i, 3)));
    El grid = DataGrid.dataGrid(
            t,
            columns,
            COUNT,
            DataGrid.options()
                .select(s.selection) // the app keeps the selection
                .rowNumbers()
                .frozen(1) // numbers and the id stay at the left
                .copy(s.app.clipboard())) // Control+C copies tab-separated text
        .key("orders")
        .label("Orders")
        .grow(1);
    // docs:end grid
    return Frame.of(t, Card.flush(t, grid).wFull().grow(1));
  }

  static El sorted(Grids s) {
    Theme t = s.t;
    // docs:start grid-sort
    int[] order = s.order();
    List<Column> all = new ArrayList<>();
    for (int c = 0; c < 4; c++) {
      int column = c;
      all.add(new Column(
          title(c),
          s.widths[c],
          c == 2 ? DataType.DECIMAL : DataType.TEXT,
          i -> value(order[i], column)));
    }
    El grid = DataGrid.dataGrid(
            t,
            all,
            COUNT,
            DataGrid.options()
                .select(s.selection)
                .sort(s.sort, keys -> s.sort = keys) // the app sorts, see order()
                .onResize((column, width) -> s.widths[column] = width)
                .columns(s.layout) // order and hidden columns
                .contextMenu(s.gridMenu, List.of()))
        .key("orders")
        .label("Orders")
        .grow(1);
    El toolbar = Card.toolbar(t, "orders", COUNT + " rows", DataGrid.columnsButton(t, s.layout));
    // docs:end grid-sort
    return Frame.of(t, Card.flush(t, toolbar, grid).wFull().grow(1));
  }

  private static String title(int c) {
    return List.of("id", "customer", "amount", "status").get(c);
  }

  // docs:start grid-order
  /** The rows in the order of the first sort key: what row i of the grid shows. */
  int[] order() {
    Comparator<Integer> by = Comparator.comparingInt(i -> i);
    if (!sort.isEmpty()) {
      DataGrid.SortKey key = sort.getFirst();
      by = key.column() == 2
          ? Comparator.comparingDouble(i -> Double.parseDouble(value(i, 2)))
          : Comparator.comparing(i -> value(i, key.column()));
      if (key.descending()) {
        by = by.reversed();
      }
    }
    return IntStream.range(0, COUNT).boxed().sorted(by).mapToInt(i -> i).toArray();
  }
  // docs:end grid-order

  static El edited(Grids s) {
    Theme t = s.t;
    // docs:start grid-edit
    List<Column> columns = List.of(
        new Column("id", 80, DataType.INTEGER, s.changes.cell(0)),
        new Column("customer", 120, DataType.TEXT, s.changes.cell(1)).editable(),
        new Column("amount", 100, DataType.DECIMAL, s.changes.cell(2))
            .editable()
            .nullable(),
        new Column("status", 100, DataType.ENUM, s.changes.cell(3))
            .editable()
            .choices(List.of(STATUSES)));
    El grid = DataGrid.dataGrid(
            t,
            columns,
            s.changes.rowCount(),
            DataGrid.options()
                .select(s.selection)
                .changes(s.changes)) // edits, pastes, new and deleted rows, undo
        .key("orders")
        .label("Orders")
        .grow(1);
    int count = s.changes.count();
    El commit = Button.button(
        t,
        PRIMARY,
        count == 0 ? "Commit" : "Commit (" + count + ")",
        count == 0
            ? null
            : () -> {
              s.said = s.changes.changes().toString(); // UPDATE, INSERT and DELETE to write
              s.changes.clear();
            });
    El revert = Button.button(t, SECONDARY, "Revert", count == 0 ? null : s.changes::clear);
    // docs:end grid-edit
    return Frame.of(
        t,
        Card.flush(t, Card.toolbar(t, "orders", null, revert, commit), grid)
            .wFull()
            .grow(1));
  }

  // docs:start typed-rows
  enum Status {
    PAID,
    PENDING,
    SHIPPED;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  static final Format<Status> STATUS = Format.choice(Status.values(), Status::label);

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
      .mapToObj(i -> new Order(
          1000 + i,
          CUSTOMERS[i % CUSTOMERS.length],
          BigDecimal.valueOf(i * 3713L % 50_000, 2),
          i % 5 == 0 ? null : LocalDate.of(2026, 11, 1).plusDays(i % 60),
          Status.values()[i % 3]))
      .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
  // docs:end typed-rows
  List<DataGrid.SortKey> typedSort = List.of();

  static El typed(Grids s) {
    Theme t = s.t;
    // docs:start grid-typed
    // The rows in the order of the grid's sort keys, by their typed values.
    List<Order> rows = s.orders;
    GridTable<Order> table = DataGrid.table(rows::get);
    List<Column> columns = List.of(
        table.column("id", 80, Format.LONG, Order::id), // no wither: not editable
        table.column("customer", 120, Format.TEXT, Order::customer, Order::withCustomer),
        table.column("amount", 100, Format.DECIMAL, Order::amount, Order::withAmount),
        table.column("due", 110, Format.DATE.optional(), Order::due, Order::withDue),
        table.column("status", 90, STATUS, Order::status, Order::withStatus));
    El grid = DataGrid.dataGrid(
            t,
            columns,
            rows.size(),
            DataGrid.options()
                .select(s.selection)
                .sort(s.typedSort, keys -> {
                  s.typedSort = keys;
                  s.orders.sort(table.comparator(columns, keys)); // the app sorts its rows
                })
                .onCommit(table, (row, order) -> {
                  if (order.customer().isBlank()) {
                    return "A customer is required"; // refused: the editor stays open
                  }
                  s.orders.set(row, order); // the new record from the wither
                  return null;
                }))
        .key("orders")
        .label("Orders")
        .grow(1);
    // docs:end grid-typed
    return Frame.of(t, Card.flush(t, grid).wFull().grow(1));
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
          for (GridChanges.ValueChange<Order> v : change.values().values()) {
            if (v.after() instanceof BigDecimal amount) {
              System.out.println("amount " + v.before() + " -> " + amount);
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

  static El filtered(Grids s) {
    Theme t = s.t;
    // docs:start grid-filter
    int[] rows = IntStream.range(0, COUNT)
        .filter(i -> s.filters.stream().allMatch(f -> f.matches(value(i, f.column()))))
        .toArray();
    List<Column> columns = new ArrayList<>();
    for (int c = 0; c < 4; c++) {
      int column = c;
      columns.add(new Column(
          title(c), 110, c == 2 ? DataType.DECIMAL : DataType.TEXT, i -> value(rows[i], column)));
    }
    El grid = DataGrid.dataGrid(
            t,
            columns,
            rows.length,
            DataGrid.options()
                .select(s.selection)
                .filters(s.filters, f -> s.filters = f)) // the grid filters nothing itself
        .key("orders")
        .label("Orders")
        .grow(1);
    El bar = DataGrid.filterBar(t, columns, s.filters, f -> s.filters = f); // null without any
    // docs:end grid-filter
    return Frame.of(
        t,
        Card.flush(
                t,
                Card.toolbar(t, "orders", rows.length + " rows"),
                bar == null
                    ? null
                    : row()
                        .padding(0, t.space().l(), t.space().m(), t.space().l())
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
  static Tree.Model<Node> model(Theme t) {
    return new Tree.Model<>() {
      @Override
      public List<Node> roots() {
        return List.of(SHOP);
      }

      @Override
      public List<Node> children(Node node) {
        return node.children(); // null while not known: the tree shows a loading row
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
        return Icon.icon(t, node.icon(), Icon.Variant.MUTED);
      }
    };
  }
  // docs:end tree-model

  static El tree(Grids s) {
    Theme t = s.t;
    // docs:start tree
    El tree = Tree.tree(
            t,
            model(t),
            node -> s.open.contains(node.name()), // which nodes are open
            s.selected,
            node -> s.selected = node, // the selection
            (node, open) -> { // open or close one
              if (open) {
                s.open.add(node.name());
              } else {
                s.open.remove(node.name());
              }
            },
            node -> s.said = "open " + node.name()) // Enter, a double press
        .key("schema")
        .label("Schema")
        .grow(1);
    // docs:end tree
    return Frame.of(t, Card.inset(t, tree).w(260).grow(1));
  }
}
