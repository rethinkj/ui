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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/** The example of saving a grid's changes: the open editor first, then what the database says. */
final class Commits {

  static final String[] CUSTOMERS = {"Ada", "Brendan", "Chen", "Dagny"};

  Theme t = Theme.DARK;
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  // The rows of the grid, which saving writes the changes into.
  final List<String[]> rows = new ArrayList<>(IntStream.range(0, 100)
      .mapToObj(i -> IntStream.range(0, 3).mapToObj(c -> value(i, c)).toArray(String[]::new))
      .toList());
  GridChanges changes = new GridChanges(rows.size(), (row, column) -> rows.get(row)[column]);
  int nextId = 1100; // the id the next new order gets
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
    for (GridChanges.Change change : changes.changes()) { // the deletes first
      int row = (int) change.key(); // the row's index, for an insert below 0
      switch (change.kind()) {
        case DELETE -> rows.set(row, null); // removed after the updates
        case UPDATE -> change.values().forEach((c, value) -> rows.get(row)[c] = value);
        case INSERT -> {
          String[] order = new String[3];
          change.values().forEach((c, value) -> order[c] = value);
          order[0] = String.valueOf(nextId++); // as a database gives the key
          rows.add(order);
        }
      }
    }
    rows.removeIf(Objects::isNull);
    // The rows hold the changes now: new GridChanges over them, with their new count.
    changes = new GridChanges(rows.size(), (row, column) -> rows.get(row)[column]);
    selection.clear(); // deleted rows moved the indexes the selection points at
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
        .onKey(s::key);
  }

  boolean key(Key k) {
    if (k.modifiers() == Key.CONTROL && k.name().equals("S")) {
      save();
      return true;
    }
    return false;
  }
}
