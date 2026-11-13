package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Views shared by tests. */
public final class Examples {

  private Examples() {}

  /**
   * The switch as a program: {@code --script <file> --out <folder>} runs a script against it, and
   * with {@code list}, {@code rows}, {@code bars}, {@code field} or {@code field-rows} before that
   * against the list, the million rows, the million rows with scrollbars, the text field or the
   * fields in rows.
   */
  public static void main(String[] args) throws IOException {
    String view = args.length > 0 && !args[0].startsWith("--") ? args[0] : "";
    App<?> app =
        switch (view) {
          case "list" -> new App<>(new Object(), s -> list());
          case "rows" -> new App<>(new Object(), s -> rows());
          case "bars" -> new App<>(new Object(), s -> bars());
          case "field" -> new App<>(new Form(), Examples::form);
          case "field-rows" -> new App<>(new Names(), Examples::fieldRows);
          default -> new App<>(new Lights(), Examples::lights);
        };
    app.textRendering(TextRendering.LINUX); // as the stored images
    if (!Script.handle(view.isEmpty() ? args : Arrays.copyOfRange(args, 1, args.length), app)) {
      System.err.println("There is no window yet. Run with --script <file> --out <folder>.");
    }
  }

  private static final String[] TABLES = {
    "accounts", "addresses", "audit_log", "carts", "categories", "customers", "deliveries",
    "discounts", "employees", "inventory", "invoices", "order_items", "orders", "payments",
    "products", "refunds", "reviews", "sessions", "shipments", "suppliers"
  };

  /** Twenty tables of a database in a rounded scroll area, more than fit into it. */
  public static El list() {
    El list =
        col().key("list").role("list").scroll().grow(1).padding(8).gap(4).radius(12)
            .fill(0xFF1C1C22);
    for (int i = 1; i <= 20; i++) {
      list.child(
          row().key("row-" + i)
              .role("listitem")
              .label(TABLES[i - 1])
              .h(28)
              .padding(0, 8)
              .gap(8)
              .align(Style.Align.CENTER)
              .radius(6)
              .fill(i % 2 == 0 ? 0xFF26262E : 0xFF2E2E38)
              .children(
                  el().size(14, 14).radius(7).fill(i % 3 == 0 ? 0xFF22C55E : 0xFF3B82F6),
                  El.text(TABLES[i - 1]).fontSize(13).color(0xFFE4E4E7).grow(1),
                  El.text(String.valueOf(i * 37 % 90 * 113)).font(Font.mono()).fontSize(12)
                      .color(0xFF8A8A99)));
    }
    return col().wFull().hFull().padding(16).fill(0xFF101014).child(list);
  }

  /**
   * A million rows of tables under a header, in a virtual list: only the rows in view and a few
   * around them exist.
   */
  public static El rows() {
    return rows(null);
  }

  /** The look of the scrollbars of {@link #bars}: thin grey thumbs that lighten under use. */
  public static final Scrollbars BARS =
      new Scrollbars(6, 2, 3, 24, 0xFF5A5A66, 0xFF7A7A88, 0xFFA1A1AA);

  /** The million rows of {@link #rows} with scrollbars that look as {@link #BARS} says. */
  public static El bars() {
    return rows(BARS);
  }

  private static El rows(Scrollbars bars) {
    El header =
        row().h(28).shrink(0).padding(0, 12).gap(12).align(Style.Align.CENTER).fill(0xFF26262E)
            .children(
                El.text("#").font(Font.mono()).fontSize(12).color(0xFF8A8A99).w(56),
                El.text("table").fontSize(12).color(0xFFA1A1AA).grow(1),
                El.text("rows").fontSize(12).color(0xFFA1A1AA));
    El grid =
        col().key("rows").role("grid").grow(1).fill(0xFF1C1C22)
            .rows(1_000_000, 28, Examples::tableRow).scrollbars(bars);
    return col().wFull().hFull().padding(16).fill(0xFF101014).children(header, grid);
  }

  /** The row of {@link #rows} with this index. */
  private static El tableRow(int i) {
    String table = TABLES[i % TABLES.length];
    return row().key("row-" + i)
        .role("row")
        .label(table)
        .padding(0, 12)
        .gap(12)
        .align(Style.Align.CENTER)
        .fill(i % 2 == 0 ? 0xFF1C1C22 : 0xFF22222A)
        .children(
            El.text(String.valueOf(i)).font(Font.mono()).fontSize(12).color(0xFF8A8A99).w(56),
            El.text(table).fontSize(13).color(0xFFE4E4E7).grow(1),
            El.text(String.valueOf(i * 37L % 90 * 113)).font(Font.mono()).fontSize(12)
                .color(0xFF8A8A99));
  }

  /** The state of the field example. */
  public static final class Form {
    public String name = "";
  }

  /** A text field for the name of a table, with a placeholder, in the middle of the window. */
  public static El form(Form form) {
    return col().wFull().hFull().center().fill(0xFF101014).child(
        El.text(form.name).editable(name -> form.name = name)
            .key("name")
            .label("Table name")
            .placeholder("Table name", 0xFF8A8A99)
            .color(0xFFE4E4E7)
            .caretColor(0xFF93C5FD)
            .selectionColor(0x803B82F6)
            .w(160)
            .h(28)
            .padding(0, 8)
            .radius(6)
            .fill(0xFF1C1C22)
            .border(1, 0xFF3A3A40)
            .whenFocused(e -> e.border(1, 0xFF3B82F6)));
  }

  /** The state of {@link #fieldRows}: a filter, and the names that edits gave rows. */
  public static final class Names {
    public String filter = "";
    public final Map<Integer, String> edited = new HashMap<>();

    public String name(int row) {
      return edited.getOrDefault(row, TABLES[row % TABLES.length]);
    }
  }

  /**
   * A thousand rows in a virtual list, each with a text field for the name of a table, under a
   * text field for a filter.
   */
  public static El fieldRows(Names names) {
    El filter = field(names.filter, f -> names.filter = f).key("filter").label("Filter")
        .placeholder("Filter", 0xFF8A8A99).w(160).shrink(0);
    El grid = col().key("rows").role("grid").grow(1).fill(0xFF1C1C22).rows(1000, 32, i ->
        row().role("row").padding(0, 8).gap(8).align(Style.Align.CENTER)
            .fill(i % 2 == 0 ? 0xFF1C1C22 : 0xFF22222A)
            .children(
                El.text(String.valueOf(i)).font(Font.mono()).fontSize(12).color(0xFF8A8A99)
                    .w(32),
                field(names.name(i), name -> names.edited.put(i, name)).key("name-" + i)
                    .label("Name of row " + i).grow(1)));
    return col().wFull().hFull().padding(12).gap(8).fill(0xFF101014).children(filter, grid);
  }

  /** A text field as in {@link #form}, without a width. */
  private static El field(String value, Consumer<String> onEdit) {
    return El.text(value).editable(onEdit)
        .color(0xFFE4E4E7)
        .caretColor(0xFF93C5FD)
        .selectionColor(0x803B82F6)
        .h(24)
        .padding(0, 6)
        .radius(4)
        .fill(0xFF16161B)
        .border(1, 0xFF3A3A40)
        .whenHovered(e -> e.fill(0xFF26262E))
        .whenFocused(e -> e.border(1, 0xFF3B82F6));
  }

  // ---- the kit's data grid, which core's tests cannot see ----------------------------------

  /**
   * The state of {@link #grid}, as {@code Orders} in kit's tests keeps it: the selected row and
   * the widths of the seven columns.
   */
  public static final class Grid {
    public int selected = 2;
    public final float[] widths = {64, 140, 200, 150, 90, 80, 240};
  }

  private static final String[] FIRST = {
    "Ada", "Grace", "Linus", "Margaret", "Ken", "Barbara", "Edsger", "Frances", "Donald", "Radia"
  };
  private static final String[] LAST = {
    "Lovelace", "Hopper", "Torvalds", "Hamilton", "Thompson", "Liskov", "Dijkstra", "Allen",
    "Knuth", "Perlman"
  };
  private static final String[] STATUS = {"paid", "pending", "shipped", "refunded"};
  private static final LocalDateTime START = LocalDateTime.of(2026, 3, 1, 8, 0);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String[] TITLES =
      {"id", "customer", "email", "created_at", "amount", "status", "note"};
  private static final boolean[] RIGHT = {true, false, false, false, true, false, false};
  private static final boolean[] MONO = {true, false, false, true, true, false, false};

  private static String customer(int i) {
    return FIRST[i % FIRST.length] + " " + LAST[i / FIRST.length % LAST.length];
  }

  /** The cells of the seven columns of {@code Orders}, null for SQL NULL. */
  private static final List<IntFunction<String>> CELLS = List.of(
      i -> String.valueOf(i + 1),
      Examples::customer,
      i -> customer(i).toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com",
      i -> START.plusSeconds(i * 97L).format(TIME),
      i -> i % 11 == 5 ? null
          : String.format(Locale.ROOT, "%d.%02d", i * 7919 % 250_000 / 100, i * 13 % 100),
      i -> STATUS[i * 7 % STATUS.length],
      i -> i % 3 != 1 ? null
          : "Customer asked to split the delivery into two parcels and to leave them next door");

  // The values of the kit's Theme.DARK that the grid reads.
  private static final int PANEL = 0xFF232428;
  private static final int HOVER = 0x0FFFFFFF;
  private static final int PRESSED = 0x1AFFFFFF;
  private static final int PRIMARY = 0xFFE4E5E9;
  private static final int MUTED = 0xFFA1A3AA;
  private static final int SUBTLE = 0xFF393A40;
  private static final int STRONG = 0xFF7A7D86;
  private static final int ACCENT = 0xFF2E62D9;
  private static final int HEADER = 0xFF232428;
  private static final int ROW = 0xFF1B1C1F;
  private static final int STRIPE = 0xFF1F2023;
  private static final int SELECTED = 0xFF1C3666;
  private static final int SELECTED_INACTIVE = 0xFF2B3240;
  private static final float ROW_HEIGHT = 22;
  private static final Scrollbars GRID_BARS =
      new Scrollbars(6, 2, 3, 24, 0xFF83868F, 0xFFA1A3AA, 0xFFB9BBC1);

  /**
   * The kit's {@code DataGrid} in {@code Theme.DARK} with a million orders, as kit's {@code
   * Orders.view} shows it, built the same way from core's elements: core's tests cannot depend on
   * the kit, which depends on core. {@code GpuRendererTest} checks that it draws the kit's stored
   * images {@code grid-dark-*} before it compares the GPU with the CPU on it, so a change of the
   * grid shows there.
   */
  public static El grid(Grid s) {
    float total = 0;
    for (float width : s.widths) {
      total += width;
    }
    El header = row().key("header").h(ROW_HEIGHT).shrink(0).fill(HEADER);
    El[] lines = new El[TITLES.length];
    for (int c = 0; c < TITLES.length; c++) {
      lines[c] = gridLine();
      El cell = gridCell(s.widths[c], RIGHT[c]).key("column-" + c).role("columnheader")
          .label(TITLES[c]).gap(2);
      El title = gridText(TITLES[c], PRIMARY, false);
      if (c == 0) { // sorted by id, ascending; the marker before the title of a right column
        cell.children(gridText("▲", MUTED, false), title);
      } else {
        cell.child(title);
      }
      header.children(cell.cursor(Cursor.POINTER).onPress(() -> {})
          .whenHovered(e -> e.fill(over(HEADER, HOVER)))
          .whenPressed(e -> e.fill(over(HEADER, PRESSED))), lines[c]);
    }
    float edge = 0;
    for (int c = 0; c < TITLES.length; c++) {
      edge += s.widths[c];
      int index = c;
      float width = s.widths[c];
      header.child(el().key("resize-" + c).role("separator").label("Resize " + TITLES[c])
          .absolute(edge - 3, 0).size(6, ROW_HEIGHT).cursor(Cursor.EW_RESIZE)
          .whenHovered(e -> lines[index].fill(STRONG))
          .whenPressed(e -> lines[index].fill(ACCENT))
          .onDrag(d -> s.widths[index] =
              d.phase() == Drag.Phase.CANCEL ? width : Math.max(width + d.dx(), 32)));
    }
    boolean[] focused = {false};
    int selected = s.selected;
    El body = col().key("rows").grow(1).revealRow(selected).rows(1_000_000, ROW_HEIGHT, i -> {
      int fill = i == selected ? (focused[0] ? SELECTED : SELECTED_INACTIVE)
          : i % 2 == 0 ? ROW : STRIPE;
      El r = row().key("row-" + i).role("row").fill(fill);
      for (int c = 0; c < TITLES.length; c++) {
        String text = CELLS.get(c).apply(i);
        r.children(gridCell(s.widths[c], RIGHT[c]).child(
            gridText(text == null ? "NULL" : text, text == null ? MUTED : PRIMARY, MONO[c])),
            gridLine());
      }
      r.onPress(() -> s.selected = i);
      return i == selected ? r : r.whenHovered(e -> e.fill(over(fill, HOVER)));
    }).scrollbars(GRID_BARS);
    El under = el().h(1).shrink(0).fill(SUBTLE);
    // Room for the rows' bar after the last column, as the kit leaves.
    float room = GRID_BARS.thickness() + 2 * GRID_BARS.margin();
    El content = col().key("content").w(total + room).children(header, under, body);
    content.style().minWidth = Len.percent(1);
    El grid = row().role("grid").scrollBoth().scrollbars(GRID_BARS).fill(ROW).child(content)
        .focusable()
        .focusRing(PRIMARY) // border().focus(), the same colour in Theme.DARK
        .whenFocused(e -> focused[0] = true)
        .onKey(k -> {
          int next = switch (k.name()) {
            case "ArrowUp" -> selected - 1;
            case "ArrowDown" -> selected + 1;
            default -> -1;
          };
          if (k.modifiers() != 0 || next < 0) {
            return false;
          }
          s.selected = next;
          return true;
        });
    return col().wFull().hFull().padding(8).fill(PANEL)
        .child(grid.key("orders").label("Orders").grow(1));
  }

  private static El gridCell(float width, boolean right) {
    return row().w(Math.max(width - 1, 0)).padding(0, 4).align(Style.Align.CENTER)
        .justify(right ? Style.Content.END : Style.Content.START).clip();
  }

  private static El gridText(String text, int colour, boolean mono) {
    return El.text(text).font(mono ? Font.mono() : Font.sans()).fontSize(mono ? 12 : 13)
        .color(colour);
  }

  private static El gridLine() {
    return el().w(1).fill(SUBTLE);
  }

  /** The colour {@code overlay} gives over the opaque {@code base}, as the kit's Theme.over. */
  private static int over(int base, int overlay) {
    int alpha = overlay >>> 24;
    int argb = 0xFF;
    for (int shift = 16; shift >= 0; shift -= 8) {
      int b = base >>> shift & 0xFF;
      int o = overlay >>> shift & 0xFF;
      argb = argb << 8 | Math.round((o * alpha + b * (255 - alpha)) / 255f);
    }
    return argb;
  }

  /** The state of the switch example. */
  public static final class Lights {
    public boolean on;
  }

  /** The switch as an app: pressing it toggles the state. */
  public static El lights(Lights state) {
    return lightSwitch(state.on, () -> state.on = !state.on);
  }

  public static El lightSwitch(boolean on) {
    return lightSwitch(on, null);
  }

  /**
   * The switch from rune's README with its label beside it, which brightens while the lights are
   * on. The label is as wide for both texts, so that the switch stays where it is.
   */
  static El lightSwitch(boolean on, Runnable onPress) {
    return row().wFull()
        .hFull()
        .center()
        .gap(12)
        .fill(0xFF101014)
        .children(
            toggle(on, onPress),
            El.text(on ? "Lights on" : "Lights off")
                .key("label")
                .w(72)
                .color(on ? 0xFFF4F4F5 : 0xFFA1A1AA)
                .motion(Spring.SNAP));
  }

  /** The switch as an app without its label, for tests that run without the text libraries. */
  public static El switchAlone(Lights state) {
    return switchAlone(state.on, () -> state.on = !state.on);
  }

  static El switchAlone(boolean on) {
    return switchAlone(on, null);
  }

  private static El switchAlone(boolean on, Runnable onPress) {
    return col().wFull().hFull().center().gap(16).fill(0xFF101014).child(toggle(on, onPress));
  }

  /** The switch with a badge beside it that is there while the lights are on. */
  public static El badged(Lights state) {
    El root =
        row().wFull()
            .hFull()
            .center()
            .gap(12)
            .fill(0xFF101014)
            .child(toggle(state.on, () -> state.on = !state.on));
    if (state.on) {
      root.child(
          el().key("badge")
              .role("status")
              .label("On")
              .size(12, 12)
              .radius(6)
              .fill(0xFF22C55E)
              .motion(Spring.SNAP));
    }
    return root;
  }

  /**
   * Lays out a view for a window of this size and scale, with its text shaped for the scale, as
   * {@link Headless#render} does; returns the view.
   */
  public static El laidOut(El root, float width, float height, float scale) {
    El.scaleText(root, scale);
    Flex.layout(root, width, height);
    return root;
  }

  // ---- the views behind the stored text images ---------------------------------------------

  public static final String SANS_SAMPLE = "Kunde Müller, Köln: 1.234,56 € (AVATAR)";
  public static final String MONO_SAMPLE = "SELECT id FROM kunden; -- ÄÖÜ != 0";

  /**
   * Text of 11 to 14 pixels in both fonts, dark on light and light on dark, for a window of 720 by
   * 176: the stored images text-sizes-1x and -2x.
   */
  public static El textSizes() {
    return row().padding(8).gap(8).fill(0xFF808080)
        .children(textSheet(0xFF1A1A1A, 0xFFFFFFFF), textSheet(0xFFDCDCDC, 0xFF1E1F22));
  }

  /** Eight lines, 11 to 14 pixels in Inter and in JetBrains Mono, in a colour on a background. */
  public static El textSheet(int colour, int background) {
    El sheet = col().padding(8).gap(2).fill(background).grow(1);
    for (Font font : new Font[] {Font.sans(), Font.mono()}) {
      for (int size = 11; size <= 14; size++) {
        sheet.child(El.text(size + "  " + (font == Font.sans() ? SANS_SAMPLE : MONO_SAMPLE))
            .font(font).fontSize(size).color(colour));
      }
    }
    return sheet;
  }

  /**
   * Grey text on white, in three columns, for a window of 480 by 104: the stored image
   * text-grey-on-light. On a parent's white fill, which the scene sees, so the text is thickened;
   * on a white box behind it that is not its ancestor, which the scene misses, so the biased rule
   * decides by the grey alone; and there again, but told with {@code textBackground}, which draws
   * it as the first column.
   */
  public static El greyOnLight() {
    El root = el().size(480, 104);
    root.child(el().absolute(0, 0).size(480, 104).fill(0xFFFFFFFF)); // not an ancestor of the text
    String[] titles = {"parent fill", "a box behind", "textBackground"};
    for (int column = 0; column < 3; column++) {
      El texts = col().absolute(160 * column, 0).padding(8).gap(2);
      if (column == 0) {
        texts.fill(0xFFFFFFFF);
      }
      texts.child(El.text(titles[column]).fontSize(11).color(0xFF1A1A1A));
      for (int grey : new int[] {0xFF6B6B6B, 0xFF808080, 0xFF999999, 0xFFAAAAAA}) {
        El text = El.text(String.format("#%06X Kunde Müller", grey & 0xFFFFFF)).fontSize(13)
            .color(grey);
        texts.child(column == 2 ? text.textBackground(0xFFFFFFFF) : text);
      }
      root.child(texts);
    }
    return root;
  }

  /**
   * Eight lines in a rounded scroll area 70 pixels high, for a window of 200 by 90: scrolled by
   * 13.25 pixels, the stored image text-scrolled.
   */
  public static El scrolledText() {
    El list = col().key("list").scroll().padding(6).gap(2).radius(10).h(70).fill(0xFF1C1C22);
    for (int i = 1; i <= 8; i++) {
      list.child(El.text("Zeile " + i + ": Größe & Söhne GmbH").color(0xFFE4E4E7));
    }
    return col().wFull().hFull().padding(10).fill(0xFF101014).child(list);
  }

  /**
   * Dark text on white and light text on near black at opacity 1, 0.6 and 0.3, for a window of
   * 336 by 76: the stored image text-opacity.
   */
  public static El textOpacity() {
    El root = row().padding(8).gap(8).fill(0xFF808080);
    for (int[] theme : new int[][] {{0xFF1A1A1A, 0xFFFFFFFF}, {0xFFDCDCDC, 0xFF1E1F22}}) {
      El panel = col().padding(8).gap(2).fill(theme[1]);
      for (float opacity : new float[] {1, 0.6f, 0.3f}) {
        panel.child(el().opacity(opacity)
            .child(El.text("Opazität " + opacity + ": Größe").color(theme[0])));
      }
      root.child(panel);
    }
    return root;
  }

  /** Lighter while the pointer is over it, darker while it is pressed, and a hand as the cursor. */
  private static El toggle(boolean on, Runnable onPress) {
    El knob =
        el().key("knob")
            .size(18, 18)
            .radius(9)
            .fill(0xFFFFFFFF)
            .absolute(on ? 18 : 2, 2)
            .motion(Spring.SNAP);
    return el().key("switch")
        .role("switch")
        .label("Lights")
        .size(38, 22)
        .radius(11)
        .fill(on ? 0xFF3B82F6 : 0xFF3A3A40)
        .whenHovered(e -> e.fill(on ? 0xFF60A5FA : 0xFF4A4A52))
        .whenPressed(e -> e.fill(on ? 0xFF2563EB : 0xFF2C2C31))
        .cursor(Cursor.POINTER)
        .focusable()
        .onPress(onPress)
        .motion(Spring.SNAP)
        .child(knob);
  }
}
