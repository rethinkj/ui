package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Text;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
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
 * selection as tab-separated text ({@link #copyText}). Enter, F2 and a double press ask the app
 * to edit the cursor's cell ({@link Options#onEdit}). The grid is one stop for Tab.
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
   */
  public record Column(
      String title, float width, Align align, boolean mono, boolean sortable,
      IntFunction<String> cell, Symbol icon, Icon.Variant iconVariant, String typeName,
      IntFunction<CellIcon> cellIcon) {

    public Column {
      Objects.requireNonNull(iconVariant, "iconVariant");
    }

    /** A column that sorts or not, without icons or type. */
    public Column(String title, float width, Align align, boolean mono, boolean sortable,
        IntFunction<String> cell) {
      this(title, width, align, mono, sortable, cell, null, Icon.Variant.MUTED, null, null);
    }

    /** A column that can be sorted. */
    public Column(String title, float width, Align align, boolean mono, IntFunction<String> cell) {
      this(title, width, align, mono, true, cell);
    }

    /**
     * A column of a kind of data, which gives its alignment, its font and the icon in its header
     * ({@link DataType}).
     */
    public Column(String title, float width, DataType type, IntFunction<String> cell) {
      this(title, width, type.align(), type.mono(), true, cell, type.symbol(), Icon.Variant.MUTED,
          null, null);
    }

    /** This column, sortable or not. */
    public Column sortable(boolean on) {
      return new Column(title, width, align, mono, on, cell, icon, iconVariant, typeName,
          cellIcon);
    }

    /** This column with {@code symbol} in muted text before the title in the header. */
    public Column icon(Symbol symbol) {
      return icon(symbol, Icon.Variant.MUTED);
    }

    /** This column with {@code symbol} before the title in the header; null for none. */
    public Column icon(Symbol symbol, Icon.Variant variant) {
      return new Column(title, width, align, mono, sortable, cell, symbol, variant, typeName,
          cellIcon);
    }

    /** This column with {@code name} after the title in the header; null for none. */
    public Column typeName(String name) {
      return new Column(title, width, align, mono, sortable, cell, icon, iconVariant, name,
          cellIcon);
    }

    /** This column with an icon in each cell for which {@code icons} gives one. */
    public Column cellIcon(IntFunction<CellIcon> icons) {
      return new Column(title, width, align, mono, sortable, cell, icon, iconVariant, typeName,
          icons);
    }
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
   */
  public static final class Selection {
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
      return new Range(Math.min(row1, row2), whole ? 0 : Math.min(column1, column2),
          Math.max(row1, row2), whole ? Range.ALL : Math.max(column1, column2));
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
    private Selection selection;
    private Runnable onSelect;
    private List<SortKey> sort = List.of();
    private Consumer<List<SortKey>> onSort;
    private Resize onResize;
    private boolean rowNumbers;
    private int frozen;
    private Clipboard clipboard;
    private String nullText = "";
    private long copyLimit = 100_000;
    private Consumer<Copy> onLargeCopy;
    private Menu.State menu;
    private List<Menu.Item> menuItems = List.of();
    private IntPredicate loaded;
    private El.RowsHandler onRows;
    private CellHandler onEdit;

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
  private static final class Build {
    final Theme t;
    final Theme.Grid g;
    final List<Column> columns;
    final int rowCount;
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
      this.o = o;
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

    int selectionFill() {
      return focused[0] ? g.selected() : g.selectedInactive();
    }
  }

  /**
   * @param rowCount the number of rows, 0 or more
   * @param options what the grid does; {@link #options()} for a grid that only shows
   */
  public static El dataGrid(Theme t, List<Column> columns, int rowCount, Options options) {
    Build b = new Build(t, columns, rowCount, options);
    Theme.Grid g = b.g;
    El header = header(b);
    Selection sel = b.sel;
    El body = col().key("rows").grow(1)
        .rows(rowCount, g.rowHeight(), (i, from, to) -> dataRow(b, i, from, to))
        .scrollbars(t.scrollbars());
    b.body = body;
    if (sel != null && sel.row() >= 0) {
      body.revealRow(sel.row());
    }
    if (options.onRows != null) {
      body.onRowsBuilt(options.onRows);
    }
    if (sel != null) {
      body.onDrag(d -> drag(b, d));
    }

    El content = col().key("content").w(b.xs[columns.size()] + b.room)
        .children(header, body);
    content.style().minWidth = Len.percent(1); // the rows reach across a grid wider than them
    // Header and rows scroll sideways together in the grid; the rows scroll up and down alone.
    El grid = row().role("grid").scrollBoth().scrollbars(t.scrollbars()).fill(g.row())
        .child(content);
    b.grid = grid;
    reveal(b);
    if (options.menu != null) {
      Menu.contextMenu(t, grid, options.menu, menuItems(b));
    }
    if (sel == null) {
      return grid;
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
    El header = row().key("header").h(g.rowHeight() + t.size().stroke()).shrink(0)
        .fill(g.header()).border(0, 0, t.size().stroke(), 0, t.border().control())
        .padding(0, 0, 0, b.fixedWidth());
    El[] lines = new El[n];
    List<El> pinned = new ArrayList<>();
    if (b.numbers > 0) {
      pinned.add(pin(b, cellBox(t, Align.RIGHT, b.numbers).key("numbers").fill(g.header()), 0));
      pinned.add(pin(b, line(t, false), b.numbers - t.size().stroke()));
    }
    for (int c = 0; c < n; c++) {
      lines[c] = line(t, c == b.frozen - 1);
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
      name = row().align(Style.Align.CENTER).gap(t.space().s()).minSize(0, 0).clip()
          .children(title, Label.label(t, Label.Variant.MUTED, column.typeName())
              .ellipsis().minSize(0, 0));
    }
    El cell = cellBox(t, column.align(), column.width()).key("column-" + index)
        .role("columnheader").label(column.title()).gap(t.space().xs())
        .position(index + 1, b.columns.size());
    // Apart from the title by the gap of an icon in a cell, as the cell's gap is the mark's.
    El icon = column.icon() == null ? null : Icon.icon(t, column.icon(), column.iconVariant())
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
      mark = row().align(Style.Align.CENTER).shrink(0).child(Icon.icon(t,
          descending ? Symbol.ARROW_DOWN : Symbol.ARROW_UP, Icon.Variant.MUTED, b.g.mark()));
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
    // The whole title and type where anything is cut short, measured only when it may show.
    float room = column.width() - t.size().stroke() - 2 * t.space().s();
    String whole = column.typeName() == null ? column.title()
        : column.title() + " " + column.typeName();
    cell.whenHoveredFor(t.motion().tooltipDelay(), e -> {
      if (headerWidth(b, index) > room + 0.5f) {
        e.overlay(Tooltip.panel(t, whole));
      }
    });
    if (b.o.onSort == null || !column.sortable()) {
      return cell;
    }
    int fill = b.g.header();
    List<SortKey> keys = b.o.sort;
    Consumer<List<SortKey>> onSort = b.o.onSort;
    return cell.cursor(Cursor.POINTER)
        .onPress((clicks, modifiers) ->
            onSort.accept(nextSort(keys, index, (modifiers & Key.SHIFT) != 0)))
        .whenHovered(e -> e.fill(over(fill, t.surface().hover())))
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
    return el().key("resize-" + c).role("separator").label("Resize " + column.title())
        .absolute(b.xs[c + 1] - g.handle() / 2, 0).size(g.handle(), g.rowHeight())
        .cursor(Cursor.EW_RESIZE)
        .whenHovered(e -> line.fill(t.border().strong()))
        .whenPressed(e -> line.fill(t.accent().color()))
        .onDrag(d -> {
          onResize.resize(c, d.phase() == Drag.Phase.CANCEL
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
  private static float fit(Build b, int c) {
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
          widest = Math.max(widest,
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
    return first < type.length() && left >= natural(
        Label.label(t, Label.Variant.MUTED, type.substring(0, first) + Text.ELLIPSIS));
  }

  /** The width a text element takes on one line, in logical pixels. */
  private static float natural(El text) {
    return text.text() == null ? 0
        : text.text().measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
            .width();
  }

  // Rows

  private static El dataRow(Build b, int i, float from, float to) {
    Theme t = b.t;
    Theme.Grid g = b.g;
    Selection sel = b.sel;
    boolean loaded = b.o.loaded == null || b.o.loaded.test(i);
    boolean rowSelected = sel != null && !b.cells && sel.isRowSelected(i);
    int fill = rowSelected ? b.selectionFill() : i % 2 == 0 ? g.row() : g.stripe();
    float stroke = t.size().stroke();
    // A hairline along the bottom, its border, which the cells end above.
    El r = row().key("row-" + i).role("row").fill(fill).selected(rowSelected)
        .current(sel != null && !b.cells && sel.row() == i)
        .border(0, 0, stroke, 0, t.border().subtle());
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
      r.children(cell(b, i, c, loaded, fill), line(t, false));
    }
    List<El> pinned = new ArrayList<>(); // with the row's fill, which hovering changes
    if (b.numbers > 0) {
      // The numbers lie on the row's fill, which they need over the cells scrolled under them.
      El number = cellBox(t, Align.RIGHT, b.numbers).key("number-" + i).role("rowheader")
          .fill(fill).child(text(t, true, String.valueOf(i + 1), t.text().muted()));
      pinned.add(number);
      if (sel != null) {
        number.onPointerDown((clicks, modifiers) -> down(b, i, Math.max(sel.column(), 0), true,
            clicks, modifiers));
      }
      r.children(pinRow(b, number, 0), pinRow(b, line(t, false), b.numbers - stroke));
    }
    for (int c = 0; c < b.frozen; c++) {
      El cell = cell(b, i, c, loaded, fill);
      if (cell.fill() == 0) {
        cell.fill(fill);
        pinned.add(cell);
      }
      r.children(pinRow(b, cell, b.xs[c]),
          pinRow(b, line(t, c == b.frozen - 1), b.xs[c + 1] - stroke));
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

  private static El cell(Build b, int i, int c, boolean loaded, int rowFill) {
    Theme t = b.t;
    Selection sel = b.sel;
    Column column = b.columns.get(c);
    El box = cellBox(t, column.align(), column.width()).key("cell-" + i + "-" + c)
        .role("gridcell").position(c + 1, b.columns.size());
    if (b.cells) {
      boolean selected = sel.contains(i, c);
      boolean cursor = sel.row() == i && sel.column() == c;
      box.selected(selected).current(cursor);
      if (selected) {
        box.fill(b.selectionFill());
      }
      if (cursor && b.focused[0]) {
        float w = b.g.cursorWidth();
        box.border(w, b.g.cursor()).radius(t.radius().small())
            .padding(0, Math.max(t.space().s() - w, 0));
      }
    }
    String value = loaded ? column.cell().apply(i) : null;
    CellIcon icon = loaded && column.cellIcon() != null ? column.cellIcon().apply(i) : null;
    if (!loaded) {
      box.child(text(t, false, Text.ELLIPSIS, t.text().muted()));
    } else {
      if (icon != null) {
        box.gap(t.space().s()).child(Icon.icon(t, icon.symbol(), icon.variant()));
      }
      if (icon == null || !icon.alone()) {
        box.child(text(t, column.mono(), value == null ? "NULL" : oneLine(value),
            value == null ? t.text().muted() : t.text().primary()));
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
    String own = icon == null ? null : icon.tooltip();
    if (value != null || alone || own != null) {
      // The icon's own tooltip, else the text an icon alone stands for, and the whole text where
      // the column cuts it short.
      float room = column.width() - t.size().stroke() - 2 * t.space().s()
          - (icon == null ? 0 : t.size().icon() + t.space().s());
      box.whenHoveredFor(t.motion().tooltipDelay(), e -> {
        String cut = !alone && value != null && natural(e.children().getLast()) > room + 0.5f
            ? value : null;
        String tip = own == null ? (alone ? (value == null ? "NULL" : value) : cut)
            : cut == null ? own : own + "\n" + cut;
        if (tip != null) {
          e.overlay(Tooltip.panel(t, tip));
        }
      });
    }
    return box;
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
    change(b, () -> {
      if ((modifiers & Key.CONTROL) != 0) {
        sel.toggle(row, column, rows);
      } else if ((modifiers & Key.SHIFT) != 0) {
        sel.extend(row, column, rows);
      } else {
        sel.select(row, column, rows);
      }
    });
    if (clicks == 2 && modifiers == 0 && !rows && b.o.onEdit != null) {
      b.o.onEdit.accept(row, column);
    }
  }

  /** Extends the selection to the cell under the pointer, whose movement may scroll the grid. */
  private static void drag(Build b, Drag d) {
    if (d.phase() == Drag.Phase.CANCEL || b.rowCount == 0 || b.columns.isEmpty()) {
      return;
    }
    Selection sel = b.sel;
    int row = (int) Math.clamp(Math.floor(d.y() / b.g.rowHeight()), 0, b.rowCount - 1);
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
    change(b, () -> sel.extend(row, to, !sel.ranges.isEmpty() && sel.ranges.getLast().wholeRows()));
  }

  /**
   * Changes the selection, scrolls what it moves the cursor to into view, and tells the app if it
   * changed.
   */
  private static void change(Build b, Runnable change) {
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
      b.grid.reveal(sel.moves, edge - fixed - b.g.handle(), 0,
          fixed + 2 * b.g.handle() + b.room, 0);
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
    if ((mods & (Key.ALT | Key.META)) != 0 || b.rowCount == 0 || n == 0) {
      return false;
    }
    boolean shift = (mods & Key.SHIFT) != 0;
    boolean control = (mods & Key.CONTROL) != 0;
    String name = k.name();
    if (control && !shift && (name.equals("C") || name.equals("Insert"))
        && b.o.clipboard != null) {
      copy(b, false);
      return true;
    }
    if (control && !shift && name.equals("A")) {
      change(b, () -> sel.selectAll(b.rowCount));
      return true;
    }
    if (mods == 0 && (name.equals("Enter") || name.equals("F2")) && b.o.onEdit != null
        && sel.row() >= 0 && sel.row() < b.rowCount) {
      b.o.onEdit.accept(sel.row(), Math.clamp(sel.column(), 0, n - 1));
      return true;
    }
    boolean ends = name.equals("Home") || name.equals("End");
    if (control && !ends) {
      return false;
    }
    float rowHeight = b.g.rowHeight();
    int page = Math.max(1, (int) (b.body.height() / rowHeight));
    boolean has = sel.row() >= 0 && sel.row() < b.rowCount;
    int inView = Math.clamp((long) Math.ceil(b.body.scrollY() / rowHeight), 0, b.rowCount - 1);
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
          row = home ? 0 : b.rowCount - 1;
        }
        if (b.cells) {
          column = home ? 0 : n - 1;
        }
      }
      default -> {
        return false;
      }
    }
    int r = Math.clamp(row, 0, b.rowCount - 1);
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
    if (!items.isEmpty() && !b.o.menuItems.isEmpty()) {
      items.add(Menu.separator());
    }
    items.addAll(b.o.menuItems);
    return items;
  }

  private static void copy(Build b, boolean headers) {
    List<Range> ranges = b.sel.ranges();
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
   * quote is put in double quotes, with each double quote doubled; NULL is {@code nullText}.
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
    for (int[] span : rowSpans(ranges)) {
      for (int r = span[0]; r <= span[1]; r++) {
        if (!out.isEmpty()) {
          out.append('\n');
        }
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
    return out.toString();
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
    if (value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0
        && value.indexOf('"') < 0) {
      out.append(value);
    } else {
      out.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
  }

  /** The columns, of {@code count}, with a selected cell. */
  private static BitSet shownColumns(List<Range> ranges, int count) {
    BitSet shown = new BitSet(count);
    for (Range range : ranges) {
      if (range.left() < count) {
        shown.set(range.left(), (int) Math.min(range.right() + 1L, count));
      }
    }
    return shown;
  }

  /** The rows of the ranges as spans that neither overlap nor touch, first to last. */
  private static List<int[]> rowSpans(List<Range> ranges) {
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
    return value.replace("\r\n", LINE_BREAK).replace('\r', '\n').replace("\n", LINE_BREAK)
        .replace("\t", TAB);
  }

  private static El text(Theme t, boolean mono, String text, int colour) {
    Theme.TextStyle style = mono ? t.type().mono() : t.type().body();
    return El.text(text).font(style.font().get()).fontSize(style.size()).color(colour)
        .ellipsis().minSize(0, 0);
  }

  /** A cell's box: its column's width less the line, which clips what is wider. */
  private static El cellBox(Theme t, Align align, float width) {
    return row().w(Math.max(width - t.size().stroke(), 0))
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
    return El.text(text).font(style.font().get()).fontSize(style.size())
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
