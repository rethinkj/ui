package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.MemorySegment;

/** A window on X11: the resize synchronization, the first frame's reveal and the background. */
final class X11Backend implements WindowBackend {

  /** The X server time of the last press of a button, or -1. */
  private static long press = -1;

  private final MemorySegment display;
  private final long xwindow;
  private long background = Long.MIN_VALUE; // as last set, none yet
  // _NET_WM_SYNC_REQUEST (see X11): the counter the window manager waits on during a resize, the
  // value it asked for, and whether the configure that follows the request has come.
  private long syncCounter;
  private long syncValue;
  private boolean syncAsked;
  private boolean syncConfigured;
  private boolean frameless;
  private boolean unseen; // transparent to the compositor until the first frame (see X11)

  X11Backend(MemorySegment display, long xwindow) {
    this.display = display;
    this.xwindow = xwindow;
  }

  @Override
  public void setUp() {
    syncCounter = X11.syncCounter(display, xwindow); // before the window shows
  }

  @Override
  public void frameless() {
    frameless = true;
  }

  @Override
  public void cloak() {
    if (frameless) {
      // On Mutter, mapping an undecorated X11 window at opacity 0 leaves MetaWindowActorX11's
      // cached paint volume without its drop shadow, and raising opacity later does not invalidate
      // it, causing shadow trails when the window is moved. Keep it opaque and give it a solid
      // background pixel instead of SDL's None so it never shows other windows' content before the
      // first frame.
      background(Sdl.getSystemTheme() == Sdl.SYSTEM_THEME_LIGHT ? 0xFFFFFFFF : 0xFF000000);
      return;
    }
    X11.opacity(display, xwindow, 0);
    unseen = true;
  }

  @Override
  public long handedPress() {
    return press;
  }

  @Override
  public boolean movesLater() {
    return true;
  }

  @Override
  public void background(int argb) {
    if (argb != background) {
      background = argb;
      X11.background(display, xwindow, argb);
    }
  }

  @Override
  public void drawn() {
    if (unseen) {
      unseen = false;
      X11.opacity(display, xwindow, 0xFFFFFFFFL);
    }
    if (syncConfigured) {
      syncAsked = false;
      syncConfigured = false;
      X11.setCounter(display, syncCounter, syncValue);
    }
  }

  @Override
  public boolean detach() {
    if (syncCounter != 0) {
      X11.destroyCounter(display, syncCounter);
    }
    return false;
  }

  /**
   * SDL_X11EventHook, through {@code Window.x11Event}: notes the window manager's requests to
   * synchronize a resize, and the time of each press.
   */
  static boolean event(MemorySegment xevent) {
    try {
      MemorySegment e = xevent.reinterpret(X11.EVENT_SIZE);
      int type = e.get(JAVA_INT, X11.EVENT_TYPE);
      long time = X11.press(e);
      if (time >= 0) { // before SDL handles it: its HIT_TEST says nothing of its time
        press = time;
      }
      if (type == X11.CLIENT_MESSAGE) {
        long value = X11.syncRequest(e);
        X11Backend window = value < 0 ? null : of(e.get(JAVA_LONG, X11.CLIENT_WINDOW));
        if (window != null) {
          window.syncValue = value;
          window.syncAsked = true;
          window.syncConfigured = false;
        }
      } else if (type == X11.CONFIGURE_NOTIFY) {
        X11Backend window = of(e.get(JAVA_LONG, X11.CONFIGURE_WINDOW));
        if (window != null && window.syncAsked) {
          window.syncConfigured = true;
        }
      }
    } catch (Throwable t) {
      Window.fail(t);
    }
    return true; // SDL handles every event as before
  }

  private static X11Backend of(long xwindow) {
    for (Window window : Window.all()) {
      if (window.backend() instanceof X11Backend x && x.xwindow == xwindow) {
        return x;
      }
    }
    return null;
  }
}
