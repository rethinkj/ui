package com.rethinkj.ui.examples;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class WorkbenchTest {

  /** Runs the example's {@code main} on {@code workbench.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("workbench", Workbench::main);
  }
}
