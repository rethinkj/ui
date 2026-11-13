package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.TextField.textField;

import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.DataGrid.Build;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Layout;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The columns of a data grid as the header changes them: its context menu, the chooser of
 * columns ({@link Layout}) and moving a column by dragging its header cell.
 */
final class GridColumns {

  private GridColumns() {}

  /** What a header cell takes besides sorting: its menu, its chooser and its drag. */
  static void cell(Build b, int index, El cell) {
    Selection sel = b.sel;
    if (sel == null) {
      return;
    }
    DataGrid.Options o = b.o;
    if (o.onSort != null && b.columns.get(index).sortable() || b.filtering() || o.layout != null
        || o.onFreeze != null || o.onResize != null) { // as items(b, index) has some
      cell.onSecondaryPress((x, y) -> {
        sel.headerColumn = index;
        sel.headerMenu.openAt(x, y);
      });
    }
    Layout l = b.o.layout;
    if (l != null) {
      if (l.chooser.isOpen() && l.from == b.o.full[index]) {
        cell.overlay(chooser(b.t, l).side(Side.BOTTOM, Style.Align.START, b.t.space().xs()));
      }
      if (index >= b.frozen) {
        cell.onDrag(d -> drag(b, index, d)).grabbed(sel.dragColumn == index);
      }
    }
  }

  /** The header's menu, and the line where a dragged column would go. */
  static void header(Build b, El header) {
    Selection sel = b.sel;
    if (sel == null) {
      return;
    }
    int n = b.columns.size();
    if (sel.headerColumn < 0 || sel.headerColumn >= n) {
      sel.headerMenu.close();
    } else {
      header.onSecondaryPress((x, y) -> { }); // beside the cells it opens nothing
      Menu.contextMenu(b.t, header, sel.headerMenu, items(b, sel.headerColumn));
    }
    if (sel.dragColumn >= 0 && sel.dropAt >= b.frozen && sel.dropAt <= n) {
      float line = b.t.size().indicator();
      header.child(el().key("marker").absolute(b.xs[sel.dropAt] - line / 2, 0)
          .size(line, b.g.rowHeight()).fill(b.t.accent().edge()));
    }
  }

  /** The items of the menu of a header cell, as far as the grid does what they do. */
  static List<Menu.Item> items(Build b, int c) {
    DataGrid.Options o = b.o;
    Column column = b.columns.get(c);
    List<Menu.Item> items = new ArrayList<>();
    if (o.onSort != null && column.sortable()) {
      boolean sorted = o.sort.stream().anyMatch(k -> k.column() == c);
      items.add(Menu.item("Sort ascending",
          () -> o.onSort.accept(List.of(new SortKey(c, false)))).icon(Symbol.ARROW_SORT_UP));
      items.add(Menu.item("Sort descending",
          () -> o.onSort.accept(List.of(new SortKey(c, true)))).icon(Symbol.ARROW_SORT_DOWN));
      items.add(Menu.item("Clear sort", !sorted ? null : () -> o.onSort.accept(
          o.sort.stream().filter(k -> k.column() != c).toList())));
    }
    if (b.filtering()) {
      items.add(Menu.item("Filter…", () -> GridFilter.open(b, c)).icon(Symbol.FILTER)
          .shortcut("Alt+Down"));
    }
    List<Menu.Item> columns = new ArrayList<>();
    if (o.layout != null) {
      Layout l = o.layout;
      int full = o.full[c];
      columns.add(Menu.item("Hide column", l.shownCount() < 2 ? null : () -> l.hide(full))
          .icon(Symbol.EYE_OFF));
    }
    if (o.onFreeze != null) {
      columns.add(c < b.frozen ? Menu.item("Unfreeze columns", () -> o.onFreeze.accept(0))
          : Menu.item("Freeze up to here", () -> o.onFreeze.accept(c + 1)).icon(Symbol.PIN));
    }
    if (o.onResize != null) {
      columns.add(Menu.item("Fit width", () -> o.onResize.resize(c, DataGrid.fit(b, c))));
    }
    if (o.layout != null) {
      Layout l = o.layout;
      int full = o.full[c];
      columns.add(Menu.item("Columns…", () -> {
        l.from = full;
        l.search = "";
        l.chooser.open();
      }).icon(Symbol.COLUMN_SINGLE));
    }
    if (!items.isEmpty() && !columns.isEmpty()) {
      items.add(Menu.separator());
    }
    items.addAll(columns);
    return items;
  }

  /**
   * Moves the dragged column: past the threshold the header shows a line at the gap nearest the
   * pointer among the columns that are not frozen, and the release moves the column there.
   */
  private static void drag(Build b, int index, Drag d) {
    Selection sel = b.sel;
    if (d.phase() == Drag.Phase.CANCEL) {
      sel.dragColumn = -1;
      return;
    }
    double x = b.xs[index] + d.x();
    int n = b.columns.size();
    int at = b.frozen;
    for (int g = b.frozen; g <= n; g++) {
      if (Math.abs(b.xs[g] - x) < Math.abs(b.xs[at] - x)) {
        at = g;
      }
    }
    if (d.phase() == Drag.Phase.END) {
      sel.dragColumn = -1;
      if (at != index && at != index + 1) {
        b.o.layout.moveBefore(b.o.full[index], at < n ? b.o.full[at] : -1);
      }
      return;
    }
    sel.dragColumn = index;
    sel.dropAt = at;
  }

  /** {@link DataGrid#columnsButton}. */
  static El button(Theme t, Layout l) {
    El button = Button.button(t, Button.Variant.GHOST, Symbol.COLUMN_SINGLE, "Columns",
        l::toggleChooser).key("columns-button").expanded(l.chooser.isOpen() && l.from < 0);
    return button.overlay(l.chooser.isOpen() && l.from < 0
        ? chooser(t, l).side(Side.BOTTOM, Style.Align.END, t.space().xs()) : null);
  }

  /**
   * The chooser: a box per column to show or hide it, in the order shown, which Alt+Arrow Up and
   * Down change for the focused one; a field that narrows the list where there are many; Show all
   * and Reset.
   */
  static El chooser(Theme t, Layout l) {
    String search = l.search.toLowerCase(Locale.ROOT).strip();
    El list = col().key("columns").role("list").label("Columns").gap(t.space().xs())
        .padding(t.space().xs(), t.space().s()).scroll().scrollbars(t.scrollbars())
        .maxSize(Float.POSITIVE_INFINITY, 10 * (t.size().control() - t.space().xs()));
    for (int full : l.order()) {
      String title = l.titles.get(full);
      if (!search.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(search)) {
        continue;
      }
      boolean shown = !l.isHidden(full);
      list.child(Checkbox.checkbox(t, shown, title,
              !shown || l.shownCount() > 1 ? () -> {
                if (l.isHidden(full)) {
                  l.show(full);
                } else {
                  l.hide(full);
                }
              } : null)
          .key("show-" + full)
          .onKey(k -> {
            boolean up = k.name().equals("ArrowUp");
            if (k.modifiers() != Key.ALT || !up && !k.name().equals("ArrowDown")) {
              return false;
            }
            l.step(full, up ? -1 : 1);
            return true;
          }));
    }
    El content = col().gap(t.space().s())
        .child(Label.label(t, Label.Variant.STRONG, "Columns").role("heading").level(3));
    if (l.titles.size() > 8) {
      content.child(TextField.withIcon(t, textField(t, l.search, "Search columns",
          v -> l.search = v).key("search").w(t.size().field()).autofocus(), Symbol.SEARCH));
    }
    content.child(list).child(Label.label(t, Label.Variant.MUTED, "Alt+↑ and Alt+↓ move one"))
        .child(row().gap(t.space().s()).justify(Style.Content.END).children(
            Button.button(t, Button.Variant.GHOST, "Show all",
                l.shownCount() == l.titles.size() ? null : l::showAll),
            Button.button(t, Button.Variant.SECONDARY, "Reset", l::reset)));
    return Popover.panel(t, l.chooser, "Columns", content).key("chooser");
  }
}
