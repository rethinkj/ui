package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.window.WindowIcon;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The system tray as an app sees it: with a {@link Trays} a test sets ({@link App#trays}), and
 * under a {@link Script}, which writes {@code .tray.txt} on {@code snap} and clicks entries with
 * {@code tray click}. Opens no window and loads no native libraries.
 */
class TrayTest {

  private static final WindowIcon ICON = WindowIcon.of(new Pixels(16, 16, new byte[16 * 16 * 4]));

  static final class State {
    final List<String> log = new ArrayList<>();
    boolean sync;
    App<State> app;

    El view() {
      return el().key("root")
          .size(80, 40)
          .child(el().key("show").size(40, 20).onPress(this::showTray))
          .child(el().key("remove").size(40, 20).onPress(() -> app.removeTray()));
    }

    void showTray() {
      app.tray(
          Tray.of(
              ICON,
              "Lights",
              Tray.button(
                  "Open", () -> log.add("open on " + Thread.currentThread().getName())),
              Tray.checkbox("Sync", sync, checked -> {
                sync = checked;
                log.add("sync " + checked);
              }),
              Tray.separator(),
              Tray.submenu(
                  "Theme",
                  Tray.button("Light", () -> log.add("theme light")),
                  Tray.checkbox("High contrast", true, c -> log.add("hc " + c)).disabled()),
              Tray.button("Reconnect", () -> log.add("reconnect")).disabled(),
              Tray.submenu("Offline", Tray.button("Retry", () -> log.add("retry")))
                  .disabled(),
              Tray.separator(),
              Tray.button("Quit", () -> log.add("quit"))),
          error -> log.add("error " + error));
    }
  }

  private static App<State> trayApp() {
    State state = new State();
    state.app = new App<>(state, State::view);
    return state.app;
  }

  @Test
  void aScriptSnapsTheTrayMenuClicksEntriesAndRemovesIt(@TempDir Path out) throws IOException {
    App<State> app = trayApp();
    List<Path> written = Script.run("""
        size 80 40
        frame
        press #show
        frame
        snap shown
        tray click "Open"
        tray click "Sync"
        tray click "Theme" "Light"
        await
        frame
        snap toggled
        tray click "Sync"
        await
        frame
        press #remove
        frame
        snap removed
        """, app, out);

    assertTrue(written.contains(out.resolve("shown.tray.txt")), written::toString);
    assertEquals("""
        tooltip "Lights"
        button "Open"
        checkbox "Sync" checked=false
        separator
        submenu "Theme"
          button "Light"
          checkbox "High contrast" checked disabled
        button "Reconnect" disabled
        submenu "Offline" disabled
          button "Retry"
        separator
        button "Quit"
        """, Files.readString(out.resolve("shown.tray.txt")));

    String main = Thread.currentThread().getName();
    assertEquals(
        List.of("open on " + main, "sync true", "theme light", "sync false"), app.state().log);
    assertTrue(
        Files.readString(out.resolve("toggled.tray.txt")).contains("checkbox \"Sync\" checked\n"));
    assertFalse(Files.exists(out.resolve("removed.tray.txt")));
  }

  @Test
  void trayClickFailsClearlyForMissingDisabledOrWrongEntries(@TempDir Path out) {
    Script.Failure none = assertThrows(
        Script.Failure.class,
        () -> Script.run("size 80 40\nframe\ntray click \"Open\"\n", trayApp(), out));
    assertTrue(none.getMessage().contains("no tray icon"), none.getMessage());

    String setup = "size 80 40\nframe\npress #show\nframe\n";
    Script.Failure missing = assertThrows(
        Script.Failure.class, () -> Script.run(setup + "tray click \"Missing\"\n", trayApp(), out));
    assertTrue(
        missing
            .getMessage()
            .contains("The tray menu has no entry \"Missing\". Its entries are"
                + " \"Open\", \"Sync\", \"Theme\", \"Reconnect\", \"Offline\", \"Quit\"."),
        missing.getMessage());

    Script.Failure disabledButton = assertThrows(
        Script.Failure.class,
        () -> Script.run(setup + "tray click \"Reconnect\"\n", trayApp(), out));
    assertTrue(
        disabledButton.getMessage().contains("Entry \"Reconnect\" is disabled"),
        disabledButton.getMessage());

    Script.Failure submenuDirectly = assertThrows(
        Script.Failure.class, () -> Script.run(setup + "tray click \"Theme\"\n", trayApp(), out));
    assertTrue(
        submenuDirectly.getMessage().contains("\"Theme\" is a submenu"),
        submenuDirectly.getMessage());
  }

  @Test
  void withoutARunnerTrayFailsAndCustomTraysPostsHandlers() throws InterruptedException {
    App<State> app = trayApp();
    app.resize(80, 40, 1);
    app.frame();
    app.state().showTray();
    assertTrue(app.state().log.isEmpty(), "error arrives as a posted task in the next frame");
    assertTrue(app.frame());
    assertEquals(List.of("error " + Trays.MISSING), app.state().log);

    List<Tray> shown = new ArrayList<>();
    app.trays(new Trays() {
      @Override
      public void show(Tray tray, Consumer<String> failed) {
        shown.add(tray);
      }

      @Override
      public void remove() {
        shown.clear();
      }
    });
    app.state().showTray();
    Tray.Button open = (Tray.Button) shown.getFirst().entries().getFirst();
    Thread worker = Thread.ofPlatform().name("tray").start(open.onClick());
    worker.join();
    assertEquals(1, app.state().log.size(), "not before the next frame");
    assertTrue(app.frame());
    assertEquals("open on " + Thread.currentThread().getName(), app.state().log.getLast());

    assertThrows(IllegalArgumentException.class, () -> Tray.button("", () -> {}));
    assertThrows(NullPointerException.class, () -> Tray.of(null, "Lights"));
  }
}
