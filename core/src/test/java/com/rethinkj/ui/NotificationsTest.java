package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Desktop notifications as an app sees them: with a test implementation ({@link
 * App#notifications}), and under a script, which records them for {@code snap} and clicks them with
 * {@code notification click}. Neither {@code libdbus} nor WinRT is loaded.
 */
class NotificationsTest {

  static final class Notifier {
    String title = "Export finished";
    String body = "orders.csv is ready.";
    final List<String> log = new ArrayList<>();
    App<Notifier> app;

    El view() {
      return el().key("send").size(80, 40).onPress(() -> {
        log.add("sent on " + Thread.currentThread().getName());
        app.notify(
            title,
            body,
            () -> log.add("clicked " + title + " on " + Thread.currentThread().getName()),
            error -> log.add("error " + error));
      });
    }
  }

  private static App<Notifier> notifier() {
    Notifier state = new Notifier();
    state.app = new App<>(state, Notifier::view);
    state.app.resize(80, 40, 1);
    state.app.frame();
    return state.app;
  }

  /** Notifications that click or fail from another thread, as D-Bus and WinRT do. */
  static final class OffThread implements Notifications {
    String error;

    @Override
    public void show(String title, String body, Runnable clicked, Consumer<String> failed) {
      Thread thread = Thread.ofPlatform().name("notify").start(() -> {
        if (error != null) {
          failed.accept(error);
        } else {
          clicked.run();
          clicked.run(); // a second click is ignored
        }
      });
      try {
        thread.join();
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Test
  void clickAndErrorArriveAsPostedTasksOnTheAppsThread() {
    App<Notifier> app = notifier();
    OffThread notifications = new OffThread();
    app.notifications(notifications);
    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame();
    String main = Thread.currentThread().getName();
    assertEquals(List.of("sent on " + main), app.state().log, "not in this frame");
    assertTrue(app.frame(), "the click is input: a frame for it");
    assertEquals(List.of("sent on " + main, "clicked Export finished on " + main), app.state().log);

    notifications.error = "No notification daemon";
    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame();
    app.frame();
    assertEquals("error No notification daemon", app.state().log.getLast());
  }

  @Test
  void withoutARunnerANotificationFailsSayingSo() {
    App<Notifier> app = notifier();
    app.pointerDown(10, 10);
    app.pointerUp(10, 10);
    app.frame();
    app.frame();
    assertEquals("error " + Notifications.MISSING, app.state().log.getLast());
  }

  @Test
  void aScriptRecordsNotificationsAndClicksThem(@TempDir Path out) throws IOException {
    App<Notifier> app = notifier();
    Script.run("""
        size 80 40
        frame
        press #send
        frame
        snap shown
        notification click "Export finished"
        await
        frame
        """, app, out);
    assertEquals(
        "\"Export finished\" \"orders.csv is ready.\"\n",
        Files.readString(out.resolve("shown.notifications.txt")));
    String main = Thread.currentThread().getName();
    assertEquals(List.of("sent on " + main, "clicked Export finished on " + main), app.state().log);

    Script.Failure again = assertThrows(
        Script.Failure.class, () -> Script.run("notification click \"Export finished\"", app, out));
    assertTrue(again.getMessage().contains("was already clicked"), again.getMessage());

    Script.Failure none = assertThrows(
        Script.Failure.class, () -> Script.run("notification click \"Missing\"", notifier(), out));
    assertTrue(none.getMessage().contains("No notification has been shown yet"), none.getMessage());

    Script.Failure wrongTitle =
        assertThrows(Script.Failure.class, () -> Script.run("""
            size 80 40
            frame
            press #send
            frame
            notification click "Other"
            """, notifier(), out));
    assertTrue(
        wrongTitle.getMessage().contains("The shown notifications are \"Export finished\""),
        wrongTitle.getMessage());

    Script.Failure usage = assertThrows(
        Script.Failure.class, () -> Script.run("notification click Export", notifier(), out));
    assertTrue(usage.getMessage().contains("`notification click \"<title>\"`"), usage.getMessage());
  }
}
