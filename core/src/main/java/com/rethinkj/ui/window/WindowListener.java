package com.rethinkj.ui.window;

/**
 * Receives a window's events, on the thread that uses the windows, from {@link Window#pollEvents}
 * and {@link Window#waitEvents}. While the user moves or resizes a window on Windows, it is also
 * called from inside the system's own loop, which those methods are waiting in.
 */
@FunctionalInterface
public interface WindowListener {
  void event(WindowEvent event);
}
