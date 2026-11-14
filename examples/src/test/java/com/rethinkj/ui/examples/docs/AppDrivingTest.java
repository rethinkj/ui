package com.rethinkj.ui.examples.docs;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import org.junit.jupiter.api.Test;

/** Drives the lamp of the app's page from Java, as a test does without a script. */
class AppDrivingTest {

  @Test
  void tabAndSpaceSwitchTheLampOn() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
    // docs:start app-from-java
    App<Lights> app = new App<>(new Lights(), Lights::view);
    app.textRendering(TextRendering.LINUX); // the same layout on every platform
    app.clipboard(Clipboard.inMemory()); // the default: a test never reads the system's
    app.resize(360, 240, 1); // logical pixels, and the scale
    app.frame(); // builds and lays out the first view

    app.keyDown(new Key("Tab", 0, false)); // input queues; frame() dispatches it in order
    app.keyUp(new Key("Tab", 0, false));
    app.keyDown(new Key("Space", 0, false));
    app.keyUp(new Key("Space", 0, false));
    app.frame();
    app.advance(1); // the lamp's colour springs on the app's clock
    app.frame();

    assertTrue(app.state().on);
    assertTrue(app.tree().dump().contains("role=switch label=\"Lamp\""));
    // docs:end app-from-java
  }
}
