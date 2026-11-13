import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Throwaway spike: wgpu-native draws a rectangle into an SDL3 window through handwritten FFM bindings, to find out
 * how to bind wgpu-native from Java. Not part of the Maven build.
 *
 * <p>Needs {@code wgpu-windows-x86_64-msvc-release.zip} from github.com/gfx-rs/wgpu-native/releases unpacked into the
 * git-ignored {@code spikes/gpu/native/}, and the {@code SDL3.dll} of the window spike in
 * {@code spikes/window/native/}. Run from the repository root:
 *
 * <pre>java --enable-native-access=ALL-UNNAMED spikes/gpu/Main.java [options]</pre>
 *
 * <p>On Linux it needs {@code wgpu-linux-x86_64-release.zip} in the same place and the SDL3 the window spike built
 * into {@code spikes/window/native/sdl3/}. The surface comes from the X11 display and window, or from the Wayland
 * display and surface when SDL runs on Wayland ({@code SDL_VIDEO_DRIVER=wayland}).
 *
 * <pre>
 * --backend=default|dx12|vulkan|gl   backends the wgpu instance may use (default: all, wgpu picks)
 * --adapters                         list every adapter of every backend and exit
 * --power=default|low|high           WGPURequestAdapterOptions.powerPreference (default: undefined)
 * --log=error|warn|info|debug|trace  level of the wgpu log callback (default warn)
 * --validation                       ask the backend for its own validation layer
 * --pos=x,y  --size=w,h              window position and client size (default 200,150 and 800,500)
 * --exit-after=seconds               close by itself
 * --idle-report                      log the frame count once a second while nothing happens
 * --provoke=name[,name...]           after the first frame, make wgpu report an error and print what arrives:
 *     shader        WGSL that does not compile, inside an error scope
 *     entrypoint    a pipeline whose vertex entry point does not exist, inside an error scope
 *     format        a pipeline for another target format drawn into the window's render pass, inside a scope
 *     depthslice    a colour attachment left zero-initialised (depthSlice 0 instead of UNDEFINED), inside a scope
 *     uncaptured    the broken shader without an error scope: arrives at the uncaptured-error callback
 *     lifetime      keeps what a callback was handed and touches it after the callback returned
 *   these end the process, so give one at a time:
 *     fatal-submit-invalid   the frame of "format" submitted although recording it reported an error
 *     fatal-waitany          wgpuInstanceWaitAny, which wgpu-native does not implement
 *     fatal-configure-zero   wgpuSurfaceConfigure with a width of 0
 *     fatal-no-callback      a device without an uncaptured-error callback, then the broken shader
 *     fatal-upcall-throws    a callback that throws
 *     fatal-stale-stub       a callback whose upcall stub was freed before wgpu calls it
 * </pre>
 *
 * Keys: [C] changes the colours (one redraw), [Esc] quits.
 */
public final class Main {
    private Main() {}

    static final String TITLE = "rethinkj gpu spike";
    /** The rectangle keeps this distance to every edge of the window, whatever its size. */
    static final int MARGIN = 60;
    static final double[] CLEAR = {0.09, 0.10, 0.13};
    /** Corner colours of the rectangle: top left, top right, bottom left, bottom right. Two sets, [C] switches. */
    static final float[][][] PALETTES = {
        {{1.00f, 0.45f, 0.10f}, {0.95f, 0.15f, 0.55f}, {0.10f, 0.70f, 0.75f}, {0.20f, 0.30f, 0.95f}},
        {{0.20f, 0.80f, 0.30f}, {0.95f, 0.90f, 0.20f}, {0.15f, 0.35f, 0.25f}, {0.90f, 0.40f, 0.10f}},
    };
    static final int VERTEX_STRIDE = 20;
    static final int VERTEX_COUNT = 6;

    static final String SHADER = """
            struct VertexOut {
                @builtin(position) position: vec4f,
                @location(0) color: vec3f,
            }

            @vertex
            fn vs_main(@location(0) position: vec2f, @location(1) color: vec3f) -> VertexOut {
                var out: VertexOut;
                out.position = vec4f(position, 0.0, 1.0);
                out.color = color;
                return out;
            }

            @fragment
            fn fs_main(in: VertexOut) -> @location(0) vec4f {
                return vec4f(in.color, 1.0);
            }
            """;

    /** The same shader with a vec3f assigned to the vec4f position. */
    static final String BROKEN_SHADER = SHADER.replace("vec4f(position, 0.0, 1.0)", "vec3f(position, 0.0)");

    static final Arena ARENA = Arena.global();
    static final MemorySegment EVENT = ARENA.allocate(Sdl.EVENT_SIZE, 8);
    static final MemorySegment WIDTH = ARENA.allocate(JAVA_INT);
    static final MemorySegment HEIGHT = ARENA.allocate(JAVA_INT);

    // Structs the frame loop fills again and again.
    static final MemorySegment SURFACE_CONFIGURATION = ARENA.allocate(Wgpu.SURFACE_CONFIGURATION);
    static final MemorySegment SURFACE_TEXTURE = ARENA.allocate(Wgpu.SURFACE_TEXTURE);
    static final MemorySegment COLOR_ATTACHMENT = ARENA.allocate(Wgpu.RENDER_PASS_COLOR_ATTACHMENT);
    static final MemorySegment RENDER_PASS = ARENA.allocate(Wgpu.RENDER_PASS_DESCRIPTOR);
    static final MemorySegment VERTICES = ARENA.allocate((long) VERTEX_STRIDE * VERTEX_COUNT, 4);
    static final MemorySegment COMMANDS = ARENA.allocate(ADDRESS);
    /** Where the WGPUFuture a call returns by value is written; it is always {id = 0} in wgpu-native. */
    static final SegmentAllocator FUTURE = SegmentAllocator.prefixAllocator(ARENA.allocate(Wgpu.FUTURE));

    static final MemorySegment ON_LOG = upcall(ARENA, "onLog", Wgpu.LOG_CALLBACK);
    static final MemorySegment ON_ADAPTER = upcall(ARENA, "onAdapter", Wgpu.REQUEST_ADAPTER_CALLBACK);
    static final MemorySegment ON_DEVICE = upcall(ARENA, "onDevice", Wgpu.REQUEST_DEVICE_CALLBACK);
    static final MemorySegment ON_UNCAPTURED_ERROR = upcall(ARENA, "onUncapturedError", Wgpu.UNCAPTURED_ERROR_CALLBACK);
    static final MemorySegment ON_DEVICE_LOST = upcall(ARENA, "onDeviceLost", Wgpu.DEVICE_LOST_CALLBACK);
    static final MemorySegment ON_POP_ERROR_SCOPE = upcall(ARENA, "onPopErrorScope", Wgpu.POP_ERROR_SCOPE_CALLBACK);
    static final MemorySegment ON_POP_THROWS = upcall(ARENA, "onPopThrows", Wgpu.POP_ERROR_SCOPE_CALLBACK);
    static final MemorySegment EVENT_WATCH = upcall(ARENA, "eventWatch", Sdl.EVENT_FILTER_CALLBACK);
    static final MemorySegment POP_ERROR_SCOPE_INFO =
            callbackInfo(Wgpu.CALLBACK_MODE_ALLOW_PROCESS_EVENTS, ON_POP_ERROR_SCOPE);

    static final long START = System.nanoTime();
    static final Thread MAIN_THREAD = Thread.currentThread();

    static MemorySegment window = MemorySegment.NULL;
    static int windowId;
    static boolean running = true;
    static boolean dirty = true;
    static String dirtyReason = "first frame";
    static int palette;
    static long frames;
    static long liveExposes;

    static MemorySegment instance = MemorySegment.NULL;
    static MemorySegment surface = MemorySegment.NULL;
    static MemorySegment adapter = MemorySegment.NULL;
    static MemorySegment device = MemorySegment.NULL;
    static MemorySegment queue = MemorySegment.NULL;
    static MemorySegment pipeline = MemorySegment.NULL;
    static MemorySegment vertexBuffer = MemorySegment.NULL;
    static int surfaceFormat;
    static int configuredWidth;
    static int configuredHeight;
    /** A pipeline the next frame uses instead of the real one, for --provoke=format. */
    static MemorySegment framePipeline = MemorySegment.NULL;
    static boolean zeroDepthSlice;
    /** For --provoke=fatal-submit-invalid: submit a command buffer although recording it reported an error. */
    static boolean submitInvalid;
    static String frameError = "";
    static boolean traceScopeCallback;

    /** The wgpu function the main thread is in, so a callback can tell whether it runs inside that call. */
    static String inCall = "";
    static String requestMessage = "";
    static String scopeMessage = "";
    static MemorySegment keptMessage = MemorySegment.NULL;
    static MemorySegment keptData = MemorySegment.NULL;
    static long keptLength;

    public static void main(String[] args) {
        int x = 200;
        int y = 150;
        int w = 800;
        int h = 500;
        long backends = Wgpu.INSTANCE_BACKEND_ALL;
        String backendName = "default";
        long flags = 0;
        int power = 0;
        int logLevel = Wgpu.LOG_LEVEL_WARN;
        boolean listAdapters = false;
        boolean idleReport = false;
        double exitAfter = 0;
        List<String> provoke = new ArrayList<>();
        for (String arg : args) {
            String value = arg.substring(arg.indexOf('=') + 1);
            if (arg.startsWith("--pos=")) {
                x = Integer.parseInt(value.split(",")[0]);
                y = Integer.parseInt(value.split(",")[1]);
            } else if (arg.startsWith("--size=")) {
                w = Integer.parseInt(value.split(",")[0]);
                h = Integer.parseInt(value.split(",")[1]);
            } else if (arg.startsWith("--backend=")) {
                backendName = value;
                backends = switch (value) {
                    case "default" -> Wgpu.INSTANCE_BACKEND_ALL;
                    case "dx12" -> Wgpu.INSTANCE_BACKEND_DX12;
                    case "vulkan" -> Wgpu.INSTANCE_BACKEND_VULKAN;
                    case "gl" -> Wgpu.INSTANCE_BACKEND_GL;
                    default -> throw new IllegalArgumentException("unknown backend " + value);
                };
            } else if (arg.startsWith("--power=")) {
                power = List.of("default", "low", "high").indexOf(value);
            } else if (arg.startsWith("--log=")) {
                logLevel = Wgpu.LOG_LEVEL_NAMES.indexOf(value);
            } else if (arg.equals("--validation")) {
                flags = Wgpu.INSTANCE_FLAG_DEBUG | Wgpu.INSTANCE_FLAG_VALIDATION;
            } else if (arg.equals("--adapters")) {
                listAdapters = true;
            } else if (arg.equals("--idle-report")) {
                idleReport = true;
            } else if (arg.startsWith("--exit-after=")) {
                exitAfter = Double.parseDouble(value);
            } else if (arg.startsWith("--provoke=")) {
                provoke.addAll(List.of(value.split(",")));
            } else {
                throw new IllegalArgumentException("unknown option " + arg);
            }
        }

        int version = Wgpu.getVersion();
        log("wgpu-native %d.%d.%d.%d, backends: %s", version >>> 24, version >> 16 & 0xFF, version >> 8 & 0xFF,
                version & 0xFF, backendName);
        Wgpu.setLogCallback(ON_LOG, MemorySegment.NULL);
        Wgpu.setLogLevel(logLevel);

        createInstance(backends, flags);
        if (listAdapters) {
            listAdapters();
            Wgpu.instanceRelease(instance);
            return;
        }

        check(Sdl.init(Sdl.INIT_VIDEO), "SDL_Init: " + Sdl.getError());
        window = Sdl.createWindow(ARENA.allocateFrom(TITLE + " - " + backendName), w, h, Sdl.WINDOW_RESIZABLE);
        check(!window.equals(MemorySegment.NULL), "SDL_CreateWindow: " + Sdl.getError());
        windowId = Sdl.getWindowID(window);
        Sdl.setWindowPosition(window, x, y);
        createSurface(Sdl.getWindowProperties(window));
        requestAdapter(power);
        logAdapter("adapter", adapter);
        device = requestDevice(true);
        queue = Wgpu.deviceGetQueue(device);
        chooseSurfaceFormat();
        MemorySegment shader = createShader("rectangle", SHADER);
        pipeline = createPipeline("rectangle", shader, "vs_main", surfaceFormat);
        Wgpu.shaderModuleRelease(shader);
        createVertexBuffer();
        fillStaticFrameStructs();
        check(Sdl.addEventWatch(EVENT_WATCH, MemorySegment.NULL), "SDL_AddEventWatch");

        double nextReport = 1;
        while (running) {
            if (!dirty) {
                // Nothing to draw: sleep in SDL until the next event, a report is due or it is time to leave.
                int timeout = -1;
                if (idleReport) {
                    timeout = Math.max(1, (int) ((nextReport - seconds()) * 1000));
                }
                if (exitAfter > 0) {
                    int left = Math.max(1, (int) ((exitAfter - seconds()) * 1000));
                    timeout = timeout < 0 ? left : Math.min(timeout, left);
                }
                if (Sdl.waitEventTimeout(EVENT, timeout)) {
                    handle(EVENT);
                }
            }
            while (Sdl.pollEvent(EVENT)) {
                handle(EVENT);
            }
            if (exitAfter > 0 && seconds() > exitAfter) {
                running = false;
            }
            if (idleReport && seconds() >= nextReport) {
                log("idle report: %d frames submitted so far", frames);
                nextReport = Math.floor(seconds()) + 1;
            }
            if (running && dirty && (Sdl.getWindowFlags(window) & Sdl.WINDOW_MINIMIZED) == 0) {
                render();
                if (!provoke.isEmpty()) {
                    for (String name : provoke) {
                        provoke(name);
                    }
                    provoke.clear();
                }
            }
        }

        log("closing after %d frames, %d exposed events during live resize", frames, liveExposes);
        int polled = Wgpu.devicePoll(device, true);
        log("wgpuDevicePoll(wait) returned 0x%08X, lowest byte %d", polled, polled & 0xFF);
        inCall = "release";
        Wgpu.bufferRelease(vertexBuffer);
        Wgpu.renderPipelineRelease(pipeline);
        Wgpu.surfaceUnconfigure(surface);
        Wgpu.queueRelease(queue);
        Wgpu.deviceRelease(device);
        Wgpu.adapterRelease(adapter);
        Wgpu.surfaceRelease(surface);
        Wgpu.instanceRelease(instance);
        inCall = "";
        Sdl.destroyWindow(window);
        Sdl.quit();
        log("closed");
    }

    // ---- set-up ------------------------------------------------------------------------------

    static void createInstance(long backends, long flags) {
        MemorySegment extras = ARENA.allocate(Wgpu.INSTANCE_EXTRAS);
        extras.set(JAVA_INT, Wgpu.offset(Wgpu.INSTANCE_EXTRAS, "chain", "sType"), Wgpu.STYPE_INSTANCE_EXTRAS);
        extras.set(JAVA_LONG, Wgpu.offset(Wgpu.INSTANCE_EXTRAS, "backends"), backends);
        extras.set(JAVA_LONG, Wgpu.offset(Wgpu.INSTANCE_EXTRAS, "flags"), flags);
        MemorySegment descriptor = ARENA.allocate(Wgpu.INSTANCE_DESCRIPTOR);
        descriptor.set(ADDRESS, Wgpu.offset(Wgpu.INSTANCE_DESCRIPTOR, "nextInChain"), extras);
        instance = Wgpu.createInstance(descriptor);
        check(!instance.equals(MemorySegment.NULL), "wgpuCreateInstance");
    }

    static void listAdapters() {
        long count = Wgpu.instanceEnumerateAdapters(instance, MemorySegment.NULL, MemorySegment.NULL);
        MemorySegment adapters = ARENA.allocate(ADDRESS, Math.max(1, count));
        Wgpu.instanceEnumerateAdapters(instance, MemorySegment.NULL, adapters);
        log("%d adapters", count);
        for (long i = 0; i < count; i++) {
            MemorySegment each = adapters.getAtIndex(ADDRESS, i);
            logAdapter("adapter " + i, each);
            Wgpu.adapterRelease(each);
        }
    }

    /** A surface on the window's native handles; which ones depends on the video driver SDL runs on. */
    static void createSurface(int properties) {
        String driver = Sdl.getCurrentVideoDriver();
        MemorySegment source = switch (driver) {
            case "windows" -> {
                MemorySegment hwnd = pointer(properties, Sdl.PROP_WINDOW_WIN32_HWND_POINTER);
                MemorySegment hinstance = pointer(properties, Sdl.PROP_WINDOW_WIN32_INSTANCE_POINTER);
                log("window hwnd 0x%X, hinstance 0x%X", hwnd.address(), hinstance.address());
                MemorySegment s = ARENA.allocate(Wgpu.SURFACE_SOURCE_WINDOWS_HWND);
                s.set(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_SOURCE_WINDOWS_HWND, "chain", "sType"),
                        Wgpu.STYPE_SURFACE_SOURCE_WINDOWS_HWND);
                s.set(ADDRESS, Wgpu.offset(Wgpu.SURFACE_SOURCE_WINDOWS_HWND, "hinstance"), hinstance);
                s.set(ADDRESS, Wgpu.offset(Wgpu.SURFACE_SOURCE_WINDOWS_HWND, "hwnd"), hwnd);
                yield s;
            }
            case "x11" -> {
                MemorySegment display = pointer(properties, Sdl.PROP_WINDOW_X11_DISPLAY_POINTER);
                long xid = Sdl.getNumberProperty(properties, ARENA.allocateFrom(Sdl.PROP_WINDOW_X11_WINDOW_NUMBER), 0);
                check(xid != 0, "SDL_PROP_WINDOW_X11_WINDOW_NUMBER");
                log("video driver x11: display 0x%X, window 0x%X", display.address(), xid);
                MemorySegment s = ARENA.allocate(Wgpu.SURFACE_SOURCE_XLIB_WINDOW);
                s.set(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_SOURCE_XLIB_WINDOW, "chain", "sType"),
                        Wgpu.STYPE_SURFACE_SOURCE_XLIB_WINDOW);
                s.set(ADDRESS, Wgpu.offset(Wgpu.SURFACE_SOURCE_XLIB_WINDOW, "display"), display);
                s.set(JAVA_LONG, Wgpu.offset(Wgpu.SURFACE_SOURCE_XLIB_WINDOW, "window"), xid);
                yield s;
            }
            case "wayland" -> {
                MemorySegment display = pointer(properties, Sdl.PROP_WINDOW_WAYLAND_DISPLAY_POINTER);
                MemorySegment wlSurface = pointer(properties, Sdl.PROP_WINDOW_WAYLAND_SURFACE_POINTER);
                log("video driver wayland: display 0x%X, surface 0x%X", display.address(), wlSurface.address());
                MemorySegment s = ARENA.allocate(Wgpu.SURFACE_SOURCE_WAYLAND_SURFACE);
                s.set(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_SOURCE_WAYLAND_SURFACE, "chain", "sType"),
                        Wgpu.STYPE_SURFACE_SOURCE_WAYLAND_SURFACE);
                s.set(ADDRESS, Wgpu.offset(Wgpu.SURFACE_SOURCE_WAYLAND_SURFACE, "display"), display);
                s.set(ADDRESS, Wgpu.offset(Wgpu.SURFACE_SOURCE_WAYLAND_SURFACE, "surface"), wlSurface);
                yield s;
            }
            default -> throw new IllegalStateException("no wgpu surface source for video driver " + driver);
        };
        MemorySegment descriptor = ARENA.allocate(Wgpu.SURFACE_DESCRIPTOR);
        descriptor.set(ADDRESS, Wgpu.offset(Wgpu.SURFACE_DESCRIPTOR, "nextInChain"), source);
        surface = Wgpu.instanceCreateSurface(instance, descriptor);
        check(!surface.equals(MemorySegment.NULL), "wgpuInstanceCreateSurface");
    }

    static MemorySegment pointer(int properties, String name) {
        MemorySegment value = Sdl.getPointerProperty(properties, ARENA.allocateFrom(name), MemorySegment.NULL);
        check(!value.equals(MemorySegment.NULL), name);
        return value;
    }

    static void requestAdapter(int powerPreference) {
        MemorySegment options = ARENA.allocate(Wgpu.REQUEST_ADAPTER_OPTIONS);
        options.set(JAVA_INT, Wgpu.offset(Wgpu.REQUEST_ADAPTER_OPTIONS, "powerPreference"), powerPreference);
        options.set(ADDRESS, Wgpu.offset(Wgpu.REQUEST_ADAPTER_OPTIONS, "compatibleSurface"), surface);
        inCall = "wgpuInstanceRequestAdapter";
        long future = Wgpu.instanceRequestAdapter(FUTURE, instance, options,
                callbackInfo(Wgpu.CALLBACK_MODE_ALLOW_PROCESS_EVENTS, ON_ADAPTER));
        inCall = "";
        log("wgpuInstanceRequestAdapter returned future id %d, adapter %s", future,
                adapter.equals(MemorySegment.NULL) ? "not yet there" : "already there");
        check(!adapter.equals(MemorySegment.NULL), "wgpuInstanceRequestAdapter: " + requestMessage);
    }

    static void logAdapter(String what, MemorySegment adapter) {
        MemorySegment info = ARENA.allocate(Wgpu.ADAPTER_INFO);
        check(Wgpu.adapterGetInfo(adapter, info) == Wgpu.STATUS_SUCCESS, "wgpuAdapterGetInfo");
        log("%s: backend %s, type %s, vendor 0x%04X \"%s\", device 0x%04X \"%s\", architecture \"%s\", "
                + "description \"%s\"", what,
                Wgpu.BACKEND_TYPE_NAMES.get(info.get(JAVA_INT, Wgpu.offset(Wgpu.ADAPTER_INFO, "backendType"))),
                Wgpu.ADAPTER_TYPE_NAMES.get(info.get(JAVA_INT, Wgpu.offset(Wgpu.ADAPTER_INFO, "adapterType"))),
                info.get(JAVA_INT, Wgpu.offset(Wgpu.ADAPTER_INFO, "vendorID")),
                Wgpu.string(info, Wgpu.offset(Wgpu.ADAPTER_INFO, "vendor")),
                info.get(JAVA_INT, Wgpu.offset(Wgpu.ADAPTER_INFO, "deviceID")),
                Wgpu.string(info, Wgpu.offset(Wgpu.ADAPTER_INFO, "device")),
                Wgpu.string(info, Wgpu.offset(Wgpu.ADAPTER_INFO, "architecture")),
                Wgpu.string(info, Wgpu.offset(Wgpu.ADAPTER_INFO, "description")));
        Wgpu.adapterInfoFreeMembers(info);
    }

    static MemorySegment requestDevice(boolean withErrorCallbacks) {
        MemorySegment descriptor = ARENA.allocate(Wgpu.DEVICE_DESCRIPTOR);
        Wgpu.setStringView(descriptor, Wgpu.offset(Wgpu.DEVICE_DESCRIPTOR, "label"), Wgpu.utf8(ARENA, "spike device"));
        if (withErrorCallbacks) {
            long lost = Wgpu.offset(Wgpu.DEVICE_DESCRIPTOR, "deviceLostCallbackInfo");
            descriptor.set(JAVA_INT, lost + Wgpu.offset(Wgpu.CALLBACK_INFO, "mode"),
                    Wgpu.CALLBACK_MODE_ALLOW_SPONTANEOUS);
            descriptor.set(ADDRESS, lost + Wgpu.offset(Wgpu.CALLBACK_INFO, "callback"), ON_DEVICE_LOST);
            descriptor.set(ADDRESS, Wgpu.offset(Wgpu.DEVICE_DESCRIPTOR, "uncapturedErrorCallbackInfo", "callback"),
                    ON_UNCAPTURED_ERROR);
        }
        device = MemorySegment.NULL;
        inCall = "wgpuAdapterRequestDevice";
        long future = Wgpu.adapterRequestDevice(FUTURE, adapter, descriptor,
                callbackInfo(Wgpu.CALLBACK_MODE_ALLOW_PROCESS_EVENTS, ON_DEVICE));
        inCall = "";
        log("wgpuAdapterRequestDevice returned future id %d, device %s", future,
                device.equals(MemorySegment.NULL) ? "not yet there" : "already there");
        check(!device.equals(MemorySegment.NULL), "wgpuAdapterRequestDevice: " + requestMessage);
        return device;
    }

    static void chooseSurfaceFormat() {
        MemorySegment capabilities = ARENA.allocate(Wgpu.SURFACE_CAPABILITIES);
        check(Wgpu.surfaceGetCapabilities(surface, adapter, capabilities) == Wgpu.STATUS_SUCCESS,
                "wgpuSurfaceGetCapabilities");
        List<Integer> formats = ints(capabilities, "formatCount", "formats");
        List<Integer> presentModes = ints(capabilities, "presentModeCount", "presentModes");
        List<Integer> alphaModes = ints(capabilities, "alphaModeCount", "alphaModes");
        Wgpu.surfaceCapabilitiesFreeMembers(capabilities);
        // A format without sRGB encoding, so that a screenshot shows the numbers the shader wrote.
        surfaceFormat = formats.contains(Wgpu.TEXTURE_FORMAT_BGRA8_UNORM) ? Wgpu.TEXTURE_FORMAT_BGRA8_UNORM
                : formats.contains(Wgpu.TEXTURE_FORMAT_RGBA8_UNORM) ? Wgpu.TEXTURE_FORMAT_RGBA8_UNORM
                : formats.get(0);
        log("surface formats %s, present modes %s, alpha modes %s; using format 0x%02X with Fifo",
                formats.stream().map("0x%02X"::formatted).toList(),
                presentModes.stream().map(Wgpu.PRESENT_MODE_NAMES::get).toList(),
                alphaModes.stream().map(Wgpu.ALPHA_MODE_NAMES::get).toList(), surfaceFormat);
    }

    static List<Integer> ints(MemorySegment capabilities, String countField, String arrayField) {
        long count = capabilities.get(JAVA_LONG, Wgpu.offset(Wgpu.SURFACE_CAPABILITIES, countField));
        MemorySegment array = capabilities.get(ADDRESS, Wgpu.offset(Wgpu.SURFACE_CAPABILITIES, arrayField))
                .reinterpret(count * JAVA_INT.byteSize());
        List<Integer> values = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            values.add(array.getAtIndex(JAVA_INT, i));
        }
        return values;
    }

    static MemorySegment createShader(String label, String wgsl) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(Wgpu.SHADER_SOURCE_WGSL);
            source.set(JAVA_INT, Wgpu.offset(Wgpu.SHADER_SOURCE_WGSL, "chain", "sType"),
                    Wgpu.STYPE_SHADER_SOURCE_WGSL);
            Wgpu.setStringView(source, Wgpu.offset(Wgpu.SHADER_SOURCE_WGSL, "code"), Wgpu.utf8(arena, wgsl));
            MemorySegment descriptor = arena.allocate(Wgpu.SHADER_MODULE_DESCRIPTOR);
            descriptor.set(ADDRESS, Wgpu.offset(Wgpu.SHADER_MODULE_DESCRIPTOR, "nextInChain"), source);
            Wgpu.setStringView(descriptor, Wgpu.offset(Wgpu.SHADER_MODULE_DESCRIPTOR, "label"),
                    Wgpu.utf8(arena, label));
            inCall = "wgpuDeviceCreateShaderModule";
            MemorySegment module = Wgpu.deviceCreateShaderModule(device, descriptor);
            inCall = "";
            return module;
        }
    }

    static MemorySegment createPipeline(String label, MemorySegment shader, String vertexEntryPoint, int format) {
        try (Arena arena = Arena.ofConfined()) {
            // x, y at offset 0 and r, g, b at offset 8 of each vertex.
            MemorySegment attributes = arena.allocate(Wgpu.VERTEX_ATTRIBUTE, 2);
            int[][] formatOffsetLocation = {{Wgpu.VERTEX_FORMAT_FLOAT32X2, 0, 0}, {Wgpu.VERTEX_FORMAT_FLOAT32X3, 8, 1}};
            for (int i = 0; i < formatOffsetLocation.length; i++) {
                MemorySegment attribute = attributes.asSlice(i * Wgpu.VERTEX_ATTRIBUTE.byteSize());
                attribute.set(JAVA_INT, Wgpu.offset(Wgpu.VERTEX_ATTRIBUTE, "format"), formatOffsetLocation[i][0]);
                attribute.set(JAVA_LONG, Wgpu.offset(Wgpu.VERTEX_ATTRIBUTE, "offset"), formatOffsetLocation[i][1]);
                attribute.set(JAVA_INT, Wgpu.offset(Wgpu.VERTEX_ATTRIBUTE, "shaderLocation"),
                        formatOffsetLocation[i][2]);
            }

            MemorySegment buffers = arena.allocate(Wgpu.VERTEX_BUFFER_LAYOUT);
            buffers.set(JAVA_INT, Wgpu.offset(Wgpu.VERTEX_BUFFER_LAYOUT, "stepMode"), Wgpu.VERTEX_STEP_MODE_VERTEX);
            buffers.set(JAVA_LONG, Wgpu.offset(Wgpu.VERTEX_BUFFER_LAYOUT, "arrayStride"), VERTEX_STRIDE);
            buffers.set(JAVA_LONG, Wgpu.offset(Wgpu.VERTEX_BUFFER_LAYOUT, "attributeCount"), 2);
            buffers.set(ADDRESS, Wgpu.offset(Wgpu.VERTEX_BUFFER_LAYOUT, "attributes"), attributes);

            MemorySegment target = arena.allocate(Wgpu.COLOR_TARGET_STATE);
            target.set(JAVA_INT, Wgpu.offset(Wgpu.COLOR_TARGET_STATE, "format"), format);
            target.set(JAVA_LONG, Wgpu.offset(Wgpu.COLOR_TARGET_STATE, "writeMask"), Wgpu.COLOR_WRITE_MASK_ALL);

            MemorySegment fragment = arena.allocate(Wgpu.FRAGMENT_STATE);
            fragment.set(ADDRESS, Wgpu.offset(Wgpu.FRAGMENT_STATE, "module"), shader);
            Wgpu.setStringView(fragment, Wgpu.offset(Wgpu.FRAGMENT_STATE, "entryPoint"), Wgpu.utf8(arena, "fs_main"));
            fragment.set(JAVA_LONG, Wgpu.offset(Wgpu.FRAGMENT_STATE, "targetCount"), 1);
            fragment.set(ADDRESS, Wgpu.offset(Wgpu.FRAGMENT_STATE, "targets"), target);

            StructLayout layout = Wgpu.RENDER_PIPELINE_DESCRIPTOR;
            MemorySegment descriptor = arena.allocate(layout);
            Wgpu.setStringView(descriptor, Wgpu.offset(layout, "label"), Wgpu.utf8(arena, label));
            descriptor.set(ADDRESS, Wgpu.offset(layout, "vertex", "module"), shader);
            Wgpu.setStringView(descriptor, Wgpu.offset(layout, "vertex", "entryPoint"),
                    Wgpu.utf8(arena, vertexEntryPoint));
            descriptor.set(JAVA_LONG, Wgpu.offset(layout, "vertex", "bufferCount"), 1);
            descriptor.set(ADDRESS, Wgpu.offset(layout, "vertex", "buffers"), buffers);
            descriptor.set(JAVA_INT, Wgpu.offset(layout, "primitive", "topology"),
                    Wgpu.PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            // The two members of WGPU_MULTISAMPLE_STATE_INIT that are not zero.
            descriptor.set(JAVA_INT, Wgpu.offset(layout, "multisample", "count"), 1);
            descriptor.set(JAVA_INT, Wgpu.offset(layout, "multisample", "mask"), 0xFFFFFFFF);
            descriptor.set(ADDRESS, Wgpu.offset(layout, "fragment"), fragment);
            inCall = "wgpuDeviceCreateRenderPipeline";
            MemorySegment created = Wgpu.deviceCreateRenderPipeline(device, descriptor);
            inCall = "";
            return created;
        }
    }

    static void createVertexBuffer() {
        MemorySegment descriptor = ARENA.allocate(Wgpu.BUFFER_DESCRIPTOR);
        Wgpu.setStringView(descriptor, Wgpu.offset(Wgpu.BUFFER_DESCRIPTOR, "label"), Wgpu.utf8(ARENA, "rectangle"));
        descriptor.set(JAVA_LONG, Wgpu.offset(Wgpu.BUFFER_DESCRIPTOR, "usage"),
                Wgpu.BUFFER_USAGE_VERTEX | Wgpu.BUFFER_USAGE_COPY_DST);
        descriptor.set(JAVA_LONG, Wgpu.offset(Wgpu.BUFFER_DESCRIPTOR, "size"), VERTICES.byteSize());
        vertexBuffer = Wgpu.deviceCreateBuffer(device, descriptor);
        check(!vertexBuffer.equals(MemorySegment.NULL), "wgpuDeviceCreateBuffer");
    }

    static void fillStaticFrameStructs() {
        SURFACE_CONFIGURATION.set(ADDRESS, Wgpu.offset(Wgpu.SURFACE_CONFIGURATION, "device"), device);
        SURFACE_CONFIGURATION.set(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_CONFIGURATION, "format"), surfaceFormat);
        SURFACE_CONFIGURATION.set(JAVA_LONG, Wgpu.offset(Wgpu.SURFACE_CONFIGURATION, "usage"),
                Wgpu.TEXTURE_USAGE_RENDER_ATTACHMENT);
        SURFACE_CONFIGURATION.set(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_CONFIGURATION, "alphaMode"),
                Wgpu.COMPOSITE_ALPHA_MODE_AUTO);
        SURFACE_CONFIGURATION.set(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_CONFIGURATION, "presentMode"),
                Wgpu.PRESENT_MODE_FIFO);

        COLOR_ATTACHMENT.set(JAVA_INT, Wgpu.offset(Wgpu.RENDER_PASS_COLOR_ATTACHMENT, "loadOp"), Wgpu.LOAD_OP_CLEAR);
        COLOR_ATTACHMENT.set(JAVA_INT, Wgpu.offset(Wgpu.RENDER_PASS_COLOR_ATTACHMENT, "storeOp"),
                Wgpu.STORE_OP_STORE);
        long clear = Wgpu.offset(Wgpu.RENDER_PASS_COLOR_ATTACHMENT, "clearValue");
        COLOR_ATTACHMENT.set(JAVA_DOUBLE, clear, CLEAR[0]);
        COLOR_ATTACHMENT.set(JAVA_DOUBLE, clear + 8, CLEAR[1]);
        COLOR_ATTACHMENT.set(JAVA_DOUBLE, clear + 16, CLEAR[2]);
        COLOR_ATTACHMENT.set(JAVA_DOUBLE, clear + 24, 1.0);

        RENDER_PASS.set(JAVA_LONG, Wgpu.offset(Wgpu.RENDER_PASS_DESCRIPTOR, "colorAttachmentCount"), 1);
        RENDER_PASS.set(ADDRESS, Wgpu.offset(Wgpu.RENDER_PASS_DESCRIPTOR, "colorAttachments"), COLOR_ATTACHMENT);
    }

    static MemorySegment callbackInfo(int mode, MemorySegment callback) {
        MemorySegment info = ARENA.allocate(Wgpu.CALLBACK_INFO);
        info.set(JAVA_INT, Wgpu.offset(Wgpu.CALLBACK_INFO, "mode"), mode);
        info.set(ADDRESS, Wgpu.offset(Wgpu.CALLBACK_INFO, "callback"), callback);
        return info;
    }

    // ---- drawing -----------------------------------------------------------------------------

    static void configure(int width, int height) {
        SURFACE_CONFIGURATION.set(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_CONFIGURATION, "width"), width);
        SURFACE_CONFIGURATION.set(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_CONFIGURATION, "height"), height);
        inCall = "wgpuSurfaceConfigure";
        Wgpu.surfaceConfigure(surface, SURFACE_CONFIGURATION);
        inCall = "";
        configuredWidth = width;
        configuredHeight = height;
        log("surface configured %dx%d", width, height);
    }

    /** Draws one frame. Called when something changed, never on a timer. */
    static void render() {
        String reason = dirtyReason;
        dirty = false;
        Sdl.getWindowSizeInPixels(window, WIDTH, HEIGHT);
        int width = WIDTH.get(JAVA_INT, 0);
        int height = HEIGHT.get(JAVA_INT, 0);
        if (width <= 0 || height <= 0) {
            return; // wgpuSurfaceConfigure ends the process for a size of zero
        }
        if (width != configuredWidth || height != configuredHeight) {
            configure(width, height);
        }

        MemorySegment texture = acquireTexture();
        if (texture.equals(MemorySegment.NULL)) {
            return;
        }
        MemorySegment view = Wgpu.textureCreateView(texture, MemorySegment.NULL);
        writeVertices(width, height);
        Wgpu.queueWriteBuffer(queue, vertexBuffer, 0, VERTICES, VERTICES.byteSize());

        COLOR_ATTACHMENT.set(ADDRESS, Wgpu.offset(Wgpu.RENDER_PASS_COLOR_ATTACHMENT, "view"), view);
        COLOR_ATTACHMENT.set(JAVA_INT, Wgpu.offset(Wgpu.RENDER_PASS_COLOR_ATTACHMENT, "depthSlice"),
                zeroDepthSlice ? 0 : Wgpu.DEPTH_SLICE_UNDEFINED);
        // wgpu reports what is wrong with a pass when the encoder is finished, and wgpu-native then ends the
        // process if that command buffer is submitted. So: record inside an error scope, submit only if it is empty.
        MemorySegment[] recorded = new MemorySegment[2];
        frameError = captured(() -> {
            MemorySegment encoder = Wgpu.deviceCreateCommandEncoder(device, MemorySegment.NULL);
            inCall = "wgpuCommandEncoderBeginRenderPass";
            MemorySegment pass = Wgpu.commandEncoderBeginRenderPass(encoder, RENDER_PASS);
            inCall = "wgpuRenderPassEncoder*";
            Wgpu.renderPassEncoderSetPipeline(pass,
                    framePipeline.equals(MemorySegment.NULL) ? pipeline : framePipeline);
            Wgpu.renderPassEncoderSetVertexBuffer(pass, 0, vertexBuffer, 0, VERTICES.byteSize());
            Wgpu.renderPassEncoderDraw(pass, VERTEX_COUNT, 1, 0, 0);
            Wgpu.renderPassEncoderEnd(pass);
            Wgpu.renderPassEncoderRelease(pass);
            inCall = "wgpuCommandEncoderFinish";
            recorded[0] = encoder;
            recorded[1] = Wgpu.commandEncoderFinish(encoder, MemorySegment.NULL);
            inCall = "";
        });
        boolean submit = frameError.isEmpty() || submitInvalid;
        int presented = Wgpu.STATUS_SUCCESS;
        if (submit) {
            COMMANDS.set(ADDRESS, 0, recorded[1]);
            inCall = "wgpuQueueSubmit";
            Wgpu.queueSubmit(queue, 1, COMMANDS);
            inCall = "wgpuSurfacePresent";
            presented = Wgpu.surfacePresent(surface);
            inCall = "";
            frames++;
        }
        Wgpu.commandBufferRelease(recorded[1]);
        Wgpu.commandEncoderRelease(recorded[0]);
        Wgpu.textureViewRelease(view);
        // Releasing a surface texture that was not presented gives it back to the surface.
        Wgpu.textureRelease(texture);
        if (submit) {
            log("frame %d (%s) %dx%d%s", frames, reason, width, height,
                    presented == Wgpu.STATUS_SUCCESS ? "" : ", present failed");
        } else {
            log("frame not submitted (%s): recording it reported an error", reason);
        }
    }

    /** The texture of the next frame, after one reconfiguration if the surface asks for it; NULL to skip the frame. */
    static MemorySegment acquireTexture() {
        for (int attempt = 0; attempt < 2; attempt++) {
            inCall = "wgpuSurfaceGetCurrentTexture";
            long before = System.nanoTime();
            Wgpu.surfaceGetCurrentTexture(surface, SURFACE_TEXTURE);
            long millis = (System.nanoTime() - before) / 1_000_000;
            inCall = "";
            if (millis >= 100) {
                log("wgpuSurfaceGetCurrentTexture took %d ms", millis);
            }
            MemorySegment texture = SURFACE_TEXTURE.get(ADDRESS, Wgpu.offset(Wgpu.SURFACE_TEXTURE, "texture"));
            int status = SURFACE_TEXTURE.get(JAVA_INT, Wgpu.offset(Wgpu.SURFACE_TEXTURE, "status"));
            switch (status) {
                case Wgpu.SURFACE_TEXTURE_SUCCESS_OPTIMAL, Wgpu.SURFACE_TEXTURE_SUCCESS_SUBOPTIMAL -> {
                    return texture;
                }
                case Wgpu.SURFACE_TEXTURE_TIMEOUT, Wgpu.SURFACE_TEXTURE_OUTDATED, Wgpu.SURFACE_TEXTURE_LOST -> {
                    log("surface texture status %d, configuring again", status);
                    if (!texture.equals(MemorySegment.NULL)) {
                        Wgpu.textureRelease(texture);
                    }
                    configure(configuredWidth, configuredHeight);
                }
                case Wgpu.SURFACE_TEXTURE_OCCLUDED -> {
                    log("surface is occluded, frame skipped");
                    return MemorySegment.NULL;
                }
                default -> throw new IllegalStateException("wgpuSurfaceGetCurrentTexture status " + status);
            }
        }
        return MemorySegment.NULL;
    }

    /** Two triangles in clip space for the rectangle MARGIN pixels inside the window: x, y, r, g, b per vertex. */
    static void writeVertices(int width, int height) {
        float left = -1 + 2f * MARGIN / width;
        float right = 1 - 2f * MARGIN / width;
        float top = 1 - 2f * MARGIN / height;
        float bottom = -1 + 2f * MARGIN / height;
        float[][] colors = PALETTES[palette];
        float[][] corners = {{left, top}, {right, top}, {left, bottom}, {right, bottom}};
        int[] order = {0, 2, 3, 0, 3, 1};
        for (int i = 0; i < order.length; i++) {
            long base = (long) i * VERTEX_STRIDE;
            VERTICES.set(JAVA_FLOAT, base, corners[order[i]][0]);
            VERTICES.set(JAVA_FLOAT, base + 4, corners[order[i]][1]);
            VERTICES.set(JAVA_FLOAT, base + 8, colors[order[i]][0]);
            VERTICES.set(JAVA_FLOAT, base + 12, colors[order[i]][1]);
            VERTICES.set(JAVA_FLOAT, base + 16, colors[order[i]][2]);
        }
    }

    // ---- events ------------------------------------------------------------------------------

    static void handle(MemorySegment event) {
        int type = event.get(JAVA_INT, Sdl.EVENT_TYPE);
        if (type == Sdl.EVENT_QUIT) {
            running = false;
            return;
        }
        if (type < Sdl.EVENT_WINDOW_SHOWN || type > Sdl.EVENT_KEY_DOWN
                || event.get(JAVA_INT, Sdl.EVENT_WINDOW_ID) != windowId) {
            return;
        }
        switch (type) {
            case Sdl.EVENT_WINDOW_CLOSE_REQUESTED -> running = false;
            case Sdl.EVENT_WINDOW_EXPOSED -> invalidate("exposed");
            case Sdl.EVENT_WINDOW_PIXEL_SIZE_CHANGED -> invalidate("resized to %dx%d".formatted(
                    event.get(JAVA_INT, Sdl.WINDOW_EVENT_DATA1), event.get(JAVA_INT, Sdl.WINDOW_EVENT_DATA2)));
            case Sdl.EVENT_WINDOW_MINIMIZED -> log("minimized");
            case Sdl.EVENT_WINDOW_RESTORED -> invalidate("restored");
            case Sdl.EVENT_KEY_DOWN -> {
                int key = event.get(JAVA_INT, Sdl.KEYBOARD_KEY);
                if (key == 0x1B) {
                    running = false;
                } else if (key == 'c' && !event.get(JAVA_BOOLEAN, Sdl.KEYBOARD_REPEAT)) {
                    palette = (palette + 1) % PALETTES.length;
                    invalidate("colours changed");
                }
            }
            default -> { }
        }
    }

    static void invalidate(String reason) {
        dirty = true;
        dirtyReason = reason;
    }

    /**
     * SDL_EventFilter. While the user drags a window edge, Windows runs a modal loop and SDL_WaitEvent does not
     * return; SDL sends exposed events with data1 = 1 from inside that loop, about sixty a second whether or not
     * the size changed. This draws for those that find a new size.
     */
    static boolean eventWatch(MemorySegment userdata, MemorySegment event) {
        try {
            if (event.get(JAVA_INT, Sdl.EVENT_TYPE) == Sdl.EVENT_WINDOW_EXPOSED
                    && event.get(JAVA_INT, Sdl.EVENT_WINDOW_ID) == windowId
                    && event.get(JAVA_INT, Sdl.WINDOW_EVENT_DATA1) == 1 && !device.equals(MemorySegment.NULL)) {
                liveExposes++;
                Sdl.getWindowSizeInPixels(window, WIDTH, HEIGHT);
                if (dirty || WIDTH.get(JAVA_INT, 0) != configuredWidth
                        || HEIGHT.get(JAVA_INT, 0) != configuredHeight) {
                    dirtyReason = "live resize, exposed event " + liveExposes;
                    render();
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return true;
    }

    // ---- callbacks from wgpu-native ----------------------------------------------------------

    static void onLog(int level, MemorySegment message, MemorySegment userdata) {
        try {
            log("[wgpu %s, %s] %s", Wgpu.LOG_LEVEL_NAMES.get(level), where(), Wgpu.string(message, 0));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void onAdapter(int status, MemorySegment handle, MemorySegment message, MemorySegment userdata1,
            MemorySegment userdata2) {
        try {
            requestMessage = Wgpu.string(message, 0);
            log("[callback requestAdapter, %s] status %d, message \"%s\"", where(), status, requestMessage);
            if (status == Wgpu.REQUEST_STATUS_SUCCESS) {
                adapter = handle;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void onDevice(int status, MemorySegment handle, MemorySegment message, MemorySegment userdata1,
            MemorySegment userdata2) {
        try {
            requestMessage = Wgpu.string(message, 0);
            log("[callback requestDevice, %s] status %d, message \"%s\"", where(), status, requestMessage);
            if (status == Wgpu.REQUEST_STATUS_SUCCESS) {
                device = handle;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void onUncapturedError(MemorySegment devicePointer, int type, MemorySegment message,
            MemorySegment userdata1, MemorySegment userdata2) {
        try {
            log("[callback uncapturedError, %s] type %s%n%s", where(), Wgpu.ERROR_TYPE_NAMES.get(type),
                    Wgpu.string(message, 0));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void onDeviceLost(MemorySegment devicePointer, int reason, MemorySegment message, MemorySegment userdata1,
            MemorySegment userdata2) {
        try {
            log("[callback deviceLost, %s] reason %s, message \"%s\"", where(),
                    Wgpu.DEVICE_LOST_REASON_NAMES.get(reason), Wgpu.string(message, 0));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void onPopErrorScope(int status, int type, MemorySegment message, MemorySegment userdata1,
            MemorySegment userdata2) {
        try {
            scopeMessage = Wgpu.string(message, 0);
            keptMessage = message;
            keptData = message.get(ADDRESS, 0);
            keptLength = message.get(JAVA_LONG, 8);
            if (traceScopeCallback) {
                log("[callback popErrorScope, %s] status %d, type %s", where(), status,
                        Wgpu.ERROR_TYPE_NAMES.get(type));
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void onPopThrows(int status, int type, MemorySegment message, MemorySegment userdata1,
            MemorySegment userdata2) {
        throw new IllegalStateException("thrown inside the popErrorScope callback");
    }

    /** The thread a callback runs on and the wgpu call the main thread is in at that moment. */
    static String where() {
        Thread thread = Thread.currentThread();
        return "thread " + (thread == MAIN_THREAD ? "main" : "\"" + thread.getName() + "\" (not main)")
                + (inCall.isEmpty() ? ", outside any wgpu call" : ", inside " + inCall);
    }

    static MemorySegment upcall(Arena arena, String method, FunctionDescriptor descriptor) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(Main.class, method, descriptor.toMethodType());
            return Wgpu.LINKER.upcallStub(target, descriptor, arena);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- errors on purpose -------------------------------------------------------------------

    /** Runs {@code action} inside a validation error scope and returns the message wgpu collected, or "". */
    static String captured(Runnable action) {
        Wgpu.devicePushErrorScope(device, Wgpu.ERROR_FILTER_VALIDATION);
        action.run();
        scopeMessage = "";
        inCall = "wgpuDevicePopErrorScope";
        Wgpu.devicePopErrorScope(FUTURE, device, POP_ERROR_SCOPE_INFO);
        inCall = "";
        return scopeMessage;
    }

    static void report(String name, String message) {
        log("provoke %s: %s%n----%n%s%n----", name, message.isEmpty() ? "NO ERROR ARRIVED" : "message in the scope",
                message);
    }

    static void provoke(String name) {
        log("provoke %s", name);
        traceScopeCallback = true;
        switch (name) {
            case "shader" -> report(name, captured(() -> {
                MemorySegment module = createShader("broken shader", BROKEN_SHADER);
                log("wgpuDeviceCreateShaderModule returned %s", nullOrHandle(module));
                if (!module.equals(MemorySegment.NULL)) {
                    Wgpu.shaderModuleRelease(module);
                }
            }));
            case "entrypoint" -> report(name, captured(() -> {
                MemorySegment shader = createShader("rectangle", SHADER);
                MemorySegment broken = createPipeline("pipeline with a typo", shader, "vs_mian", surfaceFormat);
                log("wgpuDeviceCreateRenderPipeline returned %s", nullOrHandle(broken));
                if (!broken.equals(MemorySegment.NULL)) {
                    Wgpu.renderPipelineRelease(broken);
                }
                Wgpu.shaderModuleRelease(shader);
            }));
            case "format" -> {
                MemorySegment shader = createShader("rectangle", SHADER);
                MemorySegment other = createPipeline("pipeline for rgba16float", shader, "vs_main",
                        Wgpu.TEXTURE_FORMAT_RGBA16_FLOAT);
                Wgpu.shaderModuleRelease(shader);
                framePipeline = other;
                dirtyReason = "provoke format";
                render();
                report(name, frameError);
                framePipeline = MemorySegment.NULL;
                Wgpu.renderPipelineRelease(other);
                invalidate("after provoke format");
            }
            case "depthslice" -> {
                zeroDepthSlice = true;
                dirtyReason = "provoke depthslice";
                render();
                report(name, frameError);
                zeroDepthSlice = false;
                invalidate("after provoke depthslice");
            }
            case "uncaptured" -> {
                MemorySegment module = createShader("broken shader, no scope", BROKEN_SHADER);
                if (!module.equals(MemorySegment.NULL)) {
                    Wgpu.shaderModuleRelease(module);
                }
            }
            case "lifetime" -> {
                captured(() -> {
                    MemorySegment module = createShader("broken shader", BROKEN_SHADER);
                    if (!module.equals(MemorySegment.NULL)) {
                        Wgpu.shaderModuleRelease(module);
                    }
                });
                // The struct the callback got by value: FFM copied it into a segment of its own.
                try {
                    log("lifetime: the WGPUStringView segment after the callback: data 0x%X, length %d",
                            keptMessage.get(ADDRESS, 0).address(), keptMessage.get(JAVA_LONG, 8));
                } catch (RuntimeException e) {
                    log("lifetime: the WGPUStringView segment after the callback: %s", e);
                }
                // The bytes it pointed to belong to wgpu-native, which has freed them by now.
                String now = new String(keptData.reinterpret(keptLength).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
                log("lifetime: the %d message bytes read again after the callback are %s", keptLength,
                        now.equals(scopeMessage) ? "still the same (freed memory, not yet reused)"
                                : "different from what the callback copied: " + printable(now, 60));
            }
            case "fatal-submit-invalid" -> {
                MemorySegment shader = createShader("rectangle", SHADER);
                framePipeline = createPipeline("pipeline for rgba16float", shader, "vs_main",
                        Wgpu.TEXTURE_FORMAT_RGBA16_FLOAT);
                submitInvalid = true;
                dirtyReason = "provoke fatal-submit-invalid";
                log("submitting a command buffer although recording it reported an error");
                render();
                log("wgpuQueueSubmit returned");
            }
            case "fatal-waitany" -> {
                MemorySegment info = ARENA.allocate(Wgpu.FUTURE_WAIT_INFO);
                log("calling wgpuInstanceWaitAny");
                int status = Wgpu.instanceWaitAny(instance, 1, info, 0);
                log("wgpuInstanceWaitAny returned %d", status);
            }
            case "fatal-configure-zero" -> {
                log("calling wgpuSurfaceConfigure with width 0");
                configure(0, configuredHeight);
                log("wgpuSurfaceConfigure returned");
            }
            case "fatal-no-callback" -> {
                MemorySegment first = device;
                device = requestDevice(false);
                log("creating the broken shader on a device without an uncaptured-error callback");
                createShader("broken shader, no callback", BROKEN_SHADER);
                log("wgpuDeviceCreateShaderModule returned");
                device = first;
            }
            case "fatal-upcall-throws" -> {
                Wgpu.devicePushErrorScope(device, Wgpu.ERROR_FILTER_VALIDATION);
                log("calling wgpuDevicePopErrorScope with a callback that throws");
                Wgpu.devicePopErrorScope(FUTURE, device, callbackInfo(Wgpu.CALLBACK_MODE_ALLOW_PROCESS_EVENTS,
                        ON_POP_THROWS));
                log("wgpuDevicePopErrorScope returned");
            }
            case "fatal-stale-stub" -> {
                Arena shortLived = Arena.ofConfined();
                MemorySegment stub = upcall(shortLived, "onPopErrorScope", Wgpu.POP_ERROR_SCOPE_CALLBACK);
                shortLived.close();
                Wgpu.devicePushErrorScope(device, Wgpu.ERROR_FILTER_VALIDATION);
                log("calling wgpuDevicePopErrorScope with an upcall stub whose arena is closed");
                Wgpu.devicePopErrorScope(FUTURE, device, callbackInfo(Wgpu.CALLBACK_MODE_ALLOW_PROCESS_EVENTS, stub));
                log("wgpuDevicePopErrorScope returned");
            }
            default -> throw new IllegalArgumentException("unknown --provoke " + name);
        }
    }

    static String nullOrHandle(MemorySegment object) {
        return object.equals(MemorySegment.NULL) ? "NULL" : "a handle";
    }

    static String printable(String text, int max) {
        StringBuilder out = new StringBuilder();
        text.chars().limit(max).forEach(c -> out.append(c >= 0x20 && c < 0x7F ? (char) c : '.'));
        return out.toString();
    }

    // ---- helpers -----------------------------------------------------------------------------

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException(what + " failed");
        }
    }

    static double seconds() {
        return (System.nanoTime() - START) / 1e9;
    }

    /** UTF-8 whatever the console code page is: naga draws its source excerpts with box-drawing characters. */
    static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    static void log(String format, Object... args) {
        OUT.printf(Locale.ROOT, "%8.3f  %s%n", seconds(), format.formatted(args));
    }
}
