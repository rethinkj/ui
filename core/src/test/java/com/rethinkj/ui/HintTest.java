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
    assertThrows(
        IllegalStateException.class,
        () -> El.text("plain").hints(List.of(), h -> El.text(h.text())));
    assertThrows(
        IllegalStateException.class,
        () -> El.text("one").editable(v -> {}).hints(List.of(), h -> El.text(h.text())));
    assertThrows(
        IllegalArgumentException.class, () -> new El.Hint(-1, El.Hint.Affinity.AFTER, "x:"));

    String source = """
        var files = scan(root, true);
        var count = files.size();""";
    int filesEnd = source.indexOf("files") + "files".length();
    int rootAt = source.indexOf("root");
    int trueAt = source.indexOf("true");
    int countEnd = source.indexOf("count") + "count".length();

    Context<String> tag = new Context<>("default");
    Doc doc = new Doc(
        source,
        List.of(
            new El.Hint(filesEnd, El.Hint.Affinity.BEFORE, ": List<Path>"),
            new El.Hint(rootAt, El.Hint.Affinity.AFTER, "folder:"),
            new El.Hint(trueAt, El.Hint.Affinity.AFTER, "recursive:"),
            new El.Hint(countEnd, El.Hint.Affinity.BEFORE, ": int")));

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
                    .hints(s.hints, h -> pill(h, h.at() == trueAt ? () -> s.clicks++ : null))
                    .lineEnds(line -> line == 0
                        ? List.of(El.text(tag.get() + ":ok").fontSize(11).color(0xFF9CA3AF))
                        : List.of()))));
    app.textRendering(TextRendering.LINUX);

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
    assertTrue(tree.contains("#hint-" + filesEnd), tree);
    assertTrue(tree.contains("#hint-" + rootAt), tree);
    assertTrue(tree.contains("#hint-" + trueAt), tree);
    assertTrue(tree.contains("#hint-" + countEnd), tree);

    El codeEl = field(app);
    El end0 = codeEl.children().stream()
        .filter(c -> "end-0".equals(c.key()))
        .findFirst()
        .orElseThrow();
    El hintTrue = codeEl.children().stream()
        .filter(c -> ("hint-" + trueAt).equals(c.key()))
        .findFirst()
        .orElseThrow();
    assertTrue(
        end0.x() > hintTrue.x() + hintTrue.width(), "line-end element sits after hinted line end");

    // Stepping Right across the BEFORE hint at filesEnd stops on both sides.
    app.select(field(app), filesEnd - 1, filesEnd - 1);
    app.frame();
    key(app, "ArrowRight"); // arrives at filesEnd -> LEFT stop (before ": List<Path>")
    assertEquals(filesEnd, field(app).editor().caret());
    float leftStopX = field(app).editor().caretBox()[0];
    key(app, "ArrowRight"); // steps to DEFAULT stop (after ": List<Path>")
    assertEquals(filesEnd, field(app).editor().caret());
    float rightStopX = field(app).editor().caretBox()[0];
    assertTrue(
        rightStopX > leftStopX + 20f,
        "Right stepped across BEFORE hint: " + leftStopX + " -> " + rightStopX);
    key(app, "ArrowRight"); // moves to next character (space after files)
    assertEquals(filesEnd + 1, field(app).editor().caret());

    // Mirror Left across the same BEFORE hint.
    key(app, "ArrowLeft"); // arrives at filesEnd -> DEFAULT stop (right side)
    assertEquals(filesEnd, field(app).editor().caret());
    assertEquals(rightStopX, field(app).editor().caretBox()[0], 0.01f);
    key(app, "ArrowLeft"); // LEFT stop (left side)
    assertEquals(filesEnd, field(app).editor().caret());
    assertEquals(leftStopX, field(app).editor().caretBox()[0], 0.01f);
    key(app, "ArrowLeft"); // moves to filesEnd - 1
    assertEquals(filesEnd - 1, field(app).editor().caret());

    // Stepping Right/Left across the AFTER hint at rootAt stops on both sides.
    app.select(field(app), rootAt - 1, rootAt - 1);
    app.frame();
    key(app, "ArrowRight"); // arrives at rootAt -> DEFAULT stop (left side of "folder:")
    assertEquals(rootAt, field(app).editor().caret());
    float folderLeftX = field(app).editor().caretBox()[0];
    key(app, "ArrowRight"); // RIGHT stop (right side of "folder:")
    assertEquals(rootAt, field(app).editor().caret());
    float folderRightX = field(app).editor().caretBox()[0];
    assertTrue(
        folderRightX > folderLeftX + 20f,
        "Right stepped across AFTER hint: " + folderLeftX + " -> " + folderRightX);
    key(app, "ArrowRight"); // moves to rootAt + 1
    assertEquals(rootAt + 1, field(app).editor().caret());
    key(app, "ArrowLeft"); // arrives at rootAt -> RIGHT stop
    assertEquals(rootAt, field(app).editor().caret());
    assertEquals(folderRightX, field(app).editor().caretBox()[0], 0.01f);
    key(app, "ArrowLeft"); // DEFAULT stop (left side)
    assertEquals(rootAt, field(app).editor().caret());
    assertEquals(folderLeftX, field(app).editor().caretBox()[0], 0.01f);

    // Shift+Right selection across "folder:" and Ctrl+C copies only the string.
    app.select(field(app), rootAt - 1, rootAt - 1);
    app.frame();
    key(app, "ArrowRight", SHIFT); // selects "(" -> ends at rootAt (before hint)
    float selBeforeHint = field(app).text().selection().getFirst().width();
    key(app, "ArrowRight", SHIFT); // steps over hint at rootAt
    key(app, "ArrowRight", SHIFT); // selects "r" -> hint is now strictly inside selection
    float selAcrossHint = field(app).text().selection().getFirst().width();
    assertTrue(
        selAcrossHint > selBeforeHint + (folderRightX - folderLeftX),
        "selection across hint highlights the hint's width");
    key(app, "C", CONTROL);
    assertEquals("(r", app.clipboard().read(), "copy ignores inline hints");

    // Selection starting at rootAt starts after the AFTER hint.
    app.select(field(app), rootAt + 4, rootAt);
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
        .filter(c -> ("hint-" + rootAt).equals(c.key()))
        .findFirst()
        .orElseThrow();
    assertEquals(
        -1,
        field(app)
            .textPositionAt(
                hintRoot.x() + hintRoot.width() / 2f, hintRoot.y() + hintRoot.height() / 2f),
        "textPositionAt reports -1 over a hint");
    assertEquals(
        rootAt,
        field(app).textPositionAt(folderRightX + 2f, hintRoot.y() + hintRoot.height() / 2f),
        "textPositionAt reports the argument's offset right after the hint");

    // Pressing on a non-interactive hint places the caret at that offset at the nearer stop.
    float clickRightX = 12 + hintRoot.x() + hintRoot.width() * 0.85f;
    float clickY = 12 + hintRoot.y() + hintRoot.height() / 2f;
    app.pointerDown(clickRightX, clickY);
    app.pointerUp(clickRightX, clickY);
    app.frame();
    assertEquals(rootAt, field(app).editor().caret());
    assertEquals(folderRightX, field(app).editor().caretBox()[0], 0.01f);

    float clickLeftX = 12 + hintRoot.x() + hintRoot.width() * 0.15f;
    app.pointerDown(clickLeftX, clickY);
    app.pointerUp(clickLeftX, clickY);
    app.frame();
    assertEquals(rootAt, field(app).editor().caret());
    assertEquals(folderLeftX, field(app).editor().caretBox()[0], 0.01f);

    // Pressing on an interactive hint runs its own press handler without moving caret or focus.
    int caretBeforeClick = field(app).editor().caret();
    El interactive = field(app).children().stream()
        .filter(c -> ("hint-" + trueAt).equals(c.key()))
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
}
