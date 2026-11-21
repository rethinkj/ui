package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A field that keeps its editing in a {@link FieldState} of the app's ({@link El#fieldState}): it
 * leaves the tree, as the content of a tab that is not active, and comes back as it was.
 */
class FieldStateTest {

  /** Two queries, of which the view shows the active one, as tabs show their content. */
  static final class Model {
    final String[] sql = new String[2];
    final FieldState[] fields = {new FieldState(), new FieldState()};
    int active;
    boolean keep = true; // whether the fields have their FieldState
    boolean shared; // whether both fields of a second view share one
  }

  private static El query(Model m, int i) {
    El field = El.text(m.sql[i])
        .editable(v -> m.sql[i] = v)
        .multiline()
        .tabIndents(true)
        .key("query")
        .font(Font.mono())
        .fontSize(14)
        .w(200)
        .lines(3)
        .padding(4)
        .scroll();
    return m.keep ? field.fieldState(m.fields[m.shared ? 0 : i]) : field;
  }

  private static App<Model> start(Model model) {
    App<Model> app = new App<>(
        model,
        m -> col()
            .wFull()
            .hFull()
            .padding(10)
            .gap(4)
            .children(
                // The same place for either query: the identity of the field is the same.
                query(m, m.active), el().key("bar").focusable().size(10, 10)));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 240, 1);
    app.frame();
    return app;
  }

  private static App<Model> started() {
    Model model = new Model();
    StringBuilder lines = new StringBuilder();
    for (int i = 1; i <= 20; i++) {
      lines.append("SELECT ").append(i).append(i < 20 ? "\n" : "");
    }
    model.sql[0] = lines.toString();
    model.sql[1] = "SELECT 2";
    return start(model);
  }

  private static void choose(App<Model> app, int tab) {
    app.state().active = tab;
    app.focus(app.tree().children().get(1)); // as a press on a tab leaves the focus on the bar
    app.frame();
  }

  private static El field(App<?> app) {
    return app.tree().children().getFirst();
  }

  @Test
  void twoFieldsCannotShareOne() {
    // Before layout, so without the text libraries.
    Model model = new Model();
    model.sql[0] = "a";
    model.sql[1] = "b";
    model.shared = true;
    FieldState shared = new FieldState();
    App<Model> app = new App<>(
        model,
        m -> col()
            .children(
                El.text("a").editable(v -> {}).key("one").fieldState(shared),
                El.text("b").editable(v -> {}).key("two").fieldState(shared)));
    app.resize(320, 240, 1);
    IllegalStateException e = assertThrows(IllegalStateException.class, app::frame);
    assertTrue(e.getMessage().contains("FieldState of its own"), e.getMessage());
  }

  @Test
  void caretSelectionAndScrollComeBack() {
    TextLibraries.assumeLoaded();
    App<Model> app = started();
    app.focus(field(app));
    app.frame();
    key(app, "End", Key.CONTROL); // the last line, scrolled into view
    key(app, "ArrowLeft", Key.SHIFT);
    key(app, "ArrowLeft", Key.SHIFT);
    double scrolled = field(app).scrollY();
    assertTrue(scrolled > 0, "scrolled to the end");
    String before = show(app);

    choose(app, 1);
    assertEquals("SELECT 2", field(app).editor().text());
    assertEquals(0, field(app).scrollY(), "the other query starts at its top");
    choose(app, 0);

    app.focus(field(app));
    app.frame();
    assertEquals(before, show(app), "caret and selection as they were");
    assertEquals(scrolled, field(app).scrollY(), "and the scroll");
    assertSame(app.state().fields[0].editor, field(app).editor());
  }

  /** The view of a window that shows the first query while {@code active} names it. */
  private static App<Model> window(Model model, int window) {
    App<Model> app = new App<>(
        model,
        m -> col()
            .key("window-" + window)
            .wFull()
            .hFull()
            .padding(10)
            .gap(4)
            .children(
                m.active == window ? query(m, 0) : el().key("empty").size(10, 10),
                el().key("bar").focusable().size(10, 10)));
    app.textRendering(TextRendering.LINUX);
    app.resize(320, 240, 1);
    app.frame();
    return app;
  }

  /**
   * A field that moves to the tree of another app of the same state, as a tab to another window,
   * comes with its caret, selection, scroll and history, whichever app builds first, and the same
   * build number in both is no second field.
   */
  @Test
  void aFieldThatMovesToAnotherAppComesAsItWas() {
    TextLibraries.assumeLoaded();
    for (boolean arrivingFirst : new boolean[] {true, false}) {
      Model model = started().state();
      model.active = 0;
      App<Model> from = window(model, 0);
      App<Model> to = window(model, 1);
      from.focus(field(from));
      from.frame();
      type(from, "x");
      key(from, "End", Key.CONTROL);
      key(from, "ArrowLeft", Key.SHIFT);
      double scrolled = field(from).scrollY();
      assertTrue(scrolled > 0, "scrolled to the end");
      String before = show(from);

      model.active = 1; // the tab goes to the other window
      while (to.builds < from.builds) { // the next builds of both have one number
        to.invalidate();
        to.frame();
      }
      assertEquals(from.builds, to.builds);
      for (App<Model> app : arrivingFirst ? List.of(to, from) : List.of(from, to)) {
        app.invalidate();
        app.frame();
      }
      assertEquals(before, show(to), "caret and selection as they were");
      assertEquals(scrolled, field(to).scrollY(), "and the scroll");
      to.focus(field(to));
      to.frame();
      key(to, "Z", Key.CONTROL);
      assertTrue(model.sql[0].startsWith("SELECT 1\n"), "undo took back the typing");
      key(to, "End", Key.CONTROL);
      String there = show(to);
      double down = field(to).scrollY();

      model.active = 0; // and back
      for (App<Model> app : arrivingFirst ? List.of(from, to) : List.of(to, from)) {
        app.invalidate();
        app.frame();
      }
      assertEquals(there, show(from));
      assertEquals(down, field(from).scrollY(), "the scroll back as it was in the other");
    }
  }

  @Test
  void undoGoesBackToTheStepBeforeTheSwitch() {
    TextLibraries.assumeLoaded();
    App<Model> app = started();
    app.focus(field(app));
    app.frame();
    type(app, "x");
    key(app, "ArrowLeft"); // ends the run of typing: two steps
    key(app, "ArrowRight");
    type(app, "y");
    assertTrue(app.state().sql[0].startsWith("xyS"), app.state().sql[0]);

    choose(app, 1);
    app.focus(field(app));
    app.frame();
    type(app, "z");
    choose(app, 0);
    app.focus(field(app));
    app.frame();
    key(app, "Z", Key.CONTROL);
    assertTrue(app.state().sql[0].startsWith("xS"), "the last step before the switch undone");
    key(app, "Z", Key.CONTROL);
    assertTrue(app.state().sql[0].startsWith("SELECT 1\n"), "and the one before");
    assertEquals("zSELECT 2", app.state().sql[1], "the other query keeps its own");
    key(app, "Z", Key.CONTROL | Key.SHIFT);
    assertTrue(app.state().sql[0].startsWith("xS"), "redone");
  }

  @Test
  void replaceSelectionIsOneStepOfTheFieldThatHasTheState() {
    TextLibraries.assumeLoaded();
    App<Model> app = started();
    FieldState state = app.state().fields[0];
    state.select(6, 0); // SELECT
    app.frame();
    app.post(() -> state.replaceSelection("VALUES")); // as a button's handler
    app.frame();
    assertTrue(show(app).startsWith("VALUES| 1\nSELECT 2"), show(app));
    app.undo(field(app));
    app.frame();
    assertTrue(show(app).startsWith("^SELECT| 1\nSELECT 2"), "one step: " + show(app));

    app.post(() -> {
      state.replaceSelection("x");
      app.state().active = 1; // the field there now is another query's,
      app.state().keep = false; // without a state, which takes over the editor
    });
    app.frame();
    assertEquals("SELECT 2", app.state().sql[1]);
    assertTrue(app.state().sql[0].startsWith("SELECT 1\n"), app.state().sql[0]);
  }

  @Test
  void replaceSelectionPutsTheCaretAtItsPlaceInTheText() {
    TextLibraries.assumeLoaded();
    App<Model> app = started();
    FieldState state = app.state().fields[0];
    state.select(6, 6); // after SELECT
    app.frame();
    app.post(() -> state.replaceSelection("()", 1)); // between the parentheses
    app.frame();
    assertTrue(show(app).startsWith("SELECT(|) 1\n"), show(app));

    state.select(0, 0);
    app.frame();
    app.post(() -> state.replaceSelection("SELECT 0\n", 8)); // a line of its own, before its break
    app.frame();
    assertTrue(show(app).startsWith("SELECT 0|\nSELECT() 1\n"), show(app));
    app.undo(field(app));
    app.frame();
    assertTrue(show(app).startsWith("|SELECT() 1\n"), "one step: " + show(app));

    assertThrows(IllegalArgumentException.class, () -> state.replaceSelection("()", 3));
    assertThrows(IllegalArgumentException.class, () -> state.replaceSelection("()", -1));
  }

  @Test
  void replaceEditsTheRangeAndUndoBringsBackTheSelection() {
    TextLibraries.assumeLoaded();
    App<Model> app = started();
    FieldState state = app.state().fields[0];
    state.select(5, 3); // SEL^EC|T 1, inside the line
    app.frame();
    app.post(() -> state.replace(8, 9, "\nSELECT 0\n", 9)); // a line below, the caret at its end
    app.frame();
    assertTrue(show(app).startsWith("SELECT 1\nSELECT 0|\nSELECT 2"), show(app));
    app.undo(field(app));
    app.frame();
    assertTrue(show(app).startsWith("SEL^EC|T 1\nSELECT 2"), "where it was: " + show(app));

    app.state().sql[0] = "SELECT 1;́"; // a combining accent after the semicolon
    app.frame();
    app.post(() -> state.replace(9, 9, " -- one", 7)); // not moved before the semicolon
    app.frame();
    assertEquals("SELECT 1; -- oné", app.state().sql[0]);

    assertThrows(IllegalArgumentException.class, () -> state.replace(-1, 0, "x", 0));
    assertThrows(IllegalArgumentException.class, () -> state.replace(2, 1, "x", 0));
    assertThrows(IllegalArgumentException.class, () -> state.replace(0, 0, "x", 2));
  }

  @Test
  void aStringTheAppChangedMeanwhileEndsTheHistory() {
    TextLibraries.assumeLoaded();
    App<Model> app = started();
    app.focus(field(app));
    app.frame();
    type(app, "x");
    choose(app, 1);
    app.state().sql[0] = "SELECT 42";
    choose(app, 0);
    app.focus(field(app));
    app.frame();
    key(app, "Z", Key.CONTROL);
    assertEquals("SELECT 42", app.state().sql[0], "nothing to undo");
    assertEquals("S|ELECT 42", show(app), "the caret near its text");
  }

  @Test
  void withoutOneTheFieldForgets() {
    TextLibraries.assumeLoaded();
    Model model = new Model();
    model.keep = false;
    model.sql[0] = "SELECT 1";
    model.sql[1] = "SELECT 2";
    App<Model> app = start(model);
    app.focus(field(app));
    app.frame();
    type(app, "x");
    choose(app, 1);
    choose(app, 0);
    app.focus(field(app));
    app.frame();
    key(app, "Z", Key.CONTROL);
    assertEquals(
        "xSELECT 1",
        app.state().sql[0],
        "as before: the identity's editor took the other string, which ended the history");
  }

  @Test
  void textInputIsOnAsSoonAsAPressLandsInAField() {
    TextLibraries.assumeLoaded();
    App<Model> app = started();
    app.focus(app.tree().children().get(1));
    app.frame();
    assertNull(app.inputArea(), "the focus on an element that takes no text");
    El f = field(app);
    app.pointerDown(f.x() + 20, f.y() + 10);
    assertNotNull(app.inputArea(), "a window turns text input on before the frame");
    app.pointerUp(f.x() + 20, f.y() + 10);
    app.textInput("x"); // typed before the frame, which arrives because text input was on
    app.frame();
    assertTrue(app.state().sql[0].contains("x"), app.state().sql[0]);
    assertNotNull(app.inputArea());
  }

  @Test
  void aLongTextComesBackWithItsLines() {
    TextLibraries.assumeLoaded();
    Model model = new Model();
    model.sql[0] = "SELECT id, name, total FROM orders;\n".repeat(2000);
    model.sql[1] = "SELECT 2";
    App<Model> app = start(model);
    app.focus(field(app));
    app.frame();
    key(app, "End", Key.CONTROL);
    Lines lines = field(app).editor().lineIndex();
    choose(app, 1);
    choose(app, 0);
    assertSame(lines, field(app).editor().lineIndex(), "the lines are not counted again");
  }

  /** A field whose selection the app set and whose row layout then left out hears nothing. */
  static final class Rows {
    final FieldState top = new FieldState();
    final FieldState inRow = new FieldState();
    String text = "abc";
    boolean collapsed;
    App<Rows> app;
    El field;
    El list;
    El inRowField;
    int told;
    int toldAtKey = -1; // what the handler of F8 found
  }

  @Test
  void aSelectionSetInARowThatLayoutLeavesOutTellsNothing() {
    TextLibraries.assumeLoaded();
    Rows rows = new Rows();
    RowHeights heights = new RowHeights(index -> index == 0 ? 1200 : 20);
    rows.app = new App<>(rows, r -> {
      r.field = El.text(r.text)
          .editable(value -> r.text = value)
          .fieldState(r.top)
          .onSelect((caret, anchor) -> {})
          .h(24)
          .onKey(key -> {
            if (!key.name().equals("F6")) {
              return false;
            }
            r.collapsed = true; // the first row shrinks, so that the rows in view lie below it
            r.top.select(0, 3);
            r.inRow.select(1, 0);
            return true;
          });
      r.list = el().wFull().h(200).rows(100, heights, index -> {
        if (index != 0) {
          return el().h(20);
        }
        El inRow = El.text("word").h(20).editable(value -> {}).fieldState(r.inRow);
        inRow.onSelect((caret, anchor) -> r.app.select(inRow, 4, 0));
        return col().h(r.collapsed ? 20 : 1200).child(inRow);
      });
      return col().wFull().hFull().child(r.field).child(r.list);
    });
    App<Rows> app = rows.app;
    app.textRendering(TextRendering.LINUX);
    app.resize(400, 240, 1);
    app.frame();
    app.focus(rows.field);
    app.frame();
    app.wheel(30, 50, 0, 1000);
    app.frame();
    assertTrue(rows.list.hasRow(0), "scrolled into the first row");
    app.keyDown(new Key("F6"));
    app.textInput("z");
    app.frame();
    assertEquals("z", rows.text);
  }

  @Test
  void aFocusedFieldWhoseRowLayoutLeavesOutIsToldOnceScrollingBringsItBack() {
    Rows rows = focusInARowThatLayoutLeftOut();
    int before = rows.told;
    rows.app.wheel(30, 50, 0, -1000);
    rows.app.frame();
    assertTrue(rows.list.hasRow(0), "scrolled back");
    assertTrue(rows.told > before, "told once it is back");
    rows.app.keyDown(new Key("F8"));
    rows.app.frame();
    assertTrue(rows.toldAtKey > before, "before the next key");
  }

  @Test
  void aFieldWhoseRowLayoutLeftOutIsNotToldWhenItLosesTheFocus() {
    Rows rows = focusInARowThatLayoutLeftOut();
    int before = rows.told;
    rows.app.pointerDown(30, 220); // below the list: the focus leaves the field
    rows.app.frame();
    assertEquals(before, rows.told);
  }

  /** A focused field whose row shrank out of the rows in view, with a selection set meanwhile. */
  private static Rows focusInARowThatLayoutLeftOut() {
    TextLibraries.assumeLoaded();
    Rows rows = new Rows();
    RowHeights heights = new RowHeights(index -> index == 0 ? 1200 : 20);
    rows.app = new App<>(rows, r -> {
      r.list = el().wFull().h(200).rows(100, heights, index -> {
        if (index != 0) {
          return el().h(20);
        }
        El inRow = El.text("word").h(20).editable(value -> {}).fieldState(r.inRow);
        inRow.onSelect((caret, anchor) -> {
          r.told++;
          r.app.select(inRow, caret, anchor);
        });
        inRow.onKey(key -> {
          if (key.name().equals("F8")) {
            r.toldAtKey = r.told;
            return true;
          }
          if (!key.name().equals("F6")) {
            return false;
          }
          r.collapsed = true; // the row shrinks out of the rows in view, with the focus in it
          r.inRow.select(1, 0);
          return true;
        });
        r.inRowField = inRow;
        return col().h(r.collapsed ? 20 : 1200).child(inRow);
      });
      return col().wFull().hFull().child(r.list);
    });
    App<Rows> app = rows.app;
    app.textRendering(TextRendering.LINUX);
    app.resize(400, 240, 1);
    app.frame();
    app.focus(rows.inRowField);
    app.frame();
    app.wheel(30, 50, 0, 1000);
    app.frame();
    assertTrue(rows.list.hasRow(0), "scrolled within the first row");
    int before = rows.told;
    app.keyDown(new Key("F6"));
    app.frame();
    assertFalse(rows.list.hasRow(0), "the shrunk row is out of view");
    assertEquals(before, rows.told, "not told while its row is gone");
    return rows;
  }

  static final class Moves {
    final FieldState state = new FieldState();
    String text = "";
    int caret = -1;
    int caretAtKey = -1; // what the handler of F8 found
    boolean moved;
    El field;
  }

  @Test
  void aSelectionTheFramesLastBuildAppliesIsToldBeforeTheNextKey() {
    TextLibraries.assumeLoaded();
    Moves moves = new Moves();
    App<Moves> app = new App<>(moves, m -> {
      m.field = El.text(m.text)
          .h(24)
          .editable(value -> m.text = value)
          .fieldState(m.state)
          .onSelect((caret, anchor) -> {
            m.caret = caret;
            if (caret == 3 && !m.moved) {
              m.moved = true;
              m.state.select(0, 0); // the frame's last build applies it
            }
          })
          .onKey(key -> {
            if (!key.name().equals("F8")) {
              return false;
            }
            m.caretAtKey = m.caret;
            return true;
          });
      return col().wFull().hFull().child(m.field);
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(300, 100, 1);
    app.frame();
    app.focus(moves.field);
    app.frame();
    app.textInput("abc");
    app.frame();
    app.keyDown(new Key("F8"));
    app.frame();
    assertEquals(0, moves.caretAtKey);
  }

  static final class Drafts {
    String text = "";
    boolean open = true;
    El field;
  }

  @Test
  void anEditThatClosesItsFieldLeavesNothingToANewFieldUnderItsKey() {
    TextLibraries.assumeLoaded();
    Drafts drafts = new Drafts();
    App<Drafts> app = new App<>(drafts, d -> {
      El root = col().wFull().hFull();
      if (d.open) {
        d.field = El.text(d.text).key("draft").h(24).editable(value -> {
          d.text = value.endsWith("!") ? "" : value; // the draft is sent and closed
          d.open = !value.endsWith("!");
        });
        root.child(d.field);
      }
      return root;
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(300, 100, 1);
    app.frame();
    app.focus(drafts.field);
    app.frame();
    app.textInput("hi");
    app.frame();
    app.textInput("!");
    app.frame();
    drafts.open = true; // a new draft
    app.invalidate();
    app.frame();
    app.focus(drafts.field);
    app.frame();
    app.keyDown(new Key("Z", Key.CONTROL, false));
    app.frame();
    assertEquals("", drafts.text, "nothing of the closed draft's history");
  }

  static final class Panel {
    String text = "";
    boolean open = true;
    boolean grown; // the row above is tall, which pushes the draft's out of view
    boolean growOnEdit;
    El field;
    El list;
    El other;
  }

  @Test
  void anEditWaitingInARowOfAListThatFadesOutLeavesNothingToANewDraft() {
    TextLibraries.assumeLoaded();
    Panel panel = new Panel();
    App<Panel> app = new App<>(panel, p -> {
      p.other = el().h(20).focusable();
      El root = col().wFull().hFull().child(p.other);
      if (p.open) {
        p.list = el().wFull()
            .h(100)
            .rows(20, new RowHeights(i -> i == 0 && p.grown ? 2000 : 24), i -> {
              if (i != 5) {
                return el().h(i == 0 && p.grown ? 2000 : 24);
              }
              p.field = El.text(p.text).key("draft").h(24).wFull().editable(value -> {
                p.text = value;
                p.grown |= p.growOnEdit;
              });
              return p.field;
            });
        root.child(el().key("panel").motion(Spring.SNAP).child(p.list));
      }
      return root;
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(300, 200, 1);
    app.frame();
    app.focus(panel.field);
    app.frame();
    app.textInput("h");
    app.frame();
    panel.growOnEdit = true;
    app.textInput("i"); // its answer leaves the row out
    app.frame();
    assertFalse(panel.list.hasRow(5), "the row is out of view");
    panel.growOnEdit = false;
    app.focus(panel.other); // the focus no longer keeps the draft's editor
    app.frame();
    panel.open = false; // the panel fades out
    app.invalidate();
    app.frame();
    while (app.isMoving()) {
      app.advance(1.0 / 60);
      app.frame();
    }
    panel.open = true; // a new, empty draft under the same keys
    panel.text = "";
    panel.grown = false;
    app.invalidate();
    app.frame();
    app.focus(panel.field);
    app.frame();
    app.keyDown(new Key("Z", Key.CONTROL, false));
    app.frame();
    assertEquals("", panel.text, "nothing of the closed draft's history");
  }
}
