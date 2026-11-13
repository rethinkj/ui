package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.CellValue;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.kit.DataGrid.Status;
import com.rethinkj.ui.kit.GridChanges.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The log of changes ({@link GridChanges}) against a plain model, a list of rows copied for each
 * step, through random runs of setting, pasting, adding, deleting, undoing and redoing; and the
 * reading of pasted text ({@link DataGrid#parseText}). Neither needs native libraries.
 */
class GridChangesTest {

  private static final int COLUMNS = 3;

  private static String base(int row, int column) {
    return row % 5 == 4 && column == 2 ? null : "r" + row + "c" + column;
  }

  /** A row of the model: the app's row with its key, or a new one, with its values. */
  private record Row(long key, boolean added, boolean deleted, Map<Integer, String> values) {

    Row with(boolean deleted, Map<Integer, String> values) {
      return new Row(key, added, deleted, values);
    }

    String cell(int column) {
      return values.containsKey(column) ? values.get(column)
          : added ? null : base((int) key, column);
    }
  }

  @Test
  void randomRunsOfChangesMatchAPlainModel() {
    for (int seed = 0; seed < 200; seed++) {
      run(new Random(seed), 20);
    }
  }

  private void run(Random random, int count) {
    GridChanges changes = new GridChanges(count, GridChangesTest::base);
    List<Row> rows = new ArrayList<>();
    for (int r = 0; r < count; r++) {
      rows.add(new Row(r, false, false, Map.of()));
    }
    List<List<Row>> undo = new ArrayList<>();
    List<List<Row>> redo = new ArrayList<>();
    String[] values = {null, "x", "y", ""};
    for (int step = 0; step < 60; step++) {
      List<Row> before = List.copyOf(rows);
      int n = rows.size();
      int what = random.nextInt(10);
      String did;
      if (what < 3 && n > 0) {
        List<CellValue> cells = new ArrayList<>();
        for (int k = random.nextInt(3); k >= 0; k--) {
          int row = random.nextInt(n);
          int column = random.nextInt(COLUMNS);
          String value = random.nextInt(4) == 0 ? rows.get(row).cell(column)
              : values[random.nextInt(values.length)];
          if (!rows.get(row).added() && random.nextInt(5) == 0) {
            value = base((int) rows.get(row).key(), column); // back to the app's
          }
          cells.add(new CellValue(row, column, value));
          Row r = rows.get(row);
          if (!r.deleted()) {
            Map<Integer, String> v = new HashMap<>(r.values());
            if (!r.added() && Objects.equals(value, base((int) r.key(), column))) {
              v.remove(column);
            } else {
              v.put(column, value);
            }
            rows.set(row, r.with(false, v));
          }
        }
        changes.set(cells);
        did = "set " + cells;
      } else if (what < 5) {
        int after = random.nextInt(n + 1) - 1;
        changes.insert(after);
        rows.add(after + 1, new Row(-1, true, false, Map.of()));
        did = "insert after " + after;
      } else if (what < 7 && n > 0) {
        int top = random.nextInt(n);
        int bottom = Math.min(n - 1, top + random.nextInt(3));
        changes.delete(List.of(new Range(top, 0, bottom, Range.ALL)));
        boolean all = true;
        for (int r = top; r <= bottom; r++) {
          all &= rows.get(r).added() || rows.get(r).deleted();
        }
        for (int r = bottom; r >= top; r--) {
          Row row = rows.get(r);
          if (row.added()) {
            rows.remove(r);
          } else {
            rows.set(r, row.with(!all, row.values()));
          }
        }
        did = "delete " + top + "-" + bottom;
      } else if (what < 9) {
        changes.undo();
        if (!undo.isEmpty()) {
          redo.add(List.copyOf(rows));
          rows = new ArrayList<>(undo.removeLast());
        }
        did = "undo";
        compare(changes, rows, did);
        continue;
      } else {
        changes.redo();
        if (!redo.isEmpty()) {
          undo.add(List.copyOf(rows));
          rows = new ArrayList<>(redo.removeLast());
        }
        did = "redo";
        compare(changes, rows, did);
        continue;
      }
      if (!same(before, rows)) {
        undo.add(before);
        redo.clear();
      }
      compare(changes, rows, did);
    }
  }

  /** The same rows: new ones are told apart by their places only. */
  private static boolean same(List<Row> a, List<Row> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      Row x = a.get(i);
      Row y = b.get(i);
      if (x.added() != y.added() || x.deleted() != y.deleted() || !x.added() && x.key() != y.key()
          || !x.values().equals(y.values())) {
        return false;
      }
    }
    return true;
  }

  private static void compare(GridChanges changes, List<Row> rows, String did) {
    assertEquals(rows.size(), changes.rowCount(), did);
    int updates = 0;
    int inserts = 0;
    int deletes = 0;
    for (int r = 0; r < rows.size(); r++) {
      Row row = rows.get(r);
      Status status = row.added() ? Status.NEW : row.deleted() ? Status.DELETED
          : row.values().isEmpty() ? Status.UNCHANGED : Status.CHANGED;
      assertEquals(status, changes.rowStatus(r), did + ", row " + r);
      for (int c = 0; c < COLUMNS; c++) {
        assertEquals(row.cell(c), changes.cell(r, c), did + ", row " + r + " column " + c);
        Status cell = status == Status.CHANGED
            ? (row.values().containsKey(c) ? Status.CHANGED : Status.UNCHANGED) : status;
        assertEquals(cell, changes.cellStatus(r, c), did + ", cell " + r + "," + c);
      }
      if (row.added()) {
        inserts++;
      } else if (row.deleted()) {
        deletes++;
      } else if (!row.values().isEmpty()) {
        updates++;
      }
    }
    assertEquals(updates + inserts + deletes, changes.count(), did);
    List<GridChanges.Change> list = changes.changes();
    assertEquals(deletes, list.stream().filter(c -> c.kind() == Kind.DELETE).count(), did);
    assertEquals(inserts, list.stream().filter(c -> c.kind() == Kind.INSERT).count(), did);
  }

  @Test
  void changesStayWithTheirRowsWhileTheAppFiltersAndSortsThem() {
    int[] order = {0, 1, 2, 3, 4, 5};
    GridChanges.Rows rows = rows(order);
    GridChanges changes = new GridChanges(rows);
    changes.set(2, 0, "two");
    changes.insert(2); // after the row with key 2, shown at 3
    changes.set(3, 1, "new");
    changes.delete(List.of(new Range(5, 0, 5, Range.ALL))); // shown 5 is key 4

    int[] reversed = {5, 4, 3, 2, 1, 0};
    changes.rows(rows(reversed));
    assertEquals(7, changes.rowCount());
    assertEquals("two", changes.cell(3, 0), "key 2 is shown at 3");
    assertEquals(Status.NEW, changes.rowStatus(4), "after it");
    assertEquals("new", changes.cell(4, 1));
    assertEquals(Status.DELETED, changes.rowStatus(1), "key 4");

    int[] filtered = {0, 1, 3, 4}; // key 2 hidden: its new row goes to the end
    changes.rows(rows(filtered));
    assertEquals(5, changes.rowCount());
    assertEquals(Status.NEW, changes.rowStatus(4));
    assertEquals(List.of(Kind.DELETE, Kind.UPDATE, Kind.INSERT),
        changes.changes().stream().map(GridChanges.Change::kind).toList());
    assertEquals(4, changes.changes().get(0).key());
    assertEquals(Map.of(0, "two"), changes.changes().get(1).values());
    changes.clear();
    assertEquals(4, changes.rowCount());
    assertEquals(0, changes.count());
  }

  private static GridChanges.Rows rows(int[] keys) {
    return new GridChanges.Rows() {
      @Override
      public int count() {
        return keys.length;
      }

      @Override
      public long key(int row) {
        return keys[row];
      }

      @Override
      public int place(long key) {
        for (int i = 0; i < keys.length; i++) {
          if (keys[i] == key) {
            return i;
          }
        }
        return -1;
      }

      @Override
      public String cell(int row, int column) {
        return base(keys[row], column);
      }
    };
  }

  @Test
  void aFailedCellIsAnErrorUntilItChangesAgain() {
    GridChanges changes = new GridChanges(5, GridChangesTest::base);
    changes.set(1, 1, "x");
    changes.error(1, 1, "too long");
    assertEquals(Status.ERROR, changes.rowStatus(1));
    assertEquals(Status.ERROR, changes.cellStatus(1, 1));
    assertEquals("too long", changes.errorText(1, 1));
    changes.set(1, 1, "y");
    assertEquals(Status.CHANGED, changes.cellStatus(1, 1));
    assertNull(changes.errorText(1, 1));
  }

  // Pasted text

  @Test
  void pastedTextIsReadAsSpreadsheetsWriteIt() {
    assertEquals(List.of(), DataGrid.parseText(""));
    assertEquals(List.of(List.of("")), DataGrid.parseText("\n"));
    assertEquals(List.of(List.of("a", "b"), List.of("c")), DataGrid.parseText("a\tb\r\nc"));
    assertEquals(List.of(List.of("x")), DataGrid.parseText("x\r\n"), "the line break at the end");
    assertEquals(List.of(List.of("a", "")), DataGrid.parseText("a\t"));
    assertEquals(List.of(List.of("a\"bc", "d")), DataGrid.parseText("\"a\"\"b\"c\td"));
    assertEquals(List.of(List.of("one\ttwo\nthree")), DataGrid.parseText("\"one\ttwo\nthree\""));
    assertEquals(List.of(List.of("\"ab"), List.of("c")), DataGrid.parseText("\"ab\nc"),
        "a quote never closed is text");
    assertEquals(List.of(List.of("a\"b")), DataGrid.parseText("a\"b"));
  }

  @Test
  void whatTheGridCopiesIsReadBackAsItWas() {
    Random random = new Random(7);
    String alphabet = "ab \t\n\r\"x";
    for (int run = 0; run < 300; run++) {
      int rows = 1 + random.nextInt(4);
      int columns = 1 + random.nextInt(4);
      String[][] cells = new String[rows][columns];
      List<Column> list = new ArrayList<>();
      for (int c = 0; c < columns; c++) {
        int column = c;
        list.add(new Column("c" + c, 50, Align.LEFT, false, row -> cells[row][column]));
      }
      for (String[] row : cells) {
        for (int c = 0; c < columns; c++) {
          StringBuilder text = new StringBuilder();
          for (int k = random.nextInt(5); k > 0; k--) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
          }
          row[c] = text.toString().replace("\r\n", "\n").replace('\r', '\n');
        }
      }
      if (columns == 1 && rows > 0 && cells[rows - 1][0].isEmpty()) {
        cells[rows - 1][0] = "z"; // an empty last line is no line, as for spreadsheets
      }
      String text = DataGrid.copyText(list, List.of(new Range(0, 0, rows - 1, columns - 1)),
          false, "");
      List<List<String>> read = DataGrid.parseText(text);
      List<List<String>> expected = new ArrayList<>();
      for (String[] row : cells) {
        expected.add(List.of(row));
      }
      assertEquals(expected, read, text);
    }
  }
}
