package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.codeArea;
import static com.rethinkj.ui.kit.TextField.passwordField;
import static com.rethinkj.ui.kit.TextField.revealable;
import static com.rethinkj.ui.kit.TextField.searchField;
import static com.rethinkj.ui.kit.TextField.textArea;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Every component in every variant and state, as a sheet: a row per component and variant, a
 * column per state. Each cell is an app of its own with the component in the middle, which a
 * script brings into the cell's state (the pointer over it, pressing it, the focus moved to it by
 * the keyboard) before it is drawn; the cells are then put together into one image, and their
 * trees into one text.
 *
 * <p>A new component gets its rows in {@link #ROWS}: a name and how to build it with a handler,
 * or with null, which disables it, and steps that follow those of each state, such as selecting
 * text in a field.
 */
final class Gallery {

  static final int WIDTH = 140;
  static final int HEIGHT = 40;
  static final int STACKED = 56; // the row of a stacked-subtitle button, two lines high
  static final int AREA = 72; // the rows of text areas, three lines high
  static final int PROGRESS = 4; // the rows of progress forms, which have no states

  /** The columns, with the steps that bring a component into each state. */
  enum State {
    REST(""),
    HOVER("move #c\nframe\nadvance 1\nframe"),
    PRESSED("move #c\ndown #c\nframe\nadvance 1\nframe"),
    FOCUSED("focus #c\nframe\nadvance 1\nframe"),
    DISABLED("");

    final String steps;

    State(String steps) {
      this.steps = steps;
    }
  }

  /** A row of the sheet, {@link #HEIGHT} high unless it says otherwise. */
  record Row(String name, Function<Runnable, El> component, String steps, int height) {

    Row(String name, Function<Runnable, El> component) {
      this(name, component, "");
    }

    Row(String name, Function<Runnable, El> component, String steps) {
      this(name, component, steps, HEIGHT);
    }
  }

  /** A text field as wide as a cell allows, enabled when the handler is. */
  private static El field(String value, Runnable handler) {
    return textField(value, "Search tables", handler == null ? null : s -> handler.run())
        .w(WIDTH - 20);
  }

  /** A search field as wide as {@link #field}, enabled when the handler is. */
  private static El search(String value, Runnable handler) {
    return searchField(value, "Search tables", handler == null ? null : s -> handler.run())
        .w(WIDTH - 20);
  }

  /** A text area three lines high, as wide as {@link #field}. */
  private static El area(Runnable handler) {
    return textArea(
            "Ship by Friday,\nfrom the north\nwarehouse.",
            "Notes",
            handler == null ? null : s -> handler.run())
        .w(WIDTH - 20)
        .lines(3);
  }

  /** A code area three lines high, with a line a tab indents. */
  private static El code(Runnable handler) {
    return codeArea(
            "SELECT id,\n\tname\nFROM t;", "SQL", handler == null ? null : s -> handler.run())
        .w(WIDTH - 20)
        .lines(3);
  }

  /** A password field with its button, as wide as {@link #field}. */
  private static El password(boolean revealed, Runnable handler) {
    El field = passwordField("hunter2", "Password", handler == null ? null : s -> handler.run());
    return revealable(field.w(WIDTH - 20), revealed, handler);
  }

  /** Busy, or off in the column of a button that is off. */
  private static Button.Options busy(Runnable h) {
    return Button.options().state(h == null ? Button.State.DISABLED : Button.State.BUSY);
  }

  private static Button.Options danger() {
    return Button.options().tone(Button.Tone.DANGER);
  }

  static final List<Row> ROWS = List.of(
      new Row("primary", h -> button(Variant.PRIMARY, "Run query", h)),
      new Row("secondary", h -> button(Variant.SECONDARY, "Cancel", h)),
      new Row("busy", h -> button(Variant.PRIMARY, "Running", h, busy(h))),
      new Row("busy text", h -> button(Variant.PRIMARY, "Save", h, busy(h).busyText("Saving…"))),
      new Row("ghost", h -> button(Variant.GHOST, "Format", h)),
      new Row("danger", h -> button(Variant.PRIMARY, "Drop table", h, danger())),
      new Row("danger secondary", h -> button(Variant.SECONDARY, "Discard", h, danger())),
      new Row("danger ghost", h -> button(Variant.GHOST, "Delete", h, danger())),
      new Row(
          "success ghost",
          h -> Button.icon(
              Variant.GHOST,
              Symbol.CHECKMARK,
              "Apply",
              h,
              Button.options().tone(Button.Tone.SUCCESS))),
      new Row(
          "accent ghost",
          h -> Button.icon(
              Variant.GHOST,
              Symbol.ARROW_UNDO,
              "Undo",
              h,
              Button.options().tone(Button.Tone.ACCENT))),
      new Row(
          "busy icon",
          h -> Button.icon(Variant.GHOST, Symbol.ARROW_CLOCKWISE, "Refresh", h, busy(h))),
      new Row(
          "progress icon",
          h -> Button.icon(
              Variant.GHOST,
              Symbol.ARROW_DOWNLOAD,
              "Download update",
              h,
              busy(h).progress(0.42))),
      new Row(
          "small",
          h -> button(Variant.SECONDARY, "Filter", h, Button.options().size(Button.Size.SMALL))),
      new Row(
          "large",
          h -> button(
              Variant.PRIMARY,
              "Connect",
              h,
              Button.options().icon(Symbol.ADD).size(Button.Size.LARGE))),
      new Row(
          "menu",
          h -> Button.menu(
              Variant.SECONDARY,
              "Export",
              new Menu.State(),
              List.of(Menu.item("CSV", h), Menu.item("JSON", h)))),
      new Row(
          "subtitle",
          h -> button(Variant.SECONDARY, "Opus 5.5", h, Button.options().subtitle("High"))),
      new Row(
          "subtitle stacked",
          h -> button(
              Variant.SECONDARY,
              "Opus 5.5",
              h,
              Button.options().icon(Symbol.FLASH).subtitle("High", Button.Subtitle.STACKED)),
          "",
          STACKED),
      new Row("toggle off", h -> toggle(false, "Autocommit", h)),
      new Row("toggle on", h -> toggle(true, "Autocommit", h)),
      new Row("checkbox off", h -> checkbox(false, "Read only", h)),
      new Row("checkbox on", h -> checkbox(true, "Read only", h)),
      new Row("field empty", h -> field("", h)),
      new Row("field filled", h -> field("orders 2024", h)),
      // The last word selected from the caret, wherever the state left it.
      new Row(
          "field selected",
          h -> field("orders 2024", h),
          "key ArrowRight control shift\nkey ArrowLeft control shift\nframe"),
      new Row("search empty", h -> search("", h)),
      new Row("search filled", h -> search("orders", h)),
      new Row("password", h -> password(false, h)),
      new Row("password shown", h -> password(true, h)),
      new Row("area", Gallery::area, "", AREA),
      // From the third character to the third of the last line: the line feeds shown.
      new Row("area selected", Gallery::area, """
          key Home control
          key ArrowRight
          key ArrowRight
          key ArrowDown shift
          key ArrowDown shift
          frame""", AREA),
      new Row("code area", Gallery::code, "", AREA),
      new Row("switch row", h -> Group.switchRow("Wrap", null, true, h).w(WIDTH - 20)),
      new Row("link row", h -> Group.linkRow("Keymap", null, null, h).w(WIDTH - 20)),
      new Row("expander", h -> Group.expander("More", null, false, h).w(WIDTH - 20)),
      new Row("radio", h -> Radio.radioGroup(List.of("Spaces"), 0, choose(h))),
      new Row(
          "segmented",
          h -> Segmented.segmented(List.of("Rows", "Cells"), 0, choose(h)).label("Mode")),
      new Row(
          "select",
          h -> Select.select(List.of("Rows", "Cells"), 0, new Menu.State(), choose(h))
              .w(WIDTH - 20)),
      new Row(
          "combo box",
          h -> ComboBox.comboBox(List.of("analytics", "shop"), 1, new ComboBox.State(), choose(h))
              .w(WIDTH - 20)),
      new Row(
          "combo free",
          h -> ComboBox.free(
                  "localhost",
                  List.of("localhost"),
                  new ComboBox.State(),
                  h == null ? null : v -> h.run())
              .w(WIDTH - 20)),
      new Row("number", h -> NumberField.numberField(4, 1, 16, 1, choose(h)).w(WIDTH - 40)),
      new Row(
          "slider", h -> Slider.slider(13, 11, 16, 1, choose(h)).w(WIDTH - 20).label("Size")),
      new Row(
          "nav list",
          h -> NavList.navList(
                  List.of(NavList.item(Symbol.TABLE, "logs")
                      .meta(label(Label.Variant.SMALL, "2m"))
                      .count("12")),
                  0,
                  choose(h))
              .w(WIDTH - 20)
              .label("Tables")),
      new Row("status item", h -> StatusBar.status(StatusBar.State.OK, "localhost", h)),
      new Row(
          "badge",
          h -> Badge.badge(
              "Add filter",
              Badge.options().toolbar().kind(Badge.Kind.ACCENT).icon(Symbol.ADD).onPress(h))),
      new Row(
          "toggle button",
          h -> Button.toggle(Symbol.PIN, "Pin", false, h == null ? null : on -> h.run())),
      new Row(
          "toggle on",
          h -> Button.toggle(Symbol.PIN, "Pin", true, h == null ? null : on -> h.run())));

  /** A handler of a choice that runs {@code h}, or none when {@code h} is. */
  private static IntConsumer choose(Runnable h) {
    return h == null ? null : i -> h.run();
  }

  private final Theme theme;
  private final float scale;
  private final Path out;
  private final Pixels sheet;
  private final StringBuilder trees = new StringBuilder();
  private int cells;

  private Gallery(Theme theme, float scale, Path out) {
    this.theme = theme;
    this.scale = scale;
    this.out = out;
    int width = (State.values().length + 1) * Math.round(WIDTH * scale);
    int height = (2 + PROGRESS) * Math.round(HEIGHT * scale);
    for (Row row : ROWS) {
      height += Math.round(row.height() * scale);
    }
    this.sheet = new Pixels(width, height, new byte[width * height * 4]);
  }

  /** Draws the sheet; each cell's frame goes into a folder of {@code out}. */
  static Headless.Frame draw(Theme theme, float scale, Path out) throws IOException {
    Gallery gallery = new Gallery(theme, scale, out);
    int cell = Math.round(HEIGHT * scale);
    gallery.put(0, 0, HEIGHT, el(), "");
    for (State state : State.values()) {
      gallery.put(1 + state.ordinal(), 0, HEIGHT, gallery.name(state.name()), "");
    }
    // The label's styles have no states.
    gallery.put(0, cell, HEIGHT, gallery.name("label"), "");
    gallery.put(1, cell, HEIGHT, label("Normal"), "");
    gallery.put(2, cell, HEIGHT, label(Label.Variant.MUTED, "Muted"), "");
    gallery.put(3, cell, HEIGHT, label(Label.Variant.MONO, "mono_0123"), "");
    gallery.put(4, cell, HEIGHT, label(Label.Variant.HEADING, "Heading"), "");
    gallery.put(5, cell, HEIGHT, label(Label.Variant.STRONG, "Strong"), "");
    // Progress takes no input: its rows show its forms, the running ones at two times.
    float w = WIDTH - 20;
    String[] names = {"progress", "running", "labeled", "ring"};
    El[][] forms = {
      {
        Progress.bar(0).w(w),
        Progress.bar(0.42).w(w),
        Progress.bar(1).w(w),
        Progress.bar(0.64, Progress.State.ERROR).w(w),
        Progress.bar(1, Progress.State.DONE).w(w)
      },
      {
        Progress.bar(0.42, Progress.State.PAUSED).w(w),
        Progress.indeterminate().w(w),
        Progress.indeterminate().w(w),
        Progress.spinner(),
        Progress.spinner()
      },
      {
        Progress.labeled("orders.csv", 0.42, null).w(w),
        Progress.labeled("orders.csv", 0.64, "Failed", Progress.State.ERROR).w(w),
        Progress.labeled("orders.csv", 1, "Done", Progress.State.DONE).w(w),
        Progress.line(0.42).w(w),
        Progress.line().w(w)
      },
      {
        Progress.ring(0),
        Progress.ring(0.08),
        Progress.ring(0.42),
        Progress.ring(0.75),
        Progress.ring(1)
      }
    };
    String[][] times = {
      {"", "", "", "", ""},
      {"", "advance 0.5\nframe", "advance 1.1\nframe", "advance 0.2\nframe", "advance 0.7\nframe"},
      {"", "", "", "", "advance 0.5\nframe"},
      {"", "", "", "", ""}
    };
    int top = 2 * cell;
    for (int r = 0; r < names.length; r++) {
      gallery.put(0, top, HEIGHT, gallery.name(names[r]), "");
      for (int c = 0; c < forms[r].length; c++) {
        String tree = gallery.put(1 + c, top, HEIGHT, forms[r][c].key("c"), times[r][c]);
        gallery
            .trees
            .append("# ")
            .append(names[r])
            .append(", ")
            .append(c + 1)
            .append('\n')
            .append(tree);
      }
      top += cell;
    }
    for (Row row : ROWS) {
      gallery.put(0, top, row.height(), gallery.name(row.name()), "");
      for (State state : State.values()) {
        El component = row.component().apply(state == State.DISABLED ? null : () -> {});
        String steps = state.steps + (row.steps().isEmpty() ? "" : "\n" + row.steps());
        String tree =
            gallery.put(1 + state.ordinal(), top, row.height(), component.key("c"), steps);
        gallery
            .trees
            .append("# ")
            .append(row.name())
            .append(", ")
            .append(state.name().toLowerCase(Locale.ROOT))
            .append('\n')
            .append(tree);
      }
      top += Math.round(row.height() * scale);
    }
    return new Headless.Frame(gallery.sheet, gallery.trees.toString());
  }

  private El name(String text) {
    return label(Label.Variant.MUTED, text.toLowerCase(Locale.ROOT));
  }

  /**
   * Runs the steps on an app the size of a cell, {@code height} logical pixels high, with {@code
   * component} in the middle, and puts the frame into the sheet at this column, {@code top}
   * physical pixels from its top.
   *
   * @return the tree of the frame
   */
  private String put(int column, int top, int height, El component, String steps)
      throws IOException {
    El view = row().wFull().hFull().center().fill(theme.surface().window()).child(component);
    App<Object> app = new App<>(new Object(), s -> view);
    app.textRendering(TextRendering.LINUX);
    Path folder = out.resolve(String.valueOf(cells++));
    Script.run(
        "size " + WIDTH + " " + height + " scale " + scale + "\nframe\n" + steps + "\nsnap cell",
        app,
        folder);
    Pixels cell = Png.decode(Files.readAllBytes(folder.resolve("cell.png")));
    for (int y = 0; y < cell.height(); y++) {
      System.arraycopy(
          cell.rgba(),
          y * cell.width() * 4,
          sheet.rgba(),
          ((top + y) * sheet.width() + column * cell.width()) * 4,
          cell.width() * 4);
    }
    return Files.readString(folder.resolve("cell.tree.txt"));
  }
}
