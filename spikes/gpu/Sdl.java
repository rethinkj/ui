import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The part of the handwritten SDL3 bindings in {@code spikes/window/Sdl.java} this spike needs, plus the window size
 * functions. It is a copy because the source launcher only sees the folder of the file it starts, and classes it
 * compiles cannot reach package-private classes that come from the class path.
 */
final class Sdl {
    private Sdl() {}

    static final int INIT_VIDEO = 0x20;

    static final long WINDOW_RESIZABLE = 0x20;
    static final long WINDOW_MINIMIZED = 0x40;

    static final String PROP_WINDOW_WIN32_HWND_POINTER = "SDL.window.win32.hwnd";
    static final String PROP_WINDOW_WIN32_INSTANCE_POINTER = "SDL.window.win32.instance";

    static final int EVENT_QUIT = 0x100;
    static final int EVENT_WINDOW_SHOWN = 0x202;
    static final int EVENT_WINDOW_EXPOSED = 0x204;
    static final int EVENT_WINDOW_RESIZED = 0x206;
    static final int EVENT_WINDOW_PIXEL_SIZE_CHANGED = 0x207;
    static final int EVENT_WINDOW_MINIMIZED = 0x209;
    static final int EVENT_WINDOW_MAXIMIZED = 0x20A;
    static final int EVENT_WINDOW_RESTORED = 0x20B;
    static final int EVENT_WINDOW_CLOSE_REQUESTED = 0x210;
    static final int EVENT_KEY_DOWN = 0x300;

    /** SDL_Event is a union padded to 128 bytes. Window events: type, reserved, timestamp, windowID, data1, data2. */
    static final long EVENT_SIZE = 128;
    static final long EVENT_TYPE = 0;
    static final long EVENT_WINDOW_ID = 16;
    static final long WINDOW_EVENT_DATA1 = 20;
    static final long WINDOW_EVENT_DATA2 = 24;
    /** SDL_KeyboardEvent: type, reserved, timestamp, windowID, which, scancode, key, mod, raw, down, repeat. */
    static final long KEYBOARD_KEY = 28;
    static final long KEYBOARD_REPEAT = 37;

    /** bool (*SDL_EventFilter)(void *userdata, SDL_Event *event) */
    static final FunctionDescriptor EVENT_FILTER_CALLBACK = FunctionDescriptor.of(
            JAVA_BOOLEAN, ADDRESS, ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(EVENT_SIZE, JAVA_BYTE)));

    static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(locate(), Arena.global());

    static final FunctionDescriptor SDL_INIT = FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT);
    static final FunctionDescriptor SDL_QUIT = FunctionDescriptor.ofVoid();
    static final FunctionDescriptor SDL_GET_ERROR = FunctionDescriptor.of(ADDRESS);
    static final FunctionDescriptor SDL_GET_VERSION = FunctionDescriptor.of(JAVA_INT);
    static final FunctionDescriptor SDL_CREATE_WINDOW =
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor SDL_DESTROY_WINDOW = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor SDL_SET_WINDOW_POSITION =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor SDL_SET_WINDOW_SIZE =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor SDL_GET_WINDOW_SIZE_IN_PIXELS =
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor SDL_GET_WINDOW_FLAGS = FunctionDescriptor.of(JAVA_LONG, ADDRESS);
    static final FunctionDescriptor SDL_GET_WINDOW_ID = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor SDL_GET_WINDOW_PROPERTIES = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor SDL_GET_POINTER_PROPERTY =
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor SDL_POLL_EVENT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
    static final FunctionDescriptor SDL_WAIT_EVENT_TIMEOUT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT);
    static final FunctionDescriptor SDL_ADD_EVENT_WATCH = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS);

    private static final MethodHandle INIT = bind("SDL_Init", SDL_INIT);
    private static final MethodHandle QUIT = bind("SDL_Quit", SDL_QUIT);
    private static final MethodHandle GET_ERROR = bind("SDL_GetError", SDL_GET_ERROR);
    private static final MethodHandle GET_VERSION = bind("SDL_GetVersion", SDL_GET_VERSION);
    private static final MethodHandle CREATE_WINDOW = bind("SDL_CreateWindow", SDL_CREATE_WINDOW);
    private static final MethodHandle DESTROY_WINDOW = bind("SDL_DestroyWindow", SDL_DESTROY_WINDOW);
    private static final MethodHandle SET_WINDOW_POSITION = bind("SDL_SetWindowPosition", SDL_SET_WINDOW_POSITION);
    private static final MethodHandle SET_WINDOW_SIZE = bind("SDL_SetWindowSize", SDL_SET_WINDOW_SIZE);
    private static final MethodHandle GET_WINDOW_SIZE_IN_PIXELS =
            bind("SDL_GetWindowSizeInPixels", SDL_GET_WINDOW_SIZE_IN_PIXELS);
    private static final MethodHandle GET_WINDOW_FLAGS = bind("SDL_GetWindowFlags", SDL_GET_WINDOW_FLAGS);
    private static final MethodHandle GET_WINDOW_ID = bind("SDL_GetWindowID", SDL_GET_WINDOW_ID);
    private static final MethodHandle GET_WINDOW_PROPERTIES =
            bind("SDL_GetWindowProperties", SDL_GET_WINDOW_PROPERTIES);
    private static final MethodHandle GET_POINTER_PROPERTY = bind("SDL_GetPointerProperty", SDL_GET_POINTER_PROPERTY);
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
            MemorySegment text = (MemorySegment) GET_ERROR.invokeExact();
            return text.equals(MemorySegment.NULL) ? null : text.reinterpret(Long.MAX_VALUE).getString(0);
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

    static boolean setWindowSize(MemorySegment window, int w, int h) {
        try {
            return (boolean) SET_WINDOW_SIZE.invokeExact(window, w, h);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static boolean getWindowSizeInPixels(MemorySegment window, MemorySegment w, MemorySegment h) {
        try {
            return (boolean) GET_WINDOW_SIZE_IN_PIXELS.invokeExact(window, w, h);
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

    static boolean pollEvent(MemorySegment event) {
        try {
            return (boolean) POLL_EVENT.invokeExact(event);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** A timeout of -1 waits without limit. */
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

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("SDL3.dll has no " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    static RuntimeException failed(Throwable t) {
        return t instanceof RuntimeException r ? r : new IllegalStateException(t);
    }

    /** The DLL the window spike unpacked from the official release zip into its git-ignored folder. */
    private static Path locate() {
        String override = System.getProperty("sdl3.dll");
        if (override != null) {
            return Path.of(override);
        }
        for (String candidate : new String[] {"spikes/window/native/SDL3.dll", "../window/native/SDL3.dll"}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new UnsatisfiedLinkError(
                "SDL3.dll not found. Unpack SDL3-<version>-win32-x64.zip into spikes/window/native/.");
    }
}
