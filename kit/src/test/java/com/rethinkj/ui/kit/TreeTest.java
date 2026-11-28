package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.kit.Catalog.Node;
import com.rethinkj.ui.text.TextRendering;
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
    assertLabeledOnce(catalog, app);

    catalog.labeled.clear();
    catalog.detailed.clear();
    catalog.mutedAsked.clear();
    run(app, "scroll #schema row 76543\nframe");
    assertRowsInDump(app, 76_543 - El.OVERSCAN);
    assertLabeledOnce(catalog, app);
    // Six rows above the tables, a hundred for each: row 76543 is column 36 of table 765.
    El row = find(app, "row-76543");
    assertEquals("treeitem", row.role());
    assertEquals("customer_id_3", row.label());
    assertEquals(T.space().s() + 4 * T.space().l(), row.children().get(1).x(), "level 4");
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
  private static void assertLabeledOnce(Catalog catalog, App<Catalog> app) {
    List<String> rows = find(app, "schema").children().stream().map(El::label).toList();
    assertEquals(rows, catalog.labeled.stream().map(n -> n.name).toList());
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
    assertEquals(
        T.text().muted(), find(app, "mark-2").children().getFirst().text().color());
    run(app, "move #mark-2\nframe");
    assertEquals(T.surface().hover(), find(app, "row-2").fill(), "the row's hover too");
    assertEquals(
        T.text().primary(),
        find(app, "mark-2").children().getFirst().text().color(),
        "the mark under the pointer brightens");

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

    catalog.toggles = node -> node.kind != Catalog.Kind.TABLE;
    catalog.log.clear();
    run(app, "frame\npress role=treeitem label=\"customers\" clicks 2\nframe");
    assertEquals(
        List.of(
            "select prod-eu/billing/public/customers", "activate prod-eu/billing/public/customers"),
        catalog.log,
        "a table that does not toggle on a double press only activates");

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

  @Test
  void rowsKeyedByTheModelKeepTheirKeysWhenNodesAboveThemOpen() throws IOException {
    Catalog catalog = new Catalog(T);
    catalog.keyed = true;
    App<Catalog> app = start(catalog);
    run(app, "press #mark-localhost\nframe");
    assertTrue(
        dumpLine(app, "row-prod-eu").contains(" level=1 pos=2/4"), dumpLine(app, "row-prod-eu"));
    run(app, "press #mark-prod-eu\nframe");
    assertEquals(
        List.of("select localhost", "open localhost", "select prod-eu", "open prod-eu"),
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
    // ArrowDown reaches localhost/analytics, closed, before the four ArrowRight.
    run(app, """
        focus #schema
        key Home
        key ArrowDown
        frame
        key ArrowRight
        key ArrowRight
        key ArrowRight
        key ArrowRight
        frame""");
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
                button(Variant.SECONDARY, "Before", () -> {}),
                c.tree().key("schema").grow(1),
                button(Variant.SECONDARY, "After", () -> {})));
    run(app, "frame\nkey Tab\nframe\nkey Tab\nframe");
    assertEquals("schema", app.focused().key());
    run(app, "key Tab\nframe");
    assertEquals("After", app.focused().label(), "not into the rows");
    run(app, "key Tab shift\nframe\nkey A\nframe\nkey ArrowDown shift\nframe\nkey Space\nframe");
    assertEquals("schema", app.focused().key());
    assertEquals(List.of(), catalog.log);
  }

  @Test
  void typingSelectsTheNextNodeShownThatStartsWithTheText() throws IOException {
    Catalog catalog = new Catalog(T).opened("localhost", "localhost/shop", "localhost/shop/public");
    App<Catalog> app = start(catalog);
    run(app, "focus #schema\nkey Home\nframe");
    assertEquals("localhost/postgres", typed(app, catalog, "type \"p\""));
    assertEquals("localhost/shop/public", typed(app, catalog, "type \"P\""), "again: the next");
    assertEquals(
        "localhost/shop/public/sessions",
        typed(app, catalog, "advance 1\ntype \"s\""),
        "a pause starts a new text");
    assertEquals(
        "localhost/shop/public/suppliers",
        typed(app, catalog, "advance 0.5\ntype \"u\""),
        "within the pause it adds to the text, searched from the selected node on");
    assertEquals(
        "prod-eu", typed(app, catalog, "key Escape\ntype \"eu\""), "Escape too; else holds it");
    assertEquals("prod-eu", typed(app, catalog, "type \"xyz\""), "nothing matches: it stays");
  }

  /** Runs the steps and a frame; the path of the selected node, which is whole in view. */
  private String typed(App<Catalog> app, Catalog catalog, String steps) throws IOException {
    run(app, steps + "\nframe");
    El selected = find(app, "schema").children().stream()
        .filter(row -> row.fill() == T.grid().selected())
        .findFirst()
        .orElseThrow(() -> new AssertionError("the selected row is built"));
    assertInView(app, Integer.parseInt(selected.key().substring("row-".length())));
    return catalog.selected.path();
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
    assertEquals("status", loading.role());
    assertEquals("Loading…", loading.label());
    assertEquals("progressbar", loading.children().get(2).role(), "a spinner");
    El text = loading.children().getLast();
    assertEquals("Loading…", text.text().string());
    assertEquals(T.text().muted(), text.text().color());
    assertEquals(T.space().s() + T.space().l(), loading.children().get(1).x(), "level 1");

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
        T.space().s() + T.space().l(), find(app, "row-3").children().get(1).x());
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
    assertEquals(T.space().s() + T.space().l(), row.children().get(1).x(), "level 2");
    assertEquals(T.status().danger(), row.children().get(2).text().color(), "the icon");
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
    assertEquals("status", find(app, "row-4").role(), "loading again");
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

  // Label tones

  @Test
  void toneColorsOnlyTheLabelBeatsMutedAndYieldsToGrabbed() throws IOException {
    Tree.Dragging dragging = new Tree.Dragging();
    Tree.Model<String> model = new Tree.Model<>() {
      @Override
      public List<String> roots() {
        return List.of("modified", "both", "detailed", "plain", "ignored");
      }

      @Override
      public Tree.Children<String> children(String node) {
        return Tree.ready(List.of());
      }

      @Override
      public boolean isLeaf(String node) {
        return true;
      }

      @Override
      public String label(String node) {
        return node;
      }

      @Override
      public El icon(String node) {
        return Icon.icon(Symbol.DOCUMENT, Icon.Variant.MUTED);
      }

      @Override
      public String detail(String node) {
        return node.equals("detailed") ? "new" : null;
      }

      @Override
      public boolean muted(String node) {
        return node.equals("ignored") || node.equals("both");
      }

      @Override
      public Tree.Tone tone(String node) {
        return switch (node) {
          case "modified", "both" -> Tree.Tone.ACCENT;
          case "detailed" -> Tree.Tone.SUCCESS;
          default -> null;
        };
      }

      @Override
      public String key(String node) {
        return node;
      }
    };
    App<Tree.Model<String>> app = new App<>(
            model,
            m -> col()
                .wFull()
                .hFull()
                .child(Tree.tree(
                        m,
                        new Tree.Options<String>()
                            .drag(dragging, n -> true, move -> true, move -> {}))
                    .key("files")
                    .grow(1)))
        .provide(Theme.CURRENT, m -> T);
    run(app, "frame");

    assertTrue(dumpLine(app, "row-both").endsWith(" tone=accent"), dumpLine(app, "row-both"));
    assertFalse(dumpLine(app, "row-plain").contains("tone="), dumpLine(app, "row-plain"));

    assertEquals(
        T.prose().link(), textOf(find(app, "row-modified"), "modified").text().color());
    assertEquals(
        T.prose().link(),
        textOf(find(app, "row-both"), "both").text().color(),
        "tone wins over muted");
    assertEquals(
        T.status().success(),
        textOf(find(app, "row-detailed"), "detailed").text().color(),
        "detail path uses the tone");
    assertEquals(
        T.text().primary(), textOf(find(app, "row-plain"), "plain").text().color());
    assertEquals(
        T.text().muted(), textOf(find(app, "row-ignored"), "ignored").text().color());

    // Icon and detail keep their own colors.
    assertEquals(
        T.text().muted(),
        find(app, "row-modified").children().get(1).text().color(),
        "icon keeps its own color");
    assertEquals(
        T.text().muted(),
        textOf(find(app, "row-detailed"), "new").text().color(),
        "detail stays muted");

    // Dragging a toned row mutes its label while grabbed.
    run(app, "down #row-modified\nmove #row-plain\nframe");
    assertTrue(dumpLine(app, "row-modified").contains(" grabbed "), dumpLine(app, "row-modified"));
    assertEquals(
        T.text().muted(),
        textOf(find(app, "row-modified"), "modified").text().color(),
        "grabbed row stays muted");
    run(app, "key Escape\nup #row-plain\nframe");
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void tonesLookAsStored(String name) throws IOException {
    Theme theme = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    String[] selected = {null};
    Tree.Model<String> model = new Tree.Model<>() {
      @Override
      public List<String> roots() {
        return List.of("src", "README.md", "target");
      }

      @Override
      public Tree.Children<String> children(String node) {
        return node.equals("src")
            ? Tree.ready(
                List.of("App.java", "Tree.java", "Config.java", "Draft.java", "Merge.java"))
            : Tree.ready(List.of());
      }

      @Override
      public boolean isLeaf(String node) {
        return !node.equals("src") && !node.equals("target");
      }

      @Override
      public String label(String node) {
        return node;
      }

      @Override
      public El icon(String node) {
        return Icon.icon(isLeaf(node) ? Symbol.DOCUMENT : Symbol.FOLDER, Icon.Variant.MUTED);
      }

      @Override
      public String detail(String node) {
        return node.equals("Merge.java") ? "conflict" : null;
      }

      @Override
      public boolean muted(String node) {
        return node.equals("target");
      }

      @Override
      public Tree.Tone tone(String node) {
        return switch (node) {
          case "src", "App.java" -> Tree.Tone.ACCENT;
          case "Tree.java" -> Tree.Tone.SUCCESS;
          case "Config.java" -> Tree.Tone.WARNING;
          case "Draft.java", "Merge.java" -> Tree.Tone.DANGER;
          default -> null;
        };
      }

      @Override
      public String key(String node) {
        return node;
      }
    };
    App<String[]> app = new App<>(
            selected,
            s -> col()
                .wFull()
                .hFull()
                .padding(theme.space().m())
                .fill(theme.surface().panel())
                .child(Tree.tree(
                        model,
                        new Tree.Options<String>()
                            .open("src"::equals, null)
                            .select(s[0], n -> s[0] = n))
                    .key("files")
                    .label("Files")
                    .grow(1)))
        .provide(Theme.CURRENT, s -> theme);
    app.textRendering(TextRendering.LINUX);

    List<Path> files = Script.run(
        "size 260 216 scale 2\nframe\npress #row-App.java\nframe\nsnap tones\n",
        app,
        out.resolve("tones-" + name));
    Golden.assertSnaps("tree-" + name + "-", files, "tones");
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
    public Tree.Children<String> children(String node) {
      return switch (node) {
        case "deep" -> Tree.ready(List.of("deep/two"));
        case "deep/two" -> Tree.ready(List.of("deep/two/three"));
        case "deep/two/three" -> Tree.ready(List.of("deep/two/three/four"));
        case "deep/two/three/four" ->
          Tree.ready(List.of("deep/two/three/four/plain", "deep/two/three/four/detailed"));
        default -> Tree.loading(WAITING);
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
  }

  private static final String WAITING =
      "Loading the databases of a server far away, which takes a while";

  @Test
  void aLongLabelEndsInAnEllipsisAndShowsWholeInATooltip() throws IOException {
    int width = 150;
    LongNames model = new LongNames();
    App<LongNames> app = new App<>(
        model,
        m -> col()
            .wFull()
            .hFull()
            .child(Tree.tree(
                    m,
                    new Tree.Options<String>().open(m.open::contains, null).select(null, s -> {}))
                .key("tree")
                .grow(1)));
    run(app, "size " + width + " 300\nframe");
    // Rows 0 and 1 at level 1, 6 and 7 at level 5, 9 the loading row below waiting.
    for (int row : new int[] {0, 1, 6, 7, 9}) {
      El el = find(app, "row-" + row);
      String text = row == 9 ? WAITING : LONG;
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

  // Meta and trailing slots

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void metaAndTrailingKeepTheirWidthWhileALongLabelShortens(String name) throws IOException {
    Theme theme = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    String longBranch = "feat/support-rich-row-slots-in-tree-model";
    String[] selected = {"main"};
    Tree.Model<String> model = new Tree.Model<>() {
      @Override
      public List<String> roots() {
        return List.of("branches", "tags");
      }

      @Override
      public Tree.Children<String> children(String node) {
        return node.equals("branches")
            ? Tree.ready(List.of("main", "release", "feat"))
            : Tree.ready(List.of());
      }

      @Override
      public boolean isLeaf(String node) {
        return !node.equals("branches") && !node.equals("tags");
      }

      @Override
      public String label(String node) {
        return switch (node) {
          case "branches" -> "Branches";
          case "tags" -> "Tags";
          case "release" -> "release/v2";
          case "feat" -> longBranch;
          default -> node;
        };
      }

      @Override
      public El icon(String node) {
        Symbol symbol = switch (node) {
          case "branches" -> Symbol.FOLDER;
          case "tags" -> Symbol.TAG;
          default -> Symbol.BRANCH;
        };
        return Icon.icon(symbol, Icon.Variant.MUTED);
      }

      @Override
      public El meta(String node) {
        return switch (node) {
          case "main" -> Badge.badge(Badge.Kind.ACCENT, "current");
          case "release" -> Badge.badge(Badge.Kind.SUCCESS, "+2");
          case "feat" -> Badge.badge(Badge.Kind.WARNING, "dirty");
          default -> null;
        };
      }

      @Override
      public String detail(String node) {
        return node.equals("release") ? "3 commits" : null;
      }

      @Override
      public El trailing(String node) {
        return switch (node) {
          case "branches" -> Badge.badge("3");
          case "tags" -> Badge.badge("12");
          case "main" -> Label.label(Label.Variant.MUTED, "origin/main");
          case "release" -> Icon.icon(Symbol.CHEVRON_RIGHT, Icon.Variant.MUTED);
          case "feat" -> Label.label(Label.Variant.MUTED, "origin/feat");
          default -> null;
        };
      }

      @Override
      public String key(String node) {
        return node;
      }
    };
    App<String[]> app = new App<>(
            selected,
            s -> col()
                .wFull()
                .hFull()
                .padding(theme.space().m())
                .fill(theme.surface().panel())
                .child(Tree.tree(
                        model,
                        new Tree.Options<String>()
                            .open("branches"::equals, null)
                            .select(s[0], n -> s[0] = n))
                    .key("branches")
                    .label("Branches")
                    .grow(1)))
        .provide(Theme.CURRENT, s -> theme);
    app.textRendering(TextRendering.LINUX);

    List<Path> files =
        Script.run("size 280 144 scale 2\nframe\nsnap slots\n", app, out.resolve("slots-" + name));
    Golden.assertSnaps("tree-" + name + "-", files, "slots");

    String dump = app.tree().dump();
    // The row's padding, or the scrollbar's strip where that is wider.
    float padRight = Math.max(theme.space().s(), theme.scrollbars().strip());

    // Short label: meta sits immediately after label; trailing sits at the right edge.
    float[] mainRow = StructureTest.rect(StructureTest.line(dump, "#row-main "));
    float[] mainLabel = StructureTest.rect(StructureTest.line(dump, "text \"main\""));
    float[] mainMeta = StructureTest.rect(StructureTest.line(dump, "el label=\"current\""));
    float[] mainTrailing = StructureTest.rect(StructureTest.line(dump, "text \"origin/main\""));
    assertFalse(textOf(find(app, "row-main"), "main").text().shortened());
    assertEquals(mainLabel[0] + mainLabel[2] + theme.space().s(), mainMeta[0], 0.5f, dump);
    assertEquals(
        mainRow[0] + mainRow[2] - padRight,
        mainTrailing[0] + mainTrailing[2],
        0.5f,
        "trailing aligned to the right edge:\n" + dump);

    // Long label: truncates with ellipsis while meta and trailing keep full width and positions.
    float[] featRow = StructureTest.rect(StructureTest.line(dump, "#row-feat "));
    float[] featLabel = StructureTest.rect(StructureTest.line(dump, "text \"" + longBranch + "\""));
    float[] featMeta = StructureTest.rect(StructureTest.line(dump, "el label=\"dirty\""));
    float[] featTrailing = StructureTest.rect(StructureTest.line(dump, "text \"origin/feat\""));
    assertTrue(textOf(find(app, "row-feat"), longBranch).text().shortened(), dump);
    assertFalse(textOf(find(app, "row-feat"), "dirty").text().shortened(), dump);
    assertFalse(textOf(find(app, "row-feat"), "origin/feat").text().shortened(), dump);
    assertEquals(featLabel[0] + featLabel[2] + theme.space().s(), featMeta[0], 0.5f, dump);
    assertEquals(
        featRow[0] + featRow[2] - padRight,
        featTrailing[0] + featTrailing[2],
        0.5f,
        "trailing aligned to the right edge on a long row:\n" + dump);

    // Meta + detail + trailing: in reading order, detail carries row value, trailing at right edge.
    float[] releaseRow = StructureTest.rect(StructureTest.line(dump, "#row-release "));
    float[] releaseMeta = StructureTest.rect(StructureTest.line(dump, "el label=\"+2\""));
    float[] releaseDetail = StructureTest.rect(StructureTest.line(dump, "text \"3 commits\""));
    El releaseEl = find(app, "row-release");
    assertTrue(dumpLine(app, "row-release").contains("value=\"3 commits\""), dump);
    assertEquals(releaseMeta[0] + releaseMeta[2] + theme.space().s(), releaseDetail[0], 0.5f, dump);
    El releaseChevron = releaseEl.children().getLast();
    assertEquals(
        releaseRow[2] - padRight,
        releaseChevron.x() + releaseChevron.width(),
        0.5f,
        "trailing chevron aligned to the right edge:\n" + dump);

    // Pressing the passive meta selects its row.
    float mx = featMeta[0] + featMeta[2] / 2;
    float my = featMeta[1] + featMeta[3] / 2;
    Script.run(
        "size 280 144\npress " + mx + "," + my + "\nframe\n", app, out.resolve("press-" + name));
    assertEquals("feat", selected[0], "pressing passive meta selects its row");

    // In a narrower tree, detail gives way first while label, meta and trailing stay whole.
    Script.run("size 205 144\nframe\nadvance 1\nframe\n", app, out.resolve("narrow-" + name));
    String narrowDump = app.tree().dump();
    El narrowRelease = find(app, "row-release");
    El narrowDetail = textOf(narrowRelease, "3 commits");
    assertTrue(
        narrowDetail.text().shortened() || narrowDetail.y() >= theme.grid().rowHeight(),
        "detail yields first:\n" + narrowDump);
    assertFalse(textOf(narrowRelease, "release/v2").text().shortened(), narrowDump);
    float[] narrowFeatRow = StructureTest.rect(StructureTest.line(narrowDump, "#row-feat "));
    float[] narrowFeatMeta =
        StructureTest.rect(StructureTest.line(narrowDump, "el label=\"dirty\""));
    float[] narrowFeatTrailing =
        StructureTest.rect(StructureTest.line(narrowDump, "text \"origin/feat\""));
    assertEquals(featMeta[2], narrowFeatMeta[2], 0.5f, "meta keeps full width in narrow tree");
    assertEquals(
        featTrailing[2], narrowFeatTrailing[2], 0.5f, "trailing keeps full width in narrow tree");
    assertEquals(
        narrowFeatRow[0] + narrowFeatRow[2] - padRight,
        narrowFeatTrailing[0] + narrowFeatTrailing[2],
        0.5f,
        "trailing stays at the right edge in narrow tree:\n" + narrowDump);

    // Narrower still: release/v2 shortens while its meta badge (+2) keeps its full width.
    Script.run("size 170 144\nframe\nadvance 1\nframe\n", app, out.resolve("tight-" + name));
    String tightDump = app.tree().dump();
    assertTrue(textOf(find(app, "row-release"), "release/v2").text().shortened(), tightDump);
    float[] tightReleaseMeta = StructureTest.rect(StructureTest.line(tightDump, "el label=\"+2\""));
    assertEquals(
        releaseMeta[2], tightReleaseMeta[2], 0.5f, "meta keeps full width when label+detail cut");
    float[] tightFeatRow = StructureTest.rect(StructureTest.line(tightDump, "#row-feat "));
    float[] tightFeatTrailing =
        StructureTest.rect(StructureTest.line(tightDump, "text \"origin/feat\""));
    assertEquals(
        tightFeatRow[0] + tightFeatRow[2] - padRight,
        tightFeatTrailing[0] + tightFeatTrailing[2],
        0.5f,
        "meta gives way before the trailing element leaves the row:\n" + tightDump);
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
            .child(Tree.tree(
                    c,
                    new Tree.Options<Node>()
                        .open(c.open::contains, null)
                        .select(c.node("localhost/shop"), null))
                .key("schema")
                .grow(1)));
    run(app, "frame\nmove #row-1\npress #row-1\npress #mark-0\nkey Tab\nframe");
    assertNull(app.focused());
    assertEquals(0, find(app, "row-1").fill(), "no hover");
    assertEquals(T.grid().selectedInactive(), find(app, "row-3").fill(), "the selection shows");
    assertTrue(dumpLine(app, "row-3").contains(" selected "));
    assertEquals(7, find(app, "schema").rowCount());
  }

  // Details

  @Test
  void anEmptyDetailDoesNotPreventTheRowFromBeingShown() throws IOException {
    Tree.Model<String> model = new Tree.Model<>() {
      @Override
      public List<String> roots() {
        return List.of("name");
      }

      @Override
      public Tree.Children<String> children(String node) {
        return Tree.ready(List.of());
      }

      @Override
      public boolean isLeaf(String node) {
        return true;
      }

      @Override
      public String label(String node) {
        return node;
      }

      @Override
      public String detail(String node) {
        return "";
      }
    };
    App<Tree.Model<String>> app =
        new App<>(model, m -> Tree.tree(m, new Tree.Options<>()).wFull().hFull());
    run(app, "frame\nmove #row-0\nframe\nadvance 1\nframe");
    assertTrue(dumpLine(app, "row-0").contains("value=\"\""));
    assertFalse(textOf(find(app, "row-0"), "name").text().shortened());
    assertFalse(app.tree().dump().contains("role=tooltip"));
  }

  // Dragging

  /**
   * Folders and files by name, keyed by name, which the tree lets the user drag: docs holds
   * drafts, which holds b.txt, then a.txt and as many more files as the test asks for; music
   * holds c.mp3; notes.txt lies at the top. Drops are logged, not made.
   */
  private static final class Folders implements Tree.Model<String> {
    final java.util.Map<String, List<String>> folders = new java.util.HashMap<>();
    final java.util.Set<String> open = new java.util.HashSet<>(List.of("docs", "drafts"));
    final Tree.Dragging dragging = new Tree.Dragging();
    final List<String> log = new ArrayList<>();
    final List<Tree.Move<String>> asked = new ArrayList<>();
    java.util.function.Predicate<Tree.Move<String>> allows = move -> true;
    String selected;
    float padding;

    Folders(int files) {
      List<String> docs = new ArrayList<>(List.of("drafts", "a.txt"));
      for (int i = 0; i < files; i++) {
        docs.add(String.format("f%03d.txt", i));
      }
      folders.put("docs", docs);
      folders.put("drafts", List.of("b.txt"));
      folders.put("music", List.of("c.mp3"));
    }

    @Override
    public List<String> roots() {
      return List.of("docs", "music", "notes.txt");
    }

    @Override
    public Tree.Children<String> children(String node) {
      return Tree.ready(folders.get(node));
    }

    @Override
    public boolean isLeaf(String node) {
      return !folders.containsKey(node);
    }

    @Override
    public String label(String node) {
      return node;
    }

    @Override
    public String key(String node) {
      return node;
    }

    El view() {
      return Tree.tree(
              this,
              new Tree.Options<String>()
                  .open(open::contains, (node, on) -> {
                    log.add((on ? "open " : "close ") + node);
                    if (on) {
                      open.add(node);
                    } else {
                      open.remove(node);
                    }
                  })
                  .select(selected, node -> {
                    log.add("select " + node);
                    selected = node;
                  })
                  .onActivate(node -> log.add("activate " + node))
                  .drag(
                      dragging,
                      node -> true,
                      move -> asked.add(move) && allows.test(move),
                      move -> log.add(move.node() + " " + move.place() + " " + move.target())))
          .key("tree")
          .padding(padding)
          .wFull()
          .hFull();
    }
  }

  // The rows of new Folders(0), 24 high from the top of the window: docs, drafts, b.txt, a.txt,
  // music, notes.txt. A level of the tree is 12 wide from 4 on: x 20 is the first's.

  @Test
  void aDropReportsTheNodeTheTargetAndWhereItGoes() throws IOException {
    Folders folders = new Folders(0);
    App<Folders> app = new App<>(folders, Folders::view);
    run(app, """
        frame
        down #row-a.txt
        move #row-music
        up #row-music
        frame
        down #row-notes.txt
        move #row-a.txt at top+2
        up #row-a.txt at top+2
        frame
        down #row-notes.txt
        move #row-b.txt at bottom-2
        up #row-b.txt at bottom-2
        frame
        down #row-notes.txt
        move #row-b.txt at left+20,bottom-2
        up #row-b.txt at left+20,bottom-2
        frame
        down #row-a.txt
        move 200,200
        up 200,200
        frame
        press #row-a.txt
        frame
        press #row-drafts clicks 2
        frame""");
    assertEquals(
        List.of(
            "a.txt INTO music", // the middle of a closed folder
            "notes.txt BEFORE a.txt", // the top of a row
            "notes.txt AFTER b.txt", // the bottom of the last child of drafts
            "notes.txt AFTER drafts", // there, at the level of drafts
            "a.txt AFTER notes.txt", // below the rows: after the last root
            "select a.txt", // a press without a drag
            "select drafts",
            "close drafts",
            "activate drafts"),
        folders.log);
    assertNull(find(app, "marker"));
  }

  @Test
  void theTreesOwnPaddingMovesTheDropsWithItsRows() throws IOException {
    Folders folders = new Folders(0);
    folders.padding = 12;
    App<Folders> app = new App<>(folders, Folders::view);
    run(app, """
        frame
        down #row-notes.txt
        move #row-b.txt at left+20,bottom-2
        frame""");
    // The line along the top of a.txt from the level of drafts, under the pointer: 16 to 28.
    assertEquals(16, find(find(app, "row-a.txt"), "marker").x());
    run(app, """
        up #row-b.txt at left+20,bottom-2
        frame
        down #row-notes.txt
        move #row-a.txt at top+2
        frame""");
    assertNotNull(find(find(app, "row-a.txt"), "marker"), "the line along the top of a.txt");
    run(app, "up #row-a.txt at top+2\nframe");
    assertEquals(List.of("notes.txt AFTER drafts", "notes.txt BEFORE a.txt"), folders.log);
  }

  @Test
  void aNodeGoesNeitherIntoItselfNorIntoItsDescendantsNorWhereTheAppSaysNo() throws IOException {
    Folders folders = new Folders(0);
    folders.allows = move -> !move.target().equals("music");
    App<Folders> app = new App<>(folders, Folders::view);
    run(app, "frame\ndown #row-docs\nmove #row-drafts\nframe");
    assertTrue(dumpLine(app, "row-docs").contains(" grabbed"));
    for (String over :
        List.of("#row-drafts", "#row-b.txt", "#row-a.txt", "#row-docs", "#row-music")) {
      run(app, "move " + over + "\nframe");
      assertNull(find(app, "marker"), "nothing shows over " + over);
    }
    run(app, "move 200,122\nframe"); // the top of notes.txt
    assertNotNull(find(app, "marker"), "a line above notes.txt");
    run(app, "move #row-b.txt\nup #row-b.txt\nframe");
    assertEquals(List.of(), folders.log);
    List<String> inside = List.of("docs", "drafts", "b.txt", "a.txt");
    assertTrue(
        folders.asked.stream().noneMatch(move -> inside.contains(move.target())),
        "never asked about a move into docs itself: " + folders.asked);
    assertFalse(app.tree().dump().contains("grabbed"));
  }

  @Test
  void aClosedNodeOpensWhileThePointerRestsOnItAndEscapeGivesTheDragUp() throws IOException {
    Folders folders = new Folders(0);
    App<Folders> app = new App<>(folders, Folders::view);
    run(app, "frame\ndown #row-a.txt\nmove #row-music\nframe\nadvance 0.2\nframe");
    assertEquals(List.of(), folders.log, "not yet");
    run(app, "advance 0.1\nframe"); // the theme's submenu delay, without a move
    assertEquals(List.of("open music"), folders.log);
    assertTrue(app.tree().dump().contains("#row-c.mp3"));
    run(app, "key Escape\nup #row-music\nframe");
    assertEquals(List.of("open music"), folders.log, "nothing moved");
    assertNull(find(app, "marker"));
  }

  @Test
  void theTreeScrollsAlongNearAnEdgeAndNotToTheSelection() throws IOException {
    Folders folders = new Folders(100);
    folders.open.remove("drafts");
    folders.selected = "docs";
    App<Folders> app = new App<>(folders, Folders::view);
    run(app, "frame\nwheel #tree 0 10000\nframe"); // the end of the rows, docs out of view
    double end = find(app, "tree").scrollY();
    assertTrue(end > 2000);
    run(app, "down #row-f099.txt\nmove #row-music\nframe\nadvance 0.3\nframe");
    assertEquals(List.of("open music"), folders.log);
    assertEquals(end, find(app, "tree").scrollY(), "the selection above stays out of view");
    run(app, "move 140,5\nframe\nadvance 0.5\nframe"); // waits at the top
    double along = find(app, "tree").scrollY();
    assertEquals(end - 120, along, 0.01, "half a second of ten rows a second");
    // The line shows where the rows went, at the pointer: below f084.txt, the top of f085.txt.
    El marked = find(app, "row-f085.txt");
    assertTrue(marked.children().stream().anyMatch(c -> "marker".equals(c.key())));
    run(app, "up 140,5\nframe");
    assertEquals("f099.txt AFTER f084.txt", folders.log.getLast());
    assertEquals(along, find(app, "tree").scrollY(), "where the drag left it");
  }

  /** A chain of twelve open folders, n1 holding n2 down to the leaf n12, and the root other. */
  private static final class Chain implements Tree.Model<String> {
    final Tree.Dragging dragging = new Tree.Dragging();
    final List<String> log = new ArrayList<>();

    @Override
    public List<String> roots() {
      return List.of("n1", "other");
    }

    @Override
    public Tree.Children<String> children(String node) {
      int level = Integer.parseInt(node.substring(1));
      return Tree.ready(List.of("n" + (level + 1)));
    }

    @Override
    public boolean isLeaf(String node) {
      return node.equals("other") || node.equals("n12");
    }

    @Override
    public String label(String node) {
      return node.equals("other") ? node : "level " + node.substring(1);
    }

    @Override
    public String key(String node) {
      return node;
    }

    El view() {
      return Tree.tree(
              this,
              new Tree.Options<String>()
                  .open(node -> true, null)
                  .drag(
                      dragging,
                      node -> true,
                      move -> true,
                      move -> log.add(move.node() + " " + move.place() + " " + move.target())))
          .key("tree")
          .wFull()
          .hFull();
    }
  }

  @Test
  void deepRowsKeepTheirMarksAndNamesInANarrowTreeAndDropsFollowTheirIndent() throws IOException {
    Chain chain = new Chain();
    App<Chain> app = new App<>(chain, Chain::view);
    run(app, "size 160 400\nframe");
    float width = find(app, "tree").width();
    for (int level = 1; level <= 12; level++) {
      El row = find(app, "row-n" + level);
      El name = textOf(row, "level " + level);
      boolean shown = name.width() >= 32 && name.x() + name.width() <= width;
      assertTrue(shown, "32 of the name of level " + level + " in view");
      if (level < 12) {
        El mark = find(row, "mark-n" + level);
        assertTrue(mark.x() + mark.width() <= width, "the mark of level " + level + " in view");
      }
    }
    // Half of 160 holds six indents of 12: deeper rows stay at the seventh level's place.
    assertEquals(
        find(find(app, "row-n7"), "mark-n7").x(),
        find(find(app, "row-n11"), "mark-n11").x());
    assertTrue(dumpLine(app, "row-n12").contains(" level=12 "), dumpLine(app, "row-n12"));
    // Below the leaf n12, the line at n6's indent means after n6, at the deepest after n12.
    run(app, "size 160 400\ndown #row-other\nmove #row-n12 at left+66,bottom-2\nframe");
    El marker = find(find(app, "row-other"), "marker");
    assertEquals(4 + 5 * 12, marker.x(), "the line at n6's indent");
    run(app, """
        size 160 400
        up #row-n12 at left+66,bottom-2
        frame
        down #row-other
        move #row-n12 at left+78,bottom-2
        up #row-n12 at left+78,bottom-2
        frame""");
    assertEquals(List.of("other AFTER n6", "other AFTER n12"), chain.log);
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
    public Tree.Children<String> children(String node) {
      return children == null ? Tree.loading() : Tree.ready(children);
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
              this,
              new Tree.Options<String>()
                  .open(open::contains, (node, on) -> {
                    if (on) {
                      open.add(node);
                    } else {
                      open.remove(node);
                    }
                  })
                  .select(selected, s -> selected = s)
                  .onActivate(activated::add))
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
