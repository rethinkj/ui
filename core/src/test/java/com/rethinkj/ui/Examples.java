package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.motion.Spring;
import java.io.IOException;

/** Views shared by tests. */
public final class Examples {

  private Examples() {}

  /** The switch as a program: {@code --script <file> --out <folder>} runs a script against it. */
  public static void main(String[] args) throws IOException {
    if (!Script.handle(args, new App<>(new Lights(), Examples::lights))) {
      System.err.println("There is no window yet. Run with --script <file> --out <folder>.");
    }
  }

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
    return col().wFull().hFull().center().gap(16).fill(0xFF101014).child(toggle(on, onPress));
  }

  /** The switch with a badge beside it that is there while the lights are on. */
  static El badged(Lights state) {
    El root =
        row().wFull()
            .hFull()
            .center()
            .gap(12)
            .fill(0xFF101014)
            .child(toggle(state.on, () -> state.on = !state.on));
    if (state.on) {
      root.child(
          el().key("badge")
              .role("status")
              .label("On")
              .size(12, 12)
              .radius(6)
              .fill(0xFF22C55E)
              .motion(Spring.SNAP));
    }
    return root;
  }

  private static El toggle(boolean on, Runnable onPress) {
    El knob =
        el().key("knob")
            .size(18, 18)
            .radius(9)
            .fill(0xFFFFFFFF)
            .absolute(on ? 18 : 2, 2)
            .motion(Spring.SNAP);
    return el().key("switch")
        .role("switch")
        .label("Lights")
        .size(38, 22)
        .radius(11)
        .fill(on ? 0xFF3B82F6 : 0xFF3A3A40)
        .onPress(onPress)
        .motion(Spring.SNAP)
        .child(knob);
  }
}
