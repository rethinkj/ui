package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;

/**
 * The light switch of rune's README in a frameless window with a title bar of its own. A press on
 * the switch toggles the lamp above it; so does Space once Tab has given the switch the focus.
 */
public final class LightSwitch {

  private static final int GLYPH = 0xFFE6E8EE;

  boolean on;

  public static void main(String[] args) throws IOException {
    App<LightSwitch> app = new App<>(new LightSwitch(), LightSwitch::view);
    if (Script.handle(args, app)) {
      return;
    }
    Window.Options options = Window.options().frameless().minimumSize(240, 160);
    try (Window window = Window.open("Lights", 360, 240, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  static El view(LightSwitch s) {
    // No text yet: the lamp says what the switch does.
    El lamp = el().key("lamp").size(56, 56).radius(28).fill(s.on ? 0xFFFACC15 : 0xFF2A2A30)
        .motion(Spring.SNAP);
    El knob = el().key("knob").size(18, 18).radius(9).fill(0xFFFFFFFF)
        .absolute(s.on ? 18 : 2, 2).motion(Spring.SNAP);
    El lightSwitch = el().key("switch").role("switch").label("Lights").focusable()
        .size(38, 22).radius(11).fill(s.on ? 0xFF3B82F6 : 0xFF3A3A40).motion(Spring.SNAP)
        .onPress(() -> s.on = !s.on)
        .child(knob);
    return col().wFull().hFull().fill(0xFF101014)
        .child(titleBar())
        .child(col().grow(1).center().gap(16).child(lamp).child(lightSwitch));
  }

  /** The system moves the window by the bar and acts on its buttons. */
  static El titleBar() {
    return row().wFull().h(32).shrink(0).align(Style.Align.CENTER).fill(0xFF1A1A20)
        .windowPart(WindowPart.DRAG)
        .child(el().size(12, 12).radius(6).fill(0xFFFACC15).margin(0, 0, 0, 10))
        .child(el().grow(1))
        .child(button(WindowPart.MINIMIZE, "Minimize", el().size(10, 1).fill(GLYPH)))
        .child(button(WindowPart.MAXIMIZE, "Maximize", el().size(10, 10).border(1, GLYPH)))
        .child(button(WindowPart.CLOSE, "Close", cross()));
  }

  static El button(WindowPart part, String label, El glyph) {
    return el().role("button").label(label).size(46, 32).center().windowPart(part).child(glyph);
  }

  /** An X, from two diagonals of small squares. */
  static El cross() {
    El cross = el().size(10, 10);
    for (int i = 0; i <= 8; i++) {
      cross.child(el().size(2, 2).absolute(i, i).fill(GLYPH))
          .child(el().size(2, 2).absolute(8 - i, i).fill(GLYPH));
    }
    return cross;
  }
}
