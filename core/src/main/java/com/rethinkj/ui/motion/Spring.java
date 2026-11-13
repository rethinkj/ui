package com.rethinkj.ui.motion;

/**
 * A spring with mass 1, tuned like SwiftUI's {@code Spring(duration:bounce:)}: by its perceived
 * duration and its bounce.
 *
 * <p>The angular frequency is ω = 2π / duration. The damping ratio is ζ = 1 − bounce for bounce ≥
 * 0 and ζ = 1 / (1 + bounce) for negative bounce, as in SwiftUI, Flutter and rune. Bounce 0 is
 * critically damped, positive bounce overshoots, negative bounce is overdamped.
 *
 * <p>Positions, velocities and rest times are computed in closed form with {@link StrictMath}, so
 * the same inputs give the same bits on every platform.
 *
 * @param duration in seconds; 0 makes a spring that is at its target at once, like {@link #NONE}
 * @param bounce greater than −1 and less than 1
 */
public record Spring(double duration, double bounce) {

  /** No motion: at the target at once. */
  public static final Spring NONE = new Spring(0, 0);

  // The presets are rune's motion tokens (enter springs), from crates/rune-motion/src/tokens.rs.

  /** Tied to the pointer: takes the edge off a jump without lagging the hand. */
  public static final Spring INSTANT = new Spring(0.1, 0);

  /** Small controls answering a click: indicators, widths that lead, exits. */
  public static final Spring SNAP = new Spring(0.25, 0);

  /** The default state change: arrives and stops. */
  public static final Spring SETTLE = new Spring(0.3, 0);

  /** Layout moving out of the way: siblings sliding into a gap, containers resizing. */
  public static final Spring REFLOW = new Spring(0.4, 0);

  /** Big surfaces travelling: drawers, sheets, pages. */
  public static final Spring GLIDE = new Spring(0.32, 0);

  /** One shape becoming another, such as a menu opening out of its button. */
  public static final Spring MORPH = new Spring(0.3, 0.16);

  /** A release with a little life in it, for presses and confirmations. */
  public static final Spring POP = new Spring(0.25, 0.15);

  /** Slow reveals the eye should follow, never utility UI. */
  public static final Spring DRIFT = new Spring(0.85, 0);

  public Spring {
    if (!(duration >= 0 && duration < Double.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "duration must be 0 or a positive finite number of seconds, was " + duration);
    }
    if (!(bounce > -1 && bounce < 1)) {
      throw new IllegalArgumentException(
          "bounce must be greater than -1 and less than 1, was " + bounce);
    }
  }

  /**
   * The position {@code t} seconds after leaving {@code from} with {@code velocity} (units per
   * second) towards {@code to}. A negative {@code t} counts as 0.
   */
  public double value(double from, double velocity, double to, double t) {
    return to + solve(from - to, velocity, t, false);
  }

  /** The velocity, in units per second, at the moment {@link #value} describes. */
  public double velocity(double from, double velocity, double to, double t) {
    return solve(from - to, velocity, t, true);
  }

  /**
   * Seconds after the start from which the distance to {@code to} stays at most {@code tolerance}
   * and the speed at most {@code tolerance} per second, up to rounding. It is an upper bound on the
   * true time, close to it: the bound follows the decay of the movement, not its exact shape.
   */
  public double restTime(double from, double velocity, double to, double tolerance) {
    if (!Double.isFinite(from) || !Double.isFinite(velocity) || !Double.isFinite(to)) {
      throw new IllegalArgumentException("from, velocity and to must be finite");
    }
    if (!(tolerance > 0 && tolerance < Double.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "tolerance must be a positive finite number, was " + tolerance);
    }
    if (duration == 0) {
      return 0;
    }
    double a = from - to, v0 = velocity, w = 2 * Math.PI / duration, z = zeta(), x, v;
    if (a == 0 && v0 == 0) {
      return 0;
    }
    // Each bound below holds from some time on; the earlier one of two counts.
    if (z < 1) {
      // offset = e^(−σt)·(a·cos ωd·t + (k/ωd)·sin ωd·t), with |sin ωd·t| ≤ min(1, ωd·t)
      double s = z * w, wd = w * StrictMath.sqrt((1 - z) * (1 + z)), k = v0 + s * a;
      double vk = a * wd * wd + s * k;
      x = Math.min(decay(StrictMath.hypot(a, k / wd), -s, tolerance),
          tail(Math.abs(a), Math.abs(k), s, tolerance));
      v = Math.min(decay(StrictMath.hypot(v0, vk / wd), -s, tolerance),
          tail(Math.abs(v0), Math.abs(vk), s, tolerance));
    } else {
      // offset = e^(r1·t)·(a + k·(1 − e^(−d·t))/d), with (1 − e^(−d·t))/d ≤ t
      double root = StrictMath.sqrt((z - 1) * (z + 1)), r1 = -w / (z + root), d = 2 * w * root;
      double r2 = r1 - d, k = v0 - r1 * a;
      x = tail(Math.abs(a), Math.abs(k), -r1, tolerance);
      v = tail(Math.abs(v0), Math.abs(r2 * k), -r1, tolerance);
      if (d > 0) {
        // offset = c1·e^(r1·t) + c2·e^(r2·t); each term is held to half the tolerance.
        double c1 = (v0 - r2 * a) / d, c2 = -k / d, half = tolerance / 2;
        x = Math.min(x, Math.max(decay(c1, r1, half), decay(c2, r2, half)));
        v = Math.min(v, Math.max(decay(c1 * r1, r1, half), decay(c2 * r2, r2, half)));
      }
    }
    return Math.max(0, Math.max(x, v));
  }

  private double zeta() {
    return bounce >= 0 ? 1 - bounce : 1 / (1 + bounce);
  }

  // The offset from the target (a at t = 0) or, for `derivative`, its velocity (v0 at t = 0).
  // Both forms stay accurate as ζ approaches 1; the second one is exact at ζ = 1.
  private double solve(double a, double v0, double t, boolean derivative) {
    if (duration == 0) {
      return 0;
    }
    t = Math.max(0, t);
    double w = 2 * Math.PI / duration, z = zeta();
    if (z < 1) {
      double s = z * w, wd = w * StrictMath.sqrt((1 - z) * (1 + z)), k = v0 + s * a;
      double e = StrictMath.exp(-s * t), cos = StrictMath.cos(wd * t), sin = StrictMath.sin(wd * t);
      return derivative
          ? e * (v0 * cos - (a * wd + s * k / wd) * sin)
          : e * (a * cos + k / wd * sin);
    }
    // Roots r1 = −ω(ζ − √(ζ² − 1)), written without the cancellation, and r2 = r1 − d.
    double root = StrictMath.sqrt((z - 1) * (z + 1)), r1 = -w / (z + root), d = 2 * w * root;
    double k = v0 - r1 * a, e = StrictMath.exp(r1 * t);
    double m = d == 0 ? -t : StrictMath.expm1(-d * t) / d;
    return derivative ? e * (v0 - (r1 - d) * k * m) : e * (a - k * m);
  }

  // When |c|·e^(rt), r < 0, falls to tol; −∞ for c = 0.
  private static double decay(double c, double r, double tol) {
    return StrictMath.log(Math.abs(c) / tol) / -r;
  }

  // From when (p + q·t)·e^(−wt), p, q ≥ 0, stays at most tol. Past its peak its logarithm is
  // concave and falling, so every Newton step after the first lands at or after the crossing.
  private static double tail(double p, double q, double w, double tol) {
    double peak = q > p * w ? 1 / w - p / q : 0;
    if ((p + q * peak) * StrictMath.exp(-w * peak) <= tol) {
      return 0;
    }
    double t = peak + 1 / w;
    for (int i = 0; i < 100; i++) {
      double next = t - (StrictMath.log((p + q * t) / tol) - w * t) / (q / (p + q * t) - w);
      if (i > 0 && !(next < t)) {
        break;
      }
      t = next;
    }
    return t;
  }
}
