package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/** The search behind {@link Find}, without native libraries. */
class SearchTest {

  private static final long NONE = Long.MAX_VALUE;

  private static List<String> found(String text, Search.Query query) {
    Pattern p = query.compile();
    Search.Matches m = Search.scan(p, text, 0, text.length(), 1000, Search.deadline(5000));
    List<String> out = new ArrayList<>();
    for (int i = 0; i < m.count(); i++) {
      out.add(m.starts()[i] + ":" + text.substring(m.starts()[i], m.ends()[i]));
    }
    return out;
  }

  private static Search.Query plain(String text) {
    return new Search.Query(text, false, false, false);
  }

  @Test
  void findsEveryMatchInOrderWithoutOverlapping() {
    assertEquals(List.of("0:aa", "2:aa"), found("aaaaa", plain("aa")));
    assertEquals(List.of("4:id", "12:Id", "16:ID"), found("WHERid = 1, Id, ID", plain("id")));
    assertNull(plain("").compile(), "nothing to find");
  }

  @Test
  void caseFoldsLettersOneByOne() {
    String text = "Ärger ärger ÄRGER straße STRASSE STRAẞE Σίσυφος ΣΊΣΥΦΟΣ";
    assertEquals(3, found(text, plain("ärger")).size());
    assertEquals(
        List.of("18:straße", "33:STRAẞE"),
        found(text, plain("straße")),
        "ß finds the capital ẞ, but not the two letters SS");
    assertEquals(2, found(text, plain("σίσυφος")).size());
    assertEquals(List.of("6:ärger"), found(text, new Search.Query("ärger", true, false, false)));
  }

  @Test
  void aWholeWordHasNoLetterDigitMarkOrUnderscoreBesideIt() {
    Search.Query word = new Search.Query("id", false, true, false);
    assertEquals(List.of("0:id", "16:id", "25:id"), found("id, order_id, o.id, idx, id", word));
    assertEquals(
        List.of(),
        found("idé x", new Search.Query("ide", false, true, false)),
        "an e with its combining accent goes on");
    assertEquals(
        List.of("0:straße"),
        found("straße straßen", new Search.Query("straße", true, true, false)));
    assertEquals(List.of("5:a.b"), found("xa.b a.b", new Search.Query("a\\.b", false, true, true)));
  }

  @Test
  void aMatchBeginsAndEndsBetweenGraphemes() {
    String accent = "café cafe";
    assertEquals(
        List.of("6:cafe"), found(accent, plain("cafe")), "not the e that carries an accent");
    assertEquals(List.of("0:café"), found(accent, plain("café")));
    String family = "👨‍👩‍👧 👨";
    assertEquals(List.of("9:👨"), found(family, plain("👨")), "not the man inside the family");
    String flags = "🇩🇪🇫🇷";
    assertEquals(List.of(), found(flags, plain("🇪🇫")), "not across two flags");
    assertEquals(List.of("4:🇫🇷"), found(flags, plain("🇫🇷")));
    assertFalse(Search.boundary("é", 1));
    assertTrue(Search.boundary("ab", 1));
    assertFalse(Search.boundary("a\r\nb", 2));
  }

  @Test
  void aRegularExpressionMatchesLinesAndNeverEmpty() {
    Search.Query lines = new Search.Query("^\\w+", true, false, true);
    assertEquals(List.of("0:SELECT", "9:FROM"), found("SELECT *\nFROM t", lines));
    assertEquals(
        List.of("1:b", "3:b"),
        found("ab_b", new Search.Query("b*", true, false, true)),
        "no empty matches");
    assertThrows(
        PatternSyntaxException.class, () -> new Search.Query("(x", false, false, true).compile());
  }

  @Test
  void stopsAtTheLimitAndAtTheDeadline() {
    String text = "x ".repeat(5000);
    Search.Matches m = Search.scan(plain("x").compile(), text, 0, text.length(), 1000, NONE);
    assertEquals(1000, m.count());
    assertTrue(m.more());
    assertFalse(m.complete());
    String evil = "x".repeat(5000);
    Pattern backtracks = new Search.Query("(x+x+)+y", true, false, true).compile();
    long start = System.nanoTime();
    Search.Matches slow =
        Search.scan(backtracks, evil, 0, evil.length(), 1000, Search.deadline(50));
    assertTrue(slow.stopped());
    assertTrue((System.nanoTime() - start) / 1e6 < 1000, "stopped soon after its deadline");
    assertThrows(
        Search.Stopped.class,
        () -> Search.next(backtracks, evil, 0, evil.length(), 0, Search.deadline(50)));
  }

  @Test
  void aPatternThatRecursesTooDeepStopsAsTooSlow() {
    String text = "a".repeat(20_000);
    Pattern p = new Search.Query("(a|b)+", true, false, true).compile();
    assertTrue(Search.scan(p, text, 0, text.length(), 10, NONE).stopped());
    assertThrows(Search.Stopped.class, () -> Search.next(p, text, 0, text.length(), 0, NONE));
    assertThrows(
        Search.Stopped.class,
        () -> Search.replaceAll(
            new Search.Query("(a|b)+", true, false, true), p, text, 0, text.length(), "x", NONE));
    assertThrows(
        Search.Stopped.class,
        () -> Search.replacement(
            new Search.Query("(a|b)+", true, false, true),
            p,
            text,
            0,
            text.length(),
            text.length(),
            "x",
            NONE));
  }

  @Test
  void nextAndPreviousGoRoundWithinTheirPart() {
    Pattern p = plain("ab").compile();
    String text = "ab ab ab ab";
    assertArrayEquals(new int[] {3, 5}, Search.next(p, text, 0, text.length(), 1, NONE));
    assertArrayEquals(new int[] {0, 2}, Search.next(p, text, 0, text.length(), 10, NONE));
    assertArrayEquals(new int[] {3, 5}, Search.next(p, text, 3, 8, 7, NONE), "round in 3 to 8");
    assertArrayEquals(new int[] {3, 5}, Search.previous(p, text, 0, text.length(), 6, 0, NONE));
    assertArrayEquals(new int[] {9, 11}, Search.previous(p, text, 0, text.length(), 0, 0, NONE));
    assertNull(Search.next(plain("zz").compile(), text, 0, text.length(), 0, NONE));
  }

  @Test
  void replacesWithTheGroupsOfARegularExpression() {
    Search.Query swap = new Search.Query("(\\w+) = (?<v>\\w+)", true, false, true);
    Pattern p = swap.compile();
    String text = "a = 1, b = 2";
    assertEquals("1 = a", Search.replacement(swap, p, text, 0, 5, 12, "${v} = $1", NONE));
    assertEquals(
        "$1",
        Search.replacement(plain("a = 1"), plain("a = 1").compile(), text, 0, 5, 12, "$1", NONE),
        "a plain search's replacement is taken as it is");
    assertEquals("\\$a", Search.replacement(swap, p, text, 0, 5, 12, "\\\\\\$$1", NONE));
    assertThrows(
        IllegalArgumentException.class,
        () -> Search.replacement(swap, p, text, 0, 5, 12, "$3", NONE));
    assertNull(Search.replacement(swap, p, text, 0, 4, 12, "x", NONE), "not a whole match");
    Search.Query greedy = new Search.Query("(a+)", true, false, true);
    Pattern a = greedy.compile();
    assertEquals(
        "a!",
        Search.replacement(greedy, a, "aa", 0, 1, 1, "$1!", NONE),
        "the match that a search up to 1 finds, not the longer one after it");
    assertEquals("a!", Search.replaceAll(greedy, a, "aa", 0, 1, "$1!", NONE).text());
    assertNull(Search.replacement(greedy, a, "aa", 0, 2, 1, "x", NONE), "beyond the part");
    Search.Replaced all = Search.replaceAll(swap, p, text, 0, text.length(), "$2=$1", NONE);
    assertEquals(new Search.Replaced(0, 12, "1=a, 2=b", 2), all);
    assertNull(Search.replaceAll(swap, p, text, 0, 3, "x", NONE), "no match inside 0 to 3");
  }

  @Test
  void parsesALineAndAColumn() {
    assertArrayEquals(new int[] {42, 1}, Find.parseLine("42"));
    assertArrayEquals(new int[] {42, 7}, Find.parseLine(" 42:7 "));
    assertArrayEquals(new int[] {3, 2}, Find.parseLine("3,2"));
    assertNull(Find.parseLine("0"));
    assertNull(Find.parseLine("x"));
    assertNull(Find.parseLine(""));
    assertEquals("a\\.b\\(c\\)", Find.quote("a.b(c)"));
  }
}
