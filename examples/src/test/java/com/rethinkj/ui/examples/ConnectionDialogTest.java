package com.rethinkj.ui.examples;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ConnectionDialogTest {

  /** Runs the example's {@code main} on {@code connection-dialog.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("connection-dialog", ConnectionDialog::main);
  }
}
