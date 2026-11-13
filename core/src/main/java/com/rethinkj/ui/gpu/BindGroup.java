package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;

/**
 * Buffers and texture views bound to the bindings of a {@link BindGroupLayout}, set with one call
 * in a pass.
 */
public final class BindGroup extends GpuObject {

  /**
   * A range of {@code buffer}, or a texture view, at {@code @binding(binding)}: exactly one of the
   * two. An entry with neither ends the process in wgpu-native.
   *
   * @param size the size of the range in bytes, or -1 for the rest of the buffer; -1 for a view
   */
  public record Entry(int binding, Buffer buffer, long offset, long size, TextureView view) {
    public Entry {
      if (binding < 0
          || (buffer == null) == (view == null)
          || buffer != null && (offset < 0 || size < -1 || size == 0)
          || view != null && (offset != 0 || size != Wgpu.WHOLE_SIZE)) {
        throw new IllegalArgumentException("A bind group entry needs a binding number of 0 or"
            + " more and either a buffer, an offset of 0 or more and a size above 0 or -1, or a"
            + " texture view: " + binding + ", " + buffer + ", " + offset + ", " + size + ", "
            + view);
      }
    }

    /** All of {@code buffer}. */
    public static Entry buffer(int binding, Buffer buffer) {
      return new Entry(binding, buffer, 0, Wgpu.WHOLE_SIZE, null);
    }

    /** A view, for a binding of a {@link BindGroupLayout.TextureType}. */
    public static Entry texture(int binding, TextureView view) {
      return new Entry(binding, null, 0, Wgpu.WHOLE_SIZE, view);
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
