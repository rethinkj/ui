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
        .leading(() -> el().key("lead").size(3 * t.size().icon(), t.size().icon()))
        .detail("driver")
        .shortcut("F1")
        .checked(true)));
    App<FilterList.State> app = new App<>(
        new FilterList.State(),
        s -> FilterList.list(
                t, s, FilterList.shown(s, entries), 4, t.size().control(), "Connections", e -> {})
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
    final CountDownLatch interrupted = new CountDownLatch(2);
    App<Finder> app;

    List<FilterList.Entry> find(String text) {
      if (!text.equals("two")) {
        (text.equals("one") ? first : last).countDown();
        try {
          release.await();
        } catch (InterruptedException _) {
          interrupted.countDown();
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
                x.palette,
                List.of(),
                List.of(new CommandPalette.Source(
                    "@", "find", FilterList.Entries.background(x.app, "find", x::find))))));
    try {
      f.palette.open("@one");
      String tree = run(f.app, "size 480 320\nframe");
      assertTrue(f.first.await(5, TimeUnit.SECONDS));
      assertTrue(tree.contains("Loading…"), tree);
      tree = run(f.app, "key A control\nclipboard \"@two\"\nkey V control\nframe\nawait");
      assertEquals(List.of("two"), ComboBoxTest.options(tree), "the first one cancelled");
      run(f.app, "key A control\nclipboard \"@closed\"\nkey V control");
      assertTrue(f.last.await(5, TimeUnit.SECONDS));
      tree = run(f.app, "key Escape\nframe\nawait");
      assertFalse(f.palette.isOpen(), tree);
      assertFalse(tree.contains("role=listbox"), tree);
      assertTrue(
          f.interrupted.await(5, TimeUnit.SECONDS), "both waiting searches were interrupted");
    } finally {
      f.palette.close();
      f.release.countDown();
    }
  }

  @Test
  void filterListFiltersMovesChoosesKeepsFocusAndFocusesInPopover() throws IOException {
    TextLibraries.assumeLoaded();
    FilterList.State listState = new FilterList.State();
    String[] chosen = {null};
    int[] enters = {0}; // Enter keys that reach the root, as a dialog's default button hears them
    FilterList.Entries entries = FilterList.Entries.of(List.of(
        FilterList.entry("Claude Opus", () -> chosen[0] = "Claude Opus"),
        FilterList.entry("Claude Sonnet", () -> chosen[0] = "Claude Sonnet"),
        FilterList.entry("GPT-5", () -> chosen[0] = "GPT-5")));

    App<FilterList.State> embedded = new App<>(
        listState,
        s -> col()
            .onKey(k -> k.name().equals("Enter") && ++enters[0] > 0)
            .child(FilterList.filterList(s, "Search models", entries).w(280)));
    String tree = run(embedded, """
        size 320 200
        frame
        press role=textbox label="Search models"
        frame
        type "cl"
        frame
        key ArrowDown
        frame
        key Enter""");
    assertEquals("cl", listState.text());
    assertEquals(List.of("Claude Opus", "Claude Sonnet"), ComboBoxTest.options(tree));
    assertEquals("Claude Sonnet", chosen[0], tree);
    assertTrue(StructureTest.line(tree, "role=textbox").contains(" focused"), tree);

    tree = run(embedded, "press role=option label=\"Claude Opus\"");
    assertEquals("Claude Opus", chosen[0], tree);
    assertTrue(StructureTest.line(tree, "role=textbox").contains(" focused"), tree);

    run(embedded, "type \"zzz\"\nframe\nkey Enter");
    assertEquals(0, enters[0], "Enter without a match stays in the field");

    Popover.State popover = new Popover.State();
    FilterList.State popList = new FilterList.State();
    App<Popover.State> inPopover = new App<>(
        popover,
        p -> col()
            .child(Popover.popover(
                Button.button(Button.Variant.SECONDARY, "Model", p::toggle),
                p,
                "Model",
                FilterList.filterList(popList, "Search models", entries).w(280))));
    tree = run(inPopover, "size 360 240\nframe\npress role=button label=\"Model\"");
    assertTrue(popover.isOpen(), tree);
    assertTrue(tree.contains("role=listbox label=\"Search models\""), tree);
    assertTrue(StructureTest.line(tree, "role=textbox").contains(" focused"), tree);

    run(inPopover, "key Escape");
    assertFalse(popover.isOpen());
  }

  /** Entries found on another thread under a name, which tells one source from another. */
  static final class Sources {
    final FilterList.State list = new FilterList.State();
    final List<String> asked = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    String name = "tables";
    App<Sources> app;

    List<FilterList.Entry> find(String text) {
      String found = name + ":" + text;
      asked.add(found);
      return List.of(FilterList.entry(found, () -> {}));
    }
  }

  @Test
  void anotherSourceWithTheSameTextIsFilteredOrAskedAnew() throws IOException {
    FilterList.State s = new FilterList.State();
    s.typed("fi", true);
    List<FilterList.Entry> files = List.of(FilterList.entry("File: Exit", () -> {}));
    FilterList.Entries found =
        FilterList.Entries.by(text -> List.of(FilterList.entry("Find", () -> {})));
    assertEquals(
        "File: Exit", FilterList.shown(s, FilterList.Entries.of(files)).entry(0).text());
    assertEquals("Find", FilterList.shown(s, found).entry(0).text());
    assertEquals(
        "File: Exit", FilterList.shown(s, FilterList.Entries.of(files)).entry(0).text());

    TextLibraries.assumeLoaded();
    Sources sources = new Sources();
    sources.list.typed("fi", true);
    sources.app = new App<>(
        sources,
        x -> col()
            .child(FilterList.filterList(
                x.list, "Search", FilterList.Entries.background(x.app, x.name, x::find))));
    String tree = run(sources.app, "size 320 200\nframe\nawait\nframe");
    assertEquals(List.of("tables:fi"), ComboBoxTest.options(tree));
    sources.name = "columns";
    sources.app.invalidate();
    tree = run(sources.app, "frame\nawait\nframe");
    assertEquals(List.of("columns:fi"), ComboBoxTest.options(tree));
    assertEquals(List.of("tables:fi", "columns:fi"), sources.asked, "each source asked once");
  }
}
