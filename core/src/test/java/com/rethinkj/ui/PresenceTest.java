package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Elements that fade in when they appear and fade out when a rebuild leaves them out. */
class PresenceTest {

  @TempDir Path out;

  @Test
  void aLeavingElementFadesOutWhereItWasAndIsThenRemoved() {
    App<Lights> app = badgeApp(true);
    press(app, 88, 60); // the switch: off, and the badge leaves
    assertTrue(app.frame());
    El badge = find(app, "badge");
    assertTrue(badge.isExiting());
    assertEquals(1, badge.opacity(), "the rebuild itself fades nothing");
    assertEquals(69, find(app, "switch").x(), "the switch springs to the centre from here");

    float last = 1;
    while (find(app, "badge") != null) {
      app.advance(1.0 / 60);
      assertTrue(app.frame());
      assertTrue(app.time() < 1, "still there after a second");
      if (find(app, "badge") != null) {
        float opacity = badge.opacity();
        assertTrue(opacity <= last, "brightens from " + last + " to " + opacity);
        assertEquals(119, badge.x(), "the badge stays put");
        assertEquals(54, badge.y());
        last = opacity;
      }
    }
    assertTrue(last < 0.01, "removed at " + last);
    assertFalse(app.tree().dump().contains("#badge"));
    Pixels pixels = app.render().pixels();
    int at = (60 * pixels.width() + 125) * 4;
    assertEquals(0x10, pixels.rgba()[at] & 0xFF, "the background where the badge was");

    while (app.isMoving()) {
      app.advance(1.0 / 60);
      app.frame();
    }
    assertEquals(81, find(app, "switch").x());
    assertFalse(app.frame(), "an app at rest draws nothing");
  }

  @Test
  void theOthersTakeTheirNewPlacesAtOnceOrSpringThereWhileTheLeavingOneStaysPut() {
    boolean[] shown = {true};
    App<boolean[]> app =
        new App<>(
            shown,
            s -> {
              El root = row().size(100, 20).onPress(() -> s[0] = false);
              root.child(el().key("a").size(20, 20));
              if (s[0]) {
                root.child(
                    el().key("b")
                        .size(20, 20)
                        .motion(Spring.SNAP)
                        .child(el().key("dot").absolute(5, 5).size(10, 10)));
              }
              return root.children(
                  el().key("c").size(20, 20), el().key("d").size(20, 20).motion(Spring.SNAP));
            });
    app.resize(100, 20, 1);
    app.frame();
    press(app, 90, 10);
    app.frame();

    assertEquals(
        """
        el 0,0 100x20
          el #a 0,0 20x20
          el #b 20,0 20x20 exiting
            el #dot 25,5 10x10
          el #c 20,0 20x20
          el #d 60,0 20x20
        """,
        app.tree().dump(),
        "b with its subtree keeps its place in the drawing order; c jumps, d starts to spring");
    app.advance(0.1);
    app.frame();
    float d = find(app, "d").x();
    assertTrue(d > 40 && d < 60, "d half way, at " + d);
    assertEquals(20, find(app, "b").x());
    app.advance(1);
    app.frame();
    assertEquals(
        """
        el 0,0 100x20
          el #a 0,0 20x20
          el #c 20,0 20x20
          el #d 40,0 20x20
        """,
        app.tree().dump());
  }

  @Test
  void anElementThatComesBackWhileLeavingGoesOnFromItsOpacityAndPosition() {
    int[] step = {0};
    App<int[]> app =
        new App<>(
            step,
            s -> {
              El root = el().size(200, 20).onPress(() -> s[0]++);
              if (s[0] != 2) {
                root.child(
                    el().key("box").size(20, 20).absolute(s[0] * 50, 0).motion(Spring.SETTLE));
              }
              return root;
            });
    app.resize(200, 20, 1);
    app.frame();
    press(app, 190, 10); // the box starts towards 50
    app.frame();
    app.advance(0.05);
    app.frame();
    press(app, 190, 10); // and leaves on its way
    app.frame();
    app.advance(0.1);
    app.frame();
    El leaving = find(app, "box");
    float opacity = leaving.opacity();
    float x = leaving.x();
    assertTrue(leaving.isExiting());
    assertTrue(opacity > 0.1 && opacity < 0.9, "half faded at " + opacity);
    assertTrue(x > 0 && x < 50, "goes on towards 50, at " + x);

    press(app, 190, 10); // back, now towards 150
    app.frame();
    El back = find(app, "box");
    assertNotSame(leaving, back);
    assertFalse(back.isExiting());
    assertEquals(opacity, back.opacity());
    assertEquals(x, back.x());
    app.advance(0.001);
    app.frame();
    assertEquals(opacity, back.opacity(), 0.01);
    assertEquals(x, back.x(), 1);

    app.advance(1);
    app.frame();
    assertEquals(1, back.opacity());
    assertEquals(150, back.x());
    assertFalse(app.isMoving());
  }

  @Test
  void anElementThatAppearsAfterTheFirstFrameFadesInWhereLayoutPutsIt() {
    App<Lights> app = badgeApp(false);
    press(app, 100, 60);
    app.frame();
    El badge = find(app, "badge");
    assertEquals(0, badge.opacity());
    assertEquals(119, badge.x(), "the rectangle does not animate");
    assertEquals(3, app.scene().count(), "drawn: the root, the switch and the knob");

    float last = 0;
    while (app.isMoving()) {
      app.advance(1.0 / 60);
      app.frame();
      assertTrue(badge.opacity() >= last, "fades from " + last + " to " + badge.opacity());
      assertEquals(119, badge.x());
      last = badge.opacity();
    }
    assertEquals(1, last);
  }

  @Test
  void onTheFirstFrameNothingFadesIn() {
    App<Lights> app = badgeApp(true);
    assertEquals(1, find(app, "badge").opacity());
    assertFalse(app.isMoving());
    assertFalse(app.frame());
  }

  @Test
  void whatAppearsWithItsParentFadesInWithIt() {
    boolean[] shown = {false};
    App<boolean[]> app =
        new App<>(
            shown,
            s -> {
              El root = el().size(100, 100).onPress(() -> s[0] = true);
              if (s[0]) {
                root.child(
                    el().key("card")
                        .size(50, 50)
                        .motion(Spring.SNAP)
                        .child(el().key("title").size(20, 20).motion(Spring.SNAP)));
              }
              return root;
            });
    app.resize(100, 100, 1);
    app.frame();
    press(app, 90, 90);
    app.frame();
    app.advance(0.05);
    app.frame();
    float card = find(app, "card").opacity();
    assertTrue(card > 0 && card < 1, "the card fades in, at " + card);
    assertEquals(1, find(app, "title").opacity(), "the title only through the card");
  }

  @Test
  void opacityNeverOvershoots() {
    boolean[] shown = {false};
    App<boolean[]> app =
        new App<>(
            shown,
            s -> {
              El root = el().size(100, 100).onPress(() -> s[0] = !s[0]);
              if (s[0]) {
                root.child(el().key("pop").size(20, 20).motion(new Spring(0.3, 0.6)));
              }
              return root;
            });
    app.resize(100, 100, 1);
    app.frame();
    press(app, 90, 90);
    app.frame();
    El pop = find(app, "pop");
    boolean reachedOne = false;
    while (app.isMoving()) {
      app.advance(1.0 / 120);
      app.frame();
      assertTrue(pop.opacity() <= 1, "opacity " + pop.opacity());
      reachedOne |= pop.opacity() == 1;
    }
    assertTrue(reachedOne);
    press(app, 90, 90);
    app.frame();
    while (find(app, "pop") != null) {
      assertTrue(pop.opacity() >= 0, "opacity " + pop.opacity());
      app.advance(1.0 / 120);
      app.frame();
    }
  }

  @Test
  void anElementWithoutKeyOrMotionDisappearsAtOnce() {
    boolean[] shown = {true};
    App<boolean[]> app =
        new App<>(
            shown,
            s -> {
              El root = row().size(100, 20).onPress(() -> s[0] = false);
              if (s[0]) {
                root.children(
                    el().size(20, 20).motion(Spring.SNAP), el().key("k").size(20, 20));
              }
              return root;
            });
    app.resize(100, 20, 1);
    app.frame();
    press(app, 90, 10);
    assertTrue(app.frame());
    assertEquals("el 0,0 100x20\n", app.tree().dump());
    assertFalse(app.isMoving());
  }

  @Test
  void neitherALeavingNorAnInvisibleElementIsHit() throws IOException {
    List<String> log = new ArrayList<>();
    boolean[] shown = {true};
    App<List<String>> app =
        new App<>(
            log,
            l -> {
              El root =
                  el().size(100, 100)
                      .onPress(() -> l.add("root"))
                      .child(
                          el().key("ghost")
                              .absolute(60, 0)
                              .size(40, 40)
                              .opacity(0)
                              .onPress(() -> l.add("ghost")));
              if (shown[0]) {
                root.child(
                    el().key("box")
                        .absolute(0, 0)
                        .size(40, 40)
                        .motion(Spring.SNAP)
                        .onPress(() -> {
                          l.add("box");
                          shown[0] = false;
                        }));
              }
              return root;
            });
    app.resize(100, 100, 1);
    app.frame();
    press(app, 20, 20);
    app.frame();
    assertTrue(find(app, "box").isExiting());
    press(app, 20, 20);
    press(app, 80, 20);
    app.frame();
    assertEquals(List.of("box", "root", "root"), log);

    for (String target : List.of("#box", "#ghost")) {
      String message =
          assertThrows(
                  Script.Failure.class,
                  () -> Script.run("size 100 100\npress " + target + "\n", app, out))
              .getMessage();
      assertTrue(message.contains("No element of the last frame matches " + target), message);
    }
  }

  @Test
  void theStoredSequenceOfTheBadgeLeavingAndComingBack() throws IOException {
    String script =
        """
        size 200 120 scale 2
        frame
        snap on
        press #switch   # off: the badge leaves
        frame
        advance 0.06
        frame
        snap leaving-060ms
        advance 0.06
        frame
        snap leaving-120ms
        press #switch   # on again before the badge is gone
        frame
        advance 0.06
        frame
        snap back-060ms
        advance 1
        frame
        snap back
        press #switch
        frame
        advance 1
        frame
        snap gone
        """;
    Script.run(script, badgeApp(true), out);
    for (String[] snap :
        new String[][] {
          {"on", "badge-on"},
          {"leaving-060ms", "badge-leaving-060ms"},
          {"leaving-120ms", "badge-leaving-120ms"},
          {"back-060ms", "badge-back-060ms"},
          {"back", "badge-on"},
          {"gone", "badge-gone"},
        }) {
      Golden.assertMatches(
          snap[1],
          Png.decode(Files.readAllBytes(out.resolve(snap[0] + ".png"))),
          Files.readString(out.resolve(snap[0] + ".tree.txt")));
    }
  }

  /** The badge example after its first frame, 200 by 120 pixels. */
  private static App<Lights> badgeApp(boolean on) {
    Lights lights = new Lights();
    lights.on = on;
    App<Lights> app = new App<>(lights, Examples::badged);
    app.resize(200, 120, 1);
    app.frame();
    return app;
  }

  /** The element of the last frame with this key, or null. */
  private static El find(App<?> app, String key) {
    List<El> todo = new ArrayList<>(List.of(app.tree()));
    while (!todo.isEmpty()) {
      El el = todo.removeLast();
      if (key.equals(el.key())) {
        return el;
      }
      todo.addAll(el.children());
    }
    return null;
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
  }
}
