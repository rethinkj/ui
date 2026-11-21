package com.rethinkj.ui.window;

import java.lang.foreign.MemorySegment;

/** A window on Windows: its shell (taskbar, icons, DWM) and, when frameless, its frame. */
final class Win32Backend implements WindowBackend {

  private final Window window;
  private final MemorySegment hwnd;
  private Win32Frame frame;
  private Win32Shell shell;
  private boolean cloaked; // from before it shows until its first frame is drawn
  private char highSurrogate; // of a WM_CHAR that SDL dropped, until its second half comes

  Win32Backend(Window window, MemorySegment hwnd) {
    this.window = window;
    this.hwnd = hwnd;
  }

  /** The frame of a frameless window, or null. */
  Win32Frame frame() {
    return frame;
  }

  /** The shell, or null until it is attached. */
  Win32Shell shell() {
    return shell;
  }

  @Override
  public void setUp() {
    shell = Win32Shell.attach(window, hwnd);
  }

  @Override
  public void frameless() {
    frame = Win32Frame.attach(window, hwnd);
  }

  @Override
  public void cloak() {
    if (shell != null) {
      // SDL shows the window black before the app can draw: DWM keeps it off the screen until
      // drawn() says that the first frame is in it.
      cloaked = shell.cloak(true);
    }
  }

  @Override
  public void noOpeningAnimation() {
    if (shell != null) {
      shell.transitions(false); // no opening animation before the first frame
    }
  }

  @Override
  public void pointerInClient() {
    if (frame != null) {
      frame.pointerInClient();
    }
  }

  @Override
  public boolean pointerOnMaximize() {
    return frame != null && frame.pointerOnMaximize();
  }

  @Override
  public boolean systemChars() {
    return true;
  }

  @Override
  public String charText(char c, boolean drop) {
    if (drop) {
      highSurrogate = 0;
      return null;
    }
    if (Character.isHighSurrogate(c)) {
      highSurrogate = c; // the second half comes in the next WM_CHAR
      return null;
    }
    String text = Character.isLowSurrogate(c) && highSurrogate != 0
        ? new String(new char[] {highSurrogate, c})
        : String.valueOf(c);
    highSurrogate = 0;
    return text;
  }

  @Override
  public void scaleChanged(float scale) {
    if (shell != null) {
      shell.scaleChanged(scale); // icons for the new scale
    }
  }

  @Override
  public boolean maximizedOrMinimized() {
    // While Windows maximizes a window, it moves and sizes it before SDL has the flag.
    return Win32.isZoomed(hwnd) || Win32.isIconic(hwnd);
  }

  @Override
  public int iconSize(float scale) {
    return shell != null ? Math.round(32 * scale) : 64;
  }

  @Override
  public void icon(WindowIcon icon, float scale) {
    if (shell != null) {
      shell.icon(icon, scale);
    }
  }

  @Override
  public boolean progress(Window.Progress state, float value) {
    return shell != null && shell.progress(state, value);
  }

  @Override
  public boolean badge(WindowIcon badge, String description, float scale) {
    return shell != null && shell.badge(badge, description, scale);
  }

  @Override
  public boolean frameDark(boolean dark) {
    return shell != null && shell.frameDark(dark);
  }

  @Override
  public boolean reveal(boolean open) {
    if (cloaked && open && shell.cloak(false)) {
      cloaked = false;
    }
    return !cloaked;
  }

  @Override
  public boolean detach() {
    if (frame != null) {
      frame.detach();
    }
    if (shell == null) {
      return false;
    }
    shell.detach();
    if (SystemDialogs.pending(window)) {
      return true; // see Window#close
    }
    shell.closeDialogs(); // a file dialog it owns answers as cancelled
    return false;
  }

  @Override
  public void closeDialogs() {
    shell.closeDialogs();
  }

  @Override
  public void destroyed() {
    if (shell != null) {
      shell.destroyIcons();
    }
  }
}
