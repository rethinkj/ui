package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.GermanTexts;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Toggle;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.util.Locale;

// docs:start languages
/** An app whose user picks the language of the kit's own texts. */
final class Languages {

  static final Texts GERMAN = new GermanTexts();

  boolean german = Locale.getDefault().getLanguage().equals("de"); // the system's at first
  String password = "";
  boolean revealed;

  Texts texts() {
    return german ? GERMAN : Texts.ENGLISH;
  }

  void toggleGerman() {
    german = !german;
  }

  void password(String value) {
    password = value;
  }

  void toggleRevealed() {
    revealed = !revealed;
  }

  static El view(Languages state) {
    // The placeholder is the app's own text; the name of the button that reveals is the kit's.
    String placeholder = state.german ? "Passwort" : "Password";
    El password = TextField.revealable(
        TextField.passwordField(state.password, placeholder, state::password),
        state.revealed,
        state::toggleRevealed);
    return Frame.of(
        Card.card(Toggle.toggle(state.german, "Deutsch", state::toggleGerman), password));
  }

  public static void main(String[] args) {
    Languages state = new Languages();
    // Each build of the view reads the texts the state chooses, so a new choice shows at once.
    App<Languages> app = new App<>(state, Languages::view).provide(Texts.CURRENT, Languages::texts);
    try (Window window = Window.open("Languages", 420, 220, Window.options());
        GpuPresenter presenter = new GpuPresenter(window)) {
      new WindowLoop(window, app, presenter).run();
    }
  }
}
// docs:end languages
