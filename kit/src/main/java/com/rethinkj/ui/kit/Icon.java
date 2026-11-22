package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.text.Font;
import java.util.Objects;

/**
 * An icon value that any kit component can show: a {@link Symbol} from the bundled icon font, a
 * {@link Glyph} from an app's own font ({@link #glyph}), or a {@link Picture} from an image
 * ({@link #image}). {@link #icon(Icon, Variant, float)} turns one into an element that is as wide
 * and as high as its size and does not shrink. In a row it lines up with the text beside it when
 * the row centers its children:
 *
 * <pre>{@code
 * row().gap(theme.space().s()).align(Style.Align.CENTER).children(
 *     icon(Symbol.TABLE, Icon.Variant.MUTED), label("orders"));
 * }</pre>
 *
 * <p>A {@link Symbol} uses {@link Font#icons()}, which needs the artifact {@code rethinkj-ui-icons}
 * and is loaded when the first symbol is built. A {@link Glyph} takes the variant's or component's
 * color just as a symbol does; a {@link Picture} keeps its own colors and dims to 40 % opacity
 * while disabled. A symbol or glyph has no role; a picture is an image element ({@code role=img}).
 * Its {@link #label} names it in a tree dump (for a symbol, its name in lower case, {@code
 * label="chevron_right"}); one that means something by itself, such as a button with no text, gets
 * a {@code label} on the element that takes the press.
 */
public sealed interface Icon permits Symbol, Icon.Glyph, Icon.Picture {

  /** The color of an icon. */
  enum Variant {
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
    SUCCESS,
    /** An action at hand, such as running a statement: in the accent's edge. */
    ACCENT
  }

  /**
   * One glyph of an app's own font, drawn at the icon's size and colored like a {@link Symbol}.
   *
   * @param font the font that holds the glyph
   * @param codePoint the Unicode code point of the glyph in {@code font}
   * @param label what names the icon in a tree dump
   */
  record Glyph(Font font, int codePoint, String label) implements Icon {

    /** The font size a glyph's advance is measured at. */
    private static final float MEASURED = 100;

    public Glyph {
      Objects.requireNonNull(font, "font");
      if (!Character.isValidCodePoint(codePoint)) {
        throw new IllegalArgumentException("not a Unicode code point: " + codePoint);
      }
      Objects.requireNonNull(label, "label");
    }
  }

  /**
   * An image shown as an icon, fitted whole inside the icon's square. It keeps its own colors and
   * is drawn at 40 % opacity while disabled.
   *
   * @param image the image to show
   * @param label what names the icon in a tree dump
   */
  record Picture(com.rethinkj.ui.Image image, String label) implements Icon {

    /**
     * The opacity of a picture icon while disabled: 40 %, as {@link Theme.Danger#disabled} is its
     * color at 40 %.
     */
    private static final float DISABLED_OPACITY = 0.4f;

    public Picture {
      Objects.requireNonNull(image, "image");
      Objects.requireNonNull(label, "label");
    }

    /**
     * Sets the color of a symbol's or a glyph's element and leaves a picture's alone, which keeps
     * its own colors: a picture is dimmed only when it is built with {@link Variant#DISABLED}.
     */
    static El color(El icon, int color) {
      return icon.text() != null ? icon.color(color) : icon;
    }

    /** Dims an image element to the opacity of a picture that is off; leaves others alone. */
    static El off(El icon) {
      return icon.image() != null ? icon.opacity(icon.opacity() * DISABLED_OPACITY) : icon;
    }
  }

  /** What names this icon in a tree dump. */
  String label();

  /** One glyph of an app's own font as an icon. */
  static Glyph glyph(Font font, int codePoint, String label) {
    return new Glyph(font, codePoint, label);
  }

  /** An image as an icon, keeping its own colors and dimmed while disabled. */
  static Picture image(com.rethinkj.ui.Image image, String label) {
    return new Picture(image, label);
  }

  /** The icon in the text color, {@link Theme.Sizes#icon} high. */
  static El icon(Icon icon) {
    return icon(icon, Variant.NORMAL);
  }

  /** The icon in the variant's color, {@link Theme.Sizes#icon} high. */
  static El icon(Icon icon, Variant variant) {
    Theme theme = Theme.CURRENT.get();
    return icon(icon, variant, theme.size().icon());
  }

  /**
   * The icon in the variant's color, {@code size} logical pixels wide and high: for a {@link
   * Symbol}, the drawing {@link Symbol#codePoint} picks for that size; for a {@link Glyph}, that
   * glyph centered in a square of {@code size}; for a {@link Picture}, the image fitted inside that
   * square with {@link com.rethinkj.ui.Image.Fit#CONTAIN}, dimmed while {@link Variant#DISABLED}.
   */
  static El icon(Icon icon, Variant variant, float size) {
    Objects.requireNonNull(icon, "icon");
    Objects.requireNonNull(variant, "variant");
    Theme theme = Theme.CURRENT.get();
    int color = switch (variant) {
      case NORMAL -> theme.text().primary();
      case MUTED -> theme.text().muted();
      case DISABLED -> theme.text().disabled();
      case DANGER -> theme.status().danger();
      case WARNING -> theme.status().warning();
      case SUCCESS -> theme.status().success();
      case ACCENT -> theme.accent().edge();
    };
    return switch (icon) {
      case Symbol symbol ->
        El.text(Character.toString(symbol.codePoint(size)))
            .label(symbol.label())
            .font(Font.icons())
            .fontSize(size)
            .color(color)
            .shrink(0);
      case Glyph glyph -> {
        String text = Character.toString(glyph.codePoint());
        Font font = glyph.font();
        // The glyph's advance and the font's line fit the square and lie in its middle, which
        // centers the drawing of an icon font whose glyphs fill the em square, as most do.
        float line = font.ascent() + font.descent() + font.lineGap();
        float advance = font.shape(text, Glyph.MEASURED, 1).width() / Glyph.MEASURED;
        float fontSize = size / Math.max(1, Math.max(line, advance));
        yield El.text(text)
            .label(glyph.label())
            .font(font)
            .fontSize(fontSize)
            .color(color)
            .size(size, size)
            .padding((size - line * fontSize) / 2, 0)
            .textAlign(Style.Align.CENTER)
            .shrink(0);
      }
      case Picture picture -> {
        El el = El.image(picture.image())
            .fit(com.rethinkj.ui.Image.Fit.CONTAIN)
            .label(picture.label())
            .size(size, size)
            .shrink(0);
        yield variant == Variant.DISABLED ? Picture.off(el) : el;
      }
    };
  }
}
