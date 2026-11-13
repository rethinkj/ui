package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The tooltip of the workbench's help button ({@link Bench}): it shows after the pointer has
 * rested on the button, or the keyboard has focused it, for the theme's delay on the app's clock;
 * stored images in both themes at scales 1 and 2.
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

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void showsWhenTheKeyboardFocusedItsAnchorAndGoesWithAKey(String theme, int scale)
      throws IOException {
    App<Bench> app = Bench.assertScript("keytip", theme, scale, out);
    String shown = Bench.tree(out, "keytip", theme, scale, "shown");
    assertTrue(shown.contains("\noverlay bottom center gap 2 on #help role=button label=\"?\""),
        shown);
    assertTrue(shown.contains(" label=\"?\" ") && shown.contains(" focused\n"), shown);
    String size = "size 360 240 scale " + scale + "\n";
    Script.run(size + "key A\nframe\n", app, out);
    assertFalse(app.tree().dump().contains("role=tooltip"), "a key hid it");
    Script.run(size + "advance 1\nframe\nkey Tab shift\nkey Tab\nframe\nadvance 0.5\nframe\n",
        app, out);
    assertTrue(app.tree().dump().contains("role=tooltip"), "back with the focus");
    Script.run(size + "key Tab\nframe\n", app, out);
    assertFalse(app.tree().dump().contains("role=tooltip"), "the focus left");
  }
}
