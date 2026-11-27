package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
  void unifiedSplitsLongRemovedRunIntoChunksInOrder() throws IOException {
    StringBuilder removed = new StringBuilder();
    for (int i = 1; i <= 350; i++) {
      if (i > 1) {
        removed.append('\n');
      }
      removed.append("removed line ").append(i);
    }
    removed.append(" ".repeat(200)).append("wide"); // the last chunk is wider than the others
    String oldText = removed.toString();
    Model model = new Model();
    App<Model> app = new App<>(
            model,
            s -> col()
                .wFull()
                .hFull()
                .child(
                    DiffView.unified(s.app, oldText, "", s.diff, null).wFull().grow(1)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    model.app = app;
    app.textRendering(TextRendering.LINUX);

    // 350 removed lines split into 100 + 100 + 100 + 50: the two middle chunks are equal INSERT(1)
    // blocks of 100 lines and must stay distinct across rebuilds.
    Script.run("""
        size 400 240 scale 2
        frame
        snap top
        wheel #diff-unified 0 3150
        frame
        snap boundary
        """, app, out);

    // Rebuild at the scrolled position so Lines.blocks receives fresh equal Block instances.
    app.invalidate();
    Path afterRebuild = out.resolve("after-rebuild");
    Script.run("""
        size 400 240 scale 2
        frame
        snap boundary
        wheel #diff-unified 0 3000
        frame
        snap bottom
        """, app, afterRebuild);

    String top = Files.readString(out.resolve("top.tree.txt"));
    assertTrue(top.contains("#deleted-0 "), "first chunk built at top: " + top);
    assertTrue(top.contains("#deleted-gutter-0 "), "first gutter chunk built at top: " + top);
    assertTrue(top.contains("#old-line-0 "), "old line 1 at top: " + top);
    assertTrue(top.contains("\"removed line 1\""), "first removed line text: " + top);
    assertFalse(top.contains("#old-line-349 "), "far removed lines are not built on open: " + top);
    assertFalse(top.contains("#deleted-200 "), "later chunks are not built on open: " + top);

    String boundary = Files.readString(afterRebuild.resolve("boundary.tree.txt"));
    assertTrue(boundary.contains("#deleted-100 "), "second chunk built at boundary: " + boundary);
    assertTrue(boundary.contains("#deleted-200 "), "third chunk built at boundary: " + boundary);
    assertTrue(
        boundary.contains("#deleted-gutter-100 ") && boundary.contains("#deleted-gutter-200 "),
        "gutter chunks stay distinct across equal INSERT blocks and rebuilds: " + boundary);
    assertTrue(boundary.contains("\"removed line 200\""), boundary);
    assertTrue(boundary.contains("\"removed line 201\""), boundary);
    assertFalse(
        boundary.contains("#deleted-0 "), "scrolled-out first chunk is dropped: " + boundary);
    float y199 = elementTop(boundary, "#old-line-199 ");
    float y200 = elementTop(boundary, "#old-line-200 ");
    assertTrue(
        y200 > y199 && y200 - y199 <= 24,
        "old line 201 follows 200 without gap or overlap (" + y199 + " -> " + y200 + ")");

    String bottom = Files.readString(afterRebuild.resolve("bottom.tree.txt"));
    assertTrue(bottom.contains("#deleted-300 "), "last chunk built at bottom: " + bottom);
    assertTrue(bottom.contains("#old-line-349 "), "old line 350 at bottom: " + bottom);
    assertTrue(bottom.contains("\"removed line 350"), "last removed line text: " + bottom);
    assertEquals(
        elementWidth(bottom, "#deleted-300 "),
        elementWidth(boundary, "#deleted-100 "),
        "every chunk of the run is as wide as its widest line, so the tint scrolls sideways whole");
  }

  private static float elementWidth(String tree, String marker) {
    String line = tree.lines().filter(l -> l.contains(marker)).findFirst().orElseThrow();
    return Float.parseFloat(line.trim().split(" ")[3].split("x")[0]);
  }

  private static float elementTop(String tree, String marker) {
    String line = tree.lines().filter(l -> l.contains(marker)).findFirst().orElseThrow();
    return Float.parseFloat(line.trim().split(" ")[2].split(",")[1]);
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

  @Test
  void sideBySideReviewBlocksStayAlignedWhenGrowingBothSidesAndInsideFillers() {
    final class ReviewModel {
      final DiffView.State diff = new DiffView.State();
      App<ReviewModel> app;
      float threadHeight = 40;
      float oldPairedHeight = 30;
      float newPairedHeight = 60;
      float addedThreadHeight = 50;
    }

    String oldText = """
        line 0
        line 1
        line 2
        line 3
        line 4
        line 5""";
    String newText = """
        line 0
        line 1 changed
        line 2
        line 3
        added a
        added b
        added c
        line 4
        line 5""";

    ReviewModel model = new ReviewModel();
    // 1. Thread on NEW line 1 (paired with OLD line 1) -> empty spacer on OLD.
    // 2. Blocks on BOTH sides on line 2 -> pair with each other (no spacer).
    // 3. Thread on NEW line 5 ("added b", inside added hunk past OLD's lines) -> splits OLD filler.
    model.diff.markup(
        DiffView.Side.NEW,
        "review",
        newText,
        List.of(
            new CodeEditor.Block(
                newText.indexOf("line 1 changed"),
                () -> el().key("new-thread").h(model.threadHeight)),
            new CodeEditor.Block(
                newText.indexOf("line 2"), () -> el().key("new-shared").h(model.newPairedHeight)),
            new CodeEditor.Block(
                newText.indexOf("added b"),
                () -> el().key("added-thread").h(model.addedThreadHeight))));
    model.diff.markup(
        DiffView.Side.OLD,
        "review",
        oldText,
        List.of(new CodeEditor.Block(
            oldText.indexOf("line 2"), () -> el().key("old-shared").h(model.oldPairedHeight))));

    App<ReviewModel> app = new App<>(
            model,
            s -> col()
                .wFull()
                .hFull()
                .child(DiffView.sideBySide(s.app, oldText, newText, s.diff, null)
                    .wFull()
                    .grow(1)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    model.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(640, 420, 1);
    app.frame();

    El oldPane = findByKey(app.tree(), "old");
    El newPane = findByKey(app.tree(), "new");
    assertEquals(
        40, findByKey(oldPane, "spacer-2-2").height(), 0.5f, "spacer matches thread initially");
    assertEquals(
        elementTop(oldPane, "line-2"),
        elementTop(newPane, "line-2"),
        0.5f,
        "line after thread aligns on both sides");
    // Blocks on both sides at line 2 pair with each other (taller side wins, no spacer).
    assertEquals(60, findByKey(oldPane, "old-shared").height(), 0.5f);
    assertEquals(60, findByKey(newPane, "new-shared").height(), 0.5f);
    assertEquals(null, findByKeyOrNull(oldPane, "spacer-3-3"));
    assertEquals(null, findByKeyOrNull(newPane, "spacer-3-3"));
    // Spacer inside split filler aligns added lines and trailing unchanged line 4 / line 7.
    El fillerSpacer = findByKey(oldPane, "spacer-4-6");
    assertEquals(50, fillerSpacer.height(), 0.5f, "spacer inside filler matches added thread");
    assertEquals(
        findByKey(newPane, "added-thread").y(),
        fillerSpacer.y(),
        0.5f,
        "spacer sits at the exact row of the thread inside the filler");
    assertEquals(
        elementTop(oldPane, "line-4"),
        elementTop(newPane, "line-7"),
        0.5f,
        "line after added hunk stays aligned");

    // Grow the thread on NEW: spacer grows to match and lines below stay aligned in the same frame.
    model.threadHeight = 110;
    model.oldPairedHeight = 95;
    model.addedThreadHeight = 85;
    app.invalidate();
    app.frame();

    oldPane = findByKey(app.tree(), "old");
    newPane = findByKey(app.tree(), "new");
    assertEquals(
        110, findByKey(oldPane, "spacer-2-2").height(), 0.5f, "spacer grew with the thread");
    assertEquals(
        elementTop(oldPane, "line-2"),
        elementTop(newPane, "line-2"),
        0.5f,
        "line after grown thread stays aligned");
    assertEquals(95, findByKey(oldPane, "old-shared").height(), 0.5f);
    assertEquals(95, findByKey(newPane, "new-shared").height(), 0.5f);
    assertEquals(85, findByKey(oldPane, "spacer-4-6").height(), 0.5f);
    assertEquals(
        findByKey(newPane, "added-thread").y(),
        findByKey(oldPane, "spacer-4-6").y(),
        0.5f,
        "spacer inside filler stays aligned after growth");
    assertEquals(
        elementTop(oldPane, "line-4"),
        elementTop(newPane, "line-7"),
        0.5f,
        "lines below stay aligned after all threads grow");

    // Scrolling through the thread inside the added lines keeps the spacer beside it.
    model.addedThreadHeight = 400;
    app.invalidate();
    app.frame();
    int compared = 0;
    for (int step = 0; step < 12; step++) {
      app.wheel(480, 200, 0, 30);
      app.frame();
      El thread = findByKeyOrNull(findByKey(app.tree(), "new"), "added-thread");
      if (thread != null) {
        El spacer = findByKey(findByKey(app.tree(), "old"), "spacer-4-6");
        assertEquals(thread.y(), spacer.y(), 0.5f, "spacer beside the thread, step " + step);
        compared++;
      }
    }
    assertTrue(compared > 2, "scrolled through the thread");
  }

  @Test
  void markedLinesAreNotHiddenInCollapsedRegions() {
    Model model = new Model();
    // Line 1 ("package shop;") is inside the top 4-line collapsed region of OLD_TEXT / NEW_TEXT.
    int pkgAt = OLD_TEXT.indexOf("package shop;");
    App<Model> app = new App<>(
            model,
            s -> col()
                .wFull()
                .hFull()
                .child(DiffView.sideBySide(s.app, OLD_TEXT, NEW_TEXT, s.diff, null)
                    .wFull()
                    .grow(1)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    model.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(700, 380, 1);
    app.frame();
    assertTrue(app.tree().dump().contains("label=\"4 unchanged lines\""), "collapsed initially");

    // Attaching a review block on an unchanged line prevents collapsing that region.
    model.diff.markup(
        DiffView.Side.OLD,
        "review",
        OLD_TEXT,
        List.of(new CodeEditor.Block(pkgAt, () -> el().key("pkg-thread").h(24))));
    app.invalidate();
    app.frame();
    String withMark = app.tree().dump();
    assertFalse(withMark.contains("#unchanged-0"), "marked line is not collapsed: " + withMark);
    assertTrue(
        withMark.contains("#pkg-thread"), "thread on unchanged line is visible: " + withMark);
    assertTrue(
        withMark.contains("#spacer-2-2"), "paired spacer appears on opposite side: " + withMark);

    // Clearing the layer lets the unchanged region collapse again.
    model.diff.noMarkup(DiffView.Side.OLD, "review");
    app.invalidate();
    app.frame();
    assertTrue(app.tree().dump().contains("label=\"4 unchanged lines\""), "collapses once cleared");
  }

  @Test
  void lineActionReportsItsSide() {
    DiffView.State state = new DiffView.State();

    List<DiffView.Lines> pressed = new ArrayList<>();
    state.lineAction(Symbol.ADD, "Add comment", pressed::add);

    String oldText = "a\nb\nc\nd";
    String newText = "a\nB\nc\nd";
    Model model = new Model();
    model.diff = state;
    App<Model> app = new App<>(
            model,
            s -> col()
                .wFull()
                .hFull()
                .child(DiffView.sideBySide(s.app, oldText, newText, s.diff, null)
                    .wFull()
                    .grow(1)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    model.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(600, 200, 1);
    app.frame();

    // Press line 1 action button on OLD side.
    El oldPane = findByKey(app.tree(), "old");
    El oldAction = findByKey(oldPane, "action-line-1");
    float[] oldCenter = absCenter(app.tree(), oldAction);
    app.pointerMove(oldCenter[0], oldCenter[1]);
    app.frame();
    app.pointerDown(oldCenter[0], oldCenter[1]);
    app.pointerUp(oldCenter[0], oldCenter[1]);
    app.frame();

    // Drag across lines 1..2 on NEW side's gutter.
    El newPane = findByKey(app.tree(), "new");
    El newAction1 = findByKey(newPane, "action-line-1");
    El newLine2 = findByKey(newPane, "line-2");
    float[] start = absCenter(app.tree(), newAction1);
    float[] end = absCenter(app.tree(), newLine2);
    app.pointerMove(start[0], start[1]);
    app.frame();
    app.pointerDown(start[0], start[1]);
    app.pointerMove(start[0], end[1]);
    app.frame();
    app.pointerUp(start[0], end[1]);
    app.frame();

    assertEquals(
        List.of(
            new DiffView.Lines(DiffView.Side.OLD, 1, 1),
            new DiffView.Lines(DiffView.Side.NEW, 1, 2)),
        pressed);
  }

  @Test
  void unifiedShowsOldMarksSplitsRemovedBlocksAndReportsLineAction() {
    String oldText = """
        keep 0
        removed a
        removed b
        removed c
        keep 4""";
    String newText = """
        keep 0
        added x
        keep 4""";

    DiffView.State state = new DiffView.State();
    List<DiffView.Lines> pressed = new ArrayList<>();
    state.lineAction(Symbol.ADD, "Add comment", pressed::add);

    int remA = oldText.indexOf("removed a");
    int remB = oldText.indexOf("removed b");
    int remBEnd = remB + "removed b".length();
    int keep4 = oldText.indexOf("keep 4");
    int keep4End = keep4 + "keep 4".length();
    state.markup(
        DiffView.Side.OLD,
        "review",
        oldText,
        List.of(
            new CodeEditor.Block(remA, () -> el().key("removed-thread").h(36)),
            new CodeEditor.Highlight(remA, remBEnd, CodeEditor.Highlight.Tone.NOTE),
            new CodeEditor.Marker(remB, Symbol.PLAY, Icon.Variant.ACCENT, "Comment", null),
            // The same key on another removed line: all removed lines insert at one line.
            new CodeEditor.Block(oldText.indexOf("removed c"), () -> el().key("removed-thread")),
            new CodeEditor.Block(keep4, () -> el().key("unchanged-thread").h(24)),
            new CodeEditor.Highlight(keep4, keep4End, CodeEditor.Highlight.Tone.NOTE),
            new CodeEditor.Marker(keep4, Symbol.PLAY, Icon.Variant.ACCENT, "Keep note", null)));

    Model model = new Model();
    model.diff = state;
    App<Model> app = new App<>(
            model,
            s -> col()
                .wFull()
                .hFull()
                .child(DiffView.unified(s.app, oldText, newText, s.diff, null)
                    .wFull()
                    .grow(1)))
        .provide(Theme.CURRENT, s -> Theme.DARK);
    model.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(540, 320, 1);
    app.frame();

    El tree = app.tree();
    El chunk1 = findByKey(tree, "deleted-1");
    El removedThread = findByKey(tree, "removed-block-1-removed-thread");
    El chunk2 = findByKey(tree, "deleted-2");
    assertEquals(
        chunk1.y() + chunk1.height(),
        removedThread.y(),
        0.5f,
        "removed thread sits right below removed a");
    assertEquals(
        removedThread.y() + removedThread.height(),
        chunk2.y(),
        0.5f,
        "remaining removed lines follow the thread");

    // NOTE stripes and markers on removed rows; unchanged OLD marks mapped onto NEW line 2.
    El oldRow1 = findByKey(tree, "old-line-1");
    El oldRow2 = findByKey(tree, "old-line-2");
    El oldRow3 = findByKey(tree, "old-line-3");
    assertTrue(findByKeyOrNull(oldRow1, "note") != null, "removed a carries note stripe");
    assertTrue(findByKeyOrNull(oldRow2, "note") != null, "removed b carries note stripe");
    assertEquals(null, findByKeyOrNull(oldRow3, "note"), "removed c has no note stripe");
    assertTrue(findByKeyOrNull(oldRow2, "marker-1") != null, "removed b carries marker");

    El newLine2 = findByKey(tree, "line-2");
    assertTrue(findByKeyOrNull(newLine2, "note") != null, "unchanged OLD note maps to NEW line 2");
    assertTrue(
        findByKeyOrNull(newLine2, "marker-1") != null, "unchanged OLD marker maps to NEW line 2");
    El unchangedThread = findByKey(tree, "unchanged-thread");
    assertTrue(unchangedThread.y() > newLine2.y(), "unchanged OLD block sits below NEW line 2");

    // Gutter columns stay aligned between new and removed rows with marker + action slots.
    El newLine0 = findByKey(tree, "line-0");
    assertEquals(
        newLine0.children().get(newLine0.children().size() - 4).x(),
        oldRow1.children().get(oldRow1.children().size() - 5).x(),
        0.5f,
        "old line numbers align across new and removed rows");

    // Press the action on removed line 1 -> Side.OLD (1..1).
    float[] action1 = absCenter(tree, findByKey(tree, "action-old-line-1"));
    app.pointerMove(action1[0], action1[1]);
    app.frame();
    app.pointerDown(action1[0], action1[1]);
    app.pointerUp(action1[0], action1[1]);
    app.frame();

    // Drag across removed lines 2..3 -> Side.OLD (2..3).
    tree = app.tree();
    float[] dragStart = absCenter(tree, findByKey(tree, "action-old-line-2"));
    float[] dragEnd = absCenter(tree, findByKey(tree, "old-line-3"));
    app.pointerMove(dragStart[0], dragStart[1]);
    app.frame();
    app.pointerDown(dragStart[0], dragStart[1]);
    app.pointerMove(dragStart[0], dragEnd[1]);
    app.frame();
    app.pointerUp(dragStart[0], dragEnd[1]);
    app.frame();

    // Dragging past the removed run into NEW line 1 stays clamped to the removed run (2..3).
    tree = app.tree();
    float[] newLine1Center = absCenter(tree, findByKey(tree, "line-1"));
    app.pointerMove(dragStart[0], dragStart[1]);
    app.frame();
    app.pointerDown(dragStart[0], dragStart[1]);
    app.pointerMove(dragStart[0], newLine1Center[1]);
    app.frame();
    app.pointerUp(dragStart[0], newLine1Center[1]);
    app.frame();

    // Press the action on added NEW line 1 -> Side.NEW (1..1).
    tree = app.tree();
    float[] newAction1 = absCenter(tree, findByKey(tree, "action-line-1"));
    app.pointerMove(newAction1[0], newAction1[1]);
    app.frame();
    app.pointerDown(newAction1[0], newAction1[1]);
    app.pointerUp(newAction1[0], newAction1[1]);
    app.frame();

    assertEquals(
        List.of(
            new DiffView.Lines(DiffView.Side.OLD, 1, 1),
            new DiffView.Lines(DiffView.Side.OLD, 2, 3),
            new DiffView.Lines(DiffView.Side.OLD, 2, 3),
            new DiffView.Lines(DiffView.Side.NEW, 1, 1)),
        pressed);
  }

  private static El findByKey(El root, String key) {
    El found = findByKeyOrNull(root, key);
    if (found == null) {
      throw new AssertionError("no element #" + key + " in\n" + root.dump());
    }
    return found;
  }

  private static El findByKeyOrNull(El root, String key) {
    if (key.equals(root.key())) {
      return root;
    }
    for (El child : root.children()) {
      El found = findByKeyOrNull(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static float elementTop(El pane, String key) {
    return findByKey(pane, key).y();
  }

  private static float[] absCenter(El root, El target) {
    float[] pos = absPos(root, target, 0, 0);
    if (pos == null) {
      throw new AssertionError("target not in tree");
    }
    return new float[] {pos[0] + target.width() / 2f, pos[1] + target.height() / 2f};
  }

  private static float[] absPos(El current, El target, float ox, float oy) {
    float x = ox + current.x();
    float y = oy + current.y();
    if (current == target) {
      return new float[] {x, y};
    }
    for (El child : current.children()) {
      float[] found = absPos(child, target, x, y);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
