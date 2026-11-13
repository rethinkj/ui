package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.motion.Spring;
import java.io.IOException;

/** Views shared by tests. */
final class Examples {

  private Examples() {}

  /** The switch as a program: {@code --script <file> --out <folder>} runs a script against it. */
  public static void main(String[] args) throws IOException {
    if (!Script.handle(args, new App<>(new Lights(), Examples::lights))) {
      System.err.println("There is no window yet. Run with --script <file> --out <folder>.");
    }
  }

  /** The state of the switch example. */
  static final class Lights {
    boolean on;
  }

  /** The switch as an app: pressing it toggles the state. */
  static El lights(Lights state) {
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
