package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.TextField.searchField;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link TextField#searchField}: magnifier, clear button, Escape and {@link Prop} binding. */
class SearchFieldTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class State {
    String query;
    int escapes;

    State(String query) {
      this.query = query;
    }
  }

  private static App<State> app(String query) {
    App<State> app = new App<>(
        new State(query),
        s -> col()
            .padding(16)
            .onKey(k -> {
              if (k.name().equals("Escape")) {
                s.escapes++;
                return true;
              }
              return false;
            })
            .child(searchField(s.query, "Filter", v -> s.query = v)));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 300 80\nframe\n" + steps + "\nframe", app, out);
  }

  private static String line(App<?> app, String part) {
    return app.tree()
        .dump()
        .lines()
        .filter(l -> l.contains(part))
        .findFirst()
        .orElseThrow()
        .strip();
  }

  private static El textbox(El root) {
    if ("textbox".equals(root.role())) {
      return root;
    }
    for (El child : root.children()) {
      El found = textbox(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @Test
  void clearButtonAppearsOnlyWithTextAndKeepsFocusInTheField() throws IOException {
    App<State> app = app("");
    run(app, "frame");
    String empty = app.tree().dump();
    assertTrue(empty.contains("label=\"search\""), empty);
    assertFalse(empty.contains("role=button"), "no clear button while empty:\n" + empty);

    run(app, "press role=textbox label=\"Filter\"\ntype \"orders\"\nframe\nadvance 1");
    assertEquals("orders", app.state().query);
    assertTrue(line(app, "role=button").contains("label=\"Clear\""), line(app, "role=button"));

    run(app, "move role=button label=\"Clear\"\nframe");
    assertEquals(Cursor.POINTER, app.cursor());
    run(app, "press role=button label=\"Clear\"\nframe\nadvance 1");
    assertEquals("", app.state().query);
    assertFalse(app.tree().dump().contains("role=button"), "clear button disappears once empty");
    assertEquals("Filter", app.focused().label(), "clear button leaves focus in the field");

    run(app, "type \"abc\"\nframe\nadvance 1");
    assertEquals("abc", app.state().query, "typing continues in the focused field");

    // Tab moves to the clear button while keeping the field's focused border; Space clears and
    // returns focus to the field.
    run(app, "key Tab\nframe\nadvance 1");
    assertEquals("Clear", app.focused().label());
    assertEquals(
        T.accent().color(),
        textbox(app.tree()).borderColor(),
        "the frame keeps its focused look while the clear button has focus");
    run(app, "key Space\nframe\nadvance 1");
    assertEquals("", app.state().query);
    assertEquals("Filter", app.focused().label(), "focus returns to the field when cleared");

    // A disabled search field with text has no clear button.
    App<State> disabled = new App<>(
        new State("orders"), s -> col().padding(16).child(searchField(s.query, "Filter", null)));
    disabled.textRendering(TextRendering.LINUX);
    run(disabled, "frame");
    assertFalse(disabled.tree().dump().contains("role=button"), disabled.tree().dump());
    assertTrue(line(disabled, "role=textbox").endsWith("disabled"), line(disabled, "role=textbox"));
  }

  @Test
  void escapeEmptiesFilledFieldAndBubblesWhenEmpty() throws IOException {
    App<State> app = app("orders");
    run(app, "focus role=textbox label=\"Filter\"\nframe\nkey Escape shift");
    assertEquals("orders", app.state().query, "modified Escape does not empty");
    assertEquals(1, app.state().escapes, "modified Escape bubbles");

    run(app, "key Escape");
    assertEquals("", app.state().query, "Escape empties a filled search field");
    assertEquals(1, app.state().escapes, "Escape is consumed when it empties the field");
    assertEquals("Filter", app.focused().label(), "focus stays in the field");

    run(app, "key Escape");
    assertEquals(2, app.state().escapes, "Escape in an empty search field bubbles to ancestors");

    run(app, "type \"abc\"\nframe\nkey Tab\nframe\nkey Escape");
    assertEquals("", app.state().query, "Escape on the clear button empties the field");
    assertEquals(2, app.state().escapes, "and is consumed");
  }

  @Test
  void clearButtonReadsTextsAndPropOverloadBindsValue() throws IOException {
    State state = new State("orders");
    App<State> app = new App<>(
        state,
        s -> col()
            .padding(16)
            .child(
                searchField(Prop.of(() -> s.query, v -> s.query = v), "Suche").w(180)));
    app.textRendering(TextRendering.LINUX);
    app.provide(Texts.CURRENT, s -> new GermanTexts());
    run(app, "frame");
    assertTrue(line(app, "role=textbox").contains("180x24"), line(app, "role=textbox"));
    assertTrue(line(app, "role=button").contains("label=\"Entfernen\""), line(app, "role=button"));

    run(app, "press role=button label=\"Entfernen\"\nframe\nadvance 1");
    assertEquals("", state.query);
    assertFalse(app.tree().dump().contains("role=button"), app.tree().dump());

    App<State> readOnly = new App<>(
        new State("orders"),
        s -> col().padding(16).child(searchField(Prop.of(() -> s.query, null), "Filter")));
    readOnly.textRendering(TextRendering.LINUX);
    run(readOnly, "frame");
    assertFalse(readOnly.tree().dump().contains("role=button"), readOnly.tree().dump());
    assertTrue(line(readOnly, "role=textbox").endsWith("disabled"), line(readOnly, "role=textbox"));
  }
}
