package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textField;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A query editor in a frameless window of 360 by 240 unless a test says otherwise, for the tests
 * of the menu bar and the toast: a title bar that moves the window, with the bar's File, Edit,
 * View and Help menus and the window's buttons, then a field with the query and a status line.
 * File's Export goes three submenus deep, and its Close is disabled. Edit's Clear empties the
 * query and says so in a toast with Undo.
 */
final class Studio {

  final Theme t;
  final MenuBar.State bar = new MenuBar.State();
  String query = "SELECT * FROM orders";
  String status = "Ready";
  String cleared; // the query that Clear took, while its toast shows
  boolean wrap = true;
  boolean numbers;

  Studio(Theme t) {
    this.t = t;
  }

  App<Studio> app() {
    App<Studio> app = new App<>(this, Studio::view);
    app.focusRing(t.accent().edge());
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private Runnable says(String text) {
    return () -> status = text;
  }

  List<Menu.Item> menus() {
    return List.of(
        Menu.submenu("&File", List.of(
            Menu.item("New query", says("New query")).icon(Symbol.ADD)
                .shortcut(Key.CONTROL, "N"),
            Menu.item("Open…", says("Open")).icon(Symbol.FOLDER_OPEN)
                .shortcut(Key.CONTROL, "O"),
            Menu.item("Save", says("Saved")).icon(Symbol.SAVE).shortcut(Key.CONTROL, "S"),
            Menu.separator(),
            Menu.submenu("Export", List.of(
                Menu.item("CSV", says("Exported as CSV")),
                Menu.item("JSON", says("Exported as JSON")),
                Menu.submenu("SQL", List.of(
                    Menu.item("Inserts", says("Exported as inserts")),
                    Menu.submenu("Script", List.of(
                        Menu.item("Schema", says("Exported the schema")),
                        Menu.item("Schema and data", says("Exported everything")))))))),
            Menu.separator(),
            Menu.item("Close", null).shortcut(Key.CONTROL, "W"),
            Menu.item("Quit", says("Quit")).shortcut(Key.CONTROL, "Q"))),
        Menu.submenu("&Edit", List.of(
            Menu.item("Undo", says("Undone")).icon(Symbol.ARROW_UNDO)
                .shortcut(Key.CONTROL, "Z"),
            Menu.item("Redo", says("Redone")).icon(Symbol.ARROW_REDO)
                .shortcut(Key.CONTROL | Key.SHIFT, "Z"),
            Menu.separator(),
            Menu.item("Cut", says("Cut")).icon(Symbol.CUT).shortcut(Key.CONTROL, "X"),
            Menu.item("Copy", says("Copied")).icon(Symbol.COPY).shortcut(Key.CONTROL, "C"),
            Menu.item("Paste", says("Pasted")).icon(Symbol.CLIPBOARD_PASTE)
                .shortcut(Key.CONTROL, "V"),
            Menu.separator(),
            Menu.item("Clear", this::clear).icon(Symbol.DELETE))),
        Menu.submenu("&View", List.of(
            Menu.item("Word wrap", () -> wrap = !wrap).checked(wrap),
            Menu.item("Line numbers", () -> numbers = !numbers).checked(numbers),
            Menu.separator(),
            Menu.item("Refresh", says("Refreshed")).icon(Symbol.ARROW_CLOCKWISE)
                .shortcut(0, "F5"))),
        Menu.submenu("&Help", List.of(
            Menu.item("About", says("About")).icon(Symbol.INFO))));
  }

  void clear() {
    cleared = query;
    query = "";
  }

  El view() {
    List<Menu.Item> menus = menus();
    El titleBar = row().key("titlebar").wFull().h(32).shrink(0).align(Style.Align.CENTER)
        .padding(0, 0, 0, t.space().m()).gap(t.space().s())
        .fill(t.surface().panel()).windowPart(WindowPart.DRAG)
        .children(
            icon(t, Symbol.DATABASE, Icon.Variant.MUTED),
            MenuBar.menuBar(t, bar, menus),
            el().grow(1),
            frameButton(WindowPart.MINIMIZE, "Minimize", Symbol.SUBTRACT),
            frameButton(WindowPart.CLOSE, "Close", Symbol.DISMISS));
    return col().wFull().hFull().fill(t.surface().window())
        .children(
            titleBar,
            col().grow(1).padding(t.space().l()).gap(t.space().m()).children(
                textField(t, query, "Query", v -> query = v).key("query").wFull(),
                label(t, Label.Variant.MUTED, status + (wrap ? " · wrap" : "")
                    + (numbers ? " · numbers" : "")).key("status")))
        .onKey(MenuBar.keys(bar, menus))
        .onKeyUp(MenuBar.keysUp(bar, menus))
        .overlay(cleared == null ? null : Toast.toast(t, "Query cleared", "Undo", () -> {
          query = cleared;
          status = "Restored";
        }, () -> cleared = null));
  }

  private El frameButton(WindowPart part, String label, Symbol symbol) {
    return row().key(part.name().toLowerCase(java.util.Locale.ROOT)).role("button").label(label)
        .size(40, 32).center().windowPart(part).child(icon(t, symbol));
  }

  /**
   * Runs a script of {@code src/test/resources/scripts} in the window at this scale and compares
   * its snaps with the stored images {@code <script>-<theme>-<scale>x-<snap>}.
   *
   * @return the app after the script
   */
  static App<Studio> assertScript(String file, String theme, int scale, Path out)
      throws IOException {
    return assertScript(file, theme, scale, 360, 240, out);
  }

  /** As {@link #assertScript(String, String, int, Path)} in a window of this size. */
  static App<Studio> assertScript(
      String file, String theme, int scale, int width, int height, Path out) throws IOException {
    Studio studio = new Studio(theme.equals("dark") ? Theme.DARK : Theme.LIGHT);
    App<Studio> app = studio.app();
    String script = Files.readString(Path.of("src/test/resources/scripts", file + ".txt"));
    Path folder = out.resolve(file + "-" + theme + "-" + scale + "x");
    List<Path> files = Script.run(
        "size " + width + " " + height + " scale " + scale + "\n" + script, app, folder);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      assertEquals(snap + ".tree.txt", files.get(i + 1).getFileName().toString());
      Golden.assertMatches(file + "-" + theme + "-" + scale + "x-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))), Files.readString(files.get(i + 1)));
    }
    return app;
  }

  /** The tree of a snap of the last {@link #assertScript} with these arguments. */
  static String tree(Path out, String file, String theme, int scale, String snap)
      throws IOException {
    return Files.readString(out.resolve(file + "-" + theme + "-" + scale + "x")
        .resolve(snap + ".tree.txt"));
  }
}
