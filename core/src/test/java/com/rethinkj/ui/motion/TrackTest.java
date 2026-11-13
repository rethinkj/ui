package com.rethinkj.ui.motion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrackTest {

  @Test
  void retargetingMidMovementKeepsValueAndVelocity() {
    Track track = new Track(0, 1e-3);
    track.retarget(100, Spring.POP, 1);
    Spring[] next = {Spring.SETTLE, new Spring(0.4, -0.5), Spring.MORPH};
    double now = 1;
    for (Spring spring : next) {
      now += 0.07;
      double value = track.value(now), velocity = track.velocity(now);
      assertTrue(Math.abs(velocity) > 10, "still moving");
      track.retarget(-50 * now, spring, now);
      assertEquals(value, track.value(now), 1e-12);
      assertEquals(velocity, track.velocity(now), 1e-9);
      assertEquals(value + velocity * 1e-6, track.value(now + 1e-6), 1e-6);
    }
  }

  @Test
  void endsExactlyOnTheTarget() {
    Track track = new Track(0, 1e-3);
    track.retarget(100, Spring.MORPH, 0);
    int frame = 0;
    while (!track.isAtRest(frame / 60.0)) {
      frame++;
      assertTrue(frame < 120, "no rest within two seconds");
    }
    for (int later = frame; later < frame + 60; later++) {
      assertEquals(100.0, track.value(later / 60.0));
      assertEquals(0.0, track.velocity(later / 60.0));
      assertTrue(track.isAtRest(later / 60.0));
    }
  }

  @Test
  void setJumpsWithoutAnimation() {
    Track track = new Track(0, 1e-3);
    track.retarget(100, Spring.DRIFT, 0);
    track.set(7);
    assertEquals(7, track.value(0.1));
    assertEquals(0, track.velocity(0.1));
    assertTrue(track.isAtRest(0.1));
  }

  @Test
  void theInstantSpringArrivesAtOnce() {
    Track track = new Track(1, 1e-3);
    track.retarget(5, Spring.NONE, 2);
    assertEquals(5, track.value(2));
    assertEquals(0, track.velocity(2));
    assertTrue(track.isAtRest(2));
  }

  @Test
  void retargetingToWhereItRestsChangesNothing() {
    Track track = new Track(4, 1e-3);
    track.retarget(4, Spring.SETTLE, 3);
    assertTrue(track.isAtRest(3));
    track.retarget(5, Spring.SETTLE, 3);
    assertFalse(track.isAtRest(3));
    assertEquals(4, track.value(3), 1e-12);
  }

  @Test
  void rejectsBadValues() {
    assertThrows(IllegalArgumentException.class, () -> new Track(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new Track(Double.NaN, 1e-3));
    Track track = new Track(0, 1e-3);
    assertThrows(IllegalArgumentException.class, () -> track.retarget(Double.NaN, Spring.SNAP, 0));
    assertEquals(0, track.value(0));
  }
}
