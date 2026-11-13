package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class LightSwitchTest {

  /** Runs the example's {@code main} on {@code light-switch.txt}; see {@link Snaps#check}. */
  @Test
  void theScriptShowsTheStoredFrames() throws IOException {
    Snaps.check("light-switch", LightSwitch::main);
    assertTrue(Snaps.line("light-switch", "light-switch-off", "role=switch")
        .endsWith(" checked=false"));
    assertTrue(Snaps.line("light-switch", "light-switch-on", "role=switch")
        .endsWith(" checked"), "a press leaves the focus where it was: nowhere");
  }
}
