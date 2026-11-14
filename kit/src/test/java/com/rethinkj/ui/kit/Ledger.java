package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Orders that a user edits in the grid, with a column of every editor and the changes kept in a
 * {@link GridChanges} until a commit: what the tests of editing drive. The grid fills a window of
 * 640 by 260 with a margin, row numbers and the id frozen at the left.
 */
final class Ledger {

  static final List<String> STATUS = List.of("paid", "pending", "shipped", "refunded");

  final Theme theme;
  final int count;
  final GridChanges changes;
  final Selection selection;
  final Menu.State menu = new Menu.State();
  final List<String> log = new ArrayList<>();
  UnaryOperator<DataGrid.Options> more = o -> o;
  String refuse; // what the app says of every value while set, null to take them
  List<DataGrid.Filter> filters = List.of();
  boolean filtering; // the header sets filters, and a bar of them lies above the grid
  List<DataGrid.SortKey> sort = List.of();
  DataGrid.Layout layout;
  java.util.function.Supplier<El> top; // a part at the right above the grid, or null
  DataGrid.CellEditor custom; // the app's own editor of customers, or null
  List<String> choices = STATUS; // of the status column
  App<Ledger> app;

  Ledger(Theme theme, int count) {
    this(theme, count, Mode.CELLS);
  }

  Ledger(Theme theme, int count, Mode mode) {
    this.selection = new Selection(mode);
    this.theme = theme;
    this.count = count;
    this.changes = new GridChanges(count, Ledger::cell);
    selection.select(0, 1);
  }

  /** The app's value of a cell, made up from the row's index. */
  static String cell(int row, int column) {
    return switch (column) {
      case 0 -> String.valueOf(row + 1);
      case 1 -> Orders.customer(row);
      case 2 -> String.valueOf(1 + row % 9);
      case 3 ->
        row % 5 == 4
            ? null
            : String.format(java.util.Locale.ROOT, "%d.%02d", 10 + row * 37 % 500, row * 13 % 100);
      case 4 -> row % 7 == 6 ? null : String.valueOf(row % 3 != 1);
      case 5 -> STATUS.get(row * 7 % STATUS.size());
      case 6 -> String.format(java.util.Locale.ROOT, "2026-%02d-%02d", 1 + row % 12, 1 + row % 28);
      default -> row % 4 == 1 ? "Leave at the door\nCall first" : row % 4 == 3 ? "" : null;
    };
  }

  List<Column> columns() {
    return List.of(
        new Column("id", 56, DataType.INTEGER, changes.cell(0)).typeName("bigint"),
        custom == null
            ? new Column("customer", 140, DataType.TEXT, changes.cell(1))
                .typeName("text")
                .editable()
            : new Column("customer", 140, DataType.TEXT, changes.cell(1))
                .typeName("text")
                .editable()
                .editor(custom),
        new Column("qty", 64, DataType.INTEGER, changes.cell(2)).typeName("int").editable(),
        new Column("amount", 90, DataType.DECIMAL, changes.cell(3))
            .typeName("numeric")
            .editable()
            .nullable(),
        new Column("paid", 70, DataType.BOOLEAN, changes.cell(4))
            .typeName("bool")
            .editable()
            .nullable(),
        new Column("status", 100, DataType.ENUM, changes.cell(5))
            .typeName("status")
            .editable()
            .choices(choices),
        new Column("due", 110, DataType.DATE, changes.cell(6)).typeName("date").editable(),
        new Column("note", 160, DataType.TEXT, changes.cell(7))
            .typeName("text")
            .editable()
            .nullable());
  }

  El view() {
    El bar = filtering
        ? DataGrid.filterBar(theme, columns(), filters, f -> {
          log.add("filters " + f.size());
          filters = f;
        })
        : null;
    El view = col()
        .wFull()
        .hFull()
        .padding(theme.space().m())
        .gap(theme.space().s())
        .fill(theme.surface().panel());
    if (top != null) {
      view.child(com.rethinkj.ui.El.row()
          .align(com.rethinkj.ui.Style.Align.CENTER)
          .child(bar == null ? com.rethinkj.ui.El.el() : bar)
          .child(com.rethinkj.ui.El.el().grow(1))
          .child(top.get()));
    } else if (bar != null) {
      view.child(bar);
    }
    return view.child(grid().key("ledger").label("Ledger").grow(1));
  }

  El grid() {
    DataGrid.Options options = DataGrid.options()
        .select(selection)
        .rowNumbers()
        .frozen(1)
        .copy(app.clipboard())
        .contextMenu(menu, List.of())
        .changes(changes)
        .onCommit((row, column, value) -> {
          log.add("commit " + row + "," + column + " " + value);
          if (refuse != null) {
            return refuse;
          }
          changes.set(row, column, value);
          return null;
        });
    if (filtering) {
      options.filters(filters, f -> {
        log.add("filter " + f);
        filters = f;
      });
    }
    return dataGrid(theme, columns(), changes.rowCount(), more.apply(options));
  }

  App<Ledger> app() {
    app = new App<>(this, Ledger::view);
    app.textRendering(TextRendering.LINUX);
    return app;
  }
}
