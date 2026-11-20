package com.rethinkj.ui.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Formatted.Kind;
import com.rethinkj.ui.Formatted.Problem;
import org.junit.jupiter.api.Test;

/** {@link Json#format}: the formatted text, its tokens as written, and the first problem. */
class JsonFormatTest {

  /** The formatted text, which formats to itself. */
  private static String format(String json) {
    Formatted formatted = Json.format(json);
    assertEquals(new Formatted(formatted.text(), null), formatted, json);
    assertEquals(formatted, Json.format(formatted.text()), "formatting is idempotent");
    return formatted.text();
  }

  private static void problem(String json, int from, int to, Kind kind) {
    assertEquals(new Formatted(null, new Problem(from, to, kind)), Json.format(json), json);
  }

  @Test
  void minifiedAndFormatted() {
    String formatted = """
        {
            "id": 1042,
            "customer": "Ada",
            "items": [
                {
                    "sku": "A-17",
                    "quantity": 2
                },
                []
            ],
            "coupon": null,
            "tags": {}
        }""";
    assertEquals(
        formatted,
        format("{\"id\":1042,\"customer\":\"Ada\",\"items\":[{\"sku\":\"A-17\",\"quantity\":2},[]],"
            + "\"coupon\":null,\"tags\":{}}"));
    assertEquals(
        formatted,
        format(formatted.replace("\n", "\r\n\t").replace(": ", " :\t")),
        "spaces, tabs, line feeds and carriage returns between tokens");
    assertEquals("{}", format(" {\n} "));
    assertEquals("[]", format("[]"));
    assertEquals("[\n    [],\n    {}\n]", format("[[],{}]"));
  }

  @Test
  void tokensStayAsWritten() {
    assertEquals(
        "{\n    \"a,b]}\\\"\\\\\\/\\u00e9\": \"{[\\n\",\n    \"a,b]}\\\"\\\\\\/\\u00e9\": 2\n}",
        format("{\"a,b]}\\\"\\\\\\/\\u00e9\":\"{[\\n\",\"a,b]}\\\"\\\\\\/\\u00e9\":2}"),
        "strings with brackets, commas and escapes, and the members in their order, also twice");
    assertEquals(
        "[\n    12345678901234567890.123,\n    -0,\n    1E+2,\n    0.5e-7\n]",
        format("[12345678901234567890.123,-0,1E+2,0.5e-7]"),
        "numbers to the last digit");
    assertEquals(
        "{\n    \"😀\": \"𝄞\u2028\"\n}",
        format("{\"😀\":\"𝄞\u2028\"}"),
        "beyond the BMP, and a line separator in a string, which JSON allows");
    assertEquals("42", format(" 42 "), "a single value");
    assertEquals("\"x\"", format("\"x\""));
    assertEquals("null", format("null"));
  }

  @Test
  void theFirstProblemAndItsPlace() {
    problem("", 0, 0, Kind.UNEXPECTED_END);
    problem("{\"a\":1", 6, 6, Kind.UNEXPECTED_END);
    problem("[1,]", 3, 4, Kind.UNEXPECTED);
    problem("{\"a\" 1}", 5, 6, Kind.UNEXPECTED);
    problem("{\"a\":1,}", 7, 8, Kind.UNEXPECTED);
    problem("{\"a\":True}", 5, 9, Kind.UNEXPECTED);
    problem("{} {}", 3, 4, Kind.UNEXPECTED);
    problem("[\"a\tb\"]", 3, 4, Kind.UNEXPECTED);
    problem("[01]", 1, 3, Kind.INVALID_NUMBER);
    problem("[1.,2]", 1, 3, Kind.INVALID_NUMBER);
    problem("[-Infinity]", 1, 10, Kind.INVALID_NUMBER);
    problem("[\"a\\x\"]", 3, 5, Kind.INVALID_ESCAPE);
    problem("[\"\\u12\"]", 2, 6, Kind.INVALID_ESCAPE);
    problem("[\"\\u00\uFF210\"]", 2, 6, Kind.INVALID_ESCAPE); // a fullwidth A is no hex digit
    problem("[1 \"\\q\"]", 3, 6, Kind.UNEXPECTED); // the missing comma before the bad escape
    problem("[\"abc", 1, 5, Kind.UNCLOSED_STRING);
    problem("[\"abc\n\"]", 1, 5, Kind.UNCLOSED_STRING);
    problem("[\"😀\",😀]", 6, 8, Kind.UNEXPECTED);
    problem("{\r\n\"a\": x}", 7, 8, Kind.UNEXPECTED);
  }

  @Test
  void anyDepthNeedsNoDeeperStack() {
    int depth = 100_000;
    problem("[".repeat(depth), depth, depth, Kind.UNEXPECTED_END);
    problem("{\"a\":".repeat(depth), 5 * depth, 5 * depth, Kind.UNEXPECTED_END);
    problem("[".repeat(depth) + "]".repeat(depth), 0, 0, Kind.TOO_LARGE);
    problem(
        "[".repeat(depth) + "]".repeat(depth - 1) + "}", 2 * depth - 1, 2 * depth, Kind.UNEXPECTED);
  }
}
