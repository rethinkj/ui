package com.rethinkj.ui;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * What shows the system's dialogs for an app ({@link App#dialogs}): a {@link
 * com.rethinkj.ui.window.WindowLoop} sets the system's for each window, a {@link Script} one that
 * answers them from its {@code dialog} steps, and a test may set its own. {@link App#fileDialog}
 * and {@link App#messageBox} call it on the app's thread and post its answer to the app.
 */
public interface Dialogs {

  /** For an app that no runner runs: every dialog fails, saying so. */
  Dialogs NONE = new Dialogs() {
    @Override
    public void file(
        FileDialog dialog, Consumer<FileDialog.Choice> chosen, Consumer<String> failed) {
      failed.accept(MISSING);
    }

    @Override
    public void message(MessageBox box, IntConsumer pressed, Consumer<String> failed) {
      failed.accept(MISSING);
    }
  };

  /** Why {@link #NONE} fails. */
  String MISSING = "Nothing shows dialogs for this app: run it in a WindowLoop or a Script, which"
      + " set App.dialogs, or set them in a test.";

  /**
   * Shows a file dialog. Calls {@code chosen} or {@code failed} once, now or later, on any
   * thread.
   *
   * @param failed gets what went wrong, as the system says it
   */
  void file(FileDialog dialog, Consumer<FileDialog.Choice> chosen, Consumer<String> failed);

  /**
   * Shows a message box. Calls {@code pressed} with the index of the button, or {@code failed},
   * once, now or later, on any thread.
   */
  void message(MessageBox box, IntConsumer pressed, Consumer<String> failed);
}
