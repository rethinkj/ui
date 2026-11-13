package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.motion.Spring;

/** Views shared by tests. */
public final class Examples {

  private Examples() {}

  /** The state of the switch example. */
  public static final class Lights {
    public boolean on;
  }

  /** The switch as an app: pressing it toggles the state. */
  public static El lights(Lights state) {
    return lightSwitch(state.on, () -> state.on = !state.on);
  }

  static El lightSwitch(boolean on) {
    return lightSwitch(on, null);
  }

  /** The switch from rune's README, without its text label. */
  static El lightSwitch(boolean on, Runnable onPress) {
    El knob =
        el().key("knob")
            .size(18, 18)
            .radius(9)
            .fill(0xFFFFFFFF)
            .absolute(on ? 18 : 2, 2)
            .motion(Spring.SNAP);
    El toggle =
        el().key("switch")
            .role("switch")
            .label("Lights")
            .size(38, 22)
            .radius(11)
            .fill(on ? 0xFF3B82F6 : 0xFF3A3A40)
            .onPress(onPress)
            .motion(Spring.SNAP)
            .child(knob);
    return col().wFull().hFull().center().gap(16).fill(0xFF101014).child(toggle);
  }
}
