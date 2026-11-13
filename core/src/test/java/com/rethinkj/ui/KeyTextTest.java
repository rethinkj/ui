package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.text.TextLibraries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link App#keyText}: the text that the system made of a key while the window took no text, as
 * the keys that come in one batch with the click, Tab or key that takes the focus into a field.
 * It goes in where its key went to an element that takes text, and is dropped where its key went
 * elsewhere, as the space of Space that presses a button that opens a dialog with a field.
 */
class KeyTextTest {

  /** A field, a button that opens a dialog with a field that takes the focus, and their strings. */
  static final class Form {
    String name = "";
    boolean dialog;
    String inDialog = "";
  }

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static App<Form> app() {
    App<Form> app = new App<>(new Form(), f -> col().wFull().hFull().padding(20).gap(10).children(
        El.text(f.name).editable(s -> f.name = s).key("name").w(160).h(28).padding(0, 8),
        el().key("open").size(40, 20).focusable().onPress(() -> f.dialog = true)
            .overlay(f.dialog ? col().key("dialog").padding(10).child(
                El.text(f.inDialog).editable(s -> f.inDialog = s).key("in-dialog").autofocus()
                    .w(120).h(28)).centerInWindow().modal() : null)));
    app.resize(240, 140, 1);
    app.frame();
    return app;
  }

  /** A key with its text, as a window hands on both while its text input is off. */
  private static void type(App<?> app, String name, String text) {
    app.keyDown(new Key(name, 0, false));
    app.keyText(text);
    app.keyUp(new Key(name, 0, false));
  }

  @Test
  void theTextOfKeysAfterAClickIntoAFieldIsTyped() {
    App<Form> app = app();
    app.pointerDown(100, 34);
    app.pointerUp(100, 34);
    type(app, "A", "a");
    type(app, "B", "b");
    app.frame(); // all in one frame, as one batch
    assertEquals("ab", app.state().name);
  }

  @Test
  void theTextOfKeysAfterTabIntoAFieldIsTyped() {
    App<Form> app = app();
    app.keyDown(new Key("Tab", 0, false)); // its character is no text
    type(app, "A", "a");
    app.frame();
    assertEquals("a", app.state().name);
  }

  @Test
  void theTextOfAKeyThatPressedAButtonIsDroppedAndTheKeysAfterGoToTheDialog() {
    App<Form> app = app();
    app.keyDown(new Key("Tab", 0, false));
    app.keyDown(new Key("Tab", 0, false));
    app.frame();
    assertEquals("open", app.focused().key());
    type(app, "Space", " "); // presses the button, which opens the dialog
    type(app, "A", "a");
    type(app, "B", "b");
    app.frame();
    assertEquals("ab", app.state().inDialog, "without the space of Space");
    assertEquals("", app.state().name);
  }

  @Test
  void textWithoutAKeyGoesToTheFocusedField() {
    App<Form> app = app();
    app.pointerDown(100, 34);
    app.pointerUp(100, 34);
    app.textInput("é"); // as a character sent without a key comes
    app.frame();
    assertEquals("é", app.state().name);
  }
}
