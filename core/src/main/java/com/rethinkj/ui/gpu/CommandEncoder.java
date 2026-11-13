package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.rethinkj.ui.gpu.Structs.CommandBufferDescriptor;
import com.rethinkj.ui.gpu.Structs.RenderPassColorAttachment;
import com.rethinkj.ui.gpu.Structs.RenderPassDescriptor;
import com.rethinkj.ui.gpu.Structs.TexelCopyBufferInfo;
import com.rethinkj.ui.gpu.Structs.TexelCopyTextureInfo;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Records render passes and copies into a {@link CommandBuffer}.
 *
 * <p>wgpu reports what is wrong with the recorded commands when the encoder is finished, and
 * wgpu-native ends the process when such a command buffer is submitted. So the encoder records
 * inside error scopes from its creation on, and {@link #finish} throws {@link GpuException}
 * instead of returning a command buffer if they caught anything.
 *
 * <pre>{@code
 * try (CommandEncoder encoder = device.createCommandEncoder("frame")) {
 *   try (RenderPass pass = encoder.beginRenderPass("scene", view, Color.BLACK)) {
 *     pass.setPipeline(pipeline);
 *     pass.draw(4, count, 0, 0);
 *   }
 *   device.queue().submit(encoder.finish());
 * }
 * }</pre>
 */
public final class CommandEncoder extends GpuObject {

  private final Device device;
  private RenderPass pass;
  private boolean finished;
  /** The surface frames the commands draw into; they must still be held when submitted. */
  private final Set<SurfaceFrame> frames = new LinkedHashSet<>();

  CommandEncoder(Device device, String label, MemorySegment handle) {
    super(device.instance, device, label, handle);
    this.device = device;
  }

  /**
   * Begins a render pass into {@code target} that clears it to {@code clear} first, or keeps what
   * it holds if {@code clear} is null. Only one pass can be open at a time.
   */
  public RenderPass beginRenderPass(String label, TextureView target, Color clear) {
    requireLabel(label);
    MemorySegment encoder = recording();
    MemorySegment view = device.owned(target).handle();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment attachment = RenderPassColorAttachment.allocate(arena);
      attachment.set(ADDRESS, RenderPassColorAttachment.VIEW, view);
      attachment.set(JAVA_INT, RenderPassColorAttachment.LOAD_OP,
          clear == null ? Wgpu.LOAD_OP_LOAD : Wgpu.LOAD_OP_CLEAR);
      attachment.set(JAVA_INT, RenderPassColorAttachment.STORE_OP, Wgpu.STORE_OP_STORE);
      if (clear != null) {
        long c = RenderPassColorAttachment.CLEAR_VALUE;
        attachment.set(JAVA_DOUBLE, c, clear.r());
        attachment.set(JAVA_DOUBLE, c + 8, clear.g());
        attachment.set(JAVA_DOUBLE, c + 16, clear.b());
        attachment.set(JAVA_DOUBLE, c + 24, clear.a());
      }
      MemorySegment descriptor = arena.allocate(RenderPassDescriptor.LAYOUT);
      Structs.string(descriptor, RenderPassDescriptor.LABEL, label, arena);
      descriptor.set(JAVA_LONG, RenderPassDescriptor.COLOR_ATTACHMENT_COUNT, 1);
      descriptor.set(ADDRESS, RenderPassDescriptor.COLOR_ATTACHMENTS, attachment);
      MemorySegment handle = Wgpu.commandEncoderBeginRenderPass(encoder, descriptor);
      if (target.frame != null) {
        frames.add(target.frame);
      }
      pass = new RenderPass(this, device, label, handle, target.width(), target.height());
      return pass;
    }
  }

  /**
   * Copies all of {@code source} into {@code destination} from {@code offset} on, one row every
   * {@code bytesPerRow} bytes.
   *
   * @param bytesPerRow a multiple of 256 that holds a row of the texture
   */
  public void copyTextureToBuffer(Texture source, Buffer destination, long offset,
      int bytesPerRow) {
    MemorySegment encoder = recording();
    MemorySegment texture = device.owned(source).handle();
    MemorySegment buffer = device.owned(destination).handle();
    if (bytesPerRow <= 0 || bytesPerRow % 256 != 0) {
      throw new IllegalArgumentException("bytesPerRow is " + bytesPerRow + "; wgpu needs a"
          + " positive multiple of 256.");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment from = arena.allocate(TexelCopyTextureInfo.LAYOUT);
      from.set(ADDRESS, TexelCopyTextureInfo.TEXTURE, texture);
      MemorySegment to = TexelCopyBufferInfo.allocate(arena);
      to.set(JAVA_LONG, TexelCopyBufferInfo.OFFSET, offset);
      to.set(JAVA_INT, TexelCopyBufferInfo.BYTES_PER_ROW, bytesPerRow);
      to.set(JAVA_INT, TexelCopyBufferInfo.ROWS_PER_IMAGE, source.height());
      to.set(ADDRESS, TexelCopyBufferInfo.BUFFER, buffer);
      MemorySegment size = arena.allocate(Structs.Extent3D.LAYOUT);
      Structs.Extent3D.set(size, 0, source.width(), source.height());
      Wgpu.commandEncoderCopyTextureToBuffer(encoder, from, to, size);
    }
  }

  /**
   * Finishes recording. The encoder can only be closed afterwards.
   *
   * @throws GpuException if wgpu found anything wrong with the recorded commands; there is then
   *     no command buffer to submit
   */
  public CommandBuffer finish() {
    MemorySegment encoder = recording();
    finished = true;
    device.finishedRecording(this);
    MemorySegment commands;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment descriptor = arena.allocate(CommandBufferDescriptor.LAYOUT);
      Structs.string(descriptor, CommandBufferDescriptor.LABEL, label(), arena);
      commands = Wgpu.commandEncoderFinish(encoder, descriptor);
    } catch (RuntimeException | Error e) {
      device.popScopes("recording commands", label());
      throw e;
    }
    GpuException error;
    try {
      error = device.popScopes("recording commands", label());
    } catch (RuntimeException | Error e) {
      Wgpu.commandBufferRelease(commands);
      throw e;
    }
    if (error != null) {
      Wgpu.commandBufferRelease(commands);
      throw error;
    }
    return new CommandBuffer(device, label(), commands, Set.copyOf(frames));
  }

  /** The encoder's handle, if it can still record. */
  private MemorySegment recording() {
    MemorySegment encoder = handle();
    device.usable();
    if (finished) {
      throw new IllegalStateException(this + " is finished; it cannot record any more.");
    }
    if (pass != null) {
      throw new IllegalStateException(pass + " is open; the encoder records only into it until"
          + " it ends.");
    }
    return encoder;
  }

  void passEnded(RenderPass ended) {
    if (pass == ended) {
      pass = null;
    }
  }

  @Override
  void beforeClose() {
    if (!finished) {
      // Closes the pass first, then gives up the scopes and what they caught.
      if (pass != null) {
        pass.closeAs("closed with its command encoder '" + label() + "'");
      }
      finished = true;
      device.finishedRecording(this);
      device.popScopes("recording commands", label());
    }
  }

  @Override
  String kind() {
    return "Command encoder";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.commandEncoderRelease(handle);
  }
}
