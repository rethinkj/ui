package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The kit's {@link CodeEditor} with {@link Sql}, driven by {@code code-editor.txt}, with stored
 * images in both themes at scales 1 and 2: every kind of token, line numbers, the caret's line, a
 * pair of brackets, an error underlined, and the editor scrolled to its end.
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

  @TempDir Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Query {
    String sql = QUERY;
    final CodeEditor.State editor = new CodeEditor.State();

    Query() {
      int note = QUERY.indexOf("o.note");
      editor.error(note, note + "o.note".length()); // as a database would say: no such column
    }
  }

  static App<Query> app(Theme t) {
    Sql sql = Sql.of(t);
    App<Query> app = new App<>(new Query(), s -> col().wFull().hFull().padding(12)
        .fill(t.surface().window()).child(
            codeEditor(t, s.sql, s.editor, sql, v -> s.sql = v).key("query").lines(9).wFull()));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @ParameterizedTest
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void looksAsStored(String theme, int scale) throws IOException {
    App<Query> app = app(theme.equals("dark") ? Theme.DARK : Theme.LIGHT);
    String script = Files.readString(Path.of("src/test/resources/scripts/code-editor.txt"));
    List<Path> files = Script.run("size 420 220 scale " + scale + "\n" + script, app, out);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches("code-editor-" + theme + "-" + scale + "x-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))), Files.readString(files.get(i + 1)));
    }
    String brackets = Files.readString(out.resolve("brackets.tree.txt"));
    assertTrue(brackets.contains("text #line-8 \"9\""), brackets);
    int after = QUERY.indexOf("'shipped')") + "'shipped')".length();
    assertTrue(brackets.contains(" caret " + after + " (9:38)"), brackets);
    assertEquals(QUERY.length(), app.state().editor.caret(),
        "the editor keeps the caret in the state, at the end after the last snap");
    String scrolled = Files.readString(out.resolve("scrolled.tree.txt"));
    assertTrue(scrolled.contains("text #line-15 \"16\""), scrolled);
  }
}
