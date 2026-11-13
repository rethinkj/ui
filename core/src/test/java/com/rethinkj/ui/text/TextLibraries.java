package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.natives.Natives;

/**
 * Skips a test that needs FreeType and HarfBuzz where they cannot be loaded: without the natives
 * module (another platform, or the build without its profile), or with a natives module that does
 * not carry them yet, as on Windows for now.
 */
public final class TextLibraries {

  private TextLibraries() {}

  public static void assumeLoaded() {
    try {
      Natives.lookup("freetype");
      Natives.lookup("harfbuzz");
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }
}
