package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.CodeEditor.codeEditor;
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
 * The find bar of the kit's {@link CodeEditor} ({@link Find}), driven by {@code find.txt}, with
 * stored images in both themes at scales 1 and 2; and the occurrences of the word at the caret.
 */
class FindTest {

  @TempDir Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Query {
    String sql = CodeEditorTest.QUERY;
    final CodeEditor.State editor = new CodeEditor.State();
  }

  static App<Query> app(Theme t) {
    Sql sql = Sql.of(t);
    App<Query>[] app = new App[1];
    app[0] = new App<>(new Query(), s -> col().wFull().hFull().padding(12)
        .fill(t.surface().window()).child(
            codeEditor(t, app[0], s.sql, s.editor, sql, v -> s.sql = v).key("query").lines(11)
                .wFull()));
    app[0].textRendering(TextRendering.LINUX);
    return app[0];
  }

  @ParameterizedTest
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void looksAsStored(String theme, int scale) throws IOException {
    App<Query> app = app(theme.equals("dark") ? Theme.DARK : Theme.LIGHT);
    String script = Files.readString(Path.of("src/test/resources/scripts/find.txt"));
    List<Path> files = Script.run("size 560 260 scale " + scale + "\n" + script, app, out);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches("find-" + theme + "-" + scale + "x-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))), Files.readString(files.get(i + 1)));
    }
    String open = Files.readString(out.resolve("open.tree.txt"));
    assertTrue(open.contains("role=search"), open);
  }
}
