package com.rethinkj.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Where a frameless window resizes from; needs no window. */
class EdgesTest {

  private static int edge(float x, float y) {
    return Window.edge(x, y, 300, 200, 6);
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
}
