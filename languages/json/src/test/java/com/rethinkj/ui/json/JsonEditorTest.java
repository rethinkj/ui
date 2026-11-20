package com.rethinkj.ui.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Control+Alt+L in a code editor of {@link Json}, driven by scripts. */
class JsonEditorTest {

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }

  static final class Doc {
    String text;
    final CodeEditor.State editor = new CodeEditor.State();
    int maxLength = -1;
    App<Doc> app;

    Doc(String text) {
      this.text = text;
    }
  }

  private static App<Doc> app(Doc doc) {
    App<Doc> app = new App<>(
        doc,
        s -> El.col()
            .wFull()
            .hFull()
            .padding(12)
            .child(
                CodeEditor.codeEditor(s.app, s.text, s.editor, Json.of(), value -> s.text = value)
                    .key("json")
                    .lines(8)
                    .maxLength(s.maxLength < 0 ? Integer.MAX_VALUE : s.maxLength)
                    .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** Runs the steps and returns the tree of the frame after them. */
  private String run(App<Doc> app, String script) throws IOException {
    Script.run("size 640 280\n" + script + "\nframe\nsnap s", app, out);
    return Files.readString(out.resolve("s.tree.txt"));
  }

  @Test
  void controlAltLFormatsAsOneStepWithTheSelectionAtTheSameTokens() throws IOException {
    String minified = "{\"id\":1,\"tags\":[\"a\",\"b\"]}";
    Doc doc = new Doc(minified);
    App<Doc> app = app(doc);
    run(
        app,
        "frame\nfocus #json\nkey Home control\n" + "key ArrowRight\n".repeat(7) + "type \"2\"\n"
            + "key ArrowRight\n" + "key ArrowRight shift\n".repeat(6));
    String typed = "{\"id\":12,\"tags\":[\"a\",\"b\"]}";
    assertEquals(typed, doc.text);
    run(app, "key L control alt");
    String formatted = """
        {
            "id": 12,
            "tags": [
                "a",
                "b"
            ]
        }""";
    assertEquals(formatted, doc.text);
    int tags = formatted.indexOf("\"tags\"");
    assertEquals(tags, doc.editor.anchor(), "\"tags\" stays selected, without the indentation");
    assertEquals(tags + 6, doc.editor.caret());
    run(app, "key Z control");
    assertEquals(typed, doc.text, "one Control+Z takes the format back, and not the typing");
    run(app, "key Z control");
    assertEquals(minified, doc.text);
  }

  @Test
  void textThatIsNotJsonStaysAndShowsItsProblemUntilItChanges() throws IOException {
    Doc doc = new Doc("{\"a\":1,}");
    App<Doc> app = app(doc);
    String tree = run(app, "frame\nfocus #json\nkey End control\nkey L control alt");
    assertEquals("{\"a\":1,}", doc.text);
    assertEquals(7, doc.editor.caret(), "at the problem's start");
    assertTrue(tree.contains("underline 7-8"), tree);
    assertTrue(tree.contains("role=tooltip label=\"Not expected here\""), tree);
    tree = run(app, "key Backspace");
    assertFalse(tree.contains("underline"), "gone with the text it was about:\n" + tree);
    run(app, "key L control alt");
    assertEquals("{\n    \"a\": 1\n}", doc.text);

    Doc small = new Doc("[1,2]");
    small.maxLength = 8;
    tree = run(app(small), "frame\nfocus #json\nkey L control alt");
    assertEquals("[1,2]", small.text, "not cut at the field's limit");
    assertTrue(tree.contains("role=tooltip label=\"Too large to format\""), tree);
  }

  @Test
  void aCharacterThatComesWithControlAndAltIsTypedAndFormatsNothing() throws IOException {
    Doc doc = new Doc("[\"x\"]");
    App<Doc> app = app(doc);
    StringBuilder steps = new StringBuilder("frame\nfocus #json\nkey Home control\n");
    steps.append("key ArrowRight\n".repeat(3));
    // AltGr on a German keyboard, which comes as Control+Alt on Windows.
    String[][] altGr = {
      {"Q", "@"}, {"E", "€"}, {"7", "{"}, {"8", "["}, {"9", "]"}, {"0", "}"}, {"+", "~"}, {"<", "|"}
    };
    for (String[] key : altGr) {
      steps.append("key ").append(key[0]).append(" control alt\n");
      steps.append("type \"").append(key[1]).append("\"\n");
    }
    run(app, steps.toString());
    assertEquals("[\"x@€{[]}~|\"]", doc.text);
    run(app, "key F control\nkey L control alt");
    assertEquals("[\"x@€{[]}~|\"]", doc.text, "nor does Control+Alt+L in the find bar");
  }
}
