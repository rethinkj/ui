package com.rethinkj.ui.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The upcalls wgpu-native calls, one stub per callback type for the life of the process, so no
 * stub can be freed while wgpu still holds it.
 *
 * <p>wgpu passes back the {@code userdata1} it was given; here that is the id of a {@link Target}
 * in a table, not an address. A callback for an id that is gone, because its device was closed or
 * its request finished, is ignored.
 *
 * <p>An exception that leaves an upcall ends the JVM, so every upcall catches everything and hands
 * it to its target, whose calling code throws it after the wgpu call returns. Text in a callback is
 * copied before the callback returns: wgpu-native frees it afterwards.
 */
final class Callbacks {

  private Callbacks() {}

  /** Something callbacks report to, registered for as long as wgpu may call back for it. */
  abstract static class Target {
    /** What an upcall threw, for the calling code to throw. */
    volatile Throwable failure;

    /** Throws what an upcall threw, if one did. */
    final void rethrowFailure(String what) {
      Throwable thrown = failure;
      if (thrown != null) {
        failure = null;
        throw new IllegalStateException("A wgpu callback failed while " + what + ".", thrown);
      }
    }
  }

  /** The result of {@code wgpuInstanceRequestAdapter} or {@code wgpuAdapterRequestDevice}. */
  static final class Request extends Target {
    boolean done;
    int status;
    MemorySegment handle = MemorySegment.NULL;
    String message = "";
  }

  /** The result of {@code wgpuBufferMapAsync}. */
  static final class MapRequest extends Target {
    volatile boolean done;
    volatile int status;
    volatile String message = "";
  }

  /** What a device reports: popped error scopes, uncaptured errors, its loss. */
  static final class DeviceEvents extends Target {
    final String label;
    boolean scopeDone;
    int scopeStatus;
    int scopeType;
    String scopeMessage = "";
    /** Errors that no scope caught, type and text; wgpu may report them on any thread. */
    final ConcurrentLinkedQueue<GpuException> uncaptured = new ConcurrentLinkedQueue<>();
    /** Null until the device is lost; then wgpu's text. */
    volatile String lost;

    DeviceEvents(String label) {
      this.label = label;
    }
  }

  private static final AtomicLong NEXT = new AtomicLong(1);
  private static final ConcurrentHashMap<Long, Target> TARGETS = new ConcurrentHashMap<>();

  static long register(Target target) {
    long id = NEXT.getAndIncrement();
    TARGETS.put(id, target);
    return id;
  }

  static void unregister(long id) {
    TARGETS.remove(id);
  }

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  static final MemorySegment REQUEST_ADAPTER =
      stub("requestAdapter", Wgpu.REQUEST_ADAPTER_CALLBACK);
  static final MemorySegment REQUEST_DEVICE = stub("requestDevice", Wgpu.REQUEST_DEVICE_CALLBACK);
  static final MemorySegment UNCAPTURED_ERROR =
      stub("uncapturedError", Wgpu.UNCAPTURED_ERROR_CALLBACK);
  static final MemorySegment DEVICE_LOST = stub("deviceLost", Wgpu.DEVICE_LOST_CALLBACK);
  static final MemorySegment POP_ERROR_SCOPE = stub("popErrorScope", Wgpu.POP_ERROR_SCOPE_CALLBACK);
  static final MemorySegment BUFFER_MAP = stub("bufferMap", Wgpu.BUFFER_MAP_CALLBACK);
  static final MemorySegment LOG = stub("log", Wgpu.LOG_CALLBACK);

  private static final System.Logger LOGGER = System.getLogger("com.rethinkj.ui.gpu");

  private static MemorySegment stub(String method, FunctionDescriptor descriptor) {
    MethodHandle target;
    try {
      target = LOOKUP.findStatic(Callbacks.class, method, descriptor.toMethodType());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return Linker.nativeLinker().upcallStub(target, descriptor, Arena.global());
  }

  // ---- the upcalls; none may throw ---------------------------------------------------------

  private static void requestAdapter(
      int status,
      MemorySegment adapter,
      MemorySegment message,
      MemorySegment userdata1,
      MemorySegment userdata2) {
    try {
      if (target(userdata1) instanceof Request request) {
        request.status = status;
        request.handle = adapter;
        request.message = Wgpu.string(message, 0);
        request.done = true;
      }
    } catch (Throwable t) {
      fail(userdata1, t);
    }
  }

  private static void requestDevice(
      int status,
      MemorySegment device,
      MemorySegment message,
      MemorySegment userdata1,
      MemorySegment userdata2) {
    try {
      if (target(userdata1) instanceof Request request) {
        request.status = status;
        request.handle = device;
        request.message = Wgpu.string(message, 0);
        request.done = true;
      }
    } catch (Throwable t) {
      fail(userdata1, t);
    }
  }

  private static void uncapturedError(
      MemorySegment device,
      int type,
      MemorySegment message,
      MemorySegment userdata1,
      MemorySegment userdata2) {
    try {
      if (target(userdata1) instanceof DeviceEvents events) {
        events.uncaptured.add(new GpuException(
            GpuException.kindOf(type), "using device", events.label, Wgpu.string(message, 0)));
      }
    } catch (Throwable t) {
      fail(userdata1, t);
    }
  }

  private static void deviceLost(
      MemorySegment device,
      int reason,
      MemorySegment message,
      MemorySegment userdata1,
      MemorySegment userdata2) {
    try {
      if (target(userdata1) instanceof DeviceEvents events) {
        String text = Wgpu.string(message, 0);
        events.lost = text.isEmpty() ? "reason " + reason : text;
      }
    } catch (Throwable t) {
      fail(userdata1, t);
    }
  }

  /** Runs with a lock of wgpu-native held: calls nothing in wgpu. */
  private static void popErrorScope(
      int status,
      int type,
      MemorySegment message,
      MemorySegment userdata1,
      MemorySegment userdata2) {
    try {
      if (target(userdata1) instanceof DeviceEvents events) {
        events.scopeStatus = status;
        events.scopeType = type;
        events.scopeMessage = Wgpu.string(message, 0);
        events.scopeDone = true;
      }
    } catch (Throwable t) {
      fail(userdata1, t);
    }
  }

  private static void bufferMap(
      int status, MemorySegment message, MemorySegment userdata1, MemorySegment userdata2) {
    try {
      if (target(userdata1) instanceof MapRequest map) {
        map.status = status;
        map.message = Wgpu.string(message, 0);
        map.done = true;
      }
    } catch (Throwable t) {
      fail(userdata1, t);
    }
  }

  /** wgpu's log, at warning level and above, to the platform logger "com.rethinkj.ui.gpu". */
  private static void log(int level, MemorySegment message, MemorySegment userdata) {
    try {
      System.Logger.Level to = switch (level) {
        case Wgpu.LOG_LEVEL_ERROR -> System.Logger.Level.ERROR;
        case Wgpu.LOG_LEVEL_WARN -> System.Logger.Level.WARNING;
        case Wgpu.LOG_LEVEL_INFO -> System.Logger.Level.INFO;
        case Wgpu.LOG_LEVEL_DEBUG -> System.Logger.Level.DEBUG;
        default -> System.Logger.Level.TRACE;
      };
      if (LOGGER.isLoggable(to)) {
        LOGGER.log(to, "wgpu: " + Wgpu.string(message, 0));
      }
    } catch (Throwable t) {
      // Nobody to hand it to: the log runs outside any call of ours.
    }
  }

  private static Target target(MemorySegment userdata) {
    return TARGETS.get(userdata.address());
  }

  private static void fail(MemorySegment userdata, Throwable t) {
    try {
      Target target = target(userdata);
      if (target != null && target.failure == null) {
        target.failure = t;
      }
    } catch (Throwable ignored) {
      // An upcall must return normally.
    }
  }
}
