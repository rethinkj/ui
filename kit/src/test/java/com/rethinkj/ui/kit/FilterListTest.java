package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A {@link FilterList}'s custom leading content and entries found on another thread. */
class FilterListTest {

  @TempDir
  Path out;

  private String run(App<?> app, String steps) throws IOException {
    Script.run(steps + "\nframe\nadvance 1\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void aWideLeadingElementLeavesRoomForTheTextShortcutAndCheck() throws IOException {
    TextLibraries.assumeLoaded();
    Theme t = Theme.DARK;
    FilterList.Entries entries = FilterList.Entries.of(List.of(FilterList.entry(
            "A connection with a long descriptive name", () -> {})
        .leading(th -> el().key("lead").size(3 * th.size().icon(), th.size().icon()))
        .detail("driver")
        .shortcut("F1")
        .checked(true)));
    App<FilterList.State> app = new App<>(
        new FilterList.State(),
        s -> FilterList.list(t, s, FilterList.shown(s, entries), 4, "Connections", e -> {})
            .wFull());
    String tree = run(app, "size 260 40");
    float[] lead = StructureTest.rect(StructureTest.line(tree, "#lead "));
    float[] name = StructureTest.rect(StructureTest.line(tree, "text \"A connection"));
    float[] detail = StructureTest.rect(StructureTest.line(tree, "text \"driver\""));
    float[] shortcut = StructureTest.rect(StructureTest.line(tree, "text \"F1\""));
    float[] check = StructureTest.rect(StructureTest.line(tree, "label=\"checkmark\""));
    float[] option = StructureTest.rect(StructureTest.line(tree, "#option-0 "));
    assertEquals(3 * t.size().icon(), lead[2], tree);
    assertTrue(name[0] >= lead[0] + lead[2] + t.space().m(), tree);
    assertTrue(name[2] > 0 && name[0] + name[2] <= detail[0] - t.space().m(), tree);
    assertTrue(shortcut[0] + shortcut[2] < check[0], tree);
    assertTrue(check[0] + check[2] <= option[0] + option[2] - t.space().m(), tree);
  }

  /** A finder that answers "two" at once and waits with every other text until it is let go. */
  static final class Finder {
    final CommandPalette.State palette = new CommandPalette.State();
    final CountDownLatch first = new CountDownLatch(1);
    final CountDownLatch last = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger interrupted = new AtomicInteger();
    App<Finder> app;

    List<FilterList.Entry> find(String text) {
      if (!text.equals("two")) {
        (text.equals("one") ? first : last).countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          interrupted.incrementAndGet();
        }
      }
      return List.of(FilterList.entry(text, () -> {}));
    }
  }

  /** More entries than {@link FilterList#PARALLEL}: scored in parts, a text that grows and a new one. */
  @Test
  void aLongListFindsTheBestMatchAsTheTextGrowsAndAnew() {
    String[] tables = {"orders", "order_items", "customers", "customer_notes", "invoices"};
    List<FilterList.Entry> entries = new java.util.ArrayList<>();
    for (int i = 0; entries.size() < 2 * FilterList.PARALLEL; i++) {
      String table = "s" + i / 50 + "." + tables[i % tables.length] + "_" + i / tables.length;
      for (String column : List.of("", ".id", ".status", ".note")) {
        entries.add(FilterList.entry(table + column, () -> {}));
      }
    }
    FilterList.Entries all = FilterList.Entries.of(entries);
    FilterList.State s = new FilterList.State();
    FilterList.shown(s, all);
    for (String typed : List.of("o", "or", "ord", "orderit")) {
      s.typed(typed, true);
      FilterList.shown(s, all);
    }
    assertEquals("s0.order_items_0", FilterList.shown(s, all).entry(0).text());
    s.typed("cnot", true);
    assertEquals("s0.customer_notes_0", FilterList.shown(s, all).entry(0).text());
  }

  @Test
  void aNewTextAndClosingTheListCancelTheSearchOnItsWay() throws Exception {
    TextLibraries.assumeLoaded();
    Finder f = new Finder();
    f.app = new App<>(
        f,
        x -> col()
            .overlay(CommandPalette.palette(
                Theme.DARK,
                x.palette,
                List.of(),
                List.of(new CommandPalette.Source(
                    "@", "find", FilterList.Entries.background(x.app, "find", x::find))))));
    try {
      f.palette.open("@one");
      String tree = run(f.app, "size 480 320\nframe");
      assertTrue(f.first.await(5, TimeUnit.SECONDS));
      assertTrue(tree.contains("Loading…"), tree);
      tree = run(f.app, "key A control\ntype \"@two\"\nframe\nawait");
      assertEquals(List.of("two"), ComboBoxTest.options(tree), "the first one cancelled");
      run(f.app, "key A control\ntype \"@closed\"");
      assertTrue(f.last.await(5, TimeUnit.SECONDS));
      tree = run(f.app, "key Escape\nframe\nawait");
      assertFalse(f.palette.isOpen(), tree);
      assertFalse(tree.contains("role=listbox"), tree);
      assertEquals(2, f.interrupted.get(), "both waiting searches were interrupted");
    } finally {
      f.palette.close();
      f.release.countDown();
    }
  }
}
