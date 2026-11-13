package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.rethinkj.ui.gpu.Structs.BindGroupDescriptor;
import com.rethinkj.ui.gpu.Structs.BindGroupEntry;
import com.rethinkj.ui.gpu.Structs.BindGroupLayoutDescriptor;
import com.rethinkj.ui.gpu.Structs.BindGroupLayoutEntry;
import com.rethinkj.ui.gpu.Structs.BufferDescriptor;
import com.rethinkj.ui.gpu.Structs.CallbackInfo;
import com.rethinkj.ui.gpu.Structs.CommandEncoderDescriptor;
import com.rethinkj.ui.gpu.Structs.ShaderModuleDescriptor;
import com.rethinkj.ui.gpu.Structs.ShaderSourceWgsl;
import com.rethinkj.ui.gpu.Structs.TextureDescriptor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A logical GPU device: it creates shader modules, buffers, textures, bind groups, pipelines and
 * command encoders, and owns the {@link Queue}.
 *
 * <p>wgpu reports most errors not as a result but to an error scope or to the device's
 * uncaptured-error callback, and a create function returns a handle even when it fails. So every
 * call here runs inside a validation and an out-of-memory error scope and throws {@link
 * GpuException} with wgpu's text and the object's label if one caught something. A command
 * encoder keeps its scopes open from creation to {@link CommandEncoder#finish}, which is where
 * wgpu reports what was wrong with the recorded commands. An error that still reaches the
 * uncaptured-error callback is thrown by the next call on the device.
 *
 * <p>Closing a device unconfigures the surfaces configured with it and closes everything it
 * created, newest first.
 */
public final class Device extends GpuObject {

  private final MemorySegment adapter;
  private final Callbacks.DeviceEvents events;
  /** The id of the device's callback target; package-private for a test. */
  final long eventsId;
  private final Queue queue;
  private final Limits limits;
  /** Structs that every error scope pop reuses, for the life of the device. */
  private final Arena arena = Arena.ofConfined();
  private final MemorySegment popInfo;
  private final SegmentAllocator future;
  /** Error scopes pushed and not yet popped; popping one more ends the process. */
  private int scopes;
  private CommandEncoder recording;
  private final List<Surface> surfaces = new ArrayList<>();

  Device(Instance instance, String label, MemorySegment handle, MemorySegment adapter,
      Callbacks.DeviceEvents events, long eventsId) {
    super(instance, instance, label, handle);
    this.adapter = adapter;
    this.events = events;
    this.eventsId = eventsId;
    this.queue = new Queue(this, Wgpu.deviceGetQueue(handle));
    this.popInfo = CallbackInfo.allocate(arena, Callbacks.POP_ERROR_SCOPE, eventsId);
    this.future = SegmentAllocator.prefixAllocator(arena.allocate(Structs.FUTURE));
    MemorySegment limits = arena.allocate(Structs.Limits.LAYOUT);
    if (Wgpu.deviceGetLimits(handle, limits) != Wgpu.STATUS_SUCCESS) {
      throw new IllegalStateException("wgpuDeviceGetLimits failed for " + this);
    }
    this.limits = Limits.read(limits);
  }

  public Queue queue() {
    checkOpen();
    return queue;
  }

  public Limits limits() {
    checkOpen();
    return limits;
  }

  /** True once wgpu reported the device lost; every call but {@link #close} then throws. */
  public boolean isLost() {
    return events.lost != null;
  }

  /** Compiles WGSL. A WGSL error is a {@link GpuException} whose text gives line and column. */
  public ShaderModule createShaderModule(String label, String wgsl) {
    requireLabel(label);
    MemorySegment device = usable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment source = arena.allocate(ShaderSourceWgsl.LAYOUT);
      source.set(JAVA_INT, Structs.CHAIN_STYPE, Wgpu.STYPE_SHADER_SOURCE_WGSL);
      Structs.string(source, ShaderSourceWgsl.CODE, wgsl, arena);
      MemorySegment descriptor = arena.allocate(ShaderModuleDescriptor.LAYOUT);
      descriptor.set(ADDRESS, ShaderModuleDescriptor.NEXT_IN_CHAIN, source);
      Structs.string(descriptor, ShaderModuleDescriptor.LABEL, label, arena);
      MemorySegment module = create("creating shader module", label,
          () -> Wgpu.deviceCreateShaderModule(device, descriptor), Wgpu::shaderModuleRelease);
      return new ShaderModule(this, label, module);
    }
  }

  /**
   * A buffer of {@code size} bytes, zeroed.
   *
   * @param size a multiple of 4, as wgpu requires for writes and copies
   */
  public Buffer createBuffer(String label, long size, BufferUsage usage, BufferUsage... more) {
    requireLabel(label);
    Set<BufferUsage> usages = EnumSet.of(usage, more);
    if (size <= 0 || size % 4 != 0) {
      throw new IllegalArgumentException("The size of buffer '" + label + "' is " + size
          + "; it must be a positive multiple of 4.");
    }
    MemorySegment device = usable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment descriptor = arena.allocate(BufferDescriptor.LAYOUT);
      Structs.string(descriptor, BufferDescriptor.LABEL, label, arena);
      descriptor.set(JAVA_LONG, BufferDescriptor.USAGE, BufferUsage.bits(usages));
      descriptor.set(JAVA_LONG, BufferDescriptor.SIZE, size);
      MemorySegment buffer = create("creating buffer", label,
          () -> Wgpu.deviceCreateBuffer(device, descriptor), Wgpu::bufferRelease);
      return new Buffer(this, label, buffer, size, usages);
    }
  }

  /** A 2D texture with one mip level and one sample. */
  public Texture createTexture(String label, int width, int height, TextureFormat format,
      TextureUsage usage, TextureUsage... more) {
    return createTexture(label, width, height, 1, false, 1, format, EnumSet.of(usage, more));
  }

  /**
   * A 2D texture with {@code levels} mip levels and one sample: level {@code k} is {@code max(1,
   * width >> k)} by {@code max(1, height >> k)} texels. Its views hold all levels, which a shader
   * reads with {@code textureLoad(texture, texel, level)}; {@link Queue#writeTexture(Texture, int,
   * int, int, int, int, int, MemorySegment, long, int)} writes one.
   *
   * @param levels from 1 to the number of halvings that leave a side of at least 1, plus 1
   */
  public Texture createTexture(String label, int width, int height, int levels,
      TextureFormat format, TextureUsage usage, TextureUsage... more) {
    return createTexture(label, width, height, 1, false, levels, format, EnumSet.of(usage, more));
  }

  /**
   * An array of {@code layers} 2D textures of the same size and format, with one mip level and one
   * sample. Its views are 2D arrays of all layers, even of one, which a shader reads as {@code
   * texture_2d_array}.
   *
   * @param layers from 1 to {@link Limits#maxTextureArrayLayers}
   */
  public Texture createTextureArray(String label, int width, int height, int layers,
      TextureFormat format, TextureUsage usage, TextureUsage... more) {
    return createTexture(label, width, height, layers, true, 1, format, EnumSet.of(usage, more));
  }

  private Texture createTexture(String label, int width, int height, int layers, boolean array,
      int levels, TextureFormat format, Set<TextureUsage> usages) {
    requireLabel(label);
    checkSize("texture '" + label + "'", width, height);
    int most = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    if (levels < 1 || levels > most) {
      throw new IllegalArgumentException("Texture '" + label + "' of " + width + "x" + height
          + " has " + levels + " mip levels; it can have between 1 and " + most + ".");
    }
    int maxLayers = limits.maxTextureArrayLayers();
    if (layers < 1 || layers > maxLayers) {
      throw new IllegalArgumentException("Texture '" + label + "' has " + layers + " layers;"
          + " it needs between 1 and " + maxLayers + ", the device's limit.");
    }
    MemorySegment device = usable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment descriptor = TextureDescriptor.allocate(arena);
      Structs.string(descriptor, TextureDescriptor.LABEL, label, arena);
      descriptor.set(JAVA_LONG, TextureDescriptor.USAGE, TextureUsage.bits(usages));
      descriptor.set(JAVA_INT, TextureDescriptor.DIMENSION, Wgpu.TEXTURE_DIMENSION_2D);
      Structs.Extent3D.set(descriptor, TextureDescriptor.SIZE, width, height, layers);
      descriptor.set(JAVA_INT, TextureDescriptor.FORMAT, format.value);
      descriptor.set(JAVA_INT, TextureDescriptor.MIP_LEVEL_COUNT, levels);
      MemorySegment texture = create("creating texture", label,
          () -> Wgpu.deviceCreateTexture(device, descriptor), Wgpu::textureRelease);
      return new Texture(this, this, label, texture, width, height, layers, array, levels,
          format, usages);
    }
  }

  /**
   * A layout of bindings: uniform and read-only storage buffers, and textures read with {@code
   * textureLoad}.
   */
  public BindGroupLayout createBindGroupLayout(String label, BindGroupLayout.Entry... entries) {
    requireLabel(label);
    MemorySegment device = usable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment array =
          arena.allocate(BindGroupLayoutEntry.LAYOUT, Math.max(1, entries.length));
      for (int i = 0; i < entries.length; i++) {
        BindGroupLayout.Entry entry = entries[i];
        MemorySegment e = array.asSlice(i * BindGroupLayoutEntry.LAYOUT.byteSize());
        e.set(JAVA_INT, BindGroupLayoutEntry.BINDING, entry.binding());
        e.set(JAVA_LONG, BindGroupLayoutEntry.VISIBILITY, ShaderStage.bits(entry.visibility()));
        // The other binding layouts stay zero: not used.
        switch (entry.type()) {
          case BindGroupLayout.BufferType buffer -> {
            e.set(JAVA_INT, BindGroupLayoutEntry.BUFFER_TYPE, buffer.value);
            e.set(JAVA_LONG, BindGroupLayoutEntry.BUFFER_MIN_BINDING_SIZE,
                entry.minBindingSize());
          }
          case BindGroupLayout.TextureType texture -> {
            e.set(JAVA_INT, BindGroupLayoutEntry.TEXTURE_SAMPLE_TYPE,
                Wgpu.TEXTURE_SAMPLE_TYPE_UNFILTERABLE_FLOAT);
            e.set(JAVA_INT, BindGroupLayoutEntry.TEXTURE_VIEW_DIMENSION, texture.viewDimension);
          }
        }
      }
      MemorySegment descriptor = arena.allocate(BindGroupLayoutDescriptor.LAYOUT);
      Structs.string(descriptor, BindGroupLayoutDescriptor.LABEL, label, arena);
      descriptor.set(JAVA_LONG, BindGroupLayoutDescriptor.ENTRY_COUNT, entries.length);
      descriptor.set(ADDRESS, BindGroupLayoutDescriptor.ENTRIES, array);
      MemorySegment layout = create("creating bind group layout", label,
          () -> Wgpu.deviceCreateBindGroupLayout(device, descriptor),
          Wgpu::bindGroupLayoutRelease);
      return new BindGroupLayout(this, label, layout, List.of(entries));
    }
  }

  /** Binds buffers and texture views to the bindings of {@code layout}. */
  public BindGroup createBindGroup(String label, BindGroupLayout layout,
      BindGroup.Entry... entries) {
    requireLabel(label);
    MemorySegment device = usable();
    MemorySegment layoutHandle = owned(layout).handle();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment array = arena.allocate(BindGroupEntry.LAYOUT, Math.max(1, entries.length));
      for (int i = 0; i < entries.length; i++) {
        BindGroup.Entry entry = entries[i];
        MemorySegment e = array.asSlice(i * BindGroupEntry.LAYOUT.byteSize());
        e.set(JAVA_INT, BindGroupEntry.BINDING, entry.binding());
        // wgpu-native takes the buffer if there is one, else the sampler, else the view.
        if (entry.buffer() != null) {
          e.set(ADDRESS, BindGroupEntry.BUFFER, owned(entry.buffer()).handle());
        } else {
          e.set(ADDRESS, BindGroupEntry.TEXTURE_VIEW, owned(entry.view()).handle());
        }
        e.set(JAVA_LONG, BindGroupEntry.OFFSET, entry.offset());
        e.set(JAVA_LONG, BindGroupEntry.SIZE, entry.size());
      }
      MemorySegment descriptor = arena.allocate(BindGroupDescriptor.LAYOUT);
      Structs.string(descriptor, BindGroupDescriptor.LABEL, label, arena);
      descriptor.set(ADDRESS, BindGroupDescriptor.LAYOUT_HANDLE, layoutHandle);
      descriptor.set(JAVA_LONG, BindGroupDescriptor.ENTRY_COUNT, entries.length);
      descriptor.set(ADDRESS, BindGroupDescriptor.ENTRIES, array);
      MemorySegment group = create("creating bind group", label,
          () -> Wgpu.deviceCreateBindGroup(device, descriptor), Wgpu::bindGroupRelease);
      return new BindGroup(this, label, group);
    }
  }

  public RenderPipeline createRenderPipeline(RenderPipeline.Descriptor descriptor) {
    return RenderPipeline.create(this, descriptor);
  }

  /**
   * A command encoder. Only one can record at a time on a device: its error scopes stay open until
   * {@link CommandEncoder#finish} or {@link CommandEncoder#close}.
   */
  public CommandEncoder createCommandEncoder(String label) {
    requireLabel(label);
    MemorySegment device = usable();
    if (recording != null) {
      throw new IllegalStateException("Cannot create command encoder '" + label + "': " + recording
          + " is still recording on " + this + ". Finish or close it first.");
    }
    MemorySegment encoder;
    pushScopes();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment descriptor = arena.allocate(CommandEncoderDescriptor.LAYOUT);
      Structs.string(descriptor, CommandEncoderDescriptor.LABEL, label, arena);
      encoder = Wgpu.deviceCreateCommandEncoder(device, descriptor);
    } catch (RuntimeException | Error e) {
      popScopes("creating command encoder", label);
      throw e;
    }
    recording = new CommandEncoder(this, label, encoder);
    return recording;
  }

  /** Blocks until the GPU has done all submitted work. */
  public void waitIdle() {
    poll(true);
  }

  // ---- for the other classes of the layer ----------------------------------------------------

  /** The handle, after checking that the device is open, not lost and has no pending error. */
  MemorySegment usable() {
    MemorySegment device = handle();
    String lost = events.lost;
    if (lost != null) {
      throw new GpuException(GpuException.Kind.DEVICE_LOST, "using device", label(), lost);
    }
    events.rethrowFailure("reporting an error of " + this);
    GpuException uncaptured = events.uncaptured.poll();
    if (uncaptured != null) {
      for (GpuException more; (more = events.uncaptured.poll()) != null; ) {
        uncaptured.addSuppressed(more);
      }
      throw uncaptured;
    }
    return device;
  }

  /** Checks that {@code object} was created by this device. */
  <T extends GpuObject> T owned(T object) {
    GpuObject owner = object instanceof TextureView view ? view.device
        : object instanceof Texture texture ? texture.device
        : object instanceof Buffer buffer ? buffer.device
        : object.parent;
    if (owner != this) {
      throw new IllegalArgumentException(object + " belongs to " + owner + ", not to " + this);
    }
    return object;
  }

  void checkSize(String what, int width, int height) {
    int max = limits.maxTextureDimension2D();
    if (width <= 0 || height <= 0 || width > max || height > max) {
      throw new IllegalArgumentException("The size of " + what + " is " + width + "x" + height
          + "; each side must be between 1 and " + max + ", the device's limit.");
    }
  }

  /**
   * Runs a create function inside error scopes. If they caught an error, the handle wgpu returned
   * anyway is released and the error thrown.
   */
  MemorySegment create(String what, String label, Supplier<MemorySegment> function,
      Consumer<MemorySegment> release) {
    MemorySegment handle = MemorySegment.NULL;
    GpuException error;
    pushScopes();
    try {
      handle = function.get();
    } finally {
      error = popScopes(what, label);
      if (error != null && !handle.equals(MemorySegment.NULL)) {
        release.accept(handle);
      }
    }
    if (error != null) {
      throw error;
    }
    if (handle.equals(MemorySegment.NULL)) {
      throw new IllegalStateException("wgpu returned NULL while " + what + " '" + label + "'");
    }
    return handle;
  }

  /** Runs {@code action} inside error scopes and throws what they caught. */
  void run(String what, String label, Runnable action) {
    GpuException error;
    pushScopes();
    try {
      action.run();
    } finally {
      error = popScopes(what, label);
    }
    if (error != null) {
      throw error;
    }
  }

  /** Pushes an out-of-memory and, on top of it, a validation error scope. */
  void pushScopes() {
    MemorySegment device = handle();
    Wgpu.devicePushErrorScope(device, Wgpu.ERROR_FILTER_OUT_OF_MEMORY);
    scopes++;
    Wgpu.devicePushErrorScope(device, Wgpu.ERROR_FILTER_VALIDATION);
    scopes++;
  }

  /** Pops the two scopes of {@link #pushScopes} and returns what they caught, or null. */
  GpuException popScopes(String what, String label) {
    GpuException validation = popScope(what, label);
    GpuException outOfMemory = popScope(what, label);
    if (validation == null) {
      return outOfMemory;
    }
    if (outOfMemory != null) {
      validation.addSuppressed(outOfMemory);
    }
    return validation;
  }

  private GpuException popScope(String what, String label) {
    if (scopes == 0) {
      throw new IllegalStateException("No error scope is open on " + this);
    }
    scopes--;
    events.scopeDone = false;
    events.scopeMessage = "";
    Wgpu.devicePopErrorScope(future, handle(), popInfo);
    events.rethrowFailure(what + " '" + label + "'");
    if (!events.scopeDone) {
      throw Instance.notSynchronous("wgpuDevicePopErrorScope");
    }
    if (events.scopeStatus != Wgpu.POP_ERROR_SCOPE_STATUS_SUCCESS
        || events.scopeType == Wgpu.ERROR_TYPE_NO_ERROR) {
      return null;
    }
    return new GpuException(GpuException.kindOf(events.scopeType), what, label,
        events.scopeMessage);
  }

  /** The adapter the device came from, which it keeps a reference to. */
  MemorySegment adapterHandle() {
    checkOpen();
    return adapter;
  }

  /** Waits for or checks on submitted work; runs due callbacks. True if the queue is empty. */
  boolean poll(boolean wait) {
    // wgpu-native ends the process if polling fails, which it does for a lost device.
    return Wgpu.devicePoll(usable(), wait);
  }

  void finishedRecording(CommandEncoder encoder) {
    if (recording == encoder) {
      recording = null;
    }
  }

  void configured(Surface surface) {
    if (!surfaces.contains(surface)) {
      surfaces.add(surface);
    }
  }

  void unconfigured(Surface surface) {
    surfaces.remove(surface);
  }

  @Override
  void beforeClose() {
    for (Surface surface : List.copyOf(surfaces)) {
      if (!surface.isClosed()) {
        surface.unconfigure();
      }
    }
    surfaces.clear();
  }

  @Override
  String kind() {
    return "Device";
  }

  @Override
  void release(MemorySegment handle) {
    Callbacks.unregister(eventsId);
    queue.released();
    arena.close();
    Wgpu.queueRelease(queue.handle);
    if (events.lost == null) {
      // Releasing the last reference waits for the GPU, and wgpu-native ends the process if that
      // fails. A lost device is left to the end of the process instead.
      Wgpu.deviceRelease(handle);
    }
    Wgpu.adapterRelease(adapter);
  }
}
