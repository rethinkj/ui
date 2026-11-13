package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.TextField.passwordField;
import static com.rethinkj.ui.kit.TextField.revealable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The kit's password field and the button that shows its password. */
class PasswordFieldTest {

  private static final Theme T = Theme.DARK;
  private static final String DOTS = "\u2022".repeat(7);

  @TempDir Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Login {
    String password = "";
    boolean revealed;
  }

  private static App<Login> app(UnaryOperator<El> field) {
    App<Login> app = new App<>(new Login(), s -> col().padding(16).child(revealable(T,
        field.apply(passwordField(T, s.password, "Password", v -> s.password = v).key("pw")),
        s.revealed, () -> s.revealed = !s.revealed)));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 300 80\nframe\n" + steps + "\nframe", app, out);
  }

  /** The dump's line of the element with this key or label. */
  private static String line(App<?> app, String part) {
    return app.tree().dump().lines().filter(l -> l.contains(part)).findFirst().orElseThrow()
        .strip();
  }

  @Test
  void theButtonShowsThePasswordAndLeavesTheCaretInTheField() throws IOException {
    App<Login> app = app(f -> f);
    run(app, "press #pw\ntype \"hunter2\"\nkey ArrowLeft shift");
    assertEquals("hunter2", app.state().password);
    assertTrue(line(app, "#pw").contains("\"" + DOTS + "\" 16,16 200x24 password focused caret 6"
        + " anchor 7"), line(app, "#pw"));
    assertFalse(app.tree().dump().contains("hunter2"));

    run(app, "move role=button\nframe");
    assertEquals(Cursor.POINTER, app.cursor());
    run(app, "press role=button label=\"Show password\"");
    assertTrue(app.state().revealed);
    assertEquals("pw", app.focused().key(), "the press left the focus in the field");
    assertTrue(line(app, "#pw").endsWith("\"hunter2\" 16,16 200x24 focused caret 6 anchor 7"),
        line(app, "#pw"));
    assertTrue(line(app, "role=button").contains("label=\"Hide password\""));

    run(app, "type \"!\"");
    assertEquals("hunter!", app.state().password, "typing goes on where it was");

    run(app, "key Tab\nframe\nkey Space");
    assertEquals("Show password", app.focused().label(), "Tab reached the button, Space hid");
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
    assertTrue(line(readOnly, "#pw").contains("\"hunter2\""), line(readOnly, "#pw"));

    Login login = new Login();
    App<Login> disabled = new App<>(login, s -> col().padding(16).child(revealable(T,
        passwordField(T, "hunter2", "Password", null).key("pw"), s.revealed,
        () -> s.revealed = true)));
    disabled.textRendering(TextRendering.LINUX);
    run(disabled, "press role=button\nframe\nkey Tab");
    assertFalse(login.revealed);
    assertEquals(null, disabled.focused());
    assertTrue(line(disabled, "role=button").endsWith("disabled"), line(disabled, "role=button"));
  }
}
