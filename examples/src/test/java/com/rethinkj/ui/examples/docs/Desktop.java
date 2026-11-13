package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.MessageBox;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowIcon;
import com.rethinkj.ui.window.WindowLoop;

/** The examples of the page about windows, icons, the taskbar and the system's dialogs. */
final class Desktop {

  private Desktop() {}

  /** Opens the Lights app in a frameless window with an icon of its own; not run by the tests. */
  static void open() throws Exception {
    // docs:start window
    Window.identity("com.example.Lights", "Lights"); // the taskbar groups by it
    WindowIcon icon = WindowIcon.of(
        size -> // drawn for each size asked
        AppIcon.appIcon(Theme.DARK, size, Colors.rgb(0xD97706), Symbol.FLASH));
    Window.Options options = Window.options().frameless().minimumSize(240, 160).icon(icon);
    App<Lights> app = new App<>(new Lights(), Lights::view);
    try (Window window = Window.open("Lights", 360, 240, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      new WindowLoop(window, app, presenter)
          .appearance(look -> {}) // the system's dark or light, accent
          .run();
    }
    // docs:end window
  }

  static void taskbar(Window window, Theme t) {
    // docs:start taskbar
    window.badge(
        WindowIcon.of(size -> AppIcon.count(t, size, t.accent().color(), 3)),
        "3 queries running"); // Windows only
    window.attention(Window.Attention.BRIEFLY); // flash the taskbar button
    // docs:end taskbar
  }

  static void message(App<?> app) {
    // docs:start message-box
    app.messageBox(
        MessageBox.warning("Close Workbench", "2 queries are still running.")
            .buttons("Close", "Cancel"),
        button -> {
          if (button == 0) {
            app.windows().all().forEach(w -> w.close());
          }
        },
        error -> {});
    // docs:end message-box
  }
}
