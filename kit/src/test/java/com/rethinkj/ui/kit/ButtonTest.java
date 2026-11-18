package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Theme.over;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static com.rethinkj.ui.kit.Workbench.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The options of {@link Button}: off, busy, the danger tone, the shortcut, and the keys of the
 * split and menu buttons.
 */
class ButtonTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Clicks {
    int presses;
    int chosen;
    final Menu.State menu = new Menu.State();

    void press() {
      presses++;
    }
  }

  private static App<Clicks> app(Function<Clicks, El> button) {
    App<Clicks> app = new App<>(new Clicks(), s -> row().padding(16).child(button.apply(s)));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 320 200\nframe\n" + steps + "\nframe", app, out);
  }

  @Test
  void aTextTooLongForItsButtonEndsInAnEllipsisAndShowsWholeInATooltip() throws IOException {
    String text = "Export public.customer_addresses as CSV";
    App<Clicks> app = app(s -> row()
        .children(
            button(SECONDARY, text, s::press).key("narrow").w(120),
            button(SECONDARY, "Save", s::press).key("fits").w(120)));
    run(app, "move #narrow\nframe\nadvance 1\nframe");
    String dump = app.tree().dump();
    float[] button = StructureTest.rect(dumpLine(app, "narrow"));
    float[] label = StructureTest.rect(StructureTest.line(dump, "text \"" + text + "\""));
    assertEquals(120, button[2]);
    assertTrue(
        label[0] >= button[0] && label[0] + label[2] <= button[0] + button[2],
        "the text stays inside: " + dump);
    assertTrue(find(app, "narrow").children().getLast().text().shortened(), dump);
    assertTrue(dump.contains("role=tooltip label=\"" + text + "\""), dump);

    run(app, "move #fits\nframe\nadvance 1\nframe");
    assertFalse(app.tree().dump().contains("role=tooltip"), "a text that fits needs none");
  }

  @Test
  void aButtonThatIsOffIsAsOneWithoutAHandler() throws IOException {
    App<Clicks> off =
        app(s -> button(SECONDARY, "Save", s::press, Button.options().state(Button.State.DISABLED))
            .key("b"));
    App<Clicks> none = app(s -> button(SECONDARY, "Save", null).key("b"));
    run(off, "key Tab\nframe\npress #b\nkey Enter");
    run(none, "key Tab\nframe\npress #b\nkey Enter");
    assertEquals(none.tree().dump(), off.tree().dump());
    assertEquals(0, off.state().presses);
    assertTrue(dumpLine(off, "b").contains(" disabled"), dumpLine(off, "b"));
    assertFalse(dumpLine(off, "b").contains("focused"), dumpLine(off, "b"));
  }

  @Test
  void aPressOnAButtonThatIsOffLeavesTheFocusWhereItIs() throws IOException {
    App<Clicks> app = new App<>(
        new Clicks(),
        s -> row()
            .padding(16)
            .focusable()
            .key("pane")
            .child(El.el().focusable().key("editor").w(40).h(24))
            .child(button(SECONDARY, "Save", null).key("b")));
    app.textRendering(TextRendering.LINUX);
    run(app, "focus #editor\npress #b");
    assertEquals("editor", app.focused().key());
  }

  @Test
  void aBusyTextShowsAfterTheSpinnerAndTheButtonKeepsItsWidth() throws IOException {
    boolean[] busy = {false};
    App<Clicks> app = app(s -> row()
        .child(button(
                PRIMARY,
                "Save",
                busy[0] ? null : s::press,
                Button.options()
                    .state(busy[0] ? Button.State.BUSY : Button.State.READY)
                    .busyText("Saving…"))
            .key("b"))
        .child(Button.menu(
                SECONDARY,
                "Export",
                s.menu,
                List.of(Menu.item("CSV", () -> {})),
                Button.options()
                    .state(busy[0] ? Button.State.BUSY : Button.State.READY)
                    .busyText("Exporting…"))
            .key("menu")));
    String size = "size 320 200 scale 1.25\nframe\n"; // where a text may round a pixel wider
    Script.run(size, app, out);
    float ready = StructureTest.rect(dumpLine(app, "b"))[2];
    float menu = StructureTest.rect(dumpLine(app, "menu"))[2];
    busy[0] = true;
    app.invalidate();
    // Past the spring of a change of width, which would else hide it.
    Script.run(size + "key Tab\nframe\nkey Enter\nframe\nadvance 1\nframe", app, out);
    String line = dumpLine(app, "b");
    assertTrue(line.contains("label=\"Save\"") && line.contains(" busy"), line);
    assertTrue(line.contains("focused"), "busy also without a handler: " + line);
    assertTrue(app.tree().dump().contains("text \"Saving…\""), app.tree().dump());
    assertEquals(ready, StructureTest.rect(line)[2], "the wider text's width in both states");
    assertEquals(menu, StructureTest.rect(dumpLine(app, "menu"))[2], "also with a chevron");
    assertEquals(0, app.state().presses);
  }

  @Test
  void aShortcutShowsAfterTheLabelInTheTooltip() throws IOException {
    App<Clicks> app = app(s -> row()
        .child(Button.icon(
                GHOST,
                Symbol.HISTORY,
                "History",
                s::press,
                Button.options().shortcut(Key.CONTROL, "H"))
            .key("icon"))
        .child(button(
                SECONDARY,
                "Commit",
                s::press,
                Button.options().shortcut(Key.CONTROL | Key.SHIFT, "C"))
            .key("text")));
    run(app, "move #icon\nframe\nadvance 1\nframe");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=tooltip label=\"History\""), dump);
    assertTrue(dump.contains("text \"Ctrl+H\""), dump);
    run(app, "move #text\nframe\nadvance 1\nframe");
    dump = app.tree().dump();
    assertTrue(dump.contains("text \"Ctrl+Shift+C\""), "also where the text fits: " + dump);
  }

  @Test
  void aBusyIconButtonShowsASpinnerAndTakesNoPress() throws IOException {
    App<Clicks> app = app(s -> Button.icon(
            GHOST,
            Symbol.ARROW_CLOCKWISE,
            "Refresh",
            s::press,
            Button.options().state(Button.State.BUSY))
        .key("b"));
    run(app, "key Tab\nframe\nkey Enter\nkey Space\npress #b");
    String line = dumpLine(app, "b");
    assertTrue(line.contains("label=\"Refresh\"") && line.contains(" busy"), line);
    assertTrue(line.contains("focused"), line);
    assertEquals(0, app.state().presses);
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=progressbar"), dump);
    assertFalse(dump.contains("arrow_clockwise"), "the spinner in the icon's place: " + dump);
  }

  @Test
  void theDangerToneColorsEachVariant() throws IOException {
    Theme.Danger d = T.danger();
    Theme.Surfaces s = T.surface();
    App<Clicks> app = app(c -> row()
        .child(button(PRIMARY, "Drop", c::press, danger()).key("p"))
        .child(button(SECONDARY, "Discard", c::press, danger()).key("s"))
        .child(button(GHOST, "Delete", c::press, danger()).key("g"))
        .child(button(PRIMARY, "Off", c::press, danger().state(Button.State.DISABLED))
            .key("o")));
    run(app, "");
    El primary = find(app, "p");
    assertEquals(d.color(), primary.fill());
    assertEquals(d.onColor(), primary.children().getFirst().text().color());
    assertEquals(d.ring(), (int) primary.focusRing());
    El secondary = find(app, "s");
    assertEquals(s.raised(), secondary.fill());
    assertEquals(d.text(), secondary.children().getFirst().text().color());
    assertEquals(d.text(), find(app, "g").children().getFirst().text().color());
    assertEquals(
        T.text().disabled(), find(app, "o").children().getFirst().text().color());
    run(app, "move #p\nframe\nadvance 1");
    assertEquals(d.hover(), find(app, "p").fill());
    run(app, "move #s\nframe\nadvance 1");
    assertEquals(over(s.raised(), s.hover()), find(app, "s").fill());
  }

  @Test
  void enterAndSpaceShowTheButtonPressedWhileHeld() throws IOException {
    App<Clicks> app = app(c -> button(SECONDARY, "Save", c::press).key("b"));
    run(app, "key Tab\nframe\nkey Space down\nframe\nadvance 1");
    Theme.Surfaces s = T.surface();
    assertEquals(over(s.raised(), s.pressed()), find(app, "b").fill());
    assertEquals(1, app.state().presses);
    run(app, "key Space up\nframe\nadvance 1");
    assertEquals(s.raised(), find(app, "b").fill());
  }

  private static App<Clicks> split(Button.Options o) {
    return app(c -> Button.split(
        SECONDARY,
        "Run",
        c::press,
        c.menu,
        List.of(
            Menu.item("Run selection", () -> c.chosen = 1),
            Menu.item("Explain", () -> c.chosen = 2),
            Menu.separator(),
            Menu.item("Profile", null)),
        o));
  }

  @Test
  void theKeysOnTheChevronOpenItsMenuAtTheFirstOrLastItem() throws IOException {
    App<Clicks> app = split(Button.options());
    run(app, "focus #arrow\nkey ArrowDown");
    assertTrue(app.state().menu.isOpen());
    assertEquals(0, app.state().menu.active());
    run(app, "advance 1\nframe\nkey Escape\nframe\nfocus #arrow\nkey ArrowUp");
    assertTrue(app.state().menu.isOpen());
    assertEquals(1, app.state().menu.active(), "the last enabled item, past a disabled one");
    run(app, "advance 1\nframe\nkey Enter");
    assertEquals(2, app.state().chosen);
    run(app, "advance 1\nframe\nfocus #arrow\nkey Enter");
    assertEquals(0, app.state().menu.active(), "Enter: the first");
    run(app, "advance 1\nframe\nkey Escape\nframe\nfocus #arrow\nkey Space");
    assertEquals(0, app.state().menu.active(), "Space: the first");
    run(app, "advance 1\nframe\nkey Escape\nframe\npress #arrow");
    assertTrue(app.state().menu.isOpen());
    assertEquals(-1, app.state().menu.active(), "the pointer: none");
  }

  @Test
  void aBusySplitButtonIsBusyInItsMainHalfAndStillOpensItsMenu() throws IOException {
    App<Clicks> app = split(Button.options().state(Button.State.BUSY));
    run(app, "press #main\nkey Enter");
    assertEquals(0, app.state().presses);
    assertTrue(dumpLine(app, "main").contains(" busy"), dumpLine(app, "main"));
    run(app, "press #arrow");
    assertTrue(app.state().menu.isOpen());
  }

  @Test
  void aSplitButtonTurnedOffWhileItsMenuIsOpenClosesItAndChoosesNothing() throws IOException {
    boolean[] off = {false};
    App<Clicks> app = app(c -> Button.split(
        SECONDARY,
        "Run",
        c::press,
        c.menu,
        List.of(Menu.item("Explain", () -> c.chosen = 2)),
        Button.options().state(off[0] ? Button.State.DISABLED : Button.State.READY)));
    run(app, "press #arrow\nframe\nadvance 1");
    assertTrue(app.state().menu.isOpen());
    off[0] = true;
    app.invalidate();
    run(app, "key ArrowDown\nkey Enter");
    assertEquals(0, app.state().chosen, "the menu is gone with its items");
    assertFalse(app.state().menu.isOpen());
    run(app, "advance 1"); // past the fade
    assertFalse(app.tree().dump().contains("role=menu"), app.tree().dump());
  }

  @Test
  void aMenuButtonOpensItsMenuAndIsOffWithoutAnEnabledItem() throws IOException {
    App<Clicks> app = app(c -> row()
        .child(Button.menu(
                SECONDARY,
                "Export",
                c.menu,
                List.of(
                    Menu.item("CSV", () -> c.chosen = 1), Menu.item("JSON", () -> c.chosen = 2)))
            .key("export"))
        .child(Button.menu(SECONDARY, "Import", new Menu.State(), List.of(Menu.item("CSV", null)))
            .key("import")));
    run(app, "focus #export\nkey ArrowDown\nframe\nadvance 1");
    assertTrue(app.state().menu.isOpen());
    assertEquals(0, app.state().menu.active(), "Arrow Down: the first item");
    assertTrue(dumpLine(app, "export").contains(" expanded"), dumpLine(app, "export"));
    run(app, "key Enter");
    assertEquals(1, app.state().chosen);
    assertTrue(dumpLine(app, "import").contains(" disabled"), dumpLine(app, "import"));
  }

  @Test
  void theMoreMenuOfAToolbarShowsItsButtonsOptions() throws IOException {
    Toolbar.State tools = new Toolbar.State();
    App<Clicks> app = app(c -> Toolbar.toolbar(
            tools,
            "Query",
            List.of(
                Toolbar.button(Symbol.ADD, "Add", c::press),
                Toolbar.text(Symbol.PLAY, "Run", c::press)
                    .options(Button.options().state(Button.State.BUSY)),
                Toolbar.split(
                        Button.Variant.GHOST,
                        null,
                        "Explain",
                        c::press,
                        c.menu,
                        List.of(Menu.item("Plan", () -> c.chosen = 1)))
                    .options(Button.options().state(Button.State.DISABLED))))
        .w(72));
    run(
        app,
        "frame\nadvance 1\nframe\npress role=button label=\"More\"\nframe\nadvance 0.25\nframe");
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "role=menuitem label=\"Run\"").contains(" disabled"), dump);
    assertTrue(
        StructureTest.line(dump, "role=menuitem label=\"Explain\"").contains(" disabled"),
        "off, a split button's entry opens no submenu: " + dump);
  }

  private static Button.Options danger() {
    return Button.options().tone(Button.Tone.DANGER);
  }
}
