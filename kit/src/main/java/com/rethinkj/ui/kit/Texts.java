package com.rethinkj.ui.kit;

import com.rethinkj.ui.Context;
import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.kit.DataGrid.Filter;
import java.time.DayOfWeek;
import java.time.Month;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * The texts the kit's components write themselves, in one language: the names of their buttons,
 * menu items and tooltips, their messages and the names of keys in shortcuts. The texts the app
 * gives a component, such as a button's or a field's, stay as the app writes them.
 *
 * <p>Components read the texts of {@link #CURRENT}, {@link #ENGLISH} unless the app gives others,
 * as they read their theme: {@code app.provide(Texts.CURRENT, Settings::texts)} for the whole
 * view, also when the language changes while the app runs. The kit comes with {@link #ENGLISH}
 * and {@link GermanTexts}; another language is an implementation of this interface, and a single
 * text another is an override of its method:
 *
 * <pre>{@code
 * Texts texts = new GermanTexts() {
 *   @Override
 *   public String noResults() {
 *     return "Nichts gefunden";
 *   }
 * };
 * }</pre>
 *
 * <p>A method's default is the English text. Methods with parameters build a whole sentence, so
 * that each language puts its words in its own order. {@link Format}'s messages and {@link
 * Menu#shortcutText} read the texts where they are called: the kit's own handlers run them in the
 * texts of the view they came from, a handler of the app in its own (see {@link Context}). A
 * message a handler wrote before the texts changed, such as a refused cell's in a data grid, stays
 * as it was until it goes.
 */
public interface Texts {

  /** The English texts, the kit's default. */
  Texts ENGLISH = new Texts() {};

  /**
   * The texts the kit's components read, {@link #ENGLISH} unless the app gives others: {@code
   * app.provide(Texts.CURRENT, Settings::texts)}.
   */
  Context<Texts> CURRENT = new Context<>(ENGLISH);

  // Keys

  /**
   * The name of a key in a shortcut, given its English one as {@link Menu#shortcutText} writes
   * it: {@code Ctrl}, {@code Shift}, {@code Enter}, {@code Del} and the like. A letter or a digit
   * stays as it is.
   */
  default String key(String name) {
    return name;
  }

  /** A combination of keys, by their English names, such as {@code keys("Ctrl", "H")}. */
  default String keys(String... names) {
    StringJoiner keys = new StringJoiner("+");
    for (String name : names) {
      keys.add(key(name));
    }
    return keys.toString();
  }

  // Shared

  default String close() {
    return "Close";
  }

  default String open() {
    return "Open";
  }

  default String more() {
    return "More";
  }

  /** The row of a list or tree whose entries are still loading. */
  default String loading() {
    return "Loading…";
  }

  /** The name of a spinner. */
  default String busy() {
    return "Loading";
  }

  default String noResults() {
    return "No results";
  }

  default String cancel() {
    return "Cancel";
  }

  default String save() {
    return "Save";
  }

  default String apply() {
    return "Apply";
  }

  default String clear() {
    return "Clear";
  }

  default String reset() {
    return "Reset";
  }

  default String cut() {
    return "Cut";
  }

  default String copy() {
    return "Copy";
  }

  /** What a copy button says once it copied. */
  default String copied() {
    return "Copied";
  }

  /** A button that copies {@code what}, such as a property's value by the property's name. */
  default String copy(String what) {
    return "Copy " + what;
  }

  default String copyCode() {
    return "Copy code";
  }

  default String paste() {
    return "Paste";
  }

  default String selectAll() {
    return "Select all";
  }

  default String error() {
    return "Error";
  }

  default String warning() {
    return "Warning";
  }

  /** The handle between two panes. */
  default String resize() {
    return "Resize";
  }

  /** The handle that resizes {@code what}, such as a pane or a grid's column. */
  default String resize(String what) {
    return "Resize " + what;
  }

  /** A range of values or lines, such as {@code 1 to 100}. */
  default String range(String from, String to) {
    return from + " to " + to;
  }

  // Buttons and fields

  /** The part of a split button that opens its menu. */
  default String moreOf(String button) {
    return "More " + button;
  }

  /** A badge's button that removes it. */
  default String remove(String badge) {
    return "Remove " + badge;
  }

  default String showPassword() {
    return "Show password";
  }

  default String hidePassword() {
    return "Hide password";
  }

  default String increase() {
    return "Increase";
  }

  default String decrease() {
    return "Decrease";
  }

  // Date picker

  /** The first day of a week in the calendar's rows: {@link DayOfWeek#SUNDAY} in English. */
  default DayOfWeek firstDayOfWeek() {
    return DayOfWeek.SUNDAY;
  }

  /** A month's name as shown in the calendar's heading. */
  default String month(Month month) {
    return switch (month) {
      case JANUARY -> "January";
      case FEBRUARY -> "February";
      case MARCH -> "March";
      case APRIL -> "April";
      case MAY -> "May";
      case JUNE -> "June";
      case JULY -> "July";
      case AUGUST -> "August";
      case SEPTEMBER -> "September";
      case OCTOBER -> "October";
      case NOVEMBER -> "November";
      case DECEMBER -> "December";
    };
  }

  /** A day of the week's two-letter heading above a calendar column. */
  default String weekdayShort(DayOfWeek day) {
    return switch (day) {
      case MONDAY -> "Mo";
      case TUESDAY -> "Tu";
      case WEDNESDAY -> "We";
      case THURSDAY -> "Th";
      case FRIDAY -> "Fr";
      case SATURDAY -> "Sa";
      case SUNDAY -> "Su";
    };
  }

  /** A day of the week's full name. */
  default String weekday(DayOfWeek day) {
    return switch (day) {
      case MONDAY -> "Monday";
      case TUESDAY -> "Tuesday";
      case WEDNESDAY -> "Wednesday";
      case THURSDAY -> "Thursday";
      case FRIDAY -> "Friday";
      case SATURDAY -> "Saturday";
      case SUNDAY -> "Sunday";
    };
  }

  default String today() {
    return "Today";
  }

  /** The button that opens a calendar popover and the popover's label. */
  default String chooseDate() {
    return "Choose date";
  }

  default String previousMonth() {
    return "Previous month";
  }

  default String nextMonth() {
    return "Next month";
  }

  /** The time field in a date-time picker's popover. */
  default String time() {
    return "Time";
  }

  // Windows, tabs and bars

  default String minimize() {
    return "Minimize";
  }

  default String maximize() {
    return "Maximize";
  }

  default String restore() {
    return "Restore";
  }

  /** A tab's button that closes it. */
  default String closeTab(String tab) {
    return "Close " + tab;
  }

  default String moveToNewWindow() {
    return "Move to new window";
  }

  default String moveToMainWindow() {
    return "Move back to main window";
  }

  /** A breadcrumb's button that lists the parts it hides. */
  default String hiddenParts() {
    return "Hidden parts";
  }

  /** A breadcrumb's button that lists what is inside {@code part}. */
  default String inside(String part) {
    return "Inside " + part;
  }

  default String statusBar() {
    return "Status bar";
  }

  default String breadcrumb() {
    return "Breadcrumb";
  }

  /** The popover of a {@link HuePicker#button}. */
  default String color() {
    return "Color";
  }

  /** A hue's name: {@code Red} for {@link Theme.Hue#RED}. */
  default String hue(Theme.Hue hue) {
    String name = hue.name();
    return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
  }

  // Command palette and completion

  default String commandPalette() {
    return "Command palette";
  }

  default String command() {
    return "Command";
  }

  default String commands() {
    return "Commands";
  }

  /** The command palette's field, before the prefixes of its other sources. */
  default String typeACommand() {
    return "Type a command";
  }

  default String recentlyUsed() {
    return "Recently used";
  }

  default String otherCommands() {
    return "Other commands";
  }

  default String completions() {
    return "Completions";
  }

  // Find

  default String find() {
    return "Find";
  }

  default String replace() {
    return "Replace";
  }

  default String replaceAll() {
    return "Replace all";
  }

  default String toggleReplace() {
    return "Toggle replace";
  }

  default String findInSelection() {
    return "Find in selection";
  }

  default String previousMatch() {
    return "Previous match";
  }

  default String nextMatch() {
    return "Next match";
  }

  default String matchCase() {
    return "Match case";
  }

  default String wholeWord() {
    return "Whole word";
  }

  default String regularExpression() {
    return "Regular expression";
  }

  default String goToLine() {
    return "Go to line";
  }

  /** The placeholder of the field of the line to go to. */
  default String lineOrColumn() {
    return "Line or line:column";
  }

  default String searching() {
    return "Searching…";
  }

  /** A search that took too long to find anything. */
  default String tooSlow() {
    return "Too slow";
  }

  default String replacingTookTooLong() {
    return "Replacing took too long";
  }

  /** A replacement whose dollar and opening brace have no closing brace after them. */
  default String unclosedGroup() {
    return "A ${ without its }";
  }

  /** A replacement with {@code $2} where the pattern has fewer groups. */
  default String noGroup(int number) {
    return "No group " + number;
  }

  /** An invalid regular expression: the JDK's description and the 1-based position of the error. */
  default String patternError(String description, int position) {
    return description + " at " + position;
  }

  /** Which match is selected: {@code 3 of 17}; {@code total} may be {@code 1000+}. */
  default String match(int number, String total) {
    return number + " of " + total;
  }

  /** How many matches there are: {@code 1 result}, {@code 17 results}, {@code 1000+ results}. */
  default String results(String total, boolean one) {
    return total + (one ? " result" : " results");
  }

  // Code

  /**
   * What kept a text of code from formatting ({@link Formatted.Problem}), as a diagnostic of the
   * code editor says it.
   */
  default String formatProblem(Formatted.Kind kind) {
    return switch (kind) {
      case UNEXPECTED -> "Not expected here";
      case UNEXPECTED_END -> "The text ends too early";
      case UNCLOSED_STRING -> "A string without its closing quote";
      case INVALID_ESCAPE -> "Not a valid escape";
      case INVALID_NUMBER -> "Not a valid number";
      case TOO_LARGE -> "Too large to format";
    };
  }

  // Data grid

  default String sortAscending() {
    return "Sort ascending";
  }

  default String sortDescending() {
    return "Sort descending";
  }

  default String clearSort() {
    return "Clear sort";
  }

  /** The menu item that opens a column's filter. */
  default String filterMenu() {
    return "Filter…";
  }

  default String hideColumn() {
    return "Hide column";
  }

  default String unfreezeColumns() {
    return "Unfreeze columns";
  }

  default String freezeUpToHere() {
    return "Freeze up to here";
  }

  default String fitWidth() {
    return "Fit width";
  }

  /** The menu item that fits every shown column as {@link #fitWidth} fits one. */
  default String fitAllColumns() {
    return "Fit all columns";
  }

  /** The menu item that opens the chooser of columns. */
  default String chooseColumns() {
    return "Columns…";
  }

  default String columns() {
    return "Columns";
  }

  default String searchColumns() {
    return "Search columns";
  }

  default String searchValues() {
    return "Search values";
  }

  /** The hint of the chooser of columns, with the keys that move a column up and down. */
  default String moveOne(String up, String down) {
    return up + " and " + down + " move one";
  }

  default String showAll() {
    return "Show all";
  }

  /** The submenu of a column in the grid's context menu. */
  default String column(String title) {
    return "Column " + title;
  }

  /** A filter's column by its number, where the grid has no such column. */
  default String column(int number) {
    return "column " + number;
  }

  default String copyWithHeaders() {
    return "Copy with headers";
  }

  default String setNull() {
    return "Set NULL";
  }

  default String insertRow() {
    return "Insert row";
  }

  default String deleteRows() {
    return "Delete rows";
  }

  /** The editor of a cell in {@code column}. */
  default String edit(String column) {
    return "Edit " + column;
  }

  /** The placeholder of an empty text that is not NULL. */
  default String empty() {
    return "empty";
  }

  default String editInLines() {
    return "Edit in several lines";
  }

  /** The list of the values a cell of {@code column} may take. */
  default String choicesOf(String column) {
    return "Choices of " + column;
  }

  /** The hint of a cell's editor of several lines, with the keys that save it. */
  default String toSave(String keys) {
    return keys + " to save";
  }

  /** The filter of {@code column}: its button and its popover. */
  default String filter(String column) {
    return "Filter " + column;
  }

  default String filters() {
    return "Filters";
  }

  default String clearAll() {
    return "Clear all";
  }

  default String value() {
    return "Value";
  }

  /** The field of a range's start. */
  default String from() {
    return "From";
  }

  /** The field of a range's end. */
  default String to() {
    return "To";
  }

  /** What a filter of this kind keeps, in a list to choose from. */
  default String filterKind(Filter.Kind kind) {
    return switch (kind) {
      case CONTAINS -> "Contains";
      case STARTS_WITH -> "Starts with";
      case EQUALS -> "Equals";
      case MATCHES -> "Matches regular expression";
      case BETWEEN -> "Between";
      case ONE_OF -> "One of";
      case IS_NULL -> "Is NULL";
      case NOT_NULL -> "Is not NULL";
    };
  }

  /** A filter that keeps text with {@code value} in it ({@link Filter#describe}). */
  default String contains(String column, String value) {
    return column + " contains " + value;
  }

  default String startsWith(String column, String value) {
    return column + " starts with " + value;
  }

  default String matches(String column, String value) {
    return column + " matches " + value;
  }

  /** A filter that keeps one of {@code values}, given as a list such as {@code paid, shipped}. */
  default String oneOf(String column, String values) {
    return column + " in " + values;
  }

  default String isNull(String column) {
    return column + " is NULL";
  }

  default String isNotNull(String column) {
    return column + " is not NULL";
  }

  default String notARegularExpression() {
    return "Not a regular expression";
  }

  /** A range filter whose start lies above its end. */
  default String fromAboveTo() {
    return "From is greater than To";
  }

  /** An edit for a column that the grid's table does not have. */
  default String notAColumn() {
    return "Not a column of this table";
  }

  /** An edit of a value inside a part of the row that is NULL. */
  default String insideNull() {
    return "The value lies inside a part that is NULL in this row";
  }

  default String rowNotLoaded() {
    return "The row is not loaded";
  }

  /** The count of values in a footer cell or a selection summary. */
  default String count() {
    return "Count";
  }

  /** The sum of values in a footer cell or a selection summary. */
  default String sum() {
    return "Sum";
  }

  /** The average of values in a footer cell or a selection summary. */
  default String average() {
    return "Avg";
  }

  /** The minimum of values in a footer cell or a selection summary. */
  default String minimum() {
    return "Min";
  }

  /** The maximum of values in a footer cell or a selection summary. */
  default String maximum() {
    return "Max";
  }

  // Formats

  /** The message of an empty text where a value is required ({@link Format#REQUIRED}). */
  default String required() {
    return Format.REQUIRED;
  }

  default String notAChoice() {
    return "Not one of the choices";
  }

  default String notANumber() {
    return "Not a number";
  }

  default String notAWholeNumber() {
    return "Not a whole number";
  }

  default String wholeNumber(String min, String max) {
    return "Expected a whole number from " + min + " to " + max;
  }

  default String numberTooLong(int characters) {
    return "Expected a number of at most " + characters + " characters";
  }

  default String tooManyZeros(int zeros) {
    return "Expected a number whose exponent adds at most " + zeros + " zeros";
  }

  default String expectedDate() {
    return "Expected a date such as 2026-11-13";
  }

  default String expectedTime() {
    return "Expected a time such as 14:30 or 14:30:15";
  }

  default String expectedDateTime() {
    return "Expected a date and time such as 2026-11-13 14:30";
  }

  default String expectedOffsetDateTime() {
    return "Expected a date, a time and an offset such as 2026-11-13 14:30+01:00";
  }

  default String expectedSize() {
    return "Expected a size such as 817 B, 1.2 MB or 4 GiB";
  }

  default String sizeTooLarge(String max) {
    return "Expected a size of at most " + max + " B";
  }

  default String expectedDuration() {
    return "Expected a duration such as 12 ms, 1.4 s or 2 min 3 s";
  }

  default String expectedBoolean() {
    return "Expected true or false";
  }
}
