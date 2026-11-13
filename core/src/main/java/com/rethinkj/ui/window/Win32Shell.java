package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.rethinkj.ui.render.Pixels;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * What every window needs on Windows beyond SDL, framed or not: its icons in the sizes the system
 * asks for, the badge on its taskbar button, and the messages that say that the system's colours
 * changed. A subclass of the window procedure of its own, beside {@link Win32Frame}'s, sees those
 * messages after SDL has handled them.
 *
 * <p>SDL's {@code SDL_SetWindowIcon} makes one icon of the surface's base image and gives it to
 * the window as both its small and its big icon; it leaves out the alternate images. Windows asks
 * for a small icon of 16 pixels times the scale (title bar, system menu, the taskbar's small
 * buttons) and a big one of 32 pixels times the scale (Alt+Tab and the taskbar, which scales it to
 * its 24 pixels), so this class sets both with {@code WM_SETICON} after SDL, from the image of
 * each size, and again when the scale changes.
 *
 * <p>The badge is an overlay icon, {@code ITaskbarList3::SetOverlayIcon}, through a taskbar list
 * object of the windows' thread, on which SDL has initialised COM. The taskbar takes it only once
 * it has made the window's button, which it says with the registered message {@code
 * TaskbarButtonCreated}; a badge set before that waits for it, and the message comes again when
 * Explorer restarts, which also clears the badge, so it is set again.
 */
final class Win32Shell {

  private static final long SUBCLASS_ID = 0x726A7573; // "rjus"

  private static final class Upcalls {
    static final MemorySegment PROC =
        Window.upcall(MethodHandles.lookup(), Win32Shell.class, "proc", Win32.SUBCLASS_PROC);
  }

  /** The registered message {@code TaskbarButtonCreated}, or 0 before the first window. */
  private static int taskbarButtonCreated;
  /** The ITaskbarList3 of the windows' thread, made on first use; released before SDL quits. */
  private static MemorySegment taskbar = MemorySegment.NULL;
  private static boolean taskbarFailed;

  private final Window window;
  private final MemorySegment hwnd;
  private boolean subclassed;
  private WindowIcon icon;
  private MemorySegment smallIcon = MemorySegment.NULL;
  private MemorySegment bigIcon = MemorySegment.NULL;
  private boolean buttonCreated;
  private WindowIcon badge;
  private String badgeDescription;
  private MemorySegment badgeIcon = MemorySegment.NULL;
  /** The frame's darkness the app asked for, or null to follow the system as SDL does. */
  private Boolean frameDark;

  private Win32Shell(Window window, MemorySegment hwnd) {
    this.window = window;
    this.hwnd = hwnd;
  }

  /** Before the window is shown, so that the taskbar's message about its button arrives. */
  static Win32Shell attach(Window window, MemorySegment hwnd) {
    if (taskbarButtonCreated == 0) {
      taskbarButtonCreated = Win32.registerWindowMessage("TaskbarButtonCreated");
    }
    Win32Shell shell = new Win32Shell(window, hwnd);
    shell.subclassed = Win32.setWindowSubclass(hwnd, Upcalls.PROC, SUBCLASS_ID, window.id());
    return shell;
  }

  /** Sets the window's small and big icon from {@code icon} at the window's scale. */
  void icon(WindowIcon icon, float scale) {
    this.icon = icon;
    MemorySegment small = create(icon.image(Math.round(16 * scale)));
    MemorySegment big = create(icon.image(Math.round(32 * scale)));
    if (small.equals(MemorySegment.NULL) || big.equals(MemorySegment.NULL)) {
      destroy(small);
      destroy(big);
      return; // SDL's icon stays
    }
    Win32.sendMessage(hwnd, Win32.WM_SETICON, Win32.ICON_SMALL, small.address());
    Win32.sendMessage(hwnd, Win32.WM_SETICON, Win32.ICON_BIG, big.address());
    destroy(smallIcon);
    destroy(bigIcon);
    smallIcon = small;
    bigIcon = big;
  }

  /**
   * Shows {@code badge} on the taskbar button, or none if it is null.
   *
   * @return false if the taskbar list cannot be made or refused the badge
   */
  boolean badge(WindowIcon badge, String description, float scale) {
    this.badge = badge;
    this.badgeDescription = description;
    return applyBadge(scale);
  }

  /**
   * Draws the frame's border, and a framed window's caption, dark or light, whatever the system
   * is set to ({@code DWMWA_USE_IMMERSIVE_DARK_MODE}). SDL sets the same for the system's setting
   * whenever that changes, so this is set again after SDL has handled the change.
   *
   * @return false if DWM refused it, as before Windows 10 20H1
   */
  boolean frameDark(boolean dark) {
    frameDark = dark;
    return applyFrameDark();
  }

  /**
   * Whether DWM animates the window as it shows, hides, minimizes and restores ({@code
   * DWMWA_TRANSITIONS_FORCEDISABLED} off or on). A window that opens under the pointer during a
   * drag turns it off: its open animation would zoom an empty frame in before the first frame.
   *
   * @return false if DWM refused it
   */
  boolean transitions(boolean on) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment value = arena.allocateFrom(JAVA_INT, on ? 0 : 1);
      return Win32.dwmSetWindowAttribute(hwnd, Win32.DWMWA_TRANSITIONS_FORCEDISABLED, value)
          == Win32.S_OK;
    }
  }

  private boolean applyFrameDark() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment value = arena.allocateFrom(JAVA_INT, frameDark ? 1 : 0);
      return Win32.dwmSetWindowAttribute(hwnd, Win32.DWMWA_USE_IMMERSIVE_DARK_MODE, value)
          == Win32.S_OK;
    }
  }

  void scaleChanged(float scale) {
    if (icon != null) {
      icon(icon, scale);
    }
    if (badge != null) {
      applyBadge(scale);
    }
  }

  /**
   * Closes the system's dialogs the window owns, before SDL destroys it: a file dialog that SDL
   * shows on a thread of its own outlives its owner's destruction, stays on the screen without an
   * owner and never answers. Asked to close, it answers as cancelled.
   */
  void closeDialogs() {
    for (MemorySegment dialog : Win32.ownedDialogs(hwnd)) {
      Win32.postClose(dialog);
    }
  }

  /** Removes the subclass; before SDL destroys the window. */
  void detach() {
    if (subclassed) {
      subclassed = false;
      Win32.removeWindowSubclass(hwnd, Upcalls.PROC, SUBCLASS_ID);
    }
  }

  /** Frees the icons; after SDL destroyed the window, which showed them until then. */
  void destroyIcons() {
    destroy(smallIcon);
    destroy(bigIcon);
    destroy(badgeIcon);
    smallIcon = bigIcon = badgeIcon = MemorySegment.NULL;
  }

  /** Releases the taskbar list; before SDL quits, which uninitialises COM. */
  static void releaseTaskbar() {
    if (!taskbar.equals(MemorySegment.NULL)) {
      Win32.comCall(taskbar, Win32.VTBL_RELEASE);
      taskbar = MemorySegment.NULL;
    }
    taskbarFailed = false;
  }

  private boolean applyBadge(float scale) {
    if (!buttonCreated) {
      return taskbar() != null; // the message about the button sets it
    }
    MemorySegment list = taskbar();
    if (list == null) {
      return false;
    }
    MemorySegment made = badge == null ? MemorySegment.NULL
        : create(badge.image(Math.round(16 * scale)));
    int hr = Win32.taskbarSetOverlayIcon(list, hwnd, made, badge == null ? null : badgeDescription);
    destroy(badgeIcon);
    badgeIcon = made;
    return hr == Win32.S_OK;
  }

  private static MemorySegment taskbar() {
    if (taskbar.equals(MemorySegment.NULL) && !taskbarFailed) {
      int[] hr = new int[1];
      MemorySegment list = Win32.coCreateInstance(
          Win32.CLSID_TASKBAR_LIST, Win32.IID_ITASKBAR_LIST3, hr);
      if (list.equals(MemorySegment.NULL)
          || Win32.comCall(list, Win32.VTBL_HR_INIT) != Win32.S_OK) {
        if (!list.equals(MemorySegment.NULL)) {
          Win32.comCall(list, Win32.VTBL_RELEASE);
        }
        taskbarFailed = true;
        return null;
      }
      taskbar = list;
    }
    return taskbar.equals(MemorySegment.NULL) ? null : taskbar;
  }

  /** SUBCLASSPROC, in front of SDL's window procedure; acts after SDL has had the message. */
  private static long proc(
      MemorySegment hwnd, int message, long wParam, long lParam, long id, long data) {
    long result;
    try {
      result = Win32.defSubclassProc(hwnd, message, wParam, lParam);
    } catch (Throwable t) {
      Window.fail(t);
      return 0;
    }
    try {
      Window window = Window.failed() ? null : Window.byId((int) data);
      Win32Shell shell = window == null ? null : window.shell();
      if (shell != null) {
        shell.message(message, wParam);
      }
    } catch (Throwable t) {
      Window.fail(t);
    }
    return result;
  }

  private void message(int message, long wParam) {
    if (message == Win32.WM_CHAR) {
      window.systemChar((char) wParam); // what SDL dropped while text input was off
      return;
    }
    if (message == Win32.WM_KEYDOWN && wParam == Win32.VK_PACKET) {
      window.packetKey(); // its character, which follows, belongs to no key SDL reported
      return;
    }
    if (message == taskbarButtonCreated && taskbarButtonCreated != 0) {
      buttonCreated = true;
      if (icon != null) {
        // The taskbar asked for the icon when it made the button, and took the class's, which is
        // java.exe's, if the window's thread did not answer in time, as while an app sets up its
        // GPU after opening the window. A new icon makes it ask again.
        icon(icon, window.scale());
      }
      if (badge != null) {
        applyBadge(window.scale());
      }
      return;
    }
    switch (message) {
      case Win32.WM_SETTINGCHANGE, Win32.WM_SYSCOLORCHANGE,
          Win32.WM_DWMCOLORIZATIONCOLORCHANGED -> {
        if (frameDark != null) {
          applyFrameDark(); // SDL has set the system's again
        }
        Window.appearanceMayHaveChanged();
      }
      default -> {}
    }
  }

  // ---- The system's colours

  /** The accent colour as 0xFFRRGGBB, or 0 if Windows has none. */
  static int accent() {
    // The colour chosen in Settings, as 0xAABBGGRR. DwmGetColorizationColor gives the frame's
    // colorization colour, the same blue with an alpha of its own, and is only the fallback.
    Integer abgr = Win32.userDword("Software\\Microsoft\\Windows\\DWM", "AccentColor");
    if (abgr != null) {
      int c = abgr;
      return 0xFF000000 | (c & 0xFF) << 16 | (c & 0xFF00) | (c >>> 16 & 0xFF);
    }
    Integer argb = Win32.dwmColorizationColor();
    return argb == null ? 0 : 0xFF000000 | argb;
  }

  static boolean highContrast() {
    return Win32.highContrast();
  }

  // ---- Icons from pixels

  private static MemorySegment create(Pixels image) {
    byte[] resource = iconResource(image);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bits = arena.allocateFrom(JAVA_BYTE, resource);
      return Win32.createIconFromResource(bits, image.width(), image.height());
    }
  }

  private static void destroy(MemorySegment icon) {
    if (!icon.equals(MemorySegment.NULL)) {
      Win32.destroyIcon(icon);
    }
  }

  /**
   * The image as the resource of an icon, as an .ico file holds one: a {@code BITMAPINFOHEADER}
   * of twice the height, the colours as BGRA with straight alpha from the bottom row up, and the
   * AND mask, one bit per pixel in rows of whole DWORDs, set where the image is transparent.
   */
  static byte[] iconResource(Pixels image) {
    int w = image.width();
    int h = image.height();
    int maskRow = (w + 31) / 32 * 4;
    ByteBuffer b = ByteBuffer.allocate(Win32.BITMAPINFOHEADER_SIZE + w * h * 4 + maskRow * h)
        .order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(Win32.BITMAPINFOHEADER_SIZE).putInt(w).putInt(2 * h)
        .putShort((short) 1).putShort((short) 32) // planes, bits per pixel
        .putInt(0).putInt(w * h * 4) // BI_RGB, the size of the colours
        .putInt(0).putInt(0).putInt(0).putInt(0);
    byte[] rgba = image.rgba();
    for (int y = h - 1; y >= 0; y--) {
      for (int x = 0; x < w; x++) {
        int i = (y * w + x) * 4;
        b.put(rgba[i + 2]).put(rgba[i + 1]).put(rgba[i]).put(rgba[i + 3]);
      }
    }
    int mask = b.position();
    for (int y = h - 1, row = 0; y >= 0; y--, row++) {
      for (int x = 0; x < w; x++) {
        if (rgba[(y * w + x) * 4 + 3] == 0) {
          int at = mask + row * maskRow + x / 8;
          b.put(at, (byte) (b.get(at) | 0x80 >>> (x % 8)));
        }
      }
    }
    return b.array();
  }
}
