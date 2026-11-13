package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Scroll areas that keep their offsets in a {@link ScrollState} of the app's ({@link
 * El#scrollState}): they leave the tree, as the content of a tab that is not active, or go to the
 * tree of another app, as a tab to another window, and come back where they were.
 */
class ScrollStateTest {

  /** Two tabs, each a list of rows and a panel that scrolls both ways; the view shows one. */
  static final class Model {
    final ScrollState[] rows = {new ScrollState(), new ScrollState()};
    final ScrollState[] panels = {new ScrollState(), new ScrollState()};
    final RowHeights[] heights = {new RowHeights(i -> 20), new RowHeights(i -> 20)};
    int active;
    boolean keep = true;
    boolean shared;
    boolean measured; // the list measures its rows instead of a fixed height
    int reveal = -1;
  }

  private static El view(Model m) {
    int t = m.active;
    El list = (m.measured
        ? col().rows(1000, m.heights[t], i -> el().h(10 + i % 3 * 10))
        : col().rows(1000, 20, i -> el().h(20)))
        .key("list-" + t).w(100).h(100).revealRow(m.reveal);
    El panel = col().key("panel-" + t).scrollBoth().size(100, 100)
        .child(el().size(400, 400));
    if (m.keep) {
      list.scrollState(m.rows[t]);
      panel.scrollState(m.panels[m.shared ? 0 : t]);
      if (m.shared) {
        list.scrollState(m.panels[0]);
      }
    }
    return El.row().children(list, panel);
  }

  private static App<Model> app(Model model) {
    App<Model> app = new App<>(model, ScrollStateTest::view);
    app.resize(300, 200, 1);
    app.frame();
    return app;
  }

  private static El list(App<?> app) {
    return app.tree().children().get(0);
  }

  private static El panel(App<?> app) {
    return app.tree().children().get(1);
  }

  private static void choose(App<Model> app, int tab) {
    app.state().active = tab;
    app.invalidate();
    app.frame();
  }

  @Test
  void theOffsetsComeBackAfterAnotherTab() {
    for (boolean measured : new boolean[] {false, true}) {
      Model model = new Model();
      model.measured = measured;
      App<Model> app = app(model);
      app.wheel(50, 50, 0, 1234);
      app.wheel(150, 50, 70, 90);
      app.frame();
      double down = list(app).scrollY();
      assertTrue(down > 1000, "scrolled");
      assertEquals(70, panel(app).scrollX());
      assertEquals(down, model.rows[0].y());

      choose(app, 1);
      assertEquals(0, list(app).scrollY(), "the other tab at its start");
      assertEquals(0, panel(app).scrollX());
      choose(app, 0);
      assertEquals(down, list(app).scrollY(), "back where it was");
      assertEquals(70, panel(app).scrollX());
      assertEquals(90, panel(app).scrollY());
      assertTrue(app.tree().dump().contains(" scroll 70,90"), app.tree().dump());
    }
  }

  @Test
  void withoutOneTheAreasForget() {
    Model model = new Model();
    model.keep = false;
    App<Model> app = app(model);
    app.wheel(50, 50, 0, 500);
    app.frame();
    assertEquals(500, list(app).scrollY());
    choose(app, 1);
    choose(app, 0);
    assertEquals(0, list(app).scrollY());
  }

  @Test
  void twoAreasCannotShareOne() {
    Model model = new Model();
    model.shared = true;
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> app(model));
    assertTrue(e.getMessage().contains("ScrollState of its own"), e.getMessage());
  }

  @Test
  void aRowItKeptInViewDoesNotPullItBack() {
    Model model = new Model();
    model.reveal = 2;
    App<Model> app = app(model);
    app.wheel(50, 50, 0, 3000);
    app.frame();
    choose(app, 1);
    choose(app, 0);
    assertEquals(3000, list(app).scrollY(), "the row it showed before it left counts as seen");
    model.reveal = 3; // a new row to keep in view still scrolls
    app.invalidate();
    app.frame();
    assertEquals(60, list(app).scrollY());
  }

  @Test
  void anAreaThatMovesToAnotherAppComesAsItWas() {
    for (boolean arrivingFirst : new boolean[] {true, false}) {
      Model model = new Model();
      int[] shown = {0, 1}; // the tab each of two windows shows
      App<Model> from = window(model, shown, 0);
      App<Model> to = window(model, shown, 1);
      from.wheel(50, 50, 0, 777);
      from.wheel(150, 50, 33, 44);
      from.frame();

      shown[0] = 1; // the tabs change windows
      shown[1] = 0;
      for (App<Model> app : arrivingFirst ? List.of(to, from) : List.of(from, to)) {
        app.invalidate();
        app.frame();
      }
      assertEquals(777, list(to).scrollY(), "the rows where they were");
      assertEquals(33, panel(to).scrollX());
      assertEquals(44, panel(to).scrollY());
      assertEquals(0, list(from).scrollY(), "and the other tab where it was");
      to.wheel(50, 50, 0, -77);
      to.frame();
      assertEquals(700, model.rows[0].y());

      shown[0] = 0; // and back
      shown[1] = 1;
      for (App<Model> app : arrivingFirst ? List.of(from, to) : List.of(to, from)) {
        app.invalidate();
        app.frame();
      }
      assertEquals(700, list(from).scrollY());
      assertEquals(33, panel(from).scrollX());
    }
  }

  private static App<Model> window(Model model, int[] shown, int index) {
    App<Model> app = new App<>(model, m -> {
      m.active = shown[index];
      return view(m);
    });
    app.resize(300, 200, 1);
    app.frame();
    return app;
  }
}
