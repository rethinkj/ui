package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;

/** Text in one of the theme's styles. A label takes no input; it names what is beside it. */
public final class Label {

  public enum Variant {
    /** The text of the interface. */
    NORMAL,
    /** Secondary text: hints, captions, values beside their names. */
    MUTED,
    /** Code, identifiers and values that line up. */
    MONO,
    /** The title of a section. */
    HEADING
  }

  private Label() {}

  public static El label(Theme t, String text) {
    return label(t, Variant.NORMAL, text);
  }

  public static El label(Theme t, Variant variant, String text) {
    Theme.TextStyle style =
        switch (variant) {
          case NORMAL, MUTED -> t.type().body();
          case MONO -> t.type().mono();
          case HEADING -> t.type().heading();
        };
    return El.text(text)
        .font(style.font().get())
        .fontSize(style.size())
        .color(variant == Variant.MUTED ? t.text().muted() : t.text().primary());
  }
}
