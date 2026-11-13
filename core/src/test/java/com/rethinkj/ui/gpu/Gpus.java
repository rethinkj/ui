package com.rethinkj.ui.gpu;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.natives.Natives;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens instances and adapters for the tests, or skips the test with a message that says why:
 * no natives module on the class path (another platform, or the build without its profile), or
 * no adapter for the backend on this machine.
 */
final class Gpus {

  private Gpus() {}

  private static final List<Instance> OPENED = new ArrayList<>();

  /** An instance of {@code backend}, or the test is skipped. */
  static Instance instance(Backend backend) {
    try {
      Natives.lookup("wgpu_native");
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: wgpu-native cannot be loaded. " + e.getMessage());
    }
    assumeTrue(
        backend == Backend.VULKAN || Backend.forPlatform() == Backend.D3D12,
        "Skipped: " + backend + " exists only on Windows");
    Instance instance = Instance.create(backend);
    OPENED.add(instance);
    return instance;
  }

  /** An adapter of {@code instance}, or the test is skipped and the instance closed. */
  static Adapter adapter(Instance instance) {
    try {
      return instance.requestAdapter();
    } catch (GpuException e) {
      if (e.kind() == GpuException.Kind.ADAPTER_UNAVAILABLE) {
        instance.close();
        assumeTrue(
            false,
            "Skipped: no " + instance.backend() + " adapter on this machine. " + e.wgpuMessage());
      }
      throw e;
    }
  }

  /** A device on the platform's backend; {@link #closeAll} closes its instance. */
  static Device device(String label) {
    return adapter(instance(Backend.forPlatform())).requestDevice(label);
  }

  /** Closes the instances the tests left open. */
  static void closeAll() {
    for (Instance instance : OPENED) {
      if (!instance.isClosed()) {
        instance.close();
      }
    }
    OPENED.clear();
  }
}
