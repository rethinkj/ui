package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Scene;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scroll areas: the wheel, offsets kept across rebuilds, and what is scrolled out of view. */
class ScrollTest {

  @TempDir
  Path out;

  /** Rows of 20 pixels with a key each in an area 50 pixels tall; ten scroll 150 pixels. */
  private static El rows(String key, int count) {
    El list = col().key(key).scroll().size(100, 50);
    for (int i = 0; i < count; i++) {
      list.child(el().key(key + i).label(key + " " + i).h(20).fill(0xFFFFFFFF));
    }
    return list;
  }

  private static App<Object> listApp() {
    App<Object> app = new App<>(new Object(), s -> col().child(rows("row", 10)));
    app.resize(100, 50, 1);
    app.frame();
    return app;
  }

  @Test
  void theRowsKeepTheirHeightInsteadOfShrinkingToFit() {
    App<Object> app = listApp();
    assertEquals(20, find(app, "row9").height());
    assertEquals(180, find(app, "row9").y());
  }

  @Test
  void theWheelMovesTheRowsAtTheNextFrameAndStopsAtBothEnds() {
    App<Object> app = listApp();
    El list = find(app, "row");
    app.wheel(50, 25, 0, 30);
    assertEquals(0, list.scrollY(), "input changes nothing before the frame");
    assertTrue(app.frame());
    assertEquals(30, list.scrollY(), "no rebuild needed");
    assertEquals(-30, find(app, "row0").y());
    assertEquals(150, find(app, "row9").y());

    app.wheel(50, 25, 0, -100);
    assertTrue(app.frame());
    assertEquals(0, list.scrollY(), "stops at the top");
    app.wheel(50, 25, 0, -10);
    assertFalse(app.frame(), "nothing moves at the top");

    app.wheel(50, 25, 0, 10_000);
    assertTrue(app.frame());
    assertEquals(150, list.scrollY(), "stops where the last row ends");
    assertEquals(30, find(app, "row9").y());
    app.wheel(50, 25, 0, 1);
    assertFalse(app.frame(), "nothing moves at the end");
    app.wheel(50, 25, 40, 0);
    assertFalse(app.frame(), "the area does not scroll sideways");
    app.wheel(150, 25, 0, -10);
    assertFalse(app.frame(), "the pointer is outside the area");
  }

  @Test
  void theInnermostAreaScrollsFirstAndTheOuterOneTakesOverAtItsEnd() {
    // An outer area 50 tall holds the inner one, 50 tall with 150 to go, and 40 more pixels.
    App<Object> app = new App<>(
        new Object(),
        s -> col().key("outer").scroll().size(100, 50).children(rows("inner", 10), el().h(40)));
    app.resize(100, 50, 1);
    app.frame();
    El outer = find(app, "outer");
    El inner = find(app, "inner");

    app.wheel(50, 25, 0, 100);
    app.wheel(50, 25, 0, 100); // the rest beyond the inner end is not passed on
    app.frame();
    assertEquals(150, inner.scrollY());
    assertEquals(0, outer.scrollY());

    app.wheel(50, 25, 0, 30);
    app.frame();
    assertEquals(30, outer.scrollY(), "the inner area is at its end");
    assertEquals(150, inner.scrollY());
    assertEquals(-30, inner.y(), "moved up with the outer area");

    app.wheel(50, 5, 0, -20); // still over the inner area, which can move up again
    app.frame();
    assertEquals(130, inner.scrollY());
    assertEquals(30, outer.scrollY());
  }

  @Test
  void bothAxesScrollIndependently() {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .key("outer")
            .scroll()
            .size(100, 50)
            .child(el().key("wide").scrollBoth().size(100, 50).child(el().size(300, 80))));
    app.resize(100, 50, 1);
    app.frame();
    app.wheel(50, 25, 500, 10);
    app.frame();
    El wide = find(app, "wide");
    assertEquals(200, wide.scrollX());
    assertEquals(10, wide.scrollY());
    assertEquals("el #wide 0,0 100x50 scroll 200,10", line(app, "#wide"));
  }

  /** Ten tabs of 40 pixels in a row 100 wide that scrolls only sideways, as a tab bar does. */
  private static El tabs(int shown) {
    El bar = el().key("bar").scrollBoth().size(100, 20).revealRow(shown);
    bar.style().overflowY = Style.Overflow.HIDDEN;
    for (int i = 0; i < 10; i++) {
      bar.child(el().key("tab" + i).size(40, 20).shrink(0));
    }
    return col().child(bar);
  }

  @Test
  void aWheelThatTurnsOnlyUpAndDownMovesAnAreaThatScrollsOnlySideways() {
    App<Object> app = new App<>(
        new Object(),
        s -> col().key("page").scroll().size(100, 50).children(tabs(-1), el().h(100)));
    app.resize(100, 50, 1);
    app.frame();
    El bar = find(app, "bar");

    app.wheel(50, 10, 0, 30);
    app.frame();
    assertEquals(30, bar.scrollX(), "down moves it to the right");
    app.wheel(50, 10, 0, 10_000);
    app.frame();
    assertEquals(300, bar.scrollX());
    assertEquals(0, find(app, "page").scrollY());
    app.wheel(50, 10, 0, 10);
    app.frame();
    assertEquals(10, find(app, "page").scrollY(), "at its end the page around it takes over");

    app.wheel(50, 5, -50, 30); // a touchpad: sideways goes sideways, down goes down
    app.frame();
    assertEquals(250, bar.scrollX());
    assertEquals(40, find(app, "page").scrollY());
    app.wheel(50, 40, 0, -30); // below the bar
    app.frame();
    assertEquals(250, bar.scrollX());
    assertEquals(10, find(app, "page").scrollY());
  }

  @Test
  void aScrollAreaKeepsTheChildItRevealsInViewWhenTheIndexChanges() {
    int[] shown = {5};
    App<int[]> app = new App<>(shown, s -> tabs(s[0]));
    app.resize(100, 20, 1);
    app.frame();
    assertEquals(140, find(app, "bar").scrollX(), "tab 5, 200 to 240, at the right end");

    app.wheel(50, 10, -100, 0);
    app.frame();
    app.resize(100, 20, 1); // a rebuild with the same index
    app.frame();
    assertEquals(40, find(app, "bar").scrollX(), "scrolled away, and it stays away");

    shown[0] = 0;
    app.resize(100, 20, 1);
    app.frame();
    assertEquals(0, find(app, "bar").scrollX(), "tab 0, to the left, at the left end");
    shown[0] = 1;
    app.resize(100, 20, 1);
    app.frame();
    assertEquals(0, find(app, "bar").scrollX(), "already in view");
    shown[0] = 10;
    app.resize(100, 20, 1);
    app.frame();
    assertEquals(0, find(app, "bar").scrollX(), "beyond the last: none");
  }

  @Test
  void theOffsetSurvivesARebuildAndFollowsItsKeyAcrossAnInsertedSibling() {
    boolean[] banner = {false};
    App<boolean[]> app = new App<>(banner, b -> {
      El root = col().key("root").size(100, 60).onPress(() -> b[0] = !b[0]);
      if (b[0]) {
        root.child(el().key("banner").size(100, 10));
      }
      return root.child(rows("row", 10));
    });
    app.resize(100, 60, 1);
    app.frame();
    app.wheel(50, 25, 0, 40);
    app.frame();

    app.pointerDown(50, 55);
    app.pointerUp(50, 55); // below the list: toggles the banner
    assertTrue(app.frame());
    assertTrue(banner[0]);
    assertEquals(40, find(app, "row").scrollY());
    assertEquals(
        """
        el #root 0,0 100x60
          el #banner 0,0 100x10
          el #row 0,10 100x50 scroll 0,40
            el #row0 label="row 0" 0,-30 100x20
        """, app.tree().dump().lines().limit(4).map(l -> l + "\n").reduce("", String::concat));

    app.resize(100, 60, 2);
    app.frame();
    assertEquals(40, find(app, "row").scrollY(), "another rebuild keeps it too");
  }

  @Test
  void aRebuildClampsTheOffsetToTheNewContent() {
    int[] count = {10};
    App<int[]> app =
        new App<>(count, c -> col().size(100, 60).onPress(() -> c[0] = 3).child(rows("row", c[0])));
    app.resize(100, 60, 1);
    app.frame();
    app.wheel(50, 25, 0, 100);
    app.frame();
    app.pointerDown(50, 55);
    app.pointerUp(50, 55);
    app.frame();
    assertEquals(10, find(app, "row").scrollY(), "three rows reach 10 past the area");
  }

  @Test
  void anAppScrollsToTheTopOrTheEndItself() {
    List<App<Object>> self = new ArrayList<>();
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .size(100, 60)
            .child(rows("row", 10))
            .child(el().key("top")
                .h(10)
                .onPress(() -> self.getFirst().scrollTo(find(self.getFirst(), "row"), 0, 0))));
    self.add(app);
    app.resize(100, 60, 1);
    app.frame();
    El list = find(app, "row");
    app.scrollTo(list, 0, Float.POSITIVE_INFINITY);
    assertEquals(0, list.scrollY(), "the next frame shows it");
    assertTrue(app.frame());
    assertEquals(150, list.scrollY());

    app.pointerDown(50, 55);
    app.pointerUp(50, 55);
    app.frame();
    assertEquals(0, find(app, "row").scrollY(), "from a handler, in the frame that runs it");
    assertThrows(IllegalArgumentException.class, () -> app.scrollTo(list, Float.NaN, 0));
    assertThrows(IllegalArgumentException.class, () -> app.wheel(0, 0, 0, Float.NaN));
  }

  @Test
  void rowsOutsideTheAreaAreNeitherDrawnNorHitNorFound() throws IOException {
    List<String> pressed = new ArrayList<>();
    App<List<String>> app = new App<>(pressed, log -> {
      El list = col().key("list").scroll().size(100, 50);
      for (int i = 0; i < 10; i++) {
        int row = i;
        list.child(el().label("Row " + i).h(20).fill(0xFFFFFFFF).onPress(() -> log.add("" + row)));
      }
      return col()
          .size(100, 100)
          .fill(0xFF000000)
          .onPress(() -> log.add("root"))
          .child(list);
    });
    app.resize(100, 100, 1);
    app.frame();
    app.wheel(50, 25, 0, 20);
    app.frame();

    // Rows 0 and 4 to 9 lie outside: row 0 above the area, the others below it.
    Scene scene = app.scene();
    assertEquals(4, scene.count(), "the background and rows 1 to 3, row 3 cut in half");
    assertEquals(40, scene.get(3, Scene.Y));
    Pixels pixels = app.render().pixels();
    assertEquals(0xFFFFFFFF, pixels.argb(50, 49));
    assertEquals(0xFF000000, pixels.argb(50, 50), "row 3 is cut off at the bottom of the area");
    assertEquals(0xFF000000, pixels.argb(50, 70), "row 4 would be here");

    app.pointerDown(50, 70);
    app.pointerUp(50, 70);
    app.pointerDown(50, 45);
    app.pointerUp(50, 45);
    app.frame();
    assertEquals(List.of("root", "3"), pressed);

    assertEquals(
        """
        line 1: press label="Row 0"
          What matches label="Row 0" is clipped away in the last frame, for example scrolled out\
         of its scroll area, so it cannot be pressed or scrolled. Bring it into view first, for\
         example with `wheel <target> <deltaX> <deltaY>` over its scroll area.""",
        assertThrows(Script.Failure.class, () -> Script.run("press label=\"Row 0\"\n", app, out))
            .getMessage());
    assertThrows(
        Script.Failure.class, () -> Script.run("press label=\"Row 4\"\n", app, out), "below");
    Script.run("size 100 100\npress label=\"Row 3\"\nframe\n", app, out);
    assertEquals(List.of("root", "3", "3"), pressed, "the visible half of row 3 is pressed");
  }

  @Test
  void aClippedAwayPartTakesNoPress() {
    List<String> pressed = new ArrayList<>();
    App<List<String>> app = new App<>(pressed, log -> {
      El child = el().absolute(20, 20).size(60, 60).onPress(() -> log.add("child"));
      return el().size(100, 100)
          .onPress(() -> log.add("root"))
          .child(el().clip().size(40, 40).radius(20).child(child));
    });
    app.resize(100, 100, 1);
    app.frame();
    press(app, 30, 30); // inside the clip
    press(app, 70, 70); // the part of the child that sticks out
    press(app, 37, 37); // the cut-off corner of the round clip
    app.frame();
    assertEquals(List.of("child", "root", "root"), pressed);
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
  }

  private static String line(App<?> app, String key) {
    return app.tree()
        .dump()
        .lines()
        .map(String::strip)
        .filter(l -> l.startsWith("el " + key + " "))
        .findFirst()
        .orElseThrow();
  }

  static El find(App<?> app, String key) {
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

  @Test
  void whatSticksOutOfAChildThatDoesNotClipCanBeScrolledTo() {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .size(100, 100)
            .scroll()
            .child(col().h(100).child(el().h(200).shrink(0)))
            .child(col().h(0).scroll().child(el().h(500).shrink(0))));
    app.resize(100, 100, 1);
    app.frame();
    app.scrollTo(app.tree(), 0, Double.POSITIVE_INFINITY);
    app.frame();
    assertEquals(100, app.tree().scrollY(), "200 high, not what the area that clips holds");
  }
}
