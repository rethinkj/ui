package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bracket fold ranges, fold tracking across edits, and folding by click and keys in {@link CodeEditor}. */
class CodeFoldingTest {

  /** Marks brackets outside single-quoted strings so bracket folds ignore string literals. */
  private static final Highlighter QUOTED = new Highlighter() {
    @Override
    public Object line(String line, Object state, Spans out) {
      boolean inString = false;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        if (c == '\'') {
          inString = !inString;
        } else if (!inString && "()[]{}".indexOf(c) >= 0) {
          out.bracket(i);
        }
      }
      return null;
    }

    @Override
    public boolean marksBrackets() {
      return true;
    }
  };

  private static final String SAMPLE = """
      {
        "id": 1,
        "nested": [
          "a",
          "b"
        ],
        "inline": (1 + 2),
        "quoted": '{'
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

  private static App<Doc> app(Doc doc, Highlighter highlighter) {
    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(codeEditor(
                    state.app, state.text, state.editor, highlighter, value -> state.text = value)
                .key("code")
                .lines(10)
                .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void bracketFoldsPairsMultiLineBracketsAndIgnoresStringsAndSameLinePairs() {
    El marked = El.text(SAMPLE).editable(v -> {}).multiline().highlight("q", QUOTED);
    El.BracketFolds result = marked.bracketFolds();
    assertTrue(result.complete());
    assertEquals(List.of(new El.Fold(0, 7), new El.Fold(2, 4)), lines(result));

    // Without a bracket-marking highlighter, every bracket counts, including the quoted '{'.
    El plain = El.text(SAMPLE).editable(v -> {}).multiline();
    El.BracketFolds plainResult = plain.bracketFolds();
    assertTrue(plainResult.complete());
    // Line 7 has unclosed '{' matched with line 8's '}', leaving line 0's '{' unclosed.
    assertEquals(List.of(new El.Fold(2, 4)), lines(plainResult));
  }

  private static List<El.Fold> lines(El.BracketFolds found) {
    return found.folds().stream().map(El.BracketFold::lines).toList();
  }

  @Test
  void foldsFollowTheirBracketsThroughEditsAndGoWithThem() {
    Doc doc = new Doc("""
        header
        "items": [
          {
            "a": 1
          }
        ]""");
    doc.editor.folds(List.of(new El.Fold(1, 4))); // restored by line
    App<Doc> app = app(doc, QUOTED);
    app.resize(420, 240, 1);
    app.frame();
    assertEquals(List.of(new El.Fold(1, 4)), doc.editor.folds());

    edit(app, doc, "header\n", "added\nheader\n"); // a line above
    assertEquals(List.of(new El.Fold(2, 5)), doc.editor.folds());
    edit(app, doc, "header\n", "header"); // joins the fold's line onto the one above
    assertEquals(List.of(new El.Fold(1, 4)), doc.editor.folds());
    edit(app, doc, ": [", ":\n["); // splits it before the bracket
    assertEquals(List.of(new El.Fold(2, 5)), doc.editor.folds());
    edit(app, doc, "  }\n]", "  }\n"); // the fold's closing bracket goes, and with it the fold
    assertEquals(List.of(), doc.editor.folds());
  }

  @Test
  void foldsSurviveBulkReplacementsAndFormatting() {
    Doc doc = new Doc("""
        foo = 1
        "items": [
          "a",
          "b"
        ]
        foo = 2""");
    doc.editor.folds(List.of(new El.Fold(1, 3)));
    // Formats as a formatter does: other white space between the same characters.
    Highlighter formats = new Highlighter() {
      @Override
      public Object line(String line, Object state, Spans out) {
        return QUOTED.line(line, state, out);
      }

      @Override
      public boolean marksBrackets() {
        return true;
      }

      @Override
      public Function<String, Formatted> formatter() {
        return text ->
            new Formatted(text.replace(" = ", "   =   ").replace("\"a\",", "\"a\",\n"), null);
      }
    };
    App<Doc> app = app(doc, formats);
    app.resize(420, 240, 1);
    app.frame();
    assertEquals(List.of(new El.Fold(1, 3)), doc.editor.folds());

    // Replace All: one edit from the first match to the last, across the fold.
    int from = doc.text.indexOf("foo");
    int to = doc.text.lastIndexOf("foo") + 3;
    String replaced = doc.text.substring(from, to).replace("foo", "updated\nvalue");
    doc.editor.replace(from, to, replaced, replaced.length());
    app.frame();
    app.frame();
    assertEquals(List.of(new El.Fold(2, 4)), doc.editor.folds());

    // Control+Alt+L: one edit of the whole text, with white space changed inside the fold.
    CodeEditor.run(app, doc.editor, CodeEditor.Command.FORMAT);
    app.frame();
    app.frame();
    assertTrue(doc.text.contains("value   =   2"), doc.text);
    assertEquals(List.of(new El.Fold(2, 5)), doc.editor.folds());
  }

  private static void edit(App<Doc> app, Doc doc, String from, String to) {
    assertTrue(doc.text.contains(from), from);
    doc.text = doc.text.replace(from, to);
    app.invalidate();
    app.frame();
  }

  @Test
  void foldAndUnfoldWorkByKeysGutterChevronPillAndInReadOnlyEditor() throws IOException {
    Doc doc = new Doc(SAMPLE);
    App<Doc> app = app(doc, QUOTED);

    // 1. Fold by key (Ctrl+Shift+[) from inside "nested", navigate over it, unfold by Ctrl+Shift+].
    Script.run("""
        size 480 240
        frame
        focus #code
        key ArrowDown
        key ArrowDown
        key ArrowDown
        key [ control shift
        frame
        snap folded
        key ArrowDown
        frame
        snap afterDown
        key ArrowUp
        key ] control shift
        frame
        snap unfolded
        """, app, out);
    assertEquals(List.of(), doc.editor.folds(), "unfolded at the end");
    String foldedTree = Files.readString(out.resolve("folded.tree.txt"));
    assertTrue(foldedTree.contains("el #fold-line-2 role=button label=\"Unfold\""), foldedTree);
    assertTrue(foldedTree.contains("el #fold-2 role=button label=\"Unfold\""), foldedTree);
    // Caret moved to end of line 3 (0-based line 2: '  "nested": [', length 13 -> col 14).
    assertTrue(foldedTree.contains(" (3:14)"), foldedTree);
    // ArrowDown skipped hidden lines 4 and 5 (0-based 3 and 4) onto line 6 (0-based 5: '  ],').
    String afterDown = Files.readString(out.resolve("afterDown.tree.txt"));
    assertTrue(afterDown.contains(" (6:"), afterDown);
    String unfoldedTree = Files.readString(out.resolve("unfolded.tree.txt"));
    assertFalse(unfoldedTree.contains("#fold-2"), unfoldedTree);

    // 2. Fold by clicking the gutter chevron on line 2, then unfold by clicking the placeholder
    // pill.
    Script.run("""
        size 480 240
        frame
        move #line-2
        frame
        press #fold-line-2
        frame
        snap byChevron
        press #fold-2
        frame
        snap byPill
        """, app, out);
    assertTrue(Files.readString(out.resolve("byChevron.tree.txt")).contains("#fold-2"));
    assertFalse(Files.readString(out.resolve("byPill.tree.txt")).contains("#fold-2"));
    assertEquals(List.of(), doc.editor.folds());

    // 3. Read-only editor also supports folding by key.
    Doc ro = new Doc(SAMPLE);
    ro.editor.readOnly(true);
    App<Doc> roApp = new App<>(
        ro,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(codeEditor(state.app, state.text, state.editor, QUOTED, null)
                .key("code")
                .lines(10)
                .wFull()));
    ro.app = roApp;
    roApp.textRendering(TextRendering.LINUX);
    Script.run("""
            size 480 240
            frame
            focus #code
            key ArrowDown
            key ArrowDown
            key ArrowDown
            key [ control shift
            frame
            """, roApp, out);
    assertEquals(List.of(new El.Fold(2, 4)), ro.editor.folds());
  }
}
