package com.rethinkj.ui;

import java.util.Objects;
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
 */
public final class RowHeights {

  private final IntToDoubleFunction estimate;
  private int count;
  private double[] heights = new double[0];
  private double[] tree = new double[1]; // tree[i] holds a range of heights ending at row i - 1

  /**
   * @param estimate the height of the row with this index, in logical pixels above 0, until it is
   *     built: from the length of its text, say
   */
  public RowHeights(IntToDoubleFunction estimate) {
    this.estimate = Objects.requireNonNull(estimate, "estimate");
  }

  /** As many rows as the list has; keeps the heights of the rows that stay. */
  void count(int rows) {
    if (rows == count) {
      return;
    }
    double[] kept = new double[rows];
    for (int i = 0; i < rows; i++) {
      kept[i] = i < count ? heights[i] : Math.max(estimate.applyAsDouble(i), 1);
    }
    heights = kept;
    count = rows;
    tree = new double[rows + 1];
    for (int i = 1; i <= rows; i++) { // the tree in one pass
      tree[i] += heights[i - 1];
      int parent = i + (i & -i);
      if (parent <= rows) {
        tree[parent] += tree[i];
      }
    }
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
    return Math.min(row, count);
  }
}
