package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The rendering a system gets unless an app chooses one, and the property that names it. */
class TextRenderingTest {

  @Test
  void aSystemPropertyNamesThePlatformsRendering() {
    String before = System.getProperty(TextRendering.PROPERTY);
    try {
      System.setProperty(TextRendering.PROPERTY, "windows");
      assertSame(TextRendering.WINDOWS, TextRendering.platform());
      System.setProperty(TextRendering.PROPERTY, "linux");
      assertSame(TextRendering.LINUX, TextRendering.platform());
      System.setProperty(TextRendering.PROPERTY, "mac");
      assertThrows(IllegalArgumentException.class, TextRendering::platform);
      System.clearProperty(TextRendering.PROPERTY);
      boolean windows = System.getProperty("os.name", "").startsWith("Windows");
      assertSame(windows ? TextRendering.WINDOWS : TextRendering.LINUX, TextRendering.platform());
    } finally {
      if (before == null) {
        System.clearProperty(TextRendering.PROPERTY);
      } else {
        System.setProperty(TextRendering.PROPERTY, before);
      }
    }
  }

  @Test
  void theBuildsTestsDrawAsOnLinuxOnEverySystem() {
    // The POMs set the property, so a stored image needs no test to pin the rendering.
    assertSame(TextRendering.LINUX, TextRendering.platform());
  }
}
