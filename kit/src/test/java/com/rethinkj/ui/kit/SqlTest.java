package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.rethinkj.ui.Spans;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tokens of {@link Sql}, line by line with the state the lines before left, and the bracket
 * matching of {@link CodeEditor}; without the native libraries.
 */
class SqlTest {

  private static final Theme.Code C = new Theme.Code(1, 2, 3, 4, 5, 0, 0);
  private static final Sql SQL = new Sql(C);

  /** The tokens of the lines as "text:kind" with the kinds 1 to 5 of {@link #C}, line by line. */
  private static List<String> tokens(String... lines) {
    List<String> out = new ArrayList<>();
    Object state = null;
    for (String line : lines) {
      Spans spans = new Spans(line.length());
      state = SQL.line(line, state, spans);
      StringBuilder tokens = new StringBuilder();
      for (String range : spans.toString().split("\n")) {
        if (range.isEmpty()) {
          continue;
        }
        String[] parts = range.split(" ");
        String[] at = parts[1].split("-");
        tokens.append(tokens.isEmpty() ? "" : " ")
            .append(line, Integer.parseInt(at[0]), Integer.parseInt(at[1]))
            .append(':').append(parts[2]);
      }
      out.add(tokens.toString());
    }
    return out;
  }

  @Test
  void keywordsNumbersAndOperators() {
    assertEquals(List.of("select:1 ,:5 1.5:3 ,:5 .5:3 ,:5 2e-3:3 from:1 (:5 =:5 ):5 ;:5"),
        tokens("select id, 1.5, .5, 2e-3 from t(a = b);"));
    assertEquals(List.of("SELECT:1 ,:5 Order:1 ::5 ::5"),
        tokens("SELECT selected, Order::text_x"), "words that only start like keywords are none");
  }

  @Test
  void stringsWithDoubledQuotesAndQuotedIdentifiers() {
    assertEquals(List.of("WHERE:1 =:5 'it''s':2 AND:1 =:5 '':2"),
        tokens("WHERE \"select\" = 'it''s' AND \"a\"\"b\" = ''"));
    assertEquals(List.of("'one:2", "two'':2", "three':2 ;:5"),
        tokens("'one", "two''", "three';"), "a string across lines");
  }

  @Test
  void dollarQuotedStrings() {
    assertEquals(List.of("AS:1 $fn$ BEGIN 'x' $$:2", "  RETURN 1; -- no:2", "$fn$:2 LANGUAGE:1 ;:5"),
        tokens("AS $fn$ BEGIN 'x' $$", "  RETURN 1; -- no", "$fn$ LANGUAGE plpgsql;"));
    assertEquals(List.of("$$x$$:2 =:5 $1:3"), tokens("$$x$$ = $1"), "a parameter is a number");
  }

  @Test
  void commentsNestAndGoOnAcrossLines() {
    assertEquals(List.of("AS:1 -- x 'y':4"), tokens("AS -- x 'y'"));
    assertEquals(List.of("/* a /* b */:4", "c */:4 SELECT:1"),
        tokens("/* a /* b */", "c */ SELECT"));
    Object state = SQL.line("/* open", null, new Spans(7));
    assertEquals(state, SQL.line("still", state, new Spans(5)), "the same state, equal");
    assertNull(SQL.line("end */", state, new Spans(6)));
  }

  @Test
  void bracketsMatchAcrossNesting() {
    String text = "f(a, (b), [c])";
    assertArrayEquals(new int[] {1, 13}, CodeEditor.matching(text, 2), "after the caret's left");
    assertArrayEquals(new int[] {13, 1}, CodeEditor.matching(text, 14), "before the caret");
    assertArrayEquals(new int[] {5, 7}, CodeEditor.matching(text, 5), "the one after the caret");
    assertArrayEquals(new int[0], CodeEditor.matching(text, 3));
    assertArrayEquals(new int[] {0}, CodeEditor.matching("(a", 0), "no partner");
  }
}
