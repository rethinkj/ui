package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.WindowPart;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The menu bar of the studio ({@link Studio}) in the title bar of a frameless window: the pointer,
 * the keys from Alt and F10, mnemonics, submenus three deep at the window's edge and the key
 * combinations of the items, with stored images in both themes at scales 1 and 2.
 */
class MenuBarTest {

  @TempDir Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** Runs these steps of a script in the studio's window of 360 by 240. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 360 240\n" + steps, app, out);
  }

  private static String line(App<?> app, String key) {
    return line(app.tree().dump(), key);
  }

  /** The first line of a dump with this key, without its indentation. */
  private static String line(String tree, String key) {
    for (String line : tree.split("\n")) {
      if (line.contains("#" + key + " ")) {
        return line.strip();
      }
    }
    throw new AssertionError("no #" + key + " in\n" + tree);
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void aPressOpensAMenuAndThePointerSwitchesToAnother(String theme, int scale)
      throws IOException {
    App<Studio> app = Studio.assertScript("menubar", theme, scale, out);
    String open = Studio.tree(out, "menubar", theme, scale, "open");
    assertEquals("el #title-0 role=menuitem label=\"File\" 28,4 38x24 expanded",
        line(open, "title-0"));
    assertTrue(open.contains("\noverlay bottom start gap 2 on #title-0 role=menuitem"
        + " label=\"File\""), open);
    assertTrue(line(open, "item-6").endsWith(" disabled"), "Close");
    assertTrue(line(open, "menu").endsWith(" focused"), open);
    String switched = Studio.tree(out, "menubar", theme, scale, "switched");
    assertTrue(line(switched, "title-1").endsWith(" expanded hovered"), switched);
    assertTrue(line(switched, "title-0").endsWith(" expanded=false"), switched);
    assertEquals(1, app.state().bar.open());
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void submenusGoThreeDeepAndOnToTheLeftAtTheWindowsEdge(String theme, int scale)
      throws IOException {
    App<Studio> app = Studio.assertScript("nested", theme, scale, 480, 280, out);
    String deep = Studio.tree(out, "nested", theme, scale, "deep");
    assertEquals(4, deep.split("\noverlay ", -1).length - 1, "the menu and three submenus");
    assertTrue(line(deep, "item-4").endsWith(" expanded"), deep);
    Menu.State menu = app.state().bar.menu();
    assertEquals(List.of(4, 2, 1, 0), List.of(menu.active(0), menu.active(1), menu.active(2),
        menu.active(3)));
    float[] script = rect(line(app, "subitem-1 role=menuitem label=\"Script\""));
    assertTrue(line(app, "subitem-1 role=menuitem label=\"Script\"").endsWith(" expanded"));
    El last = app.tree().shownOverlays().getLast();
    assertTrue(last.x() + last.width() <= script[0], "left of its item: " + deep);
    run(app, "key Enter\nframe\nadvance 0.25\nframe\n"); // the menus fade out
    assertEquals("Exported the schema", app.state().status);
    assertTrue(app.tree().shownOverlays().isEmpty());
  }

  /** The rectangle {@code x,y wxh} of a dump's line. */
  private static float[] rect(String line) {
    for (String word : line.split(" ")) {
      if (word.matches("-?[\\d.]+,-?[\\d.]+")) {
        String[] at = word.split(",");
        return new float[] {Float.parseFloat(at[0]), Float.parseFloat(at[1])};
      }
    }
    throw new AssertionError(line);
  }

  @Test
  void deeperSubmenusOpenAndCloseAfterTheRestAsTheFirstLevelDoes() throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    Menu.State menu = studio.bar.menu();
    // Each menu fades in for 0.2 seconds before the pointer can reach its items.
    run(app, "frame\npress #title-0\nframe\nadvance 0.25\nframe\nmove label=\"Export\"\nframe\n"
        + "advance 0.3\nframe\nadvance 0.25\nframe\nmove label=\"SQL\"\nframe\n");
    assertEquals(2, menu.active(1), "the pointer highlights at once");
    assertEquals(-1, menu.submenu(1), "the submenu waits");
    run(app, "advance 0.3\nframe\nadvance 0.25\nframe\nmove label=\"Script\"\nframe\n"
        + "advance 0.3\nframe\nadvance 0.25\nframe\n");
    assertEquals(List.of(4, 2, 1), List.of(menu.submenu(0), menu.submenu(1), menu.submenu(2)));
    run(app, "move label=\"Schema and data\"\nframe\nmove label=\"CSV\"\nframe\nadvance 0.1\n"
        + "frame\n");
    assertEquals(2, menu.submenu(1), "passing over another item keeps them");
    run(app, "advance 0.25\nframe\n");
    assertEquals(List.of(4, -1, -1), List.of(menu.submenu(0), menu.submenu(1), menu.submenu(2)),
        "resting on CSV closes SQL's and the one inside it");
    assertEquals("submenu", app.focused().key(), "Export's, which the closed ones gave it back to");
    run(app, "key ArrowDown\nkey ArrowDown\nkey Enter\nframe\n");
    assertEquals(List.of(4, 2, -1), List.of(menu.submenu(0), menu.submenu(1), menu.submenu(2)),
        "the keys there: Down to SQL, Enter opens its submenu");
    assertEquals(0, menu.active(2), "with Inserts highlighted");
    run(app, "key Escape\nframe\n");
    assertEquals(List.of(4, -1, -1), List.of(menu.submenu(0), menu.submenu(1), menu.submenu(2)));
  }

  @Test
  void thePressesOnTitlesSwitchCloseAndGiveTheFocusBackToTheField() throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    run(app, "frame\npress #query\npress #title-0\nframe\n");
    assertEquals(0, studio.bar.open());
    assertEquals("menu", app.focused().key());
    run(app, "press #title-2\nframe\n");
    assertEquals(2, studio.bar.open(), "one press switched to View");
    run(app, "press #title-2\nframe\n");
    assertEquals(-1, studio.bar.open(), "its own title closed it");
    assertEquals("query", app.focused().key(), "the titles left the focus in the field");
    run(app, "press #title-1\nframe\npress 20,200\nframe\n");
    assertEquals(-1, studio.bar.open());
    assertEquals("Ready", studio.status, "the press outside did nothing else");
    run(app, "press #title-1\nframe\nmove #title-3\nframe\n");
    assertEquals(3, studio.bar.open(), "moving onto Help switched");
    run(app, "advance 0.25\nframe\npress label=\"About\"\nframe\n");
    assertEquals("About", studio.status);
    assertEquals("query", app.focused().key());
  }

  @Test
  void theTitlesTakePressesAndTheSpaceAroundThemMovesTheWindow() throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    run(app, "frame\n");
    // Controls: they take presses, and the resize margin gives way to them.
    assertEquals(WindowPart.CONTROL, app.windowPart(40, 16), "File");
    assertEquals(WindowPart.CONTROL, app.windowPart(170, 16), "Help");
    assertEquals(WindowPart.DRAG, app.windowPart(66, 2), "above Edit, in the bar's margin");
    assertEquals(WindowPart.DRAG, app.windowPart(240, 16), "beside the titles");
    assertEquals(WindowPart.CLOSE, app.windowPart(340, 16));
    run(app, "press #title-0\nframe\n");
    assertEquals(WindowPart.DRAG, app.windowPart(240, 16), "also while a menu is open");
  }

  @Test
  void altAloneGivesTheBarTheKeysAndEscapeStepsBackOneLevelAtATime() throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    run(app, "frame\npress #query\nkey Alt\nframe\n");
    assertEquals(0, studio.bar.title());
    assertEquals("menubar", app.focused().key(), "the bar holds the focus");
    assertTrue(line(app, "title-0").endsWith("expanded=false"), line(app, "title-0"));
    run(app, "key ArrowRight\nkey ArrowRight\nframe\n");
    assertEquals(2, studio.bar.title());
    run(app, "key ArrowDown\nframe\n");
    assertEquals(2, studio.bar.open(), "Down opens View");
    assertEquals(0, studio.bar.menu().active(), "with its first item highlighted");
    run(app, "key ArrowRight\nframe\n");
    assertEquals(3, studio.bar.open(), "Right on an item without a submenu: the next menu");
    run(app, "key ArrowRight\nframe\n");
    assertEquals(0, studio.bar.open(), "around to File");
    run(app, "key End\nkey ArrowUp\nkey ArrowUp\nframe\n");
    assertEquals(4, studio.bar.menu().active(), "Export");
    run(app, "key ArrowRight\nframe\n");
    assertEquals(4, studio.bar.menu().submenu(), "Right on Export opens its submenu");
    run(app, "key ArrowLeft\nframe\n");
    assertEquals(-1, studio.bar.menu().submenu(), "Left closes it");
    assertEquals(0, studio.bar.open(), "and stays in File");
    run(app, "key ArrowLeft\nframe\n");
    assertEquals(3, studio.bar.open(), "Left in the menu: the previous menu");

    run(app, "key Escape\nframe\n");
    assertEquals(-1, studio.bar.open(), "Escape closes the menu");
    assertEquals(3, studio.bar.title(), "and the bar keeps the keys");
    assertEquals("menubar", app.focused().key());
    run(app, "key Escape\nframe\n");
    assertEquals(-1, studio.bar.title());
    assertEquals("query", app.focused().key(), "the focus is back where it was");

    run(app, "key F10\nframe\n");
    assertEquals(0, studio.bar.title(), "F10 too");
    run(app, "key F10\nframe\n");
    assertEquals(-1, studio.bar.title(), "and F10 again leaves");
    assertEquals("query", app.focused().key());
    run(app, "key Alt\nframe\nkey E\nframe\n");
    assertEquals(1, studio.bar.open(), "a letter in the bar opens the menu of that mnemonic");
    run(app, "key Alt\nframe\n");
    assertEquals(-1, studio.bar.open(), "Alt alone again closes everything");
    assertEquals("query", app.focused().key());
  }

  @Test
  void theArrowsGoOnFromAMenuThePointerOpenedAndEscapeClosesItAtOnce() throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    run(app, "frame\npress #query\npress #title-0\nframe\n");
    assertEquals(-1, studio.bar.menu().active(), "opened by the pointer: nothing highlighted");
    run(app, "key ArrowRight\nframe\n");
    assertEquals(1, studio.bar.open(), "Right goes to Edit");
    assertEquals(0, studio.bar.menu().active(), "with its first item highlighted");
    assertEquals(-1, studio.bar.title(), "the bar did not take the keys");
    run(app, "key Escape\nframe\n");
    assertEquals(-1, studio.bar.open());
    assertEquals("query", app.focused().key(), "one Escape: back in the field");
  }

  @Test
  void aKeyThatTypesIsNamedInUpperCase() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> Menu.item("New", () -> {}).shortcut(Key.CONTROL, "n"));
    assertTrue(e.getMessage().contains("\"N\""), e.getMessage());
  }

  @Test
  void altWithALetterOpensItsMenuAndAPressOutsideTheBarLeavesIt() throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    run(app, "frame\npress #query\nkey V alt\nframe\n");
    assertEquals(2, studio.bar.open());
    run(app, "key Enter\nframe\n");
    assertFalse(studio.wrap, "Word wrap, highlighted first, was chosen");
    assertEquals(-1, studio.bar.title(), "choosing left the bar");
    assertEquals("query", app.focused().key());
    run(app, "key Alt\nframe\npress #status\nframe\n");
    assertEquals(-1, studio.bar.title(), "a press outside the bar leaves it");
    assertEquals("query", app.focused().key());
    run(app, "key Alt\nframe\npress #title-1\nframe\n");
    assertEquals(1, studio.bar.open(), "a press on a title of the bar opens it");
  }

  @Test
  void theMnemonicsAreUnderlinedWhileAltIsHeldOrTheBarHasTheKeys() throws IOException {
    Studio studio = new Studio(Theme.LIGHT);
    App<Studio> app = studio.app();
    run(app, "frame\n");
    assertNull(underline(app, "title-0"));
    app.keyDown(new Key("Alt", Key.ALT, false));
    app.frame();
    assertNotNull(underline(app, "title-0"), "while Alt is down");
    assertEquals(0, underline(app, "title-0").x(), "under the F");
    app.keyDown(new Key("Shift", Key.ALT | Key.SHIFT, false)); // another key: not alone
    app.keyUp(new Key("Shift", Key.ALT, false));
    app.keyUp(new Key("Alt", 0, false));
    app.frame();
    assertNull(underline(app, "title-0"));
    assertEquals(-1, studio.bar.title(), "Alt with another key does not give the bar the keys");
    run(app, "key F10\nframe\n");
    assertNotNull(underline(app, "title-3"), "while the bar has the keys");
  }

  @Test
  void altHeldDuringAPressDoesNotGiveTheBarTheKeys() throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    run(app, "frame\npress #query\nframe\n");
    float[] at = {100, 56}; // in the field, which takes the press before any handler around it
    app.keyDown(new Key("Alt", Key.ALT, false));
    app.pointerDown(at[0], at[1], 1, Key.ALT);
    app.pointerUp(at[0], at[1]);
    app.keyUp(new Key("Alt", 0, false));
    app.frame();
    assertEquals(-1, studio.bar.title(), "Alt+click, as in Windows, opens nothing");
    assertNull(underline(app, "title-0"), "and the mnemonics go with Alt");
    assertTrue(line(app, "query").contains("focused"), line(app, "query"));
    app.keyDown(new Key("Alt", Key.ALT, false));
    app.secondaryDown(300, 150);
    app.secondaryUp(300, 150);
    app.keyUp(new Key("Alt", 0, false));
    app.frame();
    assertEquals(-1, studio.bar.title(), "nor with the secondary button");
    run(app, "key Alt\nframe\n");
    assertEquals(0, studio.bar.title(), "Alt alone still does");
  }

  @Test
  void altWithAKeyThatAnotherElementTookDoesNotGiveTheBarTheKeys() {
    MenuBar.State bar = new MenuBar.State();
    int[] taken = {0};
    List<Menu.Item> menus = List.of(Menu.submenu("&File", List.of(Menu.item("Open", () -> {}))));
    App<Object> app = new App<>(new Object(), s -> col().wFull().hFull()
        .onKey(MenuBar.keys(bar, menus)).onKeyUp(MenuBar.keysUp(bar, menus))
        .child(MenuBar.menuBar(Theme.DARK, bar, menus))
        .child(el().key("finder").size(100, 30).focusable().onPress(() -> {})
            .onKey(k -> k.modifiers() == Key.ALT && k.name().equals("C") && ++taken[0] > 0)));
    app.resize(400, 200, 1);
    app.frame();
    app.focus(find(app.tree(), "finder"));
    app.frame();
    // Alt+C, which the focused element takes, as a find bar takes it for Match case.
    app.keyDown(new Key("Alt", Key.ALT, false));
    app.keyDown(new Key("C", Key.ALT, false));
    app.keyUp(new Key("C", Key.ALT, false));
    app.keyUp(new Key("Alt", 0, false));
    app.frame();
    assertEquals(1, taken[0]);
    assertEquals(-1, bar.title(), "Alt was not alone");
    assertEquals("finder", app.focused().key());
    // Alt released before the key, as a fast hand does.
    app.keyDown(new Key("Alt", Key.ALT, false));
    app.keyDown(new Key("C", Key.ALT, false));
    app.keyUp(new Key("Alt", 0, false));
    app.keyUp(new Key("C", 0, false));
    app.frame();
    assertEquals(2, taken[0]);
    assertEquals(-1, bar.title(), "nor when Alt goes up first");
    app.keyDown(new Key("Alt", Key.ALT, false));
    app.keyUp(new Key("Alt", 0, false));
    app.frame();
    assertEquals(0, bar.title(), "Alt alone still gives the bar the keys");
  }

  private static com.rethinkj.ui.Text.Box underline(App<?> app, String title) {
    return find(app.tree(), title).children().getFirst().text().composition();
  }

  @Test
  void keyCombinationsRunTheirItemsUnlessAFieldTakesTheKeyOrTheItemIsDisabled()
      throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    run(app, "frame\npress #query\nkey N control\nframe\n");
    assertEquals("New query", studio.status, "from the field, which does not take Ctrl+N");
    run(app, "key A control\nkey C control\nframe\n");
    assertEquals("New query", studio.status, "Ctrl+C in the field copies its text");
    assertEquals("SELECT * FROM orders", app.clipboard().read());
    run(app, "key W control\nframe\n");
    assertEquals("New query", studio.status, "Close is disabled");
    run(app, "key Z control shift\nframe\n");
    assertEquals("New query", studio.status, "the field redoes with Ctrl+Shift+Z");
    run(app, "press #title-1\nframe\nkey F5\nframe\n");
    assertEquals("Refreshed", studio.status, "with a menu open too");
    assertEquals(-1, studio.bar.open(), "which closes");
    assertEquals("query", app.focused().key());
    run(app, "press #status\nkey C control\nframe\n");
    assertEquals("Copied", studio.status, "without the field's focus, Ctrl+C is Edit's Copy");
    run(app, "key Z control shift\nframe\n");
    assertEquals("Redone", studio.status);
  }

  @Test
  void twoItemsWithOneCombinationAreRefusedWithBothLabels() {
    List<Menu.Item> items = List.of(
        Menu.item("New query", () -> {}).shortcut(Key.CONTROL, "N"),
        Menu.submenu("More", List.of(Menu.item("New window", () -> {})
            .shortcut(Key.CONTROL, "N"))));
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Menu.shortcuts(items));
    assertTrue(e.getMessage().contains("\"New query\"") && e.getMessage().contains("\"New window\"")
        && e.getMessage().startsWith("Ctrl+N "), e.getMessage());
    List<Menu.Item> menus = List.of(Menu.submenu("&File", List.of(
        Menu.item("Find", () -> {}).shortcut(Key.ALT, "F"))));
    e = assertThrows(IllegalArgumentException.class,
        () -> MenuBar.menuBar(Theme.DARK, new MenuBar.State(), menus));
    assertTrue(e.getMessage().contains("\"Find\"") && e.getMessage().contains("\"File\""),
        e.getMessage());
  }

  @Test
  void combinationsAreWrittenAsThePlatformWritesThem() {
    Key all = new Key("Delete", Key.CONTROL | Key.ALT | Key.SHIFT | Key.META, false);
    assertEquals("Win+Ctrl+Alt+Shift+Del", Menu.shortcutText(all, true));
    assertEquals("Super+Ctrl+Alt+Shift+Delete", Menu.shortcutText(all, false));
    assertEquals("Ctrl+Shift+N", Menu.shortcutText(new Key("N", Key.SHIFT | Key.CONTROL, false)));
    assertEquals("F5", Menu.shortcutText(new Key("F5")));
    assertEquals("Alt+Up", Menu.shortcutText(new Key("ArrowUp", Key.ALT, false)));
    assertEquals("PgDn", Menu.shortcutText(new Key("PageDown"), true));
    assertEquals("Ctrl+Num +", Menu.shortcutText(new Key("NumpadAdd", Key.CONTROL, false)), "");
  }

  @Test
  void mnemonicsComeFromTheAmpersand() {
    assertEquals("File", MenuBar.text("&File"));
    assertEquals(0, MenuBar.mnemonic("&File"));
    assertEquals("Save & close", MenuBar.text("Save && &close"));
    assertEquals(7, MenuBar.mnemonic("Save && &close"));
    assertEquals(-1, MenuBar.mnemonic("Tools"));
  }
}
