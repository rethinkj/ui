package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Blocks between lines in a multi-line field ({@link El#blocks}): both placements, navigation,
 * selection and copy, reveal from select/undo/edit, clicks, gutter, folds, and anchor stability.
 */
class BlockTest {

  private static final int SHIFT = Key.SHIFT;
  private static final int CONTROL = Key.CONTROL;

  private static final class State {
    String value;
    final List<El.Block> blocks = new ArrayList<>();
    final List<El.Fold> folds = new ArrayList<>();
    final List<El.Block> revealed = new ArrayList<>();
    FieldState fieldState;
    Function<El.Block, El> builder;
    boolean autoReveal = true;
    int visionClicks;

    State(String value, El.Block... blocks) {
      this.value = value;
      this.blocks.addAll(List.of(blocks));
      this.builder = b -> El.row()
          .padding(4, 6)
          .fill(0xFF26262E)
          .child(El.text(b.placement() + ":" + b.first() + "-" + b.last()).fontSize(12));
    }

    void reveal(El.Block block) {
      revealed.add(block);
      if (autoReveal) {
        blocks.remove(block);
      }
    }
  }

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  private static App<State> start(State state, UnaryOperator<El> extra) {
    App<State> app = new App<>(
        state,
        s -> El.col()
            .wFull()
            .hFull()
            .padding(10)
            .child(extra.apply(El.text(s.value)
                .editable(v -> s.value = v)
                .multiline()
                .key("f")
                .font(Font.mono())
                .fontSize(14)
                .w(240)
                .lines(8)
                .padding(4)
                .fieldState(s.fieldState)
                .folds(s.folds, s.folds::remove)
                .blocks(s.blocks, s.builder, s::reveal))));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 260, 1);
    app.frame();
    focus(app);
    return app;
  }

  @Test
  void validationAndFieldWithoutBlocksUnchanged() {
    assertThrows(IllegalArgumentException.class, () -> El.Block.insert(-1));
    assertThrows(IllegalArgumentException.class, () -> El.Block.replace(3, 2));
    assertThrows(
        IllegalArgumentException.class, () -> new El.Block(El.Block.Placement.INSERT, 1, 2, 0));
    assertThrows(IllegalArgumentException.class, () -> El.Block.insert(0).lines(-1));

    assertThrows(
        IllegalStateException.class,
        () -> El.text("one").editable(v -> {}).blocks(List.of(), b -> El.el(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> El.text("a\nb\nc")
            .editable(v -> {})
            .multiline()
            .blocks(List.of(El.Block.replace(0, 1), El.Block.replace(1, 2)), b -> El.el(), null));

    // Without blocks, layout matches a plain multi-line field identically.
    State emptyBlocks = new State("a\nb\nc\nd");
    App<State> withEmpty = start(emptyBlocks, UnaryOperator.identity());
    App<String> plain = new App<>(
        "a\nb\nc\nd",
        s -> El.col()
            .wFull()
            .hFull()
            .padding(10)
            .child(El.text(s)
                .editable(v -> {})
                .multiline()
                .key("f")
                .font(Font.mono())
                .fontSize(14)
                .w(240)
                .lines(8)
                .padding(4)));
    plain.textRendering(TextRendering.LINUX);
    plain.resize(320, 260, 1);
    plain.frame();
    focus(plain);
    assertEquals(
        plain.tree().children().getFirst().text().extentHeight(),
        field(withEmpty).text().extentHeight());
    assertEquals(0, field(withEmpty).editor().lineIndex().totalBlockPixels());
  }

  @Test
  void bothPlacementsArrowsSelectionCopyClicksGutterAndEnclosingFold() {
    Context<String> tag = new Context<>("default");
    String value = "class A {\n/** doc0 */\n/** doc1 */\nvoid run() {\n  ok();\n}\n}";
    El.Block docReplace = El.Block.replace(1, 2);
    El.Block visionInsert = El.Block.insert(3);
    El.Block visionInsert2 = El.Block.insert(3);
    El.Block tailFiller = El.Block.insert(7).lines(2);

    State state = new State(value, docReplace, visionInsert, visionInsert2, tailFiller);
    AtomicInteger built = new AtomicInteger();
    state.builder = b -> {
      built.incrementAndGet();
      if (b.equals(visionInsert)) {
        return El.row()
            .h(18)
            .child(El.text(tag.get() + ": 2 usages")
                .key("vision-btn")
                .fontSize(12)
                .onPress(() -> state.visionClicks++));
      }
      if (b.equals(docReplace)) {
        return El.col().h(36).fill(0xFF26262E).child(El.text("Rendered doc").fontSize(12));
      }
      return El.el().fill(0x20888888);
    };

    List<El.Gutter.Line> gutterSeen = new ArrayList<>();
    App<State> app = new App<>(
        state,
        s -> tag.with(
            "ctx",
            () -> El.col()
                .wFull()
                .hFull()
                .padding(10)
                .child(El.text(s.value)
                    .editable(v -> s.value = v)
                    .multiline()
                    .key("f")
                    .font(Font.mono())
                    .fontSize(14)
                    .w(240)
                    .lines(12)
                    .padding(4)
                    .gutter(line -> {
                      gutterSeen.add(line);
                      return El.text(String.valueOf(line.index() + 1))
                          .font(Font.mono())
                          .fontSize(14)
                          .padding(0, 4, 0, 0);
                    })
                    .folds(s.folds, s.folds::remove)
                    .blocks(s.blocks, s.builder, s::reveal))));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 320, 1);
    app.frame();
    focus(app);

    // Default keys and captured context.
    String dump = app.tree().dump();
    assertTrue(dump.contains("#block-1"), dump);
    assertTrue(dump.contains("#block-3 "), dump);
    assertTrue(dump.contains("#block-3-1"), dump);
    assertTrue(dump.contains("#block-7"), dump);
    assertTrue(dump.contains("ctx: 2 usages"), dump);

    // Gutter: real line numbers, one cell for REPLACE(1, 2) with start(1)..end(2), none for INSERT.
    Lines ls = field(app).editor().lineIndex();
    assertTrue(
        gutterSeen.stream()
            .anyMatch(g -> g.index() == 1 && g.start() == ls.start(1) && g.end() == ls.end(2)),
        "replaced range gets one gutter cell covering first..last: " + gutterSeen);
    assertFalse(
        gutterSeen.stream().anyMatch(g -> g.index() == 2),
        "second line of replaced range gets no gutter cell: " + gutterSeen);
    assertTrue(
        gutterSeen.stream().anyMatch(g -> g.index() == 3),
        "line after insert keeps its real index: " + gutterSeen);

    // Down steps over REPLACE(1,2) and INSERT(3) directly from line 0 to line 3; Up returns.
    key(app, "ArrowDown");
    assertEquals(3, ls.line(field(app).editor().caret()));
    key(app, "ArrowUp");
    assertEquals(0, ls.line(field(app).editor().caret()));

    // Right at end of line 0 jumps across REPLACE(1,2) to start of line 3; Left jumps back.
    key(app, "End");
    key(app, "ArrowRight");
    assertEquals(ls.start(3), field(app).editor().caret());
    key(app, "ArrowLeft");
    assertEquals(ls.end(0), field(app).editor().caret());

    // Shift+Right selects across REPLACE(1,2) and INSERT(3): tints blocks and copies hidden text.
    key(app, "ArrowRight", SHIFT);
    assertFalse(field(app).text().blockSelection().isEmpty(), "selected blocks get selection tint");
    key(app, "C", CONTROL);
    assertEquals("\n/** doc0 */\n/** doc1 */\n", app.clipboard().read());
    assertEquals(List.of(), state.revealed, "selection and copy do not reveal");

    // Clicking the clickable element inside INSERT(3) reaches it.
    El fieldEl = field(app);
    El block3 = fieldEl.children().stream()
        .filter(c -> "block-3".equals(c.key()))
        .findFirst()
        .orElseThrow();
    El visionBtn = block3.children().getFirst();
    float btnX = 10 + block3.x() + visionBtn.x() + visionBtn.width() / 2;
    float btnY = 10 + block3.y() + visionBtn.y() + visionBtn.height() / 2;
    app.pointerDown(btnX, btnY);
    app.pointerUp(btnX, btnY);
    app.frame();
    assertEquals(1, state.visionClicks, "click on interactive child in block reaches handler");

    // Clicking elsewhere in the REPLACE(1,2) block band places caret at start of line 3.
    El block1 = field(app).children().stream()
        .filter(c -> "block-1".equals(c.key()))
        .findFirst()
        .orElseThrow();
    float bandX = 10 + block1.x() + block1.width() - 10;
    float bandY = 10 + block1.y() + block1.height() / 2;
    app.pointerDown(bandX, bandY);
    app.pointerUp(bandX, bandY);
    app.frame();
    assertEquals(ls.start(3), field(app).editor().caret());

    // Enclosing fold hides blocks inside it (0 pixels, not built).
    int builtBeforeFold = built.get();
    state.folds.add(new El.Fold(0, 5));
    app.invalidate();
    app.frame();
    assertFalse(app.tree().dump().contains("#block-1"));
    assertFalse(app.tree().dump().contains("#block-3"));
    assertTrue(app.tree().dump().contains("#block-7"), "trailing block after fold stays shown");
    assertEquals(builtBeforeFold + 1, built.get(), "only the trailing block was built after fold");
  }

  @Test
  void replacedLineZeroAndEndKeepCaretOutOfReplacedRange() {
    State state = new State(
        "doc0\ndoc1\ncode2\ntail3\ntail4", El.Block.replace(0, 1), El.Block.replace(3, 4));
    App<State> app = start(state, UnaryOperator.identity());
    Lines ls = field(app).editor().lineIndex();
    assertEquals(ls.start(2), field(app).editor().caret(), "the first caret is where it shows");

    // Ctrl+Home goes to start of first visible line (line 2), never 0.
    key(app, "Home", CONTROL);
    assertEquals(ls.start(2), field(app).editor().caret());
    key(app, "ArrowLeft");
    assertEquals(ls.start(2), field(app).editor().caret());
    key(app, "ArrowUp");
    assertEquals(ls.start(2), field(app).editor().caret());

    // Ctrl+End goes to end of last visible line (line 2), never into tail3..tail4.
    key(app, "End", CONTROL);
    assertEquals(ls.end(2), field(app).editor().caret());
    key(app, "ArrowRight");
    assertEquals(ls.end(2), field(app).editor().caret());
    key(app, "ArrowDown");
    assertEquals(ls.end(2), field(app).editor().caret());
  }

  @Test
  void aFocusableControlInABlockTakesPressesButNotTheFocus() {
    State state = new State("a\nb\nc", El.Block.insert(1));
    state.builder = b -> El.row()
        .h(20)
        .child(El.text("press")
            .key("btn")
            .fontSize(12)
            .focusable()
            .onPress(() -> state.visionClicks++));
    App<State> app = start(state, UnaryOperator.identity());
    key(app, "Tab"); // from the field: nothing in the block takes the focus
    key(app, "Tab");
    El block = field(app).children().stream()
        .filter(child -> "block-1".equals(child.key()))
        .findFirst()
        .orElseThrow();
    El button = block.children().getFirst();
    float x = 10 + block.x() + button.x() + button.width() / 2;
    float y = 10 + block.y() + button.y() + button.height() / 2;
    app.pointerDown(x, y);
    app.pointerUp(x, y);
    app.frame();
    assertEquals(1, state.visionClicks);
  }

  @Test
  void horizontalScrollMovesBlocksAndClipsAtGutterWhileWrappingKeepsBlocksFixed() {
    String longLine = " ".repeat(40) + "void deeplyIndented() {}";
    AtomicInteger deepClicks = new AtomicInteger();
    AtomicInteger leftClicks = new AtomicInteger();
    AtomicInteger gutterClicks = new AtomicInteger();
    int blockFill = 0xFFFF0000;
    int gutterFill = 0xFF102030;
    State state = new State(longLine + "\nsecond", El.Block.insert(0));
    state.builder = b -> El.row()
        .h(20)
        .fill(blockFill)
        .child(El.text("left").key("left-btn").fontSize(12).onPress(leftClicks::incrementAndGet))
        .child(El.el().w(300))
        .child(
            El.text("3 usages").key("deep-btn").fontSize(12).onPress(deepClicks::incrementAndGet));
    App<State> app = start(
        state,
        f -> f.w(200)
            .gutter(line -> El.row()
                .fill(gutterFill)
                .padding(0, 4, 0, 0)
                .onPress(gutterClicks::incrementAndGet)
                .child(
                    El.text(String.valueOf(line.index() + 1)).font(Font.mono()).fontSize(14))));

    El f = field(app);
    float contentLeft = f.border(Side.LEFT) + f.padding(Side.LEFT);
    El blockBefore = findChild(f, "block-0");
    assertEquals(contentLeft, blockBefore.x(), 0.5f, "at scrollX=0 block starts at contentLeft");

    // Scroll right so the deeply indented button moves into view and the left part scrolls under
    // the gutter.
    app.scrollTo(f, 260, 0);
    app.frame();
    f = field(app);
    float scrolledX = (float) f.scrollX();
    assertTrue(scrolledX > 200, "field scrolled horizontally: " + scrolledX);
    app.invalidate();
    app.frame();
    f = field(app);
    assertEquals(scrolledX, (float) f.scrollX(), 0.5f, "a rebuild keeps the scroll");
    El blockAfter = findChild(f, "block-0");
    assertEquals(
        contentLeft - scrolledX,
        blockAfter.x(),
        1.0f,
        "block follows horizontal scroll in non-wrapping field");

    // Deeply indented button is now reachable and clickable.
    El deepBtn = findChild(blockAfter, "deep-btn");
    float contentRight = f.width() - f.border(Side.RIGHT) - f.padding(Side.RIGHT);
    assertTrue(
        blockAfter.x() + deepBtn.x() + deepBtn.width() <= contentRight + 0.5f,
        "the end of a block wider than the view comes into view");
    float deepX = 10 + blockAfter.x() + deepBtn.x() + deepBtn.width() / 2;
    float deepY = 10 + blockAfter.y() + deepBtn.y() + deepBtn.height() / 2;
    app.pointerDown(deepX, deepY);
    app.pointerUp(deepX, deepY);
    app.frame();
    assertEquals(
        1, deepClicks.get(), "deeply indented block button reachable after horizontal scroll");

    // Over the gutter: block's scrolled-left portion is clipped in both hit-testing and rendering.
    float gx = 10 + 6;
    float gy = 10 + blockAfter.y() + 10;
    app.pointerDown(gx, gy);
    app.pointerUp(gx, gy);
    app.frame();
    assertEquals(0, leftClicks.get(), "clipped part of block over gutter takes no clicks");
    int gutterPixel = app.render().pixels().argb(Math.round(gx), Math.round(gy));
    assertNotEquals(
        blockFill, gutterPixel, "block is clipped at content left and never covers gutter");

    // Wrapping field keeps blocks fixed at contentLeft.
    State wrapped = new State("word ".repeat(30), El.Block.insert(0));
    App<State> wrapApp = start(wrapped, el -> el.w(200).wrapText());
    El wf = field(wrapApp);
    El wBlock = findChild(wf, "block-0");
    assertEquals(wf.border(Side.LEFT) + wf.padding(Side.LEFT), wBlock.x(), 0.5f);
  }

  @Test
  void overlaysOnBlockAndLineEndPartsAttachOnFirstFrame() {
    boolean[] menuOpen = {false};
    AtomicInteger menuClicks = new AtomicInteger();
    State state = new State("line0\nline1", El.Block.insert(0));
    state.builder = b -> {
      El btn = El.text("open").key("open-btn").fontSize(12).onPress(() -> menuOpen[0] = true);
      if (menuOpen[0]) {
        btn.overlay(El.row()
            .key("block-menu")
            .padding(4)
            .fill(0xFF333344)
            .child(El.text("Action").fontSize(12))
            .onPress(menuClicks::incrementAndGet));
      }
      return El.row().h(20).child(btn);
    };
    App<State> app = start(
        state,
        f -> f.lineEnds(line -> line == 1
            ? List.of(El.text("diag")
                .key("diag-end")
                .fontSize(12)
                .overlay(El.text("tip").key("end-tip").fontSize(12).fill(0xFF222233)))
            : List.of()));

    // Line-end overlay attached on the initial build is laid out and shown immediately.
    El endTip = app.tree().shownOverlays().stream()
        .filter(o -> "end-tip".equals(o.key()))
        .findFirst()
        .orElse(null);
    assertTrue(
        endTip != null && endTip.isPlaced() && endTip.width() > 0, "line-end overlay placed");

    // Clicking the block button opens its menu overlay on the very next frame.
    El block = findChild(field(app), "block-0");
    El openBtn = findChild(block, "open-btn");
    float bx = 10 + block.x() + openBtn.x() + openBtn.width() / 2;
    float by = 10 + block.y() + openBtn.y() + openBtn.height() / 2;
    app.pointerDown(bx, by);
    app.pointerUp(bx, by);
    app.frame();

    El menu = app.tree().shownOverlays().stream()
        .filter(o -> "block-menu".equals(o.key()))
        .findFirst()
        .orElse(null);
    assertTrue(
        menu != null && menu.isPlaced() && menu.width() > 0, "block overlay placed on attach");
    app.pointerDown(menu.x() + menu.width() / 2, menu.y() + menu.height() / 2);
    app.pointerUp(menu.x() + menu.width() / 2, menu.y() + menu.height() / 2);
    app.frame();
    assertEquals(1, menuClicks.get(), "click on block overlay reaches handler");
  }

  @Test
  void pressedLooksApplyToGutterBlocksAndLineEnds() {
    int pressedColor = 0xFFAA3344;
    AtomicInteger gutterClicks = new AtomicInteger();
    AtomicInteger blockClicks = new AtomicInteger();
    AtomicInteger endClicks = new AtomicInteger();
    State state = new State("line0\nline1", El.Block.insert(1));
    state.builder = b -> El.row()
        .h(20)
        .child(El.text("vision")
            .key("vision-btn")
            .fontSize(12)
            .onPress(blockClicks::incrementAndGet)
            .whenPressed(e -> e.fill(pressedColor)));
    App<State> app = start(
        state,
        f -> f.gutter(line -> El.row()
                .padding(0, 4, 0, 0)
                .onPress(gutterClicks::incrementAndGet)
                .whenPressed(e -> e.fill(pressedColor))
                .child(
                    El.text(String.valueOf(line.index() + 1)).font(Font.mono()).fontSize(14)))
            .lineEnds(line -> line == 0
                ? List.of(El.text("badge")
                    .key("end-btn")
                    .fontSize(12)
                    .onPress(endClicks::incrementAndGet)
                    .whenPressed(
                        e -> e.fill(pressedColor).overlay(El.text("held").fontSize(12))))
                : List.of()));

    // 1. Gutter cell pressed look.
    El gutter = findChild(field(app), "gutter");
    El line0Cell = findChild(gutter, "line-0");
    float gx = 10 + gutter.x() + line0Cell.x() + line0Cell.width() / 2;
    float gy = 10 + gutter.y() + line0Cell.y() + line0Cell.height() / 2;
    app.pointerDown(gx, gy);
    app.frame();
    assertEquals(
        pressedColor,
        findChild(findChild(field(app), "gutter"), "line-0").fill(),
        "gutter cell shows whenPressed while held");
    app.pointerUp(gx, gy);
    app.frame();
    assertEquals(1, gutterClicks.get());
    assertEquals(0, findChild(findChild(field(app), "gutter"), "line-0").fill());

    // 2. Block button pressed look.
    El block = findChild(field(app), "block-1");
    El vision = findChild(block, "vision-btn");
    float bx = 10 + block.x() + vision.x() + vision.width() / 2;
    float by = 10 + block.y() + vision.y() + vision.height() / 2;
    app.pointerDown(bx, by);
    app.frame();
    assertEquals(
        pressedColor,
        findChild(findChild(field(app), "block-1"), "vision-btn").fill(),
        "block child shows whenPressed while held");
    app.advance(App.BLINK); // the caret blinks while the button is held
    app.frame();
    assertEquals(
        pressedColor,
        findChild(findChild(field(app), "block-1"), "vision-btn").fill(),
        "the look stays through a caret blink");
    app.pointerUp(bx, by);
    app.frame();
    assertEquals(1, blockClicks.get());
    assertEquals(0, findChild(findChild(field(app), "block-1"), "vision-btn").fill());

    // 3. Line-end button pressed look.
    El endBtn = findChild(field(app), "end-btn");
    float ex = 10 + endBtn.x() + endBtn.width() / 2;
    float ey = 10 + endBtn.y() + endBtn.height() / 2;
    app.pointerDown(ex, ey);
    app.frame();
    assertEquals(
        pressedColor,
        findChild(field(app), "end-btn").fill(),
        "line-end element shows whenPressed while held");
    app.advance(App.BLINK); // a blink builds the block anew, not the line-end element
    app.frame();
    assertEquals(1, findChild(field(app), "end-btn").overlays().size(), "the look applied once");
    app.pointerUp(ex, ey);
    app.frame();
    assertEquals(1, endClicks.get());
    assertEquals(0, findChild(field(app), "end-btn").fill());
  }

  @Test
  void replaceBlockShorterThanARowBoundsAndClipsItsGutterCell() {
    State state = new State("line0\nrep1\nrep2\nline3", El.Block.replace(1, 2));
    state.builder = b -> El.el().h(6).fill(0xFF26262E);
    App<State> app = start(
        state,
        f -> f.gutter(line -> El.row()
            .padding(0, 4, 0, 0)
            .child(El.text(String.valueOf(line.index() + 1)).font(Font.mono()).fontSize(14))));

    El gutter = findChild(field(app), "gutter");
    El cell0 = findChild(gutter, "line-0");
    El cell1 = findChild(gutter, "line-1");
    El cell3 = findChild(gutter, "line-3");
    assertTrue(cell0.height() > 6, "normal row has full line height: " + cell0.height());
    assertEquals(6f, cell1.height(), 0.01f, "replaced block's gutter cell bounded to block height");
    assertEquals(
        Style.Overflow.CLIP, cell1.style().overflowY, "short replaced block's gutter cell clips");
    assertTrue(
        cell1.y() + cell1.height() <= cell3.y() + 0.01f,
        "short replaced cell does not overlap next row");
  }

  @Test
  void insertAtLineZeroShowsWhilePlaceholderShows() {
    State state = new State("", El.Block.insert(0), El.Block.insert(1), El.Block.replace(0, 0));
    state.builder = b -> El.row().h(20).fill(0xFF26262E).child(El.text("filler").fontSize(12));
    App<State> app = start(state, f -> f.placeholder("Type here...", 0xFF888888));

    El f = field(app);
    assertTrue(f.text().placeholderShown(), "placeholder is shown for empty field");
    String dump = app.tree().dump();
    assertTrue(dump.contains("#block-0 "), "INSERT(0) shows while placeholder shows: " + dump);
    assertFalse(dump.contains("#block-1"), "INSERT(1) stays hidden while placeholder shows");
    assertFalse(dump.contains("#block-0-1"), "REPLACE(0,0) stays hidden while placeholder shows");
    assertEquals(20f, findChild(f, "block-0").height(), 0.01f);

    // Typing clears the placeholder and transitions to normal text.
    type(app, "x");
    assertFalse(field(app).text().placeholderShown());
    assertEquals("x", state.value);
  }

  private static El findChild(El parent, String key) {
    return parent.children().stream()
        .filter(c -> key.equals(c.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No child #" + key + " in " + parent.dump()));
  }

  @Test
  void revealTriggeredBySelectFieldStateUndoAndEdit() {
    El.Block rep = El.Block.replace(1, 2);

    // 1. App.select into replaced range calls reveal.
    State find = new State("L0\nFIND\nL2\nL3", rep);
    App<State> findApp = start(find, UnaryOperator.identity());
    int match = find.value.indexOf("FIND");
    findApp.select(field(findApp), match + 4, match);
    findApp.frame();
    assertEquals(List.of(rep), find.revealed);
    assertTrue(find.blocks.isEmpty());

    // 2. FieldState.select into replaced range calls reveal on attach.
    State fs = new State("L0\nTARGET\nL2\nL3", rep);
    fs.fieldState = new FieldState();
    fs.fieldState.select(fs.value.indexOf("TARGET"), fs.value.indexOf("TARGET"));
    start(fs, UnaryOperator.identity());
    assertEquals(List.of(rep), fs.revealed);

    // 3. Undo into replaced range calls reveal.
    State undo = new State("L0\nEDIT\nL2\nL3");
    App<State> undoApp = start(undo, UnaryOperator.identity());
    key(undoApp, "ArrowDown");
    type(undoApp, "X");
    assertEquals("L0\nXEDIT\nL2\nL3", undo.value);
    key(undoApp, "Home", CONTROL);
    undo.blocks.add(rep);
    undoApp.invalidate();
    undoApp.frame();
    key(undoApp, "Z", CONTROL);
    assertEquals(List.of(rep), undo.revealed);
    assertEquals("L0\nEDIT\nL2\nL3", undo.value);

    // 4. Delete from end of line 0 into replaced line 1 calls reveal; typing on line 0 does not.
    State edit = new State("L0\nL1\nL2\nL3", rep);
    App<State> editApp = start(edit, UnaryOperator.identity());
    key(editApp, "End");
    type(editApp, "!");
    assertEquals(List.of(), edit.revealed, "typing on visible line 0 does not reveal");
    key(editApp, "Delete");
    assertEquals(List.of(rep), edit.revealed, "Delete touching line 1 reveals");
    assertEquals("L0!L1\nL2\nL3", edit.value);
  }

  @Test
  void measuringBlockAboveViewDoesNotMoveWhatIsInView() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      if (i > 0) {
        text.append('\n');
      }
      text.append("line ").append(i);
    }
    // Tall unmeasured block (80px) at line 15; field starts scrolled down at line 20 so line 15
    // is above the layout margin and unmeasured on the initial frame.
    El.Block tallAbove = El.Block.insert(15);
    State state = new State(text.toString(), tallAbove);
    state.builder = b -> El.el().h(80).fill(0xFF26262E);
    App<State> app = start(state, f -> f.lines(6));
    Text t = field(app).text();
    float lh = t.lineHeight();

    // Jump directly to line 20 so the block at line 15 (5 lines above) is outside the 3-row margin.
    double initialScrollY = 20 * lh;
    app.scrollTo(field(app), 0, initialScrollY);
    app.frame();
    Lines ls = field(app).editor().lineIndex();
    assertEquals(Math.round(lh), ls.totalBlockPixels(), "block at 15 is still estimated (1 line)");

    // Line 20 is at the top of the view; record its viewport y.
    double line20ViewportBefore = ls.lineTop(20, lh) - field(app).text().viewY();

    // Scroll up by 3 lines so block at line 15 enters the top layout margin and gets measured
    // (80px).
    double scrollDelta = 3 * lh;
    app.scrollTo(field(app), 0, field(app).scrollY() - scrollDelta);
    app.frame();

    assertEquals(80, ls.totalBlockPixels(), "block at 15 was measured to 80px");
    double line20ViewportAfter = ls.lineTop(20, lh) - field(app).text().viewY();
    assertEquals(
        line20ViewportBefore + scrollDelta,
        line20ViewportAfter,
        1.0,
        "measuring a block above the view must not jump what is in view");

    // A rebuild keeps the measured height of a block out of view: the view does not move again.
    app.scrollTo(field(app), 0, field(app).scrollY() + 10 * lh);
    app.frame();
    double scrolled = field(app).scrollY();
    app.invalidate();
    app.frame();
    assertEquals(80, field(app).editor().lineIndex().totalBlockPixels(), "still measured");
    assertEquals(scrolled, field(app).scrollY(), "a rebuild does not move the view");
  }
}
