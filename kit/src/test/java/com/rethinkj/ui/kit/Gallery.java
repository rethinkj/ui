package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.codeArea;
import static com.rethinkj.ui.kit.TextField.passwordField;
import static com.rethinkj.ui.kit.TextField.revealable;
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
import java.util.function.BiFunction;
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
  static final int AREA = 72; // the rows of text areas, three lines high
  static final int PROGRESS = 3; // the rows of progress forms, which have no states

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
  record Row(String name, BiFunction<Theme, Runnable, El> component, String steps, int height) {

    Row(String name, BiFunction<Theme, Runnable, El> component) {
      this(name, component, "");
    }

    Row(String name, BiFunction<Theme, Runnable, El> component, String steps) {
      this(name, component, steps, HEIGHT);
    }
  }

  /** A text field as wide as a cell allows, enabled when the handler is. */
  private static El field(Theme t, String value, Runnable handler) {
    return textField(t, value, "Search tables", handler == null ? null : s -> handler.run())
        .w(WIDTH - 20);
  }

  /** A text area three lines high, as wide as {@link #field}. */
  private static El area(Theme t, Runnable handler) {
    return textArea(t, "Ship by Friday,\nfrom the north\nwarehouse.", "Notes",
        handler == null ? null : s -> handler.run()).w(WIDTH - 20).lines(3);
  }

  /** A code area three lines high, with a line a tab indents. */
  private static El code(Theme t, Runnable handler) {
    return codeArea(t, "SELECT id,\n\tname\nFROM t;", "SQL",
        handler == null ? null : s -> handler.run()).w(WIDTH - 20).lines(3);
  }

  /** A password field with its button, as wide as {@link #field}. */
  private static El password(Theme t, boolean revealed, Runnable handler) {
    El field = passwordField(t, "hunter2", "Password", handler == null ? null : s -> handler.run());
    return revealable(t, field.w(WIDTH - 20), revealed, handler);
  }

  static final List<Row> ROWS =
      List.of(
          new Row("primary", (t, h) -> button(t, Variant.PRIMARY, "Run query", h)),
          new Row("secondary", (t, h) -> button(t, Variant.SECONDARY, "Cancel", h)),
          new Row("busy", (t, h) -> button(t, Variant.PRIMARY, null, "Running", true, h)),
          new Row("ghost", (t, h) -> button(t, Variant.GHOST, "Format", h)),
          new Row("toggle off", (t, h) -> toggle(t, false, "Autocommit", h)),
          new Row("toggle on", (t, h) -> toggle(t, true, "Autocommit", h)),
          new Row("checkbox off", (t, h) -> checkbox(t, false, "Read only", h)),
          new Row("checkbox on", (t, h) -> checkbox(t, true, "Read only", h)),
          new Row("field empty", (t, h) -> field(t, "", h)),
          new Row("field filled", (t, h) -> field(t, "orders 2024", h)),
          // The last word selected from the caret, wherever the state left it.
          new Row("field selected", (t, h) -> field(t, "orders 2024", h),
              "key ArrowRight control shift\nkey ArrowLeft control shift\nframe"),
          new Row("password", (t, h) -> password(t, false, h)),
          new Row("password shown", (t, h) -> password(t, true, h)),
          new Row("area", Gallery::area, "", AREA),
          // From the third character to the third of the last line: the line feeds shown.
          new Row("area selected", Gallery::area, "key Home control\nkey ArrowRight\n"
              + "key ArrowRight\nkey ArrowDown shift\nkey ArrowDown shift\nframe", AREA),
          new Row("code area", Gallery::code, "", AREA),
          new Row("switch row", (t, h) -> Group.switchRow(t, "Wrap", null, true, h).w(WIDTH - 20)),
          new Row("link row", (t, h) -> Group.linkRow(t, "Keymap", null, null, h).w(WIDTH - 20)),
          new Row("expander", (t, h) -> Group.expander(t, "More", null, false, h).w(WIDTH - 20)),
          new Row("radio", (t, h) -> Radio.radioGroup(t, List.of("Spaces"), 0, choose(h))),
          new Row("segmented", (t, h) ->
              Segmented.segmented(t, List.of("Rows", "Cells"), 0, choose(h)).label("Mode")),
          new Row("number", (t, h) -> NumberField.numberField(t, 4, 1, 16, 1, choose(h))
              .w(WIDTH - 40)),
          new Row("slider", (t, h) -> Slider.slider(t, 13, 11, 16, 1, choose(h)).w(WIDTH - 20)
              .label("Size")),
          new Row("nav list", (t, h) -> NavList.navList(t, List.of(NavList.item(Symbol.TABLE,
              "orders").count("12")), 0, choose(h)).w(WIDTH - 20).label("Tables")));

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
    Theme t = theme;
    gallery.put(0, cell, HEIGHT, gallery.name("label"), "");
    gallery.put(1, cell, HEIGHT, label(t, "Normal"), "");
    gallery.put(2, cell, HEIGHT, label(t, Label.Variant.MUTED, "Muted"), "");
    gallery.put(3, cell, HEIGHT, label(t, Label.Variant.MONO, "mono_0123"), "");
    gallery.put(4, cell, HEIGHT, label(t, Label.Variant.HEADING, "Heading"), "");
    gallery.put(5, cell, HEIGHT, label(t, Label.Variant.STRONG, "Strong"), "");
    // Progress takes no input: its rows show its forms, the running ones at two times.
    float w = WIDTH - 20;
    String[] names = {"progress", "running", "labeled"};
    El[][] forms = {
        {Progress.bar(t, 0).w(w), Progress.bar(t, 0.42).w(w), Progress.bar(t, 1).w(w),
            Progress.bar(t, 0.64, Progress.State.ERROR).w(w),
            Progress.bar(t, 1, Progress.State.DONE).w(w)},
        {Progress.bar(t, 0.42, Progress.State.PAUSED).w(w), Progress.indeterminate(t).w(w),
            Progress.indeterminate(t).w(w), Progress.spinner(t), Progress.spinner(t)},
        {Progress.labeled(t, "orders.csv", 0.42, null).w(w),
            Progress.labeled(t, "orders.csv", 0.64, "Failed", Progress.State.ERROR).w(w),
            Progress.labeled(t, "orders.csv", 1, "Done", Progress.State.DONE).w(w),
            Progress.line(t, 0.42).w(w), Progress.line(t).w(w)}};
    String[][] times = {
        {"", "", "", "", ""},
        {"", "advance 0.5\nframe", "advance 1.1\nframe", "advance 0.2\nframe",
            "advance 0.7\nframe"},
        {"", "", "", "", "advance 0.5\nframe"}};
    int top = 2 * cell;
    for (int r = 0; r < names.length; r++) {
      gallery.put(0, top, HEIGHT, gallery.name(names[r]), "");
      for (int c = 0; c < forms[r].length; c++) {
        String tree = gallery.put(1 + c, top, HEIGHT, forms[r][c].key("c"), times[r][c]);
        gallery.trees.append("# ").append(names[r]).append(", ").append(c + 1).append('\n')
            .append(tree);
      }
      top += cell;
    }
    for (Row row : ROWS) {
      gallery.put(0, top, row.height(), gallery.name(row.name()), "");
      for (State state : State.values()) {
        El component = row.component().apply(t, state == State.DISABLED ? null : () -> {});
        String steps = state.steps + (row.steps().isEmpty() ? "" : "\n" + row.steps());
        String tree = gallery.put(1 + state.ordinal(), top, row.height(), component.key("c"),
            steps);
        gallery.trees.append("# ").append(row.name()).append(", ")
            .append(state.name().toLowerCase(Locale.ROOT)).append('\n').append(tree);
      }
      top += Math.round(row.height() * scale);
    }
    return new Headless.Frame(gallery.sheet, gallery.trees.toString());
  }

  private El name(String text) {
    return label(theme, Label.Variant.MUTED, text.toLowerCase(Locale.ROOT));
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
