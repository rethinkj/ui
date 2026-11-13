package com.rethinkj.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples.Lights;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Enter and Space on a focused element with a press handler show its {@link El#whenPressed} look:
 * while the key is held, and at least {@link App#KEY_PRESS} after it went down when it comes up in
 * a later frame, but not when it goes down and up in one frame, as a script's {@code key} step.
 */
class KeyPressTest {

  private static final Key SPACE = new Key("Space", 0, false);

  @TempDir
  Path out;

  private static App<Lights> focused() {
    App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
    app.resize(200, 120, 1);
    app.frame();
    app.keyDown(new Key("Tab", 0, false));
    app.frame();
    assertEquals("switch", app.focused().key());
    return app;
  }

  private static boolean pressed(App<?> app) {
    return app.pressed() != null && "switch".equals(app.pressed().key());
  }

  @Test
  void aKeyHeldShowsThePressedLookUntilItIsUp() {
    App<Lights> app = focused();
    app.keyDown(SPACE);
    assertTrue(app.frame());
    assertTrue(app.state().on, "the press ran as the key went down");
    assertTrue(pressed(app));
    app.advance(1); // held longer than KEY_PRESS: the look stays while the key is down
    app.frame();
    assertTrue(pressed(app));
    assertEquals(0xFF2563EB, app.focused().fill(), "the pressed look of the switch that is on");
    app.keyUp(SPACE);
    app.frame();
    assertFalse(pressed(app));
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());
  }

  @Test
  void aShortPressShowsForAMomentOnTheClock() {
    App<Lights> app = focused();
    app.keyDown(SPACE);
    app.frame();
    app.advance(0.02);
    app.keyUp(SPACE);
    app.frame();
    assertTrue(pressed(app), "up again, but not yet KEY_PRESS after it went down");
    assertEquals(app.time() - 0.02 + App.KEY_PRESS, app.nextChange(), 1e-9);
    app.advance(App.KEY_PRESS);
    assertTrue(app.frame(), "the look ends, which draws a frame");
    assertFalse(pressed(app));
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange(), "then the window is idle");
    app.advance(1); // the switch's colour springs back
    app.frame();
    assertFalse(app.frame());
  }

  @Test
  void aKeyDownAndUpInOneFrameShowsNothing() {
    App<Lights> app = focused();
    app.keyDown(SPACE);
    app.keyUp(SPACE);
    app.frame();
    assertTrue(app.state().on);
    assertFalse(pressed(app));
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());
  }

  @Test
  void aWindowThatGoesInactiveEndsTheLook() {
    App<Lights> app = focused();
    app.keyDown(SPACE);
    app.frame();
    app.windowActive(false);
    app.frame();
    assertFalse(pressed(app));
    assertEquals(Double.POSITIVE_INFINITY, app.nextChange());
  }

  @Test
  void aScriptHoldsAKeyWithDownAndUp() throws IOException {
    App<Lights> app = new App<>(new Lights(), Examples::switchAlone);
    Script.run("""
        size 200 120
        frame
        key Tab
        frame
        key Space down
        frame
        """, app, out);
    assertTrue(pressed(app));
    Script.run("key Space up\nadvance 1\nframe", app, out);
    assertFalse(pressed(app));
    assertNull(app.pressed());
  }

  @Test
  void thePressedLookOfAKeyEndsWhenItsElementLeavesAndOneWithItsIdentityComesBack() {
    App<int[]> app = new App<>(new int[1], s -> {
      El root = El.el().size(100, 100);
      if (s[0] != 1) {
        root.child(El.el()
            .key("button")
            .size(40, 40)
            .focusable()
            .autofocus(s[0] == 0 ? Boolean.TRUE : null)
            .onPress(() -> {})
            .whenPressed(e -> e.fill(0xFF0000FF)));
      }
      return root;
    });
    app.resize(100, 100, 1);
    app.frame();
    app.keyDown(SPACE); // held: no key up
    app.frame();
    assertEquals("button", app.pressed().key());
    app.post(() -> app.state()[0] = 1);
    app.frame();
    assertNull(app.pressed());
    app.post(() -> app.state()[0] = 2);
    app.frame();
    assertNull(app.focused());
    assertNull(app.pressed(), "the new button takes over neither the press nor its look");
  }
}
