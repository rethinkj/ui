package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        .endsWith("\"localhost/shop/public/orders · 1,000,000 rows\" 289,569 286x16"),
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
  private static App<Workbench> windowOf(Workbench s, Workbench.Opened tab) {
    return s.appOf(s.groups.groupOf(tab));
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
        press 300,300          # a row of a million, and the grid's last
        key End
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
