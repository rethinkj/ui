package com.rethinkj.ui.window;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Notifications;
import java.util.function.Consumer;

/**
 * The system's desktop notifications for an app in a window: {@link WindowLoop} sets this on each
 * app ({@link App#notifications}). Neither {@code libdbus} nor WinRT is loaded until the app first
 * shows a notification.
 */
final class SystemNotifications implements Notifications {

  private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

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
}
