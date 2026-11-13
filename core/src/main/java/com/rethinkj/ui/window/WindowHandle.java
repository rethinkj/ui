package com.rethinkj.ui.window;

import java.lang.foreign.MemorySegment;

/**
 * The system's handles of a window, which a GPU API needs to create a surface for it. Which one a
 * window has depends on the system and, on Linux, on whether the session runs X11 or Wayland.
 */
public sealed interface WindowHandle {

  /** A window on Windows: {@code HWND} and the {@code HINSTANCE} of the module that created it. */
  record Win32(MemorySegment hwnd, MemorySegment hinstance) implements WindowHandle {}

  /** A window on X11: the Xlib {@code Display *} and the {@code Window} XID. */
  record Xlib(MemorySegment display, long window) implements WindowHandle {}

  /** A window on Wayland: the {@code wl_display *} and the {@code wl_surface *}. */
  record Wayland(MemorySegment display, MemorySegment surface) implements WindowHandle {}
}
