package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.window.SystemAppearance;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.util.List;

// docs:start theming
/** An app whose user picks dark, light or the system's look, and an accent. */
final class Themes {

  enum Mode {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System");

    final String text;

    Mode(String text) {
      this.text = text;
    }
  }

  Mode mode = Mode.SYSTEM; // the user's choice; keep it with the app's settings
  Theme.Hue accent = Theme.Hue.BLUE;
  boolean systemDark = true; // what the system asks for, told by the loop

  Theme theme() {
    boolean dark = mode == Mode.SYSTEM ? systemDark : mode == Mode.DARK;
    return (dark ? Theme.DARK : Theme.LIGHT).withAccent(accent); // the hue's dark or light tone
  }

  void mode(Mode value) {
    mode = value;
  }

  void accent(Theme.Hue hue) {
    accent = hue;
  }

  /** Told the system's look at the start and on each change of the system's setting. */
  void appearance(SystemAppearance look) {
    if (look.scheme() != SystemAppearance.Scheme.UNKNOWN) {
      systemDark = look.dark(); // changes the look only in System mode
    }
  }

  static El view(Themes state) {
    Theme theme = Theme.CURRENT.get(); // the one the app provides
    El settings = Card.card(
            Label.label("Appearance"),
            Segmented.segmented(List.of(Mode.values()), state.mode, mode -> mode.text, state::mode)
                .key("mode"),
            Label.label("Accent"),
            HuePicker.huePicker(state.accent, state::accent).key("accent"))
        .gap(theme.space().m());
    return Frame.of(settings);
  }

  public static void main(String[] args) throws Exception {
    Themes state = new Themes();
    // Each build of the view reads the theme the state chooses, so a new choice shows at once.
    App<Themes> app = new App<>(state, Themes::view).provide(Theme.CURRENT, Themes::theme);
    try (Window window = Window.open("Themes", 420, 260, Window.options());
        GpuPresenter presenter = new GpuPresenter(window)) {
      new WindowLoop(window, app, presenter).appearance(state::appearance).run();
    }
  }
}
// docs:end theming
