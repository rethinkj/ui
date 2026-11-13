import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Handwritten FFM bindings for the part of wgpu-native v29 ({@code webgpu.h} and {@code wgpu.h}) the spike uses:
 * enough to open a surface on a window, draw with one pipeline and read back errors.
 *
 * <p>Struct layouts are written member by member in the order of the header; {@link #struct} inserts the padding the
 * C ABI would. {@code LayoutCheck} compares every layout here with what jextract derives from the headers.
 */
final class Wgpu {
    private Wgpu() {}

    // ---- enum and flag values ----------------------------------------------------------------

    static final int STYPE_SHADER_SOURCE_WGSL = 0x00000002;
    static final int STYPE_SURFACE_SOURCE_WINDOWS_HWND = 0x00000005;
    static final int STYPE_SURFACE_SOURCE_XLIB_WINDOW = 0x00000006;
    static final int STYPE_SURFACE_SOURCE_WAYLAND_SURFACE = 0x00000007;
    static final int STYPE_INSTANCE_EXTRAS = 0x00030004;

    static final int CALLBACK_MODE_WAIT_ANY_ONLY = 1;
    static final int CALLBACK_MODE_ALLOW_PROCESS_EVENTS = 2;
    static final int CALLBACK_MODE_ALLOW_SPONTANEOUS = 3;

    static final int BACKEND_TYPE_UNDEFINED = 0;
    static final int BACKEND_TYPE_D3D12 = 4;
    static final int BACKEND_TYPE_VULKAN = 6;
    static final int BACKEND_TYPE_OPENGL = 7;
    static final List<String> BACKEND_TYPE_NAMES =
            List.of("Undefined", "Null", "WebGPU", "D3D11", "D3D12", "Metal", "Vulkan", "OpenGL", "OpenGLES");
    static final List<String> ADAPTER_TYPE_NAMES = List.of("?", "DiscreteGPU", "IntegratedGPU", "CPU", "Unknown");

    /** WGPUInstanceBackend, a 64-bit flag set in WGPUInstanceExtras. Zero means all. */
    static final long INSTANCE_BACKEND_ALL = 0;
    static final long INSTANCE_BACKEND_VULKAN = 1 << 0;
    static final long INSTANCE_BACKEND_GL = 1 << 1;
    static final long INSTANCE_BACKEND_DX12 = 1 << 3;

    static final long INSTANCE_FLAG_DEBUG = 1 << 0;
    static final long INSTANCE_FLAG_VALIDATION = 1 << 1;

    static final int REQUEST_STATUS_SUCCESS = 1;
    static final int STATUS_SUCCESS = 1;

    static final int ERROR_TYPE_NO_ERROR = 1;
    static final List<String> ERROR_TYPE_NAMES =
            List.of("?", "NoError", "Validation", "OutOfMemory", "Internal", "Unknown");
    static final int ERROR_FILTER_VALIDATION = 1;
    static final List<String> DEVICE_LOST_REASON_NAMES =
            List.of("?", "Unknown", "Destroyed", "CallbackCancelled", "FailedCreation");

    static final int LOG_LEVEL_ERROR = 1;
    static final int LOG_LEVEL_WARN = 2;
    static final int LOG_LEVEL_INFO = 3;
    static final List<String> LOG_LEVEL_NAMES = List.of("off", "error", "warn", "info", "debug", "trace");

    static final int TEXTURE_FORMAT_RGBA8_UNORM = 0x16;
    static final int TEXTURE_FORMAT_RGBA8_UNORM_SRGB = 0x17;
    static final int TEXTURE_FORMAT_BGRA8_UNORM = 0x1B;
    static final int TEXTURE_FORMAT_BGRA8_UNORM_SRGB = 0x1C;
    static final int TEXTURE_FORMAT_RGBA16_FLOAT = 0x28;

    static final long TEXTURE_USAGE_RENDER_ATTACHMENT = 0x10;
    static final long BUFFER_USAGE_COPY_DST = 0x08;
    static final long BUFFER_USAGE_VERTEX = 0x20;
    static final long COLOR_WRITE_MASK_ALL = 0x0F;

    static final int PRESENT_MODE_FIFO = 1;
    static final List<String> PRESENT_MODE_NAMES = List.of("Undefined", "Fifo", "FifoRelaxed", "Immediate", "Mailbox");
    static final int COMPOSITE_ALPHA_MODE_AUTO = 0;
    static final List<String> ALPHA_MODE_NAMES =
            List.of("Auto", "Opaque", "Premultiplied", "Unpremultiplied", "Inherit");

    static final int SURFACE_TEXTURE_SUCCESS_OPTIMAL = 1;
    static final int SURFACE_TEXTURE_SUCCESS_SUBOPTIMAL = 2;
    static final int SURFACE_TEXTURE_TIMEOUT = 3;
    static final int SURFACE_TEXTURE_OUTDATED = 4;
    static final int SURFACE_TEXTURE_LOST = 5;
    static final int SURFACE_TEXTURE_ERROR = 6;
    /** wgpu.h extends the enum of webgpu.h with this value. */
    static final int SURFACE_TEXTURE_OCCLUDED = 0x00030001;

    static final int LOAD_OP_CLEAR = 2;
    static final int STORE_OP_STORE = 1;
    static final int PRIMITIVE_TOPOLOGY_TRIANGLE_LIST = 4;
    static final int VERTEX_FORMAT_FLOAT32X2 = 0x1D;
    static final int VERTEX_FORMAT_FLOAT32X3 = 0x1E;
    static final int VERTEX_STEP_MODE_VERTEX = 1;

    /** WGPU_DEPTH_SLICE_UNDEFINED; the value a colour attachment needs when its view is not 3D. Zero is an error. */
    static final int DEPTH_SLICE_UNDEFINED = 0xFFFFFFFF;

    // ---- struct layouts ----------------------------------------------------------------------

    static final StructLayout STRING_VIEW =
            struct("WGPUStringView", ADDRESS.withName("data"), JAVA_LONG.withName("length"));

    static final StructLayout CHAINED_STRUCT =
            struct("WGPUChainedStruct", ADDRESS.withName("next"), JAVA_INT.withName("sType"));

    static final StructLayout FUTURE = struct("WGPUFuture", JAVA_LONG.withName("id"));

    static final StructLayout FUTURE_WAIT_INFO =
            struct("WGPUFutureWaitInfo", FUTURE.withName("future"), JAVA_INT.withName("completed"));

    static final StructLayout INSTANCE_DESCRIPTOR = struct("WGPUInstanceDescriptor",
            ADDRESS.withName("nextInChain"), JAVA_LONG.withName("requiredFeatureCount"),
            ADDRESS.withName("requiredFeatures"), ADDRESS.withName("requiredLimits"));

    static final StructLayout XLIB_DISPLAY_HANDLE =
            struct("WGPUXlibDisplayHandle", ADDRESS.withName("display"), JAVA_INT.withName("screen"));

    static final StructLayout NATIVE_DISPLAY_HANDLE = struct("WGPUNativeDisplayHandle",
            JAVA_INT.withName("type"),
            // The union's largest members, xlib and xcb, have the same shape.
            MemoryLayout.unionLayout(XLIB_DISPLAY_HANDLE.withName("xlib")).withName("data"));

    static final StructLayout INSTANCE_EXTRAS = struct("WGPUInstanceExtras",
            CHAINED_STRUCT.withName("chain"), JAVA_LONG.withName("backends"), JAVA_LONG.withName("flags"),
            JAVA_INT.withName("dx12ShaderCompiler"), JAVA_INT.withName("gles3MinorVersion"),
            JAVA_INT.withName("glFenceBehaviour"), STRING_VIEW.withName("dxcPath"),
            JAVA_INT.withName("dxcMaxShaderModel"), JAVA_INT.withName("dx12PresentationSystem"),
            ADDRESS.withName("budgetForDeviceCreation"), ADDRESS.withName("budgetForDeviceLoss"),
            NATIVE_DISPLAY_HANDLE.withName("displayHandle"));

    static final StructLayout INSTANCE_ENUMERATE_ADAPTER_OPTIONS = struct("WGPUInstanceEnumerateAdapterOptions",
            ADDRESS.withName("nextInChain"), JAVA_LONG.withName("backends"));

    static final StructLayout SURFACE_DESCRIPTOR =
            struct("WGPUSurfaceDescriptor", ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"));

    static final StructLayout SURFACE_SOURCE_WINDOWS_HWND = struct("WGPUSurfaceSourceWindowsHWND",
            CHAINED_STRUCT.withName("chain"), ADDRESS.withName("hinstance"), ADDRESS.withName("hwnd"));

    /** The window is an XID: uint64_t in the header, whatever size C long has. */
    static final StructLayout SURFACE_SOURCE_XLIB_WINDOW = struct("WGPUSurfaceSourceXlibWindow",
            CHAINED_STRUCT.withName("chain"), ADDRESS.withName("display"), JAVA_LONG.withName("window"));

    static final StructLayout SURFACE_SOURCE_WAYLAND_SURFACE = struct("WGPUSurfaceSourceWaylandSurface",
            CHAINED_STRUCT.withName("chain"), ADDRESS.withName("display"), ADDRESS.withName("surface"));

    static final StructLayout REQUEST_ADAPTER_OPTIONS = struct("WGPURequestAdapterOptions",
            ADDRESS.withName("nextInChain"), JAVA_INT.withName("featureLevel"), JAVA_INT.withName("powerPreference"),
            JAVA_INT.withName("forceFallbackAdapter"), JAVA_INT.withName("backendType"),
            ADDRESS.withName("compatibleSurface"));

    /**
     * WGPURequestAdapterCallbackInfo, WGPURequestDeviceCallbackInfo, WGPUPopErrorScopeCallbackInfo and
     * WGPUDeviceLostCallbackInfo: the same members, only the type of the function pointer differs.
     */
    static final StructLayout CALLBACK_INFO = struct("WGPU*CallbackInfo",
            ADDRESS.withName("nextInChain"), JAVA_INT.withName("mode"), ADDRESS.withName("callback"),
            ADDRESS.withName("userdata1"), ADDRESS.withName("userdata2"));

    /** The only callback info without a mode. */
    static final StructLayout UNCAPTURED_ERROR_CALLBACK_INFO = struct("WGPUUncapturedErrorCallbackInfo",
            ADDRESS.withName("nextInChain"), ADDRESS.withName("callback"),
            ADDRESS.withName("userdata1"), ADDRESS.withName("userdata2"));

    static final StructLayout ADAPTER_INFO = struct("WGPUAdapterInfo",
            ADDRESS.withName("nextInChain"), STRING_VIEW.withName("vendor"), STRING_VIEW.withName("architecture"),
            STRING_VIEW.withName("device"), STRING_VIEW.withName("description"), JAVA_INT.withName("backendType"),
            JAVA_INT.withName("adapterType"), JAVA_INT.withName("vendorID"), JAVA_INT.withName("deviceID"),
            JAVA_INT.withName("subgroupMinSize"), JAVA_INT.withName("subgroupMaxSize"));

    static final StructLayout QUEUE_DESCRIPTOR =
            struct("WGPUQueueDescriptor", ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"));

    static final StructLayout DEVICE_DESCRIPTOR = struct("WGPUDeviceDescriptor",
            ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"),
            JAVA_LONG.withName("requiredFeatureCount"), ADDRESS.withName("requiredFeatures"),
            ADDRESS.withName("requiredLimits"), QUEUE_DESCRIPTOR.withName("defaultQueue"),
            CALLBACK_INFO.withName("deviceLostCallbackInfo"),
            UNCAPTURED_ERROR_CALLBACK_INFO.withName("uncapturedErrorCallbackInfo"));

    static final StructLayout SURFACE_CAPABILITIES = struct("WGPUSurfaceCapabilities",
            ADDRESS.withName("nextInChain"), JAVA_LONG.withName("usages"),
            JAVA_LONG.withName("formatCount"), ADDRESS.withName("formats"),
            JAVA_LONG.withName("presentModeCount"), ADDRESS.withName("presentModes"),
            JAVA_LONG.withName("alphaModeCount"), ADDRESS.withName("alphaModes"));

    static final StructLayout SURFACE_CONFIGURATION = struct("WGPUSurfaceConfiguration",
            ADDRESS.withName("nextInChain"), ADDRESS.withName("device"), JAVA_INT.withName("format"),
            JAVA_LONG.withName("usage"), JAVA_INT.withName("width"), JAVA_INT.withName("height"),
            JAVA_LONG.withName("viewFormatCount"), ADDRESS.withName("viewFormats"),
            JAVA_INT.withName("alphaMode"), JAVA_INT.withName("presentMode"));

    static final StructLayout SURFACE_TEXTURE = struct("WGPUSurfaceTexture",
            ADDRESS.withName("nextInChain"), ADDRESS.withName("texture"), JAVA_INT.withName("status"));

    static final StructLayout SHADER_MODULE_DESCRIPTOR =
            struct("WGPUShaderModuleDescriptor", ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"));

    static final StructLayout SHADER_SOURCE_WGSL =
            struct("WGPUShaderSourceWGSL", CHAINED_STRUCT.withName("chain"), STRING_VIEW.withName("code"));

    static final StructLayout VERTEX_ATTRIBUTE = struct("WGPUVertexAttribute",
            ADDRESS.withName("nextInChain"), JAVA_INT.withName("format"), JAVA_LONG.withName("offset"),
            JAVA_INT.withName("shaderLocation"));

    static final StructLayout VERTEX_BUFFER_LAYOUT = struct("WGPUVertexBufferLayout",
            ADDRESS.withName("nextInChain"), JAVA_INT.withName("stepMode"), JAVA_LONG.withName("arrayStride"),
            JAVA_LONG.withName("attributeCount"), ADDRESS.withName("attributes"));

    static final StructLayout VERTEX_STATE = struct("WGPUVertexState",
            ADDRESS.withName("nextInChain"), ADDRESS.withName("module"), STRING_VIEW.withName("entryPoint"),
            JAVA_LONG.withName("constantCount"), ADDRESS.withName("constants"),
            JAVA_LONG.withName("bufferCount"), ADDRESS.withName("buffers"));

    static final StructLayout PRIMITIVE_STATE = struct("WGPUPrimitiveState",
            ADDRESS.withName("nextInChain"), JAVA_INT.withName("topology"), JAVA_INT.withName("stripIndexFormat"),
            JAVA_INT.withName("frontFace"), JAVA_INT.withName("cullMode"), JAVA_INT.withName("unclippedDepth"));

    static final StructLayout MULTISAMPLE_STATE = struct("WGPUMultisampleState",
            ADDRESS.withName("nextInChain"), JAVA_INT.withName("count"), JAVA_INT.withName("mask"),
            JAVA_INT.withName("alphaToCoverageEnabled"));

    static final StructLayout COLOR_TARGET_STATE = struct("WGPUColorTargetState",
            ADDRESS.withName("nextInChain"), JAVA_INT.withName("format"), ADDRESS.withName("blend"),
            JAVA_LONG.withName("writeMask"));

    static final StructLayout FRAGMENT_STATE = struct("WGPUFragmentState",
            ADDRESS.withName("nextInChain"), ADDRESS.withName("module"), STRING_VIEW.withName("entryPoint"),
            JAVA_LONG.withName("constantCount"), ADDRESS.withName("constants"),
            JAVA_LONG.withName("targetCount"), ADDRESS.withName("targets"));

    static final StructLayout RENDER_PIPELINE_DESCRIPTOR = struct("WGPURenderPipelineDescriptor",
            ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"), ADDRESS.withName("layout"),
            VERTEX_STATE.withName("vertex"), PRIMITIVE_STATE.withName("primitive"),
            ADDRESS.withName("depthStencil"), MULTISAMPLE_STATE.withName("multisample"),
            ADDRESS.withName("fragment"));

    static final StructLayout COLOR = struct("WGPUColor",
            JAVA_DOUBLE.withName("r"), JAVA_DOUBLE.withName("g"), JAVA_DOUBLE.withName("b"), JAVA_DOUBLE.withName("a"));

    static final StructLayout RENDER_PASS_COLOR_ATTACHMENT = struct("WGPURenderPassColorAttachment",
            ADDRESS.withName("nextInChain"), ADDRESS.withName("view"), JAVA_INT.withName("depthSlice"),
            ADDRESS.withName("resolveTarget"), JAVA_INT.withName("loadOp"), JAVA_INT.withName("storeOp"),
            COLOR.withName("clearValue"));

    static final StructLayout RENDER_PASS_DESCRIPTOR = struct("WGPURenderPassDescriptor",
            ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"),
            JAVA_LONG.withName("colorAttachmentCount"), ADDRESS.withName("colorAttachments"),
            ADDRESS.withName("depthStencilAttachment"), ADDRESS.withName("occlusionQuerySet"),
            ADDRESS.withName("timestampWrites"));

    static final StructLayout BUFFER_DESCRIPTOR = struct("WGPUBufferDescriptor",
            ADDRESS.withName("nextInChain"), STRING_VIEW.withName("label"), JAVA_LONG.withName("usage"),
            JAVA_LONG.withName("size"), JAVA_INT.withName("mappedAtCreation"));

    /** Every layout above with the name of its C struct, for LayoutCheck. */
    static final List<StructLayout> LAYOUTS = List.of(STRING_VIEW, CHAINED_STRUCT, FUTURE, FUTURE_WAIT_INFO,
            INSTANCE_DESCRIPTOR, XLIB_DISPLAY_HANDLE, NATIVE_DISPLAY_HANDLE, INSTANCE_EXTRAS,
            INSTANCE_ENUMERATE_ADAPTER_OPTIONS, SURFACE_DESCRIPTOR, SURFACE_SOURCE_WINDOWS_HWND,
            SURFACE_SOURCE_XLIB_WINDOW, SURFACE_SOURCE_WAYLAND_SURFACE,
            REQUEST_ADAPTER_OPTIONS, CALLBACK_INFO, UNCAPTURED_ERROR_CALLBACK_INFO, ADAPTER_INFO, QUEUE_DESCRIPTOR,
            DEVICE_DESCRIPTOR, SURFACE_CAPABILITIES, SURFACE_CONFIGURATION, SURFACE_TEXTURE,
            SHADER_MODULE_DESCRIPTOR, SHADER_SOURCE_WGSL, VERTEX_ATTRIBUTE, VERTEX_BUFFER_LAYOUT, VERTEX_STATE,
            PRIMITIVE_STATE, MULTISAMPLE_STATE, COLOR_TARGET_STATE, FRAGMENT_STATE, RENDER_PIPELINE_DESCRIPTOR,
            COLOR, RENDER_PASS_COLOR_ATTACHMENT, RENDER_PASS_DESCRIPTOR, BUFFER_DESCRIPTOR);

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

    // ---- callbacks ---------------------------------------------------------------------------

    /** void (*)(WGPURequestAdapterStatus status, WGPUAdapter adapter, WGPUStringView message, void*, void*) */
    static final FunctionDescriptor REQUEST_ADAPTER_CALLBACK =
            FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, STRING_VIEW, ADDRESS, ADDRESS);

    /** void (*)(WGPURequestDeviceStatus status, WGPUDevice device, WGPUStringView message, void*, void*) */
    static final FunctionDescriptor REQUEST_DEVICE_CALLBACK =
            FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, STRING_VIEW, ADDRESS, ADDRESS);

    /** void (*)(WGPUDevice const *device, WGPUErrorType type, WGPUStringView message, void*, void*) */
    static final FunctionDescriptor UNCAPTURED_ERROR_CALLBACK =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, STRING_VIEW, ADDRESS, ADDRESS);

    /** void (*)(WGPUDevice const *device, WGPUDeviceLostReason reason, WGPUStringView message, void*, void*) */
    static final FunctionDescriptor DEVICE_LOST_CALLBACK =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, STRING_VIEW, ADDRESS, ADDRESS);

    /** void (*)(WGPUPopErrorScopeStatus status, WGPUErrorType type, WGPUStringView message, void*, void*) */
    static final FunctionDescriptor POP_ERROR_SCOPE_CALLBACK =
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, STRING_VIEW, ADDRESS, ADDRESS);

    /** void (*WGPULogCallback)(WGPULogLevel level, WGPUStringView message, void *userdata) */
    static final FunctionDescriptor LOG_CALLBACK = FunctionDescriptor.ofVoid(JAVA_INT, STRING_VIEW, ADDRESS);

    // ---- functions ---------------------------------------------------------------------------

    static final Linker LINKER = Linker.nativeLinker();
    /** wgpu_native.dll on Windows, libwgpu_native.so on Linux. */
    private static final String FILE = System.mapLibraryName("wgpu_native");
    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(locate(), Arena.global());

    static final FunctionDescriptor GET_VERSION = FunctionDescriptor.of(JAVA_INT);
    static final FunctionDescriptor SET_LOG_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    static final FunctionDescriptor SET_LOG_LEVEL = FunctionDescriptor.ofVoid(JAVA_INT);
    static final FunctionDescriptor CREATE_INSTANCE = FunctionDescriptor.of(ADDRESS, ADDRESS);
    static final FunctionDescriptor INSTANCE_ENUMERATE_ADAPTERS =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor INSTANCE_CREATE_SURFACE = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor INSTANCE_REQUEST_ADAPTER =
            FunctionDescriptor.of(FUTURE, ADDRESS, ADDRESS, CALLBACK_INFO);
    static final FunctionDescriptor INSTANCE_WAIT_ANY =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    static final FunctionDescriptor ADAPTER_GET_INFO = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor ADAPTER_INFO_FREE_MEMBERS = FunctionDescriptor.ofVoid(ADAPTER_INFO);
    static final FunctionDescriptor ADAPTER_REQUEST_DEVICE =
            FunctionDescriptor.of(FUTURE, ADDRESS, ADDRESS, CALLBACK_INFO);
    static final FunctionDescriptor DEVICE_GET_QUEUE = FunctionDescriptor.of(ADDRESS, ADDRESS);
    static final FunctionDescriptor DEVICE_PUSH_ERROR_SCOPE = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);
    static final FunctionDescriptor DEVICE_POP_ERROR_SCOPE = FunctionDescriptor.of(FUTURE, ADDRESS, CALLBACK_INFO);
    static final FunctionDescriptor DEVICE_CREATE_SHADER_MODULE = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor DEVICE_CREATE_RENDER_PIPELINE = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor DEVICE_CREATE_BUFFER = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor DEVICE_CREATE_COMMAND_ENCODER = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor DEVICE_POLL = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    static final FunctionDescriptor SURFACE_GET_CAPABILITIES =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor SURFACE_CAPABILITIES_FREE_MEMBERS =
            FunctionDescriptor.ofVoid(SURFACE_CAPABILITIES);
    static final FunctionDescriptor SURFACE_CONFIGURE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    static final FunctionDescriptor SURFACE_GET_CURRENT_TEXTURE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    static final FunctionDescriptor SURFACE_PRESENT = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor SURFACE_UNCONFIGURE = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor TEXTURE_CREATE_VIEW = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor COMMAND_ENCODER_BEGIN_RENDER_PASS =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor COMMAND_ENCODER_FINISH = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor RENDER_PASS_ENCODER_SET_PIPELINE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    static final FunctionDescriptor RENDER_PASS_ENCODER_SET_VERTEX_BUFFER =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG);
    static final FunctionDescriptor RENDER_PASS_ENCODER_DRAW =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor RENDER_PASS_ENCODER_END = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor QUEUE_SUBMIT = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    static final FunctionDescriptor QUEUE_WRITE_BUFFER =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    /** Every wgpu*Release function has this shape. */
    static final FunctionDescriptor RELEASE = FunctionDescriptor.ofVoid(ADDRESS);

    private static final MethodHandle GET_VERSION_MH = bind("wgpuGetVersion", GET_VERSION);
    private static final MethodHandle SET_LOG_CALLBACK_MH = bind("wgpuSetLogCallback", SET_LOG_CALLBACK);
    private static final MethodHandle SET_LOG_LEVEL_MH = bind("wgpuSetLogLevel", SET_LOG_LEVEL);
    private static final MethodHandle CREATE_INSTANCE_MH = bind("wgpuCreateInstance", CREATE_INSTANCE);
    private static final MethodHandle INSTANCE_ENUMERATE_ADAPTERS_MH =
            bind("wgpuInstanceEnumerateAdapters", INSTANCE_ENUMERATE_ADAPTERS);
    private static final MethodHandle INSTANCE_CREATE_SURFACE_MH =
            bind("wgpuInstanceCreateSurface", INSTANCE_CREATE_SURFACE);
    private static final MethodHandle INSTANCE_REQUEST_ADAPTER_MH =
            bind("wgpuInstanceRequestAdapter", INSTANCE_REQUEST_ADAPTER);
    private static final MethodHandle INSTANCE_WAIT_ANY_MH = bind("wgpuInstanceWaitAny", INSTANCE_WAIT_ANY);
    private static final MethodHandle INSTANCE_RELEASE_MH = bind("wgpuInstanceRelease", RELEASE);
    private static final MethodHandle ADAPTER_GET_INFO_MH = bind("wgpuAdapterGetInfo", ADAPTER_GET_INFO);
    private static final MethodHandle ADAPTER_INFO_FREE_MEMBERS_MH =
            bind("wgpuAdapterInfoFreeMembers", ADAPTER_INFO_FREE_MEMBERS);
    private static final MethodHandle ADAPTER_REQUEST_DEVICE_MH =
            bind("wgpuAdapterRequestDevice", ADAPTER_REQUEST_DEVICE);
    private static final MethodHandle ADAPTER_RELEASE_MH = bind("wgpuAdapterRelease", RELEASE);
    private static final MethodHandle DEVICE_GET_QUEUE_MH = bind("wgpuDeviceGetQueue", DEVICE_GET_QUEUE);
    private static final MethodHandle DEVICE_PUSH_ERROR_SCOPE_MH =
            bind("wgpuDevicePushErrorScope", DEVICE_PUSH_ERROR_SCOPE);
    private static final MethodHandle DEVICE_POP_ERROR_SCOPE_MH =
            bind("wgpuDevicePopErrorScope", DEVICE_POP_ERROR_SCOPE);
    private static final MethodHandle DEVICE_CREATE_SHADER_MODULE_MH =
            bind("wgpuDeviceCreateShaderModule", DEVICE_CREATE_SHADER_MODULE);
    private static final MethodHandle DEVICE_CREATE_RENDER_PIPELINE_MH =
            bind("wgpuDeviceCreateRenderPipeline", DEVICE_CREATE_RENDER_PIPELINE);
    private static final MethodHandle DEVICE_CREATE_BUFFER_MH = bind("wgpuDeviceCreateBuffer", DEVICE_CREATE_BUFFER);
    private static final MethodHandle DEVICE_CREATE_COMMAND_ENCODER_MH =
            bind("wgpuDeviceCreateCommandEncoder", DEVICE_CREATE_COMMAND_ENCODER);
    private static final MethodHandle DEVICE_POLL_MH = bind("wgpuDevicePoll", DEVICE_POLL);
    private static final MethodHandle DEVICE_RELEASE_MH = bind("wgpuDeviceRelease", RELEASE);
    private static final MethodHandle QUEUE_RELEASE_MH = bind("wgpuQueueRelease", RELEASE);
    private static final MethodHandle SURFACE_GET_CAPABILITIES_MH =
            bind("wgpuSurfaceGetCapabilities", SURFACE_GET_CAPABILITIES);
    private static final MethodHandle SURFACE_CAPABILITIES_FREE_MEMBERS_MH =
            bind("wgpuSurfaceCapabilitiesFreeMembers", SURFACE_CAPABILITIES_FREE_MEMBERS);
    private static final MethodHandle SURFACE_CONFIGURE_MH = bind("wgpuSurfaceConfigure", SURFACE_CONFIGURE);
    private static final MethodHandle SURFACE_GET_CURRENT_TEXTURE_MH =
            bind("wgpuSurfaceGetCurrentTexture", SURFACE_GET_CURRENT_TEXTURE);
    private static final MethodHandle SURFACE_PRESENT_MH = bind("wgpuSurfacePresent", SURFACE_PRESENT);
    private static final MethodHandle SURFACE_UNCONFIGURE_MH = bind("wgpuSurfaceUnconfigure", SURFACE_UNCONFIGURE);
    private static final MethodHandle SURFACE_RELEASE_MH = bind("wgpuSurfaceRelease", RELEASE);
    private static final MethodHandle TEXTURE_CREATE_VIEW_MH = bind("wgpuTextureCreateView", TEXTURE_CREATE_VIEW);
    private static final MethodHandle TEXTURE_RELEASE_MH = bind("wgpuTextureRelease", RELEASE);
    private static final MethodHandle TEXTURE_VIEW_RELEASE_MH = bind("wgpuTextureViewRelease", RELEASE);
    private static final MethodHandle SHADER_MODULE_RELEASE_MH = bind("wgpuShaderModuleRelease", RELEASE);
    private static final MethodHandle RENDER_PIPELINE_RELEASE_MH = bind("wgpuRenderPipelineRelease", RELEASE);
    private static final MethodHandle BUFFER_RELEASE_MH = bind("wgpuBufferRelease", RELEASE);
    private static final MethodHandle COMMAND_ENCODER_BEGIN_RENDER_PASS_MH =
            bind("wgpuCommandEncoderBeginRenderPass", COMMAND_ENCODER_BEGIN_RENDER_PASS);
    private static final MethodHandle COMMAND_ENCODER_FINISH_MH =
            bind("wgpuCommandEncoderFinish", COMMAND_ENCODER_FINISH);
    private static final MethodHandle COMMAND_ENCODER_RELEASE_MH = bind("wgpuCommandEncoderRelease", RELEASE);
    private static final MethodHandle COMMAND_BUFFER_RELEASE_MH = bind("wgpuCommandBufferRelease", RELEASE);
    private static final MethodHandle RENDER_PASS_ENCODER_SET_PIPELINE_MH =
            bind("wgpuRenderPassEncoderSetPipeline", RENDER_PASS_ENCODER_SET_PIPELINE);
    private static final MethodHandle RENDER_PASS_ENCODER_SET_VERTEX_BUFFER_MH =
            bind("wgpuRenderPassEncoderSetVertexBuffer", RENDER_PASS_ENCODER_SET_VERTEX_BUFFER);
    private static final MethodHandle RENDER_PASS_ENCODER_DRAW_MH =
            bind("wgpuRenderPassEncoderDraw", RENDER_PASS_ENCODER_DRAW);
    private static final MethodHandle RENDER_PASS_ENCODER_END_MH =
            bind("wgpuRenderPassEncoderEnd", RENDER_PASS_ENCODER_END);
    private static final MethodHandle RENDER_PASS_ENCODER_RELEASE_MH = bind("wgpuRenderPassEncoderRelease", RELEASE);
    private static final MethodHandle QUEUE_SUBMIT_MH = bind("wgpuQueueSubmit", QUEUE_SUBMIT);
    private static final MethodHandle QUEUE_WRITE_BUFFER_MH = bind("wgpuQueueWriteBuffer", QUEUE_WRITE_BUFFER);

    /** The four parts of the release tag, one byte each: 0x1D000101 is v29.0.1.1. */
    static int getVersion() {
        try {
            return (int) GET_VERSION_MH.invokeExact();
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void setLogCallback(MemorySegment callback, MemorySegment userdata) {
        try {
            SET_LOG_CALLBACK_MH.invokeExact(callback, userdata);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void setLogLevel(int level) {
        try {
            SET_LOG_LEVEL_MH.invokeExact(level);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment createInstance(MemorySegment descriptor) {
        try {
            return (MemorySegment) CREATE_INSTANCE_MH.invokeExact(descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** With NULL for {@code adapters} it returns the count. */
    static long instanceEnumerateAdapters(MemorySegment instance, MemorySegment options, MemorySegment adapters) {
        try {
            return (long) INSTANCE_ENUMERATE_ADAPTERS_MH.invokeExact(instance, options, adapters);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment instanceCreateSurface(MemorySegment instance, MemorySegment descriptor) {
        try {
            return (MemorySegment) INSTANCE_CREATE_SURFACE_MH.invokeExact(instance, descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** The WGPUFuture comes back by value; its id is returned. */
    static long instanceRequestAdapter(SegmentAllocator allocator, MemorySegment instance, MemorySegment options,
            MemorySegment callbackInfo) {
        try {
            MemorySegment future =
                    (MemorySegment) INSTANCE_REQUEST_ADAPTER_MH.invokeExact(allocator, instance, options, callbackInfo);
            return future.get(JAVA_LONG, 0);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static int instanceWaitAny(MemorySegment instance, long futureCount, MemorySegment futures, long timeoutNs) {
        try {
            return (int) INSTANCE_WAIT_ANY_MH.invokeExact(instance, futureCount, futures, timeoutNs);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void instanceRelease(MemorySegment instance) {
        release(INSTANCE_RELEASE_MH, instance);
    }

    static int adapterGetInfo(MemorySegment adapter, MemorySegment info) {
        try {
            return (int) ADAPTER_GET_INFO_MH.invokeExact(adapter, info);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void adapterInfoFreeMembers(MemorySegment info) {
        try {
            ADAPTER_INFO_FREE_MEMBERS_MH.invokeExact(info);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static long adapterRequestDevice(SegmentAllocator allocator, MemorySegment adapter, MemorySegment descriptor,
            MemorySegment callbackInfo) {
        try {
            MemorySegment future =
                    (MemorySegment) ADAPTER_REQUEST_DEVICE_MH.invokeExact(allocator, adapter, descriptor, callbackInfo);
            return future.get(JAVA_LONG, 0);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void adapterRelease(MemorySegment adapter) {
        release(ADAPTER_RELEASE_MH, adapter);
    }

    static MemorySegment deviceGetQueue(MemorySegment device) {
        try {
            return (MemorySegment) DEVICE_GET_QUEUE_MH.invokeExact(device);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void devicePushErrorScope(MemorySegment device, int filter) {
        try {
            DEVICE_PUSH_ERROR_SCOPE_MH.invokeExact(device, filter);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static long devicePopErrorScope(SegmentAllocator allocator, MemorySegment device, MemorySegment callbackInfo) {
        try {
            MemorySegment future =
                    (MemorySegment) DEVICE_POP_ERROR_SCOPE_MH.invokeExact(allocator, device, callbackInfo);
            return future.get(JAVA_LONG, 0);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment deviceCreateShaderModule(MemorySegment device, MemorySegment descriptor) {
        try {
            return (MemorySegment) DEVICE_CREATE_SHADER_MODULE_MH.invokeExact(device, descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment deviceCreateRenderPipeline(MemorySegment device, MemorySegment descriptor) {
        try {
            return (MemorySegment) DEVICE_CREATE_RENDER_PIPELINE_MH.invokeExact(device, descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment deviceCreateBuffer(MemorySegment device, MemorySegment descriptor) {
        try {
            return (MemorySegment) DEVICE_CREATE_BUFFER_MH.invokeExact(device, descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment deviceCreateCommandEncoder(MemorySegment device, MemorySegment descriptor) {
        try {
            return (MemorySegment) DEVICE_CREATE_COMMAND_ENCODER_MH.invokeExact(device, descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /**
     * The raw return value. wgpu.h declares WGPUBool, a uint32_t, but the Rust function returns a one-byte bool, so
     * only the lowest byte is defined: 1 when the queue is empty.
     */
    static int devicePoll(MemorySegment device, boolean wait) {
        try {
            return (int) DEVICE_POLL_MH.invokeExact(device, wait ? 1 : 0, MemorySegment.NULL);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void deviceRelease(MemorySegment device) {
        release(DEVICE_RELEASE_MH, device);
    }

    static void queueRelease(MemorySegment queue) {
        release(QUEUE_RELEASE_MH, queue);
    }

    static int surfaceGetCapabilities(MemorySegment surface, MemorySegment adapter, MemorySegment capabilities) {
        try {
            return (int) SURFACE_GET_CAPABILITIES_MH.invokeExact(surface, adapter, capabilities);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void surfaceCapabilitiesFreeMembers(MemorySegment capabilities) {
        try {
            SURFACE_CAPABILITIES_FREE_MEMBERS_MH.invokeExact(capabilities);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void surfaceConfigure(MemorySegment surface, MemorySegment configuration) {
        try {
            SURFACE_CONFIGURE_MH.invokeExact(surface, configuration);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void surfaceGetCurrentTexture(MemorySegment surface, MemorySegment surfaceTexture) {
        try {
            SURFACE_GET_CURRENT_TEXTURE_MH.invokeExact(surface, surfaceTexture);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static int surfacePresent(MemorySegment surface) {
        try {
            return (int) SURFACE_PRESENT_MH.invokeExact(surface);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void surfaceUnconfigure(MemorySegment surface) {
        try {
            SURFACE_UNCONFIGURE_MH.invokeExact(surface);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void surfaceRelease(MemorySegment surface) {
        release(SURFACE_RELEASE_MH, surface);
    }

    static MemorySegment textureCreateView(MemorySegment texture, MemorySegment descriptor) {
        try {
            return (MemorySegment) TEXTURE_CREATE_VIEW_MH.invokeExact(texture, descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void textureRelease(MemorySegment texture) {
        release(TEXTURE_RELEASE_MH, texture);
    }

    static void textureViewRelease(MemorySegment view) {
        release(TEXTURE_VIEW_RELEASE_MH, view);
    }

    static void shaderModuleRelease(MemorySegment module) {
        release(SHADER_MODULE_RELEASE_MH, module);
    }

    static void renderPipelineRelease(MemorySegment pipeline) {
        release(RENDER_PIPELINE_RELEASE_MH, pipeline);
    }

    static void bufferRelease(MemorySegment buffer) {
        release(BUFFER_RELEASE_MH, buffer);
    }

    static MemorySegment commandEncoderBeginRenderPass(MemorySegment encoder, MemorySegment descriptor) {
        try {
            return (MemorySegment) COMMAND_ENCODER_BEGIN_RENDER_PASS_MH.invokeExact(encoder, descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static MemorySegment commandEncoderFinish(MemorySegment encoder, MemorySegment descriptor) {
        try {
            return (MemorySegment) COMMAND_ENCODER_FINISH_MH.invokeExact(encoder, descriptor);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void commandEncoderRelease(MemorySegment encoder) {
        release(COMMAND_ENCODER_RELEASE_MH, encoder);
    }

    static void commandBufferRelease(MemorySegment commandBuffer) {
        release(COMMAND_BUFFER_RELEASE_MH, commandBuffer);
    }

    static void renderPassEncoderSetPipeline(MemorySegment pass, MemorySegment pipeline) {
        try {
            RENDER_PASS_ENCODER_SET_PIPELINE_MH.invokeExact(pass, pipeline);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void renderPassEncoderSetVertexBuffer(MemorySegment pass, int slot, MemorySegment buffer, long offset,
            long size) {
        try {
            RENDER_PASS_ENCODER_SET_VERTEX_BUFFER_MH.invokeExact(pass, slot, buffer, offset, size);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void renderPassEncoderDraw(MemorySegment pass, int vertexCount, int instanceCount, int firstVertex,
            int firstInstance) {
        try {
            RENDER_PASS_ENCODER_DRAW_MH.invokeExact(pass, vertexCount, instanceCount, firstVertex, firstInstance);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void renderPassEncoderEnd(MemorySegment pass) {
        try {
            RENDER_PASS_ENCODER_END_MH.invokeExact(pass);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void renderPassEncoderRelease(MemorySegment pass) {
        release(RENDER_PASS_ENCODER_RELEASE_MH, pass);
    }

    static void queueSubmit(MemorySegment queue, long commandCount, MemorySegment commands) {
        try {
            QUEUE_SUBMIT_MH.invokeExact(queue, commandCount, commands);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void queueWriteBuffer(MemorySegment queue, MemorySegment buffer, long bufferOffset, MemorySegment data,
            long size) {
        try {
            QUEUE_WRITE_BUFFER_MH.invokeExact(queue, buffer, bufferOffset, data, size);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Writes a WGPUStringView that points at {@code utf8}, a segment without a terminating NUL. */
    static void setStringView(MemorySegment struct, long offset, MemorySegment utf8) {
        struct.set(ADDRESS, offset, utf8);
        struct.set(JAVA_LONG, offset + 8, utf8.byteSize());
    }

    /** UTF-8 bytes of {@code text} without a terminating NUL, which is what a WGPUStringView with a length wants. */
    static MemorySegment utf8(Arena arena, String text) {
        return arena.allocateFrom(JAVA_BYTE, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Copies the text of a WGPUStringView at {@code offset} in {@code struct}. wgpu-native frees the bytes behind
     * a view it hands to a callback when the callback returns, so this has to run inside the callback.
     */
    static String string(MemorySegment struct, long offset) {
        MemorySegment data = struct.get(ADDRESS, offset);
        long length = struct.get(JAVA_LONG, offset + 8);
        if (data.equals(MemorySegment.NULL)) {
            return "";
        }
        if (length == -1) { // WGPU_STRLEN: NUL-terminated
            return data.reinterpret(Long.MAX_VALUE).getString(0);
        }
        return new String(data.reinterpret(length).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static void release(MethodHandle handle, MemorySegment object) {
        try {
            handle.invokeExact(object);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol =
                LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError(FILE + " has no " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    static RuntimeException failed(Throwable t) {
        return t instanceof RuntimeException r ? r : new IllegalStateException(t);
    }

    /** The library from the wgpu-native release zip, unpacked into the git-ignored folder next to the sources. */
    private static Path locate() {
        String override = System.getProperty("wgpu.dll");
        if (override != null) {
            return Path.of(override);
        }
        for (String candidate : new String[] {"spikes/gpu/native/lib/" + FILE, "native/lib/" + FILE}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new UnsatisfiedLinkError(FILE + " not found. Unpack wgpu-windows-x86_64-msvc-release.zip (Windows) "
                + "or wgpu-linux-x86_64-release.zip (Linux) "
                + "from github.com/gfx-rs/wgpu-native/releases into spikes/gpu/native/.");
    }
}
