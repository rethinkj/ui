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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tree with the catalog of three database servers ({@link Catalog}): the rows it builds,
 * the pointer, the keys, children that load late, and stored images.
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
    // prod-eu, the third row from the end of the list
    run(app, "focus #schema\nkey End\nkey ArrowUp\nkey ArrowUp\nframe");
    Node table = catalog.selected;
    int row = selectedRow(app);
    float h = T.grid().rowHeight();
    El tree = Workbench.find(app, "schema");
    assertTrue(tree.scrollY() + tree.height() < (row + 3) * h + 1, "near the bottom of the view");

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

  // Rows

  /**
   * localhost/shop/public with 1000 tables of 99 columns, all open: 100 009 nodes are shown, the
   * four servers, localhost's three databases and shop's two schemas included.
   */
  private static Catalog hundredThousand() {
    Catalog catalog = new Catalog(T, 1000, 99).opened("localhost", "localhost/shop");
    Node schema = catalog.node("localhost/shop/public");
    catalog.open.add(schema);
    catalog.open.addAll(schema.children);
    return catalog;
  }

  private static final int SHOWN = 100_009;

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

  // The pointer

  @Test
  void aPressSelectsARowAndFocusesTheTreeWithoutARing() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = start(catalog);
    run(app, "press #row-1\nframe");
    assertEquals(List.of("select prod-eu"), catalog.log);
    assertEquals("schema", app.focused().key());
    assertEquals(T.grid().selected(), find(app, "row-1").fill(), "the focused tree's selection");
    assertTrue(dumpLine(app, "row-1").contains(" selected expanded=false level=1 pos=2/4"));

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

  @Test
  void aPressOnAMarkSelectsAndOpensAndAnotherCloses() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = start(catalog);
    run(app, "press #mark-0\nframe");
    assertEquals(List.of("select localhost", "open localhost"), catalog.log);
    assertEquals(7, find(app, "schema").rowCount());
    assertEquals(Tree.OPEN, mark(app, 0));
    assertEquals(Tree.CLOSED, mark(app, 1));
    assertTrue(dumpLine(app, "row-0").contains(" selected expanded level=1 pos=1/4"));
    assertTrue(dumpLine(app, "row-1").contains(" expanded=false level=2 pos=1/3"));
    assertEquals("staging", find(app, "row-5").label());

    run(app, "press #mark-0\nframe");
    assertEquals(List.of("select localhost", "open localhost", "close localhost"), catalog.log);
    assertEquals(4, find(app, "schema").rowCount());
    assertEquals(Tree.CLOSED, mark(app, 0));
    assertTrue(dumpLine(app, "row-0").contains(" selected expanded=false level=1 pos=1/4"));
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
    assertEquals("backup", key(app, catalog, "End"));
    assertEquals("backup", key(app, catalog, "ArrowDown"), "stays at the end");
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
            "select backup",
            "select localhost",
            "activate localhost"),
        catalog.log,
        "told only of changes");
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
    assertEquals(308, row(app, catalog, "End"));
    assertInView(app, 308);
    assertEquals(297, row(app, catalog, "PageUp"));
    assertInView(app, 297);
    assertEquals(0, row(app, catalog, "Home"));
    assertInView(app, 0);
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

    run(app, "press #row-3\nframe\nfocus #schema\nkey ArrowDown\nframe");
    assertEquals(List.of("select staging", "open staging", "select backup"), catalog.log);
    run(app, "key ArrowUp\nframe");
    assertEquals("staging", catalog.selected.path(), "passing the loading row, not a node");

    run(app, "frame\nframe");
    assertEquals(5, find(app, "schema").rowCount(), "a script lets the answer in at await only");
    assertEquals(List.of(catalog.node("staging")), catalog.loading);

    run(app, "await\nframe");
    assertEquals(6, find(app, "schema").rowCount());
    assertEquals(
        List.of("localhost", "prod-eu", "staging", "scratch", "shop", "backup"),
        find(app, "schema").children().stream().map(El::label).toList());
    assertEquals(
        T.space().s() + T.space().xl(), find(app, "row-3").children().getFirst().x());
    assertEquals("backup", key(app, catalog, "End"));
    assertEquals("staging/shop", key(app, catalog, "ArrowUp"));
  }

  // Children that could not be read

  /** Opens backup, whose first connection is refused, and lets the refusal in. */
  private App<Catalog> refused(Catalog catalog) throws IOException {
    App<Catalog> app = start(catalog);
    run(app, "press #mark-3\nframe\nawait\nframe");
    assertEquals(
        List.of("select backup", "open backup", "failed backup: connection refused"), catalog.log);
    return app;
  }

  private static final String REFUSED = "Cannot read the databases: connection refused";

  @Test
  void aFailureShowsInPlaceOfTheLoadingRowWithItsAction() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = refused(catalog);
    assertEquals(
        "el #row-4 role=treeitem label=\"" + REFUSED + "\" 8,104 264x24 invalid level=2 pos=1/1"
            + " error=\"" + REFUSED + "\"",
        dumpLine(app, "row-4"));
    assertTrue(app.tree().dump().contains(" role=button label=\"Try again\" "));
    El row = find(app, "row-4");
    assertEquals(T.space().s() + T.space().xl(), row.children().getFirst().x(), "level 2");
    assertEquals(T.status().danger(), row.children().get(1).text().color(), "the icon");
    El message = textOf(row, REFUSED);
    assertEquals(T.text().primary(), message.text().color());
    El action = row.children().getLast();
    assertEquals(T.prose().link(), action.children().getFirst().text().color());
    assertTrue(action.height() <= row.height(), "inside the row");
    float bar = T.scrollbars().thickness() + 2 * T.scrollbars().margin();
    assertEquals(
        row.width() - bar, action.x() + action.width(), 0.01, "at its end, clear of the bar");

    // Not a node: the keys pass it, and a press beside the action selects nothing.
    run(app, "press role=treeitem invalid\nframe\nkey ArrowDown\nframe\nkey End\nframe");
    assertEquals(3, catalog.log.size(), catalog.log.toString());
    assertEquals("schema", app.focused().key());
  }

  @Test
  void aPressOnTheActionTriesAgainAndTheChildrenArrive() throws IOException {
    Catalog catalog = new Catalog(T);
    App<Catalog> app = refused(catalog);
    run(app, "press role=button label=\"Try again\"\nframe");
    assertEquals("retry backup", catalog.log.getLast());
    assertEquals("schema", app.focused().key(), "the press leaves the focus in the tree");
    assertNull(find(app, "row-4").role(), "loading again");
    assertEquals("Loading…", find(app, "row-4").children().getLast().text().string());

    run(app, "await\nframe");
    assertEquals(
        List.of("localhost", "prod-eu", "staging", "backup", "scratch", "shop"),
        find(app, "schema").children().stream().map(El::label).toList());
    assertFalse(app.tree().dump().contains(" invalid"));
  }

  @Test
  void tabReachesTheActionAndEnterOrSpaceRunIt() throws IOException {
    for (String key : List.of("Enter", "Space")) {
      Catalog catalog = new Catalog(T);
      App<Catalog> app = refused(catalog);
      run(app, "key Tab\nframe");
      assertEquals("Try again", app.focused().label(), "after the tree, its one stop");
      assertTrue(app.tree().dump().contains(" role=button label=\"Try again\" "), "x");
      run(app, "key " + key + "\nframe");
      assertEquals(
          List.of(
              "select backup", "open backup", "failed backup: connection refused", "retry backup"),
          catalog.log,
          key + " runs the action, not the tree's activation of the selected node");
      assertEquals("schema", app.focused().key(), key + ": the focus back to the tree");
      run(app, "await\nframe");
      assertEquals(6, find(app, "schema").rowCount());
    }
  }

  // Muted rows

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

  @Test
  void aLongLabelEndsInAnEllipsisAndShowsWholeInATooltip() throws IOException {
    int width = 150;
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
    assertEquals(7, find(app, "schema").rowCount());
  }

  // Images

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Catalog catalog = new Catalog(name.equals("dark") ? Theme.DARK : Theme.LIGHT);
    Path folder = Path.of("target/tree", name);
    String script = Files.readString(Path.of("src/test/resources/scripts/tree.txt"));
    List<Path> files = Script.run(script, catalog.app(), folder);
    Golden.assertSnaps("tree-" + name + "-", files);
    assertEquals(10, files.size());
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
            "select backup",
            "select staging",
            "open staging",
            "select localhost",
            "open localhost",
            "select backup",
            "open backup",
            "failed backup: connection refused",
            "retry backup",
            "select localhost",
            "select localhost/analytics",
            "select localhost/postgres",
            "open localhost/postgres",
            "select localhost/postgres/pg_catalog",
            "open localhost/postgres/pg_catalog",
            "close localhost/postgres/pg_catalog",
            "select localhost/postgres",
            "close localhost/postgres",
            "select localhost/shop",
            "select localhost/shop/audit",
            "open localhost/shop/audit"),
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
}
