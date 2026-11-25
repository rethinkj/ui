package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Text fields inside elements that take presses and drags: a press in a field is the field's, so
 * moving from it selects text and drags nothing around it, with double and triple presses and
 * Shift as without a drag handler; a press beside the field drags as before; and a lost release
 * of one kind ends it when the next press begins the other.
 */
class FieldDragTest {

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  /** A field's string, and what the press and drag handlers around it were told. */
  static final class Model {
    String value = "hello world";
    final List<String> log = new ArrayList<>();
  }

  /**
   * A field 100 wide and 24 high at 10,10, its text from x 14, in a column over the whole window
   * of 200 by 80 that has a press and a drag handler.
   */
  private static App<Model> start() {
    App<Model> app = new App<>(
        new Model(),
        m -> col()
            .key("panel")
            .wFull()
            .hFull()
            .padding(10)
            .onPress(() -> m.log.add("press"))
            .onDrag(d -> m.log.add(d.phase().name()))
            .child(El.text(m.value)
                .editable(v -> m.value = v)
                .key("f")
                .w(100)
                .h(24)
                .padding(0, 4)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 80, 1);
    app.frame();
    return app;
  }

  private static El field(App<?> app) {
    return app.tree().children().getFirst();
  }

  /** The field's string with the caret as {@code |} and the selection's other end as {@code ^}. */
  private static String show(App<?> app) {
    Editor editor = field(app).editor();
    StringBuilder out = new StringBuilder(editor.text());
    int caret = editor.caret();
    int anchor = editor.anchor();
    if (caret == anchor) {
      return out.insert(caret, '|').toString();
    }
    out.insert(Math.max(caret, anchor), caret > anchor ? '|' : '^');
    return out.insert(Math.min(caret, anchor), caret > anchor ? '^' : '|').toString();
  }

  @Test
  void aFieldHasItsPressedLookWhileAPressSelectsInItAndTheColumnHasNone() {
    int pressedField = 0xFF3B82F6;
    int pressedPanel = 0xFF1C1C22;
    App<Model> app = new App<>(
        new Model(),
        m -> col()
            .key("panel")
            .wFull()
            .hFull()
            .padding(10)
            .onPress(() -> m.log.add("press"))
            .whenPressed(e -> e.fill(pressedPanel))
            .child(El.text(m.value)
                .editable(v -> m.value = v)
                .key("f")
                .w(100)
                .h(24)
                .padding(0, 4)
                .whenPressed(e -> e.fill(pressedField))));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 80, 1);
    app.frame();
    app.pointerDown(15, 22);
    app.pointerMove(180, 70); // selecting on, outside the field
    app.frame();
    assertEquals(pressedField, field(app).fill());
    assertEquals(0, app.tree().fill(), "the column around it is not pressed");
    app.pointerUp(180, 70);
    app.frame();
    assertEquals(0, field(app).fill(), "until the pointer is up");
    assertNull(app.pressed());
    app.pointerDown(150, 50); // beside the field: the column's press
    app.frame();
    assertEquals(pressedPanel, app.tree().fill());
  }

  @Test
  void draggingInAFieldSelectsAndDragsNoAncestor() {
    App<Model> app = start();
    app.pointerMove(15, 22);
    app.pointerDown(15, 22);
    app.pointerMove(60, 22); // far beyond the drag threshold, inside the field
    app.frame();
    assertSame(field(app), app.focused());
    String within = show(app);
    assertEquals('^', within.charAt(0), within);

    app.pointerMove(180, 70); // outside the field, over the column with the handlers
    app.frame();
    assertEquals("^hello world|", show(app), "selects on to the end");
    assertSame(field(app), app.hovered(), "the field counts as under the pointer");
    assertEquals(Cursor.TEXT, app.cursor());
    assertSame(field(app), app.pressed(), "the field is pressed while it selects, the column not");

    app.pointerUp(180, 70);
    app.frame();
    assertEquals("^hello world|", show(app), "the selection stays");
    assertEquals(List.of(), app.state().log, "no drag and no press of the column");
    assertSame(app.tree(), app.hovered(), "after the release, what is under the pointer");
    assertEquals(Cursor.DEFAULT, app.cursor());
  }

  @Test
  void doubleTripleAndShiftPressesSelectAsWithoutADragHandler() {
    App<Model> app = start();
    app.pointerDown(30, 22, 2, 0); // in "hello"
    app.pointerUp(30, 22);
    app.frame();
    assertEquals("^hello| world", show(app), "a word");

    app.pointerDown(30, 22, 2, 0); // a double press that drags goes by words
    app.pointerMove(80, 22);
    app.pointerUp(80, 22);
    app.frame();
    assertEquals("^hello world|", show(app));

    app.pointerDown(15, 22);
    app.pointerUp(15, 22);
    app.frame();
    assertEquals("|hello world", show(app));
    app.pointerDown(30, 22, 3, 0);
    app.pointerUp(30, 22);
    app.frame();
    assertEquals("^hello world|", show(app), "everything");

    app.pointerDown(15, 22);
    app.pointerUp(15, 22);
    app.pointerDown(100, 22, 1, Key.SHIFT); // beyond the end of the text
    app.pointerUp(100, 22);
    app.frame();
    assertEquals("^hello world|", show(app), "Shift extends");
    assertEquals(List.of(), app.state().log);
  }

  @Test
  void aPressBesideTheFieldStillDragsTheElementAroundIt() {
    App<Model> app = start();
    app.pointerDown(150, 60);
    app.pointerMove(120, 60);
    app.pointerMove(30, 22); // across the field
    app.pointerUp(30, 22);
    app.frame();
    assertEquals(List.of("START", "MOVE", "END"), app.state().log);
    assertNull(app.focused(), "the field did not take the press");
    assertEquals("hello world", app.state().value);
  }

  // A field in the rows of a list whose rows take presses and drags, as a grid's rows do

  /** The row a press selected, and what the rows' drag handlers were told. */
  static final class Rows {
    int selected = -1;
    final List<String> log = new ArrayList<>();
    final List<String> names = new ArrayList<>(List.of("ab", "cd", "ef", "gh", "ij"));
  }

  @Test
  void aPressOnAFieldInARowIsTheFieldsAndBesideItTheRows() {
    App<Rows> app = new App<>(
        new Rows(),
        r -> col()
            .key("list")
            .wFull()
            .hFull()
            .rows(
                r.names.size(),
                30,
                i -> row()
                    .key("row-" + i)
                    .h(30)
                    .padding(3)
                    .onPress(() -> r.selected = i)
                    .onDrag(d -> r.log.add(i + " " + d.phase()))
                    .child(El.text(r.names.get(i))
                        .editable(v -> r.names.set(i, v))
                        .key("name")
                        .w(100)
                        .h(24)
                        .padding(0, 4))));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 150, 1);
    app.frame();
    app.pointerDown(10, 45); // the field of row 1
    app.pointerMove(90, 45);
    app.pointerUp(90, 45);
    app.frame();
    assertEquals(-1, app.state().selected, "the row did not take the press");
    assertEquals(List.of(), app.state().log, "nor the drag");
    Editor editor = app.focused().editor();
    assertEquals("name", app.focused().key());
    assertEquals(0, editor.anchor());
    assertEquals(2, editor.caret(), "the field selected its text");

    app.pointerDown(150, 75); // row 2, beside its field
    app.pointerUp(150, 75);
    app.frame();
    assertEquals(2, app.state().selected);
    app.pointerDown(150, 105);
    app.pointerMove(150, 130);
    app.pointerUp(150, 130);
    app.frame();
    assertEquals(List.of("3 START", "3 END"), app.state().log);
    assertEquals(2, app.state().selected, "a drag is no press");
  }

  private static El byKey(El root, String key) {
    if (key.equals(root.key())) {
      return root;
    }
    for (El child : root.children()) {
      El found = byKey(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @Test
  void selectionScopeSelectsExtendsCopiesAndClearsAcrossReadOnlyFields() {
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .padding(10)
            .gap(12)
            .child(col()
                .key("scope")
                .selectionScope()
                .gap(12)
                .child(El.text("alpha one")
                    .editable(v -> {})
                    .readOnly()
                    .key("f0")
                    .w(160)
                    .h(24)
                    .padding(0, 4))
                .child(col()
                    .selectionScope() // nested scope counts as the outermost one
                    .child(El.text("beta two")
                        .editable(v -> {})
                        .readOnly()
                        .key("f1")
                        .w(160)
                        .h(24)
                        .padding(0, 4)))
                .child(El.text("secret")
                    .editable(v -> {})
                    .readOnly()
                    .masked()
                    .key("secret")
                    .w(160)
                    .h(24)
                    .padding(0, 4))
                .child(El.text("gamma three")
                    .editable(v -> {})
                    .readOnly()
                    .key("f2")
                    .w(160)
                    .h(24)
                    .padding(0, 4)))
            .child(el().key("outside").w(160).h(24)));
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 220, 1);
    app.frame();

    // f0: y 10..34, f1: y 46..70, secret: y 82..106, f2: y 118..142, outside: y 154..178
    // Drag downwards from middle of f0 across nested f1 and masked secret into middle of f2.
    app.pointerDown(42, 22);
    app.pointerMove(42, 37); // gap closer to f0 than f1
    app.frame();
    assertFalse(byKey(app.tree(), "f1").editor().hasSelection(), "gap near f0 stays in f0");

    app.pointerMove(42, 43); // gap closer to f1 than f0
    app.frame();
    assertTrue(byKey(app.tree(), "f1").editor().hasSelection(), "gap near f1 enters f1");

    app.pointerMove(52, 130); // inside f2
    app.pointerUp(52, 130);
    app.frame();

    El f0 = byKey(app.tree(), "f0");
    El f2 = byKey(app.tree(), "f2");
    assertEquals("beta two", byKey(app.tree(), "f1").editor().selectedText());
    assertFalse(
        byKey(app.tree(), "secret").editor().hasSelection(),
        "a masked field never joins a scope selection");
    // Unfocused fields of the scope show their selection without a caret.
    assertSame(f2, app.focused());
    assertFalse(f0.text().selection().isEmpty(), "unfocused f0 shows its selection");
    assertNull(f0.text().caret(), "unfocused f0 shows no caret");

    String expectedDown =
        f0.editor().selectedText() + "\nbeta two\n" + f2.editor().selectedText();
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals(expectedDown, app.clipboard().read());
    app.clipboard().write("stale");
    app.keyDown(new Key("Insert", Key.CONTROL, false));
    app.frame();
    assertEquals(expectedDown, app.clipboard().read(), "Control+Insert also copies across fields");

    // Drag upwards from middle of f2 into middle of f0: the copy is still in tree order.
    app.pointerDown(52, 130);
    app.pointerMove(42, 22);
    app.pointerUp(42, 22);
    app.frame();
    f0 = byKey(app.tree(), "f0");
    f2 = byKey(app.tree(), "f2");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals("beta two", byKey(app.tree(), "f1").editor().selectedText());
    assertEquals(
        f0.editor().selectedText() + "\nbeta two\n" + f2.editor().selectedText(),
        app.clipboard().read());

    // Shift+press extends from the scope's anchor.
    app.pointerDown(42, 22);
    app.pointerUp(42, 22);
    app.frame();
    int anchor = byKey(app.tree(), "f0").editor().anchor();
    app.pointerDown(48, 58, 1, Key.SHIFT); // into f1
    app.pointerUp(48, 58);
    app.frame();
    assertEquals(anchor, byKey(app.tree(), "f0").editor().anchor());
    assertTrue(byKey(app.tree(), "f1").editor().caret() > 0);
    app.pointerDown(52, 130, 1, Key.SHIFT); // further into f2 from f0's anchor
    app.pointerUp(52, 130);
    app.frame();
    assertEquals("beta two", byKey(app.tree(), "f1").editor().selectedText());

    // A press outside the scope's fields clears the cross-field selection.
    app.pointerDown(50, 166); // on #outside
    app.pointerUp(50, 166);
    app.frame();
    assertFalse(byKey(app.tree(), "f0").editor().hasSelection());
    assertFalse(byKey(app.tree(), "f2").editor().hasSelection());
    assertTrue(byKey(app.tree(), "f1").text().selection().isEmpty());
  }

  @Test
  void selectionScopeInVirtualListTracksKeyedRowsAndMultipleFieldsPerRow() {
    record Item(int id, String title, String body) {}
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      items.add(new Item(i, "Title " + i, "Body " + i + " text"));
    }
    RowHeights heights = new RowHeights(i -> 48, i -> items.get(i).id());
    App<List<Item>> app = new App<>(
        items,
        list -> col()
            .wFull()
            .hFull()
            .selectionScope()
            .child(col()
                .key("list")
                .grow(1)
                .minSize(0, 0)
                .rows(
                    list.size(),
                    heights,
                    i -> col()
                        .padding(4)
                        .child(El.text(list.get(i).title())
                            .editable(v -> {})
                            .readOnly()
                            .key("title")
                            .w(200)
                            .h(20))
                        .child(El.text("hidden")
                            .editable(v -> {})
                            .readOnly()
                            .masked()
                            .key("secret")
                            .w(200)
                            .h(1))
                        .child(El.text(list.get(i).body())
                            .editable(v -> {})
                            .readOnly()
                            .key("body")
                            .w(200)
                            .h(20)))));
    app.textRendering(TextRendering.LINUX);
    app.resize(240, 160, 1);
    app.frame();

    // Click inside row 1's body (row 0 is ~0..49, row 1 is ~49..98; body is lower half of row 1).
    El listEl = byKey(app.tree(), "list");
    El row1Body = byKey(listEl.children().get(1), "body");
    float r1x = listEl.x() + listEl.children().get(1).x() + row1Body.x() + 28;
    float r1y = listEl.y() + listEl.children().get(1).y() + row1Body.y() + 10;
    app.pointerDown(r1x, r1y);
    app.pointerUp(r1x, r1y);
    app.frame();

    // Scroll to row 40 so row 1 leaves the built rows, then Shift+click row 40's title.
    app.scrollToRow(byKey(app.tree(), "list"), 40);
    app.frame();
    listEl = byKey(app.tree(), "list");
    assertTrue(listEl.firstRow() > 1, "row 1 is no longer built");
    El row40 = listEl.children().get(40 - listEl.firstRow());
    El row40Title = byKey(row40, "title");
    float r40x = listEl.x() + row40.x() + row40Title.x() + 30;
    float r40y = listEl.y() + row40.y() + row40Title.y() + 10;
    app.pointerDown(r40x, r40y, 1, Key.SHIFT);
    app.pointerUp(r40x, r40y);
    app.frame();

    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    String expected = app.clipboard().read();
    List<String> lines = expected.lines().toList();
    // Row 1 body + 38 full rows (2..39, 2 fields each = 76) + Row 40 title = 78 lines.
    assertEquals(78, lines.size(), "all unmasked fields across rows 1..40: " + lines.size());
    assertTrue(lines.getFirst().endsWith("1 text"), lines.getFirst());
    assertEquals("Title 2", lines.get(1));
    assertEquals("Body 2 text", lines.get(2));
    assertEquals("Title 20", lines.get(37));
    assertEquals("Body 39 text", lines.get(76));
    assertTrue(lines.getLast().startsWith("Tit"), lines.getLast());
    assertFalse(expected.contains("hidden"), "masked fields are excluded");

    // Prepend 3 items above the selection: keyed RowHeights remaps anchor and end to rows 4..43.
    items.add(0, new Item(-3, "New -3", "New body -3"));
    items.add(1, new Item(-2, "New -2", "New body -2"));
    items.add(2, new Item(-1, "New -1", "New body -1"));
    app.invalidate();
    app.frame();
    app.clipboard().write("stale");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals(expected, app.clipboard().read(), "selection follows keyed rows after prepend");

    // Scroll back to the remapped anchor row (now row 4) and verify it shows its selection again.
    app.scrollToRow(byKey(app.tree(), "list"), 4);
    app.frame();
    listEl = byKey(app.tree(), "list");
    El movedRow = listEl.children().get(4 - listEl.firstRow());
    assertFalse(
        byKey(movedRow, "title").editor().hasSelection(), "title before anchor is unselected");
    assertTrue(byKey(movedRow, "body").editor().hasSelection(), "remapped anchor body is selected");
    assertFalse(byKey(movedRow, "body").text().selection().isEmpty());
  }

  @Test
  void selectionScopeOrdersFieldsAfterVirtualListAfterItsRows() {
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .selectionScope()
            .child(El.text("Header text")
                .editable(v -> {})
                .readOnly()
                .key("header")
                .w(200)
                .h(24))
            .child(col()
                .key("list")
                .grow(1)
                .minSize(0, 0)
                .rows(
                    20,
                    32,
                    i -> El.text("Row " + i + " text")
                        .editable(v -> {})
                        .readOnly()
                        .key("row")
                        .w(200)
                        .h(32)))
            .child(El.text("Footer one")
                .editable(v -> {})
                .readOnly()
                .key("footer1")
                .w(200)
                .h(24))
            .child(El.text("Footer two")
                .editable(v -> {})
                .readOnly()
                .key("footer2")
                .w(200)
                .h(24)));
    app.textRendering(TextRendering.LINUX);
    app.resize(240, 220, 1);
    app.frame();

    // Anchor inside row 2, scroll to the end so row 2 leaves the built rows, then Shift+click
    // footer1: rows 2..19 and footer1 are selected, header and rows 0..1 are not, and footer copies
    // last.
    El listEl = byKey(app.tree(), "list");
    El row2 = listEl.children().get(2);
    float r2x = listEl.x() + row2.x() + 28;
    float r2y = listEl.y() + row2.y() + 12;
    app.pointerDown(r2x, r2y);
    app.pointerUp(r2x, r2y);
    app.frame();

    app.scrollToRow(byKey(app.tree(), "list"), 19);
    app.frame();
    listEl = byKey(app.tree(), "list");
    assertTrue(listEl.firstRow() > 2, "row 2 is no longer built");
    El footer1 = byKey(app.tree(), "footer1");
    float f1x = footer1.x() + 36;
    float f1y = footer1.y() + 12;
    app.pointerDown(f1x, f1y, 1, Key.SHIFT);
    app.pointerUp(f1x, f1y);
    app.frame();

    assertFalse(byKey(app.tree(), "header").editor().hasSelection(), "header stays unselected");
    El row19 = byKey(app.tree(), "list").children().getLast();
    assertTrue(row19.editor().hasSelection(), "last row before footer is selected");
    assertFalse(row19.text().selection().isEmpty());
    assertTrue(byKey(app.tree(), "footer1").editor().hasSelection(), "footer1 is selected");
    assertFalse(byKey(app.tree(), "footer2").editor().hasSelection(), "footer2 stays unselected");

    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    List<String> downLines = app.clipboard().read().lines().toList();
    assertEquals(19, downLines.size(), "rows 2..19 plus footer1: " + downLines);
    assertTrue(downLines.getFirst().endsWith("2 text"), downLines.getFirst());
    assertEquals("Row 3 text", downLines.get(1));
    assertEquals("Row 19 text", downLines.get(17));
    assertTrue(downLines.getLast().startsWith("Foo"), "footer copied last: " + downLines.getLast());

    // Scrolling back up shows row 2 selected and rows before it unselected.
    app.scrollToRow(byKey(app.tree(), "list"), 0);
    app.frame();
    listEl = byKey(app.tree(), "list");
    assertFalse(listEl.children().get(1).editor().hasSelection(), "row 1 before anchor unselected");
    assertTrue(listEl.children().get(2).editor().hasSelection(), "rebuilt row 2 stays selected");
    assertFalse(listEl.children().get(2).text().selection().isEmpty());

    // Dragging upward from footer2 into row 19 copies in tree order: row 19, footer1, footer2.
    app.scrollToRow(byKey(app.tree(), "list"), 19);
    app.frame();
    listEl = byKey(app.tree(), "list");
    row19 = listEl.children().getLast();
    El footer2 = byKey(app.tree(), "footer2");
    app.pointerDown(footer2.x() + 36, footer2.y() + 12);
    app.pointerMove(listEl.x() + row19.x() + 28, listEl.y() + row19.y() + 12);
    app.pointerUp(listEl.x() + row19.x() + 28, listEl.y() + row19.y() + 12);
    app.frame();
    app.clipboard().write("stale");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    List<String> upLines = app.clipboard().read().lines().toList();
    assertEquals(3, upLines.size(), "row 19, footer1, footer2: " + upLines);
    assertTrue(upLines.get(0).endsWith("19 text"), upLines.get(0));
    assertEquals("Footer one", upLines.get(1));
    assertTrue(upLines.get(2).startsWith("Foo"), upLines.get(2));
  }

  @Test
  void selectionScopeWithoutVirtualListFollowsItsFieldsAcrossInsertAndRemove() {
    // "b" has no key, only a FieldState, which the selection follows as well.
    FieldState beta = new FieldState();
    record Item(String key, String text) {}
    List<Item> items = new ArrayList<>(List.of(
        new Item("a", "Alpha one"), new Item("b", "Beta two"), new Item("c", "Gamma three")));
    App<List<Item>> app = new App<>(items, list -> {
      El scope = col().key("scope").selectionScope().gap(8);
      for (Item item : list) {
        El field =
            El.text(item.text()).editable(v -> {}).readOnly().w(180).h(24).padding(0, 4);
        scope.child(item.key().equals("b") ? field.fieldState(beta) : field.key(item.key()));
      }
      return col().wFull().hFull().padding(10).child(scope);
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(220, 200, 1);
    app.frame();

    // Drag across "a" (y 10..34) into "b" (y 42..66).
    app.pointerDown(42, 22);
    app.pointerMove(46, 54);
    app.pointerUp(46, 54);
    app.frame();
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    String expected = app.clipboard().read();
    assertTrue(expected.contains("\n"), "cross-field selection copied: " + expected);

    // Insert a keyed field before the selection: the selection stays on "a" and "b".
    items.add(0, new Item("pre", "Inserted zero"));
    app.invalidate();
    app.frame();
    assertFalse(byKey(app.tree(), "pre").editor().hasSelection(), "inserted field is not selected");
    assertTrue(byKey(app.tree(), "pre").text().selection().isEmpty());
    assertTrue(byKey(app.tree(), "a").editor().hasSelection(), "anchor field stays selected");
    assertFalse(byKey(app.tree(), "a").text().selection().isEmpty());
    assertTrue(beta.editor.hasSelection(), "end field stays selected");
    assertFalse(byKey(app.tree(), "c").editor().hasSelection(), "trailing field stays unselected");
    app.clipboard().write("stale");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals(expected, app.clipboard().read(), "Ctrl+C copies the same fields after insert");

    // Remove the inserted field before the selection: the selection stays on "a" and "b".
    items.remove(0);
    app.invalidate();
    app.frame();
    assertTrue(byKey(app.tree(), "a").editor().hasSelection());
    assertTrue(beta.editor.hasSelection());
    assertFalse(byKey(app.tree(), "c").editor().hasSelection());
    app.clipboard().write("stale");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals(expected, app.clipboard().read(), "Ctrl+C copies the same fields after remove");

    // Remove a selected endpoint field: the selection clears cleanly.
    items.remove(0);
    app.invalidate();
    app.frame();
    assertFalse(beta.editor.hasSelection(), "selection ends when anchor leaves");
    assertTrue(beta.editor.element().text().selection().isEmpty());
  }

  @Test
  void selectionScopeInsideVirtualRowFollowsItsFieldsAcrossInsert() {
    List<String> keys = new ArrayList<>(List.of("a", "b"));
    App<List<String>> app = new App<>(
        keys,
        list -> col()
            .wFull()
            .hFull()
            .child(col().key("list").grow(1).minSize(0, 0).rows(3, 100, i -> {
              El scope = col().selectionScope().gap(8);
              for (String key : list) {
                scope.child(El.text("Field " + key)
                    .editable(v -> {})
                    .readOnly()
                    .key(key)
                    .w(180)
                    .h(24));
              }
              return scope;
            })));
    app.textRendering(TextRendering.LINUX);
    app.resize(220, 300, 1);
    app.frame();
    El list = byKey(app.tree(), "list");
    El row0 = list.children().getFirst();
    float ox = list.x() + row0.x();
    float oy = list.y() + row0.y();
    El a = byKey(app.tree(), "a");
    El b = byKey(app.tree(), "b");
    app.pointerDown(ox + a.x() + 20, oy + a.y() + 12);
    app.pointerMove(ox + b.x() + 30, oy + b.y() + 12);
    app.pointerUp(ox + b.x() + 30, oy + b.y() + 12);
    app.frame();
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    String expected = app.clipboard().read();
    assertTrue(expected.contains("\n"), expected);
    keys.add(0, "pre");
    app.invalidate();
    app.frame();
    assertFalse(byKey(app.tree(), "pre").editor().hasSelection(), "inserted field is not selected");
    app.clipboard().write("stale");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals(expected, app.clipboard().read(), "Ctrl+C copies the same fields after insert");
  }

  /**
   * A virtual list in a row of the scope's list, as high as its rows: its rows count in the scope's
   * text whether they are built or not, and selecting in one that is not built scrolls it in.
   */
  @Test
  void scopeTextAndSelectReachTheRowsOfAListInARow() {
    RowHeights outer = new RowHeights(i -> 30);
    RowHeights inner = new RowHeights(i -> 30);
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .child(col()
                .key("scope")
                .selectionScope()
                .grow(1)
                .minSize(0, 0)
                .child(col()
                    .key("list")
                    .grow(1)
                    .minSize(0, 0)
                    .rows(
                        3,
                        outer,
                        i -> i == 1
                            ? col()
                                .children(
                                    readOnly("head", "head"),
                                    col().rows(200, inner, j -> readOnly("line " + j, "l" + j)))
                            : readOnly("message " + i, "m" + i)))));
    app.textRendering(TextRendering.LINUX);
    app.resize(220, 160, 1);
    app.frame();

    El scope = byKey(app.tree(), "scope");
    String all = app.scopeText(scope);
    assertTrue(all.startsWith("message 0\nhead\nline 0\nline 1\n"), all);
    assertTrue(all.endsWith("\nline 199\nmessage 2"), all);

    int at = all.indexOf("line 150");
    assertNull(byKey(app.tree(), "l150"), "row 150 is not built yet");
    app.select(scope, at + 8, at + 5); // "150"
    app.frame();
    El field = byKey(app.tree(), "l150");
    assertNotNull(field, "row 150 scrolled into view and built");
    assertEquals("150", field.editor().selectedText());

    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals("150", app.clipboard().read());
  }

  private static El readOnly(String text, String key) {
    return El.text(text).editable(v -> {}).readOnly().key(key).w(180).h(24);
  }

  @Test
  void scopeTextSelectAndDecorateSpanFieldsAndVirtualRows() {
    int[] mark = {0, 0};
    App<Object> app = new App<>(
        new Object(),
        state -> col()
            .wFull()
            .hFull()
            .child(col()
                .key("scope")
                .selectionScope()
                .grow(1)
                .minSize(0, 0)
                .decorate((line, start, text, out) -> {
                  int end = start + text.length();
                  if (mark[1] > start && mark[0] < end) {
                    int a = Math.max(mark[0], start) - start;
                    int b = Math.min(mark[1], end) - start;
                    out.background(a, b, 0x44FFCC00);
                    out.underline(a, b, 0xFFFFCC00);
                  }
                })
                .child(El.text("lead")
                    .editable(v -> {})
                    .readOnly()
                    .key("lead")
                    .w(180)
                    .h(24))
                .child(El.text("hidden")
                    .editable(v -> {})
                    .readOnly()
                    .masked()
                    .w(180)
                    .h(24))
                .child(col().key("list").grow(1).minSize(0, 0).rows(30, 30, i -> {
                  El field = El.text("row " + i + " item")
                      .editable(v -> {})
                      .readOnly()
                      .multiline()
                      .fitLines()
                      .key("r" + i)
                      .w(180);
                  if (i == 0) {
                    field.decorate((line, start, text, out) -> out.background(0, 3, 0x2200FF00));
                  }
                  return field;
                }))
                .child(El.text("tail")
                    .editable(v -> {})
                    .readOnly()
                    .key("tail")
                    .w(180)
                    .h(24))));
    app.textRendering(TextRendering.LINUX);
    app.resize(220, 160, 2);
    app.frame();

    El scope = byKey(app.tree(), "scope");
    String all = app.scopeText(scope);
    assertTrue(all.startsWith("lead\nrow 0 item\n"), all);
    assertFalse(all.contains("hidden"), "masked fields are excluded");
    assertTrue(all.endsWith("\nrow 29 item\ntail"), all);

    // Decorate a range in row 0: merged with row 0's own decorator.
    int row0Start = all.indexOf("row 0 item");
    mark[0] = row0Start + 6; // "item"
    mark[1] = row0Start + 10;
    app.invalidate();
    app.frame();
    El r0 = byKey(app.tree(), "r0");
    assertTrue(r0.text().marks().size() >= 3, "own background plus scope background and underline");
    assertEquals(2, r0.text().underlined().length);
    assertEquals(6, r0.text().underlined()[0]);
    assertEquals(10, r0.text().underlined()[1]);

    // Select a match in an out-of-view row: scrolls it into view, decorates it, and Ctrl+C copies.
    int row25Start = all.indexOf("row 25 item");
    assertEquals(null, byKey(app.tree(), "r25"), "row 25 is not built yet");
    mark[0] = row25Start + 4;
    mark[1] = row25Start + 11; // "25 item"
    app.select(byKey(app.tree(), "scope"), mark[1], mark[0]);
    app.frame();

    El r25 = byKey(app.tree(), "r25");
    assertNotNull(r25, "row 25 scrolled into view and built");
    assertEquals("25 item", r25.editor().selectedText());
    assertEquals(2, r25.text().underlined().length);
    assertEquals(4, r25.text().underlined()[0]);
    assertEquals(11, r25.text().underlined()[1]);

    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals("25 item", app.clipboard().read());
  }

  @Test
  void selectionScopeKeepsColumnsWhenSpansListScrollsSideways() {
    int cols = 8;
    float colWidth = 100;
    float rowHeight = 24;
    App<Object> app = new App<>(
        new Object(),
        state -> row()
            .key("area")
            .scrollBoth()
            .selectionScope()
            .w(220)
            .h(120)
            .child(col()
                .w(cols * colWidth)
                .h(120)
                .child(col().key("list").grow(1).minSize(0, 0).rows(6, rowHeight, (i, from, to) -> {
                  El r = row().key("row-" + i);
                  int first = cols;
                  int last = -1;
                  for (int c = 0; c < cols; c++) {
                    float left = c * colWidth;
                    if (left + colWidth > from && left < to) {
                      first = Math.min(first, c);
                      last = c;
                    }
                  }
                  if (first < cols) {
                    r.padding(0, 0, 0, first * colWidth);
                    for (int c = first; c <= last; c++) {
                      r.child(El.text("R" + i + "C" + c + " text")
                          .editable(v -> {})
                          .readOnly()
                          .key("cell-" + i + "-" + c)
                          .w(colWidth)
                          .h(rowHeight));
                    }
                  }
                  return r;
                }))));
    app.textRendering(TextRendering.LINUX);
    app.resize(220, 120, 1);
    app.frame();

    assertNull(byKey(app.tree(), "cell-1-4"), "trailing columns are outside the initial span");

    // Drag from row 1 col 0 into row 2 col 1.
    app.pointerDown(30, rowHeight + 12);
    app.pointerMove(colWidth + 45, 2 * rowHeight + 12);
    app.pointerUp(colWidth + 45, 2 * rowHeight + 12);
    app.frame();
    assertTrue(byKey(app.tree(), "cell-1-0").editor().hasSelection());
    assertFalse(byKey(app.tree(), "cell-2-2").editor().hasSelection());

    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    String expected = app.clipboard().read();
    List<String> lines = expected.lines().toList();
    assertEquals(10, lines.size(), "row 1 cols 0..7 + row 2 cols 0..1: " + lines);
    assertEquals("R1C4 text", lines.get(4), "unbuilt column in row 1 copied");
    assertEquals("R2C0 text", lines.get(8));

    // Scroll sideways far enough that both anchor (col 0) and end (col 1) leave the built span.
    app.wheel(100, 60, 480, 0);
    app.frame();
    assertNull(byKey(app.tree(), "cell-1-0"), "anchor column left the built span");
    assertTrue(byKey(app.tree(), "cell-1-4").editor().hasSelection(), "row 1 stays highlighted");
    assertFalse(byKey(app.tree(), "cell-2-3").editor().hasSelection(), "row 2 after the end");
    app.clipboard().write("stale");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals(expected, app.clipboard().read(), "Ctrl+C copies the same while scrolled");

    // Scroll back: the anchor and end columns return with their highlight.
    app.wheel(100, 60, -480, 0);
    app.frame();
    assertTrue(byKey(app.tree(), "cell-1-0").editor().hasSelection());
    assertTrue(byKey(app.tree(), "cell-2-1").editor().hasSelection());
    assertFalse(byKey(app.tree(), "cell-2-2").editor().hasSelection());
    app.clipboard().write("stale");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    assertEquals(expected, app.clipboard().read(), "Ctrl+C is unchanged after scrolling back");

    // Shift+click while scrolled sideways extends from the unbuilt anchor column.
    app.wheel(100, 60, 480, 0);
    app.frame();
    app.pointerDown(5 * colWidth - 480 + 45, 2 * rowHeight + 12, 1, Key.SHIFT);
    app.pointerUp(5 * colWidth - 480 + 45, 2 * rowHeight + 12);
    app.frame();
    assertTrue(byKey(app.tree(), "cell-2-5").editor().hasSelection(), "row 2 col 5 is the new end");
    assertFalse(byKey(app.tree(), "cell-2-6").editor().hasSelection());
    app.clipboard().write("stale");
    app.keyDown(new Key("C", Key.CONTROL, false));
    app.frame();
    List<String> extended = app.clipboard().read().lines().toList();
    assertEquals(14, extended.size(), "row 1 cols 0..7 + row 2 cols 0..5: " + extended);
    assertEquals(lines.get(0), extended.get(0), "anchor offset in unbuilt col 0 preserved");
  }
}
