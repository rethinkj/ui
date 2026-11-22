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
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** The examples of the page about windows, icons, the taskbar, the system's dialogs and browser. */
final class Desktop {

  private Desktop() {}

  /** Opens the Lights app in a frameless window with an icon of its own; not run by the tests. */
  static void open() {
    // docs:start window
    Window.identity("com.example.Lights", "Lights"); // the taskbar groups by it
    WindowIcon icon = WindowIcon.of(
        size -> // drawn for each size asked
        AppIcon.appIcon(size, Colors.rgb(0xD97706), Symbol.FLASH));
    Window.Options options = Window.options().frameless().minimumSize(240, 160).icon(icon);
    App<Lights> app = new App<>(new Lights(), Lights::view);
    try (Window window = Window.open("Lights", 360, 240, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
    // docs:end window
  }

  /** Opens the window and tells the updater once it shows; not run by the tests. */
  static void openAndConfirm(Updater updater) {
    // docs:start window-shown
    App<Lights> app = new App<>(new Lights(), Lights::view);
    try (Window window = Window.open("Lights", 360, 240, Window.options());
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop loop = new WindowLoop(window, app, presenter);
      loop.windows().of(app).onShown(updater::confirm); // the new version can be seen to run
      loop.run();
    }
    // docs:end window-shown
  }

  /** What installed a new version of the app, and keeps the old one until the new one runs. */
  interface Updater {
    void confirm();
  }

  // docs:start placement
  static final Path PLACEMENT = // where the app keeps its settings
      Path.of(System.getProperty("user.home"), ".lights", "window.txt");

  static void openWhereLeft() throws IOException {
    Window.Options options = Window.options();
    try {
      options.placement(Window.Placement.parse(Files.readString(PLACEMENT)));
    } catch (IOException | IllegalArgumentException _) {
      // the first start, or a line it cannot read: the window opens as given below
    }
    App<Lights> app = new App<>(new Lights(), Lights::view);
    try (Window window = Window.open("Lights", 360, 240, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter); // ends at the close request; the window is open
      if (window.isOpen()) {
        Files.createDirectories(PLACEMENT.getParent());
        Files.writeString(PLACEMENT, window.placement().toString()); // 120,80 360x240 display "…"
      }
    }
  }
  // docs:end placement

  static void taskbar(Window window, Theme theme) {
    // docs:start taskbar
    window.badge(
        WindowIcon.of(size -> AppIcon.count(size, theme.accent().color(), 3)),
        "3 queries running"); // Windows only
    window.attention(Window.Attention.BRIEFLY); // flash the taskbar button
    window.progress(Window.Progress.NORMAL, 0.4f); // a bar 40 % full in the button; Windows only
    window.progress(Window.Progress.NONE, 0); // gone again when the work ends
    // docs:end taskbar
  }

  // docs:start message-box
  static void message(App<?> app) {
    app.messageBox(
        MessageBox.warning("Close Workbench", "2 queries are still running.")
            .buttons("Close", "Cancel"),
        button -> answered(app, button),
        error -> {});
  }

  static void answered(App<?> app, int button) {
    if (button == 0) { // Close
      app.windows().all().forEach(window -> window.close());
    }
  }
  // docs:end message-box

  // docs:start open-uri
  static void website(App<?> app) {
    app.openUri(URI.create("https://ui.rethinkj.com"), error -> notOpened(app, error));
  }

  static void notOpened(App<?> app, String error) {
    app.messageBox(MessageBox.error("Cannot open the website", error), button -> {}, ignored -> {});
  }
  // docs:end open-uri
}
