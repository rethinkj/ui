package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link Fuzzy}'s scores and positions, without native libraries. */
class FuzzyTest {

  /** The texts that match, best first, as a list would show them. */
  private static List<String> ranked(String query, String... texts) {
    return List.of(texts).stream()
        .filter(t -> Fuzzy.score(query, t) != Fuzzy.NO_MATCH)
        .sorted(Comparator.comparingInt((String t) -> -Fuzzy.score(query, t)))
        .toList();
  }

  @Test
  void theStartsOfWordsComeFirst() {
    assertEquals(
        List.of("orders_total", "customer_note"),
        ranked("ot", "customer_note", "orders_total", "status"));
    assertEquals(
        List.of("order_items", "customer_id", "provider_index"),
        ranked("oi", "provider_index", "customer_id", "order_items"));
    assertEquals(List.of("customerId", "musician"), ranked("ci", "musician", "customerId"));
    assertEquals(
        List.of("cobalt", "customers_old"),
        ranked("co", "customers_old", "cobalt"),
        "a prefix beats the starts of two words");
    assertEquals(
        List.of("File: New query", "Query: Run query"),
        ranked("nq", "Query: Run query", "File: New query"));
  }

  @Test
  void charactersInARowBeatScatteredOnes() {
    assertEquals(List.of("orders", "other_records"), ranked("ord", "other_records", "orders"));
    assertTrue(Fuzzy.score("tab", "table") > Fuzzy.score("tab", "the_cat_bar"));
  }

  @Test
  void caseCountsOnlyWhenTheQueryHasCapitals() {
    assertTrue(Fuzzy.score("ord", "Orders") != Fuzzy.NO_MATCH);
    assertEquals(Fuzzy.NO_MATCH, Fuzzy.score("Ord", "orders"));
    assertTrue(Fuzzy.score("Ord", "Orders") != Fuzzy.NO_MATCH);
    assertEquals(List.of("CustomerOrders"), ranked("CO", "customer_orders", "CustomerOrders"));
  }

  @Test
  void umlautsMatchTheirCapitalsButNotTheirBases() {
    assertTrue(Fuzzy.score("über", "Übersicht") != Fuzzy.NO_MATCH);
    assertTrue(Fuzzy.score("grö", "Schriftgröße") != Fuzzy.NO_MATCH);
    assertEquals(Fuzzy.NO_MATCH, Fuzzy.score("uber", "Übersicht"));
    assertEquals(Fuzzy.NO_MATCH, Fuzzy.score("ss", "Straße"));
  }

  @Test
  void theOrderOfTheCharactersCounts() {
    assertEquals(Fuzzy.NO_MATCH, Fuzzy.score("to", "orders_t"));
    assertEquals(Fuzzy.NO_MATCH, Fuzzy.score("x", "orders"));
    assertEquals(0, Fuzzy.score("", "anything"), "an empty query matches all alike");
  }

  @Test
  void positionsAreThoseOfTheBestMatch() {
    assertArrayEquals(new int[] {0, 7}, Fuzzy.positions("ot", "orders_total"));
    assertArrayEquals(new int[] {10, 11}, Fuzzy.positions("ot", "customer_note"));
    assertArrayEquals(new int[] {0, 1, 2}, Fuzzy.positions("ord", "orders_order"));
    assertArrayEquals(new int[] {6, 10}, Fuzzy.positions("nq", "File: New query"));
    assertArrayEquals(new int[0], Fuzzy.positions("", "orders"));
    assertNull(Fuzzy.positions("zz", "orders"));
  }

  @Test
  void positionsScoreWhatTheScoreSays() {
    Random random = new Random(7);
    String letters = "abc_.A";
    for (int round = 0; round < 3000; round++) {
      String text = random(random, letters, 1 + random.nextInt(14));
      String query = random(random, "abc", 1 + random.nextInt(4));
      int score = Fuzzy.score(query, text);
      int[] at = Fuzzy.positions(query, text);
      if (score == Fuzzy.NO_MATCH) {
        assertNull(at, query + " in " + text);
        continue;
      }
      assertEquals(score, rescore(query, text, at), query + " in " + text);
    }
  }

  /** The score of a match at these positions, added up from the rules. */
  private static int rescore(String query, String text, int[] at) {
    Fuzzy.Matcher m = Fuzzy.matcher(query);
    int sum = 0;
    for (int j = 0; j < at.length; j++) {
      int i = at[j];
      assertEquals(Character.toLowerCase(query.charAt(j)), Character.toLowerCase(text.charAt(i)));
      char before = i == 0 ? ' ' : text.charAt(i - 1);
      char c = text.charAt(i);
      int bonus = before == ' '
          ? Fuzzy.BOUNDARY
          : ".-_/\\:,;".indexOf(before) >= 0
              ? Fuzzy.SEPARATOR
              : Character.isLowerCase(before) && Character.isUpperCase(c) ? Fuzzy.CAMEL : 0;
      sum += Fuzzy.MATCH + (j == 0 ? 2 : 1) * bonus;
      if (j > 0) {
        int gap = i - at[j - 1] - 1;
        sum += gap == 0 ? Fuzzy.CONSECUTIVE : -Fuzzy.GAP_START - Fuzzy.GAP_EXTENSION * (gap - 1);
      }
    }
    assertTrue(m.matches(text));
    return sum;
  }

  private static String random(Random random, String letters, int length) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < length; i++) {
      s.append(letters.charAt(random.nextInt(letters.length())));
    }
    return s.toString();
  }
}
