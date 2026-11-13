package com.rethinkj.ui.motion;

/**
 * One animated number. It follows one {@link Spring} at a time; {@link #retarget} starts the next
 * movement from where the current one is, so neither the value nor its velocity jumps.
 *
 * <p>Times are seconds on a clock of the caller's choosing; the track never reads one.
 */
public final class Track {

  private final double tolerance;
  private Spring spring;
  private double start, from, velocity, to, end;

  /**
   * @param value the value the track starts at rest with
   * @param tolerance how near its target, in the value's units, and how slow, in units per second,
   *     the value must stay to count as at rest
   */
  public Track(double value, double tolerance) {
    if (!(tolerance > 0 && tolerance < Double.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "tolerance must be a positive finite number, was " + tolerance);
    }
    this.tolerance = tolerance;
    set(value);
  }

  /** Jumps to {@code value} without animation and stays there. */
  public void set(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("value must be finite, was " + value);
    }
    spring = Spring.NONE;
    from = to = value;
    velocity = 0;
    start = end = Double.NEGATIVE_INFINITY;
  }

  /** Moves towards {@code target} with {@code spring}, starting from the state at {@code now}. */
  public void retarget(double target, Spring spring, double now) {
    double x = value(now), v = velocity(now);
    end = now + spring.restTime(x, v, target, tolerance);
    this.spring = spring;
    from = x;
    velocity = v;
    to = target;
    start = now;
  }

  /** The value at {@code now}: exactly the target once at rest. */
  public double value(double now) {
    return isAtRest(now) ? to : spring.value(from, velocity, to, now - start);
  }

  /** The velocity in units per second at {@code now}: exactly 0 once at rest. */
  public double velocity(double now) {
    return isAtRest(now) ? 0 : spring.velocity(from, velocity, to, now - start);
  }

  /** Whether the value has reached its target and stays there from {@code now} on. */
  public boolean isAtRest(double now) {
    return now >= end;
  }
}
