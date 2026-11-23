package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.Symbol;
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
 * Rendered doc comments (reader mode) in {@link CodeEditor}: gutter and key toggling, caret
 * exclusion, reveal on find/go-to/edit, staying raw while edited, inline comments staying raw,
 * copying across rendered blocks, scrolling, and link clicks.
 */
class DocCommentRenderTest {

  private enum CommentState {
    DOC_BLOCK,
    DOC_LINE
  }

  /** Minimal language with {@code /** ... *}{@code /} and {@code ///} doc comments. */
  private static final class Lang implements Language {
    @Override
    public Object line(String line, Object state, Spans out) {
      return null;
    }

    @Override
    public Object comments(String line, Object state, Out out) {
      int n = line.length();
      int i = 0;
      if (state == CommentState.DOC_BLOCK) {
        int close = line.indexOf("*/");
        if (close < 0) {
          out.comment(0, n, true, true);
          return CommentState.DOC_BLOCK;
        }
        out.comment(0, close + 2, true, true);
        i = close + 2;
      } else {
        int indent = 0;
        while (indent < n && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
          indent++;
        }
        if (line.startsWith("///", indent)) {
          out.comment(indent, n, true, state == CommentState.DOC_LINE);
          return CommentState.DOC_LINE;
        }
        i = indent;
      }
      while (i < n) {
        if (line.startsWith("/**", i) && !line.startsWith("/**/", i)) {
          int close = line.indexOf("*/", i + 3);
          if (close < 0) {
            out.comment(i, n, true, false);
            return CommentState.DOC_BLOCK;
          }
          out.comment(i, close + 2, true, false);
          i = close + 2;
        } else {
          i++;
        }
      }
      return null;
    }

    @Override
    public DocComment docComment(String text) {
      StringBuilder body = new StringBuilder();
      List<DocComment.Entry> params = new ArrayList<>();
      String returns = null;
      for (String rawLine : text.split("\n", -1)) {
        String s = rawLine.strip();
        if (s.startsWith("/**")) {
          s = s.substring(3).stripLeading();
        }
        if (s.endsWith("*/")) {
          s = s.substring(0, s.length() - 2).stripTrailing();
        }
        if (s.startsWith("///")) {
          s = s.substring(3).stripLeading();
        } else if (s.startsWith("*")) {
          s = s.substring(1).stripLeading();
        }
        if (s.isEmpty()) {
          continue;
        }
        if (s.startsWith("@param ")) {
          String rest = s.substring(7).strip();
          int sp = rest.indexOf(' ');
          String name = sp < 0 ? rest : rest.substring(0, sp);
          String desc = sp < 0 ? "" : rest.substring(sp + 1).strip();
          params.add(
              new DocComment.Entry(name, null, desc.isEmpty() ? null : Markdown.parse(desc)));
        } else if (s.startsWith("@return ")) {
          returns = s.substring(8).strip();
        } else {
          if (!body.isEmpty()) {
            body.append('\n');
          }
          body.append(s);
        }
      }
      return new DocComment(
          body.isEmpty() ? null : Markdown.parse(body.toString()),
          params,
          returns == null ? null : Markdown.parse(returns),
          List.of(),
          List.of(),
          null);
    }
  }

  private static final Language LANG = new Lang();

  private static final String SAMPLE = """
      package demo;

      /**
       * Scales a value; see [Guide](#guide).
       * @param factor multiplier
       * @return scaled result
       */
      class Calc {
        /** Inline comment stays raw. */ int x = 1;
        /** */
        /// Doubles `n`.
        /// @param n input
        int twice(int n) {
          return n * 2;
        }
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
    final List<String> links = new ArrayList<>();
    Language language = LANG;
    App<Doc> app;

    Doc(String text) {
      this.text = text;
      editor.onLink(links::add);
    }
  }

  private static App<Doc> app(Doc doc, boolean readOnly) {
    if (readOnly) {
      doc.editor.readOnly(true);
    }
    App<Doc> app = new App<>(
            doc,
            state -> col()
                .wFull()
                .hFull()
                .padding(12)
                .child(codeEditor(
                        state.app,
                        state.text,
                        state.editor,
                        state.language,
                        readOnly ? null : value -> state.text = value)
                    .key("code")
                    .lines(14)
                    .wFull()))
        .provide(Theme.CURRENT, state -> Theme.DARK);
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void readerModeRendersCopiesAcrossAndClicksLinks() throws IOException {
    // 1. Off by default: comments stay raw with no doc toggle buttons.
    Doc doc = new Doc(SAMPLE);
    assertFalse(doc.editor.readerMode());
    App<Doc> app = app(doc, false);
    Script.run("size 560 320 scale 2\nframe\nsnap off\n", app, out);
    String offTree = Files.readString(out.resolve("off.tree.txt"));
    assertFalse(offTree.contains("#doc-"), offTree);

    // 2. Reader mode on: standalone /** and /// render; inline and empty stay raw; links work.
    doc.editor.readerMode(true);
    app.invalidate();
    assertTrue(doc.editor.readerMode());
    Script.run("""
        size 560 320 scale 2
        frame
        snap on
        press role=link label="Guide"
        frame
        """, app, out);
    String onTree = Files.readString(out.resolve("on.tree.txt"));
    assertTrue(onTree.contains("#doc-2 "), onTree);
    assertTrue(onTree.contains("#doc-10 "), onTree);
    assertTrue(onTree.contains("el #doc-line-2 role=button label=\"Edit doc comment\""), onTree);
    assertTrue(onTree.contains("el #doc-line-10 role=button label=\"Edit doc comment\""), onTree);
    assertFalse(onTree.contains("#doc-8"), "inline comment sharing a line with code stays raw");
    assertFalse(onTree.contains("#doc-9"), "empty doc comment stays raw");
    assertTrue(onTree.contains("label=\"Params:\""), onTree);
    assertTrue(onTree.contains("label=\"factor\""), onTree);
    assertTrue(onTree.contains("label=\"Returns:\""), onTree);
    assertEquals(List.of("#guide"), doc.links);

    // 3. Arrows step over rendered block; copying across it copies raw source without revealing.
    Script.run("""
        size 560 320 scale 2
        frame
        focus #code
        key Home control
        key ArrowDown
        key ArrowDown
        frame
        snap afterDown
        key ArrowUp
        key ArrowDown shift
        key C control
        frame
        snap afterCopy
        """, app, out);
    String afterDown = Files.readString(out.resolve("afterDown.tree.txt"));
    assertTrue(
        afterDown.contains(" (8:1)"), "ArrowDown skipped lines 3..7 onto line 8: " + afterDown);
    String copied = app.clipboard().read();
    assertTrue(copied.contains("@param factor multiplier"), "copied raw source: " + copied);
    String afterCopy = Files.readString(out.resolve("afterCopy.tree.txt"));
    assertTrue(afterCopy.contains("#doc-2 "), "copying did not reveal the rendered block");
  }

  @Test
  void togglesFindsAndGoesToWhileEditsKeepTheEditedCommentRaw() throws IOException {
    Doc doc = new Doc(SAMPLE);
    doc.editor.readerMode(true);
    App<Doc> app = app(doc, false);
    // 4. Gutter pencil toggles to raw; editing inside keeps it raw; edits elsewhere shift toggle.
    Script.run("""
        size 560 320 scale 2
        frame
        press #doc-line-2
        frame
        snap rawByPencil
        """, app, out);
    String rawByPencil = Files.readString(out.resolve("rawByPencil.tree.txt"));
    assertFalse(rawByPencil.contains("#doc-2 "), rawByPencil);
    assertTrue(
        rawByPencil.contains("el #doc-line-2 role=button label=\"Render doc comment\""),
        rawByPencil);
    assertTrue(rawByPencil.contains("#doc-10 "), "other doc comment stayed rendered");

    // Edit inside the raw doc comment (line 3) and add a line above (line 0): stays raw & shifts.
    int atMultiplier = doc.text.indexOf("multiplier");
    doc.editor.select(atMultiplier, atMultiplier);
    app.invalidate();
    Script.run("""
        size 560 320 scale 2
        frame
        focus #code
        type "scale "
        frame
        key Home control
        key Enter
        frame
        snap afterEdits
        """, app, out);
    String afterEdits = Files.readString(out.resolve("afterEdits.tree.txt"));
    assertTrue(doc.text.contains("scale multiplier"));
    assertFalse(afterEdits.contains("#doc-3 "), "edited doc comment stayed raw");
    assertTrue(
        afterEdits.contains("el #doc-line-3 role=button label=\"Render doc comment\""), afterEdits);
    assertTrue(afterEdits.contains("#doc-11 "), "second doc comment shifted and stayed rendered");

    // Toggle back to rendered via gutter button while caret is on line 1.
    Script.run("""
        size 560 320 scale 2
        frame
        press #doc-line-3
        frame
        snap backRendered
        """, app, out);
    String backRendered = Files.readString(out.resolve("backRendered.tree.txt"));
    assertTrue(backRendered.contains("#doc-3 "), backRendered);

    // Move caret to class line right after rendered block (line 9), toggle raw and back with
    // the command.
    int classPos = doc.text.indexOf("class Calc");
    doc.editor.select(classPos, classPos);
    app.invalidate();
    Script.run("size 560 320 scale 2\nframe\nfocus #code\nframe\n", app, out);
    CodeEditor.run(app, doc.editor, CodeEditor.Command.TOGGLE_DOC_COMMENT);
    Script.run("frame\nsnap rawByKey\n", app, out);
    CodeEditor.run(app, doc.editor, CodeEditor.Command.TOGGLE_DOC_COMMENT);
    Script.run("frame\nsnap renderedByKey\n", app, out);
    String rawByKey = Files.readString(out.resolve("rawByKey.tree.txt"));
    assertFalse(rawByKey.contains("#doc-3 "), rawByKey);
    String renderedByKey = Files.readString(out.resolve("renderedByKey.tree.txt"));
    assertTrue(renderedByKey.contains("#doc-3 "), renderedByKey);
    assertTrue(
        renderedByKey.contains(" (9:1)"),
        "toggling rendered moved caret out of comment onto class line: " + renderedByKey);

    // 5. Find into the rendered doc comment reveals it raw.
    Script.run("""
        size 560 320 scale 2
        frame
        focus #code
        key F control
        frame
        type "scale multiplier"
        key Enter
        frame
        snap found
        """, app, out);
    String found = Files.readString(out.resolve("found.tree.txt"));
    assertFalse(found.contains("#doc-3 "), "find into rendered comment revealed it: " + found);

    // 6. Go to line into the second rendered doc comment (line 12, 1-based) reveals it raw.
    Script.run("""
        size 560 320 scale 2
        frame
        focus #code
        key G control
        frame
        type "12"
        key Enter
        frame
        snap gotoLine
        """, app, out);
    String gotoLine = Files.readString(out.resolve("gotoLine.tree.txt"));
    assertFalse(gotoLine.contains("#doc-11 "), "go-to-line into rendered comment revealed it");

    // 7. Read-only editor with readerMode off supports per-comment toggling and scroll.
    Doc ro = new Doc(SAMPLE + "\n// pad\n".repeat(20));
    App<Doc> roApp = app(ro, true);
    int inFirstDoc = ro.text.indexOf("Scales");
    ro.editor.select(inFirstDoc, inFirstDoc);
    Script.run("size 560 220 scale 2\nframe\n", roApp, out);
    CodeEditor.run(roApp, ro.editor, CodeEditor.Command.TOGGLE_DOC_COMMENT);
    Script.run("""
        frame
        snap roRendered
        wheel #code 0 200
        frame
        wheel #code 0 -200
        frame
        """, roApp, out);
    CodeEditor.run(roApp, ro.editor, CodeEditor.Command.TOGGLE_DOC_COMMENT);
    Script.run("frame\nsnap roRaw\n", roApp, out);
    assertTrue(Files.readString(out.resolve("roRendered.tree.txt")).contains("#doc-2 "));
    assertFalse(Files.readString(out.resolve("roRaw.tree.txt")).contains("#doc-2 "));
  }

  @Test
  void aCommentPastTheFirstScanRendersAndStaysRenderedWhileTypingAbove() throws IOException {
    Doc doc = new Doc("int a;\n".repeat(20_000) + "/** Far down. */\nint b;");
    doc.editor.readerMode(true);
    doc.editor.select(doc.text.length(), doc.text.length());
    App<Doc> app = app(doc, false);
    // Without input, the scan goes on by itself, a bounded part per frame.
    Script.run("size 560 320 scale 2\n" + "frame\n".repeat(8) + "snap far\n", app, out);
    assertTrue(Files.readString(out.resolve("far.tree.txt")).contains("#doc-20000 "));
    // Typing above starts the scan over; the comment found before stays rendered meanwhile.
    doc.editor.replace(0, 0, "x", 1);
    Script.run("frame\n", app, out);
    doc.editor.select(doc.text.length(), doc.text.length());
    Script.run("frame\nsnap typed\n", app, out);
    assertTrue(Files.readString(out.resolve("typed.tree.txt")).contains("#doc-20000 "));
    // A shorter text and another language (as another palette gives) in one build.
    doc.text = "int a;\n".repeat(5_000) + SAMPLE;
    doc.language = new Lang();
    doc.editor.select(doc.text.length(), doc.text.length());
    app.invalidate();
    Script.run("frame\nframe\nframe\nsnap shorter\n", app, out);
    assertTrue(Files.readString(out.resolve("shorter.tree.txt")).contains("#doc-5010 "));
  }

  @Test
  void aRenderedCommentsCellShowsOnlyTheMarkersOfItsFirstLine() throws IOException {
    Doc doc = new Doc(SAMPLE);
    doc.editor.readerMode(true);
    int first = SAMPLE.indexOf("/**");
    int param = SAMPLE.indexOf("@param factor");
    doc.editor.markers(List.of(
        new CodeEditor.Marker(first, Symbol.PLAY, Icon.Variant.ACCENT, "First line", null),
        new CodeEditor.Marker(param, Symbol.RECORD, Icon.Variant.DANGER, "Later line", null),
        new CodeEditor.Marker(param, Symbol.RECORD, Icon.Variant.MUTED, "Later line", null)));
    App<Doc> app = app(doc, true);
    Script.run("size 560 320 scale 2\nframe\nsnap markers\n", app, out);
    String tree = Files.readString(out.resolve("markers.tree.txt"));
    int from = tree.indexOf("#line-2 ");
    String cell = tree.substring(from, tree.indexOf("#line-7 ", from));
    assertTrue(cell.contains("el #marker-1 role=img label=\"First line\""), cell);
    assertFalse(cell.contains("Later line"), cell);
  }
}
