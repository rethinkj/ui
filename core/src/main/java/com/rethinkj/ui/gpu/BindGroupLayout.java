package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The bindings a group of a pipeline has: here, uniform buffers. */
public final class BindGroupLayout extends GpuObject {

  /**
   * A uniform buffer at {@code @binding(binding)}, seen by the given stages.
   *
   * @param minBindingSize the least size a bound range must have, or 0 to have wgpu check it
   *     against the shader on every draw
   */
  public record Entry(int binding, Set<ShaderStage> visibility, long minBindingSize) {
    public Entry {
      visibility = Set.copyOf(visibility);
      if (binding < 0 || visibility.isEmpty() || minBindingSize < 0) {
        throw new IllegalArgumentException("A binding needs a binding number of 0 or more, at"
            + " least one stage and a minimum size of 0 or more: " + binding + ", " + visibility
            + ", " + minBindingSize);
      }
    }

    public static Entry uniform(int binding, ShaderStage stage, ShaderStage... more) {
      return new Entry(binding, EnumSet.of(stage, more), 0);
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
