package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The layouts of the wgpu-native v29 structs the bindings use, written by hand from {@code
 * webgpu.h} and {@code wgpu.h}, member by member in the order of the header. {@link #struct} adds
 * the padding a C compiler would. {@code scripts/LayoutCheck.java} compares every layout here with
 * what jextract derives from the headers.
 *
 * <p>Each struct has a nested class with its {@code LAYOUT}, the offsets of the members the safe
 * layer writes or reads, and, where the header's {@code WGPU_*_INIT} macro sets members to
 * something other than zero, an {@code allocate} method that applies it. Zero is not always the
 * default: a {@code WGPUStringView} of {@code {NULL, 0}} is the empty string, while the default is
 * {@code {NULL, WGPU_STRLEN}}, "no string", and an entry point of "" names no function at all.
 *
 * <p>{@code allocate} takes an {@link Arena} because an arena hands out zeroed memory, which the
 * initialisation relies on.
 */
final class Structs {

  private Structs() {}

  /** {@code WGPUStringView}: UTF-8 bytes and their length, or {@link Wgpu#STRLEN}. */
  static final StructLayout STRING_VIEW =
      struct("WGPUStringView", ADDRESS.withName("data"), JAVA_LONG.withName("length"));

  static final StructLayout CHAINED_STRUCT =
      struct("WGPUChainedStruct", ADDRESS.withName("next"), JAVA_INT.withName("sType"));

  /** Offset of {@code sType} in a struct that starts with a {@code WGPUChainedStruct}. */
  static final long CHAIN_STYPE = offset(CHAINED_STRUCT, "sType");

  /** {@code WGPUFuture}, which wgpu-native returns by value and always as {@code {0}}. */
  static final StructLayout FUTURE = struct("WGPUFuture", JAVA_LONG.withName("id"));

  /** Writes {@code WGPU_STRING_VIEW_INIT}, no string, at {@code offset}. */
  static void noString(MemorySegment struct, long offset) {
    struct.set(ADDRESS, offset, MemorySegment.NULL);
    struct.set(JAVA_LONG, offset + 8, Wgpu.STRLEN);
  }

  /**
   * Writes a string view of {@code text} at {@code offset}, with the bytes allocated in {@code
   * arena}; null writes no string.
   */
  static void string(MemorySegment struct, long offset, String text, Arena arena) {
    if (text == null) {
      noString(struct, offset);
      return;
    }
    MemorySegment bytes = Wgpu.utf8(arena, text);
    struct.set(ADDRESS, offset, bytes.byteSize() == 0 ? MemorySegment.NULL : bytes);
    struct.set(JAVA_LONG, offset + 8, bytes.byteSize());
  }

  // ---- instance ----------------------------------------------------------------------------

  static final class InstanceDescriptor {
    static final StructLayout LAYOUT = struct("WGPUInstanceDescriptor",
        ADDRESS.withName("nextInChain"), JAVA_LONG.withName("requiredFeatureCount"),
        ADDRESS.withName("requiredFeatures"), ADDRESS.withName("requiredLimits"));
    static final long NEXT_IN_CHAIN = offset(LAYOUT, "nextInChain");
  }

  static final class XlibDisplayHandle {
    static final StructLayout LAYOUT = struct("WGPUXlibDisplayHandle",
        ADDRESS.withName("display"), JAVA_INT.withName("screen"));
  }

  static final class NativeDisplayHandle {
    /** The union's largest members, {@code xlib} and {@code xcb}, have the same shape. */
    static final StructLayout LAYOUT = struct("WGPUNativeDisplayHandle",
        JAVA_INT.withName("type"),
        MemoryLayout.unionLayout(XlibDisplayHandle.LAYOUT.withName("xlib")).withName("data"));
  }

  /** wgpu.h. Zero members are the defaults, except {@code dxcPath}. */
  static final class InstanceExtras {
    static final StructLayout LAYOUT = struct("WGPUInstanceExtras",
        CHAINED_STRUCT.withName("chain"), JAVA_LONG.withName("backends"),
        JAVA_LONG.withName("flags"), JAVA_INT.withName("dx12ShaderCompiler"),
        JAVA_INT.withName("gles3MinorVersion"), JAVA_INT.withName("glFenceBehaviour"),
        STRING_VIEW.withName("dxcPath"), JAVA_INT.withName("dxcMaxShaderModel"),
        JAVA_INT.withName("dx12PresentationSystem"), ADDRESS.withName("budgetForDeviceCreation"),
        ADDRESS.withName("budgetForDeviceLoss"),
        NativeDisplayHandle.LAYOUT.withName("displayHandle"));
    static final long BACKENDS = offset(LAYOUT, "backends");
    static final long FLAGS = offset(LAYOUT, "flags");
    static final long DXC_PATH = offset(LAYOUT, "dxcPath");

    static MemorySegment allocate(Arena arena) {
      MemorySegment s = arena.allocate(LAYOUT);
      s.set(JAVA_INT, CHAIN_STYPE, Wgpu.STYPE_INSTANCE_EXTRAS);
      noString(s, DXC_PATH);
      return s;
    }
  }

  // ---- surface -----------------------------------------------------------------------------

  static final class SurfaceDescriptor {
    static final StructLayout LAYOUT = struct("WGPUSurfaceDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"));
    static final long NEXT_IN_CHAIN = offset(LAYOUT, "nextInChain");
    static final long LABEL = offset(LAYOUT, "label");
  }

  static final class SurfaceSourceWindowsHwnd {
    static final StructLayout LAYOUT = struct("WGPUSurfaceSourceWindowsHWND",
        CHAINED_STRUCT.withName("chain"), ADDRESS.withName("hinstance"), ADDRESS.withName("hwnd"));
    static final long HINSTANCE = offset(LAYOUT, "hinstance");
    static final long HWND = offset(LAYOUT, "hwnd");
  }

  /** The window is an XID: {@code uint64_t} in the header, whatever size C {@code long} has. */
  static final class SurfaceSourceXlibWindow {
    static final StructLayout LAYOUT = struct("WGPUSurfaceSourceXlibWindow",
        CHAINED_STRUCT.withName("chain"), ADDRESS.withName("display"),
        JAVA_LONG.withName("window"));
    static final long DISPLAY = offset(LAYOUT, "display");
    static final long WINDOW = offset(LAYOUT, "window");
  }

  static final class SurfaceSourceWaylandSurface {
    static final StructLayout LAYOUT = struct("WGPUSurfaceSourceWaylandSurface",
        CHAINED_STRUCT.withName("chain"), ADDRESS.withName("display"), ADDRESS.withName("surface"));
    static final long DISPLAY = offset(LAYOUT, "display");
    static final long SURFACE = offset(LAYOUT, "surface");
  }

  static final class SurfaceCapabilities {
    static final StructLayout LAYOUT = struct("WGPUSurfaceCapabilities",
        ADDRESS.withName("nextInChain"), JAVA_LONG.withName("usages"),
        JAVA_LONG.withName("formatCount"), ADDRESS.withName("formats"),
        JAVA_LONG.withName("presentModeCount"), ADDRESS.withName("presentModes"),
        JAVA_LONG.withName("alphaModeCount"), ADDRESS.withName("alphaModes"));
    static final long USAGES = offset(LAYOUT, "usages");
    static final long FORMAT_COUNT = offset(LAYOUT, "formatCount");
    static final long FORMATS = offset(LAYOUT, "formats");
    static final long PRESENT_MODE_COUNT = offset(LAYOUT, "presentModeCount");
    static final long PRESENT_MODES = offset(LAYOUT, "presentModes");
    static final long ALPHA_MODE_COUNT = offset(LAYOUT, "alphaModeCount");
    static final long ALPHA_MODES = offset(LAYOUT, "alphaModes");
  }

  static final class SurfaceConfiguration {
    static final StructLayout LAYOUT = struct("WGPUSurfaceConfiguration",
        ADDRESS.withName("nextInChain"), ADDRESS.withName("device"), JAVA_INT.withName("format"),
        JAVA_LONG.withName("usage"), JAVA_INT.withName("width"), JAVA_INT.withName("height"),
        JAVA_LONG.withName("viewFormatCount"), ADDRESS.withName("viewFormats"),
        JAVA_INT.withName("alphaMode"), JAVA_INT.withName("presentMode"));
    static final long DEVICE = offset(LAYOUT, "device");
    static final long FORMAT = offset(LAYOUT, "format");
    static final long USAGE = offset(LAYOUT, "usage");
    static final long WIDTH = offset(LAYOUT, "width");
    static final long HEIGHT = offset(LAYOUT, "height");
    static final long ALPHA_MODE = offset(LAYOUT, "alphaMode");
    static final long PRESENT_MODE = offset(LAYOUT, "presentMode");

    /** {@code WGPU_SURFACE_CONFIGURATION_INIT}: the usage is a render attachment. */
    static MemorySegment allocate(Arena arena) {
      MemorySegment s = arena.allocate(LAYOUT);
      s.set(JAVA_LONG, USAGE, Wgpu.TEXTURE_USAGE_RENDER_ATTACHMENT);
      return s;
    }
  }

  static final class SurfaceTexture {
    static final StructLayout LAYOUT = struct("WGPUSurfaceTexture",
        ADDRESS.withName("nextInChain"), ADDRESS.withName("texture"), JAVA_INT.withName("status"));
    static final long TEXTURE = offset(LAYOUT, "texture");
    static final long STATUS = offset(LAYOUT, "status");
  }

  // ---- adapter and device ------------------------------------------------------------------

  static final class RequestAdapterOptions {
    static final StructLayout LAYOUT = struct("WGPURequestAdapterOptions",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("featureLevel"),
        JAVA_INT.withName("powerPreference"), JAVA_INT.withName("forceFallbackAdapter"),
        JAVA_INT.withName("backendType"), ADDRESS.withName("compatibleSurface"));
    static final long POWER_PREFERENCE = offset(LAYOUT, "powerPreference");
    static final long BACKEND_TYPE = offset(LAYOUT, "backendType");
    static final long COMPATIBLE_SURFACE = offset(LAYOUT, "compatibleSurface");
  }

  /**
   * {@code WGPURequestAdapterCallbackInfo}, {@code WGPURequestDeviceCallbackInfo}, {@code
   * WGPUPopErrorScopeCallbackInfo}, {@code WGPUDeviceLostCallbackInfo} and {@code
   * WGPUBufferMapCallbackInfo}: the same members, only the type of the function pointer differs.
   */
  static final class CallbackInfo {
    static final StructLayout LAYOUT = struct("WGPU*CallbackInfo",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("mode"), ADDRESS.withName("callback"),
        ADDRESS.withName("userdata1"), ADDRESS.withName("userdata2"));
    static final long MODE = offset(LAYOUT, "mode");
    static final long CALLBACK = offset(LAYOUT, "callback");
    static final long USERDATA1 = offset(LAYOUT, "userdata1");

    /** Fills a callback info at {@code offset} in {@code struct}. */
    static void set(MemorySegment struct, long offset, MemorySegment callback, long target) {
      struct.set(JAVA_INT, offset + MODE, Wgpu.CALLBACK_MODE_ALLOW_PROCESS_EVENTS);
      struct.set(ADDRESS, offset + CALLBACK, callback);
      struct.set(ADDRESS, offset + USERDATA1, MemorySegment.ofAddress(target));
    }

    static MemorySegment allocate(Arena arena, MemorySegment callback, long target) {
      MemorySegment s = arena.allocate(LAYOUT);
      set(s, 0, callback, target);
      return s;
    }
  }

  /** The only callback info without a mode. */
  static final class UncapturedErrorCallbackInfo {
    static final StructLayout LAYOUT = struct("WGPUUncapturedErrorCallbackInfo",
        ADDRESS.withName("nextInChain"), ADDRESS.withName("callback"),
        ADDRESS.withName("userdata1"), ADDRESS.withName("userdata2"));
    static final long CALLBACK = offset(LAYOUT, "callback");
    static final long USERDATA1 = offset(LAYOUT, "userdata1");
  }

  static final class AdapterInfo {
    static final StructLayout LAYOUT = struct("WGPUAdapterInfo",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("vendor"),
        STRING_VIEW.withName("architecture"), STRING_VIEW.withName("device"),
        STRING_VIEW.withName("description"), JAVA_INT.withName("backendType"),
        JAVA_INT.withName("adapterType"), JAVA_INT.withName("vendorID"),
        JAVA_INT.withName("deviceID"), JAVA_INT.withName("subgroupMinSize"),
        JAVA_INT.withName("subgroupMaxSize"));
    static final long VENDOR = offset(LAYOUT, "vendor");
    static final long ARCHITECTURE = offset(LAYOUT, "architecture");
    static final long DEVICE = offset(LAYOUT, "device");
    static final long DESCRIPTION = offset(LAYOUT, "description");
    static final long BACKEND_TYPE = offset(LAYOUT, "backendType");
    static final long ADAPTER_TYPE = offset(LAYOUT, "adapterType");
    static final long VENDOR_ID = offset(LAYOUT, "vendorID");
    static final long DEVICE_ID = offset(LAYOUT, "deviceID");
  }

  /** Written by {@code wgpuAdapterGetLimits} and {@code wgpuDeviceGetLimits}. */
  static final class Limits {
    static final StructLayout LAYOUT = struct("WGPULimits",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("maxTextureDimension1D"),
        JAVA_INT.withName("maxTextureDimension2D"), JAVA_INT.withName("maxTextureDimension3D"),
        JAVA_INT.withName("maxTextureArrayLayers"), JAVA_INT.withName("maxBindGroups"),
        JAVA_INT.withName("maxBindGroupsPlusVertexBuffers"),
        JAVA_INT.withName("maxBindingsPerBindGroup"),
        JAVA_INT.withName("maxDynamicUniformBuffersPerPipelineLayout"),
        JAVA_INT.withName("maxDynamicStorageBuffersPerPipelineLayout"),
        JAVA_INT.withName("maxSampledTexturesPerShaderStage"),
        JAVA_INT.withName("maxSamplersPerShaderStage"),
        JAVA_INT.withName("maxStorageBuffersPerShaderStage"),
        JAVA_INT.withName("maxStorageTexturesPerShaderStage"),
        JAVA_INT.withName("maxUniformBuffersPerShaderStage"),
        JAVA_LONG.withName("maxUniformBufferBindingSize"),
        JAVA_LONG.withName("maxStorageBufferBindingSize"),
        JAVA_INT.withName("minUniformBufferOffsetAlignment"),
        JAVA_INT.withName("minStorageBufferOffsetAlignment"), JAVA_INT.withName("maxVertexBuffers"),
        JAVA_LONG.withName("maxBufferSize"), JAVA_INT.withName("maxVertexAttributes"),
        JAVA_INT.withName("maxVertexBufferArrayStride"),
        JAVA_INT.withName("maxInterStageShaderVariables"), JAVA_INT.withName("maxColorAttachments"),
        JAVA_INT.withName("maxColorAttachmentBytesPerSample"),
        JAVA_INT.withName("maxComputeWorkgroupStorageSize"),
        JAVA_INT.withName("maxComputeInvocationsPerWorkgroup"),
        JAVA_INT.withName("maxComputeWorkgroupSizeX"),
        JAVA_INT.withName("maxComputeWorkgroupSizeY"),
        JAVA_INT.withName("maxComputeWorkgroupSizeZ"),
        JAVA_INT.withName("maxComputeWorkgroupsPerDimension"),
        JAVA_INT.withName("maxImmediateSize"));
    static final long MAX_TEXTURE_DIMENSION_2D = offset(LAYOUT, "maxTextureDimension2D");
    static final long MAX_BIND_GROUPS = offset(LAYOUT, "maxBindGroups");
    static final long MAX_UNIFORM_BUFFER_BINDING_SIZE =
        offset(LAYOUT, "maxUniformBufferBindingSize");
    static final long MIN_UNIFORM_BUFFER_OFFSET_ALIGNMENT =
        offset(LAYOUT, "minUniformBufferOffsetAlignment");
    static final long MAX_VERTEX_BUFFERS = offset(LAYOUT, "maxVertexBuffers");
    static final long MAX_BUFFER_SIZE = offset(LAYOUT, "maxBufferSize");
    static final long MAX_VERTEX_ATTRIBUTES = offset(LAYOUT, "maxVertexAttributes");
    static final long MAX_VERTEX_BUFFER_ARRAY_STRIDE = offset(LAYOUT, "maxVertexBufferArrayStride");
    static final long MAX_COLOR_ATTACHMENTS = offset(LAYOUT, "maxColorAttachments");
  }

  static final class QueueDescriptor {
    static final StructLayout LAYOUT = struct("WGPUQueueDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"));
    static final long LABEL = offset(LAYOUT, "label");
  }

  static final class DeviceDescriptor {
    static final StructLayout LAYOUT = struct("WGPUDeviceDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"),
        JAVA_LONG.withName("requiredFeatureCount"), ADDRESS.withName("requiredFeatures"),
        ADDRESS.withName("requiredLimits"), QueueDescriptor.LAYOUT.withName("defaultQueue"),
        CallbackInfo.LAYOUT.withName("deviceLostCallbackInfo"),
        UncapturedErrorCallbackInfo.LAYOUT.withName("uncapturedErrorCallbackInfo"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long DEFAULT_QUEUE_LABEL = offset(LAYOUT, "defaultQueue", "label");
    static final long DEVICE_LOST_CALLBACK_INFO = offset(LAYOUT, "deviceLostCallbackInfo");
    static final long UNCAPTURED_ERROR_CALLBACK_INFO =
        offset(LAYOUT, "uncapturedErrorCallbackInfo");
  }

  // ---- shaders, buffers, textures ----------------------------------------------------------

  static final class ShaderModuleDescriptor {
    static final StructLayout LAYOUT = struct("WGPUShaderModuleDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"));
    static final long NEXT_IN_CHAIN = offset(LAYOUT, "nextInChain");
    static final long LABEL = offset(LAYOUT, "label");
  }

  static final class ShaderSourceWgsl {
    static final StructLayout LAYOUT = struct("WGPUShaderSourceWGSL",
        CHAINED_STRUCT.withName("chain"), STRING_VIEW.withName("code"));
    static final long CODE = offset(LAYOUT, "code");
  }

  static final class BufferDescriptor {
    static final StructLayout LAYOUT = struct("WGPUBufferDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"), JAVA_LONG.withName("usage"),
        JAVA_LONG.withName("size"), JAVA_INT.withName("mappedAtCreation"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long USAGE = offset(LAYOUT, "usage");
    static final long SIZE = offset(LAYOUT, "size");
  }

  static final class Extent3D {
    static final StructLayout LAYOUT = struct("WGPUExtent3D",
        JAVA_INT.withName("width"), JAVA_INT.withName("height"),
        JAVA_INT.withName("depthOrArrayLayers"));
    static final long WIDTH = offset(LAYOUT, "width");
    static final long HEIGHT = offset(LAYOUT, "height");
    static final long DEPTH_OR_ARRAY_LAYERS = offset(LAYOUT, "depthOrArrayLayers");

    /** Writes an extent of width by height by one at {@code offset}. */
    static void set(MemorySegment struct, long offset, int width, int height) {
      struct.set(JAVA_INT, offset + WIDTH, width);
      struct.set(JAVA_INT, offset + HEIGHT, height);
      struct.set(JAVA_INT, offset + DEPTH_OR_ARRAY_LAYERS, 1);
    }
  }

  static final class Origin3D {
    static final StructLayout LAYOUT = struct("WGPUOrigin3D",
        JAVA_INT.withName("x"), JAVA_INT.withName("y"), JAVA_INT.withName("z"));
  }

  static final class TextureDescriptor {
    static final StructLayout LAYOUT = struct("WGPUTextureDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"), JAVA_LONG.withName("usage"),
        JAVA_INT.withName("dimension"), Extent3D.LAYOUT.withName("size"),
        JAVA_INT.withName("format"), JAVA_INT.withName("mipLevelCount"),
        JAVA_INT.withName("sampleCount"), JAVA_LONG.withName("viewFormatCount"),
        ADDRESS.withName("viewFormats"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long USAGE = offset(LAYOUT, "usage");
    static final long DIMENSION = offset(LAYOUT, "dimension");
    static final long SIZE = offset(LAYOUT, "size");
    static final long FORMAT = offset(LAYOUT, "format");
    static final long MIP_LEVEL_COUNT = offset(LAYOUT, "mipLevelCount");
    static final long SAMPLE_COUNT = offset(LAYOUT, "sampleCount");

    /** {@code WGPU_TEXTURE_DESCRIPTOR_INIT}: one mip level, one sample, size {0, 1, 1}. */
    static MemorySegment allocate(Arena arena) {
      MemorySegment s = arena.allocate(LAYOUT);
      noString(s, LABEL);
      Extent3D.set(s, SIZE, 0, 1);
      s.set(JAVA_INT, MIP_LEVEL_COUNT, 1);
      s.set(JAVA_INT, SAMPLE_COUNT, 1);
      return s;
    }
  }

  static final class TextureViewDescriptor {
    static final StructLayout LAYOUT = struct("WGPUTextureViewDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"), JAVA_INT.withName("format"),
        JAVA_INT.withName("dimension"), JAVA_INT.withName("baseMipLevel"),
        JAVA_INT.withName("mipLevelCount"), JAVA_INT.withName("baseArrayLayer"),
        JAVA_INT.withName("arrayLayerCount"), JAVA_INT.withName("aspect"),
        JAVA_LONG.withName("usage"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long MIP_LEVEL_COUNT = offset(LAYOUT, "mipLevelCount");
    static final long ARRAY_LAYER_COUNT = offset(LAYOUT, "arrayLayerCount");

    /**
     * {@code WGPU_TEXTURE_VIEW_DESCRIPTOR_INIT}: all mip levels and array layers. A count of zero
     * would end the process: wgpu-native panics on it.
     */
    static MemorySegment allocate(Arena arena) {
      MemorySegment s = arena.allocate(LAYOUT);
      noString(s, LABEL);
      s.set(JAVA_INT, MIP_LEVEL_COUNT, Wgpu.MIP_LEVEL_COUNT_UNDEFINED);
      s.set(JAVA_INT, ARRAY_LAYER_COUNT, Wgpu.ARRAY_LAYER_COUNT_UNDEFINED);
      return s;
    }
  }

  // ---- bind groups -------------------------------------------------------------------------

  static final class BufferBindingLayout {
    static final StructLayout LAYOUT = struct("WGPUBufferBindingLayout",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("type"),
        JAVA_INT.withName("hasDynamicOffset"), JAVA_LONG.withName("minBindingSize"));
    static final long TYPE = offset(LAYOUT, "type");
    static final long MIN_BINDING_SIZE = offset(LAYOUT, "minBindingSize");
  }

  static final class SamplerBindingLayout {
    static final StructLayout LAYOUT = struct("WGPUSamplerBindingLayout",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("type"));
  }

  static final class TextureBindingLayout {
    static final StructLayout LAYOUT = struct("WGPUTextureBindingLayout",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("sampleType"),
        JAVA_INT.withName("viewDimension"), JAVA_INT.withName("multisampled"));
  }

  static final class StorageTextureBindingLayout {
    static final StructLayout LAYOUT = struct("WGPUStorageTextureBindingLayout",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("access"), JAVA_INT.withName("format"),
        JAVA_INT.withName("viewDimension"));
  }

  /**
   * {@code WGPU_BIND_GROUP_LAYOUT_ENTRY_INIT} is all zeros, and zero in the four binding layouts
   * means "binding not used". The buffer binding type of {@code WGPU_BUFFER_BINDING_LAYOUT_INIT},
   * {@code Undefined}, is one, not zero.
   */
  static final class BindGroupLayoutEntry {
    static final StructLayout LAYOUT = struct("WGPUBindGroupLayoutEntry",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("binding"),
        JAVA_LONG.withName("visibility"), JAVA_INT.withName("bindingArraySize"),
        BufferBindingLayout.LAYOUT.withName("buffer"),
        SamplerBindingLayout.LAYOUT.withName("sampler"),
        TextureBindingLayout.LAYOUT.withName("texture"),
        StorageTextureBindingLayout.LAYOUT.withName("storageTexture"));
    static final long BINDING = offset(LAYOUT, "binding");
    static final long VISIBILITY = offset(LAYOUT, "visibility");
    static final long BUFFER_TYPE = offset(LAYOUT, "buffer", "type");
    static final long BUFFER_MIN_BINDING_SIZE = offset(LAYOUT, "buffer", "minBindingSize");
  }

  static final class BindGroupLayoutDescriptor {
    static final StructLayout LAYOUT = struct("WGPUBindGroupLayoutDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"),
        JAVA_LONG.withName("entryCount"), ADDRESS.withName("entries"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long ENTRY_COUNT = offset(LAYOUT, "entryCount");
    static final long ENTRIES = offset(LAYOUT, "entries");
  }

  static final class BindGroupEntry {
    static final StructLayout LAYOUT = struct("WGPUBindGroupEntry",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("binding"), ADDRESS.withName("buffer"),
        JAVA_LONG.withName("offset"), JAVA_LONG.withName("size"), ADDRESS.withName("sampler"),
        ADDRESS.withName("textureView"));
    static final long BINDING = offset(LAYOUT, "binding");
    static final long BUFFER = offset(LAYOUT, "buffer");
    static final long OFFSET = offset(LAYOUT, "offset");
    static final long SIZE = offset(LAYOUT, "size");
  }

  static final class BindGroupDescriptor {
    static final StructLayout LAYOUT = struct("WGPUBindGroupDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"), ADDRESS.withName("layout"),
        JAVA_LONG.withName("entryCount"), ADDRESS.withName("entries"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long LAYOUT_HANDLE = offset(LAYOUT, "layout");
    static final long ENTRY_COUNT = offset(LAYOUT, "entryCount");
    static final long ENTRIES = offset(LAYOUT, "entries");
  }

  static final class PipelineLayoutDescriptor {
    static final StructLayout LAYOUT = struct("WGPUPipelineLayoutDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"),
        JAVA_LONG.withName("bindGroupLayoutCount"), ADDRESS.withName("bindGroupLayouts"),
        JAVA_INT.withName("immediateSize"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long BIND_GROUP_LAYOUT_COUNT = offset(LAYOUT, "bindGroupLayoutCount");
    static final long BIND_GROUP_LAYOUTS = offset(LAYOUT, "bindGroupLayouts");
  }

  // ---- render pipeline ---------------------------------------------------------------------

  static final class VertexAttribute {
    static final StructLayout LAYOUT = struct("WGPUVertexAttribute",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("format"), JAVA_LONG.withName("offset"),
        JAVA_INT.withName("shaderLocation"));
    static final long FORMAT = offset(LAYOUT, "format");
    static final long OFFSET = offset(LAYOUT, "offset");
    static final long SHADER_LOCATION = offset(LAYOUT, "shaderLocation");
  }

  static final class VertexBufferLayout {
    static final StructLayout LAYOUT = struct("WGPUVertexBufferLayout",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("stepMode"),
        JAVA_LONG.withName("arrayStride"), JAVA_LONG.withName("attributeCount"),
        ADDRESS.withName("attributes"));
    static final long STEP_MODE = offset(LAYOUT, "stepMode");
    static final long ARRAY_STRIDE = offset(LAYOUT, "arrayStride");
    static final long ATTRIBUTE_COUNT = offset(LAYOUT, "attributeCount");
    static final long ATTRIBUTES = offset(LAYOUT, "attributes");
  }

  static final class VertexState {
    static final StructLayout LAYOUT = struct("WGPUVertexState",
        ADDRESS.withName("nextInChain"), ADDRESS.withName("module"),
        STRING_VIEW.withName("entryPoint"), JAVA_LONG.withName("constantCount"),
        ADDRESS.withName("constants"), JAVA_LONG.withName("bufferCount"),
        ADDRESS.withName("buffers"));
  }

  static final class PrimitiveState {
    static final StructLayout LAYOUT = struct("WGPUPrimitiveState",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("topology"),
        JAVA_INT.withName("stripIndexFormat"), JAVA_INT.withName("frontFace"),
        JAVA_INT.withName("cullMode"), JAVA_INT.withName("unclippedDepth"));
  }

  static final class MultisampleState {
    static final StructLayout LAYOUT = struct("WGPUMultisampleState",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("count"), JAVA_INT.withName("mask"),
        JAVA_INT.withName("alphaToCoverageEnabled"));
  }

  static final class BlendComponent {
    static final StructLayout LAYOUT = struct("WGPUBlendComponent",
        JAVA_INT.withName("operation"), JAVA_INT.withName("srcFactor"),
        JAVA_INT.withName("dstFactor"));
    static final long OPERATION = offset(LAYOUT, "operation");
    static final long SRC_FACTOR = offset(LAYOUT, "srcFactor");
    static final long DST_FACTOR = offset(LAYOUT, "dstFactor");
  }

  static final class BlendState {
    static final StructLayout LAYOUT = struct("WGPUBlendState",
        BlendComponent.LAYOUT.withName("color"), BlendComponent.LAYOUT.withName("alpha"));
    static final long COLOR = offset(LAYOUT, "color");
    static final long ALPHA = offset(LAYOUT, "alpha");
  }

  static final class ColorTargetState {
    static final StructLayout LAYOUT = struct("WGPUColorTargetState",
        ADDRESS.withName("nextInChain"), JAVA_INT.withName("format"), ADDRESS.withName("blend"),
        JAVA_LONG.withName("writeMask"));
    static final long FORMAT = offset(LAYOUT, "format");
    static final long BLEND = offset(LAYOUT, "blend");
    static final long WRITE_MASK = offset(LAYOUT, "writeMask");
  }

  static final class FragmentState {
    static final StructLayout LAYOUT = struct("WGPUFragmentState",
        ADDRESS.withName("nextInChain"), ADDRESS.withName("module"),
        STRING_VIEW.withName("entryPoint"), JAVA_LONG.withName("constantCount"),
        ADDRESS.withName("constants"), JAVA_LONG.withName("targetCount"),
        ADDRESS.withName("targets"));
    static final long MODULE = offset(LAYOUT, "module");
    static final long ENTRY_POINT = offset(LAYOUT, "entryPoint");
    static final long TARGET_COUNT = offset(LAYOUT, "targetCount");
    static final long TARGETS = offset(LAYOUT, "targets");

    /** {@code WGPU_FRAGMENT_STATE_INIT}. */
    static MemorySegment allocate(Arena arena) {
      MemorySegment s = arena.allocate(LAYOUT);
      noString(s, ENTRY_POINT);
      return s;
    }
  }

  static final class RenderPipelineDescriptor {
    static final StructLayout LAYOUT = struct("WGPURenderPipelineDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"), ADDRESS.withName("layout"),
        VertexState.LAYOUT.withName("vertex"), PrimitiveState.LAYOUT.withName("primitive"),
        ADDRESS.withName("depthStencil"), MultisampleState.LAYOUT.withName("multisample"),
        ADDRESS.withName("fragment"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long LAYOUT_HANDLE = offset(LAYOUT, "layout");
    static final long VERTEX_MODULE = offset(LAYOUT, "vertex", "module");
    static final long VERTEX_ENTRY_POINT = offset(LAYOUT, "vertex", "entryPoint");
    static final long VERTEX_BUFFER_COUNT = offset(LAYOUT, "vertex", "bufferCount");
    static final long VERTEX_BUFFERS = offset(LAYOUT, "vertex", "buffers");
    static final long PRIMITIVE_TOPOLOGY = offset(LAYOUT, "primitive", "topology");
    static final long MULTISAMPLE_COUNT = offset(LAYOUT, "multisample", "count");
    static final long MULTISAMPLE_MASK = offset(LAYOUT, "multisample", "mask");
    static final long FRAGMENT = offset(LAYOUT, "fragment");

    /**
     * {@code WGPU_RENDER_PIPELINE_DESCRIPTOR_INIT}: no label, no vertex entry point, one sample
     * with all bits of the mask set.
     */
    static MemorySegment allocate(Arena arena) {
      MemorySegment s = arena.allocate(LAYOUT);
      noString(s, LABEL);
      noString(s, VERTEX_ENTRY_POINT);
      s.set(JAVA_INT, MULTISAMPLE_COUNT, 1);
      s.set(JAVA_INT, MULTISAMPLE_MASK, 0xFFFFFFFF);
      return s;
    }
  }

  // ---- commands ----------------------------------------------------------------------------

  static final class Color {
    static final StructLayout LAYOUT = struct("WGPUColor",
        JAVA_DOUBLE.withName("r"), JAVA_DOUBLE.withName("g"), JAVA_DOUBLE.withName("b"),
        JAVA_DOUBLE.withName("a"));
  }

  static final class RenderPassColorAttachment {
    static final StructLayout LAYOUT = struct("WGPURenderPassColorAttachment",
        ADDRESS.withName("nextInChain"), ADDRESS.withName("view"), JAVA_INT.withName("depthSlice"),
        ADDRESS.withName("resolveTarget"), JAVA_INT.withName("loadOp"),
        JAVA_INT.withName("storeOp"), Color.LAYOUT.withName("clearValue"));
    static final long VIEW = offset(LAYOUT, "view");
    static final long DEPTH_SLICE = offset(LAYOUT, "depthSlice");
    static final long LOAD_OP = offset(LAYOUT, "loadOp");
    static final long STORE_OP = offset(LAYOUT, "storeOp");
    static final long CLEAR_VALUE = offset(LAYOUT, "clearValue");

    /**
     * {@code WGPU_RENDER_PASS_COLOR_ATTACHMENT_INIT}. The depth slice must be undefined for a view
     * that is not 3D; zero is a validation error.
     */
    static MemorySegment allocate(Arena arena) {
      MemorySegment s = arena.allocate(LAYOUT);
      s.set(JAVA_INT, DEPTH_SLICE, Wgpu.DEPTH_SLICE_UNDEFINED);
      return s;
    }
  }

  static final class RenderPassDescriptor {
    static final StructLayout LAYOUT = struct("WGPURenderPassDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"),
        JAVA_LONG.withName("colorAttachmentCount"), ADDRESS.withName("colorAttachments"),
        ADDRESS.withName("depthStencilAttachment"), ADDRESS.withName("occlusionQuerySet"),
        ADDRESS.withName("timestampWrites"));
    static final long LABEL = offset(LAYOUT, "label");
    static final long COLOR_ATTACHMENT_COUNT = offset(LAYOUT, "colorAttachmentCount");
    static final long COLOR_ATTACHMENTS = offset(LAYOUT, "colorAttachments");
  }

  static final class CommandEncoderDescriptor {
    static final StructLayout LAYOUT = struct("WGPUCommandEncoderDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"));
    static final long LABEL = offset(LAYOUT, "label");
  }

  static final class CommandBufferDescriptor {
    static final StructLayout LAYOUT = struct("WGPUCommandBufferDescriptor",
        ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"));
    static final long LABEL = offset(LAYOUT, "label");
  }

  static final class TexelCopyTextureInfo {
    static final StructLayout LAYOUT = struct("WGPUTexelCopyTextureInfo",
        ADDRESS.withName("texture"), JAVA_INT.withName("mipLevel"),
        Origin3D.LAYOUT.withName("origin"), JAVA_INT.withName("aspect"));
    static final long TEXTURE = offset(LAYOUT, "texture");
  }

  static final class TexelCopyBufferLayout {
    static final StructLayout LAYOUT = struct("WGPUTexelCopyBufferLayout",
        JAVA_LONG.withName("offset"), JAVA_INT.withName("bytesPerRow"),
        JAVA_INT.withName("rowsPerImage"));
  }

  static final class TexelCopyBufferInfo {
    static final StructLayout LAYOUT = struct("WGPUTexelCopyBufferInfo",
        TexelCopyBufferLayout.LAYOUT.withName("layout"), ADDRESS.withName("buffer"));
    static final long OFFSET = offset(LAYOUT, "layout", "offset");
    static final long BYTES_PER_ROW = offset(LAYOUT, "layout", "bytesPerRow");
    static final long ROWS_PER_IMAGE = offset(LAYOUT, "layout", "rowsPerImage");
    static final long BUFFER = offset(LAYOUT, "buffer");

    /** {@code WGPU_TEXEL_COPY_BUFFER_INFO_INIT}: both strides undefined. */
    static MemorySegment allocate(Arena arena) {
      MemorySegment s = arena.allocate(LAYOUT);
      s.set(JAVA_INT, BYTES_PER_ROW, Wgpu.COPY_STRIDE_UNDEFINED);
      s.set(JAVA_INT, ROWS_PER_IMAGE, Wgpu.COPY_STRIDE_UNDEFINED);
      return s;
    }
  }

  /** Every layout above with the name of its C struct, for {@code scripts/LayoutCheck.java}. */
  static final List<StructLayout> ALL = List.of(STRING_VIEW, CHAINED_STRUCT, FUTURE,
      InstanceDescriptor.LAYOUT, XlibDisplayHandle.LAYOUT, NativeDisplayHandle.LAYOUT,
      InstanceExtras.LAYOUT, SurfaceDescriptor.LAYOUT, SurfaceSourceWindowsHwnd.LAYOUT,
      SurfaceSourceXlibWindow.LAYOUT, SurfaceSourceWaylandSurface.LAYOUT,
      SurfaceCapabilities.LAYOUT, SurfaceConfiguration.LAYOUT, SurfaceTexture.LAYOUT,
      RequestAdapterOptions.LAYOUT, CallbackInfo.LAYOUT, UncapturedErrorCallbackInfo.LAYOUT,
      AdapterInfo.LAYOUT, Limits.LAYOUT, QueueDescriptor.LAYOUT, DeviceDescriptor.LAYOUT,
      ShaderModuleDescriptor.LAYOUT, ShaderSourceWgsl.LAYOUT, BufferDescriptor.LAYOUT,
      Extent3D.LAYOUT, Origin3D.LAYOUT, TextureDescriptor.LAYOUT, TextureViewDescriptor.LAYOUT,
      BufferBindingLayout.LAYOUT, SamplerBindingLayout.LAYOUT, TextureBindingLayout.LAYOUT,
      StorageTextureBindingLayout.LAYOUT, BindGroupLayoutEntry.LAYOUT,
      BindGroupLayoutDescriptor.LAYOUT, BindGroupEntry.LAYOUT, BindGroupDescriptor.LAYOUT,
      PipelineLayoutDescriptor.LAYOUT, VertexAttribute.LAYOUT, VertexBufferLayout.LAYOUT,
      VertexState.LAYOUT, PrimitiveState.LAYOUT, MultisampleState.LAYOUT, BlendComponent.LAYOUT,
      BlendState.LAYOUT, ColorTargetState.LAYOUT, FragmentState.LAYOUT,
      RenderPipelineDescriptor.LAYOUT, Color.LAYOUT, RenderPassColorAttachment.LAYOUT,
      RenderPassDescriptor.LAYOUT, CommandEncoderDescriptor.LAYOUT, CommandBufferDescriptor.LAYOUT,
      TexelCopyTextureInfo.LAYOUT, TexelCopyBufferLayout.LAYOUT, TexelCopyBufferInfo.LAYOUT);

  /** A struct layout with the padding a C compiler puts between and after the members. */
  static StructLayout struct(String name, MemoryLayout... members) {
    List<MemoryLayout> padded = new ArrayList<>();
    long size = 0;
    long alignment = 1;
    for (MemoryLayout member : members) {
      long gap = -size & (member.byteAlignment() - 1);
      if (gap > 0) {
        padded.add(MemoryLayout.paddingLayout(gap));
      }
      padded.add(member);
      size += gap + member.byteSize();
      alignment = Math.max(alignment, member.byteAlignment());
    }
    long tail = -size & (alignment - 1);
    if (tail > 0) {
      padded.add(MemoryLayout.paddingLayout(tail));
    }
    return MemoryLayout.structLayout(padded.toArray(MemoryLayout[]::new)).withName(name);
  }

  /** The byte offset of a member, or of a member of a nested struct. */
  static long offset(StructLayout layout, String... path) {
    PathElement[] elements = new PathElement[path.length];
    for (int i = 0; i < path.length; i++) {
      elements[i] = PathElement.groupElement(path[i]);
    }
    return layout.byteOffset(elements);
  }
}
