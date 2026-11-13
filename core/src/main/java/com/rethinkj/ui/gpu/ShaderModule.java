package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;

/** Compiled WGSL. A pipeline keeps what it needs of it, so it may be closed after use. */
public final class ShaderModule extends GpuObject {

  ShaderModule(Device device, String label, MemorySegment handle) {
    super(device.instance, device, label, handle);
  }

  @Override
  String kind() {
    return "Shader module";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.shaderModuleRelease(handle);
  }
}
