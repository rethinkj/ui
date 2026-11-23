package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DiffView} side-by-side and unified layouts of the same change:
 * collapsed unchanged regions, intraline highlights, gutter bars and F7 / Shift+F7 navigation.
 */
class DiffViewTest {

  private static final String OLD_TEXT = """
      // Order report
      package shop;

      import java.util.List;

      // Configuration
      class Report {
        static final int LIMIT = 50;
        static final String STATUS = "pending";

        List<String> columns() {
          return List.of("id", "total");
        }

        // Legacy helper removed
        int legacyFee(int total) {
          return total / 20;
        }

        boolean isEmpty(List<?> items) {
          return items.isEmpty();
        }
      }""";

  private static final String NEW_TEXT = """
      // Order report
      package shop;

      import java.util.List;

      // Configuration
      class Report {
        static final int LIMIT = 100;
        static final String STATUS = "paid";

        List<String> columns() {
          return List.of("id", "total");
        }

        boolean isEmpty(List<?> items) {
          return items.isEmpty();
        }

        int count(List<?> items) {
          return items.size();
        }
      }""";

  private static final Set<String> KEYWORDS =
      Set.of("package", "import", "class", "static", "final", "int", "return", "boolean");

  /** Minimal keyword/comment/string highlighter for stored diff images in the editor module. */
  private record MiniJava(Theme.Code colors) implements Highlighter {
    @Override
    public Object line(String line, Object state, Spans out) {
      int n = line.length();
      int i = 0;
      while (i < n) {
        char c = line.charAt(i);
        if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
          out.color(i, n, colors.comment());
          return null;
        }
        if (c == '"') {
          int end = i + 1;
          while (end < n && line.charAt(end) != '"') {
            end++;
          }
          out.color(i, Math.min(end + 1, n), colors.string());
          i = end + 1;
        } else if (Character.isDigit(c)) {
          int end = i + 1;
          while (end < n && Character.isDigit(line.charAt(end))) {
            end++;
          }
          out.color(i, end, colors.number());
          i = end;
        } else if (Character.isLetter(c) || c == '_') {
          int end = i + 1;
          while (end < n
              && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
            end++;
          }
          if (KEYWORDS.contains(line.substring(i, end))) {
            out.color(i, end, colors.keyword());
          }
          i = end;
        } else {
          i++;
        }
      }
      return null;
    }
  }

  static final class Model {
    DiffView.State diff = new DiffView.State();
    boolean unified;
    App<Model> app;
  }

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  @Test
  void sideBySideAndUnifiedShowSameChangeAndNavigateWithF7() throws IOException {
    Model model = new Model();
    Theme theme = Theme.DARK;
    Highlighter highlighter = new MiniJava(theme.code());
    App<Model> app = new App<>(
            model,
            s -> col()
                .wFull()
                .hFull()
                .padding(12)
                .fill(theme.surface().window())
                .child((s.unified
                        ? DiffView.unified(s.app, OLD_TEXT, NEW_TEXT, s.diff, highlighter)
                        : DiffView.sideBySide(s.app, OLD_TEXT, NEW_TEXT, s.diff, highlighter))
                    .wFull()
                    .grow(1)))
        .provide(Theme.CURRENT, s -> theme);
    model.app = app;
    app.textRendering(TextRendering.LINUX);

    // 1. Side-by-side layout: collapsed top region, modified hunks with word changes, added hunk
    // with exact filler block, and F7 / Shift+F7 navigation across all three hunks.
    Script.run("""
        size 800 390 scale 2
        frame
        snap side-by-side
        focus #new
        key F7
        frame
        snap side-hunk-0
        key F7
        frame
        snap side-hunk-1
        key F7
        frame
        snap side-hunk-2
        key F7 shift
        frame
        snap side-back
        """, app, out);

    assertEquals(3, model.diff.changeCount());
    assertEquals(1, model.diff.currentChange(), "Shift+F7 moved back from hunk 2 to hunk 1");
    String sideTree = Files.readString(out.resolve("side-by-side.tree.txt"));
    assertTrue(sideTree.contains("label=\"4 unchanged lines\""), sideTree);
    String hunk0Tree = Files.readString(out.resolve("side-hunk-0.tree.txt"));
    assertTrue(hunk0Tree.contains(" (8:1)"), "F7 moved caret to hunk 0 (line 8): " + hunk0Tree);
    String hunk2Tree = Files.readString(out.resolve("side-hunk-2.tree.txt"));
    assertTrue(
        hunk2Tree.contains(" (17:1)"), "third F7 moved caret to hunk 2 (added): " + hunk2Tree);

    // 2. Unified layout of the same change: deleted lines in read-only inserted blocks with old
    // line numbers in the second number column, F7 / Shift+F7 navigation, and expanding unchanged.
    model.unified = true;
    app.invalidate();
    Path unifiedOut = out.resolve("unified");
    Script.run("""
        size 540 460 scale 2
        frame
        advance 1
        frame
        snap unified
        focus #diff-unified
        key F7
        frame
        snap unified-hunk-2
        key F7 shift
        frame
        snap unified-hunk-1
        press #unchanged-0
        frame
        snap unified-expanded
        """, app, unifiedOut);

    assertEquals(1, model.diff.currentChange(), "Shift+F7 returned to hunk 1 in unified view");
    String unifiedTree = Files.readString(unifiedOut.resolve("unified.tree.txt"));
    assertTrue(
        unifiedTree.contains("#deleted-7"), "deleted lines block for hunk 0: " + unifiedTree);
    assertTrue(
        unifiedTree.contains("#old-line-7"), "old line number in block gutter: " + unifiedTree);
    assertTrue(
        unifiedTree.contains("#deleted-14"), "deleted lines block for hunk 1: " + unifiedTree);
    assertTrue(unifiedTree.contains("label=\"4 unchanged lines\""), unifiedTree);
    String unifiedHunk2 = Files.readString(unifiedOut.resolve("unified-hunk-2.tree.txt"));
    assertTrue(
        unifiedHunk2.contains(" (17:1)"),
        "F7 navigated to hunk 2 in unified view: " + unifiedHunk2);
    String expandedTree = Files.readString(unifiedOut.resolve("unified-expanded.tree.txt"));
    assertFalse(
        expandedTree.contains("#unchanged-0"),
        "pressing collapsed row expanded the unchanged lines: " + expandedTree);
    assertTrue(expandedTree.contains("#line-0 "), "line 0 is now visible: " + expandedTree);
  }

  @Test
  void f7ShowsTheStartOfLongChanges() throws IOException {
    StringBuilder ten = new StringBuilder();
    StringBuilder forty = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      ten.append("a").append(i).append('\n');
    }
    for (int i = 0; i < 40; i++) {
      forty.append("b").append(i).append('\n');
    }
    String inserted = ten + forty.toString() + ten;
    String removedAtEnd = ten + forty.toString().strip();
    Model model = new Model();
    App<Model> app = new App<>(
            model,
            s -> col()
                .wFull()
                .hFull()
                .child((s.unified
                        ? DiffView.unified(
                            s.app, removedAtEnd, ten.toString().strip(), s.diff, null)
                        : DiffView.sideBySide(s.app, ten + ten.toString(), inserted, s.diff, null))
                    .wFull()
                    .grow(1)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    model.app = app;
    app.textRendering(TextRendering.LINUX);

    Script.run("size 400 200\nframe\nfocus #new\nkey F7\nframe\nsnap inserted\n", app, out);
    String sideBySide = Files.readString(out.resolve("inserted.tree.txt"));
    assertTrue(
        sideBySide.contains("lines 11-"), "the first inserted line at the top: " + sideBySide);

    model.unified = true;
    model.diff = new DiffView.State();
    app.invalidate();
    Path unifiedOut = out.resolve("unified");
    Script.run(
        "size 400 200\nframe\nadvance 1\nframe\nfocus #diff-unified\nkey F7\nframe\nsnap removed\n",
        app,
        unifiedOut);
    String unified = Files.readString(unifiedOut.resolve("removed.tree.txt"));
    String first =
        unified.lines().filter(l -> l.contains("#old-line-10 ")).findFirst().orElseThrow();
    float top = Float.parseFloat(first.trim().split(" ")[2].split(",")[1]);
    assertTrue(top >= 0 && top < 40, "the first removed line at the top: " + unified);
  }

  @Test
  void rowsBothSidesHaveScrollOneToOne() {
    // Old lines 1-2 replaced by new lines 1-4: the filler follows old line 2, beside new 3 and 4.
    Diff diff = Diff.lines("a\nb\nc\nd", "a\nB\nC\nX\nY\nd");
    assertTrue(
        DiffView.scrollAnchors(diff).contains(new ScrollState.Anchor(2, 2)), diff.toString());
    // A new file: the empty side's one line, under its filler, spans all lines of the other.
    assertTrue(
        DiffView.scrollAnchors(Diff.lines("", "a\nb\nc")).contains(new ScrollState.Anchor(1, 3)));
  }
}
