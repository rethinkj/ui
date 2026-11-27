package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Scroll areas that keep their offsets in a {@link ScrollState} of the app's ({@link
 * El#scrollState}): they leave the tree, as the content of a tab that is not active, or go to the
 * tree of another app, as a tab to another window, and come back where they were.
 */
class ScrollStateTest {

  /** Two tabs, each a list of rows and a panel that scrolls both ways; the view shows one. */
  static final class Model {
    final ScrollState[] rows = {new ScrollState(), new ScrollState()};
    final ScrollState[] panels = {new ScrollState(), new ScrollState()};
    final RowHeights[] heights = {new RowHeights(i -> 20), new RowHeights(i -> 20)};
    int active;
    boolean keep = true;
    boolean shared;
    boolean measured; // the list measures its rows instead of a fixed height
    int reveal = -1;
  }

  private static El view(Model m) {
    int t = m.active;
    El list = (m.measured
            ? col().rows(1000, m.heights[t], i -> el().h(10 + i % 3 * 10))
            : col().rows(1000, 20, i -> el().h(20)))
        .key("list-" + t)
        .w(100)
        .h(100)
        .revealRow(m.reveal);
    El panel = col().key("panel-" + t).scrollBoth().size(100, 100).child(el().size(400, 400));
    if (m.keep) {
      list.scrollState(m.rows[t]);
      panel.scrollState(m.panels[m.shared ? 0 : t]);
      if (m.shared) {
        list.scrollState(m.panels[0]);
      }
    }
    return El.row().children(list, panel);
  }

  private static App<Model> app(Model model) {
    App<Model> app = new App<>(model, ScrollStateTest::view);
    app.resize(300, 200, 1);
    app.frame();
    return app;
  }

  private static El list(App<?> app) {
    return app.tree().children().get(0);
  }

  private static El panel(App<?> app) {
    return app.tree().children().get(1);
  }

  private static void choose(App<Model> app, int tab) {
    app.state().active = tab;
    app.invalidate();
    app.frame();
  }

  @Test
  void theOffsetsComeBackAfterAnotherTab() {
    for (boolean measured : new boolean[] {false, true}) {
      Model model = new Model();
      model.measured = measured;
      App<Model> app = app(model);
      app.wheel(50, 50, 0, 1234);
      app.wheel(150, 50, 70, 90);
      app.frame();
      double down = list(app).scrollY();
      assertTrue(down > 1000, "scrolled");
      assertEquals(70, panel(app).scrollX());
      assertEquals(down, model.rows[0].y());

      choose(app, 1);
      assertEquals(0, list(app).scrollY(), "the other tab at its start");
      assertEquals(0, panel(app).scrollX());
      choose(app, 0);
      assertEquals(down, list(app).scrollY(), "back where it was");
      assertEquals(70, panel(app).scrollX());
      assertEquals(90, panel(app).scrollY());
      assertTrue(app.tree().dump().contains(" scroll 70,90"), app.tree().dump());
    }
  }

  @Test
  void withoutOneTheAreasForget() {
    Model model = new Model();
    model.keep = false;
    App<Model> app = app(model);
    app.wheel(50, 50, 0, 500);
    app.frame();
    assertEquals(500, list(app).scrollY());
    choose(app, 1);
    choose(app, 0);
    assertEquals(0, list(app).scrollY());
  }

  @Test
  void aViewThatAsksForTheStartRebuildsWhenScrollingOrLayoutChangesTheAnswer() {
    App<ScrollState> app = new App<>(
        new ScrollState(),
        state -> col()
            .wFull()
            .hFull()
            .child(col().grow(1).minSize(0, 0).scroll().scrollState(state).child(el().h(150)))
            .child(
                state.atStart() ? null : el().key("more-above").absolute(0, 0).size(2, 2)));
    app.resize(100, 60, 1);
    app.frame();
    assertEquals(1, app.tree().children().size());

    app.wheel(50, 30, 0, 30);
    app.frame();
    assertEquals("more-above", app.tree().children().getLast().key(), app.tree().dump());

    app.wheel(50, 30, 0, -30);
    app.frame();
    assertEquals(1, app.tree().children().size());

    app.wheel(50, 30, 0, 30);
    app.frame();
    app.resize(100, 160, 1);
    app.frame();
    assertEquals(0, app.state().y(), "layout clamps the offset when the content fits");
    assertEquals(1, app.tree().children().size());
  }

  @Test
  void twoAreasCannotShareOne() {
    Model model = new Model();
    model.shared = true;
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> app(model));
    assertTrue(e.getMessage().contains("ScrollState of its own"), e.getMessage());
  }

  @Test
  void aRowItKeptInViewDoesNotPullItBack() {
    Model model = new Model();
    model.reveal = 2;
    App<Model> app = app(model);
    app.wheel(50, 50, 0, 3000);
    app.frame();
    choose(app, 1);
    choose(app, 0);
    assertEquals(3000, list(app).scrollY(), "the row it showed before it left counts as seen");
    model.reveal = 3; // a new row to keep in view still scrolls
    app.invalidate();
    app.frame();
    assertEquals(60, list(app).scrollY());
  }

  @Test
  void anAreaThatMovesToAnotherAppComesAsItWas() {
    for (boolean arrivingFirst : new boolean[] {true, false}) {
      Model model = new Model();
      int[] shown = {0, 1}; // the tab each of two windows shows
      App<Model> from = window(model, shown, 0);
      App<Model> to = window(model, shown, 1);
      from.wheel(50, 50, 0, 777);
      from.wheel(150, 50, 33, 44);
      from.frame();

      shown[0] = 1; // the tabs change windows
      shown[1] = 0;
      for (App<Model> app : arrivingFirst ? List.of(to, from) : List.of(from, to)) {
        app.invalidate();
        app.frame();
      }
      assertEquals(777, list(to).scrollY(), "the rows where they were");
      assertEquals(33, panel(to).scrollX());
      assertEquals(44, panel(to).scrollY());
      assertEquals(0, list(from).scrollY(), "and the other tab where it was");
      to.wheel(50, 50, 0, -77);
      to.frame();
      assertEquals(700, model.rows[0].y());

      shown[0] = 0; // and back
      shown[1] = 1;
      for (App<Model> app : arrivingFirst ? List.of(from, to) : List.of(to, from)) {
        app.invalidate();
        app.frame();
      }
      assertEquals(700, list(from).scrollY());
      assertEquals(33, panel(from).scrollX());
    }
  }

  private static App<Model> window(Model model, int[] shown, int index) {
    App<Model> app = new App<>(model, m -> {
      m.active = shown[index];
      return view(m);
    });
    app.resize(300, 200, 1);
    app.frame();
    return app;
  }

  /** Two multiline fields coupled with {@link ScrollState#scrollWith}. */
  static final class Coupled {
    final ScrollState left = new ScrollState();
    final ScrollState right = new ScrollState();
    final FieldState leftField = new FieldState();
    final FieldState rightField = new FieldState();
    String leftText;
    String rightText;
    List<El.Block> leftBlocks = List.of();
    boolean showRight = true;

    Coupled(int leftLines, int rightLines) {
      this.leftText = numberedLines(leftLines);
      this.rightText = numberedLines(rightLines);
    }

    private static String numberedLines(int count) {
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < count; i++) {
        if (i > 0) {
          out.append('\n');
        }
        out.append("line ").append(i).append(" — a wide enough line for sideways scrolling");
      }
      return out.toString();
    }
  }

  private static App<Coupled> coupledApp(Coupled model) {
    App<Coupled> app = new App<>(model, m -> {
      El left = El.text(m.leftText)
          .editable(v -> m.leftText = v)
          .multiline()
          .key("left")
          .font(Font.mono())
          .fontSize(14)
          .w(180)
          .lines(6)
          .padding(4)
          .scrollBoth()
          .fieldState(m.leftField)
          .scrollState(m.left);
      if (!m.leftBlocks.isEmpty()) {
        left.blocks(m.leftBlocks, b -> el().h(10), null);
      }
      El root = El.row().wFull().hFull().padding(10).gap(10).child(left);
      if (m.showRight) {
        El right = El.text(m.rightText)
            .editable(v -> m.rightText = v)
            .multiline()
            .key("right")
            .font(Font.mono())
            .fontSize(14)
            .w(180)
            .lines(6)
            .padding(4)
            .scrollBoth()
            .fieldState(m.rightField)
            .scrollState(m.right);
        root.child(right);
      }
      return root;
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(420, 200, 1);
    app.frame();
    return app;
  }

  @Test
  void coupledScrollMapsByAnchorsAndBlocksBothWays() {
    TextLibraries.assumeLoaded();
    Coupled model = new Coupled(60, 80);
    model.left.scrollWith(
        model.right, List.of(new ScrollState.Anchor(10, 15), new ScrollState.Anchor(20, 35)));
    App<Coupled> app = coupledApp(model);
    El left = app.tree().children().get(0);
    double lh = left.text().lineHeight() / left.text().scale();

    // Before first anchor (diff +5): left line 5 -> right line 10.
    app.wheel(50, 50, 0, (float) (5 * lh));
    app.frame();
    assertEquals(5 * lh, model.left.y(), 0.5);
    assertEquals(10 * lh, model.right.y(), 0.5);

    // Midpoint between (10,15) and (20,35): left line 15 -> right line 25.
    app.wheel(50, 50, 0, (float) (10 * lh));
    app.frame();
    assertEquals(15 * lh, model.left.y(), 0.5);
    assertEquals(25 * lh, model.right.y(), 0.5);

    // After last anchor (diff +15): left line 25 -> right line 40.
    app.wheel(50, 50, 0, (float) (10 * lh));
    app.frame();
    assertEquals(25 * lh, model.left.y(), 0.5);
    assertEquals(40 * lh, model.right.y(), 0.5);

    // Reverse: scrolling right back to line 25 moves left back to line 15.
    app.wheel(250, 50, 0, (float) (-15 * lh));
    app.frame();
    assertEquals(25 * lh, model.right.y(), 0.5);
    assertEquals(15 * lh, model.left.y(), 0.5);

    // Sideways: both keep the same horizontal offset.
    app.wheel(50, 50, 40, 0);
    app.frame();
    assertEquals(40, model.left.x(), 0.5);
    assertEquals(40, model.right.x(), 0.5);

    // Blocks between lines count in the distance to the next line's top.
    model.leftBlocks = List.of(El.Block.insert(12).lines(3));
    model.left.scrollWith(model.right, List.of()); // line n -> line n
    app.invalidate();
    app.frame();
    // Taking kept offsets on rebuild must NOT move the other one.
    assertEquals(15 * lh, model.left.y(), 0.5);
    assertEquals(25 * lh, model.right.y(), 0.5);

    // ScrollState.scrollTo moves the partner on the next build: line 11 + half of (1 line + 3-line
    // block) = 11 + 2 lines = fractional line 11.5 -> right at 11.5 * lh.
    model.left.scrollTo(0, 13 * lh);
    app.invalidate();
    app.frame();
    assertEquals(13 * lh, model.left.y(), 0.5);
    assertEquals(11.5 * lh, model.right.y(), 0.5);

    // Caret reveal in right moves left as well.
    model.rightField.select(model.rightText.length(), model.rightText.length());
    app.invalidate();
    app.frame();
    assertTrue(model.right.y() > 50 * lh, "right scrolled to end");
    assertTrue(model.left.y() > 40 * lh, "left followed caret reveal");

    // Partner out of tree is left alone.
    double savedRight = model.right.y();
    model.showRight = false;
    app.invalidate();
    app.frame();
    app.wheel(50, 50, 0, (float) (-20 * lh));
    app.frame();
    assertEquals(savedRight, model.right.y(), 0.5);

    // Uncoupling with null stops following for both.
    model.showRight = true;
    model.left.scrollWith(null, List.of());
    app.invalidate();
    app.frame();
    double rightBefore = model.right.y();
    app.wheel(50, 50, 0, (float) (5 * lh));
    app.frame();
    assertEquals(rightBefore, model.right.y(), 0.5);

    assertThrows(
        IllegalArgumentException.class, () -> model.left.scrollWith(model.left, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new ScrollState.Anchor(-1, 0));
    List<ScrollState.Anchor> crossing =
        List.of(new ScrollState.Anchor(10, 15), new ScrollState.Anchor(20, 15));
    assertThrows(
        IllegalArgumentException.class, () -> model.left.scrollWith(model.right, crossing));
  }

  @Test
  void pairedBlocksMatchHeightsAcrossCoupledFieldsWhenGrowingShrinkingAndOffScreen() {
    TextLibraries.assumeLoaded();

    final class Diff {
      final ScrollState left = new ScrollState();
      final ScrollState right = new ScrollState();
      final FieldState leftField = new FieldState();
      final FieldState rightField = new FieldState();
      final String text = Coupled.numberedLines(35);
      String reply = "one";
      float rightHeight;
      float top;
    }

    Diff model = new Diff();
    model.left.scrollWith(model.right, List.of(new ScrollState.Anchor(0, 1)));
    El.Block leftComment = El.Block.insert(2).paired("comment");
    El.Block rightComment = El.Block.insert(2).paired("comment");
    El.Block leftShort = El.Block.insert(3).paired("short");
    El.Block rightShort = El.Block.insert(30).paired("short"); // off screen on right
    El.Block leftFar = El.Block.insert(28).paired("far"); // off screen on both
    El.Block rightFar = El.Block.insert(28).paired("far");
    El.Block leftTop = El.Block.insert(0).paired("top");
    El.Block rightTop = El.Block.insert(1).paired("top"); // before the line left's line 0 aligns
    // A filler of a fixed line count comes first, as in a diff.
    El.Block leftFiller = El.Block.insert(0).lines(1);
    El.Block rightFiller = El.Block.insert(1).lines(1);

    App<Diff> app = new App<>(model, m -> {
      El left = El.text(m.text)
          .editable(v -> {})
          .multiline()
          .key("left")
          .font(Font.mono())
          .fontSize(14)
          .w(180)
          .lines(8)
          .padding(4)
          .fieldState(m.leftField)
          .scrollState(m.left)
          .blocks(
              List.of(leftFiller, leftTop, leftComment, leftShort, leftFar),
              b -> {
                if ("comment".equals(b.pair())) {
                  return El.col()
                      .padding(4)
                      .fill(0xFF26262E)
                      .child(El.text(m.reply)
                          .editable(v -> m.reply = v)
                          .multiline()
                          .fitLines()
                          .maxLines(5)
                          .key("reply")
                          .font(Font.mono())
                          .fontSize(12)
                          .w(150));
                }
                if ("short".equals(b.pair())) {
                  return el().h(8);
                }
                if ("top".equals(b.pair())) {
                  return el().h(m.top);
                }
                return el().h(64);
              },
              null);
      El right = El.text(m.text)
          .editable(v -> {})
          .multiline()
          .key("right")
          .font(Font.mono())
          .fontSize(14)
          .w(180)
          .lines(8)
          .padding(4)
          .fieldState(m.rightField)
          .scrollState(m.right)
          .blocks(
              List.of(rightFiller, rightTop, rightComment, rightShort, rightFar),
              b -> "comment".equals(b.pair()) ? el().h(m.rightHeight).fill(0x20888888) : el(),
              null);
      return El.row().wFull().hFull().padding(10).gap(10).children(left, right);
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(420, 260, 1);
    app.frame();

    El left = app.tree().children().get(0);
    El right = app.tree().children().get(1);
    El leftBlock = blockChild(left, "block-2");
    El rightBlock = blockChild(right, "block-2");
    float h1 = leftBlock.height();
    assertTrue(h1 > 10, "comment block measured its height: " + h1);
    assertEquals(h1, rightBlock.height(), 0.5f, "spacer matches comment height on initial frame");
    assertEquals(
        left.editor().lineIndex().lineTop(2, left.text().lineHeight()),
        right.editor().lineIndex().lineTop(2, right.text().lineHeight()),
        0.5,
        "lines after paired block stay aligned");
    assertEquals(
        left.text().extentHeight(),
        right.text().extentHeight(),
        0.5,
        "total height matches including off-screen paired blocks");

    // Unmeasured partner takes the measured side's height even when smaller than one line, and
    // a pair unmeasured on both sides uses the same single-line estimate on both sides.
    Lines leftLines = left.editor().lineIndex();
    Lines rightLines = right.editor().lineIndex();
    assertEquals(8, rightLines.blockHeight(4), "unmeasured partner takes measured 8px height");
    assertEquals(
        leftLines.blockHeight(4),
        rightLines.blockHeight(3),
        "unmeasured pair uses the same estimate on both sides");

    // Focus the reply field inside left's block and add a second line: partner grows in the same
    // frame.
    El reply = blockChild(leftBlock, "reply");
    float rx = left.x() + leftBlock.x() + reply.x() + reply.width() - 4;
    float ry = left.y() + leftBlock.y() + reply.y() + reply.height() / 2;
    app.pointerDown(rx, ry);
    app.pointerUp(rx, ry);
    app.frame();
    assertEquals("reply", app.focused().key());

    key(app, "Enter");
    type(app, "two");
    left = app.tree().children().get(0);
    right = app.tree().children().get(1);
    float h2 = blockChild(left, "block-2").height();
    assertTrue(h2 > h1 + 5, "reply grew with a second line: " + h1 + " -> " + h2);
    assertEquals(
        h2, blockChild(right, "block-2").height(), 0.5f, "partner grew to match in the same frame");
    assertEquals(left.text().extentHeight(), right.text().extentHeight(), 0.5);

    // Delete the second line: both sides shrink back to h1 in the same frame.
    key(app, "Backspace");
    key(app, "Backspace");
    key(app, "Backspace");
    key(app, "Backspace");
    left = app.tree().children().get(0);
    right = app.tree().children().get(1);
    assertEquals(h1, blockChild(left, "block-2").height(), 0.5f, "left shrank back");
    assertEquals(
        h1, blockChild(right, "block-2").height(), 0.5f, "partner shrank back in the same frame");
    assertEquals(left.text().extentHeight(), right.text().extentHeight(), 0.5);

    // Both sides measured: max wins in either direction, and shrinking the taller side shrinks
    // both back to the other side's height.
    model.rightHeight = 90;
    app.invalidate();
    app.frame();
    left = app.tree().children().get(0);
    right = app.tree().children().get(1);
    assertEquals(90, blockChild(left, "block-2").height(), 0.5f, "left matches taller right");
    assertEquals(90, blockChild(right, "block-2").height(), 0.5f);

    model.rightHeight = 0;
    app.invalidate();
    app.frame();
    left = app.tree().children().get(0);
    right = app.tree().children().get(1);
    assertEquals(h1, blockChild(left, "block-2").height(), 0.5f);
    assertEquals(h1, blockChild(right, "block-2").height(), 0.5f);

    // A pair right after the fillers before the lines anchored together grows from 0 px: both
    // views keep their offsets, so the pair grows into view on both sides.
    double lh = right.text().lineHeight();
    model.right.scrollTo(0, 2 * lh); // its line 1 at the top; left follows with its line 0
    app.invalidate();
    app.frame();
    assertEquals(lh, app.tree().children().get(0).scrollY());
    assertEquals(2 * lh, app.tree().children().get(1).scrollY());
    model.top = 40;
    app.invalidate();
    app.frame();
    left = app.tree().children().get(0);
    right = app.tree().children().get(1);
    assertEquals(40, blockChild(right, "block-1-1").height(), 0.5f);
    assertEquals(lh, left.scrollY());
    assertEquals(2 * lh, right.scrollY());

    // Uncoupling restores each side's own natural height.
    model.left.scrollWith(null, List.of());
    app.invalidate();
    app.frame();
    left = app.tree().children().get(0);
    right = app.tree().children().get(1);
    assertEquals(h1, blockChild(left, "block-2").height(), 0.5f);
    assertEquals(0, blockChild(right, "block-2").height(), 0.5f);
  }

  @Test
  void pairedBlocksKeepCoupledViewsAtTheirEnd() {
    TextLibraries.assumeLoaded();
    final class Model {
      final ScrollState left = new ScrollState();
      final ScrollState right = new ScrollState();
      float thread = 20;
    }
    Model model = new Model();
    model.left.scrollWith(model.right, List.of());
    App<Model> app = new App<>(
        model,
        m -> El.row()
            .wFull()
            .hFull()
            .children(
                pane(m.left, b -> el().h(m.thread)), // a thread
                pane(m.right, b -> el()))); // its spacer
    app.textRendering(TextRendering.LINUX);
    app.resize(420, 260, 1);
    app.frame();
    model.left.scrollToEnd();
    app.invalidate();
    app.frame();
    double end = app.tree().children().get(0).scrollY();
    assertEquals(end, app.tree().children().get(1).scrollY());

    model.thread = 80;
    app.invalidate();
    app.frame();
    assertEquals(end + 60, app.tree().children().get(0).scrollY());
    assertEquals(end + 60, app.tree().children().get(1).scrollY());
  }

  private static El pane(ScrollState scroll, Function<El.Block, El> block) {
    return El.text(Coupled.numberedLines(30))
        .editable(v -> {})
        .multiline()
        .font(Font.mono())
        .fontSize(14)
        .w(180)
        .lines(8)
        .scrollState(scroll)
        .blocks(List.of(El.Block.insert(28).paired("thread")), block, null);
  }

  private static El blockChild(El parent, String key) {
    for (El child : parent.children()) {
      if (key.equals(child.key())) {
        return child;
      }
    }
    throw new AssertionError("no child #" + key + " in " + parent.dump());
  }

  /** A transcript of variable-height rows and a plain scroll area, both following their end. */
  static final class Transcript {
    final ScrollState listScroll = new ScrollState().followEnd();
    final ScrollState plainScroll = new ScrollState().followEnd();
    final RowHeights heights = new RowHeights(i -> 20);
    final List<Float> sizes = new ArrayList<>();
    int reveal = -1;

    double total() {
      return sizes.stream().mapToDouble(Float::doubleValue).sum();
    }
  }

  @Test
  void followEndTracksAppendsAndGrowthUntilScrolledAway() {
    Transcript model = new Transcript();
    model.sizes.add(30f); // fits in the 100px viewport at first
    App<Transcript> app = new App<>(model, m -> {
      El list = col()
          .key("list")
          .w(100)
          .h(100)
          .rows(m.sizes.size(), m.heights, i -> el().key("row-" + i).h(m.sizes.get(i)))
          .revealRow(m.reveal)
          .scrollState(m.listScroll);
      El plain = col().key("plain").scroll().w(100).h(100).scrollState(m.plainScroll);
      for (int i = 0; i < m.sizes.size(); i++) {
        plain.child(el().key("item-" + i).h(m.sizes.get(i)));
      }
      return El.row().children(list, plain);
    });
    app.resize(200, 100, 1);
    app.frame();
    assertBoth(app, true, 0, "fits at first");

    // Appending rows past the viewport (actual 35px > 20px estimate) follows on the same frame.
    for (int i = 0; i < 6; i++) {
      model.sizes.add(35f);
    }
    app.invalidate();
    app.frame();
    assertBoth(app, true, model.total() - 100, "followed appended rows");

    // Growing the last row (streaming answer) stays at the new end on the same frame.
    model.sizes.set(model.sizes.size() - 1, 90f);
    app.invalidate();
    app.frame();
    double end = model.total() - 100;
    assertBoth(app, true, end, "followed a taller last row");

    // Scrolling up stops following for both areas.
    app.wheel(50, 50, 0, -80);
    app.wheel(150, 50, 0, -80);
    app.frame();
    assertBoth(app, false, end - 80, "scrolled up");

    model.sizes.add(40f);
    model.sizes.set(model.sizes.size() - 1, 70f);
    app.invalidate();
    app.frame();
    assertBoth(app, false, end - 80, "stayed where the user scrolled");

    // Reaching the end again resumes following.
    app.wheel(50, 50, 0, 500);
    app.wheel(150, 50, 0, 500);
    app.frame();
    assertBoth(app, true, model.total() - 100, "wheeled back to the end");

    model.sizes.add(45f);
    app.invalidate();
    app.frame();
    assertBoth(app, true, model.total() - 100, "resumed following");

    // Reveal above the end stops following; scrollToEnd() jumps back and resumes it.
    model.reveal = 0;
    app.invalidate();
    app.frame();
    assertFalse(model.listScroll.atEnd());
    assertEquals(0, list(app).scrollY());

    model.listScroll.scrollToEnd();
    model.sizes.add(50f);
    app.invalidate();
    app.frame();
    assertTrue(model.listScroll.atEnd());
    assertEquals(model.total() - 100, list(app).scrollY(), "scrollToEnd went to the end");
  }

  /** Asserts the offset of both areas of the transcript and whether they are at their end. */
  private static void assertBoth(App<Transcript> app, boolean atEnd, double y, String what) {
    assertEquals(atEnd, app.state().listScroll.atEnd(), what);
    assertEquals(atEnd, app.state().plainScroll.atEnd(), what);
    assertEquals(y, list(app).scrollY(), what);
    assertEquals(y, panel(app).scrollY(), what);
  }

  @Test
  void scrollToEndReachesTheMeasuredEndWithoutFollowing() {
    ScrollState state = new ScrollState();
    RowHeights heights = new RowHeights(row -> 20); // half of what the rows measure
    App<ScrollState> app = new App<>(
        state,
        s -> col()
            .key("list")
            .w(100)
            .h(100)
            .rows(20, heights, row -> el().key("row-" + row).h(40))
            .scrollState(s));
    app.resize(100, 100, 1);
    app.frame();
    assertFalse(state.atEnd());

    state.scrollToEnd();
    app.invalidate();
    app.frame();
    assertTrue(state.atEnd(), "at the end of the rows as measured");
  }

  @Test
  void aUserMoveToTheEndKeepsTheRowsItFoundAboveRowsAppendedMeanwhile() {
    ScrollState state = new ScrollState();
    RowHeights heights = new RowHeights(row -> row < 20 ? 10 : 1); // the rest measure 10 too
    int[] count = {40};
    App<ScrollState> app = new App<>(
        state,
        s -> col()
            .w(100)
            .h(100)
            .rows(count[0], heights, row -> el().key("row-" + row).h(10))
            .scrollState(s)
            .onUserScroll(() -> count[0] = 60)); // loads more at the end, as an endless list does
    app.resize(100, 100, 1);
    app.frame();

    app.wheel(50, 50, 0, 1000);
    app.frame();
    assertEquals(60, app.tree().rowCount());
    assertEquals(
        300, app.tree().scrollY(), "the bottom of row 39, its last row then, at the bottom");

    state.scrollToEnd(); // the app's own end is the end of every row
    app.invalidate();
    app.frame();
    assertTrue(state.atEnd());
    assertEquals(500, app.tree().scrollY());

    state.followEnd(); // a list that follows its end follows what the user's move found below it
    state.scrollTo(0, 0);
    count[0] = 40;
    app.invalidate();
    app.frame();
    assertEquals(40, app.tree().rowCount());
    app.wheel(50, 50, 0, 1000);
    app.frame();
    assertEquals(60, app.tree().rowCount());
    assertTrue(state.atEnd());
  }
}
