package com.rethinkj.ui.gpu;

/** The {@code WGPUTextureFormat}s a 2D renderer uses, with their size per texel. */
public enum TextureFormat {
  R8_UNORM(0x01, 1),
  R16_FLOAT(0x09, 2),
  RG8_UNORM(0x0A, 2),
  R32_FLOAT(0x0E, 4),
  RGBA8_UNORM(0x16, 4),
  /** Stores sRGB-encoded values; shaders read and write linear ones. */
  RGBA8_UNORM_SRGB(0x17, 4),
  BGRA8_UNORM(0x1B, 4),
  BGRA8_UNORM_SRGB(0x1C, 4),
  RGB10A2_UNORM(0x1E, 4),
  RGBA16_FLOAT(0x28, 8),
  RGBA32_FLOAT(0x29, 16);

  final int value;
  private final int bytesPerTexel;

  TextureFormat(int value, int bytesPerTexel) {
    this.value = value;
    this.bytesPerTexel = bytesPerTexel;
  }

  public int bytesPerTexel() {
    return bytesPerTexel;
  }

  /** The format of a {@code WGPUTextureFormat}, or null for one not listed here. */
  static TextureFormat of(int value) {
    for (TextureFormat format : values()) {
      if (format.value == value) {
        return format;
      }
    }
    return null;
  }
}
