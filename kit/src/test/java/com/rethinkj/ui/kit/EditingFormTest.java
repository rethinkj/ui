package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The components of the kit over the props of one {@link Editing}, driven by scripts. */
class EditingFormTest {

  enum Level {
    LOW,
    HIGH
  }

  record Job(
      String name,
      Integer retries,
      boolean active,
      boolean alert,
      Level level,
      Level mode,
      Level unit,
      Integer workers,
      Integer load,
      String host,
      Level region) {
    static final Part<Job, String> NAME = Part.of(Job::name, (j, v) -> j.with(0, v));
    static final Part<Job, Integer> RETRIES = Part.of(Job::retries, (j, v) -> j.with(1, v));
    static final Part<Job, Boolean> ACTIVE = Part.of(Job::active, (j, v) -> j.with(2, v));
    static final Part<Job, Boolean> ALERT = Part.of(Job::alert, (j, v) -> j.with(3, v));
    static final Part<Job, Level> LEVEL = Part.of(Job::level, (j, v) -> j.with(4, v));
    static final Part<Job, Level> MODE = Part.of(Job::mode, (j, v) -> j.with(5, v));
    static final Part<Job, Level> UNIT = Part.of(Job::unit, (j, v) -> j.with(6, v));
    static final Part<Job, Integer> WORKERS = Part.of(Job::workers, (j, v) -> j.with(7, v));
    static final Part<Job, Integer> LOAD = Part.of(Job::load, (j, v) -> j.with(8, v));
    static final Part<Job, String> HOST = Part.of(Job::host, (j, v) -> j.with(9, v));
    static final Part<Job, Level> REGION = Part.of(Job::region, (j, v) -> j.with(10, v));

    /** A copy with one component changed, by its index: a wither for every part at once. */
    Job with(int i, Object v) {
      Object[] c = {name, retries, active, alert, level, mode, unit, workers, load, host, region};
      c[i] = v;
      return new Job(
          (String) c[0],
          (Integer) c[1],
          (Boolean) c[2],
          (Boolean) c[3],
          (Level) c[4],
          (Level) c[5],
          (Level) c[6],
          (Integer) c[7],
          (Integer) c[8],
          (String) c[9],
          (Level) c[10]);
    }
  }

  static final Format<Integer> RETRIES =
      Format.INTEGER.where(n -> n >= 0, "0 or more").optional();
  static final List<Level> LEVELS = List.of(Level.values());

  static final class State {
    final Editing<Job> editing = Editing.of(new Job(
            "nightly", 3, true, false, Level.LOW, Level.LOW, Level.LOW, 2, 10, "db", Level.LOW))
        .rule(Job.NAME, j -> !j.name().isBlank(), "A name is required");
    final Menu.State levelMenu = new Menu.State();
    final ComboBox.State hosts = new ComboBox.State();
    final ComboBox.State regions = new ComboBox.State();
  }

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  static El view(State s) {
    Theme t = Theme.DARK;
    Editing<Job> e = s.editing;
    return col()
        .fill(t.surface().window())
        .wFull()
        .hFull()
        .padding(t.space().l())
        .child(Form.form(
            t,
            Form.Labels.BESIDE,
            Form.field("Name", TextField.textField(t, e.at(Job.NAME)).key("name")),
            Form.field("Active", Checkbox.checkbox(t, e.at(Job.ACTIVE), null).key("active")),
            // Shows only while the job is active.
            e.value().active()
                ? Form.field("Retries", ValueField.valueField(t, RETRIES, e.at(Job.RETRIES)))
                : null,
            Form.field("Alert", Toggle.toggle(t, e.at(Job.ALERT), null)),
            Form.field(
                "Level",
                Select.select(t, LEVELS, e.at(Job.LEVEL), Level::name, s.levelMenu)
                    .key("level")),
            Form.field(
                "Mode", Radio.radioGroup(t, LEVELS, e.at(Job.MODE), Level::name).key("mode")),
            Form.field(
                "Unit",
                Segmented.segmented(t, LEVELS, e.at(Job.UNIT), Level::name).key("unit")),
            Form.field("Workers", NumberField.numberField(t, e.at(Job.WORKERS), 1, 8, 1)),
            Form.field("Load", Slider.slider(t, e.at(Job.LOAD), 0, 100, 10)),
            Form.field(
                "Host",
                ComboBox.free(t, e.at(Job.HOST), List.of("db", "dc"), s.hosts).key("host")),
            Form.field(
                "Region", ComboBox.comboBox(t, LEVELS, e.at(Job.REGION), Level::name, s.regions)),
            Form.field(
                "Owner", TextField.textField(t, Prop.of(() -> "ops", null)).key("owner"))));
  }

  private static App<State> app(State s) {
    return new App<>(s, EditingFormTest::view);
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run("size 520 640\nframe\n" + steps + "\nframe", app, Path.of("target/editing-form"));
  }

  private static String line(App<?> app, String part) {
    return StructureTest.line(app.tree().dump(), part);
  }

  @Test
  void everyComponentWritesItsPropBackIntoTheEditing() throws IOException {
    State s = new State();
    App<State> app = app(s);
    run(app, """
        focus #name
        key A control
        type "weekly"
        press label="Alert"
        focus #level
        key ArrowDown
        focus #mode
        key ArrowDown
        focus #unit
        key ArrowRight
        focus role=spinbutton
        key ArrowUp
        focus role=slider
        key ArrowRight
        press #host
        key A control
        type "dc"
        key Escape""");
    Job j = s.editing.value();
    assertEquals("weekly", j.name());
    assertTrue(j.alert());
    assertEquals(Level.HIGH, j.level());
    assertEquals(Level.HIGH, j.mode());
    assertEquals(Level.HIGH, j.unit());
    assertEquals(3, j.workers());
    assertEquals(20, j.load());
    assertEquals("dc", j.host());
    assertTrue(s.editing.changed());
    assertTrue(line(app, "#owner").contains(" disabled"), "a prop that cannot be written");
  }

  @Test
  void errorsShowAtTheirFieldsWithoutTheAppPassingThem() throws IOException {
    State s = new State();
    App<State> app = app(s);
    run(app, "focus #name\nkey A control\nkey Delete");
    assertTrue(line(app, "#name").contains(" invalid error=\"A name is required\""));
    assertTrue(app.tree().dump().contains("text \"A name is required\""), "the form shows a rule");
    assertFalse(s.editing.valid());
    run(app, "type \"x\"");
    assertTrue(s.editing.valid());
    s.editing.at(Job.LEVEL).error("Not on this server");
    app.invalidate();
    run(app, "");
    assertTrue(line(app, "#level").contains(" invalid"));
    assertTrue(app.tree().dump().contains("text \"Not on this server\""), "the app's error");
  }

  @Test
  void aFieldThatComesBackKeepsItsDraft() throws IOException {
    State s = new State();
    App<State> app = app(s);
    run(app, "focus label=\"Retries\"\nkey A control\ntype \"-4\"");
    assertTrue(line(app, "label=\"Retries\"").contains("\"-4\" "), line(app, "label=\"Retries\""));
    assertFalse(s.editing.valid());
    run(app, "press #active");
    assertFalse(app.tree().dump().contains("label=\"Retries\""), "hidden");
    assertTrue(line(app, "label=\"Alert\"").contains("checked=false"), "the others stay");
    run(app, "press #active");
    String back = line(app, "label=\"Retries\"");
    assertTrue(back.contains("\"-4\" ") && back.contains("error=\"0 or more\""), back);
    assertEquals(3, s.editing.value().retries(), "the value is the last valid one");
  }

  @Test
  void aPropThatCannotBeWrittenDisablesEveryComponent() throws IOException {
    Theme t = Theme.DARK;
    Part<String, String> whole = Part.of(v -> v, (v, w) -> w);
    Editing<String> nothing = Editing.of(null);
    App<Object> app = new App<>(
        new Object(),
        x -> col()
            .children(
                TextField.textField(t, Prop.of(() -> "text", null)).key("text"),
                TextField.passwordField(t, Prop.of(() -> "secret", null), "Password")
                    .key("password"),
                ValueField.valueField(t, Format.TEXT.optional(), nothing.at(whole))
                    .key("value"),
                Checkbox.checkbox(t, Prop.of(() -> true, null), "Check").key("check"),
                Toggle.toggle(t, Prop.of(() -> true, null), "Toggle").key("toggle"),
                Radio.radioGroup(t, List.of("A"), Prop.of(() -> "A", null), v -> v)
                    .key("radio"),
                Segmented.segmented(t, List.of("A"), Prop.of(() -> "A", null), v -> v)
                    .key("segment"),
                Select.select(t, List.of("A"), Prop.of(() -> "A", null), v -> v, new Menu.State())
                    .key("select"),
                ComboBox.comboBox(
                        t, List.of("A"), Prop.of(() -> "A", null), v -> v, new ComboBox.State())
                    .key("combo"),
                ComboBox.items(
                        t,
                        List.of("A"),
                        Prop.of(() -> "A", null),
                        ComboBox::item,
                        new ComboBox.State())
                    .key("items"),
                ComboBox.free(t, Prop.of(() -> "A", null), List.of("A"), new ComboBox.State())
                    .key("free"),
                NumberField.numberField(t, Prop.of(() -> 5, null), 0, 10, 1).key("number"),
                Slider.slider(t, Prop.of(() -> 5, null), 0, 10, 1).key("slider")));
    run(app, "key Tab\nkey Enter\nkey Space\nmove #combo\npress #combo");
    assertEquals(null, app.focused(), "nothing takes the focus");
    assertEquals(Cursor.DEFAULT, app.cursor());
    for (String key : List.of(
        "text",
        "password",
        "value",
        "check",
        "toggle",
        "radio",
        "segment",
        "select",
        "combo",
        "items",
        "free",
        "slider")) {
      assertTrue(line(app, "#" + key + " ").contains(" disabled"), key);
    }
    assertTrue(line(app, "role=spinbutton").contains(" disabled"));
  }

  /** A value that is none of the choices chooses nothing, and the first arrow the first. */
  @Test
  void aChoiceOfAValueThatIsNotThereGoesToTheFirstByArrow() throws IOException {
    Theme t = Theme.DARK;
    List<String> values = List.of("A", "B");
    List<Function<Prop<String>, El>> controls = List.of(
        p -> Radio.radioGroup(t, values, p, v -> v),
        p -> Segmented.segmented(t, values, p, v -> v),
        p -> Select.select(t, values, p, v -> v, new Menu.State()));
    for (Function<Prop<String>, El> control : controls) {
      String[] value = {"missing"};
      List<String> told = new ArrayList<>();
      App<Object> app = new App<>(
          new Object(),
          x -> col().child(control.apply(Prop.of(() -> value[0], v -> {
            value[0] = v;
            told.add(v);
          }))));
      run(app, "key Tab\nkey ArrowDown\nkey ArrowDown control");
      assertEquals(List.of("A"), told);
    }
  }
}
