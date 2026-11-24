package com.rethinkj.ui.diagram;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZonedDateTime;

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
  public String uniqueMark() {
    return "UQ";
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
  public String noTablesMatch(String filter) {
    return "Keine Tabelle passt zu „" + filter + "“";
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

  @Override
  public String branch(String name) {
    return "Branch " + name;
  }

  @Override
  public String headBranch(String name) {
    return "HEAD → " + name;
  }

  @Override
  public String detachedHead() {
    return "HEAD (losgelöst)";
  }

  @Override
  public String remoteBranch(String name) {
    return "Remote-Branch " + name;
  }

  @Override
  public String tag(String name) {
    return "Tag " + name;
  }

  @Override
  public String inSync(String ref, String remotes) {
    return ref + ", synchron mit " + remotes;
  }

  @Override
  public String moreRefs(int count, String names) {
    return (count == 1 ? "1 weitere Referenz: " : count + " weitere Referenzen: ") + names;
  }

  @Override
  public String history() {
    return "Verlauf";
  }

  @Override
  public String commits(int count) {
    return count == 1 ? "1 Commit" : count + " Commits";
  }

  @Override
  public String commitsOf(int loaded, int total) {
    return loaded + " von " + total;
  }

  @Override
  public String loadingCommits() {
    return "Ältere Commits werden geladen…";
  }

  @Override
  public String noCommits() {
    return "Keine Commits";
  }

  @Override
  public String graphColumn() {
    return "Graph";
  }

  @Override
  public String messageColumn() {
    return "Nachricht";
  }

  @Override
  public String authorColumn() {
    return "Autor";
  }

  @Override
  public String dateColumn() {
    return "Datum";
  }

  @Override
  public String commitColumn() {
    return "Commit";
  }

  @Override
  public String branches(int count) {
    return count == 1 ? "1 Branch" : count + " Branches";
  }

  @Override
  public String tags(int count) {
    return count == 1 ? "1 Tag" : count + " Tags";
  }

  @Override
  public String commitLabel(String shortHash) {
    return "Commit " + shortHash;
  }

  @Override
  public String monthShort(Month month) {
    return switch (month) {
      case JANUARY -> "Jan.";
      case FEBRUARY -> "Feb.";
      case MARCH -> "März";
      case APRIL -> "Apr.";
      case MAY -> "Mai";
      case JUNE -> "Juni";
      case JULY -> "Juli";
      case AUGUST -> "Aug.";
      case SEPTEMBER -> "Sep.";
      case OCTOBER -> "Okt.";
      case NOVEMBER -> "Nov.";
      case DECEMBER -> "Dez.";
    };
  }

  @Override
  public String justNow() {
    return "gerade eben";
  }

  @Override
  public String minutesAgo(int minutes) {
    return "vor " + minutes + " Min.";
  }

  @Override
  public String hoursAgo(int hours) {
    return "vor " + hours + " Std.";
  }

  @Override
  public String yesterday() {
    return "gestern";
  }

  @Override
  public String daysAgo(int days) {
    return "vor " + days + " Tagen";
  }

  @Override
  public String shortDate(LocalDate date, boolean withYear) {
    String base = date.getDayOfMonth() + ". " + monthShort(date.getMonth());
    return withYear ? base + " " + date.getYear() : base;
  }

  @Override
  public String fullDate(ZonedDateTime date) {
    return String.format(
        "%d. %s %d, %02d:%02d",
        date.getDayOfMonth(),
        monthShort(date.getMonth()),
        date.getYear(),
        date.getHour(),
        date.getMinute());
  }
}
