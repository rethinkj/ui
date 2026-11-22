package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The empty view of a data grid ({@link DataGrid.Options#empty}). */
class GridEmptyTest {

  private static final Filter NOBODY =
      new Filter(1, new Filter.TextMatch(Filter.Kind.CONTAINS, "nobody"));

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** A filterable grid whose columns are wider than its window. */
  private static final class State {
    final Selection selection = new Selection(Mode.CELLS);
    List<Filter> filters = List.of(NOBODY);
    List<SortKey> sort = List.of();
    boolean appendRow;

    void clearFilters() {
      filters = List.of();
    }

    El view() {
      int[] rows = IntStream.range(0, 20)
          .filter(r -> filters.stream().allMatch(f -> f.matches(Ledger.cell(r, f.column()))))
          .toArray();
      List<Column> columns = List.of(
          new Column("id", 70, DataType.INTEGER, r -> Ledger.cell(rows[r], 0)).sortable(true),
          new Column("customer", 160, DataType.TEXT, r -> Ledger.cell(rows[r], 1)),
          new Column("amount", 110, DataType.DECIMAL, r -> Ledger.cell(rows[r], 3)),
          new Column("note", 400, DataType.TEXT, r -> Ledger.cell(rows[r], 7)));
      DataGrid.Options options = DataGrid.options()
          .select(selection)
          .sort(sort, s -> sort = s)
          .filters(filters, f -> filters = f)
          .empty(EmptyState.emptyState(
              Symbol.SEARCH,
              "No orders match",
              null,
              button(Button.Variant.SECONDARY, "Clear filters", this::clearFilters)));
      if (appendRow) {
        options.appendRow();
      }
      return col()
          .wFull()
          .hFull()
          .child(dataGrid(columns, rows.length, options).key("orders").grow(1));
    }

    App<State> app() {
      App<State> app = new App<>(this, State::view).provide(Theme.CURRENT, s -> Theme.LIGHT);
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  @Test
  void showsInViewWithoutRowsAndItsButtonIsReachedByTabAndPointer() throws IOException {
    State state = new State();
    App<State> app = state.app();
    // The header still sorts, and the view stays in the middle of the grid scrolled sideways.
    Script.run("""
        size 480 150 scale 2
        frame
        press #column-0
        frame
        wheel #orders 180 0
        frame""", app, out);
    assertEquals(List.of(new SortKey(0, false)), state.sort);
    String dump = app.tree().dump();
    float[] grid = rect(line(dump, "role=grid "));
    float[] header = rect(line(dump, "#header "));
    float[] status = rect(line(dump, "role=status "));
    assertEquals(grid[0] + grid[2] / 2, status[0] + status[2] / 2, 1f, dump);
    assertTrue(status[1] >= header[1] + header[3], dump);

    Script.run("size 480 150 scale 2\nfocus #orders\nkey Tab\nframe", app, out);
    assertEquals("Clear filters", app.focused().label());
    Script.run("size 480 150 scale 2\nkey Enter\nframe", app, out);
    assertTrue(state.filters.isEmpty());
    dump = app.tree().dump();
    assertTrue(dump.contains("#row-0") && !dump.contains("role=status"), dump);

    state.filters = List.of(NOBODY);
    app.invalidate();
    // Taller than the short grid, the view scrolls to its button.
    Script.run("""
        size 480 150 scale 2
        frame
        wheel #empty 0 400
        frame
        press role=button label="Clear filters"
        frame""", app, out);
    assertTrue(state.filters.isEmpty());
  }

  @Test
  void anAppendRowShowsInsteadOfTheView() throws IOException {
    State state = new State();
    state.appendRow = true;
    App<State> app = state.app();
    Script.run("size 480 150 scale 2\nframe", app, out);
    String dump = app.tree().dump();
    assertTrue(dump.contains("#row-0") && !dump.contains("role=status"), dump);
  }

  private static String line(String dump, String part) {
    return dump.lines()
        .filter(l -> l.contains(part))
        .findFirst()
        .orElseThrow(() -> new AssertionError(part + " in\n" + dump));
  }

  /** The x, y, width and height of a line of the dump. */
  private static float[] rect(String line) {
    Matcher m = Pattern.compile(" (-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+)")
        .matcher(line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\""));
    assertTrue(m.find(), line);
    return new float[] {
      Float.parseFloat(m.group(1)),
      Float.parseFloat(m.group(2)),
      Float.parseFloat(m.group(3)),
      Float.parseFloat(m.group(4))
    };
  }
}
