package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ConnectionDialogTest {

  /** Runs the example's {@code main} on {@code connection-dialog.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("connection-dialog", ConnectionDialog::main);
    // What the images show by colour, the trees say in words.
    assertTrue(Snaps.line("connection-dialog", "connection-empty", "label=\"Connect\"")
        .endsWith(" disabled"));
    assertFalse(Snaps.line("connection-dialog", "connection-filled", "label=\"Connect\"")
        .contains(" disabled"));
    assertTrue(Snaps.line("connection-dialog", "connection-empty", "label=\"Require SSL\"")
        .endsWith(" checked"));
    assertTrue(Snaps.line("connection-dialog", "connection-light", "label=\"Dark theme\"")
        .endsWith(" checked=false"));
  }
}
