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

  @TempDir
  Path out;

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
    assertTrue(
        tree.scrollY() + tree.height() >= shown - 0.5f,
        "its columns below it, as far as there is room: " + tree.scrollY());
    assertTrue(tree.scrollY() <= row * h + 0.5f, "and the table itself");
  }

  /** The index of the selected row, from the dump. */
  private static int selectedRow(App<?> app) {
    Matcher m = Pattern.compile("#row-(\\d+) role=treeitem [^\\n]* selected")
        .matcher(app.tree().dump());
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
    catalog.detailed.clear();
    catalog.mutedAsked.clear();
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
    Matcher line = Pattern.compile(
            "el #schema role=tree label=\"Schema\" .* rows (\\d+)-(\\d+)/(\\d+)")
        .matcher(app.tree().dump());
    assertTrue(line.find(), app.tree().dump());
    assertEquals(
        List.of(first, last, SHOWN),
        List.of(
            Integer.parseInt(line.group(1)),
            Integer.parseInt(line.group(2)),
            Integer.parseInt(line.group(3))));
    assertEquals(last - first + 1, app.tree().dump().split("role=treeitem", -1).length - 1);
  }

  /** The labels and details of the rows that exist were asked for once each, and no others. */
  private static void assertLabelledOnce(Catalog catalog, App<Catalog> app) {
    List<String> rows = find(app, "schema").children().stream().map(El::label).toList();
    assertEquals(rows, catalog.labelled.stream().map(n -> n.name).toList());
    assertEquals(rows, catalog.detailed.stream().map(n -> n.name).toList());
    assertEquals(rows, catalog.mutedAsked.stream().map(n -> n.name).toList());
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
    assertEquals(20_000 / 24 - El.OVERSCAN, find(app, "schema").firstRow());
  }

  @Test
  void aRebuildWithAHundredThousandNodesStaysWellUnderAFrame() throws IOException {
    long[] small = rebuilds(
        new Catalog(T).opened("localhost", "localhost/shop", "localhost/shop/public")); // 308 nodes
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
    // again for each, and the rows are built once, at the end. Each key after the first asks the
    // row functions whether a row has an onAnyKeyDown handler, without laying them out.
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
    List<El> rows = find(app, "schema").children();
    assertTrue(rows.stream()
        .allMatch(r -> catalog.labelled.stream().anyMatch(n -> n.name.equals(r.label()))));
    assertTrue(catalog.labelled.size() <= 10 * rows.size(), catalog.labelled.size() + " labels");
    long one = keyFrames(hundredThousand(), 1);
    long ten = keyFrames(hundredThousand(), 10);
    System.out.printf(
        "Tree: frame() after keys, median of %d, %d nodes shown: %.2f ms after one"
            + " ArrowDown, %.2f ms after ten%n",
        REBUILDS, SHOWN, one / 1e6, ten / 1e6);
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
  void aSecondaryPressSelectsTheRowBeforeTheTreesContextMenuOpens() throws IOException {
    Catalog catalog = new Catalog(T);
    Menu.State menu = new Menu.State();
    List<String> chosen = new ArrayList<>();
    App<Catalog> app = new App<>(
        catalog,
        c -> col()
            .wFull()
            .hFull()
            .child(Menu.contextMenu(
                T,
                c.tree().key("schema").grow(1),
                menu,
                List.of(Menu.item("Copy name", () -> chosen.add("copy " + c.selected))))));
    catalog.app = app;
    run(app, "frame\npress #row-1 right\nframe");
    assertEquals(List.of("select prod-eu"), catalog.log, "the row under the pointer first");
    assertTrue(menu.isOpen());
    run(app, "advance 0.25\nframe\npress role=menuitem label=\"Copy name\"\nframe");
    assertEquals(List.of("copy prod-eu"), chosen, "the menu acts on that row");

    run(app, "press #row-1 right\nframe"); // the selected row: no second selection
    assertEquals(List.of("select prod-eu"), catalog.log);
    assertTrue(menu.isOpen());
  }

  /** Shift+F10 opens the tree's menu at the selected row, beside a menu bar that F10 opens. */
  @Test
  void shiftF10OpensTheMenuAtTheSelectedRowAndF10TheMenuBar() throws IOException {
    Catalog catalog = new Catalog(T);
    Menu.State menu = new Menu.State();
    MenuBar.State bar = new MenuBar.State();
    List<Menu.Item> menus = List.of(Menu.submenu("&File", List.of(Menu.item("Quit", () -> {}))));
    App<Catalog> app = new App<>(
        catalog,
        c -> col()
            .wFull()
            .hFull()
            .onKey(MenuBar.keys(bar, menus))
            .child(MenuBar.menuBar(T, bar, menus))
            .child(Menu.contextMenu(
                T,
                c.tree().key("schema").grow(1),
                menu,
                List.of(Menu.item("Copy name", () -> {})))));
    catalog.app = app;
    run(app, "frame\npress #row-1\nframe\nkey F10 shift\nframe");
    assertTrue(menu.isOpen(), "the tree's menu");
    assertEquals(-1, bar.title(), "not the bar");
    // The row's rectangle in the window, and the menu at its bottom left.
    Matcher rect = Pattern.compile(" (\\d+),(\\d+) (\\d+)x(\\d+)").matcher(dumpLine(app, "row-1"));
    assertTrue(rect.find());
    int bottom = Integer.parseInt(rect.group(2)) + Integer.parseInt(rect.group(4));
    String at = "overlay at " + rect.group(1) + "," + bottom + " ";
    assertTrue(
        app.tree().dump().lines().anyMatch(l -> l.startsWith(at)), app.tree().dump());
    run(app, "key Escape\nframe\nkey F10\nframe");
    assertEquals(0, bar.title(), "F10 alone gives the bar the keys");
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
    assertEquals(
        List.of("select prod-eu/billing", "close prod-eu/billing", "activate prod-eu/billing"),
        catalog.log);
    run(app, "press role=treeitem label=\"billing\" clicks 2\nframe"); // a double press alone
    run(app, "press role=treeitem label=\"invoices\" clicks 2\nframe");
    assertEquals(
        List.of(
            "open prod-eu/billing",
            "activate prod-eu/billing",
            "select prod-eu/billing/public/invoices",
            "open prod-eu/billing/public/invoices",
            "activate prod-eu/billing/public/invoices"),
        catalog.log.subList(3, 8));

    catalog.log.clear();
    run(app, "press role=treeitem label=\"amount\"\nframe"); // a column of invoices
    run(app, "press role=treeitem label=\"amount\" clicks 2\nframe");
    run(app, "press role=treeitem label=\"amount\" clicks 3\nframe"); // a third press selects
    assertEquals(
        List.of(
            "select prod-eu/billing/public/invoices/amount",
            "activate prod-eu/billing/public/invoices/amount"),
        catalog.log);
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
    assertEquals(
        List.of(
            "select localhost",
            "select localhost/analytics",
            "open localhost/analytics",
            "select localhost/analytics/public",
            "select localhost/analytics",
            "close localhost/analytics",
            "select localhost/postgres",
            "select localhost/shop",
            "select prod-eu",
            "select localhost/shop",
            "select localhost",
            "close localhost",
            "select staging",
            "select localhost",
            "activate localhost"),
        catalog.log,
        "told only of changes");
  }

  @Test
  void aLeafNeitherOpensNorHasChildrenToGoTo() throws IOException {
    Catalog catalog = new Catalog(T)
        .opened("prod-eu", "prod-eu/shop", "prod-eu/shop/public", "prod-eu/shop/public/orders");
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
    // The tree is 284 high: 11 rows of 24 whole.
    assertEquals(11, row(app, catalog, "PageDown"));
    assertInView(app, 11);
    assertEquals(22, row(app, catalog, "PageDown"));
    assertInView(app, 22);
    assertEquals(307, row(app, catalog, "End"));
    assertInView(app, 307);
    assertEquals(296, row(app, catalog, "PageUp"));
    assertInView(app, 296);
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
    run(
        app,
        "focus #schema\nkey Home\nkey ArrowDown\nframe\n" // localhost/analytics, closed
            + "key ArrowRight\nkey ArrowRight\nkey ArrowRight\nkey ArrowRight\nframe");
    assertEquals(
        catalog.node("localhost/analytics/public/events"),
        catalog.selected,
        "each Right goes on in the rows the one before it opened");
    assertEquals(
        List.of(
            "select localhost",
            "select localhost/analytics",
            "open localhost/analytics",
            "select localhost/analytics/public",
            "open localhost/analytics/public",
            "select localhost/analytics/public/events"),
        catalog.log.subList(0, 6));
  }

  @Test
  void withoutASelectionAKeySelectsTheFirstRowInView() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost", "localhost/shop", "localhost/shop/public");
    catalog.selected = catalog.node("localhost/analytics/public"); // inside a closed node
    App<Catalog> app = start(catalog);
    assertTrue(find(app, "schema").children().stream().allMatch(r -> r.fill() == 0), "none shown");
    run(app, "focus #schema\nwheel #schema 0 2400\nframe"); // row 100 at the top
    for (String name : List.of("ArrowRight", "ArrowLeft", "Enter")) {
      key(app, catalog, name);
    }
    assertEquals(List.of(), catalog.log, "nothing to open, close or activate");
    assertEquals(100, row(app, catalog, "ArrowDown"));
    assertEquals(2400, find(app, "schema").scrollY(), "already in view");
  }

  @Test
  void theTreeIsOneStopForTabAndLeavesOtherKeysAlone() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = new App<>(
        catalog,
        c -> col()
            .wFull()
            .hFull()
            .children(
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
    List<String> said = app.tree()
        .dump()
        .lines()
        .map(String::strip)
        .filter(l -> l.contains(" selected "))
        .toList();
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
    assertTrue(
        el != null && el.y() >= 0 && el.y() + el.height() <= tree.height(),
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
    assertEquals(
        List.of("localhost", "prod-eu", "staging", "scratch", "shop"),
        find(app, "schema").children().stream().map(El::label).toList());
    assertEquals(
        T.space().s() + T.space().xl(), find(app, "row-3").children().getFirst().x());
    assertEquals("staging/shop", key(app, catalog, "End"));
  }

  // Without handlers

  @Test
  void withoutHandlersTheTreeOnlyShows() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost");
    App<Catalog> app = new App<>(
        catalog,
        c -> col()
            .wFull()
            .hFull()
            .child(Tree.tree(T, c, c.open::contains, c.node("localhost/shop"), null, null, null)
                .key("schema")
                .grow(1)));
    run(app, "frame\nmove #row-1\npress #row-1\npress #mark-0\nkey Tab\nframe");
    assertNull(app.focused());
    assertEquals(0, find(app, "row-1").fill(), "no hover");
    assertEquals(T.grid().selectedInactive(), find(app, "row-3").fill(), "the selection shows");
    assertTrue(dumpLine(app, "row-3").contains(" selected "));
    assertEquals(6, find(app, "schema").rowCount());
  }

  // Details

  @Test
  void aDetailFollowsTheLabelAndGivesWayBeforeIt() throws IOException {
    Catalog catalog = new Catalog(T)
        .opened(
            "localhost",
            "localhost/shop",
            "localhost/shop/public",
            "localhost/shop/public/accounts");
    App<Catalog> app = catalog.app();
    run(app, "size 280 300\nframe");
    String dump = app.tree().dump();
    assertTrue(
        dump.contains("role=treeitem label=\"public\" ") && dump.contains("value=\"300 tables\""),
        dump);
    String id = line(dump, "label=\"id\"");
    assertTrue(id.endsWith("level=5 pos=1/3 value=\"bigint\""), id);
    El type = find(app, "row-" + row(dump, "label=\"id\""))
        .children()
        .getLast()
        .children()
        .getLast();
    assertEquals("bigint", type.text().string());
    assertEquals(T.text().muted(), type.text().color());
    assertFalse(type.text().shortened());
    assertTrue(type.y() < T.grid().rowHeight(), "beside the name");

    // Narrower: the longest type shortens, then goes; the names stay whole.
    for (int width = 280; width >= 150; width -= 2) {
      run(app, "size " + width + " 300\nframe");
      El row = find(app, "row-" + row(app.tree().dump(), "label=\"created_at\""));
      El box = row.children().getLast();
      El name = box.children().get(1);
      El detail = box.children().get(2);
      boolean gone = detail.y() >= T.grid().rowHeight();
      if (!gone && detail.text().shortened()) {
        assertFalse(name.text().shortened(), "the name whole while the type shows: " + width);
        assertTrue(detail.width() >= 8, "at least a character and the ellipsis: " + width);
      }
      if (name.text().shortened()) {
        assertTrue(gone, "the type gone before the name is cut: " + width);
      }
    }
    run(app, "size 150 300\nframe");
    El row = find(app, "row-" + row(app.tree().dump(), "label=\"created_at\""));
    assertTrue(row.children().getLast().children().get(2).y() >= T.grid().rowHeight(), "gone");

    // Where a part is cut or gone, a tooltip after the delay names both.
    run(app, "size 150 300\nmove role=treeitem label=\"created_at\"\nframe\nadvance 1\nframe");
    assertTrue(
        app.tree().dump().contains("role=tooltip label=\"created_at timestamptz\""),
        app.tree().dump());
    run(
        app,
        "size 280 300\nleave\nframe\nmove role=treeitem label=\"id\"\nframe\nadvance 1\nframe");
    assertFalse(app.tree().dump().contains("role=tooltip"), "nothing cut, no tooltip");
  }

  private static String line(String dump, String part) {
    return dump.lines().filter(l -> l.contains(part)).findFirst().orElseThrow().strip();
  }

  /** The index of the row whose line holds {@code part}. */
  private static int row(String dump, String part) {
    Matcher m = Pattern.compile("#row-(\\d+) ").matcher(line(dump, part));
    assertTrue(m.find());
    return Integer.parseInt(m.group(1));
  }

  // Long labels

  private static final String LONG = "a_name_far_too_long_for_a_narrow_tree_of_a_database_tool";

  /**
   * Long labels at levels 1 and 5, with and without a detail, and a loading row of a long text.
   * Nodes are paths; the leaves {@code …plain} and {@code …detailed} have the long label, the
   * second a detail too.
   */
  private static final class LongNames implements Tree.Model<String> {
    final java.util.Set<String> open = new java.util.HashSet<>(
        List.of("deep", "deep/two", "deep/two/three", "deep/two/three/four", "waiting"));

    @Override
    public List<String> roots() {
      return List.of("plain", "detailed", "deep", "waiting");
    }

    @Override
    public List<String> children(String node) {
      return switch (node) {
        case "deep" -> List.of("deep/two");
        case "deep/two" -> List.of("deep/two/three");
        case "deep/two/three" -> List.of("deep/two/three/four");
        case "deep/two/three/four" ->
          List.of("deep/two/three/four/plain", "deep/two/three/four/detailed");
        default -> null;
      };
    }

    @Override
    public boolean isLeaf(String node) {
      return node.endsWith("plain") || node.endsWith("detailed");
    }

    @Override
    public String label(String node) {
      return node.endsWith("plain") || node.endsWith("detailed") ? LONG : node;
    }

    @Override
    public String detail(String node) {
      return node.endsWith("detailed") ? "varchar(320)" : null;
    }

    @Override
    public String loading(String node) {
      return "Loading the databases of a server far away, which takes a while";
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {150, 200, 320})
  void aLongLabelEndsInAnEllipsisAndShowsWholeInATooltip(int width) throws IOException {
    LongNames model = new LongNames();
    App<LongNames> app = new App<>(
        model,
        m -> col()
            .wFull()
            .hFull()
            .child(Tree.tree(T, m, m.open::contains, null, s -> {}, null, null)
                .key("tree")
                .grow(1)));
    run(app, "size " + width + " 300\nframe");
    // Rows 0 and 1 at level 1, 6 and 7 at level 5, 9 the loading row below waiting.
    for (int row : new int[] {0, 1, 6, 7, 9}) {
      El el = find(app, "row-" + row);
      String text = row == 9 ? model.loading("waiting") : LONG;
      El name = textOf(el, text);
      assertTrue(name.text().shortened(), width + ": row " + row);
      String tip = row == 1 || row == 7 ? LONG + " varchar(320)" : text;
      run(app, "leave\nframe\nmove #row-" + row + "\nframe\nadvance 1\nframe");
      assertTrue(
          app.tree().dump().contains("role=tooltip label=\"" + tip + "\""),
          width + ": row " + row + "\n" + app.tree().dump());
    }
    assertTrue(dumpLine(app, "row-6").contains(" level=5 "), dumpLine(app, "row-6"));
    // A short label is not cut and has no tooltip.
    run(app, "leave\nframe\nmove #row-2\nframe\nadvance 1\nframe");
    assertFalse(textOf(find(app, "row-2"), "deep").text().shortened());
    assertFalse(app.tree().dump().contains("role=tooltip"), app.tree().dump());
  }

  /** The text element inside {@code el} whose string is {@code string}. */
  private static El textOf(El el, String string) {
    if (el.text() != null && string.equals(el.text().string())) {
      return el;
    }
    for (El child : el.children()) {
      El found = textOf(child, string);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  // Muted rows

  @Test
  void aMutedRowHasItsLabelInMutedTextAndNothingElseChanges() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost", "localhost/postgres");
    catalog.open.add(catalog.node("localhost/postgres/pg_catalog"));
    App<Catalog> app = start(catalog);
    String dump = app.tree().dump();
    assertEquals(
        "el #row-3 role=treeitem label=\"pg_catalog\" 8,80 264x24 expanded level=3 pos=1/2"
            + " value=\"2 tables\"",
        dumpLine(app, "row-3"));
    assertEquals(
        T.text().muted(), textOf(find(app, "row-3"), "pg_catalog").text().color());
    assertEquals(T.text().muted(), textOf(find(app, "row-4"), "pg_class").text().color());
    assertEquals(T.text().primary(), textOf(find(app, "row-6"), "public").text().color());
    assertEquals(
        T.text().primary(), textOf(find(app, "row-2"), "postgres").text().color());
    assertEquals(T.text().muted(), textOf(find(app, "row-3"), "2 tables").text().color());
    assertTrue(dump.contains("label=\"pg_class\" "), dump);

    run(app, "press #row-3\nframe");
    assertEquals("localhost/postgres/pg_catalog", catalog.selected.path(), "works as any row");
    assertEquals(
        T.text().muted(), textOf(find(app, "row-3"), "pg_catalog").text().color());
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
    assertEquals(14, files.size());
    assertEquals(
        List.of(
            "select localhost",
            "open localhost",
            "select localhost/shop",
            "open localhost/shop",
            "activate localhost/shop",
            "select localhost/shop/public",
            "open localhost/shop/public",
            "activate localhost/shop/public",
            "select localhost/shop/public/accounts",
            "open localhost/shop/public/accounts",
            "activate localhost/shop/public/accounts",
            "select localhost/shop/public/accounts/id",
            "select localhost/shop/public/accounts/created_at",
            "select localhost",
            "close localhost",
            "select staging",
            "open staging",
            "select localhost",
            "open localhost"),
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

  /**
   * A tree of one parent beside a leaf, whose children the test sets, or of two parents with one
   * shared child: the smallest model for the order of the app's changes and the tree's input.
   */
  private static class Family implements Tree.Model<String> {
    final java.util.Set<String> open = new java.util.HashSet<>();
    final List<String> activated = new ArrayList<>();
    List<String> children; // the parent's, null while not known
    String selected;

    @Override
    public List<String> roots() {
      return List.of("parent", "other");
    }

    @Override
    public List<String> children(String node) {
      return children;
    }

    @Override
    public boolean isLeaf(String node) {
      return !node.equals("parent");
    }

    @Override
    public String label(String node) {
      return node;
    }

    El view() {
      return Tree.tree(
              T,
              this,
              open::contains,
              selected,
              s -> selected = s,
              (node, on) -> {
                if (on) {
                  open.add(node);
                } else {
                  open.remove(node);
                }
              },
              activated::add)
          .key("tree")
          .wFull()
          .hFull();
    }
  }

  @Test
  void childrenThatArriveAfterTheirNodeClosedStayHidden() throws IOException {
    Family family = new Family();
    App<Family> app = new App<>(family, Family::view);
    run(app, "frame\nfocus #tree\nkey Home\nkey ArrowRight\nframe\nkey ArrowLeft\nframe");
    assertFalse(family.open.contains("parent"));
    app.post(() -> family.children = List.of("late child"));
    run(app, "await\nframe");
    assertFalse(app.tree().dump().contains("late child"));
    assertEquals("parent", family.selected);
  }

  @Test
  void aSelectionThatClosingItsParentHidIsNeitherShownNorActivated() throws IOException {
    Family family = new Family();
    family.children = List.of("child");
    family.open.add("parent");
    family.selected = "child";
    App<Family> app = new App<>(family, Family::view);
    run(app, "frame\nfocus #tree\nframe");
    app.post(family.open::clear);
    run(app, "await\nframe\nkey Enter\nframe");
    assertEquals(List.of(), family.activated);
    assertFalse(app.tree().dump().contains(" selected"));
  }

  @Test
  void aNodeUnderTwoParentsStaysReachableWhenTheFirstCloses() throws IOException {
    Family family = new Family() {
      @Override
      public List<String> roots() {
        return List.of("a", "b");
      }

      @Override
      public List<String> children(String node) {
        return List.of("shared");
      }

      @Override
      public boolean isLeaf(String node) {
        return node.equals("shared");
      }
    };
    family.open.addAll(List.of("a", "b"));
    family.selected = "shared";
    App<Family> app = new App<>(family, Family::view);
    run(app, "frame\nfocus #tree\nframe");
    assertEquals(
        1, app.tree().dump().lines().filter(l -> l.contains(" selected")).count());
    app.post(() -> family.open.remove("a"));
    run(app, "await\nframe\nkey ArrowLeft\nframe");
    assertEquals("b", family.selected, "Left goes to the parent it is shown under");
  }

  @Test
  void aKeyAfterAPostedChangeOfTheModelInOneFrameSeesTheChange() throws IOException {
    Family family = new Family();
    family.children = List.of("old child");
    family.open.add("parent");
    App<Family> app = new App<>(family, Family::view);
    run(app, "frame\nfocus #tree\nkey Home\nframe");
    app.post(() -> family.children = List.of("replacement"));
    run(app, "await\nkey ArrowRight\nkey Enter\nframe");
    assertEquals(List.of("replacement"), family.activated);
  }
}
