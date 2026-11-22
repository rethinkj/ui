package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static com.rethinkj.ui.kit.Label.Variant.MUTED;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.TitleBar.titleBar;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Layout;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Drawer;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.GridTable;
import com.rethinkj.ui.kit.Icon.Variant;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Properties;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toast;
import com.rethinkj.ui.kit.Tooltip;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * A million orders in the kit's data grid, with a filter above it and a status line below, as a
 * database tool shows a table: cells to select, copy and edit, row numbers and the id frozen at
 * the left, a context menu, sorting by id, filters per column and a chooser of columns. The rows
 * are made up from their index; what is written is kept beside them. Every text column repeats
 * after {@link #PERIOD} rows, so the filter tests the first thousand rows only and finds the
 * others by arithmetic, at the same speed for any filter. It keeps the rows that hold each of its
 * words in a text column, and the columns' filters work on the text columns; numbers and dates
 * are left out of them. The order at the cursor stays selected while the filter or the sort moves
 * it, and while the filter hides it.
 *
 * <p>The orders are records ({@link Order}) and the columns typed values of them ({@link
 * GridTable}): a {@code BigDecimal} amount, {@code LocalDateTime}s, an enum of statuses. Edits
 * collect in a {@link GridChanges} until Commit, which takes them as typed values and writes them
 * through the records' withers: changed orders beside the made-up ones, new orders after the million, which no filter or sort moves, and
 * deleted orders taken out of the rows; Revert forgets them.
 */
public final class DataBrowser {

  static final int ROWS = 1_000_000;
  static final int PERIOD = 1000;

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
  private static final String[] DOMAIN = {
    "example.com", "example.org", "example.net", "mail.example", "shop.example"
  };
  private static final String[] CITY = {
    "Berlin", "Paris", "Lisbon", "Oslo", "Vienna", "Madrid", "Dublin", "Prague", "Warsaw", "Rome"
  };
  private static final String[] COUNTRY = {
    "DE", "FR", "PT", "NO", "AT", "ES", "IE", "CZ", "PL", "IT"
  };
  private static final String[] PRODUCT = {
    "Keyboard", "Monitor", "Laptop", "Mouse", "Headset", "Dock", "Webcam", "Cable"
  };
  private static final int[] CENTS = {4990, 22900, 129900, 1990, 8900, 17900, 6900, 990};
  private static final String[] NOTE = {
    null, "Leave at the door", null, null, "Gift wrap", null, "Call before delivery", null
  };
  private static final LocalDateTime START = LocalDateTime.of(2026, 1, 5, 8, 0);

  /** The status of an order, written in lower case. */
  enum Status {
    PAID,
    PENDING,
    SHIPPED,
    REFUNDED;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  static final Format<Status> STATUS = Format.choice(List.of(Status.values()), Status::label);

  /** An order; a new one holds only what was written, the rest is null. */
  record Order(
      Long id,
      String customer,
      String email,
      String city,
      String country,
      String product,
      Integer quantity,
      BigDecimal amount,
      LocalDateTime createdAt,
      LocalDateTime shippedAt,
      Status status,
      String note) {

    static final Order EMPTY =
        new Order(null, null, null, null, null, null, null, null, null, null, null, null);

    Order withCustomer(String value) {
      return new Order(
          id, value, email, city, country, product, quantity, amount, createdAt, shippedAt, status,
          note);
    }

    Order withEmail(String value) {
      return new Order(
          id, customer, value, city, country, product, quantity, amount, createdAt, shippedAt,
          status, note);
    }

    Order withCity(String value) {
      return new Order(
          id, customer, email, value, country, product, quantity, amount, createdAt, shippedAt,
          status, note);
    }

    Order withCountry(String value) {
      return new Order(
          id, customer, email, city, value, product, quantity, amount, createdAt, shippedAt, status,
          note);
    }

    Order withProduct(String value) {
      return new Order(
          id, customer, email, city, country, value, quantity, amount, createdAt, shippedAt, status,
          note);
    }

    Order withQuantity(Integer value) {
      return new Order(
          id, customer, email, city, country, product, value, amount, createdAt, shippedAt, status,
          note);
    }

    Order withAmount(BigDecimal value) {
      return new Order(
          id, customer, email, city, country, product, quantity, value, createdAt, shippedAt,
          status, note);
    }

    Order withCreatedAt(LocalDateTime value) {
      return new Order(
          id, customer, email, city, country, product, quantity, amount, value, shippedAt, status,
          note);
    }

    Order withShippedAt(LocalDateTime value) {
      return new Order(
          id, customer, email, city, country, product, quantity, amount, createdAt, value, status,
          note);
    }

    Order withStatus(Status value) {
      return new Order(
          id, customer, email, city, country, product, quantity, amount, createdAt, shippedAt,
          value, note);
    }

    Order withNote(String value) {
      return new Order(
          id, customer, email, city, country, product, quantity, amount, createdAt, shippedAt,
          status, value);
    }
  }

  /** The made-up order at an index. */
  static Order madeUp(int index) {
    int quantity = 1 + index * 13 % 9;
    int product = index * 3 % PRODUCT.length;
    String customer = customer(index);
    LocalDateTime created = START.plusSeconds(index * 29L).truncatedTo(ChronoUnit.MINUTES);
    Status status = Status.values()[index * 7 % Status.values().length];
    return new Order(
        index + 1L,
        customer,
        customer.toLowerCase(Locale.ROOT).replace(' ', '.') + "@"
            + DOMAIN[index / 100 % DOMAIN.length],
        CITY[index / 4 % CITY.length],
        COUNTRY[index / 4 % COUNTRY.length],
        PRODUCT[product],
        quantity,
        BigDecimal.valueOf((long) quantity * CENTS[product], 2),
        created,
        status == Status.SHIPPED ? created.plusMinutes(index * 7919L % 4320) : null,
        status,
        NOTE[index % NOTE.length]);
  }

  /** The widths the columns start with. */
  private static final float[] WIDTHS = {72, 140, 220, 90, 80, 100, 80, 100, 140, 140, 90, 180};

  /** The columns of the orders, in the order of {@link Order}'s parts. */
  // docs:start data-browser-columns
  static List<Column> columns(GridTable<Order> table, float[] widths) {
    return List.of(
        table.column("id", widths[0], Format.LONG, Order::id),
        table.column("customer", widths[1], Format.TEXT, Order::customer, Order::withCustomer),
        table.column("email", widths[2], Format.TEXT, Order::email, Order::withEmail),
        table.column("city", widths[3], Format.TEXT, Order::city, Order::withCity),
        table.column("country", widths[4], Format.TEXT, Order::country, Order::withCountry),
        table
            .column("product", widths[5], Format.TEXT, Order::product, Order::withProduct)
            .choices(List.of(PRODUCT)),
        table.column("quantity", widths[6], Format.INTEGER, Order::quantity, Order::withQuantity),
        table.column("amount", widths[7], Format.DECIMAL, Order::amount, Order::withAmount),
        table.column(
            "created_at", widths[8], Format.DATE_TIME, Order::createdAt, Order::withCreatedAt),
        table.column(
            "shipped_at",
            widths[9],
            Format.DATE_TIME.optional(),
            Order::shippedAt,
            Order::withShippedAt),
        table.column("status", widths[10], STATUS, Order::status, Order::withStatus),
        table.column("note", widths[11], Format.TEXT.optional(), Order::note, Order::withNote));
  }
  // docs:end data-browser-columns

  /** The columns of the made-up orders, which the filter looks at. */
  private static final List<Column> MADE_UP = columns(DataGrid.table(DataBrowser::madeUp), WIDTHS);

  /** Whether the filter looks at a column: the columns of text. */
  private static boolean text(int column) {
    return switch (MADE_UP.get(column).type()) {
      case TEXT, ENUM -> true;
      default -> false;
    };
  }

  // A colored mark before each status, in the order of STATUS: constants, allocated once.
  private static final CellIcon[] MARKS = {
    CellIcon.before(Symbol.RECORD, Variant.SUCCESS),
        CellIcon.before(Symbol.RECORD, Variant.WARNING),
    CellIcon.before(Symbol.RECORD, Variant.NORMAL), CellIcon.before(Symbol.RECORD, Variant.DANGER)
  };

  boolean dark = true;
  String filter = "";
  List<Filter> filters = List.of(); // per column, on the text columns
  int[] matching = all(); // the rows below PERIOD that the filters keep, in order
  final Selection selection = new Selection(Mode.CELLS); // in the places the grid shows
  int selected = -1; // the order at the cursor, also while the filter hides it
  boolean inspecting; // whether the selected order's drawer is open
  boolean descending; // by id
  final float[] widths = new float[WIDTHS.length];
  final Menu.State menu = new Menu.State();
  String toast; // a message at the bottom of the window, or null
  App<DataBrowser> app;
  // What has been written: values by order and column (a null value is NULL), new orders after
  // the million, and deleted orders, with the places of those shown, ascending.
  final Map<Integer, Order> written = new HashMap<>();
  final List<Order> added = new ArrayList<>();
  final Set<Integer> removed = new TreeSet<>();
  private int[] gone = new int[0];
  final GridChanges changes;
  // The orders shown, as the changes count them, and their columns, whose cells the changes show.
  final GridTable<Order> table = DataGrid.table(row -> order(orderShown(row)));
  final List<Column> base = columns(table, WIDTHS);
  final Layout layout = new Layout(MADE_UP.stream().map(Column::title).toList());
  private int last = -1; // the made-up order read last, which the cells of a row ask for again
  private Order lastOrder;

  DataBrowser() {
    System.arraycopy(WIDTHS, 0, widths, 0, WIDTHS.length);
    // docs:start data-browser-changes
    changes = new GridChanges(new GridChanges.Rows() {
      @Override
      public int count() {
        return shownCount();
      }

      @Override
      public long key(int row) {
        return orderShown(row);
      }

      @Override
      public int place(long key) {
        return placeShown((int) key);
      }

      @Override
      public String cell(int row, int column) {
        return base.get(column).cell().apply(row);
      }
    });
    // docs:end data-browser-changes
  }

  /** An order as written, else as made up; new orders hold only what was written. */
  Order order(int order) {
    if (order >= ROWS) {
      return added.get(order - ROWS);
    }
    Order written = this.written.get(order);
    if (written != null) {
      return written;
    }
    if (order != last) { // made up once for all the cells of its row
      last = order;
      lastOrder = madeUp(order);
    }
    return lastOrder;
  }

  /** The rows shown: the orders the filters keep, without deleted ones, then the new ones. */
  int shownCount() {
    return count() - gone.length + added.size();
  }

  /** The order at a row shown, past the deleted ones; new orders after the million's. */
  int orderShown(int row) {
    int kept = row;
    for (int place : gone) {
      if (place <= kept) {
        kept++;
      } else {
        break;
      }
    }
    return kept < count() ? orderAt(kept) : ROWS + kept - count();
  }

  /** The row at which an order is shown, or -1. */
  int placeShown(int order) {
    if (order >= ROWS) {
      return order - ROWS < added.size() && !removed.contains(order)
          ? count() - gone.length + order - ROWS
          : -1;
    }
    int place = placeOf(order);
    if (place < 0 || removed.contains(order)) {
      return -1;
    }
    int before = Arrays.binarySearch(gone, place);
    return place - (before < 0 ? -before - 1 : before);
  }

  /** The places of the deleted orders the filters keep, after the filters or the sort changed. */
  private void places() {
    gone = removed.stream()
        .filter(order -> order < ROWS)
        .mapToInt(this::placeOf)
        .filter(place -> place >= 0)
        .sorted()
        .toArray();
  }

  static String customer(int index) {
    return FIRST[index % FIRST.length] + " " + LAST[index / FIRST.length % LAST.length];
  }

  private static int[] all() {
    int[] rows = new int[PERIOD];
    Arrays.setAll(rows, index -> index);
    return rows;
  }

  void filter(String value) {
    filter = value;
    refilter();
  }

  /** The filters of the columns, which are of text columns only. */
  void filters(List<Filter> columns) {
    filters = columns;
    refilter();
  }

  private void refilter() {
    String[] words = filter.toLowerCase(Locale.ROOT).trim().split("\\s+");
    Predicate<Order> keeps = DataGrid.table(DataBrowser::madeUp).filter(MADE_UP, filters);
    int[] found = new int[PERIOD];
    int count = 0;
    for (int row = 0; row < PERIOD; row++) {
      if (matches(row, words) && keeps.test(madeUp(row))) {
        found[count++] = row;
      }
    }
    matching = Arrays.copyOf(found, count);
    places();
    follow();
  }

  /** After the filter or the sort moved the rows: the cursor goes where its order is shown. */
  private void follow() {
    int column = Math.max(selection.column(), 0);
    int row = selected < 0 ? -1 : changes.rowOf(selected);
    if (row >= 0) {
      selection.select(row, column);
    } else {
      selection.clear();
    }
  }

  /** Inspect opens the selected order in a drawer beside the grid, or closes it again. */
  void inspect() {
    inspecting = !inspecting;
  }

  void closeInspector() {
    inspecting = false;
  }

  /** The grid moved the cursor: its order is the selected one, unless it is a new row. */
  void selected() {
    long key = selection.row() < 0 || selection.row() >= changes.rowCount()
        ? -1
        : changes.key(selection.row());
    selected = key < 0 ? -1 : (int) key;
  }

  void sort(List<SortKey> keys) {
    descending = keys.contains(new SortKey(0, true)); // none is by id, ascending
    places();
    follow();
  }

  /**
   * Writes the changes: values beside the made-up ones, new orders, deleted orders. An open
   * editor's value comes first; a value the grid refuses keeps its editor open and writes nothing.
   */
  // docs:start data-browser-commit
  void commit() {
    if (!selection.commitEdit() || changes.isEmpty()) {
      return;
    }
    int count = changes.count();
    for (GridChanges.RowChange<Order> change : changes.changes(table, base)) {
      int order = (int) change.key();
      switch (change.kind()) {
        // By its key, also where the filter hides the order and the change has no row.
        case UPDATE -> {
          Order next = change.apply(order(order));
          if (order >= ROWS) {
            added.set(order - ROWS, next);
          } else {
            written.put(order, next);
          }
        }
        case INSERT -> added.add(change.apply(Order.EMPTY));
        case DELETE -> removed.add(order);
      }
    }
    changes.clear();
    places();
    toast = count == 1 ? "1 change written" : count + " changes written";
  }
  // docs:end data-browser-commit

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
    for (int column = 0; column < MADE_UP.size(); column++) {
      String text = text(column) ? MADE_UP.get(column).cell().apply(row) : null;
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
    int place = descending ? count() - 1 - shown : shown;
    return place / matching.length * PERIOD + matching[place % matching.length];
  }

  /** The place of a row in the grid, or -1 if the filter hides it. */
  int placeOf(int row) {
    int at = row < 0 ? -1 : Arrays.binarySearch(matching, row % PERIOD);
    if (at < 0) {
      return -1;
    }
    int place = row / PERIOD * matching.length + at;
    return descending ? count() - 1 - place : place;
  }

  String status() {
    int changed = changes.count();
    String edits = changed == 0 ? "" : changed == 1 ? " · 1 change" : " · " + changed + " changes";
    return rows() + edits;
  }

  private String rows() {
    String rows = String.format(
        Locale.ROOT, "%,d of %,d orders", shownCount(), ROWS - removed.size() + added.size());
    long many = selection.rowCount();
    if (many > 1) {
      return rows + String.format(Locale.ROOT, " · %,d orders selected", many);
    }
    if (selected < 0) {
      return rows;
    }
    return rows + " · order " + (selected + 1)
        + (placeShown(selected) < 0 ? " selected, hidden by the filter" : " selected");
  }

  public static void main(String[] args) throws IOException {
    DataBrowser browser = new DataBrowser();
    App<DataBrowser> app =
        new App<>(browser, DataBrowser::view).provide(Theme.CURRENT, DataBrowser::theme);
    browser.app = app;
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(
            DataBrowser.class, "Orders", Identity.BLUE, Symbol.TABLE)
        .frameless()
        .minimumSize(480, 320);
    try (Window window = Window.open("Orders", 1000, 600, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  static El view(DataBrowser state) {
    Theme theme = Theme.CURRENT.get();
    List<Column> columns = new ArrayList<>();
    List<Column> typed = columns(state.table, state.widths);
    for (int index = 0; index < typed.size(); index++) {
      // The cells through the changes, which show the edited values and the new rows.
      Column column =
          typed.get(index).cell(state.changes.cell(index)).sortable(index == 0); // id sorts
      columns.add(
          switch (column.title()) {
            case "id" -> column.icon(Symbol.KEY, Variant.WARNING);
            case "status" ->
              column.cellIcon(shown -> {
                String status = state.changes.cell(shown, 10);
                int at = status == null ? -1 : STATUS.choices().indexOf(status);
                return at < 0 ? null : MARKS[at];
              });
            default -> column;
          });
    }
    DataGrid.Options options = DataGrid.options()
        .select(state.selection)
        .onSelect(state::selected)
        .sort(List.of(new SortKey(0, state.descending)), state::sort)
        .onResize((column, width) -> state.widths[column] = width)
        .rowNumbers()
        .frozen(1)
        .contextMenu(state.menu, List.of())
        .changes(state.changes)
        .filters(state.filters, state::filters)
        .filterable(DataBrowser::text)
        .columns(state.layout);
    if (state.app != null) { // the window's clipboard once it runs, the app's own before
      options.copy(state.app.clipboard());
    }
    // The grid copies nothing beyond its limit; a toast says why instead of nothing happening.
    options.onLargeCopy((copy, limit) -> state.toast = String.format(
        Locale.ROOT, "%,d cells are too many to copy at once: at most %,d", copy.cells(), limit));
    El grid = dataGrid(columns, state.changes.rowCount(), options);
    El filter = TextField.withIcon(
        textField(state.filter, "Filter", state::filter).key("filter").w(220), Symbol.SEARCH);
    filter = Tooltip.tooltip(
        filter, "Words in customer, email, city, country, product," + " status or note");
    El inspect = button(
            Button.Variant.GHOST,
            Symbol.PANEL_RIGHT,
            "Inspect",
            state.selected < 0 ? null : state::inspect)
        .key("inspect");
    int changed = state.changes.count();
    // The row of the order inspected; while the filter hides it, the drawer waits closed.
    int inspected =
        state.inspecting && state.selected >= 0 ? state.changes.rowOf(state.selected) : -1;
    El revert = button(
            Button.Variant.GHOST,
            "Revert",
            state.changes::clear,
            Button.options()
                .icon(Symbol.ARROW_UNDO)
                .state(changed != 0 ? Button.State.READY : Button.State.DISABLED)
                .tone(Button.Tone.DANGER))
        .key("revert");
    El commit = button(
            Button.Variant.PRIMARY,
            Symbol.SAVE,
            changed == 0 ? "Commit" : "Commit (" + changed + ")",
            changed == 0 && !state.selection.isEditing() ? null : state::commit)
        .key("commit");
    El bar = DataGrid.filterBar(columns, state.filters, state::filters);
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .onKey(
            key -> { // Control+S commits, as in Workbench
              if (key.modifiers() == Key.CONTROL && key.name().equals("S")) {
                state.commit();
                return true;
              }
              return false;
            })
        // Above the card's footer, its edge and the space below the card: the status stays seen.
        .overlay(
            state.toast == null
                ? null
                : Toast.toast(
                    state.toast,
                    null,
                    null,
                    () -> state.toast = null,
                    Card.footerHeight() + theme.size().stroke() + theme.space().m()))
        .overlay(inspected < 0 ? null : inspector(state, inspected))
        .child(titleBar(
            AppIcon.appIcon(16, Identity.BLUE, Symbol.TABLE),
            "Orders",
            Button.icon(
                    Button.Variant.GHOST,
                    Symbol.WEATHER_MOON,
                    "Dark theme",
                    () -> state.dark = !state.dark)
                .checked(state.dark)))
        .child(col()
            .grow(1)
            .minSize(0, 0)
            .padding(0, theme.space().m(), theme.space().m(), theme.space().m())
            .child(Card.flush(
                    Card.toolbar(
                        "orders",
                        String.format(Locale.ROOT, "%,d rows", state.changes.rowCount()),
                        filter,
                        DataGrid.columnsButton(state.layout),
                        inspect,
                        revert,
                        commit),
                    Card.filters(bar),
                    Divider.divider(),
                    grid.key("orders").label("Orders").grow(1),
                    Card.footer(label(MUTED, state.status()).key("status").ellipsis()))
                .grow(1)));
  }

  /** The selected order's fields as its row shows them, pending changes included. */
  private static El inspector(DataBrowser state, int row) {
    List<Properties.Property> fields = new ArrayList<>();
    for (int column = 0; column < state.base.size(); column++) {
      String value = state.changes.cell(column).apply(row);
      fields.add(
          Properties.property(state.base.get(column).title(), value == null ? "NULL" : value));
    }
    El details = Properties.properties(fields, state.app == null ? null : state.app.clipboard());
    return Drawer.drawer(
        "Order " + (state.selected + 1),
        details,
        state::closeInspector,
        Drawer.options().modal(false));
  }
}
