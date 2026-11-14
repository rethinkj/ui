package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/** Reading the window manager's _NET_WM_SYNC_REQUEST from an XEvent; needs no X server. */
class X11Test {

  static final long PROTOCOLS = 301;
  static final long SYNC = 377;

  @Test
  void aSyncRequestGivesTheValueOfItsTwoHalves() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment e = request(arena, SYNC, 0xFFFF_FFF0L, 2);
      assertEquals(0x2_FFFF_FFF0L, X11.syncRequest(e, PROTOCOLS, SYNC));
      // A low half that a window manager sign-extended stays 32 bits.
      e.set(JAVA_LONG, X11.CLIENT_DATA + 16, (long) (int) 0xFFFF_FFF0L);
      assertEquals(0x2_FFFF_FFF0L, X11.syncRequest(e, PROTOCOLS, SYNC));
    }
  }

  @Test
  void otherEventsAreNoRequest() {
    try (Arena arena = Arena.ofConfined()) {
      assertEquals(-1, X11.syncRequest(request(arena, 999, 240, 0), PROTOCOLS, SYNC));
      MemorySegment e = request(arena, SYNC, 240, 0);
      e.set(JAVA_INT, X11.CLIENT_FORMAT, 8);
      assertEquals(-1, X11.syncRequest(e, PROTOCOLS, SYNC));
      e = request(arena, SYNC, 240, 0);
      e.set(JAVA_INT, X11.EVENT_TYPE, X11.CONFIGURE_NOTIFY);
      assertEquals(-1, X11.syncRequest(e, PROTOCOLS, SYNC));
      assertEquals(-1, X11.syncRequest(request(arena, SYNC, 240, 0), PROTOCOLS, 0), "no atoms");
    }
  }

  /** An XClientMessageEvent of WM_PROTOCOLS with the protocol in data.l[0]. */
  private static MemorySegment request(Arena arena, long protocol, long low, long high) {
    MemorySegment e = arena.allocate(X11.EVENT_SIZE);
    e.set(JAVA_INT, X11.EVENT_TYPE, X11.CLIENT_MESSAGE);
    e.set(JAVA_LONG, X11.CLIENT_MESSAGE_TYPE, PROTOCOLS);
    e.set(JAVA_INT, X11.CLIENT_FORMAT, 32);
    e.set(JAVA_LONG, X11.CLIENT_DATA, protocol);
    e.set(JAVA_LONG, X11.CLIENT_DATA + 16, low);
    e.set(JAVA_LONG, X11.CLIENT_DATA + 24, high);
    return e;
  }
}
