package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gutter line action in {@link CodeEditor}: hover button, single-line and multi-line selection
 * press, line-range drag in either direction with preview and Escape cancel, and context menu item.
 */
class CodeEditorLineActionTest {

  private static final String SAMPLE = """
      SELECT id, total
      FROM orders
      JOIN customers ON customers.id = orders.customer_id
      WHERE status = 'paid'
      ORDER BY total DESC;""";

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Doc {
    String text;
    final CodeEditor.State editor = new CodeEditor.State();
    final List<CodeEditor.Lines> triggered = new ArrayList<>();
    App<Doc> app;

    Doc(String text) {
      this.text = text;
    }

    void onLineAction(CodeEditor.Lines lines) {
      triggered.add(lines);
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
                    .lines(6)
                    .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void linesValidatesBoundsAndLineActionValidatesLabel() {
    assertEquals(new CodeEditor.Lines(0, 0), new CodeEditor.Lines(0, 0));
    assertEquals(new CodeEditor.Lines(2, 5), new CodeEditor.Lines(2, 5));
    assertThrows(IllegalArgumentException.class, () -> new CodeEditor.Lines(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new CodeEditor.Lines(3, 2));

    CodeEditor.State state = new CodeEditor.State();
    assertThrows(NullPointerException.class, () -> state.lineAction(Symbol.ADD, null, lines -> {}));
    // Null symbol or handler removes the action without requiring a label.
    state.lineAction(null, null, null);
    state.lineAction(Symbol.ADD, null, null);
  }

  @Test
  void hoverPressSelectionDragEscapeAndSlotsWork() throws IOException {
    Doc doc = new Doc(SAMPLE);
    doc.editor.diagnostics(
        SAMPLE, List.of(new CodeEditor.Diagnostic(0, 6, CodeEditor.Severity.ERROR, "Bad syntax")));
    doc.editor.markup(
        "app",
        SAMPLE,
        List.of(new CodeEditor.Marker(0, Symbol.PLAY, Icon.Variant.ACCENT, "Run", null)));
    doc.editor.lineAction(Symbol.ADD, "Add comment", doc::onLineAction);
    App<Doc> app = app(doc);

    // 1. Resting vs hovered: diagnostic and app marker keep their slots before the action button,
    // line numbers keep their x coordinate on hover, hovering shows the button and tooltip, and
    // Tab does not stop on the button.
    Script.run("""
        size 520 180 scale 2
        frame
        snap rest
        focus #code
        frame
        move #line-1
        frame
        move #action-line-1
        frame
        advance 0.6
        frame
        snap hovered
        """, app, out);

    String rest = Files.readString(out.resolve("rest.tree.txt"));
    String hovered = Files.readString(out.resolve("hovered.tree.txt"));
    String cell0 = cell(rest, 0);
    int diagPos = cell0.indexOf("#marker role=img");
    int appMarkerPos = cell0.indexOf("#marker-1 role=img label=\"Run\"");
    int actionPos = cell0.indexOf("#action-line-0 role=button label=\"Add comment\"");
    int numberPos = cell0.indexOf("text \"1\"");
    assertTrue(
        diagPos >= 0 && appMarkerPos > diagPos && actionPos > appMarkerPos && numberPos > actionPos,
        cell0);
    assertEquals(
        lineNumberX(rest, 1), lineNumberX(hovered, 1), "line number does not shift on hover");
    assertTrue(hovered.contains("#action-line-1 role=button label=\"Add comment\""), hovered);
    assertTrue(hovered.contains("role=tooltip label=\"Add comment\""), hovered);
    Pixels restPx = Png.decode(Files.readAllBytes(out.resolve("rest.png")));
    Pixels hoveredPx = Png.decode(Files.readAllBytes(out.resolve("hovered.png")));
    assertFalse(Arrays.equals(restPx.rgba(), hoveredPx.rgba()), "hover shows the action button");

    // 2. Press on a line reports that line, keeps focus on the editor, and the button is not a Tab
    // stop.
    Script.run("""
        size 520 180 scale 2
        press #action-line-1
        frame
        snap afterPress
        """, app, out);
    assertEquals(List.of(new CodeEditor.Lines(1, 1)), doc.triggered);
    String afterPress = Files.readString(out.resolve("afterPress.tree.txt"));
    assertTrue(afterPress.contains("text #code ") && afterPress.contains(" focused "), afterPress);
    assertFalse(findByKey(app.tree(), "action-line-1").isFocusable(), "button is not a Tab stop");

    // 3. Multi-line selection (lines 1..3): pressing inside reports (1, 3), outside reports its
    // own line, and a selection ending at column 0 of line 3 leaves line 3 out.
    doc.triggered.clear();
    int line1 = SAMPLE.indexOf("FROM");
    int line3 = SAMPLE.indexOf("WHERE");
    doc.editor.select(line3 + 5, line1 + 2);
    app.invalidate();
    Script.run("""
        size 520 180 scale 2
        frame
        move #line-2
        frame
        press #action-line-2
        frame
        move #line-0
        frame
        press #action-line-0
        frame
        """, app, out);
    assertEquals(List.of(new CodeEditor.Lines(1, 3), new CodeEditor.Lines(0, 0)), doc.triggered);

    doc.triggered.clear();
    doc.editor.select(line3, line1); // ends at start of line 3 -> lines 1..2
    app.invalidate();
    Script.run("""
        size 520 180 scale 2
        frame
        move #line-2
        frame
        press #action-line-2
        frame
        """, app, out);
    assertEquals(List.of(new CodeEditor.Lines(1, 2)), doc.triggered);

    // 4. Drag downward from line 1 to line 3 previews the range and reports (1, 3) on release.
    doc.triggered.clear();
    doc.editor.select(0, 0);
    app.invalidate();
    List<Path> dragDown = Script.run("""
        size 520 180 scale 2
        frame
        move #line-1
        frame
        down #action-line-1
        frame
        move #line-3
        frame
        snap drag
        up #line-3
        frame
        snap afterDrag
        """, app, out);
    assertEquals(List.of(new CodeEditor.Lines(1, 3)), doc.triggered);
    Golden.assertSnaps("line-action-", dragDown, "drag");
    Pixels dragPx = Png.decode(Files.readAllBytes(out.resolve("drag.png")));
    Pixels afterDragPx = Png.decode(Files.readAllBytes(out.resolve("afterDrag.png")));
    assertFalse(Arrays.equals(dragPx.rgba(), afterDragPx.rgba()), "drag preview clears on release");

    // 5. Drag upward from line 3 to line 1 reports (1, 3).
    doc.triggered.clear();
    Script.run("""
        size 520 180 scale 2
        frame
        move #line-3
        frame
        down #action-line-3
        frame
        move #line-1
        frame
        up #line-1
        frame
        """, app, out);
    assertEquals(List.of(new CodeEditor.Lines(1, 3)), doc.triggered);

    // 6. Escape cancels a drag: reports nothing and clears the preview.
    doc.triggered.clear();
    Script.run("""
        size 520 180 scale 2
        frame
        leave
        frame
        snap clean
        move #line-1
        frame
        down #action-line-1
        frame
        move #line-3
        frame
        key Escape
        frame
        up #line-3
        frame
        leave
        frame
        snap cancelled
        """, app, out);
    assertEquals(List.of(), doc.triggered, "Escape cancels without firing onPress");
    assertTrue(
        Arrays.equals(
            Png.decode(Files.readAllBytes(out.resolve("clean.png"))).rgba(),
            Png.decode(Files.readAllBytes(out.resolve("cancelled.png"))).rgba()),
        "Escape clears the drag preview");

    // 7. Removing the action removes its gutter button.
    doc.editor.lineAction(null, null, null);
    app.invalidate();
    Script.run("size 520 180 scale 2\nframe\nsnap removed", app, out);
    assertFalse(Files.readString(out.resolve("removed.tree.txt")).contains("#action-line-"));
  }

  @Test
  void dragInScrolledEditorReportsTheLinesUnderThePointer() throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      text.append("SELECT ").append(i).append(";\n");
    }
    Doc doc = new Doc(text.toString());
    doc.editor.lineAction(Symbol.ADD, "Add comment", doc::onLineAction);
    doc.editor.select(text.indexOf("SELECT 20;"), text.indexOf("SELECT 20;"));
    App<Doc> app = app(doc);
    Script.run("""
        size 520 180 scale 2
        frame
        move #line-20
        frame
        down #action-line-20
        frame
        move #line-17
        frame
        up #line-17
        frame
        """, app, out);
    assertEquals(List.of(new CodeEditor.Lines(17, 20)), doc.triggered);
  }

  @Test
  void contextMenuItemWorksWithAndWithoutFindBar() throws IOException {
    // Without find bar: the line action still shows in the context menu, with no default shortcut.
    Doc doc = new Doc(SAMPLE);
    doc.editor.findBar(false).lineAction(Symbol.ADD, "Add comment", doc::onLineAction);
    App<Doc> app = app(doc);

    int line2 = SAMPLE.indexOf("JOIN");
    doc.editor.select(line2 + 3, line2 + 3);
    Script.run("""
        size 520 220 scale 2
        frame
        focus #code
        frame
        press #code right
        frame
        advance 0.3
        frame
        snap menuNoFind
        press role=menuitem label="Add comment"
        frame
        """, app, out);

    String menuNoFind = Files.readString(out.resolve("menuNoFind.tree.txt"));
    assertTrue(menuNoFind.contains("role=menuitem label=\"Add comment\""), menuNoFind);
    assertFalse(menuNoFind.contains("label=\"Find\""), menuNoFind);
    assertEquals(List.of(new CodeEditor.Lines(2, 2)), doc.triggered);

    // With find bar and a multi-line selection (lines 1..3): both Find and the line action show.
    doc.triggered.clear();
    doc.editor.findBar(true);
    int line1 = SAMPLE.indexOf("FROM");
    int line3 = SAMPLE.indexOf("WHERE");
    doc.editor.select(line3 + 4, line1 + 1);
    app.invalidate();
    Script.run("""
        size 520 260 scale 2
        frame
        focus #code
        frame
        press #code right
        frame
        advance 0.3
        frame
        snap menuWithFind
        press role=menuitem label="Add comment"
        frame
        """, app, out);

    String menuWithFind = Files.readString(out.resolve("menuWithFind.tree.txt"));
    assertTrue(menuWithFind.contains("role=menuitem label=\"Find\""), menuWithFind);
    assertTrue(menuWithFind.contains("role=menuitem label=\"Add comment\""), menuWithFind);
    assertEquals(List.of(new CodeEditor.Lines(1, 3)), doc.triggered);
  }

  @Test
  void hoverRevealsBothLineActionAndFoldChevronOnFoldableLine() throws IOException {
    String json = "{\n  \"a\": {\n    \"b\": 1\n  }\n}";
    Doc doc = new Doc(json);
    doc.editor.lineAction(Symbol.ADD, "Add comment", doc::onLineAction);
    App<Doc> app = app(doc);

    Script.run("""
        size 480 180 scale 2
        frame
        move #line-1
        frame
        snap both
        """, app, out);

    El root = app.tree();
    El action = findByKey(root, "action-line-1");
    El fold = findByKey(root, "fold-line-1");
    assertNotEquals(null, action, "action button present on foldable line");
    assertNotEquals(null, fold, "fold chevron present on foldable line");
    assertEquals(1f, action.opacity(), "line action visible on hover");
    assertEquals(1f, fold.opacity(), "fold chevron also visible on hover");
  }

  private static El findByKey(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = findByKey(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static String cell(String tree, int index) {
    int from = tree.indexOf("#line-" + index + " ");
    int to = tree.indexOf("#line-" + (index + 1) + " ", from);
    return from < 0 ? "" : tree.substring(from, to < 0 ? tree.length() : to);
  }

  private static int lineNumberX(String tree, int index) {
    String text = "text \"" + (index + 1) + "\" ";
    String line = cell(tree, index)
        .lines()
        .filter(l -> l.contains(text))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing line number " + (index + 1) + ":\n" + tree));
    String[] parts = line.trim().split(" ");
    String coords = parts[parts.length - 2];
    return Integer.parseInt(coords.substring(0, coords.indexOf(',')));
  }
}
