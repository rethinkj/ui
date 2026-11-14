package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Handwritten bindings for the Xlib and XSync calls the window makes on the X11 window SDL
 * created. Only X11 touches this class; it finds libX11 and libXext, which SDL has loaded, on
 * first use.
 *
 * <p>A resize of a window that draws with Vulkan is not in step with the window manager by
 * itself: the window manager resizes the window as the pointer moves, the app builds a swapchain
 * for a size the window has left by then, and the compositor shows the window at sizes the app
 * has not drawn. With {@code _NET_WM_SYNC_REQUEST} (EWMH) the window manager asks the app before
 * each step, holds the window's picture until the app has drawn the step, and then goes on. SDL
 * answers it only for OpenGL windows, so the window answers it itself ({@link
 * Window#drawn()}). What a resize uncovers before the app has drawn shows the window's
 * background, which SDL leaves {@code None}: whatever lay there, other windows' content
 * included.
 */
final class X11 {

  private X11() {}

  /** {@code int XChangeWindowAttributes(Display *, Window, unsigned long valuemask, XSetWindowAttributes *)} */
  static final FunctionDescriptor CHANGE_WINDOW_ATTRIBUTES =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS);
  /** {@code int XFlush(Display *)} */
  static final FunctionDescriptor FLUSH = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  /** {@code Atom XInternAtom(Display *, const char *name, Bool only_if_exists)} */
  static final FunctionDescriptor INTERN_ATOM =
      FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT);
  /** {@code Status XGetWMProtocols(Display *, Window, Atom **protocols, int *count)} */
  static final FunctionDescriptor GET_WM_PROTOCOLS =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS);
  /** {@code Status XSetWMProtocols(Display *, Window, Atom *protocols, int count)} */
  static final FunctionDescriptor SET_WM_PROTOCOLS =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT);
  /**
   * {@code int XChangeProperty(Display *, Window, Atom property, Atom type, int format, int mode,
   * const unsigned char *data, int nelements)}
   */
  static final FunctionDescriptor CHANGE_PROPERTY = FunctionDescriptor.of(
      JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
  /** {@code int XFree(void *)} */
  static final FunctionDescriptor FREE = FunctionDescriptor.of(JAVA_INT, ADDRESS);

  /** {@code XSyncValue}: {@code int hi; unsigned int lo;}, passed by value. */
  static final StructLayout SYNC_VALUE =
      MemoryLayout.structLayout(JAVA_INT.withName("hi"), JAVA_INT.withName("lo"));
  /** {@code XSyncCounter XSyncCreateCounter(Display *, XSyncValue initial)} */
  static final FunctionDescriptor SYNC_CREATE_COUNTER =
      FunctionDescriptor.of(JAVA_LONG, ADDRESS, SYNC_VALUE);
  /** {@code Status XSyncSetCounter(Display *, XSyncCounter, XSyncValue)} */
  static final FunctionDescriptor SYNC_SET_COUNTER =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, SYNC_VALUE);
  /** {@code Status XSyncDestroyCounter(Display *, XSyncCounter)} */
  static final FunctionDescriptor SYNC_DESTROY_COUNTER =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG);

  static final long CW_BACK_PIXEL = 1L << 1;
  static final long CW_BIT_GRAVITY = 1L << 4;
  static final int NORTH_WEST_GRAVITY = 1;
  static final long XA_CARDINAL = 6;
  static final int PROP_MODE_REPLACE = 0;

  /** {@code XSetWindowAttributes} on 64 bits: its size and the offsets of the fields set here. */
  static final long SET_WINDOW_ATTRIBUTES_SIZE = 112;

  static final long BACKGROUND_PIXEL = 8;
  static final long BIT_GRAVITY = 32;

  /** {@code XEvent} on 64 bits: its size, and the offsets of the fields read here. */
  static final long EVENT_SIZE = 192;

  static final long EVENT_TYPE = 0;
  static final int CONFIGURE_NOTIFY = 22;
  static final int CLIENT_MESSAGE = 33;
  /** {@code XConfigureEvent.window} */
  static final long CONFIGURE_WINDOW = 40;
  /** {@code XClientMessageEvent}: window, message_type, format and data.l[0], each long. */
  static final long CLIENT_WINDOW = 32;

  static final long CLIENT_MESSAGE_TYPE = 40;
  static final long CLIENT_FORMAT = 48;
  static final long CLIENT_DATA = 56;

  // The atoms of the one display SDL opens, interned with the first window's counter.
  private static long wmProtocols;
  private static long syncRequest;

  private static final class H {
    static final SymbolLookup X11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());

    static final MethodHandle CHANGE_WINDOW_ATTRIBUTES_H =
        bind(X11, "XChangeWindowAttributes", CHANGE_WINDOW_ATTRIBUTES);
    static final MethodHandle FLUSH_H = bind(X11, "XFlush", FLUSH);
    static final MethodHandle INTERN_ATOM_H = bind(X11, "XInternAtom", INTERN_ATOM);
    static final MethodHandle GET_WM_PROTOCOLS_H = bind(X11, "XGetWMProtocols", GET_WM_PROTOCOLS);
    static final MethodHandle SET_WM_PROTOCOLS_H = bind(X11, "XSetWMProtocols", SET_WM_PROTOCOLS);
    static final MethodHandle CHANGE_PROPERTY_H = bind(X11, "XChangeProperty", CHANGE_PROPERTY);
    static final MethodHandle FREE_H = bind(X11, "XFree", FREE);

    static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor d) {
      MemorySegment symbol =
          library.find(name).orElseThrow(() -> new UnsatisfiedLinkError("No function " + name));
      return Linker.nativeLinker().downcallHandle(symbol, d);
    }
  }

  private static final class Sync {
    static final SymbolLookup XEXT = SymbolLookup.libraryLookup("libXext.so.6", Arena.global());

    static final MethodHandle CREATE_COUNTER_H =
        H.bind(XEXT, "XSyncCreateCounter", SYNC_CREATE_COUNTER);
    static final MethodHandle SET_COUNTER_H = H.bind(XEXT, "XSyncSetCounter", SYNC_SET_COUNTER);
    static final MethodHandle DESTROY_COUNTER_H =
        H.bind(XEXT, "XSyncDestroyCounter", SYNC_DESTROY_COUNTER);
  }

  /**
   * Has the X server fill what a resize uncovers with this colour, and keep what the window shows
   * at its top left, until the app draws the new size.
   */
  static void background(MemorySegment display, long window, int rgb) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment attributes = arena.allocate(SET_WINDOW_ATTRIBUTES_SIZE);
      attributes.set(JAVA_LONG, BACKGROUND_PIXEL, rgb & 0xFFFFFFL);
      attributes.set(JAVA_INT, BIT_GRAVITY, NORTH_WEST_GRAVITY);
      int unused = (int) H.CHANGE_WINDOW_ATTRIBUTES_H.invokeExact(
          display, window, CW_BACK_PIXEL | CW_BIT_GRAVITY, attributes);
      unused = (int) H.FLUSH_H.invokeExact(display);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /**
   * Makes the counter the window manager waits on during a resize and adds {@code
   * _NET_WM_SYNC_REQUEST} to the window's protocols. Before the window is shown: Mutter reads
   * the counter only then.
   *
   * @return the counter, or 0 where the X server or libXext offer none
   */
  static long syncCounter(MemorySegment display, long window) {
    try (Arena arena = Arena.ofConfined()) {
      long counter;
      try {
        counter = (long) Sync.CREATE_COUNTER_H.invokeExact(display, arena.allocate(SYNC_VALUE));
      } catch (LinkageError e) {
        return 0; // no libXext; resizes stay unsynchronised
      }
      if (counter == 0) {
        return 0;
      }
      if (syncRequest == 0) {
        wmProtocols = atom(arena, display, "WM_PROTOCOLS");
        syncRequest = atom(arena, display, "_NET_WM_SYNC_REQUEST");
      }
      long property = atom(arena, display, "_NET_WM_SYNC_REQUEST_COUNTER");
      MemorySegment value = arena.allocateFrom(JAVA_LONG, counter); // format 32 is a long each
      int unused = (int) H.CHANGE_PROPERTY_H.invokeExact(
          display, window, property, XA_CARDINAL, 32, PROP_MODE_REPLACE, value, 1);
      // Added to the protocols SDL set (WM_DELETE_WINDOW, _NET_WM_PING).
      MemorySegment list = arena.allocate(ADDRESS);
      MemorySegment count = arena.allocate(JAVA_INT);
      long[] protocols = new long[0];
      if ((int) H.GET_WM_PROTOCOLS_H.invokeExact(display, window, list, count) != 0) {
        MemorySegment atoms = list.get(ADDRESS, 0);
        protocols = atoms.reinterpret(count.get(JAVA_INT, 0) * 8L).toArray(JAVA_LONG);
        unused = (int) H.FREE_H.invokeExact(atoms);
      }
      long[] with = java.util.Arrays.copyOf(protocols, protocols.length + 1);
      with[protocols.length] = syncRequest;
      unused = (int) H.SET_WM_PROTOCOLS_H.invokeExact(
          display, window, arena.allocateFrom(JAVA_LONG, with), with.length);
      return counter;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /**
   * The value a {@code _NET_WM_SYNC_REQUEST} asks the counter to reach, or -1 when the event is
   * no such request.
   */
  static long syncRequest(MemorySegment event) {
    return syncRequest(event, wmProtocols, syncRequest);
  }

  /** With the display's atoms {@code WM_PROTOCOLS} and {@code _NET_WM_SYNC_REQUEST}. */
  static long syncRequest(MemorySegment event, long wmProtocols, long syncRequest) {
    if (syncRequest == 0
        || event.get(JAVA_INT, EVENT_TYPE) != CLIENT_MESSAGE
        || event.get(JAVA_LONG, CLIENT_MESSAGE_TYPE) != wmProtocols
        || event.get(JAVA_INT, CLIENT_FORMAT) != 32
        || event.get(JAVA_LONG, CLIENT_DATA) != syncRequest) {
      return -1;
    }
    // EWMH: data.l[2] the low 32 bits, data.l[3] the high ones.
    long low = event.get(JAVA_LONG, CLIENT_DATA + 16);
    long high = event.get(JAVA_LONG, CLIENT_DATA + 24);
    return high << 32 | low & 0xFFFFFFFFL;
  }

  /** Sets the counter, which tells the window manager that the window has drawn its new size. */
  static void setCounter(MemorySegment display, long counter, long value) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment v = arena.allocate(SYNC_VALUE);
      v.set(JAVA_INT, 0, (int) (value >>> 32));
      v.set(JAVA_INT, 4, (int) value);
      int unused = (int) Sync.SET_COUNTER_H.invokeExact(display, counter, v);
      unused = (int) H.FLUSH_H.invokeExact(display);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  static void destroyCounter(MemorySegment display, long counter) {
    try {
      int unused = (int) Sync.DESTROY_COUNTER_H.invokeExact(display, counter);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  private static long atom(Arena arena, MemorySegment display, String name) throws Throwable {
    return (long) H.INTERN_ATOM_H.invokeExact(display, arena.allocateFrom(name), 0);
  }
}
