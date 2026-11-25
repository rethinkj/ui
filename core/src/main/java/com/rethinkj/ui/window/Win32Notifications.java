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
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Desktop notifications on Windows 11 via WinRT toasts ({@code ToastNotificationManager}, {@code
 * ToastNotification}, {@code XmlDocument}) through COM and FFM. Neither {@code combase.dll} nor
 * {@code advapi32.dll} is loaded until the app first shows a notification.
 *
 * <p>All WinRT calls run on a single MTA daemon thread ({@code RoInitialize(RO_INIT_MULTITHREADED)})
 * so they never interfere with SDL's STA apartment on the windows' thread, never block the UI, and
 * can release toast COM objects safely after WinRT dispatches {@code Activated}, {@code Dismissed}
 * or {@code Failed} callbacks on its own threadpool threads.
 */
final class Win32Notifications {

  private Win32Notifications() {}

  private static final int RO_INIT_MULTITHREADED = 1;
  private static final int KEY_SET_VALUE = 0x0002;
  private static final int REG_SZ = 1;
  private static final int E_NOINTERFACE = 0x80004002;
  private static final int E_POINTER = 0x80004003;
  private static final int TOAST_DISMISSAL_APPLICATION_HIDDEN = 1;
  // The notification center keeps the last 20 toasts of an app; older ones cannot be clicked.
  private static final int KEPT = 20;

  private static final String CLASS_XML_DOCUMENT = "Windows.Data.Xml.Dom.XmlDocument";
  private static final String CLASS_TOAST_MANAGER =
      "Windows.UI.Notifications.ToastNotificationManager";
  private static final String CLASS_TOAST_NOTIFICATION =
      "Windows.UI.Notifications.ToastNotification";

  /** {@code IID_IUnknown}, {00000000-0000-0000-C000-000000000046}. */
  private static final byte[] IID_IUNKNOWN =
      Win32.guid(0x00000000, 0x0000, 0x0000, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46);

  /** {@code IID_IAgileObject}, {94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}. */
  private static final byte[] IID_IAGILE_OBJECT =
      Win32.guid(0x94EA2B94, 0xE9CC, 0x49E0, 0xC0, 0xFF, 0xEE, 0x64, 0xCA, 0x8F, 0x5B, 0x90);

  /** {@code Windows.Data.Xml.Dom.IXmlDocument}, {F7F3A506-1E87-42D6-BCFB-B8C809FA5494}. */
  private static final byte[] IID_IXML_DOCUMENT =
      Win32.guid(0xF7F3A506, 0x1E87, 0x42D6, 0xBC, 0xFB, 0xB8, 0xC8, 0x09, 0xFA, 0x54, 0x94);

  /** {@code Windows.Data.Xml.Dom.IXmlDocumentIO}, {6CD0E74E-EE65-4489-9EBF-CA43E87BA637}. */
  private static final byte[] IID_IXML_DOCUMENT_IO =
      Win32.guid(0x6CD0E74E, 0xEE65, 0x4489, 0x9E, 0xBF, 0xCA, 0x43, 0xE8, 0x7B, 0xA6, 0x37);

  /**
   * {@code Windows.UI.Notifications.IToastNotificationManagerStatics},
   * {50AC103F-D235-4598-BBEF-98FE4D1A3AD4}.
   */
  private static final byte[] IID_ITOAST_MANAGER_STATICS =
      Win32.guid(0x50AC103F, 0xD235, 0x4598, 0xBB, 0xEF, 0x98, 0xFE, 0x4D, 0x1A, 0x3A, 0xD4);

  /**
   * {@code Windows.UI.Notifications.IToastNotificationFactory},
   * {04124B20-82C6-4229-B109-FD9ED4662B53}.
   */
  private static final byte[] IID_ITOAST_FACTORY =
      Win32.guid(0x04124B20, 0x82C6, 0x4229, 0xB1, 0x09, 0xFD, 0x9E, 0xD4, 0x66, 0x2B, 0x53);

  /**
   * {@code ITypedEventHandler<ToastNotification*, IInspectable*>},
   * {AB54DE2D-97D9-5528-B6AD-105AFE156530}.
   */
  private static final byte[] IID_ACTIVATED_HANDLER =
      Win32.guid(0xAB54DE2D, 0x97D9, 0x5528, 0xB6, 0xAD, 0x10, 0x5A, 0xFE, 0x15, 0x65, 0x30);

  /**
   * {@code ITypedEventHandler<ToastNotification*, ToastDismissedEventArgs*>},
   * {61C2402F-0ED0-5A18-AB69-59F4AA99A368}.
   */
  private static final byte[] IID_DISMISSED_HANDLER =
      Win32.guid(0x61C2402F, 0x0ED0, 0x5A18, 0xAB, 0x69, 0x59, 0xF4, 0xAA, 0x99, 0xA3, 0x68);

  /**
   * {@code ITypedEventHandler<ToastNotification*, ToastFailedEventArgs*>},
   * {95E3E803-C969-5E3A-9753-EA2AD22A9A33}.
   */
  private static final byte[] IID_FAILED_HANDLER =
      Win32.guid(0x95E3E803, 0xC969, 0x5E3A, 0x97, 0x53, 0xEA, 0x2A, 0xD2, 0x2A, 0x9A, 0x33);

  // COM / WinRT vtable slot indexes.
  // IUnknown: 0 QueryInterface, 1 AddRef, 2 Release.
  // IInspectable (inherits IUnknown): 3 GetIids, 4 GetRuntimeClassName, 5 GetTrustLevel.
  /** IUnknown::QueryInterface — slot 0. */
  private static final int VTBL_QUERY_INTERFACE = 0;

  /** IUnknown::Release — slot 2. */
  private static final int VTBL_RELEASE = 2;

  /** IXmlDocumentIO::LoadXml — slot 6 (first method after IInspectable). */
  private static final int VTBL_XML_LOAD_XML = 6;

  /**
   * IToastNotificationManagerStatics::CreateToastNotifierWithId — slot 7 (slot 6 is
   * CreateToastNotifier, slot 7 is CreateToastNotifierWithId, slot 8 is GetTemplateContent).
   */
  private static final int VTBL_MANAGER_CREATE_NOTIFIER_WITH_ID = 7;

  /** IToastNotifier::Show — slot 6 (first method after IInspectable). */
  private static final int VTBL_NOTIFIER_SHOW = 6;

  /** IToastNotificationFactory::CreateToastNotification — slot 6 (only method after IInspectable). */
  private static final int VTBL_FACTORY_CREATE_TOAST = 6;

  // IToastNotification (inherits IInspectable):
  // 6 get_Content, 7 put_ExpirationTime, 8 get_ExpirationTime,
  // 9 add_Dismissed, 10 remove_Dismissed, 11 add_Activated, 12 remove_Activated,
  // 13 add_Failed, 14 remove_Failed.
  /** IToastNotification::add_Dismissed — slot 9. */
  private static final int VTBL_TOAST_ADD_DISMISSED = 9;

  /** IToastNotification::remove_Dismissed — slot 10. */
  private static final int VTBL_TOAST_REMOVE_DISMISSED = 10;

  /** IToastNotification::add_Activated — slot 11. */
  private static final int VTBL_TOAST_ADD_ACTIVATED = 11;

  /** IToastNotification::remove_Activated — slot 12. */
  private static final int VTBL_TOAST_REMOVE_ACTIVATED = 12;

  /** IToastNotification::add_Failed — slot 13. */
  private static final int VTBL_TOAST_ADD_FAILED = 13;

  /** IToastNotification::remove_Failed — slot 14. */
  private static final int VTBL_TOAST_REMOVE_FAILED = 14;

  /** IToastDismissedEventArgs::get_Reason — slot 6 (first method after IInspectable). */
  private static final int VTBL_DISMISSED_GET_REASON = 6;

  /** IToastFailedEventArgs::get_ErrorCode — slot 6 (first method after IInspectable). */
  private static final int VTBL_FAILED_GET_ERROR_CODE = 6;

  // All native descriptors are static final constants for GraalVM native-image registration.
  static final FunctionDescriptor RO_INITIALIZE = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
  static final FunctionDescriptor WINDOWS_CREATE_STRING =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
  static final FunctionDescriptor WINDOWS_DELETE_STRING = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor RO_GET_ACTIVATION_FACTORY =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor RO_ACTIVATE_INSTANCE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor REG_CREATE_KEY_EX = FunctionDescriptor.of(
      JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor REG_SET_VALUE_EX =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor REG_CLOSE_KEY = FunctionDescriptor.of(JAVA_INT, ADDRESS);

  // COM vtable downcall descriptors (first argument is the COM object `this`).
  static final FunctionDescriptor COM_THIS = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor COM_THIS_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor COM_THIS_PTR_PTR =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor COM_THIS_LONG =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG);

  // ITypedEventHandler COM upcall descriptors (inherits IUnknown: 0 QI, 1 AddRef, 2 Release, 3
  // Invoke).
  static final FunctionDescriptor HANDLER_QI =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor HANDLER_REF = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor HANDLER_INVOKE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

  /** Loaded lazily on the first Windows notification, never from the headless path. */
  private static final class H {
    static final SymbolLookup COMBASE = SymbolLookup.libraryLookup("combase", Arena.global());
    static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", Arena.global());

    static final MethodHandle RO_INITIALIZE_H = bind(COMBASE, "RoInitialize", RO_INITIALIZE);
    static final MethodHandle WINDOWS_CREATE_STRING_H =
        bind(COMBASE, "WindowsCreateString", WINDOWS_CREATE_STRING);
    static final MethodHandle WINDOWS_DELETE_STRING_H =
        bind(COMBASE, "WindowsDeleteString", WINDOWS_DELETE_STRING);
    static final MethodHandle RO_GET_ACTIVATION_FACTORY_H =
        bind(COMBASE, "RoGetActivationFactory", RO_GET_ACTIVATION_FACTORY);
    static final MethodHandle RO_ACTIVATE_INSTANCE_H =
        bind(COMBASE, "RoActivateInstance", RO_ACTIVATE_INSTANCE);
    static final MethodHandle REG_CREATE_KEY_EX_H =
        bind(ADVAPI32, "RegCreateKeyExW", REG_CREATE_KEY_EX);
    static final MethodHandle REG_SET_VALUE_EX_H =
        bind(ADVAPI32, "RegSetValueExW", REG_SET_VALUE_EX);
    static final MethodHandle REG_CLOSE_KEY_H = bind(ADVAPI32, "RegCloseKey", REG_CLOSE_KEY);

    static final MethodHandle COM_THIS_H = Linker.nativeLinker().downcallHandle(COM_THIS);
    static final MethodHandle COM_THIS_PTR_H = Linker.nativeLinker().downcallHandle(COM_THIS_PTR);
    static final MethodHandle COM_THIS_PTR_PTR_H =
        Linker.nativeLinker().downcallHandle(COM_THIS_PTR_PTR);
    static final MethodHandle COM_THIS_LONG_H = Linker.nativeLinker().downcallHandle(COM_THIS_LONG);

    /** Shared 4-slot vtable for our ITypedEventHandler COM objects: [QI, AddRef, Release, Invoke]. */
    static final MemorySegment HANDLER_VTABLE = buildVtable();

    private static MemorySegment buildVtable() {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      MemorySegment vtable =
          Arena.global().allocate(4 * ADDRESS.byteSize(), ADDRESS.byteAlignment());
      vtable.setAtIndex(
          ADDRESS,
          0,
          Window.upcall(lookup, Win32Notifications.class, "queryInterface", HANDLER_QI));
      vtable.setAtIndex(
          ADDRESS, 1, Window.upcall(lookup, Win32Notifications.class, "addRef", HANDLER_REF));
      vtable.setAtIndex(
          ADDRESS, 2, Window.upcall(lookup, Win32Notifications.class, "release", HANDLER_REF));
      vtable.setAtIndex(
          ADDRESS, 3, Window.upcall(lookup, Win32Notifications.class, "invoke", HANDLER_INVOKE));
      return vtable;
    }

    private static MethodHandle bind(
        SymbolLookup library, String name, FunctionDescriptor descriptor) {
      MemorySegment symbol = library
          .find(name)
          .orElseThrow(() -> new UnsatisfiedLinkError("Windows has no function " + name));
      return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }
  }

  private record Request(
      String id,
      String name,
      String iconUri,
      String title,
      String body,
      Runnable clicked,
      Consumer<String> failed) {}

  /** A live toast COM object kept until clicked, dismissed by user/app, or failed. */
  private static final class ActiveToast {
    final MemorySegment toast;
    final long activatedToken;
    final long dismissedToken;
    final long failedToken;
    final AtomicBoolean settled = new AtomicBoolean();

    ActiveToast(MemorySegment toast, long activatedToken, long dismissedToken, long failedToken) {
      this.toast = toast;
      this.activatedToken = activatedToken;
      this.dismissedToken = dismissedToken;
      this.failedToken = failedToken;
    }
  }

  /** State behind one 16-byte native COM handler object: [vtablePtr, handlerId]. */
  private static final class ComHandler {
    final long id;
    final Arena arena;
    final MemorySegment object;
    final byte[] expectedIid;
    final AtomicInteger refCount = new AtomicInteger(1);
    final BiConsumer<MemorySegment, MemorySegment> onInvoke;

    ComHandler(byte[] expectedIid, BiConsumer<MemorySegment, MemorySegment> onInvoke) {
      this.id = HANDLER_IDS.incrementAndGet();
      this.arena = Arena.ofShared();
      this.object = arena.allocate(2 * ADDRESS.byteSize(), ADDRESS.byteAlignment());
      this.object.set(ADDRESS, 0, H.HANDLER_VTABLE);
      this.object.set(JAVA_LONG, ADDRESS.byteSize(), id);
      this.expectedIid = expectedIid;
      this.onInvoke = onInvoke;
      HANDLERS.put(id, this);
    }
  }

  private static final AtomicLong HANDLER_IDS = new AtomicLong();
  private static final ConcurrentHashMap<Long, ComHandler> HANDLERS = new ConcurrentHashMap<>();
  private static final ConcurrentLinkedQueue<ActiveToast> ACTIVE = new ConcurrentLinkedQueue<>();

  private static final Object LOCK = new Object();
  private static final ConcurrentLinkedQueue<Request> OUTGOING = new ConcurrentLinkedQueue<>();
  private static final ConcurrentLinkedQueue<ActiveToast> RELEASES = new ConcurrentLinkedQueue<>();
  private static boolean started;
  private static String registeredId;
  private static String registeredName;
  private static String registeredIconUri;

  static void show(
      Window window, String title, String body, Runnable clicked, Consumer<String> failed) {
    String id = Window.appId();
    if (id == null) {
      failed.accept("Desktop notifications on Windows need an app identity: call"
          + " Window.identity(id, name) before opening the first window.");
      return;
    }
    String name = Window.appName();
    if (name == null || name.isEmpty()) {
      name = window != null && window.title() != null && !window.title().isEmpty()
          ? window.title()
          : id;
    }
    Path icon = window != null ? SystemNotifications.iconFile(window, id) : null;
    String iconUri = icon != null ? icon.toString() : null;
    OUTGOING.add(new Request(id, name, iconUri, title, body, clicked, failed));
    synchronized (LOCK) {
      if (!started) {
        started = true;
        Thread.ofPlatform()
            .daemon(true)
            .name("notifications-winrt")
            .start(Win32Notifications::loop);
      }
      LOCK.notifyAll();
    }
  }

  private static void loop() {
    boolean initialized = false;
    while (true) {
      ActiveToast stale;
      while ((stale = RELEASES.poll()) != null) {
        releaseToast(stale);
      }
      Request req;
      while ((req = OUTGOING.poll()) != null) {
        if (!initialized) {
          try {
            int hr = (int) H.RO_INITIALIZE_H.invokeExact(RO_INIT_MULTITHREADED);
            if (hr < 0) {
              fail(req, "RoInitialize failed: " + hex(hr));
              continue;
            }
            initialized = true;
          } catch (Throwable t) {
            fail(req, "Could not initialize Windows notifications: " + t.getMessage());
            continue;
          }
        }
        send(req);
      }
      synchronized (LOCK) {
        while (OUTGOING.isEmpty() && RELEASES.isEmpty()) {
          try {
            LOCK.wait();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    }
  }

  private static void send(Request req) {
    MemorySegment xmlInspectable = MemorySegment.NULL;
    MemorySegment xmlDocIo = MemorySegment.NULL;
    MemorySegment xmlDoc = MemorySegment.NULL;
    MemorySegment toastFactory = MemorySegment.NULL;
    MemorySegment toast = MemorySegment.NULL;
    MemorySegment manager = MemorySegment.NULL;
    MemorySegment notifier = MemorySegment.NULL;
    boolean kept = false;
    try (Arena arena = Arena.ofConfined()) {
      registerAumid(arena, req.id, req.name, req.iconUri);

      // 1. Activate XmlDocument and load ToastGeneric XML via IXmlDocumentIO::LoadXml (slot 6).
      xmlInspectable = activateInstance(arena, CLASS_XML_DOCUMENT);
      xmlDocIo = queryInterface(arena, xmlInspectable, IID_IXML_DOCUMENT_IO);
      loadXml(arena, xmlDocIo, toastXml(req.title, req.body));
      xmlDoc = queryInterface(arena, xmlInspectable, IID_IXML_DOCUMENT);

      // 2. Create ToastNotification via IToastNotificationFactory::CreateToastNotification (slot
      // 6).
      toastFactory = activationFactory(arena, CLASS_TOAST_NOTIFICATION, IID_ITOAST_FACTORY);
      toast = createToast(arena, toastFactory, xmlDoc);

      // 3. Register Activated (slot 11), Dismissed (slot 9), Failed (slot 13) event handlers.
      ActiveToast[] holder = new ActiveToast[1];
      long activatedToken =
          addEventHandler(arena, toast, VTBL_TOAST_ADD_ACTIVATED, IID_ACTIVATED_HANDLER, (_, _) -> {
            ActiveToast entry = holder[0];
            if (entry != null && entry.settled.compareAndSet(false, true)) {
              try {
                req.clicked.run();
              } catch (Throwable _) {
                // never escape a COM upcall
              }
              scheduleRelease(entry);
            }
          });
      long dismissedToken = addEventHandler(
          arena, toast, VTBL_TOAST_ADD_DISMISSED, IID_DISMISSED_HANDLER, (_, args) -> {
            // A banner that timed out or was closed with × stays clickable in the notification
            // center, so only a toast the app hid lets go of its handlers here.
            if (dismissalReason(args) != TOAST_DISMISSAL_APPLICATION_HIDDEN) {
              return;
            }
            ActiveToast entry = holder[0];
            if (entry != null && entry.settled.compareAndSet(false, true)) {
              scheduleRelease(entry);
            }
          });
      long failedToken =
          addEventHandler(arena, toast, VTBL_TOAST_ADD_FAILED, IID_FAILED_HANDLER, (_, args) -> {
            int code = failedErrorCode(args);
            ActiveToast entry = holder[0];
            if (entry != null && entry.settled.compareAndSet(false, true)) {
              fail(req, "Windows could not show the notification: " + hex(code));
              scheduleRelease(entry);
            }
          });
      ActiveToast entry = new ActiveToast(toast, activatedToken, dismissedToken, failedToken);
      holder[0] = entry;

      // 4. Create notifier with AppUserModelID (slot 7) and show toast (slot 6).
      manager = activationFactory(arena, CLASS_TOAST_MANAGER, IID_ITOAST_MANAGER_STATICS);
      notifier = createNotifierWithId(arena, manager, req.id);
      int hr = (int)
          H.COM_THIS_PTR_H.invokeExact(Win32.method(notifier, VTBL_NOTIFIER_SHOW), notifier, toast);
      if (hr < 0) {
        kept = true; // releaseToast releases toast; avoid a second Release in finally
        releaseToast(entry);
        fail(req, "IToastNotifier::Show failed: " + hex(hr));
        return;
      }
      kept = true;
      // Kept only once shown, so a toast that failed to show evicts none still clickable.
      ACTIVE.add(entry);
      while (ACTIVE.size() > KEPT) {
        ActiveToast oldest = ACTIVE.poll();
        if (oldest != null && oldest.settled.compareAndSet(false, true)) {
          releaseToast(oldest);
        }
      }
    } catch (Throwable t) {
      fail(req, "Could not show Windows notification: " + t.getMessage());
    } finally {
      comRelease(notifier);
      comRelease(manager);
      if (!kept) {
        comRelease(toast);
      }
      comRelease(toastFactory);
      comRelease(xmlDoc);
      comRelease(xmlDocIo);
      comRelease(xmlInspectable);
    }
  }

  /**
   * Registers the AppUserModelID for an unpackaged app under {@code
   * HKCU\Software\Classes\AppUserModelId\<id>} with {@code DisplayName} and, when an icon is
   * available, {@code IconUri}.
   */
  private static void registerAumid(Arena arena, String id, String name, String iconUri)
      throws Throwable {
    if (iconUri == null) {
      iconUri = registeredIconUri;
    }
    if (id.equals(registeredId)
        && name.equals(registeredName)
        && (iconUri == null || iconUri.equals(registeredIconUri))) {
      return;
    }
    MemorySegment keyOut = arena.allocate(ADDRESS);
    int status = (int) H.REG_CREATE_KEY_EX_H.invokeExact(
        MemorySegment.ofAddress(Win32.HKEY_CURRENT_USER),
        Win32.wide(arena, "Software\\Classes\\AppUserModelId\\" + id),
        0,
        MemorySegment.NULL,
        0,
        KEY_SET_VALUE,
        MemorySegment.NULL,
        keyOut,
        MemorySegment.NULL);
    if (status != 0) {
      return; // best-effort: a shortcut or installer may have registered it already
    }
    MemorySegment hKey = keyOut.get(ADDRESS, 0);
    try {
      setRegString(arena, hKey, "DisplayName", name);
      if (iconUri != null) {
        setRegString(arena, hKey, "IconUri", iconUri);
        setRegString(arena, hKey, "IconBackgroundColor", "0");
      }
      registeredId = id;
      registeredName = name;
      registeredIconUri = iconUri;
    } finally {
      int _ = (int) H.REG_CLOSE_KEY_H.invokeExact(hKey);
    }
  }

  private static void setRegString(Arena arena, MemorySegment hKey, String name, String value)
      throws Throwable {
    MemorySegment data = Win32.wide(arena, value);
    int _ = (int) H.REG_SET_VALUE_EX_H.invokeExact(
        hKey, Win32.wide(arena, name), 0, REG_SZ, data, (int) data.byteSize());
  }

  /** Builds the {@code ToastGeneric} XML payload with XML-escaped title and body. */
  static String toastXml(String title, String body) {
    StringBuilder xml =
        new StringBuilder("<toast><visual><binding template=\"ToastGeneric\"><text>");
    appendXmlEscaped(xml, title);
    xml.append("</text>");
    if (!body.isEmpty()) {
      xml.append("<text>");
      appendXmlEscaped(xml, body);
      xml.append("</text>");
    }
    xml.append("</binding></visual></toast>");
    return xml.toString();
  }

  private static void appendXmlEscaped(StringBuilder out, String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&apos;");
        default -> {
          if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
            out.append(c);
          }
        }
      }
    }
  }

  private static MemorySegment createHString(Arena arena, String text) throws Throwable {
    MemorySegment out = arena.allocate(ADDRESS);
    int hr =
        (int) H.WINDOWS_CREATE_STRING_H.invokeExact(Win32.wide(arena, text), text.length(), out);
    if (hr < 0) {
      throw new IllegalStateException("WindowsCreateString failed: " + hex(hr));
    }
    return out.get(ADDRESS, 0);
  }

  private static void deleteHString(MemorySegment hstring) {
    if (hstring != null && !hstring.equals(MemorySegment.NULL)) {
      try {
        int _ = (int) H.WINDOWS_DELETE_STRING_H.invokeExact(hstring);
      } catch (Throwable _) {
        // best-effort cleanup
      }
    }
  }

  private static MemorySegment activateInstance(Arena arena, String className) throws Throwable {
    MemorySegment hClass = createHString(arena, className);
    try {
      MemorySegment out = arena.allocate(ADDRESS);
      int hr = (int) H.RO_ACTIVATE_INSTANCE_H.invokeExact(hClass, out);
      if (hr < 0) {
        throw new IllegalStateException("RoActivateInstance(" + className + ") failed: " + hex(hr));
      }
      return out.get(ADDRESS, 0);
    } finally {
      deleteHString(hClass);
    }
  }

  private static MemorySegment activationFactory(Arena arena, String className, byte[] iid)
      throws Throwable {
    MemorySegment hClass = createHString(arena, className);
    try {
      MemorySegment out = arena.allocate(ADDRESS);
      int hr = (int) H.RO_GET_ACTIVATION_FACTORY_H.invokeExact(
          hClass, arena.allocateFrom(JAVA_BYTE, iid), out);
      if (hr < 0) {
        throw new IllegalStateException(
            "RoGetActivationFactory(" + className + ") failed: " + hex(hr));
      }
      return out.get(ADDRESS, 0);
    } finally {
      deleteHString(hClass);
    }
  }

  private static MemorySegment queryInterface(Arena arena, MemorySegment object, byte[] iid)
      throws Throwable {
    MemorySegment out = arena.allocate(ADDRESS);
    int hr = (int) H.COM_THIS_PTR_PTR_H.invokeExact(
        Win32.method(object, VTBL_QUERY_INTERFACE),
        object,
        arena.allocateFrom(JAVA_BYTE, iid),
        out);
    if (hr < 0) {
      throw new IllegalStateException("QueryInterface failed: " + hex(hr));
    }
    return out.get(ADDRESS, 0);
  }

  private static void loadXml(Arena arena, MemorySegment xmlDocIo, String xml) throws Throwable {
    MemorySegment hXml = createHString(arena, xml);
    try {
      int hr = (int)
          H.COM_THIS_PTR_H.invokeExact(Win32.method(xmlDocIo, VTBL_XML_LOAD_XML), xmlDocIo, hXml);
      if (hr < 0) {
        throw new IllegalStateException("IXmlDocumentIO::LoadXml failed: " + hex(hr));
      }
    } finally {
      deleteHString(hXml);
    }
  }

  private static MemorySegment createToast(Arena arena, MemorySegment factory, MemorySegment xmlDoc)
      throws Throwable {
    MemorySegment out = arena.allocate(ADDRESS);
    int hr = (int) H.COM_THIS_PTR_PTR_H.invokeExact(
        Win32.method(factory, VTBL_FACTORY_CREATE_TOAST), factory, xmlDoc, out);
    if (hr < 0) {
      throw new IllegalStateException(
          "IToastNotificationFactory::CreateToastNotification failed: " + hex(hr));
    }
    return out.get(ADDRESS, 0);
  }

  private static MemorySegment createNotifierWithId(
      Arena arena, MemorySegment manager, String appId) throws Throwable {
    MemorySegment hAppId = createHString(arena, appId);
    try {
      MemorySegment out = arena.allocate(ADDRESS);
      int hr = (int) H.COM_THIS_PTR_PTR_H.invokeExact(
          Win32.method(manager, VTBL_MANAGER_CREATE_NOTIFIER_WITH_ID), manager, hAppId, out);
      if (hr < 0) {
        throw new IllegalStateException(
            "IToastNotificationManagerStatics::CreateToastNotifierWithId failed: " + hex(hr));
      }
      return out.get(ADDRESS, 0);
    } finally {
      deleteHString(hAppId);
    }
  }

  private static long addEventHandler(
      Arena arena,
      MemorySegment toast,
      int slot,
      byte[] expectedIid,
      BiConsumer<MemorySegment, MemorySegment> onInvoke)
      throws Throwable {
    ComHandler handler = new ComHandler(expectedIid, onInvoke);
    try {
      MemorySegment tokenOut = arena.allocate(JAVA_LONG);
      int hr = (int) H.COM_THIS_PTR_PTR_H.invokeExact(
          Win32.method(toast, slot), toast, handler.object, tokenOut);
      if (hr < 0) {
        throw new IllegalStateException("add event handler (slot " + slot + ") failed: " + hex(hr));
      }
      return tokenOut.get(JAVA_LONG, 0);
    } finally {
      // Drop our initial reference; WinRT holds its own AddRef'd reference until remove_* /
      // Release.
      release(handler.object);
    }
  }

  private static void scheduleRelease(ActiveToast entry) {
    ACTIVE.remove(entry);
    RELEASES.add(entry);
    synchronized (LOCK) {
      LOCK.notifyAll();
    }
  }

  private static void releaseToast(ActiveToast entry) {
    try {
      int _ = (int) H.COM_THIS_LONG_H.invokeExact(
          Win32.method(entry.toast, VTBL_TOAST_REMOVE_ACTIVATED),
          entry.toast,
          entry.activatedToken);
      int _ = (int) H.COM_THIS_LONG_H.invokeExact(
          Win32.method(entry.toast, VTBL_TOAST_REMOVE_DISMISSED),
          entry.toast,
          entry.dismissedToken);
      int _ = (int) H.COM_THIS_LONG_H.invokeExact(
          Win32.method(entry.toast, VTBL_TOAST_REMOVE_FAILED), entry.toast, entry.failedToken);
    } catch (Throwable _) {
      // still release the toast below
    } finally {
      comRelease(entry.toast);
    }
  }

  private static int dismissalReason(MemorySegment args) {
    if (args.equals(MemorySegment.NULL)) {
      return -1;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(JAVA_INT);
      int hr = (int)
          H.COM_THIS_PTR_H.invokeExact(Win32.method(args, VTBL_DISMISSED_GET_REASON), args, out);
      return hr >= 0 ? out.get(JAVA_INT, 0) : -1;
    } catch (Throwable _) {
      return -1;
    }
  }

  private static int failedErrorCode(MemorySegment args) {
    if (args.equals(MemorySegment.NULL)) {
      return 0;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(JAVA_INT);
      int hr = (int)
          H.COM_THIS_PTR_H.invokeExact(Win32.method(args, VTBL_FAILED_GET_ERROR_CODE), args, out);
      return hr >= 0 ? out.get(JAVA_INT, 0) : hr;
    } catch (Throwable _) {
      return 0;
    }
  }

  private static void comRelease(MemorySegment object) {
    if (object != null && !object.equals(MemorySegment.NULL)) {
      try {
        int _ = (int) H.COM_THIS_H.invokeExact(Win32.method(object, VTBL_RELEASE), object);
      } catch (Throwable _) {
        // best-effort cleanup
      }
    }
  }

  // ---- COM ITypedEventHandler upcalls

  private static ComHandler handlerOf(MemorySegment self) {
    if (self.equals(MemorySegment.NULL)) {
      return null;
    }
    long id = self.reinterpret(2 * ADDRESS.byteSize()).get(JAVA_LONG, ADDRESS.byteSize());
    return HANDLERS.get(id);
  }

  /** ITypedEventHandler::QueryInterface (slot 0). */
  @SuppressWarnings("java:S1144") // upcall target found by name
  private static int queryInterface(
      MemorySegment self, MemorySegment riid, MemorySegment ppvObject) {
    try {
      if (ppvObject.equals(MemorySegment.NULL)) {
        return E_POINTER;
      }
      MemorySegment out = ppvObject.reinterpret(ADDRESS.byteSize());
      out.set(ADDRESS, 0, MemorySegment.NULL);
      ComHandler h = handlerOf(self);
      if (h == null || riid.equals(MemorySegment.NULL)) {
        return E_NOINTERFACE;
      }
      byte[] requested = riid.reinterpret(16).toArray(JAVA_BYTE);
      if (Arrays.equals(requested, IID_IUNKNOWN)
          || Arrays.equals(requested, IID_IAGILE_OBJECT)
          || Arrays.equals(requested, h.expectedIid)) {
        h.refCount.incrementAndGet();
        out.set(ADDRESS, 0, self);
        return Win32.S_OK;
      }
      return E_NOINTERFACE;
    } catch (Throwable _) {
      return E_NOINTERFACE;
    }
  }

  /** ITypedEventHandler::AddRef (slot 1). */
  @SuppressWarnings("java:S1144") // upcall target found by name
  private static int addRef(MemorySegment self) {
    try {
      ComHandler h = handlerOf(self);
      return h == null ? 0 : h.refCount.incrementAndGet();
    } catch (Throwable _) {
      return 0;
    }
  }

  /** ITypedEventHandler::Release (slot 2). */
  @SuppressWarnings("java:S1144") // upcall target found by name
  private static int release(MemorySegment self) {
    try {
      ComHandler h = handlerOf(self);
      if (h == null) {
        return 0;
      }
      int refs = h.refCount.decrementAndGet();
      if (refs == 0) {
        HANDLERS.remove(h.id);
        h.arena.close();
      }
      return Math.max(refs, 0);
    } catch (Throwable _) {
      return 0;
    }
  }

  /** ITypedEventHandler::Invoke (slot 3). */
  @SuppressWarnings("java:S1144") // upcall target found by name
  private static int invoke(MemorySegment self, MemorySegment sender, MemorySegment args) {
    try {
      ComHandler h = handlerOf(self);
      if (h != null) {
        h.onInvoke.accept(sender, args);
      }
    } catch (Throwable _) {
      // never let an exception leave a COM upcall
    }
    return Win32.S_OK;
  }

  private static String hex(int hr) {
    return String.format("0x%08X", hr);
  }

  private static void fail(Request req, String message) {
    try {
      req.failed.accept(message);
    } catch (Throwable _) {
      // keep the worker thread running
    }
  }
}
