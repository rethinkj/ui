package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
            checkbox(T, false, "Read only", h), "text=\"Read only\"", true));
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
    run(app, "key Enter\nframe\nkey Space");
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
  void theTreeNamesEachComponent() throws IOException {
    TextLibraries.assumeLoaded();
    App<Object> app = app(row().children(
        button(T, Variant.PRIMARY, "Run", () -> {}),
        toggle(T, true, "Autocommit", () -> {}),
        toggle(T, false, () -> {}).label("Wi-Fi"),
        checkbox(T, true, "Read only", () -> {}),
        button(T, Variant.GHOST, "Format", null)));
    run(app, "");

    List<String> named = new ArrayList<>();
    Matcher m = Pattern.compile("role=\\S+ label=\"[^\"]*\"").matcher(app.tree().dump());
    while (m.find()) {
      named.add(m.group());
    }
    assertEquals(List.of(
        "role=button label=\"Run\"",
        "role=switch label=\"Autocommit\"",
        "role=switch label=\"Wi-Fi\"",
        "role=checkbox label=\"Read only\"",
        "role=button label=\"Format\""), named);
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
