import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** Handwritten FFM bindings for the Win32, DWM and COM calls the spike makes on the HWND that SDL created. */
final class Win32 {
    private Win32() {}

    static final int GWL_STYLE = -16;
    static final int GWL_EXSTYLE = -20;

    static final int WM_NCHITTEST = 0x0084;
    static final int WM_NCMOUSEMOVE = 0x00A0;
    static final int WM_NCLBUTTONDOWN = 0x00A1;
    static final int WM_NCLBUTTONUP = 0x00A2;
    static final int WM_NCLBUTTONDBLCLK = 0x00A3;
    static final int WM_MOUSEMOVE = 0x0200;
    static final int WM_NCMOUSELEAVE = 0x02A2;

    static final int HTMAXBUTTON = 9;

    static final int TME_LEAVE = 0x02;
    static final int TME_NONCLIENT = 0x10;

    static final int DWMWA_NCRENDERING_ENABLED = 1;
    static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    static final int DWMWCP_DEFAULT = 0;
    static final int DWMWCP_DONOTROUND = 1;
    static final int DWMWCP_ROUND = 2;
    static final int DWMWCP_ROUNDSMALL = 3;

    static final int COINIT_APARTMENTTHREADED = 0x2;
    static final int CLSCTX_INPROC_SERVER = 0x1;
    static final String CLSID_TASKBAR_LIST = "56FDF344-FD6D-11D0-958A-006097C9A090";
    static final String IID_ITASKBAR_LIST3 = "EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF";

    /** Slots in the ITaskbarList3 vtable: IUnknown (0-2), ITaskbarList (3-7), ITaskbarList2 (8), ITaskbarList3 (9-20). */
    private static final int VTABLE_RELEASE = 2;
    private static final int VTABLE_HR_INIT = 3;
    private static final int VTABLE_SET_OVERLAY_ICON = 18;

    // ---- struct layouts ----------------------------------------------------------------------

    static final StructLayout POINT = MemoryLayout.structLayout(JAVA_INT.withName("x"), JAVA_INT.withName("y"));

    static final StructLayout RECT = MemoryLayout.structLayout(
            JAVA_INT.withName("left"), JAVA_INT.withName("top"),
            JAVA_INT.withName("right"), JAVA_INT.withName("bottom"));

    static final StructLayout TRACKMOUSEEVENT = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"), JAVA_INT.withName("dwFlags"),
            ADDRESS.withName("hwndTrack"), JAVA_INT.withName("dwHoverTime"), MemoryLayout.paddingLayout(4));

    static final StructLayout GUID = MemoryLayout.structLayout(
            JAVA_INT.withName("Data1"), JAVA_SHORT.withName("Data2"), JAVA_SHORT.withName("Data3"),
            MemoryLayout.sequenceLayout(8, JAVA_BYTE).withName("Data4"));

    // ---- callbacks ---------------------------------------------------------------------------

    /** LRESULT (*SUBCLASSPROC)(HWND, UINT msg, WPARAM, LPARAM, UINT_PTR idSubclass, DWORD_PTR refData) */
    static final FunctionDescriptor SUBCLASS_PROC =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    // ---- functions ---------------------------------------------------------------------------

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
    private static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi", Arena.global());
    private static final SymbolLookup COMCTL32 = SymbolLookup.libraryLookup("comctl32", Arena.global());
    private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32", Arena.global());

    static final FunctionDescriptor GET_WINDOW_LONG_PTR_W = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT);
    static final FunctionDescriptor GET_WINDOW_RECT = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor SCREEN_TO_CLIENT = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor GET_DPI_FOR_WINDOW = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor TRACK_MOUSE_EVENT = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor CREATE_ICON =
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_BYTE, JAVA_BYTE, ADDRESS, ADDRESS);
    static final FunctionDescriptor DESTROY_ICON = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor DWM_SET_WINDOW_ATTRIBUTE =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);
    static final FunctionDescriptor DWM_GET_WINDOW_ATTRIBUTE =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);
    static final FunctionDescriptor SET_WINDOW_SUBCLASS =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG);
    static final FunctionDescriptor REMOVE_WINDOW_SUBCLASS =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);
    static final FunctionDescriptor DEF_SUBCLASS_PROC =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
    static final FunctionDescriptor CO_INITIALIZE_EX = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    static final FunctionDescriptor CO_CREATE_INSTANCE =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor IUNKNOWN_RELEASE = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor ITASKBAR_LIST_HR_INIT = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor ITASKBAR_LIST3_SET_OVERLAY_ICON =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    private static final MethodHandle GET_WINDOW_LONG_PTR_W_MH =
            bind(USER32, "GetWindowLongPtrW", GET_WINDOW_LONG_PTR_W);
    private static final MethodHandle GET_WINDOW_RECT_MH = bind(USER32, "GetWindowRect", GET_WINDOW_RECT);
    private static final MethodHandle SCREEN_TO_CLIENT_MH = bind(USER32, "ScreenToClient", SCREEN_TO_CLIENT);
    private static final MethodHandle GET_DPI_FOR_WINDOW_MH = bind(USER32, "GetDpiForWindow", GET_DPI_FOR_WINDOW);
    private static final MethodHandle TRACK_MOUSE_EVENT_MH = bind(USER32, "TrackMouseEvent", TRACK_MOUSE_EVENT);
    private static final MethodHandle CREATE_ICON_MH = bind(USER32, "CreateIcon", CREATE_ICON);
    private static final MethodHandle DESTROY_ICON_MH = bind(USER32, "DestroyIcon", DESTROY_ICON);
    private static final MethodHandle DWM_SET_WINDOW_ATTRIBUTE_MH =
            bind(DWMAPI, "DwmSetWindowAttribute", DWM_SET_WINDOW_ATTRIBUTE);
    private static final MethodHandle DWM_GET_WINDOW_ATTRIBUTE_MH =
            bind(DWMAPI, "DwmGetWindowAttribute", DWM_GET_WINDOW_ATTRIBUTE);
    private static final MethodHandle SET_WINDOW_SUBCLASS_MH =
            bind(COMCTL32, "SetWindowSubclass", SET_WINDOW_SUBCLASS);
    private static final MethodHandle REMOVE_WINDOW_SUBCLASS_MH =
            bind(COMCTL32, "RemoveWindowSubclass", REMOVE_WINDOW_SUBCLASS);
    private static final MethodHandle DEF_SUBCLASS_PROC_MH = bind(COMCTL32, "DefSubclassProc", DEF_SUBCLASS_PROC);
    private static final MethodHandle CO_INITIALIZE_EX_MH = bind(OLE32, "CoInitializeEx", CO_INITIALIZE_EX);
    private static final MethodHandle CO_CREATE_INSTANCE_MH = bind(OLE32, "CoCreateInstance", CO_CREATE_INSTANCE);

    /** COM methods have no symbol: these handles take the function pointer read from the vtable as first argument. */
    private static final MethodHandle IUNKNOWN_RELEASE_MH = LINKER.downcallHandle(IUNKNOWN_RELEASE);
    private static final MethodHandle ITASKBAR_LIST_HR_INIT_MH = LINKER.downcallHandle(ITASKBAR_LIST_HR_INIT);
    private static final MethodHandle ITASKBAR_LIST3_SET_OVERLAY_ICON_MH =
            LINKER.downcallHandle(ITASKBAR_LIST3_SET_OVERLAY_ICON);

    static long getWindowLongPtr(MemorySegment hwnd, int index) {
        try {
            return (long) GET_WINDOW_LONG_PTR_W_MH.invokeExact(hwnd, index);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static boolean getWindowRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            return (int) GET_WINDOW_RECT_MH.invokeExact(hwnd, rect) != 0;
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static boolean screenToClient(MemorySegment hwnd, MemorySegment point) {
        try {
            return (int) SCREEN_TO_CLIENT_MH.invokeExact(hwnd, point) != 0;
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static int getDpiForWindow(MemorySegment hwnd) {
        try {
            return (int) GET_DPI_FOR_WINDOW_MH.invokeExact(hwnd);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static boolean trackMouseEvent(MemorySegment trackMouseEvent) {
        try {
            return (int) TRACK_MOUSE_EVENT_MH.invokeExact(trackMouseEvent) != 0;
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static MemorySegment createIcon(int width, int height, int planes, int bitsPerPixel,
            MemorySegment andBits, MemorySegment xorBits) {
        try {
            return (MemorySegment) CREATE_ICON_MH.invokeExact(
                    MemorySegment.NULL, width, height, (byte) planes, (byte) bitsPerPixel, andBits, xorBits);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static boolean destroyIcon(MemorySegment icon) {
        try {
            return (int) DESTROY_ICON_MH.invokeExact(icon) != 0;
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    /** Returns the HRESULT. */
    static int dwmSetWindowAttribute(MemorySegment hwnd, int attribute, MemorySegment value) {
        try {
            return (int) DWM_SET_WINDOW_ATTRIBUTE_MH.invokeExact(hwnd, attribute, value, (int) value.byteSize());
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    /** Returns the HRESULT. */
    static int dwmGetWindowAttribute(MemorySegment hwnd, int attribute, MemorySegment value) {
        try {
            return (int) DWM_GET_WINDOW_ATTRIBUTE_MH.invokeExact(hwnd, attribute, value, (int) value.byteSize());
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static boolean setWindowSubclass(MemorySegment hwnd, MemorySegment proc, long id, long refData) {
        try {
            return (int) SET_WINDOW_SUBCLASS_MH.invokeExact(hwnd, proc, id, refData) != 0;
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static boolean removeWindowSubclass(MemorySegment hwnd, MemorySegment proc, long id) {
        try {
            return (int) REMOVE_WINDOW_SUBCLASS_MH.invokeExact(hwnd, proc, id) != 0;
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static long defSubclassProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (long) DEF_SUBCLASS_PROC_MH.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    /** Returns the HRESULT. */
    static int coInitializeEx(int coInit) {
        try {
            return (int) CO_INITIALIZE_EX_MH.invokeExact(MemorySegment.NULL, coInit);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    /** Returns the HRESULT; the interface pointer is written to {@code out}. */
    static int coCreateInstance(MemorySegment clsid, int clsContext, MemorySegment iid, MemorySegment out) {
        try {
            return (int) CO_CREATE_INSTANCE_MH.invokeExact(clsid, MemorySegment.NULL, clsContext, iid, out);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    static int release(MemorySegment comObject) {
        try {
            return (int) IUNKNOWN_RELEASE_MH.invokeExact(vtableSlot(comObject, VTABLE_RELEASE), comObject);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    /** ITaskbarList::HrInit. Returns the HRESULT. */
    static int taskbarListHrInit(MemorySegment taskbarList) {
        try {
            return (int) ITASKBAR_LIST_HR_INIT_MH.invokeExact(vtableSlot(taskbarList, VTABLE_HR_INIT), taskbarList);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    /** ITaskbarList3::SetOverlayIcon. Returns the HRESULT. */
    static int taskbarListSetOverlayIcon(MemorySegment taskbarList, MemorySegment hwnd, MemorySegment icon,
            MemorySegment description) {
        try {
            return (int) ITASKBAR_LIST3_SET_OVERLAY_ICON_MH.invokeExact(
                    vtableSlot(taskbarList, VTABLE_SET_OVERLAY_ICON), taskbarList, hwnd, icon, description);
        } catch (Throwable t) {
            throw Sdl.failed(t);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** A COM interface pointer points at a pointer to the vtable, an array of function pointers. */
    private static MemorySegment vtableSlot(MemorySegment comObject, int index) {
        MemorySegment vtable = comObject.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return vtable.reinterpret((index + 1) * ADDRESS.byteSize()).getAtIndex(ADDRESS, index);
    }

    static MemorySegment guid(Arena arena, String text) {
        String hex = text.replace("-", "");
        MemorySegment guid = arena.allocate(GUID);
        guid.set(JAVA_INT, 0, Integer.parseUnsignedInt(hex.substring(0, 8), 16));
        guid.set(JAVA_SHORT, 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        guid.set(JAVA_SHORT, 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) {
            guid.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(hex.substring(16 + 2 * i, 18 + 2 * i), 16));
        }
        return guid;
    }

    static MemorySegment wideString(Arena arena, String text) {
        return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
    }

    static String hresult(int hr) {
        return String.format("0x%08X", hr);
    }

    private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = library.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return LINKER.downcallHandle(symbol, descriptor);
    }
}
