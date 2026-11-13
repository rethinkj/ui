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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The find bar ({@link Find}) and the commands of the {@link CodeEditor}, driven by scripts and
 * read from the tree dump and the app's state.
 */
class FindKeysTest {

  static final String TEXT = """
      SELECT id, name FROM orders;
      -- orders of the Straße
      select * from Orders o where o.name = 'STRASSE';
      UPDATE orders SET name = 'x' WHERE id = 1;""";

  @TempDir Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Doc {
    String text;
    final CodeEditor.State editor = new CodeEditor.State();
    final Find.State find = new Find.State();
    boolean readOnly;
    boolean area; // a plain text area with a find bar, not a code editor
    App<Doc> app;

    Doc(String text) {
      this.text = text;
    }
  }

  static App<Doc> app(Doc doc) {
    Theme t = Theme.DARK;
    App<Doc> app = new App<>(doc, s -> {
      El field;
      if (s.area) {
        field = Find.find(t, s.app, TextField.textArea(t, s.text, "Notes", v -> s.text = v)
            .key("query").lines(6).wFull(), s.find);
      } else {
        field = codeEditor(t, s.app, s.text, s.editor, Sql.of(t), v -> s.text = v)
            .key("query").lines(6).wFull();
        if (s.readOnly) {
          field.readOnly();
        }
      }
      return col().wFull().hFull().padding(12).fill(t.surface().window()).child(field);
    });
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** Runs the steps and returns the tree of the frame after them. */
  private String run(App<Doc> app, String script) throws IOException {
    Script.run("size 640 240\n" + script + "\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  private static String selected(Doc doc) {
    CodeEditor.State e = doc.editor;
    return doc.text.substring(Math.min(e.caret(), e.anchor()), Math.max(e.caret(), e.anchor()));
  }

  private static int caret(Doc doc) {
    return doc.editor.caret();
  }

  private static String line(String tree, String key) {
    return tree.lines().filter(l -> l.contains("#" + key + " ")).findFirst()
        .orElseThrow(() -> new AssertionError("no #" + key + " in\n" + tree));
  }

  private static String count(String tree) {
    String line = line(tree, "count");
    return line.substring(line.indexOf('"') + 1, line.indexOf('"', line.indexOf('"') + 1));
  }

  @Test
  void controlFOpensWithTheWordAtTheCaretAndGoesRoundAtTheEnds() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, """
        frame
        focus #query
        key ArrowRight
        key ArrowRight
        key F control""");
    assertTrue(tree.contains("el #find role=search label=\"Find\""), tree);
    assertTrue(line(tree, "find-field").contains("\"SELECT\"")
        && line(tree, "find-field").contains("focused caret 6 anchor 0"),
        "the word at the caret, selected in the focused field:\n" + tree);
    // SELECT and select: case is not matched.
    assertEquals("1 of 2", count(tree));
    assertEquals(0, Math.min(caret(doc), doc.editor.anchor()));
    tree = run(app, "key Enter");
    assertEquals("2 of 2", count(tree));
    assertEquals("select", selected(doc));
    tree = run(app, "key Enter");
    assertEquals("1 of 2", count(tree), "round to the first");
    tree = run(app, "key Enter shift");
    assertEquals("2 of 2", count(tree), "and back round to the last");
    run(app, "key F3 shift");
    assertEquals(0, doc.editor.anchor());

    tree = run(app, "key Escape");
    assertFalse(tree.contains("role=search"), tree);
    assertTrue(line(tree, "query").contains("focused caret 6 (1:7) anchor 0"),
        "the editor has the focus back with the last match selected:\n" + tree);
    run(app, "key F3");
    assertEquals("select", selected(doc), "F3 goes on with the closed bar's text");
  }

  @Test
  void typingSearchesFromWhereTheBarOpened() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, """
        frame
        focus #query
        key ArrowDown
        key F control
        type "orders\"""");
    // The caret was on the second line, where "-- orders" is the first after it.
    assertEquals("2 of 4", count(tree));
    assertEquals(TEXT.indexOf("orders of"), doc.editor.anchor());
    tree = run(app, "type \"x\"");
    assertEquals("No results", count(tree));
    tree = run(app, "key Backspace\nkey Backspace\nkey Backspace\nkey Backspace\n"
        + "key Backspace\nkey Backspace\nkey Backspace");
    assertEquals("", count(tree), "nothing to find");
  }

  @Test
  void eachSwitchNarrowsTheSearch() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, """
        frame
        focus #query
        key F control
        type "orders\"""");
    assertEquals("1 of 4", count(tree));
    assertTrue(line(tree, "match-case").contains("checked=false"), tree);
    tree = run(app, "key C alt");
    assertTrue(line(tree, "match-case").contains(" checked"), tree);
    assertEquals("1 of 3", count(tree), "not Orders");
    tree = run(app, "key C alt\npress #match-case");
    assertEquals("1 of 3", count(tree), "the button switches it too");
    assertTrue(line(tree, "find-field").contains("focused"), "and leaves the focus in the field");

    tree = run(app, "key C alt\nkey A control\ntype \"o\"\nkey W alt");
    assertEquals("1 of 2", count(tree), "the o of Orders o and of o.name are words");
    assertEquals("o", selected(doc));

    tree = run(app, "key W alt\nkey A control\ntype \"stra(ss|ß)e\"\nkey R alt");
    assertTrue(line(tree, "regex").contains(" checked"), tree);
    assertEquals("1 of 2", count(tree), "Straße and STRASSE");
  }

  @Test
  void caseFoldsUmlautsButNotSharpSToTwoLetters() throws IOException {
    Doc doc = new Doc("Ärger ärger ÄRGER straße STRASSE STRAẞE");
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey F control\nkey A control\ntype \"ärger\"");
    assertEquals("1 of 3", count(tree));
    tree = run(app, "key A control\ntype \"straße\"");
    assertEquals("1 of 2", count(tree), "ß finds ẞ, not SS");
  }

  @Test
  void anInvalidExpressionMarksTheFieldAndSaysWhy() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey F control\nkey R alt\ntype \"(orders\"");
    assertTrue(line(tree, "find-field").contains(" invalid"), tree);
    assertEquals("", count(tree));
    tree = run(app, "key Enter\nmove #find-field\nframe\nadvance 1\nframe");
    assertTrue(tree.contains("role=tooltip label=\"Unclosed group"), tree);
    tree = run(app, "type \")\"");
    assertFalse(line(tree, "find-field").contains(" invalid"), tree);
    assertEquals("1 of 4", count(tree));
  }

  @Test
  void aRegularExpressionThatBacktracksWithoutEndStops() throws IOException {
    Doc doc = new Doc("x".repeat(5000) + "\n" + TEXT);
    App<Doc> app = app(doc);
    long start = System.nanoTime();
    String tree = run(app, "frame\nfocus #query\nkey F control\nkey R alt\ntype \"(x+x+)+y\"");
    double seconds = (System.nanoTime() - start) / 1e9;
    assertTrue(seconds < 5, "took " + seconds + " s");
    assertTrue(count(tree).equals("Too slow") || count(tree).equals("No results"), tree);
  }

  @Test
  void replaceReplacesTheCurrentMatchAndAllAreOneStep() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, """
        frame
        focus #query
        key H control
        type "orders"
        frame
        advance 0.25
        frame
        focus #replace
        type "sales"
        key Enter""");
    assertTrue(tree.contains("label=\"Replace\""), tree);
    assertTrue(doc.text.startsWith("SELECT id, name FROM sales;"), doc.text);
    assertEquals("orders", selected(doc), "the next match selected");
    assertEquals("1 of 3", count(tree));

    tree = run(app, "key Enter alt control");
    assertFalse(doc.text.contains("orders"), doc.text);
    assertTrue(doc.text.contains("from sales o"), "Orders, with case not matched: " + doc.text);
    assertEquals("No results", count(tree));
    run(app, "key Escape\nkey Z control");
    assertEquals(TEXT.replaceFirst("orders", "sales"), doc.text, "one step takes all back");

    run(app, "key H control\nframe\nadvance 0.25\nframe\nkey A control\n"
        + "type \"(\\\\w+) = '(\\\\w+)'\"\nkey R alt\n"
        + "focus #replace\nkey A control\ntype \"$2 = $1\"\npress #replace-all");
    assertTrue(doc.text.contains("where o.STRASSE = name;"), doc.text);
    assertTrue(doc.text.contains("SET x = name WHERE"), doc.text);
  }

  @Test
  void findInSelectionKeepsToTheLinesTheBarOpenedWith() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, """
        frame
        focus #query
        key ArrowDown
        key ArrowDown shift
        key ArrowDown shift
        key F control""");
    assertFalse(line(tree, "in-selection").contains("disabled"), tree);
    tree = run(app, "type \"orders\"\nkey L alt");
    assertTrue(line(tree, "in-selection").contains(" checked"), tree);
    assertEquals("1 of 2", count(tree), "the second and third lines only");
    tree = run(app, "key H control\nframe\nadvance 0.25\nframe\nfocus #replace\ntype \"o\"\n"
        + "press #replace-all");
    assertTrue(doc.text.startsWith("SELECT id, name FROM orders;\n-- o of"), doc.text);
    assertTrue(doc.text.endsWith("UPDATE orders SET name = 'x' WHERE id = 1;"), doc.text);
  }

  @Test
  void controlGGoesToALineAndColumn() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey G control");
    assertTrue(line(tree, "line-field").contains("label=\"Go to line\""), tree);
    assertEquals("1 to 4", count(tree));
    tree = run(app, "type \"x\"");
    assertTrue(line(tree, "line-field").contains(" invalid"), tree);
    tree = run(app, "key Backspace\ntype \"3:8\"\nkey Enter");
    assertFalse(tree.contains("role=search"), tree);
    assertEquals(TEXT.indexOf("select") + 7, caret(doc));
    assertTrue(line(tree, "query").contains("focused"), tree);
    run(app, "key G control\nkey A control\ntype \"99\"\nkey Enter");
    assertEquals(TEXT.lastIndexOf('\n') + 1, caret(doc), "the last line, at its start");
  }

  @Test
  void aReadOnlyEditorFindsButDoesNotReplace() throws IOException {
    Doc doc = new Doc(TEXT);
    doc.readOnly = true;
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey H control\ntype \"id\"");
    assertFalse(tree.contains("label=\"Replace\""), tree);
    assertFalse(tree.contains("#toggle-replace"), tree);
    assertEquals("1 of 2", count(tree));
    run(app, "key Escape\nkey D control shift\nkey K control shift");
    assertEquals(TEXT, doc.text);
  }

  @Test
  void tabGoesThroughTheBarAndItsButtonsKeepTheFocusInTheField() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey F control\ntype \"id\"\nkey Tab");
    assertTrue(line(tree, "match-case").contains("focused"), tree);
    tree = run(app, "key Tab\nkey Tab\nkey Tab");
    assertTrue(line(tree, "previous").contains("focused"), "past the disabled in-selection");
    tree = run(app, "press #next");
    assertTrue(line(tree, "previous").contains("focused"), tree);
    assertEquals("2 of 2", count(tree));
  }

  @Test
  void aTextAreaHasTheBarToo() throws IOException {
    Doc doc = new Doc("The quick brown fox\njumps over the lazy dog.\nThe end.");
    doc.area = true;
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey F control\ntype \"the\"\nkey Enter");
    assertEquals("2 of 3", count(tree));
    assertEquals("the", doc.text.substring(doc.find.caret - 3, doc.find.caret));
  }

  @Test
  void theWordAtTheCaretIsMarkedWhereverItOccurs() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    run(app, "frame\nfocus #query\nkey End\nkey ArrowLeft\nkey ArrowLeft");
    assertEquals("orders", CodeEditor.word(doc.text, caret(doc), caret(doc)));
    assertEquals(null, CodeEditor.word("a.b", 1, 2), "a selection that is no word");
    assertEquals("b", CodeEditor.word("a.b", 2, 3));
  }

  @Test
  void controlDSelectsTheWordAndThenItsNextOccurrence() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    run(app, "frame\nfocus #query\nkey End\nkey ArrowLeft\nkey D control");
    assertEquals("orders", selected(doc));
    assertEquals(TEXT.indexOf("orders"), doc.editor.anchor());
    run(app, "key D control");
    assertEquals(TEXT.indexOf("orders of"), doc.editor.anchor(), "whole words, case matched");
    run(app, "key D control\nkey D control");
    assertEquals(TEXT.indexOf("orders"), doc.editor.anchor(), "round past UPDATE's to the first");
  }

  @Test
  void eachLineCommandIsOneStepOfTheHistory() throws IOException {
    Function<String, String> undoAfter = steps -> {
      Doc doc = new Doc(TEXT);
      App<Doc> app = app(doc);
      try {
        run(app, "frame\nfocus #query\n" + steps);
        String after = doc.text;
        run(app, "key Z control");
        assertEquals(TEXT, doc.text, "one Control+Z after " + steps);
        return after;
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    };
    String[] lines = TEXT.split("\n");
    assertEquals(String.join("\n", lines[0], lines[0], lines[1], lines[2], lines[3]),
        undoAfter.apply("key D control shift"));
    assertEquals(String.join("\n", lines[1], lines[0], lines[2], lines[3]),
        undoAfter.apply("key ArrowDown alt"));
    assertEquals(String.join("\n", lines[0], lines[2], lines[1], lines[3]),
        undoAfter.apply("key ArrowDown\nkey ArrowDown\nkey ArrowUp alt"));
    assertEquals(TEXT, undoAfter.apply("key ArrowUp alt"), "the first line goes no higher");
    assertEquals(TEXT, undoAfter.apply("key End control\nkey ArrowDown alt"), "nor the last lower");
    assertEquals(String.join("\n", lines[0], lines[1], lines[2], lines[3], lines[3]),
        undoAfter.apply("key End control\nkey D control shift"));
    assertEquals(String.join("\n", lines[0], lines[1], lines[3], lines[2]),
        undoAfter.apply("key End control\nkey ArrowUp alt"));
    assertEquals(String.join("\n", lines[1], lines[2], lines[3]),
        undoAfter.apply("key K control shift"));
    assertEquals(String.join("\n", lines[0], lines[2], lines[3]),
        undoAfter.apply("key ArrowDown\nkey K control shift"));
    assertEquals(String.join("\n", lines[0], lines[1], lines[2], lines[1], lines[2], lines[3]),
        undoAfter.apply("key ArrowDown\nkey ArrowDown shift\nkey ArrowDown shift\n"
            + "key D control shift"));
    assertEquals(String.join("\n", lines[0], lines[3], lines[1], lines[2]),
        undoAfter.apply("key ArrowDown\nkey ArrowDown shift\nkey ArrowDown shift\n"
            + "key ArrowDown alt"));
    assertEquals(String.join("\n", lines[0], lines[1], lines[2]),
        undoAfter.apply("key End control\nkey K control shift"));
    assertEquals(String.join("\n", lines[0], lines[3]),
        undoAfter.apply("key ArrowDown\nkey ArrowDown shift\nkey ArrowDown shift\n"
            + "key K control shift"));
    assertEquals(String.join("\n", "-- " + lines[0], lines[1], lines[2], lines[3]),
        undoAfter.apply("key / control"));
    assertEquals(String.join("\n", lines[0], "orders of the Straße", lines[2], lines[3]),
        undoAfter.apply("key ArrowDown\nkey 7 control shift"),
        "Control+Shift+7, the / of a German keyboard, takes the comment away");
    assertEquals(String.join("\n", lines[0], "-- -- orders of the Straße", "-- " + lines[2],
        lines[3]), undoAfter.apply("key ArrowDown\nkey ArrowDown shift\nkey End shift\n"
            + "key NumpadDivide control"));
    assertEquals(CodeEditor.Command.TOGGLE_COMMENT,
        CodeEditor.command(new com.rethinkj.ui.Key("#", com.rethinkj.ui.Key.CONTROL, false)),
        "Control+#, where a German keyboard has VS Code's");
  }
}
