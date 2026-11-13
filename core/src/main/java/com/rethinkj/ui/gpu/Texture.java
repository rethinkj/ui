package com.rethinkj.ui.gpu;

import com.rethinkj.ui.gpu.Structs.TextureViewDescriptor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Set;

/** A 2D texture, for drawing off screen or sampling. Drawn into through a {@link TextureView}. */
public final class Texture extends GpuObject {

  final Device device;
  private final int width;
  private final int height;
  private final TextureFormat format;
  private final Set<TextureUsage> usages;

  Texture(Device device, GpuObject parent, String label, MemorySegment handle, int width,
      int height, TextureFormat format, Set<TextureUsage> usages) {
    super(device.instance, parent, label, handle);
    this.device = device;
    this.width = width;
    this.height = height;
    this.format = format;
    this.usages = Collections.unmodifiableSet(usages);
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

  public Set<TextureUsage> usages() {
    return usages;
  }

  /** A view of the whole texture, with its format. */
  public TextureView createView(String label) {
    requireLabel(label);
    return createView(this, device, handle(), label, width, height, format, null);
  }

  /** A view of all mip levels and layers of a texture, for this class and surface frames. */
  static TextureView createView(GpuObject owner, Device device, MemorySegment texture,
      String label, int width, int height, TextureFormat format, SurfaceFrame frame) {
    device.usable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment descriptor = TextureViewDescriptor.allocate(arena);
      Structs.string(descriptor, TextureViewDescriptor.LABEL, label, arena);
      MemorySegment view = device.create("creating texture view", label,
          () -> Wgpu.textureCreateView(texture, descriptor), Wgpu::textureViewRelease);
      return new TextureView(owner, device, label, view, width, height, format, frame);
    }
  }

  @Override
  String kind() {
    return "Texture";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.textureRelease(handle);
  }
}
