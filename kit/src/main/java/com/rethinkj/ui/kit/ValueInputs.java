package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.TextField.textField;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Filter.Between;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.function.Consumer;

/**
 * Typed value inputs and checks shared by filter UIs ({@link GridFilter}) that work from a {@link
 * DataType} and an optional {@link Format} without needing a {@link DataGrid.Column}.
 */
final class ValueInputs {

  private ValueInputs() {}

  /** Whether a value of {@code type} takes a date calendar in its input field. */
  static boolean dateType(DataType type) {
    return type == DataType.DATE || type == DataType.DATE_TIME;
  }

  /**
   * What is wrong with a non-null {@code value} for {@code type} and {@code format} (nullable), or
   * null when it fits.
   */
  static String check(DataType type, Format<?> format, String value) {
    if (format != null) {
      return format.parseText(value).error();
    }
    return switch (type) {
      case INTEGER -> Format.BIG_INTEGER.parseText(value).error();
      case DECIMAL -> Format.DECIMAL.parseText(value).error();
      case DATE -> Format.DATE.parseText(value).error();
      case TIME -> Format.ANY_TIME.parseText(value).error();
      case DATE_TIME -> Format.ANY_DATE_TIME.parseText(value).error();
      default -> null;
    };
  }

  /**
   * Whether {@code from} lies above {@code to} in the order of {@code type} and {@code format}
   * (nullable), as SQL compares them: typed values by their format, numbers and dates by theirs,
   * and times by the instant, which their text does not tell where offsets differ. An interval's
   * order is the app's to know, so a range of intervals is never refused.
   */
  static boolean above(DataType type, Format<?> format, String from, String to) {
    if (format != null) {
      return above(format, from, to);
    }
    return switch (type) {
      case INTEGER -> above(Format.BIG_INTEGER, from, to);
      case DECIMAL -> above(Format.DECIMAL, from, to);
      case DATE -> above(Format.DATE, from, to);
      case TIME, DATE_TIME -> timeAbove(type == DataType.TIME, from, to);
      default -> false;
    };
  }

  private static boolean timeAbove(boolean time, String from, String to) {
    Format<TemporalAccessor> any = time ? Format.ANY_TIME : Format.ANY_DATE_TIME;
    Format.Parsed<TemporalAccessor> low = any.parseText(from);
    Format.Parsed<TemporalAccessor> high = any.parseText(to);
    if (!low.valid() || !high.valid()) {
      return false;
    }
    boolean offset = low.value().isSupported(ChronoField.OFFSET_SECONDS);
    if (offset != high.value().isSupported(ChronoField.OFFSET_SECONDS)) {
      return false; // a local time against one with an offset: no order to go by
    }
    try {
      return offset
          ? offsetAbove(time, low.value(), high.value())
          : localAbove(time, low.value(), high.value());
    } catch (DateTimeException _) {
      return false; // an offset java.time does not take, such as +19:00: no order to go by
    }
  }

  private static boolean offsetAbove(boolean time, TemporalAccessor low, TemporalAccessor high) {
    return time
        ? OffsetTime.from(low).isAfter(OffsetTime.from(high))
        : OffsetDateTime.from(low).isAfter(OffsetDateTime.from(high));
  }

  private static boolean localAbove(boolean time, TemporalAccessor low, TemporalAccessor high) {
    return time
        ? LocalTime.from(low).isAfter(LocalTime.from(high))
        : LocalDateTime.from(low).isAfter(LocalDateTime.from(high));
  }

  private static <V> boolean above(Format<V> format, String from, String to) {
    Format.Parsed<V> low = format.parseText(from);
    Format.Parsed<V> high = format.parseText(to);
    if (!low.valid() || !high.valid() || low.value() == null || high.value() == null) {
      return false;
    }
    // the same instant at two offsets is no empty range in SQL, though compareTo orders them
    if (low.value() instanceof OffsetDateTime a && high.value() instanceof OffsetDateTime b) {
      return a.isAfter(b);
    }
    if (low.value() instanceof OffsetTime a && high.value() instanceof OffsetTime b) {
      return a.isAfter(b);
    }
    return !new Filter(0, new Between("", to)).matches(low.value(), format);
  }

  /**
   * A date or date-time input field with an embedded calendar button and popover. When {@code
   * beforeOpen} is non-null, it runs before the calendar opens (for example to close sibling
   * popovers or menus).
   */
  static El dateField(
      Theme theme,
      Texts texts,
      DataType type,
      Format<?> format,
      String value,
      String placeholder,
      String key,
      boolean autofocus,
      boolean invalid,
      DatePicker.State calendar,
      Runnable beforeOpen,
      Consumer<String> onChange) {
    El input = textField(value, placeholder, onChange).key(key).wFull();
    if (invalid) {
      input = TextField.invalid(input);
    }
    if (autofocus) {
      input.autofocus();
    }
    Runnable open = () -> {
      if (beforeOpen != null) {
        beforeOpen.run();
      }
      calendar.open();
    };
    Runnable toggle = () -> {
      if (calendar.isOpen()) {
        calendar.close();
      } else {
        open.run();
      }
    };
    El box = DatePicker.embedField(
        theme, texts, input, calendar, type == DataType.DATE_TIME, true, open, toggle);
    if (!calendar.isOpen()) {
      return box;
    }
    return box.overlay(DatePicker.calendarPanel(
        theme,
        texts,
        DatePicker.CLOCK.get(),
        calendar,
        DatePicker.parseDatePrefix(value),
        day -> {
          onChange.accept(DatePicker.replaceDate(type, format, value, day));
          calendar.close();
        },
        () -> {
          onChange.accept("");
          calendar.close();
        },
        null));
  }
}
