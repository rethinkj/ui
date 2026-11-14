package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Split.split;
import static com.rethinkj.ui.kit.Workbench.dumpLine;
import static com.rethinkj.ui.kit.Workbench.find;
import static com.rethinkj.ui.kit.Workbench.x;
import static com.rethinkj.ui.kit.Workbench.y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Split.Direction;
import com.rethinkj.ui.kit.Split.Position;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The split pane of {@link Workbench#splitView}, the schema beside an editor in a window of 480 by
 * 200: the schema at least 120 wide, the editor 160, the line 1 wide in a strip of 7. What keeps
 * its size, drags, minimums, keys, the reset, and stored images.
 */
class SplitTest {

  private static final Theme T = Theme.DARK;

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Workbench> start(Position sidebar) throws IOException {
    Workbench w = new Workbench(T);
    w.sidebar = sidebar;
    App<Workbench> app = w.app(Workbench::splitView);
    run(app, "frame");
    return app;
  }

  private String size = "size 480 200\n";

  /** Runs the steps in the window of the last {@code size} step, which each script needs first. */
  private void run(App<?> app, String steps) throws IOException {
    Script.run(size + steps, app, out);
    steps
        .lines()
        .filter(l -> l.startsWith("size "))
        .reduce((a, b) -> b)
        .ifPresent(l -> size = l + "\n");
  }

  /** The widths of the schema's box and the editor's, and where the line is. */
  private static void assertParts(App<?> app, float first, float second) {
    assertEquals(first, find(app, "first").width(), "first");
    assertEquals(second, find(app, "second").width(), "second");
    assertEquals(first + 1, x(app, find(app, "second")), "the second after the line");
    El divider = find(app, "divider");
    assertEquals(first - 3, x(app, divider), "the strip around the line");
    assertEquals(7, divider.width());
    assertEquals(first, x(app, divider.children().getFirst()), "the line");
  }

  // What keeps its size

  @Test
  void aListOfRowsInAPartScrollsInsideASplitInAColumnThatGrows() {
    for (Direction direction : Direction.values()) {
      App<Object> app = new App<>(
          new Object(),
          s -> col()
              .wFull()
              .hFull()
              .child(split(
                      T,
                      direction,
                      Position.first(100),
                      null,
                      null,
                      col().key("list").rows(1000, 20, i -> El.el()),
                      50,
                      col().key("other").rows(1000, 20, i -> El.el()),
                      50)
                  .key("split")
                  .grow(1)));
      app.resize(300, 200, 1);
      app.frame();
      assertEquals(200, find(app, "split").height(), direction.toString());
      assertEquals(direction == Direction.ROW ? 200 : 100, find(app, "list").height());
      app.wheel(20, 20, 0, 100);
      app.frame();
      assertEquals(100, find(app, "list").scrollY(), "the rows scroll inside the part");
    }
  }

  @Test
  void theFirstPartKeepsItsWidthAndTheSecondTakesTheRest() throws IOException {
    App<Workbench> app = start(Position.first(200));
    assertParts(app, 200, 279);
    run(app, "size 800 200\nframe");
    assertParts(app, 200, 599);
  }

  @Test
  void theSecondPartCanKeepItsWidthInstead() throws IOException {
    App<Workbench> app = start(Position.second(200));
    assertParts(app, 279, 200);
    run(app, "size 800 200\nframe");
    assertParts(app, 599, 200);
  }

  @Test
  void aShareGrowsBothParts() throws IOException {
    App<Workbench> app = start(Position.share(0.5f));
    run(app, "size 481 200\nframe");
    assertParts(app, 240, 240);
    run(app, "size 801 200\nframe");
    assertParts(app, 400, 400);
  }

  @Test
  void aColumnSplitsTheHeightTheSameWay() throws IOException {
    Workbench w = new Workbench(T);
    App<Workbench> app = w.app(Workbench::columnView);
    run(app, "size 300 201\nframe");
    El divider = find(app, "divider");
    assertEquals(100, find(app, "first").height());
    assertEquals(100, find(app, "second").height());
    assertEquals(101, y(app, find(app, "second")));
    assertEquals(97, y(app, divider));
    assertEquals(7, divider.height());
    assertEquals(300, divider.width());

    run(app, "move #divider\nframe");
    assertEquals(Cursor.NS_RESIZE, app.cursor());
    run(app, "key Tab\nkey ArrowLeft\nkey ArrowDown\nframe");
    assertEquals(List.of("editor " + Position.share(0.58f)), w.log, "116 of 200");
    assertEquals(116, find(app, "first").height());
  }

  // Dragging

  @Test
  void draggingTheLineReportsWhereItIsWithinBothMinimums() throws IOException {
    App<Workbench> app = start(Position.first(200));
    Workbench w = app.state();
    run(app, "move #divider\nframe");
    assertEquals(Cursor.EW_RESIZE, app.cursor());
    assertEquals(T.border().strong(), line(app).fill(), "under the pointer");

    run(app, "down 200,100\nmove 280,100\nframe");
    assertEquals(List.of("sidebar " + Position.first(280)), w.log);
    assertParts(app, 280, 199);
    assertEquals(T.accent().color(), line(app).fill(), "while it is dragged");

    run(app, "move 0,100\nframe");
    assertParts(app, 120, 359);
    run(app, "move 470,150\nframe");
    assertParts(app, 319, 160);
    assertEquals(Cursor.EW_RESIZE, app.cursor(), "the strip's, wherever the pointer is");
    run(app, "up 470,150\nframe");
    assertEquals(
        List.of(
            "sidebar " + Position.first(280),
            "sidebar " + Position.first(120),
            "sidebar " + Position.first(319),
            "sidebar " + Position.first(319)),
        w.log);
    assertEquals(T.border().divider(), line(app).fill(), "the pointer is over the editor");
  }

  @Test
  void aCancelledDragPutsTheLineBack() throws IOException {
    App<Workbench> app = start(Position.first(200));
    app.pointerDown(200, 100);
    app.pointerMove(260, 100);
    app.frame();
    app.pointerDown(10, 10); // the release got lost
    app.frame();
    assertEquals(Position.first(200), app.state().sidebar);
    assertParts(app, 200, 279);
  }

  @Test
  void aDragKeepsTheKindOfPosition() throws IOException {
    App<Workbench> app = start(Position.second(200));
    run(app, "down 279,100\nmove 229,100\nup 229,100\nframe");
    assertEquals(Position.second(250), app.state().sidebar);
    assertParts(app, 229, 250);

    app.state().sidebar = Position.share(0.5f);
    run(app, "size 481 200\nframe\ndown 240,100\nmove 120,100\nup 120,100\nframe");
    assertEquals(Position.share(0.25f), app.state().sidebar, "120 of 480");
    assertParts(app, 120, 360);
  }

  // Minimums

  @Test
  void aNarrowerWindowKeepsTheMinimumsAndAWiderOneBringsThePositionBack() throws IOException {
    App<Workbench> app = start(Position.first(319));
    run(app, "size 300 200\nframe");
    assertParts(app, 139, 160);
    run(app, "size 250 200\nframe");
    assertEquals(120, find(app, "first").width(), "no room for both: the first keeps its own");
    assertEquals(250, find(app, "split").width());
    run(app, "size 480 200\nframe");
    assertParts(app, 319, 160);
    assertEquals(List.of(), app.state().log, "the position stays as it was");

    app.state().sidebar = Position.share(0.75f);
    run(app, "size 480 200\nframe");
    assertParts(app, 319, 160);
    run(app, "size 1001 200\nframe");
    assertParts(app, 750, 250);
  }

  // Keys and the reset

  @Test
  void theArrowKeysMoveTheFocusedLineInSteps() throws IOException {
    App<Workbench> app = start(Position.first(200));
    Workbench w = app.state();
    run(app, "key Tab\nframe");
    assertEquals(find(app, "divider"), app.focused());

    run(app, "key ArrowRight\nframe");
    assertParts(app, 216, 263);
    assertTrue(dumpLine(app, "divider").contains(" value=\"first 216\""));
    run(app, "key ArrowLeft\nkey ArrowLeft\nframe");
    assertParts(app, 184, 295);
    run(app, "key ArrowUp\nkey ArrowRight shift\nkey ArrowRight control\nframe");
    assertEquals(
        List.of(
            "sidebar " + Position.first(216),
            "sidebar " + Position.first(200),
            "sidebar " + Position.first(184)),
        w.log,
        "two keys in one frame take two steps; the others are not the line's");
    run(app, "key ArrowLeft\nkey ArrowLeft\nkey ArrowLeft\nkey ArrowLeft\nframe");
    assertParts(app, 120, 359);
  }

  @Test
  void aDoublePressPutsTheLineWhereTheCallerSays() throws IOException {
    App<Workbench> app = start(Position.first(300));
    run(app, "press #divider\nframe");
    assertEquals(List.of(), app.state().log);
    assertNull(app.focused(), "a press on the line leaves the focus where it was");
    run(app, "press #divider clicks 2\nframe");
    assertEquals(List.of("sidebar " + Workbench.SIDEBAR), app.state().log);
    assertParts(app, 200, 279);
  }

  @Test
  void withoutAHandlerTheLineStaysWhereItIs() throws IOException {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .child(split(
                    T,
                    Direction.ROW,
                    Position.first(200),
                    Position.first(100),
                    null,
                    col(),
                    120,
                    col(),
                    160)
                .grow(1)));
    run(app, "size 480 200\nframe\nmove 200,100\nframe");
    assertEquals(Cursor.DEFAULT, app.cursor());
    assertFalse(find(app, "divider").isFocusable());
    run(app, "down 200,100\nmove 300,100\nup 300,100\nkey Tab\nframe");
    assertNull(app.focused());
    assertEquals(200, find(app, "first").width());
  }

  @Test
  void positionsAreChecked() {
    assertThrows(IllegalArgumentException.class, () -> Position.share(1.5f));
    assertThrows(IllegalArgumentException.class, () -> Position.share(Float.NaN));
    assertThrows(IllegalArgumentException.class, () -> Position.first(-1));
    assertThrows(IllegalArgumentException.class, () -> Position.second(Float.POSITIVE_INFINITY));
    assertEquals(Position.first(0), new Position(Position.Kind.FIRST, 0));
  }

  private static El line(App<?> app) {
    return find(app, "divider").children().getFirst();
  }

  // Images

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void looksAsStored(String name) throws IOException {
    Workbench w = new Workbench(name.equals("dark") ? Theme.DARK : Theme.LIGHT);
    assertEquals(8, Workbench.assertLooksAsStored("split", name, w.app(Workbench::splitView)));
  }
}
