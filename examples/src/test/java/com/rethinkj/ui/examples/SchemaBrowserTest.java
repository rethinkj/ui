package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaBrowserTest {

  /** Runs the example's {@code main} on {@code schema-browser.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("schema-browser", SchemaBrowser::main);
  }

  @Test
  void theServersAnswerAsTheTreeAsks() throws Exception {
    assertEquals(List.of("analytics", "postgres", "shop"),
        SchemaBrowser.children("localhost", 1, Duration.ZERO));
    assertEquals(SchemaBrowser.columns("orders"),
        SchemaBrowser.children("localhost/shop/public/orders", 4, Duration.ZERO));
    SchemaBrowser.Rows rows = SchemaBrowser.query("staging/shop/public/orders", Duration.ZERO);
    assertEquals(SchemaBrowser.ROWS, rows.rows().size());
    assertEquals(rows.columns().size(), rows.rows().getFirst().length);
  }
}
