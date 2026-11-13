package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.motion.Spring;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MotionTest {

  @Test
  void theKnobSlidesWithoutJumpsAndStopsOnItsTarget() {
    App<Lights> app = switchApp(1);
    press(app, 90, 60);
    assertTrue(app.frame());
    assertEquals(2, knob(app).x(), "the rebuild itself moves nothing");
    assertTrue(app.isMoving());

    float last = 2;
    for (int frame = 1; app.isMoving(); frame++) {
      assertTrue(frame < 60, "still moving after a second");
      app.advance(1.0 / 60);
      assertTrue(app.frame());
      float x = knob(app).x();
      assertTrue(x >= last, "moves back at frame " + frame + ": " + last + " to " + x);
      assertTrue(x - last < 3, "jumps at frame " + frame + ": " + last + " to " + x);
      last = x;
    }
    assertEquals(18, last);
    assertEquals(0xFF3B82F6, app.tree().children().getFirst().fill());
    assertFalse(app.frame(), "an app at rest draws nothing");
    app.advance(1);
    assertFalse(app.frame());
  }

  @Test
  void aSecondPressMidMovementKeepsPositionAndVelocity() {
    App<Lights> app = switchApp(1);
    press(app, 90, 60);
    app.frame();
    double h = 1e-4;
    app.advance(0.1 - h);
    app.frame();
    float before = knob(app).x();
    app.advance(h);
    app.frame();
    float x = knob(app).x();

    press(app, 90, 60);
    assertTrue(app.frame());
    assertFalse(app.state().on);
    assertEquals(x, knob(app).x(), "position after the second press");
    app.advance(h);
    app.frame();
    double velocityBefore = (x - before) / h, velocityAfter = (knob(app).x() - x) / h;
    assertTrue(velocityBefore > 50, "moving right at " + velocityBefore + " px/s");
    assertEquals(velocityBefore, velocityAfter, velocityBefore * 0.02, "velocity in px/s");

    while (app.isMoving()) {
      app.advance(1.0 / 60);
      app.frame();
    }
    assertEquals(2, knob(app).x());
  }

  @Test
  void withoutMotionAChangeIsImmediateAndANewElementDoesNotMove() {
    Lights state = new Lights();
    App<Lights> app =
        new App<>(
            state,
            s -> {
              El root =
                  el().size(200, 100)
                      .onPress(() -> s.on = true)
                      .child(el().size(20, 20).absolute(s.on ? 100 : 0, 0).fill(s.on ? 2 : 1));
              if (s.on) {
                root.child(el().size(20, 20).absolute(50, 50).fill(3).motion(Spring.DRIFT));
              }
              return root;
            });
    app.resize(200, 100, 1);
    app.frame();
    press(app, 190, 90);
    assertTrue(app.frame());

    List<El> children = app.tree().children();
    assertEquals(100, children.get(0).x());
    assertEquals(2, children.get(0).fill());
    assertEquals(50, children.get(1).x());
    assertEquals(3, children.get(1).fill());
    assertEquals(0, children.get(1).opacity(), "it only fades in");
    app.advance(2);
    app.frame();
    assertEquals(50, children.get(1).x());
    assertEquals(1, children.get(1).opacity());
    assertFalse(app.isMoving());
    assertFalse(app.frame());
  }

  @Test
  void aKeyedElementKeepsItsIdentityWhenASiblingIsInsertedBeforeIt() {
    List<String> names = new ArrayList<>(List.of("a", "b"));
    App<List<String>> app =
        new App<>(
            names,
            list -> {
              El root = row().size(100, 20).onPress(() -> list.addFirst("c"));
              for (String name : list) {
                root.child(el().key(name).size(20, 20).motion(Spring.SNAP));
              }
              return root;
            });
    app.resize(100, 20, 1);
    app.frame();
    press(app, 90, 10);
    app.frame();

    assertEquals("0,0 0,0 20,0", positions(app), "c is new, a and b start where they were");
    assertTrue(app.isMoving());
    app.advance(1);
    app.frame();
    assertEquals("0,0 20,0 40,0", positions(app));
    assertFalse(app.isMoving());
  }

  @Test
  void hitTestingUsesThePositionOnScreen() {
    List<String> log = new ArrayList<>();
    boolean[] right = {false};
    App<List<String>> app =
        new App<>(
            log,
            l ->
                el().size(200, 100)
                    .onPress(() -> {
                      l.add("root");
                      right[0] = true;
                    })
                    .child(
                        el().size(20, 20)
                            .absolute(right[0] ? 160 : 0, 0)
                            .motion(Spring.SETTLE)
                            .onPress(() -> l.add("box"))));
    app.resize(200, 100, 1);
    app.frame();
    press(app, 100, 80);
    app.frame();
    app.advance(0.1);
    app.frame();

    float x = app.tree().children().getFirst().x();
    assertTrue(x > 20 && x < 140, "half way, at " + x);
    press(app, x + 10, 10); // where the box is now
    press(app, 170, 10); // where it is going
    app.frame();
    assertEquals(List.of("root", "box", "root"), log);
  }

  @Test
  void siblingsMustNotShareAKey() {
    App<Object> app =
        new App<>(new Object(), s -> el().children(el().key("twice"), el().key("twice")));
    app.resize(10, 10, 1);
    assertThrows(IllegalStateException.class, app::frame);
  }

  @Test
  void theSwitchInFiveFrames() {
    App<Lights> app = switchApp(2);
    press(app, 90, 60);
    app.frame();
    for (int i = 1; i <= 5; i++) {
      app.advance(0.04);
      app.frame();
      String name = "switch-moving-" + (i < 3 ? "0" : "") + 40 * i + "ms";
      Golden.assertMatches(name, app.render().pixels(), app.render().tree());
    }
  }

  private static App<Lights> switchApp(float scale) {
    App<Lights> app = new App<>(new Lights(), Examples::lights);
    app.resize(200, 120, scale);
    app.frame();
    return app;
  }

  private static El knob(App<?> app) {
    return app.tree().children().getFirst().children().getFirst();
  }

  private static String positions(App<?> app) {
    StringBuilder out = new StringBuilder();
    for (El child : app.tree().children()) {
      out.append(out.isEmpty() ? "" : " ").append((int) child.x()).append(',').append((int) child.y());
    }
    return out.toString();
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
  }
}
