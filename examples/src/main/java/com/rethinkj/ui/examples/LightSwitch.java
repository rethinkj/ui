package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Symbol;
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
  App<LightSwitch> app; // whose window says whether it is maximized

  public static void main(String[] args) throws IOException {
    App<LightSwitch> app = new App<>(new LightSwitch(), LightSwitch::view);
    app.state().app = app;
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(LightSwitch.class, "Lights", 0xFFD97706, Symbol.FLASH)
        .frameless().minimumSize(240, 160);
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
        .child(titleBar(t, "Lights", LAMP, Identity.maximized(s.app)))
        .child(col().grow(1).center()
            .child(Card.card(t, lamp, toggle(t, s.on, "Lamp", () -> s.on = !s.on))
                .padding(t.space().xxl()).gap(t.space().xl()).align(Style.Align.CENTER)));
  }
}
