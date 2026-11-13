package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.rethinkj.ui.gpu.Structs.TextureViewDescriptor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Set;

/**
 * A 2D texture, or an array of 2D layers of the same size, for drawing off screen or for a shader
 * to read. Drawn into through a {@link TextureView}, written with {@link Queue#writeTexture}.
 */
public final class Texture extends GpuObject {

  final Device device;
  private final int width;
  private final int height;
  private final int layers;
  private final boolean array;
  private final TextureFormat format;
  private final Set<TextureUsage> usages;

  Texture(Device device, GpuObject parent, String label, MemorySegment handle, int width,
      int height, int layers, boolean array, TextureFormat format, Set<TextureUsage> usages) {
    super(device.instance, parent, label, handle);
    this.device = device;
    this.width = width;
    this.height = height;
    this.layers = layers;
    this.array = array;
    this.format = format;
    this.usages = Collections.unmodifiableSet(usages);
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  /** The number of array layers: 1 for a plain 2D texture. */
  public int layers() {
    return layers;
  }

  /**
   * True for a texture of {@link Device#createTextureArray}, whose views are 2D arrays even with
   * one layer.
   */
  public boolean isArray() {
    return array;
  }

  public TextureFormat format() {
    return format;
  }

  public Set<TextureUsage> usages() {
    return usages;
  }

  /**
   * A view of the whole texture, with its format: a 2D view, or for {@link #isArray} a 2D array
   * view of all layers, which a shader reads as {@code texture_2d_array}.
   */
  public TextureView createView(String label) {
    requireLabel(label);
    return createView(this, device, handle(), label, width, height, format, array, null);
  }

  /** A view of all mip levels and layers of a texture, for this class and surface frames. */
  static TextureView createView(GpuObject owner, Device device, MemorySegment texture,
      String label, int width, int height, TextureFormat format, boolean array,
      SurfaceFrame frame) {
    device.usable();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment descriptor = TextureViewDescriptor.allocate(arena);
      Structs.string(descriptor, TextureViewDescriptor.LABEL, label, arena);
      if (array) {
        // Undefined would be 2D for a texture of one layer.
        descriptor.set(JAVA_INT, TextureViewDescriptor.DIMENSION,
            Wgpu.TEXTURE_VIEW_DIMENSION_2D_ARRAY);
      }
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
