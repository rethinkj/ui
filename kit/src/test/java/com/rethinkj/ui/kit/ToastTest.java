package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The toast of the studio ({@link Studio}) after Edit's Clear: at the bottom of the window, without
 * the focus, with Undo, gone after the theme's time on the app's clock; stored images.
 */
class ToastTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private void run(App<?> app, String steps) throws IOException {
    Script.run("size 360 240\n" + steps, app, out);
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 2", "light, 2"})
  void showsAtTheBottomWithoutTakingTheFocus(String theme, int scale) throws IOException {
    App<Studio> app = Studio.assertScript("toast", theme, scale, out);
    String shown = Studio.tree(out, "toast", theme, scale, "shown");
    assertTrue(shown.contains("\noverlay edge bottom center gap 16 on 0,0 360x240\n"), shown);
    assertTrue(shown.contains(" role=status label=\"Query cleared\" "), shown);
    assertEquals("query", app.focused().key());
  }

  @Test
  void undoRestoresWithoutMovingTheFocusAndTheTimeCloses() throws IOException {
    Studio studio = new Studio(Theme.DARK);
    App<Studio> app = studio.app();
    run(app, "frame\npress #query\nkey E alt\nframe\nkey End\nkey Enter\nframe\n");
    assertEquals("", studio.query, "Clear, the last item");
    assertEquals("query", app.focused().key(), "the toast did not take the focus");
    run(app, "type \"x\"\nframe\n");
    assertEquals("x", studio.query, "typing goes on in the field");
    run(app, "advance 0.25\nframe\npress label=\"Undo\"\nframe\n"); // after its fade
    assertEquals("SELECT * FROM orders", studio.query);
    assertNull(studio.cleared, "the action closed it");
    assertEquals("query", app.focused().key(), "the press left the focus in the field");

    run(app, "key E alt\nframe\nkey End\nkey Enter\nframe\nkey Tab\nframe\n");
    assertEquals("action", app.focused().key(), "Tab reaches Undo");
    run(app, "key Escape\nframe\n");
    assertNull(studio.cleared, "Escape there closes it");
    assertEquals("query", app.focused().key(), "and the focus goes back to the field");
    run(app, "key E alt\nframe\nkey End\nkey Enter\nframe\nkey Tab\nkey Enter\nframe\n");
    assertNull(studio.cleared, "Enter on Undo closes it");
    assertEquals("query", app.focused().key(), "so does the focus");
    run(app, "type \"y\"\nframe\n");
    assertEquals("y", studio.query, "and typing goes on there");

    // Without a focused field, whose caret would blink meanwhile.
    run(app, "press #status\nkey E alt\nframe\nkey End\nkey Enter\nframe\n");
    double opened = app.time();
    run(app, "advance 0.25\nframe\n"); // its fade
    assertNull(app.focused());
    assertEquals(opened + 5, app.nextChange(), 1e-9, "it closes after five seconds");
    app.advance(4.65);
    assertFalse(app.frame(), "nothing to draw meanwhile");
    run(app, "press #status\nframe\n");
    assertTrue(studio.cleared != null, "a press elsewhere does not close it");
    app.advance(0.1);
    app.frame();
    assertNull(studio.cleared);
    assertTrue(app.isMoving(), "it fades out");
    app.advance(0.25);
    app.frame();
    assertFalse(app.isMoving());
    assertFalse(app.frame(), "and then nothing is drawn");
    assertFalse(app.tree().dump().contains("role=status"), app.tree().dump());
  }
}
