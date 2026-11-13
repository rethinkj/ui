package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Layout;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The columns a grid shows ({@link DataGrid.Options#columns}): the chooser, the header's context
 * menu, moving a column by its header, and the handlers told columns among all of them.
 */
class GridColumnsTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  /** The ledger with a layout of its columns, the chooser's button above it, sorting, freezing. */
  private static App<Ledger> start(Ledger ledger, Path out) throws IOException {
    Layout layout = new Layout(ledger.columns().stream().map(Column::title).toList());
    int[] frozen = {1};
    ledger.filtering = true;
    ledger.more = o -> o.columns(layout)
        .frozen(frozen[0])
        .sort(ledger.sort, keys -> ledger.sort = keys)
        .onResize((c, w) -> ledger.log.add("resize " + c))
        .onFreeze(n -> frozen[0] = n);
    ledger.top = () -> DataGrid.columnsButton(ledger.theme, layout);
    ledger.layout = layout;
    App<Ledger> app = ledger.app();
    Script.run("size 640 260\nframe\nfocus #ledger\nframe\nframe", app, out);
    return app;
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 640 260\n" + steps, app, out);
  }

  private static String line(App<?> app, String key) {
    String line = dumpLine(app, key);
    assertNotNull(line, key + " in\n" + app.tree().dump());
    return line;
  }

  @Test
  void theChooserHidesShowsAndMovesColumnsAndTheGridSpeaksOfAllOfThem() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger, out);
    run(app, "press #columns-button\nframe\nadvance 1\nframe");
    assertTrue(
        app.tree().dump().contains("role=dialog label=\"Columns\""), app.tree().dump());
    run(app, "press #show-2\nframe"); // qty hidden
    assertEquals(List.of(0, 1, 3, 4, 5, 6, 7), ints(ledger.layout.shownColumns()));
    assertEquals("amount", find(app, "column-2").label(), "the header shows amount there");
    run(
        app,
        "focus #show-5\nkey ArrowUp alt\nkey ArrowUp alt\nframe"); // status before paid, amount
    assertEquals(List.of(0, 1, 2, 5, 3, 4, 6, 7), ledger.layout.order());
    run(app, "key Escape\nframe\nadvance 1\nframe");
    assertFalse(app.tree().dump().contains("role=dialog label=\"Columns\""), "closed");

    // Handlers count all the columns: amount is 3 wherever it is shown.
    run(app, "press #column-3\nframe"); // the header cell at place 3 is amount now
    assertEquals(List.of(new SortKey(3, false)), ledger.sort);
    run(
        app,
        "move #column-3\nframe\npress #filter-3\nframe\nadvance 1\nframe\ntype \"5\"\n"
            + "key Tab\ntype \"50\"\nkey Enter\nframe");
    assertEquals(List.of(Filter.between(3, "5", "50")), ledger.filters);
    run(
        app,
        "focus #ledger\nkey Home\nkey ArrowRight\nkey ArrowRight\nkey ArrowRight\nframe\n"
            + "key F2\nframe\nkey Backspace\nkey Enter\nframe");
    assertTrue(ledger.log.getLast().startsWith("commit 0,3 "), ledger.log.toString());
  }

  @Test
  void theHeadersMenuSortsHidesFreezesFitsAndOpensTheChooser() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger, out);
    run(app, "press #column-2 right\nframe\nadvance 1\nframe");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=menuitem label=\"Sort descending\""), dump);
    run(app, "press label=\"Sort descending\"\nframe\nadvance 1\nframe");
    assertEquals(List.of(new SortKey(2, true)), ledger.sort);
    run(
        app,
        "press #column-2 right\nframe\nadvance 1\nframe\npress label=\"Freeze up to here\"\n"
            + "frame\nadvance 1\nframe");
    assertTrue(line(app, "column-2").contains("el #column-2"), "still shown");
    run(
        app,
        "press #column-1 right\nframe\nadvance 1\nframe\npress label=\"Fit width\"\nframe\n"
            + "advance 1\nframe");
    assertEquals("resize 1", ledger.log.getLast());
    run(
        app,
        "press #column-1 right\nframe\nadvance 1\nframe\npress label=\"Hide column\"\nframe\n"
            + "advance 1\nframe");
    assertTrue(ledger.layout.isHidden(1));
    run(
        app,
        "press #column-1 right\nframe\nadvance 1\nframe\npress label=\"Columns…\"\nframe\n"
            + "advance 1\nframe");
    assertTrue(app.tree().dump().contains("role=dialog label=\"Columns\""));
    run(app, "press label=\"Show all\"\nframe");
    assertFalse(ledger.layout.isHidden(1));
  }

  @Test
  void aColumnHiddenFromTheHeaderLeavesTheSelectionOnItsColumns() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    ledger.selection.select(0, 3); // amount
    App<Ledger> app = start(ledger, out);
    run(
        app,
        "press #column-1 right\nframe\nadvance 1\nframe\npress label=\"Hide column\"\nframe\n"
            + "focus #ledger\nkey Delete\nframe");
    assertTrue(ledger.layout.isHidden(1));
    assertEquals(2, ledger.selection.column(), "amount, shown second now");
    var change = ledger.changes.changes().getFirst();
    assertEquals(List.of(3), List.copyOf(change.values().keySet()), "amount cleared, not paid");
  }

  @Test
  void anOpenEditorStaysOnItsColumnWhenAnEarlierOneIsHiddenAndClosesWithItsOwn()
      throws IOException {
    Ledger ledger = new Ledger(T, 40);
    ledger.selection.select(0, 6); // due, with note after it
    App<Ledger> app = start(ledger, out);
    run(app, "type \"2027-01-01\"\nframe");
    app.post(() -> ledger.layout.hide(1));
    run(app, "await\nframe");
    assertEquals(5, ledger.selection.editingColumn());
    app.post(() -> assertTrue(ledger.selection.commitEdit()));
    run(app, "await\nframe");
    var change = ledger.changes.changes().getFirst();
    assertEquals("2027-01-01", change.values().get(6), "due, not note");

    run(app, "key ArrowRight\ntype \"left behind\"\nframe"); // the note, shown sixth
    assertEquals("left behind", ledger.selection.draft());
    app.post(() -> ledger.layout.hide(7));
    run(app, "await\nframe");
    assertFalse(ledger.selection.isEditing(), "its column is gone, and so is the editor");
    assertEquals(-1, ledger.selection.row(), "and the cursor, which was on it");
    assertEquals(1, ledger.changes.count(), "nothing written to a neighbour");
    assertEquals(1, ledger.changes.changes().getFirst().values().size());
  }

  @Test
  void aMovedColumnTakesItsPartOfTheSelectionAlong() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    ledger.selection.select(1, 2);
    ledger.selection.extend(2, 4); // qty, amount and paid; the cursor on paid
    App<Ledger> app = start(ledger, out);
    app.post(() -> ledger.layout.moveBefore(3, -1)); // amount to the end
    run(app, "await\nframe");
    assertEquals(
        List.of(new DataGrid.Range(1, 2, 2, 3), new DataGrid.Range(1, 7, 2, 7)),
        ledger.selection.ranges());
    assertEquals(3, ledger.selection.column(), "paid");
    assertEquals(2, ledger.selection.anchorColumn(), "qty");
    run(app, "key Delete\nframe");
    var change = ledger.changes.changes();
    assertEquals(List.of(1, 2), change.stream().map(c -> (int) c.key()).toList());
    assertEquals(List.of(3, 4), List.copyOf(change.getFirst().values().keySet()));
  }

  /** The chooser opened from a header cell stays, with its focus, when it hides that column. */
  @Test
  void theChooserOfAHeaderCellStaysWhenItHidesThatColumn() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger, out);
    run(
        app,
        "press #column-2 right\nframe\nadvance 1\nframe\npress label=\"Columns…\"\nframe\n"
            + "advance 1\nframe\nfocus #show-2\nframe");
    String before = app.tree().dump();
    assertTrue(before.contains("role=dialog label=\"Columns\""), before);
    run(app, "key Space\nframe\nadvance 1\nframe");
    assertTrue(ledger.layout.isHidden(2));
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=dialog label=\"Columns\""), "still open: " + dump);
    assertEquals("show-2", app.focused().key(), "with the focus on the box just unchecked");
    run(app, "key Space\nframe\nadvance 1\nframe");
    assertFalse(ledger.layout.isHidden(2), "and checked again");
  }

  /** The keyboard reaches the header's menu of the cursor's column through the cells' menu. */
  @Test
  void theCellsMenuHasTheHeadersMenuOfTheCursorsColumn() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger, out);
    run(app, "key F10 shift\nframe\nadvance 1\nframe");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=menuitem label=\"Column customer\""), dump);
    run(app, "key End\nkey ArrowRight\nframe\nadvance 1\nframe");
    assertTrue(
        app.tree().dump().contains("role=menuitem label=\"Sort descending\""),
        app.tree().dump());
    run(app, "press label=\"Sort descending\"\nframe\nadvance 1\nframe");
    assertEquals(List.of(new SortKey(1, true)), ledger.sort);
  }

  @Test
  void aHeaderCellDraggedAlongTheHeaderMovesItsColumn() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger, out);
    float y = 8 + 30 + 12; // in the header, below the button's row
    run(app, "down #column-5\nmove 300," + y + "\nframe");
    assertNotNull(find(app, "marker"), app.tree().dump());
    assertTrue(line(app, "column-5").contains(" grabbed"), line(app, "column-5"));
    run(app, "up 300," + y + "\nframe");
    assertEquals(List.of(0, 1, 2, 5, 3, 4, 6, 7), ledger.layout.order(), "before amount");
    assertEquals(List.of(), ledger.sort, "a drag sorts nothing");
  }

  /** columns.txt: the chooser open, and a column dragged along the header with its line. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Ledger ledger = new Ledger(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 40);
    App<Ledger> app = start(ledger, out.resolve("start"));
    String text = Files.readString(Path.of("src/test/resources/scripts/columns.txt"));
    List<Path> files = Script.run(text, app, out.resolve(name)).stream()
        .filter(f -> !f.toString().endsWith(".clipboard.txt"))
        .toList();
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches(
          "columns-" + name + "-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))),
          Files.readString(files.get(i + 1)));
    }
    assertEquals(4, files.size());
  }

  private static List<Integer> ints(int[] values) {
    return java.util.Arrays.stream(values).boxed().toList();
  }

  private static com.rethinkj.ui.El find(App<?> app, String key) {
    return CellEditorTest.find(app, key);
  }
}
