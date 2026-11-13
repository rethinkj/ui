package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Handwritten bindings for the Win32, DWM and common-controls calls that the window makes on the
 * HWND SDL created. Only Windows touches this class; its handles are bound on first use.
 */
final class Win32 {

  private Win32() {}

  static final int WM_NCCALCSIZE = 0x0083;
  static final int WM_NCHITTEST = 0x0084;
  static final int WM_NCMOUSEMOVE = 0x00A0;
  static final int WM_NCLBUTTONDOWN = 0x00A1;
  static final int WM_NCLBUTTONUP = 0x00A2;
  static final int WM_NCLBUTTONDBLCLK = 0x00A3;
  static final int WM_NCRBUTTONDOWN = 0x00A4;
  static final int WM_NCRBUTTONUP = 0x00A5;
  static final int WM_NCMOUSELEAVE = 0x02A2;

  static final int HTCAPTION = 2;
  static final int HTMAXBUTTON = 9;

  static final int TME_LEAVE = 0x02;
  static final int TME_NONCLIENT = 0x10;

  static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
  static final int DWMWCP_ROUND = 2;

  /** {@code TRACKMOUSEEVENT}: DWORD cbSize, DWORD dwFlags, HWND hwndTrack, DWORD dwHoverTime. */
  static final long TRACKMOUSEEVENT_SIZE = 24;

  /**
   * {@code LRESULT (*SUBCLASSPROC)(HWND, UINT msg, WPARAM, LPARAM, UINT_PTR idSubclass, DWORD_PTR
   * refData)}
   */
  static final FunctionDescriptor SUBCLASS_PROC =
      FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);

  static final FunctionDescriptor SCREEN_TO_CLIENT = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor TRACK_MOUSE_EVENT = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor IS_ICONIC = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor DWM_SET_WINDOW_ATTRIBUTE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor SET_WINDOW_SUBCLASS =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG);
  static final FunctionDescriptor REMOVE_WINDOW_SUBCLASS =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);
  static final FunctionDescriptor DEF_SUBCLASS_PROC =
      FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);

  private static final class H {
    static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
    static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi", Arena.global());
    static final SymbolLookup COMCTL32 = SymbolLookup.libraryLookup("comctl32", Arena.global());

    static final MethodHandle SCREEN_TO_CLIENT_H =
        bind(USER32, "ScreenToClient", SCREEN_TO_CLIENT);
    static final MethodHandle TRACK_MOUSE_EVENT_H =
        bind(USER32, "TrackMouseEvent", TRACK_MOUSE_EVENT);
    static final MethodHandle IS_ICONIC_H = bind(USER32, "IsIconic", IS_ICONIC);
    static final MethodHandle DWM_SET_WINDOW_ATTRIBUTE_H =
        bind(DWMAPI, "DwmSetWindowAttribute", DWM_SET_WINDOW_ATTRIBUTE);
    static final MethodHandle SET_WINDOW_SUBCLASS_H =
        bind(COMCTL32, "SetWindowSubclass", SET_WINDOW_SUBCLASS);
    static final MethodHandle REMOVE_WINDOW_SUBCLASS_H =
        bind(COMCTL32, "RemoveWindowSubclass", REMOVE_WINDOW_SUBCLASS);
    static final MethodHandle DEF_SUBCLASS_PROC_H =
        bind(COMCTL32, "DefSubclassProc", DEF_SUBCLASS_PROC);

    private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor d) {
      MemorySegment symbol = library.find(name)
          .orElseThrow(() -> new UnsatisfiedLinkError("Windows has no function " + name));
      return Linker.nativeLinker().downcallHandle(symbol, d);
    }
  }

  /** Converts the two ints at {@code point} from screen to client coordinates. */
  static boolean screenToClient(MemorySegment hwnd, MemorySegment point) {
    try {
      return (int) H.SCREEN_TO_CLIENT_H.invokeExact(hwnd, point) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  static boolean trackMouseEvent(MemorySegment trackMouseEvent) {
    try {
      return (int) H.TRACK_MOUSE_EVENT_H.invokeExact(trackMouseEvent) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** Whether the window is minimized. */
  static boolean isIconic(MemorySegment hwnd) {
    try {
      return (int) H.IS_ICONIC_H.invokeExact(hwnd) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** @return the HRESULT */
  static int dwmSetWindowAttribute(MemorySegment hwnd, int attribute, MemorySegment value) {
    try {
      return (int) H.DWM_SET_WINDOW_ATTRIBUTE_H.invokeExact(
          hwnd, attribute, value, (int) value.byteSize());
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  static boolean setWindowSubclass(MemorySegment hwnd, MemorySegment proc, long id, long data) {
    try {
      return (int) H.SET_WINDOW_SUBCLASS_H.invokeExact(hwnd, proc, id, data) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  static boolean removeWindowSubclass(MemorySegment hwnd, MemorySegment proc, long id) {
    try {
      return (int) H.REMOVE_WINDOW_SUBCLASS_H.invokeExact(hwnd, proc, id) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  static long defSubclassProc(MemorySegment hwnd, int message, long wParam, long lParam) {
    try {
      return (long) H.DEF_SUBCLASS_PROC_H.invokeExact(hwnd, message, wParam, lParam);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }
}
