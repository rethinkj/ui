package com.rethinkj.ui;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;

/**
 * The heights of the rows of a {@link El#rows(int, RowHeights, java.util.function.IntFunction)
 * virtual list whose rows are as high as their content}: estimated until a row is built and laid
 * out, then as measured, kept from one build of the view to the next. The app keeps one per list
 * in its state, as it keeps the list's data, and passes it on every build.
 *
 * <p>The heights are in a Fenwick tree, so that where a row starts, which row is at a height and
 * how high all rows are together cost a logarithm of the number of rows; measuring a row costs as
 * much. A height measured at one width stays until the row is built again, at whatever width.
 *
 * <p>Without a key function, heights are kept by the row's index, and a change of the count keeps
 * those of the first rows: rows inserted, removed or moved anywhere but at the end leave the
 * heights at the indexes where they were, so that rows not built since have another row's height
 * and the offsets above the view are off by the difference until those rows are built again. Pass a
 * key function ({@link #RowHeights(IntToDoubleFunction, IntFunction)}) when rows can be inserted,
 * removed or reordered before the view: each measured height stays with its row's key, and the
 * list's scroll offset moves on the frame of the change so the row in view stays in place.
 */
public final class RowHeights {

  private final IntToDoubleFunction estimate;
  private final IntFunction<?> key;
  private int count;
  private double[] heights = new double[0];
  private double[] tree = new double[1]; // tree[i] holds a range of heights ending at row i - 1
  private Object[] keys = new Object[0]; // only used when key != null
  private int version;
  private int previousCount;
  private double[] previousTree;
  private int[] movedTo; // movedTo[oldRow] = newRow, or -1 when oldRow was removed

  /**
   * @param estimate the height of the row with this index, in logical pixels above 0, until it is
   *     built: from the length of its text, say
   */
  public RowHeights(IntToDoubleFunction estimate) {
    this.estimate = Objects.requireNonNull(estimate, "estimate");
    this.key = null;
  }

  /**
   * Keeps measured heights by each row's key rather than its index, and adjusts the list's scroll
   * offset when rows are inserted, removed or reordered so what is in view stays in place.
   *
   * @param estimate the height of the row with this index, in logical pixels above 0, until it is
   *     built: from the length of its text, say
   * @param key a unique, stable key identifying the row with this index, such as a message id;
   *     must not return {@code null}
   */
  public RowHeights(IntToDoubleFunction estimate, IntFunction<?> key) {
    this.estimate = Objects.requireNonNull(estimate, "estimate");
    this.key = Objects.requireNonNull(key, "key");
  }

  /** As many rows as the list has; keeps the heights of the rows that stay. */
  void count(int rows) {
    if (key == null) {
      if (rows == count) {
        return;
      }
      double[] kept = new double[rows];
      for (int i = 0; i < rows; i++) {
        kept[i] = i < count ? heights[i] : Math.max(estimate.applyAsDouble(i), 1);
      }
      heights = kept;
      count = rows;
      tree = buildTree(heights, rows);
      return;
    }
    boolean same = rows == count;
    for (int i = 0; same && i < rows; i++) { // without allocating on the builds that change nothing
      same = keys[i].equals(key.apply(i));
    }
    if (same) {
      return;
    }
    Object[] nextKeys = new Object[rows];
    for (int i = 0; i < rows; i++) {
      nextKeys[i] = key.apply(i);
      if (nextKeys[i] == null) {
        throw new IllegalStateException(
            "The key function of RowHeights returned null for row " + i);
      }
    }
    Map<Object, Integer> oldIndex = HashMap.newHashMap(count);
    for (int i = 0; i < count; i++) {
      oldIndex.putIfAbsent(keys[i], i);
    }
    double[] kept = new double[rows];
    int[] nextMovedTo = new int[count];
    Arrays.fill(nextMovedTo, -1);
    for (int i = 0; i < rows; i++) {
      Integer old = oldIndex.get(nextKeys[i]);
      if (old != null) {
        kept[i] = heights[old];
        if (nextMovedTo[old] < 0) {
          nextMovedTo[old] = i;
        }
      } else {
        kept[i] = Math.max(estimate.applyAsDouble(i), 1);
      }
    }
    previousCount = count;
    previousTree = tree;
    movedTo = nextMovedTo;
    heights = kept;
    keys = nextKeys;
    count = rows;
    tree = buildTree(heights, rows);
    version++;
  }

  private static double[] buildTree(double[] heights, int rows) {
    double[] built = new double[rows + 1];
    for (int i = 1; i <= rows; i++) { // the tree in one pass
      built[i] += heights[i - 1];
      int parent = i + (i & -i);
      if (parent <= rows) {
        built[parent] += built[i];
      }
    }
    return built;
  }

  /** Incremented each time {@link #count} updates the keyed rows, so a scroll offset moves once. */
  int version() {
    return version;
  }

  /**
   * Translates a scroll offset from before the last keyed change to the new rows so the row at
   * {@code oldY} stays in place, or the next surviving row starts at the top if it was removed.
   */
  double moved(double oldY) {
    if (previousTree == null || previousCount == 0 || count == 0) {
      return oldY;
    }
    int oldRow = at(previousTree, previousCount, oldY);
    if (oldRow >= previousCount) {
      return oldY;
    }
    int target = movedTo[oldRow];
    if (target >= 0) {
      double within = Math.max(0, oldY - top(previousTree, previousCount, oldRow));
      return top(target) + within;
    }
    for (int i = oldRow + 1; i < previousCount; i++) {
      if (movedTo[i] >= 0) {
        return top(movedTo[i]);
      }
    }
    return oldY;
  }

  /** The key of a row, or null without a key function or outside the rows. */
  Object key(int row) {
    return key == null || row < 0 || row >= count ? null : keys[row];
  }

  /** The index of the row with this key, or -1. */
  int indexOf(Object rowKey) {
    for (int i = 0; key != null && i < count; i++) {
      if (keys[i].equals(rowKey)) {
        return i;
      }
    }
    return -1;
  }

  /** The number of rows. */
  public int count() {
    return count;
  }

  /** The height of a row: as measured, or as estimated before. */
  public double height(int row) {
    return heights[row];
  }

  /**
   * Sets a row's height as layout measured it.
   *
   * @return how much higher it is than before, negative when lower
   */
  double measured(int row, double height) {
    double delta = height - heights[row];
    if (delta != 0) {
      heights[row] = height;
      for (int i = row + 1; i <= count; i += i & -i) {
        tree[i] += delta;
      }
    }
    return delta;
  }

  /** Where a row starts below the first: the heights of the rows before it together. */
  public double top(int row) {
    return top(tree, count, row);
  }

  private static double top(double[] tree, int count, int row) {
    double sum = 0;
    for (int i = Math.clamp(row, 0, count); i > 0; i -= i & -i) {
      sum += tree[i];
    }
    return sum;
  }

  /** The heights of all rows together. */
  public double total() {
    return top(count);
  }

  /**
   * The row at this distance below the start of the first: the last one that starts at it or
   * above it, from 0 to the number of rows, which is past the last.
   */
  public int at(double y) {
    return at(tree, count, y);
  }

  private static int at(double[] tree, int count, double y) {
    if (y < 0) {
      return 0;
    }
    int row = 0;
    double left = y;
    for (int step = Integer.highestOneBit(Math.max(count, 1)); step > 0; step >>= 1) {
      int next = row + step;
      if (next <= count && tree[next] <= left) {
        row = next;
        left -= tree[next];
      }
    }
    // The descent subtracts where top adds: at a row's top, rounding can land on its neighbor.
    if (row > 0 && top(tree, count, row) > y) {
      row--;
    } else if (row < count && top(tree, count, row + 1) <= y) {
      row++;
    }
    return Math.min(row, count);
  }
}
