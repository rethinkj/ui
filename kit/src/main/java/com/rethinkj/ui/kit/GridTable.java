package com.rethinkj.ui.kit;

import com.rethinkj.ui.kit.DataGrid.CellValue;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Editing;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * The rows of a {@link DataGrid} as objects of the app's, records or POJOs, and its columns as
 * typed values of them: each column reads its value from a row through a getter and writes it
 * back through a record's wither or a POJO's setter, and a {@link Format} turns the value into
 * the cell's text and the editor's text back into a value. The grid still shows text and the app
 * still owns its rows; the table turns between them.
 *
 * <pre>{@code
 * record Order(long id, BigDecimal amount, LocalDate due, Status status) {
 *   Order withAmount(BigDecimal amount) { return new Order(id, amount, due, status); }
 *   ...
 * }
 *
 * GridTable<Order> table = DataGrid.table(i -> s.orders.get(i));
 * List<Column> columns = List.of(
 *     table.column("id", 72, Format.LONG, Order::id),
 *     table.column("amount", 100, Format.DECIMAL, Order::amount, Order::withAmount),
 *     table.column("due", 110, Format.DATE.optional(), Order::due, Order::withDue),
 *     table.column("status", 100, STATUS, Order::status, Order::withStatus));
 * dataGrid(t, columns, s.orders.size(), DataGrid.options().select(s.selection)
 *     .onCommit(table, (row, order) -> { s.orders.set(row, order); return null; }))
 * }</pre>
 *
 * <p>The rows stay lazy: {@code rows} is asked for the rows the grid shows, while a frame runs,
 * and returns null for a row that is not loaded, whose cells show NULL; with {@link
 * DataGrid.Options#loading loading(table::loaded, ...)} they show the loading cells instead. A
 * column's alignment, font, header icon and editor come from its format's {@link Format#type};
 * an {@link Format#optional} format makes it {@link Column#nullable}, and a {@link Format#choice}
 * gives it its choices. The column is a {@link Column} like any other, so the view goes on with
 * {@code .typeName}, {@code .icon}, {@code .cellIcon} or {@code .sortable}, and its {@link
 * Column#cells} is the typed part.
 *
 * <p>Editing hands the app the row with the new value: a record's wither makes a new row, a POJO's
 * setter changes the row it has, and either way the handler of {@link
 * DataGrid.Options#onCommit(GridTable, RowCommit)} is told the row and stores it, or refuses it
 * with a message, which the grid shows under the editor (a setter's value is then set back). The
 * grid checks the text with the column's format first, so a value the format refuses never
 * reaches the handler. The grid sorts and filters nothing: {@link #comparator} and {@link #filter}
 * give the app a {@code Comparator} and a {@code Predicate} of its rows from the grid's sort keys
 * and filters, by the typed values. {@link GridChanges#changes(GridTable, List)} gives the
 * changes a {@link GridChanges} collects as typed values.
 *
 * <p>A cell asks {@code rows} for its row, so the rows should be cheap to get, such as from a
 * list: a row made anew on each call is made once per cell. It needs no native libraries.
 *
 * @param <T> the type of the rows
 */
public final class GridTable<T> {

  /**
   * Told a row that an edit changed: a new record from a wither, or the POJO its setter changed.
   */
  @FunctionalInterface
  public interface RowCommit<T> {

    /**
     * @param row the row's index, from 0
     * @param value the row with the new value
     * @return null when the app stored it, else what is wrong with it, which the grid shows and
     *     which sets a setter's value back
     */
    String commit(int row, T value);
  }

  /**
   * The typed part of a column of a {@link GridTable}: how it reads a value from a row, writes it
   * text and back, and writes it into a row.
   *
   * @param <T> the type of the rows
   * @param <V> the type of the values
   */
  public static final class Cells<T, V> {
    private final GridTable<T> table;
    private final Format<V> format;
    private final Function<? super T, ? extends V> get;
    private final BiFunction<? super T, ? super V, ? extends T> with;
    private final BiConsumer<? super T, ? super V> set;

    private Cells(
        GridTable<T> table,
        Format<V> format,
        Function<? super T, ? extends V> get,
        BiFunction<? super T, ? super V, ? extends T> with,
        BiConsumer<? super T, ? super V> set) {
      this.table = table;
      this.format = Objects.requireNonNull(format, "format");
      this.get = Objects.requireNonNull(get, "get");
      this.with = with;
      this.set = set;
    }

    public Format<V> format() {
      return format;
    }

    /** The table whose rows the column reads. */
    public GridTable<T> table() {
      return table;
    }

    /** The value in a row; null for NULL. */
    public V value(T row) {
      return get.apply(row);
    }

    /** The value in the row with this index; null for NULL and for a row not loaded. */
    public V value(int row) {
      T value = table.rows.apply(row);
      return value == null ? null : get.apply(value);
    }

    /** Whether the column writes values, through a wither or a setter. */
    public boolean writes() {
      return with != null || set != null;
    }

    /**
     * Writes a value into a row: a new row from a wither, or {@code row} itself, changed by a
     * setter.
     *
     * @throws IllegalStateException when the column has neither
     */
    public T write(T row, V value) {
      if (with != null) {
        return with.apply(row, value);
      }
      if (set == null) {
        throw new IllegalStateException("The column writes no values: give it a wither or setter");
      }
      set.accept(row, value);
      return row;
    }

    /**
     * Rows by this column's value: the natural order of values that are {@link Comparable}, such
     * as numbers, dates and enums, else their texts; NULL first.
     */
    public Comparator<T> comparator() {
      return (a, b) -> compare(get.apply(a), get.apply(b));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compare(V a, V b) {
      if (a == null || b == null) {
        return a == null ? (b == null ? 0 : -1) : 1;
      }
      if (a instanceof Comparable c && a.getClass() == b.getClass()) {
        return c.compareTo(b);
      }
      return format.text(a).compareTo(format.text(b));
    }

    /** The cell's text: the value's in the format, null for NULL and for a row not loaded. */
    String text(int row) {
      V value = value(row);
      return value == null ? null : format.text(value);
    }

    /** The value of a cell's text, null for NULL, or what is wrong with the text. */
    Format.Parsed<V> parse(String text) {
      return text == null ? Format.Parsed.of(null) : format.parseText(text);
    }

    @SuppressWarnings("unchecked")
    T writeParsed(T row, Object value) {
      return write(row, (V) value);
    }
  }

  private final IntFunction<T> rows;

  private GridTable(IntFunction<T> rows) {
    this.rows = Objects.requireNonNull(rows, "rows");
  }

  /** {@link DataGrid#table}. */
  static <T> GridTable<T> of(IntFunction<T> rows) {
    return new GridTable<>(rows);
  }

  /** The row with this index, or null when it is not loaded. */
  public T row(int index) {
    return rows.apply(index);
  }

  /** Whether the row with this index is loaded: for {@link DataGrid.Options#loading}. */
  public boolean loaded(int index) {
    return rows.apply(index) != null;
  }

  // Columns

  /** A column that shows a value of each row and edits nothing. */
  public <V> Column column(
      String title, float width, Format<V> format, Function<? super T, ? extends V> get) {
    return column(title, width, new Cells<>(this, format, get, null, null));
  }

  /**
   * A column of a value that a record's wither writes, {@code Order::withAmount}: an edit hands
   * the new row to the handler of {@link DataGrid.Options#onCommit(GridTable, RowCommit)}.
   */
  public <V> Column column(
      String title,
      float width,
      Format<V> format,
      Function<? super T, ? extends V> get,
      BiFunction<? super T, ? super V, ? extends T> with) {
    return column(
        title, width, new Cells<>(this, format, get, Objects.requireNonNull(with, "with"), null));
  }

  /**
   * A column of a value that a POJO's setter writes, {@code Order::setAmount}: an edit sets the
   * value and hands the row to the handler of {@link DataGrid.Options#onCommit(GridTable,
   * RowCommit)}. Give a method reference; for a lambda, Java cannot tell this from a wither.
   */
  public <V> Column column(
      String title,
      float width,
      Format<V> format,
      Function<? super T, ? extends V> get,
      BiConsumer<? super T, ? super V> set) {
    return column(
        title, width, new Cells<>(this, format, get, null, Objects.requireNonNull(set, "set")));
  }

  /**
   * A column of a {@link Part} of the rows, {@code Order.AMOUNT}, which writes through its wither
   * or setter: the same constant a form's {@link Editing} uses, and no overloads to tell apart.
   */
  public <V> Column column(String title, float width, Format<V> format, Part<T, V> part) {
    return column(title, width, new Cells<>(this, format, part::get, part::write, null));
  }

  private static Column column(String title, float width, Cells<?, ?> cells) {
    Format<?> format = cells.format;
    DataType type = format.type();
    Editing editing = new Editing(
        cells.writes() ? row -> true : null, format.isOptional(), format.choices(), null);
    return new Column(
        title,
        width,
        type.align(),
        type.mono(),
        true,
        cells::text,
        type.symbol(),
        Icon.Variant.MUTED,
        null,
        null,
        type,
        editing,
        cells);
  }

  // Editing

  /**
   * The grid's handler of an edit for these columns: the text the grid checked becomes the
   * column's value, which the column writes into the row, which {@code onRow} is told.
   */
  DataGrid.Commit commit(List<Column> columns, RowCommit<T> onRow) {
    return (row, column, text) -> {
      Cells<T, ?> cells = cells(columns, column);
      if (cells == null) {
        return "Not a column of this table";
      }
      Format.Parsed<?> parsed = cells.parse(text);
      if (!parsed.valid()) {
        return parsed.error();
      }
      Map<Cells<T, ?>, Object> values = new LinkedHashMap<>();
      values.put(cells, parsed.value());
      return write(row, values, onRow);
    };
  }

  /**
   * The grid's handler of pasted and cleared cells for these columns: the cells of each row
   * written into it at once, and {@code onRow} told each row; a refusal is not shown, and a value
   * the column's format does not read is left out. Each row is taken before {@code onRow} is told
   * the first: a handler that sorts, filters or refreshes the rows moves the others, which are
   * then found again by their objects among the rows there are now (up to {@code count}, or to the
   * first index that {@code rows} throws {@code IndexOutOfBoundsException} for), and left out where
   * they are gone.
   */
  Consumer<List<CellValue>> paste(List<Column> columns, int count, RowCommit<T> onRow) {
    return values -> {
      Map<Integer, Map<Cells<T, ?>, Object>> byRow = new LinkedHashMap<>();
      for (CellValue v : values) {
        Cells<T, ?> cells = cells(columns, v.column());
        Format.Parsed<?> parsed = cells == null ? null : cells.parse(v.value());
        if (parsed != null && parsed.valid()) {
          byRow.computeIfAbsent(v.row(), r -> new LinkedHashMap<>()).put(cells, parsed.value());
        }
      }
      Map<Integer, T> targets = new LinkedHashMap<>();
      byRow.keySet().forEach(index -> targets.put(index, rows.apply(index)));
      targets.forEach((index, row) -> {
        int at = row == null ? -1 : placeOf(row, index, count);
        if (at >= 0) {
          write(at, row, byRow.get(index), onRow);
        }
      });
    };
  }

  /** Where this row object is now: at {@code was} unless the app moved it; -1 when it is gone. */
  private int placeOf(T row, int was, int count) {
    if (was < count && rowOrEnd(was) == row) {
      return was;
    }
    for (int i = 0; i < count; i++) {
      T now = rowOrEnd(i);
      if (now == row) {
        return i;
      }
      if (now == END) {
        break;
      }
    }
    return -1;
  }

  private static final Object END = new Object();

  /** The row with this index, or {@link #END} beyond the rows a handler left. */
  @SuppressWarnings("unchecked")
  private T rowOrEnd(int index) {
    try {
      return rows.apply(index);
    } catch (IndexOutOfBoundsException e) { // a list that a handler made shorter
      return (T) END;
    }
  }

  /** Writes values into a row and tells the app; a refusal sets a setter's values back. */
  private String write(int index, Map<Cells<T, ?>, Object> values, RowCommit<T> onRow) {
    T row = rows.apply(index);
    return row == null ? "The row is not loaded" : write(index, row, values, onRow);
  }

  private String write(int index, T row, Map<Cells<T, ?>, Object> values, RowCommit<T> onRow) {
    Map<Cells<T, ?>, Object> before = new LinkedHashMap<>();
    T next = row;
    for (Map.Entry<Cells<T, ?>, Object> e : values.entrySet()) {
      before.put(e.getKey(), e.getKey().value(next));
      next = e.getKey().writeParsed(next, e.getValue());
    }
    String error = onRow.commit(index, next);
    if (error != null && next == row) { // a POJO the setters changed: as it was
      before.forEach((cells, value) -> cells.writeParsed(row, value));
    }
    return error;
  }

  @SuppressWarnings("unchecked")
  private Cells<T, ?> cells(List<Column> columns, int column) {
    Cells<?, ?> cells =
        column < 0 || column >= columns.size() ? null : columns.get(column).cells();
    return cells == null || !cells.writes() ? null : (Cells<T, ?>) cells;
  }

  // Sorting and filtering, which the app does

  /**
   * The order of the grid's sort keys over the app's rows, by the typed values of the columns
   * ({@link Cells#comparator}), the first key first. Columns without {@link Column#cells} are
   * left out.
   *
   * @param columns the columns the grid was given, which the keys count in
   */
  public Comparator<T> comparator(List<Column> columns, List<SortKey> keys) {
    Comparator<T> order = (a, b) -> 0;
    for (SortKey key : keys) {
      Cells<T, ?> cells = typed(columns, key.column());
      if (cells != null) {
        Comparator<T> by = cells.comparator();
        order = order.thenComparing(key.descending() ? by.reversed() : by);
      }
    }
    return order;
  }

  /**
   * The rows that pass all of the grid's filters, by the typed values of the columns ({@link
   * Filter#matches(Object, Format)}). Filters of columns without {@link Column#cells} pass all.
   *
   * @param columns the columns the grid was given, which the filters count in
   */
  public Predicate<T> filter(List<Column> columns, List<Filter> filters) {
    List<Predicate<T>> tests = new ArrayList<>();
    for (Filter f : filters) {
      Cells<T, ?> cells = typed(columns, f.column());
      if (cells != null) {
        tests.add(row -> matches(f, cells, row));
      }
    }
    return row -> {
      for (Predicate<T> test : tests) {
        if (!test.test(row)) {
          return false;
        }
      }
      return true;
    };
  }

  private static <T, V> boolean matches(Filter f, Cells<T, V> cells, T row) {
    return f.matches(cells.value(row), cells.format());
  }

  @SuppressWarnings("unchecked")
  private Cells<T, ?> typed(List<Column> columns, int column) {
    Cells<?, ?> cells =
        column < 0 || column >= columns.size() ? null : columns.get(column).cells();
    return (Cells<T, ?>) cells;
  }
}
