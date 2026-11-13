package com.rethinkj.ui.kit;

import com.rethinkj.ui.kit.DataGrid.DataType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * How a value of type {@code T} is written as text and read back, for a field that edits such a
 * value ({@link ValueField}) and a grid's column of such values ({@link DataGrid.Table}): {@link
 * #text} writes a value, {@link #parse} reads a text into a value or a message that says what a
 * valid text looks like, and {@link #allows} says whether typing may lead from a text to a valid
 * one, so that a field refuses a letter in a number.
 *
 * <pre>{@code
 * Format.DECIMAL.parse("12.50")      // Parsed[value=12.50, error=null]
 * Format.DECIMAL.parse("12,50")      // Parsed[value=null, error=Not a number]
 * Format.DATE.text(LocalDate.of(2026, 11, 13))            // "2026-11-13"
 * Format.INTEGER.where(p -> p >= 1 && p <= 65535, "1 to 65535")
 * Format.choice(List.of(Status.values()), Status::label)   // an enumeration by its labels
 * Format.of(UUID::toString, UUID::fromString, "Expected a UUID such as 1b4e28ba-2fa1-11d2-883f-0016d3cca427")
 * }</pre>
 *
 * <p>The formats do not depend on the system's locale: numbers are written with a full stop and
 * no grouping, dates and times in ISO 8601's form, as SQL writes them. Another notation is another
 * format of the app's own ({@link #of}).
 *
 * <p>The empty text is no value: {@link #parse} answers it with "A value is required", unless the
 * format is {@link #optional}, which reads it as null, or is {@link #TEXT}, whose empty text is the
 * empty string. A format is immutable and may be kept in a constant. It needs no native libraries.
 *
 * @param <T> the type of the values
 */
public final class Format<T> {

  /**
   * What {@link #parse} made of a text: a value, or a message.
   *
   * @param value the value, null for none (also for the empty text of an {@link #optional}
   *     format)
   * @param error what is wrong with the text, saying what a valid text looks like; null when it
   *     is valid
   */
  public record Parsed<T>(T value, String error) {

    /** Whether the text was valid. */
    public boolean valid() {
      return error == null;
    }

    static <T> Parsed<T> of(T value) {
      return new Parsed<>(value, null);
    }

    static <T> Parsed<T> error(String message) {
      return new Parsed<>(null, Objects.requireNonNull(message, "message"));
    }
  }

  /** What {@link #parse} answers to the empty text of a format that is not {@link #optional}. */
  public static final String REQUIRED = "A value is required";

  private static final Pattern WHOLE = Pattern.compile("[-+]?\\d+");
  private static final Pattern WHOLE_TYPED = Pattern.compile("[-+]?\\d*");
  private static final Pattern NUMBER =
      Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
  private static final Pattern NUMBER_TYPED = Pattern.compile("[-+]?\\d*\\.?\\d*([eE][-+]?\\d*)?");
  private static final DateTimeFormatter ISO_DATE =
      DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter ISO_TIME =
      DateTimeFormatter.ISO_TIME.withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter ISO_LOCAL_TIME =
      DateTimeFormatter.ISO_LOCAL_TIME.withResolverStyle(ResolverStyle.STRICT);
  // A date, a T or a space, and a time with an offset or without: 2026-11-13 14:30.
  private static final DateTimeFormatter ISO_DATE_TIME = new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .append(DateTimeFormatter.ISO_LOCAL_DATE)
      .optionalStart()
      .appendLiteral('T')
      .optionalEnd()
      .optionalStart()
      .appendLiteral(' ')
      .optionalEnd()
      .append(DateTimeFormatter.ISO_TIME)
      .toFormatter(Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT);

  private static final String WHOLE_MESSAGE = "Not a whole number";
  private static final String NUMBER_MESSAGE = "Not a number";
  private static final String DATE_MESSAGE = "Expected a date such as 2026-11-13";
  private static final String TIME_MESSAGE = "Expected a time such as 14:30 or 14:30:15";
  private static final String DATE_TIME_MESSAGE =
      "Expected a date and time such as 2026-11-13 14:30";

  /** Text as it is: the empty text is the empty string, unless {@link #optional}. */
  public static final Format<String> TEXT =
      new Format<>(v -> v, Parsed::of, text -> true, DataType.TEXT, List.of(), false, true);

  /** A whole number of any size, as SQL's {@code numeric} without a fraction. */
  public static final Format<BigInteger> BIG_INTEGER = new Format<>(
      BigInteger::toString,
      text -> WHOLE.matcher(text).matches()
          ? Parsed.of(new BigInteger(text))
          : Parsed.error(WHOLE_MESSAGE),
      text -> WHOLE_TYPED.matcher(text).matches(),
      DataType.INTEGER,
      List.of(),
      false,
      false);

  /** A whole number of {@code int}'s range, such as {@code -12} or {@code +7}. */
  public static final Format<Integer> INTEGER =
      whole(Integer.MIN_VALUE, Integer.MAX_VALUE).map(BigInteger::intValueExact, Object::toString);

  /** A whole number of {@code long}'s range. */
  public static final Format<Long> LONG =
      whole(Long.MIN_VALUE, Long.MAX_VALUE).map(BigInteger::longValueExact, Object::toString);

  /**
   * A decimal number, with a full stop and an exponent if any: {@code 12.50}, {@code -.5}, {@code
   * 1e3}. Its scale is the text's, so {@code 12.50} stays {@code 12.50}; it is written without an
   * exponent ({@link BigDecimal#toPlainString}).
   */
  public static final Format<BigDecimal> DECIMAL = new Format<>(
      BigDecimal::toPlainString,
      text -> {
        if (NUMBER.matcher(text).matches()) {
          try {
            return Parsed.of(new BigDecimal(text));
          } catch (NumberFormatException e) { // an exponent beyond an int
          }
        }
        return Parsed.error(NUMBER_MESSAGE);
      },
      text -> NUMBER_TYPED.matcher(text).matches(),
      DataType.DECIMAL,
      List.of(),
      false,
      false);

  /** A date as ISO 8601 writes it: {@code 2026-11-13}. */
  public static final Format<LocalDate> DATE =
      temporal(LocalDate::toString, ISO_DATE, LocalDate::from, DATE_MESSAGE, DataType.DATE);

  /** A time of day: {@code 14:30}, {@code 14:30:15} or {@code 14:30:15.250}, without an offset. */
  public static final Format<LocalTime> TIME =
      temporal(LocalTime::toString, ISO_LOCAL_TIME, LocalTime::from, TIME_MESSAGE, DataType.TIME);

  /**
   * A date and a time without an offset: {@code 2026-11-13 14:30}, also read with a {@code T}
   * between them. Written with a space, as SQL does.
   */
  public static final Format<LocalDateTime> DATE_TIME = temporal(
      v -> v.toLocalDate() + " " + v.toLocalTime(),
      ISO_DATE_TIME,
      t -> t.isSupported(java.time.temporal.ChronoField.OFFSET_SECONDS)
          ? null // an offset would be lost
          : LocalDateTime.from(t),
      DATE_TIME_MESSAGE,
      DataType.DATE_TIME);

  /** A date and a time with an offset: {@code 2026-11-13 14:30+01:00} or {@code ...Z}. */
  public static final Format<OffsetDateTime> OFFSET_DATE_TIME = temporal(
      v -> v.toLocalDate() + " " + v.toLocalTime() + v.getOffset(),
      ISO_DATE_TIME,
      OffsetDateTime::from,
      "Expected a date, a time and an offset such as 2026-11-13 14:30+01:00",
      DataType.DATE_TIME);

  /**
   * {@code true} or {@code false}; also read from {@code t}, {@code 1}, {@code yes}, {@code y},
   * {@code on} and their opposites, in any case.
   */
  public static final Format<Boolean> BOOLEAN = new Format<>(
      Object::toString,
      text -> switch (text.strip().toLowerCase(Locale.ROOT)) {
        case "true", "t", "1", "yes", "y", "on" -> Parsed.of(true);
        case "false", "f", "0", "no", "n", "off" -> Parsed.of(false);
        default -> Parsed.error("Expected true or false");
      },
      text -> true,
      DataType.BOOLEAN,
      List.of(),
      false,
      false);

  // The grid's checks of TIME and DATE_TIME columns, which take a time with an offset or without.
  static final Format<TemporalAccessor> ANY_TIME =
      temporal(Object::toString, ISO_TIME, t -> t, TIME_MESSAGE, DataType.TIME);
  static final Format<TemporalAccessor> ANY_DATE_TIME =
      temporal(Object::toString, ISO_DATE_TIME, t -> t, DATE_TIME_MESSAGE, DataType.DATE_TIME);

  private final Function<? super T, String> text;
  private final Function<String, Parsed<T>> parse; // of a text that is not empty
  private final Predicate<String> allows;
  private final DataType type;
  private final List<String> choices;
  private final boolean optional;
  private final boolean textual; // the empty text is a value of its own

  private Format(
      Function<? super T, String> text,
      Function<String, Parsed<T>> parse,
      Predicate<String> allows,
      DataType type,
      List<String> choices,
      boolean optional,
      boolean textual) {
    this.text = text;
    this.parse = parse;
    this.allows = allows;
    this.type = type;
    this.choices = choices;
    this.optional = optional;
    this.textual = textual;
  }

  /**
   * A format of the app's own: {@code parse} reads a text that is not empty, and fails by
   * throwing or returning null, which {@link #parse} answers with {@code message}.
   *
   * @param text writes a value, never null
   * @param parse reads a text that is not empty
   * @param message what a valid text looks like, such as {@code Expected a UUID such as ...}
   */
  public static <T> Format<T> of(
      Function<? super T, String> text, Function<String, ? extends T> parse, String message) {
    Objects.requireNonNull(parse, "parse");
    Objects.requireNonNull(message, "message");
    return new Format<T>(
        Objects.requireNonNull(text, "text"),
        s -> {
          T value;
          try {
            value = parse.apply(s);
          } catch (RuntimeException e) {
            return Parsed.error(message);
          }
          return value == null ? Parsed.error(message) : Parsed.of(value);
        },
        s -> true,
        DataType.OTHER,
        List.of(),
        false,
        false);
  }

  /**
   * One of {@code values}, each written as its {@code label}, such as the constants of an enum:
   * {@code Format.choice(List.of(Status.values()), Status::label)}. A text is read as the value whose label
   * it is exactly; {@link #choices} lists the labels, which a grid's column offers as its
   * choices.
   */
  public static <T> Format<T> choice(List<? extends T> values, Function<? super T, String> label) {
    List<T> all = List.copyOf(values);
    List<String> labels = new ArrayList<>(all.size());
    for (T value : all) {
      labels.add(Objects.requireNonNull(label.apply(value), "label"));
    }
    List<String> texts = List.copyOf(labels);
    return new Format<>(
        label,
        text -> {
          int at = texts.indexOf(text);
          return at < 0 ? Parsed.error("Not one of the choices") : Parsed.of(all.get(at));
        },
        text -> true,
        DataType.ENUM,
        texts,
        false,
        false);
  }

  // Using it

  /** The text of a value; the empty text for null. */
  public String text(T value) {
    return value == null ? "" : text.apply(value);
  }

  /**
   * The value of a text, or what is wrong with it. The empty text is {@link #REQUIRED}, null for
   * an {@link #optional} format, and the empty string for {@link #TEXT}.
   */
  public Parsed<T> parse(String text) {
    Objects.requireNonNull(text, "text");
    if (text.isEmpty() && (optional || !textual)) {
      return optional ? Parsed.of(null) : Parsed.error(REQUIRED);
    }
    return parse.apply(text);
  }

  /** {@link #parse} without the rule for the empty text, as the grid checks a cell's text. */
  Parsed<T> parseText(String text) {
    return parse.apply(text);
  }

  /**
   * Whether typing may lead from {@code text} to a valid text: false for {@code 12a} in a number,
   * true for {@code 12.} and {@code -}, which are not valid yet. A field refuses an edit that
   * leaves a text this format does not allow, and keeps one it allows as a draft.
   */
  public boolean allows(String text) {
    return allows.test(text);
  }

  /**
   * The kind of data, which a grid's column of these values takes its alignment, font, header
   * icon and editor from: {@link DataType#OTHER} for a format of the app's own.
   */
  public DataType type() {
    return type;
  }

  /** The labels of a {@link #choice} format, in order; empty for any other. */
  public List<String> choices() {
    return choices;
  }

  /** Whether the empty text is null rather than an error. */
  public boolean isOptional() {
    return optional;
  }

  // Changed copies

  /** This format reading the empty text as null, for a value that may be missing. */
  public Format<T> optional() {
    return new Format<>(text, parse, allows, type, choices, true, textual);
  }

  /**
   * This format taking only the values that pass {@code test}, and answering others with {@code
   * message}, such as a range: {@code Format.INTEGER.where(p -> p >= 1 && p <= 65535, "1 to
   * 65535")}.
   */
  public Format<T> where(Predicate<? super T> test, String message) {
    Objects.requireNonNull(test, "test");
    Objects.requireNonNull(message, "message");
    return new Format<>(
        text,
        s -> {
          Parsed<T> parsed = parse.apply(s);
          return !parsed.valid() || test.test(parsed.value()) ? parsed : Parsed.error(message);
        },
        allows,
        type,
        choices,
        optional,
        textual);
  }

  /** This format as one of another type, through two functions, as INTEGER is made of whole. */
  private <U> Format<U> map(Function<T, U> to, Function<? super U, String> written) {
    return new Format<>(
        written,
        s -> {
          Parsed<T> parsed = parse.apply(s);
          return parsed.valid()
              ? Parsed.of(to.apply(parsed.value()))
              : Parsed.error(parsed.error());
        },
        allows,
        type,
        choices,
        optional,
        textual);
  }

  private static Format<BigInteger> whole(long min, long max) {
    BigInteger low = BigInteger.valueOf(min);
    BigInteger high = BigInteger.valueOf(max);
    return BIG_INTEGER.where(
        v -> v.compareTo(low) >= 0 && v.compareTo(high) <= 0,
        "Expected a whole number from " + min + " to " + max);
  }

  private static <T> Format<T> temporal(
      Function<? super T, String> text,
      DateTimeFormatter format,
      Function<TemporalAccessor, T> from,
      String message,
      DataType type) {
    return new Format<>(
        text,
        s -> {
          try {
            T value = from.apply(format.parse(s.strip()));
            return value == null ? Parsed.error(message) : Parsed.of(value);
          } catch (java.time.DateTimeException e) {
            return Parsed.error(message);
          }
        },
        s -> true,
        type,
        List.of(),
        false,
        false);
  }

  @Override
  public String toString() {
    return "Format[" + type + (optional ? ", optional" : "") + "]";
  }
}
