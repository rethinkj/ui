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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The options of {@link Button}: off, busy, the danger tone, and the split button's keys. */
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
  void aButtonThatIsOffIsAsOneWithoutAHandler() throws IOException {
    App<Clicks> off = app(s ->
        button(T, SECONDARY, "Save", s::press, Button.options().enabled(false)).key("b"));
    App<Clicks> none = app(s -> button(T, SECONDARY, "Save", null).key("b"));
    run(off, "key Tab\nframe\npress #b\nkey Enter");
    run(none, "key Tab\nframe\npress #b\nkey Enter");
    assertEquals(none.tree().dump(), off.tree().dump());
    assertEquals(0, off.state().presses);
    assertTrue(dumpLine(off, "b").contains(" disabled"), dumpLine(off, "b"));
    assertFalse(dumpLine(off, "b").contains("focused"), dumpLine(off, "b"));
  }

  @Test
  void theOldFormsAreTheFormWithOptions() throws IOException {
    App<Clicks> old = app(s -> button(T, PRIMARY, Symbol.PLAY, "Run", true, s::press));
    App<Clicks> with = app(s ->
        button(T, PRIMARY, "Run", s::press, Button.options().icon(Symbol.PLAY).busy(true)));
    run(old, "");
    run(with, "");
    assertEquals(old.tree().dump(), with.tree().dump());
  }

  @Test
  void aBusyIconButtonShowsASpinnerAndTakesNoPress() throws IOException {
    App<Clicks> app = app(s -> Button.icon(
            T,
            GHOST,
            Symbol.ARROW_CLOCKWISE,
            "Refresh",
            s::press,
            Button.options().busy(true))
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
  void anIconButtonTakesItsSymbolAsAParameterOnly() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Button.icon(T, GHOST, Symbol.ADD, "Add", () -> {}, Button.options().icon(Symbol.ADD)));
  }

  @Test
  void theDangerToneColoursEachVariant() throws IOException {
    Theme.Danger d = T.danger();
    Theme.Surfaces s = T.surface();
    App<Clicks> app = app(c -> row()
        .child(button(T, PRIMARY, "Drop", c::press, danger()).key("p"))
        .child(button(T, SECONDARY, "Discard", c::press, danger()).key("s"))
        .child(button(T, GHOST, "Delete", c::press, danger()).key("g"))
        .child(button(T, PRIMARY, "Off", c::press, danger().enabled(false)).key("o")));
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
    App<Clicks> app = app(c -> button(T, SECONDARY, "Save", c::press).key("b"));
    run(app, "key Tab\nframe\nkey Space down\nframe\nadvance 1");
    Theme.Surfaces s = T.surface();
    assertEquals(over(s.raised(), s.pressed()), find(app, "b").fill());
    assertEquals(1, app.state().presses);
    run(app, "key Space up\nframe\nadvance 1");
    assertEquals(s.raised(), find(app, "b").fill());
  }

  private static App<Clicks> split(Button.Options o) {
    return app(c -> Button.split(
        T,
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
    App<Clicks> app = split(Button.options().busy(true));
    run(app, "press #main\nkey Enter");
    assertEquals(0, app.state().presses);
    assertTrue(dumpLine(app, "main").contains(" busy"), dumpLine(app, "main"));
    run(app, "press #arrow");
    assertTrue(app.state().menu.isOpen());
  }

  @Test
  void aSplitButtonThatIsOffIsOffInBothHalves() throws IOException {
    App<Clicks> app = split(Button.options().enabled(false));
    run(app, "press #arrow\npress #main");
    assertFalse(app.state().menu.isOpen());
    assertEquals(0, app.state().presses);
    assertTrue(dumpLine(app, "arrow").contains(" disabled"), dumpLine(app, "arrow"));
    assertNotNull(dumpLine(app, "main"));
    assertTrue(dumpLine(app, "main").contains(" disabled"), dumpLine(app, "main"));
  }

  private static Button.Options danger() {
    return Button.options().tone(Button.Tone.DANGER);
  }
}
