package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.MemorySegment;

/** The limits of an adapter or a device that the layer checks or a renderer needs to know. */
public record Limits(
    int maxTextureDimension2D,
    int maxTextureArrayLayers,
    int maxBindGroups,
    long maxUniformBufferBindingSize,
    int minUniformBufferOffsetAlignment,
    int maxStorageBuffersPerShaderStage,
    long maxStorageBufferBindingSize,
    int minStorageBufferOffsetAlignment,
    int maxVertexBuffers,
    long maxBufferSize,
    int maxVertexAttributes,
    int maxVertexBufferArrayStride,
    int maxColorAttachments) {

  /** Reads a {@code WGPULimits} that wgpu filled in. */
  static Limits read(MemorySegment s) {
    return new Limits(
        s.get(JAVA_INT, Structs.Limits.MAX_TEXTURE_DIMENSION_2D),
        s.get(JAVA_INT, Structs.Limits.MAX_TEXTURE_ARRAY_LAYERS),
        s.get(JAVA_INT, Structs.Limits.MAX_BIND_GROUPS),
        s.get(JAVA_LONG, Structs.Limits.MAX_UNIFORM_BUFFER_BINDING_SIZE),
        s.get(JAVA_INT, Structs.Limits.MIN_UNIFORM_BUFFER_OFFSET_ALIGNMENT),
        s.get(JAVA_INT, Structs.Limits.MAX_STORAGE_BUFFERS_PER_SHADER_STAGE),
        s.get(JAVA_LONG, Structs.Limits.MAX_STORAGE_BUFFER_BINDING_SIZE),
        s.get(JAVA_INT, Structs.Limits.MIN_STORAGE_BUFFER_OFFSET_ALIGNMENT),
        s.get(JAVA_INT, Structs.Limits.MAX_VERTEX_BUFFERS),
        s.get(JAVA_LONG, Structs.Limits.MAX_BUFFER_SIZE),
        s.get(JAVA_INT, Structs.Limits.MAX_VERTEX_ATTRIBUTES),
        s.get(JAVA_INT, Structs.Limits.MAX_VERTEX_BUFFER_ARRAY_STRIDE),
        s.get(JAVA_INT, Structs.Limits.MAX_COLOR_ATTACHMENTS));
  }
}
