package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.TextField.passwordField;
import static com.rethinkj.ui.kit.TextField.textArea;
import static com.rethinkj.ui.kit.TextField.textField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The menu of the kit's text fields: Cut, Copy, Paste and Select all, and the code editor's. */
class FieldMenuTest {

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Form {
    String name = "hello world";
    String notes = "one\ntwo";
    String password = "hunter2";
    String sql = "SELECT 1";
    final CodeEditor.State editor = new CodeEditor.State();
    final Menu.State own = new Menu.State();
    String chosen = "";
    App<Form> app;
  }

  private static App<Form> app(Function<Form, El> view) {
    App<Form> app = new App<>(new Form(), s -> col().padding(16).gap(8).child(view.apply(s)));
    app.state().app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** Runs the steps, then lets a menu that opens or closes finish fading; so does {@code open}. */
  private void run(App<?> app, String steps) throws IOException {
    String settle = "frame\nadvance 1\nframe";
    Script.run("size 400 300\nframe\n" + steps.replace("open", settle) + "\n" + settle, app, out);
  }

  /** The open menu's items, each its label with {@code -} after it if disabled. */
  private static String items(App<?> app) {
    return app.tree()
        .dump()
        .lines()
        .filter(l -> l.contains("role=menuitem"))
        .map(l -> {
          int from = l.indexOf("label=\"") + "label=\"".length();
          String label = l.substring(from, l.indexOf('"', from));
          return l.endsWith(" disabled") ? label + "-" : label;
        })
        .collect(Collectors.joining(", "));
  }

  @Test
  void aFieldsMenuCutsCopiesPastesAndSelectsAllAsItsKeysDo() throws IOException {
    App<Form> app = app(s -> textField(s.name, "Name", v -> s.name = v).key("name"));
    run(app, "press #name\nkey End\nkey ArrowLeft control shift\npress #name right\nopen");
    assertEquals("Cut, Copy, Paste-, Select all", items(app), "the clipboard is empty");

    run(app, "key C control");
    assertEquals("world", app.clipboard().read(), "an item's keys choose it in the menu too");
    assertEquals("", items(app), "choosing closed the menu");
    assertEquals("name", app.focused().key(), "and gave the field the focus back");

    run(app, "press #name right\nopen\npress label=\"Cut\"");
    assertEquals("hello ", app.state().name);
    run(app, "key Z control");
    assertEquals("hello world", app.state().name, "undo takes back the menu's cut");

    run(app, "key End\nkey ContextMenu\nopen\npress label=\"Paste\"");
    assertEquals("hello worldworld", app.state().name, "pasted at the caret");
    run(app, "key F10 shift\nopen\npress label=\"Select all\"\nclipboard \"\"\nkey V control");
    assertEquals("hello worldworld", app.state().name, "an empty clipboard pastes nothing");
    run(app, "key X control");
    assertEquals("", app.state().name, "the menu selected all");
  }

  @Test
  void aPasswordHasNoCutNorCopyAndAReadOnlyAreaNoCutNorPaste() throws IOException {
    App<Form> app = app(s -> col()
        .onKey(k -> {
          s.chosen = k.name(); // the app's own keys, which the open menu keeps its items' from
          return false;
        })
        .children(
            passwordField(s.password, "Password", v -> s.password = v).key("pw"),
            textArea(s.notes, "Notes", v -> s.notes = v).key("notes").readOnly()));
    run(app, "clipboard \"pasted\"\npress #pw right\nopen");
    assertEquals("Paste, Select all", items(app));
    run(app, "key C control");
    assertEquals("", app.state().chosen);

    run(app, "key Escape\nframe\nfocus #notes\nkey A control\nkey ContextMenu\nopen");
    assertEquals("Cut-, Copy, Paste-, Select all", items(app));
    run(app, "press label=\"Copy\"");
    assertEquals("one\ntwo", app.clipboard().read());
  }

  @Test
  void theCodeEditorsMenuAddsFindAndReplace() throws IOException {
    App<Form> app = app(s -> CodeEditor.codeEditor(s.app, s.sql, s.editor, null, v -> s.sql = v)
        .key("sql")
        .wFull());
    run(app, "press #sql right\nopen");
    assertEquals("Cut-, Copy-, Paste-, Select all, Find, Replace", items(app));
    run(app, "press label=\"Replace\"");
    assertTrue(app.state().editor.find().isOpen(), "the find bar opened");
    assertEquals("SELECT", app.state().editor.find().query(), "with the word at the caret");
  }

  @Test
  void anAppsOwnMenuOnAFieldGoesFirst() throws IOException {
    App<Form> app = app(s -> Menu.contextMenu(
        textField(s.name, "Name", v -> s.name = v).key("name"),
        s.own,
        List.of(Menu.item("Mine", () -> s.chosen = "mine"))));
    run(app, "press #name right\nopen");
    assertEquals("Mine", items(app));
    run(app, "press label=\"Mine\"");
    assertEquals("mine", app.state().chosen);
    assertFalse(app.tree().dump().contains("role=menu"), "and only it opened");

    App<Form> rows = app(s -> Menu.contextMenu(
        col().w(200).h(100).rows(3, 30, i -> textField(s.name, v -> s.name = v).key("f" + i)),
        s.own,
        List.of(Menu.item("Mine", () -> s.chosen = "mine"))));
    run(rows, "press #f1 right\nopen");
    assertEquals("Mine", items(rows), "a field in a row leaves the press to the list");
  }
}
