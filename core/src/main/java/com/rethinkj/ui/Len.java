package com.rethinkj.ui;

/** A length in a {@link Style}: pixels, a fraction of the parent's size, or automatic. */
public record Len(Kind kind, float value) {

  public enum Kind {
    AUTO,
    PX,
    PERCENT
  }

  public static final Len AUTO = new Len(Kind.AUTO, 0);
  public static final Len ZERO = new Len(Kind.PX, 0);

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
