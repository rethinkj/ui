package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
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
