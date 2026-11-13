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
    run(app, "key A control\ntype \"x\"");
    assertEquals(16, s.tabSize, "a letter is refused");
    run(app, "key A control\ntype \"40\"");
    assertEquals(40, s.tabSize);
    assertTrue(StructureTest.line(app.tree().dump(), "role=spinbutton").contains(" invalid"));
    run(app, "press role=button label=\"Decrease\"");
    assertEquals(16, s.tabSize, "the button steps back into the range");
    assertEquals("spinbutton", app.focused().role(), "and leaves the focus in the field");
  }

  /** The label a number field is given is its spinbutton's, which a script selects it by. */
  @Test
  void aNumberFieldsLabelIsItsSpinbuttons() throws IOException {
    int[] size = {5};
    App<Object> app = new App<>(
        new Object(),
        x -> col()
            .child(NumberField.numberField(
                Theme.DARK, "Fetch size", size[0], 1, 100, 1, v -> size[0] = v)));
    run(app, "focus role=spinbutton label=\"Fetch size\"\nkey ArrowUp");
    assertEquals(6, size[0]);
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

  @Test
  void aLargeStepIsKeptInsideTheRangeInsteadOfOverflowing() throws IOException {
    int[] number = {50};
    App<Object> field = new App<>(
        new Object(),
        x -> col()
            .child(NumberField.numberField(
                Theme.DARK, number[0], 0, 100, 300_000_000, v -> number[0] = v)));
    run(field, "focus role=spinbutton\nkey PageUp");
    assertEquals(100, number[0], "ten steps are 3 000 000 000, past the end");

    int[] at = {2_000_000_000};
    App<Object> slider = new App<>(
        new Object(),
        x -> col().child(Slider.slider(Theme.DARK, at[0], 0, 2_000_000_000, 1_000_000_000, v -> {
          at[0] = v;
        })));
    run(slider, "focus role=slider\nkey ArrowRight");
    assertEquals(2_000_000_000, at[0], "at the end, a step goes nowhere");

    int[] wide = {0};
    App<Object> whole = new App<>(
        new Object(),
        x -> col()
            .child(Slider.slider(
                Theme.DARK, wide[0], Integer.MIN_VALUE, Integer.MAX_VALUE, 1, v -> wide[0] = v)));
    run(whole, "focus role=slider\nkey PageUp");
    assertEquals(429_496_729, wide[0], "a tenth of the whole range of int");
    run(whole, "key End");
    assertEquals(Integer.MAX_VALUE, wide[0]);
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

  @Test
  void enterAndSpaceLeaveASliderBetweenTwoStopsWhereItIs() throws IOException {
    int[] value = {5};
    List<Integer> told = new ArrayList<>();
    App<Object> app = new App<>(
        new Object(), x -> col().child(Slider.slider(Theme.DARK, value[0], 0, 10, 3, told::add)));
    run(app, "focus role=slider\nkey Enter\nframe\nkey Space");
    assertEquals(List.of(), told, "5 is no stop of 3, and stays");
    run(app, "key End");
    assertEquals(List.of(9), told, "the last stop before 10");
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

  @Test
  void aGroupOfNoneOrOneChoiceTellsNoChoiceTwice() throws IOException {
    for (boolean segmented : new boolean[] {false, true}) {
      for (List<String> values : List.<List<String>>of(List.of(), List.of("A"))) {
        int[] calls = {0};
        App<Object> app = new App<>(
            new Object(),
            s -> col()
                .child(
                    segmented
                        ? Segmented.segmented(Theme.DARK, values, 0, i -> calls[0]++)
                        : Radio.radioGroup(Theme.DARK, values, 0, i -> calls[0]++)));
        run(
            app,
            "key Tab\nkey ArrowRight\nkey ArrowDown\nkey ArrowLeft\nkey ArrowUp"
                + "\nkey Home\nkey End\nkey Enter\nkey Space\nkey Escape"
                + "\nkey ArrowDown control\nkey ArrowRight shift");
        assertEquals(0, calls[0]);
        if (values.isEmpty()) {
          assertEquals(null, app.focused(), "no choice, no stop for Tab");
        }
      }
    }
  }

  @Test
  void aNumberFieldWithEqualEndsNeverSteps() throws IOException {
    int[] calls = {0};
    App<Object> app = new App<>(
        new Object(),
        s -> col().child(NumberField.numberField(Theme.DARK, 5, 5, 5, 100, v -> calls[0]++)));
    run(app, "key Tab\nkey ArrowUp\nkey ArrowDown\nkey PageUp\nkey PageDown");
    assertEquals(0, calls[0]);
    String dump = app.tree().dump();
    assertTrue(StructureTest.line(dump, "label=\"Increase\"").contains(" disabled"), dump);
    assertTrue(StructureTest.line(dump, "label=\"Decrease\"").contains(" disabled"), dump);
  }
}
