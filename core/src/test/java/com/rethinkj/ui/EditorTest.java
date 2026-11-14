package com.rethinkj.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The boundaries the caret stops at and the mapping of positions; no text libraries needed. */
class EditorTest {

  /** Every position {@code next} stops at, from the start. */
  private static List<Integer> stops(String text) {
    List<Integer> stops = new ArrayList<>();
    for (int i = 0; i < text.length(); i = Editor.next(text, i)) {
      stops.add(i);
    }
    stops.add(text.length());
    return stops;
  }

  @Test
  void theCaretStopsOnlyBetweenGraphemes() {
    assertEquals(List.of(0, 1, 3, 4), stops("a😀b"), "a surrogate pair is one");
    assertEquals(List.of(0, 2, 3), stops("éx"), "a letter with its combining mark");
    assertEquals(List.of(0, 4), stops("👍🏽"), "an emoji with a skin tone modifier");
    assertEquals(List.of(0, 5, 6), stops("👩‍💻!"), "a ZWJ sequence");
    assertEquals(List.of(0, 4), stops("🇩🇪"), "a flag of two regional indicators");
    assertEquals(List.of(0, 2, 3), stops("\r\nx"), "CR LF");

    assertEquals(1, Editor.previous("a😀b", 3));
    assertEquals(0, Editor.previous("👍🏽", 4));
    assertEquals(1, Editor.snap("a😀b", 2), "inside a pair goes to its start");
    assertEquals(0, Editor.snap("é", 1), "before the mark goes to the letter");
    assertEquals(3, Editor.snap("abc", 9));
  }

  @Test
  void wordsSkipSpacesAndPunctuation() {
    String sql = "SELECT name, id FROM t";
    assertEquals(13, Editor.wordLeft(sql, 15));
    assertEquals(7, Editor.wordLeft(sql, 13), "over the comma and the space");
    assertEquals(7, Editor.wordLeft(sql, 9), "from inside a word to its start");
    assertEquals(0, Editor.wordLeft(sql, 3));
    assertEquals(0, Editor.wordLeft("  ", 2));

    // Linux: to the end of the word; Windows: to the start of the next one.
    assertEquals(11, Editor.wordRight(sql, 6, true));
    assertEquals(15, Editor.wordRight(sql, 11, true));
    assertEquals(7, Editor.wordRight(sql, 0, false));
    assertEquals(13, Editor.wordRight(sql, 9, false));
    assertEquals(22, Editor.wordRight(sql, 21, false), "the last word goes to the end");
    assertEquals(3, Editor.wordRight("t  ", 1, true), "no word left: the end");

    assertArrayEquals(new int[] {7, 11}, Editor.word(sql, 8));
    assertArrayEquals(new int[] {11, 12}, Editor.word(sql, 11), "the comma on its own");
    assertArrayEquals(new int[] {21, 22}, Editor.word(sql, 22), "past the end: the last");
    assertArrayEquals(new int[] {0, 0}, Editor.word("", 0));
  }

  @Test
  void codeWordsEndAtPointsAndOtherPunctuation() {
    String sql = "FROM shop.public.orders_2026 o";
    assertArrayEquals(new int[] {10, 16}, Editor.codeWord(sql, 12), "a double press on public");
    assertArrayEquals(
        new int[] {5, 23},
        Editor.word(sql, 12),
        "text's words do not end at a point between letters");
    assertArrayEquals(new int[] {17, 28}, Editor.codeWord(sql, 20), "_ and digits belong to it");
    assertArrayEquals(new int[] {9, 10}, Editor.codeWord(sql, 9), "the point on its own");
    assertArrayEquals(new int[] {28, 29}, Editor.codeWord(sql, 28), "a space");
    assertArrayEquals(new int[] {1, 5}, Editor.codeWord("a  \t b", 3), "spaces and tabs");
    assertArrayEquals(new int[] {0, 2}, Editor.codeWord("e\u0301.", 1), "a mark with its letter");
    assertArrayEquals(
        new int[] {0, 3},
        Editor.codeWord("a\uD835\uDC00", 2),
        "inside a surrogate pair: the letter of the pair");
    assertArrayEquals(new int[] {0, 0}, Editor.codeWord("", 0));

    assertEquals(17, Editor.codeWordLeft(sql, 28), "Control+Backspace after orders_2026");
    assertEquals(10, Editor.codeWordLeft(sql, 17), "over the point to the start of public");
    assertEquals(5, Editor.codeWordLeft(sql, 7), "from inside a word to its start");
    assertEquals(0, Editor.codeWordLeft("  ", 2));
    assertEquals(0, Editor.codeWordLeft("(a", 1), "nothing but punctuation before a word");

    // Linux: to the end of the word; Windows: to the start of the next one.
    assertEquals(9, Editor.codeWordRight(sql, 5, true));
    assertEquals(16, Editor.codeWordRight(sql, 9, true), "over the point to the end of public");
    assertEquals(10, Editor.codeWordRight(sql, 5, false), "the start of public");
    assertEquals(17, Editor.codeWordRight(sql, 12, false));
    assertEquals(sql.length(), Editor.codeWordRight(sql, 29, false), "the last goes to the end");
    assertEquals(3, Editor.codeWordRight("t  ", 1, true), "no word left: the end");
  }

  @Test
  void codeWordsEndOnlyBetweenGraphemes() {
    assertEquals(2, Editor.codeWordLeft("❤️foo", 5), "the variation selector stays with its heart");
    assertArrayEquals(new int[] {0, 4}, Editor.codeWord("👍🏽!", 2), "the modifier with its emoji");
    assertArrayEquals(new int[] {0, 4}, Editor.codeWord("👍🏽!", 0));
    assertArrayEquals(new int[] {0, 5}, Editor.codeWord("👩‍💻!", 3), "a ZWJ sequence");
    assertArrayEquals(new int[] {1, 3}, Editor.codeWord(".e\u0301", 2), "a mark with its letter");
    assertEquals(2, Editor.codeWordLeft(".\u0301a", 3), "a mark after a point goes with it");
    assertArrayEquals(
        new int[] {0, 2}, Editor.codeWord(";\u200Dabc", 0), "a joiner joins no letter");
    assertArrayEquals(new int[] {0, 2}, Editor.codeWord("a\u200D😀", 0), "nor an emoji after one");
    assertArrayEquals(
        new int[] {0, 1}, Editor.codeWord("\n\u0301", 0), "a line break takes no mark");

    Random random = new Random(3);
    String[] parts = {
      "a",
      "_",
      "1",
      ".",
      ";",
      " ",
      "\u0301",
      "\u0903",
      "\u200D",
      "\u200C",
      "\uFE0F",
      "❤️",
      "👍🏽",
      "\uD83C\uDFFD",
      "👩‍💻",
      "😀",
      "é",
      "\n",
      "\uDB40\uDC61"
    };
    for (int round = 0; round < 400; round++) {
      StringBuilder text = new StringBuilder();
      for (int n = random.nextInt(8); n > 0; n--) {
        text.append(parts[random.nextInt(parts.length)]);
      }
      String s = text.toString();
      for (int at : stops(s)) {
        assertBoundary(s, Editor.codeWordLeft(s, at));
        assertBoundary(s, Editor.codeWordRight(s, at, true));
        assertBoundary(s, Editor.codeWordRight(s, at, false));
        int[] word = Editor.codeWord(s, at);
        assertBoundary(s, word[0]);
        assertBoundary(s, word[1]);
        int c = at < s.length() ? s.codePointAt(at) : 'a';
        if (!CodeWords.isWordChar(c) && c != ' ') {
          // A grapheme that is no word is one on its own, exactly as long as the JDK's.
          assertArrayEquals(
              new int[] {at, Editor.next(s, at)},
              word,
              () -> at + " in " + s.codePoints().boxed().toList());
        }
      }
    }
  }

  private static void assertBoundary(String text, int at) {
    assertEquals(
        Editor.snap(text, at), at, () -> at + " in " + text.codePoints().boxed().toList());
  }

  @Test
  void positionsFollowTheTextAroundThem() {
    assertEquals(2, Editor.map(3, "12a", "12"), "a refused letter at the end");
    assertEquals(1, Editor.map(2, "1a2", "12"), "a refused letter in the middle");
    assertEquals(3, Editor.map(3, "abc", "ABC"), "changed throughout: the end stays the end");
    assertEquals(2, Editor.map(2, "abc", "ABC"));
    assertEquals(0, Editor.map(0, "abc", ""), "cleared");
    assertEquals(5, Editor.map(2, "ab", "xyzab"), "text put before: the caret keeps its place");
    assertEquals(1, Editor.map(1, "ab", "abxyz"), "text put after");
  }

  @Test
  void aSingleLineTakesLineBreaksAsSpaces() {
    assertEquals("a b c d e", Editor.singleLine("a\r\nb\nc\rd e"));
  }
}
