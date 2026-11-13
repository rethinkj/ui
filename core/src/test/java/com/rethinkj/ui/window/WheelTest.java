package com.rethinkj.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.window.WindowEvent.Wheel;
import org.junit.jupiter.api.Test;

/** What the window makes of the wheel's steps; needs no window. */
class WheelTest {

  @Test
  void shiftTurnsAWheelThatTurnsUpAndDownSideways() {
    assertEquals(new Wheel(1, 0, 10, 20), Window.wheel(0, -1, 10, 20, true), "towards the user");
    assertEquals(new Wheel(-2, 0, 10, 20), Window.wheel(0, 2, 10, 20, true));
    assertEquals(new Wheel(0, -1, 10, 20), Window.wheel(0, -1, 10, 20, false), "without Shift");
    assertEquals(new Wheel(1.5f, -1, 10, 20), Window.wheel(1.5f, -1, 10, 20, true),
        "a touchpad that scrolls both ways keeps its axes");
    assertEquals(new Wheel(-3, 0, 10, 20), Window.wheel(-3, 0, 10, 20, true), "already sideways");
  }
}
