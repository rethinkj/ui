package com.rethinkj.ui.motion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpringTest {

  static final double FROM = 3, TO = -7;
  static final double[] VELOCITIES = {-40, 0, 25};

  // Underdamped, critical, overdamped, and both sides of the band that counts as critical.
  static final String REGIMES =
      """
      0.5,  0.6
      0.25, 0.15
      0.1,  0.3
      0.4,  0
      0.5,  1e-7
      0.5,  -1e-7
      0.5,  1e-5
      0.5,  -1e-5
      0.5,  -0.5
      0.85, -0.9
      """;

  @ParameterizedTest
  @CsvSource(textBlock = REGIMES)
  void matchesNumericalIntegration(double duration, double bounce) {
    Spring spring = new Spring(duration, bounce);
    double w = 2 * Math.PI / duration, z = bounce >= 0 ? 1 - bounce : 1 / (1 + bounce);
    double dt = 1e-5;
    for (double v0 : VELOCITIES) {
      double x = FROM, v = v0;
      for (int i = 0; i <= 400_000; i++) {
        if (i % 1000 == 0) {
          double t = i * dt;
          String at = "at " + t + ", v0 " + v0;
          assertEquals(x, spring.value(FROM, v0, TO, t), 1e-8, "value " + at);
          assertEquals(v, spring.velocity(FROM, v0, TO, t), 1e-7, "velocity " + at);
        }
        // One RK4 step of x'' = −ω²(x − to) − 2ζω x'.
        double k1x = v, k1v = -w * w * (x - TO) - 2 * z * w * v;
        double x2 = x + dt / 2 * k1x, v2 = v + dt / 2 * k1v;
        double k2x = v2, k2v = -w * w * (x2 - TO) - 2 * z * w * v2;
        double x3 = x + dt / 2 * k2x, v3 = v + dt / 2 * k2v;
        double k3x = v3, k3v = -w * w * (x3 - TO) - 2 * z * w * v3;
        double x4 = x + dt * k3x, v4 = v + dt * k3v;
        double k4x = v4, k4v = -w * w * (x4 - TO) - 2 * z * w * v4;
        x += dt / 6 * (k1x + 2 * k2x + 2 * k3x + k4x);
        v += dt / 6 * (k1v + 2 * k2v + 2 * k3v + k4v);
      }
    }
  }

  @ParameterizedTest
  @CsvSource(textBlock = REGIMES)
  void restTimeIsASafeBoundCloseToTheTrueOne(double duration, double bounce) {
    Spring spring = new Spring(duration, bounce);
    // The bound holds up to rounding, so it is checked against a hair more than the tolerance.
    double tolerance = 1e-3, slack = 1e-12, dt = 1e-4;
    for (double v0 : VELOCITIES) {
      double rest = spring.restTime(FROM, v0, TO, tolerance), settled = 0;
      for (int i = 0; i * dt < rest + 5; i++) {
        double t = i * dt;
        double distance = Math.abs(spring.value(FROM, v0, TO, t) - TO);
        double speed = Math.abs(spring.velocity(FROM, v0, TO, t));
        if (distance > tolerance + slack || speed > tolerance + slack) {
          assertTrue(t < rest, "outside the tolerance at " + t + " after " + rest + ", v0 " + v0);
          settled = t + dt;
        }
      }
      assertTrue(rest < 2 * settled, rest + " is far after " + settled + ", v0 " + v0);
    }
  }

  @Test
  void overshootsOnlyWithPositiveBounce() {
    for (double bounce : new double[] {0.05, 0.15, 0.3, 0.6}) {
      assertTrue(peak(new Spring(0.5, bounce)) > 1, "bounce " + bounce);
    }
    for (double bounce : new double[] {0, -1e-5, -0.5, -0.9}) {
      assertEquals(1, peak(new Spring(0.5, bounce)), "bounce " + bounce);
    }
  }

  // The largest value of a move from rest at 0 to 1, or 1 if it never passes the target.
  private static double peak(Spring spring) {
    double peak = 0;
    for (int i = 0; i <= 10_000; i++) {
      peak = Math.max(peak, spring.value(0, 0, 1, i * 1e-3));
    }
    return Math.max(peak, 1);
  }

  @Test
  void zeroDurationIsAtTheTargetAtOnce() {
    for (Spring spring : new Spring[] {Spring.NONE, new Spring(0, 0.5)}) {
      assertEquals(5, spring.value(1, 30, 5, 0));
      assertEquals(0, spring.velocity(1, 30, 5, 0));
      assertEquals(0, spring.restTime(1, 30, 5, 1e-3));
    }
  }

  @Test
  void restTimeIsZeroAtRestOnTheTarget() {
    assertEquals(0, Spring.SETTLE.restTime(4, 0, 4, 1e-3));
    assertEquals(0, Spring.POP.restTime(4, 0, 4, 1e-3));
    assertEquals(0, new Spring(0.3, -0.5).restTime(4, 0, 4, 1e-3));
  }

  @Test
  void rejectsParametersThatMakeNoSense() {
    for (double duration : new double[] {-0.1, Double.NaN, Double.POSITIVE_INFINITY}) {
      var e = assertThrows(IllegalArgumentException.class, () -> new Spring(duration, 0));
      assertEquals("duration must be 0 or a positive finite number of seconds, was " + duration,
          e.getMessage());
    }
    for (double bounce : new double[] {-1, 1, 1.5, Double.NaN}) {
      var e = assertThrows(IllegalArgumentException.class, () -> new Spring(0.3, bounce));
      assertEquals("bounce must be greater than -1 and less than 1, was " + bounce, e.getMessage());
    }
    for (double tolerance : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> Spring.SNAP.restTime(0, 0, 1, tolerance));
    }
    assertThrows(IllegalArgumentException.class, () -> Spring.SNAP.restTime(Double.NaN, 0, 1, 1));
  }
}
