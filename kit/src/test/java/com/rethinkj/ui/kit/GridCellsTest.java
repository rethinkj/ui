package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Text;
import com.rethinkj.ui.kit.DataGrid.Copy;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.kit.DataGrid.Selection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data grid of a million orders ({@link Orders}) driven by scripts, as an agent drives it:
 * selecting cells and rows with the pointer and the keys, copying, the context menu, sorting by
 * several columns, fitting and frozen columns, hundreds of columns, shortened cells and rows that
 * are not loaded. The assertions read the tree dump's states where it says what they check.
 */
class GridCellsTest {

  private static final Theme T = Theme.DARK;
  private static final int ALL = Range.ALL;

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

  /** Runs the steps in a window of 560 by 240: the grid is 544 by 224 at 8,8. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 560 240\n" + steps, app, out);
  }

  private static Orders cells() {
    return new Orders(T, 1_000_000, 7, Mode.CELLS);
  }

  private static String line(App<?> app, String key) {
    String line = dumpLine(app, key);
    assertNotNull(line, "no #" + key);
    return line;
  }

  @Test
  void aCellShowsItsLineBreaksAndTabsOnOneLineAndCopiesThemAsTheyAre() throws IOException {
    String note = "say \"hi\"\tthen\r\nbreak\nhere";
    Selection selection = new Selection(Mode.CELLS);
    Clipboard clipboard = Clipboard.inMemory();
    App<Object> app = new App<>(new Object(), s -> El.col().size(560, 240).child(
        DataGrid.dataGrid(T, List.of(new DataGrid.Column("note", 400, DataGrid.Align.LEFT, false,
                i -> note)), 3, DataGrid.options().select(selection).copy(clipboard))
            .key("grid").grow(1)));
    run(app, "frame\npress #cell-0-0\nkey C control\nframe");
    El cell = Workbench.find(app, "cell-0-0");
    Text text = cell.children().getFirst().text();
    assertEquals("say \"hi\"⇥then⏎break⏎here", text.string());
    assertEquals(1, text.lines().size(), "one line, as tall as the row");
    assertEquals("\"say \"\"hi\"\"\tthen\r\nbreak\nhere\"", clipboard.read(), "copied as it is");
  }

  // Selecting cells

  @Test
  void aPressSelectsACellAndShiftWithAPressExtendsFromTheAnchor() throws IOException {
    Orders orders = cells();
    App<Orders> app = start(orders);
    run(app, "press #cell-1-1\npress #cell-4-2 shift\nframe");
    Selection s = orders.selection;
    assertEquals(List.of(new Range(1, 1, 4, 2)), s.ranges());
    assertEquals(List.of(4, 2, 1, 1), List.of(s.row(), s.column(), s.anchorRow(), s.anchorColumn()));
    assertTrue(line(app, "cell-2-1").endsWith(" selected pos=2/7"), line(app, "cell-2-1"));
    assertTrue(line(app, "cell-4-2").endsWith(" selected current pos=3/7"));
    assertTrue(line(app, "cell-4-3").endsWith(" 149x22 pos=4/7"), "outside the range");
    assertTrue(line(app, "row-2").endsWith(" pos=3/1000000"), "no row is selected in cells");
    assertEquals("orders", app.focused().key());
    assertEquals(T.grid().cursor(), find(app, "cell-4-2").borderColor(), "the cursor's outline");
    assertEquals(T.grid().selected(), find(app, "cell-2-1").fill());

    run(app, "press 2,2\nframe"); // outside the grid: it loses the focus
    assertEquals(T.grid().selectedInactive(), find(app, "cell-2-1").fill());
    assertEquals(0, find(app, "cell-4-2").borderColor(), "no outline without the focus");
    assertTrue(line(app, "cell-4-2").endsWith(" selected current pos=3/7"), "still the cursor");
    assertEquals(List.of("select 1", "select 4"), orders.log);
  }

  @Test
  void controlWithAPressAddsACellOrTakesOneOutOfARange() throws IOException {
    Orders orders = cells();
    App<Orders> app = start(orders);
    Selection s = orders.selection;
    run(app, "press #cell-1-1\npress #cell-3-3 shift\npress #cell-6-2 control\nframe");
    assertEquals(List.of(new Range(1, 1, 3, 3), new Range(6, 2, 6, 2)), s.ranges());
    run(app, "press #cell-2-2 control\nframe"); // the middle: the pieces around it stay
    assertFalse(s.contains(2, 2));
    assertTrue(s.contains(1, 1) && s.contains(3, 3) && s.contains(2, 1) && s.contains(2, 3));
    assertTrue(line(app, "cell-2-2").endsWith(" current pos=3/7"), line(app, "cell-2-2"));
    assertTrue(line(app, "cell-2-3").endsWith(" selected pos=4/7"));
    run(app, "press #cell-6-2 control\nframe");
    assertFalse(s.contains(6, 2));
    assertEquals(List.of("select 1", "select 3", "select 6", "select 2", "select 6"), orders.log);
  }

  @Test
  void draggingExtendsToTheCellUnderThePointerAndScrollsBeyondTheEdges() throws IOException {
    Orders orders = cells();
    App<Orders> app = start(orders);
    Selection s = orders.selection;
    run(app, "down #cell-2-1\nmove #cell-5-2\nframe");
    assertEquals(List.of(new Range(2, 1, 5, 2)), s.ranges());

    // Below the grid: each movement takes the cursor to the row under the pointer, which the
    // rows scroll to; core scrolls on pointer movements only, so a pointer at rest stops.
    run(app, "move 300,260\nframe");
    El rows = find(app, "rows");
    assertTrue(rows.scrollY() > 0, "scrolled");
    int row = s.row();
    assertTrue(row > 9, "" + row);
    assertCursorRowInView(app, row);
    run(app, "move 300,280\nframe\nmove 300,300\nframe");
    assertTrue(s.row() > row, "further with each movement");
    assertCursorRowInView(app, s.row());

    // Beyond the right side: the columns follow, one movement at a time.
    run(app, "move 700,100\nframe");
    assertEquals(5, s.column());
    run(app, "move 720,100\nframe");
    assertEquals(6, s.column());
    // 964 pixels of columns and the 10 of the rows' bar after them.
    assertEquals(974 - 544, find(app, "orders").scrollX(),
        "the last column whole, clear of the rows' bar");
    run(app, "up 720,100\nframe");
    assertEquals(new Range(2, 1, s.row(), 6), s.ranges().getFirst());
    assertEquals(1, s.ranges().size());
  }

  private static void assertCursorRowInView(App<?> app, int row) {
    El rows = find(app, "rows");
    El el = find(app, "row-" + row);
    assertTrue(el != null && el.y() >= 0 && el.y() + el.height() <= rows.height(),
        "row " + row + " in view at " + rows.scrollY());
  }

  @Test
  void theArrowsMoveTheCursorAcrossCellsAndShiftExtends() throws IOException {
    Orders orders = cells(); // the cursor on the id of row 2
    App<Orders> app = start(orders);
    Selection s = orders.selection;
    run(app, "key Tab\nkey ArrowRight\nkey ArrowDown\nframe");
    assertEquals(List.of(3, 1), List.of(s.row(), s.column()));
    run(app, "key ArrowRight shift\nkey ArrowRight shift\nframe");
    assertEquals(List.of(new Range(3, 1, 3, 3)), s.ranges());
    assertTrue(line(app, "cell-3-3").endsWith(" selected current pos=4/7"));
    run(app, "key Home\nframe");
    assertEquals(List.of(new Range(3, 0, 3, 0)), s.ranges(), "the first column of the row");
    run(app, "key End\nframe");
    assertEquals(List.of(3, 6), List.of(s.row(), s.column()));
    assertEquals(974 - 544, find(app, "orders").scrollX(),
        "the last column scrolled into view, clear of the rows' bar");
    run(app, "key Home control\nframe");
    assertEquals(List.of(0, 0), List.of(s.row(), s.column()));
    assertEquals(0, find(app, "orders").scrollX());
    run(app, "key End control\nframe");
    assertEquals(List.of(999_999, 6), List.of(s.row(), s.column()));
    assertCursorRowInView(app, 999_999);
    run(app, "key PageUp\nframe");
    assertEquals(999_990, s.row(), "a page is the 9 rows in view");
    run(app, "key Home shift control\nframe"); // from the anchor up to the first cell
    assertEquals(List.of(new Range(0, 0, 999_990, 6)), s.ranges());
    run(app, "key A control\nframe");
    assertEquals(List.of(new Range(0, 0, 999_999, ALL)), s.ranges(), "one range");
    assertEquals(1_000_000, s.rowCount());
  }

  @Test
  void controlHomeAndEndScrollBackSidewaysAlsoWhereTheCursorStays() throws IOException {
    Orders orders = cells();
    App<Orders> app = start(orders);
    run(app, "key Tab\nkey Home control\nframe\nwheel #orders 300 0\nframe");
    assertEquals(300, find(app, "orders").scrollX(), "the wheel moved away from the cursor");
    run(app, "key Home control\nframe");
    assertEquals(List.of(0, 0), List.of(orders.selection.row(), orders.selection.column()));
    assertEquals(0, find(app, "orders").scrollX(), "back to the cursor, which stayed");

    Orders rows = new Orders(T, 1_000_000, 7, Mode.ROWS);
    App<Orders> rowsApp = start(rows);
    run(rowsApp, "key Tab\nframe\nwheel #orders 300 0\nframe\nkey End control\nframe");
    assertEquals(999_999, rows.selection.row());
    assertEquals(0, find(rowsApp, "orders").scrollX(), "a row's start");
  }

  @Test
  void aPressOnARowNumberSelectsItsRowAndShiftTheRowsBetween() throws IOException {
    Orders orders = Orders.cells(T);
    App<Orders> app = start(orders);
    Selection s = orders.selection;
    assertTrue(line(app, "number-4").startsWith("el #number-4 role=rowheader "));
    run(app, "press #number-4\npress #number-7 shift\nframe");
    assertEquals(List.of(new Range(4, 0, 7, ALL)), s.ranges());
    assertTrue(line(app, "cell-5-3").contains(" selected "));
    run(app, "press #number-5 control\nframe");
    assertEquals(List.of(new Range(4, 0, 4, ALL), new Range(6, 0, 7, ALL)), s.ranges());
  }

  @Test
  void inRowsShiftExtendsAndControlAddsOrRemovesARow() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    Selection s = orders.selection;
    run(app, "press #row-1\npress #row-3 shift\npress #row-6 control\nframe");
    assertEquals(List.of(new Range(1, 0, 3, ALL), new Range(6, 0, 6, ALL)), s.ranges());
    assertTrue(line(app, "row-2").endsWith(" selected pos=3/1000000"));
    assertTrue(line(app, "row-6").endsWith(" selected current pos=7/1000000"));
    assertTrue(line(app, "row-4").endsWith(" pos=5/1000000"));
    assertTrue(line(app, "cell-2-1").endsWith(" pos=2/7"), "cells carry no selection in rows");
    run(app, "press #row-2 control\nframe");
    assertEquals(List.of(new Range(1, 0, 1, ALL), new Range(3, 0, 3, ALL),
        new Range(6, 0, 6, ALL)), s.ranges());
    assertTrue(line(app, "row-2").endsWith(" current pos=3/1000000"));
    assertEquals(3, s.rowCount());
  }

  // Copying

  private static String cell(Orders orders, int row, int column) {
    return orders.columns().get(column).cell().apply(row);
  }

  @Test
  void controlCCopiesTheSelectionAsTabSeparatedTextWithNullEmpty() throws IOException {
    Orders orders = Orders.cells(T);
    App<Orders> app = start(orders);
    // The keys extend, as amount, beside created_at, is scrolled out of view.
    run(app, "press #cell-4-3\nkey ArrowRight shift\nkey ArrowDown shift\nkey C control\nframe");
    assertEquals(List.of(new Range(4, 3, 5, 4)), orders.selection.ranges());
    assertEquals(null, cell(orders, 5, 4), "row 5 has no amount");
    assertEquals(cell(orders, 4, 3) + "\t" + cell(orders, 4, 4) + "\n" + cell(orders, 5, 3) + "\t",
        app.clipboard().read());
    // Scrolled back first: the frozen id and numbers lie over customer while amount shows.
    run(app, "wheel #rows -1000 0\nframe\npress #cell-1-1\npress #cell-3-1 control\n"
        + "key Insert control\nframe");
    assertEquals(cell(orders, 1, 1) + "\n" + cell(orders, 3, 1), app.clipboard().read(),
        "the rows with a selected cell");
  }

  @Test
  void theContextMenuCopiesWithHeadersAndSelectsTheCellItOpensOnFirst() throws IOException {
    Orders orders = Orders.cells(T);
    App<Orders> app = start(orders);
    Selection s = orders.selection;
    run(app, "press #cell-1-1\npress #cell-2-2 shift\nframe\npress #cell-2-1 right\nframe\n"
        + "advance 0.25\nframe"); // past the menu's fade
    assertEquals(List.of(new Range(1, 1, 2, 2)), s.ranges(), "inside the range: it stays");
    assertTrue(orders.menu.isOpen());
    assertTrue(line(app, "item-0").contains("label=\"Copy\""));
    run(app, "press #item-1\nframe"); // Copy with headers
    assertEquals("customer\temail\n" + cell(orders, 1, 1) + "\t" + cell(orders, 1, 2) + "\n"
        + cell(orders, 2, 1) + "\t" + cell(orders, 2, 2), app.clipboard().read());
    assertFalse(orders.menu.isOpen());

    // Outside the range: the cell is selected first.
    run(app, "press #cell-5-3 right\nframe\nadvance 0.25\nframe");
    assertEquals(List.of(new Range(5, 3, 5, 3)), s.ranges());
    run(app, "press #item-3\nframe"); // the app's own item after the separator
    assertEquals("export", orders.log.getLast());
    run(app, "focus #orders\nframe\nkey F10 shift\nframe"); // and from the keyboard
    assertTrue(orders.menu.isOpen());
  }

  @Test
  void aCopyBeyondTheLimitOrOfRowsNotLoadedIsLeftToTheApp() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    List<Copy> left = new ArrayList<>();
    orders.more = o -> o.copy(orders.app.clipboard()).copyLimit(1000, left::add);
    App<Orders> app = start(orders);
    app.clipboard().write("before");
    run(app, "key Tab\nkey A control\nkey C control\nframe");
    List<Range> all = List.of(new Range(0, 0, 999_999, ALL));
    assertEquals(all, orders.selection.ranges());
    assertEquals("before", app.clipboard().read(), "as it was");
    assertEquals(List.of(new Copy(all, false, 7_000_000)), left);

    Orders lazy = Orders.cells(T); // rows from 1000 on are not loaded, and no handler is set
    App<Orders> lazyApp = start(lazy);
    lazyApp.clipboard().write("before");
    run(lazyApp, "press #cell-1-1\nframe\nscroll #rows row 995\nframe\npress #cell-1001-1 shift\n"
        + "key C control\nframe");
    assertEquals(List.of(new Range(1, 1, 1001, 1)), lazy.selection.ranges());
    assertEquals("before", lazyApp.clipboard().read());
    run(lazyApp, "press #cell-998-1 shift\nkey C control\nframe");
    assertEquals(998, lazyApp.clipboard().read().lines().count(), "rows 1 to 998, all loaded");
  }

  // Sorting

  @Test
  void shiftWithAPressAddsSortKeysWhichShowTheirOrder() throws IOException {
    Orders orders = new Orders(T, 1_000_000); // sorted by id
    orders.unsortable = 3;
    App<Orders> app = start(orders);
    run(app, "press #column-2 shift\nframe");
    assertTrue(line(app, "column-0").endsWith(" pos=1/7 sort=ascending"));
    assertTrue(line(app, "column-2").endsWith(" pos=3/7 sort=ascending"));
    assertEquals("2", mark(app, 2).children().getLast().text().string(), "its place among keys");
    assertEquals("1", mark(app, 0).children().getLast().text().string());
    run(app, "press #column-2 shift\nframe");
    assertTrue(line(app, "column-2").endsWith(" sort=descending"));
    run(app, "press #column-0 shift\nframe\npress #column-2 shift\nframe");
    assertEquals(1, mark(app, 0).children().size(), "one key: no number");
    run(app, "press #column-0\nframe\npress #column-1\nframe\npress #column-1\nframe");
    assertEquals(List.of("sort [+0, +2]", "sort [+0, -2]", "sort [-0, -2]", "sort [-0]",
        "sort []", "sort [+1]", "sort [-1]"), orders.log);

    run(app, "move #column-3\nframe"); // created_at cannot be sorted: no pointer, no press
    assertEquals(Cursor.DEFAULT, app.cursor());
    assertFalse(app.tree().dump().contains("el #column-3 role=columnheader label=\"created_at\""
        + " 412,8 149x22 pos=4/7 hovered"), "a press would go to the grid, not to the header");
    run(app, "press #column-3\nframe");
    assertEquals("sort [-1]", orders.log.getLast());
    run(app, "move #column-2\nframe");
    assertEquals(Cursor.POINTER, app.cursor());
  }

  /** The mark of a sorted column: its arrow and, with more keys, its number. */
  private static El mark(App<?> app, int column) {
    El header = find(app, "column-" + column);
    for (El child : header.children()) {
      if (child.text() == null) {
        return child;
      }
    }
    throw new AssertionError("column " + column + " shows no sort mark");
  }

  // Columns

  @Test
  void aDoublePressOnAnEdgeFitsTheColumnToItsRowsThatExistWithinTheThemesLimits()
      throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = orders.app();
    Script.run("size 1100 240\nframe", app, out); // every column in view
    assertTrue(shortened(app, 2) > 0, "some addresses are cut short");
    Script.run("size 1100 240\npress #resize-2\npress #resize-2 clicks 2\nframe", app, out);
    float email = orders.widths[2];
    assertTrue(email > 200 && email < T.grid().maxColumn(), "" + email);
    assertEquals(0, shortened(app, 2), "every address whole");
    Script.run("size 1100 240\npress #resize-6\npress #resize-6 clicks 2\nframe", app, out);
    assertEquals(T.grid().maxColumn(), orders.widths[6], "the note is longer than allowed");
    Script.run("size 1100 240\npress #resize-0\npress #resize-0 clicks 2\nframe", app, out);
    assertTrue(orders.widths[0] < 64 && orders.widths[0] >= T.grid().minColumn(),
        "" + orders.widths[0]);
    assertEquals(List.of("resize 2 " + email, "resize 6 480.0", "resize 0 " + orders.widths[0]),
        orders.log);
  }

  /** How many cells of the column that exist end in an ellipsis. */
  private static int shortened(App<?> app, int column) {
    int count = 0;
    El rows = find(app, "rows");
    for (int i = 0; i < rows.children().size(); i++) {
      El cell = find(app, "cell-" + (rows.firstRow() + i) + "-" + column);
      count += cell.children().getFirst().text().shortened() ? 1 : 0;
    }
    return count;
  }

  @Test
  void rowNumbersAndFrozenColumnsStayAtTheLeftWhileTheRestScrollsSideways() throws IOException {
    Orders orders = Orders.cells(T);
    App<Orders> app = start(orders);
    float number = x(app, "number-3");
    float id = x(app, "cell-3-0");
    float header = x(app, "column-0");
    float email = x(app, "cell-3-2");
    run(app, "wheel #rows 300 0\nframe");
    assertEquals(300, find(app, "orders").scrollX());
    assertEquals(number, x(app, "number-3"));
    assertEquals(id, x(app, "cell-3-0"));
    assertEquals(header, x(app, "column-0"));
    assertEquals(email - 300, x(app, "cell-3-2"), "the rest scrolls");
    run(app, "press #cell-3-0\nframe"); // where the id shows, not the column scrolled under it
    assertEquals(List.of(3, 0), List.of(orders.selection.row(), orders.selection.column()));
    run(app, "scroll #rows row 500\nframe"); // rows built later have them in place too
    assertEquals(id, x(app, "cell-502-0"));
    assertEquals(number, x(app, "number-502"));
  }

  @Test
  void draggingAnEdgeBeyondTheGridsSideScrollsTheGridAlong() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    El grid = find(app, "orders");
    run(app, "down #resize-2\nmove 470,19\nframe");
    assertEquals(258, orders.widths[2]);
    assertEquals(0, find(app, "orders").scrollX());
    run(app, "move 600,19\nframe"); // beyond the right side at 552
    assertEquals(388, orders.widths[2]);
    double scrolled = find(app, "orders").scrollX();
    assertTrue(scrolled > 0, "scrolled along");
    float edge = x(app, "resize-2") + T.grid().handle() / 2;
    assertTrue(edge <= 552 && edge > 500, "the edge in view at " + edge);
    run(app, "move 640,19\nframe");
    assertTrue(find(app, "orders").scrollX() > scrolled, "on with each movement");
    run(app, "up 640,19\nframe");
    double end = find(app, "orders").scrollX();
    run(app, "key Tab\nkey ArrowDown\nframe");
    assertEquals(end, find(app, "orders").scrollX(), "the keys do not scroll back");
    assertTrue(grid != find(app, "orders"));
  }

  // Many columns

  @Test
  void threeHundredColumnsBuildTheCellsInViewAndAHeaderOfAll() throws IOException {
    Orders orders = new Orders(T, 1_000_000, 300);
    App<Orders> app = start(orders);
    assertEquals(300, count(app.tree(), "columnheader"));
    long cells = count(find(app, "row-3"), "gridcell");
    assertTrue(cells >= 4 && cells <= 12, cells + " cells in a row");
    run(app, "wheel #rows 20000 0\nframe");
    double scrollX = find(app, "orders").scrollX();
    assertEquals(20_000, scrollX);
    int column = columnAt(orders, scrollX + 272); // the middle of the view
    El cell = find(app, "cell-3-" + column);
    assertNotNull(cell, "the column in view was built");
    float x = x(app, "cell-3-" + column);
    assertTrue(x > 8 - cell.width() && x < 552, "" + x);
    assertTrue(count(find(app, "row-3"), "gridcell") <= 12);
  }

  private static int columnAt(Orders orders, double x) {
    double left = 0;
    for (int c = 0; c < orders.widths.length; c++) {
      left += orders.widths[c];
      if (x < left) {
        return c;
      }
    }
    return orders.widths.length - 1;
  }

  private static long count(El el, String role) {
    long count = role.equals(el.role()) ? 1 : 0;
    for (El child : el.children()) {
      count += count(child, role);
    }
    return count;
  }

  /**
   * Scrolling a million rows of 300 columns: the median time of a frame after a wheel step, as
   * {@code DataGridTest} measures 20 columns, with the elements in the tree, and the time the CPU
   * renderer takes for the frame. Printed for the record; the test checks that the tree is as
   * small as with 20 columns.
   */
  @Test
  void aFrameOfScrollingCostsAboutAsMuchWithThreeHundredColumnsAsWithTwenty() throws IOException {
    long[] twenty = scrolling(20);
    long[] wide = scrolling(300);
    System.out.printf("DataGrid, a million rows: 20 columns %.1f µs a frame of scrolling, %.1f µs"
            + " one that builds the view after a key, %d elements, CPU render %.1f ms; 300 columns"
            + " %.1f µs, %.1f µs, %d elements, %.1f ms%n", twenty[0] / 1000.0, twenty[3] / 1000.0,
        twenty[1], twenty[2] / 1e6, wide[0] / 1000.0, wide[3] / 1000.0, wide[1], wide[2] / 1e6);
    assertTrue(wide[1] < twenty[1] + 300 * 5, wide[1] + " elements, " + twenty[1] + " with 20");
  }

  private long[] scrolling(int columns) throws IOException {
    App<Orders> app = start(new Orders(T, 1_000_000, columns));
    app.focus(find(app, "orders"));
    app.frame();
    int frames = 300;
    long[] times = new long[frames];
    for (int i = -frames; i < frames; i++) {
      app.wheel(280, 120, 0, Math.floorMod(i, 80) < 40 ? 100 : -100);
      long start = System.nanoTime();
      app.frame();
      if (i >= 0) {
        times[i] = System.nanoTime() - start;
      }
    }
    Arrays.sort(times);
    long[] keys = new long[frames]; // a key moves the selection: the view is built anew
    for (int i = -frames; i < frames; i++) {
      Key key = new Key(Math.floorMod(i, 40) < 20 ? "ArrowDown" : "ArrowUp");
      app.keyDown(key);
      long start = System.nanoTime();
      app.frame();
      if (i >= 0) {
        keys[i] = System.nanoTime() - start;
      }
    }
    Arrays.sort(keys);
    long render = Long.MAX_VALUE;
    for (int i = 0; i < 5; i++) {
      long start = System.nanoTime();
      app.render();
      render = Math.min(render, System.nanoTime() - start);
    }
    return new long[] {times[frames / 2], count(app.tree()), render, keys[frames / 2]};
  }

  private static long count(El el) {
    long count = 1;
    for (El child : el.children()) {
      count += count(child);
    }
    return count;
  }

  // Shortened text and rows not loaded

  @Test
  void aShortenedCellShowsItsWholeTextInATooltipAfterTheDelay() throws IOException {
    Orders orders = new Orders(T, 1_000_000);
    App<Orders> app = start(orders);
    run(app, "wheel #rows 400 0\nframe\nmove #cell-1-6\nframe\nadvance 0.4\nframe");
    String tooltip = "role=tooltip label=\"" + Orders.NOTE + "\"";
    assertFalse(app.tree().dump().contains(tooltip));
    run(app, "advance 0.1\nframe");
    assertTrue(app.tree().dump().contains(tooltip), "after the theme's delay");
    run(app, "move #cell-1-5\nframe\nadvance 1\nframe"); // "refunded" fits its column
    assertFalse(app.tree().dump().contains("role=tooltip"));
  }

  @Test
  void rowsNotLoadedShowPlaceholdersAndTheAppIsToldWhichRowsTheGridBuilt() throws IOException {
    Orders orders = Orders.cells(T);
    App<Orders> app = orders.app();
    run(app, "frame");
    assertEquals(app.time(), app.nextChange(), "the handler is due at once");
    run(app, "frame\nframe\nframe");
    assertEquals(List.of("rows 0-12"), orders.log, "once, not on every frame");

    orders.asked.clear();
    run(app, "scroll #rows row 2000\nframe\nframe");
    assertEquals("rows 1997-2012", orders.log.getLast());
    assertFalse(orders.asked.contains(2000), "no cell function for a row not loaded");
    El placeholder = find(app, "cell-2001-3").children().getFirst();
    assertEquals(Text.ELLIPSIS, placeholder.text().string());
    assertEquals(T.text().muted(), placeholder.text().color());

    // An app that fetches at once: the next frame shows the rows, and nothing more is told.
    orders.log.clear();
    orders.more = o -> o.loading(i -> i < orders.loaded, (first, last) -> {
      orders.log.add("rows " + first + "-" + last);
      orders.loaded = Math.max(orders.loaded, last + 1);
    });
    run(app, "scroll #rows row 3000\nframe\nframe");
    assertEquals(List.of("rows 2997-3012"), orders.log);
    assertTrue(orders.asked.contains(3001));
    assertEquals(cell(orders, 3001, 3), find(app, "cell-3001-3").children().getFirst().text()
        .string());
    run(app, "frame\nframe");
    assertEquals(1, orders.log.size());
  }

  // Helpers

  private static float x(App<?> app, String key) {
    float[] at = at(app.tree(), find(app, key), 0, 0);
    return at == null ? Float.NaN : at[0];
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
