package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Views shared by tests. */
public final class Examples {

  private Examples() {}

  /**
   * The switch as a program: {@code --script <file> --out <folder>} runs a script against it, and
   * with {@code list}, {@code rows}, {@code field} or {@code field-rows} before that against the
   * list, the million rows, the text field or the fields in rows.
   */
  public static void main(String[] args) throws IOException {
    String view = args.length > 0 && !args[0].startsWith("--") ? args[0] : "";
    App<?> app =
        switch (view) {
          case "list" -> new App<>(new Object(), s -> list());
          case "rows" -> new App<>(new Object(), s -> rows());
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
    El header =
        row().h(28).shrink(0).padding(0, 12).gap(12).align(Style.Align.CENTER).fill(0xFF26262E)
            .children(
                El.text("#").font(Font.mono()).fontSize(12).color(0xFF8A8A99).w(56),
                El.text("table").fontSize(12).color(0xFFA1A1AA).grow(1),
                El.text("rows").fontSize(12).color(0xFFA1A1AA));
    El grid =
        col().key("rows").role("grid").grow(1).fill(0xFF1C1C22)
            .rows(1_000_000, 28, Examples::tableRow);
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
