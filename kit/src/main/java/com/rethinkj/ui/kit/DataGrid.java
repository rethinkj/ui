package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * A table of query results: a header with the column titles over rows of cells, as dense as a
 * database tool shows them, for ten rows or a million. The app owns the data and the state; the
 * grid shows them and reports what the user does:
 *
 * <pre>{@code
 * List<Column> columns = List.of(
 *     new Column("id", s.widths[0], Align.RIGHT, true, i -> String.valueOf(s.ids[i])),
 *     new Column("name", s.widths[1], Align.LEFT, false, i -> s.names[i]));
 * dataGrid(theme, columns, s.count, s.selected, i -> s.selected = i, (c, w) -> s.widths[c] = w,
 *         s.sort, c -> s.sortBy(c))
 *     .key("results").label("Results").grow(1);
 * }</pre>
 *
 * <p>The rows are a virtual list ({@link El#rows}): only those in view and a few more exist, and
 * the cell functions are called for those alone. The header stays in place while the rows scroll
 * up and down, and both scroll left and right together. A press on a row selects it; while the
 * grid has the focus, Up, Down, Page Up, Page Down, Home and End move the selection and the rows
 * follow it; Home and End also with Control, as other tables go to the first and last row with
 * it. The grid is one stop for Tab. Dragging the right edge of a header cell resizes its
 * column, and a press on a header cell sorts. Scrollbars ({@link Theme#scrollbars}) show where the
 * rows in view are among all of them and where the columns in view are: the one along the right
 * edge of the rows, below the header, and the one along the bottom of the grid; dragging the
 * first reaches any of a million rows. The bars lie over the rows, and the grid scrolls far enough
 * to bring the last row and the last column out from under them: the rows past the bar along the
 * bottom, and the columns past the rows' bar, for which the header and the rows have the bar's
 * room after the last column. Like a list of rows, the grid needs a height of its own or room to
 * {@link El#grow}.
 *
 * <p>In a tree dump the grid is {@code role=grid}, its header cells {@code role=columnheader} with
 * the title as label and the key {@code column-<index>}, the edges that resize them {@code
 * role=separator} with the key {@code resize-<index>}, and its rows {@code role=row} with the key
 * {@code row-<index>}, all counted from 0. The thumbs are on the lines of the grid ({@code
 * thumb-x}) and of its rows ({@code thumb-y}, on {@code #rows}).
 */
public final class DataGrid {

  /** Where a column puts its text. Numbers usually go right, in a {@link Column#mono} column. */
  public enum Align {
    LEFT,
    RIGHT
  }

  /**
   * A column of the grid.
   *
   * @param title shown in the header, also the header cell's label
   * @param width in logical pixels, the column's line included; the app keeps it and changes it
   *     when the grid reports a resize
   * @param align where the title and the cells put their text
   * @param mono whether the cells use the monospaced font, as for numbers and timestamps
   * @param cell the text of the cell in the row with this index, or null for SQL NULL, which shows
   *     as a muted {@code NULL}. Called while a frame runs, for the rows that exist only, so it
   *     has to be cheap.
   */
  public record Column(
      String title, float width, Align align, boolean mono, IntFunction<String> cell) {}

  /** The column the rows are sorted by, which shows a marker, and the direction. */
  public record Sort(int column, boolean descending) {}

  /** Told the new width of a column while the user drags its edge. */
  @FunctionalInterface
  public interface Resize {
    void resize(int column, float width);
  }

  private DataGrid() {}

  /** A grid without sorting. */
  public static El dataGrid(
      Theme t, List<Column> columns, int rowCount, int selected, IntConsumer onSelect,
      Resize onResize) {
    return dataGrid(t, columns, rowCount, selected, onSelect, onResize, null, null);
  }

  /**
   * @param rowCount the number of rows, 0 or more
   * @param selected the index of the selected row, or -1 for none
   * @param onSelect told the index of the row the user selects; null makes the rows unselectable
   *     and the grid no stop for Tab
   * @param onResize told the new width of a column, no less than the theme's minimum, while its
   *     edge is dragged, and the old width again if the drag is cancelled; null fixes the widths
   * @param sort the column the rows are sorted by, or null
   * @param onSort told the index of a header cell that is pressed; null for none
   */
  public static El dataGrid(
      Theme t, List<Column> columns, int rowCount, int selected, IntConsumer onSelect,
      Resize onResize, Sort sort, IntConsumer onSort) {
    Theme.Grid g = t.grid();
    float total = 0;
    for (Column column : columns) {
      total += column.width();
    }

    El header = row().key("header").h(g.rowHeight()).shrink(0).fill(g.header());
    El[] lines = new El[columns.size()];
    for (int c = 0; c < columns.size(); c++) {
      lines[c] = line(t);
      header.children(headerCell(t, columns.get(c), c, sort, onSort), lines[c]);
    }
    float edge = 0;
    for (int c = 0; onResize != null && c < columns.size(); c++) {
      Column column = columns.get(c);
      edge += column.width();
      int index = c;
      // The edge goes to the handler of the view the drag began in, with the width of then.
      header.child(
          el().key("resize-" + c).role("separator").label("Resize " + column.title())
              .absolute(edge - g.handle() / 2, 0).size(g.handle(), g.rowHeight())
              .cursor(Cursor.EW_RESIZE)
              .whenHovered(e -> lines[index].fill(t.border().strong()))
              .whenPressed(e -> lines[index].fill(t.accent().color()))
              .onDrag(d -> onResize.resize(index, d.phase() == Drag.Phase.CANCEL
                  ? column.width()
                  : Math.max(column.width() + d.dx(), g.minColumn()))));
    }

    // The rows are built after the grid's focus look has run, so they can read what it found.
    boolean[] focused = {false};
    El body = col().key("rows").grow(1).revealRow(selected).rows(rowCount, g.rowHeight(), i -> {
      int fill = i == selected ? (focused[0] ? g.selected() : g.selectedInactive())
          : i % 2 == 0 ? g.row() : g.stripe();
      // shortcut: every column of a row is built, three elements each, so that a frame costs in
      // proportion to rows times columns; a few dozen columns are fine. Building only the columns
      // in the horizontal view, as the rows are built, lifts that limit.
      El r = row().key("row-" + i).role("row").fill(fill);
      for (Column column : columns) {
        r.children(cell(t, column, column.cell().apply(i)), line(t));
      }
      if (onSelect != null) {
        r.onPress(() -> onSelect.accept(i));
      }
      return i == selected ? r : r.whenHovered(e -> e.fill(over(fill, t.surface().hover())));
    }).scrollbars(t.scrollbars());

    El under = el().h(t.size().stroke()).shrink(0).fill(t.border().subtle());
    // After the last column, room for the rows' bar, which lies at the right end of the rows and
    // moves with them: scrolled to the end, the last column is clear of it.
    Scrollbars bars = t.scrollbars();
    float room = bars.thickness() + 2 * bars.margin();
    El content = col().key("content").w(total + room).children(header, under, body);
    content.style().minWidth = Len.percent(1); // the rows reach across a grid wider than them
    // Header and rows scroll sideways together in the grid; the rows scroll up and down alone.
    El grid = row().role("grid").scrollBoth().scrollbars(t.scrollbars()).fill(g.row())
        .child(content);
    if (onSelect == null) {
      return grid;
    }
    return grid.focusable()
        .focusRing(t.border().focus())
        .whenFocused(e -> focused[0] = true)
        .onKey(k -> {
          boolean ends = k.name().equals("Home") || k.name().equals("End");
          if (k.modifiers() != 0 && !(ends && k.modifiers() == Key.CONTROL) || rowCount == 0) {
            return false;
          }
          int page = Math.max(1, (int) (body.height() / g.rowHeight()));
          int from = selected >= 0 && selected < rowCount ? selected : -1;
          int inView = (int) Math.ceil(body.scrollY() / g.rowHeight());
          int next = switch (k.name()) {
            case "ArrowUp" -> from < 0 ? inView : from - 1;
            case "ArrowDown" -> from < 0 ? inView : from + 1;
            case "PageUp" -> from < 0 ? inView : from - page;
            case "PageDown" -> from < 0 ? inView : from + page;
            case "Home" -> 0;
            case "End" -> rowCount - 1;
            default -> -2;
          };
          if (next == -2) {
            return false;
          }
          next = Math.clamp(next, 0, rowCount - 1);
          if (next != selected) {
            onSelect.accept(next);
          }
          return true;
        });
  }

  private static El headerCell(Theme t, Column column, int index, Sort sort, IntConsumer onSort) {
    El title = Label.label(t, column.title());
    El cell = cell(t, column).key("column-" + index).role("columnheader").label(column.title())
        .gap(t.space().xs());
    if (sort != null && sort.column() == index) {
      El marker = Label.label(t, Label.Variant.MUTED, sort.descending() ? "▼" : "▲");
      cell.children(
          column.align() == Align.RIGHT ? new El[] {marker, title} : new El[] {title, marker});
    } else {
      cell.child(title);
    }
    if (onSort == null) {
      return cell;
    }
    int fill = t.grid().header();
    return cell.cursor(Cursor.POINTER)
        .onPress(() -> onSort.accept(index))
        .whenHovered(e -> e.fill(over(fill, t.surface().hover())))
        .whenPressed(e -> e.fill(over(fill, t.surface().pressed())));
  }

  private static El cell(Theme t, Column column, String text) {
    Theme.TextStyle style = column.mono() ? t.type().mono() : t.type().body();
    return cell(t, column).child(
        El.text(text == null ? "NULL" : text)
            .font(style.font().get())
            .fontSize(style.size())
            .color(text == null ? t.text().muted() : t.text().primary()));
  }

  /** A cell's box: its column's width less the line, which clips what is wider. */
  private static El cell(Theme t, Column column) {
    return row().w(Math.max(column.width() - t.size().stroke(), 0))
        .padding(0, t.space().s())
        .align(Style.Align.CENTER)
        .justify(column.align() == Align.RIGHT ? Style.Content.END : Style.Content.START)
        .clip();
  }

  /** The line after a column, as tall as the row. */
  private static El line(Theme t) {
    return el().w(t.size().stroke()).fill(t.border().subtle());
  }
}
