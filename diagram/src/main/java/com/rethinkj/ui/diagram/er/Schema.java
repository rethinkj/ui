package com.rethinkj.ui.diagram.er;

import java.util.List;
import java.util.Objects;

/**
 * A schema of a database and its tables, as an {@link ErDiagram} shows them in a frame of their
 * own. The app fills it from its catalog, such as {@code DatabaseMetaData.getTables}.
 *
 * @param name the schema's name; empty for a database without schemas, whose tables get no frame
 * @param tables its tables, each with a name of its own
 */
public record Schema(String name, List<Table> tables) {

  public Schema {
    Objects.requireNonNull(name, "name");
    tables = List.copyOf(tables);
  }
}
