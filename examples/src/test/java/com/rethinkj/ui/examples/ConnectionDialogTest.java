package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    // The password shows as bullets, and nowhere as itself until its button shows it.
    for (String snap : new String[] {"connection-filled", "connection-connecting"}) {
      String tree = Files.readString(Path.of("target", "connection-dialog", snap + ".tree.txt"));
      assertFalse(tree.contains("hunter2"), snap);
      assertTrue(tree.contains("\"" + "\u2022".repeat(7) + "\" 95,223 260x24 password"), tree);
    }
    assertTrue(Snaps.line("connection-dialog", "connection-connecting", "text \"Connecting")
        .contains("as admin with SSL\""));
    assertTrue(
        Snaps.line("connection-dialog", "connection-shown", "#password").contains("\"hunter2\""));
    assertTrue(
        Snaps.line("connection-dialog", "connection-shown", "#port").endsWith("focused caret 4"),
        "the button left the focus where it was");
  }
}
