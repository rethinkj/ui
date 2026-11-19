package com.rethinkj.ui;

import java.net.URI;
import java.util.function.Consumer;

/**
 * What opens a web address for an app ({@link App#browser}): a {@link
 * com.rethinkj.ui.window.WindowLoop} sets the system's default browser, a {@link Script} one that
 * only notes the address, and a test may set its own. {@link App#openUri} calls it on the app's
 * thread with an absolute {@code http} or {@code https} address.
 */
@FunctionalInterface
public interface Browser {

  /** For an app that no runner runs: every address fails, saying so. */
  Browser NONE = (uri, failed) -> failed.accept(
      "Nothing opens addresses for this app: run it in a WindowLoop or a Script, which set"
          + " App.browser, or set it in a test.");

  /**
   * Opens {@code uri}. Calls {@code failed} at most once, now or later, on any thread, with what
   * went wrong as the system says it.
   */
  void open(URI uri, Consumer<String> failed);
}
