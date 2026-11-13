package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rows in a group, pressable cards and forms, driven by scripts against {@link Sheets}. */
class GroupTest {

  @TempDir Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run("size 520 640\nframe\n" + steps + "\nframe", app, Path.of("target/group-test"));
  }

  @Test
  void aRowIsOneStopForTabAndItsControlNoSecond() throws IOException {
    Sheets s = new Sheets(Theme.DARK);
    App<Sheets> app = s.app(Sheets::rows);
    run(app, "");
    List<String> order = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      run(app, "key Tab");
      order.add(app.focused().role() + " " + app.focused().label());
    }
    assertEquals(List.of(
        "switch Word wrap", "switch Line numbers", "combobox JetBrains Mono",
        "spinbutton Tab size", "button Keymap", "button Advanced", "switch Insert spaces",
        "radiogroup Line endings", "switch Word wrap"), order,
        "the disabled row is left out, and so are the number field's buttons");
  }

  @Test
  void aPressAnywhereOnASwitchRowAndItsKeysFlipIt() throws IOException {
    Sheets s = new Sheets(Theme.DARK);
    App<Sheets> app = s.app(Sheets::rows);
    run(app, "press text=\"Wrap long lines at the edge of the editor instead of scrolling"
        + " sideways\"");
    assertFalse(s.wrap);
    run(app, "focus role=switch label=\"Word wrap\"\nkey Space\nframe\nkey Enter");
    assertFalse(s.wrap, "twice more");
    assertTrue(app.tree().dump().contains("role=switch label=\"Word wrap\" "));
    run(app, "press role=switch label=\"Telemetry\"\nframe\nkey Tab");
    assertEquals("Word wrap", app.focused().label(), "the disabled row took no focus");
  }

  @Test
  void anExpanderSaysWhetherItIsOpenAndShowsItsRowsOnlyThen() throws IOException {
    Sheets s = new Sheets(Theme.DARK);
    App<Sheets> app = s.app(Sheets::rows);
    run(app, "");
    assertTrue(app.tree().dump().contains("label=\"Advanced\" "));
    assertTrue(StructureTest.line(app.tree().dump(), "label=\"Advanced\"")
        .contains(" expanded"));
    run(app, "focus role=button label=\"Advanced\"\nkey Enter");
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "label=\"Advanced\"").contains(" expanded=false"), dump);
    assertFalse(dump.contains("Insert spaces"), dump);
  }

  @Test
  void rowsNameTheirControlsButASelectKeepsItsChoice() throws IOException {
    App<Sheets> app = new Sheets(Theme.DARK).app(Sheets::rows);
    run(app, "");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=spinbutton label=\"Tab size\" \"4\""), dump);
    assertTrue(dump.contains("role=combobox label=\"JetBrains Mono\""), dump);
    assertTrue(StructureTest.line(dump, "label=\"Keymap\"").contains(" value=\"Default\""));
  }

  @Test
  void aPressableCardSelectsAndItsControlsKeepTheirPresses() throws IOException {
    Sheets s = new Sheets(Theme.DARK);
    App<Sheets> app = s.app(Sheets::cards);
    run(app, "press role=button label=\"staging\"");
    assertEquals(1, s.selectedCard);
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "label=\"staging\"").contains(" selected"), dump);
    assertTrue(StructureTest.line(dump, "label=\"prod-eu\"").contains(" disabled"), dump);
    run(app, "press role=button label=\"prod-eu\"");
    assertEquals(1, s.selectedCard, "a disabled card takes no press");
    run(app, "press role=button label=\"Clear\"");
    assertEquals(1, s.presses, "the header's button");
  }

  @Test
  void aFormNamesItsFieldsAndMarksAnErrorAsInvalid() throws IOException {
    Sheets s = new Sheets(Theme.DARK);
    App<Sheets> app = s.app(Sheets::form);
    run(app, "");
    String dump = app.tree().dump();
    String port = StructureTest.line(dump, "#beside-port");
    assertTrue(port.contains(" invalid"), port);
    assertTrue(dump.contains("text \"Digits only\""), dump);
    assertFalse(dump.contains("From 1 to 65535"), "the error replaces the help");
    assertTrue(dump.contains("text \"*\""), dump);
    // The fields beside their labels start in one column, after the widest label.
    float host = StructureTest.rect(StructureTest.line(dump, "label=\"Host\""))[0];
    float database = StructureTest.rect(StructureTest.line(dump, "label=\"Database\""))[0];
    float label = StructureTest.rect(StructureTest.line(dump, "text \"Database\""))[2];
    assertEquals(host, database);
    assertTrue(host >= 16 + label, host + " after " + label);

    run(app, "press #beside-port\nkey End\nkey Backspace");
    assertEquals("54", s.port);
    String fixed = StructureTest.line(app.tree().dump(), "#beside-port");
    assertFalse(fixed.contains(" invalid"), fixed);
  }

  @Test
  void aCardsHeaderIsAHeadingOfTheThirdLevelBelowASectionsAndAPagesTitle() throws IOException {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(new Object(), x -> col().wFull().hFull().child(
        Section.page(t, "Settings",
            Section.section(t, "Editor", null, Card.card(t, Card.header(t, "Font", null))))
            .grow(1)));
    run(app, "");
    String dump = app.tree().dump();
    assertTrue(dump.contains("text role=heading \"Settings\" ") && dump.contains(" level=1"), dump);
    assertTrue(StructureTest.line(dump, "\"Editor\"").endsWith("level=2"), dump);
    assertTrue(StructureTest.line(dump, "\"Font\"").endsWith("level=3"), dump);
  }
}
