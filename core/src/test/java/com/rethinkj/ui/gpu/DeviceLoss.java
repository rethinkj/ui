package com.rethinkj.ui.gpu;

/** Loses a device for tests of what uses it, as wgpu's device-lost callback reports a loss. */
public final class DeviceLoss {

  private DeviceLoss() {}

  /** Marks {@code device} lost: every call but {@link Device#close} throws from then on. */
  public static void lose(Device device, String text) {
    device.lose(text);
  }
}
