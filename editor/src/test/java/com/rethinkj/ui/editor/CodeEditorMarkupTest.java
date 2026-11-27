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
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.TextEdit;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
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

  @Test
  void textEditsDropsALineStartMarkerWithItsRemovedLines() {
    // Line-start marker mapping drops a point at the start of removed whole lines while keeping
    // plain point mapping unchanged and tracking intermediate strings across chains.
    TextEdits deleteLine = TextEdits.between("alpha\nbeta\n", "beta\n");
    assertEquals(-1, deleteLine.mapLineStart(0), "line-start marker of deleted line goes");
    assertEquals(0, deleteLine.mapLineStart(6), "next line's marker moves up");
    assertEquals(0, deleteLine.mapPoint(0), "plain point mapping stays as before");
    assertEquals(0, TextEdits.between("alpha\n", "ha\n").mapLineStart(0), "partial delete keeps");
    assertEquals(1, TextEdits.between("ab\ncd", "acd").mapLineStart(1), "join of lines keeps");
    TextEdits touching = TextEdits.fromEdits(
        " \nnext", List.of(new TextEdit(0, 1, ""), new TextEdit(0, 1, "")), "next");
    assertEquals(-1, touching.mapLineStart(0), "touching deletions remove the line together");
    TextEdits chained =
        TextEdits.fromEdits("a\nb\n", List.of(new TextEdit(2, 4, ""), new TextEdit(0, 2, "")), "");
    assertEquals(-1, chained.mapLineStart(0), "chained delete drops first line's marker");
    assertEquals(-1, chained.mapLineStart(2), "chained delete drops second line's marker");
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
  void changeBarDrawsDeletionCapsOverBarAndBothCapsOnOneLine() throws IOException {
    TextLibraries.assumeLoaded();
    String text = "alpha\nbeta\ngamma\ndelta";
    Doc doc = new Doc(text);
    int betaStart = text.indexOf("beta");
    int betaEnd = text.indexOf("\ngamma");
    int gammaStart = betaEnd + 1;
    int gammaEnd = text.indexOf("\ndelta");
    int deltaStart = gammaEnd + 1;
    int deltaEnd = text.length();
    doc.editor.markup(
        "vcs",
        text,
        List.of(
            // Line 0: ADDED bar plus top DELETED cap.
            new CodeEditor.Change(0, 5, CodeEditor.Change.Kind.ADDED),
            new CodeEditor.Change(0, 0, CodeEditor.Change.Kind.DELETED),
            // Line 1: MODIFIED bar plus bottom DELETED cap.
            new CodeEditor.Change(betaStart, betaEnd, CodeEditor.Change.Kind.MODIFIED),
            new CodeEditor.Change(betaEnd, betaEnd, CodeEditor.Change.Kind.DELETED),
            // Line 2: ADDED bar plus both top and bottom DELETED caps.
            new CodeEditor.Change(gammaStart, gammaEnd, CodeEditor.Change.Kind.ADDED),
            new CodeEditor.Change(gammaStart, gammaStart, CodeEditor.Change.Kind.DELETED),
            new CodeEditor.Change(gammaEnd, gammaEnd, CodeEditor.Change.Kind.DELETED),
            // Line 3 (last line without final '\n'): two DELETED caps, no bar.
            new CodeEditor.Change(deltaStart, deltaStart, CodeEditor.Change.Kind.DELETED),
            new CodeEditor.Change(deltaEnd, deltaEnd, CodeEditor.Change.Kind.DELETED)));

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

    Script.run("size 480 240 scale 2\nframe\nsnap caps", app, out);
    String tree = Files.readString(out.resolve("caps.tree.txt"));

    // Line 0: full bar + top cap at the cell's top, no bottom cap.
    assertEquals("2x16", change(tree, 0), tree);
    assertEquals("2x4", elementSize(tree, 0, "change-top"), tree);
    assertNull(elementSize(tree, 0, "change-bottom"), tree);
    assertEquals(elementY(tree, 0, "change"), elementY(tree, 0, "change-top"), tree);

    // Line 1: full bar + bottom cap at the cell's bottom, no top cap.
    assertEquals("2x16", change(tree, 1), tree);
    assertNull(elementSize(tree, 1, "change-top"), tree);
    assertEquals("2x4", elementSize(tree, 1, "change-bottom"), tree);
    assertEquals(elementY(tree, 1, "change") + 12, elementY(tree, 1, "change-bottom"), tree);

    // Line 2: full bar + both caps at top and bottom.
    assertEquals("2x16", change(tree, 2), tree);
    assertEquals("2x4", elementSize(tree, 2, "change-top"), tree);
    assertEquals("2x4", elementSize(tree, 2, "change-bottom"), tree);
    assertEquals(elementY(tree, 2, "change"), elementY(tree, 2, "change-top"), tree);
    assertEquals(elementY(tree, 2, "change") + 12, elementY(tree, 2, "change-bottom"), tree);

    // Line 3: two deletion caps without a bar, aligned to top and bottom of the lane.
    assertEquals("2x16", change(tree, 3), tree);
    assertEquals("2x4", elementSize(tree, 3, "change-top"), tree);
    assertEquals("2x4", elementSize(tree, 3, "change-bottom"), tree);
    assertEquals(elementY(tree, 3, "change"), elementY(tree, 3, "change-top"), tree);
    assertEquals(elementY(tree, 3, "change") + 12, elementY(tree, 3, "change-bottom"), tree);
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

  @Test
  void deletingLineDropsItsLineStartMarkerAndKeepsTheNextLines() throws IOException {
    TextLibraries.assumeLoaded();
    String text = "SELECT id\nSELECT id\nFROM orders;";
    Doc doc = new Doc(text);
    doc.editor.markup(
        "app",
        text,
        List.of(
            new CodeEditor.Marker(0, Symbol.PLAY, Icon.Variant.ACCENT, "First marker", null),
            new CodeEditor.Marker(10, Symbol.RECORD, Icon.Variant.DANGER, "Second marker", null)));
    doc.editor.markup(
        "vcs", text, List.of(new CodeEditor.Change(0, 0, CodeEditor.Change.Kind.DELETED)));

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

    // Ctrl+Shift+K on line 0 drops line 0's marker, moves line 1's marker up to line 0, and keeps
    // non-marker points (such as a DELETED change cap) as before.
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
    String line0 = cell(after, 0);
    assertFalse(line0.contains("First marker"), line0);
    assertTrue(line0.contains("Second marker"), line0);
    assertEquals("2x4", change(after, 0), after);
  }

  @Test
  void sameStringEditBatchMovesMarkers() throws IOException {
    TextLibraries.assumeLoaded();
    String text = "x\nx\n";
    Doc doc = new Doc(text);
    doc.editor.markup(
        "app",
        text,
        List.of(
            new CodeEditor.Marker(0, Symbol.PLAY, Icon.Variant.ACCENT, "Top marker", null),
            new CodeEditor.Marker(2, Symbol.RECORD, Icon.Variant.DANGER, "Bottom marker", null)));

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

    Script.run("size 480 200 scale 2\nframe\nsnap before", app, out);
    String before = Files.readString(out.resolve("before.tree.txt"));
    assertTrue(cell(before, 0).contains("Top marker"), before);
    assertTrue(cell(before, 1).contains("Bottom marker"), before);

    // Insert "x\n" at 0 and delete the last "x\n": string stays "x\nx\n", Top marker moves from 0
    // to 2 (line 1) and Bottom marker on the deleted line goes.
    app.edit(doc.editor.editor(), List.of(new TextEdit(0, 0, "x\n"), new TextEdit(2, 4, "")), 0);
    Script.run("size 480 200 scale 2\nframe\nsnap after", app, out);
    assertEquals("x\nx\n", doc.text);
    String after = Files.readString(out.resolve("after.tree.txt"));
    assertFalse(cell(after, 0).contains("Top marker"), after);
    assertFalse(cell(after, 0).contains("Bottom marker"), after);
    assertTrue(cell(after, 1).contains("Top marker"), after);
    assertFalse(cell(after, 1).contains("Bottom marker"), after);

    // Alt+Down on line 0 replaces the two identical lines by themselves: the moved markers stay.
    Script.run("size 480 200 scale 2\nfocus #code\nkey ArrowDown alt\nframe\nsnap moved", app, out);
    assertEquals("x\nx\n", doc.text);
    String moved = Files.readString(out.resolve("moved.tree.txt"));
    assertFalse(cell(moved, 0).contains("Top marker"), moved);
    assertTrue(cell(moved, 1).contains("Top marker"), moved);
    assertFalse(moved.contains("Bottom marker"), moved);

    // Undoing Alt+Down keeps them; undoing the batch logs no edit and brings back the originals.
    Script.run("size 480 200 scale 2\nkey Z control\nframe\nsnap kept", app, out);
    String kept = Files.readString(out.resolve("kept.tree.txt"));
    assertTrue(cell(kept, 1).contains("Top marker"), kept);
    assertFalse(kept.contains("Bottom marker"), kept);
    Script.run("size 480 200 scale 2\nkey Z control\nframe\nsnap undone", app, out);
    String undone = Files.readString(out.resolve("undone.tree.txt"));
    assertTrue(cell(undone, 0).contains("Top marker"), undone);
    assertTrue(cell(undone, 1).contains("Bottom marker"), undone);
  }

  @Test
  void blockMarksMoveDropUndoMergeAndKeepFieldFocus() throws IOException {
    TextLibraries.assumeLoaded();
    String text = """
        /// Line one of doc
        /// Line two of doc
        void run() {
          first();
          second();
        }""";
    Language docLang = new Language() {
      @Override
      public Object line(String line, Object state, Spans out) {
        return null;
      }

      @Override
      public Object comments(String line, Object state, Out out) {
        if (line.startsWith("///")) {
          out.comment(0, line.length(), true, Boolean.TRUE.equals(state));
          return Boolean.TRUE;
        }
        return null;
      }

      @Override
      public DocComment docComment(String raw) {
        return new DocComment(
            Markdown.parse(raw.replace("///", "").trim()),
            List.of(),
            null,
            List.of(),
            List.of(),
            null);
      }
    };

    Doc doc = new Doc(text);
    String[] reply = {""};
    int docLine0 = 0;
    int header = text.indexOf("void run()");
    int firstLine = text.indexOf("  first();");
    int secondLine = text.indexOf("  second();");
    doc.editor.readerMode(true);
    doc.editor.codeVision(List.of(new CodeEditor.Vision(4, null, "1 usage", null)));
    doc.editor.markup(
        "review",
        text,
        List.of(
            new CodeEditor.Block(
                docLine0, () -> El.row().key("doc-note").child(Label.label("Doc review"))),
            new CodeEditor.Block(
                header, () -> El.row().key("header-note").child(Label.label("Header"))),
            new CodeEditor.Block(
                firstLine,
                () -> El.row()
                    .key("review-card")
                    .child(TextField.textField(reply[0], "Reply", value -> reply[0] = value)
                        .key("reply-field")
                        .w(160))),
            new CodeEditor.Block(
                firstLine, () -> El.row().key("second-note").child(Label.label("Follow-up"))),
            new CodeEditor.Block(
                secondLine, () -> El.row().key("tail-note").child(Label.label("Tail note")))));

    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .child(codeEditor(
                    state.app, state.text, state.editor, docLang, value -> state.text = value)
                .key("code")
                .lines(14)
                .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);

    // 1. Doc comment block placed after rendered doc (lines 0..1), two blocks on line 3 stay
    // distinct and appear before line 4's CodeVision row.
    Script.run("size 520 360 scale 2\nframe\nsnap initial", app, out);
    String initial = Files.readString(out.resolve("initial.tree.txt"));
    assertTrue(initial.contains("#doc-0 "), initial);
    assertTrue(initial.contains("#doc-note "), initial);
    assertTrue(initial.contains("#review-card "), initial);
    assertTrue(initial.contains("#second-note "), initial);
    assertTrue(initial.contains("#vision-4 "), initial);
    assertTrue(initial.contains("#tail-note "), initial);
    int docY = blockY(initial, "doc-0");
    int docNoteY = blockY(initial, "doc-note");
    int cardY = blockY(initial, "review-card");
    int secondNoteY = blockY(initial, "second-note");
    int visionY = blockY(initial, "vision-4");
    assertTrue(docNoteY > docY, "block on line inside rendered doc goes after doc:\n" + initial);
    assertTrue(cardY < secondNoteY, "two blocks on same line keep order:\n" + initial);
    assertTrue(
        secondNoteY < visionY, "mark blocks come before CodeVision of next line:\n" + initial);

    // 2. Kit TextField inside a keyed block keeps focus and text across rebuilds.
    Script.run("""
        size 520 360 scale 2
        focus #reply-field
        frame
        type "looks good"
        frame
        key K control shift
        frame
        """, app, out);
    assertEquals(text, doc.text, "the editor's keys stay out of a field in a block");
    assertEquals("looks good", reply[0], "typed text updated live state");
    assertEquals("reply-field", app.focused().key(), "focus stayed in reply field across rebuilds");
    app.invalidate();
    Script.run("size 520 360 scale 2\nframe\nsnap typed", app, out);
    assertEquals("reply-field", app.focused().key(), "focus survived extra rebuild");
    String typed = Files.readString(out.resolve("typed.tree.txt"));
    assertTrue(typed.contains("\"looks good\""), typed);

    // 3. Folding the method body (lines 2..4) hides blocks on lines 3 and 4 (last hidden line),
    // and shows the one on line 2 below its pill.
    doc.editor.folds(List.of(new El.Fold(2, 4)));
    app.invalidate();
    Script.run("size 520 360 scale 2\nframe\nsnap folded", app, out);
    String folded = Files.readString(out.resolve("folded.tree.txt"));
    assertTrue(folded.contains("#doc-note "), folded);
    assertTrue(folded.contains("#header-note "), folded);
    assertFalse(folded.contains("#review-card "), folded);
    assertFalse(folded.contains("#second-note "), folded);
    assertFalse(
        folded.contains("#tail-note "), "fold hides block on its last hidden line:\n" + folded);
    doc.editor.folds(List.of());
    app.invalidate();

    // 4. Insert a line above line 3: blocks stay below their shifted line.
    doc.editor.select(text.indexOf("void run()"), text.indexOf("void run()"));
    Script.run("""
        size 520 360 scale 2
        frame
        focus #code
        key Enter
        frame
        snap shifted
        """, app, out);
    String shifted = Files.readString(out.resolve("shifted.tree.txt"));
    assertTrue(shifted.contains("#review-card "), shifted);
    int shiftedCardY = blockY(shifted, "review-card");
    int shiftedLine4Y = lineY(shifted, 4);
    int shiftedLine5Y = lineY(shifted, 5);
    assertTrue(
        shiftedCardY > shiftedLine4Y && shiftedCardY < shiftedLine5Y,
        "block stayed between shifted line 4 and line 5:\n" + shifted);

    // 5. Delete the block's line with Ctrl+Shift+K: blocks on it are dropped; Ctrl+Z brings back.
    int shiftedFirst = doc.text.indexOf("  first();");
    doc.editor.select(shiftedFirst, shiftedFirst);
    Script.run("""
        size 520 360 scale 2
        frame
        focus #code
        key K control shift
        frame
        snap deleted
        """, app, out);
    String deleted = Files.readString(out.resolve("deleted.tree.txt"));
    assertFalse(deleted.contains("#review-card "), deleted);
    assertFalse(deleted.contains("#second-note "), deleted);
    assertTrue(deleted.contains("#tail-note "), deleted);

    Script.run("""
        size 520 360 scale 2
        key Z control
        frame
        key Z control
        frame
        snap undone
        """, app, out);
    String undone = Files.readString(out.resolve("undone.tree.txt"));
    assertTrue(undone.contains("#review-card "), undone);
    assertTrue(undone.contains("#second-note "), undone);
    assertTrue(undone.contains("\"looks good\""), undone);
  }

  /** The y coordinate of the block element with key {@code key} in {@code tree}. */
  private static int blockY(String tree, String key) {
    String line = tree.lines()
        .filter(l -> l.contains(" #" + key + " "))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing #" + key + " in:\n" + tree));
    String[] parts = line.trim().split(" ");
    String coords = parts[parts.length - 2];
    return Integer.parseInt(coords.substring(coords.indexOf(',') + 1));
  }

  /** The y coordinate of the gutter cell of line {@code index} in {@code tree}. */
  private static int lineY(String tree, int index) {
    return blockY(tree, "line-" + index);
  }

  /** The tree dump of the gutter cell of line {@code index}. */
  private static String cell(String tree, int index) {
    int from = tree.indexOf("#line-" + index + " ");
    int to = tree.indexOf("#line-" + (index + 1) + " ", from);
    return from < 0 ? "" : tree.substring(from, to < 0 ? tree.length() : to);
  }

  /** The size of the change bar in the cell of line {@code index}, or null without one. */
  private static String change(String tree, int index) {
    return elementSize(tree, index, "change");
  }

  /** The size of element {@code key} in the cell of line {@code index}, or null without one. */
  private static String elementSize(String tree, int index, String key) {
    return cell(tree, index)
        .lines()
        .filter(line -> line.contains("el #" + key + " "))
        .map(line -> line.substring(line.lastIndexOf(' ') + 1))
        .findFirst()
        .orElse(null);
  }

  /** The y coordinate of element {@code key} in the cell of line {@code index}. */
  private static int elementY(String tree, int index, String key) {
    String line = cell(tree, index)
        .lines()
        .filter(l -> l.contains("el #" + key + " "))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("Missing #" + key + " in cell " + index + ":\n" + tree));
    String[] parts = line.trim().split(" ");
    String coords = parts[parts.length - 2];
    return Integer.parseInt(coords.substring(coords.indexOf(',') + 1));
  }
}
