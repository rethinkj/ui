package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The system's dialogs as an app sees them: with dialogs a test sets ({@link App#dialogs}), and
 * under a script, whose {@code dialog} steps answer them. No dialog of the system opens.
 */
class DialogsTest {

  /** An app with a button that opens what {@link #opens} says, and a log of the answers. */
  static final class Opener {
    FileDialog dialog = FileDialog.open("Open").filter("SQL", "sql").filter("Text", "txt", "md");
    MessageBox box;
    final List<String> log = new ArrayList<>();
    App<Opener> app;

    El view() {
      return el().key("open").size(80, 40).onPress(() -> {
        log.add("asked on " + Thread.currentThread().getName());
        if (box != null) {
          app.messageBox(box, button -> log.add("button " + button), e -> log.add("error " + e));
        } else {
          app.fileDialog(dialog, c -> log.add(c.paths() + " filter " + c.filter()
              + " on " + Thread.currentThread().getName()), e -> log.add("error " + e));
        }
      });
    }
  }

  private static App<Opener> opener() {
    Opener o = new Opener();
    o.app = new App<>(o, Opener::view);
    o.app.resize(80, 40, 1);
    o.app.frame();
    return o.app;
  }

  /** Dialogs that answer from another thread, as SDL's do on Windows and with zenity. */
  static final class Answering implements Dialogs {
    final List<FileDialog> shown = new ArrayList<>();
    FileDialog.Choice choice;
    String error;

    @Override
    public void file(FileDialog dialog, Consumer<FileDialog.Choice> chosen,
        Consumer<String> failed) {
      shown.add(dialog);
      Thread thread = Thread.ofPlatform().name("dialog").start(() -> {
        if (error != null) {
          failed.accept(error);
        } else {
          chosen.accept(choice);
          chosen.accept(choice); // a second answer is ignored
        }
      });
      try {
        thread.join();
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public void message(MessageBox box, IntConsumer pressed, Consumer<String> failed) {
      pressed.accept(box.buttons().size() - 1);
    }
  }

  @Test
  void theChoiceArrivesAsATaskOnTheAppsThreadInTheNextFrame() {
    App<Opener> app = opener();
    Answering dialogs = new Answering();
    dialogs.choice = new FileDialog.Choice(List.of(Path.of("a.sql"), Path.of("b.md")), 1);
    app.dialogs(dialogs);
    app.state().dialog = app.state().dialog.many();
    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame(); // the press opens the dialog, which answers on its thread
    String main = Thread.currentThread().getName();
    assertEquals(List.of("asked on " + main), app.state().log, "not in this frame");
    assertTrue(app.frame(), "the answer is input: a frame for it");
    assertEquals(List.of("asked on " + main, "[a.sql, b.md] filter 1 on " + main),
        app.state().log);
    assertEquals("an open dialog for several files titled \"Open\" with the filters SQL (sql),"
        + " Text (txt, md)", dialogs.shown.getFirst().toString());
  }

  @Test
  void aCancelIsAChoiceWithoutPathsAndAFailureHasTheSystemsText() {
    App<Opener> app = opener();
    Answering dialogs = new Answering();
    dialogs.choice = new FileDialog.Choice(List.of(), -1);
    app.dialogs(dialogs);
    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame();
    app.frame();
    assertTrue(app.state().log.getLast().startsWith("[] filter -1"), app.state().log::toString);
    assertTrue(dialogs.choice.cancelled());

    dialogs.error = "Failed to open dialog via DBus";
    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame();
    app.frame();
    assertEquals("error Failed to open dialog via DBus", app.state().log.getLast());
  }

  @Test
  void withoutARunnerADialogFailsSayingSo() {
    App<Opener> app = opener();
    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame();
    app.frame();
    assertEquals("error " + Dialogs.MISSING, app.state().log.getLast());
  }

  @Test
  void aDialogSaysWhatItIsAndRefusesWhatItCannotBe() {
    assertEquals("a save dialog titled \"Export\" with the filters CSV (csv), suggesting"
        + " orders.csv", FileDialog.save("Export").filter("CSV", "csv").fileName("orders.csv")
            .toString());
    assertEquals("a dialog for several folders", FileDialog.chooseFolder(null).many().toString());
    assertThrows(IllegalArgumentException.class, () -> FileDialog.open("x").filter("SQL", ".sql"));
    assertThrows(IllegalArgumentException.class, () -> FileDialog.open("x").filter("None"));
    assertThrows(IllegalArgumentException.class, () -> FileDialog.save("x").many());
    assertThrows(IllegalArgumentException.class, () -> FileDialog.open("x").fileName("a.sql"));
    assertThrows(IllegalArgumentException.class, () -> FileDialog.save("x").fileName("d/a.sql"));
    assertThrows(IllegalArgumentException.class,
        () -> FileDialog.chooseFolder("x").filter("All", "*"));
    assertEquals(List.of("*"), FileDialog.open("x").filter("All", "*").filters().getFirst()
        .extensions());
    assertThrows(IllegalArgumentException.class,
        () -> MessageBox.error("x", "y").buttons("a", "b", "c", "d"));
    MessageBox box = MessageBox.warning("Close", "Unsaved changes").buttons("Save", "Discard",
        "Cancel");
    assertEquals(0, box.defaultButton());
    assertEquals(2, box.cancelButton());
  }

  // ---- Scripts

  @Test
  void aScriptAnswersTheNextDialogWithPathsAndAFilter(@TempDir Path out) throws IOException {
    App<Opener> app = opener();
    app.state().dialog = app.state().dialog.many();
    Script.run("""
        size 80 40
        frame
        dialog "query one.sql" "Ä/ünïcode.md" filter 1
        press #open
        frame
        await
        frame
        dialog cancel
        press #open
        frame
        await
        frame
        dialog error "No portal"
        press #open
        frame
        await
        frame
        """, app, out);
    List<String> log = app.state().log;
    // The paths as the platform writes them, with its separator.
    assertEquals("[query one.sql, " + Path.of("Ä", "ünïcode.md") + "] filter 1 on "
        + Thread.currentThread().getName(),
        log.get(1));
    assertTrue(log.get(3).startsWith("[] filter -1"), log::toString);
    assertEquals("error No portal", log.get(5));
  }

  @Test
  void aScriptAnswersAMessageBoxByTheTextOfItsButton(@TempDir Path out) throws IOException {
    App<Opener> app = opener();
    app.state().box = MessageBox.warning("Close", "Unsaved changes").buttons("Save", "Discard",
        "Cancel");
    Script.run("""
        size 80 40
        frame
        dialog button "Discard"
        press #open
        frame
        dialog cancel
        press #open
        frame
        await
        frame
        """, app, out);
    assertEquals(List.of("button 1", "button 2"),
        app.state().log.stream().filter(l -> l.startsWith("button")).toList());
  }

  @Test
  void aDialogWithoutAnAnswerFailsTheFrameNamingTitleAndFilters(@TempDir Path out) {
    Script.Failure failure = assertThrows(Script.Failure.class, () -> Script.run("""
        size 80 40
        frame
        press #open
        frame
        """, opener(), out));
    assertTrue(failure.getMessage().startsWith("line 4: frame\n  The app opened an open dialog"
        + " titled \"Open\" with the filters SQL (sql), Text (txt, md) in this frame, and no"
        + " `dialog` step answered it."), failure.getMessage());
    assertTrue(failure.getMessage().contains("write `dialog \"<path>\"`"), failure.getMessage());
  }

  @Test
  void anAnswerThatAnswersNothingOrTheWrongDialogFails(@TempDir Path out) {
    Script.Failure unused = assertThrows(Script.Failure.class, () -> Script.run("""
        size 80 40
        frame
        dialog "a.sql"
        """, opener(), out));
    assertTrue(unused.getMessage().startsWith("line 3: dialog \"a.sql\"\n  No dialog opened"),
        unused.getMessage());

    Script.Failure two = assertThrows(Script.Failure.class, () -> Script.run("""
        size 80 40
        frame
        dialog "a.sql" "b.sql"
        press #open
        frame
        """, opener(), out));
    assertTrue(two.getMessage().contains("chooses 2 files, but the app opened an open dialog"),
        two.getMessage());

    Script.Failure filter = assertThrows(Script.Failure.class, () -> Script.run("""
        size 80 40
        frame
        dialog "a.sql" filter 2
        press #open
        frame
        """, opener(), out));
    assertTrue(filter.getMessage().contains("which has 2. Count them from 0."),
        filter.getMessage());

    Script.Failure usage = assertThrows(Script.Failure.class,
        () -> Script.run("dialog a.sql", opener(), out));
    assertTrue(usage.getMessage().contains("Write `dialog \"<path>\"`"), usage.getMessage());
  }
}
