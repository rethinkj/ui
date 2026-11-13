package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@link FilterList} of 100 000 entries, the tables and columns of a large schema: what a key
 * typed costs, filtering alone and with its frame in a {@link ComboBox}. Prints the times.
 */
class FilterListLargeTest {

  static final int COUNT = 100_000;

  @TempDir Path out;

  /** Tables and their columns, as {@code schema.table.column}, 100 000 of them. */
  static List<String> names() {
    String[] tables = {"orders", "order_items", "customers", "customer_notes", "invoices",
        "payments", "products", "shipments", "suppliers", "warehouses", "audit_log", "sessions"};
    String[] columns = {"id", "customer_id", "status", "amount", "created_at", "updated_at",
        "note", "total", "email", "deleted_at"};
    List<String> names = new ArrayList<>(COUNT);
    for (int i = 0; names.size() < COUNT; i++) {
      String table = "s" + (i / 120) + "." + tables[i % tables.length] + "_" + i / tables.length;
      names.add(table);
      for (String column : columns) {
        if (names.size() < COUNT) {
          names.add(table + "." + column);
        }
      }
    }
    return names;
  }

  @Test
  void typingFiltersAHundredThousandEntriesInAFewMilliseconds() {
    List<FilterList.Entry> entries = names().stream()
        .map(n -> FilterList.entry(n, () -> { })).toList();
    FilterList.Entries all = FilterList.Entries.of(entries);
    String[] typed = {"o", "or", "ord", "orde", "order", "orderi", "orderit"};
    long[] best = new long[typed.length];
    java.util.Arrays.fill(best, Long.MAX_VALUE);
    long fresh = Long.MAX_VALUE;
    FilterList.State s = new FilterList.State();
    long start = System.nanoTime();
    FilterList.shown(s, all); // the first look at a list keeps its texts as compared
    long first = System.nanoTime() - start;
    for (int round = 0; round < 60; round++) {
      for (int i = 0; i < typed.length; i++) {
        s.typed(typed[i], true);
        start = System.nanoTime();
        FilterList.shown(s, all);
        best[i] = Math.min(best[i], System.nanoTime() - start);
      }
      s.typed("cnot", true);
      start = System.nanoTime();
      FilterList.Shown shown = FilterList.shown(s, all);
      fresh = Math.min(fresh, System.nanoTime() - start);
      assertTrue(shown.entry(0).text().contains("customer_notes"), shown.entry(0).text());
      s.typed("", true);
      FilterList.shown(s, all);
    }
    StringBuilder times = new StringBuilder();
    for (int i = 0; i < typed.length; i++) {
      times.append(String.format(Locale.ROOT, " %s %.1f ms,", typed[i], best[i] / 1e6));
    }
    System.out.printf(Locale.ROOT, "Filtering %d entries (the first look %.1f ms), each key typed"
        + " after the one before:%s \"cnot\" at once %.1f ms%n", COUNT, first / 1e6, times,
        fresh / 1e6);
  }

  @Test
  void aKeyWithItsFrameInAComboBox() throws IOException {
    TextLibraries.assumeLoaded();
    List<String> names = names();
    ComboBox.State state = new ComboBox.State();
    int[] chosen = {-1};
    Theme t = Theme.DARK;
    App<Object> app = new App<>(new Object(), s -> col().wFull().hFull().padding(12)
        .child(ComboBox.comboBox(t, names, chosen[0], state, i -> chosen[0] = i).key("name")
            .w(360)));
    app.textRendering(TextRendering.LINUX);
    Script.run("size 480 320\nframe\npress #name\nframe", app, out);
    String query = "orderitstat";
    long worst = 0;
    long total = 0;
    for (int round = 0; round < 6; round++) {
      Script.run("key Escape\nframe\npress #name\nframe", app, out);
      for (int i = 0; i < query.length(); i++) {
        long start = System.nanoTime();
        Script.run("type \"" + query.charAt(i) + "\"\nframe", app, out);
        long took = System.nanoTime() - start;
        if (round > 2) {
          worst = Math.max(worst, took);
          total += took;
        }
      }
    }
    Script.run("frame\nsnap s", app, out);
    String tree = Files.readString(out.resolve("s.tree.txt"));
    assertTrue(tree.contains("role=option label=\"s0.order_items_0.status\""), tree);
    System.out.printf(Locale.ROOT, "A key typed with its frame in a combo box of %d entries: "
        + "%.1f ms on average, at most %.1f ms%n", COUNT, total / 1e6 / (3 * query.length()),
        worst / 1e6);
    Script.run("key Enter\nframe", app, out);
    assertEquals("s0.order_items_0.status", names.get(chosen[0]));
  }
}
