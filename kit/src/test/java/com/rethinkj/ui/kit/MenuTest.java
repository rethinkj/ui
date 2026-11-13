package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
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

  @TempDir Path out;

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
    assertTrue(open.contains("\noverlay bottom start gap 2 on #file role=button label=\"File\""),
        open);
    assertTrue(open.contains("  el #menu role=menu "), open);
    assertTrue(open.contains(" focused\n"), "the menu took the focus");
    String submenu = Bench.tree(out, "menu", theme, scale, "submenu");
    assertTrue(submenu.contains("\noverlay right start gap 5 on #item-3 role=menuitem"
        + " label=\"Export\""), submenu);
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
    assertTrue(box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= 360 && box[1] + box[3] <= 240,
        "inside the window: " + menu);
  }

  /** The rectangle {@code x,y wxh} at the end of a dump's line, before its flags. */
  private static float[] rect(String line) {
    for (String word : line.split(" ")) {
      if (word.matches("-?[\\d.]+,-?[\\d.]+")) {
        int i = line.indexOf(" " + word + " ") + word.length() + 2;
        String[] size = line.substring(i).split(" ")[0].split("x");
        String[] at = word.split(",");
        return new float[] {Float.parseFloat(at[0]), Float.parseFloat(at[1]),
            Float.parseFloat(size[0]), Float.parseFloat(size[1])};
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
    assertTrue(open.contains("\noverlay bottom stretch gap 2 on #encoding role=combobox"
        + " label=\"UTF-8\""), open);
    assertEquals(1, app.state().encoding, "Arrow Down and Enter chose UTF-16");
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
    assertEquals("file", app.focused().key());
    run(app, "press #file\nframe\nkey End\nkey Enter\nframe\n");
    assertTrue(bench.file.isOpen(), "Close is disabled: Enter does nothing");
    run(app, "key Tab\nframe\n");
    assertFalse(bench.file.isOpen(), "Tab closes it");
  }

  @Test
  void thePointerHighlightsAndASubmenuClosesAfterRestingOnAnotherItem() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #file\nframe\nmove label=\"Export\"\nframe\n");
    assertEquals(3, bench.file.active());
    assertEquals(-1, bench.file.submenu(), "not yet");
    assertEquals(app.time() + 0.3, app.nextChange(), 1e-9);
    run(app, "advance 0.3\nframe\n");
    assertEquals(3, bench.file.submenu());
    run(app, "move label=\"JSON\"\nframe\n");
    assertEquals(3, bench.file.submenu(), "moving into the submenu keeps it");
    run(app, "move label=\"New query\"\nframe\nadvance 0.1\nframe\n");
    assertEquals(3, bench.file.submenu(), "passing over another item keeps it");
    run(app, "advance 0.2\nframe\n");
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
}
