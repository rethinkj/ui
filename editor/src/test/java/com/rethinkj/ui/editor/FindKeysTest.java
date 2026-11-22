package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Find;
import com.rethinkj.ui.kit.GermanTexts;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  /** Colors nothing, and comments lines out with {@code --}, as SQL does. */
  private static final Highlighter SQL_COMMENTS = new Highlighter() {
    @Override
    public Object line(String line, Object state, Spans out) {
      return null;
    }

    @Override
    public String lineComment() {
      return "--";
    }

    @Override
    public String wordCharacters() {
      return "$"; // as PostgreSQL's names may hold
    }
  };

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Doc {
    String text;
    final CodeEditor.State editor = new CodeEditor.State();
    boolean readOnly;
    boolean position; // the caret's line and column below the editor
    boolean refuse; // the app keeps its text once
    Texts texts = Texts.ENGLISH;
    App<Doc> app;

    Doc(String text) {
      this.text = text;
    }
  }

  static App<Doc> app(Doc doc) {
    Theme t = Theme.DARK;
    App<Doc> app = new App<>(doc, s -> {
      El field = codeEditor(s.app, s.text, s.editor, SQL_COMMENTS, v -> change(s, v))
          .key("query")
          .lines(6)
          .wFull();
      if (s.readOnly) {
        field.readOnly();
      }
      El page = col().wFull().hFull().padding(12).fill(t.surface().window()).child(field);
      if (s.position) { // after the editor's build, as a status bar below it
        page.child(El.text(s.editor.line() + ":" + s.editor.column()).key("position"));
      }
      return page;
    });
    doc.app = app;
    app.provide(Texts.CURRENT, s -> s.texts);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private static void change(Doc doc, String value) {
    if (doc.refuse) {
      doc.refuse = false;
    } else {
      doc.text = value;
    }
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
    return tree.lines()
        .filter(l -> l.contains("#" + key + " "))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no #" + key + " in\n" + tree));
  }

  private static String count(String tree) {
    String line = line(tree, "count");
    return line.substring(line.indexOf('"') + 1, line.indexOf('"', line.indexOf('"') + 1));
  }

  @Test
  void lineAndColumnCountFromOneAndATabAsOneCharacter() throws IOException {
    Doc doc = new Doc("SELECT 1\r\n\tFROM t");
    doc.position = true;
    App<Doc> app = app(doc);
    String tree = run(app, """
        frame
        focus #query
        key ArrowDown
        key End""");
    assertTrue(line(tree, "position").contains("\"2:8\""), tree); // after the tab and FROM t
    tree = run(app, """
        key Enter
        type \"x\"""");
    assertTrue(line(tree, "position").contains("\"3:3\""), tree); // Enter kept the tab
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
    assertTrue(
        line(tree, "find-field").contains("\"SELECT\"")
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
    assertTrue(
        line(tree, "query").contains("focused caret 6 (1:7) anchor 0"),
        "the editor has the focus back with the last match selected:\n" + tree);
    run(app, "key F3");
    assertEquals("select", selected(doc), "F3 goes on with the closed bar's text");
  }

  @Test
  void aMatchInTheFirstLineScrollsOutFromUnderTheBar() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    // The text fits the editor: only the room the open bar leaves above the first line scrolls.
    String tree =
        run(app, "frame\nfocus #query\nkey End\nkey ArrowLeft\nkey ArrowLeft\nkey F control");
    assertEquals("1 of 4", count(tree));
    assertFirstLineBelowTheBar(tree);
    tree = run(app, "key Enter\nkey Enter\nkey Enter\nkey Enter");
    assertEquals("1 of 4", count(tree), "round to the first");
    assertFirstLineBelowTheBar(tree);
    tree = run(app, "key Escape");
    assertFalse(
        line(tree, "query").contains(" scroll "), "no room above without the bar:\n" + tree);
  }

  /** The rectangle {@code x,y wxh} of a dump's line, after its quoted parts. */
  private static float[] rect(String line) {
    String plain = line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    var m = java.util.regex.Pattern.compile(" (-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+)")
        .matcher(plain);
    assertTrue(m.find(), line);
    return new float[] {
      Float.parseFloat(m.group(1)),
      Float.parseFloat(m.group(2)),
      Float.parseFloat(m.group(3)),
      Float.parseFloat(m.group(4))
    };
  }

  private static void assertFirstLineBelowTheBar(String tree) {
    float[] row = rect(line(tree, "line-0"));
    float[] bar = rect(line(tree, "find"));
    assertTrue(row[1] >= bar[1] + bar[3], "the first line below the bar:\n" + tree);
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
    tree = run(app, """
        key Backspace
        key Backspace
        key Backspace
        key Backspace
        key Backspace
        key Backspace
        key Backspace""");
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
    run(app, "key ArrowDown alt\nkey D control shift");
    assertEquals(TEXT, doc.text, "the editor's commands do nothing in the bar");
  }

  @Test
  void anInvalidExpressionMarksTheFieldAndSaysWhy() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey F control\nkey R alt\ntype \"(orders\"");
    assertTrue(line(tree, "find-field").contains(" invalid"), tree);
    assertEquals("", count(tree));
    tree = run(app, "key Enter\nmove #find-field\nframe\nadvance 1\nframe");
    assertTrue(tree.contains("role=tooltip label=\"Unclosed group at 8\""), tree);
    doc.texts = new GermanTexts();
    app.invalidate();
    tree = run(app, "frame");
    assertTrue(tree.contains("role=tooltip label=\"Unclosed group bei Zeichen 8\""), tree);
    doc.texts = Texts.ENGLISH;
    app.invalidate();
    tree = run(app, "type \")\"");
    assertFalse(line(tree, "find-field").contains(" invalid"), tree);
    assertEquals("1 of 4", count(tree));
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

    run(app, """
        key H control
        frame
        advance 0.25
        frame
        key A control
        type "(\\\\w+) = '(\\\\w+)'"
        key R alt
        focus #replace
        key A control
        type "$2 = $1"
        press #replace-all""");
    assertTrue(doc.text.contains("where o.STRASSE = name;"), doc.text);
    assertTrue(doc.text.contains("SET x = name WHERE"), doc.text);
  }

  @Test
  void aReplaceTheAppRefusesLeavesTheMatchToReplace() throws IOException {
    Doc doc = new Doc("a b");
    App<Doc> app = app(doc);
    run(app, """
        frame
        focus #query
        key H control
        type "b"
        frame
        advance 0.25
        frame
        focus #replace
        type "c\"""");
    doc.refuse = true;
    run(app, "press #replace-one");
    assertEquals("a b", doc.text);
    run(app, "press #replace-one");
    assertEquals("a c", doc.text, "the match stayed selected");
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
    tree = run(app, """
        key H control
        frame
        advance 0.25
        frame
        focus #replace
        type "o"
        press #replace-all""");
    assertTrue(doc.text.startsWith("SELECT id, name FROM orders;\n-- o of"), doc.text);
    assertTrue(doc.text.endsWith("UPDATE orders SET name = 'x' WHERE id = 1;"), doc.text);
  }

  @Test
  void replaceInSelectionLeavesAMatchOutsideItAndGoesToOneInside() throws IOException {
    Doc doc = new Doc("a\nb\na");
    App<Doc> app = app(doc);
    run(app, """
        frame
        focus #query
        key ArrowDown shift
        key ArrowDown shift
        key H control
        frame
        advance 0.25
        frame
        focus #find-field
        type "a"
        key L alt
        frame
        focus #replace
        type "X"
        frame
        press 100,100
        key End control
        key ArrowLeft shift
        frame
        press #replace-one""");
    assertEquals("a\nb\na", doc.text, "the last a lies after the lines searched in");
    assertEquals("a", selected(doc));
    assertEquals(0, Math.min(caret(doc), doc.editor.anchor()), "the match inside them instead");
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
  void theEditorsMenuAddsFindAndReplace() throws IOException {
    Doc doc = new Doc("SELECT 1");
    App<Doc> app = app(doc);
    String tree = run(app, "frame\npress #query right\nframe\nadvance 1\nframe");
    assertTrue(
        tree.lines().anyMatch(l -> l.contains("role=menuitem") && l.contains("label=\"Replace\"")),
        tree);
    run(app, "press label=\"Replace\"");
    assertTrue(doc.editor.find().isOpen(), "the find bar opened");
    assertEquals("SELECT", doc.editor.find().query(), "with the word at the caret");
  }

  @Test
  void anEditorWithoutItsBarLeavesItsKeysToTheField() throws IOException {
    Doc doc = new Doc(TEXT);
    doc.editor.findBar(false);
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey F control");
    assertFalse(tree.contains("role=search"), tree);
    run(app, "key D control shift");
    assertTrue(doc.text.startsWith("SELECT id, name FROM orders;\nSELECT"), "the commands stay");
  }

  @Test
  void theWordAtTheCaretIsMarkedWhereverItOccurs() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    run(app, "frame\nfocus #query\nkey End\nkey ArrowLeft\nkey ArrowLeft");
    assertEquals("orders", CodeEditor.word(doc.text, caret(doc), caret(doc), ""));
    assertEquals(null, CodeEditor.word("a.b", 1, 2, ""), "a selection that is no word");
    assertEquals("b", CodeEditor.word("a.b", 2, 3, ""));
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
  void controlDSkipsAWordThatGoesOnInAWordCharacterOfTheLanguage() throws IOException {
    Doc doc = new Doc("gt $gt gt$ gt");
    App<Doc> app = app(doc);
    run(app, "frame\nfocus #query\nkey Home\nkey D control\nkey D control");
    assertEquals(11, doc.editor.anchor());
  }

  @Test
  void f8GoesFromDiagnosticToDiagnosticAndShowsItsMessage() throws IOException {
    Doc doc = new Doc(TEXT);
    int name = TEXT.indexOf("name");
    int orders = TEXT.indexOf("Orders");
    int update = TEXT.indexOf("UPDATE");
    doc.editor.diagnostics(
        TEXT,
        List.of(
            new CodeEditor.Diagnostic(update, update + 6, CodeEditor.Severity.WARNING, "No WHERE"),
            new CodeEditor.Diagnostic(name, name + 4, CodeEditor.Severity.ERROR, "No column name"),
            new CodeEditor.Diagnostic(
                orders, orders + 6, CodeEditor.Severity.WARNING, "Orders is not orders")));
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #query\nkey F8");
    assertTrue(line(tree, "query").contains(" focused caret " + name + " "), tree);
    assertTrue(tree.contains("role=tooltip label=\"No column name\""), tree);
    tree = run(app, "key F8");
    assertEquals(orders, caret(doc));
    assertTrue(tree.contains("role=tooltip label=\"Orders is not orders\""), tree);
    run(app, "key F8");
    assertEquals(update, caret(doc));
    tree = run(app, "key F8");
    assertEquals(name, caret(doc), "round to the first");
    tree = run(app, "key F8 shift");
    assertEquals(update, caret(doc), "and back round to the last");
    assertTrue(tree.contains("role=tooltip label=\"No WHERE\""), tree);
    tree = run(app, "key F8 shift");
    assertEquals(orders, caret(doc));

    tree = run(app, "key Escape");
    assertFalse(tree.contains("role=tooltip"), "Escape hides the message:\n" + tree);
    assertEquals(orders, caret(doc));
    tree = run(app, "key F8\n" + "key ArrowRight\n".repeat(5));
    assertTrue(tree.contains("role=tooltip label=\"No WHERE\""), "inside it:\n" + tree);
    tree = run(app, "key ArrowRight");
    assertFalse(tree.contains("role=tooltip"), "and so does leaving the diagnostic:\n" + tree);

    // With the find bar open, Escape closes the bar first, then the message.
    run(app, "key Home control\nkey F control\nfocus #query\nkey F8");
    tree = run(app, "key Escape");
    assertFalse(tree.contains("role=search"), tree);
    assertTrue(tree.contains("role=tooltip label=\"No column name\""), tree);
    tree = run(app, "key Escape");
    assertFalse(tree.contains("role=tooltip"), tree);

    doc.editor.noDiagnostics();
    run(app, "frame\nkey F8");
    assertEquals(name, caret(doc), "without diagnostics F8 does nothing");
  }

  @Test
  void aLineCommandTheAppRefusesLeavesTheSelectionWhereItWas() throws IOException {
    Doc doc = new Doc(TEXT);
    App<Doc> app = app(doc);
    run(app, "frame\nfocus #query\nkey ArrowDown\nkey ArrowRight\nkey ArrowDown shift");
    int caret = caret(doc);
    int anchor = doc.editor.anchor();
    doc.refuse = true;
    String tree = run(app, "key ArrowDown alt");
    assertEquals(TEXT, doc.text);
    assertEquals(caret, caret(doc));
    assertEquals(anchor, doc.editor.anchor());
    assertTrue(line(tree, "query").contains(" caret " + caret + " "), tree);
    doc.refuse = true;
    run(app, "key ArrowDown alt down\nkey K control shift\nkey ArrowDown alt up");
    String[] lines = TEXT.split("\n");
    assertEquals(
        String.join("\n", lines[0], lines[3]), doc.text, "a key in the same frame finds them too");
  }

  @Test
  void commandsFromOutsideReachAnEditorInAListRowTwiceInOneFrame() throws IOException {
    Doc doc = new Doc("abc\nde");
    doc.editor.select(5, 6);
    App<Doc> app = new App<>(
        doc,
        s -> col()
            .wFull()
            .hFull()
            .child(El.el().key("toolbar").focusable().h(24).onKey(key -> switch (key.name()) {
              case "F6" -> command(s, CodeEditor.Command.DELETE_LINES);
              case "F7" -> command(s, CodeEditor.Command.DUPLICATE_LINES);
              default -> false;
            }))
            .child(El.el()
                .h(200)
                .wFull()
                .rows(
                    1,
                    100,
                    index -> codeEditor(s.app, s.text, s.editor, SQL_COMMENTS, v -> change(s, v))
                        .key("query")
                        .lines(4)
                        .wFull())));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    // The first answer's selection reaches onSelect, whose rebuild keeps the row for the second.
    Script.run(
        "size 640 240\nframe\nfocus #toolbar\nframe\nkey F6 down\nkey F7 down\nframe", app, out);
    assertEquals("abc\nabc", doc.text);
  }

  private static boolean command(Doc doc, CodeEditor.Command command) {
    CodeEditor.run(doc.app, doc.editor, command);
    return true;
  }

  @Test
  void eachLineCommandIsOneStepOfTheHistory() {
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
    assertEquals(
        String.join("\n", lines[0], lines[0], lines[1], lines[2], lines[3]),
        undoAfter.apply("key D control shift"));
    assertEquals(
        String.join("\n", lines[1], lines[0], lines[2], lines[3]),
        undoAfter.apply("key ArrowDown alt"));
    assertEquals(
        String.join("\n", lines[0], lines[2], lines[1], lines[3]),
        undoAfter.apply("key ArrowDown\nkey ArrowDown\nkey ArrowUp alt"));
    assertEquals(TEXT, undoAfter.apply("key ArrowUp alt"), "the first line goes no higher");
    assertEquals(TEXT, undoAfter.apply("key End control\nkey ArrowDown alt"), "nor the last lower");
    assertEquals(
        String.join("\n", lines[0], lines[1], lines[2], lines[3], lines[3]),
        undoAfter.apply("key End control\nkey D control shift"));
    assertEquals(
        String.join("\n", lines[0], lines[1], lines[3], lines[2]),
        undoAfter.apply("key End control\nkey ArrowUp alt"));
    assertEquals(
        String.join("\n", lines[1], lines[2], lines[3]), undoAfter.apply("key K control shift"));
    assertEquals(
        String.join("\n", lines[0], lines[2], lines[3]),
        undoAfter.apply("key ArrowDown\nkey K control shift"));
    assertEquals(
        String.join("\n", lines[0], lines[1], lines[2], lines[1], lines[2], lines[3]),
        undoAfter.apply("""
            key ArrowDown
            key ArrowDown shift
            key ArrowDown shift
            key D control shift"""));
    assertEquals(String.join("\n", lines[0], lines[3], lines[1], lines[2]), undoAfter.apply("""
            key ArrowDown
            key ArrowDown shift
            key ArrowDown shift
            key ArrowDown alt"""));
    assertEquals(
        String.join("\n", lines[0], lines[1], lines[2]),
        undoAfter.apply("key End control\nkey K control shift"));
    assertEquals(String.join("\n", lines[0], lines[3]), undoAfter.apply("""
            key ArrowDown
            key ArrowDown shift
            key ArrowDown shift
            key K control shift"""));
    assertEquals(
        String.join("\n", "-- " + lines[0], lines[1], lines[2], lines[3]),
        undoAfter.apply("key / control"));
    assertEquals(
        String.join("\n", lines[0], lines[1], lines[2], "-- " + lines[3]),
        undoAfter.apply("key End control\nkey / control"));
    assertEquals(
        String.join("\n", lines[0], "orders of the Straße", lines[2], lines[3]),
        undoAfter.apply("key ArrowDown\nkey 7 control shift"),
        "Control+Shift+7, the / of a German keyboard, takes the comment away");
    assertEquals(
        String.join("\n", lines[0], "-- -- orders of the Straße", "-- " + lines[2], lines[3]),
        undoAfter.apply("""
            key ArrowDown
            key ArrowDown shift
            key End shift
            key NumpadDivide control"""));
    assertEquals(
        CodeEditor.Command.TOGGLE_COMMENT,
        CodeEditor.command(new com.rethinkj.ui.Key("#", com.rethinkj.ui.Key.CONTROL, false)),
        "Control+#, where a German keyboard has VS Code's");
    assertEquals(TEXT, undoAfter.apply("key L control alt"), "no formatter, no format");
  }
}
