package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Quick fixes on diagnostics via Alt+Enter and the gutter bulb in {@link CodeEditor}. */
class CodeEditorQuickFixTest {

  @TempDir
  Path out;

  static final class Doc {
    String text;
    final CodeEditor.State editor = new CodeEditor.State();
    App<Doc> app;

    Doc(String text) {
      this.text = text;
    }
  }

  @Test
  void quickFixAppliesWithAltEnterUndoesAndSkipsStaleRevision() throws IOException {
    TextLibraries.assumeLoaded();
    String original = "SELECT id\nFROM ordrs\nWHERE status = 'paid';";
    String fixed = "SELECT id\nFROM orders\nWHERE status = 'paid';";
    Doc doc = new Doc(original);
    int from = original.indexOf("ordrs");
    int to = from + "ordrs".length();
    doc.editor.diagnostics(
        original,
        List.of(new CodeEditor.Diagnostic(
            from,
            to,
            CodeEditor.Severity.ERROR,
            "Unknown table",
            List.of(
                new CodeEditor.Fix("Replace with 'orders'", from, to, "orders"),
                new CodeEditor.Fix("Replace with 'order_items'", from, to, "order_items")))));

    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(
                codeEditor(state.app, state.text, state.editor, null, value -> state.text = value)
                    .key("code")
                    .lines(6)
                    .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);

    // 1. Caret inside diagnostic: bulb shows, Alt+Enter opens the menu with the first fix
    // highlighted, Enter applies it as one edit, Ctrl+Z undoes back to original.
    doc.editor.select(from + 2, from + 2);
    Script.run("""
        size 480 240 scale 2
        frame
        focus #code
        frame
        snap bulb
        key Enter alt
        frame
        advance 0.3
        frame
        snap menu
        key Enter
        frame
        advance 0.3
        frame
        """, app, out);

    String bulb = Files.readString(out.resolve("bulb.tree.txt"));
    assertTrue(bulb.contains("el #marker role=button label=\"Show quick fixes\""), bulb);
    String menu = Files.readString(out.resolve("menu.tree.txt"));
    assertTrue(menu.contains("role=menu"), menu);
    assertTrue(menu.contains("label=\"Replace with 'orders'\"") && menu.contains("current"), menu);
    assertEquals(fixed, doc.text, "Enter applied the first quick fix");
    assertEquals(from + "orders".length(), doc.editor.caret(), "caret moved after inserted text");

    Script.run("""
        size 480 240 scale 2
        key Z control
        frame
        """, app, out);
    assertEquals(original, doc.text, "Ctrl+Z undid the quick fix in one step");

    // 2. Edit elsewhere so the diagnostic shifts to a new revision: fixes are no longer offered
    // and Alt+Enter does nothing.
    String edited = "-- note\n" + original;
    doc.text = edited;
    doc.editor.select(edited.indexOf("ordrs") + 2, edited.indexOf("ordrs") + 2);
    app.invalidate();
    Script.run("""
        size 480 240 scale 2
        frame
        focus #code
        frame
        key Enter alt
        frame
        snap stale
        """, app, out);

    String stale = Files.readString(out.resolve("stale.tree.txt"));
    assertFalse(stale.contains("role=menu"), stale);
    assertFalse(stale.contains("label=\"Show quick fixes\""), stale);
    assertEquals(edited, doc.text, "Alt+Enter did not apply a stale fix");
  }

  @Test
  void quickFixSkipsWhenFieldChangesInSameEvent() throws IOException {
    TextLibraries.assumeLoaded();
    String original = "SELECT id\nFROM ordrs\nWHERE status = 'paid';";
    String shifted = "-- note\n" + original;
    Doc doc = new Doc(original);
    int from = original.indexOf("ordrs");
    int to = from + "ordrs".length();
    doc.editor.diagnostics(
        original,
        List.of(new CodeEditor.Diagnostic(
            from,
            to,
            CodeEditor.Severity.ERROR,
            "Unknown table",
            List.of(new CodeEditor.Fix("Replace with 'orders'", from, to, "orders")))));

    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .onAnyKeyDown(key -> {
              if (key.name().equals("Enter")
                  && key.modifiers() == 0
                  && state.text.equals(original)) {
                state.text = shifted;
              }
            })
            .child(
                codeEditor(state.app, state.text, state.editor, null, value -> state.text = value)
                    .key("code")
                    .lines(6)
                    .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);

    doc.editor.select(from + 2, from + 2);
    Script.run("""
        size 480 240 scale 2
        frame
        focus #code
        frame
        key Enter alt
        frame
        advance 0.3
        frame
        key Enter
        frame
        advance 0.3
        frame
        """, app, out);

    assertEquals(shifted, doc.text, "quick fix must not land on text shifted in the same event");
  }
}
