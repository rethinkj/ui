package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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

  private static final class StackState {
    final Toasts toasts = new Toasts();
    String text = "";
    String action;

    El view() {
      Theme theme = Theme.CURRENT.get();
      return col()
          .wFull()
          .hFull()
          .fill(theme.surface().window())
          .padding(theme.space().l())
          .align(Style.Align.START)
          .child(TextField.textField(text, "Query", value -> text = value).key("input"))
          .overlay(toasts.view());
    }
  }

  private static App<StackState> stackApp(StackState state) {
    App<StackState> app =
        new App<>(state, StackState::view).provide(Theme.CURRENT, s -> Theme.DARK);
    app.focusRing(Theme.DARK.accent().edge());
    app.textRendering(TextRendering.LINUX);
    return app;
  }

  @Test
  void stackOfKindsAndProgressShowsAtTheBottom() throws IOException {
    StackState state = new StackState();
    state.toasts.show(Callout.Kind.WARNING, "Disk space low");
    state.toasts.show(Callout.Kind.SUCCESS, "Backup finished", "Open", () -> state.action = "open");
    state.toasts.progress("Uploading archive…", 0.6);
    App<StackState> app = stackApp(state);
    Path folder = out.resolve("stack");
    List<Path> files =
        Script.run("size 420 240 scale 2\nframe\nadvance 0.3\nframe\nsnap shown\n", app, folder);
    Golden.assertSnaps("toast-stack-dark-2x-", files, "shown");
    String tree = java.nio.file.Files.readString(folder.resolve("shown.tree.txt"));
    assertTrue(tree.contains("\noverlay edge bottom center gap 16 on 0,0 420x240\n"), tree);
    assertTrue(tree.contains("role=status label=\"Disk space low\""), tree);
    assertTrue(tree.contains("role=status label=\"Backup finished\""), tree);
    assertTrue(tree.contains("role=status label=\"Uploading archive…\""), tree);
    assertTrue(tree.contains("role=progressbar") && tree.contains("value=\"60%\""), tree);
  }

  @Test
  void queueClosePauseOnHoverAndProgressFinish() throws IOException {
    StackState state = new StackState();
    state.toasts.show(Callout.Kind.INFO, "First");
    state.toasts.show(Callout.Kind.SUCCESS, "Second");
    state.toasts.show(Callout.Kind.WARNING, "Third");
    state.toasts.show(Callout.Kind.DANGER, "Fourth");
    App<StackState> app = stackApp(state);

    // Queue: only 3 visible, newest nearest bottom edge, focus stays in the field.
    Script.run("size 420 260 scale 2\nframe\npress #input\nframe\nadvance 0.3\nframe\n", app, out);
    String dump = app.tree().dump();
    assertEquals("input", app.focused().key(), "toasts do not take the focus");
    assertTrue(dump.contains("label=\"First\""), dump);
    assertTrue(dump.contains("label=\"Second\""), dump);
    assertTrue(dump.contains("label=\"Third\""), dump);
    assertFalse(dump.contains("label=\"Fourth\""), "4th waits in queue: " + dump);
    assertTrue(
        topOf(dump, "#toast-1") < topOf(dump, "#toast-2")
            && topOf(dump, "#toast-2") < topOf(dump, "#toast-3"),
        "newest nearest the bottom edge:\n" + dump);

    // Close button on #toast-2 removes it and promotes "Fourth" from the queue.
    Script.run("press #close-2\nframe\nadvance 0.3\nframe\n", app, out);
    dump = app.tree().dump();
    assertFalse(dump.contains("label=\"Second\""), dump);
    assertTrue(dump.contains("label=\"Fourth\""), "4th promoted when slot opened: " + dump);
    assertEquals("input", app.focused().key(), "close button keeps focus in the field");

    // Escape on a focused toast closes it; closing the remaining toasts restores focus.
    Script.run("key Tab\nframe\nkey Escape\nframe\nadvance 0.3\nframe\n", app, out);
    dump = app.tree().dump();
    assertFalse(dump.contains("label=\"First\""), "Escape closed the focused toast: " + dump);
    Script.run("key Escape\nframe\nkey Escape\nframe\nadvance 0.3\nframe\n", app, out);
    assertTrue(state.toasts.isEmpty());
    assertEquals("input", app.focused().key(), "focus returned to the field once the stack closed");

    // Pause on hover: hovering the bottom toast pauses its timeout while the one above closes.
    state.toasts.show(Callout.Kind.INFO, "Brief", "Undo", () -> state.action = "undo");
    state.toasts.show(null, "Pinned");
    app.invalidate();
    Script.run("frame\nadvance 0.3\nframe\nadvance 2\nframe\nmove #toast-6\nframe\n", app, out);
    Script.run("advance 4\nframe\nadvance 0.3\nframe\n", app, out);
    dump = app.tree().dump();
    assertTrue(dump.contains("label=\"Pinned\""), "hovered toast stays open: " + dump);
    assertFalse(dump.contains("label=\"Brief\""), "unhovered toast timed out: " + dump);
    Script.run("move 10,10\nframe\nadvance 2.5\nframe\n", app, out);
    assertTrue(app.tree().dump().contains("label=\"Pinned\""), "remaining time still running");
    Script.run("advance 0.4\nframe\nadvance 0.3\nframe\n", app, out);
    assertFalse(app.tree().dump().contains("label=\"Pinned\""), "closed after leaving");
    assertTrue(state.toasts.isEmpty());

    // Progress toast does not time out while running; after finish it times out normally.
    Toasts.Handle handle = state.toasts.progress("Reading files…");
    app.invalidate();
    Script.run("frame\nadvance 10\nframe\n", app, out);
    dump = app.tree().dump();
    assertTrue(dump.contains("label=\"Reading files…\""), "indeterminate stays open: " + dump);
    assertTrue(dump.contains("role=progressbar"), dump);

    handle.progress(0.5);
    app.invalidate();
    Script.run("frame\nadvance 10\nframe\n", app, out);
    dump = app.tree().dump();
    assertTrue(dump.contains("role=progressbar") && dump.contains("value=\"50%\""), dump);

    Script.run("key Tab\nframe\n", app, out);
    assertEquals("close-7", app.focused().key());
    handle.finish(Callout.Kind.SUCCESS, "Read 3 files", "Open", () -> state.action = "open");
    app.invalidate();
    Script.run("frame\nadvance 0.3\nframe\n", app, out);
    assertEquals("close-7", app.focused().key(), "Close keeps the focus, not the new action");
    dump = app.tree().dump();
    assertTrue(dump.contains("label=\"Read 3 files\""), dump);
    assertFalse(dump.contains("role=progressbar"), dump);
    Script.run("advance 4.5\nframe\n", app, out);
    assertTrue(app.tree().dump().contains("label=\"Read 3 files\""), "still open before 5s");
    Script.run("advance 0.3\nframe\nadvance 0.3\nframe\n", app, out);
    assertFalse(app.tree().dump().contains("role=status"), "timed out after finish");
    assertTrue(state.toasts.isEmpty());
  }

  @Test
  void longMessagesKeepThreeToastsInASmallWindow() throws IOException {
    StackState state = new StackState();
    String error = "Cannot open a-file-with-a-rather-long-name.sql: it is larger than 512 KB and"
        + " cannot be opened in this editor, which reads files of up to 512 KB only; split it or open"
        + " it in another program; the file stays as it is on disk and nothing was changed";
    for (int i = 0; i < 3; i++) {
      state.toasts.show(Callout.Kind.DANGER, error);
    }
    App<StackState> app = stackApp(state);
    Script.run("size 360 240 scale 2\nframe\nadvance 0.3\nframe\n", app, out);
    String dump = app.tree().dump();
    for (int i = 1; i <= 3; i++) {
      String line = dump.lines()
          .filter(l -> l.contains(" #close-") && l.contains("label=\"Close\""))
          .skip(i - 1)
          .findFirst()
          .orElseThrow();
      String[] size = line.substring(line.lastIndexOf(' ') + 1).split("x");
      assertTrue(topOf(dump, line.strip().split(" ")[1]) + Float.parseFloat(size[1]) <= 240, dump);
    }
  }

  private static float topOf(String dump, String key) {
    String line =
        dump.lines().filter(l -> l.contains(" " + key + " ")).findFirst().orElseThrow();
    String[] parts = line.strip().split(" ");
    for (String part : parts) {
      int comma = part.indexOf(',');
      if (comma > 0 && Character.isDigit(part.charAt(0))) {
        return Float.parseFloat(part.substring(comma + 1));
      }
    }
    throw new AssertionError("no coordinates in: " + line);
  }
}
