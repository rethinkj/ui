package com.rethinkj.ui.examples.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The search of the command palette's page, without native libraries. */
class SearchesTest {

  private static final List<Searches.Table> TABLES = List.of(
      new Searches.Table("sales", "customer_note"),
      new Searches.Table("sales", "orders_total"),
      new Searches.Table("stock", "items"));

  private static List<String> found(String typed) {
    return Searches.tables(typed, TABLES, table -> {}).stream()
        .map(entry -> entry.text() + " " + entry.detail())
        .toList();
  }

  @Test
  void theBestMatchComesFirstAndTheOthersAreLeftOut() {
    assertEquals(List.of("orders_total sales", "customer_note sales"), found("ot"));
    assertEquals(List.of("items stock"), found("itm"));
  }
}
