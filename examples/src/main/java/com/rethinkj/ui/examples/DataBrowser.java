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
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Sort;
import com.rethinkj.ui.kit.Theme;
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
 * database tool shows a table. The rows are made up from their index; nothing is stored. Every
 * text column repeats after {@link #PERIOD} rows, so the filter tests the first thousand rows
 * only and finds the others by arithmetic, at the same speed for any filter. It keeps the rows
 * that hold each of its words in a text column; numbers and dates are left out of it.
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

  /** A column of the table: its cell from a row's index, and whether the filter looks at it. */
  private record Field(
      String title, float width, Align align, boolean mono, boolean text, IntFunction<String> cell) {}

  private static final List<Field> FIELDS = List.of(
      new Field("id", 72, Align.RIGHT, true, false, i -> String.valueOf(i + 1)),
      new Field("customer", 140, Align.LEFT, false, true, DataBrowser::customer),
      new Field("email", 220, Align.LEFT, false, true, i -> customer(i).toLowerCase(Locale.ROOT)
          .replace(' ', '.') + "@" + DOMAIN[i / 100 % DOMAIN.length]),
      new Field("city", 90, Align.LEFT, false, true, i -> CITY[i / 4 % CITY.length]),
      new Field("country", 70, Align.LEFT, false, true, i -> COUNTRY[i / 4 % COUNTRY.length]),
      new Field("product", 100, Align.LEFT, false, true, i -> PRODUCT[product(i)]),
      new Field("quantity", 80, Align.RIGHT, true, false, i -> String.valueOf(quantity(i))),
      new Field("amount", 100, Align.RIGHT, true, false, i -> {
        int cents = quantity(i) * CENTS[product(i)];
        return String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
      }),
      new Field("created_at", 140, Align.LEFT, true, false, i -> created(i).format(TIME)),
      new Field("shipped_at", 140, Align.LEFT, true, false, i -> !status(i).equals("shipped")
          ? null : created(i).plusMinutes(i * 7919L % 4320).format(TIME)),
      new Field("status", 90, Align.LEFT, false, true, DataBrowser::status),
      new Field("note", 180, Align.LEFT, false, true, i -> NOTE[i % NOTE.length]));

  String filter = "";
  int[] matching = all(); // the rows below PERIOD that the filter keeps, in order
  int selected = -1; // the selected order's row, also while the filter hides it
  boolean descending; // by id
  final float[] widths = new float[FIELDS.size()];

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
    if (selected < 0) {
      return rows;
    }
    return rows + " · order " + (selected + 1)
        + (placeOf(selected) < 0 ? " selected, hidden by the filter" : " selected");
  }

  public static void main(String[] args) throws IOException {
    App<DataBrowser> app = new App<>(new DataBrowser(), DataBrowser::view);
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Window.options().frameless().minimumSize(480, 320);
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
      columns.add(new Column(f.title(), s.widths[c], f.align(), f.mono(),
          shown -> f.cell().apply(s.orderAt(shown))));
    }
    El grid = dataGrid(t, columns, s.count(), s.placeOf(s.selected),
        shown -> s.selected = s.orderAt(shown),
        (c, width) -> s.widths[c] = width,
        new Sort(0, s.descending),
        c -> s.descending = c == 0 ? !s.descending : s.descending); // only id sorts
    return col().wFull().hFull().fill(t.surface().window())
        .child(titleBar(t, "Orders", t.accent().color()))
        // No smaller than its content otherwise, which is a million rows tall: min-height: 0.
        .child(col().grow(1).minSize(0, 0).padding(t.space().l()).gap(t.space().m())
            .child(row().gap(t.space().m()).align(Style.Align.CENTER)
                .child(textField(t, s.filter, "Filter", s::filter).key("filter").w(280))
                .child(label(t, MUTED, "customer, email, city, country, product, status, note")))
            .child(grid.key("orders").label("Orders").grow(1))
            .child(label(t, MUTED, s.status()).key("status")));
  }
}
