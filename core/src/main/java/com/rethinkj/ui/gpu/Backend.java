package com.rethinkj.ui.gpu;

/**
 * The graphics API wgpu runs on. An {@link Instance} enables exactly one, so wgpu loads no other
 * driver: Direct3D 12 on Windows and Vulkan on Linux by default.
 */
public enum Backend {
  D3D12(Wgpu.INSTANCE_BACKEND_DX12, Wgpu.BACKEND_TYPE_D3D12),
  VULKAN(Wgpu.INSTANCE_BACKEND_VULKAN, Wgpu.BACKEND_TYPE_VULKAN);

  /** The {@code WGPUInstanceBackend} bit. */
  final long instanceBit;
  /** The {@code WGPUBackendType}. */
  final int type;

  Backend(long instanceBit, int type) {
    this.instanceBit = instanceBit;
    this.type = type;
  }

  /** Direct3D 12 on Windows, Vulkan elsewhere. */
  public static Backend forPlatform() {
    return System.getProperty("os.name", "").startsWith("Windows") ? D3D12 : VULKAN;
  }

  /** The backend of a {@code WGPUBackendType}, or null if it is none of these. */
  static Backend ofType(int type) {
    for (Backend backend : values()) {
      if (backend.type == type) {
        return backend;
      }
    }
    return null;
  }
}
