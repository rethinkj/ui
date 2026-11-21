package com.rethinkj.ui.diagram;

/**
 * The German texts of the diagram module. An app keeps one in a constant, {@code static final
 * DiagramTexts GERMAN = new GermanDiagramTexts();}, and changes a few texts by overriding their
 * methods.
 */
public class GermanDiagramTexts implements DiagramTexts {

  @Override
  public String diagram() {
    return "Diagramm";
  }

  @Override
  public String zoom(int percent) {
    return "Zoom " + percent + " %";
  }

  @Override
  public String pinned() {
    return "Angeheftet";
  }

  @Override
  public String highlighted() {
    return "Hervorgehoben";
  }

  @Override
  public String dimmed() {
    return "Abgeblendet";
  }

  @Override
  public String erDiagram() {
    return "Entity-Relationship-Diagramm";
  }

  @Override
  public String tables(int count) {
    return count == 1 ? "1 Tabelle" : count + " Tabellen";
  }

  @Override
  public String columns(int count) {
    return count == 1 ? "1 Spalte" : count + " Spalten";
  }

  @Override
  public String references(String tables) {
    return "verweist auf " + tables;
  }

  @Override
  public String referencedBy(String tables) {
    return "referenziert von " + tables;
  }

  @Override
  public String primaryKey() {
    return "Primärschlüssel";
  }

  @Override
  public String foreignKey() {
    return "Fremdschlüssel";
  }

  @Override
  public String unique() {
    return "eindeutig";
  }

  @Override
  public String nullable() {
    return "nullbar";
  }

  @Override
  public String defaultValue(String value) {
    return "Standard " + value;
  }

  @Override
  public String allColumns() {
    return "Alle Spalten";
  }

  @Override
  public String keysOnly() {
    return "Nur Schlüssel";
  }

  @Override
  public String collapsed() {
    return "Eingeklappt";
  }

  @Override
  public String filterTables() {
    return "Tabellen filtern";
  }

  @Override
  public String fit() {
    return "Einpassen";
  }

  @Override
  public String arrange() {
    return "Anordnen";
  }

  @Override
  public String arrangeAll() {
    return "Alle anordnen";
  }

  @Override
  public String collapse() {
    return "Einklappen";
  }

  @Override
  public String expand() {
    return "Ausklappen";
  }
}
