package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Inline inlay hints in a multi-line field ({@link El#hints}): width reservation, caret stops on
 * both sides, selection and copy, pointer hit testing, wrapping, chunked long lines, tabs, folds
 * and edge offsets.
 */
class HintTest {

  private static final String SOURCE = """
      var files = scan(root, true);
      var count = files.size();""";
  private static final int FILES_END = SOURCE.indexOf("files") + "files".length();
  private static final int ROOT_AT = SOURCE.indexOf("root");
  private static final int TRUE_AT = SOURCE.indexOf("true");
  private static final int COUNT_END = SOURCE.indexOf("count") + "count".length();

  private static final int SHIFT = Key.SHIFT;
  private static final int CONTROL = Key.CONTROL;

  @TempDir
  Path out;

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static final class Doc {
    String code;
    List<El.Hint> hints;
    List<El.Fold> folds = List.of();
    int clicks;

    Doc(String code, List<El.Hint> hints) {
      this.code = code;
      this.hints = new ArrayList<>(hints);
    }
  }

  private static El pill(El.Hint hint, Runnable onPress) {
    El chip = row()
        .align(Style.Align.CENTER)
        .padding(0, 4)
        .margin(0, 2, 0, 2)
        .radius(3)
        .fill(0xFF2B2D38)
        .child(El.text(hint.text()).font(Font.mono()).fontSize(11).color(0xFF9CA3AF));
    if (onPress != null) {
      chip.onPress(onPress).focusable();
    }
    return chip;
  }

  @Test
  void caretStopsOnBothSidesCopyIgnoresHintsAndImageDrawsParameterHints() throws IOException {
    App<Doc> app = start();

    Script.run("""
        size 540 80 scale 2
        frame
        focus #code
        frame
        snap hints
        """, app, out);

    Path png = out.resolve("hints.png");
    String tree = Files.readString(out.resolve("hints.tree.txt"));
    Golden.assertMatches("hints", Png.decode(Files.readAllBytes(png)), tree);
    for (int at : new int[] {FILES_END, ROOT_AT, TRUE_AT, COUNT_END}) {
      assertTrue(tree.contains("#hint-" + at), tree);
    }

    El codeEl = field(app);
    El end0 = codeEl.children().stream()
        .filter(c -> "end-0".equals(c.key()))
        .findFirst()
        .orElseThrow();
    El hintTrue = codeEl.children().stream()
        .filter(c -> ("hint-" + TRUE_AT).equals(c.key()))
        .findFirst()
        .orElseThrow();
    assertTrue(
        end0.x() > hintTrue.x() + hintTrue.width(), "line-end element sits after hinted line end");

    // Stepping Right across the BEFORE hint at FILES_END stops on both sides.
    app.select(field(app), FILES_END - 1, FILES_END - 1);
    app.frame();
    key(app, "ArrowRight"); // arrives at FILES_END -> LEFT stop (before ": List<Path>")
    assertEquals(FILES_END, field(app).editor().caret());
    float leftStopX = field(app).editor().caretBox()[0];
    key(app, "ArrowRight"); // steps to DEFAULT stop (after ": List<Path>")
    assertEquals(FILES_END, field(app).editor().caret());
    float rightStopX = field(app).editor().caretBox()[0];
    assertTrue(
        rightStopX > leftStopX + 20f,
        "Right stepped across BEFORE hint: " + leftStopX + " -> " + rightStopX);
    key(app, "ArrowRight"); // moves to next character (space after files)
    assertEquals(FILES_END + 1, field(app).editor().caret());

    // Mirror Left across the same BEFORE hint.
    key(app, "ArrowLeft"); // arrives at FILES_END -> DEFAULT stop (right side)
    assertEquals(FILES_END, field(app).editor().caret());
    assertEquals(rightStopX, field(app).editor().caretBox()[0], 0.01f);
    key(app, "ArrowLeft"); // LEFT stop (left side)
    assertEquals(FILES_END, field(app).editor().caret());
    assertEquals(leftStopX, field(app).editor().caretBox()[0], 0.01f);
    key(app, "ArrowLeft"); // moves to FILES_END - 1
    assertEquals(FILES_END - 1, field(app).editor().caret());

    // Stepping Right/Left across the AFTER hint at ROOT_AT stops on both sides.
    app.select(field(app), ROOT_AT - 1, ROOT_AT - 1);
    app.frame();
    key(app, "ArrowRight"); // arrives at ROOT_AT -> DEFAULT stop (left side of "folder:")
    assertEquals(ROOT_AT, field(app).editor().caret());
    float folderLeftX = field(app).editor().caretBox()[0];
    key(app, "ArrowRight"); // RIGHT stop (right side of "folder:")
    assertEquals(ROOT_AT, field(app).editor().caret());
    float folderRightX = field(app).editor().caretBox()[0];
    assertTrue(
        folderRightX > folderLeftX + 20f,
        "Right stepped across AFTER hint: " + folderLeftX + " -> " + folderRightX);
    key(app, "ArrowRight"); // moves to ROOT_AT + 1
    assertEquals(ROOT_AT + 1, field(app).editor().caret());
    key(app, "ArrowLeft"); // arrives at ROOT_AT -> RIGHT stop
    assertEquals(ROOT_AT, field(app).editor().caret());
    assertEquals(folderRightX, field(app).editor().caretBox()[0], 0.01f);
    key(app, "ArrowLeft"); // DEFAULT stop (left side)
    assertEquals(ROOT_AT, field(app).editor().caret());
    assertEquals(folderLeftX, field(app).editor().caretBox()[0], 0.01f);

    // Shift+Right selection across "folder:" and Ctrl+C copies only the string.
    app.select(field(app), ROOT_AT - 1, ROOT_AT - 1);
    app.frame();
    key(app, "ArrowRight", SHIFT); // selects "(" -> ends at ROOT_AT (before hint)
    float selBeforeHint = field(app).text().selection().getFirst().width();
    key(app, "ArrowRight", SHIFT); // steps over hint at ROOT_AT
    key(app, "ArrowRight", SHIFT); // selects "r" -> hint is now strictly inside selection
    float selAcrossHint = field(app).text().selection().getFirst().width();
    assertTrue(
        selAcrossHint > selBeforeHint + (folderRightX - folderLeftX),
        "selection across hint highlights the hint's width");
    key(app, "C", CONTROL);
    assertEquals("(r", app.clipboard().read(), "copy ignores inline hints");
  }

  /** A field with parameter and type hints; the hint at {@code TRUE_AT} counts presses. */
  private static App<Doc> start() {
    Context<String> tag = new Context<>("default");
    Doc doc = new Doc(
        SOURCE,
        List.of(
            new El.Hint(FILES_END, El.Hint.Affinity.BEFORE, ": List<Path>"),
            new El.Hint(ROOT_AT, El.Hint.Affinity.AFTER, "folder:"),
            new El.Hint(TRUE_AT, El.Hint.Affinity.AFTER, "recursive:"),
            new El.Hint(COUNT_END, El.Hint.Affinity.BEFORE, ": int")));

    App<Doc> app = new App<>(
        doc,
        s -> tag.with(
            "ctx",
            () -> col()
                .wFull()
                .hFull()
                .padding(12)
                .fill(0xFF101014)
                .child(El.text(s.code)
                    .editable(v -> s.code = v)
                    .multiline()
                    .key("code")
                    .label("Source")
                    .font(Font.mono())
                    .fontSize(13)
                    .color(0xFFE4E4E7)
                    .caretColor(0xFF93C5FD)
                    .selectionColor(0x803B82F6)
                    .grow(1)
                    .padding(6)
                    .radius(6)
                    .fill(0xFF1C1C22)
                    .border(1, 0xFF3A3A40)
                    .whenFocused(e -> e.border(1, 0xFF3B82F6))
                    .hints(s.hints, h -> pill(h, h.at() == TRUE_AT ? () -> s.clicks++ : null))
                    .lineEnds(line -> line == 0
                        ? List.of(El.text(tag.get() + ":ok").fontSize(11).color(0xFF9CA3AF))
                        : List.of()))));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void pressesOnHintsPickTheNearerStopOrRunTheHintsHandler() {
    App<Doc> app = start();
    app.resize(540, 80, 2);
    app.frame();
    focus(app);
    Doc doc = app.state();
    El codeEl = field(app);
    app.select(field(app), ROOT_AT, ROOT_AT);
    app.frame();
    float folderLeftX = field(app).editor().caretBox()[0];
    key(app, "ArrowRight"); // the stop right of "folder:"
    float folderRightX = field(app).editor().caretBox()[0];

    // Selection starting at ROOT_AT starts after the AFTER hint.
    app.select(field(app), ROOT_AT + 4, ROOT_AT);
    app.frame();
    Text.Box rootSel = field(app).text().selection().getFirst();
    float scale = field(app).text().scale();
    float padLeft = codeEl.border(Side.LEFT) + codeEl.padding(Side.LEFT);
    assertEquals(
        (folderRightX - padLeft) * scale,
        rootSel.x(),
        1.5f,
        "selection starting at AFTER hint begins at its right edge");

    // textPositionAt returns -1 over a hint and the character offset over adjacent text.
    El hintRoot = field(app).children().stream()
        .filter(c -> ("hint-" + ROOT_AT).equals(c.key()))
        .findFirst()
        .orElseThrow();
    assertEquals(
        -1,
        field(app)
            .textPositionAt(
                hintRoot.x() + hintRoot.width() / 2f, hintRoot.y() + hintRoot.height() / 2f),
        "textPositionAt reports -1 over a hint");
    assertEquals(
        ROOT_AT,
        field(app).textPositionAt(folderRightX + 2f, hintRoot.y() + hintRoot.height() / 2f),
        "textPositionAt reports the argument's offset right after the hint");

    // Pressing on a non-interactive hint places the caret at that offset at the nearer stop.
    float clickRightX = 12 + hintRoot.x() + hintRoot.width() * 0.85f;
    float clickY = 12 + hintRoot.y() + hintRoot.height() / 2f;
    app.pointerDown(clickRightX, clickY);
    app.pointerUp(clickRightX, clickY);
    app.frame();
    assertEquals(ROOT_AT, field(app).editor().caret());
    assertEquals(folderRightX, field(app).editor().caretBox()[0], 0.01f);

    float clickLeftX = 12 + hintRoot.x() + hintRoot.width() * 0.15f;
    app.pointerDown(clickLeftX, clickY);
    app.pointerUp(clickLeftX, clickY);
    app.frame();
    assertEquals(ROOT_AT, field(app).editor().caret());
    assertEquals(folderLeftX, field(app).editor().caretBox()[0], 0.01f);

    // Pressing on an interactive hint runs its own press handler without moving caret or focus.
    int caretBeforeClick = field(app).editor().caret();
    El interactive = field(app).children().stream()
        .filter(c -> ("hint-" + TRUE_AT).equals(c.key()))
        .findFirst()
        .orElseThrow();
    app.pointerDown(
        12 + interactive.x() + interactive.width() / 2f,
        12 + interactive.y() + interactive.height() / 2f);
    app.pointerUp(
        12 + interactive.x() + interactive.width() / 2f,
        12 + interactive.y() + interactive.height() / 2f);
    app.frame();
    assertEquals(1, doc.clicks, "interactive hint ran its press handler");
    assertEquals(caretBeforeClick, field(app).editor().caret(), "caret stayed put");
    assertEquals("code", app.focused().key(), "focus stayed on the field");
  }

  @Test
  void wrappingChunksEdgesTabsFoldsAndThreeStopsWork() {
    assertThrows(
        IllegalStateException.class,
        () -> El.text("plain").hints(List.of(), h -> El.text(h.text())));
    assertThrows(
        IllegalStateException.class,
        () -> El.text("one").editable(v -> {}).hints(List.of(), h -> El.text(h.text())));
    assertThrows(
        IllegalArgumentException.class, () -> new El.Hint(-1, El.Hint.Affinity.AFTER, "x:"));
    // 1. Both BEFORE and AFTER hints at the same offset give 3 distinct stops and hint-<at>-1 key.
    Doc both = new Doc(
        "ab",
        List.of(
            new El.Hint(1, El.Hint.Affinity.BEFORE, ": T"),
            new El.Hint(1, El.Hint.Affinity.AFTER, "p:")));
    App<Doc> bothApp = new App<>(
        both,
        s -> col()
            .wFull()
            .hFull()
            .child(El.text(s.code)
                .editable(v -> s.code = v)
                .multiline()
                .key("f")
                .font(Font.mono())
                .fontSize(13)
                .w(240)
                .lines(3)
                .hints(s.hints, h -> pill(h, null))));
    bothApp.textRendering(TextRendering.LINUX);
    bothApp.resize(260, 100, 1);
    bothApp.frame();
    focus(bothApp);
    String bothDump = bothApp.tree().dump();
    assertTrue(bothDump.contains("#hint-1 "), bothDump);
    assertTrue(bothDump.contains("#hint-1-1 "), bothDump);

    bothApp.select(field(bothApp), 0, 0);
    bothApp.frame();
    key(bothApp, "ArrowRight"); // (1, LEFT)
    float s0 = field(bothApp).editor().caretBox()[0];
    key(bothApp, "ArrowRight"); // (1, DEFAULT)
    float s1 = field(bothApp).editor().caretBox()[0];
    key(bothApp, "ArrowRight"); // (1, RIGHT)
    float s2 = field(bothApp).editor().caretBox()[0];
    assertEquals(1, field(bothApp).editor().caret());
    assertTrue(s0 < s1 && s1 < s2, "three distinct stops: " + s0 + " < " + s1 + " < " + s2);
    // Home resets stop to DEFAULT; word move uses DEFAULT.
    key(bothApp, "ArrowRight"); // moves to 2
    assertEquals(2, field(bothApp).editor().caret());

    // 2. Wrapping: hints reserve width and cause wrapping; changing hints re-wraps.
    Doc wrapDoc = new Doc("alpha beta", List.of());
    App<Doc> wrapApp = new App<>(
        wrapDoc,
        s -> col()
            .wFull()
            .hFull()
            .child(El.text(s.code)
                .editable(v -> s.code = v)
                .multiline()
                .wrapText()
                .key("f")
                .font(Font.mono())
                .fontSize(13)
                .w(120)
                .lines(4)
                .hints(s.hints, h -> pill(h, null))));
    wrapApp.textRendering(TextRendering.LINUX);
    wrapApp.resize(140, 120, 1);
    wrapApp.frame();
    assertEquals(1, field(wrapApp).text().lines().size(), "fits on 1 row without hints");

    wrapDoc.hints = List.of(new El.Hint(6, El.Hint.Affinity.AFTER, "parameter:"));
    wrapApp.invalidate();
    wrapApp.frame();
    assertEquals(2, field(wrapApp).text().lines().size(), "wraps onto 2 rows with hint");
    El wrappedHint = field(wrapApp).children().stream()
        .filter(c -> "hint-6".equals(c.key()))
        .findFirst()
        .orElseThrow();
    assertTrue(
        wrappedHint.y() >= field(wrapApp).text().lineHeight() - 1f, "AFTER hint moved to row 2");

    // 3. Long chunked line (> 2048 chars): hint width counted once and chunk-boundary hints work.
    String longLine = "a".repeat(2040) + ", " + "b".repeat(2050);
    int chunkBoundary = 2042; // right after ", ", where splitChunks cuts
    Doc longDoc = new Doc(longLine, List.of());
    App<Doc> longApp = new App<>(
        longDoc,
        s -> col()
            .wFull()
            .hFull()
            .child(El.text(s.code)
                .editable(v -> s.code = v)
                .multiline()
                .key("f")
                .font(Font.mono())
                .fontSize(13)
                .w(240)
                .lines(3)
                .hints(s.hints, h -> pill(h, null))));
    longApp.textRendering(TextRendering.LINUX);
    longApp.resize(260, 100, 1);
    longApp.frame();
    focus(longApp);
    // Measure end x without hints.
    longApp.select(field(longApp), longLine.length(), longLine.length());
    longApp.frame();
    float plainEnd = field(longApp).text().x(longLine.length());

    longDoc.hints = List.of(
        new El.Hint(chunkBoundary, El.Hint.Affinity.BEFORE, ": A"),
        new El.Hint(chunkBoundary, El.Hint.Affinity.AFTER, "arg:"));
    longApp.invalidate();
    longApp.frame();
    longApp.select(field(longApp), chunkBoundary, chunkBoundary);
    longApp.frame();
    float beforeGap = field(longApp).text().beforeGap(chunkBoundary);
    float afterGap = field(longApp).text().afterGap(chunkBoundary);
    assertTrue(beforeGap > 0 && afterGap > 0, "both chunk-boundary hints measured");
    longApp.select(field(longApp), longLine.length(), longLine.length());
    longApp.frame();
    float hintedEnd = field(longApp).text().x(longLine.length());
    assertEquals(
        plainEnd + beforeGap + afterGap,
        hintedEnd,
        1.0f,
        "chunked line adds each hint width exactly once");

    // 4. Edges (BEFORE at 0, AFTER at end, empty line), tabs after hint, and folded lines.
    String edgeText = "a\tb\n\nfold {\n  hidden\n}";
    AtomicInteger builtCount = new AtomicInteger();
    Doc edgeDoc = new Doc(
        edgeText,
        List.of(
            new El.Hint(0, El.Hint.Affinity.BEFORE, "start:"),
            new El.Hint(3, El.Hint.Affinity.AFTER, ":end"),
            new El.Hint(4, El.Hint.Affinity.AFTER, "empty"),
            new El.Hint(edgeText.indexOf("hidden"), El.Hint.Affinity.AFTER, "folded:")));
    edgeDoc.folds = List.of(new El.Fold(2, 4));
    App<Doc> edgeApp = new App<>(
        edgeDoc,
        s -> col()
            .wFull()
            .hFull()
            .child(El.text(s.code)
                .editable(v -> s.code = v)
                .multiline()
                .key("f")
                .font(Font.mono())
                .fontSize(13)
                .w(280)
                .lines(5)
                .folds(s.folds, f -> {})
                .hints(s.hints, h -> {
                  builtCount.incrementAndGet();
                  return pill(h, null);
                })));
    edgeApp.textRendering(TextRendering.LINUX);
    edgeApp.resize(300, 140, 1);
    edgeApp.frame();
    Text edgeT = field(edgeApp).text();
    assertTrue(edgeT.afterGap(0) > 0 && edgeT.beforeGap(0) == 0, "BEFORE at 0 behaves as AFTER");
    assertTrue(
        edgeT.beforeGap(3) > 0 && edgeT.afterGap(3) == 0, "AFTER at line end behaves as BEFORE");
    assertTrue(edgeT.afterGap(4) > 0, "empty line keeps its hint width");
    // Tab at index 1 lands on a multiple of tabStop (4 spaces).
    float tabStop = 4 * edgeT.font().shape(" ", edgeT.size(), edgeT.scale()).width();
    float bX = edgeT.x(2);
    assertEquals(0f, bX % tabStop, 0.5f, "tab after hint lands on the tab grid: bX=" + bX);
    // Folded hint is never built or placed.
    String edgeDump = edgeApp.tree().dump();
    assertTrue(edgeDump.contains("#hint-0"), edgeDump);
    assertTrue(edgeDump.contains("#hint-3"), edgeDump);
    assertTrue(edgeDump.contains("#hint-4"), edgeDump);
    assertFalse(edgeDump.contains("folded:"), "hint on folded line is hidden: " + edgeDump);
    assertEquals(3, builtCount.get(), "folded hint was not built");
  }

  @Test
  void offScreenHintChangesUpdateWrappedExtentOnEditAndDuplicates() {
    String text = "line\n".repeat(15) + "alpha beta";
    El.Hint wrappingHint = new El.Hint(text.indexOf("beta"), El.Hint.Affinity.AFTER, "parameter:");
    Doc doc = new Doc(text, List.of(wrappingHint));
    boolean[] clearOnEdit = {false};
    App<Doc> app = new App<>(
        doc,
        s -> col()
            .wFull()
            .hFull()
            .child(El.text(s.code)
                .editable(next -> {
                  int delta = next.length() - s.code.length();
                  s.code = next;
                  if (clearOnEdit[0]) {
                    s.hints = List.of();
                    clearOnEdit[0] = false;
                  } else if (delta != 0 && !s.hints.isEmpty()) {
                    s.hints = s.hints.stream()
                        .map(h -> new El.Hint(h.at() + delta, h.affinity(), h.text()))
                        .toList();
                  }
                })
                .multiline()
                .wrapText()
                .key("f")
                .font(Font.mono())
                .fontSize(13)
                .w(120)
                .lines(2)
                .hints(s.hints, h -> pill(h, null))));
    app.textRendering(TextRendering.LINUX);
    app.resize(140, 80, 1);
    app.frame();
    focus(app);

    float rowHeight = field(app).text().lineHeight();
    double baseExtent = 16 * rowHeight;
    assertEquals(
        baseExtent,
        field(app).text().extentHeight(),
        0.5,
        "last line starts unshaped off-screen at 1 estimated row");

    // Scroll down to measure the last line with its hint (wraps onto 2 rows), then return to top.
    app.select(field(app), doc.code.length(), doc.code.length());
    app.frame();
    assertEquals(
        baseExtent + rowHeight, field(app).text().extentHeight(), 0.5, "wrapped onto 2 rows");

    app.select(field(app), 0, 0);
    app.frame();
    assertEquals(0, field(app).scrollY(), 0.5, "scrolled to top");
    assertTrue(
        field(app).children().stream()
            .noneMatch(c -> c.key() != null && c.key().startsWith("hint-")),
        "last line's hint is off-screen");
    assertEquals(
        baseExtent + rowHeight,
        field(app).text().extentHeight(),
        0.5,
        "off-screen wrapped line keeps its 2-row measurement");

    // Editing line 0 while keeping the shifted hint must not forget the off-screen line's rows.
    app.textInput("x");
    app.frame();
    assertEquals(
        baseExtent + rowHeight,
        field(app).text().extentHeight(),
        0.5,
        "edit on line 0 keeps unchanged off-screen line's rows");

    // (a) Edit line 0 and remove the off-screen hint in one update -> extent drops by one row.
    clearOnEdit[0] = true;
    app.textInput("y");
    app.frame();
    assertEquals(0, field(app).scrollY(), 0.5, "last line stayed off-screen");
    assertEquals(
        baseExtent,
        field(app).text().extentHeight(),
        0.5,
        "edit + hint removal forgets off-screen line's extra row");

    // (b) Duplicate hints [h, h] -> [h] where two wrap and one fits -> extent drops off-screen.
    El.Hint dup = new El.Hint(doc.code.indexOf("beta"), El.Hint.Affinity.AFTER, "ab:");
    doc.hints = List.of(dup, dup);
    app.invalidate();
    app.frame();
    app.select(field(app), doc.code.length(), doc.code.length());
    app.frame();
    assertEquals(
        baseExtent + rowHeight,
        field(app).text().extentHeight(),
        0.5,
        "two duplicate hints wrap last line onto 2 rows");

    app.select(field(app), 0, 0);
    app.frame();
    assertEquals(0, field(app).scrollY(), 0.5, "scrolled to top again");
    assertTrue(
        field(app).children().stream()
            .noneMatch(c -> c.key() != null && c.key().startsWith("hint-")),
        "last line's hints are off-screen again");

    doc.hints = List.of(dup);
    app.invalidate();
    app.frame();
    assertEquals(
        baseExtent,
        field(app).text().extentHeight(),
        0.5,
        "removing one duplicate hint forgets off-screen line's extra row");
    // Scrolling the last line into view confirms the single hint actually fits on 1 row.
    app.select(field(app), doc.code.length(), doc.code.length());
    app.frame();
    assertEquals(
        baseExtent, field(app).text().extentHeight(), 0.5, "single hint fits on 1 row when shaped");
  }
}
