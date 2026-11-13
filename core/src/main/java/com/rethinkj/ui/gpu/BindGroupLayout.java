package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The bindings a group of a pipeline has: here, uniform and read-only storage buffers, and
 * textures that a shader reads texel by texel.
 */
public final class BindGroupLayout extends GpuObject {

  /** What a binding holds: a {@link BufferType} or a {@link TextureType}. */
  public sealed interface Type permits BufferType, TextureType {}

  /** {@code WGPUBufferBindingType}: how the shader sees a bound buffer. */
  public enum BufferType implements Type {
    /**
     * {@code var<uniform>}: at most {@link Limits#maxUniformBufferBindingSize} bytes; the buffer
     * needs {@link BufferUsage#UNIFORM}.
     */
    UNIFORM(Wgpu.BUFFER_BINDING_TYPE_UNIFORM),
    /**
     * {@code var<storage, read>}: up to {@link Limits#maxStorageBufferBindingSize} bytes, for a
     * runtime-sized array such as {@code array<Instance>}, in the vertex stage too; the buffer
     * needs {@link BufferUsage#STORAGE}.
     */
    READ_ONLY_STORAGE(Wgpu.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE);

    final int value;

    BufferType(int value) {
      this.value = value;
    }
  }

  /**
   * A texture of floats that the shader reads with {@code textureLoad}, one texel at a time and
   * without a sampler. Its sample type is {@code UnfilterableFloat}, which takes every float format,
   * {@link TextureFormat#R8_UNORM} among them; the texture needs {@link
   * TextureUsage#TEXTURE_BINDING}.
   */
  public enum TextureType implements Type {
    /** {@code texture_2d<f32>}: a view of a texture of {@link Device#createTexture}. */
    TEXTURE_2D(Wgpu.TEXTURE_VIEW_DIMENSION_2D),
    /** {@code texture_2d_array<f32>}: a view of a texture of {@link Device#createTextureArray}. */
    TEXTURE_2D_ARRAY(Wgpu.TEXTURE_VIEW_DIMENSION_2D_ARRAY);

    final int viewDimension;

    TextureType(int viewDimension) {
      this.viewDimension = viewDimension;
    }
  }

  /**
   * A buffer or a texture at {@code @binding(binding)}, seen by the given stages.
   *
   * @param minBindingSize for a buffer, the least size a bound range must have, or 0 to have wgpu
   *     check it against the shader on every draw; 0 for a texture
   */
  public record Entry(int binding, Type type, Set<ShaderStage> visibility, long minBindingSize) {
    public Entry {
      Objects.requireNonNull(type, "type");
      visibility = Set.copyOf(visibility);
      if (binding < 0 || visibility.isEmpty() || minBindingSize < 0
          || type instanceof TextureType && minBindingSize != 0) {
        throw new IllegalArgumentException("A binding needs a binding number of 0 or more, at"
            + " least one stage and a minimum size of 0 or more, 0 for a texture: " + binding
            + ", " + type + ", " + visibility + ", " + minBindingSize);
      }
    }

    public static Entry uniform(int binding, ShaderStage stage, ShaderStage... more) {
      return new Entry(binding, BufferType.UNIFORM, EnumSet.of(stage, more), 0);
    }

    public static Entry readOnlyStorage(int binding, ShaderStage stage, ShaderStage... more) {
      return new Entry(binding, BufferType.READ_ONLY_STORAGE, EnumSet.of(stage, more), 0);
    }

    /** A {@link TextureType#TEXTURE_2D}. */
    public static Entry texture(int binding, ShaderStage stage, ShaderStage... more) {
      return new Entry(binding, TextureType.TEXTURE_2D, EnumSet.of(stage, more), 0);
    }

    /** A {@link TextureType#TEXTURE_2D_ARRAY}. */
    public static Entry textureArray(int binding, ShaderStage stage, ShaderStage... more) {
      return new Entry(binding, TextureType.TEXTURE_2D_ARRAY, EnumSet.of(stage, more), 0);
    }
  }

  private final List<Entry> entries;

  BindGroupLayout(Device device, String label, MemorySegment handle, List<Entry> entries) {
    super(device.instance, device, label, handle);
    this.entries = entries;
  }

  public List<Entry> entries() {
    return entries;
  }

  @Override
  String kind() {
    return "Bind group layout";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.bindGroupLayoutRelease(handle);
  }
}
