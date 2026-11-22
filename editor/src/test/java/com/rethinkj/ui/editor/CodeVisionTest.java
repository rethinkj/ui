package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Code vision entries above lines in {@link CodeEditor}: grouping, alignment and presses. */
class CodeVisionTest {

  private static final String SAMPLE = """
      class Orders {
          int paidTotal() {
              return 0;
          }
      \tint count() {
      \t\treturn 1;
      \t}
      }""";

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Doc {
    String text;
    final CodeEditor.State editor = new CodeEditor.State();
    App<Doc> app;

    Doc(String text) {
      this.text = text;
    }
  }

  private static App<Doc> app(Doc doc) {
    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(
                codeEditor(state.app, state.text, state.editor, null, value -> state.text = value)
                    .key("code")
                    .lines(12)
                    .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void entriesAlignWithTheirLineAndPressesReachTheApp() throws IOException {
    Doc doc = new Doc(SAMPLE);
    AtomicInteger classUsages = new AtomicInteger();
    AtomicInteger methodUsages = new AtomicInteger();
    doc.editor.codeVision(List.of(
        new CodeEditor.Vision(1, null, "3 usages", methodUsages::incrementAndGet),
        new CodeEditor.Vision(0, null, "1 usage", classUsages::incrementAndGet),
        new CodeEditor.Vision(1, Symbol.PERSON, "Ada Lovelace", null),
        new CodeEditor.Vision(4, null, "2 usages", null),
        new CodeEditor.Vision(8, null, "past the end", null)));
    App<Doc> app = app(doc);

    Script.run("""
        size 480 280 scale 2
        frame
        focus #code
        frame
        snap shown
        press role=button label="3 usages"
        frame
        press role=button label="1 usage"
        frame
        """, app, out);

    assertEquals(1, methodUsages.get(), "click on method vision reached handler");
    assertEquals(1, classUsages.get(), "click on class vision reached handler");
    assertEquals("code", app.focused().key(), "clicking code vision keeps focus in editor");

    String shown = Files.readString(out.resolve("shown.tree.txt"));
    assertTrue(shown.contains("el #vision-0 "), shown);
    assertTrue(shown.contains("el #vision-1 "), shown);
    assertTrue(shown.contains("el #vision-4 "), shown);
    assertFalse(shown.contains("past the end"), shown);
    int usagesPos = shown.indexOf("label=\"3 usages\"");
    int authorPos = shown.indexOf("\"Ada Lovelace\"");
    assertTrue(usagesPos > 0 && authorPos > usagesPos, "entries on line 1 keep list order");

    // Indentation alignment: line 0 has 0 indent, line 1 (4 spaces) and line 4 (1 tab = 4 columns)
    // align identically at 4 spaces' width in the code font.
    El field = app.tree().children().getFirst();
    El v0 = findBlock(field, "vision-0");
    El v1 = findBlock(field, "vision-1");
    El v4 = findBlock(field, "vision-4");
    float x0 = v0.children().getFirst().x();
    float x1 = v1.children().getFirst().x();
    float x4 = v4.children().getFirst().x();
    assertEquals(0f, x0, 0.01f, "unindented line starts at 0");
    assertTrue(x1 > 0f, "indented line starts after its indentation");
    assertEquals(x1, x4, 0.01f, "1-tab line aligns with 4-space line");
  }

  private static El findBlock(El field, String key) {
    return field.children().stream()
        .filter(c -> key.equals(c.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No block " + key));
  }
}
