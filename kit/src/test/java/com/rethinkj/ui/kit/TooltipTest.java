package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The tooltip of the workbench's help button ({@link Bench}): it shows after the pointer has
 * rested on the button for the theme's delay on the app's clock; stored images in both themes at
 * scales 1 and 2.
 */
class TooltipTest {

  @TempDir Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void showsBelowItsAnchorAfterTheDelay(String theme, int scale) throws IOException {
    Bench.assertScript("tooltip", theme, scale, out);
    String waiting = Bench.tree(out, "tooltip", theme, scale, "waiting");
    assertFalse(waiting.contains("overlay"), waiting);
    assertTrue(waiting.contains("label=\"?\""), waiting);
    String shown = Bench.tree(out, "tooltip", theme, scale, "shown");
    assertTrue(shown.contains("\noverlay bottom center gap 2 on #help role=button label=\"?\""),
        shown);
    assertTrue(shown.contains(" role=tooltip label=\"Keyboard shortcuts\" "), shown);
  }
}
