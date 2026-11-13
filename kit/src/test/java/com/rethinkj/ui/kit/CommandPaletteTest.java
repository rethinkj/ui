package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The {@link CommandPalette} of kit's query editor ({@link Studio}): its commands from the menus,
 * the keys, the pointer, the last commands chosen, a source after a prefix, one that asks on
 * another thread, and the steps that preview a theme and ask for a name; with stored images in
 * both themes at scales 1 and 2.
 */
class CommandPaletteTest {

  @TempDir Path out;

  private App<Studio> app;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private String run(String steps) throws IOException {
    if (app == null) {
      app = new Studio(Theme.DARK).app();
      steps = "size 720 400\nframe\npress #query\n" + steps;
    }
    Script.run(steps + "\nframe\nadvance 0.25\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void theCommandsOfTheMenusWithTheirShortcuts() {
    Studio s = new Studio(Theme.DARK);
    List<FilterList.Entry> commands = CommandPalette.commands(s.menus());
    List<String> texts = commands.stream().map(FilterList.Entry::text).toList();
    assertEquals("File: New query", texts.getFirst());
    assertTrue(texts.contains("File: Export › SQL › Script › Schema and data"), texts.toString());
    assertTrue(texts.contains("View: Refresh"), texts.toString());
    FilterList.Entry close = commands.get(texts.indexOf("File: Close"));
    assertFalse(close.isEnabled(), "disabled as in the menu");
    assertEquals(Menu.shortcutText(new Key("W", Key.CONTROL, false)), close.shortcut());
    assertEquals(Menu.CHECKED, commands.get(texts.indexOf("View: Word wrap")).symbol());
    assertEquals(Symbol.ADD, commands.getFirst().symbol());
  }

  @Test
  void typingFindsACommandEnterRunsItAndTheFocusGoesBack() throws IOException {
    String tree = run("key P control shift");
    assertTrue(tree.contains("\noverlay edge top center gap 36 on "), tree);
    assertTrue(tree.contains("el #palette role=dialog label=\"Command palette\""), tree);
    assertTrue(line(tree, "role=textbox label=\"Command\"").contains(" focused"), tree);

    tree = run("type \"nq\"");
    assertEquals("File: New query", ComboBoxTest.options(tree).getFirst());
    assertTrue(line(tree, "#option-0").contains(" selected"), tree);

    tree = run("key Enter");
    assertEquals("New query", app.state().status);
    assertFalse(tree.contains("#palette"), tree);
    assertEquals("query", app.focused().key(), "back where it was");

    tree = run("key F1\nframe\nkey Escape");
    assertFalse(tree.contains("#palette"), tree);
    assertEquals("query", app.focused().key());
  }

  @Test
  void disabledCommandsShowButTheKeysPassThem() throws IOException {
    String tree = run("key F1\nframe\ntype \"file c\"");
    List<String> options = ComboBoxTest.options(tree);
    assertEquals("File: Close", options.getFirst(), options.toString());
    assertTrue(line(tree, "#option-0").contains(" disabled"), tree);
    assertFalse(line(tree, "#option-0").contains(" selected"), "not highlighted: " + tree);
    assertTrue(line(tree, "#option-1").contains(" selected"), tree);
    run("key Enter");
    assertFalse(app.state().palette.isOpen(), "Enter ran the highlighted one, " + options.get(1));
  }

  @Test
  void thePointerHighlightsAndAPressRuns() throws IOException {
    run("key F1\nframe\ntype \"view\"");
    String tree = run("move role=option label=\"View: Refresh\"\nframe\n"
        + "press role=option label=\"View: Refresh\"");
    assertEquals("Refreshed", app.state().status, tree);
    tree = run("key F1\nframe\nadvance 0.25\nframe\npress 700,380");
    assertFalse(tree.contains("#palette"), "a press outside closes it: " + tree);
  }

  @Test
  void theCommandsChosenLastComeFirst() throws IOException {
    run("key F1\nframe\ntype \"refresh\"\nframe\nkey Enter");
    run("key F1\nframe\ntype \"csv\"\nframe\nkey Enter");
    String tree = run("key F1");
    assertTrue(tree.contains("el #group-0 role=group label=\"Recently used\""), tree);
    List<String> options = ComboBoxTest.options(tree);
    assertEquals(List.of("File: Export › CSV", "View: Refresh"), options.subList(0, 2));
    assertTrue(tree.contains("role=group label=\"Other commands\""), tree);
    assertEquals(List.of("File: Export › CSV", "View: Refresh"), app.state().palette.recent());
  }

  @Test
  void aPrefixFindsTablesAndTheTextAfterItFilters() throws IOException {
    String tree = run("key F1\nframe\ntype \"@\"");
    assertEquals(Bench.TABLES.length, ComboBoxTest.options(tree).size());
    tree = run("type \"pay\"");
    assertEquals("payments", ComboBoxTest.options(tree).getFirst());
    assertTrue(tree.contains("\"shop.public.payments\""), "its path at the right: " + tree);
    run("key Enter");
    assertEquals("Open payments", app.state().status);
    assertTrue(app.state().palette.recent().isEmpty(), "a table is no command");
  }

  @Test
  void aSourceOnAnotherThreadShowsLoadingUntilItsAnswer() throws IOException {
    run("");
    app.state().server = text -> List.of(FilterList.entry("remote_" + text, () -> { }));
    String tree = run("key F1\nframe\ntype \"#ord\"");
    assertTrue(tree.contains("\"Loading…\""), tree);
    tree = run("await");
    assertEquals(List.of("remote_ord"), ComboBoxTest.options(tree));
    assertFalse(tree.contains("Loading"), tree);
    tree = run("key Escape");
    assertFalse(tree.contains("#palette"), tree);
  }

  @Test
  void changeThemePreviewsAsTheKeysGoAndEscapeTakesItBack() throws IOException {
    run("key F1\nframe\ntype \"theme\"\nframe\nkey Enter");
    Studio s = app.state();
    String tree = run("");
    assertEquals(List.of("Dark", "Light"), ComboBoxTest.options(tree));
    assertTrue(line(tree, "#option-0").contains(" selected"), "the current one: " + tree);
    run("key ArrowDown");
    assertSame(Theme.LIGHT, s.t, "previewed");
    run("key Escape");
    assertSame(Theme.DARK, s.t, "and taken back");

    run("key F1\nframe\ntype \"theme\"\nframe\nkey Enter\nframe\ntype \"li\"\nframe\nkey Enter");
    assertSame(Theme.LIGHT, s.t);
    assertFalse(s.palette.isOpen());
  }

  @Test
  void aStepAsksForAText() throws IOException {
    run("key F1\nframe\ntype \"rename\"\nframe\nkey Enter");
    String tree = run("");
    assertTrue(tree.contains("role=textbox label=\"The query's new name\" \"orders\""), tree);
    assertFalse(tree.contains("role=listbox"), tree);
    run("type \"_2026\"\nframe\nkey Enter");
    assertEquals("Renamed to orders_2026", app.state().status);
    assertNull(app.state().cleared);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void itLooksLikeAMenu(String theme, int scale) throws IOException {
    Studio.assertScript("palette", theme, scale, 720, 400, out);
    String typed = Studio.tree(out, "palette", theme, scale, "typed");
    assertTrue(ComboBoxTest.options(typed).getFirst().startsWith("File: Export"), typed);
  }

  private static String line(String tree, String part) {
    return tree.lines().filter(l -> l.contains(part)).findFirst().orElse("");
  }
}
