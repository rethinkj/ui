package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

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
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * The columns of a data grid as the header changes them: its context menu, the chooser of
 * columns ({@link Layout}) and moving a column by dragging its header cell.
 */
final class GridColumns {

  private GridColumns() {}

  /**
   * What a {@link Selection} holds of the header: its context menu and the column it opened on,
   * a column dragged along it with where it would go, -1 for none, and the columns shown.
   */
  static final class State {
    final Menu.State menu = new Menu.State();
    int menuColumn = -1;
    int dragColumn = -1;
    int dropAt = -1;
    int[] shown; // the app's column of each one shown, as the last build had them

    /** Puts the menu on its column's new place, or closes it where its column is gone. */
    void onColumnsChanged(IntUnaryOperator to) {
      menuColumn = to.applyAsInt(menuColumn);
      if (menuColumn < 0) {
        menu.close();
      }
      dragColumn = -1;
    }
  }

  /** What a header cell takes besides sorting: its menu, its chooser and its drag. */
  static void cell(Build b, int index, El cell) {
    Selection sel = b.sel;
    if (sel == null) {
      return;
    }
    DataGrid.Options o = b.o;
    if (o.onSort != null && b.columns.get(index).sortable()
        || b.filtering(index)
        || o.layout != null
        || o.onFreeze != null
        || o.onResize != null) { // as items(b, index) has some
      cell.onSecondaryPress((x, y) -> {
        sel.columns.menuColumn = index;
        sel.columns.menu.openAt(x, y);
      });
    }
    Layout l = b.o.layout;
    if (l != null && index >= b.frozen) {
      cell.onDrag(d -> drag(b, index, d)).grabbed(sel.columns.dragColumn == index);
    }
  }

  /** The header's menu, and the line where a dragged column would go. */
  static void header(Build b, El header) {
    Selection sel = b.sel;
    if (sel == null) {
      return;
    }
    int n = b.columns.size();
    if (sel.columns.menuColumn < 0 || sel.columns.menuColumn >= n) {
      sel.columns.menu.close();
    } else {
      header.onSecondaryPress((x, y) -> {}); // beside the cells it opens nothing
      Menu.contextMenu(header, sel.columns.menu, items(b, sel.columns.menuColumn));
    }
    Layout l = b.o.layout;
    int at = l == null || !l.chooser.isOpen() || l.from < 0 ? -1 : l.shownNear(l.from, b.o.full);
    if (at >= 0) {
      // Under the header cell it was opened from, or the one in its place once that is hidden: on
      // an element of its own, so that it and its focus stay while the columns change.
      El anchor = el().key("chooser-anchor")
          .absolute(b.xs[at], b.g.rowHeight())
          .size(b.columns.get(at).width(), 0)
          .overlay(chooser(b.theme, l)
              .side(Side.BOTTOM, Style.Align.START, b.theme.space().xs()));
      if (at < b.frozen) {
        anchor.fixedX();
      }
      header.child(anchor);
    }
    if (sel.columns.dragColumn >= 0 && sel.columns.dropAt >= b.frozen && sel.columns.dropAt <= n) {
      float line = b.theme.size().indicator();
      header.child(el().key("marker")
          .absolute(b.xs[sel.columns.dropAt] - line / 2, 0)
          .size(line, b.g.rowHeight())
          .fill(b.theme.accent().edge()));
    }
  }

  /** The items of the menu of a header cell, as far as the grid does what they do. */
  static List<Menu.Item> items(Build b, int c) {
    DataGrid.Options o = b.o;
    Column column = b.columns.get(c);
    Texts texts = b.texts;
    List<Menu.Item> items = new ArrayList<>();
    if (o.onSort != null && column.sortable()) {
      boolean sorted = o.sort.stream().anyMatch(k -> k.column() == c);
      items.add(Menu.item(
              texts.sortAscending(), () -> o.onSort.accept(List.of(new SortKey(c, false))))
          .icon(Symbol.ARROW_SORT_UP));
      items.add(Menu.item(
              texts.sortDescending(), () -> o.onSort.accept(List.of(new SortKey(c, true))))
          .icon(Symbol.ARROW_SORT_DOWN));
      items.add(Menu.item(
          texts.clearSort(),
          !sorted
              ? null
              : () ->
                  o.onSort.accept(o.sort.stream().filter(k -> k.column() != c).toList())));
    }
    if (b.filtering(c)) {
      items.add(Menu.item(texts.filterMenu(), () -> GridFilter.open(b, c))
          .icon(Symbol.FILTER)
          .shortcut(texts.keys("Alt", "Down")));
    }
    List<Menu.Item> columns = new ArrayList<>();
    if (o.layout != null) {
      Layout l = o.layout;
      int full = o.full[c];
      columns.add(Menu.item(texts.hideColumn(), l.shownCount() < 2 ? null : () -> l.hide(full))
          .icon(Symbol.EYE_OFF));
    }
    if (o.onFreeze != null) {
      columns.add(
          c < b.frozen
              ? Menu.item(texts.unfreezeColumns(), () -> o.onFreeze.accept(0))
              : Menu.item(texts.freezeUpToHere(), () -> o.onFreeze.accept(c + 1))
                  .icon(Symbol.PIN));
    }
    if (o.onResize != null) {
      columns.add(Menu.item(texts.fitWidth(), () -> o.onResize.resize(c, DataGrid.fit(b, c))));
      columns.add(Menu.item(texts.fitAllColumns(), () -> DataGrid.fitAll(b)));
    }
    if (o.layout != null) {
      Layout l = o.layout;
      int full = o.full[c];
      columns.add(Menu.item(texts.chooseColumns(), () -> {
            l.from = full;
            l.search = "";
            l.chooser.open();
          })
          .icon(Symbol.COLUMN_SINGLE));
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
      sel.columns.dragColumn = -1;
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
      sel.columns.dragColumn = -1;
      if (at != index && at != index + 1) {
        b.o.layout.moveBefore(b.o.full[index], at < n ? b.o.full[at] : -1);
      }
      return;
    }
    sel.columns.dragColumn = index;
    sel.columns.dropAt = at;
  }

  /** {@link DataGrid#columnsButton}. */
  static El button(Theme theme, Layout l) {
    El button = Button.button(
            Button.Variant.GHOST,
            Symbol.COLUMN_SINGLE,
            Texts.CURRENT.get().columns(),
            l::toggleChooser)
        .key("columns-button")
        .expanded(l.chooser.isOpen() && l.from < 0);
    return button.overlay(
        l.chooser.isOpen() && l.from < 0
            ? chooser(theme, l).side(Side.BOTTOM, Style.Align.END, theme.space().xs())
            : null);
  }

  /** From more than this many entries, a checklist shows a search field. */
  static final int SEARCH_THRESHOLD = 8;

  /** The search field above a long checklist of columns or filter values. */
  static El searchField(Theme theme, String value, String placeholder, Consumer<String> onChange) {
    El box = TextField.searchField(value, placeholder, onChange).w(theme.size().field());
    Form.target(box).key("search").autofocus();
    return box;
  }

  /** Normalizes a checklist's search text for case-insensitive matching. */
  static String searchQuery(String text) {
    return text.toLowerCase(Locale.ROOT).strip();
  }

  /** Whether {@code text} matches a {@link #searchQuery normalized} search query. */
  static boolean matchesSearch(String text, String query) {
    return query.isEmpty() || text.toLowerCase(Locale.ROOT).contains(query);
  }

  /** Limits a checklist column to the height of ten controls, with room for focus rings and bars. */
  static El scrollList(Theme theme, El list) {
    return list.padding(theme.space().xs(), theme.space().s())
        .scroll()
        .scrollbars(theme.scrollbars())
        .maxSize(
            Float.POSITIVE_INFINITY,
            10 * (theme.size().control() - theme.space().xs()));
  }

  /**
   * The chooser: a box per column to show or hide it, in the order shown, which Alt+Arrow Up and
   * Down change for the focused one; a field that narrows the list where there are many; Show all
   * and Reset.
   */
  static El chooser(Theme theme, Layout l) {
    Texts texts = Texts.CURRENT.get();
    String search = searchQuery(l.search);
    El list = scrollList(
        theme,
        col()
            .key("columns")
            .role("list")
            .label(texts.columns())
            .gap(theme.space().xs()));
    for (int full : l.order()) {
      String title = l.titles.get(full);
      if (!matchesSearch(title, search)) {
        continue;
      }
      boolean shown = !l.isHidden(full);
      list.child(Checkbox.checkbox(
              shown,
              title,
              !shown || l.shownCount() > 1
                  ? () -> {
                    if (l.isHidden(full)) {
                      l.show(full);
                    } else {
                      l.hide(full);
                    }
                  }
                  : null)
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
    El content = col()
        .gap(theme.space().s())
        .child(
            Label.label(Label.Variant.STRONG, texts.columns()).role("heading").level(3));
    if (l.titles.size() > SEARCH_THRESHOLD) {
      content.child(searchField(theme, l.search, texts.searchColumns(), v -> l.search = v));
    }
    content
        .child(list)
        .child(Label.label(
            Label.Variant.MUTED, texts.moveOne(texts.keys("Alt", "↑"), texts.keys("Alt", "↓"))))
        .child(row()
            .gap(theme.space().s())
            .justify(Style.Content.END)
            .children(
                Button.button(
                    Button.Variant.GHOST,
                    texts.showAll(),
                    l.shownCount() == l.titles.size() ? null : l::showAll),
                Button.button(Button.Variant.SECONDARY, texts.reset(), l::reset)));
    return Popover.panel(l.chooser, texts.columns(), content).key("chooser");
  }
}
