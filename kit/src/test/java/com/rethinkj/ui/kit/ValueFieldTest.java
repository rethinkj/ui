package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A field of a typed value: drafts, messages, the empty text and values from outside. */
class ValueFieldTest {

  static final class State {
    BigDecimal amount = new BigDecimal("12.50");
    final ValueField.Draft amountDraft = new ValueField.Draft();
    LocalDate due;
    final ValueField.Draft dueDraft = new ValueField.Draft();
    String appError;
    int handed;
  }

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static App<State> app(State s) {
    return app(s, Theme.DARK);
  }

  private static App<State> app(State s, Theme t) {
    return new App<>(
        s,
        x -> col()
            .fill(t.surface().window())
            .wFull()
            .hFull()
            .padding(t.space().l())
            .child(Form.form(
                t,
                Form.Labels.ABOVE,
                Form.field(
                    "Amount",
                    ValueField.valueField(t, Format.DECIMAL, s.amount, s.amountDraft, v -> {
                          s.amount = v;
                          s.handed++;
                        })
                        .key("amount")),
                Form.field(
                        "Due",
                        ValueField.valueField(
                                t,
                                Format.DATE.optional(),
                                s.due,
                                "2026-11-13",
                                s.dueDraft,
                                v -> s.due = v)
                            .key("due"))
                    .error(s.appError))));
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run(
        "size 400 300\nframe\n" + steps + "\nframe", app, Path.of("target/value-field-test"));
  }

  private static String line(App<?> app, String key) {
    return StructureTest.line(app.tree().dump(), "#" + key);
  }

  @Test
  void aTextThatIsNoValueStaysAsADraftWithItsMessageAndTheValueStays() throws IOException {
    State s = new State();
    App<State> app = app(s);
    run(app, "focus #amount\nkey A control\ntype \"7\"");
    assertEquals(new BigDecimal("7"), s.amount, "a valid text is handed on at once");
    run(app, "type \"e\"");
    assertEquals(new BigDecimal("7"), s.amount, "7e is no number: the value stays");
    assertEquals("Not a number", s.amountDraft.error());
    String field = line(app, "amount");
    assertTrue(field.contains("\"7e\""), field);
    assertTrue(field.contains(" invalid") && field.contains(" error=\"Not a number\""), field);
    assertTrue(app.tree().dump().contains("text \"Not a number\""), "the form shows the message");
    run(app, "type \"x\"");
    assertTrue(line(app, "amount").contains("\"7e\""), "a letter no number has is refused");
    run(app, "type \"2\"");
    assertEquals(new BigDecimal("7e2"), s.amount);
    assertNull(s.amountDraft.error());
    field = line(app, "amount");
    assertTrue(field.contains("\"7e2\"") && !field.contains("invalid"), "the text stays as typed");
  }

  @Test
  void theEmptyTextIsRequiredOrNullAsTheFormatSays() throws IOException {
    State s = new State();
    s.due = LocalDate.of(2026, 11, 13);
    App<State> app = app(s);
    run(app, "focus #amount\nkey A control\nkey Delete");
    assertEquals(new BigDecimal("12.50"), s.amount);
    assertEquals(Format.REQUIRED, s.amountDraft.error());
    run(app, "focus #due\nkey A control\nkey Delete");
    assertNull(s.due, "an optional field hands on null");
    assertNull(s.dueDraft.error());
    run(app, "type \"2026-13\"");
    assertTrue(line(app, "due").contains("error=\"Expected a date such as 2026-11-13\""));
    s.appError = "Due before the order";
    app.invalidate();
    run(app, "");
    assertTrue(app.tree().dump().contains("text \"Due before the order\""), "the app's error wins");
    assertFalse(app.tree().dump().contains("text \"Expected a date"));
  }

  @Test
  void aValueFromOutsideReplacesTheDraft() throws IOException {
    State s = new State();
    App<State> app = app(s);
    run(app, "focus #amount\nkey A control\ntype \"1e\"");
    int handed = s.handed;
    s.amount = new BigDecimal("99.90"); // as a reset of the form does
    app.invalidate();
    run(app, "");
    String field = line(app, "amount");
    assertTrue(field.contains("\"99.90\"") && !field.contains("invalid"), field);
    assertNull(s.amountDraft.error());
    assertEquals(handed, s.handed, "showing a value hands nothing on");
  }

  /** A record with a required integer, for an {@link Editing} over it. */
  record Row(Integer count, String name) {
    static final Part<Row, Integer> COUNT = Part.of(Row::count, Row::withCount);
    static final Part<Row, String> NAME = Part.of(Row::name, Row::withName);

    Row withCount(Integer count) {
      return new Row(count, name);
    }

    Row withName(String name) {
      return new Row(count, name);
    }
  }

  @Test
  void aRequiredFieldLeftEmptyIsInvalidAndShowsSoOnceEditedOrAsked() throws IOException {
    Editing<Row> editing = Editing.of(new Row(null, "original"));
    App<Editing<Row>> app = new App<>(
        editing,
        e -> col()
            .child(Form.form(
                Theme.DARK,
                Form.Labels.ABOVE,
                Form.field(
                        "Count",
                        ValueField.valueField(Theme.DARK, Format.INTEGER, e.at(Row.COUNT))
                            .key("count"))
                    .required(),
                Form.field(
                    "Name",
                    TextField.textField(Theme.DARK, e.at(Row.NAME), "Name").key("name")))));
    run(app, "focus #name\ntype \"d\"");
    assertEquals("originald", editing.value().name());
    assertFalse(editing.valid(), "an empty required field cannot be saved as null");
    assertEquals(Format.REQUIRED, editing.at(Row.COUNT).error());
    assertFalse(line(app, "count").contains("invalid"), "a new form does not open in red");
    editing.showErrors(); // as Save does
    app.invalidate();
    run(app, "");
    assertTrue(
        line(app, "count").contains("invalid error=\"A value is required\""), line(app, "count"));
    editing.reset();
    app.invalidate();
    run(app, "focus #count\ntype \"4\"\nkey Backspace");
    assertTrue(line(app, "count").contains("invalid"), "an edited field shows its error");
  }

  @Test
  void aValueTheHandlerChangesOrRefusesIsShown() throws IOException {
    int[] value = {5};
    ValueField.Draft draft = new ValueField.Draft();
    App<Object> app = new App<>(
        new Object(),
        x -> col()
            .child(ValueField.valueField(Theme.DARK, Format.INTEGER, value[0], draft, v -> {
                  value[0] = Math.min(v, 10); // at most 10
                })
                .key("n")));
    run(app, "focus #n\nkey A control\ntype \"42\"");
    assertEquals(10, value[0]);
    assertTrue(line(app, "n").contains("\"10\""), line(app, "n"));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"dark", "light"})
  void theDraftAndItsMessage(String theme) throws IOException {
    App<State> app = app(new State(), theme.equals("dark") ? Theme.DARK : Theme.LIGHT);
    app.textRendering(TextRendering.LINUX); // the stored image must not depend on the platform
    List<Path> files = Script.run(
        "size 300 160 scale 2\nframe\npress #amount\nkey A control\ntype \"12e\"\nframe\n"
            + "advance 1\nframe\nsnap draft",
        app,
        Path.of("target/value-field-test", theme));
    Golden.assertMatches(
        "value-field-" + theme + "-2x-draft",
        Png.decode(Files.readAllBytes(files.get(0))),
        Files.readString(files.get(1)));
  }
}
