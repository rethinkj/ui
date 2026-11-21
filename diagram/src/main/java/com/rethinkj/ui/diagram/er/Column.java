package com.rethinkj.ui.diagram.er;

import java.util.Objects;

/**
 * A column of a {@link Table}, from {@code DatabaseMetaData.getColumns}, {@code getPrimaryKeys}
 * and {@code getIndexInfo}.
 *
 * @param name the column's name
 * @param type its type as the database writes it, such as {@code bigint} or {@code numeric(10,2)}
 * @param nullable whether it may be null
 * @param primaryKey whether it is part of the table's primary key
 * @param unique whether a unique constraint or index covers it alone
 * @param defaultValue its default as the database writes it, null for none
 */
public record Column(
    String name,
    String type,
    boolean nullable,
    boolean primaryKey,
    boolean unique,
    String defaultValue) {

  public Column {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
  }

  /** A column that is no key and has no default. */
  public Column(String name, String type, boolean nullable) {
    this(name, type, nullable, false, false, null);
  }
}
