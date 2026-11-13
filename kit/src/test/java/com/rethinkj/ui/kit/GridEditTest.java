package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.Status;
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
 * Editing in the grid ({@link DataGrid.Options#onCommit}) with the changes kept in a {@link
 * GridChanges}: the editor over a cell and its keys, the editors of the types, NULL, rows added
 * and deleted, pasting and clearing, undo, by scripts and the dump.
 */
class GridEditTest {

  private static final Theme T = Theme.DARK;

  @TempDir Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Ledger> start(Ledger ledger) throws IOException {
    App<Ledger> app = ledger.app();
    run(app, "frame\nfocus #ledger\nframe\nframe");
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

  /**
   * edit.txt: the editors of text, a number, several lines and a list, a refused date with its
   * message, and rows changed, new, deleted and failed, with and without the selection.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Ledger ledger = new Ledger(name.equals("dark") ? Theme.DARK : Theme.LIGHT, 40);
    ledger.changes.set(6, 3, "999.00");
    ledger.changes.error(6, 3, "violates check constraint");
    ledger.changes.set(7, 2, "12");
    App<Ledger> app = ledger.app();
    Path folder = out.resolve(name);
    String text = Files.readString(Path.of("src/test/resources/scripts/edit.txt"));
    List<Path> files = Script.run(text, app, folder).stream()
        .filter(f -> !f.toString().endsWith(".clipboard.txt")).toList();
    for (int i = 0; i < files.size(); i += 2) {
      String snap = files.get(i).getFileName().toString().replace(".png", "");
      Golden.assertMatches("edit-" + name + "-" + snap,
          Png.decode(Files.readAllBytes(files.get(i))), Files.readString(files.get(i + 1)));
    }
    assertEquals(14, files.size());
  }

  /** A failed cell says what is wrong in its tooltip, also when it was a change that failed. */
  @Test
  void aFailedCellShowsItsMessageInATooltip() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    ledger.changes.set(2, 2, "4");
    ledger.changes.error(ledger.changes.changes().getFirst(), 2, "qty must be even");
    App<Ledger> app = start(ledger);
    run(app, "move #cell-2-2\nframe\nadvance 1\nframe\nadvance 1\nframe");
    assertTrue(app.tree().dump().contains("role=tooltip label=\"qty must be even\""),
        app.tree().dump());
    assertTrue(line(app, "cell-2-2").contains("invalid"), line(app, "cell-2-2"));
  }

  @Test
  void enterOpensTheEditorOverTheCellTypingThenEnterHandsItOnAndGoesDown() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key Enter\nframe");
    El editor = CellEditorTest.find(app, "editor");
    El cell = CellEditorTest.find(app, "cell-0-1");
    assertNotNull(editor, app.tree().dump());
    assertEquals(cell.x(), editor.x(), "over the cell");
    assertEquals(cell.width(), editor.width());
    assertTrue(line(app, "field").contains("\"Ada Lovelace\""), line(app, "field"));
    assertTrue(line(app, "field").endsWith("focused caret 12"), line(app, "field"));
    // The text starts where the cell's does: the frame's edge and padding make up the cell's.
    El field = CellEditorTest.find(app, "field");
    assertEquals(cell.x() + cell.children().getFirst().x(), editor.x() + field.x(), 0.01f);

    run(app, "type \" Jr.\"\nframe\nkey Enter\nframe");
    assertEquals(List.of("commit 0,1 Ada Lovelace Jr."), ledger.log);
    assertEquals("Ada Lovelace Jr.", ledger.changes.cell(0, 1));
    assertNull(CellEditorTest.find(app, "editor"));
    assertEquals(1, ledger.selection.row(), "a row down");
    assertEquals("ledger", app.focused().key(), "the grid has the focus back");
    assertTrue(line(app, "cell-0-1").contains("value=\"changed\""), line(app, "cell-0-1"));
    assertTrue(line(app, "row-0").contains("value=\"changed\""), line(app, "row-0"));
    assertNotNull(CellEditorTest.find(CellEditorTest.find(app, "row-0"), "mark"));
  }

  @Test
  void escapeClosesWithoutHandingOnAndTypingReplacesTheValue() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key F2\ntype \"x\"\nkey Escape\nframe");
    assertNull(CellEditorTest.find(app, "editor"));
    assertTrue(ledger.log.isEmpty());

    run(app, "type \"Zoe\"\nframe"); // typing on the grid opens it with the text in place
    assertTrue(line(app, "field").contains("\"Zoe\""), line(app, "field"));
    assertEquals("field", app.focused().key());
    run(app, "key Tab\nframe");
    assertEquals(List.of("commit 0,1 Zoe"), ledger.log);
    assertEquals(2, ledger.selection.column(), "Tab goes a column on");
    run(app, "key F2\nkey Tab shift\nframe");
    assertEquals(1, ledger.selection.column(), "Shift+Tab goes back, also without a change");
    assertEquals(1, ledger.log.size(), "an unchanged value is not handed on");
  }

  @Test
  void tabAtTheEndOfARowGoesToTheNextAndShiftTabBack() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key End\nframe\nkey F2\nkey Tab\nframe");
    assertEquals(1, ledger.selection.row());
    assertEquals(0, ledger.selection.column());
    run(app, "key Tab shift\nframe"); // on the grid: Tab is not the grid's
    run(app, "focus #ledger\nkey Home\nkey F2\nframe");
    assertNull(CellEditorTest.find(app, "editor"), "the id is not editable");
    run(app, "key ArrowRight\nkey F2\nkey Tab shift\nframe");
    assertEquals(0, ledger.selection.column());
  }

  @Test
  void aPressElsewhereHandsTheValueOnAndThePressGoesOn() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key Enter\ntype \"!\"\nframe\npress #cell-3-2\nframe");
    assertEquals(List.of("commit 0,1 Ada Lovelace!"), ledger.log);
    assertEquals(3, ledger.selection.row(), "and the press selected the other cell");
    assertEquals(2, ledger.selection.column());
    assertNull(CellEditorTest.find(app, "editor"));
    // A press in the editor is the editor's.
    run(app, "key F2\nframe\npress #field\nframe");
    assertNotNull(CellEditorTest.find(app, "editor"));
    // A double press opens it.
    run(app, "key Escape\nframe\npress #cell-5-1\npress #cell-5-1 clicks 2\nframe");
    assertTrue(line(app, "field").contains("\"Radia Lovelace\"") || line(app, "field")
        .contains(ledger.changes.cell(5, 1)), line(app, "field"));
  }

  @Test
  void numbersTakeOnlyWhatCanBeANumberAndAWrongDateKeepsTheEditorOpenWithAMessage()
      throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key ArrowRight\ntype \"12\"\ntype \"a\"\nframe"); // qty, an integer
    assertTrue(line(app, "field").contains("\"12\""), line(app, "field"));
    run(app, "key Enter\nframe");
    assertEquals("commit 0,2 12", ledger.log.getLast());

    run(app, "key ArrowUp\nkey ArrowRight\ntype \"-3.5e2\"\nframe"); // amount, a decimal
    assertTrue(line(app, "field").contains("\"-3.5e2\""), line(app, "field"));
    run(app, "key Escape\nkey End\nkey ArrowLeft\ntype \"2026-02-30\"\nkey Enter\nframe");
    assertTrue(line(app, "editor").contains(" invalid"), line(app, "editor"));
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=alert label=\"Expected a date such as 2026-11-13\""), dump);
    assertEquals("commit 0,2 12", ledger.log.getLast(), "not handed on");
    run(app, "key Backspace\nkey Backspace\ntype \"28\"\nframe");
    assertFalse(app.tree().dump().contains("role=alert"), "an edit takes the message away");
    run(app, "key Enter\nframe");
    assertEquals("commit 0,6 2026-02-28", ledger.log.getLast());
  }

  @Test
  void theAppMayRefuseAValueAndTheEditorStaysOpenWithItsMessage() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    ledger.refuse = "Customer names are unique";
    App<Ledger> app = start(ledger);
    run(app, "type \"Grace Hopper\"\nkey Enter\nframe");
    assertTrue(line(app, "editor").contains(" invalid"), line(app, "editor"));
    assertTrue(app.tree().dump().contains("role=alert label=\"Customer names are unique\""));
    assertEquals("field", app.focused().key(), "the editor keeps the focus");
    run(app, "press #cell-4-4\nframe"); // a press elsewhere tries again and selects nothing
    assertEquals(0, ledger.selection.row());
    assertNotNull(CellEditorTest.find(app, "editor"));
    ledger.refuse = null;
    run(app, "key Enter\nframe");
    assertEquals("Grace Hopper", ledger.changes.cell(0, 1));
    assertNull(CellEditorTest.find(app, "editor"));
  }

  @Test
  void spaceSwitchesABooleanThroughNullWhereTheColumnAllowsIt() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "press #cell-1-4\nframe"); // "false"
    run(app, "key Space\nframe\nkey Space\nframe\nkey Space\nframe\nkey Enter\nframe");
    assertEquals(List.of("commit 1,4 true", "commit 1,4 null", "commit 1,4 false",
        "commit 1,4 true"), ledger.log, "Enter switches it too");
    assertNull(CellEditorTest.find(app, "editor"), "no editor");
    run(app, "type \"x\"\nframe");
    assertNull(CellEditorTest.find(app, "editor"), "letters do nothing");
  }

  @Test
  void anEnumerationChoosesFromAListThatTypingGoesThrough() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "press #cell-0-5\nframe\nkey Enter\nframe\nadvance 1\nframe");
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=menu"), dump);
    assertEquals("menu", app.focused().key());
    assertTrue(line(app, "item-0").contains("checked"), "the current choice: " + dump);
    run(app, "key ArrowDown\nkey Enter\nframe");
    assertEquals("commit 0,5 pending", ledger.log.getLast());
    assertEquals(1, ledger.selection.row(), "Enter goes down");
    assertEquals("ledger", app.focused().key());

    run(app, "type \"r\"\nframe\nadvance 1\nframe"); // typing opens it at what it starts with
    assertTrue(line(app, "item-3").contains(" current"), app.tree().dump());
    run(app, "key Escape\nframe");
    assertFalse(app.tree().dump().contains("role=menu"));
    assertEquals("commit 0,5 pending", ledger.log.getLast(), "Escape changed nothing");
  }

  @Test
  void controlZeroSetsNullInTheEditorAndAnEmptyEditorOfANumberIsNull() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key End\nframe\nkey F2\nframe"); // note: NULL
    assertTrue(line(app, "field").contains("value=\"NULL\""), line(app, "field"));
    run(app, "type \"a\"\nkey Backspace\nframe");
    assertFalse(line(app, "field").contains("value=\"NULL\""), "empty text, not NULL");
    run(app, "key Enter\nframe");
    assertEquals("commit 0,7 ", ledger.log.getLast());
    run(app, "key ArrowUp\nkey F2\nkey 0 control\nframe");
    assertTrue(line(app, "field").contains("value=\"NULL\""), line(app, "field"));
    run(app, "key Enter\nframe");
    assertEquals("commit 0,7 null", ledger.log.getLast());

    run(app, "key Home\nkey ArrowRight\nkey ArrowRight\nkey ArrowRight\nframe\nkey F2\nframe\nkey Backspace\nkey Backspace\nkey Backspace\n"
        + "key Backspace\nkey Backspace\nkey Backspace\nkey Enter\nframe");
    assertEquals("commit 1,3 null", ledger.log.getLast(), "amount allows NULL");
    run(app, "key ArrowUp\nkey ArrowLeft\nframe\nkey F2\nframe\nkey Backspace\nkey Enter\nframe");
    assertTrue(app.tree().dump().contains("role=alert label=\"A value is required\""));
  }

  @Test
  void deleteClearsTheSelectionAndControlZeroSetsItNullInOneChange() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "press #cell-0-1\ndown #cell-0-1\nmove #cell-2-3\nup #cell-2-3\nframe\nkey Delete\n"
        + "frame");
    // Customer is text without NULL: empty; qty is a number without NULL: left; amount NULL.
    assertEquals("", ledger.changes.cell(1, 1));
    assertEquals(Ledger.cell(1, 2), ledger.changes.cell(1, 2));
    assertNull(ledger.changes.cell(1, 3));
    run(app, "key Z control\nframe");
    assertEquals(Ledger.cell(1, 1), ledger.changes.cell(1, 1), "one step");
    assertEquals(Ledger.cell(0, 3), ledger.changes.cell(0, 3));
    run(app, "key 0 control\nframe");
    assertEquals(Ledger.cell(1, 1), ledger.changes.cell(1, 1), "not NULL in customer");
    assertNull(ledger.changes.cell(2, 3));
    run(app, "key Y control\nframe\nkey Z control\nkey Z control\nframe");
    assertTrue(ledger.changes.isEmpty(), ledger.changes.changes().toString());
  }

  @Test
  void pastedTextGoesFromTheCursorOnAndASingleValueIntoEverySelectedCell() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "clipboard \"Ann\\t7\\n\\\"B\\tC\\\"\\tx\\n\"\npress #cell-2-1\nframe\n"
        + "key V control\nframe");
    assertEquals("Ann", ledger.changes.cell(2, 1));
    assertEquals("7", ledger.changes.cell(2, 2));
    assertEquals("B\tC", ledger.changes.cell(3, 1));
    assertEquals(Ledger.cell(3, 2), ledger.changes.cell(3, 2), "x is no number");
    assertTrue(line(app, "cell-3-2").contains(" selected"), "the pasted part is selected");
    run(app, "clipboard \"9\"\nkey V control\nframe");
    assertEquals("9", ledger.changes.cell(2, 2));
    assertEquals("9", ledger.changes.cell(3, 2));
    assertEquals("9", ledger.changes.cell(2, 1), "text takes it too");
    run(app, "key Z control\nkey Z control\nframe");
    assertTrue(ledger.changes.isEmpty());
  }

  @Test
  void rowsAreAddedAfterTheCursorAndMarkedToBeDeletedAndTheChangesListThem()
      throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "press #cell-1-1\nframe\nkey Insert\nframe");
    assertEquals(41, ledger.changes.rowCount());
    assertEquals(2, ledger.selection.row(), "on the new row");
    assertTrue(line(app, "row-2").contains("value=\"new\""), line(app, "row-2"));
    El number = CellEditorTest.find(app, "number-2");
    assertEquals("+", number.children().getFirst().text().string());
    run(app, "type \"Nina\"\nkey Enter\nframe");
    assertEquals("Nina", ledger.changes.cell(2, 1));
    run(app, "press #cell-5-1\nkey Delete control\nframe");
    assertTrue(line(app, "row-5").contains("value=\"deleted\""), line(app, "row-5"));
    run(app, "key F2\nframe");
    assertNull(CellEditorTest.find(app, "editor"), "a deleted row is not edited");
    assertEquals(List.of(GridChanges.Kind.DELETE, GridChanges.Kind.INSERT),
        ledger.changes.changes().stream().map(GridChanges.Change::kind).toList());
    assertEquals(4, ledger.changes.changes().getFirst().key(), "the row's key");
    assertEquals(2, ledger.changes.count());
    run(app, "key - control\nframe"); // again: the mark goes
    assertEquals(1, ledger.changes.count());
    run(app, "press #cell-2-1\nkey - control\nframe"); // a new row goes at once
    assertEquals(40, ledger.changes.rowCount());
  }

  @Test
  void anAppendedRowIsAddedWithItsFirstValue() throws IOException {
    Ledger ledger = new Ledger(T, 3);
    ledger.more = o -> o.appendRow();
    App<Ledger> app = start(ledger);
    El number = CellEditorTest.find(app, "number-3");
    assertEquals("*", number.children().getFirst().text().string());
    run(app, "press #cell-3-1\nframe\nkey F2\nkey Escape\nframe");
    assertEquals(3, ledger.changes.rowCount(), "nothing added without a value");
    run(app, "type \"Ivy\"\nkey Enter\nframe");
    assertEquals(4, ledger.changes.rowCount());
    assertEquals("Ivy", ledger.changes.cell(3, 1));
    assertEquals(Status.NEW, ledger.changes.rowStatus(3));
    assertEquals(4, ledger.selection.row(), "on the row to append again");
    assertNotNull(CellEditorTest.find(app, "number-4"));
  }

  @Test
  void altEnterOpensAFieldOfSeveralLinesWhereControlEnterHandsItOn() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key ArrowDown\nkey End\nframe\nkey Enter\nframe\nadvance 1\nframe"); // a line break
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=dialog label=\"Edit note\""), dump);
    assertEquals("text", app.focused().key());
    run(app, "key End control\nkey Enter\ntype \"Thanks\"\nframe");
    assertTrue(app.tree().dump().contains("role=dialog"), "Enter breaks a line");
    run(app, "key Enter control\nframe");
    assertEquals("commit 1,7 Leave at the door\nCall first\nThanks", ledger.log.getLast());
    run(app, "advance 1\nframe");
    assertFalse(app.tree().dump().contains("role=dialog"));

    run(app, "key Home\nkey ArrowRight\nframe\nkey Enter alt\nframe\nadvance 1\nframe");
    assertTrue(app.tree().dump().contains("role=dialog label=\"Edit customer\""));
    run(app, "key Escape\nframe");
    assertEquals(1, ledger.log.size());
  }

  /** A value longer than its cell has a button in the editor that opens the field of lines. */
  @Test
  void aLongValueHasAButtonForTheFieldOfSeveralLines() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    App<Ledger> app = start(ledger);
    run(app, "key Enter\nframe");
    assertNull(CellEditorTest.find(app, "expand"), "Ada Lovelace fits the cell");
    run(app, "type \" and the analytical engine of Charles Babbage\"\nframe");
    El expand = CellEditorTest.find(app, "expand");
    El editor = CellEditorTest.find(app, "editor");
    assertNotNull(expand, app.tree().dump());
    assertTrue(expand.x() + expand.width() <= editor.x() + editor.width(), "inside the editor");
    run(app, "press #expand\nframe\nadvance 1\nframe");
    assertTrue(app.tree().dump().contains("role=dialog label=\"Edit customer\""));
    assertEquals("text", app.focused().key(), "the field of lines has the focus");
    run(app, "key Enter control\nframe");
    assertEquals("commit 0,1 Ada Lovelace and the analytical engine of Charles Babbage",
        ledger.log.getLast());
  }

  @Test
  void theEditorKeepsItsTextCaretAndFocusWhileDataArrivesAndItsRowScrollsAway()
      throws IOException {
    Ledger ledger = new Ledger(T, 400);
    App<Ledger> app = start(ledger);
    run(app, "key F2\ntype \"!\"\nframe\nkey ArrowLeft\nframe");
    String field = line(app, "field");
    app.post(() -> ledger.log.add("data")); // a result arrives: the view is built again
    run(app, "await\nframe");
    assertEquals(field, line(app, "field"));
    assertEquals("field", app.focused().key());
    run(app, "wheel #rows 0 4000\nframe");
    assertNull(dumpLine(app, "field"), "its row is gone");
    run(app, "wheel #rows 0 -4000\nframe");
    assertEquals(field, line(app, "field"));
    assertEquals("field", app.focused().key());
    run(app, "type \"?\"\nkey Enter\nframe");
    assertEquals("commit 0,1 Ada Lovelace?!", ledger.log.getLast());
  }

  @Test
  void anEditorOfTheAppsOwnTakesTheFocusAndTheGridsKeysEndIt() throws IOException {
    Ledger ledger = new Ledger(T, 40);
    ledger.custom = (value, onChange) -> TextField.textField(T, value, onChange).key("mine")
        .label("Own editor");
    App<Ledger> app = start(ledger);
    run(app, "key F2\nframe\nframe");
    assertEquals("mine", app.focused().key(), app.tree().dump());
    run(app, "key End\ntype \"!\"\nkey Enter\nframe");
    assertEquals("commit 0,1 Ada Lovelace!", ledger.log.getLast());
    assertEquals(1, ledger.selection.row());
  }

  @Test
  void theLastRowOfAMillionAndTheLastOfThreeHundredColumnsAreEdited() throws IOException {
    Ledger ledger = new Ledger(T, 1_000_000);
    App<Ledger> app = start(ledger);
    run(app, "key End control\nframe\nkey Home\nkey ArrowRight\nframe\ntype \"Last\"\n"
        + "key Enter\nframe");
    assertEquals("commit 999999,1 Last", ledger.log.getLast());
    assertEquals("Last", ledger.changes.cell(999_999, 1));

    Orders orders = new Orders(T, 1_000_000, 300, DataGrid.Mode.CELLS);
    List<String> log = new java.util.ArrayList<>();
    orders.more = o -> o.onCommit((row, column, value) -> {
      log.add(row + "," + column + " " + value);
      return null;
    });
    orders.editable = true;
    App<Orders> wide = orders.app();
    Script.run("size 560 240\nframe\nfocus #orders\nkey End control\nframe\ntype \"x\"\n"
        + "key Enter\nframe", wide, out);
    assertEquals(List.of("999999,299 x"), log);
  }
}
