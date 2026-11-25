package com.rethinkj.ui;

import java.util.function.Consumer;

/**
 * What shows the system's desktop notifications for an app ({@link App#notifications}): a {@link
 * com.rethinkj.ui.window.WindowLoop} sets the system's for each window, a {@link Script} one that
 * notes them and lets its {@code notification click} step click them, and a test may set its own.
 * {@link App#notify(String, String, Runnable, Consumer)} calls it on the app's thread and posts
 * the click or failure to the app.
 */
@FunctionalInterface
public interface Notifications {

  /** Why {@link #NONE} fails. */
  String MISSING = "Nothing shows notifications for this app: run it in a WindowLoop or a Script,"
      + " which set App.notifications, or set them in a test.";

  /** For an app that no runner runs: every notification fails, saying so. */
  Notifications NONE = (title, body, clicked, failed) -> failed.accept(MISSING);

  /**
   * Shows a desktop notification with {@code title} and {@code body}. Calls {@code clicked} at
   * most once when the user clicks it while the app runs, or {@code failed} at most once with what
   * went wrong as the system says it, now or later, on any thread.
   */
  void show(String title, String body, Runnable clicked, Consumer<String> failed);
}
