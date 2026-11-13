import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/** Handwritten FFM bindings for the part of SDL3 the spike uses. */
final class Sdl {
    private Sdl() {}

    static final int INIT_VIDEO = 0x20;

    static final long WINDOW_BORDERLESS = 0x10;
    static final long WINDOW_RESIZABLE = 0x20;
    static final long WINDOW_MINIMIZED = 0x40;
    static final long WINDOW_MAXIMIZED = 0x80;
    static final long WINDOW_UTILITY = 0x20000;
    static final long WINDOW_NOT_FOCUSABLE = 0x80000000L;

    static final String PROP_WINDOW_WIN32_HWND_POINTER = "SDL.window.win32.hwnd";

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

    static final int PROGRESS_STATE_NONE = 0;
    static final int PROGRESS_STATE_INDETERMINATE = 1;
    static final int PROGRESS_STATE_NORMAL = 2;
    static final int PROGRESS_STATE_PAUSED = 3;
    static final int PROGRESS_STATE_ERROR = 4;

    static final int EVENT_QUIT = 0x100;
    static final int EVENT_WINDOW_SHOWN = 0x202;
    static final int EVENT_WINDOW_EXPOSED = 0x204;
    static final int EVENT_WINDOW_MOVED = 0x205;
    static final int EVENT_WINDOW_RESIZED = 0x206;
    static final int EVENT_WINDOW_MINIMIZED = 0x209;
    static final int EVENT_WINDOW_MAXIMIZED = 0x20A;
    static final int EVENT_WINDOW_RESTORED = 0x20B;
    static final int EVENT_WINDOW_MOUSE_ENTER = 0x20C;
    static final int EVENT_WINDOW_MOUSE_LEAVE = 0x20D;
    static final int EVENT_WINDOW_FOCUS_GAINED = 0x20E;
    static final int EVENT_WINDOW_FOCUS_LOST = 0x20F;
    static final int EVENT_WINDOW_CLOSE_REQUESTED = 0x210;
    static final int EVENT_WINDOW_HIT_TEST = 0x211;
    static final int EVENT_KEY_DOWN = 0x300;
    static final int EVENT_MOUSE_MOTION = 0x400;
    static final int EVENT_MOUSE_BUTTON_DOWN = 0x401;
    static final int EVENT_MOUSE_BUTTON_UP = 0x402;

    static final int BUTTON_LEFT = 1;

    // ---- struct layouts ----------------------------------------------------------------------

    static final StructLayout POINT = MemoryLayout.structLayout(JAVA_INT.withName("x"), JAVA_INT.withName("y"));

    static final StructLayout FRECT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("x"), JAVA_FLOAT.withName("y"), JAVA_FLOAT.withName("w"), JAVA_FLOAT.withName("h"));

    /** SDL_Event is a union padded to 128 bytes; the spike reads it through the member layouts below. */
    static final long EVENT_SIZE = 128;

    static final StructLayout WINDOW_EVENT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), JAVA_INT.withName("reserved"), JAVA_LONG.withName("timestamp"),
            JAVA_INT.withName("windowID"), JAVA_INT.withName("data1"), JAVA_INT.withName("data2"),
            MemoryLayout.paddingLayout(4));

    static final StructLayout MOUSE_MOTION_EVENT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), JAVA_INT.withName("reserved"), JAVA_LONG.withName("timestamp"),
            JAVA_INT.withName("windowID"), JAVA_INT.withName("which"), JAVA_INT.withName("state"),
            JAVA_FLOAT.withName("x"), JAVA_FLOAT.withName("y"),
            JAVA_FLOAT.withName("xrel"), JAVA_FLOAT.withName("yrel"), MemoryLayout.paddingLayout(4));

    static final StructLayout MOUSE_BUTTON_EVENT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), JAVA_INT.withName("reserved"), JAVA_LONG.withName("timestamp"),
            JAVA_INT.withName("windowID"), JAVA_INT.withName("which"),
            JAVA_BYTE.withName("button"), JAVA_BOOLEAN.withName("down"),
            JAVA_BYTE.withName("clicks"), JAVA_BYTE.withName("padding"),
            JAVA_FLOAT.withName("x"), JAVA_FLOAT.withName("y"), MemoryLayout.paddingLayout(4));

    static final StructLayout KEYBOARD_EVENT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), JAVA_INT.withName("reserved"), JAVA_LONG.withName("timestamp"),
            JAVA_INT.withName("windowID"), JAVA_INT.withName("which"),
            JAVA_INT.withName("scancode"), JAVA_INT.withName("key"),
            JAVA_SHORT.withName("mod"), JAVA_SHORT.withName("raw"),
            JAVA_BOOLEAN.withName("down"), JAVA_BOOLEAN.withName("repeat"), MemoryLayout.paddingLayout(2));

    static final long EVENT_TYPE = 0;
    static final long EVENT_WINDOW_ID = offset(WINDOW_EVENT, "windowID");
    static final long WINDOW_EVENT_DATA1 = offset(WINDOW_EVENT, "data1");
    static final long WINDOW_EVENT_DATA2 = offset(WINDOW_EVENT, "data2");
    static final long MOUSE_MOTION_X = offset(MOUSE_MOTION_EVENT, "x");
    static final long MOUSE_MOTION_Y = offset(MOUSE_MOTION_EVENT, "y");
    static final long MOUSE_BUTTON_BUTTON = offset(MOUSE_BUTTON_EVENT, "button");
    static final long MOUSE_BUTTON_X = offset(MOUSE_BUTTON_EVENT, "x");
    static final long MOUSE_BUTTON_Y = offset(MOUSE_BUTTON_EVENT, "y");
    static final long KEYBOARD_KEY = offset(KEYBOARD_EVENT, "key");
    static final long KEYBOARD_REPEAT = offset(KEYBOARD_EVENT, "repeat");

    private static long offset(StructLayout layout, String field) {
        return layout.byteOffset(PathElement.groupElement(field));
    }

    // ---- callbacks ---------------------------------------------------------------------------

    /** SDL_HitTestResult (*SDL_HitTest)(SDL_Window *win, const SDL_Point *area, void *data) */
    static final FunctionDescriptor HIT_TEST_CALLBACK =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS.withTargetLayout(POINT), ADDRESS);

    /** bool (*SDL_EventFilter)(void *userdata, SDL_Event *event) */
    static final FunctionDescriptor EVENT_FILTER_CALLBACK = FunctionDescriptor.of(
            JAVA_BOOLEAN, ADDRESS, ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(EVENT_SIZE, JAVA_BYTE)));

    // ---- functions ---------------------------------------------------------------------------

    static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(locate(), Arena.global());

    static final FunctionDescriptor SDL_INIT = FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT);
    static final FunctionDescriptor SDL_QUIT = FunctionDescriptor.ofVoid();
    static final FunctionDescriptor SDL_GET_ERROR = FunctionDescriptor.of(ADDRESS);
    static final FunctionDescriptor SDL_GET_VERSION = FunctionDescriptor.of(JAVA_INT);
    static final FunctionDescriptor SDL_GET_REVISION = FunctionDescriptor.of(ADDRESS);
    static final FunctionDescriptor SDL_CREATE_WINDOW =
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor SDL_DESTROY_WINDOW = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor SDL_SET_WINDOW_POSITION =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor SDL_GET_WINDOW_SIZE =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor SDL_SET_WINDOW_MINIMUM_SIZE =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor SDL_GET_WINDOW_FLAGS = FunctionDescriptor.of(JAVA_LONG, ADDRESS);
    static final FunctionDescriptor SDL_GET_WINDOW_ID = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor SDL_RAISE_WINDOW = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor SDL_MINIMIZE_WINDOW = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor SDL_MAXIMIZE_WINDOW = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor SDL_RESTORE_WINDOW = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor SDL_SET_WINDOW_HIT_TEST =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor SDL_GET_WINDOW_PROPERTIES = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor SDL_GET_POINTER_PROPERTY =
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor SDL_SET_WINDOW_PROGRESS_STATE =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT);
    static final FunctionDescriptor SDL_SET_WINDOW_PROGRESS_VALUE =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_FLOAT);
    static final FunctionDescriptor SDL_CREATE_RENDERER = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor SDL_DESTROY_RENDERER = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor SDL_GET_RENDERER_NAME = FunctionDescriptor.of(ADDRESS, ADDRESS);
    static final FunctionDescriptor SDL_SET_RENDER_VSYNC = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT);
    static final FunctionDescriptor SDL_SET_RENDER_DRAW_COLOR =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_BYTE, JAVA_BYTE, JAVA_BYTE, JAVA_BYTE);
    static final FunctionDescriptor SDL_RENDER_CLEAR = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor SDL_RENDER_FILL_RECT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS);
    static final FunctionDescriptor SDL_RENDER_DEBUG_TEXT =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, ADDRESS);
    static final FunctionDescriptor SDL_RENDER_PRESENT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor SDL_POLL_EVENT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor SDL_WAIT_EVENT_TIMEOUT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT);
    static final FunctionDescriptor SDL_ADD_EVENT_WATCH = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS);

    private static final MethodHandle INIT = bind("SDL_Init", SDL_INIT);
    private static final MethodHandle QUIT = bind("SDL_Quit", SDL_QUIT);
    private static final MethodHandle GET_ERROR = bind("SDL_GetError", SDL_GET_ERROR);
    private static final MethodHandle GET_VERSION = bind("SDL_GetVersion", SDL_GET_VERSION);
    private static final MethodHandle GET_REVISION = bind("SDL_GetRevision", SDL_GET_REVISION);
    private static final MethodHandle CREATE_WINDOW = bind("SDL_CreateWindow", SDL_CREATE_WINDOW);
    private static final MethodHandle DESTROY_WINDOW = bind("SDL_DestroyWindow", SDL_DESTROY_WINDOW);
    private static final MethodHandle SET_WINDOW_POSITION = bind("SDL_SetWindowPosition", SDL_SET_WINDOW_POSITION);
    private static final MethodHandle GET_WINDOW_SIZE = bind("SDL_GetWindowSize", SDL_GET_WINDOW_SIZE);
    private static final MethodHandle SET_WINDOW_MINIMUM_SIZE =
            bind("SDL_SetWindowMinimumSize", SDL_SET_WINDOW_MINIMUM_SIZE);
    private static final MethodHandle GET_WINDOW_FLAGS = bind("SDL_GetWindowFlags", SDL_GET_WINDOW_FLAGS);
    private static final MethodHandle GET_WINDOW_ID = bind("SDL_GetWindowID", SDL_GET_WINDOW_ID);
    private static final MethodHandle RAISE_WINDOW = bind("SDL_RaiseWindow", SDL_RAISE_WINDOW);
    private static final MethodHandle MINIMIZE_WINDOW = bind("SDL_MinimizeWindow", SDL_MINIMIZE_WINDOW);
    private static final MethodHandle MAXIMIZE_WINDOW = bind("SDL_MaximizeWindow", SDL_MAXIMIZE_WINDOW);
    private static final MethodHandle RESTORE_WINDOW = bind("SDL_RestoreWindow", SDL_RESTORE_WINDOW);
    private static final MethodHandle SET_WINDOW_HIT_TEST = bind("SDL_SetWindowHitTest", SDL_SET_WINDOW_HIT_TEST);
    private static final MethodHandle GET_WINDOW_PROPERTIES =
            bind("SDL_GetWindowProperties", SDL_GET_WINDOW_PROPERTIES);
    private static final MethodHandle GET_POINTER_PROPERTY = bind("SDL_GetPointerProperty", SDL_GET_POINTER_PROPERTY);
    private static final MethodHandle SET_WINDOW_PROGRESS_STATE =
            bind("SDL_SetWindowProgressState", SDL_SET_WINDOW_PROGRESS_STATE);
    private static final MethodHandle SET_WINDOW_PROGRESS_VALUE =
            bind("SDL_SetWindowProgressValue", SDL_SET_WINDOW_PROGRESS_VALUE);
    private static final MethodHandle CREATE_RENDERER = bind("SDL_CreateRenderer", SDL_CREATE_RENDERER);
    private static final MethodHandle DESTROY_RENDERER = bind("SDL_DestroyRenderer", SDL_DESTROY_RENDERER);
    private static final MethodHandle GET_RENDERER_NAME = bind("SDL_GetRendererName", SDL_GET_RENDERER_NAME);
    private static final MethodHandle SET_RENDER_VSYNC = bind("SDL_SetRenderVSync", SDL_SET_RENDER_VSYNC);
    private static final MethodHandle SET_RENDER_DRAW_COLOR = bind("SDL_SetRenderDrawColor", SDL_SET_RENDER_DRAW_COLOR);
    private static final MethodHandle RENDER_CLEAR = bind("SDL_RenderClear", SDL_RENDER_CLEAR);
    private static final MethodHandle RENDER_FILL_RECT = bind("SDL_RenderFillRect", SDL_RENDER_FILL_RECT);
    private static final MethodHandle RENDER_DEBUG_TEXT = bind("SDL_RenderDebugText", SDL_RENDER_DEBUG_TEXT);
    private static final MethodHandle RENDER_PRESENT = bind("SDL_RenderPresent", SDL_RENDER_PRESENT);
    private static final MethodHandle POLL_EVENT = bind("SDL_PollEvent", SDL_POLL_EVENT);
    private static final MethodHandle WAIT_EVENT_TIMEOUT = bind("SDL_WaitEventTimeout", SDL_WAIT_EVENT_TIMEOUT);
    private static final MethodHandle ADD_EVENT_WATCH = bind("SDL_AddEventWatch", SDL_ADD_EVENT_WATCH);

    static boolean init(int flags) {
        try {
            return (boolean) INIT.invokeExact(flags);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void quit() {
        try {
            QUIT.invokeExact();
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static String getError() {
        try {
            return string((MemorySegment) GET_ERROR.invokeExact());
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static int getVersion() {
        try {
            return (int) GET_VERSION.invokeExact();
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static String getRevision() {
        try {
            return string((MemorySegment) GET_REVISION.invokeExact());
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment createWindow(MemorySegment title, int w, int h, long flags) {
        try {
            return (MemorySegment) CREATE_WINDOW.invokeExact(title, w, h, flags);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void destroyWindow(MemorySegment window) {
        try {
            DESTROY_WINDOW.invokeExact(window);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean setWindowPosition(MemorySegment window, int x, int y) {
        try {
            return (boolean) SET_WINDOW_POSITION.invokeExact(window, x, y);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean getWindowSize(MemorySegment window, MemorySegment w, MemorySegment h) {
        try {
            return (boolean) GET_WINDOW_SIZE.invokeExact(window, w, h);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean setWindowMinimumSize(MemorySegment window, int w, int h) {
        try {
            return (boolean) SET_WINDOW_MINIMUM_SIZE.invokeExact(window, w, h);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static long getWindowFlags(MemorySegment window) {
        try {
            return (long) GET_WINDOW_FLAGS.invokeExact(window);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static int getWindowID(MemorySegment window) {
        try {
            return (int) GET_WINDOW_ID.invokeExact(window);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean raiseWindow(MemorySegment window) {
        try {
            return (boolean) RAISE_WINDOW.invokeExact(window);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean minimizeWindow(MemorySegment window) {
        try {
            return (boolean) MINIMIZE_WINDOW.invokeExact(window);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean maximizeWindow(MemorySegment window) {
        try {
            return (boolean) MAXIMIZE_WINDOW.invokeExact(window);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean restoreWindow(MemorySegment window) {
        try {
            return (boolean) RESTORE_WINDOW.invokeExact(window);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean setWindowHitTest(MemorySegment window, MemorySegment callback, MemorySegment data) {
        try {
            return (boolean) SET_WINDOW_HIT_TEST.invokeExact(window, callback, data);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static int getWindowProperties(MemorySegment window) {
        try {
            return (int) GET_WINDOW_PROPERTIES.invokeExact(window);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment getPointerProperty(int props, MemorySegment name, MemorySegment fallback) {
        try {
            return (MemorySegment) GET_POINTER_PROPERTY.invokeExact(props, name, fallback);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean setWindowProgressState(MemorySegment window, int state) {
        try {
            return (boolean) SET_WINDOW_PROGRESS_STATE.invokeExact(window, state);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean setWindowProgressValue(MemorySegment window, float value) {
        try {
            return (boolean) SET_WINDOW_PROGRESS_VALUE.invokeExact(window, value);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment createRenderer(MemorySegment window, MemorySegment name) {
        try {
            return (MemorySegment) CREATE_RENDERER.invokeExact(window, name);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void destroyRenderer(MemorySegment renderer) {
        try {
            DESTROY_RENDERER.invokeExact(renderer);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static String getRendererName(MemorySegment renderer) {
        try {
            return string((MemorySegment) GET_RENDERER_NAME.invokeExact(renderer));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean setRenderVSync(MemorySegment renderer, int vsync) {
        try {
            return (boolean) SET_RENDER_VSYNC.invokeExact(renderer, vsync);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean setRenderDrawColor(MemorySegment renderer, int r, int g, int b, int a) {
        try {
            return (boolean) SET_RENDER_DRAW_COLOR.invokeExact(renderer, (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean renderClear(MemorySegment renderer) {
        try {
            return (boolean) RENDER_CLEAR.invokeExact(renderer);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean renderFillRect(MemorySegment renderer, MemorySegment rect) {
        try {
            return (boolean) RENDER_FILL_RECT.invokeExact(renderer, rect);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean renderDebugText(MemorySegment renderer, float x, float y, MemorySegment text) {
        try {
            return (boolean) RENDER_DEBUG_TEXT.invokeExact(renderer, x, y, text);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean renderPresent(MemorySegment renderer) {
        try {
            return (boolean) RENDER_PRESENT.invokeExact(renderer);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean pollEvent(MemorySegment event) {
        try {
            return (boolean) POLL_EVENT.invokeExact(event);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean waitEventTimeout(MemorySegment event, int timeoutMs) {
        try {
            return (boolean) WAIT_EVENT_TIMEOUT.invokeExact(event, timeoutMs);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean addEventWatch(MemorySegment filter, MemorySegment userdata) {
        try {
            return (boolean) ADD_EVENT_WATCH.invokeExact(filter, userdata);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("SDL3.dll has no " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    private static String string(MemorySegment cString) {
        return cString.equals(MemorySegment.NULL) ? null : cString.reinterpret(Long.MAX_VALUE).getString(0);
    }

    static RuntimeException failed(Throwable t) {
        return t instanceof RuntimeException r ? r : new IllegalStateException(t);
    }

    /** The DLL comes from the official release zip, unpacked into the git-ignored folder next to the sources. */
    private static Path locate() {
        String override = System.getProperty("sdl3.dll");
        if (override != null) {
            return Path.of(override);
        }
        for (String candidate : new String[] {"spikes/window/native/SDL3.dll", "native/SDL3.dll"}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new UnsatisfiedLinkError(
                "SDL3.dll not found. Unpack SDL3-<version>-win32-x64.zip into spikes/window/native/.");
    }
}
