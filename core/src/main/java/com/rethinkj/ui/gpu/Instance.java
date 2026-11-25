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
import java.nio.file.Path;
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
 * <p>The instance and every object it hands out belong to the thread that created it, until
 * another thread {@link #adopt adopts} them. Closing it closes them all, newest first.
 */
public final class Instance extends GpuObject {

  private static final System.Logger LOGGER = System.getLogger("com.rethinkj.ui.gpu");

  private static boolean logInstalled;

  private final Backend backend;
  /** The thread that the instance and its objects belong to. */
  volatile Thread owner = Thread.currentThread();
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
   * <p>On Direct3D 12, wgpu compiles shaders with the DirectX Shader Compiler (DXC) that the
   * natives module carries, {@code dxcompiler.dll}, which wgpu-native loads by its path. Without it,
   * for example from a folder named by {@value Natives#DIR_PROPERTY} whose manifest does not list
   * it, wgpu uses FXC, Windows' own compiler, which takes about four times as long. So it does,
   * with a warning to the logger {@code com.rethinkj.ui.gpu}, when that file does not load.
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
      if (backend == Backend.D3D12) {
        // Never left undefined: then wgpu-native would take any dxcompiler.dll on the DLL search
        // path, of whatever version.
        // shortcut: with DXC wgpu skips adapters below Shader Model 6.0, which Windows 11's
        // hardware has with current drivers; retry with FXC if one turns up.
        String dxc = dxcPath();
        if (dxc == null) {
          extras.set(JAVA_INT, InstanceExtras.DX12_SHADER_COMPILER, Wgpu.DX12_COMPILER_FXC);
        } else {
          extras.set(JAVA_INT, InstanceExtras.DX12_SHADER_COMPILER, Wgpu.DX12_COMPILER_DXC);
          Structs.string(extras, InstanceExtras.DXC_PATH, dxc, arena);
        }
      }
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
      // After this thread's own linking, which it would slow down.
      Wgpu.linkAhead();
      return new Instance(backend, handle);
    }
  }

  /**
   * The path of the natives' {@code dxcompiler.dll}, or null for FXC: when they have none, or one
   * that does not load. Given a path it cannot load, wgpu-native creates the instance without
   * Direct3D 12 and ends the process when asked for a surface.
   */
  private static String dxcPath() {
    Path path = Natives.path("dxcompiler");
    if (path == null) {
      return null;
    }
    String reason;
    try {
      // Loaded for the life of the process; wgpu-native loading the same file only counts a
      // reference.
      if (Natives.lookup("dxcompiler").find("DxcCreateInstance").isPresent()) {
        return path.toString();
      }
      reason = "it has no function DxcCreateInstance";
    } catch (UnsatisfiedLinkError e) {
      reason = e.getMessage();
    }
    LOGGER.log(
        System.Logger.Level.WARNING,
        "Compiling shaders with FXC, which is slower, because " + path + " is no DirectX Shader"
            + " Compiler: " + reason);
    return null;
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
   * Makes the calling thread the owner of this instance and of every object it handed out, for
   * objects made on one thread and then handed to another, as a window's device is made while
   * the window opens. The thread that owned them must not use them afterwards, and the hand-over
   * must happen before, as through {@link java.util.concurrent.Future#get}.
   */
  public void adopt() {
    owner = Thread.currentThread();
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
