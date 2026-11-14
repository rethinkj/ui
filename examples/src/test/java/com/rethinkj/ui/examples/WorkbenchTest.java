package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchTest {

  @TempDir
  Path out;

  /** Runs the example's {@code main} on {@code workbench.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("workbench", Workbench::main);
    assertTrue(
        Snaps.line("workbench", "workbench-completion", "#option-0")
                .contains("label=\"customer_id\"")
            && Snaps.line("workbench", "workbench-completion", "#option-0").contains(" selected"),
        "the completions at the caret");
    assertTrue(
        Snaps.line("workbench", "workbench-completion", "#position").contains("\"Ln 3, Col 9\""));
    assertTrue(
        Snaps.line("workbench", "orders-workbench-torn", "#status")
            .contains("\"localhost/shop/public/orders · 5 rows\""),
        "the answer in the torn window");
  }

  @Test
  void theServersAnswerAsTheTreeAsks() throws Exception {
    assertEquals(List.of("scratch", "shop"), Workbench.connect("staging", Duration.ZERO));
    assertThrows(ConnectException.class, () -> Workbench.connect("prod-eu", Duration.ZERO));
    assertEquals(1_000_000, Workbench.count("localhost/shop/public/orders", Duration.ZERO));
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

  /** Control+P finds a table after @ and opens it. */
  @Test
  void thePaletteOpensTables() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run("size 1000 600\nframe\nkey P control\nframe\ntype \"cust\"\nframe", app, out);
    assertEquals("@cust", s.palette(s.groups.main()).text());
    assertTrue(app.tree().dump().contains("label=\"customers\""), "found among the tables");
    Script.run("size 1000 600\nkey Enter\nframe\nawait\nframe\nadvance 1\nframe", app, out);
    assertEquals("customers", s.groups.main().activeTab().title(), "the best match opened");
    assertFalse(s.palette(s.groups.main()).isOpen());
  }

  @Test
  void aStatementAboutATableTheServerLacksUnderlinesItsName() throws IOException {
    App<Workbench> app = started();
    Workbench s = app.state();
    Script.run(OPEN_ORDERS, app, out);
    Workbench.Opened orders = s.tables().getFirst();
    orders.query = "SELECT * FROM shop.public.ordrs";
    Script.run(
        "size 1000 600\nframe\nfocus #query-localhost/shop/public/orders\n"
            + "key Enter control\nframe\nawait\nframe",
        app,
        out);
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
    Script.run(
        "size 1000 600\nframe\nfocus #query-localhost/shop/public/orders\n"
            + "key Enter control\nframe\nkey Enter control\nframe\nawait\nframe",
        app,
        out);
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
        .filter(n -> n.name.equals(name))
        .findFirst()
        .orElseThrow();
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
  private static App<Workbench> windowOf(Workbench s, Workbench.Opened tab) {
    return s.appOf(s.groups.groupOf(tab));
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
    Script.run(
        "size 1000 600\nfocus #query-localhost/shop/public/orders\nkey Enter control\n" + "frame\n"
            + MOVE_ORDERS_OUT + "window main\nclose\nframe",
        app,
        out);
    assertTrue(app.windows().all().isEmpty());
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
    assertEquals(
        "1.00",
        s.tables().get(1).changes.cell(0, 3),
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
    Script.run(
        "size 1000 600\npress #cell-0-5\nkey Enter\nframe\nkey A control\n"
            + "type \"x@example.com\"\nframe\nkey S control\nframe",
        app,
        out);
    assertFalse(orders.selection.isEditing());
    assertNotNull(orders.committing, "on its way, with what was typed");
    Script.run("size 1000 600\nawait\nframe", app, out);
    assertEquals("x@example.com", s.stored.get("localhost/shop/public/orders").value(0, 5));

    // A day that does not exist: the editor stays with its message, and nothing goes on.
    Script.run(
        "size 1000 600\nkey ArrowRight\nkey ArrowRight\nframe\n"
            + "type \"2026-02-30 10:00\"\nframe\nkey S control\nframe",
        app,
        out);
    assertTrue(orders.selection.isEditing());
    assertEquals("2026-02-30 10:00", orders.selection.draft());
    assertNull(orders.committing);
    assertTrue(dump(app).contains("role=alert"), dump(app));
    Script.run("size 1000 600\nkey W control\nframe\nadvance 0.25\nframe", app, out);
    assertEquals(List.of(orders), s.tables(), "the tab stays");
    assertNull(s.question);
    assertTrue(orders.selection.isEditing());

    // A value that is taken becomes a change, which closing the tab asks about.
    Script.run(
        "size 1000 600\nkey Escape\nframe\ntype \"2026-02-28 10:00\"\nframe\n"
            + "key W control\nframe\nadvance 0.25\nframe",
        app,
        out);
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
    assertNull(
        s.stored.get("localhost/shop/public/customers").written.get(0L), "without its change");
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
    Script.run(
        "size 1000 600\npress #keep\nframe\nclose\nframe\nadvance 0.25\nframe\n"
            + "press #commit-changes\nframe",
        app,
        out);
    assertFalse(app.windows().all().isEmpty(), "open until the server took them");
    Script.run("size 1000 600\nawait\nframe", app, out);
    assertTrue(app.windows().all().isEmpty(), "closed once both were written");
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

  @Test
  void openingEditWithThePointerCommitsAValidDraftAndKeepsARefusedOne() throws IOException {
    App<Workbench> app = started();
    Script.run(OPEN_ORDERS + """
        press #cell-0-5
        key F2
        frame
        key A control
        type "draft@example.com"
        frame
        """, app, out);
    Workbench.Opened orders = app.state().tables().getFirst();
    assertTrue(orders.selection.isEditing());
    assertEquals("draft@example.com", orders.selection.draft());
    assertTrue(orders.changes.isEmpty(), "still a draft");
    app.clipboard().write("keep");

    String openEdit = """
        size 1000 600
        press role=menuitem label="Edit"
        frame
        advance 0.25
        frame
        """;
    Script.run(openEdit, app, out);
    assertTrue(app.state().bar.menu().isOpen());
    assertFalse(orders.selection.isEditing(), "opening the menu is a press outside the editor");
    assertEquals("draft@example.com", orders.changes.cell(0, 5));
    assertEquals("keep", app.clipboard().read(), "opening Edit runs no grid command");
    Script.run("size 1000 600\npress role=menuitem label=\"Copy\"\nframe", app, out);
    assertEquals("draft@example.com", app.clipboard().read());

    Script.run("""
        size 1000 600
        press #cell-0-3
        key F2
        frame
        key A control
        key Backspace
        frame
        """, app, out);
    String amount = orders.changes.cell(0, 3);
    assertTrue(orders.selection.isEditing());
    assertEquals("", orders.selection.draft());
    Script.run(openEdit, app, out);
    assertTrue(app.state().bar.menu().isOpen());
    assertTrue(orders.selection.isEditing(), "a refused value stays in its editor");
    assertEquals("", orders.selection.draft());
    assertEquals(amount, orders.changes.cell(0, 3), "the refused draft was not written");
    assertEquals(1, orders.changes.count(), "only the valid email changed");
    assertTrue(dump(app).contains("role=alert"), dump(app));
    for (String label : List.of("Copy", "Paste", "Select all")) {
      assertTrue(
          dump(app)
              .lines()
              .anyMatch(line -> line.contains("role=menuitem label=\"" + label + "\"")
                  && line.contains("disabled")),
          dump(app));
    }
    Script.run("size 1000 600\nkey Escape\nframe", app, out);
    assertEquals("field", app.focused().key(), "closing Edit restores the refused editor's focus");
    assertEquals("", orders.selection.draft());
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

  @Test
  void closingATabCancelsTheQuestionOfItsTable() throws IOException {
    App<Workbench> app = started(); // answers at once, which the close has to stop
    Workbench s = app.state();
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
