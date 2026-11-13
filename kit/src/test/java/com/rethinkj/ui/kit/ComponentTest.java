package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.TextField.codeArea;
import static com.rethinkj.ui.kit.TextField.passwordField;
import static com.rethinkj.ui.kit.TextField.revealable;
import static com.rethinkj.ui.kit.TextField.textArea;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** What every component does with input: roles and labels, presses and keys, focus, disabling. */
class ComponentTest {

  private static final Theme T = Theme.DARK;

  @TempDir Path out;

  /**
   * A component built with a handler, the selector that finds it, and whether it has text, which
   * needs the text libraries.
   */
  static Stream<Arguments> components() {
    return Stream.of(
        Arguments.of("button", (Function<Runnable, El>) h ->
            button(T, Variant.SECONDARY, "Save", h), "role=button label=\"Save\"", true),
        Arguments.of("toggle", (Function<Runnable, El>) h ->
            toggle(T, false, "Autocommit", h), "role=switch label=\"Autocommit\"", true),
        Arguments.of("toggle by its text", (Function<Runnable, El>) h ->
            toggle(T, false, "Autocommit", h), "text=\"Autocommit\"", true),
        Arguments.of("toggle without text", (Function<Runnable, El>) h ->
            toggle(T, true, h).label("Wi-Fi"), "role=switch label=\"Wi-Fi\"", false),
        Arguments.of("checkbox", (Function<Runnable, El>) h ->
            checkbox(T, false, "Read only", h), "role=checkbox label=\"Read only\"", true),
        Arguments.of("checkbox by its text", (Function<Runnable, El>) h ->
            checkbox(T, false, "Read only", h), "text=\"Read only\"", true),
        Arguments.of("switch row", (Function<Runnable, El>) h ->
            Group.switchRow(T, "Word wrap", "At the edge", true, h),
            "role=switch label=\"Word wrap\"", true),
        Arguments.of("switch row by its description", (Function<Runnable, El>) h ->
            Group.switchRow(T, "Word wrap", "At the edge", true, h), "text=\"At the edge\"",
            true),
        Arguments.of("link row", (Function<Runnable, El>) h ->
            Group.linkRow(T, Symbol.KEY, "Keymap", null, "Default", h),
            "role=button label=\"Keymap\"", true),
        Arguments.of("expander", (Function<Runnable, El>) h ->
            Group.expander(T, "Advanced", null, false, h), "role=button label=\"Advanced\"",
            true),
        Arguments.of("pressable card", (Function<Runnable, El>) h ->
            Card.pressable(T, Card.card(T, Label.label(T, "staging")), "staging", false, h),
            "role=button label=\"staging\"", true),
        Arguments.of("status bar item", (Function<Runnable, El>) h ->
            StatusBar.item(T, Symbol.PLUG_CONNECTED, "localhost", h), "text=\"localhost\"",
            true),
        Arguments.of("badge", (Function<Runnable, El>) h ->
            Badge.badge(T, "Add filter", Badge.options().onPress(h)),
            "label=\"Add filter\"", true), // without a handler it only shows, without a role
        Arguments.of("toggle button", (Function<Runnable, El>) h ->
            Button.toggle(T, Symbol.PIN, "Pin", false, h == null ? null : on -> h.run()),
            "role=button label=\"Pin\"", true));
  }

  private App<Object> app(El view) {
    App<Object> app = new App<>(new Object(), s -> view);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 480 120\nframe\n" + steps + "\nframe", app, out);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("components")
  void pressEnterAndSpaceCallTheHandler(
      String name, Function<Runnable, El> component, String target, boolean text)
      throws IOException {
    if (text) {
      TextLibraries.assumeLoaded();
    }
    int[] calls = {0};
    App<Object> app = app(row().child(component.apply(() -> calls[0]++)));

    run(app, "move " + target + "\npress " + target);
    assertEquals(1, calls[0]);
    assertEquals(Cursor.POINTER, app.cursor());
    assertNull(app.focused(), "a press leaves the focus where it was");
    run(app, "focus " + target + "\nkey Enter\nframe\nkey Space");
    assertEquals(3, calls[0]);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("components")
  void aDisabledComponentTakesNoFocus(
      String name, Function<Runnable, El> component, String target, boolean text)
      throws IOException {
    if (text) {
      TextLibraries.assumeLoaded();
    }
    App<Object> app = app(row().child(component.apply(null)));

    run(app, "move " + target + "\npress " + target + "\nframe\nkey Tab\nframe\nkey Space");
    assertNull(app.focused());
    assertEquals(Cursor.DEFAULT, app.cursor());
  }

  @Test
  void theTreeNamesEachComponentAndSaysItsState() throws IOException {
    TextLibraries.assumeLoaded();
    App<Object> app = app(col().children(
        button(T, Variant.PRIMARY, "Run", () -> {}),
        toggle(T, true, "Autocommit", () -> {}),
        toggle(T, false, () -> {}).label("Wi-Fi"),
        checkbox(T, true, "Read only", () -> {}),
        checkbox(T, false, "Strict", null),
        button(T, Variant.GHOST, "Format", null),
        textField(T, "", "Search", s -> {}),
        textField(T, "x", s -> {}).label("Name"),
        textField(T, "y", "Id", s -> {}).readOnly(),
        textField(T, "z", "Owner", null),
        revealable(T, passwordField(T, "pw", "Password", s -> {}), false, () -> {}),
        revealable(T, passwordField(T, "pw", "Key", null), true, () -> {}),
        textArea(T, "a\nb", "Notes", s -> {}),
        codeArea(T, "SELECT 1;", "SQL", s -> {}).readOnly(),
        textArea(T, "", "Comment", null)));
    run(app, "");

    // The role and label, then what follows the rectangle: the states.
    List<String> named = new ArrayList<>();
    Matcher m = Pattern.compile(
        "(role=\\S+ label=\"[^\"]*\")(?: \"[^\"]*\")? \\S+,\\S+ \\S+x\\S+(.*)")
        .matcher(app.tree().dump());
    while (m.find()) {
      named.add(m.group(1) + m.group(2));
    }
    assertEquals(List.of(
        "role=button label=\"Run\"",
        "role=switch label=\"Autocommit\" checked",
        "role=switch label=\"Wi-Fi\" checked=false",
        "role=checkbox label=\"Read only\" checked",
        "role=checkbox label=\"Strict\" disabled checked=false",
        "role=button label=\"Format\" disabled",
        "role=textbox label=\"Search\"",
        "role=textbox label=\"Name\"",
        "role=textbox label=\"Id\" readonly",
        "role=textbox label=\"Owner\" disabled",
        "role=textbox label=\"Password\" password",
        "role=button label=\"Show password\"",
        "role=textbox label=\"Key\" disabled",
        "role=button label=\"Hide password\" disabled",
        "role=textbox label=\"Notes\" lines 1-2/2",
        "role=textbox label=\"SQL\" readonly lines 1-1/1",
        "role=textbox label=\"Comment\" disabled lines 1-1/1"), named);
  }

  @Test
  void aTextFieldHandsEachEditToItsHandlerAndShowsTheTextCursor() throws IOException {
    TextLibraries.assumeLoaded();
    String[] value = {""};
    App<Object> app = new App<>(new Object(),
        s -> row().child(textField(T, value[0], "Search", v -> value[0] = v).key("q")));
    app.textRendering(TextRendering.LINUX);
    run(app, "move #q\npress #q\nframe\ntype \"orders\"\nframe\nkey Backspace");
    assertEquals("order", value[0]);
    assertEquals(Cursor.TEXT, app.cursor());
    assertEquals("q", app.focused().key());
    assertTrue(app.tree().dump().contains("text #q role=textbox label=\"Search\" \"order\""));
  }

  @Test
  void aDisabledTextFieldTakesNoFocusAndAReadOnlyOneTakesNoEdits() throws IOException {
    TextLibraries.assumeLoaded();
    App<Object> disabled = app(row().child(textField(T, "x", "Search", null)));
    run(disabled,
        "move role=textbox disabled\npress role=textbox\nframe\nkey Tab\nframe\ntype \"y\"");
    assertNull(disabled.focused());
    assertEquals(Cursor.DEFAULT, disabled.cursor());

    String[] value = {"fixed"};
    App<Object> readOnly = app(row().child(
        textField(T, value[0], "Search", v -> value[0] = v).readOnly().maxLength(10)));
    run(readOnly, "key Tab\nframe\ntype \"y\"\nframe\nkey C control");
    assertEquals("textbox", readOnly.focused().role());
    assertEquals("fixed", value[0]);
    assertEquals("fixed", readOnly.clipboard().read(), "Tab selected it all, and it copies");
  }

  @Test
  void tabSkipsWhatIsDisabled() throws IOException {
    TextLibraries.assumeLoaded();
    App<Object> app = app(row().children(
        button(T, Variant.PRIMARY, "Run", () -> {}),
        button(T, Variant.SECONDARY, "Cancel", null),
        toggle(T, false, "Autocommit", () -> {}),
        checkbox(T, false, "Read only", () -> {}),
        toggle(T, false, "Disabled", null),
        button(T, Variant.GHOST, "Format", () -> {})));
    run(app, "");

    List<String> order = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      key(app, 0);
      order.add(app.focused().label());
    }
    assertEquals(List.of("Run", "Autocommit", "Read only", "Format", "Run"), order);
    key(app, Key.SHIFT);
    assertEquals("Format", app.focused().label());
  }

  private static void key(App<?> app, int modifiers) {
    app.keyDown(new Key("Tab", modifiers, false));
    app.keyUp(new Key("Tab", modifiers, false));
    app.frame();
  }
}
