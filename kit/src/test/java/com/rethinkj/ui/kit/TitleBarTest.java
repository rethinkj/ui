package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Text;
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

/** The title bar of a frameless window: its parts, the buttons' looks, Restore, a narrow window and an inactive one. */
class TitleBarTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private App<Object> app(String title, El... end) {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .fill(t.surface().window())
            .child(TitleBar.titleBar(null, title, end)));
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  /** The color of the pixel at x, y of a snap, 0xAARRGGBB. */
  private int pixel(String snap, int x, int y) throws IOException {
    Pixels p = Png.decode(Files.readAllBytes(out.resolve(snap + ".png")));
    int i = (y * p.width() + x) * 4;
    byte[] b = p.rgba();
    return (b[i + 3] & 0xFF) << 24 | (b[i] & 0xFF) << 16 | (b[i + 1] & 0xFF) << 8 | b[i + 2] & 0xFF;
  }

  @Test
  void theBarMovesTheWindowAndItsButtonsShowHoverAndPress() throws IOException {
    App<Object> app = app("Orders", Button.button(Button.Variant.GHOST, "Run", () -> {}));
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
    App<Object> app = app("Orders");
    app.windowMaximized(true);
    Script.run("size 400 120\nframe", app, out);
    String dump = app.tree().dump();
    assertTrue(dump.contains("role=button label=\"Restore\""), dump);
    assertTrue(dump.contains("label=\"square_multiple\""), dump);
  }

  @Test
  void aNarrowWindowShortensTheTitleAndAnInactiveOneMutesIt() throws IOException {
    String name = "monthly_revenue_by_region_and_channel.sql";
    El[] actions = new El[4];
    for (int i = 0; i < actions.length; i++) {
      actions[i] = Button.icon(Button.Variant.GHOST, Symbol.ADD, "Add " + i, () -> {});
    }
    App<Object> app = app(name, actions);
    Script.run("size 240 120\nframe", app, out);
    assertEquals(WindowPart.CLOSE, app.windowPart(240 - 10, 16), "the buttons keep their width");
    assertEquals(WindowPart.MINIMIZE, app.windowPart(240 - 46 * 3 + 10, 16));
    assertEquals(WindowPart.DRAG, app.windowPart(10, 16), "a gap moves the window");
    assertTrue(title(app.tree(), name).shortened());
    assertEquals(Theme.DARK.text().primary(), title(app.tree(), name).color());
    app.windowActive(false);
    app.frame();
    assertEquals(
        Theme.DARK.text().muted(), title(app.tree(), name).color(), "muted while inactive");
  }

  @Test
  void aProjectColorGlowFadesToTheWindowColorAtTheBottomAndStaysWhileInactive() throws IOException {
    Theme theme = Theme.DARK;
    int color = theme.withAccent(Theme.Hue.TEAL).accent().color();
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .fill(theme.surface().window())
            .child(TitleBar.titleBar(AppIcon.appIcon(16, color, Symbol.DATABASE), "Shop")
                .background(TitleBar.glow(color))));
    app.textRendering(TextRendering.LINUX);
    Script.run("size 560 120\nframe\nsnap active", app, out);
    int topActive = pixel("active", 16, 1);
    int bottom = pixel("active", 16, (int) theme.caption().height() - 1);
    assertNotEquals(theme.surface().window(), topActive, "glow tints near the icon");
    assertEquals(theme.surface().window(), bottom, "no seam at the bar's bottom edge");
    app.windowActive(false);
    Script.run("frame\nsnap inactive", app, out);
    assertEquals(topActive, pixel("inactive", 16, 1), "glow stays while inactive");
  }

  /** The text {@code string} in the tree under {@code el}, or null. */
  private static Text title(El el, String string) {
    if (el.text() != null && el.text().string().equals(string)) {
      return el.text();
    }
    for (El child : el.children()) {
      Text found = title(child, string);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
