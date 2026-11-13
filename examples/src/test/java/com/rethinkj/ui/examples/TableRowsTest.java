package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.GridChanges.Change;
import com.rethinkj.ui.kit.GridChanges.Kind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The Workbench's rows of a table against a scan of every row, and its made-up server. */
class TableRowsTest {

  private static final List<List<Filter>> FILTERS = List.of(
      List.of(),
      List.of(Filter.oneOf(2, List.of("paid", "pending"))),
      List.of(Filter.between(3, "100", "900")),
      List.of(Filter.of(5, Filter.Kind.CONTAINS, "ada"), Filter.oneOf(4, List.of("true"))),
      List.of(Filter.of(6, Filter.Kind.IS_NULL, "")),
      List.of(Filter.of(5, Filter.Kind.EQUALS, "nobody@example.com")));

  /** Every row the filters keep, as a scan of all of them finds it, in the order shown. */
  private static List<Long> scan(TableRows rows) {
    List<Long> keys = new ArrayList<>();
    for (long k = 0; k < rows.base; k++) {
      if (keeps(rows, k)) {
        keys.add(k);
      }
    }
    for (int i = 0; i < rows.stored.added.size(); i++) {
      if (keeps(rows, TableRows.ADDED + i)) {
        keys.add(TableRows.ADDED + i);
      }
    }
    return keys;
  }

  private static boolean keeps(TableRows rows, long key) {
    return !rows.stored.removed.contains(key)
        && rows.filters.stream().allMatch(f -> f.matches(rows.stored.value(key, f.column())));
  }

  @Test
  void theFiltersFindTheRowsAScanFinds() {
    Random random = new Random(7);
    for (int run = 0; run < 60; run++) {
      TableRows rows = new TableRows(new TableRows.Stored());
      rows.base = random.nextInt(3) == 0 ? random.nextInt(50) : 6000 + random.nextInt(14_000);
      for (int i = 0; i < random.nextInt(40); i++) {
        long key = random.nextInt(Math.max(rows.base, 1));
        switch (random.nextInt(3)) {
          case 0 -> rows.stored.removed.add(key);
          case 1 ->
            rows.stored
                .written
                .computeIfAbsent(key, k -> new HashMap<>())
                .put(2, random.nextBoolean() ? "paid" : "refunded");
          default -> {
            Map<Integer, String> values = new HashMap<>();
            values.put(5, random.nextBoolean() ? "ada@example.com" : "x@example.com");
            values.put(6, null);
            rows.stored.added.add(values);
          }
        }
      }
      rows.filters = FILTERS.get(random.nextInt(FILTERS.size()));
      rows.refresh();
      List<Long> expected = scan(rows);
      assertEquals(expected.size(), rows.count(), "run " + run);
      for (int i = 0; i < expected.size(); i++) {
        assertEquals(expected.get(i), rows.key(i), "run " + run + ", row " + i);
        assertEquals(i, rows.place(expected.get(i)), "run " + run + ", key " + expected.get(i));
      }
      for (long k = 0; k < Math.min(rows.base, 3000); k++) {
        if (!expected.contains(k)) {
          assertEquals(-1, rows.place(k), "run " + run + ", left out " + k);
        }
      }
    }
  }

  @Test
  void aFilterOfAMillionRowsLooksAtOnePeriod() {
    TableRows rows = new TableRows(new TableRows.Stored());
    rows.base = 1_000_000;
    rows.filters = List.of(Filter.oneOf(2, List.of("pending")));
    rows.refresh();
    assertEquals(250_000, rows.count(), "every fourth order is pending");
    assertEquals("pending", rows.cell(249_999, 2));
    assertEquals(999_999, rows.key(249_999), "the last of them");
  }

  @Test
  void theServerRefusesANegativeAmountAndAPaidOrderGoingBackToPending() throws Exception {
    Change amount = new Change(Kind.UPDATE, 0, new TreeMap<>(Map.of(3, "-1.00")));
    Change status = new Change(Kind.UPDATE, 1, new TreeMap<>(Map.of(2, "pending")));
    Change fine = new Change(Kind.UPDATE, 2, new TreeMap<>(Map.of(2, "pending", 3, "5.00")));
    Map<Long, Map<Integer, String>> was = Map.of(1L, Map.of(2, "paid"), 2L, Map.of(2, "shipped"));
    List<TableRows.Refusal> refused = TableRows.write(
        "localhost/shop/public/orders", List.of(amount, status, fine), was, Duration.ZERO);
    assertEquals(
        List.of(
            new TableRows.Refusal(amount, 3, "amount must not be negative"),
            new TableRows.Refusal(status, 2, "a paid order cannot go back to pending")),
        refused);
    assertTrue(TableRows.write("p", List.of(fine), was, Duration.ZERO).isEmpty());
  }

  @Test
  void theStatementsQuoteTextAndNotNumbers() {
    GridChanges changes =
        new GridChanges(3, (r, c) -> Workbench.cell(Workbench.FIELDS.get(c).name(), r));
    changes.set(1, 5, "o'neil@example.com");
    changes.set(1, 3, "2.50");
    changes.delete(List.of(new com.rethinkj.ui.kit.DataGrid.Range(0, 0, 0, 0)));
    changes.insert(2);
    assertEquals(
        """
        DELETE FROM shop.public.orders WHERE id = 1;
        UPDATE shop.public.orders
          SET amount = 2.50, email = 'o''neil@example.com'
          WHERE id = 2;
        INSERT INTO shop.public.orders DEFAULT VALUES;""",
        TableRows.sql("shop.public.orders", changes.changes(), key -> String.valueOf(key + 1)));
  }
}
