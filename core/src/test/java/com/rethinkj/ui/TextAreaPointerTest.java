package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.TextLibraries;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pointer in a multi-line field: presses, drags across lines and beyond its four edges,
 * presses of two, three and four, Shift, the wheel and the bars. The field of {@link Areas} has
 * its content from 14,14, rows 18 high and characters 8 wide.
 */
class TextAreaPointerTest {

  private static final Scrollbars BARS =
      new Scrollbars(6, 2, 3, 24, 0xFF83868F, 0xFFA1A3AA, 0xFFB9BBC1);

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  /** The middle of character {@code column} of row {@code row} in view. */
  private static float x(int column) {
    return 14 + 8 * column + 2;
  }

  private static float y(int row) {
    return 14 + 18 * row + 9;
  }

  private static void press(App<?> app, float x, float y, int clicks, int modifiers) {
    app.pointerDown(x, y, clicks, modifiers);
    app.pointerUp(x, y);
    app.frame();
  }

  private static String lines(int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append("line ").append(i).append('\n');
    }
    return text.append("end").toString();
  }

  @Test
  void aPressPlacesTheCaretOnTheRowUnderIt() {
    App<Model> app = Areas.start("one\ntwo\nthree");
    press(app, x(2), y(1), 1, 0);
    assertEquals("f", app.focused().key());
    assertEquals("one\ntw|o\nthree", show(app));
    press(app, x(20), y(2), 1, 0);
    assertEquals("one\ntwo\nthree|", show(app), "right of a line: its end");
    press(app, x(1), 100, 1, 0);
    assertEquals("one\ntwo\nt|hree", show(app), "below the last line: on it");
  }

  @Test
  void aDragSelectsAcrossLines() {
    App<Model> app = Areas.start("one\ntwo\nthree");
    app.pointerDown(x(1), y(0), 1, 0);
    app.pointerMove(x(3), y(2));
    app.frame();
    assertEquals("o^ne\ntwo\nthr|ee", show(app));
    assertEquals(3, field(app).text().selection().size(), "a rectangle per row: "
        + field(app).text().selection());
    app.pointerMove(x(0), y(1));
    app.pointerUp(x(0), y(1));
    app.frame();
    assertEquals("o^ne\n|two\nthree", show(app));
    Text.Box box = field(app).text().selection().getFirst();
    assertEquals(1, field(app).text().selection().size(), "nothing of the second line");
    assertEquals(192, box.x() + box.width(), "the line feed: to the right edge");
  }

  @Test
  void aDragBeyondTheEdgesScrollsAllFourWays() {
    String text = lines(40).replace("line 3\n", "line 3 " + "x".repeat(100) + "\n");
    App<Model> app = Areas.start(text);
    app.pointerDown(x(1), y(0), 1, 0);
    app.frame();
    El f = field(app);
    for (int i = 0; i < 4; i++) {
      app.pointerMove(x(1), 160 + i); // below the field, which ends at 108
      app.frame();
    }
    f = field(app);
    assertTrue(f.scrollY() > 50, "down: " + f.scrollY());
    double down = f.scrollY();
    app.pointerMove(x(1), 200);
    app.frame();
    assertTrue(field(app).scrollY() > down, "further below goes faster: " + field(app).scrollY());
    for (int i = 0; i < 20; i++) {
      app.pointerMove(x(1), -20 - i); // above it
      app.frame();
    }
    assertEquals(0, field(app).scrollY(), "up to the top");
    app.pointerMove(x(1), y(3));
    app.frame();
    for (int i = 0; i < 10; i++) {
      app.pointerMove(300 + i, y(3)); // right of it
      app.frame();
    }
    assertTrue(field(app).scrollX() > 100, "sideways: " + field(app).scrollX());
    for (int i = 0; i < 40; i++) {
      app.pointerMove(-10 - i, y(3)); // left of it
      app.frame();
    }
    assertEquals(0, field(app).scrollX(), "back to the left edge");
    app.pointerUp(-50, y(3));
    app.frame();
    assertEquals("l^ine 0\nline 1\nline 2\n|line 3 x", show(app).substring(0, 31));
  }

  @Test
  void twoThreeAndFourPressesSelectAWordALineAndEverything() {
    App<Model> app = Areas.start("SELECT name\nFROM orders\nWHERE 1");
    press(app, x(7), y(1), 1, 0);
    press(app, x(7), y(1), 2, 0);
    assertEquals("SELECT name\nFROM ^orders|\nWHERE 1", show(app));
    press(app, x(7), y(1), 3, 0);
    assertEquals("SELECT name\n^FROM orders\n|WHERE 1", show(app), "the line with its line feed");
    press(app, x(7), y(1), 4, 0);
    assertEquals("^SELECT name\nFROM orders\nWHERE 1|", show(app));
    press(app, x(2), y(2), 3, 0);
    assertEquals("SELECT name\nFROM orders\n^WHERE 1|", show(app), "the last line has none");
    app.pointerDown(x(2), y(0), 3, 0);
    app.pointerMove(x(2), y(1));
    app.pointerUp(x(2), y(1));
    app.frame();
    assertEquals("^SELECT name\nFROM orders\n|WHERE 1", show(app), "a drag goes by lines");
  }

  @Test
  void shiftWithAPressExtendsTheSelection() {
    App<Model> app = Areas.start("one\ntwo\nthree");
    press(app, x(1), y(0), 1, 0);
    press(app, x(2), y(2), 1, Key.SHIFT);
    assertEquals("o^ne\ntwo\nth|ree", show(app));
  }

  @Test
  void theWheelScrollsTheFieldWhileItCanAndThenWhatIsAroundIt() {
    App<Model> app = Areas.start(lines(20), UnaryOperator.identity(), 1,
        root -> root.scroll().h(100));
    app.wheel(x(1), y(1), 0, 100);
    app.frame();
    assertEquals(100, field(app).scrollY());
    assertEquals(0, app.tree().scrollY());
    app.wheel(x(1), y(1), 0, 1000);
    app.frame();
    assertEquals(21 * 18 - 90, field(app).scrollY(), "to its end");
    app.wheel(x(1), y(1), 0, 20);
    app.frame();
    assertEquals(20, app.tree().scrollY(), "then the root, which can scroll");
    assertTrue(Areas.line(app).contains(" lines 17-21/21 scroll 0,288"), Areas.line(app));
  }

  @Test
  void theBarsMoveTheFieldAsAnyScrollArea() {
    App<Model> app = Areas.start(lines(40).replace("line 3", "line 3" + "y".repeat(60)),
        f -> f.scrollbars(BARS), 1);
    El f = field(app);
    Scrollbars.Bar bar = f.verticalBar();
    assertNotNull(bar);
    assertNotNull(f.horizontalBar(), "a line wider than the field");
    float thumbX = bar.thumbX() + 3;
    app.pointerDown(thumbX, bar.thumbY() + 2, 1, 0);
    app.pointerMove(thumbX, 400);
    app.pointerUp(thumbX, 400);
    app.frame();
    assertEquals(41 * 18 - 90, field(app).scrollY(), "the end");
    assertNull(app.focused(), "a bar takes no focus");
    assertTrue(Areas.line(app).contains(" lines 37-41/41 "), Areas.line(app));
    bar = field(app).verticalBar();
    press(app, bar.thumbX() + 3, bar.trackY() + 2, 1, 0);
    assertEquals(41 * 18 - 90 - 98, field(app).scrollY(), "a page up, the field's height");
    focus(app);
    key(app, "ArrowRight");
    assertEquals(0, field(app).scrollY(), "the next key shows the caret again");
  }
}
