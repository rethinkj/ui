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
        .contains("localhost/shop/public/orders · 1,000,000 rows · row 1,000,000 selected"),
        "the message went after five seconds");
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
