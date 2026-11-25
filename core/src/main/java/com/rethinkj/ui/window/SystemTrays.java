package com.rethinkj.ui.window;

import com.rethinkj.ui.Tray;
import com.rethinkj.ui.Trays;
import com.rethinkj.ui.render.Pixels;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The system tray icon of an app in a window, through SDL 3.4's tray API ({@code SDL_tray.h}):
 * {@code Shell_NotifyIconW} with a native popup menu on Windows, and a StatusNotifierItem through
 * AppIndicator and GTK 3 on Linux. Nothing native is loaded until {@link #show} is called.
 *
 * <p>Updating an existing tray keeps the SDL tray handle and replaces its icon, tooltip and menu
 * entries in place so the icon does not flicker. Callbacks run on the windows' thread while SDL
 * pumps events, and {@link com.rethinkj.ui.App#tray} wraps each handler in {@link
 * com.rethinkj.ui.App#post}.
 */
final class SystemTrays implements Trays {

  private static final Map<Long, Action> ACTIONS = new ConcurrentHashMap<>();
  private static final AtomicLong IDS = new AtomicLong();

  private record Action(SystemTrays owner, Runnable onClick, Consumer<Boolean> onToggle) {}

  /** Upcall stub, made on first use and kept for the life of the process. */
  private static final class Upcalls {
    static final MemorySegment CALLBACK =
        Window.upcall(MethodHandles.lookup(), SystemTrays.class, "clicked", Sdl.TRAY_CALLBACK);
  }

  private final Window window;
  private final List<Long> registered = new ArrayList<>();
  private MemorySegment sdlTray;
  private MemorySegment sdlMenu;
  private WindowIcon currentIcon;
  private int currentIconSize;
  private String currentTooltip;
  private List<Tray.Entry> currentEntries = List.of();
  private boolean populating;
  private boolean closed;

  SystemTrays(Window window) {
    this.window = window;
  }

  @Override
  public void show(Tray tray, Consumer<String> failed) {
    if (closed || !window.isOpen()) {
      failed.accept("The window of this tray icon has closed.");
      return;
    }
    int size = iconSize(isWindows(), Sdl.getDisplayContentScale(Sdl.getPrimaryDisplay()));
    if (sdlTray == null) {
      MemorySegment surface = createSurface(tray.icon(), size);
      if (surface == null) {
        failed.accept(errorMessage("The tray icon image could not be created."));
        return;
      }
      MemorySegment created;
      try {
        created = Sdl.createTray(surface, tray.tooltip());
      } finally {
        Sdl.destroySurface(surface);
      }
      if (created.equals(MemorySegment.NULL)) {
        failed.accept(errorMessage("The system cannot show a tray icon here."));
        return;
      }
      MemorySegment menu = Sdl.createTrayMenu(created);
      if (menu.equals(MemorySegment.NULL)) {
        String error = errorMessage("The system cannot create a tray menu here.");
        Sdl.destroyTray(created);
        failed.accept(error);
        return;
      }
      sdlTray = created;
      sdlMenu = menu;
      currentIcon = tray.icon();
      currentIconSize = size;
      currentTooltip = tray.tooltip();
    } else {
      if (tray.icon() != currentIcon || size != currentIconSize) {
        applyIcon(tray.icon(), size);
      }
      if (!Objects.equals(tray.tooltip(), currentTooltip)) {
        Sdl.setTrayTooltip(sdlTray, tray.tooltip());
        currentTooltip = tray.tooltip();
      }
    }
    clearCallbacks();
    populating = true;
    try {
      if (!sameShape(currentEntries, tray.entries()) || !update(sdlMenu, tray.entries())) {
        clearCallbacks(); // of an update that stopped halfway
        MemorySegment[] existing = Sdl.getTrayEntries(sdlMenu);
        for (int i = existing.length - 1; i >= 0; i--) {
          Sdl.removeTrayEntry(existing[i]);
        }
        populate(sdlMenu, tray.entries());
      }
      currentEntries = tray.entries();
    } finally {
      populating = false;
    }
  }

  /**
   * Whether the menus have the same kinds of entries in the same places, so that {@link #update}
   * can keep SDL's entries: SDL's Windows tray takes a new command ID for each entry it inserts and
   * finds a clicked entry by the ID's low 16 bits only, so a menu rebuilt on every change would stop
   * answering after 65,535 entries.
   */
  private static boolean sameShape(List<Tray.Entry> old, List<Tray.Entry> entries) {
    if (old.size() != entries.size()) {
      return false;
    }
    for (int i = 0; i < old.size(); i++) {
      Tray.Entry a = old.get(i);
      Tray.Entry b = entries.get(i);
      if (a.getClass() != b.getClass()
          || (a instanceof Tray.Submenu x
              && b instanceof Tray.Submenu y
              && !sameShape(x.entries(), y.entries()))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Sets the labels, states and handlers of a menu of the same shape on SDL's entries; false if
   * SDL's menu lacks some, because inserting them failed, and it needs building again.
   */
  private boolean update(MemorySegment menu, List<Tray.Entry> entries) {
    MemorySegment[] handles =
        menu.equals(MemorySegment.NULL) ? new MemorySegment[0] : Sdl.getTrayEntries(menu);
    if (handles.length != entries.size()) {
      return false;
    }
    for (int i = 0; i < entries.size(); i++) {
      MemorySegment handle = handles[i];
      switch (entries.get(i)) {
        case Tray.Separator _ -> {}
        case Tray.Button b -> {
          Sdl.setTrayEntryLabel(handle, b.label());
          Sdl.setTrayEntryEnabled(handle, b.enabled());
          register(handle, new Action(this, b.onClick(), null));
        }
        case Tray.Checkbox c -> {
          Sdl.setTrayEntryLabel(handle, c.label());
          Sdl.setTrayEntryEnabled(handle, c.enabled());
          Sdl.setTrayEntryChecked(handle, c.checked());
          register(handle, new Action(this, null, c.onToggle()));
        }
        case Tray.Submenu sub -> {
          Sdl.setTrayEntryLabel(handle, sub.label());
          Sdl.setTrayEntryEnabled(handle, sub.enabled());
          if (!update(Sdl.getTraySubmenu(handle), sub.entries())) {
            return false;
          }
        }
      }
    }
    return true;
  }

  @Override
  public void remove() {
    clearCallbacks();
    if (sdlTray != null) {
      if (Window.started()) {
        Sdl.destroyTray(sdlTray);
      }
      sdlTray = null;
      sdlMenu = null;
      currentIcon = null;
      currentIconSize = 0;
      currentTooltip = null;
      currentEntries = List.of();
    }
  }

  /** Removes the tray icon when the window closes or the loop ends; idempotent. */
  void close() {
    closed = true;
    remove();
  }

  /** Draws the icon again when the primary display's scale changed its size (Windows). */
  void scaleChanged() {
    if (sdlTray == null || currentIcon == null || closed || !window.isOpen()) {
      return;
    }
    int size = iconSize(isWindows(), Sdl.getDisplayContentScale(Sdl.getPrimaryDisplay()));
    if (size != currentIconSize) {
      applyIcon(currentIcon, size);
    }
  }

  /**
   * The icon size in pixels for SDL's tray surface: Windows makes an {@code HICON} for the
   * notification area of the primary display's taskbar (16 pixels times its scale), while Linux
   * saves a PNG for AppIndicator, where 64 pixels stays sharp across panel scales.
   */
  static int iconSize(boolean windows, float scale) {
    return windows ? Math.max(16, Math.round(16 * scale)) : 64;
  }

  private static boolean isWindows() {
    return "windows".equals(Sdl.getCurrentVideoDriver());
  }

  private void applyIcon(WindowIcon icon, int size) {
    MemorySegment surface = createSurface(icon, size);
    if (surface == null) {
      return;
    }
    try {
      Sdl.setTrayIcon(sdlTray, surface);
      currentIcon = icon;
      currentIconSize = size;
    } finally {
      Sdl.destroySurface(surface);
    }
  }

  private static MemorySegment createSurface(WindowIcon icon, int size) {
    Pixels pixels = icon.image(size);
    MemorySegment surface = Sdl.createSurface(pixels);
    return surface.equals(MemorySegment.NULL) ? null : surface;
  }

  private void populate(MemorySegment menu, List<Tray.Entry> entries) {
    for (Tray.Entry entry : entries) {
      switch (entry) {
        case Tray.Separator _ -> Sdl.insertTrayEntryAt(menu, -1, null, 0);
        case Tray.Button b -> {
          int flags = Sdl.TRAYENTRY_BUTTON | (b.enabled() ? 0 : Sdl.TRAYENTRY_DISABLED);
          MemorySegment handle = Sdl.insertTrayEntryAt(menu, -1, b.label(), flags);
          if (!handle.equals(MemorySegment.NULL)) {
            register(handle, new Action(this, b.onClick(), null));
          }
        }
        case Tray.Checkbox c -> {
          int flags = Sdl.TRAYENTRY_CHECKBOX
              | (c.checked() ? Sdl.TRAYENTRY_CHECKED : 0)
              | (c.enabled() ? 0 : Sdl.TRAYENTRY_DISABLED);
          MemorySegment handle = Sdl.insertTrayEntryAt(menu, -1, c.label(), flags);
          if (!handle.equals(MemorySegment.NULL)) {
            register(handle, new Action(this, null, c.onToggle()));
          }
        }
        case Tray.Submenu s -> {
          int flags = Sdl.TRAYENTRY_SUBMENU | (s.enabled() ? 0 : Sdl.TRAYENTRY_DISABLED);
          MemorySegment handle = Sdl.insertTrayEntryAt(menu, -1, s.label(), flags);
          if (!handle.equals(MemorySegment.NULL)) {
            MemorySegment sub = Sdl.createTraySubmenu(handle);
            if (!sub.equals(MemorySegment.NULL)) {
              populate(sub, s.entries());
            }
          }
        }
      }
    }
  }

  private void register(MemorySegment entry, Action action) {
    long id = IDS.incrementAndGet();
    ACTIONS.put(id, action);
    registered.add(id);
    Sdl.setTrayEntryCallback(entry, Upcalls.CALLBACK, MemorySegment.ofAddress(id));
  }

  private void clearCallbacks() {
    for (long id : registered) {
      ACTIONS.remove(id);
    }
    registered.clear();
  }

  private static String errorMessage(String fallback) {
    String error = Sdl.getError();
    return error == null || error.isEmpty() ? fallback : error;
  }

  /** SDL_TrayCallback: runs on the windows' thread while SDL pumps events. */
  @SuppressWarnings("java:S1144") // an upcall target, found by name
  private static void clicked(MemorySegment userdata, MemorySegment entry) {
    try {
      Action action = ACTIONS.get(userdata.address());
      if (action == null || action.owner.populating) {
        return;
      }
      if (action.onClick != null) {
        action.onClick.run();
      } else if (action.onToggle != null) {
        action.onToggle.accept(Sdl.getTrayEntryChecked(entry));
      }
    } catch (Throwable t) {
      Window.fail(t);
    }
  }
}
