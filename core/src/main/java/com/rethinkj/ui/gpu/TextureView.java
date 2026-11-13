package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;

/**
 * A view of a {@link Texture} or of a {@link SurfaceFrame}: what a render pass draws into, or what
 * a bind group gives a shader to read.
 */
public final class TextureView extends GpuObject {

  final Device device;
  private final int width;
  private final int height;
  private final TextureFormat format;
  /** The surface frame this is a view of, or null. */
  final SurfaceFrame frame;

  TextureView(GpuObject texture, Device device, String label, MemorySegment handle, int width,
      int height, TextureFormat format, SurfaceFrame frame) {
    super(device.instance, texture, label, handle);
    this.device = device;
    this.width = width;
    this.height = height;
    this.format = format;
    this.frame = frame;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public TextureFormat format() {
    return format;
  }

  @Override
  String kind() {
    return "Texture view";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.textureViewRelease(handle);
  }
}
