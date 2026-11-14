package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.TextField.passwordField;
import static com.rethinkj.ui.kit.TextField.revealable;
import static com.rethinkj.ui.kit.Workbench.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The kit's password field and the button that shows its password. */
class PasswordFieldTest {

  private static final Theme T = Theme.DARK;
  private static final String DOTS = "\u2022".repeat(7);

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Login {
    String password = "";
    boolean revealed;
  }

  private static App<Login> app(UnaryOperator<El> field) {
    App<Login> app = new App<>(
        new Login(),
        s -> col()
            .padding(16)
            .child(revealable(
                T,
                field.apply(passwordField(T, s.password, "Password", v -> s.password = v)
                    .key("pw")),
                s.revealed,
                () -> s.revealed = !s.revealed)));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 300 80\nframe\n" + steps + "\nframe", app, out);
  }

  /** The dump's line of the element with this key or label. */
  private static String line(App<?> app, String part) {
    return app.tree()
        .dump()
        .lines()
        .filter(l -> l.contains(part))
        .findFirst()
        .orElseThrow()
        .strip();
  }

  @Test
  void theButtonShowsThePasswordAndLeavesTheCaretInTheField() throws IOException {
    App<Login> app = app(f -> f);
    run(app, "press #pw\ntype \"hunter2\"\nkey ArrowLeft shift");
    assertEquals("hunter2", app.state().password);
    assertTrue(
        line(app, "#pw")
            .contains("\"" + DOTS + "\" 16,16 200x24 password focused caret 6" + " anchor 7"),
        line(app, "#pw"));
    assertFalse(app.tree().dump().contains("hunter2"));

    run(app, "move role=button\nframe");
    assertEquals(Cursor.POINTER, app.cursor());
    run(app, "press role=button label=\"Show password\"");
    assertTrue(app.state().revealed);
    assertEquals("pw", app.focused().key(), "the press left the focus in the field");
    assertTrue(
        line(app, "#pw")
            .endsWith("\"" + DOTS + "\" 16,16 200x24 password revealed focused caret 6 anchor 7"),
        line(app, "#pw"));
    assertFalse(app.tree().dump().contains("hunter2"), "shown, the dump still sees the mask");
    assertTrue(line(app, "role=button").contains("label=\"Hide password\""));

    run(app, "type \"!\"");
    assertEquals("hunter!", app.state().password, "typing goes on where it was");

    run(app, "key Tab\nframe\nkey Space");
    assertEquals("Show password", app.focused().label(), "Tab reached the button, Space hid");
    run(app, "advance 1"); // past the frame's spring
    assertEquals(
        T.accent().color(),
        find(app, "pw").borderColor(),
        "the field's frame keeps its focused look while its button has the focus");
    app.focus(null);
    run(app, "advance 1");
    assertEquals(T.border().strong(), find(app, "pw").borderColor(), "and loses it after");
    assertFalse(app.state().revealed);
    assertTrue(line(app, "#pw").contains("password"), line(app, "#pw"));
  }

  @Test
  void aMaskedFieldCopiesNothingAndAShownOneCopies() throws IOException {
    App<Login> app = app(f -> f);
    app.state().password = "hunter2";
    run(app, "clipboard \"before\"\nfocus #pw\nframe\nkey A control\nkey C control\nkey X control");
    assertEquals("before", app.clipboard().read());
    assertEquals("hunter2", app.state().password);
    run(app, "press role=button\nframe\nkey A control\nkey C control");
    assertEquals("hunter2", app.clipboard().read(), "shown, it is an ordinary field");
  }

  @Test
  void aReadOnlyFieldShowsItsPasswordAndADisabledOneDoesNot() throws IOException {
    App<Login> readOnly = app(El::readOnly);
    readOnly.state().password = "hunter2";
    run(readOnly, "press role=button");
    assertTrue(readOnly.state().revealed);
    assertTrue(
        line(readOnly, "#pw").contains("\"" + DOTS + "\" 16,16 200x24 readonly password revealed"),
        line(readOnly, "#pw"));

    Login login = new Login();
    App<Login> disabled = new App<>(
        login,
        s -> col()
            .padding(16)
            .child(revealable(
                T,
                passwordField(T, "hunter2", "Password", null).key("pw"),
                s.revealed,
                () -> s.revealed = true)));
    disabled.textRendering(TextRendering.LINUX);
    run(disabled, "press role=button\nframe\nkey Tab");
    assertFalse(login.revealed);
    assertEquals(null, disabled.focused());
    assertTrue(line(disabled, "role=button").endsWith("disabled"), line(disabled, "role=button"));
  }

  @Test
  void aShownPasswordIsInNoSelectorNorScriptError() throws IOException {
    App<Login> app = app(f -> f);
    run(app, "press #pw\ntype \"hunter2\"\npress role=button\nframe");
    assertTrue(app.state().revealed);
    run(app, "press role=textbox revealed\npress text=\"" + DOTS + "\"");
    for (String step : List.of(
        "press #nowhere", // lists the texts there are
        "press role=textbox disabled", // lists the lines of the textboxes
        "press text=\"hunter2\"")) { // its value finds nothing
      Script.Failure failure = assertThrows(Script.Failure.class, () -> run(app, step));
      String message = failure.getMessage().replace(step, "").replace("text=\"hunter2\"", "");
      assertFalse(message.contains("hunter2"), message);
      assertTrue(message.contains(DOTS), message);
    }
  }

  /** Invalid before {@code revealable} or made so by a form after it: the danger border stays. */
  @Test
  void anInvalidFieldKeepsItsDangerBorderWhileItOrItsButtonHasTheFocus() throws IOException {
    App<Login> before = app(f -> TextField.invalid(T, f));
    Login login = new Login();
    App<Login> after = new App<>(
        login,
        s -> col()
            .padding(16)
            .child(Form.form(
                T,
                Form.Labels.ABOVE,
                Form.field(
                        "Password",
                        revealable(
                            T,
                            passwordField(T, s.password, null, v -> s.password = v)
                                .key("pw"),
                            s.revealed,
                            () -> s.revealed = !s.revealed))
                    .error("Rejected"))));
    after.textRendering(TextRendering.LINUX);
    for (App<Login> app : List.of(before, after)) {
      run(app, "focus #pw\nframe\nadvance 1");
      assertTrue(line(app, "#pw").contains(" invalid"), line(app, "#pw"));
      assertEquals(T.status().danger(), find(app, "pw").borderColor(), "the field focused");
      run(app, "key Tab\nframe\nadvance 1");
      assertEquals("Show password", app.focused().label());
      assertEquals(T.status().danger(), find(app, "pw").borderColor(), "its button focused");
    }
  }
}
