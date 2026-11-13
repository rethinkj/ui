package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

  @Test
  void aLostReleaseOfOneKindOfDragEndsItWhenThePointerGoesDownForTheOther() {
    App<Model> app = start();
    app.pointerDown(150, 60);
    app.pointerMove(120, 60);
    app.frame();
    app.pointerDown(15, 22); // in the field, without a release before it
    app.pointerMove(100, 22);
    app.frame();
    assertEquals(List.of("START", "CANCEL"), app.state().log, "the drag is undone");
    assertEquals("^hello world|", show(app), "and the field selects");

    app.pointerDown(150, 60); // beside the field, again without a release
    app.pointerMove(15, 22);
    app.pointerUp(15, 22);
    app.frame();
    assertEquals(List.of("START", "CANCEL", "START", "END"), app.state().log);
    assertEquals("^hello world|", show(app), "the moves selected nothing more");
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
}
