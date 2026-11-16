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
    HEADING,
    /** The title of a page, above its sections. */
    TITLE,
    /** A title inside a section, such as a popover's: the body's size in a heavier weight. */
    STRONG,
    /**
     * Secondary text in the small size, beside what it is about: the details of a status bar, the
     * kind of a completion, the count of a find bar. Muted, as {@link #MUTED}.
     */
    SMALL
  }

  private Label() {}

  public static El label(Theme theme, String text) {
    return label(theme, Variant.NORMAL, text);
  }

  public static El label(Theme theme, Variant variant, String text) {
    Theme.TextStyle style = switch (variant) {
      case NORMAL, MUTED -> theme.type().body();
      case MONO -> theme.type().mono();
      case HEADING -> theme.type().heading();
      case TITLE -> theme.type().title();
      case STRONG -> theme.type().strong();
      case SMALL -> theme.type().small();
    };
    return El.text(text)
        .font(style.font().get())
        .fontSize(style.size())
        .color(
            variant == Variant.MUTED || variant == Variant.SMALL
                ? theme.text().muted()
                : theme.text().primary());
  }
}
