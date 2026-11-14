package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Theme;
import java.util.List;

/** The example of saving a grid's changes: the open editor first, then what the database says. */
final class Commits {

  static final String[] CUSTOMERS = {"Ada", "Brendan", "Chen", "Dagny"};

  final Theme t = Theme.DARK;
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final GridChanges changes = new GridChanges(100, Commits::value);
  String status = "";

  static String value(int row, int column) {
    return switch (column) {
      case 0 -> String.valueOf(1000 + row);
      case 1 -> CUSTOMERS[row % CUSTOMERS.length];
      default -> "2026-11-" + (10 + row % 20);
    };
  }

  // docs:start grid-commit
  /** Control+S and the Save button: hand on the open editor, then write. */
  void save() {
    if (!selection.commitEdit()) {
      return; // the editor refused its value: it stays open with its message
    }
    int refused = 0;
    for (GridChanges.Change change : changes.changes()) {
      String customer = change.values().get(1);
      if (customer != null && customer.isBlank()) { // what a database would refuse
        changes.error(change, 1, "customer must not be empty");
        refused++;
      }
    }
    if (refused > 0) {
      status = "Refused: " + refused; // the changes stay, the cells show why
      return;
    }
    status = "Written: " + changes.count();
    changes.clear();
  }
  // docs:end grid-commit

  static El view(Commits s) {
    Theme t = s.t;
    List<Column> columns = List.of(
        new Column("id", 80, DataType.INTEGER, s.changes.cell(0)),
        new Column("customer", 120, DataType.TEXT, s.changes.cell(1)).editable(),
        new Column("shipped", 120, DataType.DATE, s.changes.cell(2)).editable());
    El grid = DataGrid.dataGrid(
            t,
            columns,
            s.changes.rowCount(),
            DataGrid.options().select(s.selection).changes(s.changes))
        .key("orders")
        .label("Orders")
        .grow(1);
    // docs:start grid-commit-button
    boolean pending = s.changes.count() > 0 || s.selection.isEditing();
    El save = Button.button(t, PRIMARY, "Save", pending ? s::save : null).key("save");
    // docs:end grid-commit-button
    return Frame.of(
            t,
            Card.flush(
                    t,
                    Card.toolbar(t, "orders", null, save),
                    grid,
                    Card.footer(t, Label.label(t, s.status).key("status")))
                .wFull()
                .grow(1))
        .onKey(k -> {
          if (k.modifiers() == Key.CONTROL && k.name().equals("S")) {
            s.save();
            return true;
          }
          return false;
        });
  }
}
