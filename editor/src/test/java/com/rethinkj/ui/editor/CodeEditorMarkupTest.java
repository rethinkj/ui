package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link TextEdits} mapping and {@link CodeEditor.State#markup} layers across edits. */
class CodeEditorMarkupTest {

  @TempDir
  Path out;

  @Test
  void textEditsMapsRangesAndPointsAcrossInsertsFormatsAndBulkReplacements() {
    // Insert at a range's start lands before it; at its end lands after it; range keeps its length.
    TextEdits atStart = TextEdits.between("abc", "aXXbc");
    assertEquals(3, atStart.mapRange(1, 3), "insert at from shifts range right");
    assertEquals(3, atStart.mapPoint(1), "point moves with the text after it");

    TextEdits atEnd = TextEdits.between("abc", "abYYc");
    assertEquals(0, atEnd.mapRange(0, 2), "insert at to leaves range where it is");
    assertEquals(4, atEnd.mapPoint(2), "point at insert moves right");

    // Edit strictly inside a range, or deleting/replacing its characters, drops it.
    TextEdits inside = TextEdits.between("abcd", "abXcd");
    assertEquals(-1, inside.mapRange(1, 3), "insert strictly inside range drops it");
    TextEdits replaced = TextEdits.between("abcd", "aXcd");
    assertEquals(-1, replaced.mapRange(1, 3), "replacing a character of range drops it");
    assertEquals(1, replaced.mapPoint(1), "replacing the character after a point keeps it");
    assertEquals(-1, TextEdits.between("abcd", "aXd").mapPoint(2), "replacing around it drops it");
    assertEquals(2, replaced.mapPoint(2), "point after replaced character stays");

    // Whitespace-only reformat keeps all non-white-space marks at their new positions.
    String compact = "{\"a\":1,\"b\":[2,3]}";
    String formatted = "{\n    \"a\": 1,\n    \"b\": [\n        2,\n        3\n    ]\n}";
    TextEdits reformat = TextEdits.between(compact, formatted);
    int aFrom = compact.indexOf("\"a\"");
    int bFrom = compact.indexOf("\"b\"");
    int threeFrom = compact.indexOf("3");
    assertEquals(formatted.indexOf("\"a\""), reformat.mapRange(aFrom, aFrom + 3));
    assertEquals(formatted.indexOf("\"b\""), reformat.mapRange(bFrom, bFrom + 3));
    assertEquals(formatted.indexOf("3"), reformat.mapRange(threeFrom, threeFrom + 1));
    assertEquals(formatted.indexOf("{"), reformat.mapPoint(0));
    assertEquals(formatted.indexOf("}"), reformat.mapPoint(compact.length() - 1));

    // Two separate replacements (like Replace All) keep a range between them.
    String before = "foo middle foo";
    String after = "quux middle quux";
    TextEdits replaceAll = TextEdits.between(before, after);
    int mid = before.indexOf("middle");
    assertEquals(after.indexOf("middle"), replaceAll.mapRange(mid, mid + "middle".length()));
    assertEquals(-1, replaceAll.mapRange(0, 3), "replaced range is dropped");

    // Many replacements beyond the distance cap still keep what lies between them.
    String keys = "id: 1\n".repeat(150);
    String many = keys + "items: 2\n" + keys;
    TextEdits renamed = TextEdits.between(many, many.replace("id:", "customer_id:"));
    int items = many.indexOf("items");
    int renamedItems = many.replace("id:", "customer_id:").indexOf("items");
    assertEquals(renamedItems, renamed.mapRange(items, items + 5));

    // Consecutive changed lines keep what lies between their replacements.
    String calls = "f(old_id) {\n  if (old_id) { check(old_id);\n".repeat(60);
    String renamedCalls = calls.replace("old_id", "current_customer_id");
    TextEdits consecutive = TextEdits.between(calls, renamedCalls);
    int lastIf = calls.lastIndexOf("if");
    assertEquals(renamedCalls.lastIndexOf("if"), consecutive.mapRange(lastIf, lastIf + 2));
    int check = calls.lastIndexOf("check"); // between two replacements on its line
    assertEquals(renamedCalls.lastIndexOf("check"), consecutive.mapRange(check, check + 5));

    // As many lines removed around a block keep it.
    String obsolete = "-- obsolete\n".repeat(120);
    String block = obsolete + "kept\n" + obsolete;
    TextEdits removed = TextEdits.between(block, "a\n" + "kept\n" + "b\n");
    assertEquals(2, removed.mapRange(block.indexOf("kept"), block.indexOf("kept") + 4));

    // Beyond the Myers distance cap, falls back to one edit across the middle.
    String largeBefore = "HEAD" + "a".repeat(TextEdits.MAX_DISTANCE + 20) + "TAIL";
    String largeAfter = "HEAD" + "b".repeat(TextEdits.MAX_DISTANCE + 20) + "TAIL";
    TextEdits capped = TextEdits.between(largeBefore, largeAfter);
    assertEquals(0, capped.mapRange(0, 4), "common prefix survives");
    assertEquals(-1, capped.mapRange(10, 15), "middle range is dropped");
    int tailBefore = largeBefore.indexOf("TAIL");
    assertEquals(largeAfter.indexOf("TAIL"), capped.mapRange(tailBefore, tailBefore + 4));
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
  void markupLayersMoveWithEditsDropWhenTouchedRestoreOnUndoAndShowTogether() throws IOException {
    TextLibraries.assumeLoaded();
    String textA = "SELECT id\nFROM orders\nWHERE status = 'paid';";
    Doc doc = new Doc(textA);
    int orders = textA.indexOf("orders");
    int id = textA.indexOf("id");
    doc.editor.markup(
        "analysis",
        textA,
        List.of(
            new CodeEditor.Diagnostic(
                orders, orders + "orders".length(), CodeEditor.Severity.ERROR, "Unknown table"),
            new CodeEditor.Highlight(id, id + "id".length())));
    doc.editor.markup(
        "app",
        textA,
        List.of(new CodeEditor.Marker(0, Symbol.PLAY, Icon.Variant.ACCENT, "Run statement", null)));

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

    // 1. Both layers show together: diagnostic squiggle from "analysis" and marker from "app".
    Script.run("size 480 240 scale 2\nframe\nsnap initial", app, out);
    String initial = Files.readString(out.resolve("initial.tree.txt"));
    assertTrue(initial.contains("squiggle " + orders + "-" + (orders + 6)), initial);
    assertTrue(initial.contains("error=\"Unknown table\""), initial);
    assertTrue(initial.contains("el #marker-1 role=img label=\"Run statement\""), initial);

    // 2. Insert a line above: diagnostic and marker both move down by the inserted length.
    String prefix = "-- header\n";
    doc.text = prefix + textA;
    app.invalidate();
    Script.run("size 480 240 scale 2\nframe\nsnap shifted", app, out);
    String shifted = Files.readString(out.resolve("shifted.tree.txt"));
    int shiftedOrders = orders + prefix.length();
    assertTrue(shifted.contains("squiggle " + shiftedOrders + "-" + (shiftedOrders + 6)), shifted);
    assertTrue(
        shifted.matches(
            "(?s).*#line-1 [^\n]*\n\\s+el [^\n]*\n\\s+el #marker-1 role=img label=\"Run statement\".*"),
        shifted);

    // 3. Edit the diagnostic's own characters: diagnostic is dropped, other layer's marker stays.
    doc.text = textA.replace("orders", "ordXrs");
    app.invalidate();
    Script.run("size 480 240 scale 2\nframe\nsnap touched", app, out);
    String touched = Files.readString(out.resolve("touched.tree.txt"));
    assertFalse(touched.contains("squiggle"), touched);
    assertFalse(touched.contains("error="), touched);
    assertTrue(touched.contains("el #marker-1 role=img label=\"Run statement\""), touched);

    // 4. Back to text A (as after undo): the diagnostic comes back at its original range.
    doc.text = new String(textA);
    app.invalidate();
    Script.run("size 480 240 scale 2\nframe\nsnap restored", app, out);
    String restored = Files.readString(out.resolve("restored.tree.txt"));
    assertTrue(restored.contains("squiggle " + orders + "-" + (orders + 6)), restored);
    assertTrue(restored.contains("el #marker-1 role=img label=\"Run statement\""), restored);

    // 5. Clearing one layer leaves the other intact.
    doc.editor.noMarkup("analysis");
    app.invalidate();
    Script.run("size 480 240 scale 2\nframe\nsnap cleared", app, out);
    String cleared = Files.readString(out.resolve("cleared.tree.txt"));
    assertFalse(cleared.contains("squiggle"), cleared);
    assertTrue(cleared.contains("el #marker-1 role=img label=\"Run statement\""), cleared);

    // 6. Positions are in the string as the editor shows it, a carriage return and line feed one.
    doc.editor.markup(
        "crlf",
        "a\r\nfoo",
        List.of(new CodeEditor.Diagnostic(2, 5, CodeEditor.Severity.WARNING, "Unused")));
    doc.text = "a\r\nfoo!";
    app.invalidate();
    Script.run("size 480 240 scale 2\nframe\nsnap crlf", app, out);
    String crlf = Files.readString(out.resolve("crlf.tree.txt"));
    assertTrue(crlf.contains("squiggle 2-5"), crlf);
  }

  @Test
  void gutterShowsMultipleMarkersPerLineAndMovesChangeBarsWithEdits() throws IOException {
    TextLibraries.assumeLoaded();
    String text = "SELECT id\nFROM orders\nWHERE status = 'paid';";
    Doc doc = new Doc(text);
    int fromStart = text.indexOf("FROM");
    int fromEnd = text.indexOf("\nWHERE");
    int whereStart = fromEnd + 1;
    doc.editor.diagnostics(
        text, List.of(new CodeEditor.Diagnostic(0, 6, CodeEditor.Severity.ERROR, "Syntax error")));
    doc.editor.markup(
        "app",
        text,
        List.of(new CodeEditor.Marker(0, Symbol.PLAY, Icon.Variant.ACCENT, "Run statement", null)));
    doc.editor.markers(
        List.of(new CodeEditor.Marker(0, Symbol.RECORD, Icon.Variant.DANGER, "Breakpoint", null)));
    doc.editor.markup(
        "vcs",
        text,
        List.of(
            new CodeEditor.Change(fromStart, fromEnd, CodeEditor.Change.Kind.MODIFIED),
            new CodeEditor.Change(whereStart, whereStart, CodeEditor.Change.Kind.DELETED)));

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

    // Diagnostic plus two app markers (layer first, then State.markers) all show on line 0, and
    // the modified/deleted change bars show on lines 1 and 2.
    Script.run("size 480 240 scale 2\nframe\nsnap before", app, out);
    String before = Files.readString(out.resolve("before.tree.txt"));
    String first = cell(before, 0);
    int error = first.indexOf("el #marker role=img label=\"Error\"");
    int run = first.indexOf("el #marker-1 role=img label=\"Run statement\"");
    int breakpoint = first.indexOf("el #marker-2 role=img label=\"Breakpoint\"");
    assertTrue(error >= 0 && run > error && breakpoint > run, first);
    assertEquals("2x16", change(before, 1), before);
    assertEquals("2x4", change(before, 2), before);

    // Insert a line before: both Change bars move down by one line.
    doc.text = "-- header\n" + text;
    app.invalidate();
    Script.run("size 480 240 scale 2\nframe\nsnap after", app, out);
    String after = Files.readString(out.resolve("after.tree.txt"));
    assertNull(change(after, 1), after);
    assertEquals("2x16", change(after, 2), after);
    assertEquals("2x4", change(after, 3), after);
  }

  @Test
  void inlineDiagnosticsShowMostSevereMessageAfterLineWhenEnabled() throws IOException {
    TextLibraries.assumeLoaded();
    String text = "SELECT id, tax\nFROM ordrs\nWHERE status = 'paid';";
    Doc doc = new Doc(text);
    int id = text.indexOf("id");
    int tax = text.indexOf("tax");
    int from = text.indexOf("FROM");
    int ordrs = text.indexOf("ordrs");
    doc.editor.diagnostics(
        text,
        List.of(
            new CodeEditor.Diagnostic(id, id + 2, CodeEditor.Severity.WARNING, "Unused column"),
            new CodeEditor.Diagnostic(
                tax, tax + 3, CodeEditor.Severity.ERROR, "Unknown column tax"),
            new CodeEditor.Diagnostic(from, from + 4, CodeEditor.Severity.ERROR, ""),
            new CodeEditor.Diagnostic(
                ordrs, ordrs + 5, CodeEditor.Severity.WARNING, "Deprecated table"),
            new CodeEditor.Diagnostic(
                from, from + 4, CodeEditor.Severity.WARNING, "Table alias missing")));

    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(
                codeEditor(state.app, state.text, state.editor, null, value -> state.text = value)
                    .key("code")
                    .lines(5)
                    .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);

    // Off by default: no line-end diagnostic elements.
    Script.run("size 480 180 scale 2\nframe\nsnap off", app, out);
    String off = Files.readString(out.resolve("off.tree.txt"));
    assertFalse(off.contains("#end-"), off);

    // On: ERROR beats an earlier WARNING on line 0; empty ERROR skipped and earlier WARNING wins
    // on line 1; line 2 has no diagnostic.
    doc.editor.inlineDiagnostics(true);
    app.invalidate();
    Script.run("size 480 180 scale 2\nframe\nsnap on", app, out);
    String on = Files.readString(out.resolve("on.tree.txt"));
    assertTrue(on.contains("text #end-0 \"Unknown column tax\""), on);
    assertTrue(on.contains("text #end-1 \"Table alias missing\""), on);
    assertFalse(on.contains("#end-2"), on);
  }

  @Test
  void deletingFirstOfTwoIdenticalLinesDropsItsDiagnosticAndKeepsTheSeconds() throws IOException {
    TextLibraries.assumeLoaded();
    String text = "SELECT id\nSELECT id\nFROM orders;";
    Doc doc = new Doc(text);
    doc.editor.diagnostics(
        text,
        List.of(
            new CodeEditor.Diagnostic(0, 6, CodeEditor.Severity.ERROR, "First line error"),
            new CodeEditor.Diagnostic(10, 16, CodeEditor.Severity.WARNING, "Second line warning")));

    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(
                codeEditor(state.app, state.text, state.editor, null, value -> state.text = value)
                    .key("code")
                    .lines(5)
                    .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);

    // Ctrl+Shift+K on line 0 deletes the first identical line: its diagnostic goes and line 1's
    // diagnostic moves up to line 0.
    Script.run("""
        size 480 200 scale 2
        frame
        focus #code
        key K control shift
        frame
        snap after
        """, app, out);
    assertEquals("SELECT id\nFROM orders;", doc.text);
    String after = Files.readString(out.resolve("after.tree.txt"));
    assertFalse(after.contains("First line error"), after);
    assertTrue(after.contains("warning=\"Second line warning\""), after);
    assertTrue(after.contains("squiggle 0-6"), after);

    // Marks an analysis computed for the string before the delete, which arrive a build later,
    // move by the same delete.
    doc.editor.diagnostics(
        text,
        List.of(
            new CodeEditor.Diagnostic(0, 6, CodeEditor.Severity.ERROR, "Late first"),
            new CodeEditor.Diagnostic(10, 16, CodeEditor.Severity.WARNING, "Late second")));
    app.invalidate();
    Script.run("size 480 200 scale 2\nframe\nsnap late", app, out);
    String late = Files.readString(out.resolve("late.tree.txt"));
    assertFalse(late.contains("Late first"), late);
    assertTrue(late.contains("warning=\"Late second\""), late);
  }

  @Test
  void regexReplaceAllAcrossManyJoinedLinesKeepsDiagnosticsAndFoldsBetweenMatches()
      throws IOException {
    TextLibraries.assumeLoaded();
    // 340 statements (beyond the 1000-line diff cap and 200-step log cap) with a diagnostic and a
    // folded block lying right in the middle between replacements.
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 170; i++) {
      sb.append("SELECT old_id,\n  region_id\nFROM t_").append(i).append(";\n");
    }
    int midLine = 170 * 3;
    int badCol = sb.length() + "WHERE ".length();
    sb.append("WHERE bad_col IN (\n  1,\n  2\n);\n");
    for (int i = 170; i < 340; i++) {
      sb.append("SELECT old_id,\n  region_id\nFROM t_").append(i).append(";\n");
    }
    String initial = sb.toString();
    Doc doc = new Doc(initial);
    doc.editor.diagnostics(
        initial,
        List.of(new CodeEditor.Diagnostic(
            badCol, badCol + "bad_col".length(), CodeEditor.Severity.ERROR, "Unknown column")));
    doc.editor.folds(List.of(new El.Fold(midLine, midLine + 2)));

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

    // Replace All with regex `old_id,\s+region_id` -> `customer_id`, joining two lines per match.
    Script.run("""
        size 640 320 scale 2
        frame
        focus #code
        key H control
        frame
        advance 0.25
        frame
        key A control
        type "old_id,\\\\s+region_id"
        key R alt
        focus #replace
        type "customer_id"
        key Enter alt control
        frame
        key Escape
        frame
        """, app, out);
    assertFalse(doc.text.contains("old_id"), "all 340 matches replaced");
    int shiftedMidLine = midLine - 170;
    assertEquals(
        List.of(new El.Fold(shiftedMidLine, shiftedMidLine + 2)),
        doc.editor.folds(),
        "fold between replacements survived and moved up by 170 lines");

    // Scroll to the middle diagnostic and verify its squiggle and gutter marker survived.
    int shiftedBadCol = doc.text.indexOf("bad_col");
    assertTrue(shiftedBadCol > 0);
    doc.editor.select(shiftedBadCol, shiftedBadCol);
    app.invalidate();
    Script.run("size 640 320 scale 2\nframe\nsnap middle", app, out);
    String middle = Files.readString(out.resolve("middle.tree.txt"));
    assertTrue(middle.contains("error=\"Unknown column\""), middle);
    assertTrue(
        middle.contains("squiggle " + shiftedBadCol + "-" + (shiftedBadCol + "bad_col".length())),
        middle);
  }

  /** The tree dump of the gutter cell of line {@code index}. */
  private static String cell(String tree, int index) {
    int from = tree.indexOf("#line-" + index + " ");
    int to = tree.indexOf("#line-" + (index + 1) + " ", from);
    return from < 0 ? "" : tree.substring(from, to < 0 ? tree.length() : to);
  }

  /** The size of the change bar in the cell of line {@code index}, or null without one. */
  private static String change(String tree, int index) {
    return cell(tree, index)
        .lines()
        .filter(line -> line.contains("el #change "))
        .map(line -> line.substring(line.lastIndexOf(' ') + 1))
        .findFirst()
        .orElse(null);
  }
}
