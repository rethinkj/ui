package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.text.TextRendering;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The code editor with {@link Sql} on 100 000 lines: the highlighter is asked about the lines a
 * frame shapes and, after an edit, the lines from the edit down to the view, and a jump far down
 * asks once about each line before it. The test counts the lines asked and prints the times.
 */
class CodeEditorLargeTest {

  private static final int LINES = 100_000;
  // Nine lines in view and three above and below, laid out once or twice in a frame, with the
  // caret's line.
  private static final int PER_FRAME = 2 * (9 + 2 * 3) + 2;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  /** {@link Sql}, counting the lines it is asked about; one object, so the key stays. */
  static final class Counting implements Highlighter {
    final Sql sql = Sql.of(Theme.DARK);
    int asked;

    @Override
    public Object line(String line, Object state, Spans out) {
      asked++;
      return sql.line(line, state, out);
    }
  }

  static final class Query {
    String sql;
    final CodeEditor.State editor = new CodeEditor.State();
    final Counting highlighter = new Counting();
  }

  private static String text() {
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
    return null;
  }

  /** Runs a step a number of times with a frame each; returns the times and checks the work. */
  private static double[] steps(App<Query> app, int times, int perStep, Runnable step) {
    double[] millis = new double[times];
    for (int i = 0; i < times; i++) {
      int before = app.state().highlighter.asked;
      long start = System.nanoTime();
      step.run();
      app.frame();
      millis[i] = (System.nanoTime() - start) / 1e6;
      int asked = app.state().highlighter.asked - before;
      assertTrue(asked <= perStep, "a step asked about " + asked + " lines");
    }
    return millis;
  }

  private static String median(double[] millis) {
    double[] sorted = millis.clone();
    Arrays.sort(sorted);
    return String.format(Locale.ROOT, "%.2f ms", sorted[sorted.length / 2]);
  }

  private static void key(App<?> app, String name, int modifiers) {
    app.keyDown(new Key(name, modifiers, false));
  }

  @Test
  void aHundredThousandLinesOfSqlAskTheHighlighterAboutTheLinesInViewOnly() {
    Query query = new Query();
    query.sql = text();
    App<Query> app = new App<>(query, s -> col().wFull().hFull().padding(12).child(
        codeEditor(Theme.DARK, s.sql, s.editor, s.highlighter, v -> s.sql = v)
            .key("query").lines(9).wFull()));
    app.textRendering(TextRendering.LINUX);
    app.resize(480, 240, 1);
    long start = System.nanoTime();
    app.frame();
    double first = (System.nanoTime() - start) / 1e6;
    assertTrue(query.highlighter.asked <= PER_FRAME, "the first frame: " + query.highlighter.asked);
    El field = find(app.tree(), "query");
    app.focus(field);
    app.frame();

    double[] top = steps(app, 100, PER_FRAME, () -> app.textInput("x"));
    // A comment opened at the top: every line in view changes, and only those are asked again.
    key(app, "Home", Key.CONTROL);
    double[] open = steps(app, 1, PER_FRAME, () -> app.textInput("/*"));
    double[] close = steps(app, 1, PER_FRAME, () -> {
      key(app, "Backspace", 0);
      key(app, "Backspace", 0);
    });

    // Down to the middle: once a pass over the lines before it, then the lines in view only.
    int before = query.highlighter.asked;
    double lineHeight = find(app.tree(), "line-0").height(); // the gutter's cell: a line high
    double[] middle = steps(app, 1, LINES, () -> {
      app.scrollTo(find(app.tree(), "query"), 0, LINES / 2 * lineHeight);
    });
    int pass = query.highlighter.asked - before;
    assertTrue(pass > LINES / 2 - 100 && pass < LINES / 2 + 100, "one pass: " + pass);
    app.pointerDown(200, 60, 1, 0);
    app.pointerUp(200, 60);
    app.frame();
    double[] typing = steps(app, 100, PER_FRAME, () -> app.textInput("y"));
    double[] wheel = steps(app, 100, PER_FRAME, () -> app.wheel(200, 60, 0, 18 * 3));
    before = query.highlighter.asked;
    double[] end = steps(app, 1, LINES, () -> key(app, "End", Key.CONTROL));
    int rest = query.highlighter.asked - before;
    assertTrue(rest < LINES / 2 + 100, "the rest once: " + rest);
    double[] back = steps(app, 1, PER_FRAME, () -> key(app, "Home", Key.CONTROL));
    double[] again = steps(app, 1, PER_FRAME, () -> key(app, "End", Key.CONTROL));

    // An edit at the top forgets the states below it: the end costs a pass again, once.
    steps(app, 1, PER_FRAME, () -> {
      key(app, "Home", Key.CONTROL);
      app.textInput("/*");
    });
    before = query.highlighter.asked;
    double[] endAfterEdit = steps(app, 1, LINES + 10, () -> key(app, "End", Key.CONTROL));
    int whole = query.highlighter.asked - before;
    assertTrue(whole > LINES - 100, "every line once: " + whole);
    String tree = app.tree().dump();
    assertTrue(tree.contains("text #line-99999 \"100000\""), "the last line's cell");
    assertEquals(LINES, app.state().sql.split("\n", -1).length);

    System.out.println("CodeEditorLargeTest, " + LINES + " lines of SQL: the first frame "
        + String.format(Locale.ROOT, "%.2f ms", first) + ", a key typed at the top "
        + median(top) + ", \"/*\" at the top " + median(open) + " and deleted " + median(close)
        + ", a scroll to the middle the first time " + median(middle) + " (" + pass
        + " lines asked), a key typed there " + median(typing) + ", a wheel step "
        + median(wheel) + ", Control+End the first time " + median(end) + " (" + rest
        + " lines), Control+Home " + median(back) + ", Control+End again " + median(again)
        + ", Control+End after an edit at the top " + median(endAfterEdit) + " (" + whole
        + " lines); medians, each with its frame");
  }
}
