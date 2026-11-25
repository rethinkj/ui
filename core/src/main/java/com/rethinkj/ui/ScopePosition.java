package com.rethinkj.ui;

/**
 * A character position inside an {@link El#selectionScope}: the row index of the scope's virtual
 * list ({@code -1} for a field outside the virtual list), the ordinal of the read-only unmasked
 * field within that row (or within the scope outside rows) in tree order, and the character offset
 * in the field's normalized text.
 */
record ScopePosition(int row, int field, int offset) implements Comparable<ScopePosition> {

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
