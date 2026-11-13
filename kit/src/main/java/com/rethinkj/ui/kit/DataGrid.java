package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Text;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * A table of query results: a header with the column titles over rows of cells, as dense as a
 * database tool shows them, for ten rows or a million and for a dozen columns or hundreds. The app
 * owns the data and the state, and the grid shows them and tells the app what the user does:
 *
 * <pre>{@code
 * List<Column> columns = List.of(
 *     new Column("id", s.widths[0], DataType.INTEGER, i -> String.valueOf(s.ids[i]))
 *         .typeName("bigint").icon(Symbol.KEY, Icon.Variant.WARNING),
 *     new Column("name", s.widths[1], Align.LEFT, false, i -> s.names[i]));
 * dataGrid(theme, columns, s.count, DataGrid.options()
 *         .select(s.selection)                       // a Selection the app keeps
 *         .sort(s.sort, keys -> s.sort = keys)
 *         .onResize((c, w) -> s.widths[c] = w)
 *         .rowNumbers().frozen(1)
 *         .copy(s.app.clipboard())
 *         .contextMenu(s.menu, List.of(Menu.item("Export…", s::export))))
 *     .key("results").label("Results").grow(1);
 * }</pre>
 *
 * <p>The rows are a virtual list ({@link El#rows}) whose rows hold only the cells of the columns
 * in view sideways and some more: the cell functions are called for those alone, so a frame
 * costs the same for a million rows and for hundreds of columns. The header stays in place while
 * the rows scroll up and down, and both scroll sideways together; the row numbers and the first
 * {@link Options#frozen} columns stay at the left. Hiding a column is the app's: it leaves it out
 * of the list, and reorders the list to move one.
 *
 * <p>The {@link Selection} is the app's, and the grid changes it in its handlers, as a {@link
 * Menu.State}: a cursor, an anchor and ranges of rows or cells ({@link Mode}). A press selects;
 * Shift with a press extends from the anchor; Control with a press adds or removes; moving the
 * pointer with the button held extends to the cell under it, and beyond the grid's edges scrolls
 * it, one step per movement of the pointer. A press on a row's number selects the row. While the
 * grid has the focus, the arrows move the cursor (Left and Right in {@link Mode#CELLS}), Page Up
 * and Page Down by the rows in view, Home and End to the first and last row, or in cells to the
 * first and last column, with Control to the first and last cell; Shift extends, Control+A
 * selects all, and the rows and columns follow the cursor. Control+C and Control+Insert copy the
 * selection as tab-separated text ({@link #copyText}). Enter, F2, a double press and typing edit
 * the cursor's cell in an editor the grid lays over it ({@link Options#onCommit}, {@link
 * Editing}), or ask the app to ({@link Options#onEdit}); pasting, clearing, NULL, new and deleted
 * rows and what changed go through the app as well, and {@link GridChanges} keeps those changes
 * until the app writes them. The header filters columns ({@link Options#filters}) and chooses,
 * moves and hides them ({@link Options#columns}). The grid is one stop for Tab.
 *
 * <p>A header cell shows the column's icon, its title, its type in muted text and the sort mark,
 * in this order at the left or the right as the column puts its text, the mark on the outer side.
 * Where the column is narrow, the type gives way first, and goes entirely before less than its
 * first character would show, then the title ends in an ellipsis; icon
 * and mark keep their size, and a tooltip shows title and type whole. A cell shows its icon
 * ({@link Column#cellIcon}) before its text or alone in its place.
 *
 * <p>A press on a header cell sorts by its column ({@link Options#sort}), Shift with a press adds
 * it as a further key; dragging the right edge of a header cell resizes its column, and a double
 * press on it fits the column to its header and the cells of its rows that exist, icons
 * included. A cell shows its text on one line, with {@link #LINE_BREAK} for each line break and
 * {@link #TAB} for each tab; where it is wider than its column it ends in an ellipsis and shows
 * whole in a tooltip. Scrollbars ({@link
 * Theme#scrollbars}) show where the rows and columns in view are: one along the right edge of the
 * rows, below the header, and one along the bottom of the grid. They lie over the rows, and the
 * grid scrolls far enough to bring the last row and the last column out from under them: the rows
 * past the bar along the bottom, and the columns past the rows' bar, for which the header and the
 * rows have the room of that bar after the last column; the rows and columns that follow the
 * cursor stop clear of both bars too. Like a list of rows, the grid needs a height of its own or
 * room to {@link El#grow}.
 *
 * <p>In a tree dump the grid is {@code role=grid}, its header cells {@code role=columnheader}
 * with the title as label, the key {@code column-<index>}, their position among the columns and
 * the sort state, and the title and the type as text inside, the edges that resize them {@code
 * role=separator} with the key {@code resize-<index>}, its rows {@code role=row} with the key
 * {@code row-<index>}, and its cells {@code role=gridcell} with the key {@code
 * cell-<row>-<column>} and their column's position, all counted from 0. An icon is a text
 * element labelled with its symbol's name ({@link Icon}), and a cell whose icon stands alone has
 * its text as its label: {@code el #cell-4-3 role=gridcell label="true" ...} over {@code text
 * label="checkmark" ...}. Selected rows ({@link Mode#ROWS}) or cells ({@link Mode#CELLS}) are {@code
 * selected}, the cursor's row or cell {@code current}; row numbers are {@code role=rowheader}
 * with the key {@code number-<row>}. The thumbs are on the lines of the grid ({@code thumb-x}) and
 * of its rows ({@code thumb-y}, on {@code #rows}).
 */
public final class DataGrid {

  /** Where a column puts its text. Numbers usually go right, in a {@link Column#mono} column. */
  public enum Align {
    LEFT,
    RIGHT
  }

  /** What a press and the keys select. */
  public enum Mode {
    /** Whole rows, as a list of results is read. */
    ROWS,
    /** Cells, and whole rows by their numbers, as in a spreadsheet. */
    CELLS
  }

  /**
   * A column of the grid. The constructors make one with its title and text alone; the other
   * parts come with the methods that return a changed copy, as the view builds it:
   *
   * <pre>{@code
   * new Column("id", w[0], DataType.INTEGER, i -> String.valueOf(ids[i]))
   *     .typeName("bigint").icon(Symbol.KEY, Icon.Variant.WARNING)
   * new Column("paid", w[1], DataType.BOOLEAN, i -> String.valueOf(paid[i]))
   *     .typeName("boolean").cellIcon(i -> paid[i] ? CHECKED : UNCHECKED)
   * }</pre>
   *
   * @param title shown in the header, also the header cell's label
   * @param width in logical pixels, the column's line included; the app keeps it and changes it
   *     when the grid reports a resize
   * @param align where the title and the cells put their text and icons
   * @param mono whether the cells use the monospaced font, as for numbers and timestamps
   * @param sortable whether a press on its header sorts by it, when the grid sorts at all
   * @param cell the text of the cell in the row with this index, or null for SQL NULL, which shows
   *     as a muted {@code NULL}. Called while a frame runs, for the rows that exist and the
   *     columns in view only, so it has to be cheap. It is what a copy takes, also from a cell
   *     that shows its icon alone.
   * @param icon shown in the header before the title, such as the kind of data or a key; null for
   *     none
   * @param iconVariant the colour of {@code icon}
   * @param typeName shown in the header after the title in muted text, such as {@code bigint};
   *     null for none. It gives way first where the column is narrow.
   * @param cellIcon the icon of the cell in the row with this index, or null for none: called as
   *     {@code cell} is, after it and for loaded rows only. Return constants, as the icons of a
   *     column are few, so that a cell costs no allocation for its icon; null for the function
   *     means no icons at all.
   * @param type the kind of data, which chooses the editor ({@link Editing}); {@link
   *     DataType#OTHER} for a column made without one
   * @param editing whether and how its cells are edited; {@link Editing#NONE} for not at all
   * @param cells the typed part of a column of a {@link GridTable}, whose format checks the
   *     editor's text in place of the type's; null for a column of text
   */
  public record Column(
      String title,
      float width,
      Align align,
      boolean mono,
      boolean sortable,
      IntFunction<String> cell,
      Symbol icon,
      Icon.Variant iconVariant,
      String typeName,
      IntFunction<CellIcon> cellIcon,
      DataType type,
      Editing editing,
      GridTable.Cells<?, ?> cells) {

    public Column {
      Objects.requireNonNull(iconVariant, "iconVariant");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(editing, "editing");
    }

    /** A column of untyped text, without {@link #cells}. */
    public Column(
        String title,
        float width,
        Align align,
        boolean mono,
        boolean sortable,
        IntFunction<String> cell,
        Symbol icon,
        Icon.Variant iconVariant,
        String typeName,
        IntFunction<CellIcon> cellIcon,
        DataType type,
        Editing editing) {
      this(
          title,
          width,
          align,
          mono,
          sortable,
          cell,
          icon,
          iconVariant,
          typeName,
          cellIcon,
          type,
          editing,
          null);
    }

    /** A column with icons and type, of {@link DataType#OTHER} and not editable. */
    public Column(
        String title,
        float width,
        Align align,
        boolean mono,
        boolean sortable,
        IntFunction<String> cell,
        Symbol icon,
        Icon.Variant iconVariant,
        String typeName,
        IntFunction<CellIcon> cellIcon) {
      this(
          title,
          width,
          align,
          mono,
          sortable,
          cell,
          icon,
          iconVariant,
          typeName,
          cellIcon,
          DataType.OTHER,
          Editing.NONE);
    }

    /** A column that sorts or not, without icons or type. */
    public Column(
        String title,
        float width,
        Align align,
        boolean mono,
        boolean sortable,
        IntFunction<String> cell) {
      this(title, width, align, mono, sortable, cell, null, Icon.Variant.MUTED, null, null);
    }

    /** A column that can be sorted. */
    public Column(String title, float width, Align align, boolean mono, IntFunction<String> cell) {
      this(title, width, align, mono, true, cell);
    }

    /**
     * A column of a kind of data, which gives its alignment, its font, the icon in its header and
     * its editor ({@link DataType}).
     */
    public Column(String title, float width, DataType type, IntFunction<String> cell) {
      this(
          title,
          width,
          type.align(),
          type.mono(),
          true,
          cell,
          type.symbol(),
          Icon.Variant.MUTED,
          null,
          null,
          type,
          Editing.NONE);
    }

    /**
     * This column with its text from another function, keeping its {@link #cells}: a column of a
     * {@link GridTable} through a {@link GridChanges}, {@code column.cell(changes.cell(c))}.
     */
    public Column cell(IntFunction<String> cell) {
      return new Column(
          title,
          width,
          align,
          mono,
          sortable,
          Objects.requireNonNull(cell, "cell"),
          icon,
          iconVariant,
          typeName,
          cellIcon,
          type,
          editing,
          cells);
    }

    /** This column, sortable or not. */
    public Column sortable(boolean on) {
      return new Column(
          title,
          width,
          align,
          mono,
          on,
          cell,
          icon,
          iconVariant,
          typeName,
          cellIcon,
          type,
          editing,
          cells);
    }

    /** This column with {@code symbol} in muted text before the title in the header. */
    public Column icon(Symbol symbol) {
      return icon(symbol, Icon.Variant.MUTED);
    }

    /** This column with {@code symbol} before the title in the header; null for none. */
    public Column icon(Symbol symbol, Icon.Variant variant) {
      return new Column(
          title, width, align, mono, sortable, cell, symbol, variant, typeName, cellIcon, type,
          editing, cells);
    }

    /** This column with {@code name} after the title in the header; null for none. */
    public Column typeName(String name) {
      return new Column(
          title,
          width,
          align,
          mono,
          sortable,
          cell,
          icon,
          iconVariant,
          name,
          cellIcon,
          type,
          editing,
          cells);
    }

    /** This column with an icon in each cell for which {@code icons} gives one. */
    public Column cellIcon(IntFunction<CellIcon> icons) {
      return new Column(
          title,
          width,
          align,
          mono,
          sortable,
          cell,
          icon,
          iconVariant,
          typeName,
          icons,
          type,
          editing,
          cells);
    }

    /** This column with its cells editable in every row, when the grid edits ({@link Options#onCommit}). */
    public Column editable() {
      return editable(row -> true);
    }

    /** This column with the cells of the rows that {@code rows} accepts editable. */
    public Column editable(IntPredicate rows) {
      return with(new Editing(
          Objects.requireNonNull(rows, "rows"),
          editing.nullable(),
          editing.choices(),
          editing.editor()));
    }

    /**
     * This column allowing NULL: Control+0 and Delete set it, an empty editor of a column that is
     * not text means it, and a column of {@link DataType#BOOLEAN} goes through NULL as its third
     * state.
     */
    public Column nullable() {
      return with(new Editing(editing.rows(), true, editing.choices(), editing.editor()));
    }

    /** This column choosing its values from {@code choices}, as a {@link DataType#ENUM} does. */
    public Column choices(List<String> choices) {
      return with(new Editing(editing.rows(), editing.nullable(), choices, editing.editor()));
    }

    /** This column edited by an editor of the app's in place of the one of its type. */
    public Column editor(CellEditor editor) {
      return with(new Editing(
          editing.rows(),
          editing.nullable(),
          editing.choices(),
          Objects.requireNonNull(editor, "editor")));
    }

    private Column with(Editing editing) {
      return new Column(
          title,
          width,
          align,
          mono,
          sortable,
          cell,
          icon,
          iconVariant,
          typeName,
          cellIcon,
          type,
          editing,
          cells);
    }
  }

  /**
   * How the cells of a column are edited, once the grid edits ({@link Options#onCommit}). The
   * editor follows the column's {@link DataType}: text in a field over the cell, which grows up to
   * the grid's edge for a long value and opens as a larger field of several lines for one with
   * line breaks or with Alt+Enter; {@link DataType#INTEGER} and {@link DataType#DECIMAL} take
   * only what can become such a number; {@link DataType#BOOLEAN} has no editor, as Space, Enter,
   * F2 and a double press switch it in the cell; {@link DataType#ENUM}, or any column with
   * choices, a list of the choices that typing goes through; {@link DataType#DATE}, {@link
   * DataType#TIME} and {@link DataType#DATE_TIME} text in ISO 8601's form, such as {@code
   * 2026-11-13 14:30}, checked before it is handed on; every other type text.
   *
   * @param rows the rows whose cells are editable; null for none, which is not editable at all
   * @param nullable whether NULL is a value of the column
   * @param choices what an enumeration's cells choose from; empty for free input
   * @param editor the app's own editor, which replaces the one of the type; null for none
   */
  public record Editing(
      IntPredicate rows, boolean nullable, List<String> choices, CellEditor editor) {

    /** Not editable. */
    public static final Editing NONE = new Editing(null, false, List.of(), null);

    public Editing {
      choices = List.copyOf(choices);
    }
  }

  /**
   * An editor of the app's for a column's cells ({@link Column#editor}): it builds the element
   * that the grid lays over the cell, inside the editor's frame, from the value being edited, and
   * hands each new value to {@code onChange}. The grid gives the focus to the element it returns,
   * which is therefore focusable or a text field, and keeps the keys that end the editing: Enter,
   * Tab and Escape, which the element lets through.
   */
  @FunctionalInterface
  public interface CellEditor {

    /**
     * @param value what is being edited, null for NULL
     * @param onChange told each new value, null for NULL
     */
    El editor(String value, Consumer<String> onChange);
  }

  /**
   * Told the value of an edited cell, which the grid has checked for its column's type: the
   * app stores it, or refuses it with a message, which the grid shows under the editor, which
   * stays open; null accepts it.
   */
  @FunctionalInterface
  public interface Commit {

    /**
     * @param row the row, from 0; the row after the last one when the grid appends a row ({@link
     *     Options#appendRow}), which the grid has asked the app to add first
     * @param value the new value as text, null for NULL
     * @return null to accept it, else what is wrong with it
     */
    String commit(int row, int column, String value);
  }

  /** A cell and its new value, null for NULL, as pasting, clearing and Set NULL report them. */
  public record CellValue(int row, int column, String value) {}

  /** What a row or a cell holds compared with what the database has, as the app says. */
  public enum Status {
    UNCHANGED,
    /** Edited and not yet written. */
    CHANGED,
    /** Added and not yet written. */
    NEW,
    /** Marked to be deleted. */
    DELETED,
    /** Writing it failed. */
    ERROR
  }

  /**
   * A filter on a column, which the header's filter sets ({@link Options#filters}) and the app
   * applies, in SQL or with {@link #matches} to its own rows; the grid filters nothing itself.
   *
   * @param column the column, as the grid counts them
   * @param kind what it keeps
   * @param value the text of {@link Kind#CONTAINS}, {@link Kind#STARTS_WITH}, {@link
   *     Kind#EQUALS} and {@link Kind#MATCHES}, or the lower end of {@link Kind#BETWEEN}, empty
   *     for none
   * @param to the upper end of {@link Kind#BETWEEN}, empty for none; else empty
   * @param values what {@link Kind#ONE_OF} keeps; else empty
   */
  public record Filter(int column, Kind kind, String value, String to, List<String> values) {

    /** What a filter keeps. */
    public enum Kind {
      /** Text with the value in it, whatever the case. */
      CONTAINS,
      /** Text that begins with the value, whatever the case. */
      STARTS_WITH,
      /** The value, whatever the case. */
      EQUALS,
      /** Text in which the value, a regular expression, finds a match, whatever the case. */
      MATCHES,
      /** From the value to {@code to}, both included, as numbers where both are numbers. */
      BETWEEN,
      /** One of the values. */
      ONE_OF,
      IS_NULL,
      NOT_NULL
    }

    public Filter {
      Objects.requireNonNull(kind, "kind");
      value = value == null ? "" : value;
      to = to == null ? "" : to;
      values = List.copyOf(values);
    }

    /** A filter of text: one of the first four kinds, or NULL or not. */
    public static Filter of(int column, Kind kind, String value) {
      return new Filter(column, kind, value, "", List.of());
    }

    /** A filter of a range, either end empty for none. */
    public static Filter between(int column, String from, String to) {
      return new Filter(column, Kind.BETWEEN, from, to, List.of());
    }

    /** A filter of values. */
    public static Filter oneOf(int column, List<String> values) {
      return new Filter(column, Kind.ONE_OF, "", "", values);
    }

    /** Whether a cell's value, null for NULL, passes. */
    public boolean matches(String cell) {
      if (kind == Kind.IS_NULL || kind == Kind.NOT_NULL) {
        return cell == null == (kind == Kind.IS_NULL);
      }
      if (cell == null) {
        return false;
      }
      String low = cell.toLowerCase(java.util.Locale.ROOT);
      String want = value.toLowerCase(java.util.Locale.ROOT);
      return switch (kind) {
        case CONTAINS -> low.contains(want);
        case STARTS_WITH -> low.startsWith(want);
        case EQUALS -> low.equals(want);
        case MATCHES -> {
          try {
            yield java.util.regex.Pattern.compile(
                    value,
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                .matcher(cell)
                .find();
          } catch (java.util.regex.PatternSyntaxException e) {
            yield false;
          }
        }
        case BETWEEN ->
          (value.isBlank() || compare(cell, value) >= 0)
              && (to.isBlank() || compare(cell, to) <= 0);
        case ONE_OF -> values.contains(cell);
        default -> true;
      };
    }

    /**
     * Whether a typed value passes, null for NULL, read as {@code format} writes it: a range, and
     * equality of numbers, compare values where the filter's texts are values of the format and
     * the values are {@link Comparable} ({@code 10.0} equals {@code 10}, {@code 9} is below
     * {@code 10}); equality with a text that is no such value, blank included, passes nothing;
     * text kinds and others look at the value's text, as {@link #matches(String)} does.
     */
    public <V> boolean matches(V cell, Format<V> format) {
      if (cell == null || kind == Kind.IS_NULL || kind == Kind.NOT_NULL) {
        return matches(cell == null ? null : format.text(cell));
      }
      if (cell instanceof Comparable<?>
          && !(cell instanceof String) // text compares whatever the case, below
          && (kind == Kind.EQUALS || kind == Kind.BETWEEN)) {
        Format.Parsed<V> low = value.isBlank() ? null : format.parseText(value.strip());
        Format.Parsed<V> high = to.isBlank() ? null : format.parseText(to.strip());
        if (kind == Kind.EQUALS) { // needs a value of the format: no open end here
          return low != null && low.valid() && compareValues(cell, low.value()) == 0;
        }
        if ((low == null || low.valid()) && (high == null || high.valid())) {
          return (low == null || compareValues(cell, low.value()) >= 0)
              && (high == null || compareValues(cell, high.value()) <= 0);
        }
      }
      return matches(format.text(cell));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
      return ((Comparable) a).compareTo(b);
    }

    private static int compare(String a, String b) {
      try {
        return new java.math.BigDecimal(a.strip()).compareTo(new java.math.BigDecimal(b.strip()));
      } catch (NumberFormatException e) {
        return a.strip().compareTo(b.strip()); // dates and times of ISO 8601 sort as text
      }
    }

    /** What it keeps, in words, after the column's title: {@code status in paid, shipped}. */
    public String describe(String title) {
      return title
          + switch (kind) {
            case CONTAINS -> " contains " + value;
            case STARTS_WITH -> " starts with " + value;
            case EQUALS -> " = " + value;
            case MATCHES -> " matches " + value;
            case BETWEEN ->
              to.isBlank()
                  ? " ≥ " + value
                  : value.isBlank() ? " ≤ " + to : " " + value + " – " + to;
            case ONE_OF ->
              values.size() == 1 ? " = " + values.getFirst() : " in " + String.join(", ", values);
            case IS_NULL -> " is NULL";
            case NOT_NULL -> " is not NULL";
          };
    }
  }

  /**
   * Which of the app's columns a grid shows, and in which order, which the app keeps in its
   * state and the grid changes ({@link Options#columns}): the chooser of columns hides, shows and
   * moves them, the header's context menu hides one, and dragging a header cell along the header
   * moves its column. The app gives the grid all its columns; the grid shows those of the layout,
   * and tells the app of a column by its index among all of them in every handler but the
   * selection's, whose ranges are of the columns shown.
   */
  public static final class Layout {
    final List<String> titles;
    private final int[] order; // the app's columns in the order shown, hidden ones included
    private final BitSet hidden = new BitSet();
    final Popover.State chooser = new Popover.State();
    int from = -1; // where the chooser hangs: -1 on the app's button, else on that header cell
    String search = "";

    /** All the app's columns, by their titles, shown in their order. */
    public Layout(List<String> titles) {
      this.titles = List.copyOf(titles);
      this.order = new int[titles.size()];
      reset();
    }

    /** The columns shown, of all the app's, in the order shown. */
    public <T> List<T> shown(List<T> all) {
      if (all.size() != order.length) {
        throw new IllegalArgumentException(
            "The layout has " + order.length + " columns, the grid " + all.size());
      }
      List<T> shown = new ArrayList<>();
      for (int c : order) {
        if (!hidden.get(c)) {
          shown.add(all.get(c));
        }
      }
      return shown;
    }

    /** The indexes among all columns of those shown, in the order shown. */
    public int[] shownColumns() {
      return java.util.Arrays.stream(order).filter(c -> !hidden.get(c)).toArray();
    }

    public boolean isHidden(int column) {
      return hidden.get(column);
    }

    /** How many columns are shown. */
    public int shownCount() {
      return order.length - hidden.cardinality();
    }

    /**
     * Where {@code column} is among those shown ({@code full}, in the order shown), or, while it
     * is hidden, the shown column after it in the order, else the one before it; -1 for none.
     */
    int shownNear(int column, int[] full) {
      int place = 0;
      while (place < order.length && order[place] != column) {
        place++;
      }
      for (int step : new int[] {1, -1}) {
        for (int p = place; p >= 0 && p < order.length; p += step) {
          for (int i = 0; i < full.length; i++) {
            if (full[i] == order[p]) {
              return i;
            }
          }
        }
      }
      return -1;
    }

    /** Hides a column, unless it is the last one shown. */
    public void hide(int column) {
      if (shownCount() > 1) {
        hidden.set(column);
      }
    }

    public void show(int column) {
      hidden.clear(column);
    }

    /** Shows every column, in the order they have. */
    public void showAll() {
      hidden.clear();
    }

    /** Shows every column in the app's order. */
    public void reset() {
      hidden.clear();
      for (int c = 0; c < order.length; c++) {
        order[c] = c;
      }
    }

    /** Moves a column to just before another, or to the end with -1. */
    public void moveBefore(int column, int before) {
      if (column == before) {
        return;
      }
      List<Integer> list = new ArrayList<>();
      for (int c : order) {
        if (c != column) {
          list.add(c);
        }
      }
      int at = before < 0 ? list.size() : list.indexOf(before);
      list.add(at < 0 ? list.size() : at, column);
      for (int i = 0; i < order.length; i++) {
        order[i] = list.get(i);
      }
    }

    /** Moves a column one place up or down the order, past hidden ones too. */
    void step(int column, int by) {
      int at = 0;
      while (order[at] != column) {
        at++;
      }
      int to = at + by;
      if (to >= 0 && to < order.length) {
        order[at] = order[to];
        order[to] = column;
      }
    }

    /** The order of all columns, hidden ones included. */
    public List<Integer> order() {
      return java.util.Arrays.stream(order).boxed().toList();
    }

    /** Opens or closes the chooser of columns on the button of {@link DataGrid#columnsButton}. */
    public void toggleChooser() {
      if (chooser.isOpen() && from < 0) {
        chooser.close();
      } else {
        from = -1;
        search = "";
        chooser.open();
      }
    }
  }

  /** Told a cell, says its {@link Status}. */
  @FunctionalInterface
  public interface CellStatus {
    Status status(int row, int column);
  }

  /** Told a cell, says what is wrong with it, or null for nothing. */
  @FunctionalInterface
  public interface CellMessage {
    String message(int row, int column);
  }

  /**
   * The icon of a cell: before its text, or alone in its place, in one of {@link Icon}'s colours,
   * with a tooltip of its own if it needs one. It stands where the column puts its text, at the
   * left or the right, and keeps its size where the text gives way. A cell whose icon stands alone
   * keeps its text as its label, which a tree dump shows ({@code label="true"}), its tooltip when
   * it has none of its own, and what a copy takes.
   *
   * <p>The colours stand out 3:1 from the rows, the stripes and the selection in the default
   * themes, except {@link Icon.Variant#DISABLED}, which looks unavailable on purpose.
   *
   * @param symbol what it shows
   * @param variant its colour
   * @param alone whether it stands in place of the text
   * @param tooltip shown after the theme's delay with the pointer on the cell, before the text if
   *     that is cut short; null for none
   */
  public record CellIcon(Symbol symbol, Icon.Variant variant, boolean alone, String tooltip) {

    public CellIcon {
      Objects.requireNonNull(symbol, "symbol");
      Objects.requireNonNull(variant, "variant");
    }

    /** The icon before the text, such as a coloured mark before a status or a link before a key. */
    public static CellIcon before(Symbol symbol, Icon.Variant variant) {
      return new CellIcon(symbol, variant, false, null);
    }

    /** The icon in place of the text, such as a check for {@code true}. */
    public static CellIcon instead(Symbol symbol, Icon.Variant variant) {
      return new CellIcon(symbol, variant, true, null);
    }

    /** This icon with a tooltip. */
    public CellIcon tooltip(String text) {
      return new CellIcon(symbol, variant, alone, text);
    }
  }

  /**
   * The common kinds of data in a database, each with the icon of its column's header, the
   * alignment and whether the cells use the monospaced font. An app maps the types its driver
   * reports to these once, and makes its columns with {@link Column#Column(String, float,
   * DataType, IntFunction)}; the type's own name goes after the title ({@link Column#typeName}).
   */
  public enum DataType {
    INTEGER(Symbol.NUMBER_SYMBOL, Align.RIGHT, true),
    DECIMAL(Symbol.CALCULATOR, Align.RIGHT, true),
    TEXT(Symbol.TEXT_CASE_TITLE, Align.LEFT, false),
    BOOLEAN(Symbol.CHECKBOX_CHECKED, Align.LEFT, false),
    DATE(Symbol.CALENDAR_LTR, Align.LEFT, true),
    TIME(Symbol.CLOCK, Align.LEFT, true),
    /** A date with a time, with or without its time zone. */
    DATE_TIME(Symbol.CALENDAR_CLOCK, Align.LEFT, true),
    INTERVAL(Symbol.TIMER, Align.LEFT, true),
    UUID(Symbol.FINGERPRINT, Align.LEFT, true),
    /** JSON, and other documents of structured text such as XML. */
    JSON(Symbol.BRACES, Align.LEFT, true),
    /** Bytes, as a {@code bytea} or a {@code BLOB}. */
    BINARY(Symbol.DOCUMENT_DATA, Align.LEFT, true),
    ARRAY(Symbol.TEXT_BULLET_LIST_LTR, Align.LEFT, false),
    /** A value from a fixed set, such as a status. */
    ENUM(Symbol.TAG, Align.LEFT, false),
    /** Geometry and geography. */
    GEOMETRY(Symbol.LOCATION, Align.LEFT, true),
    /** Anything else: no icon. */
    OTHER(null, Align.LEFT, false);

    private final Symbol symbol;
    private final Align align;
    private final boolean mono;

    DataType(Symbol symbol, Align align, boolean mono) {
      this.symbol = symbol;
      this.align = align;
      this.mono = mono;
    }

    /** The icon of the column's header; null for {@link #OTHER}. */
    public Symbol symbol() {
      return symbol;
    }

    public Align align() {
      return align;
    }

    public boolean mono() {
      return mono;
    }
  }

  /** A column the rows are sorted by, and the direction; the first key sorts first. */
  public record SortKey(int column, boolean descending) {}

  /** Told the new width of a column while the user drags its edge or fits it. */
  @FunctionalInterface
  public interface Resize {
    void resize(int column, float width);
  }

  /** Told a cell: its row and its column, from 0. */
  @FunctionalInterface
  public interface CellHandler {
    void accept(int row, int column);
  }

  /**
   * A rectangle of selected cells, its rows and columns from 0 and both ends included; one of
   * whole rows reaches {@link #ALL} columns, whatever the columns are.
   */
  public record Range(int top, int left, int bottom, int right) {

    /** The {@link #right} of a range of whole rows. */
    public static final int ALL = Integer.MAX_VALUE;

    public Range {
      if (top < 0 || left < 0 || bottom < top || right < left) {
        throw new IllegalArgumentException(
            "A range has 0 <= top <= bottom and 0 <= left <= right, was " + top + "," + left
                + " to " + bottom + "," + right);
      }
    }

    /** Whether this range holds whole rows. */
    public boolean wholeRows() {
      return left == 0 && right == ALL;
    }

    public boolean contains(int row, int column) {
      return row >= top && row <= bottom && column >= left && column <= right;
    }
  }

  /**
   * A copy the grid leaves to the app: more cells than {@link Options#copyLimit}, or rows that
   * are not loaded. The app may build the text with {@link #copyText}, on a thread of its own, or
   * fetch it from the database.
   *
   * @param ranges the selection
   * @param headers whether the titles come first
   * @param cells the number of cells, the columns between the selected ones included
   */
  public record Copy(List<Range> ranges, boolean headers, long cells) {}

  /**
   * What is selected in a grid, which the app keeps in its state and the grid changes in its
   * handlers: the cursor, the cell that the keys move and editing starts at; the anchor, where
   * Shift extends from; and ranges, which a million selected rows make one of. In {@link
   * Mode#ROWS} every range holds whole rows. The methods change it as the grid's input does, so
   * that an app can select from its own handlers too.
   *
   * <p>It also keeps where the grid is scrolled to ({@link ScrollState}), so that a grid built
   * with it comes back to the same rows and columns after it was out of the tree, as the grid of
   * a tab that is not active, or in another window, as a tab torn off. One selection belongs to
   * one grid at a time.
   */
  public static final class Selection {
    final ScrollState rowsScroll = new ScrollState(); // the rows, up and down
    final ScrollState gridScroll = new ScrollState(); // header and rows, sideways
    private final Mode mode;
    private int row = -1;
    private int column = -1;
    private int anchorRow = -1;
    private int anchorColumn = -1;
    private final List<Range> ranges = new ArrayList<>();
    // What the grid scrolls into view: a count of the moves to show, and whether the last one
    // resized the column edgeColumn, whose edge shows, rather than moved the cursor.
    int moves;
    int edgeColumn = -1;
    boolean start; // the keys put the cursor on a frozen column: the rest scrolls to its start
    // The cell being edited, -1 for none, and what its editor holds.
    int editRow = -1;
    int editColumn = -1;
    // With rows told apart by keys (Options.changes): the key of the edited row, which the
    // editor follows wherever the rows move, or APPEND for the row to append.
    boolean editKeyed;
    long editKey;
    static final long APPEND = Long.MIN_VALUE;
    String draft; // null for NULL
    String original; // the value the editing began with
    String error; // what the app or the type said is wrong with the draft, null for nothing
    boolean multiline; // in the larger field of several lines
    int edits; // the editors opened so far: each new one takes the focus by this key
    float gridWidth; // the grid's width, as layout last told it, for an editor that grows
    Runnable handOn; // the last grid built with it that edits: hands the draft on (commitEdit)
    FieldState field = new FieldState(); // the open editor's, shared by its field and its lines
    final Menu.State choices = new Menu.State(); // an enumeration's list
    final FilterList.State search = new FilterList.State(); // a long enumeration's filter
    // The filter being set in its popover: its column, -1 for none, and what it holds.
    int filterColumn = -1;
    final Popover.State filtering = new Popover.State();
    Filter.Kind filterKind;
    String filterValue = "";
    String filterTo = "";
    final java.util.Set<String> filterValues = new java.util.LinkedHashSet<>();
    final Menu.State filterKinds = new Menu.State();
    // The header: its context menu and the column it opened on, and a column dragged along it
    // with where it would go, -1 for none.
    final Menu.State headerMenu = new Menu.State();
    int headerColumn = -1;
    int dragColumn = -1;
    int dropAt = -1;
    int[] shown; // the app's column of each one shown, as the last build had them

    public Selection(Mode mode) {
      this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Mode mode() {
      return mode;
    }

    /** The cursor's row, -1 for none. */
    public int row() {
      return row;
    }

    /** The cursor's column, -1 for none; in {@link Mode#ROWS} the column last pressed. */
    public int column() {
      return column;
    }

    public int anchorRow() {
      return anchorRow;
    }

    public int anchorColumn() {
      return anchorColumn;
    }

    /** The ranges, in the order they were selected, which may overlap. */
    public List<Range> ranges() {
      return List.copyOf(ranges);
    }

    public boolean isEmpty() {
      return ranges.isEmpty();
    }

    public boolean contains(int row, int column) {
      for (Range range : ranges) {
        if (range.contains(row, column)) {
          return true;
        }
      }
      return false;
    }

    /** Whether a range of whole rows holds this row. */
    public boolean isRowSelected(int row) {
      for (Range range : ranges) {
        if (range.wholeRows() && row >= range.top() && row <= range.bottom()) {
          return true;
        }
      }
      return false;
    }

    /** The number of rows with a selected cell, without counting a row twice. */
    public long rowCount() {
      long count = 0;
      for (int[] span : rowSpans(ranges)) {
        count += span[1] - span[0] + 1L;
      }
      return count;
    }

    public void clear() {
      row = column = anchorRow = anchorColumn = -1;
      ranges.clear();
    }

    /** Whether an editor is open over a cell ({@link Options#onCommit}). */
    public boolean isEditing() {
      return editRow >= 0;
    }

    /** The row of the cell being edited, -1 for none. */
    public int editingRow() {
      return editRow;
    }

    /** The column of the cell being edited, -1 for none. */
    public int editingColumn() {
      return editColumn;
    }

    /** What the open editor holds, null for NULL or when none is open. */
    public String draft() {
      return draft;
    }

    /**
     * Hands the open editor's value on as a press outside it does, through {@link
     * Options#onCommit}, and closes it: what an app calls before it saves, runs or closes, so
     * that what was typed is not lost. A value that the column's type or the app refuses keeps
     * the editor open, {@code invalid}, with the message below it, as after a press outside; a
     * list of choices closes without a change, as a press outside leaves it.
     *
     * @return whether no editor is open any more: true without one or when the value was taken,
     *     false when it was refused, and the app should not go on with what it was about to do
     */
    public boolean commitEdit() {
      if (!isEditing()) {
        return true;
      }
      if (handOn == null) {
        return false; // no grid built with it edits: nothing can take the value
      }
      handOn.run();
      return !isEditing();
    }

    /**
     * Closes the editor without handing on what it holds, as Escape does: also what an app calls
     * when it reorders or replaces the rows of a grid that edits through {@link
     * Options#onCommit(Commit)} while an editor is open, as its row would then be another.
     */
    public void cancelEdit() {
      editRow = editColumn = -1;
      editKeyed = false;
      draft = original = error = null;
      multiline = false;
      choices.close();
    }

    /**
     * Clears the selection and puts the grid back at its first row and column at the next build,
     * as for a new result.
     */
    public void reset() {
      clear();
      rowsScroll.scrollTo(0, 0);
      gridScroll.scrollTo(0, 0);
    }

    /** Selects this cell alone, or its row in {@link Mode#ROWS}, with cursor and anchor on it. */
    public void select(int row, int column) {
      select(row, column, false);
    }

    /** Moves the cursor here and replaces the last range by the one from the anchor to it. */
    public void extend(int row, int column) {
      extend(row, column, !ranges.isEmpty() && ranges.getLast().wholeRows());
    }

    /** Adds this cell, or row, with cursor and anchor on it; removes it where it is selected. */
    public void toggle(int row, int column) {
      toggle(row, column, false);
    }

    /** Selects every row, with the anchor at the first cell; the cursor stays. */
    public void selectAll(int rowCount) {
      ranges.clear();
      if (rowCount > 0) {
        ranges.add(new Range(0, 0, rowCount - 1, Range.ALL));
        anchorRow = 0;
        anchorColumn = 0;
      }
    }

    void select(int row, int column, boolean rows) {
      this.row = anchorRow = row;
      this.column = anchorColumn = column;
      ranges.clear();
      ranges.add(range(row, column, row, column, rows));
    }

    void extend(int row, int column, boolean rows) {
      if (ranges.isEmpty() || anchorRow < 0) {
        select(row, column, rows);
        return;
      }
      this.row = row;
      this.column = column;
      ranges.set(ranges.size() - 1, range(anchorRow, anchorColumn, row, column, rows));
    }

    void toggle(int row, int column, boolean rows) {
      boolean whole = rows || mode == Mode.ROWS;
      boolean on = whole ? isRowSelected(row) : contains(row, column);
      this.row = anchorRow = row;
      this.column = anchorColumn = column;
      if (!on) {
        ranges.add(range(row, column, row, column, rows));
        return;
      }
      List<Range> left = new ArrayList<>();
      for (Range r : ranges) {
        boolean hit = whole ? row >= r.top() && row <= r.bottom() : r.contains(row, column);
        if (!hit) {
          left.add(r);
          continue;
        }
        if (r.top() < row) {
          left.add(new Range(r.top(), r.left(), row - 1, r.right()));
        }
        if (!whole && r.left() < column) {
          left.add(new Range(row, r.left(), row, column - 1));
        }
        if (!whole && r.right() > column) {
          left.add(new Range(row, column + 1, row, r.right()));
        }
        if (r.bottom() > row) {
          left.add(new Range(row + 1, r.left(), r.bottom(), r.right()));
        }
      }
      ranges.clear();
      ranges.addAll(left);
    }

    private Range range(int row1, int column1, int row2, int column2, boolean rows) {
      boolean whole = rows || mode == Mode.ROWS;
      return new Range(
          Math.min(row1, row2),
          whole ? 0 : Math.min(column1, column2),
          Math.max(row1, row2),
          whole ? Range.ALL : Math.max(column1, column2));
    }

    /**
     * Carries what holds columns shown, cursor, anchor, ranges, the editor and the popovers, over
     * to the same columns of the app's where the columns shown have changed, as when a layout hid
     * or moved one ({@link Options#columns}). What was on a column that is no longer shown goes:
     * the cursor, the anchor, a range's part, an editor without handing anything on.
     *
     * @param full the app's column of each one shown now, null without a layout
     */
    void showing(int[] full) {
      int[] was = shown;
      shown = full;
      if (was == null || full == null || java.util.Arrays.equals(was, full)) {
        return;
      }
      java.util.Map<Integer, Integer> now = new java.util.HashMap<>();
      for (int i = 0; i < full.length; i++) {
        now.put(full[i], i);
      }
      java.util.function.IntUnaryOperator to =
          c -> c < 0 || c >= was.length ? -1 : now.getOrDefault(was[c], -1);
      if (column >= 0 && to.applyAsInt(column) < 0) {
        row = column = -1;
      } else {
        column = to.applyAsInt(column);
      }
      if (anchorColumn >= 0 && to.applyAsInt(anchorColumn) < 0) {
        anchorRow = anchorColumn = -1;
      } else {
        anchorColumn = to.applyAsInt(anchorColumn);
      }
      List<Range> carried = new ArrayList<>();
      for (Range r : ranges) {
        if (r.wholeRows()) {
          carried.add(r);
          continue;
        }
        BitSet columns = new BitSet();
        for (int c = r.left(); c <= Math.min(r.right(), was.length - 1); c++) {
          if (to.applyAsInt(c) >= 0) {
            columns.set(to.applyAsInt(c));
          }
        }
        for (int c = columns.nextSetBit(0); c >= 0; c = columns.nextSetBit(c)) {
          int end = columns.nextClearBit(c);
          carried.add(new Range(r.top(), c, r.bottom(), end - 1));
          c = end;
        }
      }
      ranges.clear();
      ranges.addAll(carried);
      if (isEditing()) {
        editColumn = to.applyAsInt(editColumn);
        if (editColumn < 0) {
          cancelEdit();
        }
      }
      filterColumn = to.applyAsInt(filterColumn);
      if (filterColumn < 0) {
        filtering.close();
      }
      headerColumn = to.applyAsInt(headerColumn);
      if (headerColumn < 0) {
        headerMenu.close();
      }
      edgeColumn = to.applyAsInt(edgeColumn);
      dragColumn = -1;
    }

    /** What the grid's views compare to tell the app of a change only. */
    private List<Object> snapshot() {
      return List.of(row, column, anchorRow, anchorColumn, List.copyOf(ranges));
    }

    @Override
    public String toString() {
      return "Selection " + mode + " cursor " + row + "," + column + " anchor " + anchorRow + ","
          + anchorColumn + " " + ranges;
    }
  }

  /**
   * What a grid does besides showing rows: its selection, sorting, resizing, frozen columns,
   * copying, context menu, loading and editing. Each is off until a call turns it on; the view
   * makes the options anew with the grid.
   */
  public static final class Options {
    Selection selection;
    Runnable onSelect;
    List<SortKey> sort = List.of();
    Consumer<List<SortKey>> onSort;
    Resize onResize;
    boolean rowNumbers;
    int frozen;
    Clipboard clipboard;
    String nullText = "";
    long copyLimit = 100_000;
    Consumer<Copy> onLargeCopy;
    Menu.State menu;
    List<Menu.Item> menuItems = List.of();
    IntPredicate loaded;
    El.RowsHandler onRows;
    CellHandler onEdit;
    Commit onCommit;
    Consumer<List<CellValue>> onPaste;
    IntConsumer onInsertRow;
    Consumer<List<Range>> onDeleteRows;
    boolean appendRow;
    IntFunction<Status> rowStatus;
    CellStatus cellStatus;
    CellMessage cellError;
    Runnable onUndo;
    Runnable onRedo;
    List<Filter> filters = List.of();
    Consumer<List<Filter>> onFilter;
    IntPredicate filterable = c -> true;
    Layout layout;
    IntConsumer onFreeze;
    // With changes: the key of the row shown at a place, and the place of a key, -1 for none.
    java.util.function.IntToLongFunction rowKey;
    java.util.function.LongToIntFunction rowPlace;
    int[] full; // with a layout: the index among all columns of each shown one
    // Wires onCommit and onPaste to a table's columns and its number of rows.
    java.util.function.ObjIntConsumer<List<Column>> table;

    private Options() {}

    /**
     * Lets the user select: the grid takes the focus and its keys and changes {@code selection},
     * which the app keeps. Without it the grid shows the rows only.
     */
    public Options select(Selection selection) {
      this.selection = selection;
      return this;
    }

    /** Runs after the grid changed the selection, and only then. */
    public Options onSelect(Runnable handler) {
      this.onSelect = handler;
      return this;
    }

    /**
     * Shows the sort keys in the header, an arrow per key and their order when there are more,
     * and lets a press on a {@link Column#sortable} column's header change them: ascending,
     * descending, unsorted, in turn; alone, or with Shift added to the others.
     *
     * @param onSort told the new keys; the app sorts and keeps them
     */
    public Options sort(List<SortKey> keys, Consumer<List<SortKey>> onSort) {
      this.sort = List.copyOf(keys);
      this.onSort = onSort;
      return this;
    }

    /**
     * Lets the user resize columns by their edges in the header: told the new width, no less than
     * the theme's minimum, while an edge is dragged, the old width again if the drag is
     * cancelled, and the width that fits the rows that exist after a double press on the edge.
     */
    public Options onResize(Resize handler) {
      this.onResize = handler;
      return this;
    }

    /** Shows each row's number, from 1, at the left, where it stays while the rest scrolls. */
    public Options rowNumbers() {
      this.rowNumbers = true;
      return this;
    }

    /** Keeps the first {@code columns} columns at the left while the others scroll sideways. */
    public Options frozen(int columns) {
      if (columns < 0) {
        throw new IllegalArgumentException("frozen columns must be 0 or more, was " + columns);
      }
      this.frozen = columns;
      return this;
    }

    /**
     * Lets Control+C, Control+Insert and the context menu copy the selection onto this clipboard,
     * usually the app's ({@link com.rethinkj.ui.App#clipboard}), which a window replaces with the
     * system's when it starts: read it in the view.
     */
    public Options copy(Clipboard clipboard) {
      this.clipboard = clipboard;
      return this;
    }

    /** What a copied NULL is; "" unless set, as spreadsheets read an empty cell. */
    public Options nullText(String text) {
      this.nullText = Objects.requireNonNull(text, "text");
      return this;
    }

    /**
     * Copies at most {@code cells} cells itself, 100 000 unless set, and leaves a larger copy to
     * {@code handler}; without one, a larger copy leaves the clipboard as it is.
     */
    public Options copyLimit(long cells, Consumer<Copy> handler) {
      this.copyLimit = cells;
      this.onLargeCopy = handler;
      return this;
    }

    /**
     * Opens a {@link Menu} where the secondary button is pressed on a cell, which first selects
     * the cell unless it is selected, and with the Menu key or Shift+F10: Copy and Copy with
     * headers when the grid copies, and then {@code items}.
     */
    public Options contextMenu(Menu.State state, List<Menu.Item> items) {
      this.menu = state;
      this.menuItems = List.copyOf(items);
      return this;
    }

    /**
     * For rows that the app fetches in pages: a row that is not {@code loaded} shows a muted
     * placeholder in each cell and asks no cell function, and {@code onRows} is told the rows the
     * grid has built whenever they change ({@link El#onRowsBuilt}), so that the app fetches them.
     * The app changes its state when they arrive, and the next frame shows them.
     */
    public Options loading(IntPredicate loaded, El.RowsHandler onRows) {
      this.loaded = loaded;
      this.onRows = onRows;
      return this;
    }

    /**
     * Asks the app to edit the cursor's cell on Enter, F2 or a double press: the app lays a text
     * field over the cell and ends the editing in the field's own key handler.
     */
    public Options onEdit(CellHandler handler) {
      this.onEdit = handler;
      return this;
    }

    /**
     * Lets the user edit the cells of {@link Column#editable} columns in an editor the grid lays
     * over the cell ({@link Editing}): Enter, F2, a double press or typing open it, typing in
     * place of the value; Enter hands the value on and goes a row down, Shift+Enter up, Tab a
     * column on and Shift+Tab back, to the next or the previous row at the ends; Escape closes
     * it without handing anything on, and a press elsewhere hands it on. Space switches a
     * boolean in the cell. Control+0 sets NULL in a {@link Column#nullable} column, in the
     * editor as on the selection. The grid hands the app the new value through {@code handler},
     * which stores it or refuses it with a message; the cells keep showing what the column's cell
     * function returns. A cell for which {@link #onEdit} asks is edited by the app as before.
     *
     * <p>The editor stays on the row with its index: the app must not reorder or replace the rows
     * while it is open, or must close it first ({@link Selection#cancelEdit}) or hand its value
     * on ({@link Selection#commitEdit}), else the value goes to the row that has the index then.
     * Through {@link #changes}, the editor follows its row by the row's key instead, and closes
     * without handing anything on when the row is gone.
     */
    public Options onCommit(Commit handler) {
      this.onCommit = handler;
      this.table = null;
      return this;
    }

    /**
     * Lets the grid edit the cells of a {@link GridTable}'s columns that write ({@link
     * GridTable#column(String, float, Format, java.util.function.Function,
     * java.util.function.BiFunction) a wither} or {@link GridTable#column(String, float, Format,
     * java.util.function.Function, java.util.function.BiConsumer) a setter}), and paste into them,
     * clear them and set them NULL ({@link #onPaste}): the grid checks the text with the column's
     * format, the column writes the value into the row, and {@code handler} is told the row,
     * which it stores or refuses with a message (a setter's value is then set back; a pasted row's
     * refusal is not shown). The value goes through the same editors as {@link
     * #onCommit(Commit)}'s.
     */
    public <T> Options onCommit(GridTable<T> table, GridTable.RowCommit<T> handler) {
      Objects.requireNonNull(table, "table");
      Objects.requireNonNull(handler, "handler");
      this.table = (columns, count) -> {
        onCommit = table.commit(columns, handler);
        onPaste = table.paste(columns, count, handler);
      };
      this.onCommit = (row, column, value) -> null; // turns editing on; replaced as the grid builds
      return this;
    }

    /**
     * Lets Control+V and Shift+Insert paste tab-separated text, as {@link #copyText} writes it and
     * spreadsheets copy it ({@link #parseText}), from the cursor's cell on, and Delete clear the
     * selected cells and Control+0 set them NULL: told the editable cells and their new values as
     * one change, at most {@link #copyLimit} cells. A single value pastes into every selected cell.
     * Clearing gives NULL in a {@link Column#nullable} column and empty text in another column of
     * text. Pasting reads {@link #copy}'s clipboard, and a field equal to a {@link #nullText} that
     * is not empty is NULL.
     */
    public Options onPaste(Consumer<List<CellValue>> handler) {
      this.onPaste = handler;
      return this;
    }

    /**
     * Lets Insert and Control+Plus ask the app for a new row after the cursor's, told its index
     * (-1 for a new first row); the grid then puts the cursor on the row after it.
     */
    public Options onInsertRow(IntConsumer handler) {
      this.onInsertRow = handler;
      return this;
    }

    /**
     * Lets Control+Minus and Control+Delete mark the rows with a selected cell, or the cursor's,
     * to be deleted: told them as ranges of whole rows. The app marks them ({@link #status}) or
     * removes them.
     */
    public Options onDeleteRows(Consumer<List<Range>> handler) {
      this.onDeleteRows = handler;
      return this;
    }

    /**
     * Shows an empty row after the last one, marked {@code *} in the row numbers, whose cells
     * are edited as any: the grid asks for the row ({@link #onInsertRow}, with the last row's
     * index) before it hands on the first value.
     */
    public Options appendRow() {
      this.appendRow = true;
      return this;
    }

    /**
     * Shows what the rows and cells hold against the database: a mark at the start of each row
     * that is not {@link Status#UNCHANGED}, a tint over it or over its cells, a {@code +} for the
     * number of a new row, and the text of a deleted row muted and struck through. In a tree dump a row and
     * a cell say it as {@code value="changed"}, {@code "new"}, {@code "deleted"} or {@code
     * "error"}, and a failed cell is {@code invalid}.
     */
    public Options status(IntFunction<Status> rows, CellStatus cells) {
      this.rowStatus = Objects.requireNonNull(rows, "rows");
      this.cellStatus = Objects.requireNonNull(cells, "cells");
      return this;
    }

    /**
     * Shows what is wrong with a failed cell ({@link Status#ERROR}) in its tooltip, before
     * anything else the tooltip says: the message of a refused write, say.
     */
    public Options errors(CellMessage messages) {
      this.cellError = Objects.requireNonNull(messages, "messages");
      return this;
    }

    /**
     * Shows a filter in each header cell, a funnel under the pointer and, in the accent's edge,
     * where {@code filters} has one for the column, which opens a popover to set it, as Alt+Arrow
     * Down does for the cursor's column: text with how it matches, a range for numbers, dates and
     * times, the values of a boolean or an enumeration to keep, or NULL or not. The grid filters
     * nothing: {@code onFilter} is told the new filters, which the app applies and gives back.
     * Needs {@link #select}. {@link DataGrid#filterBar} shows them as badges to remove.
     */
    public Options filters(List<Filter> filters, Consumer<List<Filter>> onFilter) {
      this.filters = List.copyOf(filters);
      this.onFilter = onFilter;
      return this;
    }

    /**
     * Shows the columns of {@code layout}, of all the app's columns the grid gets, and lets the
     * header change it: its context menu hides a column and opens the chooser of columns
     * (Columns…), and dragging a header cell along the header moves its column, with a line where
     * it would go. Every handler is then told columns by their index among all of them, and the
     * sort keys and filters are of those indexes too; only the selection's ranges are of the
     * columns shown. A column's sort key goes away while it is hidden.
     */
    public Options columns(Layout layout) {
      this.layout = layout;
      return this;
    }

    /**
     * Lets the header's context menu freeze the columns up to the one it opened on, and unfreeze
     * them: told how many of the columns shown to keep at the left, which the app passes to
     * {@link #frozen}.
     */
    public Options onFreeze(IntConsumer handler) {
      this.onFreeze = handler;
      return this;
    }

    /** With a layout, these options in the indexes of the columns shown, their handlers mapped. */
    void showOnly(int[] full, int all) {
      this.full = full;
      int[] shown = new int[all];
      java.util.Arrays.fill(shown, -1);
      for (int i = 0; i < full.length; i++) {
        shown[full[i]] = i;
      }
      List<SortKey> keys = new ArrayList<>();
      for (SortKey k : sort) {
        if (k.column() < all && shown[k.column()] >= 0) {
          keys.add(new SortKey(shown[k.column()], k.descending()));
        }
      }
      sort = List.copyOf(keys);
      if (onSort != null) {
        Consumer<List<SortKey>> to = onSort;
        onSort = next -> to.accept(next.stream()
            .map(k -> new SortKey(full[k.column()], k.descending()))
            .toList());
      }
      if (onResize != null) {
        Resize to = onResize;
        onResize = (c, w) -> to.resize(full[c], w);
      }
      if (onEdit != null) {
        CellHandler to = onEdit;
        onEdit = (r, c) -> to.accept(r, full[c]);
      }
      if (onCommit != null) {
        Commit to = onCommit;
        onCommit = (r, c, v) -> to.commit(r, full[c], v);
      }
      if (onPaste != null) {
        Consumer<List<CellValue>> to = onPaste;
        onPaste = cells -> to.accept(cells.stream()
            .map(v -> new CellValue(v.row(), full[v.column()], v.value()))
            .toList());
      }
      if (cellStatus != null) {
        CellStatus to = cellStatus;
        cellStatus = (r, c) -> to.status(r, full[c]);
      }
      if (cellError != null) {
        CellMessage to = cellError;
        cellError = (r, c) -> to.message(r, full[c]);
      }
      IntPredicate canFilter = filterable;
      filterable = c -> canFilter.test(full[c]);
      List<Filter> hiddenFilters = new ArrayList<>();
      List<Filter> shownFilters = new ArrayList<>();
      for (Filter f : filters) {
        if (f.column() < all && shown[f.column()] >= 0) {
          shownFilters.add(new Filter(shown[f.column()], f.kind(), f.value(), f.to(), f.values()));
        } else {
          hiddenFilters.add(f);
        }
      }
      filters = List.copyOf(shownFilters);
      if (onFilter != null) {
        Consumer<List<Filter>> to = onFilter;
        onFilter = next -> {
          List<Filter> mapped = new ArrayList<>(hiddenFilters);
          for (Filter f : next) {
            mapped.add(new Filter(full[f.column()], f.kind(), f.value(), f.to(), f.values()));
          }
          to.accept(List.copyOf(mapped));
        };
      }
    }

    /** Offers filters only on the columns {@code columns} accepts; all unless set. */
    public Options filterable(IntPredicate columns) {
      this.filterable = Objects.requireNonNull(columns, "columns");
      return this;
    }

    /** Lets Control+Z undo and Control+Y and Control+Shift+Z redo while no editor is open. */
    public Options history(Runnable undo, Runnable redo) {
      this.onUndo = undo;
      this.onRedo = redo;
      return this;
    }

    /**
     * Edits through {@code changes}, which keeps the changes until the app writes them: what
     * {@link #onCommit}, {@link #onPaste}, {@link #onInsertRow}, {@link #onDeleteRows}, {@link
     * #status}, {@link #errors} and {@link #history} do, each of which a later call replaces. The columns take
     * their cells from {@link GridChanges#cell(int)} and the grid its rows from {@link
     * GridChanges#rowCount()}. An open editor follows its row by the row's key ({@link
     * GridChanges.Rows#key}) when the app sorts, filters or replaces the rows, and closes without
     * handing anything on when its row is no longer shown.
     */
    public Options changes(GridChanges changes) {
      onCommit((row, column, value) -> {
        changes.set(row, column, value);
        return null;
      });
      onPaste(changes::set);
      onInsertRow(changes::insert);
      onDeleteRows(changes::delete);
      rowKey = changes::key;
      rowPlace = changes::rowOf;
      status(changes::rowStatus, changes::cellStatus);
      errors(changes::errorText);
      return history(changes::undo, changes::redo);
    }
  }

  /**
   * The rows of a grid as objects of the app's, records or POJOs, whose columns are typed values
   * of them ({@link GridTable}).
   *
   * @param rows the row with an index, or null while it is not loaded; asked while a frame runs
   */
  public static <T> GridTable<T> table(IntFunction<T> rows) {
    return GridTable.of(rows);
  }

  /** Options with nothing turned on. */
  public static Options options() {
    return new Options();
  }

  /** What a cell shows for a line break in its text, which shows on one line: {@code ⏎}. */
  public static final String LINE_BREAK = "⏎";

  /** What a cell shows for a tab in its text: {@code ⇥}. */
  public static final String TAB = "⇥";

  private DataGrid() {}

  /** What the parts of one build of the grid share. */
  static final class Build {
    final Theme t;
    final Theme.Grid g;
    final List<Column> columns;
    final int rowCount;
    final int rows; // the rows shown: the app's, and the row to append to them
    final boolean edits; // the grid edits cells itself
    final Options o;
    final Selection sel;
    final boolean cells;
    final int frozen;
    final float numbers; // the width of the row numbers, 0 without
    final float[] xs; // the left of each column in the content, and the right of the last
    // After the last column, the strip of the rows' bar, which lies over the right end of the rows
    // in view: scrolled to the end, the last column is clear of it.
    final float room;
    final boolean[] focused = {false}; // the grid has the focus, as its focus look found
    El grid;
    El body;

    Build(Theme t, List<Column> columns, int rowCount, Options o) {
      this.t = t;
      this.g = t.grid();
      this.columns = List.copyOf(columns);
      this.rowCount = rowCount;
      this.rows = rowCount + (o.appendRow ? 1 : 0);
      this.o = o;
      this.edits = o.onCommit != null && o.selection != null;
      this.sel = o.selection;
      this.cells = sel != null && sel.mode() == Mode.CELLS;
      this.frozen = Math.min(o.frozen, columns.size());
      this.numbers = o.rowNumbers ? numbersWidth(t, rowCount) : 0;
      this.xs = new float[columns.size() + 1];
      float x = numbers;
      for (int c = 0; c < columns.size(); c++) {
        xs[c] = x;
        x += columns.get(c).width();
      }
      xs[columns.size()] = x;
      Scrollbars bars = t.scrollbars();
      this.room = bars.thickness() + 2 * bars.margin();
    }

    float fixedWidth() {
      return xs[frozen];
    }

    /** Whether the header sets filters. */
    boolean filtering() {
      return o.onFilter != null && sel != null;
    }

    /** Whether the header sets a filter on this column. */
    boolean filtering(int column) {
      return filtering() && o.filterable.test(column);
    }

    int selectionFill() {
      return focused[0] ? g.selected() : g.selectedInactive();
    }
  }

  /**
   * @param rowCount the number of rows, 0 or more
   * @param options what the grid does; {@link #options()} for a grid that only shows
   */
  public static El dataGrid(Theme t, List<Column> columns, int rowCount, Options options) {
    if (options.table != null) { // with all the columns, before a layout counts them its way
      options.table.accept(columns, rowCount);
      options.table = null;
    }
    if (options.layout != null) {
      int all = columns.size();
      columns = options.layout.shown(columns);
      options.showOnly(options.layout.shownColumns(), all);
    }
    if (options.selection != null) { // without a layout the columns shown are the app's
      options.selection.showing(options.full);
    }
    Build b = new Build(t, columns, rowCount, options);
    Theme.Grid g = b.g;
    El header = header(b);
    Selection sel = b.sel;
    if (sel != null && sel.isEditing() && b.edits) {
      GridEdit.follow(b);
    }
    if (sel != null
        && sel.isEditing()
        && (!b.edits || sel.editRow >= b.rows || sel.editColumn >= columns.size())) {
      sel.cancelEdit(); // the rows or columns it was on are gone
    }
    if (b.edits) {
      sel.handOn = () -> GridEdit.commit(b, GridEdit.Move.NONE);
    }
    if (sel != null && !sel.headerMenu.isOpen()) {
      sel.headerColumn = -1;
    }
    if (sel != null
        && (!sel.filtering.isOpen() || sel.filterColumn >= columns.size() || !b.filtering())) {
      sel.filtering.close(); // dismissed, or its column is gone
      sel.filterColumn = -1;
    }
    El body = col()
        .key("rows")
        .grow(1)
        .rows(b.rows, g.rowHeight(), (i, from, to) -> dataRow(b, i, from, to))
        .scrollbars(t.scrollbars());
    b.body = body;
    if (sel != null) {
      body.scrollState(sel.rowsScroll);
    }
    if (sel != null && sel.row() >= 0) {
      body.revealRow(sel.row());
    }
    if (options.onRows != null) {
      body.onRowsBuilt(options.onRows);
    }
    if (sel != null) {
      body.onDrag(d -> drag(b, d));
    }

    El content = col().key("content").w(b.xs[columns.size()] + b.room);
    if (b.edits && sel.isEditing()) {
      GridEdit.anchor(b, content); // first, so that what lies over it takes the presses
    }
    content.children(header, body);
    content.style().minWidth = Len.percent(1); // the rows reach across a grid wider than them
    // Header and rows scroll sideways together in the grid; the rows scroll up and down alone.
    El grid =
        row().role("grid").scrollBoth().scrollbars(t.scrollbars()).fill(g.row()).child(content);
    b.grid = grid;
    if (sel != null) {
      grid.scrollState(sel.gridScroll);
    }
    reveal(b);
    if (options.menu != null) {
      Menu.contextMenu(t, grid, options.menu, menuItems(b));
    }
    if (sel == null) {
      return grid;
    }
    if (b.edits) {
      grid.onResize((width, height) -> sel.gridWidth = width)
          .onText(text -> GridEdit.typed(b, text));
    }
    return grid.focusable()
        .focusRing(t.accent().edge())
        .whenFocused(e -> b.focused[0] = true)
        .onKey(k -> key(b, k));
  }

  // Header

  private static El header(Build b) {
    Theme t = b.t;
    Theme.Grid g = b.g;
    int n = b.columns.size();
    // The line under it is its border, which the cells and the pinned parts end above.
    El header = row()
        .key("header")
        .h(g.rowHeight() + t.size().stroke())
        .shrink(0)
        .fill(g.header())
        .border(0, 0, t.size().stroke(), 0, t.border().control())
        .padding(0, 0, 0, b.fixedWidth());
    El[] lines = new El[n];
    List<El> pinned = new ArrayList<>();
    if (b.numbers > 0) {
      pinned.add(pin(b, cellBox(t, Align.RIGHT, b.numbers).key("numbers").fill(g.header()), 0));
      // The lines in the frozen part are filled, so that nothing scrolled under it shows.
      pinned.add(pin(b, line(t, false).fill(g.header()), b.numbers - t.size().stroke()));
    }
    for (int c = 0; c < n; c++) {
      lines[c] = line(t, c == b.frozen - 1);
      if (c < b.frozen - 1) {
        lines[c].fill(g.header());
      }
      El cell = headerCell(b, c);
      if (c < b.frozen) {
        pinned.add(pin(b, cell.fill(g.header()), b.xs[c]));
        pinned.add(pin(b, lines[c], b.xs[c + 1] - t.size().stroke()));
      } else {
        header.children(cell, lines[c]);
      }
    }
    List<El> edges = new ArrayList<>();
    for (int c = 0; b.o.onResize != null && c < n; c++) {
      El edge = edge(b, c, lines[c]);
      if (c < b.frozen) {
        pinned.add(edge.fixedX());
      } else {
        edges.add(edge);
      }
    }
    // Later children are drawn over earlier ones and hit first: the frozen part over the rest.
    edges.forEach(header::child);
    pinned.forEach(header::child);
    GridColumns.header(b, header);
    return header;
  }

  /**
   * A header cell: the icon, the title, the type and the sort mark, in this order at the left or
   * the right, the mark on the outer side. Where the column is narrow, the type gives way first
   * (entirely before less than its first character would show),
   * then the title ends in an ellipsis; icon and mark keep their size.
   */
  private static El headerCell(Build b, int index) {
    Theme t = b.t;
    Column column = b.columns.get(index);
    El title = title(t, column.title()).ellipsis().minSize(0, 0);
    El name = title;
    if (column.typeName() != null && typeFits(b, index)) {
      // The title keeps its width as long as the type has any, and never more than the room.
      title.shrink(0).style().maxWidth = Len.percent(1);
      name = row()
          .align(Style.Align.CENTER)
          .gap(t.space().s())
          .minSize(0, 0)
          .clip()
          .children(
              title,
              Label.label(t, Label.Variant.MUTED, column.typeName()).ellipsis().minSize(0, 0));
    }
    El cell = cellBox(t, column.align(), column.width())
        .key("column-" + index)
        .role("columnheader")
        .label(column.title())
        .gap(t.space().xs())
        .position(index + 1, b.columns.size());
    // Apart from the title by the gap of an icon in a cell, as the cell's gap is the mark's.
    El icon = column.icon() == null
        ? null
        : Icon.icon(t, column.icon(), column.iconVariant())
            .margin(0, t.space().s() - t.space().xs(), 0, 0);
    int at = -1;
    for (int k = 0; k < b.o.sort.size(); k++) {
      if (b.o.sort.get(k).column() == index) {
        at = k;
      }
    }
    El mark = null;
    if (at >= 0) {
      boolean descending = b.o.sort.get(at).descending();
      // The arrow, and its number among the keys when there are more, as one mark.
      mark = row()
          .align(Style.Align.CENTER)
          .shrink(0)
          .child(Icon.icon(
              t, descending ? Symbol.ARROW_DOWN : Symbol.ARROW_UP, Icon.Variant.MUTED, b.g.mark()));
      if (b.o.sort.size() > 1) {
        mark.child(Label.label(t, Label.Variant.MUTED, String.valueOf(at + 1)));
        if (column.align() == Align.RIGHT) { // the number apart from the title after it
          mark.margin(0, t.space().xs(), 0, 0);
        }
      }
      if (descending) {
        cell.sortDescending();
      } else {
        cell.sortAscending();
      }
    }
    // The filter's funnel at the outer end, apart from the rest by what is left of the cell;
    // while the column has none, it lies over that end under the pointer and takes no room.
    El funnel = b.filtering(index) ? GridFilter.funnel(b, index) : null;
    boolean inFlow = funnel != null && GridFilter.set(b, index);
    if (inFlow && column.align() == Align.RIGHT) {
      cell.children(funnel, el().grow(1).minSize(0, 0));
    }
    if (mark != null && column.align() == Align.RIGHT) {
      cell.child(mark);
    }
    if (icon != null) {
      cell.child(icon);
    }
    cell.child(name);
    if (mark != null && column.align() != Align.RIGHT) {
      cell.child(mark);
    }
    if (inFlow && column.align() != Align.RIGHT) {
      cell.children(el().grow(1).minSize(0, 0), funnel);
    } else if (funnel != null && !inFlow) {
      float inset = t.space().xs();
      cell.child(funnel
          .absolute(
              column.align() == Align.RIGHT
                  ? inset
                  : column.width() - t.size().stroke() - inset - t.size().icon(),
              (b.g.rowHeight() - t.size().icon()) / 2)
          .fill(b.g.header()));
    }
    // The whole title and type where anything is cut short, measured only when it may show.
    float room = column.width() - t.size().stroke() - 2 * t.space().s();
    String whole =
        column.typeName() == null ? column.title() : column.title() + " " + column.typeName();
    cell.whenHoveredFor(t.motion().tooltipDelay(), e -> {
      if (headerWidth(b, index) > room + 0.5f) {
        e.overlay(Tooltip.panel(t, whole));
      }
    });
    GridColumns.cell(b, index, cell);
    if (b.o.onSort == null || !column.sortable()) {
      return funnel == null ? cell : cell.whenHovered(e -> funnel.opacity(1));
    }
    int fill = b.g.header();
    List<SortKey> keys = b.o.sort;
    Consumer<List<SortKey>> onSort = b.o.onSort;
    return cell.cursor(Cursor.POINTER)
        .onPress((clicks, modifiers) ->
            onSort.accept(nextSort(keys, index, (modifiers & Key.SHIFT) != 0)))
        .whenHovered(e -> {
          e.fill(over(fill, t.surface().hover()));
          if (funnel != null) {
            funnel.opacity(1);
          }
        })
        .whenPressed(e -> e.fill(over(fill, t.surface().pressed())));
  }

  /**
   * The sort keys after a press on a column's header: its key goes from ascending to descending
   * to none; without {@code add} it is the only key, with it the others stay and a new key comes
   * last.
   */
  static List<SortKey> nextSort(List<SortKey> keys, int column, boolean add) {
    List<SortKey> next = new ArrayList<>();
    SortKey found = null;
    for (SortKey key : keys) {
      if (key.column() == column) {
        found = key;
      } else if (add) {
        next.add(key);
      }
    }
    if (found == null) {
      next.add(new SortKey(column, false));
    } else if (!found.descending()) {
      int at = add ? keys.indexOf(found) : 0;
      next.add(Math.min(at, next.size()), new SortKey(column, true));
    }
    return List.copyOf(next);
  }

  /** The strip at a column's right edge that resizes it, and fits it on a double press. */
  private static El edge(Build b, int c, El line) {
    Theme t = b.t;
    Theme.Grid g = b.g;
    Column column = b.columns.get(c);
    Resize onResize = b.o.onResize;
    Selection sel = b.sel;
    // The edge goes to the handler of the view the drag began in, with the width of then.
    return el().key("resize-" + c)
        .role("separator")
        .label("Resize " + column.title())
        .absolute(b.xs[c + 1] - g.handle() / 2, 0)
        .size(g.handle(), g.rowHeight())
        .cursor(Cursor.EW_RESIZE)
        .whenHovered(e -> line.fill(t.border().strong()))
        .whenPressed(e -> line.fill(t.accent().color()))
        .onDrag(d -> {
          onResize.resize(
              c,
              d.phase() == Drag.Phase.CANCEL
                  ? column.width()
                  : Math.max(column.width() + d.dx(), g.minColumn()));
          if (sel != null && c >= b.frozen && d.phase() != Drag.Phase.CANCEL) {
            sel.moves++; // the edge stays in view while it is dragged beyond the grid's side
            sel.edgeColumn = c;
          }
        })
        .onPress(clicks -> {
          if (clicks == 2) {
            onResize.resize(c, fit(b, c));
          }
        });
  }

  /**
   * The width that shows the column's header and the cells of its rows that exist whole: their
   * icons, title, type, sort mark and text.
   */
  static float fit(Build b, int c) {
    Theme t = b.t;
    float widest = headerWidth(b, c);
    El body = b.body;
    for (int k = 0; k < body.children().size(); k++) {
      String key = "cell-" + (body.firstRow() + k) + "-" + c;
      for (El cell : body.children().get(k).children()) {
        if (key.equals(cell.key())) {
          float width = 0;
          for (El part : cell.children()) {
            width += natural(part);
          }
          widest = Math.max(
              widest,
              width + Math.max(cell.children().size() - 1, 0) * t.space().s());
        }
      }
    }
    float width = (float) Math.ceil(widest) + 2 * t.space().s() + t.size().stroke();
    return Math.clamp(width, b.g.minColumn(), Math.max(b.g.minColumn(), b.g.maxColumn()));
  }

  /** The width of a header cell's content when nothing is cut short. */
  private static float headerWidth(Build b, int c) {
    Theme t = b.t;
    Column column = b.columns.get(c);
    float width = natural(title(t, column.title()));
    if (column.icon() != null) {
      width += t.size().icon() + t.space().s();
    }
    if (column.typeName() != null) {
      width += t.space().s() + natural(Label.label(t, Label.Variant.MUTED, column.typeName()));
    }
    if (b.o.sort.stream().anyMatch(k -> k.column() == c)) {
      width += b.g.mark() + t.space().xs();
      if (b.o.sort.size() > 1) {
        width += t.space().xs() + natural(Label.label(t, "9"));
      }
    }
    if (b.filtering(c) && GridFilter.set(b, c)) {
      width += t.size().icon() + t.space().xs();
    }
    return width;
  }

  /**
   * Whether the type of a column with one fits its header cell well enough to show: whole, or cut
   * short after at least its first character. With less room it would be an ellipsis alone, or a
   * part of one, so it gives way entirely and the tooltip names it.
   */
  private static boolean typeFits(Build b, int c) {
    Theme t = b.t;
    String type = b.columns.get(c).typeName();
    float room = b.columns.get(c).width() - t.size().stroke() - 2 * t.space().s();
    float whole = natural(Label.label(t, Label.Variant.MUTED, type));
    float left = room - (headerWidth(b, c) - whole);
    if (left >= whole) {
      return true;
    }
    int first = type.offsetByCodePoints(0, 1);
    return first < type.length()
        && left
            >= natural(
                Label.label(t, Label.Variant.MUTED, type.substring(0, first) + Text.ELLIPSIS));
  }

  /** The width a text element takes on one line, in logical pixels. */
  static float natural(El text) {
    return text.text() == null
        ? 0
        : text.text()
            .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
            .width();
  }

  // Rows

  private static El dataRow(Build b, int i, float from, float to) {
    Theme t = b.t;
    Theme.Grid g = b.g;
    Selection sel = b.sel;
    boolean append = i >= b.rowCount; // the empty row after the last, to add one
    boolean loaded = append || b.o.loaded == null || b.o.loaded.test(i);
    boolean rowSelected = sel != null && !b.cells && sel.isRowSelected(i);
    Status status = append || b.o.rowStatus == null ? Status.UNCHANGED : b.o.rowStatus.apply(i);
    int fill = rowSelected ? b.selectionFill() : i % 2 == 0 ? g.row() : g.stripe();
    if (!rowSelected && (status == Status.NEW || status == Status.DELETED)) {
      fill = over(fill, status == Status.NEW ? g.added() : g.deleted()); // the selection wins
    }
    float stroke = t.size().stroke();
    // A hairline along the bottom, its border, which the cells end above.
    El r = row()
        .key("row-" + i)
        .role("row")
        .fill(fill)
        .selected(rowSelected)
        .current(sel != null && !b.cells && sel.row() == i)
        .border(0, 0, stroke, 0, t.border().subtle());
    if (status != Status.UNCHANGED) {
      r.value(statusName(status));
    }
    int n = b.columns.size();
    int first = n;
    int last = -1;
    for (int c = b.frozen; c < n; c++) {
      if (b.xs[c + 1] > from && b.xs[c] < to) {
        first = Math.min(first, c);
        last = c;
      }
    }
    if (first < n) {
      r.padding(0, 0, 0, b.xs[first]);
    }
    for (int c = first; c <= last; c++) {
      r.children(cell(b, i, c, loaded, fill, status), line(t, false));
    }
    // The editor lies over the cells, and the frozen ones over it unless it is one of them; it is
    // there wherever its cell is, also scrolled out of the part the row was built for.
    boolean editing = sel != null && sel.editRow == i && b.edits;
    if (editing && sel.editColumn >= b.frozen) {
      r.child(GridEdit.editor(b, i, sel.editColumn));
    }
    List<El> pinned = new ArrayList<>(); // with the row's fill, which hovering changes
    if (b.numbers > 0) {
      // The numbers lie on the row's fill, which they need over the cells scrolled under them.
      String shown = append ? "*" : status == Status.NEW ? "+" : String.valueOf(i + 1);
      El number = cellBox(t, Align.RIGHT, b.numbers)
          .key("number-" + i)
          .role("rowheader")
          .fill(fill)
          .child(text(t, true, shown, t.text().muted()));
      pinned.add(number);
      if (sel != null) {
        number.onPointerDown(
            (clicks, modifiers) -> down(b, i, Math.max(sel.column(), 0), true, clicks, modifiers));
      }
      // The lines in the frozen part are filled, so that nothing scrolled under it shows.
      El after = line(t, false).fill(fill);
      pinned.add(after);
      r.children(pinRow(b, number, 0), pinRow(b, after, b.numbers - stroke));
    }
    for (int c = 0; c < b.frozen; c++) {
      El cell = cell(b, i, c, loaded, fill, status);
      if (cell.fill() == 0) {
        cell.fill(fill);
        pinned.add(cell);
      }
      El after = line(t, c == b.frozen - 1);
      if (c < b.frozen - 1) {
        after.fill(fill);
        pinned.add(after);
      }
      r.children(pinRow(b, cell, b.xs[c]), pinRow(b, after, b.xs[c + 1] - stroke));
    }
    if (editing && sel.editColumn < b.frozen) {
      r.child(GridEdit.editor(b, i, sel.editColumn));
    }
    if (status != Status.UNCHANGED) { // the mark at the start, over the numbers or first cell
      r.child(pinRow(b, el().key("mark").w(t.size().indicator()).fill(markColour(t, status)), 0));
    }
    if (rowSelected) {
      return r;
    }
    int hovered = over(fill, t.surface().hover());
    return r.whenHovered(e -> {
      e.fill(hovered);
      pinned.forEach(p -> p.fill(hovered));
    });
  }

  private static El cell(Build b, int i, int c, boolean loaded, int rowFill, Status row) {
    Theme t = b.t;
    Selection sel = b.sel;
    Column column = b.columns.get(c);
    El box = cellBox(t, column.align(), column.width())
        .key("cell-" + i + "-" + c)
        .role("gridcell")
        .position(c + 1, b.columns.size());
    boolean append = i >= b.rowCount;
    Status status = append || b.o.cellStatus == null || row == Status.DELETED
        ? Status.UNCHANGED
        : b.o.cellStatus.status(i, c);
    if (row == Status.NEW && status != Status.ERROR) {
      status = Status.UNCHANGED; // the new row's tint covers its cells; a failed one shows
    }
    boolean selected = b.cells && sel.contains(i, c);
    boolean ring = b.cells && sel.row() == i && sel.column() == c && b.focused[0];
    if (b.cells) {
      boolean cursor = sel.row() == i && sel.column() == c;
      box.selected(selected).current(cursor);
      if (selected) {
        box.fill(b.selectionFill());
      }
      if (ring) {
        float w = b.g.cursorWidth();
        box.border(w, b.g.cursor())
            .radius(t.radius().small())
            .padding(0, Math.max(t.space().s() - w, 0));
      }
    }
    if (status == Status.CHANGED || status == Status.ERROR) {
      box.value(statusName(status));
      if (!selected && (b.cells || sel == null || !sel.isRowSelected(i))) { // the selection wins
        box.fill(over(rowFill, status == Status.CHANGED ? b.g.changed() : b.g.error()));
      }
      if (status == Status.ERROR) {
        box.invalid(true);
        if (!ring) { // a line of the danger colour inside the cell, where the cursor's would be
          float w = t.size().stroke();
          box.border(w, t.status().danger())
              .radius(t.radius().small())
              .padding(0, Math.max(t.space().s() - w, 0));
        }
      }
    }
    String value = loaded && !append ? column.cell().apply(i) : null;
    CellIcon icon =
        loaded && !append && column.cellIcon() != null ? column.cellIcon().apply(i) : null;
    if (!loaded) {
      box.child(text(t, false, Text.ELLIPSIS, t.text().muted()));
    } else if (!append) {
      if (icon != null) {
        box.gap(t.space().s()).child(Icon.icon(t, icon.symbol(), icon.variant()));
      }
      if (icon == null || !icon.alone()) {
        El text = text(
            t,
            column.mono(),
            value == null ? "NULL" : oneLine(value),
            value == null || row == Status.DELETED ? t.text().muted() : t.text().primary());
        box.child(text);
        if (row == Status.DELETED) { // struck through, a line as the text is wide
          float pad = t.space().s();
          float before = icon == null ? 0 : t.size().icon() + pad;
          float width =
              Math.min(natural(text), column.width() - t.size().stroke() - 2 * pad - before);
          float x = column.align() == Align.RIGHT
              ? column.width() - t.size().stroke() - pad - width
              : pad + before;
          box.child(el().absolute(x, Math.round((b.g.rowHeight() - t.size().stroke()) / 2))
              .size(Math.max(width, 0), t.size().stroke())
              .fill(t.text().muted()));
        }
      } else {
        box.label(value == null ? "NULL" : value); // what the icon stands for, in a dump
      }
    }
    if (sel != null) {
      box.onPointerDown((clicks, modifiers) -> down(b, i, c, false, clicks, modifiers));
      if (b.o.menu != null) {
        Menu.State menu = b.o.menu;
        box.onSecondaryPress((x, y) -> {
          if (!sel.contains(i, c)) {
            change(b, () -> sel.select(i, c, false));
          }
          menu.openAt(x, y);
        });
      }
    }
    boolean alone = icon != null && icon.alone();
    String error =
        status == Status.ERROR && b.o.cellError != null ? b.o.cellError.message(i, c) : null;
    String told = icon == null ? null : icon.tooltip();
    // What is wrong first, then what the icon says.
    String own = error == null ? told : told == null ? error : error + "\n" + told;
    if (value != null || alone || own != null) {
      // The icon's own tooltip, else the text an icon alone stands for, and the whole text where
      // the column cuts it short.
      float room = column.width()
          - t.size().stroke()
          - 2 * t.space().s()
          - (icon == null ? 0 : t.size().icon() + t.space().s());
      box.whenHoveredFor(t.motion().tooltipDelay(), e -> {
        String cut =
            !alone && value != null && natural(e.children().getLast()) > room + 0.5f ? value : null;
        String tip = own == null
            ? (alone ? (value == null ? "NULL" : value) : cut)
            : cut == null ? own : own + "\n" + cut;
        if (tip != null) {
          e.overlay(Tooltip.panel(t, tip));
        }
      });
    }
    return box;
  }

  /** A status as a tree dump says it: {@code changed}, {@code new}, {@code deleted}, {@code error}. */
  static String statusName(Status status) {
    return status.name().toLowerCase(java.util.Locale.ROOT);
  }

  /** The colour of the mark at the start of a row in this status. */
  private static int markColour(Theme t, Status status) {
    return switch (status) {
      case CHANGED -> t.accent().edge();
      case NEW -> t.status().success();
      default -> t.status().danger();
    };
  }

  /** Places an element of a frozen column: at {@code x}, as tall as the row, kept from scrolling. */
  private static El pin(Build b, El el, float x) {
    return el.absolute(x, 0).h(b.g.rowHeight()).fixedX();
  }

  /** {@link #pin} in a row, above the row's hairline. */
  private static El pinRow(Build b, El el, float x) {
    return pin(b, el, x).h(b.g.rowHeight() - b.t.size().stroke());
  }

  // Selection by pointer

  private static void down(Build b, int row, int column, boolean rows, int clicks, int modifiers) {
    Selection sel = b.sel;
    if (sel.isEditing()) {
      return; // the press handed the value on, and the app refused it: the editor stays
    }
    change(b, () -> {
      if ((modifiers & Key.CONTROL) != 0) {
        sel.toggle(row, column, rows);
      } else if ((modifiers & Key.SHIFT) != 0) {
        sel.extend(row, column, rows);
      } else {
        sel.select(row, column, rows);
      }
    });
    if (clicks == 2 && modifiers == 0 && !rows) {
      if (GridEdit.editable(b, row, column)) {
        GridEdit.start(b, row, column, null, false);
      } else if (b.o.onEdit != null && row < b.rowCount) {
        b.o.onEdit.accept(row, column);
      }
    }
  }

  /** Extends the selection to the cell under the pointer, whose movement may scroll the grid. */
  private static void drag(Build b, Drag d) {
    Selection sel = b.sel;
    if (d.phase() == Drag.Phase.CANCEL || b.rows == 0 || b.columns.isEmpty() || sel.isEditing()) {
      return;
    }
    int row = (int) Math.clamp(Math.floor(d.y() / b.g.rowHeight()), 0, b.rows - 1);
    // In the content: over a frozen column while the rest is scrolled, that is the column under
    // it, so the grid scrolls back first.
    int column = b.columns.size() - 1;
    for (int c = 0; c < b.columns.size(); c++) {
      if (d.x() < b.xs[c + 1]) {
        column = c;
        break;
      }
    }
    int to = column;
    change(
        b,
        () -> sel.extend(row, to, !sel.ranges.isEmpty() && sel.ranges.getLast().wholeRows()));
  }

  /**
   * Changes the selection, scrolls what it moves the cursor to into view, and tells the app if it
   * changed.
   */
  static void change(Build b, Runnable change) {
    Selection sel = b.sel;
    List<Object> before = sel.snapshot();
    change.run();
    if (!before.equals(sel.snapshot())) {
      sel.moves++;
      sel.edgeColumn = -1;
      sel.start = false;
      if (b.o.onSelect != null) {
        b.o.onSelect.run();
      }
    }
  }

  /** Keeps the cursor's column, or a resized column's edge, in view while they move. */
  private static void reveal(Build b) {
    Selection sel = b.sel;
    if (sel == null) {
      return;
    }
    int n = b.columns.size();
    float fixed = b.fixedWidth();
    if (sel.edgeColumn >= b.frozen && sel.edgeColumn < n) {
      float edge = b.xs[sel.edgeColumn + 1];
      b.grid.reveal(
          sel.moves, edge - fixed - b.g.handle(), 0, fixed + 2 * b.g.handle() + b.room, 0);
    } else if (sel.start) {
      b.grid.reveal(sel.moves, 0, 0, fixed, 0);
    } else if (b.cells && sel.column() >= b.frozen && sel.column() < n) {
      float left = b.xs[sel.column()] - fixed; // clear of the frozen part and the rows' bar
      b.grid.reveal(sel.moves, left, 0, b.xs[sel.column() + 1] + b.room - left, 0);
    }
  }

  // Keys

  private static boolean key(Build b, Key k) {
    Selection sel = b.sel;
    int n = b.columns.size();
    int mods = k.modifiers();
    if (sel.isEditing()) {
      // What the editor left goes on and moves nothing here; before the editor has the focus,
      // in the frame that opened it, or while its row is scrolled away, its keys come here.
      return GridEdit.editKey(b, k);
    }
    if (GridEdit.gridKey(b, k)) {
      return true;
    }
    if (mods == Key.ALT
        && k.name().equals("ArrowDown")
        && n > 0
        && b.filtering(Math.clamp(sel.column(), 0, n - 1))) {
      GridFilter.open(b, Math.clamp(sel.column(), 0, n - 1));
      return true;
    }
    if ((mods & (Key.ALT | Key.META)) != 0 || b.rows == 0 || n == 0) {
      return false;
    }
    boolean shift = (mods & Key.SHIFT) != 0;
    boolean control = (mods & Key.CONTROL) != 0;
    String name = k.name();
    if (control && !shift && (name.equals("C") || name.equals("Insert")) && b.o.clipboard != null) {
      copy(b, false);
      return true;
    }
    if (control && !shift && name.equals("A")) {
      change(b, () -> sel.selectAll(b.rowCount));
      return true;
    }
    if (mods == 0
        && (name.equals("Enter") || name.equals("F2"))
        && b.o.onEdit != null
        && sel.row() >= 0
        && sel.row() < b.rowCount) {
      b.o.onEdit.accept(sel.row(), Math.clamp(sel.column(), 0, n - 1));
      return true;
    }
    if (control && !shift && name.equals("V") || shift && !control && name.equals("Insert")) {
      return false; // pasting is the editing's, when it pastes
    }
    boolean ends = name.equals("Home") || name.equals("End");
    if (control && !ends) {
      return false;
    }
    float rowHeight = b.g.rowHeight();
    int page = Math.max(1, (int) (b.body.height() / rowHeight));
    boolean has = sel.row() >= 0 && sel.row() < b.rows;
    int inView = Math.clamp((long) Math.ceil(b.body.scrollY() / rowHeight), 0, b.rows - 1);
    int row = has ? sel.row() : inView;
    int column = Math.clamp(sel.column(), 0, n - 1);
    int step = has ? 1 : 0;
    switch (name) {
      case "ArrowUp" -> row -= step;
      case "ArrowDown" -> row += step;
      case "PageUp" -> row -= has ? page : 0;
      case "PageDown" -> row += has ? page : 0;
      case "ArrowLeft", "ArrowRight" -> {
        if (!b.cells) {
          return false;
        }
        column += name.equals("ArrowLeft") ? -step : step;
      }
      case "Home", "End" -> {
        boolean home = name.equals("Home");
        if (!b.cells || control) {
          row = home ? 0 : b.rows - 1;
        }
        if (b.cells) {
          column = home ? 0 : n - 1;
        }
      }
      default -> {
        return false;
      }
    }
    int r = Math.clamp(row, 0, b.rows - 1);
    int c = Math.clamp(column, 0, n - 1);
    change(b, () -> {
      if (shift) {
        sel.extend(r, c);
      } else {
        sel.select(r, c);
      }
    });
    // A frozen cell leaves the rest where it is; Home and End in rows go back to the start. The
    // view goes to the cursor also where the key left it in place, after the wheel moved away.
    sel.start = b.cells ? c < b.frozen : ends;
    sel.edgeColumn = -1;
    sel.moves++;
    return true;
  }

  // Copying

  private static List<Menu.Item> menuItems(Build b) {
    List<Menu.Item> items = new ArrayList<>();
    if (b.o.clipboard != null && b.sel != null) {
      items.add(Menu.item("Copy", () -> copy(b, false)).shortcut("Ctrl+C"));
      items.add(Menu.item("Copy with headers", () -> copy(b, true)));
    }
    GridEdit.menuItems(b, items);
    // The header's menu of the cursor's column, which the keyboard reaches from here (Shift+F10).
    int c = b.sel == null ? -1 : b.sel.column();
    List<Menu.Item> column = c < 0 || c >= b.columns.size() ? List.of() : GridColumns.items(b, c);
    if (!column.isEmpty()) {
      if (!items.isEmpty()) {
        items.add(Menu.separator());
      }
      items.add(
          Menu.submenu("Column " + b.columns.get(c).title(), column).icon(Symbol.COLUMN_SINGLE));
    }
    if (!items.isEmpty() && !b.o.menuItems.isEmpty()) {
      items.add(Menu.separator());
    }
    items.addAll(b.o.menuItems);
    return items;
  }

  static void copy(Build b, boolean headers) {
    List<Range> ranges = new ArrayList<>();
    for (Range range : b.sel.ranges()) { // without the row to append, which has no cells
      if (range.top() < b.rowCount) {
        ranges.add(new Range(
            range.top(), range.left(), Math.min(range.bottom(), b.rowCount - 1), range.right()));
      }
    }
    if (ranges.isEmpty()) {
      return;
    }
    long cells = cellCount(ranges, b.columns.size());
    boolean missing = cells > b.o.copyLimit;
    if (!missing && b.o.loaded != null) {
      for (int[] span : rowSpans(ranges)) {
        for (int r = span[0]; r <= span[1] && !missing; r++) {
          missing = !b.o.loaded.test(r);
        }
      }
    }
    if (missing) {
      if (b.o.onLargeCopy != null) {
        b.o.onLargeCopy.accept(new Copy(ranges, headers, cells));
      }
      return;
    }
    b.o.clipboard.write(copyText(b.columns, ranges, headers, b.o.nullText));
  }

  /**
   * The selected cells as spreadsheets paste them: a line per row with a selected cell, a field
   * per column with one, separated by tabs, the lines by line feeds without one at the end, and
   * the titles first with {@code headers}. Where several ranges make more than one rectangle, a
   * cell in such a row and column that is not selected is an empty field. A field holding a tab, a line feed, a carriage return or a double
   * quote is put in double quotes, with each double quote doubled, and so is an empty field that
   * is alone on the last line, so that {@link #parseText} reads that line; NULL is {@code
   * nullText}.
   */
  public static String copyText(
      List<Column> columns, List<Range> ranges, boolean headers, String nullText) {
    BitSet shown = shownColumns(ranges, columns.size());
    StringBuilder out = new StringBuilder();
    if (headers) {
      for (int c = shown.nextSetBit(0); c >= 0; c = shown.nextSetBit(c + 1)) {
        field(out, c == shown.nextSetBit(0), columns.get(c).title());
      }
    }
    boolean first = !headers; // an empty first line still ends in a line feed
    int lastLine = out.length();
    for (int[] span : rowSpans(ranges)) {
      for (int r = span[0]; r <= span[1]; r++) {
        if (!first) {
          out.append('\n');
        }
        first = false;
        lastLine = out.length();
        for (int c = shown.nextSetBit(0); c >= 0; c = shown.nextSetBit(c + 1)) {
          String value = null;
          boolean selected = false;
          for (Range range : ranges) {
            selected |= range.contains(r, c);
          }
          if (selected) {
            value = columns.get(c).cell().apply(r);
            value = value == null ? nullText : value;
          }
          field(out, c == shown.nextSetBit(0), value == null ? "" : value);
        }
      }
    }
    if (!ranges.isEmpty() && shown.cardinality() == 1 && out.length() == lastLine) {
      out.append("\"\""); // a last line of one empty field, which would read as no line
    }
    return out.toString();
  }

  /**
   * A quiet button, Columns with the icon of a column, that opens the chooser of {@code layout}'s columns below
   * it: a box per column to show or hide it, in the order shown, which Alt+Arrow Up and Down change
   * for the focused one, a field that narrows the list where there are many, Show all and Reset.
   * The header's context menu opens the same chooser at the column it opened on.
   */
  public static El columnsButton(Theme t, Layout layout) {
    return GridColumns.button(t, layout);
  }

  /**
   * The filters as a row of badges to remove, each saying what it keeps ({@link
   * Filter#describe}), and a button that removes them all; null without filters. The app puts it
   * above the grid, as a bar of a card.
   *
   * @param columns the grid's columns, whose titles the badges name
   * @param onFilter told the filters left
   */
  public static El filterBar(
      Theme t, List<Column> columns, List<Filter> filters, Consumer<List<Filter>> onFilter) {
    return GridFilter.bar(t, columns, filters, onFilter);
  }

  /**
   * Tab-separated text, as {@link #copyText} writes it and spreadsheets copy it, as its rows of
   * fields: a line ends in a line feed, a carriage return or both, the fields of a line are
   * separated by tabs, and a field that begins with a double quote runs to the next double quote
   * that is not doubled, tabs and line breaks included, each doubled quote standing for one; text
   * after that quote up to the next tab or line end belongs to the field, and a quote that is
   * never closed is text. A line break at the end ends the last line, and empty text has no
   * rows. Rows may have different numbers of fields.
   */
  public static List<List<String>> parseText(String text) {
    List<List<String>> rows = new ArrayList<>();
    int n = text.length();
    if (n == 0) {
      return rows;
    }
    List<String> fields = new ArrayList<>();
    int i = 0;
    while (true) {
      StringBuilder field = new StringBuilder();
      int end = -1; // after a closed quoted field
      if (i < n && text.charAt(i) == '"') {
        for (int j = i + 1; j < n; j++) {
          char ch = text.charAt(j);
          if (ch != '"') {
            field.append(ch);
          } else if (j + 1 < n && text.charAt(j + 1) == '"') {
            field.append('"');
            j++;
          } else {
            end = j + 1;
            break;
          }
        }
      }
      if (end < 0) {
        field.setLength(0);
        end = i;
      }
      while (end < n
          && text.charAt(end) != '\t'
          && text.charAt(end) != '\n'
          && text.charAt(end) != '\r') {
        field.append(text.charAt(end++));
      }
      fields.add(field.toString());
      i = end;
      if (i >= n) {
        rows.add(fields);
        return rows;
      }
      char separator = text.charAt(i++);
      if (separator == '\t') {
        continue;
      }
      if (separator == '\r' && i < n && text.charAt(i) == '\n') {
        i++;
      }
      rows.add(fields);
      fields = new ArrayList<>();
      if (i >= n) {
        return rows;
      }
    }
  }

  /**
   * The number of cells {@link #copyText} writes for these ranges: the rows with a selected cell
   * times the columns with one, the header not counted.
   */
  public static long cellCount(List<Range> ranges, int columns) {
    long rows = 0;
    for (int[] span : rowSpans(ranges)) {
      rows += span[1] - span[0] + 1L;
    }
    return rows * shownColumns(ranges, columns).cardinality();
  }

  private static void field(StringBuilder out, boolean first, String value) {
    if (!first) {
      out.append('\t');
    }
    if (value.indexOf('\t') < 0
        && value.indexOf('\n') < 0
        && value.indexOf('\r') < 0
        && value.indexOf('"') < 0) {
      out.append(value);
    } else {
      out.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
  }

  /** The columns, of {@code count}, with a selected cell. */
  static BitSet shownColumns(List<Range> ranges, int count) {
    BitSet shown = new BitSet(count);
    for (Range range : ranges) {
      if (range.left() < count) {
        shown.set(range.left(), (int) Math.min(range.right() + 1L, count));
      }
    }
    return shown;
  }

  /** The rows of the ranges as spans that neither overlap nor touch, first to last. */
  static List<int[]> rowSpans(List<Range> ranges) {
    List<int[]> spans = new ArrayList<>();
    ranges.stream().sorted(Comparator.comparingInt(Range::top)).forEach(range -> {
      int[] last = spans.isEmpty() ? null : spans.getLast();
      if (last != null && range.top() <= last[1] + 1L) {
        last[1] = Math.max(last[1], range.bottom());
      } else {
        spans.add(new int[] {range.top(), range.bottom()});
      }
    });
    return spans;
  }

  // Parts

  /**
   * A cell's text on one line: each line break, also CR LF, as {@link #LINE_BREAK} and each tab
   * as {@link #TAB}, which the default fonts have. The tooltip and a copy keep it as it is.
   */
  static String oneLine(String value) {
    if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\t') < 0) {
      return value;
    }
    return value
        .replace("\r\n", LINE_BREAK)
        .replace('\r', '\n')
        .replace("\n", LINE_BREAK)
        .replace("\t", TAB);
  }

  static El text(Theme t, boolean mono, String text, int colour) {
    Theme.TextStyle style = mono ? t.type().mono() : t.type().body();
    return El.text(text)
        .font(style.font().get())
        .fontSize(style.size())
        .color(colour)
        .ellipsis()
        .minSize(0, 0);
  }

  /** A cell's box: its column's width less the line, which clips what is wider. */
  private static El cellBox(Theme t, Align align, float width) {
    return row()
        .w(Math.max(width - t.size().stroke(), 0))
        .padding(0, t.space().s())
        .align(Style.Align.CENTER)
        .justify(align == Align.RIGHT ? Style.Content.END : Style.Content.START)
        .clip();
  }

  /**
   * The line after a column, as tall as the row: a hairline after the frozen columns, elsewhere
   * room that shows a line only while a header's edge is under the pointer or dragged.
   */
  private static El line(Theme t, boolean visible) {
    return el().w(t.size().stroke()).fill(visible ? t.border().subtle() : 0);
  }

  /** A column's title, in the medium weight of {@link Theme.Type#strong}. */
  private static El title(Theme t, String text) {
    Theme.TextStyle style = t.type().strong();
    return El.text(text)
        .font(style.font().get())
        .fontSize(style.size())
        .color(t.text().primary());
  }

  /** The width of the row numbers: the widest number in the mono type, with the padding. */
  private static float numbersWidth(Theme t, int rowCount) {
    Theme.TextStyle mono = t.type().mono();
    String widest = "0".repeat(String.valueOf(Math.max(rowCount, 1)).length());
    float text = mono.font().get().shape(widest, mono.size(), 1).width();
    return (float) Math.ceil(text) + 2 * t.space().s() + t.size().stroke();
  }
}
