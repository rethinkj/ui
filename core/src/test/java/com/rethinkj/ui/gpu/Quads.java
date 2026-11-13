package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * A small instanced-quad renderer for the tests: rectangles in pixels with a premultiplied
 * colour each, drawn as triangle strips of four vertices, one instance per rectangle.
 */
final class Quads implements AutoCloseable {

  static final String WGSL = """
      struct Globals {
        size: vec2f,
        unused: vec2f,
      }

      @group(0) @binding(0) var<uniform> globals: Globals;

      struct Quad {
        @location(0) rect: vec4f,
        @location(1) color: vec4f,
      }

      struct Out {
        @builtin(position) position: vec4f,
        @location(0) color: vec4f,
      }

      @vertex
      fn vs_main(@builtin(vertex_index) index: u32, quad: Quad) -> Out {
        let corner = vec2f(f32(index & 1u), f32(index >> 1u));
        let pixel = quad.rect.xy + corner * quad.rect.zw;
        var out: Out;
        out.position = vec4f(pixel / globals.size * vec2f(2.0, -2.0) + vec2f(-1.0, 1.0), 0.0, 1.0);
        out.color = quad.color;
        return out;
      }

      @fragment
      fn fs_main(in: Out) -> @location(0) vec4f {
        return in.color;
      }
      """;

  /** Bytes per instance: x, y, width, height, r, g, b, a. */
  static final int STRIDE = 8 * Float.BYTES;

  final Device device;
  final ShaderModule module;
  final BindGroupLayout layout;
  final Buffer globals;
  final BindGroup group;
  final Buffer instances;

  Quads(Device device, int capacity) {
    this.device = device;
    module = device.createShaderModule("quads", WGSL);
    layout = device.createBindGroupLayout(
        "globals", BindGroupLayout.Entry.uniform(0, ShaderStage.VERTEX));
    globals = device.createBuffer("globals", 16, BufferUsage.UNIFORM, BufferUsage.COPY_DST);
    group = device.createBindGroup("globals", layout, BindGroup.Entry.buffer(0, globals));
    instances = device.createBuffer(
        "quads", (long) capacity * STRIDE, BufferUsage.VERTEX, BufferUsage.COPY_DST);
  }

  /** A pipeline that draws the quads into a target of {@code format}. */
  RenderPipeline pipeline(String label, TextureFormat format, String vertexEntryPoint) {
    return device.createRenderPipeline(new RenderPipeline.Descriptor(
        label,
        List.of(layout),
        module,
        vertexEntryPoint,
        List.of(new RenderPipeline.VertexBuffer(
            STRIDE,
            RenderPipeline.StepMode.INSTANCE,
            List.of(
                new RenderPipeline.Attribute(VertexFormat.FLOAT32X4, 0, 0),
                new RenderPipeline.Attribute(VertexFormat.FLOAT32X4, 16, 1)))),
        "fs_main",
        List.of(new RenderPipeline.ColorTarget(format, RenderPipeline.Blend.PREMULTIPLIED_ALPHA)),
        RenderPipeline.Topology.TRIANGLE_STRIP));
  }

  /** Uploads the target size and the quads, eight floats each. */
  void upload(int width, int height, float[] quads) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment size = arena.allocate(16, 4);
      size.setAtIndex(JAVA_FLOAT, 0, width);
      size.setAtIndex(JAVA_FLOAT, 1, height);
      device.queue().writeBuffer(globals, 0, size);
      device.queue().writeBuffer(instances, 0, MemorySegment.ofArray(quads));
    }
  }

  /** Records a pass that clears {@code target} and draws {@code count} quads. */
  void draw(
      CommandEncoder encoder, TextureView target, Color clear, RenderPipeline pipeline, int count) {
    try (RenderPass pass = encoder.beginRenderPass("quads", target, clear)) {
      pass.setPipeline(pipeline);
      pass.setBindGroup(0, group);
      pass.setVertexBuffer(0, instances, 0, (long) count * STRIDE);
      pass.draw(4, count, 0, 0);
    }
  }

  @Override
  public void close() {
    instances.close();
    group.close();
    globals.close();
    layout.close();
    module.close();
  }
}
