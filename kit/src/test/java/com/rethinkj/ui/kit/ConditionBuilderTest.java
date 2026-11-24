package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.ConditionBuilder.Arity;
import com.rethinkj.ui.kit.ConditionBuilder.Clause;
import com.rethinkj.ui.kit.ConditionBuilder.Comparison;
import com.rethinkj.ui.kit.ConditionBuilder.Field;
import com.rethinkj.ui.kit.ConditionBuilder.Join;
import com.rethinkj.ui.kit.ConditionBuilder.Operator;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConditionBuilder}: two-level golden example, adding/removing/toggling/changing join and
 * field via scripted interaction, focus moves, validation marking, disabled state and {@link
 * ConditionBuilder#applicable}.
 */
class ConditionBuilderTest {

  static final Operator EQ = new Operator("eq", "=", Arity.ONE);
  static final Operator NEQ = new Operator("neq", "≠", Arity.ONE);
  static final Operator GT = new Operator("gt", ">", Arity.ONE);
  static final Operator BETWEEN = new Operator("between", "between", Arity.TWO);
  static final Operator IN = new Operator("in", "in", Arity.MANY);
  static final Operator CONTAINS = new Operator("contains", "contains", Arity.ONE);
  static final Operator IS_NULL = new Operator("is_null", "is null", Arity.NONE);

  static final List<Field> FIELDS = List.of(
      new Field(
          "status",
          "Status",
          DataType.ENUM,
          List.of("active", "paused", "archived"),
          List.of(EQ, NEQ, IN, IS_NULL)),
      new Field("age", "Age", DataType.INTEGER, List.of(), List.of(EQ, GT, BETWEEN, IS_NULL)),
      new Field(
          "country",
          "Country",
          DataType.ENUM,
          List.of("DE", "AT", "CH", "NL"),
          List.of(EQ, IN, IS_NULL)),
      new Field("email", "Email", DataType.TEXT, List.of(), List.of(CONTAINS, EQ, IS_NULL)),
      new Field("name", "Name", DataType.TEXT, List.of(), List.of(CONTAINS, EQ, IS_NULL)),
      new Field(
          "deleted_at", "Deleted at", DataType.DATE_TIME, List.of(), List.of(IS_NULL, EQ, BETWEEN)),
      new Field("score", "Score", DataType.DECIMAL, List.of(), List.of(EQ, GT, BETWEEN)),
      new Field("verified", "Verified", DataType.BOOLEAN, List.of(), List.of(EQ, IS_NULL)),
      new Field("created_on", "Created on", DataType.DATE, List.of(), List.of(EQ, BETWEEN)),
      new Field("run_at", "Run at", DataType.TIME, List.of(), List.of(EQ, BETWEEN)));

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);

  static Clause exampleTree() {
    return new Clause(
        Join.AND,
        List.of(
            new Comparison("status", "eq", List.of("active"), true),
            new Clause(
                Join.OR,
                List.of(
                    new Comparison("age", "gt", List.of("30"), true),
                    new Comparison("country", "in", List.of("DE", "AT"), true),
                    new Comparison("email", "contains", List.of("@test."), false)),
                true),
            new Comparison("created_on", "between", List.of("2026-01-01", "2026-12-31"), true),
            new Comparison("deleted_at", "is_null", List.of(), true)),
        true);
  }

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  static final class View {
    final Theme theme;
    final ConditionBuilder.State state = new ConditionBuilder.State();
    Clause root;
    boolean disabled;

    View(Theme theme, Clause root) {
      this.theme = theme;
      this.root = root;
    }

    El view() {
      return col()
          .wFull()
          .hFull()
          .padding(theme.space().l())
          .fill(theme.surface().window())
          .child(ConditionBuilder.conditionBuilder(
                  FIELDS, root, state, disabled ? null : next -> root = next)
              .key("builder"));
    }

    App<View> app() {
      App<View> app = new App<>(this, View::view)
          .provide(Theme.CURRENT, v -> v.theme)
          .provide(DatePicker.CLOCK, v -> FIXED_CLOCK);
      app.focusRing(theme.accent().edge());
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  @Test
  void twoLevelExampleMatchesStoredImage() throws IOException {
    App<View> app = new View(Theme.DARK, exampleTree()).app();
    String script = Files.readString(Path.of("src/test/resources/scripts/condition-builder.txt"));
    List<Path> files = Script.run(script, app, out.resolve("dark2"));
    Golden.assertSnaps("condition-builder-dark-2x-", files, "view");
  }

  @Test
  void addsRemovesSwitchesOffChangesJoinAndField() throws IOException {
    View view = new View(Theme.DARK, new Clause(Join.AND, List.of(), true));
    App<View> app = view.app();
    String prefix = "size 640 360 scale 2\nframe\n";

    // Empty root shows muted "No conditions" with + Condition and + Group.
    Script.run(prefix, app, out);
    String dump = app.tree().dump();
    assertTrue(dump.contains("\"No conditions\""), dump);
    assertTrue(dump.contains("#add-condition"), dump);
    assertTrue(dump.contains("#add-group"), dump);

    // Adding a condition focuses the new row's field.
    Script.run("press #add-condition\nframe", app, out);
    assertEquals(1, view.root.conditions().size());
    assertEquals(
        new Comparison("status", "eq", List.of(), true), view.root.conditions().getFirst());
    assertEquals("field-0", app.focused().key(), app.tree().dump());

    // Change field to Email (TEXT): offers "eq" so operator is kept, values stay empty; then
    // switch operator to "contains".
    Script.run("press #field-0\nframe\nadvance 1\nframe\npress label=\"Email\"\nframe", app, out);
    assertEquals(
        new Comparison("email", "eq", List.of(), true), view.root.conditions().getFirst());
    Script.run(
        "press #operator-0\nframe\nadvance 1\nframe\npress label=\"contains\"\nframe", app, out);
    assertEquals(
        new Comparison("email", "contains", List.of(), true),
        view.root.conditions().getFirst());

    // Type a value; incomplete row had no invalid mark, valid text has none either.
    Script.run("press #value-0\ntype \"@test.\"\nframe", app, out);
    assertEquals(
        new Comparison("email", "contains", List.of("@test."), true),
        view.root.conditions().getFirst());

    // Change field to Name (also TEXT, also offers "contains"): keeps both operator and value.
    Script.run("press #field-0\nframe\nadvance 1\nframe\npress label=\"Name\"\nframe", app, out);
    assertEquals(
        new Comparison("name", "contains", List.of("@test."), true),
        view.root.conditions().getFirst());

    // Change field to Age (INTEGER, no "contains"): switches to first operator ("eq") and clears
    // values. Incomplete row is not marked invalid.
    Script.run("press #field-0\nframe\nadvance 1\nframe\npress label=\"Age\"\nframe", app, out);
    assertEquals(
        new Comparison("age", "eq", List.of(), true), view.root.conditions().getFirst());
    assertFalse(line(app, "value-0").contains("invalid"), line(app, "value-0"));

    // Typing a half-typed negative sign stays in the tree and marks the field invalid; letters are
    // refused by Format.BIG_INTEGER.allows.
    Script.run("press #value-0\ntype \"-x\"\nframe", app, out);
    assertEquals(
        new Comparison("age", "eq", List.of("-"), true), view.root.conditions().getFirst());
    assertTrue(line(app, "value-0").contains("invalid"), line(app, "value-0"));
    assertTrue(line(app, "value-0").contains("error=\"Not a whole number\""), line(app, "value-0"));
    Script.run("type \"30\"\nframe", app, out);
    assertEquals(
        new Comparison("age", "eq", List.of("-30"), true),
        view.root.conditions().getFirst());
    assertFalse(line(app, "value-0").contains("invalid"), line(app, "value-0"));

    // Switch operator to "between" (Arity.TWO): clears values and shows from/to inputs.
    Script.run(
        "press #operator-0\nframe\nadvance 1\nframe\npress label=\"between\"\nframe", app, out);
    assertEquals(
        new Comparison("age", "between", List.of(), true),
        view.root.conditions().getFirst());
    Script.run("press #from-0\ntype \"18\"\npress #to-0\ntype \"65\"\nframe", app, out);
    assertEquals(
        new Comparison("age", "between", List.of("18", "65"), true),
        view.root.conditions().getFirst());

    // Switch off the row: stays in the tree and remains editable.
    Script.run("press #enabled-0\nframe", app, out);
    assertFalse(view.root.conditions().getFirst().enabled());
    Script.run("press #to-0\nkey Backspace\nframe", app, out);
    assertEquals(
        new Comparison("age", "between", List.of("18", "6"), false),
        view.root.conditions().getFirst());

    // Switch to Created on (DATE, offers "between"): picks dates with the calendar, detects a
    // reversed range, keeps half-typed invalid text, and keeps only one popover open at a time.
    Script.run("press #enabled-0\nframe", app, out);
    assertTrue(view.root.conditions().getFirst().enabled());
    Script.run(
        "press #field-0\nframe\nadvance 1\nframe\npress label=\"Created on\"\nframe", app, out);
    assertEquals(
        new Comparison("created_on", "between", List.of(), true),
        view.root.conditions().getFirst());
    // Opening a calendar closes any other open popover: only one popover stays open at a time.
    Script.run("press #from-0\nkey ArrowDown alt\nframe\nadvance 1\nframe", app, out);
    assertEquals(
        1,
        app.tree().dump().lines().filter(l -> l.contains("role=dialog")).count(),
        app.tree().dump());
    Script.run("press #to-0\nframe\nadvance 1\nframe", app, out);
    assertFalse(app.tree().dump().contains("role=dialog"), app.tree().dump());
    Script.run(
        "press #from-0\nkey ArrowDown alt\nframe\nadvance 1\nframe\npress role=gridcell label=\"2026-11-20\"\nframe\nadvance 1\nframe",
        app,
        out);
    assertEquals(
        new Comparison("created_on", "between", List.of("2026-11-20", ""), true),
        view.root.conditions().getFirst());
    // Reversed range: both ends marked invalid with From is greater than To and an alert below.
    Script.run(
        "press #to-0\nkey ArrowDown alt\nframe\nadvance 1\nframe\npress role=gridcell label=\"2026-11-10\"\nframe\nadvance 1\nframe",
        app,
        out);
    assertEquals(
        new Comparison("created_on", "between", List.of("2026-11-20", "2026-11-10"), true),
        view.root.conditions().getFirst());
    assertTrue(line(app, "from-0").contains("invalid"), line(app, "from-0"));
    assertTrue(
        line(app, "from-0").contains("error=\"From is greater than To\""), line(app, "from-0"));
    assertTrue(line(app, "to-0").contains("invalid"), line(app, "to-0"));
    assertTrue(line(app, "to-0").contains("error=\"From is greater than To\""), line(app, "to-0"));
    assertTrue(
        app.tree().dump().contains("role=alert label=\"From is greater than To\""),
        app.tree().dump());
    // Correcting the end clears both invalid marks and the alert.
    Script.run(
        "press #to-0\nkey ArrowDown alt\nframe\nadvance 1\nframe\npress role=gridcell label=\"2026-11-25\"\nframe\nadvance 1\nframe",
        app,
        out);
    assertEquals(
        new Comparison("created_on", "between", List.of("2026-11-20", "2026-11-25"), true),
        view.root.conditions().getFirst());
    assertFalse(line(app, "from-0").contains("invalid"), line(app, "from-0"));
    assertFalse(line(app, "to-0").contains("invalid"), line(app, "to-0"));
    assertFalse(app.tree().dump().contains("role=alert"), app.tree().dump());

    // Half-typed text in a date field stays in the tree and marks it invalid with its format error.
    Script.run("press #from-0\nkey End\nkey Backspace\nframe", app, out);
    assertEquals(
        new Comparison("created_on", "between", List.of("2026-11-2", "2026-11-25"), true),
        view.root.conditions().getFirst());
    assertTrue(line(app, "from-0").contains("invalid"), line(app, "from-0"));
    assertTrue(line(app, "from-0").contains("error="), line(app, "from-0"));
    assertFalse(line(app, "to-0").contains("invalid"), line(app, "to-0"));

    // DATE_TIME picks a day with default time; TIME keeps a plain text field without a picker.
    Script.run(
        "press #field-0\nframe\nadvance 1\nframe\npress label=\"Deleted at\"\nframe", app, out);
    Script.run(
        "press #operator-0\nframe\nadvance 1\nframe\npress label=\"=\"\nframe\nadvance 1\nframe",
        app,
        out);
    Script.run(
        "press #value-0\nkey ArrowDown alt\nframe\nadvance 1\nframe\npress role=gridcell label=\"2026-11-15\"\nframe\nadvance 1\nframe",
        app,
        out);
    assertEquals(
        new Comparison("deleted_at", "eq", List.of("2026-11-15 00:00"), true),
        view.root.conditions().getFirst());
    Script.run("press #field-0\nframe\nadvance 1\nframe\npress label=\"Run at\"\nframe", app, out);
    assertFalse(app.tree().dump().contains("label=\"Choose date\""), app.tree().dump());

    // Add a nested group: focuses the new group's first row's field; change join on root and group.
    Script.run("press #add-group\nframe", app, out);
    assertEquals(2, view.root.conditions().size());
    assertEquals("field-1-0", app.focused().key(), app.tree().dump());
    Clause nested = (Clause) view.root.conditions().get(1);
    assertEquals(Join.OR, nested.join());

    // Change root join from AND to OR, and nested group join from OR to AND.
    Script.run("focus #join\nkey ArrowRight\nframe", app, out);
    assertEquals(Join.OR, view.root.join());
    Script.run("focus #join-1\nkey ArrowLeft\nframe", app, out);
    assertEquals(Join.AND, ((Clause) view.root.conditions().get(1)).join());

    // Remove first item in root: focus moves to the next item's remove button (now #remove-0).
    Script.run("press #remove-0\nframe", app, out);
    assertEquals(1, view.root.conditions().size());
    assertEquals("remove-0", app.focused().key(), app.tree().dump());

    // Remove the last item in root: focus moves to the clause's + Condition button.
    Script.run("press #remove-0\nframe", app, out);
    assertTrue(view.root.conditions().isEmpty());
    assertEquals("add-condition", app.focused().key(), app.tree().dump());

    // Disabled builder (null onChange) disables controls (including date picker buttons) and
    // ignores presses.
    view.root = exampleTree();
    view.disabled = true;
    app.invalidate();
    Script.run("frame\npress #add-condition\nframe", app, out);
    assertEquals(4, view.root.conditions().size());
    assertTrue(line(app, "add-condition").contains("disabled"), line(app, "add-condition"));
    assertTrue(line(app, "field-0").contains("disabled"), line(app, "field-0"));
    assertTrue(
        app.tree().dump().contains("role=button label=\"Choose date\"")
            && app.tree()
                .dump()
                .lines()
                .filter(l -> l.contains("label=\"Choose date\""))
                .allMatch(l -> l.contains("disabled")),
        app.tree().dump());
  }

  @Test
  void applicableDropsSwitchedOffIncompleteInvalidAndEmptyClauses() {
    // Two-level example drops the switched-off email row.
    Clause filtered = ConditionBuilder.applicable(exampleTree(), FIELDS);
    assertEquals(
        new Clause(
            Join.AND,
            List.of(
                new Comparison("status", "eq", List.of("active"), true),
                new Clause(
                    Join.OR,
                    List.of(
                        new Comparison("age", "gt", List.of("30"), true),
                        new Comparison("country", "in", List.of("DE", "AT"), true)),
                    true),
                new Comparison("created_on", "between", List.of("2026-01-01", "2026-12-31"), true),
                new Comparison("deleted_at", "is_null", List.of(), true)),
            true),
        filtered);

    // Switched-off or empty clauses add nothing, also under OR.
    Clause underOr = new Clause(
        Join.OR,
        List.of(
            new Clause(Join.AND, List.of(new Comparison("age", "gt", List.of("10"), true)), false),
            new Clause(Join.AND, List.of(), true),
            new Clause(Join.OR, List.of(new Comparison("age", "gt", List.of("10"), false)), true),
            new Comparison("status", "eq", List.of("active"), true)),
        true);
    assertEquals(
        new Clause(Join.OR, List.of(new Comparison("status", "eq", List.of("active"), true)), true),
        ConditionBuilder.applicable(underOr, FIELDS));

    // Switched-off root produces no conditions.
    assertEquals(
        new Clause(Join.AND, List.of(), false),
        ConditionBuilder.applicable(
            new Clause(Join.AND, List.of(new Comparison("age", "gt", List.of("1"), true)), false),
            FIELDS));

    // Incomplete, invalid and reversed-range values are dropped; valid values of every type are
    // kept.
    Clause mixed = new Clause(
        Join.AND,
        List.of(
            // Incomplete: dropped
            new Comparison("email", "contains", List.of(), true),
            new Comparison("email", "contains", List.of(""), true),
            new Comparison("age", "between", List.of("10", ""), true),
            new Comparison("country", "in", List.of(), true),
            // Invalid values, reversed ranges, fields or operators: dropped
            new Comparison("age", "eq", List.of("-"), true),
            new Comparison("score", "eq", List.of("12e"), true),
            new Comparison("created_on", "eq", List.of("2026-02-30"), true),
            new Comparison("run_at", "eq", List.of("25:00"), true),
            new Comparison("deleted_at", "eq", List.of("not-a-date"), true),
            new Comparison("created_on", "between", List.of("2026-12-01", "2026-01-01"), true),
            new Comparison(
                "deleted_at", "between", List.of("2026-11-24 15:00", "2026-11-24 09:00"), true),
            new Comparison("age", "between", List.of("65", "18"), true),
            new Comparison("status", "eq", List.of("unknown"), true),
            new Comparison("country", "in", List.of("DE", "XX"), true),
            new Comparison("verified", "eq", List.of("maybe"), true),
            new Comparison("missing_field", "eq", List.of("1"), true),
            new Comparison("age", "missing_op", List.of("1"), true),
            // Valid: kept
            new Comparison("age", "eq", List.of("99999999999999999999"), true),
            new Comparison("score", "eq", List.of("-12.5"), true),
            new Comparison("created_on", "between", List.of("2026-01-01", "2026-12-01"), true),
            new Comparison("created_on", "between", List.of("2026-06-01", "2026-06-01"), true),
            new Comparison("run_at", "eq", List.of("14:30:00"), true),
            new Comparison("deleted_at", "eq", List.of("2026-11-24 14:30"), true),
            new Comparison("verified", "eq", List.of("true"), true)),
        true);
    Clause kept = ConditionBuilder.applicable(mixed, FIELDS);
    assertEquals(7, kept.conditions().size(), kept.toString());
  }

  private static String line(App<?> app, String key) {
    return ComboBoxTest.line(app.tree().dump(), "#" + key);
  }
}
