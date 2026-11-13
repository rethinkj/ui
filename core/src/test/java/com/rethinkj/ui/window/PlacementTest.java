package com.rethinkj.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** A window's placement as one line of text and back; needs no window. */
class PlacementTest {

  @Test
  void aPlacementPrintsItselfAsOneLineAndReadsItBack() {
    Window.Placement p = new Window.Placement(120, 80, 1200, 720, true, "DELL U2720Q");
    assertEquals("120,80 1200x720 maximized display \"DELL U2720Q\"", p.toString());
    assertEquals(p, Window.Placement.parse(p.toString()));

    Window.Placement left = new Window.Placement(-1910, -12, 640.5f, 400, false, null);
    assertEquals("-1910,-12 640.5x400", left.toString(), "a display left of the primary one");
    assertEquals(left, Window.Placement.parse(left.toString()));

    Window.Placement odd = new Window.Placement(0, 0, 800, 600, false, "\\\\.\\DISPLAY1 \"2\"");
    assertEquals("0,0 800x600 display \"\\\\\\\\.\\\\DISPLAY1 \\\"2\\\"\"", odd.toString());
    assertEquals(odd, Window.Placement.parse(odd.toString() + "\n"), "a line from a file");
  }

  @Test
  void whatIsNoPlacementIsRefusedWithWhatOneLooksLike() {
    for (String line : new String[] {
      "",
      "120,80",
      "120,80 0x720",
      "a,b 1x1",
      "120,80 1200x720 minimized",
      "120,80 1200x720 display \"open"
    }) {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> Window.Placement.parse(line), line);
      assertEquals(
          true,
          e.getMessage().startsWith("Not a window's placement")
              || e.getMessage().startsWith("Size "),
          e.getMessage());
    }
  }
}
