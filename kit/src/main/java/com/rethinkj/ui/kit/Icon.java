package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import com.rethinkj.ui.text.Font;

/**
 * A symbol from the icon font in one of the theme's text or status colours, as a text element of
 * one glyph that is exactly as wide and as high as its size and does not shrink. In a row it lines
 * up with the text beside it when the row centres its children:
 *
 * <pre>{@code
 * row().gap(t.space().s()).align(Style.Align.CENTER).children(
 *     icon(t, Symbol.TABLE, Icon.Variant.MUTED), label(t, "orders"));
 * }</pre>
 *
 * <p>The font is {@link Font#icons()}, which needs the artifact {@code rethinkj-ui-icons}; it is
 * loaded when the first icon is built. An icon has no role or label of its own: one that means
 * something by itself, such as a button with no text, gets a {@code label} from the caller.
 */
public final class Icon {

  /** The colour of an icon. */
  public enum Variant {
    /** As the text of the interface. */
    NORMAL,
    /** As secondary text: marks and icons beside names. */
    MUTED,
    /** As the text of a control that cannot be used now. */
    DISABLED,
    /** A failure or an action that destroys. */
    DANGER,
    /** Something that needs attention. */
    WARNING,
    /** Something that worked or is ready. */
    SUCCESS
  }

  private Icon() {}

  /** The symbol in the text colour, {@link Theme.Sizes#icon} high. */
  public static El icon(Theme t, Symbol symbol) {
    return icon(t, symbol, Variant.NORMAL);
  }

  /** The symbol in the variant's colour, {@link Theme.Sizes#icon} high. */
  public static El icon(Theme t, Symbol symbol, Variant variant) {
    return icon(t, symbol, variant, t.size().icon());
  }

  /**
   * The symbol in the variant's colour, {@code size} logical pixels wide and high, in the drawing
   * {@link Symbol#codePoint} picks for that size.
   */
  public static El icon(Theme t, Symbol symbol, Variant variant, float size) {
    int colour = switch (variant) {
      case NORMAL -> t.text().primary();
      case MUTED -> t.text().muted();
      case DISABLED -> t.text().disabled();
      case DANGER -> t.status().danger();
      case WARNING -> t.status().warning();
      case SUCCESS -> t.status().success();
    };
    return El.text(Character.toString(symbol.codePoint(size)))
        .font(Font.icons())
        .fontSize(size)
        .color(colour)
        .shrink(0);
  }
}
