package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.kit.DataGrid.DataType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The formats, without native libraries. */
class FormatTest {

  enum Status {
    PENDING,
    PAID;

    String label() {
      return name().toLowerCase();
    }
  }

  private static <T> void roundTrip(Format<T> format, T value, String text) {
    assertEquals(text, format.text(value));
    assertEquals(value, format.parse(text).value(), text);
    assertTrue(format.parse(text).valid());
  }

  private static void refused(Format<?> format, String text, String message) {
    Format.Parsed<?> parsed = format.parse(text);
    assertFalse(parsed.valid(), text);
    assertNull(parsed.value());
    assertEquals(message, parsed.error());
  }

  @Test
  void numbersAreReadWithoutTheLocaleAndRefusedWithAMessage() {
    roundTrip(Format.INTEGER, 42, "42");
    roundTrip(Format.INTEGER, -7, "-7");
    assertEquals(7, Format.INTEGER.parse("+007").value());
    refused(Format.INTEGER, "4.2", "Not a whole number");
    refused(Format.INTEGER, "2147483648", "Expected a whole number from -2147483648 to 2147483647");
    roundTrip(Format.LONG, 2147483648L, "2147483648");
    roundTrip(
        Format.BIG_INTEGER,
        new BigInteger("123456789012345678901234567890"),
        "123456789012345678901234567890");
    roundTrip(Format.DECIMAL, new BigDecimal("12.50"), "12.50");
    assertEquals(new BigDecimal("1E+3"), Format.DECIMAL.parse("1e3").value());
    assertEquals("1000", Format.DECIMAL.text(new BigDecimal("1E+3")));
    assertEquals(new BigDecimal("-0.5"), Format.DECIMAL.parse("-.5").value());
    refused(Format.DECIMAL, "12,50", "Not a number");
    refused(Format.DECIMAL, "١٢", "Not a number"); // digits of another script
    refused(
        Format.DECIMAL,
        "1e99999999999",
        "Expected a number whose exponent adds at most 4096 zeros");
    assertEquals(DataType.INTEGER, Format.LONG.type());
    assertEquals(DataType.DECIMAL, Format.DECIMAL.type());
  }

  @Test
  void typingMayLeadToANumberOnlyThroughWhatANumberHas() {
    assertTrue(Format.INTEGER.allows("-"));
    assertTrue(Format.INTEGER.allows(""));
    assertFalse(Format.INTEGER.allows("1a"));
    assertFalse(Format.INTEGER.allows("1."));
    assertTrue(Format.DECIMAL.allows("12."));
    assertTrue(Format.DECIMAL.allows("1e"));
    assertFalse(Format.DECIMAL.allows("1.2.3"));
    assertTrue(Format.DATE.allows("anything")); // dates are checked when they are read
    refused(Format.DECIMAL, "1e", "Not a number");
  }

  @Test
  void datesAndTimesAreThoseOfIso8601() {
    roundTrip(Format.DATE, LocalDate.of(2026, 11, 13), "2026-11-13");
    refused(Format.DATE, "2026-02-30", "Expected a date such as 2026-11-13");
    refused(Format.DATE, "13.11.2026", "Expected a date such as 2026-11-13");
    assertEquals(LocalDate.of(2026, 11, 13), Format.DATE.parse(" 2026-11-13 ").value());
    roundTrip(Format.TIME, LocalTime.of(14, 30), "14:30");
    roundTrip(Format.TIME, LocalTime.of(14, 30, 15), "14:30:15");
    refused(Format.TIME, "14:30+01:00", "Expected a time such as 14:30 or 14:30:15");
    roundTrip(Format.DATE_TIME, LocalDateTime.of(2026, 11, 13, 14, 30), "2026-11-13 14:30");
    assertEquals(
        LocalDateTime.of(2026, 11, 13, 14, 30),
        Format.DATE_TIME.parse("2026-11-13T14:30").value());
    refused(
        Format.DATE_TIME, "2026-11-13 14:30Z", "Expected a date and time such as 2026-11-13 14:30");
    roundTrip(
        Format.OFFSET_DATE_TIME,
        OffsetDateTime.of(2026, 11, 13, 14, 30, 0, 0, ZoneOffset.ofHours(1)),
        "2026-11-13 14:30+01:00");
    // The grid's checks take a time with an offset or without.
    assertTrue(Format.ANY_TIME.parseText("14:30+01:00").valid());
    assertTrue(Format.ANY_DATE_TIME.parseText("2026-11-13 14:30").valid());
  }

  @Test
  void theEmptyTextIsRequiredUnlessTheFormatIsOptionalOrText() {
    refused(Format.DECIMAL, "", Format.REQUIRED);
    Format.Parsed<BigDecimal> empty = Format.DECIMAL.optional().parse("");
    assertTrue(empty.valid());
    assertNull(empty.value());
    assertEquals("", Format.TEXT.parse("").value());
    assertNull(Format.TEXT.optional().parse("").value());
    assertEquals("", Format.DATE.text(null));
  }

  /** What a format writes it reads back, also at the ends of each type. */
  @Test
  void theEndsOfEachTypeAreReadBackAsTheyWereWritten() {
    List<Object[]> values = List.of(
        new Object[] {Format.TEXT, ""},
        new Object[] {Format.TEXT, "é 🪟\nlong text ".repeat(1000)},
        new Object[] {Format.INTEGER, Integer.MIN_VALUE},
        new Object[] {Format.INTEGER, Integer.MAX_VALUE},
        new Object[] {Format.LONG, Long.MIN_VALUE},
        new Object[] {Format.LONG, Long.MAX_VALUE},
        new Object[] {Format.BIG_INTEGER, BigInteger.TEN.pow(200).negate()},
        new Object[] {Format.DECIMAL, new BigDecimal("-12345678901234567890.0001")},
        new Object[] {Format.DATE, LocalDate.MIN},
        new Object[] {Format.DATE, LocalDate.MAX},
        new Object[] {Format.TIME, LocalTime.MIN},
        new Object[] {Format.TIME, LocalTime.MAX},
        new Object[] {Format.DATE_TIME, LocalDateTime.MIN},
        new Object[] {Format.DATE_TIME, LocalDateTime.MAX},
        new Object[] {Format.OFFSET_DATE_TIME, OffsetDateTime.MIN},
        new Object[] {Format.OFFSET_DATE_TIME, OffsetDateTime.MAX},
        new Object[] {Format.BOOLEAN, true},
        new Object[] {Format.BOOLEAN, false});
    for (Object[] v : values) {
      @SuppressWarnings("unchecked")
      Format<Object> format = (Format<Object>) v[0];
      Format.Parsed<Object> parsed = format.parse(format.text(v[1]));
      assertTrue(parsed.valid(), parsed::toString);
      assertEquals(v[1], parsed.value());
      assertEquals("", format.text(null));
      assertNull(format.optional().parse("").value());
      assertTrue(format.optional().parse("").valid());
    }
  }

  @Test
  void choicesAreReadByTheirLabels() {
    Format<Status> format = Format.choice(List.of(Status.values()), Status::label);
    roundTrip(format, Status.PAID, "paid");
    refused(format, "PAID", "Not one of the choices");
    assertEquals(List.of("pending", "paid"), format.choices());
    assertEquals(DataType.ENUM, format.type());
  }

  @Test
  void anAppsFormatAndARangeAnswerWithTheirMessages() {
    Format<UUID> uuid = Format.of(UUID::toString, UUID::fromString, "Expected a UUID");
    UUID id = UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
    roundTrip(uuid, id, id.toString());
    refused(uuid, "1b4e", "Expected a UUID");
    refused(uuid, "", Format.REQUIRED);
    Format<Integer> port = Format.INTEGER.where(p -> p >= 1 && p <= 65535, "1 to 65535");
    roundTrip(port, 5432, "5432");
    refused(port, "0", "1 to 65535");
    refused(port, "x", "Not a whole number");
    assertFalse(port.allows("x"));
    assertTrue(port.optional().parse("").valid());
  }

  record Choice(int id, String label) {}

  @Test
  void choicesWithOneLabelForTwoValuesAreRefused() {
    var e = assertThrows(
        IllegalArgumentException.class,
        () -> Format.choice(List.of(new Choice(1, "Open"), new Choice(2, "Open")), Choice::label));
    assertEquals(
        "Two choices have the label \"Open\": Choice[id=1, label=Open] and Choice[id=2, label=Open]",
        e.getMessage());
    Format<Choice> same =
        Format.choice(List.of(new Choice(1, "Open"), new Choice(1, "Open")), Choice::label);
    assertEquals(new Choice(1, "Open"), same.parse("Open").value(), "equal values are one");
  }

  @Test
  void aDecimalWhoseExponentWouldWriteTooManyZerosIsRefused() {
    for (String text : List.of("1e2147483647", "1e5000", "1e-5000", "1e99999999999")) {
      assertFalse(Format.DECIMAL.parse(text).valid(), text);
      assertFalse(Format.DECIMAL.allows(text), text);
    }
    String most = "1e" + Format.MAX_ZEROS;
    BigDecimal value = Format.DECIMAL.parse(most).value();
    assertEquals(Format.MAX_ZEROS + 1, Format.DECIMAL.text(value).length());
    assertTrue(Format.DECIMAL.allows(most));
    assertTrue(Format.DECIMAL.allows("1e"));
    String digits = "7".repeat(20_000) + ".5";
    assertEquals(digits, Format.DECIMAL.text(Format.DECIMAL.parse(digits).value()));
    String whole = "9".repeat(20_000);
    assertEquals(whole, Format.BIG_INTEGER.text(Format.BIG_INTEGER.parse(whole).value()));
  }

  @Test
  void aNumberOfAMillionDigitsIsRefusedUnread() {
    String digits = "9".repeat(1_000_000);
    long start = System.nanoTime();
    for (Format<?> format :
        List.of(Format.INTEGER, Format.LONG, Format.BIG_INTEGER, Format.DECIMAL)) {
      assertEquals(
          "Expected a number of at most 100000 characters", format.parse(digits).error());
      assertFalse(format.allows(digits));
    }
    // Reading it would take seconds; the limit of a busy machine is far from both.
    assertTrue(System.nanoTime() - start < 2_000_000_000L, "refused without reading the digits");
  }

  @Test
  void sizesAreWrittenInDecimalUnitsWithOneDecimalBelowTen() {
    Object[][] table = {
      {0L, "0 B"},
      {817L, "817 B"},
      {999L, "999 B"},
      {1000L, "1.0 kB"},
      {1049L, "1.0 kB"},
      {1050L, "1.1 kB"}, // half up
      {9949L, "9.9 kB"},
      {9950L, "10 kB"},
      {10_499L, "10 kB"},
      {10_500L, "11 kB"},
      {817_000L, "817 kB"},
      {999_499L, "999 kB"},
      {999_500L, "1.0 MB"}, // never 1000 kB
      {999_999L, "1.0 MB"},
      {1_234_567L, "1.2 MB"},
      {12_000_000L, "12 MB"},
      {999_999_999L, "1.0 GB"},
      {1_000_000_000_000L, "1.0 TB"},
      {999_500_000_000_000L, "1.0 PB"},
      {999_500_000_000_000_000L, "1.0 EB"},
      {Long.MAX_VALUE, "9.2 EB"},
      {-5L, "-5 B"},
    };
    for (Object[] row : table) {
      assertEquals(row[1], Format.BYTES.text((Long) row[0]), String.valueOf(row[0]));
    }
    assertEquals(DataType.INTEGER, Format.BYTES.type());
  }

  @Test
  void sizesAreReadInEitherUnitsAndAnyCase() {
    Object[][] table = {
      {"1.2 MB", 1_200_000L},
      {"817 kB", 817_000L},
      {"817 B", 817L},
      {"1200000", 1_200_000L},
      {" 12mb ", 12_000_000L},
      {"12 KB", 12_000L},
      {"1 KiB", 1024L},
      {"1.5 mib", 1_572_864L},
      {"4 GiB", 4_294_967_296L},
      {"1 TiB", 1L << 40},
      {"1 PiB", 1L << 50},
      {"1 EiB", 1L << 60},
      {".5 kB", 500L},
      {"1.5 B", 2L}, // a fraction of a byte, half up
      {"9.2 EB", 9_200_000_000_000_000_000L},
      {"9223372036854775807", Long.MAX_VALUE},
    };
    for (Object[] row : table) {
      assertEquals(row[1], Format.BYTES.parse((String) row[0]).value(), (String) row[0]);
    }
    String message = "Expected a size such as 817 B, 1.2 MB or 4 GiB";
    for (String text :
        List.of("-5 B", "-1", "1,2 MB", "1 MBB", "1 Mx", "1 k", "kB", "1 iB", "1e3")) {
      refused(Format.BYTES, text, message);
    }
    refused(Format.BYTES, "", Format.REQUIRED);
    refused(
        Format.BYTES, "9223372036854775808", "Expected a size of at most 9223372036854775807 B");
    refused(Format.BYTES, "8 EiB", "Expected a size of at most 9223372036854775807 B");
    assertNull(Format.BYTES.optional().parse("").value());
  }

  @Test
  void timesOfAPrecisionAlwaysHaveItsDigits() {
    LocalTime nine = LocalTime.of(9, 0);
    LocalTime five = LocalTime.of(9, 0, 5, 123_456_789);
    assertEquals("09:00", Format.TIME.text(nine), "the plain format leaves out zero seconds");
    assertEquals("09:00:00", Format.time(0).text(nine));
    assertEquals("09:00:05", Format.time(0).text(five), "cut, not rounded");
    assertEquals("09:00:00.000", Format.time(3).text(nine));
    assertEquals("09:00:05.123", Format.time(3).text(five));
    assertEquals("09:00:05.123456789", Format.time(9).text(five));
    assertEquals("23:59:59", Format.time(0).text(LocalTime.MAX), "never up to the next day");
    LocalDateTime d = LocalDateTime.of(LocalDate.of(2026, 1, 1), nine);
    assertEquals("2026-01-01 09:00:00", Format.dateTime(0).text(d));
    assertEquals("2026-01-01 09:00:00.00", Format.dateTime(2).text(d));
    OffsetDateTime utc = OffsetDateTime.of(d, ZoneOffset.UTC);
    OffsetDateTime india = OffsetDateTime.of(d, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime west = OffsetDateTime.of(d, ZoneOffset.ofHours(-3));
    // Each spelling of an offset, for UTC, an offset with minutes and one without.
    Object[][] table = {
      {Format.Offset.Z, utc, "2026-01-01 09:00:00Z"},
      {Format.Offset.Z, india, "2026-01-01 09:00:00+05:30"},
      {Format.Offset.Z, west, "2026-01-01 09:00:00-03:00"},
      {Format.Offset.COLONS, utc, "2026-01-01 09:00:00+00:00"},
      {Format.Offset.COLONS, india, "2026-01-01 09:00:00+05:30"},
      {Format.Offset.COLONS, west, "2026-01-01 09:00:00-03:00"},
      {Format.Offset.SHORT, utc, "2026-01-01 09:00:00+00"},
      {Format.Offset.SHORT, india, "2026-01-01 09:00:00+05:30"},
      {Format.Offset.SHORT, west, "2026-01-01 09:00:00-03"},
    };
    for (Object[] row : table) {
      roundTrip(Format.offsetDateTime(0, (Format.Offset) row[0]), (OffsetDateTime) row[1], (String)
          row[2]);
    }
    assertEquals(
        "2026-01-01 09:00:05.120+00",
        Format.offsetDateTime(3, Format.Offset.SHORT)
            .text(utc.withSecond(5).withNano(120_000_000)));
    assertThrows(IllegalArgumentException.class, () -> Format.time(10));
    assertThrows(IllegalArgumentException.class, () -> Format.dateTime(-1));
    assertEquals(DataType.TIME, Format.time(3).type());
    assertEquals(DataType.DATE_TIME, Format.dateTime(3).type());
    assertEquals(DataType.DATE_TIME, Format.offsetDateTime(3, Format.Offset.Z).type());
  }
}
