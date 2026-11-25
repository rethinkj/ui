package com.rethinkj.ui;

import java.util.function.Consumer;

/**
 * What shows the system tray icon for an app ({@link App#trays}): a {@link
 * com.rethinkj.ui.window.WindowLoop} sets the system's for each window, a {@link Script} one that
 * records it for {@code snap} and lets its {@code tray click} step click entries, and a test may
 * set its own. {@link App#tray} and {@link App#removeTray} call it on the app's thread; the app
 * wraps every entry's handler and {@code onError} so they run as {@link App#post posted} tasks.
 */
public interface Trays {

  /** Why {@link #NONE} fails. */
  String MISSING = "Nothing shows a tray icon for this app: run it in a WindowLoop or a Script,"
      + " which set App.trays, or set them in a test.";

  /** For an app that no runner runs: showing a tray fails, saying so, and removing does nothing. */
  Trays NONE = new Trays() {
    @Override
    public void show(Tray tray, Consumer<String> failed) {
      failed.accept(MISSING);
    }

    @Override
    public void remove() {}
  };

  /**
   * Shows {@code tray} in the system tray, replacing any tray icon this app already shows. Calls
   * {@code failed} at most once, now or later, on any thread, with what went wrong as the system
   * says it.
   */
  void show(Tray tray, Consumer<String> failed);

  /** Removes the app's tray icon, if one is shown. */
  void remove();
}
