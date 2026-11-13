package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.rethinkj.ui.natives.Natives;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Handwritten bindings for the part of SDL 3 that the window uses, written against SDL 3.4.
 *
 * <p>The descriptors are constants, so that GraalVM native image can register them. The method
 * handles live in {@link H}, which is initialised on the first call and only then loads the library
 * through {@link Natives}; nothing in this class loads it. Struct offsets are written out by hand
 * from SDL's headers, for 64-bit platforms.
 */
final class Sdl {

  private Sdl() {}

  static final int INIT_VIDEO = 0x20;

  // SDL_WindowFlags
  static final long WINDOW_FULLSCREEN = 0x1;
  static final long WINDOW_HIDDEN = 0x8;
  static final long WINDOW_BORDERLESS = 0x10;
  static final long WINDOW_RESIZABLE = 0x20;
  static final long WINDOW_MINIMIZED = 0x40;
  static final long WINDOW_MAXIMIZED = 0x80;
  static final long WINDOW_INPUT_FOCUS = 0x200;
  static final long WINDOW_HIGH_PIXEL_DENSITY = 0x2000;

  // SDL_HitTestResult
  static final int HITTEST_NORMAL = 0;
  static final int HITTEST_DRAGGABLE = 1;
  static final int HITTEST_RESIZE_TOPLEFT = 2;
  static final int HITTEST_RESIZE_TOP = 3;
  static final int HITTEST_RESIZE_TOPRIGHT = 4;
  static final int HITTEST_RESIZE_RIGHT = 5;
  static final int HITTEST_RESIZE_BOTTOMRIGHT = 6;
  static final int HITTEST_RESIZE_BOTTOM = 7;
  static final int HITTEST_RESIZE_BOTTOMLEFT = 8;
  static final int HITTEST_RESIZE_LEFT = 9;

  // SDL_EventType
  static final int EVENT_FIRST = 0;
  static final int EVENT_LAST = 0xFFFF;
  static final int EVENT_WINDOW_SHOWN = 0x202;
  static final int EVENT_WINDOW_EXPOSED = 0x204;
  static final int EVENT_WINDOW_MOVED = 0x205;
  static final int EVENT_WINDOW_RESIZED = 0x206;
  static final int EVENT_WINDOW_PIXEL_SIZE_CHANGED = 0x207;
  static final int EVENT_WINDOW_MINIMIZED = 0x209;
  static final int EVENT_WINDOW_MAXIMIZED = 0x20A;
  static final int EVENT_WINDOW_RESTORED = 0x20B;
  static final int EVENT_WINDOW_MOUSE_ENTER = 0x20C;
  static final int EVENT_WINDOW_MOUSE_LEAVE = 0x20D;
  static final int EVENT_WINDOW_FOCUS_GAINED = 0x20E;
  static final int EVENT_WINDOW_FOCUS_LOST = 0x20F;
  static final int EVENT_WINDOW_CLOSE_REQUESTED = 0x210;
  static final int EVENT_WINDOW_DISPLAY_SCALE_CHANGED = 0x214;
  static final int EVENT_WINDOW_LAST = 0x21A;
  static final int EVENT_KEY_DOWN = 0x300;
  static final int EVENT_KEY_UP = 0x301;
  static final int EVENT_TEXT_INPUT = 0x303;
  static final int EVENT_MOUSE_MOTION = 0x400;
  static final int EVENT_MOUSE_BUTTON_DOWN = 0x401;
  static final int EVENT_MOUSE_BUTTON_UP = 0x402;
  static final int EVENT_MOUSE_WHEEL = 0x403;

  // Mouse buttons and wheel direction
  static final int BUTTON_LEFT = 1;
  static final int BUTTON_MIDDLE = 2;
  static final int BUTTON_RIGHT = 3;
  static final int BUTTON_X1 = 4;
  static final int BUTTON_X2 = 5;
  static final int MOUSEWHEEL_FLIPPED = 1;

  // SDL_SystemCursor
  static final int SYSTEM_CURSOR_DEFAULT = 0;
  static final int SYSTEM_CURSOR_TEXT = 1;
  static final int SYSTEM_CURSOR_NWSE_RESIZE = 5;
  static final int SYSTEM_CURSOR_NESW_RESIZE = 6;
  static final int SYSTEM_CURSOR_EW_RESIZE = 7;
  static final int SYSTEM_CURSOR_NS_RESIZE = 8;
  static final int SYSTEM_CURSOR_NOT_ALLOWED = 10;
  static final int SYSTEM_CURSOR_POINTER = 11;

  // SDL_Keymod
  static final int KMOD_SHIFT = 0x0003;
  static final int KMOD_CTRL = 0x00C0;
  static final int KMOD_ALT = 0x0300;
  static final int KMOD_GUI = 0x0C00;

  // SDL_ProgressState
  static final int PROGRESS_STATE_NONE = 0;
  static final int PROGRESS_STATE_NORMAL = 2;

  // Window properties
  static final String PROP_WINDOW_WIN32_HWND_POINTER = "SDL.window.win32.hwnd";
  static final String PROP_WINDOW_WIN32_INSTANCE_POINTER = "SDL.window.win32.instance";
  static final String PROP_WINDOW_X11_DISPLAY_POINTER = "SDL.window.x11.display";
  static final String PROP_WINDOW_X11_WINDOW_NUMBER = "SDL.window.x11.window";
  static final String PROP_WINDOW_WAYLAND_DISPLAY_POINTER = "SDL.window.wayland.display";
  static final String PROP_WINDOW_WAYLAND_SURFACE_POINTER = "SDL.window.wayland.surface";

  static final String HINT_NO_SIGNAL_HANDLERS = "SDL_NO_SIGNAL_HANDLERS";

  // SDL_Event is a union of 128 bytes. Every member starts with Uint32 type, Uint32 reserved and
  // Uint64 timestamp; the members the window reads continue with SDL_WindowID windowID at 16.
  static final long EVENT_SIZE = 128;
  static final long EVENT_TYPE = 0;
  static final long EVENT_WINDOW_ID = 16;
  // SDL_WindowEvent: Sint32 data1, data2
  static final long WINDOW_DATA1 = 20;
  static final long WINDOW_DATA2 = 24;
  // SDL_KeyboardEvent: SDL_KeyboardID which, SDL_Scancode scancode, SDL_Keycode key,
  // SDL_Keymod mod (Uint16), Uint16 raw, bool down, bool repeat
  static final long KEY_SCANCODE = 24;
  static final long KEY_KEY = 28;
  static final long KEY_MOD = 32;
  static final long KEY_DOWN = 36;
  static final long KEY_REPEAT = 37;
  // SDL_TextInputEvent: 4 bytes of padding, const char *text
  static final long TEXT_TEXT = 24;
  // SDL_MouseMotionEvent: SDL_MouseID which, SDL_MouseButtonFlags state, float x, y, xrel, yrel
  static final long MOTION_X = 28;
  static final long MOTION_Y = 32;
  // SDL_MouseButtonEvent: SDL_MouseID which, Uint8 button, bool down, Uint8 clicks, Uint8 padding,
  // float x, y
  static final long BUTTON_BUTTON = 24;
  static final long BUTTON_DOWN = 25;
  static final long BUTTON_CLICKS = 26;
  static final long BUTTON_X = 28;
  static final long BUTTON_Y = 32;
  // SDL_MouseWheelEvent: SDL_MouseID which, float x, y, SDL_MouseWheelDirection direction,
  // float mouse_x, mouse_y, Sint32 integer_x, integer_y
  static final long WHEEL_X = 24;
  static final long WHEEL_Y = 28;
  static final long WHEEL_DIRECTION = 32;
  static final long WHEEL_MOUSE_X = 36;
  static final long WHEEL_MOUSE_Y = 40;
  // SDL_UserEvent: Sint32 code, void *data1, void *data2
  static final long USER_CODE = 20;

  // SDL_DisplayMode: SDL_DisplayID displayID, SDL_PixelFormat format, int w, h,
  // float pixel_density, float refresh_rate, ...
  static final long DISPLAY_MODE_REFRESH_RATE = 20;

  // ---- callbacks

  /** {@code SDL_HitTestResult (*SDL_HitTest)(SDL_Window *win, const SDL_Point *area, void *data)} */
  static final FunctionDescriptor HIT_TEST = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

  /** {@code bool (*SDL_EventFilter)(void *userdata, SDL_Event *event)} */
  static final FunctionDescriptor EVENT_FILTER =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS);

  // ---- functions

  static final FunctionDescriptor SDL_INIT = FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT);
  static final FunctionDescriptor SDL_QUIT = FunctionDescriptor.ofVoid();
  static final FunctionDescriptor SDL_GET_ERROR = FunctionDescriptor.of(ADDRESS);
  static final FunctionDescriptor SDL_SET_HINT =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_GET_CURRENT_VIDEO_DRIVER = FunctionDescriptor.of(ADDRESS);
  static final FunctionDescriptor SDL_CREATE_WINDOW =
      FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG);
  static final FunctionDescriptor SDL_DESTROY_WINDOW = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor SDL_GET_WINDOW_ID = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor SDL_GET_WINDOW_FLAGS = FunctionDescriptor.of(JAVA_LONG, ADDRESS);
  static final FunctionDescriptor SDL_SHOW_WINDOW = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_RAISE_WINDOW = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_MINIMIZE_WINDOW =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_MAXIMIZE_WINDOW =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_RESTORE_WINDOW = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_SET_WINDOW_TITLE =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_GET_WINDOW_SIZE =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_GET_WINDOW_SIZE_IN_PIXELS =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_SET_WINDOW_SIZE =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor SDL_SET_WINDOW_MINIMUM_SIZE =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor SDL_GET_WINDOW_POSITION =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_SET_WINDOW_POSITION =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor SDL_GET_WINDOW_DISPLAY_SCALE =
      FunctionDescriptor.of(JAVA_FLOAT, ADDRESS);
  static final FunctionDescriptor SDL_GET_WINDOW_PIXEL_DENSITY =
      FunctionDescriptor.of(JAVA_FLOAT, ADDRESS);
  static final FunctionDescriptor SDL_GET_PRIMARY_DISPLAY = FunctionDescriptor.of(JAVA_INT);
  static final FunctionDescriptor SDL_GET_DISPLAY_CONTENT_SCALE =
      FunctionDescriptor.of(JAVA_FLOAT, JAVA_INT);
  static final FunctionDescriptor SDL_GET_DISPLAY_FOR_WINDOW =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor SDL_GET_CURRENT_DISPLAY_MODE =
      FunctionDescriptor.of(ADDRESS, JAVA_INT);
  static final FunctionDescriptor SDL_SET_WINDOW_HIT_TEST =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_SHOW_WINDOW_SYSTEM_MENU =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor SDL_GET_WINDOW_PROPERTIES =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor SDL_GET_POINTER_PROPERTY =
      FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_GET_NUMBER_PROPERTY =
      FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
  static final FunctionDescriptor SDL_SET_WINDOW_PROGRESS_STATE =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT);
  static final FunctionDescriptor SDL_SET_WINDOW_PROGRESS_VALUE =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_FLOAT);
  static final FunctionDescriptor SDL_START_TEXT_INPUT =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_ADD_EVENT_WATCH =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_REMOVE_EVENT_WATCH =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_WAIT_EVENT_TIMEOUT =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT);
  static final FunctionDescriptor SDL_POLL_EVENT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_PUSH_EVENT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_REGISTER_EVENTS = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
  static final FunctionDescriptor SDL_CREATE_SYSTEM_CURSOR =
      FunctionDescriptor.of(ADDRESS, JAVA_INT);
  static final FunctionDescriptor SDL_SET_CURSOR = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);

  /** The handles, bound on first use. Initialising this class loads SDL. */
  private static final class H {
    static final MethodHandle INIT = bind("SDL_Init", SDL_INIT);
    static final MethodHandle QUIT = bind("SDL_Quit", SDL_QUIT);
    static final MethodHandle GET_ERROR = bind("SDL_GetError", SDL_GET_ERROR);
    static final MethodHandle SET_HINT = bind("SDL_SetHint", SDL_SET_HINT);
    static final MethodHandle GET_CURRENT_VIDEO_DRIVER =
        bind("SDL_GetCurrentVideoDriver", SDL_GET_CURRENT_VIDEO_DRIVER);
    static final MethodHandle CREATE_WINDOW = bind("SDL_CreateWindow", SDL_CREATE_WINDOW);
    static final MethodHandle DESTROY_WINDOW = bind("SDL_DestroyWindow", SDL_DESTROY_WINDOW);
    static final MethodHandle GET_WINDOW_ID = bind("SDL_GetWindowID", SDL_GET_WINDOW_ID);
    static final MethodHandle GET_WINDOW_FLAGS = bind("SDL_GetWindowFlags", SDL_GET_WINDOW_FLAGS);
    static final MethodHandle SHOW_WINDOW = bind("SDL_ShowWindow", SDL_SHOW_WINDOW);
    static final MethodHandle RAISE_WINDOW = bind("SDL_RaiseWindow", SDL_RAISE_WINDOW);
    static final MethodHandle MINIMIZE_WINDOW = bind("SDL_MinimizeWindow", SDL_MINIMIZE_WINDOW);
    static final MethodHandle MAXIMIZE_WINDOW = bind("SDL_MaximizeWindow", SDL_MAXIMIZE_WINDOW);
    static final MethodHandle RESTORE_WINDOW = bind("SDL_RestoreWindow", SDL_RESTORE_WINDOW);
    static final MethodHandle SET_WINDOW_TITLE = bind("SDL_SetWindowTitle", SDL_SET_WINDOW_TITLE);
    static final MethodHandle GET_WINDOW_SIZE = bind("SDL_GetWindowSize", SDL_GET_WINDOW_SIZE);
    static final MethodHandle GET_WINDOW_SIZE_IN_PIXELS =
        bind("SDL_GetWindowSizeInPixels", SDL_GET_WINDOW_SIZE_IN_PIXELS);
    static final MethodHandle SET_WINDOW_SIZE = bind("SDL_SetWindowSize", SDL_SET_WINDOW_SIZE);
    static final MethodHandle SET_WINDOW_MINIMUM_SIZE =
        bind("SDL_SetWindowMinimumSize", SDL_SET_WINDOW_MINIMUM_SIZE);
    static final MethodHandle GET_WINDOW_POSITION =
        bind("SDL_GetWindowPosition", SDL_GET_WINDOW_POSITION);
    static final MethodHandle SET_WINDOW_POSITION =
        bind("SDL_SetWindowPosition", SDL_SET_WINDOW_POSITION);
    static final MethodHandle GET_WINDOW_DISPLAY_SCALE =
        bind("SDL_GetWindowDisplayScale", SDL_GET_WINDOW_DISPLAY_SCALE);
    static final MethodHandle GET_WINDOW_PIXEL_DENSITY =
        bind("SDL_GetWindowPixelDensity", SDL_GET_WINDOW_PIXEL_DENSITY);
    static final MethodHandle GET_PRIMARY_DISPLAY =
        bind("SDL_GetPrimaryDisplay", SDL_GET_PRIMARY_DISPLAY);
    static final MethodHandle GET_DISPLAY_CONTENT_SCALE =
        bind("SDL_GetDisplayContentScale", SDL_GET_DISPLAY_CONTENT_SCALE);
    static final MethodHandle GET_DISPLAY_FOR_WINDOW =
        bind("SDL_GetDisplayForWindow", SDL_GET_DISPLAY_FOR_WINDOW);
    static final MethodHandle GET_CURRENT_DISPLAY_MODE =
        bind("SDL_GetCurrentDisplayMode", SDL_GET_CURRENT_DISPLAY_MODE);
    static final MethodHandle SET_WINDOW_HIT_TEST =
        bind("SDL_SetWindowHitTest", SDL_SET_WINDOW_HIT_TEST);
    static final MethodHandle SHOW_WINDOW_SYSTEM_MENU =
        bind("SDL_ShowWindowSystemMenu", SDL_SHOW_WINDOW_SYSTEM_MENU);
    static final MethodHandle GET_WINDOW_PROPERTIES =
        bind("SDL_GetWindowProperties", SDL_GET_WINDOW_PROPERTIES);
    static final MethodHandle GET_POINTER_PROPERTY =
        bind("SDL_GetPointerProperty", SDL_GET_POINTER_PROPERTY);
    static final MethodHandle GET_NUMBER_PROPERTY =
        bind("SDL_GetNumberProperty", SDL_GET_NUMBER_PROPERTY);
    static final MethodHandle SET_WINDOW_PROGRESS_STATE =
        bind("SDL_SetWindowProgressState", SDL_SET_WINDOW_PROGRESS_STATE);
    static final MethodHandle SET_WINDOW_PROGRESS_VALUE =
        bind("SDL_SetWindowProgressValue", SDL_SET_WINDOW_PROGRESS_VALUE);
    static final MethodHandle START_TEXT_INPUT = bind("SDL_StartTextInput", SDL_START_TEXT_INPUT);
    static final MethodHandle ADD_EVENT_WATCH = bind("SDL_AddEventWatch", SDL_ADD_EVENT_WATCH);
    static final MethodHandle REMOVE_EVENT_WATCH =
        bind("SDL_RemoveEventWatch", SDL_REMOVE_EVENT_WATCH);
    static final MethodHandle WAIT_EVENT_TIMEOUT =
        bind("SDL_WaitEventTimeout", SDL_WAIT_EVENT_TIMEOUT);
    static final MethodHandle POLL_EVENT = bind("SDL_PollEvent", SDL_POLL_EVENT);
    static final MethodHandle PUSH_EVENT = bind("SDL_PushEvent", SDL_PUSH_EVENT);
    static final MethodHandle REGISTER_EVENTS = bind("SDL_RegisterEvents", SDL_REGISTER_EVENTS);
    static final MethodHandle CREATE_SYSTEM_CURSOR =
        bind("SDL_CreateSystemCursor", SDL_CREATE_SYSTEM_CURSOR);
    static final MethodHandle SET_CURSOR = bind("SDL_SetCursor", SDL_SET_CURSOR);

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
      MemorySegment symbol =
          Natives.lookup("sdl3")
              .find(name)
              .orElseThrow(() -> new UnsatisfiedLinkError("SDL 3 has no function " + name
                  + "; rethinkj UI needs SDL 3.4 or later."));
      return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }
  }

  static boolean init(int flags) {
    try {
      return (boolean) H.INIT.invokeExact(flags);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static void quit() {
    try {
      H.QUIT.invokeExact();
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static String getError() {
    try {
      return string((MemorySegment) H.GET_ERROR.invokeExact());
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setHint(String name, String value) {
    try (Arena arena = Arena.ofConfined()) {
      return (boolean) H.SET_HINT.invokeExact(arena.allocateFrom(name), arena.allocateFrom(value));
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** {@code windows}, {@code x11}, {@code wayland}, ... */
  static String getCurrentVideoDriver() {
    try {
      return string((MemorySegment) H.GET_CURRENT_VIDEO_DRIVER.invokeExact());
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static MemorySegment createWindow(String title, int width, int height, long flags) {
    try (Arena arena = Arena.ofConfined()) {
      return (MemorySegment)
          H.CREATE_WINDOW.invokeExact(arena.allocateFrom(title), width, height, flags);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static void destroyWindow(MemorySegment window) {
    try {
      H.DESTROY_WINDOW.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static int getWindowID(MemorySegment window) {
    try {
      return (int) H.GET_WINDOW_ID.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static long getWindowFlags(MemorySegment window) {
    try {
      return (long) H.GET_WINDOW_FLAGS.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean showWindow(MemorySegment window) {
    try {
      return (boolean) H.SHOW_WINDOW.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean raiseWindow(MemorySegment window) {
    try {
      return (boolean) H.RAISE_WINDOW.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean minimizeWindow(MemorySegment window) {
    try {
      return (boolean) H.MINIMIZE_WINDOW.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean maximizeWindow(MemorySegment window) {
    try {
      return (boolean) H.MAXIMIZE_WINDOW.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean restoreWindow(MemorySegment window) {
    try {
      return (boolean) H.RESTORE_WINDOW.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setWindowTitle(MemorySegment window, String title) {
    try (Arena arena = Arena.ofConfined()) {
      return (boolean) H.SET_WINDOW_TITLE.invokeExact(window, arena.allocateFrom(title));
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** Writes width and height, in window coordinates, into the two ints at {@code size}. */
  static boolean getWindowSize(MemorySegment window, MemorySegment size) {
    try {
      return (boolean) H.GET_WINDOW_SIZE.invokeExact(window, size, size.asSlice(4));
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** Writes width and height in pixels into the two ints at {@code size}. */
  static boolean getWindowSizeInPixels(MemorySegment window, MemorySegment size) {
    try {
      return (boolean) H.GET_WINDOW_SIZE_IN_PIXELS.invokeExact(window, size, size.asSlice(4));
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setWindowSize(MemorySegment window, int width, int height) {
    try {
      return (boolean) H.SET_WINDOW_SIZE.invokeExact(window, width, height);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setWindowMinimumSize(MemorySegment window, int width, int height) {
    try {
      return (boolean) H.SET_WINDOW_MINIMUM_SIZE.invokeExact(window, width, height);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** Writes x and y into the two ints at {@code position}. */
  static boolean getWindowPosition(MemorySegment window, MemorySegment position) {
    try {
      return (boolean) H.GET_WINDOW_POSITION.invokeExact(window, position, position.asSlice(4));
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setWindowPosition(MemorySegment window, int x, int y) {
    try {
      return (boolean) H.SET_WINDOW_POSITION.invokeExact(window, x, y);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static float getWindowDisplayScale(MemorySegment window) {
    try {
      return (float) H.GET_WINDOW_DISPLAY_SCALE.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static float getWindowPixelDensity(MemorySegment window) {
    try {
      return (float) H.GET_WINDOW_PIXEL_DENSITY.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static int getPrimaryDisplay() {
    try {
      return (int) H.GET_PRIMARY_DISPLAY.invokeExact();
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static float getDisplayContentScale(int display) {
    try {
      return (float) H.GET_DISPLAY_CONTENT_SCALE.invokeExact(display);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static int getDisplayForWindow(MemorySegment window) {
    try {
      return (int) H.GET_DISPLAY_FOR_WINDOW.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** The refresh rate of the display's current mode in hertz, or 0 if it is not known. */
  static float getCurrentRefreshRate(int display) {
    try {
      MemorySegment mode = (MemorySegment) H.GET_CURRENT_DISPLAY_MODE.invokeExact(display);
      if (mode.equals(MemorySegment.NULL)) {
        return 0;
      }
      return mode.reinterpret(DISPLAY_MODE_REFRESH_RATE + 4)
          .get(JAVA_FLOAT, DISPLAY_MODE_REFRESH_RATE);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setWindowHitTest(MemorySegment window, MemorySegment callback, MemorySegment data) {
    try {
      return (boolean) H.SET_WINDOW_HIT_TEST.invokeExact(window, callback, data);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** Shows the system's window menu at a point in window coordinates. */
  static boolean showWindowSystemMenu(MemorySegment window, int x, int y) {
    try {
      return (boolean) H.SHOW_WINDOW_SYSTEM_MENU.invokeExact(window, x, y);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static int getWindowProperties(MemorySegment window) {
    try {
      return (int) H.GET_WINDOW_PROPERTIES.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static MemorySegment getPointerProperty(int properties, String name) {
    try (Arena arena = Arena.ofConfined()) {
      return (MemorySegment) H.GET_POINTER_PROPERTY.invokeExact(
          properties, arena.allocateFrom(name), MemorySegment.NULL);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static long getNumberProperty(int properties, String name) {
    try (Arena arena = Arena.ofConfined()) {
      return (long) H.GET_NUMBER_PROPERTY.invokeExact(properties, arena.allocateFrom(name), 0L);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setWindowProgressState(MemorySegment window, int state) {
    try {
      return (boolean) H.SET_WINDOW_PROGRESS_STATE.invokeExact(window, state);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setWindowProgressValue(MemorySegment window, float value) {
    try {
      return (boolean) H.SET_WINDOW_PROGRESS_VALUE.invokeExact(window, value);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean startTextInput(MemorySegment window) {
    try {
      return (boolean) H.START_TEXT_INPUT.invokeExact(window);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean addEventWatch(MemorySegment filter, MemorySegment userdata) {
    try {
      return (boolean) H.ADD_EVENT_WATCH.invokeExact(filter, userdata);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static void removeEventWatch(MemorySegment filter, MemorySegment userdata) {
    try {
      H.REMOVE_EVENT_WATCH.invokeExact(filter, userdata);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** @param timeoutMillis -1 to wait without a limit */
  static boolean waitEventTimeout(MemorySegment event, int timeoutMillis) {
    try {
      return (boolean) H.WAIT_EVENT_TIMEOUT.invokeExact(event, timeoutMillis);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean pollEvent(MemorySegment event) {
    try {
      return (boolean) H.POLL_EVENT.invokeExact(event);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** Thread-safe. */
  static boolean pushEvent(MemorySegment event) {
    try {
      return (boolean) H.PUSH_EVENT.invokeExact(event);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** @return the first of the new event types, or 0 if there are none left */
  static int registerEvents(int count) {
    try {
      return (int) H.REGISTER_EVENTS.invokeExact(count);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  /** @return the cursor, which SDL_Quit frees, or NULL if the system has none of this shape */
  static MemorySegment createSystemCursor(int id) {
    try {
      return (MemorySegment) H.CREATE_SYSTEM_CURSOR.invokeExact(id);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static boolean setCursor(MemorySegment cursor) {
    try {
      return (boolean) H.SET_CURSOR.invokeExact(cursor);
    } catch (Throwable t) {
      throw failed(t);
    }
  }

  static String string(MemorySegment cString) {
    return cString.equals(MemorySegment.NULL)
        ? null
        : cString.reinterpret(Long.MAX_VALUE).getString(0);
  }

  /** Errors and unchecked exceptions as they are; a downcall handle throws nothing else. */
  static RuntimeException failed(Throwable t) {
    if (t instanceof Error e) {
      throw e;
    }
    return t instanceof RuntimeException r ? r : new IllegalStateException(t);
  }
}
