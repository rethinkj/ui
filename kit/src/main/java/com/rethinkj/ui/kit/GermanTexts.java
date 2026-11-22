package com.rethinkj.ui.kit;

import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.kit.DataGrid.Filter;

/**
 * The German texts of the kit, with the names of keys as German keyboards print them: {@code
 * Strg}, {@code Umschalt}, {@code Entf}. An app keeps one in a constant, {@code static final Texts
 * GERMAN = new GermanTexts();}, and changes a few texts by overriding their methods.
 */
public class GermanTexts implements Texts {

  @Override
  public String key(String name) {
    return switch (name) {
      case "Ctrl", "Control" -> "Strg";
      case "Shift" -> "Umschalt";
      case "Enter" -> "Eingabe";
      case "Escape" -> "Esc";
      case "Del", "Delete" -> "Entf";
      case "Ins", "Insert" -> "Einfg";
      case "PgUp", "Page Up" -> "Bild auf";
      case "PgDn", "Page Down" -> "Bild ab";
      case "Home" -> "Pos1";
      case "End" -> "Ende";
      case "Up" -> "↑";
      case "Down" -> "↓";
      case "Left" -> "←";
      case "Right" -> "→";
      case "Backspace" -> "Rücktaste";
      case "Space" -> "Leertaste";
      case "PrintScreen" -> "Druck";
      default -> name;
    };
  }

  @Override
  public String close() {
    return "Schließen";
  }

  @Override
  public String open() {
    return "Öffnen";
  }

  @Override
  public String more() {
    return "Mehr";
  }

  @Override
  public String loading() {
    return "Wird geladen…";
  }

  @Override
  public String busy() {
    return "Wird geladen";
  }

  @Override
  public String noResults() {
    return "Keine Ergebnisse";
  }

  @Override
  public String cancel() {
    return "Abbrechen";
  }

  @Override
  public String save() {
    return "Speichern";
  }

  @Override
  public String apply() {
    return "Anwenden";
  }

  @Override
  public String clear() {
    return "Entfernen";
  }

  @Override
  public String reset() {
    return "Zurücksetzen";
  }

  @Override
  public String cut() {
    return "Ausschneiden";
  }

  @Override
  public String copy() {
    return "Kopieren";
  }

  @Override
  public String copied() {
    return "Kopiert";
  }

  @Override
  public String copy(String what) {
    return what + " kopieren";
  }

  @Override
  public String copyCode() {
    return "Code kopieren";
  }

  @Override
  public String paste() {
    return "Einfügen";
  }

  @Override
  public String selectAll() {
    return "Alles auswählen";
  }

  @Override
  public String error() {
    return "Fehler";
  }

  @Override
  public String warning() {
    return "Warnung";
  }

  @Override
  public String resize() {
    return "Größe ändern";
  }

  @Override
  public String resize(String what) {
    return "Größe von " + what + " ändern";
  }

  @Override
  public String range(String from, String to) {
    return from + " bis " + to;
  }

  @Override
  public String moreOf(String button) {
    return "Weitere Optionen für „" + button + "“";
  }

  @Override
  public String remove(String badge) {
    return badge + " entfernen";
  }

  @Override
  public String showPassword() {
    return "Passwort anzeigen";
  }

  @Override
  public String hidePassword() {
    return "Passwort verbergen";
  }

  @Override
  public String increase() {
    return "Erhöhen";
  }

  @Override
  public String decrease() {
    return "Verringern";
  }

  @Override
  public String minimize() {
    return "Minimieren";
  }

  @Override
  public String maximize() {
    return "Maximieren";
  }

  @Override
  public String restore() {
    return "Wiederherstellen";
  }

  @Override
  public String closeTab(String tab) {
    return tab + " schließen";
  }

  @Override
  public String moveToNewWindow() {
    return "In neues Fenster verschieben";
  }

  @Override
  public String moveToMainWindow() {
    return "Zurück ins Hauptfenster verschieben";
  }

  @Override
  public String hiddenParts() {
    return "Ausgeblendete Teile";
  }

  @Override
  public String inside(String part) {
    return "In " + part;
  }

  @Override
  public String color() {
    return "Farbe";
  }

  @Override
  public String statusBar() {
    return "Statusleiste";
  }

  @Override
  public String breadcrumb() {
    return "Navigationspfad";
  }

  @Override
  public String hue(Theme.Hue hue) {
    return switch (hue) {
      case BLUE -> "Blau";
      case VIOLET -> "Violett";
      case PURPLE -> "Lila";
      case PINK -> "Rosa";
      case RED -> "Rot";
      case ORANGE -> "Orange";
      case AMBER -> "Bernstein";
      case GREEN -> "Grün";
      case TEAL -> "Petrol";
      case GRAY -> "Grau";
    };
  }

  @Override
  public String commandPalette() {
    return "Befehlspalette";
  }

  @Override
  public String command() {
    return "Befehl";
  }

  @Override
  public String commands() {
    return "Befehle";
  }

  @Override
  public String typeACommand() {
    return "Befehl eingeben";
  }

  @Override
  public String recentlyUsed() {
    return "Zuletzt verwendet";
  }

  @Override
  public String otherCommands() {
    return "Weitere Befehle";
  }

  @Override
  public String completions() {
    return "Vervollständigungen";
  }

  @Override
  public String find() {
    return "Suchen";
  }

  @Override
  public String replace() {
    return "Ersetzen";
  }

  @Override
  public String replaceAll() {
    return "Alle ersetzen";
  }

  @Override
  public String toggleReplace() {
    return "Ersetzen ein- oder ausblenden";
  }

  @Override
  public String findInSelection() {
    return "In Auswahl suchen";
  }

  @Override
  public String previousMatch() {
    return "Vorheriger Treffer";
  }

  @Override
  public String nextMatch() {
    return "Nächster Treffer";
  }

  @Override
  public String matchCase() {
    return "Groß-/Kleinschreibung beachten";
  }

  @Override
  public String wholeWord() {
    return "Nur ganzes Wort";
  }

  @Override
  public String regularExpression() {
    return "Regulärer Ausdruck";
  }

  @Override
  public String goToLine() {
    return "Gehe zu Zeile";
  }

  @Override
  public String lineOrColumn() {
    return "Zeile oder Zeile:Spalte";
  }

  @Override
  public String searching() {
    return "Suche läuft…";
  }

  @Override
  public String tooSlow() {
    return "Zu langsam";
  }

  @Override
  public String replacingTookTooLong() {
    return "Das Ersetzen hat zu lange gedauert";
  }

  @Override
  public String unclosedGroup() {
    return "Ein ${ ohne schließende }";
  }

  @Override
  public String noGroup(int number) {
    return "Keine Gruppe " + number;
  }

  @Override
  public String patternError(String description, int position) {
    return description + " bei Zeichen " + position;
  }

  @Override
  public String match(int number, String total) {
    return number + " von " + total;
  }

  @Override
  public String results(String total, boolean one) {
    return total + " Treffer";
  }

  @Override
  public String formatProblem(Formatted.Kind kind) {
    return switch (kind) {
      case UNEXPECTED -> "Hier nicht erwartet";
      case UNEXPECTED_END -> "Der Text endet zu früh";
      case UNCLOSED_STRING -> "Eine Zeichenkette ohne schließendes Anführungszeichen";
      case INVALID_ESCAPE -> "Keine gültige Escape-Sequenz";
      case INVALID_NUMBER -> "Keine gültige Zahl";
      case TOO_LARGE -> "Zu groß zum Formatieren";
    };
  }

  @Override
  public String sortAscending() {
    return "Aufsteigend sortieren";
  }

  @Override
  public String sortDescending() {
    return "Absteigend sortieren";
  }

  @Override
  public String clearSort() {
    return "Sortierung aufheben";
  }

  @Override
  public String filterMenu() {
    return "Filtern…";
  }

  @Override
  public String hideColumn() {
    return "Spalte ausblenden";
  }

  @Override
  public String unfreezeColumns() {
    return "Fixierung aufheben";
  }

  @Override
  public String freezeUpToHere() {
    return "Bis hier fixieren";
  }

  @Override
  public String fitWidth() {
    return "Breite anpassen";
  }

  @Override
  public String fitAllColumns() {
    return "Alle Spalten anpassen";
  }

  @Override
  public String chooseColumns() {
    return "Spalten…";
  }

  @Override
  public String columns() {
    return "Spalten";
  }

  @Override
  public String searchColumns() {
    return "Spalten suchen";
  }

  @Override
  public String searchValues() {
    return "Werte suchen";
  }

  @Override
  public String moveOne(String up, String down) {
    return up + " und " + down + " verschieben um eins";
  }

  @Override
  public String showAll() {
    return "Alle anzeigen";
  }

  @Override
  public String column(String title) {
    return "Spalte " + title;
  }

  @Override
  public String column(int number) {
    return "Spalte " + number;
  }

  @Override
  public String copyWithHeaders() {
    return "Mit Überschriften kopieren";
  }

  @Override
  public String setNull() {
    return "Auf NULL setzen";
  }

  @Override
  public String insertRow() {
    return "Zeile einfügen";
  }

  @Override
  public String deleteRows() {
    return "Zeilen löschen";
  }

  @Override
  public String edit(String column) {
    return column + " bearbeiten";
  }

  @Override
  public String empty() {
    return "leer";
  }

  @Override
  public String editInLines() {
    return "Mehrzeilig bearbeiten";
  }

  @Override
  public String choicesOf(String column) {
    return "Auswahl für " + column;
  }

  @Override
  public String toSave(String keys) {
    return keys + " zum Speichern";
  }

  @Override
  public String filter(String column) {
    return "Filter für " + column;
  }

  @Override
  public String filters() {
    return "Filter";
  }

  @Override
  public String clearAll() {
    return "Alle entfernen";
  }

  @Override
  public String value() {
    return "Wert";
  }

  @Override
  public String from() {
    return "Von";
  }

  @Override
  public String to() {
    return "Bis";
  }

  @Override
  public String filterKind(Filter.Kind kind) {
    return switch (kind) {
      case CONTAINS -> "Enthält";
      case STARTS_WITH -> "Beginnt mit";
      case EQUALS -> "Ist gleich";
      case MATCHES -> "Passt zum regulären Ausdruck";
      case BETWEEN -> "Zwischen";
      case ONE_OF -> "Einer von";
      case IS_NULL -> "Ist NULL";
      case NOT_NULL -> "Ist nicht NULL";
    };
  }

  @Override
  public String contains(String column, String value) {
    return column + " enthält " + value;
  }

  @Override
  public String startsWith(String column, String value) {
    return column + " beginnt mit " + value;
  }

  @Override
  public String matches(String column, String value) {
    return column + " passt zu " + value;
  }

  @Override
  public String oneOf(String column, String values) {
    return column + " ist einer von " + values;
  }

  @Override
  public String isNull(String column) {
    return column + " ist NULL";
  }

  @Override
  public String isNotNull(String column) {
    return column + " ist nicht NULL";
  }

  @Override
  public String notARegularExpression() {
    return "Kein gültiger regulärer Ausdruck";
  }

  @Override
  public String notAColumn() {
    return "Keine Spalte dieser Tabelle";
  }

  @Override
  public String insideNull() {
    return "Der Wert liegt in einem Teil, der in dieser Zeile NULL ist";
  }

  @Override
  public String rowNotLoaded() {
    return "Die Zeile ist nicht geladen";
  }

  @Override
  public String required() {
    return "Ein Wert ist erforderlich";
  }

  @Override
  public String notAChoice() {
    return "Keine der Möglichkeiten";
  }

  @Override
  public String notANumber() {
    return "Keine Zahl";
  }

  @Override
  public String notAWholeNumber() {
    return "Keine ganze Zahl";
  }

  @Override
  public String wholeNumber(String min, String max) {
    return "Erwartet wird eine ganze Zahl von " + min + " bis " + max;
  }

  @Override
  public String numberTooLong(int characters) {
    return "Erwartet wird eine Zahl mit höchstens " + characters + " Zeichen";
  }

  @Override
  public String tooManyZeros(int zeros) {
    return "Erwartet wird eine Zahl, deren Exponent höchstens " + zeros + " Nullen anfügt";
  }

  @Override
  public String expectedDate() {
    return "Erwartet wird ein Datum wie 2026-11-13";
  }

  @Override
  public String expectedTime() {
    return "Erwartet wird eine Uhrzeit wie 14:30 oder 14:30:15";
  }

  @Override
  public String expectedDateTime() {
    return "Erwartet werden Datum und Uhrzeit wie 2026-11-13 14:30";
  }

  @Override
  public String expectedOffsetDateTime() {
    return "Erwartet werden Datum, Uhrzeit und Zeitversatz wie 2026-11-13 14:30+01:00";
  }

  @Override
  public String expectedSize() {
    return "Erwartet wird eine Größe wie 817 B, 1.2 MB oder 4 GiB";
  }

  @Override
  public String sizeTooLarge(String max) {
    return "Erwartet wird eine Größe von höchstens " + max + " B";
  }

  @Override
  public String expectedDuration() {
    return "Erwartet wird eine Dauer wie 12 ms, 1.4 s oder 2 min 3 s";
  }

  @Override
  public String expectedBoolean() {
    return "Erwartet wird true oder false";
  }
}
