package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.AppIcon;
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

  private static final int LAMP = Identity.YELLOW;

  boolean on;

  // docs:start light-switch-window
  public static void main(String[] args) throws IOException {
    App<LightSwitch> app = new App<>(new LightSwitch(), LightSwitch::view);
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(
            LightSwitch.class, "Lights", Identity.AMBER, Symbol.FLASH)
        .frameless()
        .minimumSize(240, 160);
    try (Window window = Window.open("Lights", 360, 240, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }
  // docs:end light-switch-window

  // docs:start light-switch-view
  static El view(LightSwitch state) {
    Theme theme = Theme.CURRENT.get();
    El lamp = el().key("lamp")
        .size(56, 56)
        .radius(28)
        .fill(state.on ? LAMP : theme.surface().raised())
        .motion(theme.motion().move());
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(titleBar(AppIcon.appIcon(16, Identity.AMBER, Symbol.FLASH), "Lights"))
        .child(col()
            .grow(1)
            .center()
            .child(Card.card(lamp, toggle(state.on, "Lamp", state::toggleLamp))
                .padding(theme.space().xxl())
                .gap(theme.space().xl())
                .align(Style.Align.CENTER)));
  }

  void toggleLamp() {
    on = !on;
  }
  // docs:end light-switch-view
}
