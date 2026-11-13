package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;
import java.util.Set;

/**
 * Recorded commands without errors, ready for {@link Queue#submit}, which closes it. It can be
 * submitted once.
 */
public final class CommandBuffer extends GpuObject {

  private final Set<SurfaceFrame> frames;

  CommandBuffer(Device device, String label, MemorySegment handle, Set<SurfaceFrame> frames) {
    super(device.instance, device, label, handle);
    this.frames = frames;
  }

  /** Refuses to submit commands that draw into a frame that was presented or given back. */
  void checkFrames() {
    for (SurfaceFrame frame : frames) {
      if (!frame.isHeld()) {
        throw new IllegalStateException("Cannot submit " + this + ": it draws into " + frame
            + ", which is " + (frame.isPresented() ? "presented" : "closed") + " already.");
      }
    }
  }

  @Override
  String kind() {
    return "Command buffer";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.commandBufferRelease(handle);
  }
}
