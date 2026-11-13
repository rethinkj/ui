package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;

/**
 * The light switch of rune's README in a frameless window with a title bar of its own, built from
 * the kit's toggle and label in its dark theme. A press on the switch or its text toggles the lamp
 * above it; so does Space once Tab has given the switch the focus.
 */
public final class LightSwitch {

  private static final Theme THEME = Theme.DARK;
  private static final int LAMP = 0xFFFACC15;

  boolean on;

  public static void main(String[] args) throws IOException {
    App<LightSwitch> app = new App<>(new LightSwitch(), LightSwitch::view);
    app.focusRing(THEME.border().focus());
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Window.options().frameless().minimumSize(240, 160);
    try (Window window = Window.open("Lights", 360, 240, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  static El view(LightSwitch s) {
    Theme t = THEME;
    El lamp = el().key("lamp").size(56, 56).radius(28).fill(s.on ? LAMP : t.surface().raised())
        .motion(t.motion().move());
    return col().wFull().hFull().fill(t.surface().window())
        .child(titleBar(t))
        .child(col().grow(1).center().gap(t.space().xl())
            .child(lamp)
            .child(toggle(t, s.on, "Lamp", () -> s.on = !s.on)));
  }

  /** The system moves the window by the bar and acts on its buttons. */
  static El titleBar(Theme t) {
    int glyph = t.text().primary();
    return row().wFull().h(32).shrink(0).align(Style.Align.CENTER)
        .fill(t.surface().panel())
        .windowPart(WindowPart.DRAG)
        .child(el().size(12, 12).radius(6).fill(LAMP).margin(0, 0, 0, 10))
        .child(label(t, "Lights").margin(0, 0, 0, t.space().m()))
        .child(el().grow(1))
        .child(button(WindowPart.MINIMIZE, "Minimize", el().size(10, 1).fill(glyph)))
        .child(button(WindowPart.MAXIMIZE, "Maximize", el().size(10, 10).border(1, glyph)))
        .child(button(WindowPart.CLOSE, "Close", cross(glyph)));
  }

  static El button(WindowPart part, String label, El glyph) {
    return el().role("button").label(label).size(46, 32).center().windowPart(part).child(glyph);
  }

  /** An X, from two diagonals of small squares. */
  static El cross(int colour) {
    El cross = el().size(10, 10);
    for (int i = 0; i <= 8; i++) {
      cross.child(el().size(2, 2).absolute(i, i).fill(colour))
          .child(el().size(2, 2).absolute(8 - i, i).fill(colour));
    }
    return cross;
  }
}
