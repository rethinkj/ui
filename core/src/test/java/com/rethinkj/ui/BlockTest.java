package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.motion.Spring;
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
  void tabVisitsBlockAndLineEndFocusablesInOrderAndActivatesWithEnter() {
    int focusedColor = 0xFF3355AA;
    AtomicInteger endClicks = new AtomicInteger();
    State state = new State("a\nb\nc", El.Block.insert(1));
    state.builder = b -> El.row()
        .h(20)
        .child(El.text("press")
            .key("block-btn")
            .fontSize(12)
            .focusable()
            .whenFocused(e -> e.fill(focusedColor))
            .onPress(() -> state.visionClicks++));
    App<State> app = new App<>(
        state,
        s -> El.col()
            .wFull()
            .hFull()
            .padding(10)
            .child(El.text("before").key("before").fontSize(12).focusable())
            .child(El.text(s.value)
                .editable(v -> s.value = v)
                .multiline()
                .key("f")
                .font(Font.mono())
                .fontSize(14)
                .w(240)
                .lines(6)
                .padding(4)
                .lineEnds(line -> line == 0
                    ? List.of(El.text("end")
                        .key("end-btn")
                        .fontSize(12)
                        .focusable()
                        .onPress(endClicks::incrementAndGet))
                    : List.of())
                .blocks(s.blocks, s.builder, s::reveal))
            .child(El.text("after").key("after").fontSize(12).focusable()));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 260, 1);
    app.frame();

    // Forward Tab order: before -> f -> end-btn -> block-btn -> after.
    app.focus(app.tree().children().getFirst());
    app.frame();
    assertEquals("before", app.focused().key());
    key(app, "Tab");
    assertEquals("f", app.focused().key());
    key(app, "Tab");
    assertEquals("end-btn", app.focused().key());
    key(app, "Enter");
    assertEquals(1, endClicks.get(), "Enter activates focused line-end control");
    key(app, "Tab");
    assertEquals("block-btn", app.focused().key());
    assertEquals(focusedColor, app.focused().fill(), "whenFocused look applies to block child");
    key(app, "Enter");
    assertEquals(1, state.visionClicks, "Enter activates focused block control");
    key(app, "Tab");
    assertEquals("after", app.focused().key());

    // Reverse Shift+Tab order: after -> block-btn -> end-btn -> f -> before.
    key(app, "Tab", SHIFT);
    assertEquals("block-btn", app.focused().key());
    key(app, "Tab", SHIFT);
    assertEquals("end-btn", app.focused().key());
    key(app, "Tab", SHIFT);
    assertEquals("f", app.focused().key());
    key(app, "Tab", SHIFT);
    assertEquals("before", app.focused().key());

    // Pressing block-btn with pointer runs handler without stealing focus from before.
    El f = app.tree().children().get(1);
    El block = findChild(f, "block-1");
    El button = findChild(block, "block-btn");
    float x = f.x() + block.x() + button.x() + button.width() / 2;
    float y = f.y() + block.y() + button.y() + button.height() / 2;
    app.pointerDown(x, y);
    app.pointerUp(x, y);
    app.frame();
    assertEquals(2, state.visionClicks);
    assertEquals("before", app.focused().key());
  }

  @Test
  void textFieldInInsertedBlockTakesFocusTypingAndSurvivesScrollAndBlink() {
    StringBuilder lines = new StringBuilder();
    for (int i = 0; i < 14; i++) {
      if (i > 0) {
        lines.append('\n');
      }
      lines.append("line ").append(i);
    }
    String[] comment = {"hi"};
    State state = new State(lines.toString(), El.Block.insert(1));
    state.builder = b -> El.row()
        .h(24)
        .padding(2)
        .child(El.text(comment[0])
            .editable(v -> comment[0] = v)
            .key("comment")
            .font(Font.mono())
            .fontSize(12)
            .w(120)
            .whenPressed(e -> e.fill(0xFF334455)));
    App<State> app = start(state, f -> f.lines(6));
    String outerBefore = state.value;

    El f = field(app);
    El block = findChild(f, "block-1");
    El input = findChild(block, "comment");
    float ix = 10 + block.x() + input.x() + input.width() - 4;
    float iy = 10 + block.y() + input.y() + input.height() / 2;
    app.pointerDown(ix, iy);
    app.pointerUp(ix, iy);
    app.frame();
    assertEquals("comment", app.focused().key(), "clicking field in block focuses it");

    type(app, "!");
    assertEquals("hi!", comment[0], "typed text goes to inner field");
    assertEquals(outerBefore, state.value, "outer field text is unchanged");

    // Scroll outer field while keeping block in view: inner field's editor survives (undo works).
    app.scrollTo(field(app), 0, 10);
    app.frame();
    assertEquals("comment", app.focused().key(), "focus survives scroll rebuilding block");
    key(app, "Z", CONTROL);
    assertEquals("hi", comment[0], "inner field editor undo history survived part rebuild");
    assertEquals(outerBefore, state.value);

    // Caret blink on outer field after a pressed look drops block cache still registers identities.
    focus(app);
    El f2 = field(app);
    El b2 = findChild(f2, "block-1");
    El inp2 = findChild(b2, "comment");
    float bx = 10 + b2.x() + inp2.x() + 4;
    float by = 10 + b2.y() + inp2.y() + inp2.height() / 2;
    app.pointerMove(bx, by);
    app.frame();
    app.advance(App.BLINK);
    app.frame();
    key(app, "Tab");
    assertEquals("comment", app.focused().key(), "block child keeps identity after caret blink");
  }

  @Test
  void dragInsideABlockRunsThroughStartMoveAndEnd() {
    List<Drag.Phase> phases = new ArrayList<>();
    State state = new State("a\nb\nc", El.Block.insert(1));
    state.builder = b -> El.row()
        .h(22)
        .child(El.text("drag").key("handle").fontSize(12).w(60).onDrag(d -> phases.add(d.phase())));
    App<State> app = start(state, UnaryOperator.identity());

    El block = findChild(field(app), "block-1");
    El handle = findChild(block, "handle");
    float x = 10 + block.x() + handle.x() + handle.width() / 2;
    float y = 10 + block.y() + handle.y() + handle.height() / 2;
    app.pointerDown(x, y);
    app.frame();
    app.pointerMove(x + 15, y);
    app.frame();
    app.pointerMove(x + 30, y);
    app.frame();
    app.pointerUp(x + 30, y);
    app.frame();

    assertEquals(List.of(Drag.Phase.START, Drag.Phase.MOVE, Drag.Phase.END), phases);
  }

  @Test
  void blockOverlaySortsAboveModalKeepsFocusFillsListAndAttachesLookField() {
    boolean[] menuOpen = {false};
    String[] menuInput = {""};
    String[] lookInput = {""};
    State state = new State("line0\nline1", El.Block.insert(0));
    state.builder = b -> {
      El openBtn = El.text("open").key("open-btn").fontSize(12).onPress(() -> menuOpen[0] = true);
      if (menuOpen[0]) {
        openBtn.overlay(El.col()
            .key("block-menu")
            .padding(4)
            .fill(0xFF333344)
            .child(El.text(menuInput[0])
                .editable(v -> menuInput[0] = v)
                .key("menu-input")
                .font(Font.mono())
                .fontSize(12)
                .w(100))
            .child(El.col()
                .key("menu-list")
                .w(100)
                .h(40)
                .rows(5, 20, i -> El.text("row-" + i).fontSize(12))));
      }
      El hoverHost = El.text("hover")
          .key("hover-host")
          .fontSize(12)
          .whenHovered(e -> e.overlay(El.col()
              .key("look-pop")
              .padding(2)
              .fill(0xFF223344)
              .child(El.text(lookInput[0])
                  .editable(v -> lookInput[0] = v)
                  .key("look-input")
                  .font(Font.mono())
                  .fontSize(12)
                  .w(80))));
      return El.row().h(20).gap(8).child(openBtn).child(hoverHost);
    };
    App<State> app = new App<>(
        state,
        s -> El.col()
            .wFull()
            .hFull()
            .overlay(El.col()
                .key("dlg")
                .modal()
                .w(280)
                .h(200)
                .padding(8)
                .fill(0xFF1A1A22)
                .child(El.text(s.value)
                    .editable(v -> s.value = v)
                    .multiline()
                    .key("f")
                    .font(Font.mono())
                    .fontSize(14)
                    .w(240)
                    .lines(6)
                    .padding(4)
                    .gutter(line -> {
                      El cell = El.text(String.valueOf(line.index() + 1))
                          .font(Font.mono())
                          .fontSize(14)
                          .padding(0, 4, 0, 0);
                      if (line.index() == 0) {
                        cell.overlay(El.text("g-tip").key("g-tip").fontSize(12).fill(0xFF222233));
                      }
                      return cell;
                    })
                    .blocks(s.blocks, s.builder, s::reveal))));
    app.textRendering(TextRendering.LINUX);
    app.resize(360, 260, 1);
    app.frame();

    // View-attached overlay on a gutter cell is laid out even without any look applying.
    El gTip = app.tree().shownOverlays().stream()
        .filter(o -> "g-tip".equals(o.key()))
        .findFirst()
        .orElse(null);
    assertTrue(gTip != null && gTip.isPlaced() && gTip.width() > 0, "gutter overlay laid out");

    // Open block menu from inside the modal dialog: placed in same pass, sorted above modal,
    // and virtual list inside it gets its rows filled.
    El dlg = app.tree().shownOverlays().stream()
        .filter(o -> "dlg".equals(o.key()))
        .findFirst()
        .orElseThrow();
    El f = dlg.children().getFirst();
    El block = findChild(f, "block-0");
    El openBtn = findChild(block, "open-btn");
    float bx = dlg.x() + f.x() + block.x() + openBtn.x() + openBtn.width() / 2;
    float by = dlg.y() + f.y() + block.y() + openBtn.y() + openBtn.height() / 2;
    app.pointerDown(bx, by);
    app.pointerUp(bx, by);
    app.frame();

    List<El> shown = app.tree().shownOverlays();
    int dlgIdx = -1;
    int menuIdx = -1;
    for (int i = 0; i < shown.size(); i++) {
      if ("dlg".equals(shown.get(i).key())) {
        dlgIdx = i;
      } else if ("block-menu".equals(shown.get(i).key())) {
        menuIdx = i;
      }
    }
    assertTrue(menuIdx > dlgIdx && dlgIdx >= 0, "block overlay sorts above enclosing modal");
    El menu = shown.get(menuIdx);
    El menuList = findChild(menu, "menu-list");
    assertFalse(menuList.children().isEmpty(), "virtual list in block overlay has rows filled");

    // Text field inside block overlay takes focus, keeps focus across rebuilds, and takes typing.
    El mInput = findChild(menu, "menu-input");
    float mx = menu.x() + mInput.x() + mInput.width() / 2;
    float my = menu.y() + mInput.y() + mInput.height() / 2;
    app.pointerDown(mx, my);
    app.pointerUp(mx, my);
    app.frame();
    assertEquals("menu-input", app.focused().key(), "field in block menu keeps focus after frame");
    type(app, "ok");
    assertEquals("ok", menuInput[0], "typing reaches field in block menu");
    assertEquals("menu-input", app.focused().key(), "focus stays in block menu after typing");

    // Overlay attached by whenHovered on a block child attaches editors to fields inside it.
    menuOpen[0] = false;
    app.invalidate();
    app.frame();
    El dlg2 = app.tree().shownOverlays().stream()
        .filter(o -> "dlg".equals(o.key()))
        .findFirst()
        .orElseThrow();
    El f2 = dlg2.children().getFirst();
    El b2 = findChild(f2, "block-0");
    El hoverHost = findChild(b2, "hover-host");
    float hx = dlg2.x() + f2.x() + b2.x() + hoverHost.x() + hoverHost.width() / 2;
    float hy = dlg2.y() + f2.y() + b2.y() + hoverHost.y() + hoverHost.height() / 2;
    app.pointerMove(hx, hy);
    app.frame();
    El lookPop = app.tree().shownOverlays().stream()
        .filter(o -> "look-pop".equals(o.key()))
        .findFirst()
        .orElse(null);
    assertTrue(lookPop != null && lookPop.isPlaced(), "hover look overlay on block placed");
    El lInput = findChild(lookPop, "look-input");
    assertNotNull(lInput.editor(), "editor attached to field inside look-added overlay");
  }

  @Test
  void revealUsesContentBoxAnimationDoesNotDoubleScrollAndLooksRelayoutParts() {
    String longLine = " ".repeat(40) + "void target() {}";
    State state = new State(longLine + "\nline1\nline2\nline3\nline4\nline5", El.Block.insert(0));
    state.builder = b -> El.row()
        .h(20)
        .motion(Spring.SNAP)
        .child(El.text("left")
            .key("left-btn")
            .fontSize(12)
            .focusable()
            .onPress(() -> {})
            .whenPressed(e -> e.padding(8)))
        .child(El.el().w(260))
        .child(El.text("right").key("right-btn").fontSize(12).focusable());
    App<State> app = start(
        state,
        f -> f.w(200)
            .lines(3)
            .gutter(line -> El.row()
                .padding(0, 12, 0, 0)
                .child(
                    El.text(String.valueOf(line.index() + 1)).font(Font.mono()).fontSize(14)))
            .lineEnds(line -> line == 0
                ? List.of(El.text("end")
                    .key("size-end")
                    .fontSize(12)
                    .onPress(() -> {})
                    .whenPressed(e -> e.padding(8)))
                : List.of()));

    // 1. Scroll right so left-btn sits behind the gutter (inside border box, left of content box),
    // then focus left-btn: reveal must scroll left until left-btn is clear of the gutter.
    app.scrollTo(field(app), 16, 0);
    app.frame();
    El f = field(app);
    float contentLeft = f.border(Side.LEFT) + f.padding(Side.LEFT);
    assertTrue(contentLeft > 16, "gutter widens contentLeft: " + contentLeft);
    El leftBtn = findChild(findChild(f, "block-0"), "left-btn");
    app.focus(leftBtn);
    app.frame();
    f = field(app);
    El blockAfterReveal = findChild(f, "block-0");
    El leftAfterReveal = findChild(blockAfterReveal, "left-btn");
    assertTrue(
        blockAfterReveal.x() + leftAfterReveal.x() >= contentLeft - 0.5f,
        "focused block child revealed inside content box, clear of gutter");

    // 2. Animation does not apply scroll offset twice to a block with motion.
    app.scrollTo(field(app), 60, 20);
    app.frame();
    app.advance(0.05);
    app.frame();
    f = field(app);
    El animBlock = findChild(f, "block-0");
    assertEquals(
        contentLeft - (float) f.scrollX(),
        animBlock.x(),
        1.0f,
        "animated block x applies scrollX once");
    float contentTop = f.border(Side.TOP) + f.padding(Side.TOP);
    assertEquals(
        contentTop - (float) f.scrollY(),
        animBlock.y(),
        1.0f,
        "animated block y applies scrollY once");

    // 3. Pressed look changing size re-layouts both block children and line-end elements.
    app.scrollTo(field(app), 0, 0);
    app.frame();
    f = field(app);
    El b0 = findChild(f, "block-0");
    El lBtn = findChild(b0, "left-btn");
    float beforeW = lBtn.width();
    float bx = 10 + b0.x() + lBtn.x() + lBtn.width() / 2;
    float by = 10 + b0.y() + lBtn.y() + lBtn.height() / 2;
    app.pointerDown(bx, by);
    app.frame();
    El lBtnPressed = findChild(findChild(field(app), "block-0"), "left-btn");
    assertEquals(beforeW + 16f, lBtnPressed.width(), 0.5f, "block child re-laid out on press look");
    app.pointerUp(bx, by);
    app.frame();

    // Short text so line-end element is in view; press it and verify size grows.
    state.value = "hi\nline1";
    app.invalidate();
    app.frame();
    El endBefore = findChild(field(app), "size-end");
    float endW = endBefore.width();
    float ex = 10 + endBefore.x() + endBefore.width() / 2;
    float ey = 10 + endBefore.y() + endBefore.height() / 2;
    app.pointerDown(ex, ey);
    app.frame();
    El endPressed = findChild(field(app), "size-end");
    assertEquals(
        endW + 16f, endPressed.width(), 0.5f, "line-end element re-laid out on press look");
    app.pointerUp(ex, ey);
    app.frame();
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
  void pinnedBlockStaysAtLeftEdgeWithContentWidthAndWorksWithFocusAndPairing() {
    String longLine = " ".repeat(40) + "void deeplyIndented() {}";
    String[] reply = {""};
    AtomicInteger builds = new AtomicInteger();
    State state = new State(
        longLine + "\nsecond\nthird", El.Block.insert(0), El.Block.insert(1).pinned(true));
    state.builder = b -> b.pinned()
        ? El.row()
            .key("pinned-card")
            .h(26)
            .whenHovered(e -> e.w(500).minSize(500, 0).padding(0, 8).style().boxSizing =
                Style.BoxSizing.CONTENT_BOX) // a look that tries to widen it
            .child(El.text(reply[0])
                .editable(v -> reply[0] = v)
                .key("pin-input")
                .font(Font.mono())
                .fontSize(12)
                .w(80))
            .child(El.el().grow(1))
            .child(El.text("btn").key("pin-btn").fontSize(12).onPress(() -> {}))
        : El.row().key("unpinned-row").h(20).child(El.el().w(300));
    App<State> app = new App<>(state, s -> {
      builds.incrementAndGet();
      return El.col()
          .wFull()
          .hFull()
          .padding(10)
          .child(El.text(s.value)
              .editable(v -> s.value = v)
              .multiline()
              .font(Font.mono())
              .fontSize(14)
              .w(200)
              .lines(6)
              .gutter(line -> El.row()
                  .padding(0, 4, 0, 0)
                  .child(El.text(String.valueOf(line.index() + 1))
                      .font(Font.mono())
                      .fontSize(14)))
              .blocks(s.blocks, s.builder, s::reveal));
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(300, 260, 1);
    app.frame();

    El f = field(app);
    float contentLeft = f.border(Side.LEFT) + f.padding(Side.LEFT);
    float contentWidth = f.width()
        - f.border(Side.LEFT)
        - f.padding(Side.LEFT)
        - f.border(Side.RIGHT)
        - f.padding(Side.RIGHT);
    int buildsBeforeScroll = builds.get();
    f.setScroll(220, 0); // as a wheel does, before the frame places the blocks again
    assertEquals(contentLeft, findChild(f, "pinned-card").x(), 0.5f, "pinned before placing");
    f.setScroll(0, 0);

    // Scroll right without rebuilding the tree: unpinned moves, pinned stays at contentLeft with
    // contentWidth.
    app.scrollTo(f, 220, 0);
    app.frame();
    assertEquals(buildsBeforeScroll, builds.get(), "horizontal scroll does not rebuild the tree");
    f = field(app);
    float scrolledX = (float) f.scrollX();
    assertTrue(scrolledX > 150, "field scrolled horizontally: " + scrolledX);
    El unpinned = findChild(f, "unpinned-row");
    El pinned = findChild(f, "pinned-card");
    assertEquals(
        contentLeft - scrolledX, unpinned.x(), 1.0f, "unpinned block scrolls sideways with text");
    assertEquals(contentLeft, pinned.x(), 0.5f, "pinned block stays at contentLeft");
    assertEquals(contentWidth, pinned.width(), 0.5f, "pinned block keeps content width");

    // Focusing and typing in a text field inside the pinned block keeps scrollX unchanged.
    El pinInput = findChild(pinned, "pin-input");
    float ix = 10 + pinned.x() + pinInput.x() + pinInput.width() / 2;
    float iy = 10 + pinned.y() + pinInput.y() + pinInput.height() / 2;
    app.pointerDown(ix, iy);
    app.pointerUp(ix, iy);
    app.frame();
    assertEquals("pin-input", app.focused().key(), "clicking input in pinned block focuses it");
    assertEquals(
        scrolledX, (float) field(app).scrollX(), 0.5f, "focusing in pinned block keeps scrollX");
    type(app, "ok");
    assertEquals("ok", reply[0], "typing reaches input inside pinned block");
    assertEquals(
        scrolledX, (float) field(app).scrollX(), 0.5f, "typing in pinned block keeps scrollX");

    // A look that widens a pinned block keeps it, and the controls laid out in it, in the view.
    El pinnedNow = findChild(field(app), "pinned-card");
    El pinBtn = findChild(pinnedNow, "pin-btn");
    app.pointerMove(
        10 + pinnedNow.x() + pinBtn.x() + pinBtn.width() / 2,
        10 + pinnedNow.y() + pinBtn.y() + pinBtn.height() / 2);
    app.frame();
    pinnedNow = findChild(field(app), "pinned-card");
    pinBtn = findChild(pinnedNow, "pin-btn");
    assertEquals(contentWidth, pinnedNow.width(), 0.5f, "a look keeps it at content width");
    assertEquals(
        contentWidth - 8, pinBtn.x() + pinBtn.width(), 0.5f, "its button stays at its end");

    // Pinned and paired together across two coupled non-wrapping fields.
    ScrollState leftScroll = new ScrollState();
    ScrollState rightScroll = new ScrollState();
    leftScroll.scrollWith(rightScroll, List.of());
    El.Block pairedPinned = El.Block.insert(1).paired("review").pinned(true);
    App<Void> coupled = new App<>(
        null,
        unused -> El.row()
            .wFull()
            .hFull()
            .children(
                El.text(longLine + "\nline1\nline2")
                    .editable(v -> {})
                    .multiline()
                    .font(Font.mono())
                    .fontSize(14)
                    .w(180)
                    .lines(6)
                    .scrollState(leftScroll)
                    .blocks(List.of(pairedPinned), b -> El.el().key("left-pin").h(60), null),
                El.text(longLine + "\nline1\nline2")
                    .editable(v -> {})
                    .multiline()
                    .font(Font.mono())
                    .fontSize(14)
                    .w(180)
                    .lines(6)
                    .scrollState(rightScroll)
                    .blocks(List.of(pairedPinned), b -> El.el().key("right-pin").h(20), null)));
    coupled.textRendering(TextRendering.LINUX);
    coupled.resize(400, 220, 1);
    coupled.frame();
    El leftField = coupled.tree().children().get(0);
    El rightField = coupled.tree().children().get(1);
    coupled.scrollTo(leftField, 180, 0);
    coupled.scrollTo(rightField, 180, 0);
    coupled.frame();
    leftField = coupled.tree().children().get(0);
    rightField = coupled.tree().children().get(1);
    assertTrue(leftField.scrollX() > 100 && rightField.scrollX() > 100);
    El leftPin = findChild(leftField, "left-pin");
    El rightPin = findChild(rightField, "right-pin");
    assertEquals(60f, leftPin.height(), 0.5f, "paired pinned left block keeps larger height");
    assertEquals(60f, rightPin.height(), 0.5f, "paired pinned right block matches partner height");
    assertEquals(
        leftField.border(Side.LEFT) + leftField.padding(Side.LEFT),
        leftPin.x(),
        0.5f,
        "paired pinned left block stays at contentLeft");
    assertEquals(
        rightField.border(Side.LEFT) + rightField.padding(Side.LEFT),
        rightPin.x(),
        0.5f,
        "paired pinned right block stays at contentLeft");
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

  @Test
  void blockGutterPlacesCellsForInsertAndOverridesReplace() {
    El.Block insert = El.Block.insert(1).lines(2);
    El.Block replace = El.Block.replace(2, 3).lines(1);
    State state = new State("a\nb\nc\nd\ne", insert, replace);
    App<State> app = start(
        state,
        f -> f.gutter(line -> El.text(String.valueOf(line.index() + 1))
                .font(Font.mono())
                .fontSize(14)
                .padding(0, 4, 0, 0))
            .blockGutter(block -> block.equals(insert)
                ? El.text("del").key("del-gutter").font(Font.mono()).fontSize(14)
                : El.text("...").key("rep-gutter").font(Font.mono()).fontSize(14)));
    String dump = app.tree().dump();
    assertTrue(dump.contains("#del-gutter"), dump);
    assertTrue(dump.contains("#rep-gutter"), dump);
    assertFalse(dump.contains("#line-2 "), "blockGutter replaced line 2's gutter cell: " + dump);
    assertTrue(dump.contains("#line-0 "), dump);
    assertTrue(dump.contains("#line-1 "), dump);
    assertTrue(dump.contains("#line-4 "), dump);
  }

  @Test
  void looksThatResizePartsMakeRoomAndPartsOutOfReachSkipTab() {
    State state = new State("a\nb\nc", El.Block.insert(1), El.Block.insert(0));
    state.builder = b -> b.first() == 0
        ? El.row().h(20).child(El.el().w(400)).child(El.text("far").key("far").focusable())
        : El.row()
            .key("grows")
            .child(El.text("press").h(20))
            .onPress(() -> {})
            .whenPressed(e -> e.h(60));
    App<State> app = start(
        state,
        f -> f.lineEnds(line -> line == 0
            ? List.of(
                El.text("first").key("first").w(40).onPress(() -> {}).whenPressed(e -> e.w(90)),
                El.text("second").key("second").w(50))
            : List.of()));
    El field = field(app);
    El block = findChild(field, "grows");
    double below = field.text().lineToOffset(1);
    app.pointerDown(10 + field.x() + block.x() + 100, 10 + field.y() + block.y() + 5);
    app.frame();
    field = field(app);
    assertEquals(60, findChild(field, "grows").height());
    assertEquals(below + 40, field.text().lineToOffset(1), 0.5, "the rows below make room");
    app.pointerUp(10 + field.x() + block.x() + 100, 10 + field.y() + block.y() + 5);
    app.frame();

    El first = findChild(field(app), "first");
    float gap = findChild(field(app), "second").x() - first.x() - first.width();
    app.pointerDown(10 + field(app).x() + first.x() + 5, 10 + field(app).y() + first.y() + 5);
    app.frame();
    first = findChild(field(app), "first");
    assertEquals(90, first.width());
    assertEquals(gap, findChild(field(app), "second").x() - first.x() - first.width(), 0.01);
    app.pointerUp(0, 0);
    app.frame();

    key(app, "Tab"); // from the field: "far" lies past the content box, which does not scroll
    assertNotEquals("far", app.focused() == null ? null : app.focused().key());
  }

  @Test
  void fieldsInPartsKeepTheirCaretAndComeBackWithTheirRow() {
    String[] input = {"1234567890".repeat(5)};
    boolean[] before = {false};
    State state = new State("one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten");
    App<State> app = start(
        state,
        f -> f.lineEnds(line -> {
          if (line != 2) {
            return List.of();
          }
          El nested = El.text(input[0])
              .key("nested")
              .font(Font.mono())
              .fontSize(14)
              .w(100)
              .editable(v -> input[0] = v);
          return before[0] ? List.of(El.text("x"), nested) : List.of(nested);
        }));
    app.focus(findChild(field(app), "nested"));
    app.frame();
    Editor editor = findChild(field(app), "nested").editor();
    float scroll = findChild(field(app), "nested").text().scroll();
    app.scrollTo(field(app), 0, 7); // builds the line-end element anew
    app.frame();
    El nested = findChild(field(app), "nested");
    assertEquals("nested", app.focused().key());
    assertEquals(editor, nested.editor());
    assertEquals(scroll, nested.text().scroll(), "its caret stays in view");
    before[0] = true; // an element before it on its line: it keeps its identity by its key
    app.invalidate();
    app.frame();
    assertEquals("nested", app.focused().key());
    assertEquals(editor, findChild(field(app), "nested").editor());

    String[] comment = {"hi"};
    App<String> list = new App<>(
        "",
        s -> El.col()
            .padding(10)
            .child(El.col()
                .key("list")
                .w(250)
                .h(200)
                .rows(
                    100,
                    120,
                    row -> row == 0
                        ? El.text("a\nb")
                            .key("f")
                            .editable(v -> {})
                            .multiline()
                            .lines(3)
                            .blocks(
                                List.of(El.Block.insert(0)),
                                b -> El.row()
                                    .child(El.text(comment[0])
                                        .key("comment")
                                        .w(100)
                                        .editable(v -> comment[0] = v)),
                                b -> {})
                        : El.text("row " + row))));
    list.textRendering(TextRendering.LINUX);
    list.resize(320, 250, 1);
    list.frame();
    El row = list.tree().children().getFirst().children().getFirst();
    list.focus(findChild(findChild(row, "block-0"), "comment"));
    list.frame();
    list.scrollToRow(list.tree().children().getFirst(), 50);
    list.frame();
    type(list, "!"); // brings the row back, with the block the focus is in
    assertEquals("comment", list.focused().key());
    assertEquals("hi!", comment[0]);
  }

  @Test
  void partsWithRepeatedKeysStayApartAndCachedBlocksDropLooksThatEnd() {
    String[] input = {"edit"};
    State state = new State(
        "1234567890".repeat(20) + "\n" + "line\n".repeat(20),
        El.Block.insert(0),
        El.Block.insert(3));
    state.builder = b -> b.first() == 0
        ? El.row()
            .h(24)
            .child(El.el().w(250).shrink(0))
            .child(El.text(input[0]).key("item").w(60).shrink(0).editable(v -> input[0] = v))
        : El.row().key("hovered").h(20).whenHovered(e -> e.h(60));
    App<State> app = start(
        state,
        f -> f.gutter(line -> El.text("x").key("cell"))
            .lineEnds(line -> line == 1 || line == 2
                ? List.of(El.text("run").key("run"), El.text("a"), El.text("b").key("end-1-1"))
                : List.of())); // the second's generated key on line 1 is end-1-1 too
    assertEquals(
        2,
        field(app).children().stream().filter(c -> "run".equals(c.key())).count(),
        "the same key on two lines");

    // A block wider than the view keeps its width once its field has an editor.
    app.scrollTo(field(app), 170, 0);
    app.frame();
    El block = findChild(field(app), "block-0");
    El item = findChild(block, "item");
    float x = 10 + field(app).x() + block.x() + item.x() + 10;
    float y = 10 + field(app).y() + block.y() + item.y() + item.height() / 2;
    app.pointerDown(x, y);
    app.pointerUp(x, y);
    app.frame();
    assertEquals(310, findChild(field(app), "block-0").width(), 0.5);
    assertEquals("item", app.focused().key());

    // Scrolled away from a still pointer, a block drops the look the cache kept.
    app.scrollTo(field(app), 0, 0);
    app.frame();
    El hovered = findChild(field(app), "hovered");
    app.pointerMove(10 + field(app).x() + hovered.x() + 30, 10 + field(app).y() + hovered.y() + 5);
    app.frame();
    assertEquals(60, findChild(field(app), "hovered").height());
    app.scrollTo(field(app), 0, 80); // past the pointer, which stays where it was
    app.frame();
    assertEquals(20, findChild(field(app), "hovered").height());
  }

  @Test
  void overlayInBlockTakesAndRestoresFocusWithoutAutofocus() {
    boolean[] menuOpen = {false};
    boolean[] dialogOpen = {false};
    boolean[] empty = {false}; // a modal dialog without a focusable element
    State state = new State("line0\nline1", El.Block.insert(0));
    state.builder = b -> {
      El trigger = El.text("open")
          .key("open-btn")
          .fontSize(12)
          .focusable()
          .onPress(() -> menuOpen[0] = !menuOpen[0]);
      if (menuOpen[0]) {
        trigger.overlay(El.col()
            .key("block-menu")
            .padding(4)
            .fill(0xFF333344)
            .child(El.text("Rename").key("item-1").fontSize(12).focusable().onPress(() -> {
              menuOpen[0] = false;
              dialogOpen[0] = true;
            }))
            .child(El.text("Second").key("item-2").fontSize(12).focusable()));
      }
      El other = El.text("other")
          .key("other-btn")
          .fontSize(12)
          .focusable()
          .onPress(() -> menuOpen[0] = true);
      return El.row().h(20).child(trigger).child(other);
    };
    App<State> app = start(
        state,
        f -> !dialogOpen[0]
            ? f
            : f.overlay(
                empty[0]
                    ? El.col().key("dialog").modal().child(El.text("Wait"))
                    : El.col().key("dialog").child(El.text("OK").key("ok").focusable())));

    El trigger = findChild(findChild(field(app), "block-0"), "open-btn");
    app.focus(trigger);
    app.frame();
    assertEquals("open-btn", app.focused().key());

    key(app, "Enter");
    assertEquals("item-1", app.focused().key(), "menu opened from block focuses first item");

    menuOpen[0] = false;
    app.invalidate();
    app.frame();
    assertEquals("open-btn", app.focused().key(), "closing block menu restores focus to opener");

    // An item that closes the menu and opens a dialog: closing the dialog goes back to where the
    // focus was before the menu, not to the element the menu hangs on.
    app.focus(findChild(findChild(field(app), "block-0"), "other-btn"));
    app.frame();
    key(app, "Enter");
    assertEquals("item-1", app.focused().key());
    key(app, "Enter");
    assertEquals("ok", app.focused().key());
    dialogOpen[0] = false;
    app.invalidate();
    app.frame();
    assertEquals("other-btn", app.focused().key(), "the dialog gives the focus back past the menu");

    // The same with a modal dialog that holds the focus nowhere: the menu closing keeps it there.
    empty[0] = true;
    key(app, "Enter");
    key(app, "Enter");
    assertNull(app.focused(), "the modal dialog keeps the focus out of the field");
    dialogOpen[0] = false;
    app.invalidate();
    app.frame();
    assertEquals("other-btn", app.focused().key());
  }

  @Test
  void repeatedExplicitBlockKeysAtSameLineThrow() {
    // Each far higher than the view, so that the two are never in view together.
    State state = new State(
        "line0\nline1", El.Block.insert(0).lines(100), El.Block.insert(0).lines(100));
    state.builder = b -> El.row().key("dup").child(El.text("block").fontSize(12));
    App<State> app = start(state, UnaryOperator.identity());
    app.scrollTo(field(app), 0, 0); // the caret at the end showed the second
    IllegalStateException error = assertThrows(IllegalStateException.class, app::frame);
    assertTrue(error.getMessage().contains("line 0"), error.getMessage());
    assertTrue(error.getMessage().contains("dup"), error.getMessage());
  }

  @Test
  void lineEndLookNarrowingRevealsHiddenElementAndWideningDropsOverflow() {
    State state = new State("a\nb");
    App<State> app = start(
        state,
        f -> f.lineEnds(line -> switch (line) {
          case 0 ->
            List.of(
                El.text("wide").key("narrower").w(220).onPress(() -> {}).whenPressed(e -> e.w(40)),
                El.text("hidden").key("revealed").w(50));
          case 1 ->
            List.of(
                El.text("small").key("wider").w(40).onPress(() -> {}).whenPressed(e -> e.w(220)),
                El.text("pushed").key("pushed").w(50));
          default -> List.of();
        }));

    // Line 0: "revealed" starts past contentWidth and appears as soon as "narrower" shrinks.
    assertTrue(
        field(app).children().stream().noneMatch(c -> "revealed".equals(c.key())),
        "second element starts out of view");
    El narrower = findChild(field(app), "narrower");
    float nx = 10 + field(app).x() + narrower.x() + 5;
    float ny = 10 + field(app).y() + narrower.y() + 5;
    app.pointerDown(nx, ny);
    app.frame();
    El revealed = findChild(field(app), "revealed");
    assertEquals(40, findChild(field(app), "narrower").width(), 0.01);
    assertTrue(
        revealed.x() > findChild(field(app), "narrower").x() + 40,
        "revealed element is placed after narrowed element");
    app.pointerUp(nx, ny);
    app.frame();
    assertTrue(
        field(app).children().stream().noneMatch(c -> "revealed".equals(c.key())),
        "releasing hides trailing element again");

    // Line 1: "pushed" starts in view and is dropped once "wider" pushes it past contentWidth.
    assertNotNull(findChild(field(app), "pushed"));
    El wider = findChild(field(app), "wider");
    float wx = 10 + field(app).x() + wider.x() + 5;
    float wy = 10 + field(app).y() + wider.y() + 5;
    app.pointerDown(wx, wy);
    app.frame();
    assertEquals(220, findChild(field(app), "wider").width(), 0.01);
    assertTrue(
        field(app).children().stream().noneMatch(c -> "pushed".equals(c.key())),
        "widening drops element pushed past content box");
    app.pointerUp(wx, wy);
    app.frame();
    assertNotNull(findChild(field(app), "pushed"));
  }
}
