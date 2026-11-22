package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Inline inlay hints in {@link CodeEditor}: showing parameter and inferred-type hints, and moving {@link El.Hint.Affinity#BEFORE} and {@link El.Hint.Affinity#AFTER} hints across edits in
 * a markup layer.
 */
class InlayHintTest {

  private static final String SAMPLE = """
      var files = scan(root, true);
      var count = files.size();""";

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

  @Test
  void inlayHintsShowAndMoveByAffinityAcrossEdits() throws IOException {
    // Unit check of BEFORE vs AFTER point mapping in TextEdits.
    TextEdits insertAt5 = TextEdits.between("var x=1", "var x/* ok */=1");
    assertEquals(5, insertAt5.mapPointBefore(5), "BEFORE stays before text inserted at its offset");
    assertEquals(13, insertAt5.mapPoint(5), "AFTER moves past text inserted at its offset");
    TextEdits replaceAcross = TextEdits.between("abcdef", "aXef");
    assertEquals(-1, replaceAcross.mapPointBefore(2), "BEFORE inside replaced text is dropped");
    assertEquals(-1, replaceAcross.mapPoint(2), "AFTER inside replaced text is dropped");

    Doc doc = new Doc(SAMPLE);
    int filesEnd = SAMPLE.indexOf("files") + "files".length();
    int rootAt = SAMPLE.indexOf("root");
    int trueAt = SAMPLE.indexOf("true");
    doc.editor.markup(
        "hints",
        SAMPLE,
        List.of(
            new CodeEditor.InlayHint(filesEnd, El.Hint.Affinity.BEFORE, ": List<Path>"),
            new CodeEditor.InlayHint(rootAt, El.Hint.Affinity.AFTER, "folder:"),
            new CodeEditor.InlayHint(trueAt, El.Hint.Affinity.AFTER, "recursive:")));

    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(
                codeEditor(state.app, state.text, state.editor, null, value -> state.text = value)
                    .key("code")
                    .lines(3)
                    .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);

    // 1. The hints show in the tree; the docs example stores their image.
    Script.run("size 540 100 scale 2\nframe\nsnap view", app, out);
    String view = Files.readString(out.resolve("view.tree.txt"));
    assertTrue(view.contains("#hint-" + filesEnd + " label=\": List<Path>\""), view);
    assertTrue(view.contains("#hint-" + rootAt + " label=\"folder:\""), view);
    assertTrue(view.contains("#hint-" + trueAt + " label=\"recursive:\""), view);

    // 2. Insert text right at filesEnd: BEFORE stays at filesEnd, AFTER hints shift right.
    String inserted = "/* all */";
    doc.text = SAMPLE.substring(0, filesEnd) + inserted + SAMPLE.substring(filesEnd);
    app.invalidate();
    Script.run("size 540 140 scale 2\nframe\nsnap edited", app, out);
    String edited = Files.readString(out.resolve("edited.tree.txt"));
    assertTrue(
        edited.contains("#hint-" + filesEnd + " label=\": List<Path>\""),
        "BEFORE hint stayed at filesEnd: " + edited);
    assertTrue(
        edited.contains("#hint-" + (rootAt + inserted.length()) + " label=\"folder:\""),
        "AFTER hint shifted right: " + edited);

    // 3. Replace text strictly across rootAt: "folder:" drops, the others stay.
    doc.text = SAMPLE.replace("(root", "[path");
    app.invalidate();
    Script.run("size 540 140 scale 2\nframe\nsnap dropped", app, out);
    String dropped = Files.readString(out.resolve("dropped.tree.txt"));
    assertTrue(dropped.contains("label=\": List<Path>\""), dropped);
    assertFalse(
        dropped.contains("label=\"folder:\""), "hint inside replaced text dropped: " + dropped);
    assertTrue(dropped.contains("label=\"recursive:\""), dropped);

    // 4. Undo back to the original string restores all original hints.
    doc.text = new String(SAMPLE);
    app.invalidate();
    Script.run("size 540 140 scale 2\nframe\nsnap restored", app, out);
    String restored = Files.readString(out.resolve("restored.tree.txt"));
    assertTrue(restored.contains("#hint-" + rootAt + " label=\"folder:\""), restored);
  }
}
