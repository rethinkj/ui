package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * In-grid find ({@link DataGrid.Options#find}): opening with Ctrl+F, match counts and highlights,
 * stepping with Enter/Shift+Enter and F3/Shift+F3, case and regex switches, Escape returning focus
 * while keeping selection, skipping unloaded rows, and 1,000,000-row responsiveness.
 */
class GridFindTest {

  private static final String[][] ROWS = {
    {"1001", "Ada Lovelace", "Berlin", "paid"},
    {"1002", "Grace Hopper", "Paris", "pending"},
    {"1003", "ada.king@example.com", "Lisbon", "shipped"},
    {"1004", "Linus Torvalds", "Berlin", "paid"},
    {"1005", "Margaret Hamilton", "Oslo", "refunded"},
    {"1006", "Ada Byron", "Vienna", "paid"},
    {"1007", "Ken Thompson", "berlin-west", "pending"},
    {"1008", "Barbara Liskov", "Madrid", "shipped"}
  };

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static final class State {
    final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
    final DataGrid.Find find = new DataGrid.Find();
    IntPredicate loaded;
    App<State> app;

    El view() {
      List<DataGrid.Column> columns = List.of(
          new DataGrid.Column("id", 70, DataGrid.DataType.INTEGER, r -> ROWS[r][0]),
          new DataGrid.Column("customer", 170, DataGrid.DataType.TEXT, r -> ROWS[r][1]),
          new DataGrid.Column("city", 110, DataGrid.DataType.TEXT, r -> ROWS[r][2]),
          new DataGrid.Column("status", 90, DataGrid.DataType.TEXT, r -> ROWS[r][3]));
      DataGrid.Options options =
          DataGrid.options().select(selection).rowNumbers().find(app, find);
      if (loaded != null) {
        options.loading(loaded, (first, last) -> {});
      }
      return col()
          .wFull()
          .hFull()
          .padding(8)
          .fill(Theme.DARK.surface().window())
          .child(dataGrid(columns, ROWS.length, options)
              .key("orders")
              .label("Orders")
              .grow(1));
    }

    App<State> app() {
      App<State> app = new App<>(this, State::view).provide(Theme.CURRENT, s -> Theme.DARK);
      this.app = app;
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  @Test
  void findsHighlightsStepsAndClosesKeepingSelectionAndFocus() throws IOException {
    State state = new State();
    App<State> app = state.app();
    List<Path> files = Script.run("""
        size 560 220 scale 2
        frame
        focus #orders
        key F control
        frame
        advance 0.25
        frame
        type "ada"
        frame
        advance 0.25
        frame
        snap found""", app, out);
    Golden.assertSnaps("grid-find-dark-2x-", files, "found");

    String dump = app.tree().dump();
    assertTrue(dump.contains("el #find role=search label=\"Find\""), dump);
    assertEquals("1 of 3", count(dump), "matches rows 0, 2, 5 case-insensitively");
    assertEquals(0, state.selection.row());
    assertEquals(1, state.selection.column());
    // Rows stay unfiltered: all 8 rows remain present.
    assertTrue(dump.contains("#row-0") && dump.contains("#row-7"), dump);
    // Visible matching cells get background marks behind matched text; current cell is outlined.
    El currentCell = findByKey(app.tree(), "cell-0-1");
    El otherMatchCell = findByKey(app.tree(), "cell-2-1");
    assertFalse(currentCell.children().getFirst().text().marks().isEmpty());
    assertEquals(
        Theme.DARK.code().current(),
        currentCell.children().getFirst().text().marks().getFirst().color());
    assertEquals(
        Theme.DARK.code().match(),
        otherMatchCell.children().getFirst().text().marks().getFirst().color());
    assertTrue(currentCell.border(com.rethinkj.ui.Side.TOP) > 0, "current match cell outlined");

    // Enter / Shift+Enter step forward and backward, wrapping at the ends.
    Script.run("size 560 220 scale 2\nkey Enter\nframe", app, out);
    assertEquals(2, state.selection.row());
    assertEquals(1, state.selection.column());
    assertEquals("2 of 3", count(app.tree().dump()));

    Script.run("size 560 220 scale 2\nkey Enter\nframe", app, out);
    assertEquals(5, state.selection.row());
    assertEquals("3 of 3", count(app.tree().dump()));

    Script.run("size 560 220 scale 2\nkey Enter\nframe", app, out);
    assertEquals(0, state.selection.row(), "wraps to first match");
    assertEquals("1 of 3", count(app.tree().dump()));

    Script.run("size 560 220 scale 2\nkey Enter shift\nframe", app, out);
    assertEquals(5, state.selection.row(), "Shift+Enter wraps to last match");
    assertEquals("3 of 3", count(app.tree().dump()));

    // Match case (Alt+C) narrows "ada" to only row 2 ("ada.king@example.com").
    Script.run("size 560 220 scale 2\nkey C alt\nframe", app, out);
    assertTrue(state.find.matchCase());
    assertEquals("1 of 1", count(app.tree().dump()));
    assertEquals(2, state.selection.row());

    // Regex (Alt+R) and invalid expression feedback.
    Script.run(
        "size 560 220 scale 2\nkey C alt\nkey R alt\nkey A control\ntype \"^ber\"\nframe",
        app,
        out);
    assertTrue(state.find.regex());
    assertEquals("1 of 3", count(app.tree().dump()), "Berlin, Berlin, berlin-west");
    assertEquals(0, state.selection.row());
    assertEquals(2, state.selection.column());

    Script.run("size 560 220 scale 2\nkey A control\ntype \"(ber\"\nframe", app, out);
    assertTrue(line(app.tree().dump(), "find-field").contains(" invalid"));
    assertEquals("", count(app.tree().dump()));

    // Restore valid query, step with F3, then Escape closes and returns focus keeping selection.
    Script.run(
        "size 560 220 scale 2\nkey A control\ntype \"berlin\"\nframe\nkey F3\nframe", app, out);
    assertEquals(3, state.selection.row());
    assertEquals(2, state.selection.column());
    Script.run("size 560 220 scale 2\nkey Escape\nframe\nadvance 0.25\nframe", app, out);
    assertFalse(state.find.isOpen());
    assertFalse(app.tree().dump().contains("role=search"));
    assertNotNull(app.focused());
    assertEquals("orders", app.focused().key(), "focus returned to the grid");
    assertEquals(3, state.selection.row(), "current match stays selected");
    assertEquals(2, state.selection.column());
  }

  @Test
  void skipsUnloadedRows() throws IOException {
    State state = new State();
    // Row 0 is unloaded; rows 2 and 5 are loaded.
    state.loaded = row -> row != 0;
    App<State> app = state.app();
    Script.run("""
        size 560 220 scale 2
        frame
        focus #orders
        key F control
        frame
        advance 0.25
        frame
        type "ada"
        frame""", app, out);
    assertEquals("1 of 2", count(app.tree().dump()), "unloaded row 0 skipped");
    assertEquals(2, state.selection.row());
    assertEquals(1, state.selection.column());
  }

  @Test
  void millionRowsSearchAndSteppingStayResponsiveInSlices() throws IOException {
    final int million = 1_000_000;
    final int needleRow = 999_995;
    DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
    DataGrid.Find find = new DataGrid.Find();
    App<?>[] holder = new App<?>[1];
    App<Void> app = new App<>(null, ignored -> {
      List<DataGrid.Column> columns = List.of(
          new DataGrid.Column("id", 90, DataGrid.DataType.INTEGER, r -> String.valueOf(r + 1)),
          new DataGrid.Column(
              "name",
              180,
              DataGrid.DataType.TEXT,
              r -> r == needleRow ? "UniqueNeedle" : (r % 10 == 0 ? "Ada " + r : "Grace " + r)));
      return col()
          .wFull()
          .hFull()
          .child(
              dataGrid(columns, million, DataGrid.options().select(selection).find(holder[0], find))
                  .key("big")
                  .grow(1));
    });
    holder[0] = app;
    app.textRendering(TextRendering.LINUX);

    // Frequent match ("Ada"): opens, finds first match immediately, and caps count at 10000+.
    Script.run("""
        size 560 220 scale 2
        frame
        focus #big
        key F control
        frame
        type "Ada"
        frame""", app, out);
    assertEquals(0, selection.row());
    assertEquals(1, selection.column());
    Script.run("size 560 220 scale 2\nkey Enter\nframe", app, out);
    assertEquals(10, selection.row());
    int frames = 0;
    while (!find.complete && frames < 500) {
      app.frame();
      frames++;
    }
    assertTrue(find.complete, "count scan finished within slices");
    assertTrue(find.more, "capped at Find.LIMIT");
    assertEquals("2 of 10000+", count(app.tree().dump()));

    // Sparse match near row 1,000,000: finds it across App.after(0, ...) slices without freezing.
    Script.run("""
        size 560 220 scale 2
        key A control
        type "UniqueNeedle"
        frame""", app, out);
    frames = 0;
    while ((find.jump != null || !find.complete) && frames < 2000) {
      app.frame();
      frames++;
    }
    assertEquals(needleRow, selection.row(), "jumped to the match near row 1,000,000");
    assertEquals(1, selection.column());
    assertEquals("1 of 1", count(app.tree().dump()));
  }

  private static String line(String dump, String key) {
    return dump.lines()
        .filter(l -> l.contains("#" + key + " "))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no #" + key + " in\n" + dump));
  }

  private static String count(String dump) {
    String line = line(dump, "count");
    int first = line.indexOf('"');
    return line.substring(first + 1, line.indexOf('"', first + 1));
  }

  private static El findByKey(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = findByKey(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
