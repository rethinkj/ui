package com.rethinkj.ui.gpu;

import java.util.Set;

/** {@code WGPUBufferUsage}: what a buffer may be used for. */
public enum BufferUsage {
  /** Can be read back with {@link Buffer#read}; combines only with {@link #COPY_DST}. */
  MAP_READ(0x001),
  MAP_WRITE(0x002),
  COPY_SRC(0x004),
  /** Can be written with {@link Queue#writeBuffer} and be the target of a copy. */
  COPY_DST(0x008),
  INDEX(0x010),
  VERTEX(0x020),
  UNIFORM(0x040),
  STORAGE(0x080),
  INDIRECT(0x100);

  final long bit;

  BufferUsage(long bit) {
    this.bit = bit;
  }

  static long bits(Set<BufferUsage> usages) {
    long bits = 0;
    for (BufferUsage usage : usages) {
      bits |= usage.bit;
    }
    return bits;
  }
}
