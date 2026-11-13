package com.rethinkj.ui.gpu;

/** Which adapter to prefer where there are several, such as an integrated and a discrete GPU. */
public enum PowerPreference {
  /** Leaves the choice to wgpu. */
  DEFAULT(Wgpu.POWER_PREFERENCE_UNDEFINED),
  LOW_POWER(Wgpu.POWER_PREFERENCE_LOW_POWER),
  HIGH_PERFORMANCE(Wgpu.POWER_PREFERENCE_HIGH_PERFORMANCE);

  final int value;

  PowerPreference(int value) {
    this.value = value;
  }
}
