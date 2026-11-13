package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;

/**
 * A render pass into one colour target. Commands are checked when the encoder is finished;
 * the checks here only keep wgpu-native from ending the process, for example for a vertex buffer
 * range of size 0. {@link #close} ends the pass if {@link #end} was not called.
 */
public final class RenderPass extends GpuObject {

  private final CommandEncoder encoder;
  private final Device device;
  private final int width;
  private final int height;
  private boolean ended;

  RenderPass(
      CommandEncoder encoder,
      Device device,
      String label,
      MemorySegment handle,
      int width,
      int height) {
    super(device.instance, encoder, label, handle);
    this.encoder = encoder;
    this.device = device;
    this.width = width;
    this.height = height;
  }

  public void setPipeline(RenderPipeline pipeline) {
    Wgpu.renderPassSetPipeline(recording(), device.owned(pipeline).handle());
  }

  /** Sets {@code @group(index)}. */
  public void setBindGroup(int index, BindGroup group) {
    if (index < 0) {
      throw new IllegalArgumentException("Bind group index " + index);
    }
    Wgpu.renderPassSetBindGroup(recording(), index, device.owned(group).handle());
  }

  /** Binds all of {@code buffer} to vertex buffer slot {@code slot}. */
  public void setVertexBuffer(int slot, Buffer buffer) {
    setVertexBuffer(slot, buffer, 0, buffer.size());
  }

  /** Binds {@code size} bytes of {@code buffer} from {@code offset} on to slot {@code slot}. */
  public void setVertexBuffer(int slot, Buffer buffer, long offset, long size) {
    MemorySegment pass = recording();
    MemorySegment handle = device.owned(buffer).handle();
    // A size of 0 ends the process in wgpu-native.
    if (slot < 0 || offset < 0 || size <= 0 || size > buffer.size() - offset) {
      throw new IllegalArgumentException("Cannot bind " + size + " bytes at " + offset + " of "
          + buffer + " (" + buffer.size() + " bytes) to vertex buffer slot " + slot + ".");
    }
    Wgpu.renderPassSetVertexBuffer(pass, slot, handle, offset, size);
  }

  /** Limits drawing to a rectangle of the target, in pixels. */
  public void setScissorRect(int x, int y, int width, int height) {
    MemorySegment pass = recording();
    if (x < 0
        || y < 0
        || width < 0
        || height < 0
        || (long) x + width > this.width
        || (long) y + height > this.height) {
      throw new IllegalArgumentException("The scissor rectangle " + x + "," + y + " " + width
          + "x" + height + " does not fit into the target of " + this.width + "x" + this.height
          + ".");
    }
    Wgpu.renderPassSetScissorRect(pass, x, y, width, height);
  }

  /** Maps clip space to a rectangle of the target, in pixels, and depth to 0 to 1. */
  public void setViewport(float x, float y, float width, float height) {
    Wgpu.renderPassSetViewport(recording(), x, y, width, height, 0, 1);
  }

  public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
    MemorySegment pass = recording();
    if (vertexCount < 0 || instanceCount < 0 || firstVertex < 0 || firstInstance < 0) {
      throw new IllegalArgumentException("Negative draw arguments: " + vertexCount + ", "
          + instanceCount + ", " + firstVertex + ", " + firstInstance);
    }
    Wgpu.renderPassDraw(pass, vertexCount, instanceCount, firstVertex, firstInstance);
  }

  /** Ends the pass; the encoder can record again afterwards. */
  public void end() {
    MemorySegment pass = recording();
    ended = true;
    encoder.passEnded(this);
    Wgpu.renderPassEnd(pass);
  }

  private MemorySegment recording() {
    MemorySegment pass = handle();
    if (ended) {
      throw new IllegalStateException(this + " has ended.");
    }
    return pass;
  }

  @Override
  void beforeClose() {
    if (!ended) {
      ended = true;
      encoder.passEnded(this);
      Wgpu.renderPassEnd(handle());
    }
  }

  @Override
  String kind() {
    return "Render pass";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.renderPassRelease(handle);
  }
}
