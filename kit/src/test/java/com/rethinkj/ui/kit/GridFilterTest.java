package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.Filter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
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
    assertEquals(
        List.of(new Filter(1, new Filter.TextMatch(Filter.Kind.CONTAINS, "ada"))), ledger.filters);
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
    assertEquals(List.of(new Filter(3, new Filter.Between("10", "100"))), ledger.filters);

    run(
        app,
        "advance 1\nframe\nkey ArrowRight\nkey ArrowRight\nkey ArrowDown alt\nframe\n"
            + "advance 1\nframe\npress #value-paid\npress #value-refunded\nframe\n"
            + "press label=\"Apply\"\nframe");
    assertEquals(
        new Filter(5, new Filter.OneOf(List.of("pending", "shipped"))), ledger.filters.getLast());
    assertEquals(2, ledger.filters.size());

    run(
        app,
        "advance 1\nframe\nkey End\nkey ArrowDown alt\nframe\nadvance 1\nframe\n"
            + "press #kind\nframe\nadvance 1\nframe\npress label=\"Is NULL\"\nframe\n"
            + "press label=\"Apply\"\nframe");
    assertEquals(new Filter(7, new Filter.NullCheck(true)), ledger.filters.getLast());
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

  @Test
  void aRangeRefusesInvalidBoundsAndFromGreaterThanTo() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key ArrowRight\nkey ArrowDown alt\nframe\nadvance 1\nframe\ntype \"abc\"\nframe");
    assertTrue(
        app.tree().dump().contains("role=alert label=\"Not a whole number\""),
        app.tree().dump());
    assertTrue(line(app, "from").contains(" invalid"), line(app, "from"));
    assertFalse(line(app, "to").contains(" invalid"), line(app, "to"));

    run(app, "key Tab\ntype \"xyz\"\nframe");
    assertTrue(line(app, "from").contains(" invalid"), line(app, "from"));
    assertTrue(line(app, "to").contains(" invalid"), "both ends wrong: " + line(app, "to"));

    run(app, "key Tab shift\nkey A control\ntype \"10\"\nkey Tab\nframe");
    assertFalse(line(app, "from").contains(" invalid"), line(app, "from"));
    assertTrue(line(app, "to").contains(" invalid"), line(app, "to"));
    run(app, "key Enter\nframe");
    assertTrue(ledger.filters.isEmpty(), "Enter applies nothing on an invalid To");

    run(app, "key A control\ntype \"5\"\nframe");
    assertTrue(
        app.tree().dump().contains("role=alert label=\"From is greater than To\""),
        app.tree().dump());
    assertTrue(line(app, "from").contains(" invalid"), line(app, "from"));
    assertTrue(line(app, "to").contains(" invalid"), line(app, "to"));
    run(app, "key Enter\nframe");
    assertTrue(ledger.filters.isEmpty(), "Enter applies nothing when From is greater than To");

    run(app, "key A control\ntype \"20\"\nframe\nkey Enter\nframe");
    assertEquals(List.of(new Filter(2, new Filter.Between("10", "20"))), ledger.filters);
  }

  @Test
  void aRangeIsRefusedOnlyWhereFromIsSurelyAboveToInTheValuesOrder() {
    // Times with offsets are ordered by the time, not by their text, as SQL orders them.
    DataGrid.Column at = new DataGrid.Column("at", 100, DataGrid.DataType.DATE_TIME, row -> null);
    assertTrue(GridFilter.above(at, 0, "2026-11-13T09:00+00:00", "2026-11-13T10:00+02:00"));
    assertFalse(GridFilter.above(at, 0, "2026-11-13T10:00+02:00", "2026-11-13T09:00+00:00"));
    DataGrid.Column time = new DataGrid.Column("time", 100, DataGrid.DataType.TIME, row -> null);
    assertFalse(GridFilter.above(time, 0, "14:30+19:00", "14:30+01:00"), "no such offset");
    DataGrid.Column day = new DataGrid.Column("day", 100, DataGrid.DataType.DATE, row -> null);
    assertFalse(GridFilter.above(day, 0, "9999-01-01", "+10000-01-01"));
    DataGrid.Column typed = DataGrid.<java.time.OffsetDateTime>table(i -> null)
        .column("at", 100, Format.OFFSET_DATE_TIME, v -> v);
    assertFalse(
        GridFilter.above(typed, 0, "2026-11-13T10:00+01:00", "2026-11-13T09:00Z"), "one instant");
  }

  @Test
  void aLongEnumerationScrollsAndFiltersItsValuesWithoutDroppingHiddenOnes() throws IOException {
    List<String> choices = new ArrayList<>();
    IntStream.rangeClosed(1, 17).mapToObj(i -> "alpha-" + i).forEach(choices::add);
    choices.addAll(List.of("beta-1", "beta-2", "beta-3"));
    Ledger ledger = new Ledger(T, 40);
    ledger.choices = List.copyOf(choices);
    App<Ledger> app = start(ledger);
    run(app, """
        key ArrowRight
        key ArrowRight
        key ArrowRight
        key ArrowRight
        key ArrowDown alt
        frame
        advance 1
        frame""");
    assertEquals("search", app.focused().key(), "the search field takes focus");
    assertTrue(line(app, "search").contains("label=\"Search values\""), line(app, "search"));
    assertTrue(
        line(app, "values").contains("thumb-y"), "the long list scrolls: " + line(app, "values"));
    String apply = app.tree()
        .dump()
        .lines()
        .map(String::strip)
        .filter(l -> l.startsWith("el role=button label=\"Apply\" "))
        .findFirst()
        .orElseThrow();
    String[] at = apply.split(" ")[3].split(",");
    String[] size = apply.split(" ")[4].split("x");
    assertTrue(
        Float.parseFloat(at[1]) + Float.parseFloat(size[1]) <= 260,
        "Apply stays in the low window: " + apply);

    run(app, "type \" BETA \"\nframe");
    String narrowed = app.tree().dump();
    assertTrue(narrowed.contains("#value-beta-1"), narrowed);
    assertTrue(narrowed.contains("#value-beta-2"), narrowed);
    assertTrue(narrowed.contains("#value-beta-3"), narrowed);
    assertFalse(narrowed.contains("#value-alpha-1"), narrowed);

    run(app, "press #value-beta-2\nframe\npress label=\"Apply\"\nframe");
    List<String> expected = choices.stream().filter(c -> !c.equals("beta-2")).toList();
    assertEquals(List.of(new Filter(5, new Filter.OneOf(expected))), ledger.filters);

    run(app, "advance 1\nframe\nkey ArrowDown alt\nframe\nadvance 1\nframe");
    String reopened = app.tree().dump();
    assertEquals("search", app.focused().key());
    assertTrue(reopened.contains("#value-alpha-1"), "opening resets the search");

    run(app, "type \"beta\"\nframe\nkey Escape\nframe");
    assertTrue(app.tree().dump().contains("role=dialog"), "Escape empties a filled search first");
    assertTrue(app.tree().dump().contains("#value-alpha-1"), "all values return");
    run(app, "key Escape\nframe\nadvance 1\nframe");
    assertFalse(app.tree().dump().contains("role=dialog"), "Escape in an empty search closes");
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
    Golden.assertSnaps("filter-" + name + "-", files);
    assertEquals(4, files.size());
  }
}
