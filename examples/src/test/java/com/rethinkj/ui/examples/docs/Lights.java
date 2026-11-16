// docs:start lights-app
package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;

public final class Lights {

  boolean on; // the state: plain fields, changed only by handlers

  // docs:start lights-main
  public static void main(String[] args) throws Exception {
    App<Lights> app = new App<>(new Lights(), Lights::view);
    if (Script.handle(args, app)) {
      return; // --script <file> --out <folder>: no window, images and trees instead
    }
    try (Window window = Window.open("Lights", 360, 240, Window.options());
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }
  // docs:end lights-main

  // docs:start lights-view
  // The view: a function from the state to a tree of elements, built again after each input.
  static El view(Lights state) {
    Theme theme = Theme.DARK;
    El lamp = el().key("lamp")
        .size(56, 56)
        .radius(28)
        .fill(state.on ? Colors.rgb(0xFACC15) : theme.surface().raised())
        .motion(theme.motion().move()); // the color springs to its new value
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .center()
        .child(Card.card(theme, lamp, toggle(theme, state.on, "Lamp", state::toggleLamp))
            .padding(theme.space().xxl())
            .gap(theme.space().xl())
            .align(Style.Align.CENTER));
  }

  // A handler: a method that changes the state, which the view passes as state::toggleLamp.
  void toggleLamp() {
    on = !on;
  }
  // docs:end lights-view
}
// docs:end lights-app
