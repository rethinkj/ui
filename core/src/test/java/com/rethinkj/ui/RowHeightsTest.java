package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The heights of a virtual list whose rows are as high as their content ({@link
 * El#rows(int, RowHeights, java.util.function.IntFunction)}): the Fenwick tree against a sum, the
 * rows built for the part in view with what their layout measured, the offset that keeps the row
 * at the top in place, and scrolling to a row; without text, so without native libraries.
 */
class RowHeightsTest {

  @Test
  void theTreeAgreesWithASumAfterRandomMeasurements() {
    Random random = new Random(3);
    RowHeights heights = new RowHeights(i -> 10.1 + i % 7);
    heights.count(1000);
    double[] plain = new double[1000];
    for (int i = 0; i < plain.length; i++) {
      plain[i] = 10.1 + i % 7;
    }
    for (int step = 0; step < 500; step++) {
      int row = random.nextInt(plain.length);
      double h = 1 + random.nextInt(3000) / 10.0;
      assertEquals(h - plain[row], heights.measured(row, h));
      plain[row] = h;
      if (step % 50 == 0) {
        double sum = 0;
        for (int i = 0; i <= plain.length; i++) {
          assertEquals(sum, heights.top(i), 1e-6, "top of " + i);
          if (i < plain.length) {
            assertEquals(i, heights.at(heights.top(i)), "the row that starts at " + sum);
            assertEquals(i, heights.at(sum + plain[i] - 0.5), "inside row " + i);
            sum += plain[i];
          }
        }
        assertEquals(sum, heights.total(), 1e-6);
        assertEquals(plain.length, heights.at(sum + 1), "past the last");
      }
    }
    heights.count(10); // fewer rows keep their heights
    assertEquals(plain[3], heights.height(3));
  }

  /** Row i is 20 + 10 * (i % 4) high, estimated at 25: a list 100 high in a window of 200. */
  private static App<RowHeights> app() {
    RowHeights heights = new RowHeights(i -> 25);
    App<RowHeights> app = new App<>(
        heights,
        h -> col()
            .wFull()
            .hFull()
            .child(el().key("list")
                .h(100)
                .rows(1000, h, i -> el().key("row").h(20 + 10 * (i % 4)))));
    app.resize(200, 200, 1);
    app.frame();
    return app;
  }

  private static El list(App<?> app) {
    return app.tree().children().getFirst();
  }

  @Test
  void rowsAreAsHighAsTheirContentAndTheListKeepsWhatItMeasured() {
    App<RowHeights> app = app();
    El list = list(app);
    // Rows 0 to 3 are 20, 30, 40 and 50: the first four fill 100; three more below.
    assertEquals(0, list.firstRow(), app.tree().dump());
    assertEquals(7, list.children().size(), app.tree().dump());
    float y = 0;
    for (int i = 0; i < list.children().size(); i++) {
      El row = list.children().get(i);
      assertEquals(y, row.y(), "row " + i);
      assertEquals(20 + 10 * (i % 4), row.height(), "row " + i);
      y += row.height();
    }
    assertEquals(140, app.state().top(4), 1e-9);
    assertEquals(25, app.state().height(500), "estimated until built");
  }

  @Test
  void rowsAboveThatTurnOutHigherKeepTheRowAtTheTopInPlace() {
    App<RowHeights> app = app();
    El list = list(app);
    // Far down, among rows only estimated: row 400 at the top.
    app.scrollToRow(list, 400);
    app.frame();
    list = list(app);
    El top = list.children().get(400 - list.firstRow());
    assertEquals(0, top.y(), 0.001, app.tree().dump());
    // Back up a little: the rows above were 25 and are measured now, 20 to 50.
    app.wheel(100, 50, 0, -60);
    app.frame();
    list = list(app);
    double offset = list.scrollY();
    // Whatever the rows above measured, the rows that exist lie where the offset says.
    for (El row : list.children()) {
      int index = list.firstRow() + list.children().indexOf(row);
      assertEquals(app.state().top(index) - offset, row.y(), 0.001, "row " + index);
    }
    assertTrue(list.firstRow() <= 400 && list.firstRow() + list.children().size() > 400);
  }

  /**
   * A list in a row of another, as high as its rows, builds only the rows in view of the outer
   * one, and the row grows with what they measure, as it scrolls too.
   */
  @Test
  void aListInARowBuildsTheRowsInViewAndItsRowFollowsTheirHeights() {
    RowHeights outer = new RowHeights(i -> 50);
    RowHeights inner = new RowHeights(i -> 10);
    App<RowHeights> app = new App<>(
        outer,
        h -> col()
            .wFull()
            .hFull()
            .child(el().key("list")
                .h(200)
                .rows(
                    3,
                    h,
                    i -> i == 1
                        ? col()
                            .children(
                                el().h(10),
                                el().key("inner").rows(1000, inner, j -> el().h(20 + 10 * (j % 2))))
                        : el().h(50))));
    app.resize(200, 200, 1);
    app.frame();
    for (int frame = 0; frame < 3; frame++) {
      El list = list(app);
      El row = list.children().get(1 - list.firstRow());
      El nested = row.children().getLast();
      assertTrue(nested.children().size() < 40, "built " + nested.children().size());
      assertEquals(10 + inner.total(), row.height(), 0.001, app.tree().dump());
      assertEquals(row.height(), outer.height(1), 0.001);
      El above = list.children().getFirst();
      assertEquals(above.y() + 50, row.y(), 0.001, "row 0 is built above it");
      app.wheel(100, 100, 0, 300);
      app.frame();
    }
  }

  /**
   * Tab goes to a focusable row of a list in a row that isn't built, several screens down, and
   * shows it; on to a row of the outer list that isn't built either; Shift+Tab goes back, also
   * into the last row of the inner list when the outer list builds its row anew.
   */
  @Test
  void tabReachesFocusableRowsThatAreNotBuiltAndShowsThem() {
    RowHeights outer = new RowHeights(i -> 50);
    RowHeights inner = new RowHeights(i -> 20);
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .children(
                el().key("list")
                    .h(100)
                    .rows(
                        7,
                        outer,
                        i -> i == 0
                            ? el().key("inner")
                                .rows(
                                    200,
                                    inner,
                                    j -> j == 0 || j == 150
                                        ? el().key("link-" + j).focusable().h(20)
                                        : el().h(20))
                            : i == 6 ? el().key("end").focusable().h(20) : el().h(50)),
                el().key("after").focusable().h(20)));
    app.resize(200, 200, 1);
    app.frame();
    app.focus(find(app.tree(), "link-0"));
    app.frame();
    for (String expected : List.of("link-150", "end", "after")) {
      tab(app, 0);
      assertEquals(expected, key(app.focused()), app.tree().dump());
      assertInView(app);
    }
    for (String expected : List.of("end", "link-150", "link-0")) {
      tab(app, Key.SHIFT);
      assertEquals(expected, key(app.focused()), app.tree().dump());
      assertInView(app);
    }
  }

  /**
   * Tab into a list in a row goes to its first row in view of the outer list, and back into it to
   * the row that had the focus there, also when the outer list scrolled that row, or its own row
   * that holds the inner list, out of its view meanwhile; to the row in view once that row holds
   * nothing to focus; past the inner list in its row once no row from there on holds anything, and
   * past the outer list once nothing in it does.
   */
  @Test
  void tabReturnsToTheRowOfAListInARowThatTheOuterListScrolledAway() {
    RowHeights outer = new RowHeights(i -> 100);
    RowHeights inner = new RowHeights(j -> 20);
    boolean[] gone = {false, false, false}; // row 16, rows from 20 on, copy
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .children(
                el().key("before").focusable().h(20),
                el().key("list")
                    .h(100)
                    .rows(
                        9,
                        outer,
                        i -> i == 0
                            ? col()
                                .children(
                                    el().key("inner")
                                        .rows(
                                            40,
                                            inner,
                                            j -> gone[0] && j == 16 || gone[1] && j >= 20
                                                ? el().key("row-" + j).h(20)
                                                : el().key("row-" + j)
                                                    .focusable()
                                                    .h(20)),
                                    gone[2]
                                        ? el().h(20)
                                        : el().key("copy").focusable().h(20))
                            : el().h(100)),
                el().key("after").focusable().h(20)));
    app.resize(200, 200, 1);
    app.frame();
    app.scrollTo(find(app.tree(), "list"), 0, 300);
    app.frame();
    app.focus(find(app.tree(), "before"));
    app.frame();
    tab(app, 0);
    assertEquals(
        "row-15", key(app.focused()), "the first row in view: " + app.tree().dump());
    tab(app, 0);
    assertEquals("row-16", key(app.focused()));
    for (double offset : new double[] {0, 1500}) {
      app.focus(find(app.tree(), "before"));
      app.scrollTo(find(app.tree(), "list"), 0, offset);
      app.frame();
      assertEquals(null, find(app.tree(), "row-16"), "out of the outer list's view");
      tab(app, 0);
      assertEquals("row-16", key(app.focused()), app.tree().dump());
      assertInView(app);
    }
    gone[0] = true;
    app.focus(find(app.tree(), "before"));
    app.scrollTo(find(app.tree(), "list"), 0, 400);
    app.frame();
    tab(app, 0);
    assertEquals("row-20", key(app.focused()), app.tree().dump());
    gone[1] = true;
    app.focus(find(app.tree(), "before"));
    app.frame();
    tab(app, 0);
    assertEquals("copy", key(app.focused()), app.tree().dump());
    gone[2] = true;
    app.focus(find(app.tree(), "before"));
    app.scrollTo(find(app.tree(), "list"), 0, 400);
    app.frame();
    tab(app, 0);
    assertEquals("after", key(app.focused()), app.tree().dump());
  }

  /**
   * Tab into a list goes to its row in view, not into a list in a row built above the view; on to
   * the next row when the one in view has nothing to focus.
   */
  @Test
  void tabEntersTheOuterRowInViewNotAListInARowAboveIt() {
    RowHeights outer = new RowHeights(i -> 60);
    RowHeights[] inner = new RowHeights[30];
    Arrays.setAll(inner, i -> new RowHeights(j -> 20));
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .children(
                el().key("before").focusable().h(20),
                el().key("list")
                    .h(120)
                    .rows(
                        30,
                        outer,
                        i -> el().key("inner")
                            .rows(
                                3,
                                inner[i],
                                j -> i == 10
                                    ? el().key("r" + i + "-" + j).h(20)
                                    : el().key("r" + i + "-" + j).focusable().h(20)))));
    app.resize(200, 200, 1);
    app.frame();
    app.scrollTo(find(app.tree(), "list"), 0, 600);
    app.frame();
    app.focus(find(app.tree(), "before"));
    app.frame();
    tab(app, 0);
    assertEquals("r11-0", key(app.focused()), app.tree().dump());
  }

  private static void tab(App<?> app, int modifiers) {
    app.keyDown(new Key("Tab", modifiers, false));
    app.frame();
  }

  private static String key(El el) {
    return el == null ? null : el.key();
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** The focused element lies inside the window, and inside the list "list" if it is in it. */
  private static void assertInView(App<?> app) {
    List<El> path = new ArrayList<>();
    assertTrue(pathTo(app.tree(), app.focused(), path));
    float top = 0;
    float listTop = Float.NEGATIVE_INFINITY;
    float listBottom = Float.POSITIVE_INFINITY;
    for (El el : path) {
      top += el.y();
      if ("list".equals(el.key())) {
        listTop = top;
        listBottom = top + el.height();
      }
    }
    float bottom = top + app.focused().height();
    assertTrue(
        top >= Math.max(listTop, 0) && bottom <= Math.min(listBottom, 200),
        top + " to " + bottom + " in " + listTop + " to " + listBottom + "\n"
            + app.tree().dump());
  }

  private static boolean pathTo(El el, El target, List<El> path) {
    path.add(el);
    if (el == target) {
      return true;
    }
    for (El child : el.children()) {
      if (pathTo(child, target, path)) {
        return true;
      }
    }
    path.removeLast();
    return false;
  }

  @Test
  void rowsEstimatedTooHighFillTheListInTheFramesAfterAndThenTheAppIsIdle() {
    RowHeights heights = new RowHeights(i -> 100);
    App<RowHeights> app =
        new App<>(heights, h -> col().size(100, 600).rows(1000, h, i -> el().h(20)));
    app.resize(100, 600, 1);
    app.frame();
    // Three passes a frame: what the measured heights show beyond them waits for the next.
    assertEquals(app.time(), app.nextChange(), "asks for another frame");
    for (int frame = 0; frame < 5 && app.nextChange() == app.time(); frame++) {
      app.frame();
    }
    El last = app.tree().children().getLast();
    assertTrue(last.y() + last.height() >= 600, app.tree().dump());
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange(), "idle once the rows fill the list");
  }

  @Test
  void aListSentToARowGetsThereOnceItsContentHasGrownUnlessTheUserWentOn() {
    int[] height = {10};
    RowHeights heights = new RowHeights(i -> 10);
    App<RowHeights> app = new App<>(
        heights,
        h -> col()
            .size(100, 100)
            .child(el().key("list").size(100, 100).rows(12, h, i -> el().h(height[0]))));
    app.resize(100, 100, 1);
    app.frame();
    // Twelve rows of 10 in a list of 100: row 9 cannot reach the top, the end can.
    app.scrollToRow(list(app), 9);
    app.frame();
    assertEquals(20, list(app).scrollY(), 0.001);
    // The rows grow, as a document's whose images arrive: now the row gets there.
    height[0] = 40;
    app.invalidate();
    app.frame();
    app.frame();
    El list = list(app);
    assertEquals(
        0, list.children().get(9 - list.firstRow()).y(), 0.001, app.tree().dump());

    // After the user's next input the list follows no row: it stays where the wheel left it.
    app.wheel(50, 50, 0, -30);
    app.frame();
    double left = list(app).scrollY();
    height[0] = 60;
    app.invalidate();
    app.frame();
    app.frame();
    list = list(app);
    assertNotEquals(
        0f,
        list.children().get(9 - list.firstRow()).y(),
        "no jump back: " + app.tree().dump());
    assertTrue(Math.abs(list(app).scrollY() - left) < 200, "near where the wheel left it");
  }

  private record Item(int id, float height) {}

  private static final class Transcript {
    final List<Item> items = new ArrayList<>();
    final RowHeights plainHeights = new RowHeights(i -> 20, i -> items.get(i).id());
    final RowHeights stateHeights = new RowHeights(i -> 20, i -> items.get(i).id());
    final RowHeights endHeights = new RowHeights(i -> 20, i -> items.get(i).id());
    final ScrollState scroll = new ScrollState();
    final ScrollState endScroll = new ScrollState().followEnd();
  }

  private static El rowByKey(El list, String key) {
    for (El child : list.children()) {
      if (key.equals(child.key())) {
        return child;
      }
    }
    throw new AssertionError("missing " + key + " in:\n" + list.dump());
  }

  @Test
  void insertingAndRemovingRowsBeforeTheViewKeepsTheRowInView() {
    Transcript model = new Transcript();
    for (int i = 0; i < 20; i++) {
      model.items.add(new Item(100 + i, 15 + (i % 5) * 12));
    }
    App<Transcript> app = new App<>(model, m -> {
      El plain = col()
          .key("plain")
          .w(100)
          .h(100)
          .rows(
              m.items.size(),
              m.plainHeights,
              i -> el().key("item-" + m.items.get(i).id()).h(m.items.get(i).height()));
      El kept = col()
          .key("kept")
          .w(100)
          .h(100)
          .rows(
              m.items.size(),
              m.stateHeights,
              i -> el().key("item-" + m.items.get(i).id()).h(m.items.get(i).height()))
          .scrollState(m.scroll);
      El tail = col()
          .key("tail")
          .w(100)
          .h(100)
          .rows(
              m.items.size(),
              m.endHeights,
              i -> el().key("item-" + m.items.get(i).id()).h(m.items.get(i).height()))
          .scrollState(m.endScroll);
      return El.row().children(plain, kept, tail);
    });
    app.resize(300, 100, 1);
    app.frame();
    assertTrue(model.endScroll.atEnd(), "followEnd starts at the end");

    // Scroll into the middle in two steps so rows above the view are measured, stopping mid-row.
    for (int step = 0; step < 2; step++) {
      app.wheel(50, 50, 0, 120);
      app.wheel(150, 50, 0, 120);
      app.frame();
    }
    El plain = app.tree().children().get(0);
    El kept = app.tree().children().get(1);
    assertEquals(15, model.plainHeights.height(0), "row 0 above the view was measured");
    assertEquals(51, model.plainHeights.height(3), "row 3 above the view was measured");
    assertEquals(63, model.plainHeights.height(4), "row 4 above the view was measured");
    float anchorY = rowByKey(plain, "item-107").y();
    float nextY = rowByKey(plain, "item-108").y();
    assertTrue(anchorY < 0 && nextY > 0, "scrolled mid-row: " + anchorY + ", " + nextY);
    assertEquals(anchorY, rowByKey(kept, "item-107").y(), 0.001);

    // Insert five rows at the top (different heights from the 20px estimate).
    List<Item> older = List.of(
        new Item(95, 35), new Item(96, 55), new Item(97, 25), new Item(98, 45), new Item(99, 65));
    model.items.addAll(0, older);
    app.invalidate();
    app.frame();

    plain = app.tree().children().get(0);
    kept = app.tree().children().get(1);
    El tail = app.tree().children().get(2);
    assertEquals(20, model.plainHeights.height(0), "new unbuilt row uses its estimate");
    assertEquals(15, model.plainHeights.height(5), "measured height moved with item 100");
    assertEquals(63, model.plainHeights.height(9), "measured height moved with item 104");
    assertEquals(anchorY, rowByKey(plain, "item-107").y(), 0.001, "plain kept anchor row");
    assertEquals(nextY, rowByKey(plain, "item-108").y(), 0.001, "plain kept next row");
    assertEquals(anchorY, rowByKey(kept, "item-107").y(), 0.001, "ScrollState kept anchor row");
    assertEquals(nextY, rowByKey(kept, "item-108").y(), 0.001, "ScrollState kept next row");
    assertTrue(model.endScroll.atEnd(), "followEnd stayed at its end after prepend");
    El last = tail.children().getLast();
    assertEquals("item-119", last.key());
    assertEquals(100, last.y() + last.height(), 0.001, "last row ends at the bottom");

    // Remove eight rows from the top (the five prepended plus three measured rows).
    model.items.subList(0, 8).clear();
    app.invalidate();
    app.frame();

    plain = app.tree().children().get(0);
    kept = app.tree().children().get(1);
    tail = app.tree().children().get(2);
    assertEquals(51, model.plainHeights.height(0), "measured height stayed with item 103");
    assertEquals(63, model.plainHeights.height(1), "measured height stayed with item 104");
    assertEquals(anchorY, rowByKey(plain, "item-107").y(), 0.001, "plain kept row after remove");
    assertEquals(anchorY, rowByKey(kept, "item-107").y(), 0.001, "ScrollState kept row");
    assertTrue(model.endScroll.atEnd(), "followEnd stayed at its end after remove");
    last = tail.children().getLast();
    assertEquals(100, last.y() + last.height(), 0.001);

    // Removing the anchor row itself moves the next surviving row to the top of the view.
    model.items.removeIf(item -> item.id() == 107);
    app.invalidate();
    app.frame();

    plain = app.tree().children().get(0);
    kept = app.tree().children().get(1);
    assertEquals(0, rowByKey(plain, "item-108").y(), 0.001, "next surviving row at the top");
    assertEquals(0, rowByKey(kept, "item-108").y(), 0.001, "next surviving row at the top");
  }

  @Test
  void aRowScrolledToStaysTheGoalWhenRowsComeBeforeIt() {
    Transcript model = new Transcript();
    for (int i = 0; i < 40; i++) {
      model.items.add(new Item(100 + i, 20 + (i % 3) * 10));
    }
    App<Transcript> app = new App<>(
        model,
        m -> col()
            .key("list")
            .w(100)
            .h(100)
            .rows(
                m.items.size(),
                m.plainHeights,
                i -> el().key("item-" + m.items.get(i).id()).h(m.items.get(i).height())));
    app.resize(100, 100, 1);
    app.frame();
    app.scrollToRow(app.tree(), 10);
    app.frame();
    assertEquals(0, rowByKey(app.tree(), "item-110").y(), 0.001, app.tree().dump());

    model.items.addAll(0, List.of(new Item(97, 45), new Item(98, 45), new Item(99, 45)));
    app.invalidate();
    app.frame();
    assertEquals(0, rowByKey(app.tree(), "item-110").y(), 0.001, app.tree().dump());
  }

  @Test
  void rowsInsertedAboveAListScrolledIntoItsPaddingKeepTheFirstRowInView() {
    Transcript model = new Transcript();
    for (int i = 0; i < 10; i++) {
      model.items.add(new Item(100 + i, 30));
    }
    App<Transcript> app = new App<>(
        model,
        m -> col()
            .key("list")
            .w(100)
            .h(100)
            .padding(8, 0)
            .rows(
                m.items.size(),
                m.stateHeights,
                i -> el().key("item-" + m.items.get(i).id()).h(m.items.get(i).height()))
            .scrollState(m.scroll));
    app.resize(100, 100, 1);
    app.frame();
    assertEquals(0, model.scroll.y(), 0.001);
    assertEquals(8, rowByKey(app.tree(), "item-100").y(), 0.001);

    model.items.addAll(0, List.of(new Item(97, 45), new Item(98, 55), new Item(99, 35)));
    app.invalidate();
    app.frame();
    assertEquals(0, rowByKey(app.tree(), "item-100").y(), 0.001, app.tree().dump());
  }
}
