package com.rethinkj.ui.window;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Notifications;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The system's desktop notifications for an app in a window: {@link WindowLoop} sets this on each
 * app ({@link App#notifications}). Neither {@code libdbus} nor WinRT is loaded until the app first
 * shows a notification.
 */
final class SystemNotifications implements Notifications {

  private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

  private static WindowIcon writtenIcon; // the icon last written to its file, and that file
  private static Path writtenFile;

  private final Window window;
  private final App<?> app;

  SystemNotifications(Window window, App<?> app) {
    this.window = window;
    this.app = app;
  }

  @Override
  public void show(String title, String body, Runnable clicked, Consumer<String> failed) {
    Runnable onClick = () -> {
      // Unminimize before App.notify's posted handle.raise() brings the window to the front.
      app.post(() -> {
        if (window.isOpen() && window.isMinimized()) {
          window.restore();
        }
      });
      clicked.run();
    };
    if (WINDOWS) {
      Win32Notifications.show(window, title, body, onClick, failed);
    } else {
      LinuxNotifications.show(window, title, body, onClick, failed);
    }
  }

  /**
   * The window's icon as a PNG of 64 pixels in the user's cache ({@code %LOCALAPPDATA%} on
   * Windows, {@code $XDG_CACHE_HOME} or {@code ~/.cache} on Linux), for the system to show beside
   * a notification; written again only when the icon changes. Null if the window has no icon or
   * the file cannot be written.
   */
  static synchronized Path iconFile(Window window, String id) {
    WindowIcon icon = window.icon();
    if (icon == null || id == null) {
      return null;
    }
    if (icon == writtenIcon) {
      return writtenFile;
    }
    String cache = System.getenv(WINDOWS ? "LOCALAPPDATA" : "XDG_CACHE_HOME");
    Path folder;
    if (cache != null && !cache.isEmpty()) {
      folder = Path.of(cache);
    } else if (WINDOWS) {
      return null;
    } else {
      folder = Path.of(System.getProperty("user.home"), ".cache");
    }
    Path file = folder
        .resolve("rethinkj-ui")
        .resolve("icons")
        .resolve(id.replaceAll("[^A-Za-z0-9._-]", "_") + ".png")
        .toAbsolutePath();
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, Png.encode(icon.image(64)));
    } catch (IOException _) {
      return null;
    }
    writtenIcon = icon;
    writtenFile = file;
    return file;
  }
}
