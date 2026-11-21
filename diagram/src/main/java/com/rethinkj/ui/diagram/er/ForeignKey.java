package com.rethinkj.ui.diagram.er;

import java.util.List;
import java.util.Objects;

/**
 * A foreign key of a {@link Table}, from the rows of {@code DatabaseMetaData.getImportedKeys} with
 * one {@code FK_NAME}. An {@link ErDiagram} draws it as an edge from the row of its first column
 * to the row of the first column it refers to.
 *
 * @param name the constraint's name, null where the database gives none
 * @param columns the columns of the table that refer, in the key's order
 * @param schema the schema of the table referred to, null for the same schema
 * @param table the table referred to, which may be the table itself
 * @param referencedColumns the columns referred to, one for each of {@code columns}
 * @param onDelete what deleting the row referred to does, such as {@code CASCADE} or {@code SET
 *     NULL}; null to leave it out
 */
public record ForeignKey(
    String name,
    List<String> columns,
    String schema,
    String table,
    List<String> referencedColumns,
    String onDelete) {

  public ForeignKey {
    columns = List.copyOf(columns);
    Objects.requireNonNull(table, "table");
    referencedColumns = List.copyOf(referencedColumns);
    if (columns.isEmpty() || columns.size() != referencedColumns.size()) {
      throw new IllegalArgumentException("a foreign key needs one referenced column for each of its"
          + " columns, and at least one, was "
          + columns
          + " to "
          + referencedColumns);
    }
  }

  /** A foreign key of one column to a table of the same schema. */
  public ForeignKey(String name, String column, String table, String referencedColumn) {
    this(name, List.of(column), null, table, List.of(referencedColumn), null);
  }
}
