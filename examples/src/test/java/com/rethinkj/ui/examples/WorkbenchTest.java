package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchTest {

  @TempDir Path out;

  /** Runs the example's {@code main} on {@code workbench.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("workbench", Workbench::main);
    assertTrue(Snaps.line("workbench", "workbench-refused", "#status")
        .contains("prod-eu: connection refused"));
    assertTrue(Snaps.line("workbench", "workbench-light", "#status")
        .endsWith("\"localhost/shop/public/orders · 1,000,000 rows\" 289,549 286x16"),
        "the message went after five seconds");
    assertTrue(Snaps.line("workbench", "workbench-completion", "#option-0")
        .contains("label=\"customer_id\"") && Snaps.line("workbench", "workbench-completion",
        "#option-0").contains(" selected"), "the completions at the caret");
    assertTrue(Snaps.line("workbench", "workbench-completion", "#position")
        .contains("\"Ln 3, Col 9\""));
    assertTrue(Snaps.line("workbench", "workbench-error", "#status")
        .contains("relation \\\"shop.public.ordrs\\\" does not exist"));
    assertTrue(Snaps.line("workbench", "workbench-edit-menu", "#title-1").contains("expanded"));
    assertTrue(Snaps.line("workbench", "orders-workbench-torn", "#status")
        .contains("\"localhost/shop/public/orders · 5 rows\""), "the answer in the torn window");
    assertTrue(Snaps.line("workbench", "workbench-docking", "#marker").startsWith("el #marker"));
    assertTrue(Snaps.line("workbench", "workbench-find", "#count").contains("\"1 of 2\""),
        "the query's find bar: the O of FROM and the o of orders");
    assertTrue(Snaps.line("workbench", "workbench-edit-menu", "#item-12")
        .contains("label=\"Go to line…\""), "the editor's commands in the Edit menu");
    assertTrue(Snaps.line("workbench", "workbench-palette", "#option-0")
        .contains("label=\"File: New query\""), "the menus' commands in the palette");
    assertTrue(Snaps.line("workbench", "workbench-palette-tables", "#option-0")
        .contains("label=\"orders.customer_id\""), "a column of orders after @");
    assertTrue(Snaps.line("workbench", "workbench-palette-light", "#option-1")
        .contains(" selected"), "the light theme lit and shown");
    assertFalse(Files.exists(Path.of("target/workbench/orders-workbench-docked.png")));
  }

  @Test
  void theServersAnswerAsTheTreeAsks() throws Exception {
    assertEquals(List.of("scratch", "shop"), Workbench.connect("staging", Duration.ZERO));
    assertThrows(ConnectException.class, () -> Workbench.connect("prod-eu", Duration.ZERO));
    assertEquals(1_000_000, Workbench.count("localhost/shop/public/orders", Duration.ZERO));
  }

  /** The tree and the grid stay as high as their part of the split and scroll inside it. */
  @Test
  void theTreeAndTheGridScrollInsideTheirParts() throws IOException {
    Snaps.assumeText();
    Workbench s = new Workbench();
    s.latency = Duration.ZERO;
    App<Workbench> app = new App<>(s, Workbench::view);
    s.app = app;
    Script.run("""
        size 1000 600
        frame
        press role=treeitem label="orders" clicks 2
        frame
        await
        frame
        """, app, out);
    double before = find(app.tree(), "catalogue").scrollY(); // the tree showed orders' columns
    Script.run("size 1000 600\nwheel #catalogue 0 400\nwheel 600,300 0 400\nframe", app, out);
    El tree = find(app.tree(), "catalogue");
    El grid = find(app.tree(), "grid-localhost/shop/public/orders");
    assertTrue(tree.height() < 600 && grid.height() < 600, tree.height() + ", " + grid.height());
    assertEquals(before + 400, tree.scrollY(), "the tree scrolled");
    assertEquals(400, find(grid, "rows").scrollY(), "the rows scrolled");
  }

  private App<Workbench> started() {
    Snaps.assumeText();
    Workbench s = new Workbench();
    s.latency = Duration.ZERO;
    App<Workbench> app = new App<>(s, Workbench::view);
    s.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private static final String OPEN_ORDERS = """
      size 1000 600
      frame
      press role=treeitem label="orders" clicks 2
      frame
      await
      frame
      advance 1
      frame
      """;

  /** Caret, selection, scroll and history of a tab's query survive another tab in between. */
  @Test
  void eachTabKeepsItsEditorAcrossSwitches() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    StringBuilder lines = new StringBuilder("SELECT *\nFROM shop.public.orders\nWHERE id IN (");
    for (int i = 1; i <= 30; i++) {
      lines.append("\n  ").append(i).append(',');
    }
    orders.query = lines + "\n  0);";
    String field = "query-localhost/shop/public/orders";
    Script.run("size 1000 600\nframe\nfocus #" + field + "\nkey End control\ntype \" -- x\"\n"
        + "key ArrowLeft shift\nframe", app, out);
    int caret = orders.editor.caret();
    int anchor = orders.editor.anchor();
    double scrolled = find(app.tree(), field).scrollY();
    assertTrue(scrolled > 0 && caret == anchor - 1, scrolled + " " + caret + " " + anchor);

    Script.run("""
        size 1000 600
        press role=treeitem label="customers" clicks 2
        frame
        await
        frame
        advance 1
        frame
        """, app, out);
    assertEquals(2, s.groups.main().active());
    assertNull(find(app.tree(), field), "the query of orders is not in the tree");
    Script.run("size 1000 600\npress #tab-1\nframe\nadvance 1\nframe", app, out);
    assertEquals(caret, orders.editor.caret());
    assertEquals(anchor, orders.editor.anchor());
    assertEquals(scrolled, find(app.tree(), field).scrollY(), "and its scroll");
    assertTrue(app.tree().dump().contains("Ln 34, Col 10"), "the caret's place in the footer");
    Script.run("size 1000 600\nfocus #" + field + "\nkey Z control\nframe", app, out);
    assertTrue(orders.query.endsWith("0);"), "undo took back the typing from before the switch");
  }

  /**
   * The command palette: Control+P finds a table after @ and opens it, : goes to a line of the
   * active query, the commands of the menus run, and a tab's own window has a palette of its own.
   */
  @Test
  void thePaletteOpensTablesGoesToLinesAndRunsTheMenusCommands() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run("size 1000 600\nframe\nkey P control\nframe\ntype \"cust\"\nframe", app, out);
    assertEquals("@cust", s.palette(s.groups.main()).text());
    assertTrue(app.tree().dump().contains("label=\"customers\""), "found among the tables");
    Script.run("size 1000 600\nkey Enter\nframe\nawait\nframe\nadvance 1\nframe", app, out);
    assertEquals("customers", s.groups.main().activeTab().title(), "the best match opened");
    assertFalse(s.palette(s.groups.main()).isOpen());

    Workbench.Opened customers = s.groups.main().activeTab();
    customers.query = "SELECT *\nFROM customers\nLIMIT 3;";
    Script.run("size 1000 600\nframe\nkey F1\nframe\ntype \":3:4\"\nframe\nkey Enter\nframe",
        app, out);
    assertEquals(customers.query.indexOf("LIMIT") + 3, customers.editor.caret(),
        "line 3, column 4");
    assertTrue(app.tree().dump().contains("Ln 3, Col 4"), "the footer says so");

    Script.run("size 1000 600\nkey P control shift\nframe\ntype \"light\"\nframe\nkey Enter\n"
        + "frame", app, out);
    assertFalse(s.dark, "View: Light theme ran");
    assertEquals(List.of("View: Light theme"), s.palette(s.groups.main()).recent());

    // A tab in a window of its own: its palette, opened there, acts on its tab.
    Script.run("""
        size 1000 600
        down #tab-1
        move 380,60
        move 380,300
        frame
        up 380,300
        frame
        window customers
        key F1
        frame
        type "close tab"
        frame
        key Enter
        frame
        """, app, out);
    assertTrue(s.groups.groups().size() == 1, "File: Close tab closed its last tab, and its window");
    assertTrue(s.palette(s.groups.main()).recent().size() == 1, "each window has its own");
  }

  @Test
  void aStatementAboutATableTheServerLacksUnderlinesItsName() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    orders.query = "SELECT * FROM shop.public.ordrs";
    Script.run("size 1000 600\nframe\nfocus #query-localhost/shop/public/orders\n"
        + "key Enter control\nframe\nawait\nframe", app, out);
    assertEquals("relation \"shop.public.ordrs\" does not exist", orders.failure);
    assertEquals(1_000_000, orders.rows, "the rows of before stay");
    assertTrue(app.tree().dump().contains("does not exist"), "the status line says why");
    Script.run("size 1000 600\ntype \" \"\nframe", app, out);
    assertNull(orders.failure, "the next edit takes the error away");
  }

  @Test
  void aStatementRunAgainBeforeItsAnswerKeepsTheRowsOfBeforeWhenItFails() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    orders.query = "SELECT * FROM shop.public.ordrs";
    // Control+Enter twice before the answer: the second question cancels the first.
    Script.run("size 1000 600\nframe\nfocus #query-localhost/shop/public/orders\n"
        + "key Enter control\nframe\nkey Enter control\nframe\nawait\nframe", app, out);
    assertEquals("relation \"shop.public.ordrs\" does not exist", orders.failure);
    assertEquals(1_000_000, orders.rows, "the rows of before stay, not \"counting the rows\"");
  }

  @Test
  void theCompletionsHaveTheTablesAndColumnsOfConnectedServersAndKeywords() {
    Workbench s = new Workbench();
    Workbench.Opened orders = new Workbench.Opened(table(s, "orders"));
    List<String> found = s.suggest(orders, "cu").stream().map(e -> e.text()).toList();
    assertEquals("customer_id", found.getFirst(), "the tab's own columns first");
    assertTrue(found.contains("customers") && found.contains("CURRENT"), found.toString());
    assertTrue(s.suggest(orders, "SELECT").isEmpty(), "nothing for a word that is complete");
    assertEquals("localhost/shop/public/x", Workbench.resolve(orders.table, "x"));
    assertEquals("localhost/a/b/x", Workbench.resolve(orders.table, "a.b.x"));
  }

  private static Workbench.Node table(Workbench s, String name) {
    Workbench.Node shop = s.servers.getFirst().children.get(1);
    return shop.children.getFirst().children.stream()
        .filter(n -> n.name.equals(name)).findFirst().orElseThrow();
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

  // Tabs in windows of their own

  private static final String MOVE_ORDERS_OUT = """
      size 1000 600
      press #tab-1 right
      frame
      advance 0.25
      frame
      press role=menuitem label="Move to new window"
      frame
      """;

  /** The app of the window that the group of {@code tab} has, the first window's for main. */
  @SuppressWarnings("unchecked")
  /** The data grid of the active tab. */
  private static El grid(El el) {
    if ("grid".equals(el.role())) {
      return el;
    }
    for (El child : el.children()) {
      El found = grid(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static App<Workbench> windowOf(Workbench s, Workbench.Opened tab) {
    return s.appOf(s.groups.groupOf(tab));
  }

  /**
   * The window of a tab whose grid has a million rows still fits the window, with the status line
   * at its bottom: the column around the tabs takes no minimum height from the rows.
   */
  @Test
  void aTabWithAMillionRowsFitsAWindowOfItsOwn() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    assertEquals(1_000_000, orders.rows);
    Script.run(MOVE_ORDERS_OUT, app, out);
    App<Workbench> window = windowOf(s, orders);
    assertNotSame(app, window, "orders has a window of its own");
    Matcher status = Pattern.compile("text #status .* (\\d+),(\\d+) (\\d+)x(\\d+)\\n")
        .matcher(window.tree().dump());
    assertTrue(status.find(), window.tree().dump());
    float bottom = Integer.parseInt(status.group(2)) + Integer.parseInt(status.group(4));
    assertTrue(bottom <= window.tree().height(), "the status line ends at " + bottom
        + ", in a window " + window.tree().height() + " high");
  }

  /**
   * A table torn off into a window of its own runs its query there, and comes back into the first
   * window with its statement, caret, selection, history and the cursor of its grid in view.
   */
  @Test
  void aTabInAWindowOfItsOwnRunsThereAndComesBackAsItWas() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    Script.run("""
        size 1000 600
        down #tab-1
        move 380,60
        move 380,300
        frame
        up 380,300
        frame
        window orders
        press #query-localhost/shop/public/orders
        key End control
        key ArrowLeft
        type " -- here"
        key ArrowLeft shift
        key Enter control
        frame
        """, app, out);
    assertEquals(-1, orders.rows, "asked again");
    Script.run("size 1000 600\nawait\nframe", app, out);
    App<Workbench> torn = windowOf(s, orders);
    assertTrue(torn != app && s.groups.groups().size() == 2, s.groups.groups().toString());
    assertEquals(List.of(s.start), s.groups.main().tabs(), "Start alone in the first window");
    assertEquals(1_000_000, orders.rows, "the answer arrived");
    assertTrue(torn.tree().dump().contains("localhost/shop/public/orders · 1,000,000 rows"),
        "in the tab's window");
    String query = orders.query;
    int caret = orders.editor.caret();
    int anchor = orders.editor.anchor();
    assertTrue(query.endsWith(" -- here;") && caret == anchor - 1, query + caret + anchor);

    Script.run("""
        size 1000 600
        window orders
        press 300,300          # a cell of a million rows, and the last
        key End control
        frame
        press #tab-0 right
        frame
        advance 0.25
        frame
        press role=menuitem label="Move back to main window"
        frame
        window main
        advance 1              # past the fade of the toolbar and the query
        frame
        """, app, out);
    assertEquals(1, s.groups.groups().size(), "its window closed");
    assertEquals(List.of(s.start, orders), s.groups.main().tabs());
    assertEquals(1, s.groups.main().active());
    assertEquals(query, orders.query);
    assertEquals(caret, orders.editor.caret(), "the caret as it was");
    assertEquals(anchor, orders.editor.anchor(), "and the selection");
    assertEquals(999_999, orders.selection.row());
    assertTrue(find(app.tree(), "rows").scrollY() > 0, "the grid's cursor in view");
    Script.run("""
        size 1000 600
        focus #query-localhost/shop/public/orders
        key Z control
        frame
        """, app, out);
    assertFalse(orders.query.contains("here"), "undo took back the typing in the other window");
  }

  /**
   * A tab's grid keeps exactly where it was scrolled to, down and sideways, across another tab
   * in between and in a window of its own, also far from its cursor ({@code DataGrid.Selection}
   * holds it).
   */
  @Test
  void aTabsGridKeepsItsScrollAcrossSwitchesAndWindows() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    Script.run("size 1000 600\nwheel 600,400 0 12345\nwheel 600,400 37 0\nframe", app, out);
    El grid = grid(app.tree());
    double down = find(app.tree(), "rows").scrollY();
    double across = grid.scrollX();
    assertTrue(down == 12345 && across == 37, down + " " + across);

    Script.run("""
        size 1000 600
        press role=treeitem label="customers" clicks 2
        frame
        await
        frame
        advance 1
        frame
        press #tab-1
        frame
        advance 1
        frame
        """, app, out);
    assertEquals(down, find(app.tree(), "rows").scrollY(), "back where it was");
    assertEquals(across, grid(app.tree()).scrollX());

    Script.run("""
        size 1000 600
        down #tab-1
        move 380,60
        move 380,300
        frame
        up 380,300
        frame
        advance 1
        frame
        """, app, out);
    App<Workbench> torn = windowOf(s, orders);
    assertTrue(torn != app, "in a window of its own");
    assertEquals(down, find(torn.tree(), "rows").scrollY(), "where it was in the first window");
    assertEquals(across, grid(torn.tree()).scrollX());
    assertEquals(-1, orders.selection.row(), "far from a cursor, which it has none of");
  }

  /** An answer on its way when its tab moves to another window shows there. */
  @Test
  void anAnswerArrivesInTheWindowItsTabMovedTo() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    orders.query = "SELECT * FROM orders LIMIT 3;";
    Script.run("""
        size 1000 600
        frame
        focus #query-localhost/shop/public/orders
        key Enter control
        frame
        """ + MOVE_ORDERS_OUT, app, out);
    assertTrue(orders.asking != null && orders.rows < 0, "on its way while the tab moves");
    Script.run("size 1000 600\nawait\nframe", app, out);
    assertEquals(3, orders.rows);
    assertTrue(windowOf(s, orders).tree().dump().contains("orders · 3 rows"));
    assertFalse(app.tree().dump().contains("3 rows"), "not in the first window");
  }

  /** Its close button puts a window's tabs back; Control+W closes its last tab and it. */
  @Test
  void closingATabsWindowPutsItsTabsBackAndControlWClosesItsLastTab() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    orders.query = "SELECT * FROM orders LIMIT 3;";
    Script.run("""
        size 1000 600
        frame
        focus #query-localhost/shop/public/orders
        key Enter control
        frame
        """ + MOVE_ORDERS_OUT + "window orders\nclose\nframe\nwindow main", app, out);
    assertEquals(List.of(s.start, orders), s.groups.main().tabs(), "back in the first window");
    assertEquals(1, app.windows().all().size());
    assertTrue(orders.asking != null, "its question still on its way");
    Script.run("size 1000 600\nawait\nframe", app, out);
    assertEquals(3, orders.rows);

    Script.run("size 1000 600\nkey Enter control\nframe\n" + MOVE_ORDERS_OUT
        + "window orders\nkey W control\nframe\nawait\nframe\nwindow main", app, out);
    assertTrue(s.tables().isEmpty(), "closed");
    assertEquals(1, app.windows().all().size(), "with its window");
    assertEquals(-1, orders.rows, "its question given up");
    assertEquals(0, s.running());
  }

  /**
   * Opening a table from the tree that is open in another window chooses its tab there and
   * raises that window.
   */
  @Test
  void openingATableOpenElsewhereChoosesItsTabAndRaisesItsWindow() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS + """
        press role=treeitem label="customers" clicks 2
        frame
        await
        frame
        press #tab-1
        frame
        """, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    Workbench.Opened customers = s.tables().getLast();
    Script.run(MOVE_ORDERS_OUT, app, out);
    TabGroups.Group<Workbench.Opened> window = s.groups.groupOf(orders);
    s.groups.dock(s.groups.main(), 1, window, 1); // customers, active, beside orders
    app.windows().of(app).raise(); // the first window in front
    Script.run("""
        size 1000 600
        frame
        move screen 400,300    # in both windows: the first is in front
        frame
        """, app, out);
    assertFalse(windowOf(s, orders).tree().dump().contains("hovered"));
    Script.run("""
        size 1000 600
        press role=treeitem label="orders" clicks 2
        frame
        move screen 401,300
        frame
        """, app, out);
    assertEquals(List.of(orders, customers), window.tabs());
    assertEquals(0, window.active(), "orders chosen in its window");
    assertEquals(List.of(s.start), s.groups.main().tabs(), "and not opened again");
    assertTrue(windowOf(s, orders).tree().dump().contains("hovered"), "which is in front");
  }

  /** Closing the first window closes the others too and gives up the questions. */
  @Test
  void closingTheFirstWindowClosesThemAll() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    Script.run("size 1000 600\nfocus #query-localhost/shop/public/orders\nkey Enter control\n"
        + "frame\n" + MOVE_ORDERS_OUT + "window main\nclose\nframe", app, out);
    assertTrue(app.windows().all().isEmpty());
    assertTrue(orders.asking == null || orders.rows < 0);
    assertEquals(-1, orders.rows, "its question given up");
  }

  // Editing the rows

  /** Opens orders and customers, customers active, each with a change of its first email. */
  private App<Workbench> twoTablesChanged() throws IOException {
    App<Workbench> app = started();
    Script.run(OPEN_ORDERS + """
        press role=treeitem label="customers" clicks 2
        frame
        await
        frame
        advance 1
        frame
        """, app, out);
    for (Workbench.Opened o : app.state().tables()) {
      o.changes.set(0, 5, "x@example.com");
    }
    changed(app);
    Script.run("size 1000 600\nframe", app, out);
    return app;
  }

  /** The state changed outside a handler: a frame shows it, past the fade of what came. */
  private void changed(App<Workbench> app) throws IOException {
    app.invalidate();
    Script.run("size 1000 600\nframe\nadvance 1\nframe", app, out);
  }

  private static String dump(App<Workbench> app) {
    return app.tree().dump();
  }

  /** Edits wait in their tab until Commit, and the server refuses what breaks one of its rules. */
  @Test
  void commitWritesTheChangesOrTheServerSaysWhyNot() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    orders.changes.set(0, 3, "-1.00");
    orders.changes.set(1, 5, "ada@example.org");
    changed(app);
    Script.run("size 1000 600\nframe\nkey S control\nframe", app, out);
    assertTrue(orders.committing != null, "on its way");
    assertTrue(dump(app).contains("role=button label=\"Commit (2)\""), dump(app));
    assertTrue(dump(app).contains("busy"), "the button is busy");
    Script.run("size 1000 600\nawait\nframe", app, out);
    assertEquals(2, orders.changes.count(), "refused: nothing written");
    assertEquals("amount must not be negative", orders.changes.errorText(0, 3));
    assertEquals("orders: amount must not be negative", s.message);
    assertTrue(dump(app).contains("2 changes"), "in the status bar");

    orders.changes.set(0, 3, "1.00"); // fixed, which takes the error away
    assertNull(orders.changes.errorText(0, 3));
    changed(app);
    Script.run("size 1000 600\npress #commit\nframe\nawait\nframe\nadvance 1\nframe", app, out);
    assertTrue(orders.changes.isEmpty(), "written");
    assertEquals("1.00", orders.changes.cell(0, 3), "as the server has it now");
    assertEquals("ada@example.org", orders.changes.cell(1, 5));
    assertEquals("orders: 2 changes committed", s.message);
    assertFalse(dump(app).contains("#commit"), "Commit shows with changes only");
    assertFalse(dump(app).contains("#changes"));

    Script.run("size 1000 600\nkey N control\nframe\nawait\nframe", app, out);
    assertEquals("1.00", s.tables().get(1).changes.cell(0, 3),
        "another tab of the table shows what was written");
  }

  /**
   * Control+S and closing a tab take the value of an open editor first, so that nothing typed is
   * lost, and a value the grid refuses keeps its editor open and stops them.
   */
  @Test
  void aValueBeingEditedIsTakenFirstAndARefusedOneStopsWhatWasAsked() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    Script.run("size 1000 600\npress #cell-0-5\nkey Enter\nframe\nkey A control\n"
        + "type \"x@example.com\"\nframe\nkey S control\nframe", app, out);
    assertFalse(orders.selection.isEditing());
    assertNotNull(orders.committing, "on its way, with what was typed");
    Script.run("size 1000 600\nawait\nframe", app, out);
    assertEquals("x@example.com", s.stored.get("localhost/shop/public/orders").value(0, 5));

    // A day that does not exist: the editor stays with its message, and nothing goes on.
    Script.run("size 1000 600\nkey ArrowRight\nkey ArrowRight\nframe\n"
        + "type \"2026-02-30 10:00\"\nframe\nkey S control\nframe", app, out);
    assertTrue(orders.selection.isEditing());
    assertEquals("2026-02-30 10:00", orders.selection.draft());
    assertNull(orders.committing);
    assertTrue(dump(app).contains("role=alert"), dump(app));
    Script.run("size 1000 600\nkey W control\nframe\nadvance 0.25\nframe", app, out);
    assertEquals(List.of(orders), s.tables(), "the tab stays");
    assertNull(s.question);
    assertTrue(orders.selection.isEditing());

    // A value that is taken becomes a change, which closing the tab asks about.
    Script.run("size 1000 600\nkey Escape\nframe\ntype \"2026-02-28 10:00\"\nframe\n"
        + "key W control\nframe\nadvance 0.25\nframe", app, out);
    assertFalse(orders.selection.isEditing());
    assertEquals(1, orders.changes.count());
    assertNotNull(s.question, "asked whether to commit or discard it");
    assertTrue(dump(app).contains("role=dialog label=\"Changes not committed\""), dump(app));
  }

  /** Closing a tab with changes asks, and each of the dialog's answers does what it says. */
  @Test
  void closingATabWithChangesAsksWhetherToCommitOrDiscard() throws IOException {
    App<Workbench> app = twoTablesChanged();
    Workbench s = app.state();
    Workbench.Opened orders = s.tables().getFirst();
    String close = "size 1000 600\npress #close-1\nframe\nadvance 0.25\nframe\n";
    Script.run("size 1000 600\npress #tab-1\nframe\n" + close, app, out);
    assertTrue(dump(app).contains("role=dialog label=\"Changes not committed\""), dump(app));
    assertTrue(dump(app).contains("orders has 1 change that the server does not have yet."));
    Script.run("size 1000 600\npress #keep\nframe", app, out);
    assertEquals(2, s.tables().size(), "Cancel keeps the tab");
    assertEquals(1, orders.changes.count(), "and its changes");

    Script.run(close + "press #commit-changes\nframe\nawait\nframe", app, out);
    assertEquals(1, s.tables().size(), "closed once written");
    assertEquals("x@example.com", s.stored.get("localhost/shop/public/orders").value(0, 5));

    Script.run(close + "press #discard\nframe", app, out);
    assertTrue(s.tables().isEmpty(), "customers closed");
    assertNull(s.stored.get("localhost/shop/public/customers").written.get(0L),
        "without its change");
  }

  /** Run asks about the changes of its tab, and closing the first window about every tab's. */
  @Test
  void runAndExitAskAboutTheChangesFirst() throws IOException {
    App<Workbench> app = twoTablesChanged();
    Workbench s = app.state();
    Workbench.Opened customers = s.tables().get(1);
    Script.run("size 1000 600\npress #run\nframe\nadvance 0.25\nframe", app, out);
    assertEquals(List.of(customers), s.question.tabs());
    assertEquals(customers.answered, customers.rows, "not asked yet");
    Script.run("size 1000 600\npress #discard\nframe", app, out);
    assertTrue(customers.changes.isEmpty());
    assertEquals(-1, customers.rows, "asked once discarded");
    Script.run("size 1000 600\nawait\nframe", app, out);

    customers.changes.set(0, 5, "y@example.com");
    changed(app);
    Script.run("size 1000 600\nframe\nclose\nframe\nadvance 0.25\nframe", app, out);
    assertTrue(dump(app).contains("orders (1), customers (1) have changes"), dump(app));
    Script.run("size 1000 600\npress #keep\nframe\nclose\nframe\nadvance 0.25\nframe\n"
        + "press #commit-changes\nframe", app, out);
    assertFalse(app.windows().all().isEmpty(), "open until the server took them");
    Script.run("size 1000 600\nawait\nframe", app, out);
    assertTrue(app.windows().all().isEmpty(), "closed once both were written");
  }

  /** A tab's changes go with it to a window of its own and back, and the status bar counts them. */
  @Test
  void aTabTakesItsChangesToAnotherWindowAndBack() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    Script.run("size 1000 600\npress #cell-0-5\nkey Enter\nframe\nkey A control\n"
        + "type \"x@example.com\"\nkey Enter\nframe", app, out);
    assertEquals(1, orders.changes.count());
    Script.run(MOVE_ORDERS_OUT, app, out);
    App<Workbench> window = windowOf(s, orders);
    assertNotSame(app, window);
    Script.run("size 1000 600\nwindow orders\nframe", app, out);
    assertTrue(dump(window).contains("label=\"Commit (1)\""), dump(window));
    assertTrue(dump(app).contains("\"1 change\""), "the first window's status bar counts it");
    Script.run("""
        size 1000 600
        window orders
        press #tab-0 right
        frame
        advance 0.25
        frame
        press role=menuitem label="Move back to main window"
        frame
        window main
        advance 1
        frame
        """, app, out);
    assertEquals(List.of(s.start, orders), s.groups.main().tabs());
    assertEquals("x@example.com", orders.changes.cell(0, 5));
    assertTrue(dump(app).contains("label=\"Commit (1)\""));
  }

  /** Undo of the Edit menu acts on the grid's changes while the grid had the focus last. */
  @Test
  void editActsOnTheGridWhileItHasTheFocus() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    String query = orders.query;
    Script.run("size 1000 600\npress #cell-0-5\nkey Delete control\nframe\nframe", app, out);
    assertEquals(1, orders.changes.count(), "the row marked to be deleted");
    Script.run("""
        size 1000 600
        press role=menuitem label="Edit"
        frame
        advance 0.25
        frame
        press role=menuitem label="Undo"
        frame
        """, app, out);
    assertTrue(orders.changes.isEmpty(), "undone in the grid");
    assertEquals(query, orders.query, "not in the query");
  }

  /** The status bar's count of changes goes to the next tab that has some. */
  @Test
  void theCountOfChangesGoesToTheNextTabWithChanges() throws IOException {
    App<Workbench> app = twoTablesChanged();
    Workbench s = app.state();
    s.tables().get(1).changes.clear();
    changed(app);
    Script.run("size 1000 600\nframe\npress #changes\nframe", app, out);
    assertEquals(1, s.groups.main().active(), "orders, which has the change");
  }

  /** The filters of the columns keep the rows by arithmetic, and show as badges. */
  @Test
  void theColumnsFiltersKeepTheirRows() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    orders.filter(List.of(DataGrid.Filter.oneOf(2, List.of("pending"))));
    changed(app);
    Script.run("size 1000 600\nframe", app, out);
    assertTrue(dump(app).contains("\"250,000 rows\""), dump(app));
    assertTrue(dump(app).contains("Remove status"), "its badge under the toolbar");
    assertEquals("pending", orders.changes.cell(0, 2));
  }

  @Test
  void closingATabCancelsTheQuestionOfItsTable() throws IOException {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
    Workbench s = new Workbench();
    s.latency = Duration.ZERO; // answers at once, which the close has to stop
    App<Workbench> app = new App<>(s, Workbench::view);
    s.app = app;
    app.textRendering(TextRendering.LINUX);
    Script.run("""
        size 1000 600
        frame
        press role=treeitem label="customers" clicks 2
        frame
        """, app, out);
    Workbench.Opened customers = s.tables().getFirst();
    Script.run("size 1000 600\nkey W control\nframe\nawait\nframe", app, out);
    assertTrue(s.tables().isEmpty());
    assertEquals(-1, customers.rows, "its answer never arrived");
    assertEquals(0, s.groups.main().active());
    assertNull(s.message);
  }
}
