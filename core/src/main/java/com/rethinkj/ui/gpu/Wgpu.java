package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.rethinkj.ui.gpu.Structs.CallbackInfo;
import com.rethinkj.ui.natives.Natives;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raw bindings for the part of wgpu-native v29 ({@code webgpu.h} and {@code wgpu.h}) a 2D renderer
 * needs: the constants of the enums and flags it uses, the descriptor of every function and
 * callback as a {@code static final} constant, and one static method per function that takes and
 * returns raw handles. Struct layouts are in {@link Structs}. Nothing here checks anything; the
 * safe layer, {@link Instance} and the classes it hands out, is what the renderer uses.
 *
 * <p>The library is loaded by the nested holder of the method handles, on the first call of a
 * function. {@link Instance#create} loads it through {@link Natives} before that, so a missing
 * library is an {@link UnsatisfiedLinkError} with an explanation rather than a failed class
 * initialisation. Constants, layouts and descriptors need no library.
 *
 * <p>wgpu-native does not implement 35 functions of {@code webgpu.h}: calling one ends the
 * process. Among them are {@code wgpuInstanceWaitAny}, every {@code SetLabel} and {@code
 * wgpuBufferGetMapState}. None of them is bound here; {@code WgpuTest} keeps it so.
 */
final class Wgpu {

  private Wgpu() {}

  // ---- sentinel values ---------------------------------------------------------------------

  /** {@code WGPU_STRLEN}: the string view's text ends at a NUL, or is no string if data is NULL. */
  static final long STRLEN = -1L;
  /** {@code WGPU_WHOLE_SIZE}. */
  static final long WHOLE_SIZE = -1L;
  static final int MIP_LEVEL_COUNT_UNDEFINED = 0xFFFFFFFF;
  static final int ARRAY_LAYER_COUNT_UNDEFINED = 0xFFFFFFFF;
  static final int COPY_STRIDE_UNDEFINED = 0xFFFFFFFF;
  /** {@code WGPU_DEPTH_SLICE_UNDEFINED}, which a colour attachment needs if its view is not 3D. */
  static final int DEPTH_SLICE_UNDEFINED = 0xFFFFFFFF;

  // ---- enums -------------------------------------------------------------------------------

  static final int STYPE_SHADER_SOURCE_WGSL = 0x00000002;
  static final int STYPE_SURFACE_SOURCE_WINDOWS_HWND = 0x00000005;
  static final int STYPE_SURFACE_SOURCE_XLIB_WINDOW = 0x00000006;
  static final int STYPE_SURFACE_SOURCE_WAYLAND_SURFACE = 0x00000007;
  /** wgpu.h. */
  static final int STYPE_INSTANCE_EXTRAS = 0x00030004;

  static final int CALLBACK_MODE_ALLOW_PROCESS_EVENTS = 2;

  static final int BACKEND_TYPE_D3D12 = 4;
  static final int BACKEND_TYPE_VULKAN = 6;

  /** wgpu.h, {@code WGPUInstanceBackend}: a 64-bit flag set. Zero would enable every backend. */
  static final long INSTANCE_BACKEND_VULKAN = 1L << 0;
  static final long INSTANCE_BACKEND_DX12 = 1L << 3;

  static final int POWER_PREFERENCE_UNDEFINED = 0;
  static final int POWER_PREFERENCE_LOW_POWER = 1;
  static final int POWER_PREFERENCE_HIGH_PERFORMANCE = 2;

  static final int STATUS_SUCCESS = 1;
  static final int REQUEST_ADAPTER_STATUS_SUCCESS = 1;
  static final int REQUEST_ADAPTER_STATUS_UNAVAILABLE = 3;
  static final int REQUEST_DEVICE_STATUS_SUCCESS = 1;
  static final int POP_ERROR_SCOPE_STATUS_SUCCESS = 1;
  static final int MAP_ASYNC_STATUS_SUCCESS = 1;

  static final int ERROR_TYPE_NO_ERROR = 1;
  static final int ERROR_TYPE_VALIDATION = 2;
  static final int ERROR_TYPE_OUT_OF_MEMORY = 3;
  static final int ERROR_TYPE_INTERNAL = 4;

  /**
   * The two error filters wgpu-native accepts. {@code WGPUErrorFilter_Internal} (3) ends the
   * process.
   */
  static final int ERROR_FILTER_VALIDATION = 1;
  static final int ERROR_FILTER_OUT_OF_MEMORY = 2;

  static final int SURFACE_TEXTURE_SUCCESS_OPTIMAL = 1;
  static final int SURFACE_TEXTURE_SUCCESS_SUBOPTIMAL = 2;
  static final int SURFACE_TEXTURE_TIMEOUT = 3;
  static final int SURFACE_TEXTURE_OUTDATED = 4;
  static final int SURFACE_TEXTURE_LOST = 5;
  static final int SURFACE_TEXTURE_ERROR = 6;
  /** wgpu.h extends the enum of webgpu.h with this value. */
  static final int SURFACE_TEXTURE_OCCLUDED = 0x00030001;

  static final int TEXTURE_DIMENSION_2D = 2;
  static final int BUFFER_BINDING_TYPE_UNIFORM = 2;
  static final int BUFFER_BINDING_TYPE_READ_ONLY_STORAGE = 4;
  static final int LOAD_OP_LOAD = 1;
  static final int LOAD_OP_CLEAR = 2;
  static final int STORE_OP_STORE = 1;

  /** wgpu.h. */
  static final int LOG_LEVEL_ERROR = 1;
  static final int LOG_LEVEL_WARN = 2;
  static final int LOG_LEVEL_INFO = 3;
  static final int LOG_LEVEL_DEBUG = 4;

  // ---- flags -------------------------------------------------------------------------------

  static final long TEXTURE_USAGE_RENDER_ATTACHMENT = 0x10;
  static final long MAP_MODE_READ = 0x1;
  static final long COLOR_WRITE_MASK_ALL = 0xF;

  // ---- callbacks ---------------------------------------------------------------------------

  /** {@code void (*)(WGPURequestAdapterStatus, WGPUAdapter, WGPUStringView, void*, void*)}. */
  static final FunctionDescriptor REQUEST_ADAPTER_CALLBACK =
      FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, Structs.STRING_VIEW, ADDRESS, ADDRESS);

  /** {@code void (*)(WGPURequestDeviceStatus, WGPUDevice, WGPUStringView, void*, void*)}. */
  static final FunctionDescriptor REQUEST_DEVICE_CALLBACK =
      FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, Structs.STRING_VIEW, ADDRESS, ADDRESS);

  /** {@code void (*)(WGPUDevice const *, WGPUErrorType, WGPUStringView, void*, void*)}. */
  static final FunctionDescriptor UNCAPTURED_ERROR_CALLBACK =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, Structs.STRING_VIEW, ADDRESS, ADDRESS);

  /** {@code void (*)(WGPUDevice const *, WGPUDeviceLostReason, WGPUStringView, void*, void*)}. */
  static final FunctionDescriptor DEVICE_LOST_CALLBACK =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, Structs.STRING_VIEW, ADDRESS, ADDRESS);

  /** {@code void (*)(WGPUPopErrorScopeStatus, WGPUErrorType, WGPUStringView, void*, void*)}. */
  static final FunctionDescriptor POP_ERROR_SCOPE_CALLBACK =
      FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, Structs.STRING_VIEW, ADDRESS, ADDRESS);

  /** {@code void (*)(WGPUMapAsyncStatus, WGPUStringView, void*, void*)}. */
  static final FunctionDescriptor BUFFER_MAP_CALLBACK =
      FunctionDescriptor.ofVoid(JAVA_INT, Structs.STRING_VIEW, ADDRESS, ADDRESS);

  /** wgpu.h: {@code void (*WGPULogCallback)(WGPULogLevel, WGPUStringView, void *userdata)}. */
  static final FunctionDescriptor LOG_CALLBACK =
      FunctionDescriptor.ofVoid(JAVA_INT, Structs.STRING_VIEW, ADDRESS);

  /** Every callback type by the name of its C typedef, for {@code scripts/LayoutCheck.java}. */
  static final Map<String, FunctionDescriptor> CALLBACKS = ordered(
      "WGPURequestAdapterCallback", REQUEST_ADAPTER_CALLBACK,
      "WGPURequestDeviceCallback", REQUEST_DEVICE_CALLBACK,
      "WGPUUncapturedErrorCallback", UNCAPTURED_ERROR_CALLBACK,
      "WGPUDeviceLostCallback", DEVICE_LOST_CALLBACK,
      "WGPUPopErrorScopeCallback", POP_ERROR_SCOPE_CALLBACK,
      "WGPUBufferMapCallback", BUFFER_MAP_CALLBACK,
      "WGPULogCallback", LOG_CALLBACK);

  // ---- function descriptors ----------------------------------------------------------------

  static final FunctionDescriptor GET_VERSION = FunctionDescriptor.of(JAVA_INT);
  static final FunctionDescriptor SET_LOG_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor SET_LOG_LEVEL = FunctionDescriptor.ofVoid(JAVA_INT);
  /** Every {@code wgpu*Release} and {@code wgpu*AddRef} function, and a few others. */
  static final FunctionDescriptor VOID_HANDLE = FunctionDescriptor.ofVoid(ADDRESS);
  /** {@code WGPUObject f(WGPUParent, Descriptor const *)}: every create function. */
  static final FunctionDescriptor CREATE = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
  /** {@code WGPUStatus f(WGPUObject, Struct *)}: the functions that fill a struct. */
  static final FunctionDescriptor GET_STATUS = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);

  static final FunctionDescriptor CREATE_INSTANCE = FunctionDescriptor.of(ADDRESS, ADDRESS);
  static final FunctionDescriptor INSTANCE_REQUEST_ADAPTER =
      FunctionDescriptor.of(Structs.FUTURE, ADDRESS, ADDRESS, CallbackInfo.LAYOUT);
  static final FunctionDescriptor ADAPTER_INFO_FREE_MEMBERS =
      FunctionDescriptor.ofVoid(Structs.AdapterInfo.LAYOUT);
  static final FunctionDescriptor ADAPTER_REQUEST_DEVICE =
      FunctionDescriptor.of(Structs.FUTURE, ADDRESS, ADDRESS, CallbackInfo.LAYOUT);
  static final FunctionDescriptor DEVICE_GET_QUEUE = FunctionDescriptor.of(ADDRESS, ADDRESS);
  static final FunctionDescriptor DEVICE_PUSH_ERROR_SCOPE =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);
  static final FunctionDescriptor DEVICE_POP_ERROR_SCOPE =
      FunctionDescriptor.of(Structs.FUTURE, ADDRESS, CallbackInfo.LAYOUT);
  /**
   * wgpu.h declares {@code WGPUBool wgpuDevicePoll(WGPUDevice, WGPUBool wait, WGPUSubmissionIndex
   * const *)}, a {@code uint32_t} result, but the Rust function returns a one-byte {@code bool}, so
   * only the lowest byte of the return register is defined. The descriptor says what the library
   * does; {@code LayoutCheck} reports this one difference from the header.
   */
  static final FunctionDescriptor DEVICE_POLL =
      FunctionDescriptor.of(JAVA_BYTE, ADDRESS, JAVA_INT, ADDRESS);
  static final FunctionDescriptor SURFACE_GET_CAPABILITIES =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor SURFACE_CAPABILITIES_FREE_MEMBERS =
      FunctionDescriptor.ofVoid(Structs.SurfaceCapabilities.LAYOUT);
  static final FunctionDescriptor SURFACE_CONFIGURE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor SURFACE_GET_CURRENT_TEXTURE =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor SURFACE_PRESENT = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor QUEUE_SUBMIT =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
  static final FunctionDescriptor QUEUE_WRITE_BUFFER =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
  static final FunctionDescriptor BUFFER_MAP_ASYNC = FunctionDescriptor.of(Structs.FUTURE,
      ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, CallbackInfo.LAYOUT);
  static final FunctionDescriptor BUFFER_GET_CONST_MAPPED_RANGE =
      FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG);
  static final FunctionDescriptor COMMAND_ENCODER_COPY_TEXTURE_TO_BUFFER =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor RENDER_PASS_ENCODER_SET_PIPELINE =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor RENDER_PASS_ENCODER_SET_BIND_GROUP =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS);
  static final FunctionDescriptor RENDER_PASS_ENCODER_SET_VERTEX_BUFFER =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG);
  static final FunctionDescriptor RENDER_PASS_ENCODER_SET_SCISSOR_RECT =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor RENDER_PASS_ENCODER_SET_VIEWPORT = FunctionDescriptor.ofVoid(
      ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
  static final FunctionDescriptor RENDER_PASS_ENCODER_DRAW =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);

  /**
   * Every function the bindings call, by its C name, in the order of the methods below. The
   * holder binds each through this map, so a function cannot be bound without being listed here.
   */
  static final Map<String, FunctionDescriptor> FUNCTIONS = ordered(
      "wgpuGetVersion", GET_VERSION,
      "wgpuSetLogCallback", SET_LOG_CALLBACK,
      "wgpuSetLogLevel", SET_LOG_LEVEL,
      "wgpuCreateInstance", CREATE_INSTANCE,
      "wgpuInstanceCreateSurface", CREATE,
      "wgpuInstanceRequestAdapter", INSTANCE_REQUEST_ADAPTER,
      "wgpuInstanceRelease", VOID_HANDLE,
      "wgpuAdapterGetInfo", GET_STATUS,
      "wgpuAdapterInfoFreeMembers", ADAPTER_INFO_FREE_MEMBERS,
      "wgpuAdapterGetLimits", GET_STATUS,
      "wgpuAdapterRequestDevice", ADAPTER_REQUEST_DEVICE,
      "wgpuAdapterAddRef", VOID_HANDLE,
      "wgpuAdapterRelease", VOID_HANDLE,
      "wgpuDeviceGetQueue", DEVICE_GET_QUEUE,
      "wgpuDeviceGetLimits", GET_STATUS,
      "wgpuDevicePushErrorScope", DEVICE_PUSH_ERROR_SCOPE,
      "wgpuDevicePopErrorScope", DEVICE_POP_ERROR_SCOPE,
      "wgpuDeviceCreateShaderModule", CREATE,
      "wgpuDeviceCreateBuffer", CREATE,
      "wgpuDeviceCreateTexture", CREATE,
      "wgpuDeviceCreateBindGroupLayout", CREATE,
      "wgpuDeviceCreateBindGroup", CREATE,
      "wgpuDeviceCreatePipelineLayout", CREATE,
      "wgpuDeviceCreateRenderPipeline", CREATE,
      "wgpuDeviceCreateCommandEncoder", CREATE,
      "wgpuDevicePoll", DEVICE_POLL,
      "wgpuDeviceRelease", VOID_HANDLE,
      "wgpuQueueSubmit", QUEUE_SUBMIT,
      "wgpuQueueWriteBuffer", QUEUE_WRITE_BUFFER,
      "wgpuQueueRelease", VOID_HANDLE,
      "wgpuSurfaceGetCapabilities", SURFACE_GET_CAPABILITIES,
      "wgpuSurfaceCapabilitiesFreeMembers", SURFACE_CAPABILITIES_FREE_MEMBERS,
      "wgpuSurfaceConfigure", SURFACE_CONFIGURE,
      "wgpuSurfaceGetCurrentTexture", SURFACE_GET_CURRENT_TEXTURE,
      "wgpuSurfacePresent", SURFACE_PRESENT,
      "wgpuSurfaceUnconfigure", VOID_HANDLE,
      "wgpuSurfaceRelease", VOID_HANDLE,
      "wgpuShaderModuleRelease", VOID_HANDLE,
      "wgpuBufferMapAsync", BUFFER_MAP_ASYNC,
      "wgpuBufferGetConstMappedRange", BUFFER_GET_CONST_MAPPED_RANGE,
      "wgpuBufferUnmap", VOID_HANDLE,
      "wgpuBufferRelease", VOID_HANDLE,
      "wgpuTextureCreateView", CREATE,
      "wgpuTextureRelease", VOID_HANDLE,
      "wgpuTextureViewRelease", VOID_HANDLE,
      "wgpuBindGroupLayoutRelease", VOID_HANDLE,
      "wgpuBindGroupRelease", VOID_HANDLE,
      "wgpuPipelineLayoutRelease", VOID_HANDLE,
      "wgpuRenderPipelineRelease", VOID_HANDLE,
      "wgpuCommandEncoderBeginRenderPass", CREATE,
      "wgpuCommandEncoderCopyTextureToBuffer", COMMAND_ENCODER_COPY_TEXTURE_TO_BUFFER,
      "wgpuCommandEncoderFinish", CREATE,
      "wgpuCommandEncoderRelease", VOID_HANDLE,
      "wgpuCommandBufferRelease", VOID_HANDLE,
      "wgpuRenderPassEncoderSetPipeline", RENDER_PASS_ENCODER_SET_PIPELINE,
      "wgpuRenderPassEncoderSetBindGroup", RENDER_PASS_ENCODER_SET_BIND_GROUP,
      "wgpuRenderPassEncoderSetVertexBuffer", RENDER_PASS_ENCODER_SET_VERTEX_BUFFER,
      "wgpuRenderPassEncoderSetScissorRect", RENDER_PASS_ENCODER_SET_SCISSOR_RECT,
      "wgpuRenderPassEncoderSetViewport", RENDER_PASS_ENCODER_SET_VIEWPORT,
      "wgpuRenderPassEncoderDraw", RENDER_PASS_ENCODER_DRAW,
      "wgpuRenderPassEncoderEnd", VOID_HANDLE,
      "wgpuRenderPassEncoderRelease", VOID_HANDLE);

  /** The method handles; initialising this class loads the library. */
  private static final class Fn {
    private static final SymbolLookup LIBRARY = Natives.lookup("wgpu_native");

    static final MethodHandle GET_VERSION = bind("wgpuGetVersion");
    static final MethodHandle SET_LOG_CALLBACK = bind("wgpuSetLogCallback");
    static final MethodHandle SET_LOG_LEVEL = bind("wgpuSetLogLevel");
    static final MethodHandle CREATE_INSTANCE = bind("wgpuCreateInstance");
    static final MethodHandle INSTANCE_CREATE_SURFACE = bind("wgpuInstanceCreateSurface");
    static final MethodHandle INSTANCE_REQUEST_ADAPTER = bind("wgpuInstanceRequestAdapter");
    static final MethodHandle INSTANCE_RELEASE = bind("wgpuInstanceRelease");
    static final MethodHandle ADAPTER_GET_INFO = bind("wgpuAdapterGetInfo");
    static final MethodHandle ADAPTER_INFO_FREE_MEMBERS = bind("wgpuAdapterInfoFreeMembers");
    static final MethodHandle ADAPTER_GET_LIMITS = bind("wgpuAdapterGetLimits");
    static final MethodHandle ADAPTER_REQUEST_DEVICE = bind("wgpuAdapterRequestDevice");
    static final MethodHandle ADAPTER_ADD_REF = bind("wgpuAdapterAddRef");
    static final MethodHandle ADAPTER_RELEASE = bind("wgpuAdapterRelease");
    static final MethodHandle DEVICE_GET_QUEUE = bind("wgpuDeviceGetQueue");
    static final MethodHandle DEVICE_GET_LIMITS = bind("wgpuDeviceGetLimits");
    static final MethodHandle DEVICE_PUSH_ERROR_SCOPE = bind("wgpuDevicePushErrorScope");
    static final MethodHandle DEVICE_POP_ERROR_SCOPE = bind("wgpuDevicePopErrorScope");
    static final MethodHandle DEVICE_CREATE_SHADER_MODULE = bind("wgpuDeviceCreateShaderModule");
    static final MethodHandle DEVICE_CREATE_BUFFER = bind("wgpuDeviceCreateBuffer");
    static final MethodHandle DEVICE_CREATE_TEXTURE = bind("wgpuDeviceCreateTexture");
    static final MethodHandle DEVICE_CREATE_BIND_GROUP_LAYOUT =
        bind("wgpuDeviceCreateBindGroupLayout");
    static final MethodHandle DEVICE_CREATE_BIND_GROUP = bind("wgpuDeviceCreateBindGroup");
    static final MethodHandle DEVICE_CREATE_PIPELINE_LAYOUT =
        bind("wgpuDeviceCreatePipelineLayout");
    static final MethodHandle DEVICE_CREATE_RENDER_PIPELINE =
        bind("wgpuDeviceCreateRenderPipeline");
    static final MethodHandle DEVICE_CREATE_COMMAND_ENCODER =
        bind("wgpuDeviceCreateCommandEncoder");
    static final MethodHandle DEVICE_POLL = bind("wgpuDevicePoll");
    static final MethodHandle DEVICE_RELEASE = bind("wgpuDeviceRelease");
    static final MethodHandle QUEUE_SUBMIT = bind("wgpuQueueSubmit");
    static final MethodHandle QUEUE_WRITE_BUFFER = bind("wgpuQueueWriteBuffer");
    static final MethodHandle QUEUE_RELEASE = bind("wgpuQueueRelease");
    static final MethodHandle SURFACE_GET_CAPABILITIES = bind("wgpuSurfaceGetCapabilities");
    static final MethodHandle SURFACE_CAPABILITIES_FREE_MEMBERS =
        bind("wgpuSurfaceCapabilitiesFreeMembers");
    static final MethodHandle SURFACE_CONFIGURE = bind("wgpuSurfaceConfigure");
    static final MethodHandle SURFACE_GET_CURRENT_TEXTURE = bind("wgpuSurfaceGetCurrentTexture");
    static final MethodHandle SURFACE_PRESENT = bind("wgpuSurfacePresent");
    static final MethodHandle SURFACE_UNCONFIGURE = bind("wgpuSurfaceUnconfigure");
    static final MethodHandle SURFACE_RELEASE = bind("wgpuSurfaceRelease");
    static final MethodHandle SHADER_MODULE_RELEASE = bind("wgpuShaderModuleRelease");
    static final MethodHandle BUFFER_MAP_ASYNC = bind("wgpuBufferMapAsync");
    static final MethodHandle BUFFER_GET_CONST_MAPPED_RANGE =
        bind("wgpuBufferGetConstMappedRange");
    static final MethodHandle BUFFER_UNMAP = bind("wgpuBufferUnmap");
    static final MethodHandle BUFFER_RELEASE = bind("wgpuBufferRelease");
    static final MethodHandle TEXTURE_CREATE_VIEW = bind("wgpuTextureCreateView");
    static final MethodHandle TEXTURE_RELEASE = bind("wgpuTextureRelease");
    static final MethodHandle TEXTURE_VIEW_RELEASE = bind("wgpuTextureViewRelease");
    static final MethodHandle BIND_GROUP_LAYOUT_RELEASE = bind("wgpuBindGroupLayoutRelease");
    static final MethodHandle BIND_GROUP_RELEASE = bind("wgpuBindGroupRelease");
    static final MethodHandle PIPELINE_LAYOUT_RELEASE = bind("wgpuPipelineLayoutRelease");
    static final MethodHandle RENDER_PIPELINE_RELEASE = bind("wgpuRenderPipelineRelease");
    static final MethodHandle COMMAND_ENCODER_BEGIN_RENDER_PASS =
        bind("wgpuCommandEncoderBeginRenderPass");
    static final MethodHandle COMMAND_ENCODER_COPY_TEXTURE_TO_BUFFER =
        bind("wgpuCommandEncoderCopyTextureToBuffer");
    static final MethodHandle COMMAND_ENCODER_FINISH = bind("wgpuCommandEncoderFinish");
    static final MethodHandle COMMAND_ENCODER_RELEASE = bind("wgpuCommandEncoderRelease");
    static final MethodHandle COMMAND_BUFFER_RELEASE = bind("wgpuCommandBufferRelease");
    static final MethodHandle RENDER_PASS_SET_PIPELINE = bind("wgpuRenderPassEncoderSetPipeline");
    static final MethodHandle RENDER_PASS_SET_BIND_GROUP =
        bind("wgpuRenderPassEncoderSetBindGroup");
    static final MethodHandle RENDER_PASS_SET_VERTEX_BUFFER =
        bind("wgpuRenderPassEncoderSetVertexBuffer");
    static final MethodHandle RENDER_PASS_SET_SCISSOR_RECT =
        bind("wgpuRenderPassEncoderSetScissorRect");
    static final MethodHandle RENDER_PASS_SET_VIEWPORT = bind("wgpuRenderPassEncoderSetViewport");
    static final MethodHandle RENDER_PASS_DRAW = bind("wgpuRenderPassEncoderDraw");
    static final MethodHandle RENDER_PASS_END = bind("wgpuRenderPassEncoderEnd");
    static final MethodHandle RENDER_PASS_RELEASE = bind("wgpuRenderPassEncoderRelease");

    private static MethodHandle bind(String name) {
      FunctionDescriptor descriptor = FUNCTIONS.get(name);
      if (descriptor == null) {
        throw new IllegalStateException(name + " is not listed in Wgpu.FUNCTIONS");
      }
      MemorySegment symbol = LIBRARY.find(name).orElseThrow(() -> new UnsatisfiedLinkError(
          "The wgpu_native library has no function " + name + ". The natives module does not"
              + " match this version of rethinkj-ui-core."));
      return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }
  }

  // ---- global ------------------------------------------------------------------------------

  /** The four parts of the release tag, one byte each: 0x1D000101 is v29.0.1.1. */
  static int getVersion() {
    try {
      return (int) Fn.GET_VERSION.invokeExact();
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void setLogCallback(MemorySegment callback, MemorySegment userdata) {
    try {
      Fn.SET_LOG_CALLBACK.invokeExact(callback, userdata);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void setLogLevel(int level) {
    try {
      Fn.SET_LOG_LEVEL.invokeExact(level);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  // ---- instance ----------------------------------------------------------------------------

  static MemorySegment createInstance(MemorySegment descriptor) {
    try {
      return (MemorySegment) Fn.CREATE_INSTANCE.invokeExact(descriptor);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static MemorySegment instanceCreateSurface(MemorySegment instance, MemorySegment descriptor) {
    return create(Fn.INSTANCE_CREATE_SURFACE, instance, descriptor);
  }

  /** The callback runs inside this call in wgpu-native v29. */
  static void instanceRequestAdapter(SegmentAllocator allocator, MemorySegment instance,
      MemorySegment options, MemorySegment callbackInfo) {
    try {
      MemorySegment future = (MemorySegment)
          Fn.INSTANCE_REQUEST_ADAPTER.invokeExact(allocator, instance, options, callbackInfo);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void instanceRelease(MemorySegment instance) {
    voidHandle(Fn.INSTANCE_RELEASE, instance);
  }

  // ---- adapter -----------------------------------------------------------------------------

  static int adapterGetInfo(MemorySegment adapter, MemorySegment info) {
    return getStatus(Fn.ADAPTER_GET_INFO, adapter, info);
  }

  static void adapterInfoFreeMembers(MemorySegment info) {
    try {
      Fn.ADAPTER_INFO_FREE_MEMBERS.invokeExact(info);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static int adapterGetLimits(MemorySegment adapter, MemorySegment limits) {
    return getStatus(Fn.ADAPTER_GET_LIMITS, adapter, limits);
  }

  /** The callback runs inside this call in wgpu-native v29. */
  static void adapterRequestDevice(SegmentAllocator allocator, MemorySegment adapter,
      MemorySegment descriptor, MemorySegment callbackInfo) {
    try {
      MemorySegment future = (MemorySegment)
          Fn.ADAPTER_REQUEST_DEVICE.invokeExact(allocator, adapter, descriptor, callbackInfo);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void adapterAddRef(MemorySegment adapter) {
    voidHandle(Fn.ADAPTER_ADD_REF, adapter);
  }

  static void adapterRelease(MemorySegment adapter) {
    voidHandle(Fn.ADAPTER_RELEASE, adapter);
  }

  // ---- device ------------------------------------------------------------------------------

  static MemorySegment deviceGetQueue(MemorySegment device) {
    try {
      return (MemorySegment) Fn.DEVICE_GET_QUEUE.invokeExact(device);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static int deviceGetLimits(MemorySegment device, MemorySegment limits) {
    return getStatus(Fn.DEVICE_GET_LIMITS, device, limits);
  }

  static void devicePushErrorScope(MemorySegment device, int filter) {
    try {
      Fn.DEVICE_PUSH_ERROR_SCOPE.invokeExact(device, filter);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /**
   * The callback runs inside this call, with a lock of wgpu-native held: it must not call back
   * into wgpu. Popping a scope that was not pushed ends the process.
   */
  static void devicePopErrorScope(SegmentAllocator allocator, MemorySegment device,
      MemorySegment callbackInfo) {
    try {
      MemorySegment future =
          (MemorySegment) Fn.DEVICE_POP_ERROR_SCOPE.invokeExact(allocator, device, callbackInfo);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static MemorySegment deviceCreateShaderModule(MemorySegment device, MemorySegment descriptor) {
    return create(Fn.DEVICE_CREATE_SHADER_MODULE, device, descriptor);
  }

  static MemorySegment deviceCreateBuffer(MemorySegment device, MemorySegment descriptor) {
    return create(Fn.DEVICE_CREATE_BUFFER, device, descriptor);
  }

  static MemorySegment deviceCreateTexture(MemorySegment device, MemorySegment descriptor) {
    return create(Fn.DEVICE_CREATE_TEXTURE, device, descriptor);
  }

  static MemorySegment deviceCreateBindGroupLayout(MemorySegment device,
      MemorySegment descriptor) {
    return create(Fn.DEVICE_CREATE_BIND_GROUP_LAYOUT, device, descriptor);
  }

  static MemorySegment deviceCreateBindGroup(MemorySegment device, MemorySegment descriptor) {
    return create(Fn.DEVICE_CREATE_BIND_GROUP, device, descriptor);
  }

  static MemorySegment deviceCreatePipelineLayout(MemorySegment device,
      MemorySegment descriptor) {
    return create(Fn.DEVICE_CREATE_PIPELINE_LAYOUT, device, descriptor);
  }

  static MemorySegment deviceCreateRenderPipeline(MemorySegment device,
      MemorySegment descriptor) {
    return create(Fn.DEVICE_CREATE_RENDER_PIPELINE, device, descriptor);
  }

  static MemorySegment deviceCreateCommandEncoder(MemorySegment device,
      MemorySegment descriptor) {
    return create(Fn.DEVICE_CREATE_COMMAND_ENCODER, device, descriptor);
  }

  /**
   * Waits for or checks on the submitted work and runs the callbacks that are due, buffer maps
   * among them. Returns true if the queue is empty. An error ends the process.
   */
  static boolean devicePoll(MemorySegment device, boolean wait) {
    try {
      return (byte) Fn.DEVICE_POLL.invokeExact(device, wait ? 1 : 0, MemorySegment.NULL) != 0;
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void deviceRelease(MemorySegment device) {
    voidHandle(Fn.DEVICE_RELEASE, device);
  }

  // ---- queue -------------------------------------------------------------------------------

  /** Ends the process if a command buffer is invalid, already submitted or uses a mapped buffer. */
  static void queueSubmit(MemorySegment queue, long count, MemorySegment commandBuffers) {
    try {
      Fn.QUEUE_SUBMIT.invokeExact(queue, count, commandBuffers);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void queueWriteBuffer(MemorySegment queue, MemorySegment buffer, long offset,
      MemorySegment data, long size) {
    try {
      Fn.QUEUE_WRITE_BUFFER.invokeExact(queue, buffer, offset, data, size);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void queueRelease(MemorySegment queue) {
    voidHandle(Fn.QUEUE_RELEASE, queue);
  }

  // ---- surface -----------------------------------------------------------------------------

  static int surfaceGetCapabilities(MemorySegment surface, MemorySegment adapter,
      MemorySegment capabilities) {
    try {
      return (int) Fn.SURFACE_GET_CAPABILITIES.invokeExact(surface, adapter, capabilities);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void surfaceCapabilitiesFreeMembers(MemorySegment capabilities) {
    try {
      Fn.SURFACE_CAPABILITIES_FREE_MEMBERS.invokeExact(capabilities);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Ends the process on any error, a width or height of zero among them. */
  static void surfaceConfigure(MemorySegment surface, MemorySegment configuration) {
    try {
      Fn.SURFACE_CONFIGURE.invokeExact(surface, configuration);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Ends the process if the surface is not configured or its last texture is still held. */
  static void surfaceGetCurrentTexture(MemorySegment surface, MemorySegment surfaceTexture) {
    try {
      Fn.SURFACE_GET_CURRENT_TEXTURE.invokeExact(surface, surfaceTexture);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static int surfacePresent(MemorySegment surface) {
    try {
      return (int) Fn.SURFACE_PRESENT.invokeExact(surface);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void surfaceUnconfigure(MemorySegment surface) {
    voidHandle(Fn.SURFACE_UNCONFIGURE, surface);
  }

  static void surfaceRelease(MemorySegment surface) {
    voidHandle(Fn.SURFACE_RELEASE, surface);
  }

  // ---- shader module, buffer, texture ------------------------------------------------------

  static void shaderModuleRelease(MemorySegment module) {
    voidHandle(Fn.SHADER_MODULE_RELEASE, module);
  }

  /** The callback runs inside a later {@link #devicePoll}. */
  static void bufferMapAsync(SegmentAllocator allocator, MemorySegment buffer, long mode,
      long offset, long size, MemorySegment callbackInfo) {
    try {
      MemorySegment future = (MemorySegment)
          Fn.BUFFER_MAP_ASYNC.invokeExact(allocator, buffer, mode, offset, size, callbackInfo);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Ends the process if the range is not mapped. */
  static MemorySegment bufferGetConstMappedRange(MemorySegment buffer, long offset, long size) {
    try {
      return (MemorySegment) Fn.BUFFER_GET_CONST_MAPPED_RANGE.invokeExact(buffer, offset, size);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void bufferUnmap(MemorySegment buffer) {
    voidHandle(Fn.BUFFER_UNMAP, buffer);
  }

  static void bufferRelease(MemorySegment buffer) {
    voidHandle(Fn.BUFFER_RELEASE, buffer);
  }

  static MemorySegment textureCreateView(MemorySegment texture, MemorySegment descriptor) {
    return create(Fn.TEXTURE_CREATE_VIEW, texture, descriptor);
  }

  /** For a surface texture that was not presented, this gives it back to the surface. */
  static void textureRelease(MemorySegment texture) {
    voidHandle(Fn.TEXTURE_RELEASE, texture);
  }

  static void textureViewRelease(MemorySegment view) {
    voidHandle(Fn.TEXTURE_VIEW_RELEASE, view);
  }

  // ---- bind groups and pipelines -----------------------------------------------------------

  static void bindGroupLayoutRelease(MemorySegment layout) {
    voidHandle(Fn.BIND_GROUP_LAYOUT_RELEASE, layout);
  }

  static void bindGroupRelease(MemorySegment group) {
    voidHandle(Fn.BIND_GROUP_RELEASE, group);
  }

  static void pipelineLayoutRelease(MemorySegment layout) {
    voidHandle(Fn.PIPELINE_LAYOUT_RELEASE, layout);
  }

  static void renderPipelineRelease(MemorySegment pipeline) {
    voidHandle(Fn.RENDER_PIPELINE_RELEASE, pipeline);
  }

  // ---- commands ----------------------------------------------------------------------------

  static MemorySegment commandEncoderBeginRenderPass(MemorySegment encoder,
      MemorySegment descriptor) {
    return create(Fn.COMMAND_ENCODER_BEGIN_RENDER_PASS, encoder, descriptor);
  }

  static void commandEncoderCopyTextureToBuffer(MemorySegment encoder, MemorySegment source,
      MemorySegment destination, MemorySegment copySize) {
    try {
      Fn.COMMAND_ENCODER_COPY_TEXTURE_TO_BUFFER.invokeExact(encoder, source, destination,
          copySize);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Reports what was wrong with the recorded commands, and returns a handle even then. */
  static MemorySegment commandEncoderFinish(MemorySegment encoder, MemorySegment descriptor) {
    return create(Fn.COMMAND_ENCODER_FINISH, encoder, descriptor);
  }

  static void commandEncoderRelease(MemorySegment encoder) {
    voidHandle(Fn.COMMAND_ENCODER_RELEASE, encoder);
  }

  static void commandBufferRelease(MemorySegment commandBuffer) {
    voidHandle(Fn.COMMAND_BUFFER_RELEASE, commandBuffer);
  }

  static void renderPassSetPipeline(MemorySegment pass, MemorySegment pipeline) {
    try {
      Fn.RENDER_PASS_SET_PIPELINE.invokeExact(pass, pipeline);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void renderPassSetBindGroup(MemorySegment pass, int index, MemorySegment group) {
    try {
      Fn.RENDER_PASS_SET_BIND_GROUP.invokeExact(pass, index, group, 0L, MemorySegment.NULL);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** A size of zero ends the process; {@link #WHOLE_SIZE} means up to the end of the buffer. */
  static void renderPassSetVertexBuffer(MemorySegment pass, int slot, MemorySegment buffer,
      long offset, long size) {
    try {
      Fn.RENDER_PASS_SET_VERTEX_BUFFER.invokeExact(pass, slot, buffer, offset, size);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void renderPassSetScissorRect(MemorySegment pass, int x, int y, int width, int height) {
    try {
      Fn.RENDER_PASS_SET_SCISSOR_RECT.invokeExact(pass, x, y, width, height);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void renderPassSetViewport(MemorySegment pass, float x, float y, float width,
      float height, float minDepth, float maxDepth) {
    try {
      Fn.RENDER_PASS_SET_VIEWPORT.invokeExact(pass, x, y, width, height, minDepth, maxDepth);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void renderPassDraw(MemorySegment pass, int vertexCount, int instanceCount,
      int firstVertex, int firstInstance) {
    try {
      Fn.RENDER_PASS_DRAW.invokeExact(pass, vertexCount, instanceCount, firstVertex,
          firstInstance);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  static void renderPassEnd(MemorySegment pass) {
    voidHandle(Fn.RENDER_PASS_END, pass);
  }

  static void renderPassRelease(MemorySegment pass) {
    voidHandle(Fn.RENDER_PASS_RELEASE, pass);
  }

  // ---- strings -----------------------------------------------------------------------------

  /** UTF-8 bytes of {@code text} without a terminating NUL, as a string view with a length. */
  static MemorySegment utf8(Arena arena, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return bytes.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_BYTE, bytes);
  }

  /**
   * Copies the text of the {@code WGPUStringView} at {@code offset} in {@code struct}, or returns
   * "" for no string. wgpu-native frees the bytes behind a view it hands to a callback when the
   * callback returns, so a callback has to call this before it returns.
   */
  static String string(MemorySegment struct, long offset) {
    MemorySegment data = struct.get(ADDRESS, offset);
    long length = struct.get(JAVA_LONG, offset + 8);
    if (data.equals(MemorySegment.NULL) || length == 0) {
      return "";
    }
    if (length == STRLEN) {
      return data.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }
    return new String(data.reinterpret(length).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static MemorySegment create(MethodHandle handle, MemorySegment parent,
      MemorySegment descriptor) {
    try {
      return (MemorySegment) handle.invokeExact(parent, descriptor);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  private static int getStatus(MethodHandle handle, MemorySegment object, MemorySegment struct) {
    try {
      return (int) handle.invokeExact(object, struct);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  private static void voidHandle(MethodHandle handle, MemorySegment object) {
    try {
      handle.invokeExact(object);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** A downcall throws only what the FFM API throws; this keeps its type where it can. */
  private static RuntimeException rethrow(Throwable t) {
    if (t instanceof RuntimeException e) {
      return e;
    }
    if (t instanceof Error e) {
      throw e;
    }
    return new IllegalStateException(t);
  }

  private static Map<String, FunctionDescriptor> ordered(Object... namesAndDescriptors) {
    Map<String, FunctionDescriptor> map = new LinkedHashMap<>();
    for (int i = 0; i < namesAndDescriptors.length; i += 2) {
      String name = (String) namesAndDescriptors[i];
      if (map.put(name, (FunctionDescriptor) namesAndDescriptors[i + 1]) != null) {
        throw new IllegalStateException(name + " is listed twice");
      }
    }
    return Collections.unmodifiableMap(map);
  }
}
