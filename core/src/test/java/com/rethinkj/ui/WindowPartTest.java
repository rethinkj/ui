package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WindowPartTest {

  /**
   * A title bar 30 high: a menu button with a handler from x 0 to 40, a search field marked as
   * content to 100, empty space with an unmarked child to 180, then minimize, maximize and close,
   * 40 each, the last one round.
   */
  private static El window(Object state) {
    El bar =
        row().key("bar").wFull().h(30).windowPart(WindowPart.DRAG)
            .child(el().key("menu").size(40, 30).onPress(() -> {}))
            .child(el().key("search").size(60, 30).windowPart(WindowPart.CONTENT))
            .child(el().key("space").grow(1).h(30).child(el().key("dot").size(10, 10)))
            .child(el().key("min").size(40, 30).windowPart(WindowPart.MINIMIZE))
            .child(el().key("max").size(40, 30).windowPart(WindowPart.MAXIMIZE)
                .child(el().key("glyph").size(10, 10).absolute(15, 10)))
            .child(el().key("close").size(40, 30).radius(15).windowPart(WindowPart.CLOSE));
    return col().size(300, 200).child(bar).child(el().key("body").grow(1).onPress(() -> {}));
  }

  @Test
  void thePartUnderThePointerFollowsTheRuleOfPresses() {
    App<Object> app = new App<>(new Object(), WindowPartTest::window);
    assertEquals(WindowPart.CONTENT, app.windowPart(150, 15), "before the first frame");
    app.resize(300, 200, 1);
    app.frame();

    assertEquals(WindowPart.CONTROL, app.windowPart(20, 15), "a control in the bar takes presses");
    assertEquals(WindowPart.CONTENT, app.windowPart(70, 15), "marked as content");
    assertEquals(WindowPart.DRAG, app.windowPart(150, 15), "the bar itself");
    assertEquals(WindowPart.DRAG, app.windowPart(104, 4), "an unmarked child of the bar");
    assertEquals(WindowPart.MINIMIZE, app.windowPart(200, 15));
    assertEquals(WindowPart.MAXIMIZE, app.windowPart(240, 15));
    assertEquals(WindowPart.MAXIMIZE, app.windowPart(240, 20), "a child of the maximize part");
    assertEquals(WindowPart.CLOSE, app.windowPart(280, 15));
    assertEquals(WindowPart.DRAG, app.windowPart(262, 2), "the cut-off corner of the round close");
    assertEquals(WindowPart.CONTROL, app.windowPart(150, 100), "the body takes presses");
    assertEquals(WindowPart.CONTENT, app.windowPart(400, 15), "outside the window");
  }

  @Test
  void theTreeDumpNamesTheParts() {
    String dump = Headless.render(window(null), 300, 200, 1).tree();
    assertTrue(dump.contains("el #bar window=drag 0,0 300x30"), dump);
    assertTrue(dump.contains("el #close window=close 260,0 40x30"), dump);
    assertTrue(dump.contains("el #menu 0,0 40x30"), dump);
  }
}
