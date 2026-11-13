package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;

/** Buffers bound to the bindings of a {@link BindGroupLayout}, set with one call in a pass. */
public final class BindGroup extends GpuObject {

  /**
   * A range of {@code buffer} at {@code @binding(binding)}.
   *
   * @param size the size of the range in bytes, or -1 for the rest of the buffer
   */
  public record Entry(int binding, Buffer buffer, long offset, long size) {
    public Entry {
      if (binding < 0 || offset < 0 || size < -1 || size == 0 || buffer == null) {
        throw new IllegalArgumentException("A bind group entry needs a binding number of 0 or"
            + " more, a buffer, an offset of 0 or more and a size above 0 or -1: " + binding
            + ", " + buffer + ", " + offset + ", " + size);
      }
    }

    /** All of {@code buffer}. */
    public static Entry buffer(int binding, Buffer buffer) {
      return new Entry(binding, buffer, 0, Wgpu.WHOLE_SIZE);
    }
  }

  BindGroup(Device device, String label, MemorySegment handle) {
    super(device.instance, device, label, handle);
  }

  @Override
  String kind() {
    return "Bind group";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.bindGroupRelease(handle);
  }
}
