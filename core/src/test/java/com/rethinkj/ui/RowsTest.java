package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.render.Scene;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Virtual lists: rows built for what shows, at exact positions, with focus, input and scripts. */
class RowsTest {

  private static final int WHITE = 0xFFFFFFFF;
  private static final int HOVER = 0xFF3B82F6;

  @TempDir Path out;

  /** The state of the list views: the number of rows, and what the view and rows were asked. */
  static final class Data {
    int count;
    int selected = -1;
    int views;
    final List<Integer> built = new ArrayList<>();
    final List<String> log = new ArrayList<>();

    Data(int count) {
      this.count = count;
    }
  }

  /** A list that fills the window, with rows that count their calls and log their presses. */
  private static El list(Data data, float rowHeight) {
    data.views++;
    return col().key("list").wFull().hFull()
        .onKey(k -> {
          data.log.add("list " + k.name());
          return false;
        })
        .rows(data.count, rowHeight, i -> {
          data.built.add(i);
          return el().key("row-" + i).label("Row " + i).fill(WHITE).focusable()
              .onPress(() -> data.log.add("press " + i));
        });
  }

  private static App<Data> start(Data data, float rowHeight, float width, float height) {
    App<Data> app = new App<>(data, d -> list(d, rowHeight));
    app.resize(width, height, 1);
    app.frame();
    return app;
  }

  private static List<Integer> range(int from, int to) {
    return IntStream.range(from, to).boxed().toList();
  }

  /**
   * Asserts that the rows from {@code first} on exist, each at its index times its height minus
   * the offset, as wide as the list.
   */
  private static void assertRows(El list, int first, int count) {
    assertEquals(first, list.firstRow());
    assertEquals(count, list.children().size());
    for (int i = 0; i < count; i++) {
      El row = list.children().get(i);
      assertEquals("row-" + (first + i), row.key());
      assertEquals(
          (float) ((first + i) * (double) list.rowHeight() - list.scrollY()),
          row.y(),
          "row " + (first + i));
      assertEquals(list.rowHeight(), row.height());
      assertEquals(list.width(), row.width());
    }
  }

  private static String firstLine(App<?> app) {
    return app.tree().dump().lines().findFirst().orElseThrow();
  }

  // Building

  @Test
  void aMillionRowsBuildOnlyTheRowsInViewAndThreeMoreOnEachSide() {
    Data data = new Data(1_000_000);
    App<Data> app = start(data, 28, 200, 300);
    El list = app.tree();
    // 300 / 28 = 10.7: rows 0 to 10 show, and rows 11 to 13 are the overscan.
    assertEquals(range(0, 14), data.built);
    assertRows(list, 0, 14);
    assertEquals("el #list 0,0 200x300 rows 0-13/1000000", firstLine(app));

    data.built.clear();
    app.scrollToRow(list, 500_000);
    assertTrue(app.frame());
    assertEquals(500_000 * 28.0, list.scrollY());
    assertEquals(range(499_997, 500_014), data.built, "rows 500000 to 500010 show");
    assertRows(list, 499_997, 17);
    assertEquals(1, data.views, "scrolling builds the rows, not the view");

    data.built.clear();
    app.scrollTo(list, 0, Double.POSITIVE_INFINITY);
    assertTrue(app.frame());
    assertEquals(28_000_000.0 - 300, list.scrollY());
    assertEquals(range(999_986, 1_000_000), data.built);
    El last = list.children().getLast();
    assertEquals("row-999999", last.key());
    assertEquals(300, last.y() + last.height(), "the last row ends where the list does");
    assertEquals(
        "el #list 0,0 200x300 rows 999986-999999/1000000 scroll 0,27999700", firstLine(app));
  }

  @Test
  void aHundredMillionRowsKeepExactOffsetsAndPositions() {
    Data data = new Data(100_000_000);
    App<Data> app = start(data, 30, 200, 300);
    El list = app.tree();
    app.scrollTo(list, 0, Double.POSITIVE_INFINITY);
    app.frame();
    assertEquals(3_000_000_000.0 - 300, list.scrollY());
    El last = list.children().getLast();
    assertEquals("row-99999999", last.key());
    assertEquals(270, last.y());
    assertEquals(300, last.y() + last.height());

    app.scrollToRow(list, 73_456_789);
    app.frame();
    assertEquals(73_456_789 * 30.0, list.scrollY());
    assertRows(list, 73_456_786, 16);

    app.wheel(100, 150, 0, 1.5f);
    app.frame();
    assertEquals(2_203_703_671.5, list.scrollY());
    assertEquals(-1.5f, list.children().get(3).y(), "row 73456789");
    assertEquals(
        "el #list 0,0 200x300 rows 73456786-73456801/100000000 scroll 0,2203703671.5",
        firstLine(app));
  }

  @Test
  void scrollingWithinTheOverscanBuildsNothingAndAShowingRowBuildsTheRowsAnew() {
    Data data = new Data(1_000_000);
    App<Data> app = start(data, 28, 200, 300);
    El list = app.tree();
    data.built.clear();
    for (int i = 1; i <= 9; i++) {
      app.wheel(100, 150, 0, 10);
      assertTrue(app.frame(), "the offset changed");
    }
    assertEquals(90, list.scrollY());
    assertEquals(List.of(), data.built, "rows 0 to 13 still cover what shows, up to 392");
    assertSame(list, app.tree());
    assertEquals(1, data.views);
    assertRows(list, 0, 14);
    assertEquals(-90, list.children().getFirst().y());

    app.wheel(100, 150, 0, 10); // 100 to 400: row 14 shows
    assertTrue(app.frame());
    assertEquals(range(0, 18), data.built, "rows 3 to 14 show");
    assertEquals(1, data.views, "only the rows were built");
    assertEquals(-100, list.children().getFirst().y());
  }

  @Test
  void aFrameAfterAWheelStepCostsNoMoreForAMillionRowsThanForAThousand() {
    long[] thousand = wheelFrames(1_000);
    long[] million = wheelFrames(1_000_000);
    System.out.printf(
        "frame() after a wheel step, median of %d: %.1f µs with 1000 rows, %.1f µs with 1000000"
            + " rows, %d elements in each tree%n",
        2000, thousand[0] / 1000.0, million[0] / 1000.0, million[1]);
    assertEquals(thousand[1], million[1], "as many elements");
    // At most 23 rows show in 600 pixels, with three more on each side, of four elements each.
    assertTrue(million[1] <= 29 * 4 + 1, "the rows in view and the overscan, " + million[1]);
    assertTrue(
        million[0] < 3 * thousand[0] + 100_000,
        "median " + million[0] + " ns with a million rows, " + thousand[0] + " with a thousand");
  }

  /**
   * The median time of {@link App#frame} after a wheel step of 100 pixels, which builds the rows
   * anew most of the time, up and down within the first 4000 pixels; and the elements in the tree.
   */
  private static long[] wheelFrames(int count) {
    App<Data> app =
        new App<>(
            new Data(count),
            d -> col().key("list").wFull().hFull().rows(d.count, 28, i ->
                row().key("row-" + i).padding(4).gap(4).fill(WHITE)
                    .children(el().size(14, 14), el().grow(1), el().w(40))));
    app.resize(400, 600, 1);
    app.frame();
    long[] times = new long[2000];
    for (int i = -2000; i < times.length; i++) { // the first 2000 warm up
      app.wheel(200, 300, 0, Math.floorMod(i, 80) < 40 ? 100 : -100);
      long start = System.nanoTime();
      app.frame();
      if (i >= 0) {
        times[i] = System.nanoTime() - start;
      }
    }
    Arrays.sort(times);
    return new long[] {times[times.length / 2], count(app.tree())};
  }

  private static int count(El el) {
    int count = 1;
    for (El child : el.children()) {
      count += count(child);
    }
    return count;
  }

  // Changing data

  @Test
  void aShorterListClampsTheOffsetAndALongerOneKeepsIt() {
    Data data = new Data(1000);
    App<Data> app = start(data, 28, 200, 300);
    app.scrollToRow(app.tree(), 900);
    app.frame();
    assertEquals(25_200, app.tree().scrollY());

    data.count = 100;
    app.resize(200, 300, 1); // rebuilds the view
    app.frame();
    El list = app.tree();
    assertEquals(100 * 28 - 300, list.scrollY());
    assertRows(list, 86, 14);
    assertEquals(300, list.children().getLast().y() + 28, "row 99 ends at the bottom");
    // Row 89 begins 2492 down, 8 above the offset.
    assertEquals(-8, list.children().get(3).y());

    data.count = 2000;
    app.resize(200, 300, 1);
    app.frame();
    assertEquals(2500, app.tree().scrollY());
    assertRows(app.tree(), 86, 17);
    assertEquals(-8, app.tree().children().get(3).y());

    data.count = 0;
    app.resize(200, 300, 1);
    app.frame();
    assertEquals(0, app.tree().scrollY());
    assertEquals("el #list 0,0 200x300 rows -/0", firstLine(app));
  }

  @Test
  void rowsKeepTheirMovementsAcrossRebuildsButMoveWithTheListAtOnce() {
    Data data = new Data(1000);
    App<Data> app =
        new App<>(
            data,
            d -> col().key("list").wFull().hFull().rows(d.count, 28, i ->
                el().key("row-" + i).fill(i == d.selected ? HOVER : WHITE).motion(Spring.SNAP)));
    app.resize(200, 300, 1);
    app.frame();
    data.selected = 2;
    app.resize(200, 300, 1);
    app.frame();
    app.advance(0.03);
    app.frame();
    int fill = app.tree().children().get(2).fill();
    assertNotEquals(WHITE, fill, "on its way");
    assertNotEquals(HOVER, fill, "springs instead of jumping, as in a list of children");

    app.wheel(100, 150, 0, 100); // builds rows 0 to 17 anew
    app.frame();
    app.wheel(100, 150, 0, 200); // and rows 7 to 24: row 7 is the first now
    app.frame();
    El list = app.tree();
    assertEquals(7, list.firstRow());
    assertRows(list, 7, 18); // no row slides to its new place among them
    app.advance(1);
    app.frame();
    assertFalse(app.isMoving());
  }

  // Input

  @Test
  void selectorsHitTestingAndHoverWorkOnTheRowsThatExist() throws IOException {
    Data data = new Data(1_000_000);
    App<Data> app =
        new App<>(
            data,
            d -> col().key("list").wFull().hFull().rows(d.count, 28, i ->
                el().key("row-" + i).label("Row " + i).fill(WHITE)
                    .whenHovered(e -> e.fill(HOVER))
                    .onPress(() -> d.log.add("press " + i))));
    Script.run("size 200 300\nframe\npress #row-3\npress 100,20\nmove label=\"Row 5\"\nframe\n",
        app, out);
    assertEquals(List.of("press 3", "press 0"), data.log);
    El list = app.tree();
    assertEquals("row-5", app.hovered().key());
    assertEquals(HOVER, list.children().get(5).fill(), "its hover look");
    assertEquals(WHITE, list.children().get(4).fill());
    assertTrue(app.tree().dump()
        .contains("el #row-5 label=\"Row 5\" 0,140 200x28 pos=6/1000000 hovered\n"));

    app.wheel(100, 154, 0, 200); // rows 4 to 20 now, row 12 under the pointer
    app.frame();
    assertEquals("row-12", app.hovered().key());
    assertEquals(HOVER, find(app.tree(), "row-12").fill(), "the rows built anew have the look");
    assertEquals(WHITE, find(app.tree(), "row-5").fill());

    Script.Failure failure =
        assertThrows(Script.Failure.class, () -> Script.run("press #row-500\n", app, out));
    assertTrue(
        failure.getMessage().endsWith(
            " every element with its rectangle.\n"
                + "  A list of rows has only the rows near its visible part as elements, and a"
                + " selector finds only those. The last frame has these lists, with the rows that"
                + " exist and how many there are:\n"
                + "    el #list 0,0 200x300 rows 4-20/1000000 scroll 0,200\n"
                + "  To reach another row, scroll its list first: `scroll #list row <index>` puts"
                + " that row at the top of the list at the next `frame`."),
        failure.getMessage());
    Script.run("size 200 300\nscroll #row-12 row 500\nframe\npress #row-500\nframe\n", app, out);
    assertEquals(List.of("press 3", "press 0", "press 500"), data.log);
    assertEquals(14_000, app.tree().scrollY());
  }

  @Test
  void theScrollStepNamesItsMistakes() {
    Data data = new Data(1000);
    App<Data> app = start(data, 28, 200, 300);
    assertTrue(
        failure("scroll #list row 1000\n", app)
            .endsWith("The list has 1000 rows, from 0 to 999. Write a row in that range."));
    assertTrue(failure("scroll #list 3\n", app).contains("Write `scroll <target> row <index>`"));
    App<Object> plain = new App<>(new Object(), s -> el().key("box").size(10, 10));
    assertTrue(
        failure("size 10 10\nframe\nscroll #box row 3\n", plain)
            .contains("There is no list of rows at #box."));
  }

  private String failure(String script, App<?> app) {
    return assertThrows(Script.Failure.class, () -> Script.run(script, app, out)).getMessage();
  }

  // Focus

  /** A focusable element before a list of 200 pixels with focusable rows, and one after it. */
  private static App<Data> focusApp(Data data) {
    App<Data> app =
        new App<>(
            data,
            d -> col().size(200, 240).children(
                el().key("before").focusable().h(20),
                list(d, 28).h(200),
                el().key("after").focusable().h(20)));
    app.resize(200, 240, 1);
    app.frame();
    return app;
  }

  @Test
  void focusStaysOnARowThatScrollsOutAndShowsWhenItComesBack() {
    Data data = new Data(1_000_000);
    App<Data> app = focusApp(data);
    press(app, 100, 30); // row 0
    assertEquals("row-0", key(app.focused()));
    data.built.clear();

    app.scrollToRow(list(app), 20_000);
    app.frame();
    assertNull(app.focused(), "row 0 does not exist now");
    assertFalse(app.tree().dump().contains("focused"));
    app.keyDown(new Key("ArrowDown", 0, false));
    app.frame();
    assertEquals(List.of("press 0", "list ArrowDown"), data.log, "keys go to the list");
    assertTrue(data.built.stream().allMatch(i -> i >= 19_997), "nothing built around row 0");

    app.scrollToRow(list(app), 0);
    app.frame();
    assertEquals("row-0", key(app.focused()));
    assertTrue(app.tree().dump()
        .contains("el #row-0 label=\"Row 0\" 0,20 200x28 pos=1/1000000 focused\n"));
  }

  @Test
  void tabEntersTheListAtTheFirstRowInViewOrWhereTheFocusWas() {
    Data data = new Data(1_000_000);
    App<Data> app = focusApp(data);
    press(app, 100, 10);
    assertEquals("before", key(app.focused()));
    app.scrollToRow(list(app), 5000);
    app.frame();
    assertEquals(4997, list(app).firstRow());

    tab(app, 0);
    assertEquals("row-5000", key(app.focused()), "not row 4997, the first that exists");
    tab(app, 0);
    assertEquals("row-5001", key(app.focused()));
    assertEquals(5000 * 28, list(app).scrollY(), "row 5001 is in view");

    app.scrollToRow(list(app), 20_000);
    app.frame();
    assertNull(app.focused());
    tab(app, 0); // from row 5001, which comes back first, at the top as scrolling up leaves it
    assertEquals("row-5002", key(app.focused()));
    assertEquals(5001 * 28, list(app).scrollY());

    press(app, 100, 10);
    app.scrollToRow(list(app), 30_000);
    app.frame();
    tab(app, 0);
    assertEquals("row-5002", key(app.focused()), "where the focus was in the list");
    assertTrue(hasRing(app.scene()));

    tab(app, Key.SHIFT);
    assertEquals("row-5001", key(app.focused()), "row by row inside the list");
  }

  private static boolean hasRing(Scene scene) {
    for (int i = 0; i < scene.count(); i++) {
      if (scene.get(i, Scene.BORDER_WIDTH) == Scene.RING) {
        return true;
      }
    }
    return false;
  }

  private static El list(App<?> app) {
    return find(app.tree(), "list");
  }

  @Test
  void shiftTabEntersAtTheLastRowInView() {
    Data data = new Data(1_000_000);
    App<Data> app = focusApp(data);
    press(app, 100, 230);
    assertEquals("after", key(app.focused()));
    app.scrollToRow(list(app), 100);
    app.frame();
    tab(app, Key.SHIFT);
    // 200 / 28 = 7.1: rows 100 to 106 are whole.
    assertEquals("row-106", key(app.focused()));
    assertEquals(2800, list(app).scrollY(), "already in view");
  }

  @Test
  void aFocusedRowBeyondANewEndLosesTheFocus() {
    Data data = new Data(1000);
    App<Data> app = focusApp(data);
    press(app, 100, 30);
    app.scrollToRow(list(app), 500);
    app.frame();
    data.count = 0;
    app.resize(200, 240, 1);
    app.frame();
    assertNull(app.focused());
    tab(app, 0);
    assertEquals("before", key(app.focused()), "from the start: the focus was cleared");
    tab(app, 0);
    assertEquals("after", key(app.focused()), "the list has no rows to enter");
  }

  // Building without an app, and mistakes

  @Test
  void headlessRendersTheRowsAtTheTop() {
    Data data = new Data(1_000_000);
    Headless.Frame frame = Headless.render(list(data, 28), 200, 100, 1);
    assertEquals(range(0, 7), data.built);
    assertTrue(frame.tree().startsWith("el #list 0,0 200x100 rows 0-6/1000000\n"));
    assertEquals(WHITE, frame.pixels().argb(100, 99));
  }

  @Test
  void mistakesAreRefused() {
    assertThrows(IllegalStateException.class, () -> col().child(el()).rows(1, 20, i -> el()));
    assertThrows(IllegalStateException.class, () -> col().rows(1, 20, i -> el()).child(el()));
    assertThrows(IllegalArgumentException.class, () -> col().rows(-1, 20, i -> el()));
    assertThrows(IllegalArgumentException.class, () -> col().rows(1, 0, i -> el()));
    assertThrows(IllegalArgumentException.class, () -> col().rows(1, Float.NaN, i -> el()));
    App<Object> app = new App<>(new Object(), s -> col().wFull().hFull());
    app.resize(10, 10, 1);
    app.frame();
    assertThrows(IllegalArgumentException.class, () -> app.scrollToRow(app.tree(), 0));
    App<Object> nulls = new App<>(new Object(), s -> col().wFull().hFull().rows(3, 5, i -> null));
    nulls.resize(10, 10, 1);
    assertTrue(
        assertThrows(IllegalStateException.class, nulls::frame).getMessage().endsWith("row 0"));
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
    app.frame();
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
}
