package com.rethinkj.ui;

import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import org.junit.jupiter.api.Test;

class HeadlessTest {

  @Test
  void rendersTheSwitchOff() {
    TextLibraries.assumeLoaded();
    Headless.Frame frame =
        Headless.render(Examples.lightSwitch(false), 200, 120, 1, TextRendering.LINUX);
    Golden.assertMatches("switch-off", frame.pixels(), frame.tree());
  }

  @Test
  void rendersLineSegments() {
    Headless.Frame frame = Headless.render(Examples.lines(), 240, 120, 1);
    Golden.assertMatches("lines", frame.pixels(), frame.tree());
  }
}
