package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Multi-line text position under the pointer ({@link El#textPositionAt}), pointer callbacks ({@link
 * El#onTextPointerMove}, {@link El#onTextHoverFor}), and range-anchored
 * overlays ({@link El#atRange}).
 */
class TextPositionTest {

  private static final class State {
    String value;
    final List<El.Block> blocks = new ArrayList<>();
    final List<El.Fold> folds = new ArrayList<>();
    int rangeFrom = -1;
    int rangeTo = -1;
    final List<String> log = new ArrayList<>();

    State(String value) {
      this.value = value;
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
                .folds(s.folds, s.folds::remove)
                .blocks(
                    s.blocks,
                    b -> El.row()
                        .h(24)
                        .fill(0xFF26262E)
                        .child(El.text("block " + b.first()).fontSize(12)),
                    s.blocks::remove))));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 260, 1);
    app.frame();
    return app;
  }

  private static El findOverlay(App<?> app, String key) {
    for (El overlay : app.tree().shownOverlays()) {
      if (key.equals(overlay.key())) {
        return overlay;
      }
    }
    return null;
  }

  @Test
  void textPositionAtHandlesPlainTextPastLineEndEmptyLineBlocksFoldsAndComposition() {
    // Plain text with an empty middle line.
    State plain = new State("hello world\n\nsecond");
    App<State> app = start(plain, UnaryOperator.identity());
    El f = field(app);
    float[] boxH = f.editor().rangeBox(0, 1);
    float[] boxW = f.editor().rangeBox(6, 7);
    float[] boxS = f.editor().rangeBox(13, 14);
    assertNotNull(boxH);
    assertNotNull(boxW);
    assertNotNull(boxS);
    assertEquals(0, f.textPositionAt(boxH[0] + boxH[2] / 2, boxH[1] + boxH[3] / 2));
    assertEquals(6, f.textPositionAt(boxW[0] + boxW[2] / 2, boxW[1] + boxW[3] / 2));
    assertEquals(13, f.textPositionAt(boxS[0] + boxS[2] / 2, boxS[1] + boxS[3] / 2));
    // Past the end of line 0, on empty line 1, below the last line, and in left padding.
    assertEquals(-1, f.textPositionAt(200, boxH[1] + boxH[3] / 2));
    assertEquals(-1, f.textPositionAt(boxH[0] + 2, (boxH[1] + boxS[1]) / 2 + boxH[3] / 2));
    assertEquals(-1, f.textPositionAt(boxH[0] + 2, boxS[1] + boxS[3] + 20));
    assertEquals(-1, f.textPositionAt(1, boxH[1] + boxH[3] / 2));

    // IME composition maps shown positions back to the app's string.
    focus(app);
    app.select(field(app), 0, 0);
    app.frame();
    app.textComposition("XYZ", 3);
    app.frame();
    f = field(app);
    float[] boxWComposing = f.editor().rangeBox(6, 7);
    assertNotNull(boxWComposing);
    assertEquals(
        6,
        f.textPositionAt(
            boxWComposing[0] + boxWComposing[2] / 2, boxWComposing[1] + boxWComposing[3] / 2));
    app.textComposition("", 0);
    app.frame();

    // Blocks (INSERT and REPLACE) report -1 inside the block band and true offsets after.
    State withBlocks = new State("line0\nreplaced1\nreplaced2\nafter\nfinal");
    withBlocks.blocks.add(El.Block.replace(1, 2));
    withBlocks.blocks.add(El.Block.insert(3));
    App<State> blocksApp = start(withBlocks, UnaryOperator.identity());
    El bf = field(blocksApp);
    int afterStart = withBlocks.value.indexOf("after");
    float[] box0 = bf.editor().rangeBox(0, 1);
    float[] boxAfter = bf.editor().rangeBox(afterStart, afterStart + 1);
    assertNotNull(box0);
    assertNotNull(boxAfter);
    // Point inside the REPLACE block (between line 0 and the INSERT block) and inside the INSERT
    // block (right above "after").
    assertEquals(-1, bf.textPositionAt(box0[0] + 4, box0[1] + box0[3] + 10));
    assertEquals(-1, bf.textPositionAt(box0[0] + 4, boxAfter[1] - 10));
    assertEquals(
        afterStart,
        bf.textPositionAt(boxAfter[0] + boxAfter[2] / 2, boxAfter[1] + boxAfter[3] / 2));

    // Folds: header line hits its text, past header end is -1, line after fold maps to true offset.
    State withFold = new State("head {\n  hidden1\n  hidden2\n}\ntail");
    withFold.folds.add(new El.Fold(0, 2));
    App<State> foldApp = start(withFold, UnaryOperator.identity());
    El ff = field(foldApp);
    int tailStart = withFold.value.indexOf("tail");
    float[] boxHead = ff.editor().rangeBox(0, 1);
    float[] boxTail = ff.editor().rangeBox(tailStart, tailStart + 1);
    assertNotNull(boxHead);
    assertNotNull(boxTail);
    assertEquals(0, ff.textPositionAt(boxHead[0] + boxHead[2] / 2, boxHead[1] + boxHead[3] / 2));
    assertEquals(-1, ff.textPositionAt(200, boxHead[1] + boxHead[3] / 2));
    assertEquals(
        tailStart, ff.textPositionAt(boxTail[0] + boxTail[2] / 2, boxTail[1] + boxTail[3] / 2));
  }

  @Test
  void pointerCallbacksReportMovesModifierChangesAndDelayedHover() {
    State state = new State("alpha beta\ngamma");
    App<State> app = start(
        state,
        el -> el.onTextPointerMove((pos, mods) -> state.log.add("move:" + pos + ":" + mods))
            .onTextHoverFor(0.5, (pos, mods) -> state.log.add("hover:" + pos + ":" + mods)));
    El f = field(app);
    int beta = state.value.indexOf("beta");
    float[] boxB = f.editor().rangeBox(beta, beta + 1);
    float[] boxE = f.editor().rangeBox(beta + 1, beta + 2);
    assertNotNull(boxB);
    assertNotNull(boxE);
    float bx = f.x() + boxB[0] + boxB[2] / 2;
    float by = f.y() + boxB[1] + boxB[3] / 2;
    float ex = f.x() + boxE[0] + boxE[2] / 2;
    float ey = f.y() + boxE[1] + boxE[3] / 2;

    // Move onto 'b' of "beta": fires onTextPointerMove, schedules nextChange for onTextHoverFor.
    app.pointerMove(bx, by);
    app.frame();
    assertEquals(List.of("move:" + beta + ":0"), state.log);
    assertEquals(0.5, app.nextChange(), 1e-6);

    // Modifier-only change while pointer stays still fires onTextPointerMove with updated
    // modifiers.
    state.log.clear();
    app.keyDown(new Key("Control", Key.CONTROL, false));
    app.frame();
    assertEquals(List.of("move:" + beta + ":" + Key.CONTROL), state.log);

    state.log.clear();
    app.keyUp(new Key("Control", 0, false));
    app.frame();
    assertEquals(List.of("move:" + beta + ":0"), state.log);

    // Advancing past delay fires onTextHoverFor once.
    state.log.clear();
    app.advance(0.5);
    app.frame();
    assertEquals(List.of("hover:" + beta + ":0"), state.log);
    state.log.clear();
    app.advance(0.5);
    app.frame();
    assertEquals(List.of(), state.log);

    // Moving to 'e' with Control held restarts the hover; a press suppresses it.
    state.log.clear();
    app.pointerMove(ex, ey, Key.CONTROL);
    app.frame();
    assertEquals(List.of("move:" + (beta + 1) + ":" + Key.CONTROL), state.log);
    state.log.clear();
    app.pointerDown(ex, ey, 1, 0);
    app.frame(); // released in a later frame, as a hand does
    app.pointerUp(ex, ey);
    app.frame();
    app.advance(1.0);
    app.frame();
    assertEquals(List.of(), state.log, "hover suppressed after press");

    // Moving past line end reports -1, and modifier change while at -1 does not fire.
    state.log.clear();
    app.pointerMove(f.x() + 200, by);
    app.frame();
    assertEquals(List.of("move:-1:0"), state.log);
    state.log.clear();
    app.keyDown(new Key("Control", Key.CONTROL, false));
    app.frame();
    assertEquals(List.of(), state.log);
  }

  @Test
  void atRangeAnchorsToWordBoxFollowsScrollAndHidesWhenScrolledAwayOrFolded() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append("line").append(i).append(" target").append(i);
    }
    State state = new State(sb.toString());
    int target = state.value.indexOf("target1");
    state.rangeFrom = target;
    state.rangeTo = target + "target1".length();

    App<State> app = start(state, el -> {
      if (state.rangeFrom >= 0) {
        el.overlay(El.el()
            .key("tip")
            .size(80, 24)
            .fill(0xFF3B82F6)
            .atRange(state.rangeFrom, state.rangeTo)
            .side(Side.BOTTOM, Style.Align.START, 4));
      }
      return el;
    });
    El f = field(app);
    float[] box = f.editor().rangeBox(state.rangeFrom, state.rangeTo);
    assertNotNull(box);
    El tip = findOverlay(app, "tip");
    assertNotNull(tip);
    assertEquals(f.x() + box[0], tip.x(), 0.5f, "aligned with start of target1");
    assertEquals(f.y() + box[1] + box[3] + 4, tip.y(), 0.5f, "placed 4px below target1");
    float initialY = tip.y();

    // Scrolling the field moves the overlay without rebuilding the view.
    app.wheel(f.x() + 40, f.y() + 40, 0, 10);
    app.frame();
    tip = findOverlay(app, "tip");
    assertNotNull(tip);
    assertTrue(tip.y() < initialY, "overlay followed scroll up");

    // Scrolling far enough hides the range overlay.
    app.wheel(f.x() + 40, f.y() + 40, 0, 200);
    app.frame();
    assertNull(findOverlay(app, "tip"), "overlay hidden when range scrolled out of view");

    // Scrolling back reveals it again.
    app.wheel(f.x() + 40, f.y() + 40, 0, -250);
    app.frame();
    assertNotNull(findOverlay(app, "tip"), "overlay shown again after scrolling back");

    // Folding the line containing the range hides the overlay.
    state.folds.add(new El.Fold(0, 2));
    app.invalidate();
    app.frame();
    assertFalse(app.tree().shownOverlays().stream().anyMatch(o -> "tip".equals(o.key())));
  }
}
