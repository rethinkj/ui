package com.rethinkj.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.Key;
import com.rethinkj.ui.window.WindowEvent.Wheel;
import org.junit.jupiter.api.Test;

/** What the window makes of the wheel's steps; needs no window. */
class WheelTest {

  private static final int SHIFT = Key.SHIFT;

  @Test
  void shiftTurnsAWheelThatTurnsUpAndDownSideways() {
    assertEquals(
        new Wheel(1, 0, 10, 20, SHIFT), Window.wheel(0, -1, 10, 20, SHIFT), "towards the user");
    assertEquals(new Wheel(-2, 0, 10, 20, SHIFT), Window.wheel(0, 2, 10, 20, SHIFT));
    assertEquals(new Wheel(0, -1, 10, 20), Window.wheel(0, -1, 10, 20, 0), "without Shift");
    assertEquals(
        new Wheel(1.5f, -1, 10, 20, SHIFT),
        Window.wheel(1.5f, -1, 10, 20, SHIFT),
        "a touchpad that scrolls both ways keeps its axes");
    assertEquals(
        new Wheel(-3, 0, 10, 20, SHIFT), Window.wheel(-3, 0, 10, 20, SHIFT), "already sideways");
  }

  @Test
  void theWheelCarriesItsModifiers() {
    int held = Key.CONTROL | Key.ALT;
    assertEquals(new Wheel(0, 1, 10, 20, held), Window.wheel(0, 1, 10, 20, held));
  }
}
