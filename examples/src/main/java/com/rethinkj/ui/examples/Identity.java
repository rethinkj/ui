package com.rethinkj.ui.examples;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
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

  // The examples' own colours, for their icons and marks. They are not the kit's: an app has a
  // colour of its own, and the themes do not use these.

  /** Notes and Showcase. */
  static final int VIOLET = Colors.rgb(0x7C3AED);

  /** Documents and the orders of DataBrowser. */
  static final int BLUE = Colors.rgb(0x2563EB);

  /** Workbench and Manual. */
  static final int TEAL = Colors.rgb(0x0F766E);

  /** Settings. */
  static final int SLATE = Colors.rgb(0x475569);

  /** ConnectionDialog. */
  static final int GREEN = Colors.rgb(0x16A34A);

  /** The icon of LightSwitch. */
  static final int AMBER = Colors.rgb(0xD97706);

  /** The lamp of LightSwitch, and its mark. */
  static final int YELLOW = Colors.rgb(0xFACC15);

  private Identity() {}

  /** Sets the example's identity and returns window options with its icon. */
  static Window.Options options(Class<?> example, String name, int colour, Symbol symbol) {
    Window.identity("com.rethinkj.ui.examples." + example.getSimpleName(), name);
    return Window.options().icon(icon(colour, symbol));
  }

  static WindowIcon icon(int colour, Symbol symbol) {
    return WindowIcon.of(size -> AppIcon.appIcon(Theme.DARK, size, colour, symbol));
  }

  /** Whether the window that runs {@code app} is maximized, for its title bar's Restore. */
  static boolean maximized(App<?> app) {
    return app != null && app.windowMaximized();
  }
}
