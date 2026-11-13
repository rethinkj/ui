package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The data grid's selection, copied text and sort keys as values, without a window or the native
 * libraries: what {@link GridCellsTest} drives through input.
 */
class GridSelectionTest {

  private static final int ALL = Range.ALL;

  @Test
  void selectExtendAndToggleCells() {
    Selection s = new Selection(Mode.CELLS);
    assertTrue(s.isEmpty());
    assertEquals(-1, s.row());
    s.select(3, 2);
    s.extend(1, 4); // from the anchor, in any direction
    assertEquals(List.of(new Range(1, 2, 3, 4)), s.ranges());
    assertEquals(1, s.row());
    assertEquals(4, s.column());
    assertEquals(3, s.anchorRow());
    assertEquals(2, s.anchorColumn());

    s.toggle(2, 3); // inside: the rectangle loses the cell, in four pieces around it
    assertFalse(s.contains(2, 3));
    int selected = 0;
    for (int r = 0; r <= 4; r++) {
      for (int c = 0; c <= 5; c++) {
        selected += s.contains(r, c) ? 1 : 0;
      }
    }
    assertEquals(8, selected);
    assertEquals(4, s.ranges().size());
    s.toggle(2, 3); // outside again: added back, cursor and anchor on it
    assertTrue(s.contains(2, 3));
    assertEquals(2, s.anchorRow());
    s.extend(4, 3); // replaces the last range only
    assertEquals(new Range(2, 3, 4, 3), s.ranges().getLast());
    assertTrue(s.contains(1, 2), "the earlier ranges stay");
    assertEquals(4, s.rowCount(), "rows 1 to 4, each once");
  }

  @Test
  void inRowsEveryRangeHoldsWholeRows() {
    Selection s = new Selection(Mode.ROWS);
    s.select(5, 3);
    assertEquals(List.of(new Range(5, 0, 5, ALL)), s.ranges());
    assertEquals(3, s.column(), "the column pressed, for editing");
    s.extend(8, 0);
    s.toggle(12, 1);
    assertEquals(List.of(new Range(5, 0, 8, ALL), new Range(12, 0, 12, ALL)), s.ranges());
    s.toggle(6, 4); // removes the whole row, whatever the column
    assertEquals(List.of(new Range(5, 0, 5, ALL), new Range(7, 0, 8, ALL),
        new Range(12, 0, 12, ALL)), s.ranges());
    assertTrue(s.isRowSelected(7));
    assertFalse(s.isRowSelected(6));
    assertEquals(4, s.rowCount());
  }

  @Test
  void aMillionRowsSelectedAreOneRange() {
    Selection s = new Selection(Mode.CELLS);
    s.select(500_000, 2);
    s.selectAll(1_000_000);
    assertEquals(List.of(new Range(0, 0, 999_999, ALL)), s.ranges());
    assertEquals(1_000_000, s.rowCount());
    assertEquals(500_000, s.row(), "the cursor stays");
    assertEquals(7_000_000, DataGrid.cellCount(s.ranges(), 7));
    s.clear();
    assertTrue(s.isEmpty());
    assertEquals(-1, s.anchorRow());
  }

  @Test
  void aRangeIsOrdered() {
    assertThrows(IllegalArgumentException.class, () -> new Range(3, 0, 2, 0));
    assertThrows(IllegalArgumentException.class, () -> new Range(0, -1, 0, 0));
    assertTrue(new Range(0, 0, 0, ALL).wholeRows());
  }

  // Copying

  private static final List<Column> COLUMNS = List.of(
      new Column("id", 60, Align.RIGHT, true, i -> String.valueOf(i + 1)),
      new Column("name", 100, Align.LEFT, false, i -> switch (i) {
        case 1 -> "tab\there";
        case 2 -> "say \"hi\"";
        case 3 -> "two\nlines";
        case 4 -> "carriage\rreturn";
        default -> "plain";
      }),
      new Column("note", 100, Align.LEFT, false, i -> i % 2 == 0 ? null : "n" + i));

  @Test
  void copiedTextIsTabSeparatedWithQuotesWhereAFieldNeedsThem() {
    List<Range> ranges = List.of(new Range(0, 0, 4, 2));
    assertEquals(String.join("\n",
        "1\tplain\t",
        "2\t\"tab\there\"\tn1",
        "3\t\"say \"\"hi\"\"\"\t",
        "4\t\"two\nlines\"\tn3",
        "5\t\"carriage\rreturn\"\t"), DataGrid.copyText(COLUMNS, ranges, false, ""));
    assertEquals("id\tname\tnote\n1\tplain\tNULL",
        DataGrid.copyText(COLUMNS, List.of(new Range(0, 0, 0, ALL)), true, "NULL"),
        "whole rows reach the last column; NULL as the app says");
  }

  @Test
  void copiedTextHasTheRowsAndColumnsWithASelectedCellAndEmptyFieldsBetween() {
    // Row 0 column 0 and row 2 column 2: two rows, two columns, the other two fields empty.
    List<Range> ranges = List.of(new Range(0, 0, 0, 0), new Range(2, 2, 2, 2));
    assertEquals("1\t\n\t", DataGrid.copyText(COLUMNS, ranges, false, ""));
    assertEquals(4, DataGrid.cellCount(ranges, 3));
    // Overlapping ranges count each row once.
    List<Range> overlapping = List.of(new Range(0, 0, 3, 0), new Range(2, 0, 5, 0));
    assertEquals(6, DataGrid.cellCount(overlapping, 3));
    assertEquals("1\n2\n3\n4\n5\n6", DataGrid.copyText(COLUMNS, overlapping, false, ""));
  }

  // Sorting

  @Test
  void aPressCyclesItsColumnAndShiftAddsOrKeepsTheOthers() {
    List<SortKey> none = List.of();
    List<SortKey> up = DataGrid.nextSort(none, 2, false);
    assertEquals(List.of(new SortKey(2, false)), up);
    assertEquals(List.of(new SortKey(2, true)), DataGrid.nextSort(up, 2, false));
    assertEquals(none, DataGrid.nextSort(List.of(new SortKey(2, true)), 2, false));
    // Without Shift another column replaces the keys; with it, it comes last.
    assertEquals(List.of(new SortKey(4, false)), DataGrid.nextSort(up, 4, false));
    List<SortKey> two = DataGrid.nextSort(up, 4, true);
    assertEquals(List.of(new SortKey(2, false), new SortKey(4, false)), two);
    // With Shift a key changes direction in its place, and goes after descending.
    assertEquals(List.of(new SortKey(2, true), new SortKey(4, false)),
        DataGrid.nextSort(two, 2, true));
    assertEquals(List.of(new SortKey(4, false)),
        DataGrid.nextSort(List.of(new SortKey(2, true), new SortKey(4, false)), 2, true));
  }
}
