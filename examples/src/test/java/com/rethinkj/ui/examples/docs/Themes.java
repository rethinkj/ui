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

  static El view(Themes s) {
    Theme t = s.theme(); // every component of the view gets this one
    El settings = Card.card(
            t,
            Label.label(t, "Appearance"),
            Segmented.segmented(t, List.of(Mode.values()), s.mode, m -> m.text, s::mode)
                .key("mode"),
            Label.label(t, "Accent"),
            HuePicker.huePicker(t, s.accent, s::accent).key("accent"))
        .gap(t.space().m());
    return Frame.of(t, settings);
  }

  public static void main(String[] args) throws Exception {
    Themes state = new Themes();
    App<Themes> app = new App<>(state, Themes::view);
    try (Window window = Window.open("Themes", 420, 260, Window.options());
        GpuPresenter presenter = new GpuPresenter(window)) {
      new WindowLoop(window, app, presenter).appearance(state::appearance).run();
    }
  }
}
// docs:end theming
