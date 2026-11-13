package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.CodeEditor.codeEditor;
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
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Completion} at the caret of a {@link CodeEditor}, driven by scripts. */
class CompletionTest {

  private static final List<String> NAMES =
      List.of("customers", "customer_id", "count", "select", "status");

  @TempDir Path out;

  static final class Query {
    String sql = "";
    final CodeEditor.State editor = new CodeEditor.State();
    final Completion.State completion = new Completion.State();
    App<Query> app;
  }

  private static List<Completion.Entry> suggest(String word) {
    return NAMES.stream()
        .filter(n -> n.startsWith(word.toLowerCase(Locale.ROOT)))
        .map(n -> new Completion.Entry(n, Symbol.TABLE, n.startsWith("c") ? "name" : null))
        .toList();
  }

  private App<Query> app() {
    Theme t = Theme.DARK;
    Query q = new Query();
    App<Query> app = new App<>(q, s -> {
      El editor = codeEditor(t, s.sql, s.editor, Sql.of(t), v -> s.sql = v)
          .key("query").lines(4).wFull();
      return col().wFull().hFull().padding(12).fill(t.surface().window()).child(
          Completion.complete(t, s.app, editor, s.editor.caret(), s.completion,
              CompletionTest::suggest));
    });
    q.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private String run(App<Query> app, String script) throws IOException {
    Script.run("size 420 220\n" + script + "\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    return java.nio.file.Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void theWordBeforeTheCaretEndsAtWhatIsNoWordOfCode() {
    assertEquals(7, Completion.wordStart("SELECT cu", 9));
    assertEquals(9, Completion.wordStart("SELECT a.", 9), "after a point: no word yet");
    assertEquals(0, Completion.wordStart("_x1", 3));
    Completion.State state = new Completion.State();
    state.open();
    Completion.follow(state, "SELECT cu", "SELECT cu,");
    assertFalse(state.isOpen(), "a character that is no word's ends it");
    state.open();
    Completion.follow(state, "SELECT cus", "SELECT cu");
    assertTrue(state.isOpen(), "deleting within the word keeps it");
    Completion.follow(state, "SELECT c", "SELECT ");
    assertFalse(state.isOpen(), "the word gone, it closes");
    Completion.follow(state, "SELECT ", "SELECT customers");
    assertFalse(state.isOpen(), "a paste opens nothing");
  }

  @Test
  void typingOpensFromTheSecondCharacterAndKeysChoose() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT c\"\nframe");
    assertFalse(tree.contains("role=listbox"), "one character opens nothing");

    tree = run(app, "type \"u\"");
    assertTrue(tree.contains("el #completions role=listbox label=\"Completions\""), tree);
    assertTrue(tree.contains("el #option-0 role=option label=\"customers\""), tree);
    assertTrue(tree.contains("label=\"customers\"") && tree.contains(" selected"), tree);
    assertFalse(tree.contains("label=\"count\""), "narrowed to what starts with cu");
    assertTrue(tree.contains("text #query role=textbox") && tree.contains("focused caret 9"),
        "the focus stays in the field");

    tree = run(app, "key ArrowDown\nframe\nkey Enter");
    assertEquals("SELECT customer_id", app.state().sql);
    assertFalse(tree.contains("role=listbox"));
    assertEquals(18, app.state().editor.caret(), "after what it put in");

    run(app, "key Z control");
    assertEquals("SELECT cu", app.state().sql, "one step of the history");
  }

  @Test
  void controlSpaceOpensEscapeClosesAndAPressTakes() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT \"\nframe\nkey Space control");
    assertTrue(tree.contains("label=\"status\""), "every entry for no word yet");

    tree = run(app, "key Escape");
    assertFalse(tree.contains("role=listbox"));

    tree = run(app, "type \"s\"\nframe\nkey Space control");
    assertTrue(tree.contains("label=\"select\"") && tree.contains("label=\"status\""), tree);
    run(app, "press #option-1");
    assertEquals("SELECT status", app.state().sql);

    tree = run(app, "type \" co\"\nframe\ntype \"u\"\nframe\nkey ArrowLeft");
    assertFalse(tree.contains("role=listbox"), "moving the caret sideways closes it");
    assertEquals("SELECT status cou", app.state().sql);
  }

  @Test
  void aListWithoutEntriesClosesAndDoesNotComeBackWhereTheCaretGoes() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT 2\"\nframe\ntype \"0\"");
    assertFalse(tree.contains("role=listbox"), "a number has no entries");
    // The caret where every entry would fit, as Home, a press or a find bar puts it: nothing
    // opens there.
    tree = run(app, "key Home");
    assertEquals(0, app.state().editor.caret());
    assertFalse(tree.contains("role=listbox"), tree);
  }

  @Test
  void aKeyWithControlThatTheListDoesNotTakeClosesIt() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    String tree = run(app, "frame\npress #query\ntype \"SELECT c\"\nframe\ntype \"u\"");
    assertTrue(tree.contains("role=listbox"), tree);
    tree = run(app, "key F control"); // as Control+F, which opens a find bar
    assertFalse(tree.contains("role=listbox"), tree);
  }

  @Test
  void tabTakesTheEntryAndNothingElse() throws IOException {
    TextLibraries.assumeLoaded();
    App<Query> app = app();
    run(app, "frame\npress #query\ntype \"s\"\nframe\ntype \"e\"\nframe\nkey Tab");
    assertEquals("select", app.state().sql, "Tab took it, and indented nothing");
  }
}
