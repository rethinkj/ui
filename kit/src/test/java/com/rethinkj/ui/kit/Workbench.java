package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Split.split;
import static com.rethinkj.ui.kit.Tabs.tabs;
import static com.rethinkj.ui.kit.TextField.textField;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Label.Variant;
import com.rethinkj.ui.kit.Split.Direction;
import com.rethinkj.ui.kit.Split.Position;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The frame of a database tool as the app holds it: where the lines of its splits are, the open
 * queries and the active one, which the handlers change and log. Its views show a split alone,
 * tabs alone, and both together as the tool's frame: the schema on the left, the queries in tabs
 * on the right, each an editor over its results. The content is plain placeholders.
 */
final class Workbench {

  static final Position SIDEBAR = Position.first(200);
  static final Position EDITOR = Position.share(0.5f);
  static final String SQL = "SELECT name FROM customers";

  /** An open query: its title, whether it has unsaved changes, whether it can be closed. */
  record Query(String title, boolean modified, boolean closable) {}

  final Theme theme;
  Position sidebar = SIDEBAR;
  Position editor = EDITOR;
  final List<Query> queries = new ArrayList<>();
  int active;
  String filter = "";
  final List<String> log = new ArrayList<>();

  Workbench(Theme theme, String... titles) {
    this.theme = theme;
    for (String title : titles) {
      queries.add(new Query(title, title.endsWith("*"), !title.equals("Console")));
    }
  }

  /** The frame's queries, with the third one modified and active. */
  static Workbench frame(Theme theme) {
    Workbench w = new Workbench(theme,
        "Console", "customers.sql", "orders by month.sql*", "products.sql");
    w.active = 2;
    return w;
  }

  /** More queries than a bar of 400 has room for, the second one modified. */
  static Workbench many(Theme theme) {
    return new Workbench(theme, "customers.sql", "orders by month.sql*", "Console",
        "products.sql", "invoices 2026.sql", "slow queries.sql", "schema diff.sql",
        "migration 042.sql");
  }

  // Views

  /** The schema and an editor side by side, the schema at least 120 wide, the editor 160. */
  El splitView() {
    return window(split(theme, Direction.ROW, sidebar, SIDEBAR, this::sidebar,
        schema(), 120, editorPane(), 160).key("split").grow(1));
  }

  /** The editor over its results, at least 40 high each. */
  El columnView() {
    return window(split(theme, Direction.COLUMN, editor, EDITOR, this::editor,
        editorPane(), 40, results(), 40).key("split").grow(1));
  }

  /** The queries in tabs, the active one with a field for a filter. */
  El tabsView() {
    return window(queries().key("queries").grow(1));
  }

  /**
   * The tool's frame: the schema beside the queries, each an editor over its results. The root
   * takes the keys of the tabs too, for when the focus is elsewhere or nowhere.
   */
  El frameView() {
    El queries = queries();
    return window(split(theme, Direction.ROW, sidebar, SIDEBAR, this::sidebar,
        schema(), 160,
        queries, 320).key("frame").grow(1))
        .onKey(queries.onKey());
  }

  private El window(El child) {
    return col().wFull().hFull().fill(theme.surface().window()).child(child);
  }

  private El queries() {
    List<Tab> tabs = queries.stream()
        .map(q -> new Tab(q.title().replace("*", ""), q.closable(), q.modified()))
        .toList();
    El content = active < 0 || active >= queries.size() ? null
        : queries.get(active).title().startsWith("orders")
            ? split(theme, Direction.COLUMN, editor, EDITOR, this::editor,
                editorPane(), 80, results(), 80).key("work")
            : col().key("work").padding(theme.space().m()).gap(theme.space().s())
                .children(
                    label(theme, Variant.MUTED, queries.get(active).title().replace("*", "")),
                    textField(theme, filter, "Filter", f -> filter = f).key("filter"));
    return tabs(theme, tabs, active, this::select, this::close, content);
  }

  private El schema() {
    El schema = col().key("schema").label("Schema").fill(theme.surface().panel())
        .padding(theme.space().m()).gap(theme.space().s())
        .child(label(theme, Variant.HEADING, "public"));
    for (String table : List.of("customers", "orders", "order_items", "products", "invoices")) {
      schema.child(label(theme, table));
    }
    return schema;
  }

  private El editorPane() {
    El editor = col().key("editor").label("Query").padding(theme.space().m())
        .gap(theme.space().xs());
    for (String line : List.of("SELECT date_trunc('month', o.created_at) AS month,",
        "       count(*) AS orders, sum(o.amount) AS total", "FROM orders o",
        "GROUP BY 1", "ORDER BY 1 DESC;")) {
      editor.child(label(theme, Variant.MONO, line));
    }
    return editor;
  }

  private El results() {
    return col().key("results").label("Results").fill(theme.surface().panel())
        .padding(theme.space().m())
        .child(label(theme, Variant.MUTED, "12 rows in 18 ms"));
  }

  // Handlers

  private void sidebar(Position p) {
    log.add("sidebar " + p);
    sidebar = p;
  }

  private void editor(Position p) {
    log.add("editor " + p);
    editor = p;
  }

  private void select(int i) {
    log.add("select " + i);
    active = i;
  }

  private void close(int i) {
    log.add("close " + i);
    queries.remove(i);
    if (i < active || active == queries.size()) {
      active--;
    }
  }

  App<Workbench> app(Function<Workbench, El> view) {
    App<Workbench> app = new App<>(this, view);
    app.textRendering(TextRendering.LINUX);
    app.focusRing(theme.border().focus());
    return app;
  }

  /**
   * Runs the script in {@code src/test/resources/scripts} with this name and compares each snap
   * with the stored image {@code <name>-<theme>-<snap>}, the theme named by its window colour.
   *
   * @return the number of snaps
   */
  static int assertLooksAsStored(String name, String themeName, App<?> app) throws IOException {
    Path folder = Path.of("target", name, themeName);
    String script = Files.readString(Path.of("src/test/resources/scripts", name + ".txt"));
    List<Path> files = Script.run(script, app, folder);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches(
          name + "-" + themeName + "-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))),
          Files.readString(files.get(i + 1)));
    }
    return files.size() / 2;
  }

  // Finding elements

  /** The first element with this key in tree order, or null. */
  static El find(App<?> app, String key) {
    return find(app.tree(), e -> key.equals(e.key()));
  }

  /** The first element with this label in tree order, or null. */
  static El labelled(App<?> app, String label) {
    return find(app.tree(), e -> label.equals(e.label()));
  }

  private static El find(El el, Predicate<El> wanted) {
    if (wanted.test(el)) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, wanted);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** The left edge of the element in the window, or NaN if it is not in the tree. */
  static float x(App<?> app, El el) {
    float[] at = at(app.tree(), el, 0, 0);
    return at == null ? Float.NaN : at[0];
  }

  /** The top edge of the element in the window, or NaN if it is not in the tree. */
  static float y(App<?> app, El el) {
    float[] at = at(app.tree(), el, 0, 0);
    return at == null ? Float.NaN : at[1];
  }

  private static float[] at(El el, El target, float left, float top) {
    float x = left + el.x();
    float y = top + el.y();
    if (el == target) {
      return new float[] {x, y};
    }
    for (El child : el.children()) {
      float[] found = at(child, target, x, y);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
