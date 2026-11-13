package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The filters of the grid's columns ({@link DataGrid.Options#filters}): the funnel in the header,
 * the popover of each type, the keys, and the bar of badges, by scripts and the dump.
 */
class GridFilterTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Ledger> start(Ledger ledger) throws IOException {
    ledger.filtering = true;
    App<Ledger> app = ledger.app();
    run(app, "frame\nfocus #ledger\nframe\nframe");
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 640 260\n" + steps, app, out);
  }

  private static String line(App<?> app, String key) {
    String line = dumpLine(app, key);
    assertNotNull(line, key + " in\n" + app.tree().dump());
    return line;
  }

  @Test
  void theFunnelShowsUnderThePointerAndOpensTheFilterOfText() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "move #column-1\nframe\npress #filter-1\nframe\nadvance 1\nframe");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=dialog label=\"Filter customer\""), dump);
    assertEquals("value", app.focused().key());
    run(app, "type \"ada\"\nkey Enter\nframe");
    assertEquals(List.of(Filter.of(1, Filter.Kind.CONTAINS, "ada")), ledger.filters);
    run(app, "leave\nadvance 1\nframe");
    assertTrue(
        line(app, "filter-1").contains("value=\"customer contains ada\""),
        "it shows while set: " + line(app, "filter-1"));
    assertEquals("ledger", app.focused().key(), "the focus back on the grid");
    // The bar above the grid says it, and removes it.
    assertTrue(app.tree().dump().contains("label=\"customer contains ada\""));
    run(app, "press label=\"Remove customer contains ada\"\nframe");
    assertEquals(List.of(), ledger.filters);
  }

  @Test
  void numbersTakeARangeAnEnumerationItsValuesAndAnyColumnNull() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key ArrowRight\nkey ArrowRight\nkey ArrowDown alt\nframe\nadvance 1\nframe");
    assertEquals("from", app.focused().key(), "Alt+Down opens the cursor's column's filter");
    run(app, "type \"10\"\nkey Tab\ntype \"100\"\nkey Enter\nframe");
    assertEquals(List.of(Filter.between(3, "10", "100")), ledger.filters);

    run(
        app,
        "advance 1\nframe\nkey ArrowRight\nkey ArrowRight\nkey ArrowDown alt\nframe\n"
            + "advance 1\nframe\npress #value-paid\npress #value-refunded\nframe\n"
            + "press label=\"Apply\"\nframe");
    assertEquals(Filter.oneOf(5, List.of("pending", "shipped")), ledger.filters.getLast());
    assertEquals(2, ledger.filters.size());

    run(
        app,
        "advance 1\nframe\nkey End\nkey ArrowDown alt\nframe\nadvance 1\nframe\n"
            + "press #kind\nframe\nadvance 1\nframe\npress label=\"Is NULL\"\nframe\n"
            + "press label=\"Apply\"\nframe");
    assertEquals(Filter.of(7, Filter.Kind.IS_NULL, ""), ledger.filters.getLast());
    run(app, "advance 1\nframe");
    assertTrue(app.tree().dump().contains("label=\"Clear all\""), app.tree().dump());
    run(app, "press label=\"Clear all\"\nframe");
    assertTrue(ledger.filters.isEmpty());
  }

  @Test
  void aWrongRegularExpressionCannotBeAppliedAndEscapeLeavesTheFilterAsItWas() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(
        app,
        "key ArrowDown alt\nframe\nadvance 1\nframe\npress #kind\nframe\nadvance 1\nframe\n"
            + "press label=\"Matches regular expression\"\nframe\npress #value\ntype \"(ab\"\n"
            + "frame");
    assertTrue(app.tree().dump().contains("role=alert label=\"Not a regular expression\""));
    assertTrue(line(app, "value").contains(" invalid"), line(app, "value"));
    run(app, "key Enter\nframe");
    assertTrue(ledger.filters.isEmpty(), "Enter applies nothing");
    run(app, "key Escape\nframe\nadvance 1\nframe");
    assertFalse(app.tree().dump().contains("role=dialog"));
    assertTrue(ledger.filters.isEmpty());
  }

  /** filter.txt: the popover of an enumeration open, and filters set with their bar. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Ledger ledger = new Ledger(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 40);
    ledger.filtering = true;
    App<Ledger> app = ledger.app();
    String text = Files.readString(Path.of("src/test/resources/scripts/filter.txt"));
    List<Path> files = Script.run(text, app, out.resolve(name)).stream()
        .filter(f -> !f.toString().endsWith(".clipboard.txt"))
        .toList();
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches(
          "filter-" + name + "-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))),
          Files.readString(files.get(i + 1)));
    }
    assertEquals(4, files.size());
  }
}
