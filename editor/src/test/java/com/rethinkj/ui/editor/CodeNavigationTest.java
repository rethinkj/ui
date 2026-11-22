package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.GermanTexts;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Go to declaration (Ctrl+B, Ctrl+hover and Ctrl+click), find usages (Alt+F7), highlight usages
 * (Ctrl+Shift+F7) and cross-document navigation ({@link CodeEditor.State#onOpen}) in {@link
 * CodeEditor}.
 */
class CodeNavigationTest {

  private static final String SAMPLE = """
      WITH paid_orders AS (
        SELECT id, total
        FROM orders
      )
      SELECT id, total
      FROM paid_orders
      WHERE id IN (SELECT id FROM paid_orders);""";

  private static final CodeEditor.Location SCHEMA_ORDERS =
      new CodeEditor.Location("schema.sql", 14, 20, null);

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Doc {
    String text = SAMPLE;
    final CodeEditor.State editor = new CodeEditor.State();
    boolean readOnly;
    App<Doc> app;
    int declarationCalls;
    CodeEditor.Location opened;
    int parentKeys;

    Doc(boolean wired) {
      if (wired) {
        editor.declaration(this::declarationAt).usages(this::usagesAt).onOpen(loc -> opened = loc);
      }
    }

    String wordAt(int offset) {
      int from = CodeWords.start(text, offset);
      int to = CodeWords.end(text, offset);
      return text.substring(from, to);
    }

    CodeEditor.Location declarationAt(int offset) {
      declarationCalls++;
      return switch (wordAt(offset)) {
        case "paid_orders" -> {
          int from = text.indexOf("paid_orders");
          yield new CodeEditor.Location(from, from + "paid_orders".length());
        }
        case "orders" -> SCHEMA_ORDERS;
        default -> null;
      };
    }

    List<CodeEditor.Location> usagesAt(int offset) {
      if (!wordAt(offset).equals("paid_orders")) {
        return List.of();
      }
      List<CodeEditor.Location> found = new ArrayList<>();
      String name = "paid_orders";
      for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + name.length())) {
        found.add(new CodeEditor.Location(at, at + name.length()));
      }
      found.add(new CodeEditor.Location("report.sql", 8, 19, null));
      return found;
    }
  }

  private static App<Doc> app(Doc doc) {
    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .onKey(k -> {
              if ((k.name().equals("B") && k.modifiers() == Key.CONTROL)
                  || (k.name().equals("F7")
                      && (k.modifiers() == Key.ALT
                          || k.modifiers() == (Key.CONTROL | Key.SHIFT)))) {
                state.parentKeys++;
                return true;
              }
              return false;
            })
            .child(codeEditor(
                    state.app,
                    state.text,
                    state.editor,
                    null,
                    state.readOnly ? null : value -> state.text = value)
                .key("code")
                .lines(8)
                .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(520, 260, 1);
    app.frame();
    return app;
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

  private static float[] offsetPoint(App<?> app, int targetOffset) {
    El editor = findByKey(app.tree(), "code");
    assertNotNull(editor);
    for (float y = 4; y < editor.height() - 4; y += 6) {
      for (float x = 4; x < editor.width() - 4; x += 2) {
        if (editor.textPositionAt(x, y) == targetOffset) {
          return new float[] {editor.x() + x, editor.y() + y};
        }
      }
    }
    throw new AssertionError("offset not found in editor: " + targetOffset);
  }

  private static String decoratedLine(El editor, String text, int lineIndex) {
    int line = 0;
    int start = 0;
    while (line < lineIndex) {
      int next = text.indexOf('\n', start);
      start = next + 1;
      line++;
    }
    int end = text.indexOf('\n', start);
    if (end < 0) {
      end = text.length();
    }
    String slice = text.substring(start, end);
    Spans spans = new Spans(slice.length());
    editor.decorator().line(lineIndex, start, slice, spans);
    return spans.toString();
  }

  @Test
  void goToDeclarationHighlightUsagesFindUsagesAndCrossDocumentOpen() throws IOException {
    Doc doc = new Doc(true);
    App<Doc> app = app(doc);
    int declFrom = SAMPLE.indexOf("paid_orders");
    int secondUsage = SAMPLE.indexOf("paid_orders", declFrom + 1);
    int thirdUsage = SAMPLE.indexOf("paid_orders", secondUsage + 1);
    int ordersAt = SAMPLE.indexOf("FROM orders") + "FROM ".length();

    // 1. Ctrl+B goes to the same-document declaration and reveals it through a fold.
    El editor = findByKey(app.tree(), "code");
    app.focus(editor);
    app.select(editor, secondUsage + 3, secondUsage + 3);
    app.frame();
    Script.run("""
        size 520 260 scale 1
        key B control
        frame
        """, app, out);
    assertEquals(declFrom, doc.editor.caret(), "Ctrl+B moves caret to declaration");
    assertEquals(declFrom, doc.editor.anchor(), "Ctrl+B collapses selection at declaration");

    // 2. Ctrl+hover underlines the hovered word once per word with pointer cursor; Ctrl+click
    // navigates to the declaration and keeps the caret there after pointer release.
    float[] thirdA = offsetPoint(app, thirdUsage + 1);
    float[] thirdB = offsetPoint(app, thirdUsage + 4);
    int callsBefore = doc.declarationCalls;
    app.pointerMove(thirdA[0], thirdA[1], Key.CONTROL);
    app.frame();
    assertEquals(callsBefore + 1, doc.declarationCalls, "provider called once on entering word");
    assertEquals(Cursor.POINTER, app.cursor(), "pointer cursor while Ctrl-hovering navigable word");
    String thirdSpans = decoratedLine(findByKey(app.tree(), "code"), doc.text, 6);
    assertTrue(thirdSpans.contains("underline "), "hovered word is underlined: " + thirdSpans);

    app.pointerMove(thirdB[0], thirdB[1], Key.CONTROL);
    app.frame();
    assertEquals(
        callsBefore + 1, doc.declarationCalls, "provider not called again within the same word");

    // Releasing Control removes the underline and restores the text cursor.
    app.keyUp(new Key("Control", 0, false));
    app.frame();
    assertEquals(Cursor.TEXT, app.cursor(), "text cursor after releasing Control");
    assertFalse(
        decoratedLine(findByKey(app.tree(), "code"), doc.text, 6).contains("underline "),
        "underline cleared when Control released");

    // Ctrl+click without a hover frame before it (down, slight drag, up): caret lands and stays
    // at the declaration.
    app.pointerDown(thirdA[0], thirdA[1], 1, Key.CONTROL);
    app.pointerMove(thirdB[0], thirdB[1], Key.CONTROL);
    app.pointerUp(thirdB[0], thirdB[1]);
    app.frame();
    assertEquals(declFrom, doc.editor.caret(), "Ctrl+click moves caret to declaration");
    assertEquals(
        declFrom, doc.editor.anchor(), "pointer release does not drag-select back to click point");
    app.pointerLeave();
    app.frame();

    // 3. Ctrl+Shift+F7 highlights usages in the "usages" markup layer and scrollbar marks;
    // Escape clears documentation popover, then F8 diagnostic message, then usages.
    doc.editor.documentation(o -> Markdown.parse("CTE `paid_orders`."));
    doc.editor.diagnostics(
        SAMPLE,
        List.of(new CodeEditor.Diagnostic(
            declFrom, declFrom + 11, CodeEditor.Severity.WARNING, "Check CTE")));
    app.invalidate();
    app.frame();
    Script.run("""
        size 520 260 scale 1
        key F7 control shift
        frame
        """, app, out);
    El afterHighlight = findByKey(app.tree(), "code");
    List<Scrollbars.Mark> usageTicks = afterHighlight.scrollMarks().stream()
        .filter(m -> m.color() == Theme.DARK.accent().edge())
        .toList();
    assertEquals(3, usageTicks.size(), "three same-document usage ticks on scrollbar");
    String line5Spans = decoratedLine(afterHighlight, doc.text, 5);
    assertTrue(line5Spans.contains("background "), "usage highlighted in decorator: " + line5Spans);

    // Open F8 message and Ctrl+Q documentation, then verify Escape dismisses in exact order.
    app.select(afterHighlight, 0, 0);
    app.frame();
    Script.run("""
        size 520 260 scale 1
        key F8
        frame
        key Q control
        frame
        advance 0.3
        frame
        snap all-open
        key Escape
        frame
        advance 0.3
        frame
        snap after-doc
        key Escape
        frame
        advance 0.3
        frame
        snap after-f8
        """, app, out);
    String allOpen = Files.readString(out.resolve("all-open.tree.txt"));
    assertTrue(allOpen.contains("#documentation"), allOpen);
    assertTrue(allOpen.contains("role=tooltip label=\"Check CTE\""), allOpen);
    String afterDoc = Files.readString(out.resolve("after-doc.tree.txt"));
    assertFalse(afterDoc.contains("#documentation"), "1st Escape closes documentation");
    assertTrue(afterDoc.contains("role=tooltip label=\"Check CTE\""), "F8 stays after 1st Escape");
    String afterF8 = Files.readString(out.resolve("after-f8.tree.txt"));
    assertFalse(afterF8.contains("role=tooltip label=\"Check CTE\""), "2nd Escape closes F8");
    assertEquals(
        3,
        findByKey(app.tree(), "code").scrollMarks().stream()
            .filter(m -> m.color() == Theme.DARK.accent().edge())
            .count(),
        "usages stay until 3rd Escape");
    Script.run("""
        size 520 260 scale 1
        key Escape
        frame
        """, app, out);
    assertEquals(
        0,
        findByKey(app.tree(), "code").scrollMarks().stream()
            .filter(m -> m.color() == Theme.DARK.accent().edge())
            .count(),
        "3rd Escape clears highlighted usages");

    // 4. Alt+F7 opens the usages menu at the caret; choosing an item moves the caret.
    Script.run("""
        size 520 260 scale 1
        key F7 alt
        frame
        advance 0.3
        frame
        snap usages-menu
        key ArrowDown
        key Enter
        frame
        advance 0.3
        frame
        """, app, out);
    String menu = Files.readString(out.resolve("usages-menu.tree.txt"));
    assertTrue(menu.contains("#usages role=menu label=\"Usages\""), menu);
    assertTrue(menu.contains("label=\"1: WITH paid_orders AS (\""), menu);
    assertTrue(menu.contains("label=\"6: FROM paid_orders\""), menu);
    assertTrue(menu.contains("label=\"report.sql\""), menu);
    assertEquals(secondUsage, doc.editor.caret(), "choosing 2nd usage moves caret there");

    // 5. Location in another document reaches onOpen (both via Ctrl+B and usages menu).
    app.select(findByKey(app.tree(), "code"), ordersAt + 2, ordersAt + 2);
    app.frame();
    Script.run("""
        size 520 260 scale 1
        key B control
        frame
        """, app, out);
    assertEquals(SCHEMA_ORDERS, doc.opened, "other-document declaration delivered to onOpen");

    // 6. Read-only editor with German texts supports navigation; without providers, keys bubble.
    Doc ro = new Doc(true);
    ro.readOnly = true;
    ro.editor.readOnly(true);
    Texts german = new GermanTexts();
    App<Doc> roApp = app(ro).provide(Texts.CURRENT, s -> german);
    roApp.frame();
    El roEditor = findByKey(roApp.tree(), "code");
    roApp.focus(roEditor);
    roApp.select(roEditor, secondUsage + 2, secondUsage + 2);
    roApp.frame();
    Script.run("""
        size 520 260 scale 1
        key F7 alt
        frame
        advance 0.3
        frame
        snap ro-menu
        key Escape
        frame
        key B control
        frame
        """, roApp, out);
    assertTrue(
        Files.readString(out.resolve("ro-menu.tree.txt"))
            .contains("#usages role=menu label=\"Verwendungen\""),
        "German menu label in read-only editor");
    assertEquals(declFrom, ro.editor.caret(), "Ctrl+B works in read-only editor");

    Doc plain = new Doc(false);
    App<Doc> plainApp = app(plain);
    plainApp.focus(findByKey(plainApp.tree(), "code"));
    plainApp.frame();
    Script.run("""
        size 520 260 scale 1
        key B control
        key F7 alt
        key F7 control shift
        frame
        """, plainApp, out);
    assertEquals(3, plain.parentKeys, "navigation keys bubble to parent when providers are unset");
  }
}
