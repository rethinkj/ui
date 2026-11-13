package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;

/**
 * The texture of one frame of a {@link Surface}: draw into {@link #view}, submit, then {@link
 * #present}. Closing a frame that was not presented gives its texture back to the surface. The
 * surface closes the previous frame when it hands out the next one.
 */
public final class SurfaceFrame extends GpuObject {

  private final Surface surface;
  private final Device device;
  private final int width;
  private final int height;
  private final TextureFormat format;
  private final boolean suboptimal;
  private TextureView view;
  private boolean presented;

  SurfaceFrame(Surface surface, Device device, String label, MemorySegment texture, int width,
      int height, TextureFormat format, boolean suboptimal) {
    super(surface.instance, surface, label, texture);
    this.surface = surface;
    this.device = device;
    this.width = width;
    this.height = height;
    this.format = format;
    this.suboptimal = suboptimal;
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

  /**
   * True if the surface still presents this frame but would prefer to be configured again, for
   * example with the window's new size.
   */
  public boolean isSuboptimal() {
    return suboptimal;
  }

  /** The view to draw into; the same one on every call. */
  public TextureView view() {
    MemorySegment texture = held();
    if (view == null) {
      view = Texture.createView(this, device, texture, label() + " view", width, height, format,
          false, this);
    }
    return view;
  }

  /**
   * Shows the frame. Submit the commands that draw it first: commands that draw into a presented
   * frame cannot be submitted.
   *
   * @throws GpuException of kind {@link GpuException.Kind#PRESENT_FAILED} if the surface could
   *     not present; wgpu logs why
   */
  public void present() {
    held();
    device.usable();
    presented = true;
    if (Wgpu.surfacePresent(surface.handle()) != Wgpu.STATUS_SUCCESS) {
      throw new GpuException(GpuException.Kind.PRESENT_FAILED, "presenting", label(), "");
    }
  }

  /** True while the frame can be drawn into: open and not presented. */
  boolean isHeld() {
    return !isClosed() && !presented;
  }

  boolean isPresented() {
    return presented;
  }

  private MemorySegment held() {
    MemorySegment texture = handle();
    if (presented) {
      throw new IllegalStateException(this + " is presented; acquire the next frame.");
    }
    return texture;
  }

  @Override
  String kind() {
    return "Surface frame";
  }

  @Override
  void release(MemorySegment handle) {
    surface.frameClosed(this);
    // For a frame that was not presented, this gives the texture back to the surface.
    Wgpu.textureRelease(handle);
  }
}
