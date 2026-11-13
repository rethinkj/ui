package com.rethinkj.ui.gpu;

import java.util.EnumSet;
import java.util.Set;

/** {@code WGPUTextureUsage}: what a texture may be used for. */
public enum TextureUsage {
  COPY_SRC(0x01),
  COPY_DST(0x02),
  TEXTURE_BINDING(0x04),
  STORAGE_BINDING(0x08),
  RENDER_ATTACHMENT(0x10);

  final long bit;

  TextureUsage(long bit) {
    this.bit = bit;
  }

  static long bits(Set<TextureUsage> usages) {
    long bits = 0;
    for (TextureUsage usage : usages) {
      bits |= usage.bit;
    }
    return bits;
  }

  /** The usages of a bit set; bits not listed here are left out. */
  static Set<TextureUsage> of(long bits) {
    EnumSet<TextureUsage> usages = EnumSet.noneOf(TextureUsage.class);
    for (TextureUsage usage : values()) {
      if ((bits & usage.bit) != 0) {
        usages.add(usage);
      }
    }
    return usages;
  }
}
