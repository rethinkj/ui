package com.rethinkj.ui.java;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link CodeEditor} with {@link Java}, driven by {@code java.txt}, with stored images in both
 * themes at scale 2 for the documentation site.
 */
class JavaEditorTest {

  static final String SOURCE = """
      package com.example;

      import java.util.List;

      /**
       * Scales a total for display.
       * @param factor multiplier, such as {@code 1.5f}
       */
      public final class Report {
        /// Returns the formatted banner.
        @Override
        public String toString() {
          long limit = 1_000L; // row cap
          int mask = 0xFF;
          char tab = '\\t';
          /* banner
             text */
          return \"\"\"
              Report
              \"\"\" + "ok" + tab + (limit & mask) * 1.5f;
        }
      }""";

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Document {
    String source = SOURCE;
    final CodeEditor.State editor = new CodeEditor.State();
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String theme) throws IOException {
    Theme t = theme.equals("dark") ? Theme.DARK : Theme.LIGHT;
    App<Document> app = new App<>(
        new Document(),
        s -> col()
            .wFull()
            .hFull()
            .padding(12)
            .fill(t.surface().window())
            .child(codeEditor(s.source, s.editor, Java.of(), v -> s.source = v)
                .key("java")
                .lines(22)
                .wFull()));
    app.provide(Theme.CURRENT, doc -> t);
    app.textRendering(TextRendering.LINUX);
    String script = Files.readString(Path.of("src/test/resources/scripts/java.txt"));
    List<Path> files = Script.run(script, app, out);
    Golden.assertSnaps("java-" + theme + "-2x-", files, "code");
  }
}
