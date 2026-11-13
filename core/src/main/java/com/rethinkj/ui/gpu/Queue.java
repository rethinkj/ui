package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** The queue of a {@link Device}: uploads to buffers and runs command buffers. */
public final class Queue {

  private final Device device;
  final MemorySegment handle;
  private boolean released;

  Queue(Device device, MemorySegment handle) {
    this.device = device;
    this.handle = handle;
  }

  /**
   * Copies {@code data} into {@code buffer} at {@code offset}, in queue order: before the work of
   * the next {@link #submit}.
   *
   * @param offset a multiple of 4
   * @param data a multiple of 4 bytes; may be on the Java heap
   */
  public void writeBuffer(Buffer buffer, long offset, MemorySegment data) {
    MemorySegment queue = usable();
    MemorySegment target = device.owned(buffer).handle();
    long size = data.byteSize();
    if (!buffer.usages().contains(BufferUsage.COPY_DST)) {
      throw new IllegalArgumentException(buffer + " was not created with BufferUsage.COPY_DST.");
    }
    if (offset < 0 || offset % 4 != 0 || size % 4 != 0 || offset + size > buffer.size()) {
      throw new IllegalArgumentException("Cannot write " + size + " bytes at " + offset + " into "
          + buffer + " of " + buffer.size() + " bytes: offset and size must be multiples of 4"
          + " and fit into the buffer.");
    }
    if (size == 0) {
      return;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment from = data;
      if (!data.isNative()) {
        // A downcall takes only native memory.
        from = arena.allocate(size, 8).copyFrom(data);
      }
      MemorySegment bytes = from;
      device.run("writing buffer", buffer.label(),
          () -> Wgpu.queueWriteBuffer(queue, target, offset, bytes, size));
    }
  }

  /**
   * Runs the commands. The command buffer is closed afterwards; it can be submitted once.
   *
   * @throws IllegalStateException if it was submitted before, or draws into a surface frame that
   *     was presented or given back since; wgpu-native would end the process
   */
  public void submit(CommandBuffer commands) {
    MemorySegment queue = usable();
    MemorySegment buffer = device.owned(commands).handle();
    commands.checkFrames();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment array = arena.allocate(ADDRESS);
      array.set(ADDRESS, 0, buffer);
      Wgpu.queueSubmit(queue, 1, array);
    }
    commands.closeAs("submitted");
  }

  private MemorySegment usable() {
    device.usable();
    if (released) {
      throw new IllegalStateException("The queue of " + device + " is closed.");
    }
    return handle;
  }

  void released() {
    released = true;
  }
}
