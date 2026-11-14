package com.rethinkj.ui.kit;

import com.rethinkj.ui.kit.DataGrid.CellValue;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.kit.DataGrid.Status;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * The changes a user makes in a {@link DataGrid} before the app writes them, as a database tool
 * collects them until Commit: changed values, new rows and rows marked to be deleted, over the
 * app's own rows, which stay as they are. The grid shows the rows through it, edits through it
 * ({@link DataGrid.Options#changes}) and shows what changed; Control+Z and Control+Y step through
 * its history. The app keeps one in its state:
 *
 * <pre>{@code
 * GridChanges changes = new GridChanges(rows.size(), (row, column) -> rows.get(row)[column]);
 *
 * columns.add(new Column("name", 160, DataType.TEXT, changes.cell(1)).editable().nullable());
 * dataGrid(t, columns, changes.rowCount(), DataGrid.options().select(s.selection)
 *     .changes(changes))
 * button(t, PRIMARY, "Commit (" + changes.count() + ")", () -> {
 *   database.write(changes.changes()); // UPDATE, INSERT and DELETE
 *   changes.clear();                   // the rows hold them now
 * })
 * }</pre>
 *
 * <p>Rows are told apart by a key the app chooses ({@link Rows#key}), 0 or more, such as a
 * primary key or the row's index in the result, so that changes stay with their rows while the app filters or
 * sorts them. A new row comes after the row it was added after, wherever that is shown, and at
 * the end while that row is not shown. The grid's rows are the app's rows in the order the app
 * shows them, with the new rows among them; the methods that take a row count in those.
 *
 * <p>It holds only what changed: a million rows with three changes cost three entries. It needs
 * no native libraries.
 */
public final class GridChanges {

  /** The app's rows: those shown now, in the order shown. */
  public interface Rows {

    /** How many rows are shown. */
    int count();

    /** The key of the row shown at {@code row}, which stays the row's while it changes place. */
    long key(int row);

    /** Where the row with this key is shown, or -1 when it is not. */
    int place(long key);

    /** The app's value of a cell, null for NULL. */
    String cell(int row, int column);
  }

  /** A cell's value as the app has it, null for NULL. */
  @FunctionalInterface
  public interface Cells {
    String cell(int row, int column);
  }

  /** What a change does to the database. */
  public enum Kind {
    UPDATE,
    INSERT,
    DELETE
  }

  /**
   * A change to write: an update of a row's changed columns, the insert of a new row with the
   * columns given a value, or the delete of a row.
   *
   * @param key the row's key ({@link Rows#key}); for an insert, a key of its own below 0
   * @param values the columns and their new values, null for NULL, in the order of the columns;
   *     empty for a delete
   */
  public record Change(Kind kind, long key, SortedMap<Integer, String> values) {}

  /**
   * A change of a row of a {@link GridTable} with typed values ({@link #changes(GridTable,
   * List)}).
   *
   * @param key the row's key; for an insert, a key of its own below 0
   * @param row the app's row as it is, null for an insert
   * @param values the columns and their changes, in the order of the columns; empty for a delete
   */
  public record RowChange<T>(
      Kind kind, long key, T row, SortedMap<Integer, ValueChange<T>> values) {

    /**
     * Writes the new values into a row through the columns' withers or setters: {@link #row} for
     * an update, an empty row of the app's for an insert. Returns the new record, or the POJO the
     * setters changed.
     */
    public T apply(T row) {
      T next = row;
      for (ValueChange<T> v : values.values()) {
        next = v.cells().writeParsed(next, v.after());
      }
      return next;
    }
  }

  /**
   * A cell's value before and after, in the type of its column's format: a {@code BigDecimal} of
   * {@link Format#DECIMAL}, an enum of a {@link Format#choice} of its constants; null for NULL.
   * {@code if (v.after() instanceof BigDecimal amount) ...}
   *
   * @param column the column, as the grid counts them
   * @param cells the typed part of the column, which writes the value into a row
   * @param before the value in the app's row, null for NULL and for a new row
   * @param after the new value
   */
  public record ValueChange<T>(
      int column, GridTable.Cells<T, ?> cells, Object before, Object after) {}

  private static final int HISTORY = 100;
  private static final Object NULL = new Object(); // a value of NULL, besides no value at all

  private Rows rows;
  // The values changed, by row key (a new row's below 0) and column: a String or NULL.
  private final Map<Long, Map<Integer, Object>> edits = new HashMap<>();
  private final Set<Long> deleted = new HashSet<>();
  private final Map<Long, Map<Integer, String>> errors = new HashMap<>();
  private final List<New> added = new ArrayList<>(); // after their anchors; equal ones in order
  private long nextId = -1;
  private final Deque<List<Op>> undo = new ArrayDeque<>();
  private final Deque<List<Op>> redo = new ArrayDeque<>();
  // Where the new rows are shown, by the last layout: their places, ascending, and the rows.
  private int[] places = new int[0];
  private New[] shown = new New[0];
  private int count;

  /** A new row: its key, and the key of the row it follows, {@link #TOP} for the first. */
  private record New(long id, long anchor) {}

  private static final long TOP = Long.MIN_VALUE;

  /** Changes over the app's rows, which keep their places: row {@code i} has the key {@code i}. */
  public GridChanges(int count, Cells cells) {
    this(new Rows() {
      @Override
      public int count() {
        return count;
      }

      @Override
      public long key(int row) {
        return row;
      }

      @Override
      public int place(long key) {
        return key >= 0 && key < count ? (int) key : -1;
      }

      @Override
      public String cell(int row, int column) {
        return cells.cell(row, column);
      }
    });
  }

  public GridChanges(Rows rows) {
    this.rows = Objects.requireNonNull(rows, "rows");
    layout();
  }

  /** Replaces the app's rows, as after it filtered or sorted them; the changes stay by key. */
  public void rows(Rows rows) {
    this.rows = Objects.requireNonNull(rows, "rows");
    layout();
  }

  // Reading

  /**
   * The rows the grid shows: the app's and the new ones. Places the new rows anew, as the app's
   * rows may have moved: call it once per build, before the cells.
   */
  public int rowCount() {
    layout();
    return count;
  }

  /** The value shown in a cell: the changed one, else the app's; null for NULL. */
  public String cell(int row, int column) {
    int at = Arrays.binarySearch(places, row);
    if (at >= 0) {
      Object value = value(shown[at].id(), column);
      return value == null || value == NULL ? null : (String) value;
    }
    int base = row - (-at - 1);
    Object value = value(rows.key(base), column);
    return value == null ? rows.cell(base, column) : value == NULL ? null : (String) value;
  }

  /** {@link #cell(int, int)} of a column, for its {@link DataGrid.Column}. */
  public IntFunction<String> cell(int column) {
    return row -> cell(row, column);
  }

  /** What a row holds against the app's: new, deleted, failed, changed or unchanged. */
  public Status rowStatus(int row) {
    if (isNew(row)) {
      return Status.NEW;
    }
    long key = key(row);
    if (deleted.contains(key)) {
      return Status.DELETED;
    }
    if (errors.containsKey(key)) {
      return Status.ERROR;
    }
    return edits.containsKey(key) ? Status.CHANGED : Status.UNCHANGED;
  }

  /**
   * What a cell holds: as its row when that is deleted, else failed, as its row when that is new,
   * else changed or not.
   */
  public Status cellStatus(int row, int column) {
    Status status = rowStatus(row);
    if (status == Status.DELETED || status == Status.UNCHANGED) {
      return status;
    }
    long key = key(row);
    Map<Integer, String> failed = errors.get(key);
    if (failed != null && failed.containsKey(column)) {
      return Status.ERROR;
    }
    if (status == Status.NEW) {
      return status;
    }
    Map<Integer, Object> values = edits.get(key);
    return values != null && values.containsKey(column) ? Status.CHANGED : Status.UNCHANGED;
  }

  /** Whether the row is a new one. */
  public boolean isNew(int row) {
    return Arrays.binarySearch(places, row) >= 0;
  }

  /** The key of the row shown at {@code row}: the app's, or a new row's below 0. */
  public long key(int row) {
    int at = Arrays.binarySearch(places, row);
    return at >= 0 ? shown[at].id() : rows.key(row - (-at - 1));
  }

  /**
   * The row at which the row with this key is shown, the app's or a new one's ({@link #key}), or
   * -1 when it is not.
   */
  public int rowOf(long key) {
    if (key < 0) {
      for (int i = 0; i < shown.length; i++) {
        if (shown[i].id() == key) {
          return places[i];
        }
      }
      return -1;
    }
    int place = rows.place(key);
    if (place < 0) {
      return -1;
    }
    int row = place;
    for (int p : places) {
      if (p <= row) {
        row++;
      }
    }
    return row;
  }

  /** The message of a failed cell ({@link #error}), or null. */
  public String errorText(int row, int column) {
    Map<Integer, String> failed = errors.get(key(row));
    return failed == null ? null : failed.get(column);
  }

  /**
   * What to write, as UPDATE, INSERT and DELETE: the deletes first, then the updates of rows
   * that are not deleted, then the inserts, each in the order of their keys or of the new rows.
   */
  public List<Change> changes() {
    List<Change> out = new ArrayList<>();
    for (long key : new TreeSet<>(deleted)) {
      out.add(new Change(Kind.DELETE, key, Collections.emptySortedMap()));
    }
    for (long key : new TreeSet<>(edits.keySet())) {
      if (key >= 0 && !deleted.contains(key)) {
        out.add(new Change(Kind.UPDATE, key, values(key)));
      }
    }
    for (New row : added) {
      out.add(new Change(Kind.INSERT, row.id(), values(row.id())));
    }
    return out;
  }

  /**
   * {@link #changes()} of the rows of a {@link GridTable} with the values in the types of its
   * columns: each change with the app's row and, for each changed column, the value before and
   * after, which {@link RowChange#apply} writes into a row.
   *
   * @param table the app's rows, as {@link Rows} counts them
   * @param columns the table's columns as the grid counts them, each with {@link
   *     DataGrid.Column#cells}
   * @throws IllegalArgumentException for a changed column without {@code cells}, or a value its
   *     format does not read, which the grid's editing never sets
   */
  @SuppressWarnings("unchecked")
  public <T> List<RowChange<T>> changes(GridTable<T> table, List<DataGrid.Column> columns) {
    List<RowChange<T>> out = new ArrayList<>();
    for (Change change : changes()) {
      int place = change.key() >= 0 ? rows.place(change.key()) : -1;
      T row = place < 0 ? null : table.row(place);
      SortedMap<Integer, ValueChange<T>> values = new TreeMap<>();
      for (Map.Entry<Integer, String> e : change.values().entrySet()) {
        int column = e.getKey();
        GridTable.Cells<T, ?> cells = column < columns.size()
            ? (GridTable.Cells<T, ?>) columns.get(column).cells()
            : null;
        if (cells == null) {
          throw new IllegalArgumentException("Column " + column + " is not a column of a table");
        }
        Object before = row == null ? null : cells.value(row);
        Format.Parsed<?> after = cells.parse(e.getValue());
        if (!after.valid()) {
          throw new IllegalArgumentException("The value \"" + e.getValue() + "\" of column "
              + column + " is not one of its format: " + after.error());
        }
        values.put(column, new ValueChange<>(column, cells, before, after.value()));
      }
      out.add(new RowChange<>(
          change.kind(), change.key(), row, Collections.unmodifiableSortedMap(values)));
    }
    return out;
  }

  /** How many changes there are to write: the rows updated, inserted and deleted. */
  public int count() {
    int updated = 0;
    for (long key : edits.keySet()) {
      if (key >= 0 && !deleted.contains(key)) {
        updated++;
      }
    }
    return deleted.size() + updated + added.size();
  }

  public boolean isEmpty() {
    return count() == 0;
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  // Changing

  /** Sets a cell, as one step of the history; a value equal to the app's is no change. */
  public void set(int row, int column, String value) {
    set(List.of(new CellValue(row, column, value)));
  }

  /**
   * Sets these cells as one step of the history, in their order, so that the last value given for
   * a cell is its value; cells of deleted rows are left alone.
   */
  public void set(List<CellValue> cells) {
    Map<List<Long>, Put> puts = new LinkedHashMap<>(); // per cell: before the step, after it
    for (CellValue cell : cells) {
      long key = key(cell.row());
      if (deleted.contains(key)) {
        continue;
      }
      Object before = value(key, cell.column());
      Object after = cell.value() == null ? NULL : cell.value();
      if (key >= 0
          && Objects.equals(
              cell.value(), rows.cell(cell.row() - newBefore(cell.row()), cell.column()))) {
        after = null; // back to the app's value
      }
      puts.merge(
          List.of(key, (long) cell.column()),
          new Put(key, cell.column(), before, after),
          (first, last) -> new Put(key, last.column(), first.before(), last.after()));
    }
    List<Op> step = new ArrayList<>();
    for (Put put : puts.values()) {
      if (!Objects.equals(put.before(), put.after())) {
        step.add(put);
      }
    }
    apply(step);
  }

  /**
   * Adds an empty row after the row shown at {@code after}, -1 for a new first row, as one step;
   * it is shown at {@code after + 1}.
   */
  public void insert(int after) {
    layout();
    New row;
    int index;
    if (after < 0) {
      row = new New(nextId--, TOP);
      index = 0;
    } else {
      int at = Arrays.binarySearch(places, after);
      if (at >= 0) { // after a new row: with its anchor, right after it in the list
        row = new New(nextId--, shown[at].anchor());
        index = added.indexOf(shown[at]) + 1;
      } else {
        long anchor = rows.key(after - (-at - 1));
        row = new New(nextId--, anchor);
        index = added.size();
        for (int i = 0; i < added.size(); i++) {
          if (added.get(i).anchor() == anchor) {
            index = i;
            break;
          }
        }
      }
    }
    apply(List.of(new Insert(row, index)));
  }

  /**
   * Marks the rows of these ranges to be deleted, or takes the mark away when all of them have
   * it already, as one step; new rows among them go at once.
   */
  public void delete(List<Range> ranges) {
    layout();
    Set<Integer> shownRows = new LinkedHashSet<>();
    for (Range range : ranges) {
      for (int r = range.top(); r <= range.bottom() && r < count; r++) {
        shownRows.add(r);
      }
    }
    boolean all = true;
    for (int r : shownRows) {
      if (!isNew(r) && !deleted.contains(key(r))) {
        all = false;
      }
    }
    List<Op> step = new ArrayList<>();
    List<Remove> removed = new ArrayList<>();
    for (int r : shownRows) {
      if (isNew(r)) {
        New row = shown[Arrays.binarySearch(places, r)];
        removed.add(new Remove(row, added.indexOf(row), edits.get(row.id())));
      } else if (all || !deleted.contains(key(r))) {
        step.add(new Mark(key(r), !all));
      }
    }
    // Removing a new row moves the ones after it in the list: remove from the last.
    removed.sort((a, b) -> Integer.compare(b.index(), a.index()));
    step.addAll(removed);
    apply(step);
  }

  /** Marks a cell as failed with a message, as when writing it was refused; not in the history. */
  public void error(int row, int column, String message) {
    errors.computeIfAbsent(key(row), k -> new HashMap<>()).put(column, message);
  }

  /**
   * Marks a cell of the row a change of {@link #changes()} writes as failed, as when the database
   * refused that change, also while the row is not shown; not in the history.
   */
  public void error(Change change, int column, String message) {
    errors.computeIfAbsent(change.key(), k -> new HashMap<>()).put(column, message);
  }

  public void undo() {
    List<Op> step = undo.pollLast();
    if (step != null) {
      for (Op op : step.reversed()) {
        op.undo(this);
      }
      redo.addLast(step);
      layout();
    }
  }

  public void redo() {
    List<Op> step = redo.pollLast();
    if (step != null) {
      for (Op op : step) {
        op.redo(this);
      }
      undo.addLast(step);
      layout();
    }
  }

  /**
   * Forgets every change and its history: after the app wrote them, whose rows now hold them, or
   * to revert them all.
   */
  public void clear() {
    edits.clear();
    deleted.clear();
    errors.clear();
    added.clear();
    undo.clear();
    redo.clear();
    layout();
  }

  // Inside

  private void apply(List<Op> step) {
    if (step.isEmpty()) {
      return;
    }
    for (Op op : step) {
      op.redo(this);
    }
    undo.addLast(List.copyOf(step));
    if (undo.size() > HISTORY) {
      undo.pollFirst();
    }
    redo.clear();
    layout();
  }

  private Object value(long key, int column) {
    Map<Integer, Object> values = edits.get(key);
    return values == null ? null : values.get(column);
  }

  private void put(long key, int column, Object value) {
    if (value == null) {
      Map<Integer, Object> values = edits.get(key);
      if (values != null) {
        values.remove(column);
        if (values.isEmpty()) {
          edits.remove(key);
        }
      }
    } else {
      edits.computeIfAbsent(key, k -> new HashMap<>()).put(column, value);
    }
  }

  private SortedMap<Integer, String> values(long key) {
    SortedMap<Integer, String> out = new TreeMap<>();
    Map<Integer, Object> values = edits.get(key);
    if (values != null) {
      values.forEach((column, value) -> out.put(column, value == NULL ? null : (String) value));
    }
    return Collections.unmodifiableSortedMap(out);
  }

  private void clearError(long key, int column) {
    Map<Integer, String> failed = errors.get(key);
    if (failed != null) {
      failed.remove(column);
      if (failed.isEmpty()) {
        errors.remove(key);
      }
    }
  }

  /** How many new rows are shown before {@code row}. */
  private int newBefore(int row) {
    int at = Arrays.binarySearch(places, row);
    return at >= 0 ? at : -at - 1;
  }

  /**
   * Places the new rows: after their anchors where those are shown, at the end where not, those
   * of one anchor in the order of the list.
   */
  private void layout() {
    int base = rows.count();
    int n = added.size();
    Integer[] order = new Integer[n];
    int[] after = new int[n]; // the place of the anchor, -1 for the top, base - 1 for the end
    for (int i = 0; i < n; i++) {
      order[i] = i;
      long anchor = added.get(i).anchor();
      int place = anchor == TOP ? -1 : rows.place(anchor);
      after[i] = anchor != TOP && (place < 0 || place >= base) ? base - 1 : place;
    }
    Arrays.sort(order, (a, b) -> Integer.compare(after[a], after[b])); // stable
    places = new int[n];
    shown = new New[n];
    for (int k = 0; k < n; k++) {
      places[k] = after[order[k]] + 1 + k;
      shown[k] = added.get(order[k]);
    }
    count = base + n;
  }

  /** A change of the state, which a step of the history does and undoes. */
  private sealed interface Op {
    void redo(GridChanges c);

    void undo(GridChanges c);
  }

  private record Put(long key, int column, Object before, Object after) implements Op {
    // An error stays until the cell's value changes, as undoing or redoing it does.
    public void redo(GridChanges c) {
      c.put(key, column, after);
      c.clearError(key, column);
    }

    public void undo(GridChanges c) {
      c.put(key, column, before);
      c.clearError(key, column);
    }
  }

  private record Insert(New row, int index) implements Op {
    public void redo(GridChanges c) {
      c.added.add(index, row);
    }

    public void undo(GridChanges c) {
      c.added.remove(row);
      c.edits.remove(row.id());
      c.errors.remove(row.id());
    }
  }

  private record Remove(New row, int index, Map<Integer, Object> values) implements Op {
    Remove {
      values = values == null ? null : Map.copyOf(values);
    }

    public void redo(GridChanges c) {
      c.added.remove(row);
      c.edits.remove(row.id());
      c.errors.remove(row.id());
    }

    public void undo(GridChanges c) {
      c.added.add(index, row);
      if (values != null) {
        c.edits.put(row.id(), new HashMap<>(values));
      }
    }
  }

  private record Mark(long key, boolean on) implements Op {
    public void redo(GridChanges c) {
      if (on) {
        c.deleted.add(key);
      } else {
        c.deleted.remove(key);
      }
    }

    public void undo(GridChanges c) {
      if (on) {
        c.deleted.remove(key);
      } else {
        c.deleted.add(key);
      }
    }
  }
}
