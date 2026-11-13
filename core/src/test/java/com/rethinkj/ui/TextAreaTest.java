package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The keys of a multi-line field ({@link El#multiline}): line breaks, moving by rows, Home and
 * End, pages, tabs and indenting, line breaks from outside, the history across lines, input
 * methods, graphemes and the dump. {@link Areas} makes the field: 200 by 98 at 10,10, its content
 * from 14,14, five lines of 18 pixels of JetBrains Mono, whose characters advance 8 pixels.
 */
class TextAreaTest {

  private static final int SHIFT = Key.SHIFT;
  private static final int CONTROL = Key.CONTROL;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static App<Model> focused(String value) {
    return focused(value, UnaryOperator.identity());
  }

  private static App<Model> focused(String value, UnaryOperator<El> field) {
    App<Model> app = Areas.start(value, field, 1);
    focus(app);
    return app;
  }

  // Line breaks

  @Test
  void enterBreaksTheLineAndControlEnterGoesOnToTheHandlers() {
    List<String> seen = new ArrayList<>();
    App<Model> app = Areas.start(
        "ab",
        f -> f,
        1,
        root -> root.onKey(k -> {
          seen.add(k.name() + (k.modifiers() == CONTROL ? "+control" : ""));
          return true;
        }));
    focus(app);
    key(app, "ArrowRight");
    key(app, "Enter");
    assertEquals("a\n|b", show(app));
    assertEquals("a\nb", app.state().value);
    key(app, "NumpadEnter", SHIFT);
    assertEquals("a\n\n|b", show(app), "Shift+Enter breaks the line too");
    assertEquals(List.of(), seen, "the field took Enter: an ancestor's Enter does not run");
    key(app, "Enter", CONTROL);
    assertEquals(List.of("Enter+control"), seen, "Control+Enter runs what the app runs with it");
    assertEquals("a\n\n|b", show(app));
  }

  @Test
  void aReadOnlyFieldTakesEnterAndChangesNothing() {
    App<Model> app = focused("ab", f -> f.readOnly());
    key(app, "Enter");
    assertEquals("|ab", show(app));
    assertEquals(List.of(), app.state().edits);
  }

  // Moving by rows

  @Test
  void upAndDownMoveByRowsAndKeepTheirColumnOverShorterLines() {
    App<Model> app = focused("SELECT name\nFROM t\n\nWHERE id = 42");
    assertEquals("|SELECT name\nFROM t\n\nWHERE id = 42", show(app), "a text area starts at 0");
    key(app, "End");
    key(app, "ArrowLeft");
    key(app, "ArrowLeft"); // column 9
    key(app, "ArrowDown");
    assertEquals("SELECT name\nFROM t|\n\nWHERE id = 42", show(app), "the end of a shorter line");
    key(app, "ArrowDown");
    assertEquals("SELECT name\nFROM t\n|\nWHERE id = 42", show(app), "an empty line");
    key(app, "ArrowDown");
    assertEquals("SELECT name\nFROM t\n\nWHERE id |= 42", show(app), "back at column 9");
    key(app, "ArrowDown");
    assertEquals("SELECT name\nFROM t\n\nWHERE id = 42|", show(app), "the last line: its end");
    key(app, "ArrowUp");
    key(app, "ArrowUp");
    key(app, "ArrowUp");
    assertEquals(
        "SELECT na|me\nFROM t\n\nWHERE id = 42",
        show(app),
        "the end of the last line kept the column");
    key(app, "End");
    key(app, "ArrowDown");
    key(app, "ArrowDown");
    key(app, "ArrowDown");
    assertEquals(
        "SELECT name\nFROM t\n\nWHERE id = |42", show(app), "a horizontal move sets a new column");
    key(app, "ArrowUp");
    key(app, "ArrowUp");
    key(app, "ArrowUp");
    key(app, "ArrowUp");
    assertEquals("|SELECT name\nFROM t\n\nWHERE id = 42", show(app), "the first line: its start");
    key(app, "ArrowRight");
    key(app, "ArrowDown", SHIFT);
    key(app, "ArrowDown", SHIFT);
    assertEquals("S^ELECT name\nFROM t\n|\nWHERE id = 42", show(app), "Shift selects");
    key(app, "ArrowUp");
    assertEquals(
        "SELECT name\nF|ROM t\n\nWHERE id = 42",
        show(app),
        "without Shift from the caret, and the selection is gone");
  }

  @Test
  void aLastLineWithALineFeedIsAnEmptyLine() {
    App<Model> app = focused("ab\n");
    key(app, "ArrowDown");
    assertEquals("ab\n|", show(app));
    key(app, "ArrowDown");
    assertEquals("ab\n|", show(app));
    assertTrue(Areas.line(app).contains(" lines 1-2/2 "), Areas.line(app));
  }

  @Test
  void homeGoesToTheRowThenToTheIndentAndBackAndControlToTheEnds() {
    App<Model> app = focused("SELECT\n    name,\n    id");
    key(app, "ArrowDown");
    key(app, "End");
    assertEquals("SELECT\n    name,|\n    id", show(app));
    key(app, "Home");
    assertEquals("SELECT\n|    name,\n    id", show(app), "the start of the row");
    key(app, "Home");
    assertEquals("SELECT\n    |name,\n    id", show(app), "a second Home: the indent");
    key(app, "Home");
    assertEquals("SELECT\n|    name,\n    id", show(app), "and back");
    key(app, "End", SHIFT);
    assertEquals("SELECT\n^    name,|\n    id", show(app));
    key(app, "End", CONTROL);
    assertEquals("SELECT\n    name,\n    id|", show(app));
    key(app, "Home", CONTROL | SHIFT);
    assertEquals("|SELECT\n    name,\n    id^", show(app));
  }

  @Test
  void pageDownAndPageUpMoveByTheRowsInViewAndScrollAsFar() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      text.append("line ").append(i).append('\n');
    }
    App<Model> app = focused(text.toString());
    key(app, "ArrowRight");
    key(app, "PageDown");
    assertEquals(5, caretLine(app), "five rows in view");
    assertEquals(90, field(app).scrollY(), "and the view moved by them");
    key(app, "PageDown", SHIFT);
    assertEquals(10, caretLine(app));
    assertEquals(
        1,
        field(app).editor().anchor() - field(app).editor().text().indexOf("line 5"),
        "Shift selects, and the column is kept");
    key(app, "PageUp");
    assertEquals(5, caretLine(app));
    for (int i = 0; i < 10; i++) {
      key(app, "PageDown");
    }
    assertEquals(app.state().value.length(), field(app).editor().caret(), "the end");
    key(app, "PageUp", SHIFT);
    key(app, "PageUp", SHIFT);
    key(app, "PageUp", SHIFT);
    key(app, "PageUp", SHIFT);
    key(app, "PageUp", SHIFT);
    key(app, "PageUp", SHIFT);
    key(app, "PageUp", SHIFT);
    assertEquals(0, field(app).editor().caret(), "the start");
    assertEquals(0, field(app).scrollY());
  }

  @Test
  void wordsAndDeletingGoAcrossLineFeeds() {
    App<Model> app = focused("one two\n\nthree");
    key(app, "End", CONTROL);
    key(app, "ArrowLeft", CONTROL);
    key(app, "ArrowLeft", CONTROL);
    assertEquals("one |two\n\nthree", show(app), "over the empty line");
    key(app, "End"); // Control+ArrowRight would go on to "three" on Windows (Editor.WORD_ENDS)
    key(app, "ArrowRight");
    assertEquals("one two\n|\nthree", show(app), "over a line feed");
    key(app, "Delete");
    assertEquals("one two\n|three", show(app), "Delete at the end of a line joins the next");
    key(app, "Backspace");
    assertEquals("one two|three", show(app), "Backspace at the start of a line joins it");
    key(app, "Z", CONTROL);
    assertEquals("one two\n|\nthree", show(app), "one run of deleting");
  }

  @Test
  void wrappedLinesMoveByRows() {
    // 24 characters a row: "the quick brown fox and ", "jumps over the dog and ", "the lazy
    // cat today".
    String text = "the quick brown fox and jumps over the dog and the lazy cat today\nend";
    App<Model> app = focused(text, f -> f.wrapText());
    key(app, "ArrowRight");
    key(app, "ArrowRight");
    key(app, "ArrowDown");
    Editor editor = field(app).editor();
    assertEquals(text.indexOf("jumps") + 2, editor.caret(), "the second row of the line");
    key(app, "ArrowDown");
    key(app, "ArrowDown");
    assertEquals(text.indexOf("end") + 2, editor.caret(), "the next line after its last row");
    key(app, "ArrowUp");
    key(app, "End");
    assertEquals(text.indexOf(" today") + 6, editor.caret(), "the end of the last row");
    key(app, "ArrowUp");
    key(app, "End");
    assertEquals(
        text.indexOf(" the lazy"),
        editor.caret(),
        "a wrapped row ends before the space it wraps at");
    key(app, "Home");
    assertEquals(text.indexOf("jumps"), editor.caret(), "the start of the row");
    key(app, "Home");
    assertEquals(text.indexOf("jumps"), editor.caret(), "a row inside a line has no indent");
    assertTrue(Areas.line(app).contains(" lines 1-2/2 "), Areas.line(app));
    assertEquals(0, field(app).horizontalBar() == null ? 0 : 1, "no scrolling sideways");
  }

  @Test
  void aLongLineScrollsSidewaysToKeepTheCaretInView() {
    String text = "short\n" + "x".repeat(300) + "\nshort";
    App<Model> app = focused(text);
    key(app, "ArrowDown");
    key(app, "End");
    El f = field(app);
    assertTrue(f.scrollX() > 2000, "the end of 300 characters: " + f.scrollX());
    float caret = f.text().caret().x();
    assertTrue(caret > 180 && caret <= 192 - Editor.MARGIN, "near the right edge: " + caret);
    key(app, "ArrowDown");
    assertEquals(text.length(), f.editor().caret());
    assertEquals(
        40 - Editor.MARGIN, field(app).scrollX(), "the end of a short line, just far enough");
    key(app, "ArrowUp");
    assertTrue(
        field(app).scrollX() > 2000, "the column of the end kept: " + field(app).scrollX());
  }

  // Tabs

  @Test
  void tabMovesTheFocusUnlessTheFieldIndents() {
    App<Model> app = focused("ab");
    key(app, "Tab");
    assertEquals("other", app.focused().key(), "as everywhere else");
    app = focused("ab", f -> f.tabIndents(false));
    key(app, "Tab");
    assertEquals("\t|ab", show(app));
    assertEquals("f", app.focused().key());
  }

  @Test
  void tabInsertsATabOrSpacesUpToTheNextStopAndATabAdvancesToIt() {
    App<Model> app = focused("ab", f -> f.tabIndents(false));
    key(app, "ArrowRight");
    key(app, "Tab");
    assertEquals("a\t|b", show(app));
    Text text = field(app).text();
    assertEquals(32, text.caret().x(), "the tab reaches the stop of four spaces");
    key(app, "Tab");
    assertEquals(64, field(app).text().caret().x(), "and the next");
    app = focused("ab", f -> f.tabIndents(true).tabSize(3));
    key(app, "ArrowRight");
    key(app, "Tab");
    assertEquals("a  |b", show(app), "spaces up to the stop at 3");
    key(app, "Tab");
    assertEquals("a     |b", show(app));
    key(app, "Z", CONTROL);
    assertEquals("a|b", show(app), "typing tabs is a run of typing");
  }

  @Test
  void tabAndShiftTabIndentAndOutdentTheLinesOfTheSelection() {
    App<Model> app = focused("a\n\n  b\nc", f -> f.tabIndents(true));
    key(app, "ArrowRight");
    key(app, "ArrowDown", SHIFT);
    key(app, "ArrowDown", SHIFT);
    assertEquals("a^\n\n | b\nc", show(app), "the column of the caret kept");
    key(app, "Tab");
    assertEquals("    a^\n\n     | b\nc", show(app), "empty lines stay empty");
    key(app, "Tab", SHIFT);
    assertEquals("a^\n\n | b\nc", show(app));
    key(app, "Tab", SHIFT);
    assertEquals("a^\n\n|b\nc", show(app), "as far as there are spaces");
    key(app, "Z", CONTROL);
    assertEquals("a^\n\n | b\nc", show(app), "each one step");
    key(app, "Z", CONTROL);
    assertEquals("    a^\n\n     | b\nc", show(app));
    key(app, "Y", CONTROL);
    key(app, "Y", CONTROL);
    assertEquals("a^\n\n|b\nc", show(app));

    app = focused("\tx\n\ty", f -> f.tabIndents(false));
    key(app, "End", CONTROL | SHIFT);
    key(app, "Tab");
    assertEquals(
        "^\t\tx\n\t\ty|", show(app), "a selection from the start of a line keeps the indent in it");
    key(app, "Tab", SHIFT);
    key(app, "Tab", SHIFT);
    key(app, "Tab", SHIFT);
    assertEquals("^x\ny|", show(app));
    assertEquals(3, app.state().edits.size(), "the last outdent had nothing to take");

    app = focused("a\nb\nc", f -> f.tabIndents(true).tabSize(2));
    key(app, "ArrowDown", SHIFT);
    key(app, "Tab");
    assertEquals("^  a\n|b\nc", show(app), "a selection that ends at a line start leaves it out");
  }

  @Test
  void escapeAndTabOrControlMLeaveAFieldThatIndents() {
    App<Model> app = focused("ab", f -> f.tabIndents(true));
    key(app, "Escape");
    key(app, "Tab");
    assertEquals("other", app.focused().key(), "Escape, then Tab");
    app = focused("ab", f -> f.tabIndents(true));
    key(app, "Escape");
    key(app, "ArrowRight");
    key(app, "Tab");
    assertEquals("a   |b", show(app), "only right after Escape: spaces to the stop at 4");
    key(app, "M", CONTROL);
    key(app, "Tab");
    assertEquals("other", app.focused().key(), "Control+M: Tab moves the focus");
    focus(app);
    key(app, "Tab");
    assertEquals("other", app.focused().key(), "until Control+M again");
    focus(app);
    key(app, "M", CONTROL);
    key(app, "Tab");
    assertEquals("f", app.focused().key());
    app = focused("ab", f -> f.tabIndents(true).readOnly());
    key(app, "Tab");
    assertEquals("other", app.focused().key(), "a read-only field leaves Tab to the focus");
  }

  @Test
  void escapeThenShiftTabLeavesAFieldThatIndentsThoughShiftComesAsAKeyOfItsOwn() {
    App<Model> app = focused("ab", f -> f.tabIndents(true));
    key(app, "Escape");
    key(app, "Shift", SHIFT); // a keyboard reports the modifier's own key before the Tab
    key(app, "Tab", SHIFT);
    assertEquals("other", app.focused().key(), "Escape, then Shift+Tab");
    assertEquals("ab", app.state().value, "nothing outdented");
  }

  @Test
  void tabIntoATextAreaKeepsItsCaretInsteadOfSelectingAll() {
    App<Model> app = Areas.start("one\ntwo");
    key(app, "Tab");
    assertEquals("f", app.focused().key());
    assertEquals("|one\ntwo", show(app));
  }

  // Line breaks from outside

  @Test
  void pastedTypedAndTheAppsLineBreaksBecomeLineFeeds() {
    App<Model> app = focused("a\r\nb\rc");
    assertEquals("|a\nb\nc", show(app), "shown with line feeds");
    assertTrue(Areas.line(app).contains("\"a\\nb\\nc\""), Areas.line(app));
    assertEquals("a\r\nb\rc", app.state().value, "the app's string until an edit");
    app.clipboard().write("x\r\ny z");
    key(app, "V", CONTROL);
    assertEquals("x\ny\nz|a\nb\nc", show(app));
    assertEquals("x\ny\nza\nb\nc", app.state().value, "the first edit hands back line feeds");
    type(app, "1\r2");
    assertEquals("x\ny\nz1\n2|a\nb\nc", show(app));
  }

  // History

  @Test
  void undoAndRedoGoBackAndForthOverEditsOfSeveralLines() {
    App<Model> app = focused("one\ntwo\nthree");
    key(app, "ArrowDown");
    key(app, "End", SHIFT);
    key(app, "ArrowDown", SHIFT);
    assertEquals("one\n^two\nthr|ee", show(app));
    type(app, "X");
    assertEquals("one\nX|ee", show(app));
    key(app, "Enter");
    type(app, "Y");
    assertEquals("one\nX\nY|ee", show(app));
    key(app, "Z", CONTROL);
    assertEquals("one\nX\n|ee", show(app));
    key(app, "Z", CONTROL);
    assertEquals("one\nX|ee", show(app), "Enter is a step of its own");
    key(app, "Z", CONTROL);
    assertEquals("one\n^two\nthr|ee", show(app), "with the selection");
    key(app, "Y", CONTROL);
    key(app, "Y", CONTROL);
    key(app, "Y", CONTROL);
    assertEquals("one\nX\nY|ee", show(app));
    assertEquals("one\nX\nYee", app.state().value);
    assertEquals(3, field(app).editor().lineIndex().count(), "the lines follow undo and redo");
  }

  @Test
  void aPasteOfAThousandLinesIsOneStepThatHoldsOnlyWhatItInserted() {
    App<Model> app = focused("before\nafter");
    key(app, "ArrowDown");
    StringBuilder pasted = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      pasted.append("SELECT ").append(i).append(";\n");
    }
    app.clipboard().write(pasted.toString());
    key(app, "V", CONTROL);
    assertEquals(1002, field(app).editor().lineIndex().count());
    assertEquals("before\n" + pasted + "|after", show(app));
    key(app, "Z", CONTROL);
    assertEquals("before\n|after", show(app));
    assertEquals(2, field(app).editor().lineIndex().count());
    key(app, "Y", CONTROL);
    assertEquals("before\n" + pasted + "|after", show(app));
    assertEquals(1002, field(app).editor().lineIndex().count());
  }

  @Test
  void anEditTheAppChangesBecomesOneStepToWhatItMade() {
    App<Model> app = focused("ab\ncd");
    app.state().accept = s -> s.contains("x") ? s.toUpperCase(java.util.Locale.ROOT) : s;
    key(app, "ArrowDown");
    type(app, "x");
    assertEquals("AB\nX|CD", show(app));
    key(app, "Z", CONTROL);
    assertEquals("ab\n|cd", show(app));
    app.state().accept = s -> null;
    key(app, "Y", CONTROL);
    assertEquals("ab\n|cd", show(app), "a refused redo leaves it");
  }

  // Input methods, graphemes and fonts

  @Test
  void aCompositionOnAMiddleLineShowsThereAndTheInputAreaIsItsRow() {
    App<Model> app = focused("one\ntwo\nthree");
    key(app, "ArrowDown");
    key(app, "End");
    app.textComposition("ni", -1);
    app.frame();
    assertTrue(Areas.line(app).endsWith("focused caret 7 (2:4) composing \"ni\""), Areas.line(app));
    Text text = field(app).text();
    float y = text.composition().y();
    assertTrue(y > 18 && y < 36, "under the second row: " + y);
    App.InputArea area = app.inputArea();
    assertEquals(10 + 4 + 18, area.y(), "the caret's row in the window");
    assertEquals(18, area.height());
    assertEquals(4 + 5 * 8, area.cursor(), "after the composition");
    app.textInput("你");
    app.frame();
    assertEquals("one\ntwo你|\nthree", show(app));
  }

  @Test
  void graphemesAndFallbackFontsWorkOnEveryLine() {
    App<Model> app = focused(
        "é\n👍🏽\nשלום x",
        f -> f.font(Font.mono().withFallback(Font.sans(), Examples.fallbackChain())));
    key(app, "ArrowRight");
    assertEquals("é|\n👍🏽\nשלום x", show(app), "a letter with its mark");
    key(app, "ArrowRight");
    key(app, "ArrowRight");
    assertEquals("é\n👍🏽|\nשלום x", show(app), "an emoji with its tone");
    key(app, "Backspace");
    assertEquals("é\n|\nשלום x", show(app));
    key(app, "ArrowDown");
    key(app, "End");
    float end = field(app).text().caret().x();
    assertTrue(end > 40, "Hebrew from a fallback font, then x: " + end);
    key(app, "Home");
    key(app, "ArrowUp");
    key(app, "ArrowUp");
    assertEquals("|é\n\nשלום x", show(app));
  }

  // The tree dump

  @Test
  void theDumpShowsTheLinesInViewTheCaretsLineAndColumnAndAShortenedLongText() {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 40; i++) {
      text.append("line ").append(i).append('\n');
    }
    App<Model> app = focused(text.toString());
    String line = Areas.line(app);
    assertTrue(line.startsWith("text #f role=textbox \"line 1\\nline 2\\n"), line);
    assertTrue(
        line.contains("\\nline 13\\nline \" … \"line 36\\nline 37\\nline 38\\nline 39"
            + "\\nline 40\\n\" length 311 10,10 200x98 lines 1-5/41 focused caret 0 (1:1)"),
        line);
    for (int i = 0; i < 22; i++) {
      key(app, "ArrowDown");
    }
    key(app, "End");
    line = Areas.line(app);
    assertTrue(line.contains(" lines 19-23/41 scroll 0,324 focused"), line);
    assertTrue(line.endsWith(" focused caret 174 (23:8)"), line);
  }

  @Test
  void aShortMultiLineFieldIsFoundByItsText() throws Exception {
    App<Model> app = Areas.start("a\tb\nc");
    assertTrue(Areas.line(app).contains("\"a\\tb\\nc\""), Areas.line(app));
    java.nio.file.Path out = java.nio.file.Files.createTempDirectory("area");
    Script.run("""
        size 320 240
        frame
        press text="a\\tb\\nc"
        frame
        snap pressed
        """, app, out);
    assertTrue(
        java.nio.file.Files.readString(out.resolve("pressed.tree.txt")).contains("focused caret"));
  }

  // Masking

  @Test
  void aMaskedFieldCannotBeMultiline() {
    IllegalStateException one = assertThrows(
        IllegalStateException.class,
        () -> El.text("").editable(s -> {}).masked().multiline());
    assertTrue(one.getMessage().contains("a password has one line"), one.getMessage());
    assertThrows(
        IllegalStateException.class,
        () -> El.text("").editable(s -> {}).multiline().masked());
    El shown = El.text("").editable(s -> {}).multiline().masked(false);
    assertTrue(shown.text().multiline());
  }

  // Scrolling

  @Test
  void theNextKeyBringsBackACaretThatTheWheelScrolledAway() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append("line ").append(i).append('\n');
    }
    App<Model> app = focused(text.toString());
    app.wheel(50, 50, 0, 300);
    app.frame();
    assertEquals(300, field(app).scrollY());
    assertTrue(field(app).text().caret().y() < -200, "the caret is far above the view");
    key(app, "ArrowRight");
    assertEquals(0, field(app).scrollY(), "the caret's row at the top again");
    assertNotEquals(null, field(app).text().caret());
  }

  private static int caretLine(App<Model> app) {
    Editor editor = field(app).editor();
    return editor.lineIndex().line(editor.caret());
  }
}
