package com.rethinkj.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
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
    for (int round = 0; round < 200; round++) {
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
    for (int round = 0; round < 50; round++) {
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
    String shortOne = "x".repeat(TreeDumper.QUOTED);
    assertEquals("\"" + shortOne + "\"", TreeDumper.quoteText(shortOne));
    String text = "a".repeat(100) + "b".repeat(1000) + "c".repeat(40);
    assertEquals(
        "\"" + "a".repeat(100) + "\" … \"" + "c".repeat(40) + "\" length 1140",
        TreeDumper.quoteText(text));
    String emoji = "a".repeat(99) + "😀" + "d".repeat(200) + "😀" + "e".repeat(39);
    String quoted = TreeDumper.quoteText(emoji);
    assertEquals(
        "\"" + "a".repeat(99) + "\" … \"" + "e".repeat(39) + "\" length 342",
        quoted,
        "no half of a surrogate pair at either cut");
    assertEquals("\"a\\tb\\nc\"", TreeDumper.quote("a\tb\nc"));
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
    for (int edit = 0; edit < 300; edit++) {
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

  @Test
  void foldedLinesCountZeroRowsAndAreSkippedByLineOfRow() {
    // 8 lines (0..7), unsorted + nested + overlapping + clamped/ignored folds.
    String text = "0000\n1111\n2222\n3333\n4444\n5555\n6666\n7777";
    Lines lines = Lines.of(text);
    El.Fold outer = new El.Fold(1, 4); // hides 2..4
    El.Fold inner = new El.Fold(2, 3); // nested inside outer
    El.Fold tail = new El.Fold(6, 20); // clamped to 7 (hides 7)
    El.Fold past = new El.Fold(7, 10); // past last line, ignored
    lines.folds(List.of(tail, inner, past, outer));

    // Visible lines: 0, 1, 5, 6 (4 rows without wrapping).
    assertEquals(4, lines.totalRows());
    assertEquals(2, lines.rowsBefore(2));
    assertEquals(2, lines.rowsBefore(5));
    assertEquals(3, lines.rowsBefore(6));
    assertEquals(4, lines.rowsBefore(7));

    assertEquals(5, lines.lineOfRow(2), "never returns a hidden line");
    assertEquals(6, lines.lineOfRow(3));
    assertEquals(6, lines.lineOfRow(10), "clamps past end to last visible line");

    assertEquals(outer, lines.foldAt(1), "outermost fold owns the header");
    assertEquals(tail, lines.foldAt(6));
    assertEquals(5, lines.nextVisible(1));
    assertEquals(1, lines.prevVisible(5));
    assertEquals(-1, lines.nextVisible(6));
    assertEquals(List.of(inner, outer), lines.foldsHiding(3, 3));
    assertTrue(lines.isHidden(7));

    // Wrapping with folds: hidden lines still count 0 rows, visible lines count measured/estimated.
    lines.wrap("w", 2);
    lines.rows(1, 3);
    lines.rows(2, 9); // hidden: ignored
    // Visible rows: line 0 (2), line 1 (3), line 5 (2), line 6 (2) -> total 9.
    assertEquals(9, lines.totalRows());
    assertEquals(1, lines.lineOfRow(2));
    assertEquals(1, lines.lineOfRow(4));
    assertEquals(5, lines.lineOfRow(5));
    assertEquals(6, lines.lineOfRow(7));
    assertEquals(6, lines.lineOfRow(99));

    // Removing folds returns to full count and keeps wrapping rows.
    lines.folds(List.of());
    assertEquals(17, lines.totalRows());
    lines.wrap(null, 1);
    assertEquals(8, lines.totalRows());
  }

  @Test
  void lineBlocksOffsetLinesAndHideReplacedRanges() {
    // 6 lines (0..5), line height 20px.
    Lines lines = Lines.of("L0\nL1\nL2\nL3\nL4\nL5");
    lines.blocksLayout("w", 20);
    El.Block repTop = El.Block.replace(0, 1); // hides lines 0 and 1
    El.Block insMid = El.Block.insert(3); // before line 3
    El.Block insMid2 = El.Block.insert(3).lines(2); // stacked before line 3, fixed 40px
    El.Block insEnd = El.Block.insert(6).lines(1); // after last line, fixed 20px
    lines.blocks(List.of(insEnd, insMid, repTop, insMid2));

    // Line 0 and 1 are hidden by repTop.
    assertTrue(lines.isHidden(0) && lines.isHidden(1));
    assertEquals(4, lines.shownCount());
    assertEquals(
        List.of(2, -1, 2, 2),
        List.of(
            lines.firstVisible(),
            lines.visibleAtOrBefore(1),
            lines.visibleAtOrAfter(0),
            lines.lineOfRow(0)));

    // Initial estimates: repTop = 20, insMid = 20, insMid2 = 40, insEnd = 20 -> 100px blocks.
    assertEquals(100, lines.totalBlockPixels());

    // Measure repTop -> 50px, insMid -> 15px.
    // Active order: [0]=repTop(0..1), [1]=insMid(3), [2]=insMid2(3, 2 lines), [3]=insEnd(6, 1
    // line).
    lines.blockHeight(0, 50);
    lines.blockHeight(1, 15);
    assertEquals(125, lines.totalBlockPixels());
    // Vertical layout:
    //   [0, 50): repTop (slot 0)
    //   [50, 70): line 2 (row 0)
    //   [70, 85): insMid (slot 3)
    //   [85, 125): insMid2 (slot 3)
    //   [125, 145): line 3 (row 1)
    //   [145, 165): line 4 (row 2)
    //   [165, 185): line 5 (row 3)
    //   [185, 205): insEnd (slot 6)
    assertEquals(
        List.of(50.0, 70.0, 125.0, 185.0, 205.0),
        List.of(
            lines.lineTop(2, 20),
            lines.slotTop(3, 20),
            lines.lineTop(3, 20),
            lines.slotTop(6, 20),
            lines.totalHeight(20)));

    // Lockstep y -> slot lookup across blocks and hidden lines.
    assertEquals(
        List.of(0, 0, 2, 2, 3, 3, 4, 5, 6, 6),
        IntStream.of(0, 49, 50, 69, 70, 125, 145, 165, 185, 300)
            .mapToObj(y -> lines.slotAt(y, 20))
            .toList());

    // Changing layout invalidates cached measured heights back to estimates.
    lines.blocksLayout("narrow", 20);
    assertEquals(100, lines.totalBlockPixels());

    // An enclosing fold hides blocks on its folded lines (lines 3..5 -> insMid/insMid2 hidden).
    lines.folds(List.of(new El.Fold(2, 5)));
    assertEquals(40, lines.totalBlockPixels(), "only repTop (20) and trailing insEnd (20) remain");

    // Replacing all lines leaves 0 visible rows without crashing.
    lines.folds(List.of());
    lines.blocks(List.of(El.Block.replace(0, 5)));
    assertEquals(
        List.of(0L, 0L, -1L, -1L, 0L),
        List.of(
            (long) lines.shownCount(),
            lines.totalRows(),
            (long) lines.firstVisible(),
            (long) lines.lineOfRow(0),
            (long) lines.slotAt(10, 20)));

    // Clearing blocks restores fast path.
    lines.blocks(List.of());
    assertEquals(0, lines.totalBlockPixels());
    assertEquals(6, lines.totalRows());
  }

  private static String randomText(Random random, String alphabet, int length) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < length; i++) {
      out.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return out.toString();
  }
}
