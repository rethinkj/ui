package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The tooltip of the workbench's help button ({@link Bench}): it shows after the pointer has
 * rested on the button, or the keyboard has focused it, for the theme's delay on the app's clock;
 * stored images.
 */
class TooltipTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  @Test
  void aConditionalHoverReadsTheLaidOutRowOnlyAfterTheDelay() throws IOException {
    Theme t = Theme.DARK;
    String whole = "a name too long for the row";
    int[] reads = {0};
    App<int[]> app = new App<>(
        reads,
        r -> El.col().wFull().hFull().rows(1, t.grid().rowHeight(), i -> {
          El text = Label.label(t, whole).ellipsis().minSize(0, 0).grow(1);
          return Tooltip.whenHovered(t, El.row().key("anchor").child(text), () -> {
            r[0]++;
            assertTrue(text.width() > 0, "the row's layout is available");
            return text.text().shortened() ? whole : null;
          });
        }));
    Script.run("size 60 120\nframe\nmove #anchor\nframe\nadvance 0.49\nframe", app, out);
    assertEquals(0, reads[0], "no measurements while waiting");
    assertFalse(app.tree().dump().contains("role=tooltip"));
    Script.run("advance 0.01\nframe", app, out);
    assertTrue(reads[0] > 0);
    assertEquals(whole, Workbench.find(app, "anchor").overlays().getFirst().label());

    Script.run("size 600 120\nframe\nadvance 0.25\nframe", app, out);
    assertFalse(app.tree().dump().contains("role=tooltip"), "the text now fits");
    Script.run("size 60 120\nframe\npress #anchor\nframe\nadvance 1\nframe", app, out);
    assertFalse(app.tree().dump().contains("role=tooltip"), "a press keeps the tooltip away");
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 2", "light, 2"})
  void showsBelowItsAnchorAfterTheDelay(String theme, int scale) throws IOException {
    Bench.assertScript("tooltip", theme, scale, out, "shown");
    String waiting = Bench.tree(out, "tooltip", theme, scale, "waiting");
    assertFalse(waiting.contains("overlay"), waiting);
    assertTrue(waiting.contains("label=\"?\""), waiting);
    String shown = Bench.tree(out, "tooltip", theme, scale, "shown");
    assertTrue(
        shown.contains("\noverlay bottom center gap 2 on #help role=button label=\"?\""), shown);
    assertTrue(shown.contains(" role=tooltip label=\"Keyboard shortcuts\" "), shown);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 2"})
  void showsWhenTheKeyboardFocusedItsAnchorAndGoesWithAKey(String theme, int scale)
      throws IOException {
    App<Bench> app = Bench.assertScript("keytip", theme, scale, out);
    String shown = Bench.tree(out, "keytip", theme, scale, "shown");
    assertTrue(
        shown.contains("\noverlay bottom center gap 2 on #help role=button label=\"?\""), shown);
    assertTrue(shown.contains(" label=\"?\" ") && shown.contains(" focused\n"), shown);
    String size = "size 360 240 scale " + scale + "\n";
    Script.run(size + "key A\nframe\nadvance 0.25\nframe\n", app, out); // it fades out
    assertFalse(app.tree().dump().contains("role=tooltip"), "a key hid it");
    Script.run(
        size + "advance 1\nframe\nkey Tab shift\nkey Tab\nframe\nadvance 0.5\nframe\n", app, out);
    assertTrue(app.tree().dump().contains("role=tooltip"), "back with the focus");
    Script.run(size + "key Tab\nframe\nadvance 0.25\nframe\n", app, out);
    assertFalse(app.tree().dump().contains("role=tooltip"), "the focus left");
  }
}
