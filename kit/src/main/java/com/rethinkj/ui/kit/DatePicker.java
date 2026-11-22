package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Context;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.render.Scene;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A typed date or date-time field ({@link ValueField}) with a button inside its frame that opens a
 * month calendar in a {@link Popover}: the user may type an ISO date directly or pick a day from
 * the calendar.
 *
 * <pre>{@code
 * class State {
 *   LocalDate due = LocalDate.of(2026, 11, 22);
 *   final DatePicker.State duePicker = new DatePicker.State();
 * }
 *
 * Form.field("Due", DatePicker.datePicker(Format.DATE, state.due, state.duePicker, value -> state.due = value))
 * }</pre>
 *
 * <p>Typing works as in {@link ValueField}: malformed input stays in the draft with the format's
 * error, and picking a day from the calendar replaces it. Pressing the calendar button or
 * Alt+ArrowDown in the field opens the popover. Inside the popover, the month grid is one Tab stop:
 * arrows move by a day or a week, Home and End jump to the start and end of the week, PageUp and
 * PageDown switch months, Shift+PageUp and Shift+PageDown switch years, and Enter or Space chooses
 * the focused day. In {@link #dateTimePicker}, choosing a day keeps the time (or {@code 00:00} when
 * there is none) and moves the focus to the time field in the footer, where Enter closes the
 * popover.
 *
 * <p>Which day is marked as today comes from {@link #CLOCK}, which defaults to the system's clock
 * and can be overridden with {@link com.rethinkj.ui.App#provide} for deterministic tests.
 */
public final class DatePicker {

  /**
   * The clock that decides which day is today, {@link Clock#systemDefaultZone()} unless the app or
   * a test provides another ({@code app.provide(DatePicker.CLOCK, state -> fixedClock)}).
   */
  public static final Context<Clock> CLOCK = new Context<>(Clock.systemDefaultZone());

  // A leading ISO date and an optional T/space separator with the rest (time, fraction, offset).
  private static final Pattern DATE_PREFIX =
      Pattern.compile("^([+-]?\\d{4,}-\\d{2}-\\d{2})(?:([Tt ])(.+))?$");

  private static final Pattern DATE = Pattern.compile("[+-]?\\d{4,}-\\d{2}-\\d{2}");

  private DatePicker() {}

  /**
   * Whether the calendar popover is open, which month and day the keyboard is on, and the fallback
   * {@link ValueField.Draft} used when no {@link Editing} keeps one. The app keeps one in its state
   * per picker.
   */
  public static final class State {
    final Popover.State popover = new Popover.State();
    final ValueField.Draft draft = new ValueField.Draft();
    final ValueField.Draft timeDraft = new ValueField.Draft();
    LocalDate cursor;
    YearMonth shown;
    int focusTime;

    public boolean isOpen() {
      return popover.isOpen();
    }

    public void open() {
      if (!popover.isOpen()) {
        popover.open();
        resetView();
      }
    }

    public void close() {
      popover.close();
      resetView();
    }

    /** Opens the popover if closed and closes it if open. */
    public void toggle() {
      if (isOpen()) {
        close();
      } else {
        open();
      }
    }

    /** The fallback draft kept here when the picker does not read one from an {@link Editing}. */
    public ValueField.Draft draft() {
      return draft;
    }

    private void resetView() {
      cursor = null;
      shown = null;
      timeDraft.clear();
      focusTime = 0;
    }
  }

  /**
   * A date field with a calendar popover.
   *
   * @param format how the date is written and read (such as {@link Format#DATE} or {@code
   *     Format.DATE.optional()})
   * @param value the current date, from the app's state; null for none
   * @param state the picker's state, from the app's state
   * @param onChange receives each valid date (or null when optional); null disables the picker
   */
  public static El datePicker(
      Format<LocalDate> format,
      LocalDate value,
      State state,
      Consumer<? super LocalDate> onChange) {
    return datePicker(format, value, state.draft, state, onChange);
  }

  /**
   * A date picker of a {@link Prop}; a prop that cannot be written disables it. Uses the prop's
   * draft when it comes from an {@link Editing}, else {@code state}'s fallback draft.
   */
  public static El datePicker(Format<LocalDate> format, Prop<LocalDate> value, State state) {
    Theme theme = Theme.CURRENT.get();
    ValueField.Draft draft = value.keepsDraft() ? value.draft() : state.draft;
    return Prop.shown(
        theme, datePicker(format, value.get(), draft, state, value.onChange()), value);
  }

  /**
   * A date-time field with a calendar popover whose footer includes a time field.
   *
   * @param format how the date and time are written and read (such as {@link Format#DATE_TIME})
   * @param value the current date and time, from the app's state; null for none
   * @param state the picker's state, from the app's state
   * @param onChange receives each valid date-time (or null when optional); null disables the picker
   */
  public static El dateTimePicker(
      Format<LocalDateTime> format,
      LocalDateTime value,
      State state,
      Consumer<? super LocalDateTime> onChange) {
    return dateTimePicker(format, value, state.draft, state, onChange);
  }

  /**
   * A date-time picker of a {@link Prop}; a prop that cannot be written disables it. Uses the
   * prop's draft when it comes from an {@link Editing}, else {@code state}'s fallback draft.
   */
  public static El dateTimePicker(
      Format<LocalDateTime> format, Prop<LocalDateTime> value, State state) {
    Theme theme = Theme.CURRENT.get();
    ValueField.Draft draft = value.keepsDraft() ? value.draft() : state.draft;
    return Prop.shown(
        theme, dateTimePicker(format, value.get(), draft, state, value.onChange()), value);
  }

  private static El datePicker(
      Format<LocalDate> format,
      LocalDate value,
      ValueField.Draft draft,
      State state,
      Consumer<? super LocalDate> onChange) {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(state, "state");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    Clock clock = CLOCK.get();
    boolean enabled = onChange != null;
    if (!enabled) {
      state.close();
    }
    El field = ValueField.valueField(format, value, null, draft, onChange).wFull();
    El box = embedField(theme, texts, field, state, false, enabled);
    if (!state.isOpen()) {
      return box;
    }
    Consumer<LocalDate> pick = day -> {
      draft.choose(texts, format, day, onChange);
      state.close();
    };
    Runnable clear = !format.isOptional()
        ? null
        : () -> {
          draft.edit(texts, format, format.text(null), onChange);
          state.close();
        };
    return box.overlay(calendarPanel(theme, texts, clock, state, value, pick, clear, null));
  }

  private static El dateTimePicker(
      Format<LocalDateTime> format,
      LocalDateTime value,
      ValueField.Draft draft,
      State state,
      Consumer<? super LocalDateTime> onChange) {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(state, "state");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    Clock clock = CLOCK.get();
    boolean enabled = onChange != null;
    if (!enabled) {
      state.close();
    }
    El field = ValueField.valueField(format, value, null, draft, onChange).wFull();
    El box = embedField(theme, texts, field, state, true, enabled);
    if (!state.isOpen()) {
      return box;
    }
    LocalDate today = LocalDate.now(clock);
    LocalDate chosen = value != null ? value.toLocalDate() : null;
    LocalTime currentTime = value != null ? value.toLocalTime() : null;
    Consumer<LocalDate> pick = day -> {
      LocalTime kept = value != null ? value.toLocalTime() : LocalTime.MIDNIGHT;
      LocalDateTime next = LocalDateTime.of(day, kept);
      state.timeDraft.clear();
      draft.choose(texts, format, next, onChange);
      state.focusTime++;
    };
    Runnable clear = !format.isOptional()
        ? null
        : () -> {
          draft.edit(texts, format, format.text(null), onChange);
          state.close();
        };
    El timeField = ValueField.valueField(
            Format.TIME, currentTime, "00:00", state.timeDraft, pickedTime -> {
              LocalDate day =
                  value != null ? value.toLocalDate() : state.cursor != null ? state.cursor : today;
              draft.choose(texts, format, LocalDateTime.of(day, pickedTime), onChange);
            })
        .key("time")
        .label(texts.time())
        .autofocus(state.focusTime == 0 ? null : state.focusTime)
        .onKey(k -> {
          if (k.modifiers() == 0 && (k.name().equals("Enter") || k.name().equals("NumpadEnter"))) {
            if (state.timeDraft.error() == null) { // a time typed wrong stays, with its message
              state.close();
            }
            return true;
          }
          return false;
        });
    if (state.timeDraft.showsError() && state.timeDraft.error() != null) {
      timeField.overlay(GridEdit.alert(theme, state.timeDraft.error())
          .side(Side.BOTTOM, Style.Align.START, theme.space().xs()));
    }
    return box.overlay(calendarPanel(theme, texts, clock, state, chosen, pick, clear, timeField));
  }

  /**
   * Embeds the trailing calendar button inside a text field's frame, wires Alt+ArrowDown on both,
   * and keeps the field's focused edge while the button has focus.
   */
  static El embedField(
      Theme theme, Texts texts, El field, State state, boolean dateTime, boolean enabled) {
    return embedField(theme, texts, field, state, dateTime, enabled, state::open, state::toggle);
  }

  /** Variant with custom open/toggle callbacks (e.g. closing a sibling calendar in GridFilter). */
  static El embedField(
      Theme theme,
      Texts texts,
      El field,
      State state,
      boolean dateTime,
      boolean enabled,
      Runnable onOpen,
      Runnable onToggle) {
    if (enabled) {
      Predicate<Key> own = field.onKey();
      field.onKey(k -> {
        if (k.modifiers() == Key.ALT && k.name().equals("ArrowDown")) {
          onOpen.run();
          return true;
        }
        return own != null && own.test(k);
      });
    }
    El button = FieldActions.button(
            theme,
            dateTime ? Symbol.CALENDAR_CLOCK : Symbol.CALENDAR_LTR,
            texts.chooseDate(),
            enabled ? onToggle : null)
        .expanded(state.isOpen());
    if (enabled) {
      button.focusable().focusRing(theme.accent().edge()).dismissGroup(state).onKey(k -> {
        if (k.modifiers() == Key.ALT && k.name().equals("ArrowDown")) {
          onOpen.run();
          return true;
        }
        return false;
      });
    }
    El box = FieldActions.embed(theme, field, button, theme.size().control())
        .w(theme.size().field());
    if (enabled) {
      box.whenFocusWithin(e -> {
        if (field.borderColor() != theme.status().danger()) {
          TextField.edge(theme, field, theme.accent().color(), theme.accent().color());
        }
      });
    }
    return box;
  }

  /**
   * Shared month calendar popover panel used by {@link DatePicker}, {@link GridEdit} and {@link
   * GridFilter}.
   *
   * @param chosen the currently selected day, or null
   * @param onPick called when the user chooses a day from the grid or presses Today
   * @param onClear called when the user presses Clear; null hides the Clear button
   * @param timeField optional time field at the start of the footer; null for date-only
   */
  static El calendarPanel(
      Theme theme,
      Texts texts,
      Clock clock,
      State state,
      LocalDate chosen,
      Consumer<LocalDate> onPick,
      Runnable onClear,
      El timeField) {
    LocalDate today = LocalDate.now(clock);
    if (state.cursor == null) {
      state.cursor = chosen != null ? chosen : today;
    }
    if (state.shown == null) {
      state.shown = YearMonth.from(state.cursor);
    }
    YearMonth shown = state.shown;
    String monthYear = texts.month(shown.getMonth()) + " " + shown.getYear();

    // Header: month and year left, previous/next month buttons right.
    El prev = Button.icon(
            Button.Variant.GHOST,
            Symbol.CHEVRON_LEFT,
            texts.previousMonth(),
            () -> stepMonth(state, -1),
            Button.options().size(Button.Size.SMALL))
        .key("previous-month");
    El next = Button.icon(
            Button.Variant.GHOST,
            Symbol.CHEVRON_RIGHT,
            texts.nextMonth(),
            () -> stepMonth(state, 1),
            Button.options().size(Button.Size.SMALL))
        .key("next-month");
    El header = row()
        .align(Style.Align.CENTER)
        .gap(theme.space().xs())
        .children(
            Label.label(Label.Variant.STRONG, monthYear)
                .key("month")
                .role("heading")
                .level(3)
                .grow(1),
            prev,
            next);

    // Weekday row starting at Texts.firstDayOfWeek().
    float cellSize = theme.size().control();
    float cellGap = theme.space().s();
    Theme.TextStyle small = theme.type().small();
    Theme.TextStyle body = theme.type().body();
    DayOfWeek firstDow = texts.firstDayOfWeek();
    El weekdays = row().justify(Style.Content.SPACE_BETWEEN).gap(cellGap);
    for (int i = 0; i < 7; i++) {
      DayOfWeek dow = firstDow.plus(i);
      weekdays.child(el().role("columnheader")
          .label(texts.weekday(dow))
          .w(cellSize)
          .center()
          .child(El.text(texts.weekdayShort(dow))
              .font(small.font().get())
              .fontSize(small.size())
              .color(theme.text().muted())));
    }

    // 6-week day grid: one focusable Tab stop with its own key handler.
    LocalDate firstOfMonth = shown.atDay(1);
    int offset = Math.floorMod(firstOfMonth.getDayOfWeek().getValue() - firstDow.getValue(), 7);
    LocalDate start = firstOfMonth.minusDays(offset);
    El grid = col()
        .key("days")
        .role("grid")
        .label(monthYear)
        .gap(theme.space().xs())
        .focusable()
        .focusRing(0)
        .autofocus()
        .onKey(k -> gridKey(k, state, firstDow, onPick));
    El[] cursorTarget = {null};
    float dot = theme.size().dot();
    float stroke = theme.size().stroke();
    for (int week = 0; week < 6; week++) {
      El weekRow = row().justify(Style.Content.SPACE_BETWEEN).gap(cellGap);
      for (int col = 0; col < 7; col++) {
        LocalDate day = start.plusDays(week * 7L + col);
        boolean inMonth = YearMonth.from(day).equals(shown);
        boolean isChosen = day.equals(chosen);
        boolean isToday = day.equals(today);
        int textColor = isChosen
            ? theme.text().onAccent()
            : inMonth ? theme.text().primary() : theme.text().muted();
        El cell = el().role("gridcell")
            .label(day.toString())
            .selected(isChosen)
            .current(isToday)
            .size(cellSize, cellSize)
            .shrink(0)
            .center()
            .radius(theme.radius().small())
            .cursor(Cursor.POINTER)
            .keepFocus()
            .motion(theme.motion().feedback())
            .onPress(() -> {
              state.cursor = day;
              state.shown = YearMonth.from(day);
              onPick.accept(day);
            })
            .child(El.text(Integer.toString(day.getDayOfMonth()))
                .font(body.font().get())
                .fontSize(body.size())
                .color(textColor));
        if (isChosen) {
          cell.fill(theme.accent().color())
              .whenHovered(e -> e.fill(theme.accent().hover()))
              .whenPressed(e -> e.fill(theme.accent().pressed()));
        } else {
          cell.whenHovered(e -> e.fill(theme.surface().hover()))
              .whenPressed(e -> e.fill(theme.surface().pressed()));
        }
        if (isToday) {
          El mark = el().size(dot, dot)
              .radius(dot / 2)
              .fill(isChosen ? theme.text().onAccent() : theme.accent().edge())
              .inert();
          Style at = mark.style();
          at.position = Style.Position.ABSOLUTE;
          at.bottom = Len.px(stroke);
          at.left = Len.px((cellSize - dot) / 2);
          cell.child(mark);
        }
        if (day.equals(state.cursor)) {
          if (isChosen) {
            float outset = stroke + Scene.RING;
            El ring = el().size(cellSize + 2 * outset, cellSize + 2 * outset)
                .radius(theme.radius().small() + outset)
                .border(Scene.RING, 0)
                .inert();
            Style at = ring.style();
            at.position = Style.Position.ABSOLUTE;
            at.left = Len.px(-outset);
            at.top = Len.px(-outset);
            cell.child(ring);
            cursorTarget[0] = ring;
          } else {
            cursorTarget[0] = cell;
          }
        }
        weekRow.child(cell);
      }
      grid.child(weekRow);
    }
    if (cursorTarget[0] != null) {
      El target = cursorTarget[0];
      grid.whenFocused(e -> target.border(Scene.RING, theme.accent().edge()));
    }

    // Footer: optional time field at the start, Today and optional Clear at the end.
    El todayButton = Button.button(
            Button.Variant.GHOST,
            texts.today(),
            () -> {
              state.cursor = today;
              state.shown = YearMonth.from(today);
              onPick.accept(today);
            },
            Button.options().size(Button.Size.SMALL))
        .key("today");
    El actions = row().gap(theme.space().xs()).align(Style.Align.CENTER).child(todayButton);
    if (onClear != null) {
      actions.child(Button.button(
              Button.Variant.GHOST,
              texts.clear(),
              onClear,
              Button.options().size(Button.Size.SMALL))
          .key("clear"));
    }
    El footer = row().align(Style.Align.CENTER).gap(theme.space().s());
    if (timeField != null) {
      // As wide as three days of the grid above it, which holds a time with its seconds.
      footer.child(timeField.w(3 * cellSize + 2 * cellGap)).child(el().grow(1)).child(actions);
    } else {
      footer.justify(Style.Content.END).child(actions);
    }

    El content =
        col().gap(theme.space().s()).children(header, weekdays, grid, Divider.divider(), footer);
    return Popover.panel(state.popover, texts.chooseDate(), content)
        .key("calendar")
        .keepFocus()
        .holdFocus()
        .dismissGroup(state)
        .onDismiss(state::close)
        .onKey(k -> {
          if (k.modifiers() == 0 && k.name().equals("Escape")) {
            state.close();
            return true;
          }
          return false;
        })
        .side(Side.BOTTOM, Style.Align.START, theme.space().xs());
  }

  private static void stepMonth(State state, int delta) {
    YearMonth next = state.shown.plusMonths(delta);
    state.shown = next;
    int day =
        state.cursor != null ? Math.min(state.cursor.getDayOfMonth(), next.lengthOfMonth()) : 1;
    state.cursor = next.atDay(day);
  }

  private static boolean gridKey(
      Key k, State state, DayOfWeek firstDow, Consumer<LocalDate> onPick) {
    int mods = k.modifiers();
    LocalDate cursor = state.cursor;
    if (cursor == null) {
      return false;
    }
    LocalDate next;
    if (mods == 0) {
      switch (k.name()) {
        case "ArrowLeft" -> next = cursor.minusDays(1);
        case "ArrowRight" -> next = cursor.plusDays(1);
        case "ArrowUp" -> next = cursor.minusWeeks(1);
        case "ArrowDown" -> next = cursor.plusWeeks(1);
        case "Home" -> {
          int offset = Math.floorMod(cursor.getDayOfWeek().getValue() - firstDow.getValue(), 7);
          next = cursor.minusDays(offset);
        }
        case "End" -> {
          int offset = Math.floorMod(cursor.getDayOfWeek().getValue() - firstDow.getValue(), 7);
          next = cursor.plusDays(6 - offset);
        }
        case "PageUp" -> next = cursor.minusMonths(1);
        case "PageDown" -> next = cursor.plusMonths(1);
        case "Enter", "NumpadEnter", "Space" -> {
          if (!k.repeat()) {
            onPick.accept(cursor);
          }
          return true;
        }
        default -> {
          return false;
        }
      }
    } else if (mods == Key.SHIFT) {
      switch (k.name()) {
        case "PageUp" -> next = cursor.minusYears(1);
        case "PageDown" -> next = cursor.plusYears(1);
        default -> {
          return false;
        }
      }
    } else {
      return false;
    }
    state.cursor = next;
    state.shown = YearMonth.from(next);
    return true;
  }

  /** Extracts a valid leading ISO date from a date or date-time text, or null. */
  static LocalDate parseDatePrefix(String text) {
    if (text == null) {
      return null;
    }
    Matcher m = DATE_PREFIX.matcher(text.strip());
    if (!m.matches()) {
      return null;
    }
    Format.Parsed<LocalDate> parsed = Format.DATE.parseText(m.group(1));
    return parsed.valid() ? parsed.value() : null;
  }

  /**
   * Replaces only the date part of a grid cell's or filter's text, keeping any typed time,
   * fraction, or offset; for a {@code DATE_TIME} column without a time part, appends {@code 00:00}
   * (or {@code 00:00Z} when the column requires an offset).
   */
  static String replaceDate(Column column, String text, LocalDate day) {
    if (column.type() != DataGrid.DataType.DATE_TIME) {
      return day.toString();
    }
    // The time is the digits before the first colon and what follows, also behind a date typed
    // wrong or none; its separator stays where it is a T or a space.
    String typed = text == null ? "" : text.strip();
    Matcher date = DATE.matcher(typed);
    if (date.lookingAt() && typed.substring(date.end()).chars().anyMatch(Character::isDigit)) {
      return day + typed.substring(date.end()); // a whole date: all after it, as it was typed
    }
    int colon = typed.indexOf(':');
    int at = colon;
    while (at > 0 && Character.isDigit(typed.charAt(at - 1))) {
      at--;
    }
    if (colon > 0 && at < colon) {
      boolean separated = at > 0 && " Tt".indexOf(typed.charAt(at - 1)) >= 0;
      return day + (separated ? typed.substring(at - 1, at) : " ") + typed.substring(at);
    }
    String local = day + " 00:00";
    if (GridEdit.check(column, local) == null) {
      return local;
    }
    String utc = day + " 00:00Z";
    return GridEdit.check(column, utc) == null ? utc : local;
  }
}
