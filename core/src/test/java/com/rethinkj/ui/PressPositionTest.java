package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A press handler is told where the pointer was released, in the element's content. */
class PressPositionTest {

  @TempDir
  Path out;

  /** A track of 200 by 20 at 30, 40 in a scroll area at 20, 20 scrolled by 15, and a box in it. */
  private static App<List<String>> app() {
    return new App<>(new ArrayList<>(), log -> el().wFull().hFull().child(
        col().key("area").absolute(20, 20).size(240, 100).scroll().children(
            el().h(20).shrink(0),
            el().key("track").role("slider").focusable().size(200, 20).shrink(0)
                .margin(0, 0, 0, 10)
                .onPress((clicks, modifiers, x, y) -> log.add("press " + clicks + " " + modifiers
                    + " at " + x + "," + y))
                .onPointerDown((clicks, modifiers, x, y) -> log.add("down at " + x + "," + y))
                .child(el().key("knob").size(10, 10)),
            el().h(200).shrink(0))));
  }

  @Test
  void aPressIsToldWhereItWasReleasedInTheElementsContent() {
    App<List<String>> app = app();
    app.resize(300, 200, 1);
    app.frame();
    app.wheel(100, 60, 0, 15);
    app.frame();
    // The track's border box is at 30, 25 in the window now.
    app.pointerDown(50, 30, 1, Key.SHIFT);
    app.pointerUp(52, 33); // on the knob, which has no handler: the track's press
    app.frame();
    assertEquals(List.of("down at 20.0,5.0", "press 1 1 at 22.0,8.0"), app.state());
  }

  @Test
  void enterAndSpaceGiveTheMiddle() {
    App<List<String>> app = app();
    app.resize(300, 200, 1);
    app.frame();
    app.keyDown(new Key("Tab", 0, false));
    app.keyDown(new Key("Enter", 0, false));
    app.frame();
    assertEquals(List.of("press 1 0 at 100.0,10.0"), app.state());
  }

  @Test
  void aScriptPressesAtAPositionOrTheMiddleOfATarget() throws Exception {
    App<List<String>> app = app();
    Script.run("""
        size 300 200
        frame
        press 130,50
        press #track clicks 2
        frame
        wheel #area 0 15
        frame
        press 40,40
        frame
        """, app, out);
    assertEquals(List.of("down at 100.0,10.0", "press 1 0 at 100.0,10.0", "down at 100.0,10.0",
        "press 2 0 at 100.0,10.0", "down at 10.0,15.0", "press 1 0 at 10.0,15.0"), app.state());
  }
}
