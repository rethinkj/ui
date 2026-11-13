package com.rethinkj.ui.gpu;

/** {@code WGPUVertexFormat}: the type of a vertex attribute in its buffer. */
public enum VertexFormat {
  UNORM8X2(0x08, 2),
  UNORM8X4(0x09, 4),
  UINT16X2(0x0E, 4),
  UINT16X4(0x0F, 8),
  FLOAT16X2(0x1A, 4),
  FLOAT16X4(0x1B, 8),
  FLOAT32(0x1C, 4),
  FLOAT32X2(0x1D, 8),
  FLOAT32X3(0x1E, 12),
  FLOAT32X4(0x1F, 16),
  UINT32(0x20, 4),
  UINT32X2(0x21, 8),
  UINT32X3(0x22, 12),
  UINT32X4(0x23, 16),
  SINT32(0x24, 4),
  SINT32X2(0x25, 8),
  SINT32X3(0x26, 12),
  SINT32X4(0x27, 16);

  final int value;
  private final int size;

  VertexFormat(int value, int size) {
    this.value = value;
    this.size = size;
  }

  /** The size in bytes. */
  public int size() {
    return size;
  }
}
