package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import com.rethinkj.ui.text.TextRendering;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * A result set of orders as a database tool holds it: the rows, made up from their index, and the
 * grid's state, which the grid's handlers change and log. The view puts the grid in a window with
 * a margin, which leaves room for the focus ring. A test turns on more of the grid with {@link
 * #more}, which gets the options the grid has here.
 */
final class Orders {

  private static final String[] FIRST = {
    "Ada", "Grace", "Linus", "Margaret", "Ken", "Barbara", "Edsger", "Frances", "Donald", "Radia"
  };
  private static final String[] LAST = {
    "Lovelace",
    "Hopper",
    "Torvalds",
    "Hamilton",
    "Thompson",
    "Liskov",
    "Dijkstra",
    "Allen",
    "Knuth",
    "Perlman"
  };
  private static final String[] STATUS = {"paid", "pending", "shipped", "refunded"};
  private static final LocalDateTime START = LocalDateTime.of(2026, 3, 1, 8, 0);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  static final String NOTE =
      "Customer asked to split the delivery into two parcels and to leave them next door";

  /** The seven columns of an order, repeated beyond seven with a number after the title. */
  private static final List<Column> BASE = List.of(
      new Column("id", 64, Align.RIGHT, true, i -> String.valueOf(i + 1)),
      new Column("customer", 140, Align.LEFT, false, Orders::customer),
      new Column(
          "email",
          200,
          Align.LEFT,
          false,
          i -> customer(i).toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com"),
      new Column(
          "created_at", 150, Align.LEFT, true, i -> START.plusSeconds(i * 97L).format(TIME)),
      new Column(
          "amount",
          90,
          Align.RIGHT,
          true,
          i -> i % 11 == 5
              ? null
              : String.format(Locale.ROOT, "%d.%02d", i * 7919 % 250_000 / 100, i * 13 % 100)),
      new Column("status", 80, Align.LEFT, false, i -> STATUS[i * 7 % STATUS.length]),
      new Column("note", 240, Align.LEFT, false, i -> i % 3 != 1 ? null : NOTE));

  // Paid, not paid, and NULL as a marker; a status as a coloured mark before its text.
  static final CellIcon PAID = CellIcon.instead(Symbol.CHECKMARK, Icon.Variant.SUCCESS);
  static final CellIcon UNPAID = CellIcon.instead(Symbol.SUBTRACT, Icon.Variant.MUTED);
  static final CellIcon UNKNOWN = CellIcon.instead(Symbol.PROHIBITED, Icon.Variant.MUTED);
  static final CellIcon LINK =
      CellIcon.before(Symbol.LINK, Icon.Variant.MUTED).tooltip("References customers.id");
  private static final CellIcon[] MARKS = {
    CellIcon.before(Symbol.RECORD, Icon.Variant.SUCCESS),
    CellIcon.before(Symbol.RECORD, Icon.Variant.WARNING),
    CellIcon.before(Symbol.RECORD, Icon.Variant.SUCCESS),
    CellIcon.before(Symbol.RECORD, Icon.Variant.DANGER)
  };

  /**
   * Orders as a database tool shows a table: a type icon and the type's name in each header, a
   * key on the id, a link before the customer's id, paid as a check, a dash or the NULL marker
   * alone, and the status after a coloured mark. Repeated beyond eight as {@link #BASE} is.
   */
  private static final List<Column> TYPED = List.of(
      new Column("id", 64, DataType.INTEGER, i -> String.valueOf(i + 1))
          .typeName("bigint")
          .icon(Symbol.KEY, Icon.Variant.WARNING),
      new Column("customer_id", 128, DataType.INTEGER, i -> String.valueOf(1 + i * 7919 % 5000))
          .typeName("integer")
          .icon(Symbol.LINK)
          .cellIcon(i -> LINK),
      new Column("customer", 140, DataType.TEXT, Orders::customer).typeName("text"),
      new Column("paid", 80, DataType.BOOLEAN, Orders::paid)
          .typeName("boolean")
          .cellIcon(i -> paid(i) == null ? UNKNOWN : paid(i).equals("true") ? PAID : UNPAID),
      new Column("status", 130, DataType.ENUM, i -> STATUS[i * 7 % STATUS.length])
          .typeName("order_status")
          .cellIcon(i -> MARKS[i * 7 % STATUS.length]),
      new Column(
              "amount",
              100,
              DataType.DECIMAL,
              i -> i % 11 == 5
                  ? null
                  : String.format(Locale.ROOT, "%d.%02d", i * 7919 % 250_000 / 100, i * 13 % 100))
          .typeName("numeric(10,2)"),
      new Column(
              "created_at",
              190,
              DataType.DATE_TIME,
              i -> START.plusSeconds(i * 97L).format(TIME))
          .typeName("timestamptz"),
      new Column("note", 240, DataType.TEXT, i -> i % 3 != 1 ? null : NOTE).typeName("text"));

  final Theme theme;
  final List<Column> base;
  int count;
  final Selection selection;
  final float[] widths;
  List<SortKey> sort = List.of(new SortKey(0, false));
  final Menu.State menu = new Menu.State();
  UnaryOperator<DataGrid.Options> more = o -> o;
  int unsortable = -1; // a column that cannot be sorted
  boolean bare; // the columns without their icons and types
  boolean editable; // the columns' cells editable as text
  int loaded = Integer.MAX_VALUE; // the rows below this one are loaded, with cells()
  App<Orders> app;
  final List<String> log = new ArrayList<>();
  final List<Integer> asked = new ArrayList<>(); // the rows whose cells were asked for
  final List<Integer> askedIcons = new ArrayList<>(); // the rows whose cell icons were asked for
  // The details of the columns that have one, by their index among the base columns.
  final java.util.Map<Integer, IntFunction<String>> details = new java.util.HashMap<>();
  final List<Integer> askedDetails = new ArrayList<>(); // the rows whose details were asked for

  Orders(Theme theme, int count) {
    this(theme, count, BASE.size());
  }

  Orders(Theme theme, int count, int columns) {
    this(theme, count, columns, Mode.ROWS);
  }

  Orders(Theme theme, int count, int columns, Mode mode) {
    this(theme, count, columns, mode, BASE);
  }

  private Orders(Theme theme, int count, int columns, Mode mode, List<Column> base) {
    this.theme = theme;
    this.base = base;
    this.count = count;
    this.selection = new Selection(mode);
    selection.select(2, 0);
    this.widths = new float[columns];
    for (int c = 0; c < columns; c++) {
      widths[c] = base.get(c % base.size()).width();
    }
  }

  /** {@link #TYPED} in cells, with row numbers, the id and the customer's id frozen, copying. */
  static Orders typed(Theme theme, int columns) {
    Orders orders = new Orders(theme, 1_000_000, columns, Mode.CELLS, TYPED);
    orders.more = o -> o.rowNumbers().frozen(2).copy(orders.app.clipboard());
    return orders;
  }

  /** Whether the order is paid, "true" or "false", or null where nobody knows yet. */
  static String paid(int i) {
    return i % 7 == 6 ? null : String.valueOf(i % 3 != 1);
  }

  static String customer(int i) {
    return FIRST[i % FIRST.length] + " " + LAST[i / FIRST.length % LAST.length];
  }

  List<Column> columns() {
    List<Column> columns = new ArrayList<>();
    for (int c = 0; c < widths.length; c++) {
      Column column = base.get(c % base.size());
      IntFunction<String> cell = column.cell();
      IntFunction<CellIcon> icons = column.cellIcon();
      Column made = new Column(
          c < base.size() ? column.title() : column.title() + "_" + c / base.size(),
          widths[c],
          column.align(),
          column.mono(),
          c != unsortable,
          i -> {
            asked.add(i);
            return cell.apply(i);
          },
          bare ? null : column.icon(),
          column.iconVariant(),
          bare ? null : column.typeName(),
          bare || icons == null
              ? null
              : i -> {
                askedIcons.add(i);
                return icons.apply(i);
              });
      IntFunction<String> detail = details.get(c % base.size());
      if (detail != null) {
        made = made.detail(i -> {
          askedDetails.add(i);
          return detail.apply(i);
        });
      }
      columns.add(editable ? made.editable() : made);
    }
    return columns;
  }

  El view() {
    return col()
        .wFull()
        .hFull()
        .padding(theme.space().m())
        .fill(theme.surface().panel())
        .child(grid().key("orders").label("Orders").grow(1));
  }

  El grid() {
    return dataGrid(
        theme,
        columns(),
        count,
        more.apply(DataGrid.options()
            .select(selection)
            .onSelect(() -> log.add("select " + selection.row()))
            .onResize((c, w) -> {
              log.add("resize " + c + " " + w);
              widths[c] = w;
            })
            .sort(sort, keys -> {
              log.add("sort "
                  + keys.stream()
                      .map(k -> (k.descending() ? "-" : "+") + k.column())
                      .toList());
              sort = keys;
            })));
  }

  /**
   * Orders in cells, with row numbers and the id frozen, copying onto the app's clipboard, a
   * context menu with an item of the app's, and the first 1000 rows loaded: the grid of cells.txt.
   */
  static Orders cells(Theme theme) {
    Orders orders = new Orders(theme, 1_000_000, BASE.size(), Mode.CELLS);
    orders.loaded = 1000;
    orders.more = o -> o.rowNumbers()
        .frozen(1)
        .copy(orders.app.clipboard())
        .contextMenu(orders.menu, List.of(Menu.item("Export…", () -> orders.log.add("export"))))
        .loading(
            i -> i < orders.loaded, (first, last) -> orders.log.add("rows " + first + "-" + last));
    return orders;
  }

  /** The size of a note, as the detail beside it shows it: {@code 82 B}; none without a note. */
  static String noteSize(int i) {
    return i % 3 != 1 ? null : Format.BYTES.text((long) NOTE.length());
  }

  /**
   * {@link #typed} with details: the size beside each note, and the currency before each amount,
   * whose column is wide enough for both.
   */
  static Orders detailed(Theme theme, int columns) {
    Orders orders = typed(theme, columns);
    orders.details.put(7, Orders::noteSize);
    orders.details.put(5, i -> i % 11 == 5 ? null : "EUR");
    orders.widths[5] = 130;
    return orders;
  }

  /** The row the cursor is on, as the tests of a grid of rows read it. */
  int selected() {
    return selection.row();
  }

  App<Orders> app() {
    app = new App<>(this, Orders::view);
    app.textRendering(TextRendering.LINUX);
    return app;
  }
}
