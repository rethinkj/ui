package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The title bar of a frameless window: its parts, the buttons' looks and Restore. */
class TitleBarTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Object> app(boolean maximized) {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .fill(t.surface().window())
            .child(TitleBar.titleBar(
                t,
                "Orders",
                null,
                null,
                maximized,
                Button.button(t, Button.Variant.GHOST, "Run", () -> {}))));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** The colour of the pixel at x, y of a snap, 0xAARRGGBB. */
  private int pixel(String snap, int x, int y) throws IOException {
    Pixels p = Png.decode(Files.readAllBytes(out.resolve(snap + ".png")));
    int i = (y * p.width() + x) * 4;
    byte[] b = p.rgba();
    return (b[i + 3] & 0xFF) << 24 | (b[i] & 0xFF) << 16 | (b[i + 1] & 0xFF) << 8 | b[i + 2] & 0xFF;
  }

  @Test
  void theBarMovesTheWindowAndItsButtonsShowHoverAndPress() throws IOException {
    App<Object> app = app(false);
    Script.run("""
        size 400 120
        frame
        move label="Close"
        frame
        snap hovered
        down label="Close"
        frame
        snap pressed
        up label="Close"
        move label="Minimize"
        down label="Minimize"
        frame
        snap minimize
        """, app, out);
    Theme.Caption c = Theme.DARK.caption();
    assertEquals(c.closeHover(), pixel("hovered", 400 - 3, 3), "red under the pointer");
    assertEquals(c.closePressed(), pixel("pressed", 400 - 3, 3), "lighter while pressed");
    assertEquals(
        Theme.over(Theme.DARK.surface().window(), Theme.DARK.surface().pressed()),
        pixel("minimize", 400 - 2 * 46 - 3, 3),
        "a caption button darkens while pressed");
    assertEquals(WindowPart.DRAG, app.windowPart(120, 16));
    assertEquals(WindowPart.CLOSE, app.windowPart(400 - 10, 16));
    assertEquals(WindowPart.MINIMIZE, app.windowPart(400 - 46 * 3 + 10, 16));
    assertEquals(WindowPart.CONTROL, app.windowPart(400 - 46 * 3 - 20, 16), "the app's control");
  }

  @Test
  void aMaximizedWindowOffersRestore() throws IOException {
    App<Object> app = app(true);
    Script.run("size 400 120\nframe", app, out);
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=button label=\"Restore\""), dump);
    assertTrue(dump.contains("label=\"square_multiple\""), dump);
  }
}
