package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a table needs from core, without text: presses with their modifiers and at pointer down,
 * drags that know where the pointer is in a scrolled element, elements that stay in place
 * sideways, rows built for the part in view sideways, a part kept in view by key, the rows a
 * list built told to the app, and the {@code current} state.
 */
class TableTest {

  @TempDir
  Path out;

  /** The app's state: a log, and what the views read. */
  static final class Log {
    final List<String> lines = new ArrayList<>();
    Object reveal;
    float revealX;
    int rows = 1000;
  }

  private static App<Log> app(java.util.function.Function<Log, El> view) {
    return new App<>(new Log(), view);
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 200 100\n" + steps, app, out);
  }

  @Test
  void aPressHandlerIsToldTheModifiersAndAPointerDownHandlerRunsAtOnce() throws IOException {
    App<Log> app = app(s -> col()
        .wFull()
        .hFull()
        .onPointerDown((clicks, modifiers) -> s.lines.add("down " + clicks + " " + modifiers))
        .child(el().key("box")
            .size(80, 40)
            .focusable()
            .onPress((clicks, modifiers) -> s.lines.add("press " + clicks + " " + modifiers))));
    run(app, "frame\ndown #box shift\nframe");
    assertEquals(List.of("down 1 1"), app.state().lines, "at once, from the ancestor");
    run(app, "up #box\nframe\npress #box clicks 2 control\nframe\nkey Enter\nframe");
    assertEquals(
        List.of("down 1 1", "press 1 1", "down 2 2", "press 2 2", "press 1 0"),
        app.state().lines,
        "the modifiers held when the pointer went down; Enter has none");
  }

  @Test
  void aDragIsToldWhereThePointerIsInTheContentOfItsScrolledElement() throws IOException {
    App<Log> app = app(s -> col()
        .wFull()
        .hFull()
        .padding(10)
        .child(col()
            .key("area")
            .scroll()
            .h(80)
            .onDrag(d -> s.lines.add(d.phase() + " " + d.x() + "," + d.y()))
            .child(el().h(1000).w(100))));
    run(app, "frame\ndown 20,20\nmove 20,30\nframe");
    assertEquals(List.of("START 10.0,20.0"), app.state().lines, "from the area's top left");
    // The scroll that the wheel queued comes after the steps of the frame that runs it.
    run(app, "wheel 20,30 0 100\nframe\nmove 21,30\nframe");
    assertEquals("MOVE 11.0,120.0", app.state().lines.getLast(), "with the offset of its scroll");
  }

  @Test
  void aFixedElementStaysInPlaceSidewaysAndTakesThePressThere() throws IOException {
    App<Log> app = app(s -> row()
        .key("area")
        .scrollBoth()
        .w(200)
        .h(60)
        .child(row()
            .w(600)
            .h(60)
            .padding(0, 0, 0, 50)
            .children(
                el().key("moving").w(100).h(60).onPress(() -> s.lines.add("moving")),
                el().key("fixed")
                    .absolute(0, 0)
                    .size(50, 60)
                    .fill(0xFF000000)
                    .fixedX()
                    .onPress(() -> s.lines.add("fixed")))));
    run(app, "frame\nwheel 100,30 80 0\nframe");
    assertEquals(80, find(app.tree(), "area").scrollX());
    assertEquals(0, x(app.tree(), "fixed"), "kept in place");
    assertEquals(-30, x(app.tree(), "moving"), "50 - 80");
    run(app, "press 20,30\nframe");
    assertEquals(List.of("fixed"), app.state().lines, "drawn over and hit before what scrolls");
    assertTrue(app.tree().dump().contains("el #fixed 0,0 50x60"), app.tree().dump());
  }

  @Test
  void rowsAreBuiltForThePartInViewSidewaysAndAnewBeyondIt() throws IOException {
    List<String> built = new ArrayList<>();
    App<Log> app = app(s -> row()
        .key("area")
        .scrollBoth()
        .w(200)
        .h(100)
        .child(col().w(1000).h(100).child(col().key("list").grow(1).rows(50, 20, (i, from, to) -> {
          if (i == 0) {
            built.add(from + "-" + to);
          }
          return el();
        }))));
    run(app, "frame");
    assertEquals(List.of("0.0-300.0"), built, "the 200 in view and half as much on each side");
    app.wheel(100, 50, 50, 0); // not run(), whose size step builds the view anew
    app.frame();
    assertEquals(1, built.size(), "50 to 250 lies inside " + built);
    app.wheel(100, 50, 100, 0);
    app.frame();
    assertEquals(List.of("0.0-300.0", "50.0-450.0"), built, "150 to 350 does not");
  }

  @Test
  void aKeyKeepsItsRectangleInViewWhenItChangesAndOnlyThen() throws IOException {
    App<Log> app = app(s -> row()
        .key("area")
        .scrollBoth()
        .w(200)
        .h(50)
        .reveal(s.reveal, s.revealX, 0, 60, 0)
        .child(el().w(1000).h(50).onPress(() -> s.lines.add("press"))));
    run(app, "frame");
    El area = find(app.tree(), "area");
    Log s = app.state();
    s.reveal = 1;
    s.revealX = 500;
    run(app, "press 10,10\nframe");
    assertEquals(360, find(app.tree(), "area").scrollX(), "500 + 60 at the right edge");
    s.revealX = 800; // the same key: it stays
    run(app, "press 10,10\nframe");
    assertEquals(360, find(app.tree(), "area").scrollX());
    s.reveal = 2;
    s.revealX = 100;
    run(app, "press 10,10\nframe");
    assertEquals(100, find(app.tree(), "area").scrollX(), "its start at the left edge");
    assertTrue(area != find(app.tree(), "area"));
  }

  @Test
  void aListTellsWhichRowsItBuiltWhenTheyChangeInTheNextFrame() throws IOException {
    App<Log> app = app(s -> col()
        .key("list")
        .wFull()
        .hFull()
        .onRowsBuilt((first, last) -> s.lines.add(first + "-" + last))
        .rows(s.rows, 20, i -> el().key("row-" + i).onPress(() -> s.lines.add("press"))));
    run(app, "frame");
    assertEquals(List.of(), app.state().lines);
    assertEquals(app.time(), app.nextChange(), "due at once");
    run(app, "frame\nframe");
    assertEquals(List.of("0-7"), app.state().lines, "5 rows in view and 3 more");
    run(app, "press #row-1\nframe\nframe"); // the view is built again: the same rows
    assertEquals(List.of("0-7", "press"), app.state().lines);
    run(app, "scroll #list row 100\nframe\nframe");
    assertEquals("97-107", app.state().lines.getLast());
  }

  @Test
  void theCurrentStateIsDumpedAndSelected() throws IOException {
    App<Log> app = app(s -> col()
        .wFull()
        .hFull()
        .children(
            el().key("a").size(20, 20).selected(true),
            el().key("b").size(20, 20).selected(true).current(true)));
    run(app, "frame");
    assertTrue(app.tree().dump().contains("el #b 0,20 20x20 selected current\n"));
    run(app, "press selected current\nframe"); // one element: the current one
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

  /** The window x of the element with this key. */
  private static float x(El root, String key) {
    return x(root, key, 0);
  }

  private static float x(El el, String key, float left) {
    float x = left + el.x();
    if (key.equals(el.key())) {
      return x;
    }
    for (El child : el.children()) {
      float found = x(child, key, x);
      if (!Float.isNaN(found)) {
        return found;
      }
    }
    return Float.NaN;
  }

  @Test
  void pointerHooksInTheRowsOfAListRunAfterARebuildBetweenInputs() {
    List<String> seen = new ArrayList<>();
    App<Void> app = new App<>(
        null,
        s -> col()
            .size(100, 100)
            .rows(
                1,
                20,
                i -> el().onAnyPointerDown(() -> seen.add("any"))
                    .onPressOutside(() -> seen.add("outside"))));
    app.resize(100, 100, 1);
    app.frame();
    app.post(() -> {}); // the view is built again before the press, at first without its rows
    app.pointerDown(50, 80);
    app.frame();
    assertEquals(List.of("any", "outside"), seen);
  }
}
