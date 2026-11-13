package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.label;
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

  record Row(String name, BiFunction<Theme, Runnable, El> component, String steps) {

    Row(String name, BiFunction<Theme, Runnable, El> component) {
      this(name, component, "");
    }
  }

  /** A text field as wide as a cell allows, enabled when the handler is. */
  private static El field(Theme t, String value, Runnable handler) {
    return textField(t, value, "Search tables", handler == null ? null : s -> handler.run())
        .w(WIDTH - 20);
  }

  static final List<Row> ROWS =
      List.of(
          new Row("primary", (t, h) -> button(t, Variant.PRIMARY, "Run query", h)),
          new Row("secondary", (t, h) -> button(t, Variant.SECONDARY, "Cancel", h)),
          new Row("ghost", (t, h) -> button(t, Variant.GHOST, "Format", h)),
          new Row("toggle off", (t, h) -> toggle(t, false, "Autocommit", h)),
          new Row("toggle on", (t, h) -> toggle(t, true, "Autocommit", h)),
          new Row("checkbox off", (t, h) -> checkbox(t, false, "Read only", h)),
          new Row("checkbox on", (t, h) -> checkbox(t, true, "Read only", h)),
          new Row("field empty", (t, h) -> field(t, "", h)),
          new Row("field filled", (t, h) -> field(t, "orders 2024", h)),
          // The last word selected from the caret, wherever the state left it.
          new Row("field selected", (t, h) -> field(t, "orders 2024", h),
              "key ArrowRight control shift\nkey ArrowLeft control shift\nframe"));

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
    int height = (ROWS.size() + 2) * Math.round(HEIGHT * scale);
    this.sheet = new Pixels(width, height, new byte[width * height * 4]);
  }

  /** Draws the sheet; each cell's frame goes into a folder of {@code out}. */
  static Headless.Frame draw(Theme theme, float scale, Path out) throws IOException {
    Gallery gallery = new Gallery(theme, scale, out);
    gallery.put(0, 0, el(), "");
    for (State state : State.values()) {
      gallery.put(1 + state.ordinal(), 0, gallery.name(state.name()), "");
    }
    // The label's styles have no states.
    Theme t = theme;
    gallery.put(0, 1, gallery.name("label"), "");
    gallery.put(1, 1, label(t, "Normal"), "");
    gallery.put(2, 1, label(t, Label.Variant.MUTED, "Muted"), "");
    gallery.put(3, 1, label(t, Label.Variant.MONO, "mono_0123"), "");
    gallery.put(4, 1, label(t, Label.Variant.HEADING, "Heading"), "");
    gallery.put(5, 1, el(), "");
    for (int r = 0; r < ROWS.size(); r++) {
      Row row = ROWS.get(r);
      gallery.put(0, r + 2, gallery.name(row.name()), "");
      for (State state : State.values()) {
        El component = row.component().apply(t, state == State.DISABLED ? null : () -> {});
        String steps = state.steps + (row.steps().isEmpty() ? "" : "\n" + row.steps());
        String tree = gallery.put(1 + state.ordinal(), r + 2, component.key("c"), steps);
        gallery.trees.append("# ").append(row.name()).append(", ")
            .append(state.name().toLowerCase(Locale.ROOT)).append('\n').append(tree);
      }
    }
    return new Headless.Frame(gallery.sheet, gallery.trees.toString());
  }

  private El name(String text) {
    return label(theme, Label.Variant.MUTED, text.toLowerCase(Locale.ROOT));
  }

  /**
   * Runs the steps on an app the size of a cell with {@code component} in the middle, and puts
   * the frame into the sheet at this column and row.
   *
   * @return the tree of the frame
   */
  private String put(int column, int row, El component, String steps) throws IOException {
    El view = row().wFull().hFull().center().fill(theme.surface().window()).child(component);
    App<Object> app = new App<>(new Object(), s -> view);
    app.textRendering(TextRendering.LINUX);
    app.focusRing(theme.border().focus());
    Path folder = out.resolve(String.valueOf(cells++));
    Script.run(
        "size " + WIDTH + " " + HEIGHT + " scale " + scale + "\nframe\n" + steps + "\nsnap cell",
        app,
        folder);
    Pixels cell = Png.decode(Files.readAllBytes(folder.resolve("cell.png")));
    for (int y = 0; y < cell.height(); y++) {
      System.arraycopy(
          cell.rgba(),
          y * cell.width() * 4,
          sheet.rgba(),
          ((row * cell.height() + y) * sheet.width() + column * cell.width()) * 4,
          cell.width() * 4);
    }
    return Files.readString(folder.resolve("cell.tree.txt"));
  }
}
