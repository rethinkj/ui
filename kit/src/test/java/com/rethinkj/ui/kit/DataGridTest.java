package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button.Variant;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The data grid with a million orders ({@link Orders}): what it builds, selection by pointer and
 * keys, the header under scrolling, resizing and sorting, the cells, and stored images.
 */
class DataGridTest {

  private static final Theme T = Theme.DARK;
  // The grid of Orders.view: 544 by 224 at 8,8, with a header of 22 and a line of 1 over 201
  // pixels of rows, which show 9 rows whole.
  private static final int PAGE = 9;

  @TempDir Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Orders> start(Orders orders) throws IOException {
    App<Orders> app = orders.app();
    run(app, "frame");
    return app;
  }

  /** Runs the steps in a window of 560 by 240, which each script has to say first. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 560 240\n" + steps, app, out);
  }

  // Rows and cost

  @Test
  void aMillionRowsOfTwentyColumnsAskOnlyForTheCellsOfTheRowsThatExist() throws IOException {
    Orders orders = new Orders(T, 1_000_000, 20);
    App<Orders> app = start(orders);
    assertAskedForExistingRows(orders, app);
    El rows = find(app, "rows");
    assertTrue(rows.children().size() <= 9 + 1 + 2 * El.OVERSCAN, "" + rows.children().size());

    orders.asked.clear();
    run(app, "scroll #rows row 654321\nframe");
    assertAskedForExistingRows(orders, app);
    assertEquals(654_321 - El.OVERSCAN, find(app, "rows").firstRow());
    orders.asked.clear();
    app.wheel(280, 120, 0, 300); // past the overscan: builds the rows anew, not the view
    app.frame();
    assertAskedForExistingRows(orders, app);
  }

  /** Every cell of each row that exists was asked for once since the last clear, and no other. */
  private static void assertAskedForExistingRows(Orders orders, App<?> app) {
    El rows = find(app, "rows");
    List<Integer> exist =
        IntStream.range(rows.firstRow(), rows.firstRow() + rows.children().size()).boxed().toList();
    assertEquals(exist, List.copyOf(new TreeSet<>(orders.asked)));
    assertEquals(exist.size() * 20, orders.asked.size(), "each cell once");
  }

  @Test
  void aFrameAfterAWheelStepCostsNoMoreForAMillionRowsThanForAThousand() throws IOException {
    long[] thousand = wheelFrames(1_000);
    long[] million = wheelFrames(1_000_000);
    System.out.printf(
        "DataGrid, 20 columns: frame() after a wheel step, median of %d: %.1f µs with 1000 rows,"
            + " %.1f µs with 1000000 rows, %d elements in each tree%n",
        FRAMES, thousand[0] / 1000.0, million[0] / 1000.0, million[1]);
    assertEquals(thousand[1], million[1], "as many elements");
    assertTrue(
        million[0] < 3 * thousand[0] + 1_000_000,
        "median " + million[0] + " ns with a million rows, " + thousand[0] + " with a thousand");
  }

  private static final int FRAMES = 600;

  /**
   * The median time of {@link App#frame} after a wheel step of 100 pixels, which builds the rows
   * anew most of the time, up and down within the first 4000 pixels; and the elements in the tree.
   */
  private long[] wheelFrames(int count) throws IOException {
    App<Orders> app = start(new Orders(T, count, 20));
    long[] times = new long[FRAMES];
    for (int i = -FRAMES; i < times.length; i++) { // the first round warms up
      app.wheel(280, 120, 0, Math.floorMod(i, 80) < 40 ? 100 : -100);
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

  // Selection

  @Test
  void aPressSelectsARowAndFocusesTheGridWithoutARing() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    run(app, "press #row-6\nframe");
    assertEquals(List.of("select 6"), orders.log);
    assertEquals(6, orders.selected);
    assertEquals("orders", app.focused().key());
    assertEquals(T.grid().selected(), find(app, "row-6").fill(), "the focused grid's selection");
    assertTrue(dumpLine(app, "row-6").endsWith(" selected pos=7/1000000"), dumpLine(app, "row-6"));

    run(app, "press 2,2\nframe"); // the window around the grid: the focus leaves it
    assertNull(app.focused());
    assertEquals(T.grid().selectedInactive(), find(app, "row-6").fill());
    assertEquals(T.grid().row(), find(app, "row-2").fill());
    assertEquals(T.grid().stripe(), find(app, "row-3").fill());
    assertTrue(dumpLine(app, "row-6").contains(" selected "), "still selected");
    assertTrue(dumpLine(app, "row-2").endsWith(" pos=3/1000000"), "row 2 is not");
  }

  @Test
  void theKeysMoveTheSelectionAndTheRowsFollowIt() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    run(app, "key Tab\nframe");
    assertEquals("orders", app.focused().key());

    assertEquals(3, key(app, orders, "ArrowDown"));
    assertEquals(2, key(app, orders, "ArrowUp"));
    assertEquals(2 + PAGE, key(app, orders, "PageDown"), "by the rows in view");
    assertInView(app, 2 + PAGE);
    assertEquals(2 + 2 * PAGE, key(app, orders, "PageDown"));
    assertInView(app, 2 + 2 * PAGE);
    assertEquals(2 + PAGE, key(app, orders, "PageUp"));
    assertInView(app, 2 + PAGE);
    assertEquals(999_999, key(app, orders, "End"));
    assertInView(app, 999_999);
    assertEquals(999_999, key(app, orders, "ArrowDown"), "stays at the end");
    assertEquals(999_999 - PAGE, key(app, orders, "PageUp"));
    assertInView(app, 999_999 - PAGE);
    assertEquals(0, key(app, orders, "Home"));
    assertInView(app, 0);
    assertEquals(0, key(app, orders, "ArrowUp"), "stays at the start");
    assertEquals(0, key(app, orders, "PageUp"));
    assertEquals(
        List.of("select 3", "select 2", "select 11", "select 20", "select 11", "select 999999",
            "select 999990", "select 0"),
        orders.log, "told only of changes");
  }

  @Test
  void aKeyWithoutASelectionSelectsTheFirstRowInView() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    orders.selected = -1;
    App<Orders> app = start(orders);
    run(app, "focus #orders\nwheel #rows 0 4400\nframe"); // row 200 at the top
    assertEquals(200, key(app, orders, "ArrowDown"));
    assertEquals(4400, find(app, "rows").scrollY(), "already in view");
  }

  @Test
  void theGridIsOneStopForTabAndLeavesOtherKeysAlone() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = new App<>(orders, o -> col().wFull().hFull().children(
        button(T, Variant.SECONDARY, "Before", () -> {}),
        o.grid().key("orders").grow(1),
        button(T, Variant.SECONDARY, "After", () -> {})));
    run(app, "frame\nkey Tab\nframe\nkey Tab\nframe");
    assertEquals("orders", app.focused().key());
    run(app, "key Tab\nframe");
    assertEquals("After", app.focused().label(), "not into the rows");
    run(app, "key Tab shift\nframe\nkey A\nframe\nkey ArrowDown shift\nframe");
    assertEquals("orders", app.focused().key());
    assertEquals(List.of(), orders.log);
  }

  private static int key(App<Orders> app, Orders orders, String name) {
    app.keyDown(new Key(name));
    app.keyUp(new Key(name));
    app.frame();
    return orders.selected;
  }

  /** The row is whole inside the grid's rows, and is the one with the selected look. */
  private static void assertInView(App<?> app, int row) {
    El rows = find(app, "rows");
    El el = find(app, "row-" + row);
    assertTrue(el != null && el.y() >= 0 && el.y() + el.height() <= rows.height(),
        "row " + row + " in view at " + rows.scrollY());
    assertEquals(T.grid().selected(), el.fill());
    assertTrue(dumpLine(app, "row-" + row).contains(" selected "));
  }

  // Header

  @Test
  void theHeaderStaysWhileTheRowsScrollAndMovesWithThemSideways() throws IOException {
    App<Orders> app = start(new Orders(T, 1_000_000));
    El header = find(app, "header");
    float headerX = x(app, header);
    float headerY = y(app, header);
    float cellX = x(app, find(app, "row-0").children().getFirst());
    assertEquals(8, headerY);
    assertEquals(8, cellX);

    run(app, "wheel #rows 0 5000\nframe");
    assertEquals(5000, find(app, "rows").scrollY());
    assertEquals(headerX, x(app, find(app, "header")));
    assertEquals(headerY, y(app, find(app, "header")), "the header stays");

    run(app, "wheel #rows 300 0\nframe");
    assertEquals(headerX - 300, x(app, find(app, "header")));
    assertEquals(headerY, y(app, find(app, "header")));
    El first = find(app, "rows").children().getFirst();
    assertEquals(cellX - 300, x(app, first.children().getFirst()), "the cells move with it");
    assertEquals(5000, find(app, "rows").scrollY(), "the rows kept their offset");

    run(app, "wheel 300,19 -100 0\nframe"); // over the header, sideways too
    assertEquals(headerX - 200, x(app, find(app, "header")));
    run(app, "wheel 300,19 0 500\nframe"); // over the header, up and down: nothing moves
    assertEquals(5000, find(app, "rows").scrollY());
  }

  @Test
  void theRowsReachAcrossAGridWiderThanItsColumns() throws IOException {
    App<Orders> app = start(new Orders(T, 10, 2)); // 64 and 140 pixels of columns in 544
    assertEquals(544, find(app, "header").width());
    assertEquals(544, find(app, "row-0").width());
    run(app, "wheel #rows 100 0\nframe");
    assertEquals(0, find(app, "orders").scrollX(), "nothing to scroll sideways");
  }

  // Resizing and sorting

  @Test
  void draggingAHeaderEdgeResizesItsColumnDownToAMinimum() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    El edge = find(app, "resize-1");
    float x = x(app, edge) + edge.width() / 2; // 8 + 64 + 140, the right end of customer
    assertEquals(212, x);
    run(app, "move #resize-1\nframe");
    assertEquals(Cursor.EW_RESIZE, app.cursor());

    run(app, "down #resize-1\nmove 230,40\nframe");
    assertEquals(List.of("resize 1 158.0"), orders.log);
    assertEquals(158, orders.widths[1]);
    assertEquals(157, find(app, "column-1").width(), "less the line");
    assertEquals(157, find(app, "row-0").children().get(2).width(), "the cells too");
    assertEquals(8 + 64 + 158, x(app, find(app, "column-2")), "the next column follows");
    assertEquals(T.accent().color(), find(app, "header").children().get(3).fill(), "its line");

    run(app, "move 0,40\nframe"); // far to the left
    assertEquals(T.grid().minColumn(), orders.widths[1]);
    assertEquals(Cursor.EW_RESIZE, app.cursor(), "the edge's, wherever the pointer is");
    run(app, "move 260,300\nframe\nup 260,300\nframe"); // ends below the grid
    assertEquals(188, orders.widths[1]);
    assertEquals(
        List.of("resize 1 158.0", "resize 1 32.0", "resize 1 188.0", "resize 1 188.0"),
        orders.log, "no sort, no selection");
  }

  @Test
  void aPressOnAHeaderSortsAndAPressOnAnEdgeDoesNothing() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    assertTrue(app.tree().dump().contains("text \"▲\""), "sorted by id, ascending");
    assertTrue(dumpLine(app, "column-0").endsWith(" pos=1/7 sort=ascending"));
    assertTrue(dumpLine(app, "column-2").endsWith(" pos=3/7"));

    run(app, "press #column-2\nframe");
    assertEquals(new DataGrid.Sort(2, false), orders.sort);
    run(app, "press #column-2\nframe");
    assertEquals(new DataGrid.Sort(2, true), orders.sort);
    El email = find(app, "column-2");
    assertEquals("▼", email.children().getLast().text().string(), "after the title");
    assertTrue(dumpLine(app, "column-2").endsWith(" pos=3/7 sort=descending"));
    assertTrue(dumpLine(app, "column-0").endsWith(" pos=1/7"));
    run(app, "down #resize-2\nmove 412,19\nup 412,19\nframe"); // within the threshold
    assertEquals(List.of("sort 2", "sort 2"), orders.log);
    assertEquals(200, orders.widths[2]);
  }

  // Cells

  @Test
  void nullsAreMutedLongTextIsClippedAndNumbersAlignRight() throws IOException {
    App<Orders> app = start(new Orders(T, 1_000_000));
    run(app, "wheel #rows 300 0\nframe");
    El row = find(app, "row-1");
    El amount = row.children().get(8);
    El note = row.children().get(12);
    El nullNote = find(app, "row-0").children().get(12);
    El nullAmount = find(app, "row-5").children().get(8);

    assertEquals("NULL", text(nullNote).text().string());
    assertEquals(T.text().muted(), text(nullNote).text().color());
    assertEquals("NULL", text(nullAmount).text().string());
    assertEquals(right(nullAmount), right(text(nullAmount)), "aligned as the column");

    assertEquals("79.13", text(amount).text().string());
    assertEquals(T.text().primary(), text(amount).text().color());
    assertEquals(right(amount), right(text(amount)), "right, inside the padding");
    assertEquals(x(app, nullNote) + T.space().s(), x(app, text(nullNote)), "left");

    assertEquals(Orders.NOTE, text(note).text().string());
    assertTrue(text(note).width() > note.width(), "wider than its column");
    assertEquals(Style.Overflow.CLIP, note.style().overflowX, "and clipped by it");
  }

  private static El text(El cell) {
    return cell.children().getFirst();
  }

  /** The right end of the element's content box within its cell, or of the cell itself. */
  private static float right(El el) {
    return el.text() != null ? el.x() + el.width() : el.width() - T.space().s();
  }

  // Scrollbars

  @Test
  void theBarsReachTheLastRowAndTheLastColumnAndPressNothingElse() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    int rest = T.scrollbars().thumb();
    // The rows' bar at the grid's right edge, although the rows reach 420 pixels beyond it, from
    // below the header to above the bar along the bottom.
    assertEquals(new Scrollbars.Bar(544, 33, 6, 187, 544, 33, 6, 24, rest),
        find(app, "rows").verticalBar());
    Scrollbars.Bar sideways = find(app, "orders").horizontalBar();
    assertEquals(new Scrollbars.Bar(10, 224, 540, 6, 10, 224, 540 * 544 / 964f, 6, rest),
        sideways);
    assertNull(find(app, "orders").verticalBar(), "the grid itself does not scroll up and down");

    run(app, "down 547,40\nmove 547,400\nframe"); // the rows' thumb, to the end
    El rows = find(app, "rows");
    assertEquals(1_000_000 * 22.0 - rows.height(), rows.scrollY(), "the last row at the bottom");
    assertEquals(999_999, rows.firstRow() + rows.children().size() - 1);
    assertEquals(List.of(), orders.log, "no row selected");
    assertNull(app.focused(), "and the grid not focused");

    run(app, "up 547,400\ndown 100,227\nmove 700,227\nup 700,227\nframe");
    assertEquals(964 - 544, find(app, "orders").scrollX(), "the last column in view");
    assertEquals(544, find(app, "rows").verticalBar().thumbX(), "the rows' bar stays");
    assertEquals(x(app, find(app, "column-6")), x(app, find(app, "row-999999").children().get(12)),
        "the header moved with the rows");
  }

  // Images

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Orders orders = new Orders(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 1_000_000);
    Path folder = Path.of("target/grid", name);
    String script = Files.readString(Path.of("src/test/resources/scripts/grid.txt"));
    List<Path> files = Script.run(script, orders.app(), folder);
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches(
          "grid-" + name + "-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))),
          Files.readString(files.get(i + 1)));
    }
    assertEquals(10, files.size());
  }

  private static float x(App<?> app, El el) {
    float[] at = at(app.tree(), el, 0, 0);
    return at == null ? Float.NaN : at[0];
  }

  private static float y(App<?> app, El el) {
    float[] at = at(app.tree(), el, 0, 0);
    return at == null ? Float.NaN : at[1];
  }

  /** The window position of {@code target} below {@code el}, which is at this position. */
  private static float[] at(El el, El target, float left, float top) {
    float x = left + el.x();
    float y = top + el.y();
    if (el == target) {
      return new float[] {x, y};
    }
    for (El child : el.children()) {
      float[] found = at(child, target, x, y);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static El find(App<?> app, String key) {
    return find(app.tree(), key);
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
