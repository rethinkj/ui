package com.rethinkj.ui;

/**
 * A character position inside an {@link El#selectionScope}: the row index of the scope's virtual
 * list ({@link #BEFORE} for a field before the list or in a scope without one, {@link #AFTER} for
 * a field after the list), the ordinal of the read-only unmasked field within that row (or before
 * or after the list) in tree order, and the character offset in the field's normalized text.
 */
record ScopePosition(int row, int field, int offset) implements Comparable<ScopePosition> {

  /** Row sentinel for fields before the scope's virtual list (or when there is no list). */
  static final int BEFORE = -1;

  /** Row sentinel for fields after the scope's virtual list. */
  static final int AFTER = Integer.MAX_VALUE;

  /** Whether {@code row} is an index into the scope's virtual list rather than before or after it. */
  static boolean inRows(int row) {
    return row >= 0 && row != AFTER;
  }

  /** Whether this position is inside a row of the scope's virtual list. */
  boolean inRows() {
    return inRows(row);
  }

  @Override
  public int compareTo(ScopePosition other) {
    int cmp = Integer.compare(row, other.row);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Integer.compare(field, other.field);
    return cmp != 0 ? cmp : Integer.compare(offset, other.offset);
  }

  /** Compares a field identified by {@code (row, field)} against this position's field. */
  int compareField(int otherRow, int otherField) {
    int cmp = Integer.compare(otherRow, row);
    return cmp != 0 ? cmp : Integer.compare(otherField, field);
  }

  /** Whether this position and {@code other} refer to the same field. */
  boolean sameField(ScopePosition other) {
    return row == other.row && field == other.field;
  }

  /** This position moved to another row index, keeping field ordinal and offset. */
  ScopePosition inRow(int newRow) {
    return newRow == row ? this : new ScopePosition(newRow, field, offset);
  }
}
