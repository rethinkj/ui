package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.rethinkj.ui.gpu.Structs.BlendState;
import com.rethinkj.ui.gpu.Structs.ColorTargetState;
import com.rethinkj.ui.gpu.Structs.FragmentState;
import com.rethinkj.ui.gpu.Structs.PipelineLayoutDescriptor;
import com.rethinkj.ui.gpu.Structs.RenderPipelineDescriptor;
import com.rethinkj.ui.gpu.Structs.VertexAttribute;
import com.rethinkj.ui.gpu.Structs.VertexBufferLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How to draw: a vertex and a fragment shader, the vertex buffers they read, the bind group
 * layouts of their uniforms and the colour targets they write, with blending.
 */
public final class RenderPipeline extends GpuObject {

  /** Pipelines created in this process, for the tests that count them. */
  private static final AtomicLong CREATED = new AtomicLong();

  /**
   * What a pipeline consists of.
   *
   * @param bindGroupLayouts the layouts of {@code @group(0)}, {@code @group(1)}, ...; may be empty
   * @param module the WGSL module with both entry points
   * @param vertexEntryPoint the {@code @vertex} function, or null if the module has only one
   * @param fragmentEntryPoint the {@code @fragment} function, or null if the module has only one
   * @param targets one per {@code @location} the fragment shader writes
   */
  public record Descriptor(String label, List<BindGroupLayout> bindGroupLayouts,
      ShaderModule module, String vertexEntryPoint, List<VertexBuffer> vertexBuffers,
      String fragmentEntryPoint, List<ColorTarget> targets, Topology topology) {

    public Descriptor {
      Objects.requireNonNull(label, "label");
      bindGroupLayouts = List.copyOf(bindGroupLayouts);
      Objects.requireNonNull(module, "module");
      vertexBuffers = List.copyOf(vertexBuffers);
      targets = List.copyOf(targets);
      Objects.requireNonNull(topology, "topology");
      if (targets.isEmpty()) {
        throw new IllegalArgumentException("Render pipeline '" + label + "' has no colour target.");
      }
    }
  }

  /**
   * One vertex buffer slot: its stride, whether it advances per vertex or per instance, and the
   * attributes it holds.
   */
  public record VertexBuffer(long arrayStride, StepMode stepMode, List<Attribute> attributes) {
    public VertexBuffer {
      Objects.requireNonNull(stepMode, "stepMode");
      attributes = List.copyOf(attributes);
      if (arrayStride < 0 || arrayStride % 4 != 0) {
        throw new IllegalArgumentException("A vertex buffer's stride must be a multiple of 4: "
            + arrayStride);
      }
    }
  }

  /** An attribute at {@code @location(shaderLocation)}, {@code offset} bytes into an element. */
  public record Attribute(VertexFormat format, long offset, int shaderLocation) {
    public Attribute {
      Objects.requireNonNull(format, "format");
      if (offset < 0 || shaderLocation < 0) {
        throw new IllegalArgumentException("offset " + offset + ", location " + shaderLocation);
      }
    }
  }

  /** {@code WGPUVertexStepMode}. */
  public enum StepMode {
    VERTEX(1), INSTANCE(2);

    final int value;

    StepMode(int value) {
      this.value = value;
    }
  }

  /** {@code WGPUPrimitiveTopology}. */
  public enum Topology {
    POINT_LIST(1), LINE_LIST(2), LINE_STRIP(3), TRIANGLE_LIST(4), TRIANGLE_STRIP(5);

    final int value;

    Topology(int value) {
      this.value = value;
    }
  }

  /** A colour target: its format and how to blend into it, or null to replace. */
  public record ColorTarget(TextureFormat format, Blend blend) {
    public ColorTarget {
      Objects.requireNonNull(format, "format");
    }
  }

  /** {@code WGPUBlendState}. */
  public record Blend(BlendComponent color, BlendComponent alpha) {

    /** Source over destination for colours premultiplied by alpha. */
    public static final Blend PREMULTIPLIED_ALPHA = new Blend(
        new BlendComponent(BlendOperation.ADD, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA),
        new BlendComponent(BlendOperation.ADD, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA));

    /** Source over destination for straight alpha. */
    public static final Blend ALPHA = new Blend(
        new BlendComponent(BlendOperation.ADD, BlendFactor.SRC_ALPHA,
            BlendFactor.ONE_MINUS_SRC_ALPHA),
        new BlendComponent(BlendOperation.ADD, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA));

    public Blend {
      Objects.requireNonNull(color, "color");
      Objects.requireNonNull(alpha, "alpha");
    }
  }

  /** {@code WGPUBlendComponent}: {@code src * srcFactor operation dst * dstFactor}. */
  public record BlendComponent(BlendOperation operation, BlendFactor srcFactor,
      BlendFactor dstFactor) {
    public BlendComponent {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(srcFactor, "srcFactor");
      Objects.requireNonNull(dstFactor, "dstFactor");
    }
  }

  /** {@code WGPUBlendOperation}. */
  public enum BlendOperation {
    ADD(1), SUBTRACT(2), REVERSE_SUBTRACT(3), MIN(4), MAX(5);

    final int value;

    BlendOperation(int value) {
      this.value = value;
    }
  }

  /** {@code WGPUBlendFactor}. */
  public enum BlendFactor {
    ZERO(1), ONE(2), SRC(3), ONE_MINUS_SRC(4), SRC_ALPHA(5), ONE_MINUS_SRC_ALPHA(6), DST(7),
    ONE_MINUS_DST(8), DST_ALPHA(9), ONE_MINUS_DST_ALPHA(10);

    final int value;

    BlendFactor(int value) {
      this.value = value;
    }
  }

  private RenderPipeline(Device device, String label, MemorySegment handle) {
    super(device.instance, device, label, handle);
  }

  /**
   * How many pipelines this process has created so far: the first frame that needs one compiles
   * its shaders, which takes over 300 ms on Direct3D 12, so the windows of a thread share theirs,
   * and tests count them.
   */
  public static long created() {
    return CREATED.get();
  }

  static RenderPipeline create(Device device, Descriptor d) {
    MemorySegment deviceHandle = device.usable();
    MemorySegment module = device.owned(d.module()).handle();
    String label = d.label();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment layouts = arena.allocate(ADDRESS, Math.max(1, d.bindGroupLayouts().size()));
      for (int i = 0; i < d.bindGroupLayouts().size(); i++) {
        layouts.setAtIndex(ADDRESS, i, device.owned(d.bindGroupLayouts().get(i)).handle());
      }
      MemorySegment layoutDescriptor = arena.allocate(PipelineLayoutDescriptor.LAYOUT);
      Structs.string(layoutDescriptor, PipelineLayoutDescriptor.LABEL, label, arena);
      layoutDescriptor.set(JAVA_LONG, PipelineLayoutDescriptor.BIND_GROUP_LAYOUT_COUNT,
          d.bindGroupLayouts().size());
      layoutDescriptor.set(ADDRESS, PipelineLayoutDescriptor.BIND_GROUP_LAYOUTS, layouts);
      MemorySegment pipelineLayout = device.create("creating pipeline layout", label,
          () -> Wgpu.deviceCreatePipelineLayout(deviceHandle, layoutDescriptor),
          Wgpu::pipelineLayoutRelease);
      try {
        MemorySegment descriptor = RenderPipelineDescriptor.allocate(arena);
        Structs.string(descriptor, RenderPipelineDescriptor.LABEL, label, arena);
        descriptor.set(ADDRESS, RenderPipelineDescriptor.LAYOUT_HANDLE, pipelineLayout);
        descriptor.set(ADDRESS, RenderPipelineDescriptor.VERTEX_MODULE, module);
        Structs.string(descriptor, RenderPipelineDescriptor.VERTEX_ENTRY_POINT,
            d.vertexEntryPoint(), arena);
        descriptor.set(JAVA_LONG, RenderPipelineDescriptor.VERTEX_BUFFER_COUNT,
            d.vertexBuffers().size());
        descriptor.set(ADDRESS, RenderPipelineDescriptor.VERTEX_BUFFERS,
            vertexBuffers(d.vertexBuffers(), arena));
        descriptor.set(JAVA_INT, RenderPipelineDescriptor.PRIMITIVE_TOPOLOGY, d.topology().value);
        MemorySegment fragment = FragmentState.allocate(arena);
        fragment.set(ADDRESS, FragmentState.MODULE, module);
        Structs.string(fragment, FragmentState.ENTRY_POINT, d.fragmentEntryPoint(), arena);
        fragment.set(JAVA_LONG, FragmentState.TARGET_COUNT, d.targets().size());
        fragment.set(ADDRESS, FragmentState.TARGETS, targets(d.targets(), arena));
        descriptor.set(ADDRESS, RenderPipelineDescriptor.FRAGMENT, fragment);
        MemorySegment pipeline = device.create("creating render pipeline", label,
            () -> Wgpu.deviceCreateRenderPipeline(deviceHandle, descriptor),
            Wgpu::renderPipelineRelease);
        CREATED.incrementAndGet();
        return new RenderPipeline(device, label, pipeline);
      } finally {
        // The pipeline holds on to its layout.
        Wgpu.pipelineLayoutRelease(pipelineLayout);
      }
    }
  }

  private static MemorySegment vertexBuffers(List<VertexBuffer> buffers, Arena arena) {
    if (buffers.isEmpty()) {
      return MemorySegment.NULL;
    }
    MemorySegment array = arena.allocate(VertexBufferLayout.LAYOUT, buffers.size());
    for (int i = 0; i < buffers.size(); i++) {
      VertexBuffer buffer = buffers.get(i);
      MemorySegment b = array.asSlice(i * VertexBufferLayout.LAYOUT.byteSize());
      // Never Undefined: with attributes, wgpu-native ends the process for that.
      b.set(JAVA_INT, VertexBufferLayout.STEP_MODE, buffer.stepMode().value);
      b.set(JAVA_LONG, VertexBufferLayout.ARRAY_STRIDE, buffer.arrayStride());
      b.set(JAVA_LONG, VertexBufferLayout.ATTRIBUTE_COUNT, buffer.attributes().size());
      MemorySegment attributes = buffer.attributes().isEmpty() ? MemorySegment.NULL
          : arena.allocate(VertexAttribute.LAYOUT, buffer.attributes().size());
      for (int j = 0; j < buffer.attributes().size(); j++) {
        Attribute attribute = buffer.attributes().get(j);
        MemorySegment a = attributes.asSlice(j * VertexAttribute.LAYOUT.byteSize());
        a.set(JAVA_INT, VertexAttribute.FORMAT, attribute.format().value);
        a.set(JAVA_LONG, VertexAttribute.OFFSET, attribute.offset());
        a.set(JAVA_INT, VertexAttribute.SHADER_LOCATION, attribute.shaderLocation());
      }
      b.set(ADDRESS, VertexBufferLayout.ATTRIBUTES, attributes);
    }
    return array;
  }

  private static MemorySegment targets(List<ColorTarget> targets, Arena arena) {
    MemorySegment array = arena.allocate(ColorTargetState.LAYOUT, targets.size());
    for (int i = 0; i < targets.size(); i++) {
      ColorTarget target = targets.get(i);
      MemorySegment t = array.asSlice(i * ColorTargetState.LAYOUT.byteSize());
      t.set(JAVA_INT, ColorTargetState.FORMAT, target.format().value);
      t.set(JAVA_LONG, ColorTargetState.WRITE_MASK, Wgpu.COLOR_WRITE_MASK_ALL);
      if (target.blend() != null) {
        MemorySegment blend = arena.allocate(BlendState.LAYOUT);
        component(blend, BlendState.COLOR, target.blend().color());
        component(blend, BlendState.ALPHA, target.blend().alpha());
        t.set(ADDRESS, ColorTargetState.BLEND, blend);
      }
    }
    return array;
  }

  private static void component(MemorySegment blend, long offset, BlendComponent c) {
    blend.set(JAVA_INT, offset + Structs.BlendComponent.OPERATION, c.operation().value);
    blend.set(JAVA_INT, offset + Structs.BlendComponent.SRC_FACTOR, c.srcFactor().value);
    blend.set(JAVA_INT, offset + Structs.BlendComponent.DST_FACTOR, c.dstFactor().value);
  }

  @Override
  String kind() {
    return "Render pipeline";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.renderPipelineRelease(handle);
  }
}
