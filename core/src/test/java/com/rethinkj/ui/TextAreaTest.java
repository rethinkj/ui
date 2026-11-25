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
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  void aReadOnlyFieldLeavesEnterToTheHandlers() {
    List<String> seen = new ArrayList<>();
    App<Model> app = Areas.start(
        "ab",
        El::readOnly,
        1,
        root -> root.onKey(k -> {
          seen.add(k.name() + (k.modifiers() == SHIFT ? "+shift" : ""));
          return true;
        }));
    focus(app);
    key(app, "Enter");
    key(app, "NumpadEnter", SHIFT);
    assertEquals(List.of("Enter", "NumpadEnter+shift"), seen, "an ancestor's Enter runs");
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
  void aWrappedWordLongerThanTheRowBreaksAndCaretsKeysPressesAndSelectionFollowItsRows() {
    // 24 characters a row: "word ", then the 500 letters 24 a row, the last 20 with " end".
    StringBuilder letters = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      letters.append((char) ('a' + i % 26));
    }
    String text = "word " + letters + " end";
    App<Model> app = focused(text, f -> f.wrapText().lines(25));
    app.resize(320, 600, 1);
    app.frame();
    El f = field(app);
    Editor editor = f.editor();
    Text t = f.text();
    assertEquals(22, t.shownIndex().totalRows(), "the word's row, 21 of letters");
    for (int row = 1; row <= 21; row++) {
      int start = 5 + 24 * (row - 1);
      assertEquals(row * 18.0, t.caretTop(start), "row " + row + " starts at " + start);
      assertEquals((row - 1) * 18.0, t.caretTop(start - 1), "the letter before on the row above");
      assertEquals(start, t.rowStart(start + 3));
    }
    key(app, "Home", CONTROL);
    key(app, "ArrowRight");
    key(app, "ArrowRight");
    key(app, "ArrowDown");
    assertEquals(7, editor.caret(), "the second row, two letters in");
    for (int row = 2; row <= 21; row++) {
      key(app, "ArrowDown");
      assertEquals(5 + 24 * (row - 1) + 2, editor.caret(), "down to row " + row);
    }
    key(app, "ArrowDown");
    assertEquals(text.length(), editor.caret(), "below the last row: the end");
    key(app, "ArrowUp", CONTROL);
    key(app, "Home", CONTROL);
    key(app, "ArrowDown");
    key(app, "End");
    assertEquals(5 + 23, editor.caret(), "a row broken inside the word ends before its last");
    key(app, "ArrowRight");
    assertEquals(29, editor.caret());
    assertEquals(36.0, t.caretTop(29), "after the last letter: the start of the next row");
    for (int row = 21; row >= 2; row--) {
      key(app, "ArrowUp");
    }
    key(app, "ArrowUp");
    assertEquals(0, editor.caret(), "above the first row: the start");

    // A press on each row, three letters in.
    for (int row = 1; row <= 21; row++) {
      float x = 14 + 8 * 3 + 2;
      float y = 14 + 18 * row + 9;
      app.pointerDown(x, y, 1, 0);
      app.pointerUp(x, y);
      app.frame();
      assertEquals(5 + 24 * (row - 1) + 3, editor.caret(), "a press on row " + row);
    }
    // From the second letter of row 1 to the fourth of row 4: a rectangle per row.
    app.pointerDown(14 + 8 + 2, 14 + 18 + 9, 1, 0);
    app.pointerMove(14 + 8 * 4 + 2, 14 + 18 * 4 + 9);
    app.pointerUp(14 + 8 * 4 + 2, 14 + 18 * 4 + 9);
    app.frame();
    assertEquals(6, editor.anchor());
    assertEquals(5 + 24 * 3 + 4, editor.caret());
    List<Text.Box> boxes = field(app).text().selection();
    assertEquals(4, boxes.size(), boxes.toString());
    assertEquals(8, boxes.getFirst().x(), "from the second letter");
    assertEquals(192, boxes.getFirst().x() + boxes.getFirst().width(), "to the box's end");
    assertEquals(32, boxes.getLast().x() + boxes.getLast().width(), "to the fourth letter");
  }

  @Test
  void randomEditsWithLongWordsKeepTheRowsAFreshCountGives() {
    java.util.Random random = new java.util.Random(5);
    for (int round = 0; round < 8; round++) {
      App<Model> app =
          focused(randomWords(random, 1 + random.nextInt(6)), f -> f.wrapText().fitLines());
      for (int edit = 0; edit < 6; edit++) {
        String text = field(app).editor().text();
        int at = random.nextInt(text.length() + 1);
        int to = at + random.nextInt(Math.min(text.length() - at, 40) + 1);
        String inserted = randomWords(random, random.nextInt(3));
        app.edit(field(app), at, to, inserted, at + inserted.length());
        app.frame();
        String now = field(app).editor().text();
        assertEquals(
            monoRows(now), field(app).text().shownIndex().totalRows(), TreeDumper.quote(now));
      }
    }
  }

  @Test
  void aFrameShapesOnlyTheLinesInViewOfALongText() {
    App<Model> app = focused("SELECT c FROM t WHERE id = 1;\n".repeat(5000));
    assertTrue(field(app).editor().lineIndex().shaped < 40, "the first frames shaped the view");
    app.scrollTo(field(app), 0, 2500 * 18.0);
    app.frame();
    app.pointerDown(30, 30, 1, 0);
    app.pointerUp(30, 30);
    app.frame();
    for (int i = 0; i < 5; i++) {
      Lines lines = field(app).editor().lineIndex();
      int before = lines.shaped;
      app.textInput("x");
      app.frame();
      // Five rows in view and three above and below, for each of up to two layouts, and the
      // caret's line.
      assertTrue(lines.shaped - before <= 28, "a frame shaped " + (lines.shaped - before));
    }
    assertEquals(5001, field(app).editor().lineIndex().count());
  }

  @Test
  void aMillionCharacterLineShapesOnlyThePartInViewAndReachesItsEnd() {
    String line = "START " + "abcd ".repeat(199_996) + "12345678901END";
    assertEquals(1_000_000, line.length());
    App<Model> app = focused(line);
    Lines lines = field(app).editor().lineIndex();
    assertTrue(
        lines.shapedCharacters < 20_000,
        "first frames shaped " + lines.shapedCharacters + " characters");

    key(app, "End");
    assertEquals(line.length(), field(app).editor().caret());
    assertTrue(
        field(app).scrollX() > 7_000_000, "scrolled to end: " + field(app).scrollX());
    Text.Box caret = field(app).text().caret();
    assertTrue(caret.x() > 170 && caret.x() <= 192 - Editor.MARGIN, "caret in view: " + caret);
    assertTrue(
        lines.shapedCharacters < 30_000,
        "moving to end shaped " + lines.shapedCharacters + " characters");

    GlyphRun endGlyphs = Font.mono().shape("END", 14, 1, new String[0]);
    Text.Line tail = field(app).text().lines().getLast();
    GlyphRun tailRun = tail.glyphs();
    for (int i = 0; i < 3; i++) {
      assertEquals(
          endGlyphs.glyph(i),
          tailRun.glyph(tailRun.count() - 3 + i),
          "end glyph " + i + " laid out");
    }
    float tailRight = tail.x() + tail.width() - field(app).text().scroll();
    assertTrue(tailRight > 170 && tailRight <= 192, "end of line in view: " + tailRight);

    long beforeType = lines.shapedCharacters;
    type(app, "!");
    assertEquals(line.length() + 1, field(app).editor().caret());
    assertTrue(app.state().value.endsWith("END!"));
    assertTrue(
        lines.shapedCharacters - beforeType < 20_000,
        "typing at end shaped " + (lines.shapedCharacters - beforeType) + " characters");

    // Horizontal scrolling back to the start and then to the end lays out the last glyphs there.
    app.scrollTo(field(app), 0, 0);
    app.frame();
    app.scrollTo(field(app), 1e9, 0);
    app.frame();
    assertTrue(
        field(app).scrollX() > 7_000_000, "scrolled back to end: " + field(app).scrollX());
    GlyphRun bang = Font.mono().shape("!", 14, 1, new String[0]);
    Text.Line endLine = field(app).text().lines().getLast();
    assertEquals(
        bang.glyph(0),
        endLine.glyphs().glyph(endLine.glyphs().count() - 1),
        "scrolled end lays out the last character");
    assertTrue(
        lines.shapedCharacters < 60_000,
        "all operations shaped " + lines.shapedCharacters + " characters");
  }

  @Test
  void aLongLineHighlightsAndHitsInChunksWithoutWrappingAndWrapsAsBefore() {
    String line = "alpha ".repeat(4000) + "OMEGA";
    int accent = Colors.rgb(0xFF0000);
    int markBg = Colors.rgb(0x00FF00);
    Highlighter highlighter = (text, state, out) -> {
      out.color(text.length() - 5, text.length(), accent);
      return null;
    };
    Decorator decorator = (lineIndex, lineStart, text, out) -> {
      out.background(0, 5, markBg);
      out.background(text.length() - 5, text.length(), markBg);
    };
    App<Model> app = focused(line, f -> f.highlight("h", highlighter).decorate(decorator));
    Lines lines = field(app).editor().lineIndex();
    assertTrue(lines.shapedCharacters < 5000, "start shaped " + lines.shapedCharacters);
    assertEquals(1, field(app).text().marks().size(), "only the visible mark at the start");

    long beforeEnd = lines.shapedCharacters;
    key(app, "End");
    Text text = field(app).text();
    Text.Line last = text.lines().getLast();
    assertEquals(accent, last.colors()[last.colors().length - 1], "highlighter color at end");
    assertEquals(1, text.marks().size(), "only the visible mark at the end");
    assertTrue(
        lines.shapedCharacters - beforeEnd < 2500,
        "End shaped " + (lines.shapedCharacters - beforeEnd));

    // Click inside the end of the line: hits the right character without shaping the whole line.
    long beforeClick = lines.shapedCharacters;
    float endX = 14 + text.caret().x() - 16; // two characters left of the end
    app.pointerDown(endX, 14 + 9, 1, 0);
    app.pointerUp(endX, 14 + 9);
    app.frame();
    assertEquals(line.length() - 2, field(app).editor().caret());
    assertTrue(
        lines.shapedCharacters - beforeClick < 2500,
        "click shaped " + (lines.shapedCharacters - beforeClick));

    // With wrapping, a line longer than a chunk wraps into rows as before.
    String wrapped = "abcd ".repeat(600);
    App<Model> wrapping = focused(wrapped, El::wrapText);
    assertEquals(monoRows(wrapped), field(wrapping).text().shownIndex().totalRows());
  }

  @Test
  void aLongLineInAProportionalFontShowsItsEndAndTheCaretOnItsGlyphs() {
    String line = "Wide words, thin ones; ".repeat(20_000) + "END";
    App<Model> app = focused(line, f -> f.font(Font.sans()));
    app.scrollTo(field(app), 1e9, 0);
    app.frame();
    Text text = field(app).text();
    Text.Line tail = text.lines().getLast();
    float right = tail.x() + tail.width() - text.scroll();
    assertTrue(right > 0 && right <= text.contentWidth(), "end of line in view: " + right);

    // The anchor's chunk is measured before the glyphs are placed around the caret.
    app.select(field(app), 16_000, 8_000);
    app.frame();
    Text selected = field(app).text();
    float caret = selected.caret().x();
    assertTrue(caret >= 0 && caret <= selected.contentWidth(), "caret in view: " + caret);
    assertTrue(
        selected.lines().stream()
            .anyMatch(l -> l.x() - selected.scroll() <= caret
                && caret <= l.x() + l.width() - selected.scroll()),
        "glyphs laid out under the caret");
  }

  /** Words of 1 to 4 letters and some of 25 to 80, with spaces and line feeds between them. */
  private static String randomWords(java.util.Random random, int words) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < words; i++) {
      int length = random.nextInt(4) == 0 ? 25 + random.nextInt(56) : 1 + random.nextInt(4);
      text.append("ab".repeat(length).substring(0, length));
      text.append(random.nextInt(5) == 0 ? "\n" : " ".repeat(1 + random.nextInt(2)));
    }
    return text.toString();
  }

  /**
   * The rows of {@link Areas}'s field for a text of letters, spaces and line feeds, 24 characters
   * a row: words while they fit, spaces at a row's end taking no room, a word longer than a row on
   * rows of its own, 24 letters each, and what follows its last part on that row while it fits.
   */
  private static long monoRows(String text) {
    long rows = 0;
    for (String line : text.split("\n", -1)) {
      int used = 0; // letters and spaces on the row so far
      boolean empty = true;
      rows++;
      for (String word : line.split("(?<= )(?=[^ ])")) {
        int letters = word.stripTrailing().length();
        int spaces = word.length() - letters;
        if (!empty && used + letters > 24) {
          rows++;
          used = 0;
        }
        while (used + letters > 24) {
          rows++;
          letters -= 24 - used;
          used = 0;
        }
        used += letters + spaces;
        empty = false;
      }
    }
    return rows;
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
  void enterKeepsTheIndentOfTheLineInAFieldThatIndents() {
    App<Model> app = focused("\t  ab\ncd", f -> f.tabIndents(true));
    key(app, "End");
    key(app, "Enter");
    assertEquals("\t  ab\n\t  |\ncd", show(app), "the tab and spaces before the caret");
    type(app, "x");
    key(app, "Z", CONTROL);
    key(app, "Z", CONTROL);
    assertEquals("\t  ab|\ncd", show(app), "Enter with its indent is one step");

    key(app, "Home");
    key(app, "ArrowRight");
    key(app, "Enter");
    assertEquals("\t\n\t|  ab\ncd", show(app), "no more than there is before the caret");

    app = focused("  ab\ncd", f -> f.tabIndents(true));
    key(app, "ArrowRight");
    key(app, "ArrowRight");
    key(app, "ArrowRight");
    key(app, "ArrowDown", SHIFT);
    key(app, "Enter");
    assertEquals("  a\n  |", show(app), "a selection: the indent of the line where it starts");

    app = focused("  ab");
    key(app, "End");
    key(app, "Enter");
    assertEquals("  ab\n|", show(app), "a field that does not indent breaks the line only");
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

  @Test
  void aJumpInALongWrappedTextShowsTheCaretsRowAfterTheRowsAboveAreMeasured() {
    // Words of 13 characters, one to a row of 24: each line takes more rows than its length tells.
    String line = "abcdefghijkl ".repeat(8) + "\n";
    App<Model> app = focused(line.repeat(200), f -> f.wrapText());
    int caret = line.length() * 150 + 40;
    app.select(field(app), caret, caret);
    app.frame();
    float top = field(app).text().caret().y();
    assertTrue(top >= 0 && top + 18 <= 90, "the caret's row in view: " + top);

    // Spaces at a row's end take no room: each line takes fewer rows than its length tells. The
    // match comes into view below what covers the field's top, 25 pixels of its content box.
    line = "x" + " ".repeat(200) + "\n";
    app = focused(line.repeat(200), f -> f.wrapText().coveredTop(29));
    int start = line.length() * 150;
    app.select(field(app), start + 1, start);
    app.frame();
    top = field(app).text().caret().y();
    assertTrue(top >= 25 && top + 18 <= 90, "the match's row below the cover: " + top);

    // A match in view over three lines, of two rows each at 36 characters a row. In the same frame
    // the field narrows to 24, where they take three rows, but the estimate still says two: the
    // jump measures only the lines of the match's ends, the middle one pushes the end down.
    line = "abcdefghijklmnop abcdefghijklmnop abcdefghijklmn\n";
    app = focused(line.repeat(200), f -> f.wrapText().wFull().lines(10).coveredTop(29));
    app.scrollTo(field(app), 0, 99 * 36);
    app.frame();
    app.resize(220, 240, 1);
    start = line.length() * 101 + 34; // the last row of line 101, to the start of line 103
    app.select(field(app), start, line.length() * 103 + 1);
    app.frame();
    Text t = field(app).text();
    double first = t.caretTop(start) - t.scrollY();
    double last = t.caretTop(line.length() * 103) - t.scrollY();
    assertTrue(
        first >= 25 && last + 18 <= 180, "the match below the cover: " + first + ", " + last);
  }

  private static int caretLine(App<Model> app) {
    Editor editor = field(app).editor();
    return editor.lineIndex().line(editor.caret());
  }

  @Test
  void aPressInTheFrameOfAJumpBelowTheCoverPutsTheCaretUnderThePointer(@TempDir Path out)
      throws IOException {
    // F3 jumps to line 30, as a find bar's next match: the pointer then presses at 30, left of a
    // bar that covers the field's top. The press sees the view after the jump, and the jump alone
    // keeps the match below the cover: the row pressed stays under the pointer.
    AtomicReference<App<Model>> app = new AtomicReference<>();
    app.set(Areas.start(
        "line\n".repeat(40),
        f -> f.coveredTop(29),
        1,
        root -> root.onKey(k -> {
          if (!k.name().equals("F3")) {
            return false;
          }
          app.get().select(field(app.get()), 30 * 5 + 4, 30 * 5);
          return true;
        })));
    focus(app.get());
    Script.run("size 320 240\nframe\nkey F3\npress 30,30\nframe", app.get(), out);
    Text.Box caret = field(app.get()).text().caret();
    float pointer = 30 - 14; // in the content box, inside a padding of 4 at 10,10
    assertTrue(
        caret.y() <= pointer && pointer < caret.y() + caret.height(),
        "the caret's row under the pointer: " + caret);
    assertEquals(26, caretLine(app.get()), "the press, on the first row the jump left in view");
  }

  @Test
  void aColumnThatFitsItsContentMeasuresAWrappedFieldAtItsOwnWidth() {
    // The sibling makes the column 120 wide, where the 34 mono characters wrap to three rows.
    BiFunction<El, UnaryOperator<El>, El> column = (inner, lines) -> inner.children(
        lines.apply(El.text("aaaa bbbb cccc dddd eeee ffff gggg")
            .editable(value -> {})
            .multiline()
            .wrapText()
            .font(Font.mono())
            .fontSize(14)),
        El.el().size(120, 10));
    List<El> trees = List.of(
        El.col()
            .wFull()
            .hFull()
            .align(Style.Align.START)
            .child(column.apply(El.col().maxSize(120, Float.POSITIVE_INFINITY), El::fitLines)),
        El.col()
            .wFull()
            .hFull()
            .align(Style.Align.START)
            .child(column.apply(El.col(), field -> field.lines(1).maxLines(4))),
        El.col()
            .hFull()
            .child(column.apply(El.col().wFull(), field -> field.lines(1).maxLines(4))));
    for (El tree : trees) {
      App<Object> app = new App<>(new Object(), state -> tree);
      app.textRendering(TextRendering.LINUX);
      app.resize(800, 400, 1);
      app.frame();
      El inner = app.tree().children().getFirst();
      assertEquals(120, inner.width(), app.tree().dump());
      assertEquals(3 * 18 + 10, inner.height(), app.tree().dump());
      assertEquals(3 * 18, inner.children().getFirst().height(), app.tree().dump());
    }
  }

  @Test
  void aFieldPlacedAtItsMinimumWidthIsMeasuredAtThatWidth() {
    // The sibling makes the column 120 wide, where the 34 mono characters wrap to three rows; at
    // the field's minimum width of 60 each word takes a row. Without a minimum height, the
    // field's automatic minimum height is measured at 60 as well.
    for (boolean minHeight : new boolean[] {true, false}) {
      El field = El.text("aaaa bbbb cccc dddd eeee ffff gggg")
          .editable(value -> {})
          .multiline()
          .wrapText()
          .lines(1)
          .maxLines(10)
          .font(Font.mono())
          .fontSize(14)
          .alignSelf(Style.Align.START);
      if (minHeight) {
        field.minSize(60, 0);
      } else {
        field.style().minWidth = Len.px(60);
      }
      El tree = El.col()
          .wFull()
          .hFull()
          .align(Style.Align.START)
          .child(El.col().children(field, El.el().size(120, 10)));
      App<Object> app = new App<>(new Object(), state -> tree);
      app.textRendering(TextRendering.LINUX);
      app.resize(800, 400, 1);
      app.frame();
      El inner = app.tree().children().getFirst();
      assertEquals(60, inner.children().getFirst().width(), app.tree().dump());
      assertEquals(7 * 18, inner.children().getFirst().height(), app.tree().dump());
      assertEquals(7 * 18 + 10, inner.height(), app.tree().dump());
    }
  }

  @Test
  void aWrappingFitLinesFieldMeasuresAsWideAsItsWidestRowAndShrinksToWrap() {
    // Mono 14 advances 8px per character and 18px per row. In a row sized to content:
    // short text stays as wide as its widest line (plus caret room); long text caps at maxWidth
    // and wraps to its rows; non-wrapping fitLines still measures 0 wide.
    for (float scale : new float[] {1f, 2f}) {
      El shortRow = El.row()
          .child(El.text("hi\nlonger line")
              .editable(value -> {})
              .readOnly()
              .multiline()
              .wrapText()
              .fitLines()
              .font(Font.mono())
              .fontSize(14)
              .shrink(1));
      shortRow.children().getFirst().style().minWidth = Len.ZERO;
      shortRow.style().maxWidth = Len.px(200);

      El longRow = El.row()
          .child(El.text("aaaa bbbb cccc dddd eeee ffff gggg")
              .editable(value -> {})
              .readOnly()
              .multiline()
              .wrapText()
              .fitLines()
              .font(Font.mono())
              .fontSize(14)
              .shrink(1));
      longRow.children().getFirst().style().minWidth = Len.ZERO;
      longRow.style().maxWidth = Len.px(120);

      El noWrapRow = El.row()
          .child(El.text("a long non-wrapping line")
              .editable(value -> {})
              .readOnly()
              .multiline()
              .fitLines()
              .font(Font.mono())
              .fontSize(14));

      El plainRow =
          El.row().child(El.text("hi\nlonger line").wrapText().font(Font.mono()).fontSize(14));

      App<Object> app = new App<>(
          new Object(),
          state -> El.col()
              .wFull()
              .hFull()
              .align(Style.Align.START)
              .children(shortRow, longRow, noWrapRow, plainRow));
      app.textRendering(TextRendering.LINUX);
      app.resize(400, 300, scale);
      app.frame();

      El s = app.tree().children().get(0);
      El l = app.tree().children().get(1);
      El n = app.tree().children().get(2);
      El p = app.tree().children().get(3);
      assertEquals(p.width() + 1f, s.width(), 1.5f, app.tree().dump());
      assertEquals(p.height(), s.height(), 0.5f, app.tree().dump());
      assertEquals(120f, l.width(), 0.5f, app.tree().dump());
      assertEquals(3 * (p.height() / 2), l.height(), 0.5f, app.tree().dump());
      assertEquals(0f, n.width(), 0.5f, app.tree().dump());
    }
  }

  @Test
  void pageUpAndPageDownInAReadOnlyFitLinesFieldScrollTheEnclosingArea() {
    App<Object> app = new App<>(new Object(), state -> {
      El area = El.col().key("area").w(200).h(100).scroll();
      for (int i = 0; i < 20; i++) {
        area.child(El.text("Paragraph " + i + " with selectable text.")
            .key("p-" + i)
            .editable(value -> {})
            .readOnly()
            .multiline()
            .fitLines()
            .font(Font.mono())
            .fontSize(14));
      }
      return area;
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 100, 1);
    app.frame();

    // Click inside the first field, which does not wrap, as a code block of Markdown.
    app.pointerDown(20, 9, 1, 0);
    app.pointerUp(20, 9);
    app.frame();
    assertEquals("p-0", app.focused().key());
    assertEquals(0, app.tree().scrollY());

    // PageDown scrolls the enclosing scroll area; PageUp scrolls it back.
    app.keyDown(new Key("PageDown"));
    app.frame();
    double scrolled = app.tree().scrollY();
    assertTrue(scrolled > 0, "PageDown scrolled the enclosing area: " + scrolled);

    app.keyDown(new Key("PageUp"));
    app.frame();
    assertEquals(0, app.tree().scrollY(), 0.5);
  }

  @Test
  void aGrowingFieldFollowsItsRowsBetweenLinesAndMaxLines() {
    // Rows of 18 and a padding of 4 at each side: 1 to 4 rows are 26, 44, 62 and 80 high.
    App<Model> app = focused("", f -> f.wrapText().lines(1).maxLines(4));
    assertEquals(26, field(app).height(), "starts at its smallest height");

    type(app, "one");
    key(app, "Enter");
    assertEquals(44, field(app).height(), "grows to two rows on the same frame");
    type(app, "the quick brown fox jumps over");
    assertEquals(62, field(app).height(), "a wrapped line grows it by both rows");
    key(app, "Enter");
    type(app, "four");
    assertEquals(80, field(app).height(), "reaches maxLines");
    assertEquals(0, field(app).scrollY());

    key(app, "Enter");
    type(app, "five");
    assertEquals(80, field(app).height(), "stops at maxLines");
    assertEquals(18, field(app).scrollY(), "scrolls one row");
    assertEquals(54, field(app).text().caret().y(), "the caret on the last row in view");

    for (int i = 0; i < 5; i++) {
      key(app, "Backspace"); // "five" and the line break before it
    }
    assertEquals(80, field(app).height());
    assertEquals(0, field(app).scrollY(), "four rows fit without scrolling");
    key(app, "A", CONTROL);
    key(app, "Backspace");
    assertEquals(26, field(app).height(), "shrinks back to its minimum");

    App<Model> clamped = focused("a\nb\nc\nd", f -> f.lines(3).maxLines(2));
    assertEquals(62, field(clamped).height(), "lines wins when maxLines is smaller");
    App<Model> fitted = focused("a\nb\nc\nd", f -> f.fitLines().maxLines(2));
    assertEquals(80, field(fitted).height(), "fitLines wins over maxLines");
  }

  @Test
  void aGrowingFieldKeepsItsScrollThroughPastesRebuildsAndWidening() {
    App<Model> app = focused("", f -> f.wrapText().lines(1).maxLines(4));
    app.clipboard().write("line\n".repeat(200) + "end");
    key(app, "V", CONTROL);
    assertEquals(80, field(app).height(), "a long paste stays at maxLines");
    assertEquals(201 * 18 - 4 * 18, field(app).scrollY(), "scrolled to the caret at the end");
    assertTrue(field(app).editor().lineIndex().shaped < 40, "only the lines in view shaped");

    String paragraph = ("a".repeat(29) + " ").repeat(20); // a word a row, at either width
    App<Model> wide = focused("", f -> f.wrapText().lines(1).maxLines(4).wFull());
    wide.clipboard().write(paragraph);
    key(wide, "V", CONTROL);
    double scrolled = field(wide).scrollY();
    assertTrue(scrolled > 0, "a paragraph longer than four rows scrolls: " + scrolled);
    wide.invalidate();
    wide.frame();
    assertEquals(scrolled, field(wide).scrollY(), "a rebuild keeps the scroll offset");
    wide.resize(420, 240, 1);
    wide.frame();
    wide.wheel(50, 30, 0, 5000);
    wide.frame();
    assertEquals(20 * 18 - 4 * 18, field(wide).scrollY(), "widened, it scrolls to its last row");
  }
}
