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
    assertTrue(Snaps.line("connection-dialog", "connection-connecting", "text \"Connecting")
        .contains("as admin with SSL\""));
    // The password shows as bullets in the tree, also once its button shows it on the screen.
    assertTrue(Snaps.line("connection-dialog", "connection-shown", "#password")
        .contains("\"" + "•".repeat(7) + "\" 95,247 324x24 password revealed"));
    assertTrue(
        Snaps.line("connection-dialog", "connection-connecting", "label=\"Connect\"")
            .endsWith(" disabled"),
        "nothing to connect with until the profile changes");
    // The editing's errors show at their fields: the server's refusal, then a rule.
    assertTrue(Snaps.line("connection-dialog", "connection-refused", "#port")
        .contains(" invalid error=\"No server answers on port 5433\""));
    assertTrue(Snaps.line("connection-dialog", "connection-refused", "label=\"Connect\"")
        .endsWith(" disabled"));
    assertFalse(Snaps.line("connection-dialog", "connection-rule", "#port").contains("invalid"));
    assertTrue(Snaps.line("connection-dialog", "connection-rule", "#user")
        .contains(" invalid error=\"postgres connects only to localhost\""));
  }
}
