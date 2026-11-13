package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Input that reaches the app between two frames, as from a held key or during a slow frame: each
 * input is dispatched against a tree built from the state that the inputs before it left, and an
 * input that no handler took costs no rebuild.
 */
class BurstTest {

  /** A counter that a key handler raises from the value of its view, and the views built. */
  static final class Counter {
    int count;
    int views;
    final List<String> log = new ArrayList<>();
  }

  private static El counter(Counter s) {
    s.views++;
    int count = s.count; // what the handler sees is the view's, as a selection in a grid
    return row().wFull().hFull().focusable().key("counter").onKey(k -> {
      if (!k.name().equals("ArrowUp")) {
        return false;
      }
      s.count = count + 1;
      return true;
    });
  }

  private static App<Counter> start() {
    App<Counter> app = new App<>(new Counter(), BurstTest::counter);
    app.resize(100, 100, 1);
    app.frame();
    app.focus(app.tree());
    app.frame();
    return app;
  }

  @Test
  void eachKeyOfABurstSeesWhatTheKeyBeforeItChanged() {
    App<Counter> app = start();
    for (int i = 0; i < 3; i++) {
      app.keyDown(new Key("ArrowUp"));
      app.keyUp(new Key("ArrowUp"));
    }
    app.frame();
    assertEquals(3, app.state().count);
  }

  @Test
  void onlyInputThatAHandlerTookCostsARebuildBeforeTheNext() {
    App<Counter> app = start();
    int views = app.state().views;
    app.pointerMove(10, 10);
    app.keyDown(new Key("A")); // the handler does not take it
    app.pointerMove(20, 20);
    app.keyDown(new Key("Tab")); // moves the focus, around to where it is: no handler
    app.frame();
    assertEquals(views + 1, app.state().views, "only the frame's own rebuild");

    views = app.state().views;
    app.keyDown(new Key("ArrowUp"));
    app.pointerMove(30, 30);
    app.keyDown(new Key("ArrowUp"));
    app.keyDown(new Key("A"));
    app.frame();
    assertEquals(views + 3, app.state().views, "one after each ArrowUp, the second the frame's");
    assertEquals(2, app.state().count);
  }

  @Test
  void aPressAfterAHandlerGoesWhereThatHandlersChangePutThings() {
    // A key inserts a box before the button, which moves it from 0..40 to 40..80; a press at 60
    // in the same frame is on the button as the state now places it.
    Counter s = new Counter();
    App<Counter> app = new App<>(s, c -> {
      El root = row().wFull().hFull().focusable().onKey(k -> {
        c.count++;
        return true;
      });
      if (c.count > 0) {
        root.child(el().key("new").size(40, 20));
      }
      return root.child(el().key("button").size(40, 20).onPress(() -> c.log.add("press")));
    });
    app.resize(100, 20, 1);
    app.frame();
    app.focus(app.tree());
    app.frame();
    app.keyDown(new Key("A"));
    app.pointerDown(60, 10);
    app.pointerUp(60, 10);
    app.frame();
    assertEquals(List.of("press"), s.log);
  }

  /** A list of rows with a field each, whose handler keeps only digits. */
  static final class Digits {
    final String[] values = {"", "", ""};
  }

  @Test
  void editsOfAFieldInARowEachGetTheAppsAnswer() {
    TextLibraries.assumeLoaded();
    Digits s = new Digits();
    App<Digits> app = new App<>(
        s,
        d -> col()
            .key("rows")
            .wFull()
            .hFull()
            .rows(
                3,
                30,
                i -> El.text(d.values[i])
                    .editable(v -> {
                      if (v.chars().allMatch(Character::isDigit)) {
                        d.values[i] = v;
                      }
                    })
                    .key("field")
                    .w(100)));
    app.textRendering(TextRendering.LINUX);
    app.resize(100, 90, 1);
    app.frame();
    app.pointerDown(50, 45); // the field of row 1
    app.pointerUp(50, 45);
    app.frame();
    app.textInput("8");
    app.textInput("a");
    app.textInput("8");
    app.frame();
    assertEquals("88", s.values[1], "the second 8 builds on the first, not on the refused a");
    assertEquals(2, app.focused().editor().caret());
  }
}
