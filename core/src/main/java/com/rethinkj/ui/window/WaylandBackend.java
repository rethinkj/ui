package com.rethinkj.ui.window;

/** A window on Wayland. */
final class WaylandBackend implements WindowBackend {

  @Override
  public int[] iconAlternates() {
    // Only Wayland's driver passes alternates on; drawing them for X11 delays the first frame.
    return new int[] {128, 256};
  }
}
