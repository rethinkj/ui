package com.rethinkj.ui;

/** A length in a {@link Style}: pixels, a fraction of the parent's size, or automatic. */
public record Len(Kind kind, float value) {

  public enum Kind {
    AUTO,
    PX,
    PERCENT,
    FIT_CONTENT
  }

  public static final Len AUTO = new Len(Kind.AUTO, 0);
  public static final Len ZERO = new Len(Kind.PX, 0);

  /**
   * CSS's {@code fit-content} as a width or height: the size of the content, as auto gives it,
   * but never stretched to the parent's line by {@code align-items: stretch}, which then places
   * the element at the start instead. Any other alignment of the parent still applies, so a
   * control in a column is as wide as itself whether the column stretches or centres. As a
   * minimum, maximum or basis it counts as auto.
   */
  public static final Len FIT_CONTENT = new Len(Kind.FIT_CONTENT, 0);

  public static Len px(float pixels) {
    return new Len(Kind.PX, pixels);
  }

  /** @param fraction 1 is the full size of the parent */
  public static Len percent(float fraction) {
    return new Len(Kind.PERCENT, fraction);
  }

  public boolean isAuto() {
    return kind == Kind.AUTO;
  }
}
