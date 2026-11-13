package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.WindowPart;
import org.junit.jupiter.api.Test;

/** Where a frameless window resizes from, and where it gives way to the app; needs no window. */
class EdgesTest {

  private static int edge(float x, float y) {
    return Window.edge(x, y, 300, 200, 6);
  }

  @Test
  void aTitleBarComesBackIntoTheWorkAreaFarEnoughToBeGrabbed() {
    int[] area = {0, 0, 1920, 1032}; // a work area above a taskbar of 48
    int[] fine = {300, 200, 800, 32};
    assertEquals(0, Window.reachable(fine, area, 120)[0]);
    assertEquals(0, Window.reachable(fine, area, 120)[1]);
    int[] above = {300, -400, 800, 32}; // let go above the top of the screen
    assertEquals(400, Window.reachable(above, area, 120)[1]);
    assertEquals(0, Window.reachable(above, area, 120)[0]);
    int[] under = {300, 1020, 800, 32}; // its bar under the taskbar
    assertEquals(-20, Window.reachable(under, area, 120)[1]);
    int[] left = {-790, 500, 800, 32}; // 10 pixels of it left on the screen
    assertEquals(110, Window.reachable(left, area, 120)[0], "120 of it on the screen again");
    int[] right = {1900, 500, 800, 32};
    assertEquals(-100, Window.reachable(right, area, 120)[0]);
    int[] narrow = {-90, 500, 100, 32}; // narrower than the part that has to show: all of it
    assertEquals(90, Window.reachable(narrow, area, 120)[0]);
    int[] second = {2000, -50, 800, 32}; // a display to the right, at x 1920
    int[] there = {1920, 0, 2560, 1400};
    assertEquals(50, Window.reachable(second, there, 120)[1]);
    assertEquals(0, Window.reachable(second, there, 120)[0]);
  }

  @Test
  void theMarginAlongEachEdgeResizes() {
    assertEquals(Sdl.HITTEST_RESIZE_LEFT, edge(0, 100));
    assertEquals(Sdl.HITTEST_RESIZE_LEFT, edge(5.9f, 100));
    assertEquals(Sdl.HITTEST_NORMAL, edge(6, 100));
    assertEquals(Sdl.HITTEST_RESIZE_RIGHT, edge(299, 100));
    assertEquals(Sdl.HITTEST_RESIZE_RIGHT, edge(294, 100));
    assertEquals(Sdl.HITTEST_NORMAL, edge(293.9f, 100));
    assertEquals(Sdl.HITTEST_RESIZE_TOP, edge(150, 0));
    assertEquals(Sdl.HITTEST_RESIZE_BOTTOM, edge(150, 199));
    assertEquals(Sdl.HITTEST_NORMAL, edge(150, 100));
  }

  @Test
  void cornersReachFurtherAlongTheEdges() {
    assertEquals(Sdl.HITTEST_RESIZE_TOPLEFT, edge(0, 0));
    assertEquals(Sdl.HITTEST_RESIZE_TOPLEFT, edge(13, 0));
    assertEquals(Sdl.HITTEST_RESIZE_TOP, edge(14, 0));
    assertEquals(Sdl.HITTEST_RESIZE_TOPLEFT, edge(0, 13));
    assertEquals(Sdl.HITTEST_RESIZE_LEFT, edge(0, 14));
    assertEquals(Sdl.HITTEST_RESIZE_TOPRIGHT, edge(299, 0));
    assertEquals(Sdl.HITTEST_RESIZE_TOPRIGHT, edge(290, 2));
    assertEquals(Sdl.HITTEST_RESIZE_BOTTOMLEFT, edge(3, 190));
    assertEquals(Sdl.HITTEST_RESIZE_BOTTOMRIGHT, edge(299, 199));
    assertEquals(Sdl.HITTEST_RESIZE_BOTTOMRIGHT, edge(287, 197));
  }

  @Test
  void noMarginNoEdges() {
    assertEquals(Sdl.HITTEST_NORMAL, Window.edge(0, 0, 300, 200, 0));
    assertEquals(Sdl.HITTEST_NORMAL, Window.edge(299, 100, 300, 200, -1));
  }

  /**
   * A window of 300 by 200: a title bar 30 high with a close button 46 wide at the right, a list of
   * pressable rows with a scrollbar along the window's right edge, its thumb from x 292 to 298, and
   * a status line 30 high without handlers at the bottom.
   */
  private static App<Object> framed() {
    Scrollbars look = new Scrollbars(6, 2, 3, 24, 0xFF505050, 0xFF707070, 0xFF909090);
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .size(300, 200)
            .child(row()
                .wFull()
                .h(30)
                .windowPart(WindowPart.DRAG)
                .child(el().grow(1))
                .child(el().size(46, 30).windowPart(WindowPart.CLOSE)))
            .child(col()
                .key("list")
                .wFull()
                .grow(1)
                .minSize(0, 0)
                .scrollbars(look)
                .rows(1000, 20, i -> el().key("row-" + i).onPress(() -> {})))
            .child(el().wFull().h(30).shrink(0)));
    app.resize(300, 200, 1);
    app.frame();
    return app;
  }

  private static int zone(App<?> app, float x, float y) {
    return Window.zone(x, y, 300, 200, 6, app.windowPart(x, y));
  }

  @Test
  void theMarginGivesWayToWhatTakesPresses() {
    App<Object> app = framed();
    assertEquals(
        "el #list 0,30 300x140 rows 0-9/1000 thumb-y 292,32 6x24",
        app.tree()
            .dump()
            .lines()
            .filter(l -> l.contains("#list"))
            .findFirst()
            .orElseThrow()
            .strip());
    assertEquals(WindowPart.CONTROL, app.windowPart(297, 40), "the scrollbar");
    assertEquals(Sdl.HITTEST_NORMAL, zone(app, 297, 40), "the thumb's outer pixel");
    assertEquals(Sdl.HITTEST_NORMAL, zone(app, 294, 100), "the track");
    assertEquals(Sdl.HITTEST_RESIZE_RIGHT, zone(app, 298, 40), "the last two pixels resize");
    assertEquals(Sdl.HITTEST_RESIZE_RIGHT, zone(app, 297, 185), "the status line below");
    assertEquals(Sdl.HITTEST_RESIZE_RIGHT, zone(app, 294, 185));

    assertEquals(WindowPart.CONTROL, app.windowPart(3, 100), "a row");
    assertEquals(Sdl.HITTEST_NORMAL, zone(app, 3, 100));
    assertEquals(Sdl.HITTEST_RESIZE_LEFT, zone(app, 1, 100));
    assertEquals(Sdl.HITTEST_RESIZE_LEFT, zone(app, 3, 185), "the status line");

    assertEquals(Sdl.HITTEST_RESIZE_TOP, zone(app, 150, 4), "the drag area");
    assertEquals(Sdl.HITTEST_DRAGGABLE, zone(app, 150, 10));
    assertEquals(Sdl.HITTEST_NORMAL, zone(app, 270, 4), "the close button");
    assertEquals(Sdl.HITTEST_RESIZE_TOP, zone(app, 270, 1));
    assertEquals(Sdl.HITTEST_RESIZE_TOPRIGHT, zone(app, 299, 10), "the corner keeps its reach");
    assertEquals(Sdl.HITTEST_RESIZE_TOPRIGHT, zone(app, 288, 1));
    assertEquals(Sdl.HITTEST_NORMAL, zone(app, 288, 4));
  }

  @Test
  void withoutAMarginNothingResizes() {
    App<Object> app = framed();
    assertEquals(Sdl.HITTEST_NORMAL, Window.zone(298, 40, 300, 200, 0, app.windowPart(298, 40)));
    assertEquals(Sdl.HITTEST_DRAGGABLE, Window.zone(150, 1, 300, 200, 0, WindowPart.DRAG));
  }
}
