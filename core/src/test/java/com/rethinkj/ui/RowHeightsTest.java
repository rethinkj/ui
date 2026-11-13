package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    RowHeights heights = new RowHeights(i -> 10 + i % 7);
    heights.count(1000);
    double[] plain = new double[1000];
    for (int i = 0; i < plain.length; i++) {
      plain[i] = 10 + i % 7;
    }
    for (int step = 0; step < 5000; step++) {
      int row = random.nextInt(plain.length);
      double h = 1 + random.nextInt(300);
      assertEquals(h - plain[row], heights.measured(row, h));
      plain[row] = h;
      if (step % 50 == 0) {
        double sum = 0;
        for (int i = 0; i <= plain.length; i++) {
          assertEquals(sum, heights.top(i), 1e-6, "top of " + i);
          if (i < plain.length) {
            assertEquals(i, heights.at(sum), "the row that starts at " + sum);
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
    App<RowHeights> app = new App<>(heights, h -> col().wFull().hFull().child(
        el().key("list").h(100).rows(1000, h, i -> el().key("row").h(20 + 10 * (i % 4)))));
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
}
