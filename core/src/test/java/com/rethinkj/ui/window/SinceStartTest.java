package com.rethinkj.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The time since the process started that the first frame's log line gives; needs no window. */
class SinceStartTest {

  @Test
  void readsTheStartFromProcEvenWhenTheNameHoldsSpacesAndParentheses() {
    String stat = "4242 (java (main) x) S 1 2 3 0 -1 4194560 100 0 0 0 5 6 0 0 20 0 30 0 12345"
        + " 1000000 250 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0\n";
    // Booted 200.50 s ago, started 123.45 s after the boot.
    assertEquals(77_050, WindowLoop.sinceStart(stat, "200.50 1000.00\n"));
  }
}
