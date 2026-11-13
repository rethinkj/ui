package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Handwritten bindings for the Win32, DWM, common-controls, shell and COM calls that the window
 * makes on the HWND SDL created. Only Windows touches this class; its handles are bound on first
 * use. COM objects are called through their vtables by hand: {@link #method} reads the function
 * of a slot, and a handle without an address ({@code Linker.downcallHandle(descriptor)}) calls
 * it, with the object as its first argument.
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

  static final int SM_CXDOUBLECLK = 36;
  static final int SM_CYDOUBLECLK = 37;

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
  static final FunctionDescriptor GET_SYSTEM_METRICS = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
  static final FunctionDescriptor DWM_SET_WINDOW_ATTRIBUTE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor SET_WINDOW_SUBCLASS =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG);
  static final FunctionDescriptor REMOVE_WINDOW_SUBCLASS =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);
  static final FunctionDescriptor DEF_SUBCLASS_PROC =
      FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);

  // ---- Icons, the taskbar and the system's colours (Win32Shell)

  static final int WM_SETTINGCHANGE = 0x001A;
  static final int WM_CHAR = 0x0102;
  static final int WM_KEYDOWN = 0x0100;
  /** The key of a character that SendInput sends as such (KEYEVENTF_UNICODE): no key of SDL. */
  static final int VK_PACKET = 0xE7;
  static final int WM_SYSCOLORCHANGE = 0x0015;
  static final int WM_SETICON = 0x0080;
  static final int WM_GETICON = 0x007F;
  static final int WM_DWMCOLORIZATIONCOLORCHANGED = 0x0320;
  static final int ICON_SMALL = 0;
  static final int ICON_BIG = 1;
  static final int SPI_GETHIGHCONTRAST = 0x0042;
  static final int SPI_SETHIGHCONTRAST = 0x0043;
  static final int HCF_HIGHCONTRASTON = 0x1;
  /** {@code HIGHCONTRASTW}: UINT cbSize, DWORD dwFlags, LPWSTR lpszDefaultScheme. */
  static final long HIGHCONTRAST_SIZE = 16;
  static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
  static final int DWMWA_TRANSITIONS_FORCEDISABLED = 3;
  /** {@code HKEY_CURRENT_USER}, a LONG sign-extended into a handle. */
  static final long HKEY_CURRENT_USER = 0xFFFFFFFF80000001L;
  static final int RRF_RT_REG_DWORD = 0x10;
  static final int CLSCTX_INPROC_SERVER = 0x1;
  static final int S_OK = 0;
  /** The size of a BITMAPINFOHEADER, which starts an icon's resource. */
  static final int BITMAPINFOHEADER_SIZE = 40;
  /** The version of an icon resource for {@code CreateIconFromResourceEx}. */
  static final int ICON_RESOURCE_VERSION = 0x00030000;

  /** {@code CLSID_TaskbarList}, {56FDF344-FD6D-11d0-958A-006097C9A090}, as a GUID's bytes. */
  static final byte[] CLSID_TASKBAR_LIST = guid(0x56FDF344, 0xFD6D, 0x11D0,
      0x95, 0x8A, 0x00, 0x60, 0x97, 0xC9, 0xA0, 0x90);
  /** {@code IID_ITaskbarList3}, {EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}. */
  static final byte[] IID_ITASKBAR_LIST3 = guid(0xEA1AFB91, 0x9E28, 0x4B86,
      0x90, 0xE9, 0x9E, 0x9F, 0x8A, 0x5E, 0xEF, 0xAF);

  // The slots of ITaskbarList3's vtable that the shell calls: IUnknown, then ITaskbarList's
  // HrInit, AddTab, DeleteTab, ActivateTab, SetActiveAlt, ITaskbarList2's MarkFullscreenWindow,
  // and ITaskbarList3's SetProgressValue, SetProgressState, RegisterTab, UnregisterTab,
  // SetTabOrder, SetTabActive, ThumbBarAddButtons, ThumbBarUpdateButtons, ThumbBarSetImageList,
  // SetOverlayIcon.
  static final int VTBL_RELEASE = 2;
  static final int VTBL_HR_INIT = 3;
  static final int VTBL_SET_OVERLAY_ICON = 18;

  static final FunctionDescriptor SEND_MESSAGE =
      FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
  /**
   * {@code HICON CreateIconFromResourceEx(PBYTE presbits, DWORD dwResSize, BOOL fIcon, DWORD
   * dwVer, int cxDesired, int cyDesired, UINT Flags)}
   */
  static final FunctionDescriptor CREATE_ICON_FROM_RESOURCE_EX = FunctionDescriptor.of(
      ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor DESTROY_ICON = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor REGISTER_WINDOW_MESSAGE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor SYSTEM_PARAMETERS_INFO =
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor SET_APP_USER_MODEL_ID = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  /** {@code HRESULT GetCurrentProcessExplicitAppUserModelID(PWSTR *AppID)} */
  static final FunctionDescriptor GET_APP_USER_MODEL_ID = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor CO_TASK_MEM_FREE = FunctionDescriptor.ofVoid(ADDRESS);
  /** {@code HRESULT CoCreateInstance(REFCLSID, LPUNKNOWN, DWORD, REFIID, LPVOID *)} */
  static final FunctionDescriptor CO_CREATE_INSTANCE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
  /**
   * {@code LSTATUS RegGetValueW(HKEY, LPCWSTR lpSubKey, LPCWSTR lpValue, DWORD dwFlags, LPDWORD
   * pdwType, PVOID pvData, LPDWORD pcbData)}
   */
  static final FunctionDescriptor REG_GET_VALUE = FunctionDescriptor.of(
      JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  /** {@code HRESULT DwmGetColorizationColor(DWORD *pcrColorization, BOOL *pfOpaqueBlend)} */
  static final FunctionDescriptor DWM_GET_COLORIZATION_COLOR =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);

  // COM methods, called through a vtable: the first argument is the object.
  /** {@code ULONG Release(IUnknown *This)}, and {@code HRESULT HrInit(ITaskbarList *This)}. */
  static final FunctionDescriptor COM_THIS = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  /**
   * {@code HRESULT SetOverlayIcon(ITaskbarList3 *This, HWND hwnd, HICON hIcon, LPCWSTR
   * pszDescription)}
   */
  static final FunctionDescriptor TASKBAR_SET_OVERLAY_ICON =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

  /**
   * {@code DWORD GetLongPathNameW(LPCWSTR lpszShortPath, LPWSTR lpszLongPath, DWORD cchBuffer)}:
   * the length copied without the NUL, or the length needed with it if the buffer is too small,
   * or 0 if it fails.
   */
  static final FunctionDescriptor GET_LONG_PATH_NAME =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);

  /** {@code int MessageBoxW(HWND hWnd, LPCWSTR lpText, LPCWSTR lpCaption, UINT uType)} */
  static final FunctionDescriptor MESSAGE_BOX = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS,
      ADDRESS, JAVA_INT);
  static final int MB_ICONERROR = 0x10;
  static final int MB_TASKMODAL = 0x2000;
  static final int MB_SETFOREGROUND = 0x10000;

  /** The handle of user32's message box, bound on first use; it needs no SDL. */
  private static final class Box {
    static final MethodHandle MESSAGE_BOX_H = H.bind(H.USER32, "MessageBoxW", MESSAGE_BOX);
  }

  /** {@code HWND FindWindowExW(HWND hWndParent, HWND hWndChildAfter, LPCWSTR, LPCWSTR)} */
  static final FunctionDescriptor FIND_WINDOW_EX =
      FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
  /** {@code HWND GetWindow(HWND hWnd, UINT uCmd)} */
  static final FunctionDescriptor GET_WINDOW = FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT);
  /** {@code BOOL PostMessageW(HWND, UINT, WPARAM, LPARAM)} */
  static final FunctionDescriptor POST_MESSAGE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
  static final int GW_OWNER = 4;
  static final int WM_CLOSE = 0x0010;

  /** The handles that find and close the dialogs a window owns, bound on first use. */
  private static final class Owned {
    static final MethodHandle FIND_WINDOW_EX_H = H.bind(H.USER32, "FindWindowExW", FIND_WINDOW_EX);
    static final MethodHandle GET_WINDOW_H = H.bind(H.USER32, "GetWindow", GET_WINDOW);
    static final MethodHandle POST_MESSAGE_H = H.bind(H.USER32, "PostMessageW", POST_MESSAGE);
  }

  /** The handles of the file system's calls, bound on first use. */
  private static final class Files {
    static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

    static final MethodHandle GET_LONG_PATH_NAME_H =
        H.bind(KERNEL32, "GetLongPathNameW", GET_LONG_PATH_NAME);
  }

  private static final class H {
    static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
    static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi", Arena.global());
    static final SymbolLookup COMCTL32 = SymbolLookup.libraryLookup("comctl32", Arena.global());

    static final MethodHandle SCREEN_TO_CLIENT_H =
        bind(USER32, "ScreenToClient", SCREEN_TO_CLIENT);
    static final MethodHandle TRACK_MOUSE_EVENT_H =
        bind(USER32, "TrackMouseEvent", TRACK_MOUSE_EVENT);
    static final MethodHandle IS_ICONIC_H = bind(USER32, "IsIconic", IS_ICONIC);
    static final MethodHandle GET_SYSTEM_METRICS_H =
        bind(USER32, "GetSystemMetrics", GET_SYSTEM_METRICS);
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

  /** The handles of Win32Shell, bound on its first use. */
  private static final class Shell {
    static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32", Arena.global());
    static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32", Arena.global());
    static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", Arena.global());

    static final MethodHandle SEND_MESSAGE_H = H.bind(H.USER32, "SendMessageW", SEND_MESSAGE);
    static final MethodHandle CREATE_ICON_FROM_RESOURCE_EX_H =
        H.bind(H.USER32, "CreateIconFromResourceEx", CREATE_ICON_FROM_RESOURCE_EX);
    static final MethodHandle DESTROY_ICON_H = H.bind(H.USER32, "DestroyIcon", DESTROY_ICON);
    static final MethodHandle REGISTER_WINDOW_MESSAGE_H =
        H.bind(H.USER32, "RegisterWindowMessageW", REGISTER_WINDOW_MESSAGE);
    static final MethodHandle SYSTEM_PARAMETERS_INFO_H =
        H.bind(H.USER32, "SystemParametersInfoW", SYSTEM_PARAMETERS_INFO);
    static final MethodHandle SET_APP_USER_MODEL_ID_H =
        H.bind(SHELL32, "SetCurrentProcessExplicitAppUserModelID", SET_APP_USER_MODEL_ID);
    static final MethodHandle GET_APP_USER_MODEL_ID_H =
        H.bind(SHELL32, "GetCurrentProcessExplicitAppUserModelID", GET_APP_USER_MODEL_ID);
    static final MethodHandle CO_TASK_MEM_FREE_H = H.bind(OLE32, "CoTaskMemFree", CO_TASK_MEM_FREE);
    static final MethodHandle CO_CREATE_INSTANCE_H =
        H.bind(OLE32, "CoCreateInstance", CO_CREATE_INSTANCE);
    static final MethodHandle REG_GET_VALUE_H = H.bind(ADVAPI32, "RegGetValueW", REG_GET_VALUE);
    static final MethodHandle DWM_GET_COLORIZATION_COLOR_H =
        H.bind(H.DWMAPI, "DwmGetColorizationColor", DWM_GET_COLORIZATION_COLOR);

    // Handles that take the function's address first, for the methods of COM objects.
    static final MethodHandle COM_THIS_H = Linker.nativeLinker().downcallHandle(COM_THIS);
    static final MethodHandle TASKBAR_SET_OVERLAY_ICON_H =
        Linker.nativeLinker().downcallHandle(TASKBAR_SET_OVERLAY_ICON);
  }

  /** A GUID's 16 bytes as they lie in memory: Data1 to Data3 little-endian, then Data4. */
  static byte[] guid(int data1, int data2, int data3, int... data4) {
    byte[] b = new byte[16];
    for (int i = 0; i < 4; i++) {
      b[i] = (byte) (data1 >>> 8 * i);
    }
    b[4] = (byte) data2;
    b[5] = (byte) (data2 >>> 8);
    b[6] = (byte) data3;
    b[7] = (byte) (data3 >>> 8);
    for (int i = 0; i < 8; i++) {
      b[8 + i] = (byte) data4[i];
    }
    return b;
  }

  /** A NUL-terminated UTF-16 string for a {@code LPCWSTR}. */
  static MemorySegment wide(Arena arena, String text) {
    return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
  }

  static long sendMessage(MemorySegment hwnd, int message, long wParam, long lParam) {
    try {
      return (long) Shell.SEND_MESSAGE_H.invokeExact(hwnd, message, wParam, lParam);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** @return the icon, or NULL if Windows could not make it */
  static MemorySegment createIconFromResource(MemorySegment resource, int width, int height) {
    try {
      return (MemorySegment) Shell.CREATE_ICON_FROM_RESOURCE_EX_H.invokeExact(resource,
          (int) resource.byteSize(), 1, ICON_RESOURCE_VERSION, width, height, 0);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  static boolean destroyIcon(MemorySegment icon) {
    try {
      return (int) Shell.DESTROY_ICON_H.invokeExact(icon) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  static int registerWindowMessage(String name) {
    try (Arena arena = Arena.ofConfined()) {
      return (int) Shell.REGISTER_WINDOW_MESSAGE_H.invokeExact(wide(arena, name));
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** Whether high contrast is on ({@code SPI_GETHIGHCONTRAST}). */
  static boolean highContrast() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment hc = arena.allocate(HIGHCONTRAST_SIZE, 8);
      hc.set(JAVA_INT, 0, (int) HIGHCONTRAST_SIZE);
      int ok = (int) Shell.SYSTEM_PARAMETERS_INFO_H.invokeExact(
          SPI_GETHIGHCONTRAST, (int) HIGHCONTRAST_SIZE, hc, 0);
      return ok != 0 && (hc.get(JAVA_INT, 4) & HCF_HIGHCONTRASTON) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** @return the HRESULT */
  static int setCurrentProcessExplicitAppUserModelId(String id) {
    try (Arena arena = Arena.ofConfined()) {
      return (int) Shell.SET_APP_USER_MODEL_ID_H.invokeExact(wide(arena, id));
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** The process's AppUserModelID, or null if it has none of its own. */
  static String currentProcessExplicitAppUserModelId() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      if ((int) Shell.GET_APP_USER_MODEL_ID_H.invokeExact(out) != S_OK) {
        return null;
      }
      MemorySegment text = out.get(ADDRESS, 0);
      try {
        return text.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
      } finally {
        Shell.CO_TASK_MEM_FREE_H.invokeExact(text);
      }
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** A DWORD value under {@code HKEY_CURRENT_USER}, or null if it is not there. */
  static Integer userDword(String key, String value) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment data = arena.allocate(JAVA_INT);
      MemorySegment size = arena.allocateFrom(JAVA_INT, 4);
      int status = (int) Shell.REG_GET_VALUE_H.invokeExact(
          MemorySegment.ofAddress(HKEY_CURRENT_USER), wide(arena, key), wide(arena, value),
          RRF_RT_REG_DWORD, MemorySegment.NULL, data, size);
      return status == 0 ? data.get(JAVA_INT, 0) : null;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** DWM's colorization colour as 0xAARRGGBB, or null if DWM gives none. */
  static Integer dwmColorizationColor() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment colour = arena.allocate(JAVA_INT);
      MemorySegment opaque = arena.allocate(JAVA_INT);
      int hr = (int) Shell.DWM_GET_COLORIZATION_COLOR_H.invokeExact(colour, opaque);
      return hr == S_OK ? colour.get(JAVA_INT, 0) : null;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /**
   * Creates an in-process COM object on this thread, which COM has to be initialised on (SDL
   * does that on the windows' thread).
   *
   * @return the interface, or NULL with the HRESULT in {@code hresult[0]}
   */
  static MemorySegment coCreateInstance(byte[] clsid, byte[] iid, int[] hresult) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      int hr = (int) Shell.CO_CREATE_INSTANCE_H.invokeExact(arena.allocateFrom(JAVA_BYTE, clsid),
          MemorySegment.NULL, CLSCTX_INPROC_SERVER, arena.allocateFrom(JAVA_BYTE, iid), out);
      hresult[0] = hr;
      return hr == S_OK ? out.get(ADDRESS, 0) : MemorySegment.NULL;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** The function in slot {@code index} of a COM object's vtable. */
  static MemorySegment method(MemorySegment object, int index) {
    MemorySegment vtable = object.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    return vtable.reinterpret((index + 1) * ADDRESS.byteSize())
        .get(ADDRESS, index * ADDRESS.byteSize());
  }

  /** Calls a method that takes only the object, such as Release or HrInit. */
  static int comCall(MemorySegment object, int index) {
    try {
      return (int) Shell.COM_THIS_H.invokeExact(method(object, index), object);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** @return the HRESULT */
  static int taskbarSetOverlayIcon(
      MemorySegment taskbar, MemorySegment hwnd, MemorySegment icon, String description) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment text = description == null ? MemorySegment.NULL : wide(arena, description);
      return (int) Shell.TASKBAR_SET_OVERLAY_ICON_H.invokeExact(
          method(taskbar, VTBL_SET_OVERLAY_ICON), taskbar, hwnd, icon, text);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /**
   * The long form of a path whose parts may be 8.3 short names ({@code GetLongPathNameW}), as
   * the system's file dialog gives a path longer than {@code MAX_PATH}; null if the path is not
   * there or Windows cannot say. Paths of any length go with the {@code \\?\} prefix, which the
   * answer comes back without.
   */
  static String longPathName(String path) {
    String in = path.startsWith("\\\\?\\") ? path
        : path.startsWith("\\\\") ? "\\\\?\\UNC\\" + path.substring(2) : "\\\\?\\" + path;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment from = wide(arena, in);
      int size = 1024;
      for (int attempt = 0; attempt < 3; attempt++) {
        MemorySegment to = arena.allocate(2L * size, 2);
        int n = (int) Files.GET_LONG_PATH_NAME_H.invokeExact(from, to, size);
        if (n == 0) {
          return null;
        }
        if (n < size) {
          String out = to.getString(0, StandardCharsets.UTF_16LE);
          return out.startsWith("\\\\?\\UNC\\") ? "\\\\" + out.substring(8)
              : out.startsWith("\\\\?\\") ? out.substring(4) : out;
        }
        size = n;
      }
      return null;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /**
   * Shows an error box with an OK button and waits until it closes ({@code MessageBoxW}), with no
   * window and without SDL, for when SDL itself cannot load.
   *
   * @return false if Windows could not show it
   */
  static boolean errorBox(String title, String text) {
    try (Arena arena = Arena.ofConfined()) {
      return (int) Box.MESSAGE_BOX_H.invokeExact(MemorySegment.NULL, wide(arena, text),
          wide(arena, title), MB_ICONERROR | MB_TASKMODAL | MB_SETFOREGROUND) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /**
   * The system's dialogs (window class {@code #32770}) that {@code owner} owns, such as a file
   * dialog that SDL shows on a thread of its own.
   */
  static List<MemorySegment> ownedDialogs(MemorySegment owner) {
    List<MemorySegment> found = new ArrayList<>();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment dialogClass = wide(arena, "#32770");
      MemorySegment after = MemorySegment.NULL;
      for (int i = 0; i < 10_000; i++) {
        MemorySegment next = (MemorySegment) Owned.FIND_WINDOW_EX_H.invokeExact(
            MemorySegment.NULL, after, dialogClass, MemorySegment.NULL);
        if (next.equals(MemorySegment.NULL)) {
          break;
        }
        MemorySegment by = (MemorySegment) Owned.GET_WINDOW_H.invokeExact(next, GW_OWNER);
        if (by.address() == owner.address()) {
          found.add(next);
        }
        after = next;
      }
      return found;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** Asks a window to close ({@code WM_CLOSE}), as its close button does; a dialog cancels. */
  static boolean postClose(MemorySegment hwnd) {
    try {
      return (int) Owned.POST_MESSAGE_H.invokeExact(hwnd, WM_CLOSE, 0L, 0L) != 0;
    } catch (Throwable t) {
      throw Sdl.failed(t);
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

  /** A metric or setting of the system, such as {@link #SM_CXDOUBLECLK}; 0 if it fails. */
  static int getSystemMetrics(int index) {
    try {
      return (int) H.GET_SYSTEM_METRICS_H.invokeExact(index);
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
