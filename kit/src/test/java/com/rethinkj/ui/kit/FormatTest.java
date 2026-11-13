package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    refused(Format.DECIMAL, "1e99999999999", "Not a number");
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
}
