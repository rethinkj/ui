package com.rethinkj.ui.examples;

import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.GridChanges.Change;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * The rows of a table in a tab of the {@link Workbench}: the made-up rows its statement asked
 * for, with what the made-up server has written to the table, as the tab's column filters keep
 * them. The rows are told apart by their index among the made-up rows, and those a commit
 * inserted by {@link #ADDED} and their place after them.
 *
 * <p>The filters find their rows without looking at each of a million: the cells of the columns
 * they may be set on repeat after {@link #PERIOD} rows, so the rows of one period that a filter
 * keeps give all the others by arithmetic, and the few rows written to are looked at one by one.
 */
final class TableRows implements GridChanges.Rows {

  /** The rows after which the cells of the filterable columns repeat. */
  static final int PERIOD = 6000;
  /** The keys of rows that a commit inserted: this plus their index among them. */
  static final long ADDED = 1L << 40;
  /** The columns whose cells repeat after {@link #PERIOD} rows, by their index. */
  static final Set<Integer> FILTERABLE = Set.of(2, 3, 4, 5, 6); // status, amount, paid, email, note

  /** What the made-up server has written to a table, beside its made-up rows. */
  static final class Stored {
    final Map<Long, Map<Integer, String>> written = new HashMap<>(); // a null value is NULL
    final List<Map<Integer, String>> added = new ArrayList<>();
    final Set<Long> removed = new HashSet<>();
    long nextId = 2_000_001; // the ids the server gives the rows inserted

    /** A cell of the row with this key, null for NULL. */
    String value(long key, int column) {
      Map<Integer, String> values = key >= ADDED ? added.get((int) (key - ADDED))
          : written.get(key);
      if (values != null && values.containsKey(column)) {
        return values.get(column);
      }
      return key >= ADDED ? null : Workbench.cell(Workbench.FIELDS.get(column).name(), (int) key);
    }

    /** Writes the changes the server has taken. */
    void apply(List<Change> changes) {
      for (Change change : changes) {
        switch (change.kind()) {
          case UPDATE -> (change.key() >= ADDED ? added.get((int) (change.key() - ADDED))
              : written.computeIfAbsent(change.key(), k -> new HashMap<>()))
              .putAll(change.values());
          case INSERT -> {
            Map<Integer, String> values = new HashMap<>(change.values());
            values.put(0, String.valueOf(nextId++));
            added.add(values);
          }
          case DELETE -> removed.add(change.key());
        }
      }
    }
  }

  /** A change the server refused, at a column, and why. */
  record Refusal(Change change, int column, String message) {}

  final Stored stored;
  int base; // the made-up rows the statement asked for
  List<Filter> filters = List.of();
  // Worked out by refresh(): the rows of a period the filters keep, ascending; the rows written
  // to that the filters judge otherwise than their made-up values, ascending, with whether they
  // are kept now and how many kept made-up rows lie before them; the inserted rows kept.
  private int[] matching = new int[0];
  private long[] special = new long[0];
  private boolean[] kept = new boolean[0];
  private long[] before = new long[0];
  private long[] addedShown = new long[0];
  private int madeUp; // the made-up rows shown
  private int count;

  TableRows(Stored stored) {
    this.stored = stored;
  }

  /** Works out the rows shown again, after the rows, the filters or what was written changed. */
  void refresh() {
    int[] found = new int[PERIOD];
    int m = 0;
    for (int r = 0; r < PERIOD; r++) {
      if (passes(r, true)) {
        found[m++] = r;
      }
    }
    matching = Arrays.copyOf(found, m);
    Set<Long> touched = new HashSet<>(stored.removed);
    touched.addAll(stored.written.keySet());
    long[] keys = touched.stream().mapToLong(Long::longValue).filter(k -> k < base).sorted()
        .toArray();
    List<long[]> differ = new ArrayList<>(); // key, kept now, made-up rows before it
    for (long k : keys) {
      boolean was = m > 0 && Arrays.binarySearch(matching, (int) (k % PERIOD)) >= 0;
      boolean is = !stored.removed.contains(k) && passes(k, false);
      if (was != is) {
        differ.add(new long[] {k, is ? 1 : 0, position(k)});
      }
    }
    special = differ.stream().mapToLong(d -> d[0]).toArray();
    kept = new boolean[special.length];
    before = differ.stream().mapToLong(d -> d[2]).toArray();
    int shown = position(base);
    for (int i = 0; i < special.length; i++) {
      kept[i] = differ.get(i)[1] == 1;
      shown += kept[i] ? 1 : -1;
    }
    madeUp = shown;
    List<Long> added = new ArrayList<>();
    for (int i = 0; i < stored.added.size(); i++) {
      long key = ADDED + i;
      if (!stored.removed.contains(key) && passes(key, false)) {
        added.add(key);
      }
    }
    addedShown = added.stream().mapToLong(Long::longValue).toArray();
    count = madeUp + addedShown.length;
  }

  /** Whether the filters keep a row, by its made-up values or as written. */
  private boolean passes(long key, boolean madeUpOnly) {
    for (Filter f : filters) {
      String value = madeUpOnly
          ? Workbench.cell(Workbench.FIELDS.get(f.column()).name(), (int) key)
          : stored.value(key, f.column());
      if (!f.matches(value)) {
        return false;
      }
    }
    return true;
  }

  /** How many made-up rows the filters keep before the row {@code key}, by their values alone. */
  private int position(long key) {
    int m = matching.length;
    if (m == 0) {
      return 0;
    }
    int at = Arrays.binarySearch(matching, (int) (key % PERIOD));
    return (int) (key / PERIOD) * m + (at < 0 ? -at - 1 : at);
  }

  /** The made-up row kept at this place among them by their values alone. */
  private long madeUpAt(long place) {
    int m = matching.length;
    return place / m * PERIOD + matching[(int) (place % m)];
  }

  @Override
  public int count() {
    return count;
  }

  @Override
  public long key(int row) {
    if (row >= madeUp) {
      return addedShown[row - madeUp];
    }
    int out = 0; // rows the filters judge otherwise, before the row: left out, then put in
    int in = 0;
    for (int i = 0; i < special.length; i++) {
      long shown = before[i] - out + in;
      if (kept[i]) {
        if (shown == row) {
          return special[i];
        }
        if (shown > row) {
          break;
        }
        in++;
      } else {
        if (shown > row) {
          break;
        }
        out++;
      }
    }
    return madeUpAt(row + out - in);
  }

  @Override
  public int place(long key) {
    if (key >= ADDED) {
      for (int i = 0; i < addedShown.length; i++) {
        if (addedShown[i] == key) {
          return madeUp + i;
        }
      }
      return -1;
    }
    if (key < 0 || key >= base) {
      return -1;
    }
    int at = Arrays.binarySearch(special, key);
    boolean shown = at >= 0 ? kept[at]
        : matching.length > 0 && Arrays.binarySearch(matching, (int) (key % PERIOD)) >= 0;
    if (!shown) {
      return -1;
    }
    int place = position(key);
    for (int i = 0; i < special.length && special[i] < key; i++) {
      place += kept[i] ? 1 : -1;
    }
    return place;
  }

  @Override
  public String cell(int row, int column) {
    return stored.value(key(row), column);
  }

  // The made-up server, on the work's threads: it gets the changes and the values they replace.

  /**
   * Writes changes to a table, or refuses them all where one breaks a rule of the made-up
   * server: an amount below zero, or an order whose status was paid going back to pending.
   *
   * @param was the values the changes replace, by the row's key and column
   */
  static List<Refusal> write(String path, List<Change> changes,
      Map<Long, Map<Integer, String>> was, Duration latency) throws InterruptedException {
    Thread.sleep(path.startsWith("staging") ? latency.multipliedBy(3) : latency);
    List<Refusal> refused = new ArrayList<>();
    for (Change change : changes) {
      String amount = change.values().get(3);
      if (amount != null && new BigDecimal(amount).signum() < 0) {
        refused.add(new Refusal(change, 3, "amount must not be negative"));
      }
      String status = change.values().get(2);
      Map<Integer, String> old = was.getOrDefault(change.key(), Map.of());
      if ("pending".equals(status) && "paid".equals(old.get(2))) {
        refused.add(new Refusal(change, 2, "a paid order cannot go back to pending"));
      }
    }
    return refused;
  }

  /**
   * The statements that write the changes to {@code table} (database, schema and name, with dots),
   * as the server would run them; {@code id} gives a row's id by its key.
   */
  static String sql(String table, List<Change> changes, LongFunction<String> id) {
    StringBuilder out = new StringBuilder();
    for (Change change : changes) {
      if (!out.isEmpty()) {
        out.append('\n');
      }
      switch (change.kind()) {
        case DELETE -> out.append("DELETE FROM ").append(table).append(" WHERE id = ")
            .append(id.apply(change.key())).append(';');
        case UPDATE -> {
          out.append("UPDATE ").append(table).append("\n  SET ");
          String sep = "";
          for (Map.Entry<Integer, String> e : change.values().entrySet()) {
            out.append(sep).append(Workbench.FIELDS.get(e.getKey()).name()).append(" = ")
                .append(literal(e.getKey(), e.getValue()));
            sep = ", ";
          }
          out.append("\n  WHERE id = ").append(id.apply(change.key())).append(';');
        }
        case INSERT -> {
          if (change.values().isEmpty()) {
            out.append("INSERT INTO ").append(table).append(" DEFAULT VALUES;");
            break;
          }
          List<String> names = new ArrayList<>();
          List<String> values = new ArrayList<>();
          change.values().forEach((c, v) -> {
            names.add(Workbench.FIELDS.get(c).name());
            values.add(literal(c, v));
          });
          out.append("INSERT INTO ").append(table).append(" (").append(String.join(", ", names))
              .append(")\n  VALUES (").append(String.join(", ", values)).append(");");
        }
      }
    }
    return out.toString();
  }

  /** A value as SQL writes it: NULL, a number or a boolean as it is, else quoted. */
  static String literal(int column, String value) {
    if (value == null) {
      return "NULL";
    }
    DataType kind = Workbench.FIELDS.get(column).kind();
    return kind == DataType.INTEGER || kind == DataType.DECIMAL || kind == DataType.BOOLEAN
        ? value : "'" + value.replace("'", "''") + "'";
  }
}
