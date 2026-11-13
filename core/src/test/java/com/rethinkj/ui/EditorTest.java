package com.rethinkj.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
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
