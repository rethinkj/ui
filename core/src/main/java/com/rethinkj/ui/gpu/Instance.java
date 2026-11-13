package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.rethinkj.ui.gpu.Structs.CallbackInfo;
import com.rethinkj.ui.gpu.Structs.InstanceDescriptor;
import com.rethinkj.ui.gpu.Structs.InstanceExtras;
import com.rethinkj.ui.gpu.Structs.RequestAdapterOptions;
import com.rethinkj.ui.gpu.Structs.SurfaceDescriptor;
import com.rethinkj.ui.natives.Natives;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The entry point of the GPU layer and the root of its objects: a wgpu instance with one backend
 * enabled. It creates surfaces and requests adapters; adapters create devices.
 *
 * <pre>{@code
 * try (Instance instance = Instance.create()) {
 *   Adapter adapter = instance.requestAdapter(PowerPreference.HIGH_PERFORMANCE, null);
 *   Device device = adapter.requestDevice("renderer");
 *   ...
 * } // closes the device, the adapter and everything else the instance created
 * }</pre>
 *
 * <p>The instance and every object it hands out belong to the thread that created it. Closing it
 * closes them all, newest first.
 */
public final class Instance extends GpuObject {

  private static boolean logInstalled;

  private final Backend backend;
  /** For tests: told of every object released, in the order of release. */
  Consumer<GpuObject> onRelease;

  private Instance(Backend backend, MemorySegment handle) {
    super(null, null, backend.name().toLowerCase(Locale.ROOT), handle);
    this.backend = backend;
  }

  /** An instance on the backend of the platform: Direct3D 12 on Windows, Vulkan on Linux. */
  public static Instance create() {
    return create(Backend.forPlatform());
  }

  /**
   * An instance that enables only {@code backend}.
   *
   * @throws UnsatisfiedLinkError if wgpu-native cannot be loaded; its message says why
   */
  public static Instance create(Backend backend) {
    // Loads the library with a message that explains what is missing, before Wgpu needs it.
    Natives.lookup("wgpu_native");
    installLog();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment extras = InstanceExtras.allocate(arena);
      extras.set(JAVA_LONG, InstanceExtras.BACKENDS, backend.instanceBit);
      // No backend validation layers and no debug information: wgpu's own validation still runs.
      extras.set(JAVA_LONG, InstanceExtras.FLAGS, 0L);
      MemorySegment descriptor = arena.allocate(InstanceDescriptor.LAYOUT);
      descriptor.set(ADDRESS, InstanceDescriptor.NEXT_IN_CHAIN, extras);
      MemorySegment handle = Wgpu.createInstance(descriptor);
      if (handle.equals(MemorySegment.NULL)) {
        throw new GpuException(
            GpuException.Kind.REQUEST_FAILED,
            "creating instance",
            backend.name(),
            "wgpuCreateInstance returned NULL");
      }
      return new Instance(backend, handle);
    }
  }

  /** The wgpu-native version as four numbers, such as "29.0.1.1". */
  public static String version() {
    Natives.lookup("wgpu_native");
    int v = Wgpu.getVersion();
    return (v >>> 24) + "." + (v >>> 16 & 0xFF) + "." + (v >>> 8 & 0xFF) + "." + (v & 0xFF);
  }

  private static synchronized void installLog() {
    if (!logInstalled) {
      Wgpu.setLogCallback(Callbacks.LOG, MemorySegment.NULL);
      Wgpu.setLogLevel(Wgpu.LOG_LEVEL_WARN);
      logInstalled = true;
    }
  }

  public Backend backend() {
    return backend;
  }

  /**
   * A surface on a native window. The window must outlive the surface.
   *
   * @throws GpuException if wgpu cannot create it
   */
  public Surface createSurface(String label, SurfaceSource source) {
    requireLabel(label);
    MemorySegment instance = handle();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment descriptor = arena.allocate(SurfaceDescriptor.LAYOUT);
      descriptor.set(ADDRESS, SurfaceDescriptor.NEXT_IN_CHAIN, Surface.chain(source, arena));
      Structs.string(descriptor, SurfaceDescriptor.LABEL, label, arena);
      // wgpu-native ends the process if the backend refuses the window.
      MemorySegment surface = Wgpu.instanceCreateSurface(instance, descriptor);
      return new Surface(this, label, surface);
    }
  }

  /**
   * Requests an adapter of this instance's backend.
   *
   * @param compatibleSurface a surface the adapter must be able to present to, or null
   * @throws GpuException of kind {@link GpuException.Kind#ADAPTER_UNAVAILABLE} if there is none
   */
  public Adapter requestAdapter(PowerPreference power, Surface compatibleSurface) {
    MemorySegment instance = handle();
    if (compatibleSurface != null && compatibleSurface.instance != this) {
      throw new IllegalArgumentException(compatibleSurface + " belongs to another instance.");
    }
    MemorySegment surface =
        compatibleSurface == null ? MemorySegment.NULL : compatibleSurface.handle();
    String label = backend.name() + " adapter";
    Callbacks.Request request = new Callbacks.Request();
    long id = Callbacks.register(request);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment options = arena.allocate(RequestAdapterOptions.LAYOUT);
      options.set(JAVA_INT, RequestAdapterOptions.POWER_PREFERENCE, power.value);
      options.set(JAVA_INT, RequestAdapterOptions.BACKEND_TYPE, backend.type);
      options.set(ADDRESS, RequestAdapterOptions.COMPATIBLE_SURFACE, surface);
      Wgpu.instanceRequestAdapter(
          arena, instance, options, CallbackInfo.allocate(arena, Callbacks.REQUEST_ADAPTER, id));
    } finally {
      Callbacks.unregister(id);
    }
    request.rethrowFailure("requesting an adapter");
    if (!request.done) {
      throw notSynchronous("wgpuInstanceRequestAdapter");
    }
    if (request.status != Wgpu.REQUEST_ADAPTER_STATUS_SUCCESS) {
      throw new GpuException(
          request.status == Wgpu.REQUEST_ADAPTER_STATUS_UNAVAILABLE
              ? GpuException.Kind.ADAPTER_UNAVAILABLE
              : GpuException.Kind.REQUEST_FAILED,
          "requesting",
          label,
          request.message);
    }
    return new Adapter(this, label, request.handle);
  }

  /** {@link #requestAdapter(PowerPreference, Surface)} for a fast adapter and no surface. */
  public Adapter requestAdapter() {
    return requestAdapter(PowerPreference.HIGH_PERFORMANCE, null);
  }

  static IllegalStateException notSynchronous(String function) {
    return new IllegalStateException(function + " returned before calling its callback. This"
        + " layer relies on wgpu-native v29 calling it inside the call, and does not use"
        + " wgpuInstanceWaitAny, which wgpu-native does not implement.");
  }

  void released(GpuObject object) {
    if (onRelease != null) {
      onRelease.accept(object);
    }
  }

  @Override
  String kind() {
    return "Instance";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.instanceRelease(handle);
  }
}
