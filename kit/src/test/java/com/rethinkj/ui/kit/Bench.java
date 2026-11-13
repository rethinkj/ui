package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textField;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The workbench of a database tool, for the tests of menus, tooltips, dialogs and selects: a File
 * menu, a Rename button that opens a dialog, a help button with a tooltip and a select of
 * encodings above a list of tables with a context menu, and a small scrolled panel of actions in
 * the bottom right corner whose last button opens a menu. Its window is 360 by 240.
 */
final class Bench {

  static final String[] TABLES = {
    "accounts", "customers", "invoices", "orders", "payments", "products", "shipments", "suppliers"
  };
  static final List<String> ENCODINGS = List.of("UTF-8", "UTF-16", "ISO-8859-1", "Windows-1252");

  final Theme t;
  final Menu.State file = new Menu.State();
  final Menu.State tables = new Menu.State();
  final Menu.State more = new Menu.State();
  final Menu.State encodings = new Menu.State();
  int encoding;
  int selected = -1;
  boolean wrap = true;
  boolean renaming;
  String name = "orders";
  String draft = "";
  String status = "Ready";

  Bench(Theme t) {
    this.t = t;
  }

  App<Bench> app() {
    App<Bench> app = new App<>(this, Bench::view);
    app.focusRing(t.accent().edge());
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  void startRename() {
    draft = name;
    renaming = true;
  }

  El view() {
    El fileMenu = Menu.dropdown(
        t,
        button(t, SECONDARY, "File", file::toggle).key("file"),
        file,
        List.of(
            Menu.item("New query", () -> status = "New query").shortcut("Ctrl+N"),
            Menu.item("Open…", () -> status = "Open").shortcut("Ctrl+O"),
            Menu.separator(),
            Menu.submenu(
                "Export",
                List.of(
                    Menu.item("CSV", () -> status = "Exported as CSV"),
                    Menu.item("JSON", () -> status = "Exported as JSON"),
                    Menu.item("SQL inserts", null))),
            Menu.separator(),
            Menu.item("Word wrap", () -> wrap = !wrap).checked(wrap),
            Menu.item("Close", null)));
    El help = Tooltip.tooltip(
        t, button(t, GHOST, "?", () -> status = "Help").key("help"), "Keyboard shortcuts");
    El toolbar = row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .children(
            fileMenu,
            button(t, SECONDARY, "Rename…", this::startRename).key("rename"),
            help,
            Select.select(t, ENCODINGS, encoding, encodings, i -> encoding = i)
                .key("encoding")
                .w(120));

    El list = col()
        .key("tables")
        .role("list")
        .label("Tables")
        .focusable()
        .grow(1)
        .scroll()
        .padding(t.space().s())
        .radius(t.radius().large())
        .fill(t.surface().panel())
        .border(t.size().stroke(), t.border().subtle());
    for (int i = 0; i < TABLES.length; i++) {
      int at = i;
      list.child(row()
          .key("table-" + i)
          .role("listitem")
          .label(TABLES[i])
          .h(t.size().control())
          .shrink(0)
          .padding(0, t.space().m())
          .align(Style.Align.CENTER)
          .radius(t.radius().medium())
          .fill(i == selected ? t.grid().selectedInactive() : 0)
          .onPress(() -> selected = at)
          .onSecondaryPress((x, y) -> {
            selected = at;
            tables.openAt(x, y);
          })
          .child(label(t, TABLES[i])));
    }
    Menu.contextMenu(
        t,
        list,
        tables,
        List.of(
            Menu.item("Open", () -> status = "Open " + TABLES[selected]),
            Menu.item("Rename…", this::startRename).shortcut("F2"),
            Menu.item("Copy name", () -> status = "Copied").shortcut("Ctrl+C"),
            Menu.separator(),
            Menu.item("Duplicate", () -> status = "Duplicated " + TABLES[selected]),
            Menu.item("Drop table", () -> status = "Dropped " + TABLES[selected])));

    El actions = col()
        .key("actions")
        .role("toolbar")
        .label("Actions")
        .w(110)
        .h(84)
        .alignSelf(Style.Align.END)
        .scroll()
        .scrollbars(t.scrollbars())
        .padding(t.space().s())
        .gap(t.space().xs())
        .radius(t.radius().large())
        .fill(t.surface().panel())
        .border(t.size().stroke(), t.border().subtle())
        .children(
            button(t, GHOST, "Refresh", () -> status = "Refreshed"),
            button(t, GHOST, "Analyze", () -> status = "Analyzed"),
            button(t, GHOST, "Vacuum", () -> status = "Vacuumed"),
            button(t, GHOST, "Reindex", () -> status = "Reindexed"),
            Menu.dropdown(
                t,
                button(t, GHOST, "More…", more::toggle).key("more"),
                more,
                List.of(
                    Menu.item("Properties", () -> status = "Properties"),
                    Menu.item("Permissions", () -> status = "Permissions"),
                    Menu.separator(),
                    Menu.item("Truncate", null))));

    El root = col()
        .wFull()
        .hFull()
        .padding(t.space().l())
        .gap(t.space().m())
        .fill(t.surface().window())
        .children(
            toolbar,
            row().grow(1).minSize(0, 0).gap(t.space().m()).children(list, actions),
            label(
                    t,
                    Label.Variant.MUTED,
                    status + " · " + name + " · " + ENCODINGS.get(encoding)
                        + (wrap ? " · wrap" : ""))
                .key("status"));
    if (renaming) {
      root.overlay(Dialog.dialog(
          t,
          "Rename table",
          textField(t, draft, "Table name", v -> draft = v).key("name"),
          List.of(
              button(t, SECONDARY, "Cancel", () -> renaming = false).key("cancel"),
              button(t, PRIMARY, "Rename", () -> {
                    name = draft;
                    renaming = false;
                    status = "Renamed";
                  })
                  .key("ok")),
          () -> renaming = false));
    }
    return root;
  }

  /**
   * Runs a script of {@code src/test/resources/scripts} in a window of 360 by 240 at this scale and
   * compares its snaps with the stored images {@code <prefix>-<theme>-<scale>x-<snap>}.
   *
   * @return the app after the script
   */
  static App<Bench> assertScript(String file, String theme, int scale, Path out)
      throws IOException {
    Bench bench = new Bench(theme.equals("dark") ? Theme.DARK : Theme.LIGHT);
    App<Bench> app = bench.app();
    String script = Files.readString(Path.of("src/test/resources/scripts", file + ".txt"));
    Path folder = out.resolve(file + "-" + theme + "-" + scale + "x");
    List<Path> files = Script.run("size 360 240 scale " + scale + "\n" + script, app, folder);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      assertEquals(snap + ".tree.txt", files.get(i + 1).getFileName().toString());
      Golden.assertMatches(
          file + "-" + theme + "-" + scale + "x-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))),
          Files.readString(files.get(i + 1)));
    }
    return app;
  }

  /** The tree of a snap of the last {@link #assertScript} with these arguments. */
  static String tree(Path out, String file, String theme, int scale, String snap)
      throws IOException {
    return Files.readString(
        out.resolve(file + "-" + theme + "-" + scale + "x").resolve(snap + ".tree.txt"));
  }
}
