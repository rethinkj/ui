package com.rethinkj.ui.sql;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The kit's {@link CodeEditor} with {@link Sql}, driven by {@code code-editor.txt}, with stored
 * images: every kind of token, line numbers, the caret's line in a marked block on the rail, a
 * marker, a pair of brackets, an error underlined and gone after an edit, and the editor scrolled
 * to its end; and embedded in a card.
 */
class CodeEditorTest {

  static final String QUERY = """
      -- Orders of the north region
      SELECT o.id, c."name", o.total * 1.19 AS gross
      FROM orders o
      JOIN customers c ON c.id = o.customer_id
      WHERE c.region = 'north' AND o.note <> 'it''s'
        /* placed this year,
           not cancelled */
        AND o.created_at >= DATE '2024-01-01'
        AND o.status IN ('paid', 'shipped')
      ORDER BY o.total DESC
      LIMIT 50;

      CREATE FUNCTION gross(n numeric)
      RETURNS numeric AS $$
        SELECT n * 1.19
      $$ LANGUAGE sql;""";

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Query {
    String sql = QUERY;
    final CodeEditor.State editor = new CodeEditor.State();
    int runs; // presses on the marker

    Query() {
      int note = QUERY.indexOf("o.note");
      editor.diagnostics( // as a database would say
          QUERY,
          List.of(new CodeEditor.Diagnostic(
              note,
              note + "o.note".length(),
              CodeEditor.Severity.ERROR,
              "column o.note does not exist")));
      int select = QUERY.indexOf("SELECT");
      editor.block(select, QUERY.indexOf("LIMIT 50;") + 9); // the caret's query
      editor.markers(List.of(new CodeEditor.Marker(
          select, Symbol.PLAY, Icon.Variant.ACCENT, "Run statement", () -> runs++)));
    }
  }

  static App<Query> app(Theme t) {
    Sql sql = new Sql(t.code());
    App<Query> app = new App<>(
        new Query(),
        s -> col()
            .wFull()
            .hFull()
            .padding(12)
            .fill(t.surface().window())
            .child(codeEditor(s.sql, s.editor, sql, v -> s.sql = v)
                .key("query")
                .lines(9)
                .wFull()));
    app.provide(Theme.CURRENT, query -> t);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @ParameterizedTest
  @CsvSource({"dark, 2", "light, 2"})
  void looksAsStored(String theme, int scale) throws IOException {
    App<Query> app = app(theme.equals("dark") ? Theme.DARK : Theme.LIGHT);
    String script = Files.readString(Path.of("src/test/resources/scripts/code-editor.txt"));
    List<Path> files = Script.run("size 440 220 scale " + scale + "\n" + script, app, out);
    Golden.assertSnaps("code-editor-" + theme + "-" + scale + "x-", files, "brackets");
    String brackets = Files.readString(out.resolve("brackets.tree.txt"));
    assertTrue(brackets.contains("el #line-8 "), brackets);
    assertTrue(brackets.contains("el #marker role=button label=\"Run statement\""), "the marker");
    int after = QUERY.indexOf("'shipped')") + "'shipped')".length();
    assertTrue(brackets.contains(" caret " + after + " (9:38)"), brackets);
    assertEquals(
        QUERY.length(),
        app.state().editor.caret(),
        "the editor keeps the caret in the state, at the end after the last snap");
    String scrolled = Files.readString(out.resolve("scrolled.tree.txt"));
    assertTrue(scrolled.contains("el #line-15 "), scrolled);
    int note = QUERY.indexOf("o.note");
    assertTrue(
        brackets.contains(" underline " + note + "-" + (note + 6)), "the error, in the dump");
    assertTrue(brackets.contains(" error=\"column o.note does not exist\""), "and its marker");
    assertTrue(
        Files.readString(out.resolve("tooltip.tree.txt"))
            .contains("role=tooltip label=\"Run statement\""),
        "the marker's tooltip: " + Files.readString(out.resolve("tooltip.tree.txt")));
    assertEquals(1, app.state().runs, "a press on the marker runs it, across frames");
    assertTrue(
        Files.readString(out.resolve("ran.tree.txt")).contains(" focused caret " + after + " "),
        "the editor keeps the focus and the caret");
    String edited = Files.readString(out.resolve("edited.tree.txt"));
    assertTrue(
        !edited.contains("underline") && !edited.contains("error="),
        "an edit takes it away: " + edited);
  }

  @Test
  void aSelectionSetWhileTheEditorIsAwayHoldsWhenItComesBack() throws IOException {
    boolean[] shown = {false};
    App<Query> app = new App<>(
        new Query(),
        s -> col()
            .wFull()
            .child(
                shown[0]
                    ? codeEditor(s.sql, s.editor, null, v -> s.sql = v)
                        .key("query")
                        .lines(3)
                        .wFull()
                    : null));
    app.textRendering(TextRendering.LINUX);
    Script.run("size 420 120\nframe", app, out);
    int limit = QUERY.indexOf("LIMIT");
    app.state().editor.select(limit + 5, limit); // as a tab's that is not shown
    shown[0] = true;
    Script.run("size 420 120\nframe\nadvance 1\nframe\nfocus #query\nframe\nsnap s", app, out);
    String tree = Files.readString(out.resolve("s.tree.txt"));
    assertTrue(tree.contains(" caret " + (limit + 5) + " (11:6) anchor " + limit), tree);
    assertTrue(tree.contains(" lines 9-11/16"), "scrolled into view: " + tree);
  }

  @Test
  void embeddedLooksAsStored() throws IOException {
    Theme t = Theme.DARK;
    Sql sql = new Sql(t.code());
    App<Query> app = new App<>(
        new Query(),
        s -> col()
            .wFull()
            .hFull()
            .padding(12)
            .fill(t.surface().window())
            .child(Card.flush(
                Card.toolbar("orders.sql", null),
                CodeEditor.embedded(codeEditor(s.sql, s.editor, sql, v -> s.sql = v)
                    .key("query")
                    .lines(6)))));
    app.textRendering(TextRendering.LINUX);
    // The pointer gives it the focus: no ring, the hairline along its top in the accent's edge.
    String script =
        "size 440 200 scale 2\nframe\npress #query\nframe\nadvance 1\nframe\nsnap focused\n";
    Golden.assertSnaps("code-editor-embedded-dark-2x-", Script.run(script, app, out), "focused");
  }
}
