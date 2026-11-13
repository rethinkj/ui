package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drags: the handler's steps, how a drag differs from a press, cancelling, and scroll areas. */
class DragTest {

  private static final int GREY = 0xFF808080;
  private static final int BLUE = 0xFF3B82F6;

  @TempDir Path out;

  /** A bar whose width the drag of its handle changes, as a column's edge does. */
  static final class Bar {
    float width = 100;
    boolean shown = true;
    int views;
    final List<String> log = new ArrayList<>();
  }

  /**
   * The bar at 0,0, 20 high, with a handle of 10 by 20 over its right end; the handle takes
   * presses and drags. 300 by 100.
   */
  private static App<Bar> bar() {
    App<Bar> app = new App<>(new Bar(), b -> {
      b.views++;
      float start = b.width;
      El root = row().wFull().hFull().fill(GREY).onPress(() -> b.log.add("root"));
      if (b.shown) {
        root.child(el().key("bar").size(b.width, 20).fill(GREY).child(
            el().key("handle").absolute(b.width - 10, 0).size(10, 20).fill(GREY)
                .cursor(Cursor.EW_RESIZE)
                .whenPressed(e -> e.fill(BLUE))
                .onPress(() -> b.log.add("press"))
                .onDrag(d -> {
                  b.log.add(d.phase() + " " + d.dx() + "," + d.dy());
                  b.width = d.phase() == Drag.Phase.CANCEL ? start : start + d.dx();
                })));
      }
      return root;
    });
    app.resize(300, 100, 1);
    app.frame();
    return app;
  }

  @Test
  void aDragStartsBeyondTheThresholdAndMovesUntilTheRelease() {
    App<Bar> app = bar();
    Bar bar = app.state();
    app.pointerDown(95, 10);
    app.pointerMove(98, 13); // 3 pixels: not yet
    app.frame();
    assertEquals(List.of(), bar.log);
    assertEquals(BLUE, find(app, "handle").fill(), "pressed meanwhile");

    app.pointerMove(100, 10); // 5 pixels: a drag
    assertEquals(List.of(), bar.log, "handlers run in the frame");
    app.frame();
    assertEquals(List.of("START 5.0,0.0"), bar.log);
    assertEquals(105, find(app, "bar").width());

    app.pointerMove(250, 60); // outside the bar and the handle
    app.pointerMove(400, -20); // outside the window
    app.frame();
    assertEquals(List.of("START 5.0,0.0", "MOVE 155.0,50.0", "MOVE 305.0,-30.0"), bar.log);
    assertEquals(405, bar.width, "the handler of the view at the start");
    assertEquals(Cursor.EW_RESIZE, app.cursor(), "the dragged element's, wherever the pointer is");

    app.pointerUp(390, -20);
    app.frame();
    assertEquals("END 295.0,-30.0", bar.log.getLast());
    assertEquals(4, bar.log.size(), "no press, not even of the root under the pointer");
    assertEquals(395, bar.width);
    assertEquals(GREY, find(app, "handle").fill(), "released");
    assertEquals(Cursor.DEFAULT, app.cursor());
  }

  @Test
  void aPressThatStaysWithinTheThresholdIsAPress() {
    App<Bar> app = bar();
    app.pointerDown(95, 10);
    app.pointerMove(99, 6);
    app.pointerUp(99, 6);
    app.frame();
    assertEquals(List.of("press"), app.state().log);
    assertEquals(100, find(app, "bar").width());
  }

  @Test
  void stepsQueuedWithoutFramesInBetweenRunInOrder() {
    App<Bar> app = bar();
    app.pointerDown(95, 10);
    app.pointerMove(120, 10);
    app.pointerMove(140, 10);
    app.pointerUp(140, 10);
    app.frame();
    assertEquals(List.of("START 25.0,0.0", "MOVE 45.0,0.0", "END 45.0,0.0"), app.state().log);
    assertEquals(145, find(app, "bar").width());
  }

  @Test
  void aDragIsCancelledWhenItsElementLeavesTheTree() {
    App<Bar> app = bar();
    Bar bar = app.state();
    app.pointerDown(95, 10);
    app.pointerMove(130, 10);
    app.frame();
    bar.shown = false;
    app.resize(300, 100, 1); // rebuilds without the bar
    app.frame();
    app.pointerMove(150, 10);
    app.pointerUp(150, 10);
    app.frame();
    assertEquals(List.of("START 35.0,0.0", "CANCEL 55.0,0.0"), bar.log, "nothing after that");
    assertEquals(100, bar.width);
  }

  @Test
  void aSecondPointerDownCancelsADragWhoseReleaseGotLost() {
    App<Bar> app = bar();
    app.pointerDown(95, 10);
    app.pointerMove(120, 10);
    app.frame();
    app.pointerDown(200, 50); // on the root
    app.pointerUp(200, 50);
    app.frame();
    assertEquals(List.of("START 25.0,0.0", "CANCEL 25.0,0.0", "root"), app.state().log);
    assertEquals(100, app.state().width);
  }

  @Test
  void escapeCancelsADragAndTheReleaseAfterItPressesNothing() {
    App<Bar> app = bar();
    app.pointerDown(95, 10);
    app.pointerMove(120, 10);
    app.keyDown(new Key("Escape", 0, false));
    app.pointerMove(140, 10);
    app.pointerUp(140, 10);
    app.frame();
    assertEquals(List.of("START 25.0,0.0", "CANCEL 25.0,0.0"), app.state().log);
    assertEquals(100, app.state().width);
  }

  @Test
  void aPressOnAnElementWithADragHandlerOnlyDoesNotReachItsAncestors() {
    App<List<String>> app = new App<>(new ArrayList<>(), log ->
        row().wFull().hFull().onPress(() -> log.add("root"))
            .child(el().key("edge").size(10, 10).onDrag(d -> log.add(d.phase().name()))));
    app.resize(100, 100, 1);
    app.frame();
    app.pointerMove(5, 5);
    app.pointerDown(5, 5);
    app.pointerUp(5, 5);
    app.frame();
    assertEquals(List.of(), app.state());
    assertTrue(app.tree().dump().contains("el #edge 0,0 10x10 hovered\n"), app.tree().dump());
  }

  @Test
  void theWheelScrollsDuringADragInsideAScrollArea() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = new App<>(log, l ->
        col().key("area").size(100, 100).scroll().children(
            el().key("knob").size(100, 20).onDrag(d -> l.add(d.phase() + " " + d.dy())),
            el().size(100, 300)));
    app.resize(100, 100, 1);
    app.frame();
    app.pointerDown(50, 10);
    app.pointerMove(50, 30);
    app.wheel(50, 30, 0, 40);
    app.frame();
    El area = app.tree();
    assertEquals(40, area.scrollY());
    assertEquals(-40, find(app, "knob").y(), "scrolled out of view");
    app.pointerMove(50, 60);
    app.pointerUp(50, 60);
    app.frame();
    assertEquals(List.of("START 20.0", "MOVE 50.0", "END 50.0"), log, "the pointer's distance");
    assertEquals(40, area.scrollY(), "the drag scrolls nothing itself");
  }

  @Test
  void aScriptDragsWithDownMoveAndUp() throws IOException {
    App<Bar> app = bar();
    Script.run(
        "size 300 100\nframe\ndown #handle\nmove 160,10\nframe\nsnap dragging\nup 160,10\nframe",
        app, out);
    assertEquals(List.of("START 65.0,0.0", "END 65.0,0.0"), app.state().log);
    assertEquals(165, find(app, "bar").width());
    assertNull(app.pressed());
  }

  // Keeping a row in view

  /** A list of 1000 rows of 20 pixels in a window of 100, keeping one row in view. */
  static final class Rows {
    int shown = 50;
  }

  private static App<Rows> rows() {
    App<Rows> app = new App<>(new Rows(), r ->
        col().key("list").wFull().hFull().revealRow(r.shown)
            .rows(1000, 20, i -> el().key("row-" + i)));
    app.resize(100, 100, 1);
    app.frame();
    return app;
  }

  @Test
  void aListScrollsToTheRowItKeepsInViewWhenTheRowChanges() {
    App<Rows> app = rows();
    El list = app.tree();
    assertEquals(51 * 20 - 100, list.scrollY(), "row 50 at the bottom from the first frame");

    app.wheel(50, 50, 0, 2000);
    app.frame();
    assertEquals(2920, list.scrollY(), "the wheel scrolls it away");
    app.resize(100, 100, 1); // a rebuild with the same row
    app.frame();
    assertEquals(2920, app.tree().scrollY(), "and it stays away");

    app.state().shown = 300;
    app.resize(100, 100, 1);
    app.frame();
    assertEquals(301 * 20 - 100, app.tree().scrollY(), "row 300, below, at the bottom");
    app.state().shown = 100;
    app.resize(100, 100, 1);
    app.frame();
    assertEquals(100 * 20, app.tree().scrollY(), "row 100, above, at the top");
    app.state().shown = 104;
    app.resize(100, 100, 1);
    app.frame();
    assertEquals(100 * 20, app.tree().scrollY(), "already in view");
    app.state().shown = -1;
    app.resize(100, 100, 1);
    app.frame();
    assertEquals(100 * 20, app.tree().scrollY());
  }

  private static El find(App<?> app, String key) {
    return find(app.tree(), key);
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
