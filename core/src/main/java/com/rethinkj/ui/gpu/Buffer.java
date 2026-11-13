package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import com.rethinkj.ui.gpu.Structs.CallbackInfo;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Set;

/**
 * GPU memory: vertices, uniforms, or the target of a copy to read back. Written with {@link
 * Queue#writeBuffer}; read with {@link #read}, which maps, copies and unmaps in one call, so a
 * buffer is never mapped while commands that use it are submitted. wgpu-native ends the process
 * for that.
 */
public final class Buffer extends GpuObject {

  /** {@code WGPU_MAP_ALIGNMENT}: where a mapped range may start. */
  private static final long MAP_ALIGNMENT = 8;
  /** How often {@link #read} waits for the GPU before it gives up on the map callback. */
  private static final int MAP_POLLS = 100;

  final Device device;
  private final long size;
  private final Set<BufferUsage> usages;

  Buffer(Device device, String label, MemorySegment handle, long size, Set<BufferUsage> usages) {
    super(device.instance, device, label, handle);
    this.device = device;
    this.size = size;
    this.usages = Collections.unmodifiableSet(usages);
  }

  /** The size in bytes. */
  public long size() {
    return size;
  }

  public Set<BufferUsage> usages() {
    return usages;
  }

  /** Copies {@code length} bytes from {@code offset} into a new array; see {@link #read}. */
  public byte[] read(long offset, int length) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment into = arena.allocate(length);
      read(offset, into);
      return into.toArray(JAVA_BYTE);
    }
  }

  /**
   * Waits until the GPU has done the work submitted so far and copies {@code into.byteSize()}
   * bytes from {@code offset} of this buffer into {@code into}.
   *
   * @param offset a multiple of 8
   * @param into a multiple of 4 bytes, writable, on the heap or native
   * @throws IllegalArgumentException if the buffer was not created with {@link
   *     BufferUsage#MAP_READ}, or the range does not fit
   * @throws GpuException if wgpu cannot map it
   */
  public void read(long offset, MemorySegment into) {
    MemorySegment buffer = handle();
    long length = into.byteSize();
    if (!usages.contains(BufferUsage.MAP_READ)) {
      throw new IllegalArgumentException(this + " was not created with BufferUsage.MAP_READ.");
    }
    if (offset < 0
        || offset % MAP_ALIGNMENT != 0
        || length <= 0
        || length % 4 != 0
        || length > size - offset) {
      throw new IllegalArgumentException("Cannot read " + length + " bytes at " + offset
          + " from " + this + " of " + size + " bytes: the offset must be a multiple of 8, the"
          + " length a positive multiple of 4, and both must fit into the buffer.");
    }
    if (into.isReadOnly()) {
      throw new IllegalArgumentException("The segment to read " + this + " into is read-only.");
    }
    Callbacks.MapRequest map = new Callbacks.MapRequest();
    long id = Callbacks.register(map);
    boolean requested = false;
    try {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment info = CallbackInfo.allocate(arena, Callbacks.BUFFER_MAP, id);
        device.run(
            "mapping buffer",
            label(),
            () -> Wgpu.bufferMapAsync(arena, buffer, Wgpu.MAP_MODE_READ, offset, length, info));
      }
      requested = true;
      for (int i = 0; i < MAP_POLLS && !map.done; i++) {
        device.poll(true);
      }
      map.rethrowFailure("mapping " + this);
      if (!map.done) {
        throw new IllegalStateException("wgpu did not report whether it mapped " + this
            + " after waiting for the GPU " + MAP_POLLS + " times.");
      }
      if (map.status != Wgpu.MAP_ASYNC_STATUS_SUCCESS) {
        throw new GpuException(
            GpuException.Kind.MAP_FAILED, "mapping buffer", label(), map.message);
      }
      // The exact range that was mapped: any other range ends the process.
      MemorySegment mapped = Wgpu.bufferGetConstMappedRange(buffer, offset, length);
      if (mapped.equals(MemorySegment.NULL)) {
        throw new IllegalStateException("wgpuBufferGetConstMappedRange returned NULL for " + this);
      }
      MemorySegment.copy(mapped.reinterpret(length), 0, into, 0, length);
    } finally {
      Callbacks.unregister(id);
      // Mapped, or still pending, which unmapping cancels: either way the buffer must not stay
      // mapped, or the next submission that uses it ends the process.
      if (requested && (!map.done || map.status == Wgpu.MAP_ASYNC_STATUS_SUCCESS)) {
        device.run("unmapping buffer", label(), () -> Wgpu.bufferUnmap(buffer));
      }
    }
  }

  @Override
  String kind() {
    return "Buffer";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.bufferRelease(handle);
  }
}
