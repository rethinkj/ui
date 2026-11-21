package com.rethinkj.ui.diagram.er;

import java.util.List;
import java.util.Objects;

/**
 * A table of a {@link Schema}: its columns in their order and the foreign keys it has.
 *
 * @param name the table's name
 * @param columns its columns, such as {@code DatabaseMetaData.getColumns} lists them
 * @param foreignKeys its foreign keys, such as {@code DatabaseMetaData.getImportedKeys} lists them
 */
public record Table(String name, List<Column> columns, List<ForeignKey> foreignKeys) {

  public Table {
    Objects.requireNonNull(name, "name");
    columns = List.copyOf(columns);
    foreignKeys = List.copyOf(foreignKeys);
  }

  /** The column of this name, or null where the table has none. */
  Column column(String name) {
    for (Column column : columns) {
      if (column.name().equals(name)) {
        return column;
      }
    }
    return null;
  }
}
