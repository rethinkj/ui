package com.rethinkj.ui.diagram;

import com.rethinkj.ui.Context;

/**
 * The texts the diagram's components write themselves, in one language, as the kit's {@link
 * com.rethinkj.ui.kit.Texts} are for its components. Components read the texts of {@link
 * #CURRENT}, {@link #ENGLISH} unless the app gives others: {@code app.provide(DiagramTexts.CURRENT,
 * Settings::diagramTexts)}. The module comes with {@link #ENGLISH} and {@link GermanDiagramTexts};
 * another language is an implementation of this interface, and a single text an override of its
 * method. A method's default is the English text.
 */
public interface DiagramTexts {

  /** The English texts, the module's default. */
  DiagramTexts ENGLISH = new DiagramTexts() {};

  /** The texts the diagram's components read, {@link #ENGLISH} unless the app gives others. */
  Context<DiagramTexts> CURRENT = new Context<>(ENGLISH);

  /** The label of a {@link Canvas} that the app gives none. */
  default String diagram() {
    return "Diagram";
  }

  /** A canvas's zoom, as its value in a tree dump: {@code Zoom 100%}. */
  default String zoom(int percent) {
    return "Zoom " + percent + "%";
  }

  /** The value of a node that the user moved, which a layout leaves where it is. */
  default String pinned() {
    return "Pinned";
  }

  /** The value of an edge that stands out, {@link Canvas.Emphasis#HIGHLIGHTED}. */
  default String highlighted() {
    return "Highlighted";
  }

  /** The value of an edge that steps back, {@link Canvas.Emphasis#DIMMED}. */
  default String dimmed() {
    return "Dimmed";
  }

  /** The label of an {@link com.rethinkj.ui.diagram.er.ErDiagram}'s canvas. */
  default String erDiagram() {
    return "Entity relationship diagram";
  }

  /** A count of tables, as a schema's frame shows it: {@code 1 table}, {@code 8 tables}. */
  default String tables(int count) {
    return count == 1 ? "1 table" : count + " tables";
  }

  /** A count of columns, as a collapsed table shows it: {@code 6 columns}. */
  default String columns(int count) {
    return count == 1 ? "1 column" : count + " columns";
  }

  /** The row of a table that shows only its keys, for the columns it hides: {@code +3 columns}. */
  default String moreColumns(int count) {
    return "+" + columns(count);
  }

  /** What a table refers to, in its label: {@code references customer, address}. */
  default String references(String tables) {
    return "references " + tables;
  }

  /** What refers to a table, in its label: {@code referenced by order_item}. */
  default String referencedBy(String tables) {
    return "referenced by " + tables;
  }

  /** A column of the primary key, in the column's label. */
  default String primaryKey() {
    return "primary key";
  }

  /** A column of a foreign key, in the column's label. */
  default String foreignKey() {
    return "foreign key";
  }

  /** A column with a unique constraint of its own, in the column's label. */
  default String unique() {
    return "unique";
  }

  /** The short mark in a column's row for a unique column. */
  default String uniqueMark() {
    return "UQ";
  }

  /** A column that may be null, in the column's label. */
  default String nullable() {
    return "nullable";
  }

  /** A column's default, in its tooltip: {@code Default now()}. */
  default String defaultValue(String value) {
    return "Default " + value;
  }

  /** What a foreign key does when the row it refers to is deleted: {@code ON DELETE CASCADE}. */
  default String onDelete(String rule) {
    return "ON DELETE " + rule;
  }

  /** The choice that shows all columns of every table. */
  default String allColumns() {
    return "All columns";
  }

  /** The choice that shows only the key columns of every table. */
  default String keysOnly() {
    return "Keys only";
  }

  /** The choice that shows every table collapsed to its name. */
  default String collapsed() {
    return "Collapsed";
  }

  /** The placeholder of the field that filters tables by name. */
  default String filterTables() {
    return "Filter tables";
  }

  /** Shown on the canvas when a filter matches no table: {@code No tables match “foo”}. */
  default String noTablesMatch(String filter) {
    return "No tables match “" + filter + "”";
  }

  /** The action that zooms so that the whole diagram shows. */
  default String fit() {
    return "Fit";
  }

  /** The action that lays out the tables that the user did not move. */
  default String arrange() {
    return "Arrange";
  }

  /** The action that lays out all tables, also those the user moved. */
  default String arrangeAll() {
    return "Arrange all";
  }

  /** The button that collapses a table to its name. */
  default String collapse() {
    return "Collapse";
  }

  /** The button that shows a collapsed table's columns. */
  default String expand() {
    return "Expand";
  }

  /** A local git branch pill's label and tooltip: {@code Branch main}. */
  default String branch(String name) {
    return "Branch " + name;
  }

  /** The git branch that HEAD points to: {@code HEAD → main}. */
  default String headBranch(String name) {
    return "HEAD → " + name;
  }

  /** Detached HEAD, pointing at a commit rather than a branch. */
  default String detachedHead() {
    return "HEAD (detached)";
  }

  /** A remote git branch pill's label and tooltip: {@code Remote branch origin/main}. */
  default String remoteBranch(String name) {
    return "Remote branch " + name;
  }

  /** A git tag pill's label and tooltip: {@code Tag v1.0}. */
  default String tag(String name) {
    return "Tag " + name;
  }

  /**
   * A local or HEAD branch in sync with one or more remote branches on the same commit: {@code
   * Branch main, in sync with origin/main}.
   */
  default String inSync(String ref, String remotes) {
    return ref + ", in sync with " + remotes;
  }

  /**
   * The overflow pill for refs hidden beyond the limit: {@code 1 more ref: v1.0}, {@code 2 more
   * refs: origin/dev, v1.0}.
   */
  default String moreRefs(int count, String names) {
    return (count == 1 ? "1 more ref: " : count + " more refs: ") + names;
  }
}
