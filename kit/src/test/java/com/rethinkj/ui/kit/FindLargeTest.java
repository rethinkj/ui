package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.text.TextRendering;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The find bar on 100 000 lines of SQL (about 4 MB): what a search of the whole text costs, and
 * that typing in the bar stays quick while the matches are counted on another thread. It prints
 * the times.
 */
class FindLargeTest {

  private static final int LINES = 100_000;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Query {
    String sql;
    final CodeEditor.State editor = new CodeEditor.State();
    App<Query> app;
  }

  static String text() {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < LINES; i++) {
      out.append(switch (i % 4) {
        case 0 -> "SELECT o.id, o.total * 1.19 AS gross -- " + i;
        case 1 -> "FROM orders o WHERE o.note <> 'it''s' AND";
        case 2 -> "  o.status IN ('paid', 'shipped') /* x */";
        default -> "  AND o.id > " + i + ";";
      });
      if (i + 1 < LINES) {
        out.append('\n');
      }
    }
    return out.toString();
  }

  private static String ms(double[] millis) {
    double[] sorted = millis.clone();
    Arrays.sort(sorted);
    return String.format(Locale.ROOT, "median %.2f ms, most %.2f ms", sorted[sorted.length / 2],
        sorted[sorted.length - 1]);
  }

  interface Step {
    void run() throws InterruptedException;
  }

  private static double time(Step step) {
    long start = System.nanoTime();
    try {
      step.run();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    return (System.nanoTime() - start) / 1e6;
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    for (El overlay : el.overlays()) {
      El found = find(overlay, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static String count(App<?> app) {
    return find(app.tree(), "count").text().string();
  }

  @Test
  void aWholeSearchCostsTensOfMillisecondsAndRunsOffTheAppsThread() throws Exception {
    String text = text();
    // What a search of the whole text costs, warm, for the decision in Find.
    String[][] queries = {{"orders", "plain"}, {"zzzq", "plain, no match"},
        {"o\\.id\\s*>\\s*\\d+", "regex"}, {"o", "whole word, 150 000 matches"}};
    for (String[] q : queries) {
      Search.Query query = new Search.Query(q[0], false, q[1].startsWith("whole"),
          q[1].equals("regex"));
      Pattern p = query.compile();
      double[] millis = new double[5];
      for (int i = 0; i < 7; i++) {
        double took = time(() -> Search.scan(p, text, 0, text.length(), Integer.MAX_VALUE,
            Search.deadline(10_000)));
        if (i >= 2) {
          millis[i - 2] = took;
        }
      }
      System.out.printf(Locale.ROOT, "find, the whole text, %s (%s): %s%n", q[0], q[1],
          ms(millis));
    }

    Query query = new Query();
    query.sql = text;
    App<Query> app = new App<>(query, s -> col().wFull().hFull().padding(12).child(
        codeEditor(Theme.DARK, s.app, s.sql, s.editor, Sql.of(Theme.DARK), v -> s.sql = v)
            .key("query").lines(9).wFull()));
    query.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(640, 240, 1);
    app.frame();
    app.focus(find(app.tree(), "query"));
    app.frame();
    app.keyDown(new Key("F", Key.CONTROL, false));
    app.frame();

    // Each character typed: a jump to the next match and the lines in view, while the count
    // goes to another thread.
    double[] typing = new double[6];
    String typed = "orders";
    for (int i = 0; i < typed.length(); i++) {
      String c = typed.substring(i, i + 1);
      typing[i] = time(() -> {
        app.textInput(c);
        app.frame();
      });
    }
    assertEquals("Searching…", count(app), "counted on another thread");
    double counting = time(() -> {
      app.await(Duration.ofSeconds(10));
      app.frame();
    });
    assertEquals("1 of " + Find.LIMIT + "+", count(app));
    double[] next = new double[20];
    for (int i = 0; i < next.length; i++) {
      next[i] = time(() -> {
        app.keyDown(new Key("Enter"));
        app.frame();
      });
    }
    assertEquals("21 of " + Find.LIMIT + "+", count(app));

    // A match at the very end, from the top: further than a frame's search reaches.
    app.keyDown(new Key("A", Key.CONTROL, false));
    double far = time(() -> {
      app.textInput("> 99999;");
      app.frame();
    });
    double farAnswer = time(() -> {
      app.await(Duration.ofSeconds(10));
      app.frame();
    });
    assertEquals(text.length() - "99999;".length() - 2, query.editor.anchor(),
        "the last line's match selected");
    assertEquals("1 of 1", count(app));

    System.out.printf(Locale.ROOT, "find, a character typed with its frame: %s%n", ms(typing));
    System.out.printf(Locale.ROOT, "find, the count on another thread and its frame: %.2f ms%n",
        counting);
    System.out.printf(Locale.ROOT, "find, Enter to the next match with its frame: %s%n", ms(next));
    System.out.printf(Locale.ROOT,
        "find, a match at the end typed: %.2f ms, its answer and frame %.2f ms%n", far, farAnswer);
    assertTrue(Arrays.stream(typing).max().orElseThrow() < 500, "typing stays quick");
  }
}
