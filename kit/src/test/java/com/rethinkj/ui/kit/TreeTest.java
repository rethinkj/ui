package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.kit.Catalog.Node;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tree with the catalog of three database servers ({@link Catalog}): the rows it builds and
 * what a rebuild costs, the pointer, the keys, children that load late, and stored images.
 */
class TreeTest {

  private static final Theme T = Theme.DARK;

  @TempDir Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Catalog> start(Catalog catalog) throws IOException {
    App<Catalog> app = catalog.app();
    run(app, "frame");
    return app;
  }

  /** Runs the steps in a window of 280 by 300, which each script has to say first. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 280 300\n" + steps, app, out);
  }

  @Test
  void openingTheLastNodeByKeyboardScrollsItsChildrenIntoView() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost", "localhost/shop", "localhost/shop/public");
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nkey End\nkey ArrowUp\nframe"); // a table near the end of the list
    Node table = catalog.selected;
    int row = selectedRow(app);
    float h = T.grid().rowHeight();
    El tree = Workbench.find(app, "schema");
    assertTrue(tree.scrollY() + tree.height() < (row + 2) * h + 1, "near the bottom of the view");

    run(app, "key ArrowRight\nframe");
    assertTrue(catalog.open.contains(table), "opened");
    assertEquals(row, selectedRow(app), "still selected");
    tree = Workbench.find(app, "schema");
    float shown = Math.min((row + 1 + table.children.size()) * h, row * h + tree.height());
    assertTrue(tree.scrollY() + tree.height() >= shown - 0.5f,
        "its columns below it, as far as there is room: " + tree.scrollY());
    assertTrue(tree.scrollY() <= row * h + 0.5f, "and the table itself");
  }

  /** The index of the selected row, from the dump. */
  private static int selectedRow(App<?> app) {
    Matcher m = Pattern.compile("#row-(\\d+) role=treeitem [^\\n]* selected").matcher(
        app.tree().dump());
    assertTrue(m.find(), "a selected row");
    return Integer.parseInt(m.group(1));
  }

  // Rows and cost

  /**
   * localhost/shop/public with 1000 tables of 99 columns, all open: 100 008 nodes are shown, the
   * three servers, localhost's three databases and shop's two schemas included.
   */
  private static Catalog hundredThousand() {
    Catalog catalog = new Catalog(T, 1000, 99).opened("localhost", "localhost/shop");
    Node schema = catalog.node("localhost/shop/public");
    catalog.open.add(schema);
    catalog.open.addAll(schema.children);
    return catalog;
  }

  private static final int SHOWN = 100_008;

  @Test
  void aHundredThousandNodesBuildOnlyTheRowsInViewAndAskOnlyForTheirLabels() throws IOException {
    Catalog catalog = hundredThousand();
    App<Catalog> app = start(catalog);
    assertRowsInDump(app, 0);
    assertLabelledOnce(catalog, app);

    catalog.labelled.clear();
    run(app, "scroll #schema row 76543\nframe");
    assertRowsInDump(app, 76_543 - El.OVERSCAN);
    assertLabelledOnce(catalog, app);
    // Six rows above the tables, a hundred for each: row 76543 is column 36 of table 765.
    El row = find(app, "row-76543");
    assertEquals("treeitem", row.role());
    assertEquals("customer_id_3", row.label());
    assertEquals(T.space().s() + 4 * T.space().xl(), row.children().getFirst().x(), "level 4");
  }

  /** The tree's line in the dump names the rows that exist, and they are its children. */
  private static void assertRowsInDump(App<Catalog> app, int first) {
    El tree = find(app, "schema");
    int last = first + tree.children().size() - 1;
    assertTrue(last - first + 1 <= 13 + 1 + 2 * El.OVERSCAN, "" + (last - first + 1));
    Matcher line = Pattern.compile("el #schema role=tree label=\"Schema\" .* rows (\\d+)-(\\d+)/(\\d+)")
        .matcher(app.tree().dump());
    assertTrue(line.find(), app.tree().dump());
    assertEquals(List.of(first, last, SHOWN), List.of(Integer.parseInt(line.group(1)),
        Integer.parseInt(line.group(2)), Integer.parseInt(line.group(3))));
    assertEquals(last - first + 1, app.tree().dump().split("role=treeitem", -1).length - 1);
  }

  /** The labels of the rows that exist were asked for once each, and no others. */
  private static void assertLabelledOnce(Catalog catalog, App<Catalog> app) {
    List<String> rows = find(app, "schema").children().stream().map(El::label).toList();
    assertEquals(rows, catalog.labelled.stream().map(n -> n.name).toList());
  }

  @Test
  void scrollingAHundredThousandNodesBuildsRowsButNeverTheView() throws IOException {
    Catalog catalog = hundredThousand();
    App<Catalog> app = start(catalog);
    int walks = catalog.walks;
    for (int i = 0; i < 200; i++) {
      app.wheel(140, 150, 0, 100);
      app.frame();
    }
    assertEquals(walks, catalog.walks, "the rows are numbered once, by the view");
    assertEquals(20_000 / 22 - El.OVERSCAN, find(app, "schema").firstRow());
  }

  @Test
  void aRebuildWithAHundredThousandNodesStaysWellUnderAFrame() throws IOException {
    long[] small = rebuilds(new Catalog(T).opened("localhost", "localhost/shop",
        "localhost/shop/public")); // 308 nodes
    long[] large = rebuilds(hundredThousand());
    System.out.printf(
        "Tree: a rebuild after ArrowDown, median of %d: frame() %.2f ms and the tree's view"
            + " %.2f ms with %d nodes shown, %.2f ms and %.2f ms with 308%n",
        REBUILDS, large[0] / 1e6, large[1] / 1e6, SHOWN, small[0] / 1e6, small[1] / 1e6);
    // About 1.3 ms on a desktop; a bound of a whole frame holds on a busy machine.
    assertTrue(large[0] < 16_000_000, "median frame() of " + large[0] + " ns, a frame");
  }

  @Test
  void aFrameWithABurstOfTenKeysCostsAViewAndItsLayoutPerKey() throws IOException {
    // Each key of a burst goes to the tree the key before it built: the view numbers the rows
    // again for each, and the rows are built once, at the end.
    Catalog catalog = hundredThousand();
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nframe");
    int walks = catalog.walks;
    catalog.labelled.clear();
    for (int k = 0; k < 10; k++) {
      app.keyDown(new Key("ArrowDown"));
      app.keyUp(new Key("ArrowDown"));
    }
    app.frame();
    assertEquals(walks + 10, catalog.walks, "the view, which numbers the rows, once per key");
    assertLabelledOnce(catalog, app);
    long one = keyFrames(hundredThousand(), 1);
    long ten = keyFrames(hundredThousand(), 10);
    System.out.printf("Tree: frame() after keys, median of %d, %d nodes shown: %.2f ms after one"
        + " ArrowDown, %.2f ms after ten%n", REBUILDS, SHOWN, one / 1e6, ten / 1e6);
  }

  private static final int REBUILDS = 200;

  /**
   * The median times of {@link App#frame} after an ArrowDown, which selects another node and so
   * rebuilds the view, and of building the tree's element alone, which numbers the rows.
   */
  private long[] rebuilds(Catalog catalog) throws IOException {
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nframe");
    long[] frames = new long[REBUILDS];
    long[] views = new long[REBUILDS];
    for (int i = -REBUILDS; i < REBUILDS; i++) { // the first round warms up
      app.keyDown(new Key(Math.floorMod(i, 40) < 20 ? "ArrowDown" : "ArrowUp"));
      long start = System.nanoTime();
      app.frame();
      long frame = System.nanoTime() - start;
      start = System.nanoTime();
      catalog.tree();
      long view = System.nanoTime() - start;
      if (i >= 0) {
        frames[i] = frame;
        views[i] = view;
      }
    }
    Arrays.sort(frames);
    Arrays.sort(views);
    return new long[] {frames[REBUILDS / 2], views[REBUILDS / 2]};
  }

  /** The median time of {@link App#frame} after {@code keys} presses of ArrowDown or ArrowUp. */
  private long keyFrames(Catalog catalog, int keys) throws IOException {
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nframe");
    long[] frames = new long[REBUILDS];
    for (int i = -REBUILDS; i < REBUILDS; i++) { // the first round warms up
      Key key = new Key(Math.floorMod(i, 4) < 2 ? "ArrowDown" : "ArrowUp");
      for (int k = 0; k < keys; k++) {
        app.keyDown(key);
        app.keyUp(key);
      }
      long start = System.nanoTime();
      app.frame();
      if (i >= 0) {
        frames[i] = System.nanoTime() - start;
      }
    }
    Arrays.sort(frames);
    return frames[REBUILDS / 2];
  }

  // The pointer

  @Test
  void aPressSelectsARowAndFocusesTheTreeWithoutARing() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = start(catalog);
    run(app, "press #row-1\nframe");
    assertEquals(List.of("select prod-eu"), catalog.log);
    assertEquals("schema", app.focused().key());
    assertEquals(T.grid().selected(), find(app, "row-1").fill(), "the focused tree's selection");
    assertTrue(dumpLine(app, "row-1").contains(" selected expanded=false level=1 pos=2/3"));

    run(app, "move #row-1\nframe");
    assertEquals(T.grid().selected(), find(app, "row-1").fill(), "no hover on the selection");
    run(app, "move #row-2\nframe");
    assertEquals(T.surface().hover(), find(app, "row-2").fill());
    assertEquals(0, find(app, "row-0").fill());

    run(app, "press 2,2\nframe"); // the panel around the tree: the focus leaves it
    assertNull(app.focused());
    assertEquals(T.grid().selectedInactive(), find(app, "row-1").fill());
    run(app, "press #row-1\nframe");
    assertEquals(List.of("select prod-eu"), catalog.log, "told only of changes");
  }

  @Test
  void aPressOnAMarkSelectsAndOpensAndAnotherCloses() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = start(catalog);
    run(app, "press #mark-0\nframe");
    assertEquals(List.of("select localhost", "open localhost"), catalog.log);
    assertEquals(6, find(app, "schema").rowCount());
    assertEquals(Tree.OPEN, mark(app, 0));
    assertEquals(Tree.CLOSED, mark(app, 1));
    assertTrue(dumpLine(app, "row-0").contains(" selected expanded level=1 pos=1/3"));
    assertTrue(dumpLine(app, "row-1").contains(" expanded=false level=2 pos=1/3"));
    assertEquals("staging", find(app, "row-5").label());

    run(app, "press #mark-0\nframe");
    assertEquals(List.of("select localhost", "open localhost", "close localhost"), catalog.log);
    assertEquals(3, find(app, "schema").rowCount());
    assertEquals(Tree.CLOSED, mark(app, 0));
    assertTrue(dumpLine(app, "row-0").contains(" selected expanded=false level=1 pos=1/3"));
  }

  private static Symbol mark(App<?> app, int row) {
    String shown = find(app, "mark-" + row).children().getFirst().text().string();
    for (Symbol symbol : List.of(Tree.OPEN, Tree.CLOSED)) {
      if (shown.equals(Character.toString(symbol.codePoint(T.size().icon())))) {
        return symbol;
      }
    }
    return null;
  }

  @Test
  void aDoublePressOpensOrClosesAndActivatesAndOnALeafOnlyActivates() throws IOException {
    Catalog catalog = new Catalog(T).opened("prod-eu", "prod-eu/billing", "prod-eu/billing/public");
    App<Catalog> app = start(catalog);
    run(app, "press role=treeitem label=\"billing\"\nframe");
    run(app, "press role=treeitem label=\"billing\" clicks 2\nframe");
    assertEquals(List.of("select prod-eu/billing", "close prod-eu/billing",
        "activate prod-eu/billing"), catalog.log);
    run(app, "press role=treeitem label=\"billing\" clicks 2\nframe"); // a double press alone
    run(app, "press role=treeitem label=\"invoices\" clicks 2\nframe");
    assertEquals(List.of("open prod-eu/billing", "activate prod-eu/billing",
        "select prod-eu/billing/public/invoices", "open prod-eu/billing/public/invoices",
        "activate prod-eu/billing/public/invoices"), catalog.log.subList(3, 8));

    catalog.log.clear();
    run(app, "press role=treeitem label=\"amount\"\nframe"); // a column of invoices
    run(app, "press role=treeitem label=\"amount\" clicks 2\nframe");
    run(app, "press role=treeitem label=\"amount\" clicks 3\nframe"); // a third press selects
    assertEquals(List.of("select prod-eu/billing/public/invoices/amount",
        "activate prod-eu/billing/public/invoices/amount"), catalog.log);
  }

  // The keys

  @Test
  void theArrowsWalkTheTreeOpeningAndClosingOnTheWay() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost");
    App<Catalog> app = start(catalog);
    run(app, "key Tab\nframe");
    assertEquals("schema", app.focused().key());

    assertEquals("localhost", key(app, catalog, "ArrowDown"), "the first row in view");
    assertEquals("localhost/analytics", key(app, catalog, "ArrowRight"), "its first child");
    assertEquals("localhost/analytics", key(app, catalog, "ArrowRight"), "opens it");
    assertTrue(catalog.open.contains(catalog.node("localhost/analytics")));
    assertEquals("localhost/analytics/public", key(app, catalog, "ArrowRight"));
    assertEquals("localhost/analytics", key(app, catalog, "ArrowLeft"), "its parent");
    assertEquals("localhost/analytics", key(app, catalog, "ArrowLeft"), "closes it");
    assertFalse(catalog.open.contains(catalog.node("localhost/analytics")));
    assertEquals("localhost/postgres", key(app, catalog, "ArrowDown"));
    assertEquals("localhost/shop", key(app, catalog, "ArrowDown"));
    assertEquals("prod-eu", key(app, catalog, "ArrowDown"));
    assertEquals("localhost/shop", key(app, catalog, "ArrowUp"));
    assertEquals("localhost", key(app, catalog, "ArrowLeft"), "the parent of a closed node");
    assertEquals("localhost", key(app, catalog, "ArrowLeft"), "closes it");
    assertEquals("localhost", key(app, catalog, "ArrowLeft"), "a closed root stays");
    assertEquals("staging", key(app, catalog, "End"));
    assertEquals("staging", key(app, catalog, "ArrowDown"), "stays at the end");
    assertEquals("localhost", key(app, catalog, "Home"));
    assertEquals("localhost", key(app, catalog, "ArrowUp"), "stays at the start");
    key(app, catalog, "Enter");
    assertEquals(List.of("select localhost", "select localhost/analytics",
        "open localhost/analytics", "select localhost/analytics/public",
        "select localhost/analytics", "close localhost/analytics", "select localhost/postgres",
        "select localhost/shop", "select prod-eu", "select localhost/shop", "select localhost",
        "close localhost", "select staging", "select localhost", "activate localhost"),
        catalog.log, "told only of changes");
  }

  @Test
  void aLeafNeitherOpensNorHasChildrenToGoTo() throws IOException {
    Catalog catalog = new Catalog(T).opened("prod-eu", "prod-eu/shop", "prod-eu/shop/public",
        "prod-eu/shop/public/orders");
    catalog.selected = catalog.node("prod-eu/shop/public/orders/id");
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nframe");
    assertEquals("prod-eu/shop/public/orders/id", key(app, catalog, "ArrowRight"));
    assertEquals("prod-eu/shop/public/orders", key(app, catalog, "ArrowLeft"));
    assertEquals(List.of("select prod-eu/shop/public/orders"), catalog.log);
  }

  @Test
  void pagesMoveByTheRowsInViewAndTheRowsFollowTheSelection() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost", "localhost/shop", "localhost/shop/public");
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nframe");
    key(app, catalog, "Home");
    // The tree is 284 high: 12 rows of 22 whole.
    assertEquals(12, row(app, catalog, "PageDown"));
    assertInView(app, 12);
    assertEquals(24, row(app, catalog, "PageDown"));
    assertInView(app, 24);
    assertEquals(307, row(app, catalog, "End"));
    assertInView(app, 307);
    assertEquals(295, row(app, catalog, "PageUp"));
    assertInView(app, 295);
    assertEquals(0, row(app, catalog, "Home"));
    assertInView(app, 0);
  }

  @Test
  void twoKeysInOneFrameGoOnFromWhereTheFirstLeftTheSelection() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost");
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nkey Home\nframe\nkey ArrowDown\nkey ArrowDown\nkey ArrowDown\nframe");
    assertEquals(catalog.node("localhost/shop"), catalog.selected);
    assertEquals(T.grid().selected(), find(app, "row-3").fill());
  }

  @Test
  void keysInOneFrameWalkIntoANodeThatAnEarlierOneOpened() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost");
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nkey Home\nkey ArrowDown\nframe\n" // localhost/analytics, closed
        + "key ArrowRight\nkey ArrowRight\nkey ArrowRight\nkey ArrowRight\nframe");
    assertEquals(catalog.node("localhost/analytics/public/events"), catalog.selected,
        "each Right goes on in the rows the one before it opened");
    assertEquals(List.of("select localhost", "select localhost/analytics",
        "open localhost/analytics", "select localhost/analytics/public",
        "open localhost/analytics/public", "select localhost/analytics/public/events"),
        catalog.log.subList(0, 6));
  }

  @Test
  void withoutASelectionAKeySelectsTheFirstRowInView() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost", "localhost/shop", "localhost/shop/public");
    catalog.selected = catalog.node("localhost/analytics/public"); // inside a closed node
    App<Catalog> app = start(catalog);
    assertTrue(find(app, "schema").children().stream().allMatch(r -> r.fill() == 0), "none shown");
    run(app, "focus #schema\nwheel #schema 0 2200\nframe"); // row 100 at the top
    for (String name : List.of("ArrowRight", "ArrowLeft", "Enter")) {
      key(app, catalog, name);
    }
    assertEquals(List.of(), catalog.log, "nothing to open, close or activate");
    assertEquals(100, row(app, catalog, "ArrowDown"));
    assertEquals(2200, find(app, "schema").scrollY(), "already in view");
  }

  @Test
  void theTreeIsOneStopForTabAndLeavesOtherKeysAlone() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = new App<>(catalog, c -> col().wFull().hFull().children(
        button(T, Variant.SECONDARY, "Before", () -> {}),
        c.tree().key("schema").grow(1),
        button(T, Variant.SECONDARY, "After", () -> {})));
    run(app, "frame\nkey Tab\nframe\nkey Tab\nframe");
    assertEquals("schema", app.focused().key());
    run(app, "key Tab\nframe");
    assertEquals("After", app.focused().label(), "not into the rows");
    run(app, "key Tab shift\nframe\nkey A\nframe\nkey ArrowDown shift\nframe\nkey Space\nframe");
    assertEquals("schema", app.focused().key());
    assertEquals(List.of(), catalog.log);
  }

  private static String key(App<Catalog> app, Catalog catalog, String name) {
    app.keyDown(new Key(name));
    app.keyUp(new Key(name));
    app.frame();
    return catalog.selected == null ? null : catalog.selected.path();
  }

  /** Presses the key and returns the row of the selected node. */
  private static int row(App<Catalog> app, Catalog catalog, String name) {
    key(app, catalog, name);
    List<String> said = app.tree().dump().lines().map(String::strip)
        .filter(l -> l.contains(" selected ")).toList();
    for (El row : find(app, "schema").children()) {
      if (row.fill() == T.grid().selected()) {
        assertEquals(List.of(dumpLine(app, row.key())), said, "the dump says which is selected");
        return Integer.parseInt(row.key().substring("row-".length()));
      }
    }
    return -1;
  }

  /** The row is whole inside the tree. */
  private static void assertInView(App<?> app, int row) {
    El tree = find(app, "schema");
    El el = find(app, "row-" + row);
    assertTrue(el != null && el.y() >= 0 && el.y() + el.height() <= tree.height(),
        "row " + row + " in view at " + tree.scrollY());
  }

  // Children that load late

  @Test
  void childrenNotKnownYetShowALoadingRowUntilTheyArrive() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = start(catalog);
    run(app, "press #mark-2\nframe");
    assertEquals(List.of("select staging", "open staging"), catalog.log);
    assertEquals(List.of(catalog.node("staging")), catalog.loading, "the app starts the load");
    El loading = find(app, "row-3");
    assertNull(loading.role());
    assertNull(loading.label());
    El text = loading.children().getLast();
    assertEquals("Loading…", text.text().string());
    assertEquals(T.text().muted(), text.text().color());
    assertEquals(T.space().s() + T.space().xl(), loading.children().getFirst().x(), "level 1");

    run(app, "press #row-3\nframe\nfocus #schema\nkey ArrowDown\nframe\nkey End\nframe");
    assertEquals(List.of("select staging", "open staging"), catalog.log, "not a node");

    run(app, "frame\nframe");
    assertEquals(4, find(app, "schema").rowCount(), "a script lets the answer in at await only");
    assertEquals(List.of(catalog.node("staging")), catalog.loading);

    run(app, "await\nframe");
    assertEquals(5, find(app, "schema").rowCount());
    assertEquals(List.of("localhost", "prod-eu", "staging", "scratch", "shop"),
        find(app, "schema").children().stream().map(El::label).toList());
    assertEquals(T.space().s() + T.space().xl(), find(app, "row-3").children().getFirst().x());
    assertEquals("staging/shop", key(app, catalog, "End"));
  }

  // Without handlers

  @Test
  void withoutHandlersTheTreeOnlyShows() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost");
    App<Catalog> app = new App<>(catalog, c -> col().wFull().hFull().child(
        Tree.tree(T, c, c.open::contains, c.node("localhost/shop"), null, null, null)
            .key("schema").grow(1)));
    run(app, "frame\nmove #row-1\npress #row-1\npress #mark-0\nkey Tab\nframe");
    assertNull(app.focused());
    assertEquals(0, find(app, "row-1").fill(), "no hover");
    assertEquals(T.grid().selectedInactive(), find(app, "row-3").fill(), "the selection shows");
    assertTrue(dumpLine(app, "row-3").contains(" selected "));
    assertEquals(6, find(app, "schema").rowCount());
  }

  // Images

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Catalog catalog = new Catalog(name.equals("dark") ? Theme.DARK : Theme.LIGHT);
    Path folder = Path.of("target/tree", name);
    String script = Files.readString(Path.of("src/test/resources/scripts/tree.txt"));
    List<Path> files = Script.run(script, catalog.app(), folder);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches(
          "tree-" + name + "-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))),
          Files.readString(files.get(i + 1)));
    }
    assertEquals(12, files.size());
    assertEquals(
        List.of("select localhost", "open localhost", "select localhost/shop",
            "open localhost/shop", "activate localhost/shop", "select localhost/shop/public",
            "open localhost/shop/public", "activate localhost/shop/public",
            "select localhost/shop/public/accounts", "open localhost/shop/public/accounts",
            "activate localhost/shop/public/accounts",
            "select localhost/shop/public/accounts/id",
            "select localhost/shop/public/accounts/created_at", "select localhost",
            "close localhost", "select staging", "open staging"),
        catalog.log);
  }

  static El find(App<?> app, String key) {
    return find(app.tree(), key);
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
