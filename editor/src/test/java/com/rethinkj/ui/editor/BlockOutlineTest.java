package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Text;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link CodeEditor.State#blockOutline}: thin outline around the marked block's text in light and
 * dark themes, off by default, following scroll, folding, and inserted block rows without changing
 * layout.
 */
class BlockOutlineTest {

  // Three statements on lines 1, 3-9, and 11; the marked block is the second statement, starting
  // mid-line after "SET a = 1; ", with indented continuation lines.
  private static final String SCRIPT = """
      SELECT count(*) FROM users;

      SET a = 1; SELECT o.id, c.name
                 FROM orders o
                   JOIN customers c ON c.id = o.cid
                 WHERE c.region = 'north'
                   AND o.status = 'paid'
                 ORDER BY o.total DESC
                 LIMIT 50;

      DELETE FROM audit_log WHERE days > 30;""";

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

  private static App<Doc> app(Doc doc, Theme theme, int lines) {
    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .fill(theme.surface().window())
            .child(
                codeEditor(state.app, state.text, state.editor, null, value -> state.text = value)
                    .key("code")
                    .lines(lines)
                    .wFull()));
    doc.app = app;
    app.provide(Theme.CURRENT, state -> theme);
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  private static List<Text.Box> outlineBoxes(El editor, int color) {
    return editor.text().marks().stream()
        .filter(mark -> !mark.squiggle() && mark.color() == color)
        .map(Text.Mark::box)
        .toList();
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStoredInBothThemes(String name) throws IOException {
    Theme theme = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    Doc doc = new Doc(SCRIPT);
    int from = SCRIPT.indexOf("SELECT o.id");
    int to = SCRIPT.indexOf("LIMIT 50;") + "LIMIT 50;".length();
    doc.editor.block(from, to).blockOutline(true);
    App<Doc> app = app(doc, theme, 11);

    List<Path> files = Script.run("size 500 240 scale 2\nframe\nsnap outline\n", app, out);
    Golden.assertSnaps("block-outline-" + name + "-2x-", files, "outline");
  }

  @Test
  void outlineIsOffByDefaultMatchesBoundsAndDoesNotChangeLayout() {
    Theme theme = Theme.DARK;
    int color = theme.accent().edge();
    Doc doc = new Doc(SCRIPT);
    int from = SCRIPT.indexOf("SELECT o.id");
    int to = SCRIPT.indexOf("LIMIT 50;") + "LIMIT 50;".length();
    doc.editor.block(from, to);
    App<Doc> app = app(doc, theme, 11);
    app.resize(500, 240, 1);
    app.frame();

    El offEditor = doc.editor.editor();
    float offW = offEditor.width();
    float offH = offEditor.height();
    assertEquals(List.of(), outlineBoxes(offEditor, color), "off by default");

    doc.editor.blockOutline(true);
    app.invalidate();
    app.frame();

    El onEditor = doc.editor.editor();
    assertEquals(offW, onEditor.width(), "outline does not change width");
    assertEquals(offH, onEditor.height(), "outline does not change height");

    List<Text.Box> boxes = outlineBoxes(onEditor, color);
    assertEquals(4, boxes.size(), "top, bottom, left, right edges: " + boxes);
    Text.Box top = boxes.get(0);
    Text.Box bottom = boxes.get(1);
    Text.Box left = boxes.get(2);
    Text.Box right = boxes.get(3);

    // Top and bottom span the full horizontal width; left and right stay strictly between them.
    assertEquals(1f, top.height());
    assertEquals(1f, bottom.height());
    assertEquals(1f, left.width());
    assertEquals(1f, right.width());
    assertEquals(top.x(), bottom.x());
    assertEquals(top.width(), bottom.width());
    assertEquals(top.x(), left.x());
    assertEquals(top.x() + top.width() - 1f, right.x());
    assertEquals(top.y() + 1f, left.y());
    assertEquals(bottom.y() - (top.y() + 1f), left.height());
    assertEquals(left.y(), right.y());
    assertEquals(left.height(), right.height());

    // Left starts mid-line at "SELECT o.id" (column 11 > 0).
    assertTrue(top.x() > 0, "mid-line start stays right of col 0: " + top);

    // Vertical span covers 7 lines (lines 3..9, 0-based 2..8).
    List<Text.Line> lines = onEditor.text().lines();
    float lineHeight = lines.get(1).baseline() - lines.get(0).baseline();
    assertEquals(2f * lineHeight, top.y(), 0.01f);
    assertEquals(9f * lineHeight - 1f, bottom.y(), 0.01f);

    // Last line ending before trailing text only counts up to the block's end.
    String trailing = "SELECT id\nFROM orders; SELECT 2 FROM extra_long_table_name;";
    doc.text = trailing;
    doc.editor.block(0, trailing.indexOf("orders;") + "orders;".length());
    app.invalidate();
    app.frame();
    List<Text.Box> trimmed = outlineBoxes(doc.editor.editor(), color);
    assertEquals(4, trimmed.size());
    assertEquals(0f, trimmed.get(0).x(), "column-0 block starts at x=0 without clipping");
    float fullLine2 = doc.editor.editor().text().lines().get(1).width();
    assertTrue(
        trimmed.get(0).width() < fullLine2,
        "right edge stops at block end, before trailing statement on line 2");

    // Turning off or clearing the block removes the outline.
    doc.editor.noBlock();
    app.invalidate();
    app.frame();
    assertEquals(List.of(), outlineBoxes(doc.editor.editor(), color));

    doc.editor.block(0, 9).blockOutline(false);
    app.invalidate();
    app.frame();
    assertEquals(List.of(), outlineBoxes(doc.editor.editor(), color));
  }

  @Test
  void outlineFollowsScrollFoldsAndInsertedBlockRows() {
    Theme theme = Theme.DARK;
    int color = theme.accent().edge();
    String text = """
        -- header
        SELECT id
        FROM (
            SELECT very_long_column_name_inside_folded_subquery
            FROM orders
        ) sub
        WHERE active = true;
        -- footer
        -- extra 1
        -- extra 2
        -- extra 3""";
    int from = text.indexOf("SELECT id");
    int to = text.indexOf("active = true;") + "active = true;".length();

    Doc doc = new Doc(text);
    doc.editor.block(from, to).blockOutline(true);
    App<Doc> app = app(doc, theme, 4);
    app.resize(560, 120, 1);
    app.frame();

    // Baseline: lines 1..6 (6 lines). Bottom is below the 4-line viewport, so omitted.
    List<Text.Box> initial = outlineBoxes(doc.editor.editor(), color);
    assertEquals(3, initial.size(), "top, left, right visible before scroll: " + initial);
    float initialTopY = initial.get(0).y();
    float initialWidth = initial.get(0).width();

    // Scroll down to the block's end so the top leaves view and the bottom enters view.
    doc.editor.select(to, to);
    app.invalidate();
    app.frame();
    app.frame();
    List<Text.Box> scrolled = outlineBoxes(doc.editor.editor(), color);
    assertEquals(3, scrolled.size(), "bottom, left, right visible after scroll: " + scrolled);
    assertTrue(
        scrolled.get(0).y() > initialTopY,
        "bottom edge now in view below where top was: " + scrolled);

    // A widest line scrolled far out of view, beyond the rows laid out around it, keeps the width,
    // also through an edit after the block.
    String tallBlock =
        "SELECT a_very_long_first_column_name\n" + "FROM t\n".repeat(16) + ";\n-- footer";
    Doc tallDoc = new Doc(tallBlock);
    tallDoc.editor.block(0, tallBlock.indexOf(';') + 1).blockOutline(true);
    App<Doc> tallApp = app(tallDoc, theme, 4);
    tallApp.resize(560, 120, 1);
    tallApp.frame();
    float wide = outlineBoxes(tallDoc.editor.editor(), color).get(0).width();
    tallDoc.editor.select(tallBlock.length(), tallBlock.length());
    tallApp.invalidate();
    tallApp.frame();
    tallApp.frame();
    List<Text.Box> far = outlineBoxes(tallDoc.editor.editor(), color);
    assertEquals(wide, far.get(0).width(), 0.01f, "width kept after scrolling: " + far);
    int footer = tallBlock.indexOf("footer");
    tallDoc.editor.replace(footer, footer + 1, "F", 1);
    tallApp.invalidate();
    tallApp.frame();
    List<Text.Box> edited = outlineBoxes(tallDoc.editor.editor(), color);
    assertEquals(wide, edited.get(0).width(), 0.01f, "width kept after an edit: " + edited);

    // Reset to a tall view so the whole block fits, then fold lines 2..5 (the '(' .. ')' body).
    App<Doc> tall = app(doc, theme, 12);
    doc.editor.select(0, 0);
    tall.resize(560, 260, 1);
    tall.frame();
    List<Text.Box> unfolded = outlineBoxes(doc.editor.editor(), color);
    assertEquals(4, unfolded.size());
    float unfoldedHeight = unfolded.get(1).y() + 1f - unfolded.get(0).y();
    float unfoldedWidth = unfolded.get(0).width();
    assertEquals(initialWidth, unfoldedWidth, 0.01f);

    doc.editor.folds(List.of(new El.Fold(2, 4)));
    tall.invalidate();
    tall.frame();
    List<Text.Box> folded = outlineBoxes(doc.editor.editor(), color);
    assertEquals(4, folded.size());
    float foldedHeight = folded.get(1).y() + 1f - folded.get(0).y();
    float foldedWidth = folded.get(0).width();
    List<Text.Line> lines = doc.editor.editor().text().lines();
    float lineHeight = lines.get(1).baseline() - lines.get(0).baseline();
    assertEquals(unfoldedHeight - 2f * lineHeight, foldedHeight, 0.01f, "2 hidden lines skipped");
    assertTrue(
        foldedWidth < unfoldedWidth,
        "wider hidden line inside fold does not widen the outline: " + foldedWidth + " vs "
            + unfoldedWidth);

    // Unfold and insert CodeVision rows: one above the block's first line (line 1, outside the
    // box) and one between two lines of the block (line 6, inside the box).
    doc.editor
        .folds(List.of())
        .codeVision(List.of(
            new CodeEditor.Vision(1, null, "1 reference", null),
            new CodeEditor.Vision(6, null, "predicate", null)));
    tall.invalidate();
    tall.frame();
    List<Text.Box> withVision = outlineBoxes(doc.editor.editor(), color);
    assertEquals(4, withVision.size());
    float visionTop = withVision.get(0).y();
    float visionHeight = withVision.get(1).y() + 1f - visionTop;
    assertTrue(visionTop > unfolded.get(0).y(), "vision row above block pushes block top down");
    assertTrue(
        visionHeight > unfoldedHeight,
        "vision row between block lines lies inside the box vertically");
  }
}
