package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppTest {

  @Test
  void aPressOnTheSwitchChangesStateTreeAndImage() {
    TextLibraries.assumeLoaded();
    App<Lights> app = new App<>(new Lights(), Examples::lights);
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 120, 1);
    assertTrue(app.frame());
    Golden.assertMatches("switch-off", app.render().pixels(), app.render().tree());

    // The knob has no handler of its own, so the press goes to the switch around it.
    app.pointerDown(50, 60);
    app.pointerUp(50, 60);
    assertFalse(app.state().on, "handlers run in frame(), not while input arrives");
    assertTrue(app.frame());

    assertTrue(app.state().on);
    app.advance(1); // the knob and the colour spring to their new values
    app.resize(200, 120, 2);
    app.frame();
    Golden.assertMatches("switch-on", app.render().pixels(), app.render().tree());
  }

  @Test
  void nothingHappensWithoutInput() {
    App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
    app.resize(200, 120, 1);
    assertTrue(app.frame());
    assertFalse(app.frame());

    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    assertFalse(app.frame(), "a press beside the switch reaches no handler");
  }

  @Test
  void aPressCountsOnlyWhenItEndsWhereItBegan() {
    App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
    app.resize(200, 120, 1);
    app.frame();

    app.pointerDown(90, 60);
    app.pointerUp(10, 10);
    app.pointerDown(10, 10);
    app.pointerUp(90, 60);

    assertFalse(app.frame());
    assertFalse(app.state().on);
  }

  @Test
  void theElementOnTopGetsThePressAndRoundedCornersAreNotPartOfIt() {
    List<String> pressed = new ArrayList<>();
    App<List<String>> app =
        new App<>(
            pressed,
            log ->
                el().size(100, 100)
                    .onPress(() -> log.add("root"))
                    .child(el().size(60, 60).absolute(0, 0).onPress(() -> log.add("below")))
                    .child(
                        el().size(60, 60).radius(30).absolute(20, 20)
                            .onPress(() -> log.add("round"))
                            .child(el().size(10, 10).absolute(25, 25))));
    app.resize(100, 100, 1);
    app.frame();

    press(app, 50, 50); // on the small child, which has no handler: goes to "round"
    press(app, 30, 30); // inside both, "round" is on top
    press(app, 22, 22); // in the cut-off corner of "round": "below" shows through
    press(app, 90, 90); // only the root
    app.frame();

    assertEquals(List.of("round", "round", "below", "root"), pressed);
  }

  @Test
  void aPressHandlerIsToldHowManyPressesItCompletes() {
    List<String> pressed = new ArrayList<>();
    App<List<String>> app = new App<>(pressed, log -> el().size(100, 100).focusable()
        .onPress(clicks -> log.add("clicks " + clicks)));
    app.resize(100, 100, 1);
    app.frame();

    press(app, 50, 50);
    app.pointerDown(50, 50, 2, 0);
    app.pointerUp(50, 50);
    app.pointerDown(50, 50, 3, Key.SHIFT);
    app.pointerUp(50, 50);
    app.frame();
    app.keyDown(new Key("Enter"));
    app.frame();

    assertEquals(List.of("clicks 1", "clicks 2", "clicks 3", "clicks 1"), pressed);
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
  }
}
