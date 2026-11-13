package com.rethinkj.ui.gpu;

import java.util.Set;

/** {@code WGPUShaderStage}: the stages that see a binding. */
public enum ShaderStage {
  VERTEX(0x1),
  FRAGMENT(0x2);

  final long bit;

  ShaderStage(long bit) {
    this.bit = bit;
  }

  static long bits(Set<ShaderStage> stages) {
    long bits = 0;
    for (ShaderStage stage : stages) {
      bits |= stage.bit;
    }
    return bits;
  }
}
