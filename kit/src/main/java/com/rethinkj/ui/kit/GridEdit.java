package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.Build;
import com.rethinkj.ui.kit.DataGrid.CellValue;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Range;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.Status;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The editing of a data grid's cells ({@link DataGrid.Options#onCommit}): the editor over a cell
 * and its keys, the checks of a column's type, and the keys of the grid that edit, paste, clear,
 * add and delete rows. What it edits is the {@link Selection}'s, which the app keeps.
 */
final class GridEdit {

  private GridEdit() {}

  /** Where the cursor goes after the editor handed its value on. */
  enum Move { NONE, DOWN, UP, RIGHT, LEFT }

  private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");
  private static final Pattern INTEGER_TYPED = Pattern.compile("[-+]?\\d*");
  private static final Pattern DECIMAL = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
  private static final Pattern DECIMAL_TYPED = Pattern.compile("[-+]?\\d*\\.?\\d*([eE][-+]?\\d*)?");
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ISO_TIME.withResolverStyle(ResolverStyle.STRICT);
  // A date, a T or a space, and a time with an offset or without: 2026-11-13 14:30.
  private static final DateTimeFormatter DATE_TIME = new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .append(DateTimeFormatter.ISO_LOCAL_DATE)
      .optionalStart().appendLiteral('T').optionalEnd()
      .optionalStart().appendLiteral(' ').optionalEnd()
      .append(DateTimeFormatter.ISO_TIME)
      .toFormatter(Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT);

  // Types

  /** Whether an empty editor means empty text in this column, rather than NULL. */
  static boolean textLike(Column c) {
    return c.editing().choices().isEmpty() && switch (c.type()) {
      case INTEGER, DECIMAL, BOOLEAN, DATE, TIME, DATE_TIME, ENUM -> false;
      default -> true;
    };
  }

  /** Whether the column chooses from a list: an enumeration's. */
  static boolean choosing(Column c) {
    return c.editing().editor() == null && !c.editing().choices().isEmpty();
  }

  /** Whether the column switches in the cell, without an editor. */
  static boolean switching(Column c) {
    return c.type() == DataType.BOOLEAN && c.editing().editor() == null && !choosing(c);
  }

  /** Whether typing may lead to {@code text}, which a number column refuses otherwise. */
  static boolean accepts(Column c, String text) {
    return switch (c.type()) {
      case INTEGER -> INTEGER_TYPED.matcher(text).matches();
      case DECIMAL -> DECIMAL_TYPED.matcher(text).matches();
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
      return c.editing().nullable() ? null : "A value is required";
    }
    if (!c.editing().choices().isEmpty()) {
      return c.editing().choices().contains(value) ? null : "Not one of the choices";
    }
    return switch (c.type()) {
      case INTEGER -> INTEGER.matcher(value).matches() ? null : "Not a whole number";
      case DECIMAL -> DECIMAL.matcher(value).matches() ? null : "Not a number";
      case DATE -> parses(DATE, value) ? null : "Expected a date such as 2026-11-13";
      case TIME -> parses(TIME, value) ? null : "Expected a time such as 14:30 or 14:30:15";
      case DATE_TIME -> parses(DATE_TIME, value) ? null
          : "Expected a date and time such as 2026-11-13 14:30";
      default -> null;
    };
  }

  private static boolean parses(DateTimeFormatter format, String value) {
    try {
      format.parse(value.strip());
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
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
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    if (switching(c)) {
      toggle(b, row, column);
      return;
    }
    String value = value(b, row, column);
    sel.editRow = row;
    sel.editColumn = column;
    sel.original = value;
    sel.error = null;
    sel.edits++;
    sel.draft = typed != null ? typed : value;
    sel.multiline = c.editing().editor() == null && textLike(c)
        && (multiline || sel.draft != null && (sel.draft.indexOf('\n') >= 0
            || sel.draft.indexOf('\r') >= 0));
    sel.choices.close();
    if (choosing(c)) {
      List<Menu.Item> items = choiceItems(b, c);
      int at = typed == null ? indexOf(c, value) : Menu.startingWith(items, -1, typed);
      if (typed != null && at < 0) {
        at = indexOf(c, value);
      }
      sel.choices.open();
      sel.choices.highlight(0, at);
      sel.draft = value;
    }
  }

  /** Hands the editor's value on and moves the cursor, or keeps the editor open with a message. */
  static void commit(Build b, Move move) {
    Selection sel = b.sel;
    if (!sel.isEditing()) {
      return;
    }
    int row = sel.editRow;
    int column = sel.editColumn;
    Column c = b.columns.get(column);
    String value = normalize(c, sel.draft);
    String error = c.editing().editor() == null ? check(c, value) : null;
    boolean appended = false;
    if (error == null && !Objects.equals(value, sel.original)) {
      if (row >= b.rowCount) {
        b.o.onInsertRow.accept(b.rowCount - 1);
        appended = true;
      }
      error = b.o.onCommit.commit(row, column, value);
    }
    if (error != null) {
      sel.error = error;
      if (appended) {
        sel.original = null; // the row is the app's now, and empty
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
      default -> { }
    }
    int toRow = r;
    int toColumn = c;
    Selection sel = b.sel;
    DataGrid.change(b, () -> sel.select(toRow, toColumn));
    sel.start = b.cells && toColumn < b.frozen;
    sel.edgeColumn = -1;
    sel.moves++;
  }

  /** Switches a boolean: false, true, NULL where the column allows it, and false again. */
  static void toggle(Build b, int row, int column) {
    Column c = b.columns.get(column);
    String value = value(b, row, column);
    String next = value == null || !truthy(value) ? (value == null ? "false" : "true")
        : c.editing().nullable() ? null : "false";
    if (row >= b.rowCount) {
      b.o.onInsertRow.accept(b.rowCount - 1);
    }
    b.o.onCommit.commit(row, column, next); // a refusal leaves the cell as it was
  }

  /** The keys of the editor, which reach it from its field or the app's own editor. */
  static boolean editKey(Build b, Key k) {
    Selection sel = b.sel;
    if (!sel.isEditing()) {
      return false;
    }
    Column c = b.columns.get(sel.editColumn);
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
          if (sel.multiline || c.editing().editor() != null || !textLike(c)) {
            return false;
          }
          sel.multiline = true;
          return true;
        }
        if (mods == Key.CONTROL) {
          commit(b, Move.NONE);
          return true;
        }
        if (sel.multiline || mods != 0 && mods != Key.SHIFT) {
          return false;
        }
        commit(b, mods == 0 ? Move.DOWN : Move.UP);
        return true;
      }
      case "Tab" -> {
        if (sel.multiline || (mods & ~Key.SHIFT) != 0) {
          return false;
        }
        commit(b, mods == 0 ? Move.RIGHT : Move.LEFT);
        return true;
      }
      case "0", "Numpad0" -> {
        if (mods != Key.CONTROL || !c.editing().nullable()) {
          return false;
        }
        sel.draft = null;
        sel.error = null;
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
      // Typed before its field had the focus, in the frame that opened it: it goes on the end.
      Column c = b.columns.get(sel.editColumn);
      String draft = (sel.draft == null ? "" : sel.draft) + text;
      if (c.editing().editor() == null && !choosing(c) && accepts(c, draft)) {
        sel.draft = draft;
        sel.error = null;
      }
      return;
    }
    int row = sel.row();
    int column = Math.clamp(sel.column(), 0, n - 1);
    if (!b.edits || !editable(b, row, column)) {
      return;
    }
    Column c = b.columns.get(column);
    if (switching(c)) {
      return; // Space switches it, and nothing else types into it
    }
    if (c.editing().editor() != null) {
      start(b, row, column, null, false);
    } else if (choosing(c) || accepts(c, text)) {
      start(b, row, column, text, false);
    }
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
    if (o.onInsertRow != null && (mods == 0 && name.equals("Insert")
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
    if (o.onDeleteRows != null && control
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
      if (mods == Key.ALT && name.equals("Enter") && c.editing().editor() == null
          && textLike(c)) {
        start(b, row, column, null, true);
        return true;
      }
      if (mods == 0 && name.equals("Space") && switching(c)) {
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
        paste(b);
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

  /** Clears the selected cells, or sets them NULL: in one change. */
  static void clear(Build b, boolean toNull) {
    List<CellValue> cells = new ArrayList<>();
    for (int[] at : targets(b)) {
      Column c = b.columns.get(at[1]);
      if (c.editing().nullable()) {
        cells.add(new CellValue(at[0], at[1], null));
      } else if (!toNull && textLike(c)) {
        cells.add(new CellValue(at[0], at[1], ""));
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
    List<Menu.Item> mine = new ArrayList<>();
    if (o.onPaste != null && o.clipboard != null) {
      mine.add(Menu.item("Paste", () -> paste(b)).shortcut("Ctrl+V"));
    }
    if (o.onPaste != null) {
      mine.add(Menu.item("Set NULL", () -> clear(b, true)).shortcut("Ctrl+0"));
    }
    if (o.onInsertRow != null) {
      mine.add(Menu.item("Insert row", () -> {
        int after = b.sel.row() >= 0 ? Math.min(b.sel.row(), b.rowCount - 1) : b.rowCount - 1;
        o.onInsertRow.accept(after);
      }).shortcut("Insert"));
    }
    if (o.onDeleteRows != null) {
      mine.add(Menu.item("Delete rows", () -> {
        List<Range> rows = rowsOf(b);
        if (!rows.isEmpty()) {
          o.onDeleteRows.accept(rows);
        }
      }).shortcut("Ctrl+Delete"));
    }
    if (!mine.isEmpty() && !items.isEmpty()) {
      items.add(Menu.separator());
    }
    items.addAll(mine);
  }

  // The editor

  /**
   * The editor over the cell, absolute in its row: a field in the cell's font, its text where the
   * cell's is, growing up to the grid's edge for a long value; the app's editor; or, while the
   * list of an enumeration or the field of several lines edits, what they hold. Its edge is the
   * accent's, or the danger colour's with the message below it when the value was refused.
   */
  static El editor(Build b, int row, int column) {
    Theme t = b.t;
    Theme.Grid g = b.g;
    Selection sel = b.sel;
    Column c = b.columns.get(column);
    float stroke = t.size().stroke();
    float w = g.cursorWidth();
    float pad = Math.max(t.space().s() - w, 0);
    boolean right = c.align() == DataGrid.Align.RIGHT;
    Theme.TextStyle style = c.mono() ? t.type().mono() : t.type().body();
    float cell = Math.max(c.width() - stroke, 0);
    El box = row().key("editor").h(g.rowHeight() - stroke).align(Style.Align.CENTER)
        .justify(right ? Style.Content.END : Style.Content.START)
        .padding(0, pad)
        .fill(t.surface().raised()).radius(t.radius().small())
        .border(w, sel.error == null ? t.accent().edge() : t.status().danger())
        .keepFocus().onPress(() -> { }) // a press on its edge neither drags nor takes the focus
        .onKey(k -> editKey(b, k));
    float width = cell;
    if (c.editing().editor() != null) {
      box.child(c.editing().editor().editor(sel.draft, v -> {
        sel.draft = v;
        sel.error = null;
      }).autofocus(sel.edits));
    } else if (choosing(c) || sel.multiline) {
      // The list or the field of several lines edits it; here it shows what they hold.
      El shown = DataGrid.text(t, c.mono(),
          sel.draft == null ? "NULL" : DataGrid.oneLine(sel.draft),
          sel.draft == null ? t.text().muted() : t.text().primary());
      box.child(right ? shown : shown.grow(1));
      if (choosing(c)) {
        box.gap(t.space().xs()).child(Icon.icon(t, Select.OPENS, Icon.Variant.MUTED));
      }
    } else {
      String text = sel.draft == null ? "" : sel.draft;
      float caret = t.space().xs(); // room for the caret after the text, which then never scrolls
      float wide = DataGrid.natural(El.text(text).font(style.font().get())
          .fontSize(style.size())) + caret;
      float frame = 2 * (pad + w);
      width = Math.max(cell, Math.min(wide + frame, room(b, column, right)));
      El field = El.text(text).editable(v -> edit(c, sel, v)).key("field")
          .label("Edit " + c.title())
          .font(style.font().get()).fontSize(style.size()).color(t.text().primary())
          .selectionColor(t.accent().selection()).hFull().minSize(0, 0)
          .focusRing(0) // the editor's edge says where the keys go
          .autofocus(sel.edits);
      if (sel.draft == null) {
        field.value("NULL");
        if (c.editing().nullable()) {
          field.placeholder("NULL", t.text().muted());
        }
      } else if (text.isEmpty() && c.editing().nullable() && textLike(c)) {
        field.placeholder("empty", t.text().muted()); // not NULL: Control+0 makes it that
      }
      if (right) {
        // As wide as the text, at the right, where the cell's text ends: it does not jump.
        field.w(Math.min(wide, width - frame));
        box.padding(0, Math.max(pad - caret, 0), 0, pad);
      } else {
        field.grow(1);
      }
      box.child(field);
    }
    box.absolute(right ? b.xs[column + 1] - stroke - width : b.xs[column], 0).w(width);
    if (column < b.frozen) {
      box.fixedX();
    }
    if (!sel.multiline && !choosing(c)) {
      box.onPressOutside(() -> commit(b, Move.NONE));
    }
    if (sel.error != null && !sel.multiline) {
      box.invalid(true).overlay(col().key("error").role("alert").label(sel.error)
          .padding(t.space().xs(), t.space().s())
          .radius(t.radius().small())
          .fill(t.surface().raised())
          .border(stroke, t.status().danger())
          .shadow(t.elevation().popup())
          .child(Label.label(t, sel.error))
          .side(Side.BOTTOM, Style.Align.START, t.space().xs()));
    }
    return box;
  }

  /** How wide an editor of this column may grow: to the edge of the grid's part in view. */
  private static float room(Build b, int column, boolean right) {
    Selection sel = b.sel;
    if (sel.gridWidth <= 0) {
      return 0;
    }
    double scroll = column < b.frozen ? 0 : sel.gridScroll.x();
    if (right) {
      double left = scroll + (column < b.frozen ? 0 : b.fixedWidth());
      return (float) (b.xs[column + 1] - b.t.size().stroke() - left);
    }
    return (float) (scroll + sel.gridWidth - b.room - b.xs[column]);
  }

  private static void edit(Column c, Selection sel, String text) {
    if (!accepts(c, text)) {
      return; // refused: the field shows the draft as it was
    }
    sel.draft = text;
    sel.error = null;
  }

  /**
   * For the list of an enumeration and the field of several lines, which are overlays that take
   * the focus and so cannot lie in a row: an element over the cell, first in the grid's content,
   * that they hang on. It moves sideways with the content; up and down it is where the cell was
   * when the view was built.
   */
  static void anchor(Build b, El content) {
    Selection sel = b.sel;
    Column c = b.columns.get(sel.editColumn);
    boolean list = choosing(c) && sel.choices.isOpen();
    if (!sel.multiline && !list) {
      return;
    }
    float stroke = b.t.size().stroke();
    float height = b.g.rowHeight() - stroke;
    double top = b.g.rowHeight() + stroke + (double) sel.editRow * b.g.rowHeight()
        - sel.rowsScroll.y();
    El anchor = el().key("edit-anchor").absolute(b.xs[sel.editColumn], (float) top)
        .size(Math.max(c.width() - stroke, 1), height);
    if (sel.editColumn < b.frozen) {
      anchor.fixedX();
    }
    content.child(anchor.overlay(list ? choices(b, c) : multiline(b, c, height)));
  }

  /** The choices of an enumeration, and NULL where the column allows it, after a line. */
  private static List<Menu.Item> choiceItems(Build b, Column c) {
    Selection sel = b.sel;
    List<Menu.Item> items = new ArrayList<>();
    for (String choice : c.editing().choices()) {
      items.add(Menu.item(choice, () -> choose(b, choice, Move.NONE))
          .checked(choice.equals(sel.original)));
    }
    if (c.editing().nullable()) {
      items.add(Menu.separator());
      items.add(Menu.item("NULL", () -> choose(b, null, Move.NONE))
          .checked(sel.original == null && sel.isEditing()));
    }
    return items;
  }

  private static int indexOf(Column c, String value) {
    List<String> choices = c.editing().choices();
    return value == null ? (c.editing().nullable() ? choices.size() + 1 : -1)
        : choices.indexOf(value);
  }

  private static void choose(Build b, String value, Move move) {
    b.sel.draft = value;
    commit(b, move);
  }

  /**
   * The list of an enumeration below the cell, the current choice checked and the one typed
   * highlighted: the menu's keys and letters move, Enter and Tab choose and go on as in a field,
   * Escape and a press elsewhere leave the value as it was.
   */
  private static El choices(Build b, Column c) {
    Selection sel = b.sel;
    List<Menu.Item> items = choiceItems(b, c);
    El menu = Menu.menu(b.t, sel.choices, items).side(Side.BOTTOM, Style.Align.STRETCH, 0);
    Predicate<Key> own = menu.onKey();
    return menu.onDismiss(sel::cancelEdit).onKey(k -> {
      int mods = k.modifiers();
      boolean enter = mods == 0 && k.name().equals("Enter");
      boolean tab = k.name().equals("Tab") && (mods & ~Key.SHIFT) == 0;
      if (enter || tab) {
        int active = sel.choices.active();
        if (active < 0 || items.get(active).isSeparator()) {
          sel.cancelEdit();
        } else {
          choose(b, active < c.editing().choices().size()
              ? c.editing().choices().get(active) : null,
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
    Theme t = b.t;
    Selection sel = b.sel;
    Theme.TextStyle style = c.mono() ? t.type().mono() : t.type().body();
    String label = "Edit " + c.title();
    El area = TextField.textArea(t, sel.draft == null ? "" : sel.draft,
            sel.draft == null && c.editing().nullable() ? "NULL" : null, v -> edit(c, sel, v))
        .key("text").label(label).font(style.font().get()).fontSize(style.size())
        .lines(6).w(Math.max(c.width(), 2 * t.size().field())).autofocus();
    if (sel.draft == null) {
      area.value("NULL");
    }
    if (sel.error != null) {
      TextField.invalid(t, area);
    }
    El panel = col().key("multiline").role("dialog").label(label).modal()
        .onDismiss(() -> commit(b, Move.NONE))
        .onKey(k -> editKey(b, k))
        .padding(t.space().m()).gap(t.space().s())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .child(area);
    if (sel.error != null) {
      panel.child(Label.label(t, sel.error).key("error").role("alert").label(sel.error)
          .color(t.status().danger()));
    }
    return panel.child(row().align(Style.Align.CENTER).gap(t.space().s()).children(
            Label.label(t, Label.Variant.MUTED, "Ctrl+Enter to save").grow(1),
            Button.button(t, Button.Variant.SECONDARY, "Cancel", sel::cancelEdit),
            Button.button(t, Button.Variant.PRIMARY, "Save", () -> commit(b, Move.NONE))))
        .side(Side.BOTTOM, Style.Align.START, -height);
  }
}
