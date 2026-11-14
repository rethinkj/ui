package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Menus of the workbench ({@link Bench}): the File menu as a dropdown with a submenu, the context
 * menu of the tables, the menu of a button in a scrolled panel in the window's corner, and the
 * select of encodings; with the keys, the pointer and stored images in both themes at scales 1
 * and 2.
 */
class MenuTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** Runs these steps of a script in the workbench's window of 360 by 240. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 360 240\n" + steps, app, out);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void theFileMenuOpensBelowItsButtonWalksIntoItsSubmenuAndCloses(String theme, int scale)
      throws IOException {
    App<Bench> app = Bench.assertScript("menu", theme, scale, out);
    String open = Bench.tree(out, "menu", theme, scale, "open");
    assertTrue(
        open.contains("\noverlay bottom start gap 2 on #file role=button label=\"File\""), open);
    assertTrue(open.contains("  el #menu role=menu "), open);
    assertTrue(open.contains(" focused\n"), "the menu took the focus");
    String submenu = Bench.tree(out, "menu", theme, scale, "submenu");
    assertTrue(
        submenu.contains(
            "\noverlay right start gap 5 on #item-3 role=menuitem" + " label=\"Export\""),
        submenu);
    String dismissed = Bench.tree(out, "menu", theme, scale, "dismissed");
    assertFalse(dismissed.contains("overlay"), dismissed);
    assertTrue(dismissed.contains("\"Exported as JSON · orders · UTF-8 · wrap\""), dismissed);
    assertTrue(dismissed.contains("label=\"File\""), dismissed);
    assertFalse(app.state().file.isOpen());
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void aSecondaryPressOnARowOpensItsContextMenuWhereLettersJump(String theme, int scale)
      throws IOException {
    App<Bench> app = Bench.assertScript("context", theme, scale, out);
    String open = Bench.tree(out, "context", theme, scale, "open");
    assertTrue(open.contains("\noverlay at "), open);
    assertTrue(open.contains(" bottom start on #tables role=list label=\"Tables\""), open);
    String typed = Bench.tree(out, "context", theme, scale, "typed");
    assertTrue(typed.contains("label=\"Drop table\""), typed);
    assertFalse(app.state().tables.isOpen(), "Escape closed it");
    assertEquals(2, app.state().selected);
    assertEquals("tables", app.focused().key(), "the focus went back to the list");
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void aMenuInTheCornerFlipsAboveAndToTheLeftOfItsButtonAndIsDrawnWhole(String theme, int scale)
      throws IOException {
    Bench.assertScript("corner", theme, scale, out);
    String open = Bench.tree(out, "corner", theme, scale, "open");
    String[] lines = open.split("\n");
    int at = 0;
    while (!lines[at].startsWith("overlay ")) {
      at++;
    }
    String anchor = lines[at];
    String menu = lines[at + 1];
    float[] button = rect(anchor);
    float[] box = rect(menu);
    assertTrue(box[1] + box[3] <= button[1], "above the button: " + anchor + " / " + menu);
    assertEquals(button[0] + button[2], box[0] + box[2], 0.01, "its right edge at the button's");
    assertTrue(
        box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= 360 && box[1] + box[3] <= 240,
        "inside the window: " + menu);
  }

  /** The rectangle {@code x,y wxh} at the end of a dump's line, before its flags. */
  private static float[] rect(String line) {
    for (String word : line.split(" ")) {
      if (word.matches("-?[\\d.]+,-?[\\d.]+")) {
        int i = line.indexOf(" " + word + " ") + word.length() + 2;
        String[] size = line.substring(i).split(" ")[0].split("x");
        String[] at = word.split(",");
        return new float[] {
          Float.parseFloat(at[0]),
          Float.parseFloat(at[1]),
          Float.parseFloat(size[0]),
          Float.parseFloat(size[1])
        };
      }
    }
    throw new AssertionError("no rectangle in " + line);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void aSelectOpensItsChoicesAsWideAsItselfAndChoosesWithTheKeys(String theme, int scale)
      throws IOException {
    App<Bench> app = Bench.assertScript("select", theme, scale, out);
    String open = Bench.tree(out, "select", theme, scale, "open");
    assertTrue(
        open.contains(
            "\noverlay bottom stretch gap 2 on #encoding role=combobox" + " label=\"UTF-8\""),
        open);
    assertEquals(1, app.state().encoding, "Arrow Down and Enter chose UTF-16");
  }

  @Test
  void menusFadeInAndOutTakeNoInputWhileLeavingAndThenNothingIsDrawn() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #file\nframe\n");
    El menu = app.tree().shownOverlays().getFirst();
    assertEquals(0, menu.opacity(), "it fades in from nothing");
    run(app, "advance 0.05\nframe\n");
    float half = app.tree().shownOverlays().getFirst().opacity();
    assertTrue(half > 0 && half < 1, "on its way: " + half);
    run(app, "advance 0.2\nframe\n");
    assertEquals(1, app.tree().shownOverlays().getFirst().opacity());
    assertFalse(app.isMoving());
    String item = null;
    for (String line : app.tree().dump().split("\n")) {
      item = line.contains("label=\"Open…\"") && item == null ? line : item;
    }
    float[] open = rect(item);

    run(app, "key Escape\nframe\n");
    assertFalse(bench.file.isOpen());
    assertTrue(app.isMoving(), "it fades out");
    assertTrue(app.tree().dump().contains(" role=menu ") && app.tree().dump().contains(" exiting"));
    app.pointerDown(open[0] + 10, open[1] + 10);
    app.pointerUp(open[0] + 10, open[1] + 10);
    app.frame();
    assertEquals("Ready", bench.status, "the press on Open… while it fades out chose nothing");
    app.advance(0.25);
    assertTrue(app.frame(), "the end of the fade");
    assertFalse(app.isMoving());
    assertTrue(app.tree().shownOverlays().isEmpty());
    assertFalse(app.frame(), "and then nothing is drawn");
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange(), "an idle window waits for input");
  }

  @Test
  void aClosedSelectChoosesWithArrowsAndLettersAndOpensWithAltDown() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\nfocus #encoding\nkey ArrowDown\nkey ArrowDown\nframe\n");
    assertEquals(2, bench.encoding, "Down chooses the next, without opening");
    assertFalse(bench.encodings.isOpen());
    run(app, "key ArrowUp\nframe\n");
    assertEquals(1, bench.encoding);
    run(app, "key End\nkey ArrowDown\nframe\n");
    assertEquals(3, bench.encoding, "the last, where Down stays");
    run(app, "key U\nframe\n");
    assertEquals(0, bench.encoding, "the next that starts with U, around past the end");
    run(app, "key U\nframe\nkey I\nframe\n");
    assertEquals(2, bench.encoding, "UTF-16, then ISO-8859-1");
    assertTrue(app.tree().dump().contains("role=combobox label=\"ISO-8859-1\""));
    run(app, "key ArrowDown alt\nframe\n");
    assertTrue(bench.encodings.isOpen(), "Alt+Down opens it");
    assertEquals(2, bench.encodings.active(), "with the choice highlighted");
    run(app, "key ArrowDown\nkey ArrowUp alt\nframe\n");
    assertFalse(bench.encodings.isOpen(), "Alt+Up closes it");
    assertEquals(2, bench.encoding, "without choosing");
    assertEquals("encoding", app.focused().key());
  }

  @Test
  void keysMoveTheHighlightAroundPastSeparatorsAndOpenAndCloseTheSubmenu() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #file\nframe\n");
    assertEquals(-1, bench.file.active(), "opened by the pointer: nothing highlighted");
    run(app, "key ArrowUp\nframe\n");
    assertEquals(6, bench.file.active(), "around to the last item, which is disabled");
    run(app, "key ArrowDown\nframe\n");
    assertEquals(0, bench.file.active());
    run(app, "key End\nkey ArrowUp\nkey ArrowUp\nframe\n");
    assertEquals(3, bench.file.active(), "past the separator to Export");
    run(app, "key Enter\nframe\n");
    assertEquals(3, bench.file.submenu(), "Enter opens a submenu");
    assertEquals("submenu", app.focused().key(), "which takes the focus");
    run(app, "key ArrowLeft\nframe\n");
    assertEquals(-1, bench.file.submenu());
    assertEquals("menu", app.focused().key(), "and gives it back");
    run(app, "key W\nframe\nkey Enter\nframe\n");
    assertFalse(bench.wrap, "W went to Word wrap, Enter unchecked it");
    assertFalse(bench.file.isOpen());
    assertNull(app.focused(), "back where it was before the press that opened the menu");
    run(app, "press #file\nframe\nkey End\nkey Enter\nframe\n");
    assertTrue(bench.file.isOpen(), "Close is disabled: Enter does nothing");
    run(app, "key Tab\nframe\n");
    assertFalse(bench.file.isOpen(), "Tab closes it");
  }

  @Test
  void thePointerHighlightsAndASubmenuClosesAfterRestingOnAnotherItem() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #file\nframe\nadvance 0.25\nframe\nmove label=\"Export\"\nframe\n");
    assertEquals(3, bench.file.active());
    assertEquals(-1, bench.file.submenu(), "not yet");
    assertEquals(app.time() + 0.3, app.nextChange(), 1e-9);
    run(app, "advance 0.3\nframe\n");
    assertEquals(3, bench.file.submenu());
    run(app, "advance 0.25\nframe\nmove label=\"JSON\"\nframe\n"); // after its fade
    assertEquals(3, bench.file.submenu(), "moving into the submenu keeps it");
    run(app, "move label=\"New query\"\nframe\nadvance 0.1\nframe\n");
    assertEquals(3, bench.file.submenu(), "passing over another item keeps it");
    run(app, "advance 0.25\nframe\n");
    assertEquals(-1, bench.file.submenu(), "resting on it closes it");
    run(app, "press label=\"Open…\"\nframe\n");
    assertEquals("Open", bench.status);
  }

  @Test
  void aPressOnTheMenuButtonWhileItIsOpenClosesItAndNothingElse() throws IOException {
    Bench bench = new Bench(Theme.LIGHT);
    App<Bench> app = bench.app();
    run(app, "frame\npress #file\nframe\npress #file\nframe\n");
    assertFalse(bench.file.isOpen());
    run(app, "press #file\nframe\npress #rename\nframe\n");
    assertFalse(bench.file.isOpen());
    assertFalse(bench.renaming, "the press that closed the menu pressed nothing");
  }

  /** A menu of these items, open, in a window of its own; F2 makes the items those of {@code next}. */
  private static final class Changing {
    final Menu.State menu = new Menu.State();
    List<Menu.Item> items;
    List<Menu.Item> next;

    Changing(List<Menu.Item> items, List<Menu.Item> next) {
      this.items = items;
      this.next = next;
      menu.open();
    }

    El view() {
      return col().wFull().hFull().overlay(Menu.menu(Theme.DARK, menu, items)).onKey(k -> {
        if (!k.name().equals("F2")) {
          return false;
        }
        items = next;
        return true;
      });
    }
  }

  @Test
  void itemsThatChangeWhileTheMenuIsOpenTakeAwayAHighlightAndASubmenuTheyNoLongerHave()
      throws IOException {
    List<String> chosen = new ArrayList<>();
    Menu.Item a = Menu.item("A", () -> chosen.add("A"));
    Changing fewer = new Changing(
        List.of(a, Menu.item("B", () -> chosen.add("B")), Menu.item("C", () -> {})), List.of(a));
    App<Changing> app = new App<>(fewer, Changing::view);
    run(app, "frame\nkey End\nkey F2\nkey Enter\nframe\n");
    assertEquals(List.of(), chosen, "the highlighted item is gone: Enter chooses nothing");
    assertTrue(fewer.menu.isOpen());
    assertEquals(-1, fewer.menu.active());
    run(app, "key ArrowDown\nkey Enter\nframe\n");
    assertEquals(List.of("A"), chosen);

    Changing separated =
        new Changing(List.of(a, Menu.item("B", () -> {})), List.of(a, Menu.separator()));
    app = new App<>(separated, Changing::view);
    run(app, "frame\nkey End\nkey F2\nframe\n");
    assertEquals(-1, separated.menu.active(), "a separator is never highlighted");

    Menu.Item export = Menu.submenu("Export", List.of(Menu.item("CSV", () -> {})));
    Changing changed = new Changing(
        List.of(a, export),
        List.of(Menu.separator(), Menu.item("Export", () -> chosen.add("Export"))));
    app = new App<>(changed, Changing::view);
    run(app, "frame\nkey End\nkey ArrowRight\nkey F2\nframe\n");
    assertEquals(-1, changed.menu.submenu(), "the item no longer opens a submenu");
    assertEquals(-1, changed.menu.active(1));
    assertFalse(app.tree().dump().contains("#submenu "), app.tree().dump());
    assertEquals(1, changed.menu.active(), "and stays highlighted");
    run(app, "key Enter\nframe\n");
    assertEquals(List.of("A", "Export"), chosen);
  }

  @Test
  void theKeysKeepTheHighlightedItemInViewOfAMenuThatScrolls() throws IOException {
    Menu.State state = new Menu.State();
    state.open();
    List<Menu.Item> items = IntStream.range(0, 30)
        .mapToObj(i -> i == 3
            ? Menu.submenu(
                "Item 3",
                IntStream.range(0, 30)
                    .mapToObj(j -> Menu.item("Sub " + j, () -> {}))
                    .toList())
            : Menu.item("Item " + i, () -> {}))
        .toList();
    App<Menu.State> app =
        new App<>(state, s -> col().wFull().hFull().overlay(Menu.menu(Theme.DARK, s, items)));
    Script.run("size 400 160\nframe\nkey End\nframe\n", app, out);
    El menu = app.tree().shownOverlays().getFirst();
    assertTrue(inView(menu, 29), "End: the last item");
    Script.run("key Home\nframe\n", app, out);
    assertTrue(inView(menu = app.tree().shownOverlays().getFirst(), 0), "Home: the first");
    Script.run("key ArrowUp\nframe\nkey ArrowDown\nframe\n", app, out);
    assertTrue(inView(menu = app.tree().shownOverlays().getFirst(), 0), "around both ends");
    Script.run("key I\nkey I\nkey I\nkey ArrowRight\nframe\nkey End\nframe\n", app, out);
    assertEquals(3, state.submenu());
    El submenu = app.tree().shownOverlays().getLast();
    assertTrue(inView(submenu, 29), "in a submenu too");
    assertTrue(inView(app.tree().shownOverlays().getFirst(), 3), "its item still in view");
  }

  /** Whether the child at {@code index} of a menu lies whole in its box, where it shows. */
  private static boolean inView(El menu, int index) {
    El item = menu.children().get(index); // placed as shown, moved by the menu's scroll
    return item.y() >= 0 && item.y() + item.height() <= menu.height();
  }

  @Test
  void enterOnTheButtonOfADropdownOpensItsMenuOnTheFirstItemAndAPressOnNone() throws IOException {
    Menu.State state = new Menu.State();
    List<Menu.Item> items =
        List.of(Menu.item("Off", null), Menu.item("A", () -> {}), Menu.item("B", () -> {}));
    App<Menu.State> app = new App<>(
        state,
        s -> col()
            .wFull()
            .hFull()
            .child(Menu.dropdown(
                Theme.DARK,
                Button.button(Theme.DARK, Button.Variant.SECONDARY, "Open", s::toggle)
                    .key("open"),
                s,
                items)));
    run(app, "frame\nfocus #open\nkey Enter\nframe\n");
    assertEquals(1, state.active(), "the first enabled item");
    run(app, "key Escape\nframe\nkey Space\nframe\n");
    assertEquals(1, state.active());
    run(app, "key Escape\nframe\nkey ArrowUp\nframe\n");
    assertEquals(2, state.active(), "Arrow Up: the last");
    run(app, "key Escape\nframe\nkey ArrowDown\nframe\n");
    assertEquals(1, state.active());
    run(app, "key Escape\nframe\npress #open\nframe\n");
    assertTrue(state.isOpen());
    assertEquals(-1, state.active(), "the pointer highlights nothing");
  }

  /** A menu whose submenu Colour shows a swatch before each hue's name, the chosen one checked. */
  static final class Colours {
    final Theme t;
    final Menu.State menu = new Menu.State();
    Theme.Hue hue = Theme.Hue.RED;

    Colours(Theme t) {
      this.t = t;
    }

    List<Menu.Item> items() {
      List<Menu.Item> colours = new ArrayList<>();
      colours.add(Menu.item("None", () -> hue = null)
          .leading(t -> Icon.icon(t, Symbol.PROHIBITED, Icon.Variant.MUTED))
          .checked(hue == null));
      for (Theme.Hue each : Theme.Hue.values()) {
        colours.add(Menu.item(HuePicker.name(each), () -> hue = each)
            .leading(t -> HuePicker.swatch(t, each))
            .checked(each == hue));
      }
      return List.of(
          Menu.item("Rename…", () -> {}).icon(Symbol.EDIT),
          Menu.submenu("Colour", colours),
          Menu.item("Pinned", () -> {}).checked(true));
    }

    El view() {
      return com.rethinkj.ui.El.row()
          .wFull()
          .hFull()
          .padding(t.space().l())
          .align(com.rethinkj.ui.Style.Align.START)
          .fill(t.surface().window())
          .child(Menu.dropdown(
              t,
              Button.button(t, Button.Variant.SECONDARY, "Connection", menu::toggle)
                  .key("open"),
              menu,
              items()));
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"dark", "light"})
  void aSubmenuShowsASwatchBeforeEachHueAndTheCheckAtTheChosenOnesEnd(String theme)
      throws IOException {
    Colours s = new Colours(theme.equals("dark") ? Theme.DARK : Theme.LIGHT);
    App<Colours> app = new App<>(s, Colours::view);
    app.focusRing(s.t.accent().edge());
    app.textRendering(com.rethinkj.ui.text.TextRendering.LINUX);
    String image = "menu-swatches-" + theme + "-2x";
    Script.run(
        "size 400 400 scale 2\nframe\npress #open\nframe\nadvance 1\nframe\n"
            + "press label=\"Colour\"\nframe\nadvance 1\nframe\nsnap "
            + image,
        app,
        out);
    String tree = java.nio.file.Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(
        image,
        com.rethinkj.ui.render.Png.decode(
            java.nio.file.Files.readAllBytes(out.resolve(image + ".png"))),
        tree);
    String red = StructureTest.line(tree, "label=\"Red\"");
    assertTrue(red.endsWith(" checked"), red);
    // The swatch in the column of icons, the text after it, the check mark at the end.
    String after = tree.substring(tree.indexOf(red));
    String[] lines = after.lines().limit(7).toArray(String[]::new);
    assertTrue(lines[4].contains("text \"Red\""), after);
    assertTrue(lines[5].contains("label=\"checkmark\""), after);
    float[] swatch = StructureTest.rect(lines[3]);
    assertEquals(16, swatch[2], lines[3]);
    // An item checked without a leading element keeps its check in the column, as before.
    String pinned = tree.substring(tree.indexOf("label=\"Pinned\""));
    assertTrue(pinned.lines().skip(3).findFirst().orElseThrow().contains("checkmark"), pinned);
    run(app, "press label=\"Teal\"\nframe");
    assertEquals(Theme.Hue.TEAL, s.hue);
  }

  @Test
  void theCommandPaletteCarriesALeadingElementOverWithItsCheck() {
    Colours s = new Colours(Theme.DARK);
    List<FilterList.Entry> commands = CommandPalette.commands(s.items());
    FilterList.Entry red = commands.stream()
        .filter(e -> e.text().equals("Colour: Red"))
        .findFirst()
        .orElseThrow();
    assertTrue(red.leading() != null && red.checked() && red.symbol() == null, red.toString());
    FilterList.Entry pinned =
        commands.stream().filter(e -> e.text().equals("Pinned")).findFirst().orElseThrow();
    assertEquals(Menu.CHECKED, pinned.symbol());
    assertFalse(pinned.checked());
  }
}
