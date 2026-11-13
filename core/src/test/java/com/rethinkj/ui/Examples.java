package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.util.Arrays;

/** Views shared by tests. */
public final class Examples {

  private Examples() {}

  /**
   * The switch as a program: {@code --script <file> --out <folder>} runs a script against it, and
   * with {@code list} or {@code rows} before that against the list or the million rows.
   */
  public static void main(String[] args) throws IOException {
    String view = args.length > 0 && !args[0].startsWith("--") ? args[0] : "";
    App<?> app =
        switch (view) {
          case "list" -> new App<>(new Object(), s -> list());
          case "rows" -> new App<>(new Object(), s -> rows());
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
