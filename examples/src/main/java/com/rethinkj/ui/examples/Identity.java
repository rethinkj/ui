package com.rethinkj.ui.examples;

import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowIcon;

/**
 * Who an example is to the system: its app id, {@code com.rethinkj.ui.examples.<class>}, under
 * which the taskbar gives it a button of its own, and its icon, a rounded square of its colour with
 * a symbol.
 */
final class Identity {

  private Identity() {}

  /** Sets the example's identity and returns window options with its icon. */
  static Window.Options options(Class<?> example, String name, int colour, Symbol symbol) {
    Window.identity("com.rethinkj.ui.examples." + example.getSimpleName(), name);
    return Window.options().icon(icon(colour, symbol));
  }

  static WindowIcon icon(int colour, Symbol symbol) {
    return WindowIcon.of(size -> AppIcon.appIcon(Theme.DARK, size, colour, symbol));
  }
}
