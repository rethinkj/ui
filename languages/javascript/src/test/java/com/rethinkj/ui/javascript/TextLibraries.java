package com.rethinkj.ui.javascript;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.text.Font;

/** Skips a test that lays out text where FreeType and HarfBuzz cannot be loaded. */
final class TextLibraries {

  private TextLibraries() {}

  static void assumeLoaded() {
    try {
      assertTrue(Font.sans() != null);
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }
}
