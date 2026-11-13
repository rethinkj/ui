package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.text.Font;

/** Skips a test that lays out text where FreeType and HarfBuzz cannot be loaded. */
final class TextLibraries {

  private TextLibraries() {}

  static void assumeLoaded() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }
}
