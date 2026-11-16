package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import com.rethinkj.ui.text.Font;
import java.util.Locale;

/**
 * A symbol from the icon font in one of the theme's text or status colors, as a text element of
 * one glyph that is exactly as wide and as high as its size and does not shrink. In a row it lines
 * up with the text beside it when the row centers its children:
 *
 * <pre>{@code
 * row().gap(theme.space().s()).align(Style.Align.CENTER).children(
 *     icon(theme, Symbol.TABLE, Icon.Variant.MUTED), label(theme, "orders"));
 * }</pre>
 *
 * <p>The font is {@link Font#icons()}, which needs the artifact {@code rethinkj-ui-icons}; it is
 * loaded when the first icon is built. An icon has no role. Its label is the symbol's name in
 * lower case, {@code label="chevron_right"}, so that a tree dump says which icon it is instead of
 * a private-use code point; one that means something by itself, such as a button with no text,
 * gets a {@code label} on the element that takes the press.
 */
public final class Icon {

  /** The color of an icon. */
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

  /** The label of each symbol, by its ordinal, made once. */
  private static final String[] LABELS = new String[Symbol.values().length];

  static {
    for (Symbol symbol : Symbol.values()) {
      LABELS[symbol.ordinal()] = symbol.name().toLowerCase(Locale.ROOT);
    }
  }

  private Icon() {}

  /** The symbol in the text color, {@link Theme.Sizes#icon} high. */
  public static El icon(Theme theme, Symbol symbol) {
    return icon(theme, symbol, Variant.NORMAL);
  }

  /** The symbol in the variant's color, {@link Theme.Sizes#icon} high. */
  public static El icon(Theme theme, Symbol symbol, Variant variant) {
    return icon(theme, symbol, variant, theme.size().icon());
  }

  /**
   * The symbol in the variant's color, {@code size} logical pixels wide and high, in the drawing
   * {@link Symbol#codePoint} picks for that size.
   */
  public static El icon(Theme theme, Symbol symbol, Variant variant, float size) {
    int color = switch (variant) {
      case NORMAL -> theme.text().primary();
      case MUTED -> theme.text().muted();
      case DISABLED -> theme.text().disabled();
      case DANGER -> theme.status().danger();
      case WARNING -> theme.status().warning();
      case SUCCESS -> theme.status().success();
    };
    return El.text(Character.toString(symbol.codePoint(size)))
        .label(LABELS[symbol.ordinal()])
        .font(Font.icons())
        .fontSize(size)
        .color(color)
        .shrink(0);
  }
}
