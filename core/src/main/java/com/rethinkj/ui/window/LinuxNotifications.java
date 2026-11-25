package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Desktop notifications on Linux over D-Bus ({@code org.freedesktop.Notifications.Notify}) through
 * the system's {@code libdbus-1.so.3}. The library is loaded lazily on the first notification, on a
 * daemon thread that owns a private session-bus connection so sending notifications never blocks
 * the UI thread and {@code ActionInvoked} / {@code NotificationClosed} signals arrive on the same
 * connection that sent {@code Notify}.
 *
 * <p>Wayland's {@code ActivationToken} signal is not forwarded to SDL because a Java process cannot
 * safely update its own {@code XDG_ACTIVATION_TOKEN} environment variable.
 */
final class LinuxNotifications {

  private LinuxNotifications() {}

  private static final String BUS_NAME = "org.freedesktop.Notifications";
  private static final String OBJECT_PATH = "/org/freedesktop/Notifications";
  private static final String INTERFACE = "org.freedesktop.Notifications";
  private static final String MATCH_ACTION =
      "type='signal',interface='org.freedesktop.Notifications',member='ActionInvoked'";
  private static final String MATCH_CLOSED =
      "type='signal',interface='org.freedesktop.Notifications',member='NotificationClosed'";

  private static final int DBUS_BUS_SESSION = 0;
  private static final int DBUS_TYPE_INT32 = 'i';
  private static final int DBUS_TYPE_UINT32 = 'u';
  private static final int DBUS_TYPE_STRING = 's';
  private static final int DBUS_TYPE_ARRAY = 'a';
  private static final int DBUS_TYPE_VARIANT = 'v';
  private static final int DBUS_TYPE_DICT_ENTRY = 'e';

  /** {@code DBusError} on 64-bit Linux: {@code name} (0), {@code message} (8), flags, padding. */
  private static final long DBUS_ERROR_SIZE = 32;

  private static final long DBUS_ERROR_NAME = 0;
  private static final long DBUS_ERROR_MESSAGE = 8;

  /** {@code DBusMessageIter} on 64-bit Linux (14 fields: 2 ptrs, 10 ints, 2 ptrs = 72 bytes). */
  private static final long DBUS_MESSAGE_ITER_SIZE = 72;

  private static final int NOTIFY_TIMEOUT_MS = 5_000;
  private static final int POLL_TIMEOUT_MS = 100;

  // All native descriptors are static final constants for GraalVM native-image registration.
  static final FunctionDescriptor THREADS_INIT_DEFAULT = FunctionDescriptor.of(JAVA_INT);
  static final FunctionDescriptor ERROR_INIT = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor ERROR_FREE = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor ERROR_IS_SET = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor BUS_GET_PRIVATE =
      FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS);
  static final FunctionDescriptor BUS_ADD_MATCH =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor CONNECTION_SET_EXIT_ON_DISCONNECT =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);
  static final FunctionDescriptor CONNECTION_SEND_WITH_REPLY_AND_BLOCK =
      FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
  static final FunctionDescriptor CONNECTION_READ_WRITE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor CONNECTION_POP_MESSAGE = FunctionDescriptor.of(ADDRESS, ADDRESS);
  static final FunctionDescriptor CONNECTION_CLOSE = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor CONNECTION_UNREF = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor MESSAGE_NEW_METHOD_CALL =
      FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor MESSAGE_UNREF = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor MESSAGE_IS_SIGNAL =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor MESSAGE_GET_SENDER = FunctionDescriptor.of(ADDRESS, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_INIT =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_INIT_APPEND =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_APPEND_BASIC =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_OPEN_CONTAINER =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_CLOSE_CONTAINER =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_GET_ARG_TYPE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_GET_BASIC =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_NEXT = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor MESSAGE_ITER_RECURSE =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

  /** Loaded lazily on the first notification, never from the headless path. */
  private static final class H {
    static final SymbolLookup DBUS = SymbolLookup.libraryLookup("libdbus-1.so.3", Arena.global());

    static final MethodHandle THREADS_INIT_DEFAULT_H =
        bind("dbus_threads_init_default", THREADS_INIT_DEFAULT);
    static final MethodHandle ERROR_INIT_H = bind("dbus_error_init", ERROR_INIT);
    static final MethodHandle ERROR_FREE_H = bind("dbus_error_free", ERROR_FREE);
    static final MethodHandle ERROR_IS_SET_H = bind("dbus_error_is_set", ERROR_IS_SET);
    static final MethodHandle BUS_GET_PRIVATE_H = bind("dbus_bus_get_private", BUS_GET_PRIVATE);
    static final MethodHandle BUS_ADD_MATCH_H = bind("dbus_bus_add_match", BUS_ADD_MATCH);
    static final MethodHandle SET_EXIT_ON_DISCONNECT_H =
        bind("dbus_connection_set_exit_on_disconnect", CONNECTION_SET_EXIT_ON_DISCONNECT);
    static final MethodHandle SEND_WITH_REPLY_AND_BLOCK_H =
        bind("dbus_connection_send_with_reply_and_block", CONNECTION_SEND_WITH_REPLY_AND_BLOCK);
    static final MethodHandle READ_WRITE_H =
        bind("dbus_connection_read_write", CONNECTION_READ_WRITE);
    static final MethodHandle POP_MESSAGE_H =
        bind("dbus_connection_pop_message", CONNECTION_POP_MESSAGE);
    static final MethodHandle CONNECTION_CLOSE_H = bind("dbus_connection_close", CONNECTION_CLOSE);
    static final MethodHandle CONNECTION_UNREF_H = bind("dbus_connection_unref", CONNECTION_UNREF);
    static final MethodHandle NEW_METHOD_CALL_H =
        bind("dbus_message_new_method_call", MESSAGE_NEW_METHOD_CALL);
    static final MethodHandle MESSAGE_UNREF_H = bind("dbus_message_unref", MESSAGE_UNREF);
    static final MethodHandle MESSAGE_IS_SIGNAL_H =
        bind("dbus_message_is_signal", MESSAGE_IS_SIGNAL);
    static final MethodHandle MESSAGE_GET_SENDER_H =
        bind("dbus_message_get_sender", MESSAGE_GET_SENDER);
    static final MethodHandle ITER_INIT_H = bind("dbus_message_iter_init", MESSAGE_ITER_INIT);
    static final MethodHandle ITER_INIT_APPEND_H =
        bind("dbus_message_iter_init_append", MESSAGE_ITER_INIT_APPEND);
    static final MethodHandle ITER_APPEND_BASIC_H =
        bind("dbus_message_iter_append_basic", MESSAGE_ITER_APPEND_BASIC);
    static final MethodHandle ITER_OPEN_CONTAINER_H =
        bind("dbus_message_iter_open_container", MESSAGE_ITER_OPEN_CONTAINER);
    static final MethodHandle ITER_CLOSE_CONTAINER_H =
        bind("dbus_message_iter_close_container", MESSAGE_ITER_CLOSE_CONTAINER);
    static final MethodHandle ITER_GET_ARG_TYPE_H =
        bind("dbus_message_iter_get_arg_type", MESSAGE_ITER_GET_ARG_TYPE);
    static final MethodHandle ITER_GET_BASIC_H =
        bind("dbus_message_iter_get_basic", MESSAGE_ITER_GET_BASIC);
    static final MethodHandle ITER_NEXT_H = bind("dbus_message_iter_next", MESSAGE_ITER_NEXT);
    static final MethodHandle ITER_RECURSE_H =
        bind("dbus_message_iter_recurse", MESSAGE_ITER_RECURSE);

    static {
      try {
        int _ = (int) THREADS_INIT_DEFAULT_H.invokeExact();
      } catch (Throwable t) {
        throw new ExceptionInInitializerError(t);
      }
    }

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
      MemorySegment symbol = DBUS.find(name)
          .orElseThrow(() -> new UnsatisfiedLinkError("libdbus-1.so.3 has no function " + name));
      return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }
  }

  private record Request(
      String appName,
      String appId,
      String appIcon,
      String title,
      String body,
      Runnable clicked,
      Consumer<String> failed) {}

  private static final Object LOCK = new Object();
  private static final ConcurrentLinkedQueue<Request> OUTGOING = new ConcurrentLinkedQueue<>();
  private static boolean started;
  // The unique bus name that answered Notify: only its signals click or close, as with libnotify.
  private static String service; // on the listener thread
  // Whether the daemon reads the body as markup (GetCapabilities), so it is escaped; null: unasked.
  private static Boolean markup; // on the listener thread

  static void show(
      Window window, String title, String body, Runnable clicked, Consumer<String> failed) {
    String id = Window.appId();
    String name = Window.appName();
    if (name == null || name.isEmpty()) {
      name = window != null && window.title() != null ? window.title() : (id != null ? id : "");
    }
    Path icon = window != null ? SystemNotifications.iconFile(window, id) : null;
    String appIcon = icon != null ? icon.toUri().toString() : "";
    OUTGOING.add(new Request(name, id, appIcon, title, body, clicked, failed));
    synchronized (LOCK) {
      if (!started) {
        started = true;
        Thread.ofPlatform().daemon(true).name("notifications-dbus").start(LinuxNotifications::loop);
      }
      LOCK.notifyAll();
    }
  }

  private static void loop() {
    MemorySegment conn = MemorySegment.NULL;
    Map<Integer, Runnable> handlers = new HashMap<>();
    while (true) {
      Request req;
      while ((req = OUTGOING.poll()) != null) {
        if (conn.equals(MemorySegment.NULL)) {
          String[] error = new String[1];
          conn = connect(error);
          if (conn.equals(MemorySegment.NULL)) {
            fail(req, error[0]);
            continue;
          }
        }
        send(conn, req, handlers);
      }
      if (!conn.equals(MemorySegment.NULL)) {
        drain(conn, handlers);
      }
      if (!OUTGOING.isEmpty()) {
        continue;
      }
      if (conn.equals(MemorySegment.NULL) || handlers.isEmpty()) {
        synchronized (LOCK) {
          while (OUTGOING.isEmpty()) {
            try {
              LOCK.wait();
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
              close(conn);
              return;
            }
          }
        }
      } else {
        boolean alive = readWrite(conn, POLL_TIMEOUT_MS);
        drain(conn, handlers);
        if (!alive) {
          close(conn);
          conn = MemorySegment.NULL;
          handlers.clear();
          markup = null;
        }
      }
    }
  }

  private static MemorySegment connect(String[] errorOut) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment error = arena.allocate(DBUS_ERROR_SIZE, 8);
      H.ERROR_INIT_H.invokeExact(error);
      try {
        MemorySegment conn =
            (MemorySegment) H.BUS_GET_PRIVATE_H.invokeExact(DBUS_BUS_SESSION, error);
        if (conn.equals(MemorySegment.NULL)) {
          errorOut[0] =
              "Could not connect to the D-Bus session bus: " + readError(error, "no session bus");
          return MemorySegment.NULL;
        }
        // Keep the JVM running if the session bus disconnects.
        H.SET_EXIT_ON_DISCONNECT_H.invokeExact(conn, 0);
        addMatch(arena, conn, MATCH_ACTION, error);
        addMatch(arena, conn, MATCH_CLOSED, error);
        return conn;
      } finally {
        H.ERROR_FREE_H.invokeExact(error);
      }
    } catch (LinkageError | RuntimeException e) {
      errorOut[0] = "Desktop notifications need libdbus-1.so.3: " + e.getMessage();
      return MemorySegment.NULL;
    } catch (Throwable t) {
      errorOut[0] = "Could not connect to D-Bus: " + t.getMessage();
      return MemorySegment.NULL;
    }
  }

  private static void addMatch(Arena arena, MemorySegment conn, String rule, MemorySegment error)
      throws Throwable {
    H.BUS_ADD_MATCH_H.invokeExact(conn, arena.allocateFrom(rule), error);
    if ((int) H.ERROR_IS_SET_H.invokeExact(error) != 0) {
      H.ERROR_FREE_H.invokeExact(error);
      H.ERROR_INIT_H.invokeExact(error);
    }
  }

  private static void send(MemorySegment conn, Request req, Map<Integer, Runnable> handlers) {
    try (Arena arena = Arena.ofConfined()) {
      if (markup == null) {
        markup = bodyMarkup(arena, conn);
      }
      MemorySegment msg = (MemorySegment) H.NEW_METHOD_CALL_H.invokeExact(
          arena.allocateFrom(BUS_NAME),
          arena.allocateFrom(OBJECT_PATH),
          arena.allocateFrom(INTERFACE),
          arena.allocateFrom("Notify"));
      if (msg.equals(MemorySegment.NULL)) {
        fail(req, "Could not allocate D-Bus Notify message.");
        return;
      }
      MemorySegment error = arena.allocate(DBUS_ERROR_SIZE, 8);
      H.ERROR_INIT_H.invokeExact(error);
      try {
        if (!buildNotifyArgs(arena, msg, req)) {
          fail(req, "Could not encode D-Bus Notify arguments.");
          return;
        }
        MemorySegment reply = (MemorySegment)
            H.SEND_WITH_REPLY_AND_BLOCK_H.invokeExact(conn, msg, NOTIFY_TIMEOUT_MS, error);
        if (reply.equals(MemorySegment.NULL)) {
          fail(req, "The notification daemon refused Notify: " + readError(error, "D-Bus error"));
          return;
        }
        try {
          service = sender(reply);
          int id = readUint32(arena, reply);
          if (id != 0) {
            handlers.put(id, req.clicked);
          }
        } finally {
          H.MESSAGE_UNREF_H.invokeExact(reply);
        }
      } finally {
        H.ERROR_FREE_H.invokeExact(error);
        H.MESSAGE_UNREF_H.invokeExact(msg);
      }
    } catch (Throwable t) {
      fail(req, "Could not show desktop notification: " + t.getMessage());
    }
  }

  /** Appends Notify's {@code susssasa{sv}i} arguments. */
  private static boolean buildNotifyArgs(Arena arena, MemorySegment msg, Request req)
      throws Throwable {
    MemorySegment iter = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
    H.ITER_INIT_APPEND_H.invokeExact(msg, iter);
    if (!appendString(arena, iter, req.appName)
        || !appendInt(arena, iter, DBUS_TYPE_UINT32, 0) // replaces_id
        || !appendString(arena, iter, req.appIcon) // a file URI, else the desktop entry's
        || !appendString(arena, iter, req.title)
        || !appendString(
            arena,
            iter,
            markup
                ? req.body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                : req.body)) {
      return false;
    }
    // actions: ["default", ""] so clicking the notification emits ActionInvoked(id, "default").
    MemorySegment actions = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
    if ((int) H.ITER_OPEN_CONTAINER_H.invokeExact(
            iter, DBUS_TYPE_ARRAY, arena.allocateFrom("s"), actions)
        == 0) {
      return false;
    }
    boolean ok = appendString(arena, actions, "default") && appendString(arena, actions, "");
    ok &= (int) H.ITER_CLOSE_CONTAINER_H.invokeExact(iter, actions) != 0;
    if (!ok) {
      return false;
    }
    // hints: a{sv} with "desktop-entry" -> appId when Window.identity was set.
    MemorySegment hints = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
    if ((int) H.ITER_OPEN_CONTAINER_H.invokeExact(
            iter, DBUS_TYPE_ARRAY, arena.allocateFrom("{sv}"), hints)
        == 0) {
      return false;
    }
    if (req.appId != null && !req.appId.isEmpty()) {
      MemorySegment entry = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
      if ((int) H.ITER_OPEN_CONTAINER_H.invokeExact(
              hints, DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entry)
          == 0) {
        int _ = (int) H.ITER_CLOSE_CONTAINER_H.invokeExact(iter, hints);
        return false;
      }
      boolean entryOk = appendString(arena, entry, "desktop-entry");
      if (entryOk) {
        MemorySegment variant = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
        if ((int) H.ITER_OPEN_CONTAINER_H.invokeExact(
                entry, DBUS_TYPE_VARIANT, arena.allocateFrom("s"), variant)
            != 0) {
          entryOk = appendString(arena, variant, req.appId);
          entryOk &= (int) H.ITER_CLOSE_CONTAINER_H.invokeExact(entry, variant) != 0;
        } else {
          entryOk = false;
        }
      }
      entryOk &= (int) H.ITER_CLOSE_CONTAINER_H.invokeExact(hints, entry) != 0;
      if (!entryOk) {
        int _ = (int) H.ITER_CLOSE_CONTAINER_H.invokeExact(iter, hints);
        return false;
      }
    }
    if ((int) H.ITER_CLOSE_CONTAINER_H.invokeExact(iter, hints) == 0) {
      return false;
    }
    return appendInt(arena, iter, DBUS_TYPE_INT32, -1); // expire_timeout: server default
  }

  private static boolean appendString(Arena arena, MemorySegment iter, String value)
      throws Throwable {
    MemorySegment ptr = arena.allocateFrom(ADDRESS, arena.allocateFrom(value));
    return (int) H.ITER_APPEND_BASIC_H.invokeExact(iter, DBUS_TYPE_STRING, ptr) != 0;
  }

  private static boolean appendInt(Arena arena, MemorySegment iter, int type, int value)
      throws Throwable {
    MemorySegment box = arena.allocateFrom(JAVA_INT, value);
    return (int) H.ITER_APPEND_BASIC_H.invokeExact(iter, type, box) != 0;
  }

  private static void drain(MemorySegment conn, Map<Integer, Runnable> handlers) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iface = arena.allocateFrom(INTERFACE);
      MemorySegment actionInvoked = arena.allocateFrom("ActionInvoked");
      MemorySegment notificationClosed = arena.allocateFrom("NotificationClosed");
      while (true) {
        MemorySegment msg = (MemorySegment) H.POP_MESSAGE_H.invokeExact(conn);
        if (msg.equals(MemorySegment.NULL)) {
          return;
        }
        try {
          if (!Objects.equals(sender(msg), service)) {
            continue; // another peer of the session bus
          }
          if ((int) H.MESSAGE_IS_SIGNAL_H.invokeExact(msg, iface, actionInvoked) != 0) {
            handleAction(arena, msg, handlers);
          } else if ((int) H.MESSAGE_IS_SIGNAL_H.invokeExact(msg, iface, notificationClosed) != 0) {
            handlers.remove(readUint32(arena, msg));
          }
        } finally {
          H.MESSAGE_UNREF_H.invokeExact(msg);
        }
      }
    } catch (Throwable _) {
      // keep listening for further signals
    }
  }

  private static void handleAction(Arena arena, MemorySegment msg, Map<Integer, Runnable> handlers)
      throws Throwable {
    MemorySegment iter = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
    if ((int) H.ITER_INIT_H.invokeExact(msg, iter) == 0
        || (int) H.ITER_GET_ARG_TYPE_H.invokeExact(iter) != DBUS_TYPE_UINT32) {
      return;
    }
    MemorySegment idOut = arena.allocate(JAVA_INT);
    H.ITER_GET_BASIC_H.invokeExact(iter, idOut);
    int id = idOut.get(JAVA_INT, 0);
    if ((int) H.ITER_NEXT_H.invokeExact(iter) == 0
        || (int) H.ITER_GET_ARG_TYPE_H.invokeExact(iter) != DBUS_TYPE_STRING) {
      return;
    }
    MemorySegment actionOut = arena.allocate(ADDRESS);
    H.ITER_GET_BASIC_H.invokeExact(iter, actionOut);
    MemorySegment cstr = actionOut.get(ADDRESS, 0);
    if (cstr.equals(MemorySegment.NULL)) {
      return;
    }
    String action = cstr.reinterpret(Long.MAX_VALUE).getString(0);
    if ("default".equals(action)) {
      Runnable clicked = handlers.remove(id);
      if (clicked != null) {
        try {
          clicked.run();
        } catch (Throwable _) {
          // keep the listener thread running
        }
      }
    }
  }

  private static String sender(MemorySegment msg) throws Throwable {
    MemorySegment name = (MemorySegment) H.MESSAGE_GET_SENDER_H.invokeExact(msg);
    return name.equals(MemorySegment.NULL)
        ? null
        : name.reinterpret(Long.MAX_VALUE).getString(0);
  }

  /** Whether the daemon lists {@code body-markup} in its capabilities; true if it does not say. */
  private static boolean bodyMarkup(Arena arena, MemorySegment conn) throws Throwable {
    MemorySegment msg = (MemorySegment) H.NEW_METHOD_CALL_H.invokeExact(
        arena.allocateFrom(BUS_NAME),
        arena.allocateFrom(OBJECT_PATH),
        arena.allocateFrom(INTERFACE),
        arena.allocateFrom("GetCapabilities"));
    if (msg.equals(MemorySegment.NULL)) {
      return true;
    }
    MemorySegment error = arena.allocate(DBUS_ERROR_SIZE, 8);
    H.ERROR_INIT_H.invokeExact(error);
    try {
      MemorySegment reply = (MemorySegment)
          H.SEND_WITH_REPLY_AND_BLOCK_H.invokeExact(conn, msg, NOTIFY_TIMEOUT_MS, error);
      if (reply.equals(MemorySegment.NULL)) {
        return true;
      }
      try {
        MemorySegment iter = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
        if ((int) H.ITER_INIT_H.invokeExact(reply, iter) == 0
            || (int) H.ITER_GET_ARG_TYPE_H.invokeExact(iter) != DBUS_TYPE_ARRAY) {
          return true;
        }
        MemorySegment each = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
        H.ITER_RECURSE_H.invokeExact(iter, each);
        MemorySegment out = arena.allocate(ADDRESS);
        while ((int) H.ITER_GET_ARG_TYPE_H.invokeExact(each) == DBUS_TYPE_STRING) {
          H.ITER_GET_BASIC_H.invokeExact(each, out);
          if (out.get(ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0).equals("body-markup")) {
            return true;
          }
          if ((int) H.ITER_NEXT_H.invokeExact(each) == 0) {
            break;
          }
        }
        return false;
      } finally {
        H.MESSAGE_UNREF_H.invokeExact(reply);
      }
    } finally {
      H.ERROR_FREE_H.invokeExact(error);
      H.MESSAGE_UNREF_H.invokeExact(msg);
    }
  }

  private static int readUint32(Arena arena, MemorySegment msg) throws Throwable {
    MemorySegment iter = arena.allocate(DBUS_MESSAGE_ITER_SIZE, 8);
    if ((int) H.ITER_INIT_H.invokeExact(msg, iter) == 0
        || (int) H.ITER_GET_ARG_TYPE_H.invokeExact(iter) != DBUS_TYPE_UINT32) {
      return 0;
    }
    MemorySegment out = arena.allocate(JAVA_INT);
    H.ITER_GET_BASIC_H.invokeExact(iter, out);
    return out.get(JAVA_INT, 0);
  }

  private static boolean readWrite(MemorySegment conn, int timeoutMs) {
    try {
      return (int) H.READ_WRITE_H.invokeExact(conn, timeoutMs) != 0;
    } catch (Throwable _) {
      return false;
    }
  }

  private static void close(MemorySegment conn) {
    if (conn.equals(MemorySegment.NULL)) {
      return;
    }
    try {
      H.CONNECTION_CLOSE_H.invokeExact(conn);
      H.CONNECTION_UNREF_H.invokeExact(conn);
    } catch (Throwable _) {
      // best-effort shutdown
    }
  }

  private static String readError(MemorySegment error, String fallback) {
    MemorySegment message = error.get(ADDRESS, DBUS_ERROR_MESSAGE);
    if (!message.equals(MemorySegment.NULL)) {
      String text = message.reinterpret(Long.MAX_VALUE).getString(0);
      if (!text.isEmpty()) {
        return text;
      }
    }
    MemorySegment name = error.get(ADDRESS, DBUS_ERROR_NAME);
    if (!name.equals(MemorySegment.NULL)) {
      String text = name.reinterpret(Long.MAX_VALUE).getString(0);
      if (!text.isEmpty()) {
        return text;
      }
    }
    return fallback;
  }

  private static void fail(Request req, String message) {
    try {
      req.failed.accept(message);
    } catch (Throwable _) {
      // keep the worker thread running
    }
  }
}
