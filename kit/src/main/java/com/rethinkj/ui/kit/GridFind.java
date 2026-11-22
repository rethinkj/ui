package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.Build;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find in a {@link DataGrid} ({@link DataGrid.Options#find}): a floating bar at the grid's top
 * right reusing {@link Find}'s look and controls, match highlighting in visible cells, and
 * time-budgeted sliced scanning on the app's thread so million-row grids stay responsive without
 * calling cell functions from another thread.
 */
final class GridFind {

  private GridFind() {}

  /** What a scan and its cached match positions belong to. */
  record Scope(
      Search.Query query,
      int rowCount,
      List<String> columns,
      List<Integer> layout,
      List<SortKey> sort,
      List<Filter> filters,
      int edits,
      int revision,
      boolean editing) {}

  /** A pending jump to the next or previous match, continued across frames via {@code App.after}. */
  static final class Jump {
    final Scope scope; // the rows and columns it walks; another build's end it
    final int direction; // +1 forward, -1 backward
    int curRow;
    int curCol;
    long remaining;

    Jump(Scope scope, int direction, int curRow, int curCol, long remaining) {
      this.scope = scope;
      this.direction = direction;
      this.curRow = curRow;
      this.curCol = curCol;
      this.remaining = remaining;
    }
  }

  /**
   * Updates the find state for this build: starts a scan, with its first slice, when what it covers
   * changed, and asks a frame to go on with what is left ({@link #schedule}).
   */
  static void prepare(Build b) {
    DataGrid.Find f = b.o.find;
    if (f == null) {
      return;
    }
    f.build = b;
    f.app = b.o.findApp;
    f.texts = b.texts;
    if (!f.open) {
      f.findField = null;
      if (f.jump == null) {
        f.cancel();
        return;
      }
    }
    Search.Query query = compile(f, b.texts);
    if (f.pattern == null) {
      f.resetSearch();
      return;
    }
    f.slowCell = false; // a cell too slow to mark stops the marks of this build only
    Scope now = scopeOf(b, query);
    if (f.jump != null && !now.equals(f.jump.scope)) {
      f.jump = null; // its rows are gone: another F3 starts again
    }
    if (f.open && !now.equals(f.scope)) {
      startScan(f, b, now);
      stepScan(f, b, Search.deadline(Find.SYNC_MILLIS));
    }
    if (f.jump != null || f.open && !f.complete) {
      schedule(f);
    }
  }

  /** Opens the bar, or focuses and selects its field if already open. */
  static void open(DataGrid.Find f) {
    boolean wasOpen = f.open;
    f.open = true;
    f.opened++;
    f.slowCell = false;
    f.selectQuery = true;
    Build b = f.build;
    if (b == null || b.sel == null) {
      return;
    }
    Selection sel = b.sel;
    if (!wasOpen) {
      if (sel.row() >= 0 && sel.row() < b.rowCount) {
        f.originRow = sel.row();
        f.originCol = Math.max(sel.column(), 0);
      } else {
        f.originRow = Math.max(sel.firstRowInView(), 0);
        f.originCol = 0;
      }
    }
    if (wasOpen && f.findField != null && f.app != null) {
      f.selectQuery = false;
      f.app.focus(f.findField);
      f.app.select(f.findField, f.query.length(), 0);
    }
    if (!f.query.isEmpty()) {
      changed(f);
    }
  }

  /** Handles keys while the grid has the focus (and no cell editor is open). */
  static boolean gridKey(Build b, Key k) {
    DataGrid.Find f = b.o.find;
    if (f == null) {
      return false;
    }
    int mods = k.modifiers();
    switch (k.name()) {
      case "F" -> {
        if (mods != Key.CONTROL) {
          return false;
        }
        open(f);
      }
      case "F3" -> {
        if (mods != 0 && mods != Key.SHIFT) {
          return false;
        }
        go(f, mods == Key.SHIFT ? -1 : 1);
      }
      case "Escape" -> {
        if (mods != 0 || !f.open) {
          return false;
        }
        dismiss(f);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /** Closes the bar and gives the focus back to its grid. */
  private static void dismiss(DataGrid.Find f) {
    f.close();
    if (f.app != null && f.build != null && f.build.grid != null) {
      f.app.focus(f.build.grid);
    }
  }

  /** The height of the strip below the header that holds the open bar, clear of the rows. */
  static float strip(Build b) {
    DataGrid.Find f = b.o.find;
    if (f == null || !f.open || b.sel == null) {
      return 0;
    }
    return Find.barHeight(b.theme, 1) + 2 * b.theme.space().xs();
  }

  /** Handles keys anywhere inside the find bar. */
  private static boolean barKey(Build b, Key k) {
    DataGrid.Find f = b.o.find;
    int mods = k.modifiers();
    String name = k.name();
    if (mods == Key.ALT) {
      switch (name) {
        case "C" -> f.matchCase = !f.matchCase;
        case "R" -> f.regex = !f.regex;
        default -> {
          return false;
        }
      }
      changed(f);
      return true;
    }
    return gridKey(b, k);
  }

  /**
   * Whether the focus is in the bar: a key it leaves goes on to the grid it lies over, which lets
   * it pass to the app without acting on it, as Control+Minus would delete the selected rows.
   */
  static boolean focused(Build b) {
    DataGrid.Find f = b.o.find;
    return f != null && f.open && f.app != null && shown(f.bar, f.app.focused());
  }

  /** The query or a switch changed: restart the search from where the bar opened. */
  private static void changed(DataGrid.Find f) {
    f.slowCell = false;
    f.resetSearch();
    compile(f, f.texts);
    Build b = f.build;
    if (f.pattern == null || b == null || b.sel == null) {
      return;
    }
    // The first match now; the count in the next build, whose rows are those after the app's
    // onSelect, which selecting it may have run.
    go(f, 0, Search.deadline(Find.SYNC_MILLIS));
  }

  /** Goes to the next (1), previous (-1) or first from origin (0) matching cell, wrapping round. */
  static void go(DataGrid.Find f, int direction) {
    go(f, direction, Search.deadline(Find.SYNC_MILLIS));
  }

  private static void go(DataGrid.Find f, int direction, long deadline) {
    Build b = f.build;
    if (b == null || b.sel == null || f.query.isEmpty() || b.rowCount == 0 || b.columns.isEmpty()) {
      return;
    }
    Search.Query query = compile(f, f.texts);
    if (f.pattern == null) {
      return;
    }
    int rowCount = b.rowCount;
    int cols = b.columns.size();
    Selection sel = b.sel;
    int fromRow;
    int fromCol;
    if (direction == 0) {
      fromRow = Math.clamp(f.originRow, 0, rowCount - 1);
      fromCol = Math.clamp(f.originCol, 0, cols - 1);
    } else if (direction > 0) {
      if (sel.row() < 0 || sel.row() >= rowCount) {
        fromRow = 0;
        fromCol = 0;
      } else {
        int col = Math.clamp(sel.column(), 0, cols - 1) + 1;
        fromRow = col < cols ? sel.row() : (sel.row() + 1 < rowCount ? sel.row() + 1 : 0);
        fromCol = col < cols ? col : 0;
      }
    } else {
      if (sel.row() < 0 || sel.row() >= rowCount) {
        fromRow = rowCount - 1;
        fromCol = cols - 1;
      } else {
        int col = Math.clamp(sel.column(), 0, cols - 1) - 1;
        fromRow = col >= 0 ? sel.row() : (sel.row() > 0 ? sel.row() - 1 : rowCount - 1);
        fromCol = col >= 0 ? col : cols - 1;
      }
    }

    Scope now = scopeOf(b, query);
    // The scan's matches lead the way, unless it left out rows that were not loaded then.
    boolean trusted = now.equals(f.scope) && !f.skipped;
    long remaining = (long) rowCount * cols;
    int startRow = fromRow;
    int startCol = fromCol;
    int cached = -1;
    if (trusted && f.complete && !f.more && !f.stopped) {
      if (f.matchCount == 0) {
        f.jump = null;
        return;
      }
      if (direction >= 0) {
        int i = firstAtOrAfter(f, fromRow, fromCol);
        cached = i < f.matchCount ? i : 0;
      } else {
        int i = lastAtOrBefore(f, fromRow, fromCol);
        cached = i >= 0 ? i : f.matchCount - 1;
      }
    } else if (trusted
        && (f.scanRow > 0 || f.scanCol > 0)
        && compareCell(fromRow, fromCol, f.scanRow, f.scanCol) < 0) {
      if (direction >= 0) {
        int i = firstAtOrAfter(f, fromRow, fromCol);
        if (i < f.matchCount) {
          cached = i;
        } else { // no match in [from .. scan): on from where the scan left off
          remaining -= cellIndex(f.scanRow, f.scanCol, cols) - cellIndex(fromRow, fromCol, cols);
          startRow = f.scanRow < rowCount ? f.scanRow : 0;
          startCol = f.scanRow < rowCount ? f.scanCol : 0;
        }
      } else {
        int i = lastAtOrBefore(f, fromRow, fromCol);
        if (i >= 0) {
          cached = i;
        } else { // no match in [(0,0) .. from]: round to the last cell
          remaining -= cellIndex(fromRow, fromCol, cols) + 1;
          startRow = rowCount - 1;
          startCol = cols - 1;
        }
      }
    }
    if (cached >= 0) {
      if (stillMatches(f, b, f.matchRows[cached], f.matchCols[cached])) {
        f.jump = null;
        selectMatch(b, f.matchRows[cached], f.matchCols[cached]);
        return;
      }
      // The app changed the cell since the scan: search again, from here on directly.
      startScan(f, b, now);
      remaining = (long) rowCount * cols;
      startRow = fromRow;
      startCol = fromCol;
    }

    if (remaining <= 0) {
      f.jump = null;
      return;
    }
    f.jump = new Jump(now, direction >= 0 ? 1 : -1, startRow, startCol, remaining);
    if (!stepJump(f, b, query, deadline)) {
      schedule(f);
    }
  }

  /** Builds the floating find bar overlay for the grid. */
  static El bar(Build b) {
    DataGrid.Find f = b.o.find;
    Theme theme = b.theme;
    Texts texts = b.texts;
    El field = TextField.textField(f.query, texts.find(), v -> {
          f.query = v;
          changed(f);
        })
        .key("find-field")
        .label(texts.find())
        .autofocus(f.opened)
        .onSelect((caret, anchor) -> {
          if (f.selectQuery && f.findField != null && f.app != null) {
            f.selectQuery = false;
            f.app.select(f.findField, f.query.length(), 0);
          }
        })
        .onKey(k -> {
          boolean enter = (k.name().equals("Enter") || k.name().equals("NumpadEnter"))
              && (k.modifiers() == 0 || k.modifiers() == Key.SHIFT);
          if (enter) {
            go(f, k.modifiers() == Key.SHIFT ? -1 : 1);
          }
          return enter;
        });
    f.findField = field;
    El query = Find.queryField(
        theme,
        texts,
        field,
        f.error,
        f.matchCase,
        () -> {
          f.matchCase = !f.matchCase;
          changed(f);
        },
        null,
        null,
        f.regex,
        () -> {
          f.regex = !f.regex;
          changed(f);
        });
    El count = Find.countLabel(theme, countText(f, b));
    boolean searchable = !f.query.isEmpty() && f.error == null;
    El findRow = row()
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .child(query)
        .child(count)
        .child(Find.tool(
                theme,
                Symbol.ARROW_UP,
                texts.previousMatch(),
                texts.keys("Shift", "Enter"),
                null,
                searchable ? () -> go(f, -1) : null)
            .key("previous"))
        .child(Find.tool(
                theme,
                Symbol.ARROW_DOWN,
                texts.nextMatch(),
                texts.keys("Enter"),
                null,
                searchable ? () -> go(f, 1) : null)
            .key("next"))
        .child(Find.tool(
                theme, Symbol.DISMISS, texts.close(), texts.keys("Escape"), null, () -> dismiss(f))
            .key("close"));
    El panel = Find.panel(theme).child(findRow);
    // Float below the header at the top right, clear of the vertical scrollbar.
    float topInset = b.g.rowHeight()
        + theme.size().stroke()
        + Find.barHeight(theme, 1)
        + theme.space().xs();
    f.bar = Find.bar(theme, texts, topInset, k -> barKey(b, k), panel);
    return f.bar;
  }

  /** Match spans for one visible cell's display text, or null when none or find is inactive. */
  static Search.Matches cellMatches(
      Build b, boolean loaded, boolean append, String value, boolean current) {
    DataGrid.Find f = b.o.find;
    if (f == null
        || !f.open
        || f.pattern == null
        || f.slowCell && !current
        || !loaded
        || append
        || value == null
        || value.isEmpty()) {
      return null;
    }
    String shown = DataGrid.oneLine(value);
    long deadline = Search.deadline(1);
    try {
      if (!Search.hasMatch(f.pattern, shown, deadline)) {
        return null;
      }
    } catch (Search.Stopped _) {
      f.slowCell = true;
      return null;
    }
    Search.Matches found = Search.scan(f.pattern, shown, 0, shown.length(), Find.LIMIT, deadline);
    if (found.stopped()) {
      f.slowCell = true;
    }
    return found.count() > 0 ? found : null;
  }

  /** Attaches background highlight marks for matched spans to a cell's text element. */
  static void mark(El valueText, Search.Matches matches, int color, boolean current) {
    valueText.highlight(current ? Boolean.TRUE : Boolean.FALSE, (line, before, out) -> {
      for (int k = 0; k < matches.count(); k++) {
        out.background(matches.starts()[k], matches.ends()[k], color);
      }
      return null;
    });
  }

  // ---- Scanning and jumping in time-budgeted slices on the app's thread

  private static Search.Query compile(DataGrid.Find f, Texts texts) {
    f.error = null;
    Search.Query q = new Search.Query(f.query, f.matchCase, false, f.regex);
    try {
      f.pattern = q.compile();
    } catch (PatternSyntaxException e) {
      f.error = Find.patternError(texts, e);
      f.pattern = null;
    }
    return q;
  }

  /** Whether a cell the scan found still matches: the app may have changed it since. */
  private static boolean stillMatches(DataGrid.Find f, Build b, int row, int column) {
    if (b.o.loaded != null && !b.o.loaded.test(row) || b.pending(row)) {
      return false;
    }
    String raw = b.columns.get(column).cell().apply(row);
    try {
      return raw != null
          && Search.hasMatch(f.pattern, DataGrid.oneLine(raw), Search.deadline(Find.SYNC_MILLIS));
    } catch (Search.Stopped _) {
      return true; // too slow to tell: as the scan found it
    }
  }

  private static Scope scopeOf(Build b, Search.Query query) {
    List<String> titles = new ArrayList<>(b.columns.size());
    for (Column column : b.columns) {
      titles.add(column.title());
    }
    List<Integer> layout =
        b.o.full == null ? List.of() : Arrays.stream(b.o.full).boxed().toList();
    int edits = b.sel == null ? 0 : b.sel.edit.edits;
    int revision = b.o.changes == null ? 0 : b.o.changes.revision;
    boolean editing = b.sel != null && b.sel.isEditing();
    return new Scope(
        query,
        b.rowCount,
        List.copyOf(titles),
        layout,
        b.o.sort,
        b.o.filters,
        edits,
        revision,
        editing);
  }

  private static void startScan(DataGrid.Find f, Build b, Scope scope) {
    f.scope = scope;
    f.matchCount = 0;
    f.more = false;
    f.stopped = false;
    f.complete = b.rowCount == 0 || b.columns.isEmpty();
    f.scanRow = 0;
    f.scanCol = 0;
    f.skipped = false;
  }

  private static boolean stepScan(DataGrid.Find f, Build b, long deadline) {
    if (f.complete || f.pattern == null || f.scope == null) {
      return true;
    }
    List<Column> columns = b.columns;
    int rowCount = b.rowCount;
    int cols = columns.size();
    if (rowCount == 0 || cols == 0) {
      f.complete = true;
      return true;
    }
    IntPredicate loaded = b.o.loaded;
    Pattern pattern = f.pattern;
    int checked = 0;
    while (f.scanRow < rowCount) {
      if ((checked & 63) == 0 && System.nanoTime() - deadline > 0) {
        return false;
      }
      int r = f.scanRow;
      boolean unloaded = loaded != null && !loaded.test(r);
      if (unloaded || b.pending(r)) {
        f.skipped |= unloaded; // the count leaves them out: it says so with a +
        f.scanRow++;
        f.scanCol = 0;
        checked++;
        continue;
      }
      while (f.scanCol < cols) {
        if ((checked & 63) == 0 && System.nanoTime() - deadline > 0) {
          return false;
        }
        int c = f.scanCol;
        String raw = columns.get(c).cell().apply(r);
        boolean hit = false;
        if (raw != null && !raw.isEmpty()) {
          String text = DataGrid.oneLine(raw);
          try {
            hit = Search.hasMatch(pattern, text, deadline);
          } catch (Search.Stopped _) {
            if (checked > 0) {
              return false;
            }
            f.stopped = true;
            f.complete = true;
            return true;
          }
        }
        checked++;
        if (hit) {
          if (f.matchCount == Find.LIMIT) {
            f.more = true;
            f.complete = true;
            return true;
          }
          if (f.matchCount == f.matchRows.length) {
            int next = Math.clamp(f.matchRows.length * 2L, 16, Find.LIMIT);
            f.matchRows = Arrays.copyOf(f.matchRows, next);
            f.matchCols = Arrays.copyOf(f.matchCols, next);
          }
          f.matchRows[f.matchCount] = r;
          f.matchCols[f.matchCount] = c;
          f.matchCount++;
        }
        f.scanCol++;
      }
      f.scanRow++;
      f.scanCol = 0;
    }
    f.complete = true;
    return true;
  }

  private static boolean stepJump(DataGrid.Find f, Build b, Search.Query query, long deadline) {
    Jump j = f.jump;
    if (j == null || f.pattern == null) {
      return true;
    }
    List<Column> columns = b.columns;
    int rowCount = b.rowCount;
    int cols = columns.size();
    if (rowCount == 0 || cols == 0) {
      f.jump = null;
      return true;
    }
    IntPredicate loaded = b.o.loaded;
    Pattern pattern = f.pattern;
    int checked = 0;
    while (j.remaining > 0) {
      if ((checked & 63) == 0 && System.nanoTime() - deadline > 0) {
        return false;
      }
      int r = j.curRow;
      int c = j.curCol;
      boolean unloaded = loaded != null && !loaded.test(r);
      if (unloaded || b.pending(r)) {
        f.skipped |= unloaded; // what the scan's matches say leaves these out
        if (j.direction > 0) {
          j.remaining -= cols - c;
          j.curRow = r + 1 < rowCount ? r + 1 : 0;
          j.curCol = 0;
        } else {
          j.remaining -= c + 1;
          j.curRow = r > 0 ? r - 1 : rowCount - 1;
          j.curCol = cols - 1;
        }
        checked++;
        continue;
      }
      String raw = columns.get(c).cell().apply(r);
      boolean hit = false;
      if (raw != null && !raw.isEmpty()) {
        String text = DataGrid.oneLine(raw);
        try {
          hit = Search.hasMatch(pattern, text, deadline);
        } catch (Search.Stopped _) {
          if (checked > 0) {
            return false;
          }
          f.stopped = true;
          f.complete = true;
          f.jump = null;
          return true;
        }
      }
      checked++;
      j.remaining--;
      if (hit) {
        f.jump = null;
        selectMatch(b, r, c);
        return true;
      }
      if (j.direction > 0) {
        if (++j.curCol >= cols) {
          j.curCol = 0;
          if (++j.curRow >= rowCount) {
            j.curRow = 0;
          }
        }
      } else {
        if (--j.curCol < 0) {
          j.curCol = cols - 1;
          if (--j.curRow < 0) {
            j.curRow = rowCount - 1;
          }
        }
      }
    }
    f.jump = null;
    if (f.scope != null && f.scope.query().equals(query) && f.matchCount == 0 && !f.skipped) {
      // Every cell in the grid was checked with no match: no need to rescan for the count.
      f.complete = true;
      f.scanRow = rowCount;
      f.scanCol = 0;
    }
    return true;
  }

  /**
   * Goes on with the jump and the scan for a slice in the next frame, as a handler, so that a match
   * it selects runs the app's onSelect outside the view's build. A grid the view has left out
   * searches no further; its next build asks again.
   */
  private static void schedule(DataGrid.Find f) {
    if (f.scheduled || f.app == null) {
      return;
    }
    f.scheduled = true;
    long gen = f.gen;
    f.app.after(0, () -> {
      if (gen != f.gen) {
        return;
      }
      f.scheduled = false;
      Build b = f.build;
      if (b == null || f.pattern == null || !shown(f.app.tree(), b.grid)) {
        return;
      }
      long deadline = Search.deadline(Find.SYNC_MILLIS);
      if (f.jump != null) { // a match it selects ends the action: the next is for the next build
        stepJump(f, b, compile(f, f.texts), deadline);
      } else if (f.open && !f.complete) {
        stepScan(f, b, deadline);
      }
      if (f.jump != null || f.open && !f.complete) {
        schedule(f);
      }
    });
  }

  /** Whether {@code tree} holds {@code grid}, such as the last frame's tree. */
  private static boolean shown(El tree, El grid) {
    if (tree == null || grid == null) {
      return false;
    }
    if (tree == grid) {
      return true;
    }
    for (El child : tree.children()) {
      if (shown(child, grid)) {
        return true;
      }
    }
    for (El overlay : tree.overlays()) {
      if (shown(overlay, grid)) {
        return true;
      }
    }
    return false;
  }

  /** Selects the matching cell and ensures the grid scrolls both rows and columns to show it. */
  private static void selectMatch(Build b, int row, int col) {
    Selection sel = b.sel;
    boolean sameRow = sel.row() == row;
    DataGrid.change(b, () -> sel.select(row, col));
    sel.moves++;
    sel.edgeColumn = -1;
    sel.start = false;
    sel.holding = false;
    sel.heldRow = -1;
    sel.heldColumn = -1;
    b.o.find.revealMoves = sel.moves; // the column comes into view, in rows too
    // If the user scrolled away from the sole match on the same row, bring it back into view.
    if (sameRow) {
      int first = sel.firstRowInView();
      int last = sel.lastRowInView();
      if (first >= 0 && (row < first || row > last)) {
        sel.scrollToRow(row);
      }
    }
  }

  private static String countText(DataGrid.Find f, Build b) {
    int current = -1;
    if (f.complete && f.matchCount > 0 && b.sel != null && b.sel.row() >= 0) {
      current = indexOf(f, b.sel.row(), b.sel.column());
    }
    return Find.formatCount(
        b.texts,
        f.query.isEmpty(),
        f.error,
        !f.complete,
        f.stopped,
        f.matchCount,
        !f.more && !f.stopped && !f.skipped,
        current);
  }

  // ---- Row-major cell ordering and binary search over matchRows/matchCols

  private static long cellIndex(int row, int col, int cols) {
    return (long) row * cols + col;
  }

  private static int compareCell(int r1, int c1, int r2, int c2) {
    int dr = Integer.compare(r1, r2);
    return dr != 0 ? dr : Integer.compare(c1, c2);
  }

  private static int indexOf(DataGrid.Find f, int row, int col) {
    int i = firstAtOrAfter(f, row, col);
    return i < f.matchCount && f.matchRows[i] == row && f.matchCols[i] == col ? i : -1;
  }

  private static int firstAtOrAfter(DataGrid.Find f, int row, int col) {
    int low = 0;
    int high = f.matchCount;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (compareCell(f.matchRows[mid], f.matchCols[mid], row, col) < 0) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  private static int lastAtOrBefore(DataGrid.Find f, int row, int col) {
    int low = 0;
    int high = f.matchCount;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (compareCell(f.matchRows[mid], f.matchCols[mid], row, col) <= 0) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low - 1;
  }
}
