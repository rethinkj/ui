package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
  // The grid of Orders.view: 544 by 224 at 8,8, with a header of 24 and a line of 1 over 199
  // pixels of rows, of which the bar sideways covers the last 10, so that 7 rows show whole. Layout
  // sees at most 2^20 pixels of the 24 000 000 of a million rows, so that its floats add up to the
  // pixel.
  private static final int PAGE = 7;

  @TempDir
  Path out;

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

  // Rows

  @Test
  void aMillionRowsOfTwentyColumnsAskOnlyForTheCellsThatExist() throws IOException {
    Orders orders = new Orders(T, 1_000_000, 20);
    App<Orders> app = start(orders);
    assertAskedForExistingRows(orders, app, 0);
    El rows = find(app, "rows");
    assertEquals(199, rows.height(), "down to the bottom of the grid");
    assertTrue(
        rows.children().size() <= PAGE + 2 + 2 * El.OVERSCAN,
        "" + rows.children().size());

    orders.asked.clear();
    run(app, "scroll #rows row 654321\nframe");
    assertAskedForExistingRows(orders, app, 654_321 - El.OVERSCAN);
    assertEquals(654_321 - El.OVERSCAN, find(app, "rows").firstRow());
    rows = find(app, "rows");
    int kept = rows.firstRow() + rows.children().size();
    orders.asked.clear();
    app.wheel(280, 120, 0, 300); // past the overscan: builds the new rows, not the view
    app.frame();
    assertAskedForExistingRows(orders, app, kept);
  }

  /**
   * Every cell of the rows from {@code from} on that exist was asked for once since the last
   * clear, and no other: those rows, each with the cells of the columns in view sideways and some
   * more, not all 20.
   */
  private static void assertAskedForExistingRows(Orders orders, App<?> app, int from) {
    El rows = find(app, "rows");
    int end = rows.firstRow() + rows.children().size();
    List<Integer> exist = IntStream.range(from, end).boxed().toList();
    assertEquals(exist, List.copyOf(new TreeSet<>(orders.asked)));
    long cells = rows.children().subList(from - rows.firstRow(), rows.children().size()).stream()
        .flatMap(r -> r.children().stream())
        .filter(c -> "gridcell".equals(c.role()))
        .count();
    assertEquals(cells, orders.asked.size(), "each cell once");
    assertTrue(cells < exist.size() * 20L / 2, cells + " cells of " + exist.size() * 20);
  }

  // Selection

  @Test
  void aPressSelectsARowAndFocusesTheGridWithoutARing() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    run(app, "press #row-6\nframe");
    assertEquals(List.of("select 6"), orders.log);
    assertEquals(6, orders.selected());
    assertEquals("orders", app.focused().key());
    assertEquals(T.grid().selected(), find(app, "row-6").fill(), "the focused grid's selection");
    assertTrue(
        dumpLine(app, "row-6").endsWith(" selected current pos=7/1000000"), dumpLine(app, "row-6"));

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
        List.of(
            "select 3",
            "select 2",
            "select 9",
            "select 16",
            "select 9",
            "select 999999",
            "select 999992",
            "select 0"),
        orders.log,
        "told only of changes");
  }

  @Test
  void scrollToRowPutsTheRowAtTheTopAndTheCursorStaysUntilAKeyMovesIt() throws IOException {
    Orders orders = new Orders(T, 1_000_000); // the cursor on row 2
    App<Orders> app = start(orders);
    run(app, "key Tab\nframe");
    DataGrid.Selection selection = orders.selection;
    assertEquals(0, selection.firstRowInView());
    assertEquals(PAGE - 1, selection.lastRowInView(), "row 7 is partly under the bar sideways");

    selection.scrollToRow(500_000);
    app.invalidate();
    app.frame();
    El rows = find(app, "rows");
    assertEquals(500_000 - El.OVERSCAN, rows.firstRow(), "the overscan above it, then the row");
    assertEquals(500_000 * 24.0, rows.scrollY(), "the row at the top");
    assertEquals(500_000, selection.firstRowInView());
    assertEquals(500_000 + PAGE - 1, selection.lastRowInView());
    for (int i = 0; i < 2; i++) {
      app.invalidate();
      app.frame();
    }
    assertEquals(500_000 * 24.0, find(app, "rows").scrollY(), "not back at the cursor");
    assertEquals(2, orders.selected());
    assertEquals(List.of(), orders.log, "the selection is as it was");

    app.wheel(280, 120, 0, 240); // without building the view, the rows in view follow
    app.frame();
    assertEquals(500_010, selection.firstRowInView());

    assertEquals(3, key(app, orders, "ArrowDown"));
    assertInView(app, 3);

    selection.select(4, 0); // in one handler: the rows go to 80, and back with the cursor
    selection.scrollToRow(80);
    app.invalidate();
    app.frame();
    assertEquals(80 * 24.0, find(app, "rows").scrollY());
    assertEquals(3, key(app, orders, "ArrowUp"));
    assertInView(app, 3);

    selection.scrollToRow(999_999);
    app.invalidate();
    app.frame();
    rows = find(app, "rows");
    assertEquals(1_000_000 * 24.0 + 10 - rows.height(), rows.scrollY(), "as far as the end goes");
    assertEquals(999_999, selection.lastRowInView());
    assertEquals(999_993, selection.firstRowInView(), "above the 10 pixels for the bar sideways");
    selection.reset();
    assertEquals(999_993, selection.firstRowInView(), "until the next build");
  }

  @Test
  void aViewReadsTheRowsInViewThatItsBuildScrollsTo() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    int[] read = new int[3];
    App<Orders> app = new App<>(orders, o -> {
          El grid = o.grid().key("orders").grow(1);
          read[0] = o.selection.firstRowInView(); // as a status line below the grid reads them
          read[1] = o.selection.lastRowInView();
          read[2]++;
          return col().wFull().hFull().children(grid);
        })
        .provide(Theme.CURRENT, o -> o.theme);
    run(app, "frame\nkey Tab\nframe");
    orders.selection.scrollToRow(200);
    app.invalidate();
    app.frame();
    int builds = read[2];
    app.frame();
    assertEquals(builds, read[2], "nothing builds the view again");
    assertEquals(orders.selection.firstRowInView(), read[0]);
    assertEquals(orders.selection.lastRowInView(), read[1]);
    assertEquals(200, read[0]);

    run(app, "key ArrowDown\nframe"); // from row 2 back into view
    assertEquals(3, orders.selected());
    assertEquals(orders.selection.firstRowInView(), read[0]);
    assertEquals(orders.selection.lastRowInView(), read[1]);
    assertEquals(3, read[0], "the cursor's row at the top");
  }

  @Test
  void theRowsInViewAreToldWhenTheyChangeAndBuildTheViewOnce() throws IOException {
    Orders orders = new Orders(T, 1_000_000); // the cursor on row 2
    List<String> told = new ArrayList<>();
    orders.more = o -> o.onRowsInView((first, last) -> told.add(first + "-" + last));
    int[] builds = {0};
    App<Orders> app = new App<>(orders, o -> {
          builds[0]++;
          return o.view();
        })
        .provide(Theme.CURRENT, o -> o.theme);
    run(app, "frame\nframe");
    assertEquals(List.of("0-6"), told, "when it first shows; row 7 is partly under the bar");
    DataGrid.Selection selection = orders.selection;

    app.wheel(280, 120, 0, 4); // row 0 leaves, row 7 comes whole
    assertToldOnce(app, builds, told, "1-7");
    app.wheel(280, 120, 0, 24); // a row on, within the rows built
    assertToldOnce(app, builds, told, "2-8");
    assertEquals(List.of(2, 8), List.of(selection.firstRowInView(), selection.lastRowInView()));
    int before = builds[0];
    app.wheel(280, 120, 0, 4); // neither row changes
    app.frame();
    app.frame();
    assertEquals(before, builds[0], "no rebuild");
    assertEquals("2-8", told.getLast());

    selection.scrollToRow(500_000);
    app.invalidate();
    app.frame();
    app.frame();
    assertEquals("500000-500006", told.getLast());
    before = builds[0];
    app.frame();
    assertEquals(before, builds[0], "settled");
    assertEquals(500_000 * 24.0, find(app, "rows").scrollY(), "not back at the cursor's row");

    selection.scrollToRow(999_999);
    app.invalidate();
    app.frame();
    app.frame();
    assertEquals("999993-999999", told.getLast(), "above the bar sideways, as the getters say");
  }

  @Test
  void rowsBuiltAndRowsInViewDueTogetherCostOneRebuild() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    List<String> told = new ArrayList<>();
    orders.more = o -> o.loading(row -> true, (first, last) -> told.add("built " + first))
        .onRowsInView((first, last) -> told.add("view " + first));
    int[] builds = {0};
    App<Orders> app = new App<>(orders, o -> {
          builds[0]++;
          return o.view();
        })
        .provide(Theme.CURRENT, o -> o.theme);
    run(app, "frame\nmove 280,120\nframe\nframe");
    for (int step = 1; step <= 3; step++) {
      int before = builds[0];
      told.clear();
      app.wheel(280, 120, 0, 48); // two rows, as a step of the wheel; the pointer rests on them
      for (int frame = 0; frame < 4; frame++) {
        app.frame();
      }
      assertFalse(app.handlersWaiting());
      assertEquals(
          Set.of("view " + 2 * step, "built " + Math.max(2 * step - El.OVERSCAN, 0)),
          Set.copyOf(told));
      assertEquals(before + 1, builds[0], "one rebuild for both");
    }
  }

  /**
   * The frame of a scroll tells {@code rows} in the next one, which is due at once, as {@link
   * El#onRowsBuilt} does; that builds the view once, and the one after not.
   */
  private static void assertToldOnce(App<?> app, int[] builds, List<String> told, String rows) {
    int before = builds[0];
    int tellings = told.size();
    app.frame();
    assertEquals(app.time(), app.nextChange(), "due at once");
    app.frame();
    assertEquals(rows, told.getLast());
    assertEquals(tellings + 1, told.size());
    assertEquals(before + 1, builds[0], "one rebuild");
    app.frame();
    assertEquals(before + 1, builds[0]);
  }

  @Test
  void aNewGridOpensAtTheRowScrolledToRatherThanAtTheCursor() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    orders.selection.select(400, 0);
    orders.selection.scrollToRow(80);
    App<Orders> app = start(orders);
    assertEquals(80 * 24.0, find(app, "rows").scrollY());
  }

  @Test
  void keysInOneFrameEachMoveTheSelectionOn() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    run(app, "key Tab\nkey ArrowDown\nkey ArrowDown\nkey PageDown\nkey ArrowUp\nframe");
    assertEquals(2 + 2 + PAGE - 1, orders.selected());
    assertEquals(List.of("select 3", "select 4", "select 11", "select 10"), orders.log);
    assertInView(app, 10);
  }

  @Test
  void theGridIsOneStopForTabAndLeavesOtherKeysAlone() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = new App<>(
        orders,
        o -> col()
            .wFull()
            .hFull()
            .children(
                button(Variant.SECONDARY, "Before", () -> {}),
                o.grid().key("orders").grow(1),
                button(Variant.SECONDARY, "After", () -> {})));
    run(app, "frame\nkey Tab\nframe\nkey Tab\nframe");
    assertEquals("orders", app.focused().key());
    run(app, "key Tab\nframe");
    assertEquals("After", app.focused().label(), "not into the rows");
    run(app, "key Tab shift\nframe\nkey A\nframe\nkey ArrowDown alt\nframe");
    assertEquals("orders", app.focused().key());
    assertEquals(List.of(), orders.log);
  }

  @Test
  void enterAndADoublePressOpenARowUnlessTheyEditTheCell() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    orders.more = o -> o.rowNumbers().onActivate(row -> orders.log.add("open " + row));
    App<Orders> app = start(orders);
    run(app, "press #cell-4-1 clicks 2\nframe\nkey ArrowDown\nkey Enter\nframe");
    run(app, "press #number-2 clicks 2\nframe");
    assertEquals(
        List.of("select 4", "open 4", "select 5", "open 5", "select 2", "open 2"), orders.log);

    orders.log.clear();
    orders.more = o -> o.rowNumbers()
        .onActivate(row -> orders.log.add("open " + row))
        .onEdit((row, column) -> orders.log.add("edit " + row + " " + column));
    run(
        app,
        "frame\npress #cell-4-1 clicks 2\nframe\nkey Enter\nframe\npress #number-6 clicks 2\nframe");
    assertEquals(List.of("select 4", "edit 4 1", "edit 4 1", "select 6", "open 6"), orders.log);

    orders.log.clear();
    orders.editable = true; // editable columns, which the grid edits only with onCommit
    orders.more = o -> o.onActivate(row -> orders.log.add("open " + row))
        .loading(row -> row != 4, (first, last) -> {});
    run(app, "frame\npress #cell-3-1 clicks 2\nframe\npress #cell-4-1 clicks 2\nkey Enter\nframe");
    assertEquals(
        List.of("select 3", "open 3", "select 4"),
        orders.log,
        "3 is not edited without onCommit, 4 not loaded");
  }

  private static int key(App<Orders> app, Orders orders, String name) {
    app.keyDown(new Key(name));
    app.keyUp(new Key(name));
    app.frame();
    return orders.selected();
  }

  /** The row is whole inside the grid's rows, and is the one with the selected look. */
  private static void assertInView(App<?> app, int row) {
    El rows = find(app, "rows");
    El el = find(app, "row-" + row);
    assertTrue(
        el != null && el.y() >= 0 && el.y() + el.height() <= rows.height(),
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
    assertEquals(157, find(app, "cell-0-1").width(), "the cells too");
    assertEquals(8 + 64 + 158, x(app, find(app, "column-2")), "the next column follows");
    assertEquals(T.accent().color(), find(app, "header").children().get(3).fill(), "its line");

    run(app, "move 0,40\nframe"); // far to the left
    assertEquals(T.grid().minColumn(), orders.widths[1]);
    assertEquals(Cursor.EW_RESIZE, app.cursor(), "the edge's, wherever the pointer is");
    run(app, "move 260,300\nframe\nup 260,300\nframe"); // ends below the grid
    assertEquals(188, orders.widths[1]);
    assertEquals(
        List.of("resize 1 158.0", "resize 1 32.0", "resize 1 188.0", "resize 1 188.0"),
        orders.log,
        "no sort, no selection");
  }

  @Test
  void aPressOnAHeaderSortsAndAPressOnAnEdgeDoesNothing() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    String up = "\\u{"
        + Integer.toHexString(Symbol.ARROW_UP.codePoint(T.grid().mark()))
            .toUpperCase(java.util.Locale.ROOT)
        + "}";
    assertTrue(
        app.tree().dump().contains("text label=\"arrow_up\" \"" + up + "\""),
        "sorted by id, ascending");
    assertTrue(dumpLine(app, "column-0").endsWith(" pos=1/7 sort=ascending"));
    assertTrue(dumpLine(app, "column-2").endsWith(" pos=3/7"));

    run(app, "press #column-2\nframe");
    assertEquals(List.of(new DataGrid.SortKey(2, false)), orders.sort);
    run(app, "press #column-2\nframe");
    assertEquals(List.of(new DataGrid.SortKey(2, true)), orders.sort);
    El email = find(app, "column-2");
    assertEquals(
        Character.toString(Symbol.ARROW_DOWN.codePoint(T.grid().mark())),
        email.children().getLast().children().getFirst().text().string(),
        "after the title");
    assertTrue(dumpLine(app, "column-2").endsWith(" pos=3/7 sort=descending"));
    assertTrue(dumpLine(app, "column-0").endsWith(" pos=1/7"));
    run(app, "down #resize-2\nmove 412,19\nup 412,19\nframe"); // within the threshold
    assertEquals(List.of("sort [+2]", "sort [-2]"), orders.log);
    assertEquals(200, orders.widths[2]);
  }

  // Cells

  @Test
  void nullsAreMutedLongTextIsClippedAndNumbersAlignRight() throws IOException {
    App<Orders> app = start(new Orders(T, 1_000_000));
    run(app, "wheel #rows 300 0\nframe");
    El row = find(app, "row-1");
    El amount = row.children().get(8); // a cell and a line each
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
    assertTrue(text(note).text().shortened(), "wider than its column, so it ends in an ellipsis");
    assertTrue(text(note).width() <= note.width());
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
    assertEquals(
        new Scrollbars.Bar(544, 35, 6, 185, 544, 35, 6, 24, rest),
        find(app, "rows").verticalBar());
    // 964 pixels of columns and 10 for the rows' bar after them.
    Scrollbars.Bar sideways = find(app, "orders").horizontalBar();
    assertEquals(new Scrollbars.Bar(10, 224, 540, 6, 10, 224, 540 * 544 / 974f, 6, rest), sideways);
    assertNull(find(app, "orders").verticalBar(), "the grid itself does not scroll up and down");

    run(app, "down 547,40\nmove 547,400\nframe"); // the rows' thumb, to the end
    El rows = find(app, "rows");
    assertEquals(1_000_000 * 24.0 + 10 - rows.height(), rows.scrollY(), "10 past the last row");
    assertEquals(999_999, rows.firstRow() + rows.children().size() - 1);
    El last = find(app, "row-999999");
    assertEquals(
        sideways.trackY() - T.scrollbars().margin(),
        y(app, last) + last.height(),
        "the last row whole above the strip of the bar along the bottom");
    assertEquals(List.of(), orders.log, "no row selected");
    assertNull(app.focused(), "and the grid not focused");

    run(app, "up 547,400\ndown 100,227\nmove 700,227\nup 700,227\nframe");
    assertEquals(974 - 544, find(app, "orders").scrollX(), "the last column in view");
    Scrollbars.Bar down = find(app, "rows").verticalBar();
    assertEquals(544, down.thumbX(), "the rows' bar stays");
    El cell = find(app, "cell-999999-6"); // of the last column, whose line follows it
    assertEquals(
        down.trackX() - T.scrollbars().margin(),
        x(app, cell) + cell.width() + T.size().stroke(),
        "the last column whole left of the strip of the rows' bar");
    assertEquals(x(app, find(app, "column-6")), x(app, cell), "the header moved with the rows");
  }

  @Test
  void theCursorCellThatTheKeysRevealShowsClearOfBothBars() throws IOException {
    Orders orders = Orders.cells(T); // the cursor on the id of row 2
    App<Orders> app = start(orders);
    float m = T.scrollbars().margin();
    run(app, "key Tab\nkey ArrowRight\nkey ArrowRight\nkey ArrowRight\nframe");
    El cell = find(app, "cell-2-3");
    assertTrue(find(app, "orders").scrollX() > 0, "scrolled to show it");
    assertEquals(
        find(app, "rows").verticalBar().trackX() - m,
        x(app, cell) + cell.width() + T.size().stroke(),
        "its right edge at the strip");

    run(app, "key PageDown\nframe");
    cell = find(app, "cell-" + orders.selection.row() + "-3");
    assertTrue(find(app, "rows").scrollY() > 0, "scrolled to show it");
    assertEquals(
        find(app, "orders").horizontalBar().trackY() - m,
        y(app, cell) + cell.height() + T.size().stroke(),
        "its row's bottom at the strip");
  }

  // Images

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Orders orders = new Orders(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 1_000_000);
    assertEquals(2, snaps("grid", name, orders));
  }

  /** cells.txt: a range and two sort keys. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void cellsLookAsStored(String name) throws IOException {
    assertEquals(4, snaps("cells", name, Orders.cells(theme(name))));
  }

  /** rows.txt: rows selected with Shift and Control. */
  @Test
  void rowsLookAsStored() throws IOException {
    assertEquals(2, snaps("rows", "dark", new Orders(T, 1_000_000)));
  }

  /** numbers.txt: at scale 2, where a digit is wider than at 1, scrolled to the last row. */
  @Test
  void rowNumbersLookAsStoredAtScaleTwo() throws IOException {
    assertEquals(2, snaps("numbers", "dark", Orders.typed(T, 8)));
  }

  private static Theme theme(String name) {
    return name.equals("dark") ? Theme.DARK : Theme.LIGHT;
  }

  /**
   * Runs {@code <script>.txt} against the orders and compares each snap with the stored image
   * {@code <script>-<theme>-<snap>}, as far as the snaps are named for grid.txt: grid-dark-top.
   *
   * @return the number of files the script wrote
   */
  private static int snaps(String script, String theme, Orders orders) throws IOException {
    Path folder = Path.of("target/" + script, theme);
    String text = Files.readString(Path.of("src/test/resources/scripts/" + script + ".txt"));
    List<Path> files = Script.run(text, orders.app(), folder).stream()
        .filter(f -> !f.toString().endsWith(".clipboard.txt"))
        .toList();
    Golden.assertSnaps(script + "-" + theme + "-", files);
    return files.size();
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
