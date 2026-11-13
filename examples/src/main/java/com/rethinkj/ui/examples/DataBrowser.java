package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.examples.TitleBar.titleBar;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.Label.Variant.MUTED;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textField;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Icon.Variant;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import com.rethinkj.ui.kit.Toast;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * A million orders in the kit's data grid, with a filter above it and a status line below, as a
 * database tool shows a table: cells to select and copy, row numbers and the id frozen at the
 * left, a context menu, and sorting by id. The rows are made up from their index; nothing is
 * stored. Every text column repeats after {@link #PERIOD} rows, so the filter tests the first
 * thousand rows only and finds the others by arithmetic, at the same speed for any filter. It
 * keeps the rows that hold each of its words in a text column; numbers and dates are left out of
 * it. The order at the cursor stays selected while the filter or the sort moves it, and while the
 * filter hides it.
 */
public final class DataBrowser {

  static final int ROWS = 1_000_000;
  static final int PERIOD = 1000;

  private static final String[] FIRST = {
    "Ada", "Grace", "Linus", "Margaret", "Ken", "Barbara", "Edsger", "Frances", "Donald", "Radia"};
  private static final String[] LAST = {
    "Lovelace", "Hopper", "Torvalds", "Hamilton", "Thompson", "Liskov", "Dijkstra", "Allen",
    "Knuth", "Perlman"};
  private static final String[] DOMAIN = {
    "example.com", "example.org", "example.net", "mail.example", "shop.example"};
  private static final String[] CITY = {
    "Berlin", "Paris", "Lisbon", "Oslo", "Vienna", "Madrid", "Dublin", "Prague", "Warsaw", "Rome"};
  private static final String[] COUNTRY = {"DE", "FR", "PT", "NO", "AT", "ES", "IE", "CZ", "PL", "IT"};
  private static final String[] PRODUCT = {
    "Keyboard", "Monitor", "Laptop", "Mouse", "Headset", "Dock", "Webcam", "Cable"};
  private static final int[] CENTS = {4990, 22900, 129900, 1990, 8900, 17900, 6900, 990};
  private static final String[] STATUS = {"paid", "pending", "shipped", "refunded"};
  private static final String[] NOTE = {
    null, "Leave at the door", null, null, "Gift wrap", null, "Call before delivery", null};
  private static final LocalDateTime START = LocalDateTime.of(2026, 1, 5, 8, 0);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  /**
   * A column of the table: its kind, its cell from a row's index, and whether the filter looks at
   * it.
   */
  private record Field(
      String title, float width, DataType kind, boolean text, IntFunction<String> cell) {}

  private static final List<Field> FIELDS = List.of(
      new Field("id", 72, DataType.INTEGER, false, i -> String.valueOf(i + 1)),
      new Field("customer", 140, DataType.TEXT, true, DataBrowser::customer),
      new Field("email", 220, DataType.TEXT, true, i -> customer(i).toLowerCase(Locale.ROOT)
          .replace(' ', '.') + "@" + DOMAIN[i / 100 % DOMAIN.length]),
      new Field("city", 90, DataType.TEXT, true, i -> CITY[i / 4 % CITY.length]),
      new Field("country", 80, DataType.TEXT, true, i -> COUNTRY[i / 4 % COUNTRY.length]),
      new Field("product", 100, DataType.TEXT, true, i -> PRODUCT[product(i)]),
      new Field("quantity", 80, DataType.INTEGER, false, i -> String.valueOf(quantity(i))),
      new Field("amount", 100, DataType.DECIMAL, false, i -> {
        int cents = quantity(i) * CENTS[product(i)];
        return String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
      }),
      new Field("created_at", 140, DataType.DATE_TIME, false, i -> created(i).format(TIME)),
      new Field("shipped_at", 140, DataType.DATE_TIME, false, i -> !status(i).equals("shipped")
          ? null : created(i).plusMinutes(i * 7919L % 4320).format(TIME)),
      new Field("status", 90, DataType.ENUM, true, DataBrowser::status),
      new Field("note", 180, DataType.TEXT, true, i -> NOTE[i % NOTE.length]));
  // A coloured mark before each status, in the order of STATUS: constants, allocated once.
  private static final CellIcon[] MARKS = {
    CellIcon.before(Symbol.RECORD, Variant.SUCCESS), CellIcon.before(Symbol.RECORD, Variant.WARNING),
    CellIcon.before(Symbol.RECORD, Variant.NORMAL), CellIcon.before(Symbol.RECORD, Variant.DANGER)};

  String filter = "";
  int[] matching = all(); // the rows below PERIOD that the filter keeps, in order
  final Selection selection = new Selection(Mode.CELLS); // in the places the grid shows
  int selected = -1; // the order at the cursor, also while the filter hides it
  boolean descending; // by id
  final float[] widths = new float[FIELDS.size()];
  final Menu.State menu = new Menu.State();
  String toast; // a message at the bottom of the window, or null
  App<DataBrowser> app;
  static final long COPY_LIMIT = 100_000; // the grid's own limit, said in the toast

  DataBrowser() {
    for (int c = 0; c < widths.length; c++) {
      widths[c] = FIELDS.get(c).width();
    }
  }

  static String customer(int i) {
    return FIRST[i % FIRST.length] + " " + LAST[i / FIRST.length % LAST.length];
  }

  static int product(int i) {
    return i * 3 % PRODUCT.length;
  }

  static int quantity(int i) {
    return 1 + i * 13 % 9;
  }

  static LocalDateTime created(int i) {
    return START.plusSeconds(i * 29L);
  }

  static String status(int i) {
    return STATUS[i * 7 % STATUS.length];
  }

  private static int[] all() {
    int[] rows = new int[PERIOD];
    Arrays.setAll(rows, i -> i);
    return rows;
  }

  void filter(String value) {
    filter = value;
    String[] words = value.toLowerCase(Locale.ROOT).trim().split("\\s+");
    int[] found = new int[PERIOD];
    int count = 0;
    for (int r = 0; r < PERIOD; r++) {
      if (matches(r, words)) {
        found[count++] = r;
      }
    }
    matching = Arrays.copyOf(found, count);
    follow();
  }

  /** After the filter or the sort moved the rows: the cursor goes where its order is shown. */
  private void follow() {
    int column = Math.max(selection.column(), 0);
    if (selected >= 0 && placeOf(selected) >= 0) {
      selection.select(placeOf(selected), column);
    } else {
      selection.clear();
    }
  }

  /** The grid moved the cursor: its order is the selected one. */
  void selected() {
    selected = selection.row() < 0 ? -1 : orderAt(selection.row());
  }

  void sort(List<SortKey> keys) {
    descending = keys.contains(new SortKey(0, true)); // none is by id, ascending
    follow();
  }

  private static boolean matches(int row, String[] words) {
    // A word has no line feed, so it cannot match across two columns.
    String texts = String.join("\n", texts(row)).toLowerCase(Locale.ROOT);
    for (String word : words) {
      if (!texts.contains(word)) {
        return false;
      }
    }
    return true;
  }

  /** The row's text columns that are not NULL: what the filter looks at. */
  static List<String> texts(int row) {
    List<String> texts = new ArrayList<>();
    for (Field field : FIELDS) {
      String text = field.text() ? field.cell().apply(row) : null;
      if (text != null) {
        texts.add(text);
      }
    }
    return texts;
  }

  /** How many rows the filter keeps; ROWS is a multiple of PERIOD. */
  int count() {
    return ROWS / PERIOD * matching.length;
  }

  /** The row shown at this place of the grid. */
  int orderAt(int shown) {
    int k = descending ? count() - 1 - shown : shown;
    return k / matching.length * PERIOD + matching[k % matching.length];
  }

  /** The place of a row in the grid, or -1 if the filter hides it. */
  int placeOf(int row) {
    int at = row < 0 ? -1 : Arrays.binarySearch(matching, row % PERIOD);
    if (at < 0) {
      return -1;
    }
    int k = row / PERIOD * matching.length + at;
    return descending ? count() - 1 - k : k;
  }

  String status() {
    String rows = String.format(Locale.ROOT, "%,d of %,d orders", count(), ROWS);
    long many = selection.rowCount();
    if (many > 1) {
      return rows + String.format(Locale.ROOT, " · %,d orders selected", many);
    }
    if (selected < 0) {
      return rows;
    }
    return rows + " · order " + (selected + 1)
        + (placeOf(selected) < 0 ? " selected, hidden by the filter" : " selected");
  }

  public static void main(String[] args) throws IOException {
    DataBrowser browser = new DataBrowser();
    App<DataBrowser> app = new App<>(browser, DataBrowser::view);
    browser.app = app;
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(DataBrowser.class, "Orders", 0xFF2563EB, Symbol.TABLE)
        .frameless().minimumSize(480, 320);
    try (Window window = Window.open("Orders", 1000, 600, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  static El view(DataBrowser s) {
    Theme t = Theme.DARK;
    List<Column> columns = new ArrayList<>();
    for (int c = 0; c < FIELDS.size(); c++) {
      Field f = FIELDS.get(c);
      Column column = new Column(f.title(), s.widths[c], f.kind(),
          shown -> f.cell().apply(s.orderAt(shown))).sortable(c == 0); // id sorts
      columns.add(switch (f.title()) {
        case "id" -> column.icon(Symbol.KEY, Variant.WARNING);
        case "status" -> column.cellIcon(shown -> MARKS[s.orderAt(shown) * 7 % STATUS.length]);
        default -> column;
      });
    }
    DataGrid.Options options = DataGrid.options()
        .select(s.selection).onSelect(s::selected)
        .sort(List.of(new SortKey(0, s.descending)), s::sort)
        .onResize((c, width) -> s.widths[c] = width)
        .rowNumbers().frozen(1)
        .contextMenu(s.menu, List.of());
    if (s.app != null) { // the window's clipboard once it runs, the app's own before
      options.copy(s.app.clipboard());
    }
    // The grid copies nothing beyond its limit; a toast says why instead of nothing happening.
    options.copyLimit(COPY_LIMIT, copy -> s.toast = String.format(Locale.ROOT,
        "%,d cells are too many to copy at once: at most %,d", copy.cells(), COPY_LIMIT));
    El grid = dataGrid(t, columns, s.count(), options);
    El filter = TextField.withIcon(t, textField(t, s.filter, "Filter", s::filter).key("filter")
        .w(280), Symbol.SEARCH);
    filter = Tooltip.tooltip(t, filter, "Words in customer, email, city, country, product,"
        + " status or note");
    return col().wFull().hFull().fill(t.surface().window())
        .overlay(s.toast == null ? null : Toast.toast(t, s.toast, null, null, () -> s.toast = null))
        .child(titleBar(t, "Orders", t.accent().color()))
        .child(col().grow(1).minSize(0, 0).padding(0, t.space().m(), t.space().m(), t.space().m())
            .child(Card.flush(t,
                Card.toolbar(t, "orders", String.format(Locale.ROOT, "%,d rows", s.count()), filter),
                Divider.divider(t),
                grid.key("orders").label("Orders").grow(1),
                Card.footer(t, label(t, MUTED, s.status()).key("status").ellipsis()))
                .grow(1)));
  }
}
