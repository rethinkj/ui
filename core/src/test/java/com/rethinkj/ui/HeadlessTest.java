package com.rethinkj.ui;

import org.junit.jupiter.api.Test;

class HeadlessTest {

  @Test
  void rendersTheSwitchOn() {
    Headless.Frame frame = Headless.render(Examples.lightSwitch(true), 200, 120, 2);
    // The image only: in the stored tree, the press that turned the switch on has focused it.
    Golden.assertMatches("switch-on", frame.pixels(), null);
  }

  @Test
  void rendersTheSwitchOff() {
    Headless.Frame frame = Headless.render(Examples.lightSwitch(false), 200, 120, 1);
    Golden.assertMatches("switch-off", frame.pixels(), frame.tree());
  }
}
