package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The bindings a group of a pipeline has: here, uniform and read-only storage buffers. */
public final class BindGroupLayout extends GpuObject {

  /** {@code WGPUBufferBindingType}: how the shader sees a bound buffer. */
  public enum BufferType {
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
   * A buffer at {@code @binding(binding)}, seen by the given stages.
   *
   * @param minBindingSize the least size a bound range must have, or 0 to have wgpu check it
   *     against the shader on every draw
   */
  public record Entry(int binding, BufferType type, Set<ShaderStage> visibility,
      long minBindingSize) {
    public Entry {
      Objects.requireNonNull(type, "type");
      visibility = Set.copyOf(visibility);
      if (binding < 0 || visibility.isEmpty() || minBindingSize < 0) {
        throw new IllegalArgumentException("A binding needs a binding number of 0 or more, at"
            + " least one stage and a minimum size of 0 or more: " + binding + ", " + visibility
            + ", " + minBindingSize);
      }
    }

    public static Entry uniform(int binding, ShaderStage stage, ShaderStage... more) {
      return new Entry(binding, BufferType.UNIFORM, EnumSet.of(stage, more), 0);
    }

    public static Entry readOnlyStorage(int binding, ShaderStage stage, ShaderStage... more) {
      return new Entry(binding, BufferType.READ_ONLY_STORAGE, EnumSet.of(stage, more), 0);
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
