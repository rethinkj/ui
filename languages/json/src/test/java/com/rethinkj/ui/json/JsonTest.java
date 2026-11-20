package com.rethinkj.ui.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The tokens of {@link Json}, line by line, without the native libraries, and the brackets it
 * marks, as a field that laid it out pairs them.
 */
class JsonTest {

  private static final Theme.Code C = new Theme.Code(1, 2, 3, 4, 5, 0, 0, 0, 0, 0, 0, 0, 6);
  private static final Json JSON = new Json(C);

  /**
   * The tokens of the lines as "text:kind" with the kinds 1 to 6 of {@link #C}, line by line, each
   * line from no state, as JSON's lines start.
   */
  private static List<String> tokens(String... lines) {
    List<String> out = new ArrayList<>();
    for (String line : lines) {
      Spans spans = new Spans(line.length());
      assertNull(JSON.line(line, null, spans), line);
      StringBuilder tokens = new StringBuilder();
      for (String range : spans.toString().split("\n")) {
        if (range.isEmpty()) {
          continue;
        }
        String[] parts = range.split(" ");
        String[] at = parts[1].split("-");
        tokens
            .append(tokens.isEmpty() ? "" : " ")
            .append(line, Integer.parseInt(at[0]), Integer.parseInt(at[1]))
            .append(':')
            .append(parts[2]);
      }
      out.add(tokens.toString());
    }
    return out;
  }

  @Test
  void namesAndValues() {
    assertEquals(
        List.of("{:5 \"name\":6 ::5 \"Ada\":2 ,:5 \"tags\":6 ::5 [:5 \"a\":2 ]:5 }:5"),
        tokens("{\"name\": \"Ada\", \"tags\": [\"a\"]}"));
    assertEquals(
        List.of("\"id\":6 ::5 1:3 ,:5", "\"note\":2"),
        tokens("\"id\" : 1,", "\"note\""),
        "a name before white space and a colon; shortcut: one whose colon is on the next line not");
  }

  @Test
  void stringsKeepEscapedQuotes() {
    assertEquals(
        List.of("{:5 \"a\\\"b\":6 ::5 \"c\\\\\":2 ,:5 \"d\":6 ::5 \"\\\"}\":2 }:5"),
        tokens("{\"a\\\"b\": \"c\\\\\", \"d\": \"\\\"}\"}"));
  }

  @Test
  void numbersAndLiterals() {
    assertEquals(
        List.of("[:5 -1.5e10:3 ,:5 0:3 ,:5 2E+3:3 ,:5 0.25:3 ]:5"),
        tokens("[-1.5e10, 0, 2E+3, 0.25]"));
    assertEquals(
        List.of("[:5 true:1 ,:5 false:1 ,:5 null:1 ,:5 ,:5 ]:5"),
        tokens("[true, false, null, nullable, True]"),
        "words that only start like literals, and literals in another case, are none");
  }

  @Test
  void whatIsNotJsonIsColoredAsFarAsItGoes() {
    assertEquals(
        List.of("{:5 ::5 1:3 ,:5 }:5", "{:5 \"a\":6 ::5 \"b, 1]:2"),
        tokens("{name: 1, 'x' @ - .}", "{\"a\": \"b, 1]"),
        "a word that is no literal, other quotes and characters keep the text's color; a string"
            + " without its end goes to the line's");
    String awkward = "{\"a\\\": -1.e+, \"b\\";
    for (int end = 0; end <= awkward.length(); end++) {
      tokens(awkward.substring(0, end));
    }
  }

  /** The pair at the caret in a field that has laid out and highlighted the text. */
  private static int[] matching(Highlighter highlighter, String text, int caret) {
    FieldState state = new FieldState();
    Supplier<El> field = () -> {
      El f = El.text(text).editable(value -> {}).multiline().fieldState(state);
      return highlighter == null ? f : f.highlight(highlighter, highlighter);
    };
    App<Object> app = new App<>(new Object(), model -> field.get());
    app.resize(400, 200, 1);
    app.frame();
    return field.get().matchingBracket(caret);
  }

  @Test
  void bracketsInStringsPairWithNone() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
    assertTrue(JSON.marksBrackets());
    assertNull(JSON.lineComment(), "JSON has no comments");
    String text = "{\"a\": \"}\",\n \"b\": [1]}";
    assertArrayEquals(new int[] {0, 20}, matching(JSON, text, 1), "not the one in the string");
    assertArrayEquals(new int[0], matching(JSON, text, 8), "the one in the string pairs none");
    assertArrayEquals(new int[] {7, 0}, matching(null, text, 8), "without a highlighter: all");
  }
}
