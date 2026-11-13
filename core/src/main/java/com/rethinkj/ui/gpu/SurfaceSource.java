package com.rethinkj.ui.gpu;

/**
 * The native window a {@link Surface} draws into, as the window system's handles. The window
 * layer reads them from SDL's window properties; they are addresses, or an XID for an X11 window.
 */
public sealed interface SurfaceSource {

  /** A Win32 window: its {@code HWND} and the {@code HINSTANCE} of the module that created it. */
  record WindowsHwnd(long hinstance, long hwnd) implements SurfaceSource {
    public WindowsHwnd {
      Surface.requireHandle(hwnd, "hwnd");
    }
  }

  /** An X11 window through Xlib: the {@code Display *} and the window's XID. */
  record XlibWindow(long display, long window) implements SurfaceSource {
    public XlibWindow {
      Surface.requireHandle(display, "display");
      Surface.requireHandle(window, "window");
    }
  }

  /** A Wayland surface: the {@code wl_display *} and the {@code wl_surface *}. */
  record WaylandSurface(long display, long surface) implements SurfaceSource {
    public WaylandSurface {
      Surface.requireHandle(display, "display");
      Surface.requireHandle(surface, "surface");
    }
  }
}
