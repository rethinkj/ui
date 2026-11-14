package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.kit.DataGrid.Selection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The data grid's selection as a value, without a window or the native libraries: what {@link
 * GridCellsTest} drives through input.
 */
class GridSelectionTest {

  @Test
  void aMillionRowsSelectedAreOneRange() {
    Selection s = new Selection(Mode.CELLS);
    s.select(500_000, 2);
    s.selectAll(1_000_000);
    assertEquals(List.of(new Range(0, 0, 999_999, Range.ALL)), s.ranges());
    assertEquals(1_000_000, s.rowCount());
    assertEquals(500_000, s.row(), "the cursor stays");
    assertEquals(7_000_000, DataGrid.cellCount(s.ranges(), 7));
    s.clear();
    assertTrue(s.isEmpty());
    assertEquals(-1, s.anchorRow());
  }
}
