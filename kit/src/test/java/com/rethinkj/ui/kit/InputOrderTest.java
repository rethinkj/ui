package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.TextField.textField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Input of one frame through menus, tooltips, dialogs, the tree and the grid: each input goes to
 * the tree the one before it left, with the overlays it opened or dismissed and the focus they
 * moved.
 */
class InputOrderTest {

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

  private static String keys(String key, int times) {
    return ("key " + key + "\n").repeat(times);
  }

  // Menus

  @Test
  void keysAfterThePressThatOpensAMenuGoToTheMenuAndEnterChoosesWhereTheyWent() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #file\nkey ArrowDown\nkey ArrowDown\nkey Enter\nframe");
    assertEquals("Open", bench.status, "the second item chosen in the frame of the press");
    assertFalse(bench.file.isOpen());
    assertNull(app.focused(), "the closed menu gave the focus back to where the press left it");
  }

  @Test
  void keysOfOneFrameWalkIntoTheSubmenuThatAnEarlierOneOpened() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(
        app,
        "frame\npress #file\nframe\n" + keys("ArrowDown", 3) // New query, Open…, Export
            + "key ArrowRight\nkey ArrowDown\nkey Enter\nframe");
    assertEquals(
        "Exported as JSON",
        bench.status,
        "ArrowDown went to the submenu that ArrowRight opened, which took the focus");
    assertFalse(bench.file.isOpen());
  }

  @Test
  void aBurstOfArrowDownsInAnOpenMenuEndsWhereOneKeyPerFrameEnds() throws IOException {
    String open = "frame\npress #file\nframe\n";
    Bench burst = new Bench(Theme.DARK);
    App<Bench> one = burst.app();
    run(one, open + keys("ArrowDown", 6) + "frame");
    Bench steps = new Bench(Theme.DARK);
    App<Bench> other = steps.app();
    run(other, open + keys("ArrowDown", 6).replace("\n", "\nframe\n"));
    assertEquals(0, burst.file.active(), "around past the separators: 0 1 3 5 6, then 0");
    assertEquals(other.tree().dump(), one.tree().dump());
  }

  @Test
  void aPressOutsideDismissesAndThePressAfterItInTheSameFrameFindsNoMenu() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #file\nframe\npress #status\npress #rename\nframe");
    assertFalse(bench.file.isOpen(), "the first press dismissed the menu and did nothing else");
    assertTrue(bench.renaming, "the second found the button with no menu open");
  }

  @Test
  void aSecondPressOnTheMenuButtonFindsTheMenuTheFirstOpened() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #file\npress #file\nframe");
    assertFalse(bench.file.isOpen(), "opened, then closed by a press outside it");
    run(app, "press #file\npress #file\npress #file\nframe");
    assertTrue(bench.file.isOpen());
  }

  @Test
  void aRightPressAndKeysInOneFrameChooseFromTheContextMenuItOpened() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    run(app, "frame\npress #table-3 right\nkey ArrowDown\nkey Enter\nframe");
    assertEquals(3, bench.selected);
    assertEquals("Open orders", bench.status);
    assertFalse(bench.tables.isOpen());
    assertEquals("tables", app.focused().key(), "the focus went back to the list");
  }

  // Tooltips

  @Test
  void aTooltipWhoseDelayEndsInAFrameWithInputShowsInThatFrame() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    // Enter opens the File menu and Escape closes it: two handlers, a build of the view between
    // and after them, while the pointer rests on the help button.
    run(app, "frame\nmove #help\nframe\nadvance 0.5\nfocus #file\nkey Enter\nkey Escape\nframe");
    assertFalse(bench.file.isOpen());
    assertTrue(
        app.tree().dump().contains("role=tooltip label=\"Keyboard shortcuts\""),
        app.tree().dump());

    run(app, "leave\nframe\nmove #help\nframe\nadvance 0.5\npress #tables\nframe");
    assertFalse(app.tree().dump().contains("role=tooltip"), "a press keeps it away");
  }

  // Dialogs

  @Test
  void tabsInTheFrameOfTheKeyThatOpensADialogStayInsideIt() throws IOException {
    Bench bench = new Bench(Theme.DARK);
    App<Bench> app = bench.app();
    // The field took the focus as the dialog opened; Cancel, Rename, around to the field, Cancel.
    run(app, "frame\nfocus #rename\nkey Enter\n" + keys("Tab", 4) + "frame");
    assertTrue(bench.renaming);
    assertEquals("cancel", app.focused().key());
  }

  /** A dialog that asks for a port, whose field keeps only digits. */
  static final class Port {
    boolean asking;
    String port = "";
    int edits;

    El view() {
      Theme t = Theme.DARK;
      El root = col()
          .wFull()
          .hFull()
          .child(button(t, SECONDARY, "Port…", () -> asking = true).key("open"));
      if (asking) {
        root.overlay(Dialog.dialog(
            t,
            "Port",
            textField(t, port, "Port", v -> {
                  edits++;
                  if (v.chars().allMatch(Character::isDigit)) {
                    port = v;
                  }
                })
                .key("port"),
            List.of(button(t, PRIMARY, "OK", () -> asking = false).key("ok")),
            () -> asking = false));
      }
      return root;
    }
  }

  @Test
  void aFieldInADialogOpenedInTheSameFrameTakesAnEditAfterOneTheAppRefused() throws IOException {
    Port s = new Port();
    App<Port> app = new App<>(s, Port::view);
    app.textRendering(TextRendering.LINUX);
    run(app, "frame\npress #open\ntype \"x\"\ntype \"5\"\ntype \"y\"\ntype \"4\"\nframe");
    assertEquals("54", s.port, "each edit builds on the field as the app left it");
    assertEquals(4, s.edits);
    assertEquals("port", app.focused().key());
    assertTrue(
        app.tree().dump().contains("\"54\" ") && app.tree().dump().contains(" focused caret 2\n"),
        app.tree().dump());
  }

  // Lists with scrollbars

  @Test
  void aBurstOfArrowDownsInTheGridEndsWhereOneKeyPerFrameEnds() throws IOException {
    assertBurstAsSteps(
        () -> new Orders(Theme.DARK, 1_000_000).app(),
        "size 560 240\nframe\nfocus #orders\nframe\n",
        30);
  }

  @Test
  void aBurstOfArrowDownsInTheTreeEndsWhereOneKeyPerFrameEnds() throws IOException {
    assertBurstAsSteps(
        () -> new Catalog(Theme.DARK)
            .opened("localhost", "localhost/shop", "localhost/shop/public")
            .app(),
        "size 280 300\nframe\nfocus #schema\nkey Home\nframe\n",
        30);
  }

  /**
   * Runs {@code keys} ArrowDowns in one frame and one per frame in two apps and compares their
   * trees: rows, selection, scroll offsets and the scrollbars' thumbs.
   */
  private void assertBurstAsSteps(Supplier<App<?>> apps, String start, int keys)
      throws IOException {
    App<?> burst = apps.get();
    Script.run(start + keys("ArrowDown", keys) + "frame", burst, out);
    App<?> steps = apps.get();
    Script.run(start + keys("ArrowDown", keys).replace("\n", "\nframe\n"), steps, out);
    String dump = burst.tree().dump();
    assertTrue(dump.contains(" thumb-y "), dump);
    assertTrue(dump.matches("(?s).* rows \\d+-\\d+/\\d+ scroll 0,[1-9].*"), "scrolled: " + dump);
    assertEquals(steps.tree().dump(), dump);
  }
}
