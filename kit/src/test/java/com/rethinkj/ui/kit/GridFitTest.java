package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Fit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Columns fitted to their content once per result ({@link DataGrid.Options#fitColumns}), driven
 * by scripts on {@link Orders}: when the fit comes, with rows loaded in pages, after {@link
 * Fit#reset}, around a resize by hand and hidden columns, within the theme's limits, at scale 2,
 * with 300 columns, and a stored image.
 */
class GridFitTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** Every column in view: the grid is 1084 by 224 at 8,8. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 1100 240\n" + steps, app, out);
  }

  private static Orders fitted(Orders orders, Fit fit) {
    orders.more = orders.more.andThen(o -> o.fitColumns(fit))::apply;
    return orders;
  }

  private static List<String> resizes(Orders orders) {
    return orders.log.stream().filter(l -> l.startsWith("resize")).toList();
  }

  /** How many cells of the column that exist have a text that ends in an ellipsis. */
  private static int shortened(App<?> app, int column) {
    int count = 0;
    El rows = find(app, "rows");
    for (int i = 0; i < rows.children().size(); i++) {
      for (El part : find(app, "cell-" + (rows.firstRow() + i) + "-" + column).children()) {
        count += part.text() != null && part.text().shortened() ? 1 : 0;
      }
    }
    return count;
  }

  @Test
  void everyColumnIsFittedOnceInAHandlerOfTheFrameThatBuiltItsRows() throws IOException {
    Fit fit = new Fit();
    Orders orders = fitted(new Orders(T, 1_000_000), fit);
    App<Orders> app = orders.app();
    run(app, "frame");
    assertTrue(fit.isPending());
    assertTrue(shortened(app, 2) > 0, "the addresses are cut short before");
    assertTrue(app.tree().dump().contains("#fit-"), "what fits, while it is due");
    run(app, "frame"); // its handler runs, and the view is built with the widths
    assertFalse(fit.isPending());
    assertEquals(7, resizes(orders).size(), "" + orders.log);
    for (int c = 0; c < 6; c++) {
      assertEquals(0, shortened(app, c), "column " + c + " whole");
      assertTrue(orders.widths[c] >= T.grid().minColumn(), "" + orders.widths[c]);
    }
    assertEquals(T.grid().maxColumn(), orders.widths[6], "the note is longer than allowed");
    assertTrue(shortened(app, 6) > 0);
    assertFalse(app.tree().dump().contains("#fit-"), "nothing more to fit");

    float[] widths = orders.widths.clone();
    run(app, "wheel #rows 0 4000\nframe\nkey ArrowDown\nframe\nframe");
    assertEquals(7, resizes(orders).size(), "once per result, also where other rows come");
    assertTrue(Arrays.equals(widths, orders.widths));

    // A double press on an edge measures as the fit did.
    run(app, "wheel #rows 0 -4000\nframe\npress #resize-2\npress #resize-2 clicks 2\nframe");
    assertEquals("resize 2 " + widths[2], orders.log.getLast());
  }

  @Test
  void atScaleTwoTheFittedColumnsShowTheirTextWhole() throws IOException {
    Orders orders = fitted(new Orders(T, 1_000_000), new Fit());
    App<Orders> app = orders.app();
    Script.run("size 1100 240 scale 2\nframe\nframe", app, out);
    for (int c = 0; c < 6; c++) {
      assertEquals(0, shortened(app, c), "column " + c + " whole at scale 2");
    }
  }

  @Test
  void withRowsInPagesTheFitWaitsForTheFirstPageAndHeadersAloneAreNoReason() throws IOException {
    Fit fit = new Fit();
    Orders orders = fitted(Orders.cells(T), fit);
    orders.loaded = 0;
    App<Orders> app = orders.app();
    run(app, "frame\nframe\nframe\nframe");
    assertTrue(fit.isPending(), "nothing to measure but placeholders");
    assertEquals(List.of(), resizes(orders));
    assertTrue(orders.log.getFirst().startsWith("rows 0-"), "the app was asked for the rows");

    app.post(() -> orders.loaded = 1000); // the first page arrives
    run(app, "await\nframe");
    assertEquals(List.of(), resizes(orders), "the frame that shows the page");
    run(app, "frame");
    assertFalse(fit.isPending());
    assertEquals(7, resizes(orders).size(), "" + orders.log);
    assertEquals(0, shortened(app, 2));

    Fit empty = new Fit();
    Orders none = fitted(new Orders(T, 0), empty);
    App<Orders> noneApp = none.app();
    run(noneApp, "frame\nframe\nframe");
    assertEquals(List.of(), resizes(none), "no rows: the headers alone");
    noneApp.post(() -> none.count = 40);
    run(noneApp, "await\nframe\nframe");
    assertFalse(empty.isPending());
    assertEquals(7, resizes(none).size(), "" + none.log);
  }

  @Test
  void aResetFitsEveryColumnAgainAsForANewResult() throws IOException {
    Fit fit = new Fit();
    Orders orders = fitted(new Orders(T, 1_000_000), fit);
    App<Orders> app = orders.app();
    run(app, "frame\nframe");
    float[] widths = orders.widths.clone();
    app.post(() -> {
      Arrays.fill(orders.widths, 90); // a new result, with the widths the app starts with
      orders.selection.reset();
      fit.reset();
    });
    run(app, "await\nframe\nframe");
    assertEquals(14, resizes(orders).size());
    assertTrue(Arrays.equals(widths, orders.widths), Arrays.toString(orders.widths));
  }

  @Test
  void aColumnResizedByHandBeforeTheFitIsLeftAloneUntilAReset() throws IOException {
    Fit fit = new Fit();
    Orders orders = fitted(Orders.cells(T), fit);
    orders.loaded = 0;
    App<Orders> app = orders.app();
    // The email's edge, at 8 + numbers + 64 + 140 + 200, dragged about 30 to the left.
    run(app, "frame\nframe");
    float edge = 8 + find(app, "numbers").width() + 404;
    run(app, "down #resize-2\nmove " + (edge - 30) + ",19\nup " + (edge - 30) + ",19\nframe");
    float dragged = orders.widths[2];
    assertTrue(dragged < 175, "" + dragged);
    int told = orders.log.size();
    app.post(() -> orders.loaded = 1000);
    run(app, "await\nframe\nframe");
    assertFalse(fit.isPending());
    assertEquals(dragged, orders.widths[2], "left as the user made it");
    assertTrue(orders.widths[1] != 140, "the others fitted");
    assertTrue(orders.log.subList(told, orders.log.size()).stream()
        .noneMatch(l -> l.startsWith("resize 2 ")));

    app.post(fit::reset);
    run(app, "await\nframe\nframe");
    assertTrue(orders.widths[2] > 200, "fitted after a reset: " + orders.widths[2]);
  }

  @Test
  void theFitTakesTheThemesLimits() throws IOException {
    Fit fit = new Fit();
    Orders orders = fitted(new Orders(T, 1_000_000), fit);
    orders.widths[0] = 300; // a column of short numbers is narrowed down to them
    App<Orders> app = orders.app();
    run(app, "frame\nframe");
    assertTrue(
        orders.widths[0] >= T.grid().minColumn() && orders.widths[0] < 64, "" + orders.widths[0]);
    assertEquals(T.grid().maxColumn(), orders.widths[6]);
  }

  @Test
  void theDetailsOfCellsAreMeasuredWithTheirValues() throws IOException {
    Orders orders = fitted(Orders.detailed(T, 8), new Fit());
    App<Orders> app = orders.app();
    Script.run("size 1600 240\nframe\nframe", app, out);
    El cell = find(app, "cell-1-5");
    assertEquals(2, cell.children().size(), "EUR and the amount, both whole");
    assertEquals(0, shortened(app, 5));
  }

  @Test
  void withoutAResizeHandlerTheOptionThrowsAsTheWidthsAreTheApps() {
    List<Column> columns = List.of(new Column("id", 64, DataGrid.Align.RIGHT, true, i -> "1"));
    IllegalStateException e = assertThrows(
        IllegalStateException.class,
        () -> DataGrid.dataGrid(T, columns, 3, DataGrid.options().fitColumns(new Fit())));
    assertTrue(e.getMessage().contains("onResize"), e.getMessage());
  }

  // Images

  /** fit.txt. */
  @ParameterizedTest
  @CsvSource({"dark, 2", "light, 2"})
  void looksAsStored(String name, int scale) throws IOException {
    Orders orders =
        fitted(Orders.typed(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 8), new Fit());
    App<Orders> app = orders.app();
    String script = Files.readString(Path.of("src/test/resources/scripts/fit.txt"));
    List<Path> files = Script.run("size 720 240 scale " + scale + "\n" + script, app, out);
    Golden.assertSnaps("grid-fit-" + name + "-" + scale + "x-", files);
    assertEquals(2, files.size());
  }
}
