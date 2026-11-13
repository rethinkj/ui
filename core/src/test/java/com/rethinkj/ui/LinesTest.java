package com.rethinkj.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The lines of a multi-line field, without native libraries: where lines start after edits, the
 * rows of wrapped lines, and how line breaks become line feeds.
 */
class LinesTest {

  @Test
  void linesStartAfterEachLineFeed() {
    Lines lines = Lines.of("ab\n\ncd\n");
    assertEquals(4, lines.count());
    assertEquals(0, lines.start(0));
    assertEquals(2, lines.end(0));
    assertEquals(3, lines.start(1));
    assertEquals(3, lines.end(1), "an empty line");
    assertEquals(4, lines.start(2));
    assertEquals(7, lines.start(3), "after the last line feed, an empty last line");
    assertEquals(7, lines.end(3));
    assertEquals(0, lines.line(2), "a line feed belongs to the line it ends");
    assertEquals(1, lines.line(3));
    assertEquals(3, lines.line(7));
    assertEquals(1, Lines.of("").count());
  }

  @Test
  void editsMoveTheStartsAsFindingThemAgainWould() {
    Random random = new Random(7);
    String alphabet = "ab \n\n\t";
    for (int round = 0; round < 2000; round++) {
      String text = randomText(random, alphabet, random.nextInt(40));
      Lines lines = Lines.of(text);
      for (int edit = 0; edit < 5; edit++) {
        int at = random.nextInt(text.length() + 1);
        int removed = random.nextInt(text.length() - at + 1);
        String inserted = randomText(random, alphabet, random.nextInt(8));
        lines.edit(at, removed, inserted);
        text = text.substring(0, at) + inserted + text.substring(at + removed);
        assertSameLines(Lines.of(text), lines, text);
      }
    }
  }

  @Test
  void rowsOfWrappedLinesAreSummedMeasuredOrEstimated() {
    Random random = new Random(11);
    for (int round = 0; round < 300; round++) {
      String text = randomText(random, "abcd \n", 20 + random.nextInt(200));
      Lines lines = Lines.of(text);
      float perRow = 1 + random.nextInt(6);
      lines.wrap("width", perRow);
      int[] measured = new int[lines.count()];
      for (int edit = 0; edit < 8; edit++) {
        // Layout measures some lines, then the text changes.
        for (int i = 0; i < 4; i++) {
          int line = random.nextInt(lines.count());
          measured[line] = 1 + random.nextInt(5);
          lines.rows(line, measured[line]);
        }
        assertRows(lines, measured, text, perRow);
        int at = random.nextInt(text.length() + 1);
        int removed = random.nextInt(Math.min(text.length() - at, 6) + 1);
        String inserted = randomText(random, "ab\n", random.nextInt(4));
        int firstLine = lines.line(at);
        int lastLine = lines.line(at + removed);
        int added = (int) inserted.chars().filter(c -> c == '\n').count();
        int[] next = new int[lines.count() - (lastLine - firstLine) + added];
        System.arraycopy(measured, 0, next, 0, firstLine);
        System.arraycopy(
            measured, lastLine + 1, next, firstLine + 1 + added, measured.length - lastLine - 1);
        measured = next; // the edited lines are not measured any more
        lines.edit(at, removed, inserted);
        text = text.substring(0, at) + inserted + text.substring(at + removed);
        assertRows(lines, measured, text, perRow);
      }
    }
  }

  @Test
  void anotherLayoutForgetsTheRowsMeasured() {
    Lines lines = Lines.of("aaaa\nbb");
    lines.wrap("narrow", 2);
    assertEquals(3, lines.totalRows(), "estimated: two rows and one");
    lines.rows(0, 4);
    assertEquals(5, lines.totalRows());
    lines.wrap("narrow", 2);
    assertEquals(5, lines.totalRows(), "the same layout keeps them");
    lines.wrap("wide", 10);
    assertEquals(2, lines.totalRows());
    lines.wrap(null, 1);
    assertEquals(2, lines.totalRows(), "without wrapping a row per line");
    assertEquals(1, lines.rowsBefore(1));
    assertEquals(1, lines.lineOfRow(1));
  }

  @Test
  void theWidestLineIsForgottenWhenItChanges() {
    Lines lines = Lines.of("a\nbb\nc");
    lines.measured(1, 20);
    lines.measured(0, 10);
    assertEquals(20, lines.widest());
    lines.edit(0, 0, "x\n"); // a line above it: it moves down
    lines.measured(0, 5);
    assertEquals(20, lines.widest());
    lines.edit(lines.start(2), 1, ""); // its own line
    assertEquals(0, lines.widest(), "the lines in view measure it again");
  }

  @Test
  void theWidestLineIsForgottenForAnotherFontSizeOrScale() {
    Lines lines = Lines.of("a\nbb");
    lines.measuredFor("scale 2");
    lines.measured(1, 40);
    lines.measuredFor("scale 2");
    assertEquals(40, lines.widest(), "the same layout keeps it");
    lines.measuredFor("scale 1");
    assertEquals(0, lines.widest(), "the lines in view measure it again");
    lines.measured(1, 20);
    assertEquals(20, lines.widest());
  }

  @Test
  void lineBreaksBecomeLineFeeds() {
    assertEquals("a\nb\nc\nd\ne\n", Editor.multiLine("a\r\nb\rc d e\r\n"));
    String plain = "no breaks\nbut line feeds";
    assertSame(plain, Editor.multiLine(plain), "nothing to change, nothing copied");
    assertEquals("a b c", Editor.singleLine("a\r\nb\nc"));
  }

  @Test
  void theDumpShortensLongStrings() {
    String shortOne = "x".repeat(El.QUOTED);
    assertEquals("\"" + shortOne + "\"", El.quoteText(shortOne));
    String text = "a".repeat(100) + "b".repeat(1000) + "c".repeat(40);
    assertEquals(
        "\"" + "a".repeat(100) + "\" … \"" + "c".repeat(40) + "\" length 1140", El.quoteText(text));
    String emoji = "a".repeat(99) + "😀" + "d".repeat(200) + "😀" + "e".repeat(39);
    String quoted = El.quoteText(emoji);
    assertEquals(
        "\"" + "a".repeat(99) + "\" … \"" + "e".repeat(39) + "\" length 342",
        quoted,
        "no half of a surrogate pair at either cut");
    assertEquals("\"a\\tb\\nc\"", El.quote("a\tb\nc"));
  }

  private static void assertRows(Lines lines, int[] measured, String text, float perRow) {
    Lines fresh = Lines.of(text);
    long sum = 0;
    for (int line = 0; line < fresh.count(); line++) {
      assertEquals(sum, lines.rowsBefore(line), "rows before line " + line);
      int characters = fresh.end(line) - fresh.start(line);
      long rows =
          measured[line] > 0 ? measured[line] : Math.max(1, (long) Math.ceil(characters / perRow));
      for (long r = sum; r < sum + rows; r++) {
        assertEquals(line, lines.lineOfRow(r), "the line of row " + r);
      }
      sum += rows;
    }
    assertEquals(sum, lines.totalRows());
    assertEquals(fresh.count() - 1, lines.lineOfRow(sum + 5), "clamped to the last line");
  }

  @Test
  void highlightedLinesFollowEditsThatAddAndRemoveLines() {
    String text = "a\nb";
    Lines lines = Lines.of(text);
    Highlighter none = (line, state, out) -> null;
    lines.spans(0, text, none, none);
    lines.spans(1, text, none, none);
    lines.edit(1, 0, "\n\n"); // Enter twice at the end of the first line
    assertEquals(4, lines.count());
    text = "a\n\n\nb";
    assertEquals("", lines.spans(3, text, none, none).toString(), "asked about the moved line");
    lines.edit(0, 4, "");
    assertEquals(1, lines.count());
    lines.spans(0, "b", none, none);
  }

  @Test
  void highlightedLinesAfterEditsAreAsIfAskedAfresh() {
    // A state carried across lines: the count of + less -, which every edit may change below it.
    Highlighter depth = (line, state, out) -> {
      int next = state == null ? 0 : (Integer) state;
      for (char c : line.toCharArray()) {
        next += c == '+' ? 1 : c == '-' ? -1 : 0;
      }
      out.color(0, line.length(), 0xFF000000 | (line.hashCode() ^ next) & 0xFFFFFF);
      out.lineBackground(0xFF000000 | next & 0xFFFFFF);
      return next;
    };
    Random random = new Random(859731);
    String text = "a+\nb-\n\nc\n".repeat(60);
    Lines lines = Lines.of(text);
    for (int edit = 0; edit < 3000; edit++) {
      int at = random.nextInt(text.length() + 1);
      int removed = random.nextInt(Math.min(8, text.length() - at) + 1);
      String inserted = randomText(random, "ab+-\n\n\t", random.nextInt(9));
      lines.edit(at, removed, inserted);
      text = text.substring(0, at) + inserted + text.substring(at + removed);
      Lines fresh = Lines.of(text);
      assertSameLines(fresh, lines, text);
      for (int sample = 0; sample < 5; sample++) {
        int line = random.nextInt(fresh.count());
        assertEquals(
            fresh.spans(line, text, depth, depth).toString(),
            lines.spans(line, text, depth, depth).toString(),
            "edit " + edit + ", line " + line);
      }
    }
  }

  private static void assertSameLines(Lines expected, Lines actual, String text) {
    assertEquals(expected.count(), actual.count(), text);
    assertEquals(expected.length(), actual.length(), text);
    for (int line = 0; line < expected.count(); line++) {
      assertEquals(expected.start(line), actual.start(line), "line " + line + " of " + text);
    }
  }

  private static String randomText(Random random, String alphabet, int length) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < length; i++) {
      out.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return out.toString();
  }
}
