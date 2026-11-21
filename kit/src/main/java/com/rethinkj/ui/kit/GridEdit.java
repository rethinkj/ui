package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.Build;
import com.rethinkj.ui.kit.DataGrid.CellValue;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.Status;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * The editing of a data grid's cells ({@link DataGrid.Options#onCommit}): the editor over a cell
 * and its keys, the checks of a column's type, and the keys of the grid that edit, paste, clear,
 * add and delete rows. What it edits is the {@link Selection}'s, which the app keeps.
 */
final class GridEdit {

  private GridEdit() {}

  /**
   * What a {@link Selection} holds of the editing: the cell being edited, -1 for none, what its
   * editor holds, and a refused switch of a boolean.
   */
  static final class State {
    int row = -1;
    int column = -1;
    // With rows told apart by keys (Options.changes): the key of the edited row, which the
    // editor follows wherever the rows move, or Selection.APPEND for the row to append.
    boolean keyed;
    long key;
    String draft; // null for NULL
    String original; // the value the editing began with
    String error; // what the app or the type said is wrong with the draft, null for nothing
    CellEditMode mode; // how the open editor edits, null while none is open
    int edits; // the editors opened so far: each new one takes the focus by this key
    float gridWidth; // the grid's width, as layout last told it, for an editor that grows
    Runnable handOn; // the last grid built with it that edits: hands the draft on (commitEdit)
    FieldState field = new FieldState(); // the open editor's, shared by its field and its lines
    final Menu.State choices = new Menu.State(); // an enumeration's list
    final FilterList.State search = new FilterList.State(); // a long enumeration's filter
    // A switch of a boolean that was refused, and its cell, until the next key or press.
    String refused;
    int refusedRow = -1;
    int refusedColumn = -1;

    /** Closes the editor without handing on what it holds. */
    void cancel() {
      row = column = -1;
      keyed = false;
      draft = original = error = null;
      mode = null;
      choices.close();
    }

    /** Puts the editor on its column's new place, or closes it where its column is gone. */
    void onColumnsChanged(IntUnaryOperator to) {
      if (row >= 0) {
        column = to.applyAsInt(column);
        if (column < 0) {
          cancel();
        }
      }
    }
  }

  /** Where the cursor goes after the editor handed its value on. */
  enum Move {
    NONE,
    DOWN,
    UP,
    RIGHT,
    LEFT
  }

  // Types

  /** Whether an empty editor means empty text in this column, rather than NULL. */
  static boolean textLike(Column c) {
    return c.editing().choices().isEmpty()
        && switch (c.type()) {
          case INTEGER, DECIMAL, BOOLEAN, DATE, TIME, DATE_TIME, ENUM -> false;
          default -> true;
        };
  }

  /** Whether typing may lead to {@code text}, which a number column refuses otherwise. */
  static boolean accepts(Column c, String text) {
    if (c.cells() != null) {
      return c.cells().format().allows(text);
    }
    return switch (c.type()) {
      case INTEGER -> Format.BIG_INTEGER.allows(text);
      case DECIMAL -> Format.DECIMAL.allows(text);
      default -> true;
    };
  }

  /** The value an editor's text stands for: in a column that is not text, empty is NULL. */
  static String normalize(Column c, String text) {
    return text != null && text.isEmpty() && !textLike(c) ? null : text;
  }

  /** What is wrong with a value for its column's type, or null when it fits. */
  static String check(Column c, String value) {
    if (value == null) {
      return c.editing().nullable() ? null : Texts.CURRENT.get().required();
    }
    if (!c.editing().choices().isEmpty() && !c.editing().choices().contains(value)) {
      return Texts.CURRENT.get().notAChoice();
    }
    if (c.cells() != null) { // a choice too: a narrowed format refuses some
      return c.cells().format().parseText(value).error();
    }
    if (!c.editing().choices().isEmpty()) {
      return null;
    }
    return switch (c.type()) {
      case INTEGER -> Format.BIG_INTEGER.parseText(value).error();
      case DECIMAL -> Format.DECIMAL.parseText(value).error();
      case DATE -> Format.DATE.parseText(value).error();
      case TIME -> Format.ANY_TIME.parseText(value).error();
      case DATE_TIME -> Format.ANY_DATE_TIME.parseText(value).error();
      default -> null;
    };
  }

  private static boolean truthy(String value) {
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "true", "t", "1", "yes", "y", "on" -> true;
      default -> false;
    };
  }

  // Starting and ending

  /** Whether the cell may change, by an editor, pasting or clearing. */
  static boolean editable(Build b, int row, int column) {
    if (b.sel == null || row < 0 || row >= b.rows || column < 0 || column >= b.columns.size()) {
      return false;
    }
    Column c = b.columns.get(column);
    if (c.editing().rows() == null) {
      return false;
    }
    if (row >= b.rowCount) { // the row to append: the app adds it before the first value
      return b.edits && b.o.onInsertRow != null;
    }
    if (b.o.loaded != null && !b.o.loaded.test(row)
        || b.o.rowStatus != null && b.o.rowStatus.apply(row) == Status.DELETED) {
      return false;
    }
    return c.editing().rows().test(row);
  }

  private static String value(Build b, int row, int column) {
    return row < b.rowCount ? b.columns.get(column).cell().apply(row) : null;
  }

  /**
   * Opens the editor over a cell, with its value or with {@code typed} in place of it, in the
   * field of several lines with {@code multiline}; switches a boolean instead.
   */
  static void start(Build b, int row, int column, String typed, boolean multiline) {
    String value = value(b, row, column);
    String draft = typed != null ? typed : value;
    boolean breaks = draft != null && (draft.indexOf('\n') >= 0 || draft.indexOf('\r') >= 0);
    CellEditMode.of(b.columns.get(column), multiline || breaks).start(b, row, column, value, typed);
  }

  /** Opens the editor over a cell in a mode, with the value and draft it begins with. */
  private static Selection open(
      Build b, int row, int column, String value, String draft, CellEditMode mode) {
    Selection sel = b.sel;
    sel.edit.row = row;
    sel.edit.column = column;
    keep(b, row);
    sel.edit.original = value;
    sel.edit.error = null;
    sel.edit.edits++;
    sel.edit.field =
        new FieldState(); // caret and history of this editor, in one field or the other
    sel.edit.draft = draft;
    sel.edit.mode = mode;
    sel.edit.choices.close();
    return sel;
  }

  /** Remembers the key of the edited row, where the rows have keys. */
  private static void keep(Build b, int row) {
    Selection sel = b.sel;
    sel.edit.keyed = b.o.rowKey != null;
    if (sel.edit.keyed) {
      sel.edit.key = row >= b.rowCount ? Selection.APPEND : b.o.rowKey.applyAsLong(row);
    }
  }

  /**
   * Puts the editor on the row with its key, where the rows have keys and have moved since it
   * opened; closes it without handing anything on when the row is gone. Whether it is open.
   */
  static boolean follow(Build b) {
    Selection sel = b.sel;
    if (sel.isEditing() && sel.edit.keyed) {
      int place = sel.edit.key == Selection.APPEND
          ? b.o.rowsNow.getAsInt()
          : b.o.rowPlace.applyAsInt(sel.edit.key);
      if (place < 0) {
        sel.cancelEdit();
      } else {
        sel.edit.row = place;
      }
    }
    if (sel.isEditing()
        && sel.edit.column < b.columns.size()
        && !(sel.edit.mode instanceof CellEditMode.MultilineText)) {
      // The app builds its columns anew: the mode follows a change of the column's editing,
      // except in the field of several lines, which stays open until it hands on or closes.
      CellEditMode mode = CellEditMode.of(b.columns.get(sel.edit.column), false);
      sel.edit.mode = mode instanceof CellEditMode.Toggle ? CellEditMode.INLINE : mode;
    }
    return sel.isEditing();
  }

  /**
   * Hands the editor's value on and moves the cursor, or keeps the editor open with a message, in
   * the grid's texts: also those of the column's format and of the app's handler.
   */
  static void commit(Build b, Move move) {
    Texts.CURRENT.with(b.texts, () -> {
      handOn(b, move);
      return null;
    });
  }

  private static void handOn(Build b, Move move) {
    Selection sel = b.sel;
    if (!follow(b)) {
      return;
    }
    int row = sel.edit.row;
    int column = sel.edit.column;
    Column c = b.columns.get(column);
    CellEditMode mode = sel.edit.mode; // the app's handler may close the editor
    String value = normalize(c, sel.edit.draft);
    boolean same = Objects.equals(value, sel.edit.original); // nothing changed: nothing to check
    String error = same ? null : mode.check(c, value);
    boolean appended = false;
    // The row to append; by its key where the rows have keys, as b may be older than the rows.
    boolean append = sel.edit.keyed ? sel.edit.key == Selection.APPEND : row >= b.rowCount;
    if (error == null && !same) {
      if (append) { // after the last row there is now, which follow found
        b.o.onInsertRow.accept(row - 1);
        appended = true;
      }
      error = b.o.onCommit.commit(row, column, value);
    }
    if (error != null) {
      sel.edit.error = error;
      sel.edit.edits++; // the editor takes the focus back from a press elsewhere, which moved it
      mode.refused(b, c, value);
      if (appended) {
        sel.edit.original = null; // the row is the app's now, and empty
        if (sel.edit.keyed) {
          sel.edit.key = b.o.rowKey.applyAsLong(row); // no longer the row to append
        }
      }
      return;
    }
    sel.cancelEdit();
    move(b, row, column, move, appended);
  }

  private static void move(Build b, int row, int column, Move move, boolean appended) {
    if (move == Move.NONE) {
      return;
    }
    int n = b.columns.size();
    int rows = b.rows + (appended ? 1 : 0);
    int r = row;
    int c = column;
    switch (move) {
      case DOWN -> r = Math.min(row + 1, rows - 1);
      case UP -> r = Math.max(row - 1, 0);
      case RIGHT -> {
        if (column + 1 < n) {
          c++;
        } else if (row + 1 < rows) {
          r++;
          c = 0;
        }
      }
      case LEFT -> {
        if (column > 0) {
          c--;
        } else if (row > 0) {
          r--;
          c = n - 1;
        }
      }
      default -> {}
    }
    int toRow = r;
    int toColumn = c;
    Selection sel = b.sel;
    DataGrid.change(b, () -> sel.select(toRow, toColumn));
    sel.start = b.cells && toColumn < b.frozen;
    sel.edgeColumn = -1;
    sel.moves++;
  }

  /**
   * Switches a boolean: false, true, NULL where the column allows it, and false again. A value
   * that the column's format or the app refuses leaves the cell as it was, which shows the message
   * as a refused editor does until the next key or press in the grid.
   */
  static void toggle(Build b, int row, int column) {
    Texts.CURRENT.with(b.texts, () -> {
      switchValue(b, row, column);
      return null;
    });
  }

  private static void switchValue(Build b, int row, int column) {
    Column c = b.columns.get(column);
    String value = value(b, row, column);
    String next = value == null || !truthy(value)
        ? (value == null ? "false" : "true")
        : c.editing().nullable() ? null : "false";
    String error = check(c, next);
    if (error == null) {
      if (row >= b.rowCount) {
        b.o.onInsertRow.accept(b.rowCount - 1);
      }
      error = b.o.onCommit.commit(row, column, next);
    }
    Selection sel = b.sel;
    sel.edit.refused = error;
    sel.edit.refusedRow = row;
    sel.edit.refusedColumn = column;
  }

  /** The message of a refused value, below the editor or the switched cell. */
  static El alert(Theme theme, String message) {
    return col()
        .key("error")
        .role("alert")
        .label(message)
        .padding(theme.space().xs(), theme.space().s())
        .radius(theme.radius().small())
        .fill(theme.surface().raised())
        .border(theme.size().stroke(), theme.status().danger())
        .shadow(theme.elevation().popup())
        .child(Label.label(message));
  }

  /** The keys of the editor, which reach it from its field or the app's own editor. */
  static boolean editKey(Build b, Key k) {
    Selection sel = b.sel;
    if (!sel.isEditing()) {
      return false;
    }
    Column c = b.columns.get(sel.edit.column);
    int mods = k.modifiers();
    switch (k.name()) {
      case "Escape" -> {
        if (mods != 0) {
          return false;
        }
        sel.cancelEdit();
        return true;
      }
      case "Enter", "NumpadEnter" -> {
        if (mods == Key.ALT) {
          if (!(sel.edit.mode instanceof CellEditMode.InlineText) || !textLike(c)) {
            return false;
          }
          sel.edit.mode = CellEditMode.MULTILINE;
          return true;
        }
        if (mods == Key.CONTROL) {
          commit(b, Move.NONE);
          return true;
        }
        if (sel.edit.mode instanceof CellEditMode.MultilineText || mods != 0 && mods != Key.SHIFT) {
          return false;
        }
        commit(b, mods == 0 ? Move.DOWN : Move.UP);
        return true;
      }
      case "Tab" -> {
        if (sel.edit.mode instanceof CellEditMode.MultilineText || (mods & ~Key.SHIFT) != 0) {
          return false;
        }
        commit(b, mods == 0 ? Move.RIGHT : Move.LEFT);
        return true;
      }
      case "0", "Numpad0" -> {
        if (mods != Key.CONTROL || !c.editing().nullable()) {
          return false;
        }
        sel.edit.draft = null;
        sel.edit.error = null;
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  /** Text typed while the grid has the focus: it opens the editor over the cursor's cell. */
  static void typed(Build b, String text) {
    Selection sel = b.sel;
    int n = b.columns.size();
    if (text.isEmpty() || n == 0 || text.codePoints().anyMatch(Character::isISOControl)) {
      return;
    }
    if (sel.isEditing()) {
      sel.edit.mode.typedInto(sel, b.columns.get(sel.edit.column), text);
      return;
    }
    int row = sel.row();
    int column = Math.clamp(sel.column(), 0, n - 1);
    if (!b.edits || !editable(b, row, column)) {
      return;
    }
    CellEditMode.of(b.columns.get(column), false).typed(b, row, column, text);
  }

  /** The keys of the grid that edit, while no editor is open; whether it took the key. */
  static boolean gridKey(Build b, Key k) {
    Selection sel = b.sel;
    DataGrid.Options o = b.o;
    int n = b.columns.size();
    int mods = k.modifiers();
    String name = k.name();
    boolean control = mods == Key.CONTROL;
    int row = sel.row();
    int column = n == 0 ? -1 : Math.clamp(sel.column(), 0, n - 1);
    boolean has = row >= 0 && row < b.rows && column >= 0;
    if (o.onInsertRow != null
        && (mods == 0 && name.equals("Insert")
            || (mods & ~Key.SHIFT) == Key.CONTROL
                && (name.equals("+") || name.equals("=") || name.equals("NumpadAdd")))) {
      int after = has ? Math.min(row, b.rowCount - 1) : b.rowCount - 1;
      o.onInsertRow.accept(after);
      int to = after + 1;
      int at = Math.max(column, 0);
      DataGrid.change(b, () -> sel.select(to, at));
      sel.moves++;
      return true;
    }
    if (o.onDeleteRows != null
        && control
        && (name.equals("-") || name.equals("NumpadSubtract") || name.equals("Delete"))) {
      List<Range> rows = rowsOf(b);
      if (!rows.isEmpty()) {
        o.onDeleteRows.accept(rows);
      }
      return true;
    }
    if (control && name.equals("Z") && o.onUndo != null) {
      o.onUndo.run();
      return true;
    }
    if ((control && name.equals("Y") || mods == (Key.CONTROL | Key.SHIFT) && name.equals("Z"))
        && o.onRedo != null) {
      o.onRedo.run();
      return true;
    }
    if (!has) {
      return false;
    }
    if (b.edits && editable(b, row, column)) {
      Column c = b.columns.get(column);
      if (mods == 0 && (name.equals("Enter") || name.equals("F2"))) {
        start(b, row, column, null, false);
        return true;
      }
      if (mods == Key.ALT
          && name.equals("Enter")
          && CellEditMode.of(c, true) instanceof CellEditMode.MultilineText) {
        start(b, row, column, null, true);
        return true;
      }
      if (mods == 0
          && name.equals("Space")
          && CellEditMode.of(c, false) instanceof CellEditMode.Toggle) {
        toggle(b, row, column);
        return true;
      }
    }
    if (o.onPaste != null) {
      if (mods == 0 && name.equals("Delete")) {
        clear(b, false);
        return true;
      }
      if (control && (name.equals("0") || name.equals("Numpad0"))) {
        clear(b, true);
        return true;
      }
      if ((control && name.equals("V") || mods == Key.SHIFT && name.equals("Insert"))
          && o.clipboard != null) {
        DataGrid.run(b, DataGrid.Command.PASTE);
        return true;
      }
    }
    return false;
  }

  /** The rows with a selected cell, or the cursor's, without the row to append. */
  private static List<Range> rowsOf(Build b) {
    List<Range> rows = new ArrayList<>();
    for (int[] span : DataGrid.rowSpans(b.sel.ranges())) {
      if (span[0] < b.rowCount) {
        rows.add(new Range(span[0], 0, Math.min(span[1], b.rowCount - 1), Range.ALL));
      }
    }
    int row = b.sel.row();
    if (rows.isEmpty() && row >= 0 && row < b.rowCount) {
      rows.add(new Range(row, 0, row, Range.ALL));
    }
    return rows;
  }

  // Pasting and clearing

  /**
   * The editable cells that a change of the selection reaches: the selected ones in cells, else
   * the cursor's alone; none when they are more than the copy limit.
   */
  private static List<int[]> targets(Build b) {
    Selection sel = b.sel;
    int n = b.columns.size();
    List<int[]> cells = new ArrayList<>();
    List<Range> ranges = sel.ranges();
    if (!b.cells || ranges.isEmpty()) {
      int column = n == 0 ? -1 : Math.clamp(sel.column(), 0, n - 1);
      if (editable(b, sel.row(), column) && sel.row() < b.rowCount) {
        cells.add(new int[] {sel.row(), column});
      }
      return cells;
    }
    List<int[]> spans = DataGrid.rowSpans(ranges);
    BitSet shown = DataGrid.shownColumns(ranges, n);
    if (DataGrid.cellCount(ranges, n) > b.o.copyLimit) {
      return cells;
    }
    for (int[] span : spans) {
      for (int r = span[0]; r <= Math.min(span[1], b.rowCount - 1); r++) {
        for (int c = shown.nextSetBit(0); c >= 0; c = shown.nextSetBit(c + 1)) {
          if (sel.contains(r, c) && editable(b, r, c)) {
            cells.add(new int[] {r, c});
          }
        }
      }
    }
    return cells;
  }

  /**
   * Clears the selected cells, or sets them NULL: in one change, of the cells whose column takes
   * the value, as pasted ones.
   */
  static void clear(Build b, boolean toNull) {
    List<CellValue> cells = new ArrayList<>();
    for (int[] at : targets(b)) {
      Column c = b.columns.get(at[1]);
      String value = c.editing().nullable() ? null : !toNull && textLike(c) ? "" : null;
      if (check(c, value) == null) {
        cells.add(new CellValue(at[0], at[1], value));
      }
    }
    if (!cells.isEmpty()) {
      b.o.onPaste.accept(List.copyOf(cells));
    }
  }

  /**
   * Pastes the clipboard's tab-separated text from the cursor's cell on, or a single value into
   * every selected cell; values that do not fit their column's type are left out, and the
   * pasted rectangle is selected.
   */
  static void paste(Build b) {
    Selection sel = b.sel;
    List<List<String>> rows = DataGrid.parseText(b.o.clipboard.read());
    int n = b.columns.size();
    if (rows.isEmpty() || n == 0 || sel.row() < 0) {
      return;
    }
    List<CellValue> cells = new ArrayList<>();
    if (rows.size() == 1 && rows.getFirst().size() == 1) {
      String field = rows.getFirst().getFirst();
      for (int[] at : targets(b)) {
        add(b, cells, at[0], at[1], field);
      }
    } else {
      long count = 0;
      for (List<String> fields : rows) {
        count += fields.size();
      }
      if (count > b.o.copyLimit) {
        return;
      }
      int top = sel.row();
      int left = Math.clamp(sel.column(), 0, n - 1);
      int right = left;
      for (int dr = 0; dr < rows.size() && top + dr < b.rowCount; dr++) {
        List<String> fields = rows.get(dr);
        for (int dc = 0; dc < fields.size() && left + dc < n; dc++) {
          if (editable(b, top + dr, left + dc)) {
            add(b, cells, top + dr, left + dc, fields.get(dc));
          }
          right = Math.max(right, left + dc);
        }
      }
      int bottom = Math.min(top + rows.size() - 1, b.rowCount - 1);
      int last = right;
      if (b.cells && bottom >= top) {
        DataGrid.change(b, () -> {
          sel.select(top, left);
          sel.extend(bottom, last);
        });
      }
    }
    if (!cells.isEmpty()) {
      b.o.onPaste.accept(List.copyOf(cells));
    }
  }

  private static void add(Build b, List<CellValue> cells, int row, int column, String field) {
    Column c = b.columns.get(column);
    String nullText = b.o.nullText;
    String value = !nullText.isEmpty() && field.equals(nullText) ? null : normalize(c, field);
    if (check(c, value) == null) {
      cells.add(new CellValue(row, column, value));
    }
  }

  /** The context menu's items that edit, after Copy. */
  static void menuItems(Build b, List<Menu.Item> items) {
    DataGrid.Options o = b.o;
    if (b.sel == null) {
      return;
    }
    Texts texts = b.texts;
    List<Menu.Item> mine = new ArrayList<>();
    if (o.onPaste != null && o.clipboard != null) {
      mine.add(Menu.item(
              texts.paste(),
              DataGrid.canRun(b, DataGrid.Command.PASTE)
                  ? () -> DataGrid.run(b, DataGrid.Command.PASTE)
                  : null)
          .shortcut(texts.keys("Ctrl", "V")));
    }
    if (o.onPaste != null) {
      mine.add(Menu.item(texts.setNull(), () -> clear(b, true)).shortcut(texts.keys("Ctrl", "0")));
    }
    if (o.onInsertRow != null) {
      mine.add(Menu.item(texts.insertRow(), () -> {
            int after = b.sel.row() >= 0 ? Math.min(b.sel.row(), b.rowCount - 1) : b.rowCount - 1;
            o.onInsertRow.accept(after);
          })
          .shortcut(texts.keys("Insert")));
    }
    if (o.onDeleteRows != null) {
      mine.add(Menu.item(texts.deleteRows(), () -> {
            List<Range> rows = rowsOf(b);
            if (!rows.isEmpty()) {
              o.onDeleteRows.accept(rows);
            }
          })
          .shortcut(texts.keys("Ctrl", "Delete")));
    }
    if (!mine.isEmpty() && !items.isEmpty()) {
      items.add(Menu.separator());
    }
    items.addAll(mine);
  }

  // The modes

  /**
   * How a column's cells edit, resolved from the column ({@link #of}) as the editing starts and
   * held in the {@link Selection} while the editor is open, which {@link GridEdit#follow} keeps to
   * the column as the app builds it anew: each mode starts the edit, takes typed text,
   * builds the editor over the cell and what hangs on its anchor, and checks and refuses a value
   * its own way.
   */
  sealed interface CellEditMode {
    CellEditMode TOGGLE = new Toggle();
    CellEditMode CHOICE_SEARCH = new ChoiceSearch();
    CellEditMode CHOICE_MENU = new ChoiceMenu();
    CellEditMode CUSTOM = new Custom();
    CellEditMode INLINE = new InlineText();
    CellEditMode MULTILINE = new MultilineText();

    /** The mode of a column's cells, in the field of several lines with {@code multiline}. */
    static CellEditMode of(Column c, boolean multiline) {
      DataGrid.Editing editing = c.editing();
      if (editing.editor() != null) {
        return CUSTOM;
      }
      if (!editing.choices().isEmpty()) {
        return editing.choices().size() > ComboBox.ROWS ? CHOICE_SEARCH : CHOICE_MENU;
      }
      if (c.type() == DataType.BOOLEAN) {
        return TOGGLE;
      }
      return multiline && textLike(c) ? MULTILINE : INLINE;
    }

    /** Opens the editor over a cell, with its value or with {@code typed} in place of it. */
    default void start(Build b, int row, int column, String value, String typed) {
      open(b, row, column, value, typed != null ? typed : value, this);
    }

    /** Text typed while the grid has the focus and no editor is open: it may open this one. */
    default void typed(Build b, int row, int column, String text) {
      if (accepts(b.columns.get(column), text)) {
        start(b, row, column, value(b, row, column), text);
      }
    }

    /** Text typed into the open editor before its field had the focus, in its first frame. */
    default void typedInto(Selection sel, Column c, String text) {}

    /** Puts the editor over its cell, in the {@code box} the modes share. */
    El editor(Build b, Column c, int column, El box);

    /** What hangs on the anchor over the cell, as it takes the focus; null for nothing. */
    default El overlay(Build b, Column c, float height) {
      return null;
    }

    /** What is wrong with a changed value before it is handed on, or null when it fits. */
    default String check(Column c, String value) {
      return GridEdit.check(c, value);
    }

    /** Shows that the app or the type refused {@code value}, beyond the editor's message. */
    default void refused(Build b, Column c, String value) {}

    /**
     * A boolean, which switches in the cell without an editor ({@link GridEdit#toggle}).
     */
    record Toggle() implements CellEditMode {
      @Override
      public void start(Build b, int row, int column, String value, String typed) {
        toggle(b, row, column);
      }

      @Override
      public void typed(Build b, int row, int column, String text) {
        // Space switches it, and nothing else types into it.
      }

      @Override
      public El editor(Build b, Column c, int column, El box) {
        return box; // never open: start switches it instead
      }
    }

    /**
     * Choices more than a menu shows without scrolling ({@link ComboBox#ROWS}): a field that
     * filters them, as a combo box's, since typing narrows a long list faster than the menu's
     * first letters go through it.
     */
    record ChoiceSearch() implements CellEditMode {
      @Override
      public void start(Build b, int row, int column, String value, String typed) {
        Selection sel = open(b, row, column, value, value, this);
        int at = entryOf(b.columns.get(column), value);
        sel.edit.search.reset(at);
        sel.edit.search.none = at < 0; // a value not among the entries: nothing until a key moves
        if (typed != null) {
          sel.edit.search.typed(typed, true);
        }
      }

      @Override
      public void typed(Build b, int row, int column, String text) {
        start(b, row, column, value(b, row, column), text);
      }

      @Override
      public El editor(Build b, Column c, int column, El box) {
        return searchEditor(b, c, column, box);
      }

      @Override
      public El overlay(Build b, Column c, float height) {
        return search(b, c);
      }
    }

    /** An enumeration's few choices, in a menu whose first letters go through them. */
    record ChoiceMenu() implements CellEditMode {
      @Override
      public void start(Build b, int row, int column, String value, String typed) {
        Selection sel = open(b, row, column, value, value, this);
        Column c = b.columns.get(column);
        List<Menu.Item> items = choiceItems(b, c);
        int at = typed == null ? indexOf(c, value) : Menu.startingWith(items, -1, typed);
        if (typed != null && at < 0) {
          at = indexOf(c, value);
        }
        sel.edit.choices.open();
        sel.edit.choices.highlight(0, at);
      }

      @Override
      public void typed(Build b, int row, int column, String text) {
        start(b, row, column, value(b, row, column), text);
      }

      @Override
      public El editor(Build b, Column c, int column, El box) {
        shown(b, c, box)
            .gap(b.theme.space().xs())
            .child(Icon.icon(Select.OPENS, Icon.Variant.MUTED));
        return place(b, box, column, cell(b, c), false, Side.TOP); // the list lies below it
      }

      @Override
      public El overlay(Build b, Column c, float height) {
        return b.sel.edit.choices.isOpen() ? choices(b, c) : null;
      }

      @Override
      public void refused(Build b, Column c, String value) {
        b.sel.edit.choices.open(); // the list stays open on the refused choice
        b.sel.edit.choices.highlight(0, indexOf(c, value));
      }
    }

    /** The app's own editor ({@link DataGrid.Editing#editor}), which checks its values itself. */
    record Custom() implements CellEditMode {
      @Override
      public void typed(Build b, int row, int column, String text) {
        start(b, row, column, value(b, row, column), null);
      }

      @Override
      public El editor(Build b, Column c, int column, El box) {
        return customEditor(b, c, column, box);
      }

      @Override
      public String check(Column c, String value) {
        return null;
      }
    }

    /** The field of one line in the cell, for text and for numbers, dates and times. */
    record InlineText() implements CellEditMode {
      @Override
      public void typedInto(Selection sel, Column c, String text) {
        append(sel, c, text);
      }

      @Override
      public El editor(Build b, Column c, int column, El box) {
        return inlineEditor(b, c, column, box);
      }
    }

    /** The field of several lines below the cell, for a long text or one with line breaks. */
    record MultilineText() implements CellEditMode {
      @Override
      public void typedInto(Selection sel, Column c, String text) {
        append(sel, c, text);
      }

      @Override
      public El editor(Build b, Column c, int column, El box) {
        return place(b, shown(b, c, box), column, cell(b, c), false, null);
      }

      @Override
      public El overlay(Build b, Column c, float height) {
        return multiline(b, c, height);
      }
    }
  }

  /** Puts typed text on the end of the draft, where the column takes it. */
  private static void append(Selection sel, Column c, String text) {
    String draft = (sel.edit.draft == null ? "" : sel.edit.draft) + text;
    if (accepts(c, draft)) {
      sel.edit.draft = draft;
      sel.edit.error = null;
    }
  }

  // The editor

  /**
   * The editor over the cell, absolute in its row: a field in the cell's font, its text where the
   * cell's is, growing up to the grid's edge for a long value; the app's editor; or, while the
   * list of an enumeration or the field of several lines edits, what they hold. Its edge is the
   * accent's, or the danger color's with the message below it when the value was refused.
   */
  static El editor(Build b, int row, int column) {
    Theme theme = b.theme;
    Theme.Grid g = b.g;
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    float stroke = theme.size().stroke();
    float w = g.cursorWidth();
    El box = row()
        .key("editor")
        .h(g.rowHeight() - stroke)
        .align(Style.Align.CENTER)
        .justify(c.align() == DataGrid.Align.RIGHT ? Style.Content.END : Style.Content.START)
        .padding(0, pad(theme, w))
        .fill(theme.surface().raised())
        .radius(theme.radius().small())
        .border(
            w, sel.edit.error == null ? theme.accent().edge() : theme.status().danger())
        .keepFocus()
        .onPress(() -> {}) // a press on its edge neither drags nor takes the focus
        .onKey(k -> editKey(b, k));
    return sel.edit.mode.editor(b, c, column, box);
  }

  /** The room between the editor's edge and its text. */
  private static float pad(Theme theme, float edge) {
    return Math.max(theme.space().s() - edge, 0);
  }

  private static Theme.TextStyle style(Theme theme, Column c) {
    return c.mono() ? theme.type().mono() : theme.type().body();
  }

  /** The width of a cell inside its line. */
  private static float cell(Build b, Column c) {
    return Math.max(c.width() - b.theme.size().stroke(), 0);
  }

  /**
   * Puts the editor over its cell, {@code width} wide; a press outside it hands it on with {@code
   * pressOutside}, and a refused value's message shows on {@code alert}'s side, none for null.
   */
  private static El place(
      Build b, El box, int column, float width, boolean pressOutside, Side alert) {
    Selection sel = b.sel;
    Theme theme = b.theme;
    boolean right = b.columns.get(column).align() == DataGrid.Align.RIGHT;
    box.absolute(right ? b.xs[column + 1] - theme.size().stroke() - width : b.xs[column], 0)
        .w(width);
    if (column < b.frozen) {
      box.fixedX();
    }
    if (pressOutside) {
      box.onPressOutside(() -> commit(b, Move.NONE));
    }
    if (sel.edit.error != null && alert != null) {
      box.invalid(true)
          .overlay(alert(theme, sel.edit.error)
              .side(alert, Style.Align.START, theme.space().xs()));
    }
    return box;
  }

  /** The editor of a long enumeration: a field that filters the choices below it. */
  private static El searchEditor(Build b, Column c, int column, El box) {
    Theme theme = b.theme;
    Selection sel = b.sel;
    Theme.TextStyle style = style(theme, c);
    // It shows the value while nothing is typed.
    El field = El.text(sel.edit.search.query)
        .editable(v -> sel.edit.search.typed(v, true))
        .key("field")
        .label(b.texts.edit(c.title()))
        .font(style.font().get())
        .fontSize(style.size())
        .color(theme.text().primary())
        .placeholder(
            sel.edit.draft == null ? "NULL" : DataGrid.oneLine(sel.edit.draft),
            theme.text().muted())
        .selectionColor(theme.accent().selection())
        .hFull()
        .minSize(0, 0)
        .grow(1)
        .focusRing(0)
        .autofocus(sel.edit.edits)
        .onKey(k -> searchKey(b, c, k));
    box.gap(theme.space().xs())
        .dismissGroup(sel.edit.search)
        .child(field)
        .child(Icon.icon(Select.OPENS, Icon.Variant.MUTED));
    return place(b, box, column, cell(b, c), false, Side.TOP); // the list lies below it
  }

  /** The editor of the app's own, which hands its value on as it changes. */
  private static El customEditor(Build b, Column c, int column, El box) {
    Selection sel = b.sel;
    box.child(c.editing()
        .editor()
        .editor(sel.edit.draft, v -> {
          sel.edit.draft = v;
          sel.edit.error = null;
        })
        .autofocus(sel.edit.edits));
    return place(b, box, column, cell(b, c), true, Side.BOTTOM);
  }

  /** What the list of an enumeration or the field of several lines holds, which edit it. */
  private static El shown(Build b, Column c, El box) {
    Theme theme = b.theme;
    String draft = b.sel.edit.draft;
    El shown = DataGrid.text(
        theme,
        c.mono(),
        draft == null ? "NULL" : DataGrid.oneLine(draft),
        draft == null ? theme.text().muted() : theme.text().primary());
    return box.child(c.align() == DataGrid.Align.RIGHT ? shown : shown.grow(1));
  }

  /**
   * The field of one line: its text where the cell's is, growing up to the grid's edge for a long
   * value, with a button for the field of several lines then.
   */
  private static El inlineEditor(Build b, Column c, int column, El box) {
    Theme theme = b.theme;
    Selection sel = b.sel;
    float stroke = theme.size().stroke();
    float w = b.g.cursorWidth();
    float pad = pad(theme, w);
    boolean right = c.align() == DataGrid.Align.RIGHT;
    Theme.TextStyle style = style(theme, c);
    float cell = cell(b, c);
    String text = sel.edit.draft == null ? "" : sel.edit.draft;
    float caret = theme.space().xs(); // room for the caret after the text, which then never scrolls
    float wide =
        DataGrid.natural(El.text(text).font(style.font().get()).fontSize(style.size())) + caret;
    float frame = 2 * (pad + w);
    // Longer than the cell: a button at the right opens the field of several lines.
    boolean longer = textLike(c) && wide + frame > cell;
    float button = theme.size().icon() + 2 * stroke;
    float extra = longer ? button + theme.space().xs() : 0;
    float grown = Math.min(wide + frame + extra, room(b, column, right));
    float width = Math.max(cell, grown); // the room may be less than the cell: no clamp
    El field = El.text(text)
        .editable(v -> edit(c, sel, v))
        .key("field")
        .fieldState(sel.edit.field)
        .label(b.texts.edit(c.title()))
        .font(style.font().get())
        .fontSize(style.size())
        .color(theme.text().primary())
        .selectionColor(theme.accent().selection())
        .hFull()
        .minSize(0, 0)
        .focusRing(0) // the editor's edge says where the keys go
        .autofocus(sel.edit.edits);
    if (sel.edit.draft == null) {
      field.value("NULL");
      if (c.editing().nullable()) {
        field.placeholder("NULL", theme.text().muted());
      }
    } else if (text.isEmpty() && c.editing().nullable() && textLike(c)) {
      field.placeholder(b.texts.empty(), theme.text().muted()); // not NULL: Control+0 makes it that
    }
    El fitted = field;
    if (right) {
      // As wide as the text, at the right, where the cell's text ends: it does not jump. An
      // invisible copy of the text, no line high, gives the width as layout measures the text
      // at the window's scale and with its hinting, which a measure here cannot know (the
      // field, measured 0 wide, came out narrower than its text and scrolled its first
      // character away); the field below it takes that width, the caret's room included.
      El copy = El.text(text)
          .font(style.font().get())
          .fontSize(style.size())
          .padding(0, caret, 0, 0)
          .h(0)
          .minSize(0, 0)
          .opacity(0);
      fitted = col().key("fit").hFull().minSize(0, 0).clip().children(copy, field.wFull());
      fitted.style().maxWidth = Len.px(Math.max(width - frame - extra, 0));
      box.padding(0, Math.max(pad - caret, 0), 0, pad);
    } else {
      field.grow(1);
    }
    box.child(fitted);
    if (longer) {
      int hover = theme.surface().hover();
      box.gap(theme.space().xs())
          .child(row()
              .key("expand")
              .role("button")
              .label(b.texts.editInLines())
              .size(button, button)
              .shrink(0)
              .center()
              .radius(theme.radius().small())
              .cursor(Cursor.POINTER)
              .keepFocus() // the caret stays in the field
              .onPress(() -> sel.edit.mode = CellEditMode.MULTILINE)
              .whenHovered(e -> e.fill(hover))
              .child(Icon.icon(Symbol.ARROW_MAXIMIZE, Icon.Variant.MUTED)));
    }
    return place(b, box, column, width, true, Side.BOTTOM);
  }

  /** How wide an editor of this column may grow: to the edge of the grid's part in view. */
  private static float room(Build b, int column, boolean right) {
    Selection sel = b.sel;
    if (sel.edit.gridWidth <= 0) {
      return 0;
    }
    double scroll = column < b.frozen ? 0 : sel.gridScroll.x();
    if (right) {
      double left = scroll + (column < b.frozen ? 0 : b.fixedWidth());
      return (float) (b.xs[column + 1] - b.theme.size().stroke() - left);
    }
    return (float) (scroll + sel.edit.gridWidth - b.room - b.xs[column]);
  }

  private static void edit(Column c, Selection sel, String text) {
    if (!accepts(c, text)) {
      return; // refused: the field shows the draft as it was
    }
    sel.edit.draft = text;
    sel.edit.error = null;
  }

  /**
   * For the list of an enumeration and the field of several lines, which are overlays that take
   * the focus and so cannot lie in a row: an element over the cell, first in the grid's content,
   * that they hang on. It moves sideways with the content; up and down it is where the cell was
   * when the view was built.
   */
  static void anchor(Build b, El content) {
    Selection sel = b.sel;
    Column c = b.columns.get(sel.edit.column);
    float stroke = b.theme.size().stroke();
    float height = b.g.rowHeight() - stroke;
    El overlay = sel.edit.mode.overlay(b, c, height);
    if (overlay == null) {
      return;
    }
    double top =
        b.g.rowHeight() + stroke + (double) sel.edit.row * b.g.rowHeight() - sel.rowsScroll.y();
    El anchor = el().key("edit-anchor")
        .absolute(b.xs[sel.edit.column], (float) top)
        .size(Math.max(c.width() - stroke, 1), height);
    if (sel.edit.column < b.frozen) {
      anchor.fixedX();
    }
    content.child(anchor.overlay(overlay));
  }

  /** The choices of an enumeration, and NULL where the column allows it, after a line. */
  private static List<Menu.Item> choiceItems(Build b, Column c) {
    Selection sel = b.sel;
    List<Menu.Item> items = new ArrayList<>();
    for (String choice : c.editing().choices()) {
      items.add(Menu.item(choice, () -> choose(b, choice, Move.NONE))
          .checked(choice.equals(sel.edit.original)));
    }
    if (c.editing().nullable()) {
      items.add(Menu.separator());
      items.add(Menu.item("NULL", () -> choose(b, null, Move.NONE))
          .checked(sel.edit.original == null && sel.isEditing()));
    }
    return items;
  }

  /** The entry of a value in the filtered list, NULL's after the choices; -1 for none. */
  private static int entryOf(Column c, String value) {
    List<String> choices = c.editing().choices();
    return value == null ? (c.editing().nullable() ? choices.size() : -1) : choices.indexOf(value);
  }

  /** The item of a value in the menu of choices, NULL's after the choices and a line. */
  private static int indexOf(Column c, String value) {
    List<String> choices = c.editing().choices();
    return value == null
        ? (c.editing().nullable() ? choices.size() + 1 : -1)
        : choices.indexOf(value);
  }

  private static void choose(Build b, String value, Move move) {
    b.sel.edit.draft = value;
    commit(b, move);
  }

  /**
   * The choices of a long enumeration below the cell, filtered by what the editor's field holds
   * (Fuzzy), in a menu's panel: the current one with a check mark at its end, as a combo box's, and highlighted while nothing
   * is typed;
   * it leaves the focus in the field, whose keys move the highlight.
   */
  private static El search(Build b, Column c) {
    Selection sel = b.sel;
    Theme theme = b.theme;
    List<FilterList.Entry> entries = new ArrayList<>();
    for (String choice : c.editing().choices()) {
      entries.add(FilterList.entry(choice, () -> choose(b, choice, Move.NONE))
          .checked(choice.equals(sel.edit.original)));
    }
    if (c.editing().nullable()) {
      entries.add(FilterList.entry("NULL", () -> choose(b, null, Move.NONE))
          .checked(sel.edit.original == null));
    }
    FilterList.Shown shown = FilterList.shown(sel.edit.search, FilterList.Entries.of(entries));
    return Popup.frame(theme, col())
        .key("choices")
        .minSize(theme.size().field(), 0) // as a combo box's, wider than a narrow column
        .padding(theme.space().s())
        .keepFocus()
        .dismissGroup(sel.edit.search)
        .side(Side.BOTTOM, Style.Align.STRETCH, theme.space().xs())
        .onDismiss(sel::cancelEdit)
        .child(FilterList.list(
            theme,
            sel.edit.search,
            shown,
            ComboBox.ROWS,
            theme.size().control(),
            b.texts.choicesOf(c.title()),
            e -> e.onChoose().run()));
  }

  /** The keys of the field of a long enumeration: the list's, Enter and Tab, Escape. */
  private static boolean searchKey(Build b, Column c, Key k) {
    Selection sel = b.sel;
    FilterList.State s = sel.edit.search;
    int mods = k.modifiers();
    boolean enter = mods == 0 && (k.name().equals("Enter") || k.name().equals("NumpadEnter"));
    boolean tab = k.name().equals("Tab") && (mods & ~Key.SHIFT) == 0;
    if (enter || tab) {
      List<String> choices = c.editing().choices();
      int at = s.shown != null && s.shown.choosable(s.highlight)
          ? s.shown.rows()[s.highlight]
          : -1; // the entry's index: a choice's, or NULL's after them
      if (at < 0) {
        sel.cancelEdit();
      } else {
        choose(
            b,
            at < choices.size() ? choices.get(at) : null,
            enter ? Move.DOWN : mods == 0 ? Move.RIGHT : Move.LEFT);
      }
      return true;
    }
    if (mods == 0 && k.name().equals("Escape")) {
      sel.cancelEdit();
      return true;
    }
    return FilterList.key(k, s, ComboBox.ROWS, e -> e.onChoose().run());
  }

  /**
   * The list of an enumeration below the cell, the current choice checked and the one typed
   * highlighted: the menu's keys and letters move, Enter and Tab choose and go on as in a field,
   * Escape and a press elsewhere leave the value as it was.
   */
  private static El choices(Build b, Column c) {
    Selection sel = b.sel;
    List<Menu.Item> items = choiceItems(b, c);
    El menu = Menu.menu(sel.edit.choices, items).side(Side.BOTTOM, Style.Align.STRETCH, 0);
    Predicate<Key> own = menu.onKey();
    return menu.onDismiss(sel::cancelEdit).onKey(k -> {
      int mods = k.modifiers();
      boolean enter = mods == 0 && k.name().equals("Enter");
      boolean tab = k.name().equals("Tab") && (mods & ~Key.SHIFT) == 0;
      if (enter || tab) {
        int active = sel.edit.choices.active();
        if (active < 0 || items.get(active).isSeparator()) {
          sel.cancelEdit();
        } else {
          choose(
              b,
              active < c.editing().choices().size() ? c.editing().choices().get(active) : null,
              enter ? Move.DOWN : mods == 0 ? Move.RIGHT : Move.LEFT);
        }
        return true;
      }
      if (mods == 0 && k.name().equals("Escape")) {
        sel.cancelEdit();
        return true;
      }
      return own.test(k);
    });
  }

  /**
   * The field of several lines for a long text or one with line breaks, over the cell and below
   * it: Enter breaks a line, Control+Enter and Save hand the text on, Escape and Cancel close it,
   * a press outside it hands it on.
   */
  private static El multiline(Build b, Column c, float height) {
    Theme theme = b.theme;
    Selection sel = b.sel;
    Theme.TextStyle style = c.mono() ? theme.type().mono() : theme.type().body();
    String label = b.texts.edit(c.title());
    El area = TextField.textArea(
            sel.edit.draft == null ? "" : sel.edit.draft,
            sel.edit.draft == null && c.editing().nullable() ? "NULL" : null,
            v -> edit(c, sel, v))
        .key("text")
        .label(label)
        .font(style.font().get())
        .fontSize(style.size())
        .lines(6)
        .w(Math.max(c.width(), 2 * theme.size().field()))
        .autofocus()
        .fieldState(sel.edit.field); // goes on from the caret and history of the field it replaces
    if (sel.edit.draft == null) {
      area.value("NULL");
    }
    if (sel.edit.error != null) {
      TextField.invalid(area);
    }
    El panel = Popup.frame(theme, col())
        .key("multiline")
        .role("dialog")
        .label(label)
        .modal()
        .onDismiss(() -> commit(b, Move.NONE))
        .onKey(k -> editKey(b, k))
        .padding(theme.space().m())
        .gap(theme.space().s())
        .child(area);
    if (sel.edit.error != null) {
      panel.child(Label.label(sel.edit.error)
          .key("error")
          .role("alert")
          .label(sel.edit.error)
          .color(theme.status().danger()));
    }
    return panel
        .child(row()
            .align(Style.Align.CENTER)
            .gap(theme.space().s())
            .children(
                Label.label(Label.Variant.MUTED, b.texts.toSave(b.texts.keys("Ctrl", "Enter")))
                    .grow(1),
                Button.button(Button.Variant.SECONDARY, b.texts.cancel(), sel::cancelEdit),
                Button.button(Button.Variant.PRIMARY, b.texts.save(), () -> commit(b, Move.NONE))))
        .side(Side.BOTTOM, Style.Align.START, -height);
  }
}
