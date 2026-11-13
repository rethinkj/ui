package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.window.WindowEvent.PointerDown;
import com.rethinkj.ui.window.WindowEvent.PointerLeave;
import com.rethinkj.ui.window.WindowEvent.PointerMove;
import com.rethinkj.ui.window.WindowEvent.PointerUp;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;

/**
 * What a frameless window needs on Windows beyond SDL: rounded corners and the shadow, and the snap
 * layouts on the maximize part.
 *
 * <p>SDL gives a borderless, resizable window a caption and a thick frame in its style but makes
 * all of it client area. DWM then draws the shadow and, with the corner preference "round", rounds
 * the corners and draws a thin border, on Windows 11; earlier versions refuse the preference and
 * keep square corners.
 *
 * <p>Windows 11 shows the snap layouts when the pointer rests on what the window's {@code
 * WM_NCHITTEST} calls {@code HTMAXBUTTON}. SDL's hit test has no such answer, so a subclass of the
 * window procedure answers it before SDL does, where the view has its maximize part. That area is
 * then outside the client area: SDL reports no pointer events for it, and the subclass turns the
 * non-client messages into the same {@link WindowEvent}s instead. It keeps the button press from
 * the default window procedure, which would otherwise track it as a press on a caption button of
 * its own.
 *
 * <p>A right click on a drag area is non-client too, so SDL reports nothing, and the default window
 * procedure shows no system menu because its own hit test does not know the drag area. The
 * subclass reports the click, and {@link Window} opens the menu with {@code
 * SDL_ShowWindowSystemMenu} instead of delivering it.
 *
 * <p>The resize margin lies inside the window. A margin outside it, like the invisible border of
 * framed windows, would need a non-client frame from {@code WM_NCCALCSIZE}; SDL answers that
 * message for borderless windows with the whole window as client area, and its size and position
 * functions rely on that: with a frame of 8 pixels, {@code SDL_SetWindowSize(640, 400)} leaves 624
 * by 392 pixels of content and {@code SDL_SetWindowPosition} puts it 8 pixels to the right.
 */
final class Win32Frame {

  private static final long SUBCLASS_ID = 0x726A7569; // "rjui"

  /** Returned by {@link #message} to pass a message on. */
  private static final long PASS = Long.MIN_VALUE;

  private static final class Upcalls {
    static final MemorySegment PROC =
        Window.upcall(MethodHandles.lookup(), Win32Frame.class, "proc", Win32.SUBCLASS_PROC);
  }

  private final Window window;
  private final MemorySegment hwnd;
  private final MemorySegment point = Arena.ofAuto().allocate(8, 4);
  private final MemorySegment track = Arena.ofAuto().allocate(Win32.TRACKMOUSEEVENT_SIZE, 8);
  private boolean subclassed;
  private boolean overMaximize;
  private PointerMove lastMove;
  private boolean tracking;

  private Win32Frame(Window window, MemorySegment hwnd) {
    this.window = window;
    this.hwnd = hwnd;
  }

  static Win32Frame attach(Window window, MemorySegment hwnd) {
    Win32Frame frame = new Win32Frame(window, hwnd);
    try (Arena arena = Arena.ofConfined()) {
      // Fails before Windows 11, which has no rounded corners; the window keeps square ones.
      Win32.dwmSetWindowAttribute(
          hwnd,
          Win32.DWMWA_WINDOW_CORNER_PREFERENCE,
          arena.allocateFrom(JAVA_INT, Win32.DWMWCP_ROUND));
    }
    frame.subclassed = Win32.setWindowSubclass(hwnd, Upcalls.PROC, SUBCLASS_ID, window.id());
    return frame;
  }

  /**
   * Whether the pointer is on the maximize part, where this class reports it. Windows tells SDL that
   * the pointer left the client area only after it told this class that it is on the part, so
   * {@link Window} drops SDL's leave then.
   */
  boolean pointerOnMaximize() {
    return overMaximize;
  }

  /**
   * SDL reported a move in the client area, so the pointer is not on the maximize part, even if the
   * non-client leave for it comes later.
   */
  void pointerInClient() {
    overMaximize = false;
  }

  void detach() {
    if (subclassed) {
      subclassed = false;
      Win32.removeWindowSubclass(hwnd, Upcalls.PROC, SUBCLASS_ID);
    }
  }

  /** SUBCLASSPROC, in front of SDL's window procedure. */
  private static long proc(
      MemorySegment hwnd, int message, long wParam, long lParam, long id, long data) {
    try {
      Window window = Window.failed() ? null : Window.byId((int) data);
      Win32Frame frame = window == null ? null : window.win32();
      if (frame != null) {
        long result = frame.message(message, wParam, lParam);
        if (result != PASS) {
          return result;
        }
      }
    } catch (Throwable t) {
      Window.fail(t);
    }
    try {
      return Win32.defSubclassProc(hwnd, message, wParam, lParam);
    } catch (Throwable t) {
      Window.fail(t);
      return 0;
    }
  }

  private long message(int message, long wParam, long lParam) {
    switch (message) {
      // SDL makes all of a borderless window its client area, even of a minimized one, which
      // Windows shrinks to 160 by 28 pixels; restoring it, SDL then reports that size before the
      // real one, and the app lays out and draws a frame for it. A minimized framed window has no
      // client area: none here either.
      case Win32.WM_NCCALCSIZE -> {
        if (wParam != 0 && Win32.isIconic(hwnd)) {
          // NCCALCSIZE_PARAMS starts with the RECT that becomes the client area.
          MemorySegment rect = MemorySegment.ofAddress(lParam).reinterpret(16);
          rect.set(JAVA_INT, 8, rect.get(JAVA_INT, 0));
          rect.set(JAVA_INT, 12, rect.get(JAVA_INT, 4));
          return 0;
        }
      }
      case Win32.WM_NCHITTEST -> {
        toClient(lParam);
        float x = window.logical(point.get(JAVA_INT, 0));
        float y = window.logical(point.get(JAVA_INT, 4));
        if (window.zone(x, y) == Sdl.HITTEST_NORMAL && window.part(x, y) == WindowPart.MAXIMIZE) {
          return Win32.HTMAXBUTTON;
        }
      }
      case Win32.WM_NCMOUSEMOVE -> {
        if (wParam == Win32.HTMAXBUTTON) {
          toClient(lParam);
          // Windows repeats this while the pointer rests there; a move is only a move.
          PointerMove move = new PointerMove(x(), y());
          if (!overMaximize || !move.equals(lastMove)) {
            post(move);
          }
          lastMove = move;
          overMaximize = true;
          if (!tracking) {
            track.set(JAVA_INT, 0, (int) Win32.TRACKMOUSEEVENT_SIZE);
            track.set(JAVA_INT, 4, Win32.TME_LEAVE | Win32.TME_NONCLIENT);
            track.set(ADDRESS, 8, hwnd);
            track.set(JAVA_INT, 16, 0);
            tracking = Win32.trackMouseEvent(track);
          }
        } else if (overMaximize) {
          overMaximize = false;
          post(new PointerLeave());
        }
      }
      case Win32.WM_NCMOUSELEAVE -> {
        tracking = false;
        if (overMaximize) {
          overMaximize = false;
          post(new PointerLeave());
        }
      }
      case Win32.WM_NCLBUTTONDOWN, Win32.WM_NCLBUTTONDBLCLK -> {
        if (wParam == Win32.HTMAXBUTTON) {
          toClient(lParam);
          int clicks = message == Win32.WM_NCLBUTTONDBLCLK ? 2 : 1;
          post(new PointerDown(x(), y(), PointerButton.LEFT, clicks));
          return 0;
        }
      }
      case Win32.WM_NCLBUTTONUP -> {
        if (wParam == Win32.HTMAXBUTTON) {
          toClient(lParam);
          post(new PointerUp(x(), y(), PointerButton.LEFT));
          return 0;
        }
      }
      // A right click on a drag area. The default window procedure would decide by its own hit
      // test, which knows nothing of the drag area, and show no menu; Window shows it instead.
      case Win32.WM_NCRBUTTONDOWN -> {
        if (wParam == Win32.HTCAPTION) {
          toClient(lParam);
          post(new PointerDown(x(), y(), PointerButton.RIGHT, 1));
          return 0;
        }
      }
      case Win32.WM_NCRBUTTONUP -> {
        if (wParam == Win32.HTCAPTION) {
          toClient(lParam);
          post(new PointerUp(x(), y(), PointerButton.RIGHT));
          return 0;
        }
      }
      default -> {}
    }
    return PASS;
  }

  /**
   * Queues an event and wakes {@link Window#waitEvents}: SDL only returns from its wait for an event
   * in its own queue, and it knows nothing of these.
   */
  private void post(WindowEvent event) {
    window.post(event);
    Window.wake();
  }

  /** Writes the screen position in {@code lParam} into {@link #point}, in client coordinates. */
  private void toClient(long lParam) {
    point.set(JAVA_INT, 0, (short) lParam);
    point.set(JAVA_INT, 4, (short) (lParam >> 16));
    Win32.screenToClient(hwnd, point);
  }

  private float x() {
    return window.logical(point.get(JAVA_INT, 0));
  }

  private float y() {
    return window.logical(point.get(JAVA_INT, 4));
  }
}
