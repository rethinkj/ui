package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Label.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
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
 * Radio groups of swatches ({@link Radio#swatches}) and the hue picker ({@link HuePicker}) by
 * keys and the pointer, and their sheet, stored as {@code swatches-<theme>-<scale>x}: icons with
 * none chosen, a hue chosen, the keyboard's focus with none chosen, disabled, and wrapped in a
 * narrow box.
 */
class SwatchesTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** The state of the sheet, which the handlers change. */
  static final class State {
    final Theme t;
    int shape = -1;
    Theme.Hue chosen = Theme.Hue.RED;
    Theme.Hue focus;
    Theme.Hue narrow;

    State(Theme t) {
      this.t = t;
    }

    App<State> app() {
      App<State> app = new App<>(this, State::view);
      app.focusRing(t.accent().edge());
      app.textRendering(TextRendering.LINUX);
      return app;
    }

    El view() {
      List<Radio.Option> shapes = List.of(
          Radio.option("Table", t -> Icon.icon(t, Symbol.TABLE)),
          Radio.option("Database", t -> Icon.icon(t, Symbol.DATABASE)),
          Radio.option("Server", t -> Icon.icon(t, Symbol.SERVER)),
          Radio.option("Folder", t -> Icon.icon(t, Symbol.FOLDER)));
      return col()
          .wFull()
          .hFull()
          .padding(t.space().l())
          .fill(t.surface().window())
          .child(Card.card(
              t,
              label(t, "Icons, none chosen"),
              Radio.swatches(t, shapes, shape, i -> shape = i).key("shapes"),
              label(t, "A hue chosen, none first"),
              HuePicker.huePicker(t, chosen, "None", h -> chosen = h).key("chosen"),
              label(t, "The keyboard's focus, none chosen"),
              HuePicker.huePicker(t, focus, h -> focus = h).key("focus"),
              label(t, "Disabled"),
              HuePicker.huePicker(t, Theme.Hue.TEAL, "None", null).key("disabled"),
              label(t, "In a narrow box"),
              col()
                  .w(160)
                  .child(HuePicker.huePicker(t, narrow, "None", h -> narrow = h).key("narrow"))));
    }
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run("size 480 520\nframe\n" + steps + "\nframe", app, Path.of("target/swatches-test"));
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 2", "light, 2"})
  void looksAsStored(String theme, int scale) throws IOException {
    App<State> app = new State(theme.equals("dark") ? Theme.DARK : Theme.LIGHT).app();
    String image = "swatches-" + theme + "-" + scale + "x";
    Script.run(
        "size 480 520 scale " + scale + "\nframe\nfocus #focus\nframe\nadvance 1\nframe\nsnap "
            + image,
        app,
        out);
    String tree = Files.readString(out.resolve(image + ".tree.txt"));
    Golden.assertMatches(image, Png.decode(Files.readAllBytes(out.resolve(image + ".png"))), tree);
    // The narrow box breaks the eleven swatches into rows.
    float[] none = StructureTest.rect(line(tree, "#narrow", "label=\"None\""));
    float[] grey = StructureTest.rect(line(tree, "#narrow", "label=\"Grey\""));
    assertTrue(grey[1] > none[1], "wrapped: " + tree);
  }

  @Test
  void eachSwatchIsARadioNamedByItsHue() throws IOException {
    State s = new State(Theme.DARK);
    App<State> app = s.app();
    run(app, "");
    String dump = app.tree().dump();
    assertTrue(line(dump, "#chosen").contains("role=radiogroup"), dump);
    assertTrue(line(dump, "#chosen", "label=\"Red\"").endsWith(" checked"), dump);
    assertTrue(line(dump, "#chosen", "label=\"None\"").endsWith(" checked=false"), dump);
    assertTrue(line(dump, "#chosen", "label=\"Violet\"").contains("role=radio "), dump);
    assertTrue(line(dump, "#disabled").contains(" disabled"), dump);
    // 16 for a checkbox's box, then gap and ring twice on each side: 32 square.
    assertEquals(32, StructureTest.rect(line(dump, "#chosen", "label=\"Red\""))[2]);
  }

  @Test
  void theArrowsChooseAroundTheEndsAndNoneIsAChoiceLikeAnyOther() throws IOException {
    State s = new State(Theme.DARK);
    App<State> app = s.app();
    run(app, "focus #chosen\nkey ArrowRight");
    assertEquals(Theme.Hue.ORANGE, s.chosen);
    run(app, "key Home");
    assertNull(s.chosen, "Home chooses None, the first");
    run(app, "key ArrowLeft");
    assertEquals(Theme.Hue.GREY, s.chosen, "around from the first to the last");
    run(app, "key ArrowDown");
    assertNull(s.chosen, "and on from the last to the first");
    run(app, "key End");
    assertEquals(Theme.Hue.GREY, s.chosen);
    assertEquals("chosen", app.focused().key(), "the group keeps the focus");
  }

  @Test
  void spaceChoosesTheFirstWhileNoneIsChosenAndAPressChoosesWhatItIsOn() throws IOException {
    State s = new State(Theme.DARK);
    App<State> app = s.app();
    run(app, "focus #focus\nkey Space");
    assertEquals(Theme.Hue.BLUE, s.focus);
    run(app, "press " + at(app, "#focus", "label=\"Purple\""));
    assertEquals(Theme.Hue.PURPLE, s.focus);
    assertEquals("focus", app.focused().key(), "a press leaves the focus where it was");
    run(app, "press label=\"Server\"");
    assertEquals(2, s.shape);
    assertEquals("focus", app.focused().key(), "and does not take it");
  }

  @Test
  void aDisabledPickerTakesNoPressAndNoFocus() throws IOException {
    State s = new State(Theme.DARK);
    App<State> app = s.app();
    run(app, "key Tab\nframe\nkey Tab\nframe\nkey Tab\nframe\nkey Tab");
    // shapes, chosen, focus, then narrow: the disabled picker is passed by
    assertEquals("narrow", app.focused().key());
    String before = app.tree().dump();
    run(app, "press " + at(app, "#disabled", "label=\"Teal\""));
    assertTrue(line(app.tree().dump(), "#disabled", "label=\"Teal\"").endsWith(" checked"), before);
  }

  @Test
  void aSwatchShowsItsNameInATooltip() throws IOException {
    State s = new State(Theme.DARK);
    App<State> app = s.app();
    run(app, "");
    run(app, "move " + at(app, "#chosen", "label=\"Amber\"") + "\nframe\nadvance 1\nframe");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=tooltip label=\"Amber\""), dump);
  }

  /** The middle of the element of {@code part} in the group {@code within}, as {@code x,y}. */
  private static String at(App<?> app, String within, String part) {
    float[] r = StructureTest.rect(line(app.tree().dump(), within, part));
    return (r[0] + r[2] / 2) + "," + (r[1] + r[3] / 2);
  }

  /** The first line after the one with {@code within} that has {@code part}, or that line. */
  static String line(String tree, String within, String... part) {
    List<String> lines = tree.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(within)) {
        if (part.length == 0) {
          return lines.get(i).strip();
        }
        for (int j = i + 1; j < lines.size(); j++) {
          if (lines.get(j).contains(part[0])) {
            return lines.get(j).strip();
          }
        }
      }
    }
    throw new AssertionError(within + " " + String.join(" ", part) + " in\n" + tree);
  }
}
