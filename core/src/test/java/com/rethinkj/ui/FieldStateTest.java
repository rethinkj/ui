package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static com.rethinkj.ui.Fields.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    App<Model> app = new App<>(model, m -> col().wFull().hFull().padding(10).gap(4).children(
        // The same place for either query: the identity of the field is the same.
        query(m, m.active),
        el().key("bar").focusable().size(10, 10)));
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
    App<Model> app = new App<>(model, m -> col().children(
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
    App<Model> app = new App<>(model, m -> col().key("window-" + window).wFull().hFull()
        .padding(10).gap(4).children(
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
  void theModeOfTabComesBack() {
    TextLibraries.assumeLoaded();
    App<Model> app = started();
    app.focus(field(app));
    app.frame();
    key(app, "M", Key.CONTROL); // Tab moves the focus from now on
    choose(app, 1);
    choose(app, 0);
    app.focus(field(app));
    app.frame();
    key(app, "Tab");
    assertEquals("bar", app.focused().key(), "Tab still moves the focus");
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
    assertEquals("xSELECT 1", app.state().sql[0],
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
    model.sql[0] = "SELECT id, name, total FROM orders;\n".repeat(100_000);
    model.sql[1] = "SELECT 2";
    App<Model> app = start(model);
    app.focus(field(app));
    app.frame();
    key(app, "End", Key.CONTROL);
    Lines lines = field(app).editor().lineIndex();
    double[] back = new double[5];
    for (int i = 0; i < back.length; i++) {
      choose(app, 1);
      long start = System.nanoTime();
      choose(app, 0);
      back[i] = (System.nanoTime() - start) / 1e6;
    }
    assertSame(lines, field(app).editor().lineIndex(), "the lines are not counted again");
    System.out.printf("FieldStateTest, 100000 lines (3.5 MB): back to the tab in %s ms%n",
        java.util.Arrays.toString(java.util.Arrays.stream(back)
            .map(ms -> Math.round(ms * 100) / 100.0).toArray()));
  }
}
