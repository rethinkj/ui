import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;

/**
 * Throwaway spike: a frameless, resizable SDL3 window with a title bar of its own, to find out what SDL3 lacks on
 * Windows for custom window chrome and taskbar integration. Not part of the Maven build.
 *
 * <p>Needs {@code SDL3.dll} from the official {@code SDL3-<version>-win32-x64.zip} on
 * github.com/libsdl-org/SDL/releases, unpacked into the git-ignored {@code spikes/window/native/}. Run from the
 * repository root:
 *
 * <pre>java --enable-native-access=ALL-UNNAMED spikes/window/Main.java [options]</pre>
 *
 * <p>Options set the state a key would otherwise toggle while the window has focus:
 *
 * <pre>
 * --corners=default|none|round|small   [C]  DwmSetWindowAttribute(DWMWA_WINDOW_CORNER_PREFERENCE)
 * --subclass                           [S]  answer WM_NCHITTEST with HTMAXBUTTON over the maximize button
 * --progress=none|indeterminate|normal|paused|error
 *                                      [P]  SDL_SetWindowProgressState / SDL_SetWindowProgressValue
 * --overlay                            [O]  ITaskbarList3::SetOverlayIcon
 * --pos=x,y                                 window position (default 300,180)
 * --backdrop                                a plain light window behind, so a screenshot shows shadow and corners
 * --exit-after=seconds                      close by itself
 * </pre>
 */
public final class Main {
    private Main() {}

    static final String TITLE = "rethinkj window spike";
    static final int TITLE_BAR = 36;
    static final int BUTTON_WIDTH = 46;
    static final int BORDER = 6;
    static final int CORNER = 14;
    static final int BACKDROP_MARGIN = 120;

    static final int BUTTON_NONE = -1;
    static final int BUTTON_MINIMIZE = 0;
    static final int BUTTON_MAXIMIZE = 1;
    static final int BUTTON_CLOSE = 2;
    static final List<String> BUTTON_NAMES = List.of("minimize", "maximize", "close");
    static final int[][] BUTTON_COLORS = {{196, 150, 40}, {52, 150, 84}, {196, 60, 60}};

    static final List<String> CORNER_NAMES = List.of("default", "none", "round", "small");
    static final List<String> PROGRESS_NAMES = List.of("none", "indeterminate", "normal", "paused", "error");
    static final long SUBCLASS_ID = 1;

    static final Arena ARENA = Arena.global();
    static final MemorySegment EVENT = ARENA.allocate(Sdl.EVENT_SIZE, 8);
    static final MemorySegment WIDTH = ARENA.allocate(JAVA_INT);
    static final MemorySegment HEIGHT = ARENA.allocate(JAVA_INT);
    static final MemorySegment FRECT = ARENA.allocate(Sdl.FRECT);
    static final MemorySegment TEXT = ARENA.allocate(512);
    static final MemorySegment POINT = ARENA.allocate(Win32.POINT);
    static final MemorySegment RECT = ARENA.allocate(Win32.RECT);
    static final MemorySegment DWORD = ARENA.allocate(JAVA_INT);
    static final MemorySegment TRACK = ARENA.allocate(Win32.TRACKMOUSEEVENT);

    static final MemorySegment HIT_TEST = upcall("hitTest", Sdl.HIT_TEST_CALLBACK);
    static final MemorySegment EVENT_WATCH = upcall("eventWatch", Sdl.EVENT_FILTER_CALLBACK);
    static final MemorySegment SUBCLASS_PROC = upcall("subclassProc", Win32.SUBCLASS_PROC);

    static final long START = System.nanoTime();

    static MemorySegment window = MemorySegment.NULL;
    static MemorySegment renderer = MemorySegment.NULL;
    static MemorySegment hwnd = MemorySegment.NULL;
    static MemorySegment backdrop = MemorySegment.NULL;
    static MemorySegment backdropRenderer = MemorySegment.NULL;
    static int windowId;
    static int width;
    static int height;
    static String versionLine = "";
    static String lastEvent = "";
    static boolean running = true;

    static int hover = BUTTON_NONE;
    static int pressed = BUTTON_NONE;

    static int corners;
    static int cornersResult;
    static int progress;
    static boolean overlay;
    static int overlayResult;
    static MemorySegment taskbarList = MemorySegment.NULL;
    static MemorySegment overlayIcon = MemorySegment.NULL;

    static boolean subclassed;
    static long subclassMessages;
    static boolean ncHover;
    static boolean ncPressed;
    static boolean ncTracking;
    static boolean ncClicked;

    public static void main(String[] args) {
        int x = 300;
        int y = 180;
        int initialCorners = 0;
        int initialProgress = 0;
        boolean initialSubclass = false;
        boolean pendingOverlay = false;
        boolean withBackdrop = false;
        double exitAfter = 0;
        for (String arg : args) {
            String value = arg.substring(arg.indexOf('=') + 1);
            if (arg.startsWith("--pos=")) {
                x = Integer.parseInt(value.split(",")[0]);
                y = Integer.parseInt(value.split(",")[1]);
            } else if (arg.startsWith("--corners=")) {
                initialCorners = indexOf(CORNER_NAMES, value);
            } else if (arg.startsWith("--progress=")) {
                initialProgress = indexOf(PROGRESS_NAMES, value);
            } else if (arg.equals("--subclass")) {
                initialSubclass = true;
            } else if (arg.equals("--overlay")) {
                pendingOverlay = true;
            } else if (arg.equals("--backdrop")) {
                withBackdrop = true;
            } else if (arg.startsWith("--exit-after=")) {
                exitAfter = Double.parseDouble(value);
            } else {
                throw new IllegalArgumentException("unknown option " + arg);
            }
        }

        check(Sdl.init(Sdl.INIT_VIDEO), "SDL_Init");
        int version = Sdl.getVersion();
        versionLine = "SDL %d.%d.%d".formatted(version / 1_000_000, version / 1000 % 1000, version % 1000);
        log("%s, revision %s", versionLine, Sdl.getRevision());

        if (withBackdrop) {
            backdrop = Sdl.createWindow(utf8(TITLE + " backdrop"), 900 + 2 * BACKDROP_MARGIN,
                    600 + 2 * BACKDROP_MARGIN, Sdl.WINDOW_BORDERLESS | Sdl.WINDOW_UTILITY | Sdl.WINDOW_NOT_FOCUSABLE);
            check(!backdrop.equals(MemorySegment.NULL), "SDL_CreateWindow (backdrop)");
            Sdl.setWindowPosition(backdrop, x - BACKDROP_MARGIN, y - BACKDROP_MARGIN);
            backdropRenderer = Sdl.createRenderer(backdrop, MemorySegment.NULL);
        }

        window = Sdl.createWindow(utf8(TITLE), 900, 600, Sdl.WINDOW_BORDERLESS | Sdl.WINDOW_RESIZABLE);
        check(!window.equals(MemorySegment.NULL), "SDL_CreateWindow");
        windowId = Sdl.getWindowID(window);
        Sdl.setWindowPosition(window, x, y);
        Sdl.setWindowMinimumSize(window, 480, 240);
        renderer = Sdl.createRenderer(window, MemorySegment.NULL);
        check(!renderer.equals(MemorySegment.NULL), "SDL_CreateRenderer");
        Sdl.setRenderVSync(renderer, 1);
        versionLine += ", renderer " + Sdl.getRendererName(renderer);

        hwnd = Sdl.getPointerProperty(Sdl.getWindowProperties(window),
                utf8(Sdl.PROP_WINDOW_WIN32_HWND_POINTER), MemorySegment.NULL);
        check(!hwnd.equals(MemorySegment.NULL), "SDL_PROP_WINDOW_WIN32_HWND_POINTER");
        log("renderer %s, hwnd 0x%X, dpi %d", Sdl.getRendererName(renderer), hwnd.address(),
                Win32.getDpiForWindow(hwnd));
        logWindowFacts();

        check(Sdl.setWindowHitTest(window, HIT_TEST, MemorySegment.NULL), "SDL_SetWindowHitTest");
        check(Sdl.addEventWatch(EVENT_WATCH, MemorySegment.NULL), "SDL_AddEventWatch");
        Sdl.raiseWindow(window);

        if (initialCorners != 0) {
            setCorners(initialCorners);
        }
        if (initialSubclass) {
            setSubclass(true);
        }
        if (initialProgress != 0) {
            setProgress(initialProgress);
        }

        while (running) {
            long frameStart = System.nanoTime();
            boolean minimized = (Sdl.getWindowFlags(window) & Sdl.WINDOW_MINIMIZED) != 0;
            if (minimized && Sdl.waitEventTimeout(EVENT, 100)) {
                handle(EVENT);
            }
            while (Sdl.pollEvent(EVENT)) {
                handle(EVENT);
            }
            if (ncClicked) {
                // The click on the maximize button arrived as a non-client message in the subclass procedure.
                ncClicked = false;
                click(BUTTON_MAXIMIZE);
            }
            // The taskbar button has to exist before an overlay can be set on it.
            if (pendingOverlay && seconds() > 1.5) {
                pendingOverlay = false;
                setOverlay(true);
            }
            if (exitAfter > 0 && seconds() > exitAfter) {
                running = false;
            }
            if (!minimized) {
                render();
                // Vsync paces the loop; this only keeps it from spinning when a present returns at once.
                if (System.nanoTime() - frameStart < 2_000_000) {
                    sleep(5);
                }
            }
        }

        log("closing");
        if (subclassed) {
            setSubclass(false);
        }
        if (!taskbarList.equals(MemorySegment.NULL)) {
            Win32.taskbarListSetOverlayIcon(taskbarList, hwnd, MemorySegment.NULL, MemorySegment.NULL);
            Win32.release(taskbarList);
        }
        if (!overlayIcon.equals(MemorySegment.NULL)) {
            Win32.destroyIcon(overlayIcon);
        }
        Sdl.destroyRenderer(renderer);
        Sdl.destroyWindow(window);
        if (!backdrop.equals(MemorySegment.NULL)) {
            Sdl.destroyRenderer(backdropRenderer);
            Sdl.destroyWindow(backdrop);
        }
        Sdl.quit();
        log("closed");
    }

    // ---- title bar geometry ------------------------------------------------------------------

    static void updateSize() {
        Sdl.getWindowSize(window, WIDTH, HEIGHT);
        width = WIDTH.get(JAVA_INT, 0);
        height = HEIGHT.get(JAVA_INT, 0);
    }

    static boolean maximized() {
        return (Sdl.getWindowFlags(window) & Sdl.WINDOW_MAXIMIZED) != 0;
    }

    static int buttonLeft(int button) {
        return width - (3 - button) * BUTTON_WIDTH;
    }

    static int buttonAt(int x, int y) {
        if (y < 0 || y >= TITLE_BAR || x < buttonLeft(BUTTON_MINIMIZE) || x >= width) {
            return BUTTON_NONE;
        }
        return (x - buttonLeft(BUTTON_MINIMIZE)) / BUTTON_WIDTH;
    }

    /** What a point in the window is: a resize edge, the draggable title bar, or ordinary content. */
    static int zone(int x, int y) {
        updateSize();
        if (!maximized()) {
            boolean left = x < BORDER;
            boolean right = x >= width - BORDER;
            boolean top = y < BORDER;
            boolean bottom = y >= height - BORDER;
            boolean nearLeft = x < CORNER;
            boolean nearRight = x >= width - CORNER;
            boolean nearTop = y < CORNER;
            boolean nearBottom = y >= height - CORNER;
            if ((top && nearLeft) || (left && nearTop)) {
                return Sdl.HITTEST_RESIZE_TOPLEFT;
            }
            if ((top && nearRight) || (right && nearTop)) {
                return Sdl.HITTEST_RESIZE_TOPRIGHT;
            }
            if ((bottom && nearLeft) || (left && nearBottom)) {
                return Sdl.HITTEST_RESIZE_BOTTOMLEFT;
            }
            if ((bottom && nearRight) || (right && nearBottom)) {
                return Sdl.HITTEST_RESIZE_BOTTOMRIGHT;
            }
            if (top) {
                return Sdl.HITTEST_RESIZE_TOP;
            }
            if (bottom) {
                return Sdl.HITTEST_RESIZE_BOTTOM;
            }
            if (left) {
                return Sdl.HITTEST_RESIZE_LEFT;
            }
            if (right) {
                return Sdl.HITTEST_RESIZE_RIGHT;
            }
        }
        if (buttonAt(x, y) != BUTTON_NONE) {
            return Sdl.HITTEST_NORMAL;
        }
        return y < TITLE_BAR ? Sdl.HITTEST_DRAGGABLE : Sdl.HITTEST_NORMAL;
    }

    // ---- callbacks from native code ----------------------------------------------------------

    /** SDL_HitTest: SDL calls this while it answers WM_NCHITTEST. */
    static int hitTest(MemorySegment win, MemorySegment area, MemorySegment data) {
        try {
            return zone(area.get(JAVA_INT, 0), area.get(JAVA_INT, 4));
        } catch (Throwable t) {
            t.printStackTrace();
            return Sdl.HITTEST_NORMAL;
        }
    }

    /** SDL_EventFilter: redraws while Windows runs its modal move and resize loop and SDL_PollEvent does not return. */
    static boolean eventWatch(MemorySegment userdata, MemorySegment event) {
        try {
            if (event.get(JAVA_INT, Sdl.EVENT_TYPE) == Sdl.EVENT_WINDOW_EXPOSED
                    && event.get(JAVA_INT, Sdl.EVENT_WINDOW_ID) == windowId
                    && event.get(JAVA_INT, Sdl.WINDOW_EVENT_DATA1) == 1) {
                render();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return true;
    }

    /**
     * SUBCLASSPROC in front of SDL's window procedure. SDL's hit test has no value for the maximize button, so this
     * answers WM_NCHITTEST with HTMAXBUTTON there, which is what makes Windows 11 show the snap layout flyout. The
     * button is then non-client area: SDL sends no mouse events for it, and the click has to be handled here.
     */
    static long subclassProc(MemorySegment h, int msg, long wParam, long lParam, long id, long refData) {
        try {
            subclassMessages++;
            switch (msg) {
                case Win32.WM_NCHITTEST -> {
                    POINT.set(JAVA_INT, 0, (short) lParam);
                    POINT.set(JAVA_INT, 4, (short) (lParam >> 16));
                    Win32.screenToClient(h, POINT);
                    int x = POINT.get(JAVA_INT, 0);
                    int y = POINT.get(JAVA_INT, 4);
                    if (zone(x, y) == Sdl.HITTEST_NORMAL && buttonAt(x, y) == BUTTON_MAXIMIZE) {
                        return Win32.HTMAXBUTTON;
                    }
                }
                case Win32.WM_NCMOUSEMOVE -> {
                    boolean over = wParam == Win32.HTMAXBUTTON;
                    if (over != ncHover) {
                        ncHover = over;
                        log("subclass: WM_NCMOUSEMOVE, over maximize button = %b", over);
                    }
                    if (over && !ncTracking) {
                        TRACK.set(JAVA_INT, 0, (int) Win32.TRACKMOUSEEVENT.byteSize());
                        TRACK.set(JAVA_INT, 4, Win32.TME_LEAVE | Win32.TME_NONCLIENT);
                        TRACK.set(ADDRESS, 8, h);
                        ncTracking = Win32.trackMouseEvent(TRACK);
                    }
                }
                case Win32.WM_NCMOUSELEAVE -> {
                    if (ncHover) {
                        log("subclass: WM_NCMOUSELEAVE");
                    }
                    ncTracking = false;
                    ncHover = false;
                    ncPressed = false;
                }
                case Win32.WM_MOUSEMOVE -> ncHover = false;
                case Win32.WM_NCLBUTTONDOWN, Win32.WM_NCLBUTTONDBLCLK -> {
                    if (wParam == Win32.HTMAXBUTTON) {
                        // Not passed on: DefWindowProc would start its own tracking loop for a caption button.
                        log("subclass: WM_NCLBUTTONDOWN on HTMAXBUTTON");
                        ncPressed = true;
                        return 0;
                    }
                }
                case Win32.WM_NCLBUTTONUP -> {
                    if (wParam == Win32.HTMAXBUTTON) {
                        log("subclass: WM_NCLBUTTONUP on HTMAXBUTTON, pressed = %b", ncPressed);
                        ncClicked = ncPressed;
                        ncPressed = false;
                        return 0;
                    }
                }
                default -> { }
            }
            return Win32.defSubclassProc(h, msg, wParam, lParam);
        } catch (Throwable t) {
            t.printStackTrace();
            return 0;
        }
    }

    static MemorySegment upcall(String method, FunctionDescriptor descriptor) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(Main.class, method, descriptor.toMethodType());
            return Sdl.LINKER.upcallStub(target, descriptor, ARENA);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- events ------------------------------------------------------------------------------

    static void handle(MemorySegment event) {
        int type = event.get(JAVA_INT, Sdl.EVENT_TYPE);
        if (type == Sdl.EVENT_QUIT) {
            running = false;
            return;
        }
        if (type < Sdl.EVENT_WINDOW_SHOWN || type > Sdl.EVENT_MOUSE_BUTTON_UP
                || event.get(JAVA_INT, Sdl.EVENT_WINDOW_ID) != windowId) {
            return;
        }
        int data1 = event.get(JAVA_INT, Sdl.WINDOW_EVENT_DATA1);
        int data2 = event.get(JAVA_INT, Sdl.WINDOW_EVENT_DATA2);
        switch (type) {
            case Sdl.EVENT_WINDOW_CLOSE_REQUESTED -> running = false;
            case Sdl.EVENT_WINDOW_MOVED -> note("moved to %d,%d", data1, data2);
            case Sdl.EVENT_WINDOW_RESIZED -> note("resized to %dx%d", data1, data2);
            case Sdl.EVENT_WINDOW_MINIMIZED -> note("minimized");
            case Sdl.EVENT_WINDOW_MAXIMIZED -> note("maximized");
            case Sdl.EVENT_WINDOW_RESTORED -> note("restored");
            case Sdl.EVENT_WINDOW_FOCUS_GAINED -> note("focus gained");
            case Sdl.EVENT_WINDOW_FOCUS_LOST -> note("focus lost");
            case Sdl.EVENT_WINDOW_MOUSE_LEAVE -> hover = BUTTON_NONE;
            case Sdl.EVENT_MOUSE_MOTION -> hover = buttonAt(
                    (int) event.get(JAVA_FLOAT, Sdl.MOUSE_MOTION_X), (int) event.get(JAVA_FLOAT, Sdl.MOUSE_MOTION_Y));
            case Sdl.EVENT_MOUSE_BUTTON_DOWN, Sdl.EVENT_MOUSE_BUTTON_UP -> {
                if (event.get(JAVA_BYTE, Sdl.MOUSE_BUTTON_BUTTON) != Sdl.BUTTON_LEFT) {
                    return;
                }
                int button = buttonAt(
                        (int) event.get(JAVA_FLOAT, Sdl.MOUSE_BUTTON_X), (int) event.get(JAVA_FLOAT, Sdl.MOUSE_BUTTON_Y));
                if (type == Sdl.EVENT_MOUSE_BUTTON_DOWN) {
                    pressed = button;
                } else {
                    if (button != BUTTON_NONE && button == pressed) {
                        click(button);
                    }
                    pressed = BUTTON_NONE;
                }
            }
            case Sdl.EVENT_KEY_DOWN -> {
                if (!event.get(JAVA_BOOLEAN, Sdl.KEYBOARD_REPEAT)) {
                    key(event.get(JAVA_INT, Sdl.KEYBOARD_KEY));
                }
            }
            default -> { }
        }
    }

    static void key(int key) {
        switch (key) {
            case 'c' -> setCorners((corners + 1) % CORNER_NAMES.size());
            case 's' -> setSubclass(!subclassed);
            case 'p' -> setProgress((progress + 1) % PROGRESS_NAMES.size());
            case 'o' -> setOverlay(!overlay);
            case 0x1B -> running = false;
            default -> { }
        }
    }

    static void click(int button) {
        note("click on %s", BUTTON_NAMES.get(button));
        switch (button) {
            case BUTTON_MINIMIZE -> Sdl.minimizeWindow(window);
            case BUTTON_MAXIMIZE -> {
                if (maximized()) {
                    Sdl.restoreWindow(window);
                } else {
                    Sdl.maximizeWindow(window);
                }
            }
            default -> running = false;
        }
    }

    // ---- the things under test ---------------------------------------------------------------

    static void logWindowFacts() {
        long style = Win32.getWindowLongPtr(hwnd, Win32.GWL_STYLE);
        long exStyle = Win32.getWindowLongPtr(hwnd, Win32.GWL_EXSTYLE);
        log("style 0x%08X (WS_POPUP=%b WS_CAPTION=%b WS_THICKFRAME=%b WS_SYSMENU=%b WS_MINIMIZEBOX=%b "
                + "WS_MAXIMIZEBOX=%b), exstyle 0x%08X", style, (style & 0x80000000L) != 0,
                (style & 0x00C00000L) == 0x00C00000L, (style & 0x00040000L) != 0, (style & 0x00080000L) != 0,
                (style & 0x00020000L) != 0, (style & 0x00010000L) != 0, exStyle);
        int enabledResult = Win32.dwmGetWindowAttribute(hwnd, Win32.DWMWA_NCRENDERING_ENABLED, DWORD);
        int ncRendering = DWORD.get(JAVA_INT, 0);
        Win32.getWindowRect(hwnd, RECT);
        String windowRect = rect(RECT);
        int boundsResult = Win32.dwmGetWindowAttribute(hwnd, Win32.DWMWA_EXTENDED_FRAME_BOUNDS, RECT);
        log("DWM non-client rendering enabled = %d (%s), window rect %s, extended frame bounds %s (%s)", ncRendering,
                Win32.hresult(enabledResult), windowRect, rect(RECT), Win32.hresult(boundsResult));
    }

    static void setCorners(int preference) {
        corners = preference;
        DWORD.set(JAVA_INT, 0, preference); // the DWMWCP_* values are 0..3 in the order of CORNER_NAMES
        cornersResult = Win32.dwmSetWindowAttribute(hwnd, Win32.DWMWA_WINDOW_CORNER_PREFERENCE, DWORD);
        note("DwmSetWindowAttribute(corner preference = %s) -> %s", CORNER_NAMES.get(preference),
                Win32.hresult(cornersResult));
    }

    static void setSubclass(boolean on) {
        boolean ok = on
                ? Win32.setWindowSubclass(hwnd, SUBCLASS_PROC, SUBCLASS_ID, 0)
                : Win32.removeWindowSubclass(hwnd, SUBCLASS_PROC, SUBCLASS_ID);
        subclassed = on && ok;
        ncHover = false;
        ncPressed = false;
        note("%s -> %b", on ? "SetWindowSubclass" : "RemoveWindowSubclass", ok);
    }

    static void setProgress(int state) {
        progress = state;
        boolean stateOk = Sdl.setWindowProgressState(window, state);
        String error = stateOk ? "" : Sdl.getError();
        boolean valueOk = state < Sdl.PROGRESS_STATE_NORMAL || Sdl.setWindowProgressValue(window, 0.6f);
        error += valueOk ? "" : Sdl.getError();
        note("SDL_SetWindowProgressState(%s) -> %b, SDL_SetWindowProgressValue(0.6) -> %b %s",
                PROGRESS_NAMES.get(state), stateOk, valueOk, error);
    }

    static void setOverlay(boolean on) {
        if (taskbarList.equals(MemorySegment.NULL)) {
            int hr = Win32.coInitializeEx(Win32.COINIT_APARTMENTTHREADED);
            log("CoInitializeEx -> %s", Win32.hresult(hr));
            MemorySegment out = ARENA.allocate(ADDRESS);
            hr = Win32.coCreateInstance(Win32.guid(ARENA, Win32.CLSID_TASKBAR_LIST), Win32.CLSCTX_INPROC_SERVER,
                    Win32.guid(ARENA, Win32.IID_ITASKBAR_LIST3), out);
            log("CoCreateInstance(CLSID_TaskbarList, IID_ITaskbarList3) -> %s", Win32.hresult(hr));
            if (hr < 0) {
                overlayResult = hr;
                return;
            }
            taskbarList = out.get(ADDRESS, 0);
            log("ITaskbarList::HrInit -> %s", Win32.hresult(Win32.taskbarListHrInit(taskbarList)));
            overlayIcon = createDotIcon();
            log("CreateIcon -> 0x%X", overlayIcon.address());
        }
        overlayResult = on
                ? Win32.taskbarListSetOverlayIcon(taskbarList, hwnd, overlayIcon, Win32.wideString(ARENA, "spike"))
                : Win32.taskbarListSetOverlayIcon(taskbarList, hwnd, MemorySegment.NULL, MemorySegment.NULL);
        overlay = on && overlayResult >= 0;
        note("ITaskbarList3::SetOverlayIcon(%s) -> %s", on ? "icon" : "NULL", Win32.hresult(overlayResult));
    }

    /** A 16x16 magenta disc: 32-bit BGRA colour bits plus the 1-bit AND mask CreateIcon wants. */
    static MemorySegment createDotIcon() {
        int size = 16;
        MemorySegment and = ARENA.allocate(size * 2);
        MemorySegment xor = ARENA.allocate(size * size * 4, 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = x - 7.5;
                double dy = y - 7.5;
                if (dx * dx + dy * dy <= 7.5 * 7.5) {
                    xor.setAtIndex(JAVA_INT, y * size + x, 0xFFFF00DC);
                } else {
                    long index = y * 2 + x / 8;
                    and.set(JAVA_BYTE, index, (byte) (and.get(JAVA_BYTE, index) | (0x80 >> (x % 8))));
                }
            }
        }
        return Win32.createIcon(size, size, 1, 32, and, xor);
    }

    // ---- drawing -----------------------------------------------------------------------------

    static void render() {
        if (!backdropRenderer.equals(MemorySegment.NULL)) {
            Sdl.setRenderDrawColor(backdropRenderer, 236, 236, 236, 255);
            Sdl.renderClear(backdropRenderer);
            Sdl.renderPresent(backdropRenderer);
        }
        updateSize();
        Sdl.setRenderDrawColor(renderer, 24, 26, 32, 255);
        Sdl.renderClear(renderer);
        fill(0, 0, width, TITLE_BAR, 44, 48, 60);
        for (int button = BUTTON_MINIMIZE; button <= BUTTON_CLOSE; button++) {
            boolean hot = hover == button || (button == BUTTON_MAXIMIZE && ncHover);
            boolean down = pressed == button || (button == BUTTON_MAXIMIZE && ncPressed);
            int shift = down ? -40 : hot ? 50 : 0;
            int[] color = BUTTON_COLORS[button];
            fill(buttonLeft(button), 0, BUTTON_WIDTH, TITLE_BAR, color[0] + shift, color[1] + shift, color[2] + shift);
        }
        text(12, 14, 230, TITLE + "  -  " + versionLine);
        int y = 60;
        text(16, y, 200, "size %dx%d, maximized = %b".formatted(width, height, maximized()));
        text(16, y += 22, 200, "[C] DWM corner preference: %s (%s)"
                .formatted(CORNER_NAMES.get(corners), Win32.hresult(cornersResult)));
        text(16, y += 16, 200, "[S] WM_NCHITTEST subclass, HTMAXBUTTON on the green button: %s (%d messages seen)"
                .formatted(subclassed ? "on" : "off", subclassMessages));
        text(16, y += 16, 200, "[P] taskbar progress through SDL: %s".formatted(PROGRESS_NAMES.get(progress)));
        text(16, y += 16, 200, "[O] taskbar overlay icon through ITaskbarList3: %s (%s)"
                .formatted(overlay ? "on" : "off", Win32.hresult(overlayResult)));
        text(16, y += 16, 200, "[Esc] quit");
        text(16, y + 22, 150, "last: " + lastEvent);
        Sdl.renderPresent(renderer);
    }

    static void fill(int x, int y, int w, int h, int r, int g, int b) {
        Sdl.setRenderDrawColor(renderer, clamp(r), clamp(g), clamp(b), 255);
        FRECT.set(JAVA_FLOAT, 0, x);
        FRECT.set(JAVA_FLOAT, 4, y);
        FRECT.set(JAVA_FLOAT, 8, w);
        FRECT.set(JAVA_FLOAT, 12, h);
        Sdl.renderFillRect(renderer, FRECT);
    }

    static void text(int x, int y, int gray, String text) {
        Sdl.setRenderDrawColor(renderer, gray, gray, gray, 255);
        TEXT.setString(0, text.length() > 120 ? text.substring(0, 120) : text);
        Sdl.renderDebugText(renderer, x, y, TEXT);
    }

    // ---- helpers -----------------------------------------------------------------------------

    static String rect(MemorySegment rect) {
        return "%d,%d,%d,%d".formatted(rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 4), rect.get(JAVA_INT, 8),
                rect.get(JAVA_INT, 12));
    }

    static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    static MemorySegment utf8(String text) {
        return ARENA.allocateFrom(text);
    }

    static int indexOf(List<String> names, String name) {
        int index = names.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException(name + " is not one of " + names);
        }
        return index;
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException(what + " failed: " + Sdl.getError());
        }
    }

    static double seconds() {
        return (System.nanoTime() - START) / 1e9;
    }

    static void note(String format, Object... args) {
        lastEvent = format.formatted(args);
        log("%s", lastEvent);
    }

    static void log(String format, Object... args) {
        System.out.printf("%8.3f  %s%n", seconds(), format.formatted(args));
    }

    static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
