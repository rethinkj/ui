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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DatePicker}: pointer and keyboard picking, month switching, date-time, clear and props. */
class DatePickerTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  @Test
  void choosesADayByPointerAndKeyboardAcrossAMonthEdgeAndClearsWhenOptional() throws IOException {
    final class State {
      LocalDate due = LocalDate.of(2026, 11, 28);
      final DatePicker.State picker = new DatePicker.State();
    }
    State state = new State();
    Theme theme = Theme.DARK;
    App<State> app = new App<>(
            state,
            s -> col()
                .wFull()
                .hFull()
                .padding(theme.space().l())
                .fill(theme.surface().window())
                .child(Form.form(
                    Form.Labels.ABOVE,
                    Form.field(
                        "Due",
                        DatePicker.datePicker(
                                Format.DATE.optional(), s.due, s.picker, d -> s.due = d)
                            .key("due")))))
        .provide(Theme.CURRENT, s -> theme)
        .provide(DatePicker.CLOCK, s -> FIXED);
    app.textRendering(TextRendering.LINUX);

    // Open via calendar button and store the open popover image + tree at one theme and scale.
    List<Path> files = Script.run("""
        size 320 340 scale 2
        frame
        press role=button label="Choose date"
        frame
        advance 1
        frame
        snap open
        """, app, out.resolve("open"));
    assertTrue(state.picker.isOpen());
    String openTree = Files.readString(files.get(1));
    assertTrue(openTree.contains("role=dialog label=\"Choose date\""), openTree);
    assertTrue(openTree.contains("role=grid label=\"November 2026\""), openTree);
    assertTrue(
        openTree.contains("label=\"2026-11-28\"") && openTree.contains("selected"), openTree);
    assertTrue(openTree.contains("label=\"2026-11-22\"") && openTree.contains("current"), openTree);
    Golden.assertMatches(
        "date-picker-dark-2x-open", Png.decode(Files.readAllBytes(files.get(0))), openTree);

    // Navigate across the month edge by keyboard (2026-11-28 + 3 days = 2026-12-01) and pick.
    Script.run("""
        key ArrowRight
        key ArrowRight
        key ArrowRight
        frame
        """, app, out.resolve("nav"));
    assertTrue(
        app.tree().dump().contains("role=grid label=\"December 2026\""),
        app.tree().dump());
    Script.run("key Enter\nframe\n", app, out.resolve("pick-key"));
    assertFalse(state.picker.isOpen());
    assertEquals(LocalDate.of(2026, 12, 1), state.due);

    // Replace a malformed draft by picking a day with the pointer after switching month.
    Script.run("""
        focus role=textbox label="Due"
        key A control
        type "2026-99-99"
        frame
        key ArrowDown alt
        frame
        advance 1
        frame
        press role=button label="Previous month"
        frame
        press role=gridcell label="2026-11-15"
        frame
        """, app, out.resolve("pick-pointer"));
    assertEquals(LocalDate.of(2026, 11, 15), state.due);
    assertNull(state.picker.draft().error());
    assertFalse(state.picker.isOpen());

    // Clear on an optional format writes null and closes.
    Script.run("""
        press role=button label="Choose date"
        frame
        advance 1
        frame
        press role=button label="Clear"
        frame
        """, app, out.resolve("clear"));
    assertNull(state.due);
    assertFalse(state.picker.isOpen());
  }

  @Test
  void dateTimePickerKeepsTimeMovesFocusToTimeFieldAndHidesClearWhenRequired() throws IOException {
    final class State {
      LocalDateTime at = LocalDateTime.of(2026, 11, 13, 14, 30);
      final DatePicker.State picker = new DatePicker.State();
    }
    State state = new State();
    App<State> app = new App<>(
            state,
            s -> col()
                .wFull()
                .hFull()
                .padding(Theme.DARK.space().l())
                .child(Form.form(
                    Form.Labels.ABOVE,
                    Form.field(
                        "Scheduled",
                        DatePicker.dateTimePicker(
                            Format.DATE_TIME, s.at, s.picker, v -> s.at = v)))))
        .provide(DatePicker.CLOCK, s -> FIXED);

    Script.run("""
        size 340 360
        frame
        press role=button label="Choose date"
        frame
        advance 1
        frame
        """, app, out.resolve("dt-open"));
    String dump = app.tree().dump();
    assertFalse(dump.contains("label=\"Clear\""), "required format shows no Clear: " + dump);
    assertTrue(dump.contains("label=\"Time\""), dump);

    // Picking a day keeps 14:30, keeps popover open, and moves focus to the time field.
    Script.run("press role=gridcell label=\"2026-11-20\"\nframe\n", app, out.resolve("dt-pick"));
    assertEquals(LocalDateTime.of(2026, 11, 20, 14, 30), state.at);
    assertTrue(state.picker.isOpen());
    assertEquals("time", app.focused().key());

    // Enter on a time typed wrong keeps the popover open; a valid one keeps the day and closes it.
    Script.run(
        "key A control\ntype \"25:00\"\nframe\nkey Enter\nframe\n", app, out.resolve("dt-wrong"));
    assertTrue(state.picker.isOpen());
    assertEquals(LocalDateTime.of(2026, 11, 20, 14, 30), state.at);
    assertTrue(app.tree().dump().contains("role=alert"), "the time's message shows");
    Script.run(
        "key A control\ntype \"09:45\"\nframe\nkey Enter\nframe\n", app, out.resolve("dt-time"));
    assertEquals(LocalDateTime.of(2026, 11, 20, 9, 45), state.at);
    assertFalse(state.picker.isOpen());
  }

  @Test
  void propOverloadUsesFallbackOrEditingDraftAndReadOnlyDisables() throws IOException {
    final class State {
      LocalDate date = LocalDate.of(2026, 11, 10);
      final DatePicker.State writable = new DatePicker.State();
      final DatePicker.State readonly = new DatePicker.State();
    }
    State state = new State();
    App<State> app = new App<>(
            state,
            s -> col()
                .wFull()
                .hFull()
                .padding(Theme.DARK.space().l())
                .child(Form.form(
                    Form.Labels.ABOVE,
                    Form.field(
                        "Writable",
                        DatePicker.datePicker(
                            Format.DATE, Prop.of(() -> s.date, v -> s.date = v), s.writable)),
                    Form.field(
                        "Read-only",
                        DatePicker.datePicker(
                            Format.DATE, Prop.of(() -> s.date, null), s.readonly)))))
        .provide(DatePicker.CLOCK, s -> FIXED);

    Script.run("""
        size 340 360
        frame
        focus role=textbox label="Writable"
        key ArrowDown alt
        frame
        advance 1
        frame
        key PageUp
        key PageDown shift
        frame
        key Space
        frame
        """, app, out.resolve("prop"));
    // 2026-11-10 -> PageUp -> 2026-10-10 -> Shift+PageDown -> 2027-10-10
    assertEquals(LocalDate.of(2027, 10, 10), state.date);
    assertFalse(state.writable.isOpen());
    String dump = app.tree().dump();
    assertTrue(dump.contains("label=\"Read-only\"") && dump.contains("disabled"), dump);
  }
}
