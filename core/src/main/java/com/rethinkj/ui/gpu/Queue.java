package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** The queue of a {@link Device}: uploads to buffers and textures, and runs command buffers. */
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
    if (offset < 0 || offset % 4 != 0 || size % 4 != 0 || size > buffer.size() - offset) {
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
      device.run(
          "writing buffer",
          buffer.label(),
          () -> Wgpu.queueWriteBuffer(queue, target, offset, bytes, size));
    }
  }

  /**
   * Copies a rectangle of texels from {@code data} into one layer of {@code texture}, in queue
   * order: before the work of the next {@link #submit}. The texels are read from {@code data} in
   * rows of {@code bytesPerRow} bytes, the rectangle's top left texel at {@code offset}; so a
   * region of a larger image in memory is written without copying it out first. A rectangle of
   * width or height 0 writes nothing.
   *
   * @param x the left edge of the rectangle in the texture, in texels
   * @param y its top edge
   * @param layer the array layer, 0 for a plain 2D texture
   * @param data native memory or on the Java heap; only the rows of the rectangle are read
   * @param offset where the rectangle's first row starts in {@code data}
   * @param bytesPerRow the distance between rows in {@code data}, at least a row of the rectangle;
   *     unlike a copy between buffer and texture, it need not be a multiple of 256
   */
  public void writeTexture(
      Texture texture,
      int x,
      int y,
      int layer,
      int width,
      int height,
      MemorySegment data,
      long offset,
      int bytesPerRow) {
    writeTexture(texture, 0, x, y, layer, width, height, data, offset, bytesPerRow);
  }

  /**
   * As {@link #writeTexture(Texture, int, int, int, int, int, MemorySegment, long, int)}, into mip
   * level {@code level}, whose size is {@link Texture#width(int)} by {@link Texture#height(int)}.
   */
  public void writeTexture(
      Texture texture,
      int level,
      int x,
      int y,
      int layer,
      int width,
      int height,
      MemorySegment data,
      long offset,
      int bytesPerRow) {
    MemorySegment queue = usable();
    MemorySegment target = device.owned(texture).handle();
    if (!texture.usages().contains(TextureUsage.COPY_DST)) {
      throw new IllegalArgumentException(
          texture + " was not created with" + " TextureUsage.COPY_DST.");
    }
    if (level < 0 || level >= texture.levels()) {
      throw new IllegalArgumentException("Cannot write into mip level " + level + " of " + texture
          + ", which has " + texture.levels() + ".");
    }
    if (x < 0
        || y < 0
        || width < 0
        || height < 0
        || layer < 0
        || layer >= texture.layers()
        || (long) x + width > texture.width(level)
        || (long) y + height > texture.height(level)) {
      throw new IllegalArgumentException("Cannot write " + width + "x" + height + " texels at "
          + x + "," + y + " of layer " + layer + " into " + texture + " of "
          + texture.width(level) + "x" + texture.height(level) + " texels at mip level " + level
          + " and " + texture.layers() + " layers: the rectangle must lie inside one layer.");
    }
    if (width == 0 || height == 0) {
      return;
    }
    long row = (long) width * texture.format().bytesPerTexel();
    // The bytes the rectangle takes in data; a bytesPerRow of 0 would end the process.
    long size = (long) (height - 1) * bytesPerRow + row;
    if (bytesPerRow < row || offset < 0 || size > data.byteSize() - offset) {
      throw new IllegalArgumentException("Cannot write " + width + "x" + height + " texels of "
          + texture.format() + " from " + data.byteSize() + " bytes at " + offset + " with "
          + bytesPerRow + " bytes a row: a row needs at least " + row + " bytes, and all "
          + height + " rows must lie in the data.");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment from = data.asSlice(offset, size);
      if (!from.isNative()) {
        // A downcall takes only native memory.
        from = arena.allocate(size, 8).copyFrom(from);
      }
      MemorySegment destination = arena.allocate(Structs.TexelCopyTextureInfo.LAYOUT);
      destination.set(ADDRESS, Structs.TexelCopyTextureInfo.TEXTURE, target);
      destination.set(JAVA_INT, Structs.TexelCopyTextureInfo.MIP_LEVEL, level);
      long origin = Structs.TexelCopyTextureInfo.ORIGIN;
      destination.set(JAVA_INT, origin + Structs.Origin3D.X, x);
      destination.set(JAVA_INT, origin + Structs.Origin3D.Y, y);
      destination.set(JAVA_INT, origin + Structs.Origin3D.Z, layer);
      MemorySegment layout = Structs.TexelCopyBufferLayout.allocate(arena);
      layout.set(JAVA_INT, Structs.TexelCopyBufferLayout.BYTES_PER_ROW, bytesPerRow);
      layout.set(JAVA_INT, Structs.TexelCopyBufferLayout.ROWS_PER_IMAGE, height);
      MemorySegment extent = arena.allocate(Structs.Extent3D.LAYOUT);
      Structs.Extent3D.set(extent, 0, width, height);
      MemorySegment bytes = from;
      device.run(
          "writing texture",
          texture.label(),
          () -> Wgpu.queueWriteTexture(queue, destination, bytes, size, layout, extent));
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
