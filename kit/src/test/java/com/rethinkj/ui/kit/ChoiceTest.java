package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Radio groups, segmented controls, number fields and sliders, by keys and the pointer. */
class ChoiceTest {

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run("size 400 560\nframe\n" + steps + "\nframe", app, Path.of("target/choice-test"));
  }

  @Test
  void theArrowsChooseAroundTheEndsAndAPressChoosesWhatItIsOn() throws IOException {
    Sheets s = new Sheets(Theme.DARK);
    App<Sheets> app = s.app(Sheets::choices);
    run(app, "focus role=radiogroup label=\"Line endings\"\nkey ArrowUp");
    assertEquals(2, s.endings, "around from the first to the last");
    run(app, "key ArrowDown\nframe\nkey End\nframe\nkey Home\nframe\nkey ArrowRight");
    assertEquals(1, s.endings);
    run(app, "press role=radio label=\"As in the file\"");
    assertEquals(2, s.endings);
    assertTrue(
        StructureTest.line(app.tree().dump(), "label=\"As in the file\"").endsWith(" checked"));

    run(app, "press role=radio label=\"System\"");
    assertEquals(2, s.theme, "the press chooses System");
    assertEquals("Line endings", app.focused().label(), "and leaves the focus where it was");
    run(app, "focus role=radio label=\"System\"\nframe\nkey ArrowRight");
    assertEquals(0, s.theme, "from System around to Light");
  }

  @Test
  void aNumberFieldStepsInsideItsRangeAndShowsATypedNumberOutsideItAsInvalid() throws IOException {
    Sheets s = new Sheets(Theme.DARK);
    App<Sheets> app = s.app(Sheets::choices);
    run(app, "focus role=spinbutton\nkey ArrowUp\nframe\nkey PageUp");
    assertEquals(15, s.tabSize, "5, then ten steps");
    run(app, "key PageUp");
    assertEquals(16, s.tabSize, "kept at the end of the range");
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "label=\"Increase\"").contains(" disabled"), dump);
    run(app, "press role=button label=\"Increase\"");
    assertEquals(16, s.tabSize);
    assertEquals(
        "spinbutton", app.focused().role(), "a press on the disabled button keeps the focus too");
    run(app, "key A control\ntype \"x\"");
    assertEquals(16, s.tabSize, "a letter is refused");
    run(app, "key A control\ntype \"40\"");
    assertEquals(40, s.tabSize);
    assertTrue(StructureTest.line(app.tree().dump(), "role=spinbutton").contains(" invalid"));
    run(app, "press role=button label=\"Decrease\"");
    assertEquals(16, s.tabSize, "the button steps back into the range");
    assertEquals("spinbutton", app.focused().role(), "and leaves the focus in the field");
  }

  @Test
  void aSliderFollowsTheKeysAndADragAndSaysItsValue() throws IOException {
    Sheets s = new Sheets(Theme.DARK);
    App<Sheets> app = s.app(Sheets::choices);
    run(app, "focus role=slider\nkey ArrowRight\nframe\nkey End");
    assertEquals(16, s.fontSize);
    run(app, "key PageDown\nframe\nkey Home");
    assertEquals(11, s.fontSize);
    assertTrue(StructureTest.line(app.tree().dump(), "role=slider").contains(" value=\"11\""));
    float[] r = StructureTest.rect(StructureTest.line(app.tree().dump(), "role=slider"));
    float y = r[1] + r[3] / 2;
    run(
        app,
        "down " + (r[0] + 8) + "," + y + "\nmove " + (r[0] + r[2] / 2) + "," + y + "\nmove "
            + (r[0] + r[2] - 8) + "," + y + "\nup " + (r[0] + r[2] - 8) + "," + y);
    assertEquals(16, s.fontSize, "dragged from one end to the other");
    run(app, "press " + (r[0] + 8) + "," + y);
    assertEquals(11, s.fontSize, "a press on the track puts the knob there");
    run(app, "focus role=slider\nkey Space\nframe\nkey Enter");
    assertEquals(11, s.fontSize, "Space and Enter press nothing");
  }

  @Test
  void aDisabledChoiceTakesNoFocusAndSaysSo() throws IOException {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        x -> col()
            .children(
                Radio.radioGroup(t, List.of("A", "B"), 0, null),
                Segmented.segmented(t, List.of("C", "D"), 0, null),
                NumberField.numberField(t, 1, 0, 2, 1, null),
                Slider.slider(t, 1, 0, 2, 1, null),
                NavList.navList(t, List.of(NavList.item("E")), 0, null)));
    run(app, "key Tab");
    assertEquals(null, app.focused());
    String dump = app.tree().dump();
    for (String role : List.of("radiogroup", "slider", "listbox")) {
      assertTrue(StructureTest.line(dump, "role=" + role).contains(" disabled"), role);
    }
    assertTrue(StructureTest.line(dump, "role=spinbutton").contains(" disabled"), dump);
  }

  @Test
  void aNumberFieldTakesEveryIntAndRefusesWhatIsBeyond() throws IOException {
    int[] value = {0};
    App<Object> app = new App<>(
        new Object(),
        x -> col()
            .child(NumberField.numberField(
                Theme.DARK, value[0], Integer.MIN_VALUE, Integer.MAX_VALUE, 1, v -> value[0] = v)));
    for (int typed : new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE}) {
      run(app, "focus role=spinbutton\nkey A control\ntype \"" + typed + "\"");
      assertEquals(typed, value[0]);
    }
    run(app, "key A control\ntype \"2147483648\"");
    assertEquals(Integer.MIN_VALUE, value[0], "beyond an int: refused");
    run(app, "key ArrowDown");
    assertEquals(Integer.MIN_VALUE, value[0], "a step stays at the end");
  }

  /** A drag compares with what it reported last, not with where it began. */
  @Test
  void aSliderDragReportsEachNewStopOnceAndComesBackToWhereItBegan() throws IOException {
    int[] value = {0};
    List<Integer> told = new ArrayList<>();
    App<Object> app = new App<>(
        new Object(),
        x -> col()
            .child(Slider.slider(Theme.DARK, value[0], 0, 100, 10, v -> {
                  value[0] = v;
                  told.add(v);
                })
                .w(200)));
    run(app, "down 8,12\nmove 100,12\nframe\nmove 101,12\nframe\nmove 8,12\nup 8,12");
    assertEquals(List.of(50, 0), told, "101 is in the stop of 100, and 8 is the start");
    assertEquals(0, value[0]);
  }

  /** As WAI-ARIA's radio group: with none checked, Space checks the first; then nothing more. */
  @Test
  void spaceChecksTheFirstChoiceOfAGroupWithoutOne() throws IOException {
    for (boolean segmented : new boolean[] {false, true}) {
      int[] selected = {-1};
      List<Integer> told = new ArrayList<>();
      App<Object> app = new App<>(new Object(), x -> {
        IntConsumer choose = i -> {
          selected[0] = i;
          told.add(i);
        };
        List<String> choices = List.of("A", "B");
        return col()
            .child(
                segmented
                    ? Segmented.segmented(Theme.DARK, choices, selected[0], choose)
                    : Radio.radioGroup(Theme.DARK, choices, selected[0], choose));
      });
      run(app, "key Tab\nkey Space\nframe\nkey Space");
      assertEquals(List.of(0), told, segmented ? "segmented" : "radio");
    }
  }
}
