package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.motion.Spring;
import java.io.IOException;
import java.util.Arrays;

/** Views shared by tests. */
public final class Examples {

  private Examples() {}

  /**
   * The switch as a program: {@code --script <file> --out <folder>} runs a script against it, and
   * with {@code list} before that against the list.
   */
  public static void main(String[] args) throws IOException {
    boolean list = args.length > 0 && args[0].equals("list");
    App<?> app =
        list ? new App<>(new Object(), s -> list()) : new App<>(new Lights(), Examples::lights);
    if (!Script.handle(list ? Arrays.copyOfRange(args, 1, args.length) : args, app)) {
      System.err.println("There is no window yet. Run with --script <file> --out <folder>.");
    }
  }

  /** Twenty rows in a rounded scroll area, more than fit into it. */
  public static El list() {
    El list =
        col().key("list").role("list").scroll().grow(1).padding(8).gap(4).radius(12)
            .fill(0xFF1C1C22);
    for (int i = 1; i <= 20; i++) {
      list.child(
          row().key("row-" + i)
              .role("listitem")
              .label("Row " + i)
              .h(28)
              .padding(0, 8)
              .gap(8)
              .align(Style.Align.CENTER)
              .radius(6)
              .fill(i % 2 == 0 ? 0xFF26262E : 0xFF2E2E38)
              .children(
                  el().size(14, 14).radius(7).fill(i % 3 == 0 ? 0xFF22C55E : 0xFF3B82F6),
                  el().size(40 + i * 37 % 90, 6).radius(3).fill(0xFF8A8A99)));
    }
    return col().wFull().hFull().padding(16).fill(0xFF101014).child(list);
  }

  /** The state of the switch example. */
  public static final class Lights {
    public boolean on;
  }

  /** The switch as an app: pressing it toggles the state. */
  public static El lights(Lights state) {
    return lightSwitch(state.on, () -> state.on = !state.on);
  }

  public static El lightSwitch(boolean on) {
    return lightSwitch(on, null);
  }

  /** The switch from rune's README, without its text label. */
  static El lightSwitch(boolean on, Runnable onPress) {
    return col().wFull().hFull().center().gap(16).fill(0xFF101014).child(toggle(on, onPress));
  }

  /** The switch with a badge beside it that is there while the lights are on. */
  public static El badged(Lights state) {
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
        .focusable()
        .onPress(onPress)
        .motion(Spring.SNAP)
        .child(knob);
  }
}
