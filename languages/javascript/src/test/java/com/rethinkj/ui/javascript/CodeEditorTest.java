package com.rethinkj.ui.javascript;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.editor.Language;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link CodeEditor} highlighting {@link TypeScript} (stored in both dark and light themes at 2x
 * for the docs page) and {@link JavaScript} (stored in dark theme at 2x).
 */
class CodeEditorTest {

  private static final String TYPESCRIPT_SAMPLE = """
      /**
       * Formats an amount with currency.
       * @param amount - Value in minor units.
       * @returns Formatted label.
       */
      export interface Price {
        readonly amount: number;
        currency: string;
      }

      const UNIT_RE = /^([A-Z]{3})[/](\\d+)$/i;

      export function format(price: Price): string {
        const major = price.amount / 1_00; // division
        return `${price.currency} ${major.toFixed(2)}`;
      }""";

  private static final String JAVASCRIPT_SAMPLE = """
      /** Computes a greeting and tag count. */
      const type = "welcome"; // not a JS keyword
      const flags = 0xff_00 | 0b1010;
      const big = 42n;
      const pattern = /hello[/]world/gi;

      export async function greet(name, count) {
        /* multi-line
           block comment */
        const ratio = count / 2;
        return `${type}: ${name} (${ratio + Number(big)})`;
      }""";

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static final class Doc {
    String code;
    final CodeEditor.State editor = new CodeEditor.State();

    Doc(String code) {
      this.code = code;
    }
  }

  private static App<Doc> app(Theme theme, Language language, String source, int lines) {
    App<Doc> app = new App<>(
        new Doc(source),
        s -> col()
            .wFull()
            .hFull()
            .padding(12)
            .fill(theme.surface().window())
            .child(codeEditor(s.code, s.editor, language, v -> s.code = v)
                .key("editor")
                .lines(lines)
                .wFull()));
    app.provide(Theme.CURRENT, doc -> theme);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void typeScriptLooksAsStored(String themeName) throws IOException {
    Theme theme = themeName.equals("dark") ? Theme.DARK : Theme.LIGHT;
    App<Doc> app = app(theme, new TypeScript(theme.code()), TYPESCRIPT_SAMPLE, 16);
    String script = Files.readString(Path.of("src/test/resources/scripts/code-editor.txt"));
    List<Path> files = Script.run("size 500 298 scale 2\n" + script, app, out);
    Golden.assertSnaps("typescript-" + themeName + "-2x-", files, "view");
    String tree = Files.readString(out.resolve("view.tree.txt"));
    assertTrue(tree.contains("el #line-15 "), tree);
  }

  @Test
  void javaScriptLooksAsStored() throws IOException {
    Theme theme = Theme.DARK;
    App<Doc> app = app(theme, new JavaScript(theme.code()), JAVASCRIPT_SAMPLE, 12);
    String script = Files.readString(Path.of("src/test/resources/scripts/code-editor.txt"));
    List<Path> files = Script.run("size 500 234 scale 2\n" + script, app, out);
    Golden.assertSnaps("javascript-dark-2x-", files, "view");
    String tree = Files.readString(out.resolve("view.tree.txt"));
    assertTrue(tree.contains("el #line-11 "), tree);
  }
}
