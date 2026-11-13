package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.net.ConnectException;
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
    Workbench.Opened orders = s.tables.getFirst();
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
    assertEquals(2, s.active);
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
    Workbench.Opened orders = s.tables.getFirst();
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
    Workbench.Opened orders = s.tables.getFirst();
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
    Workbench.Opened customers = s.tables.getFirst();
    Script.run("size 1000 600\nkey W control\nframe\nawait\nframe", app, out);
    assertTrue(s.tables.isEmpty());
    assertEquals(-1, customers.rows, "its answer never arrived");
    assertEquals(0, s.active);
    assertNull(s.message);
  }
}
