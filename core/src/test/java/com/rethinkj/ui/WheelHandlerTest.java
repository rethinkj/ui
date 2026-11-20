package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link El#onWheel}: what it is told, when it is asked, and what scrolls when it declines. */
class WheelHandlerTest {

  @TempDir
  Path out;

  private final List<String> calls = new ArrayList<>();

  /** Takes the wheel with Control held and lets it scroll without. */
  private boolean zoom(double deltaX, double deltaY, int modifiers, double x, double y) {
    calls.add(deltaX + " " + deltaY + " " + modifiers + " at " + x + "," + y);
    return (modifiers & Key.CONTROL) != 0;
  }

  @Test
  void aHandlerTakesTheWheelWithItsModifiersAndOneThatDeclinesLetsItScroll() throws IOException {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .key("area")
            .scroll()
            .size(100, 50)
            .child(el().key("canvas").h(200).onWheel(this::zoom)));
    Script.run("""
        size 100 50
        frame
        wheel 50,20 0 30 control
        frame
        """, app, out);
    assertEquals(List.of("0.0 30.0 " + Key.CONTROL + " at 50.0,20.0"), calls);
    assertEquals(0, ScrollTest.find(app, "area").scrollY(), "taken: nothing scrolls");

    Script.run("wheel 50,20 0 30 shift\nframe\nwheel 50,20 0 -10 control alt\nframe\n", app, out);
    assertEquals(30, ScrollTest.find(app, "area").scrollY(), "declined: it scrolls as before");
    assertEquals("0.0 30.0 " + Key.SHIFT + " at 50.0,20.0", calls.get(1), "asked first");
    assertEquals(
        "0.0 -10.0 " + (Key.CONTROL | Key.ALT) + " at 50.0,50.0",
        calls.getLast(),
        "the position in the canvas, which scrolled up by 30");
  }

  @Test
  void aScrollAreaInsideTheElementThatCanMoveTakesTheWheelFirst() {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .size(100, 50)
            .onWheel(this::zoom)
            .child(col().key("inner").scroll().h(50).child(el().h(80))));
    app.resize(100, 50, 1);
    app.frame();
    app.wheel(50, 25, 0, 100, Key.CONTROL);
    app.frame();
    assertEquals(30, ScrollTest.find(app, "inner").scrollY());
    assertEquals(List.of(), calls, "the handler is not asked while the area inside can move");

    app.wheel(50, 25, 0, 10, Key.CONTROL);
    app.frame();
    assertEquals(1, calls.size(), "at its end, the handler around it is");
  }
}
