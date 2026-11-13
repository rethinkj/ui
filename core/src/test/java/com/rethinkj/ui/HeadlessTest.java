package com.rethinkj.ui;

import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import org.junit.jupiter.api.Test;

class HeadlessTest {

  @Test
  void rendersTheSwitchOn() {
    TextLibraries.assumeLoaded();
    Headless.Frame frame =
        Headless.render(Examples.lightSwitch(true), 200, 120, 2, TextRendering.LINUX);
    // The image only: in the stored tree, the press that turned the switch on has focused it.
    Golden.assertMatches("switch-on", frame.pixels(), null);
  }

  @Test
  void rendersTheSwitchOff() {
    TextLibraries.assumeLoaded();
    Headless.Frame frame =
        Headless.render(Examples.lightSwitch(false), 200, 120, 1, TextRendering.LINUX);
    Golden.assertMatches("switch-off", frame.pixels(), frame.tree());
  }
}
