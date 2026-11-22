package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.Objects;

/**
 * A short word in a small rounded fill: the type of a column ({@code bigint}), a property ({@code
 * PK}, {@code NOT NULL}, {@code read only}) or a count. A badge is quiet by default, its kind's
 * color as text on a tint of it, and strong on request, filled with its kind's color. It may
 * have an icon before its text, a cross after it that removes it, as a filter that the user takes
 * away, and it may be pressable. Two heights: {@link Theme.Badges#row} fits a row of a grid, a
 * tree or a list without making it higher, {@link Theme.Badges#toolbar} stands beside the
 * controls of a toolbar or a header.
 *
 * <pre>{@code
 * Badge.badge("bigint")
 * Badge.badge(Badge.Kind.ACCENT, "PK")
 * Badge.badge("status = paid",
 *     Badge.options().toolbar().onRemove(() -> state.filters.remove(filter)))
 * }</pre>
 *
 * <p>In a tree dump a badge that only shows has no role and its text as its label; a pressable
 * one is {@code role=button}, and the cross is {@code role=button} with the label {@code
 * Remove <text>}. Both keep the focus where it is when pressed ({@link El#keepFocus}), as they act
 * on something else, and Tab reaches them.
 */
public final class Badge {

  /** What a badge says about its subject, which picks its colors from {@link Theme.Badges}. */
  public enum Kind {
    NEUTRAL,
    ACCENT,
    SUCCESS,
    WARNING,
    DANGER
  }

  private Badge() {}

  /** What a badge looks like and does beyond its text; {@link #options()} is a quiet one. */
  public static final class Options {
    private Kind kind = Kind.NEUTRAL;
    private boolean strong;
    private Icon icon;
    private boolean toolbar;
    private Runnable onPress;
    private Runnable onRemove;

    private Options() {}

    public Options kind(Kind kind) {
      this.kind = Objects.requireNonNull(kind, "kind");
      return this;
    }

    /** Filled with the kind's color instead of a tint of it. */
    public Options strong() {
      this.strong = true;
      return this;
    }

    /** An icon before the text, in the text's color. */
    public Options icon(Icon icon) {
      this.icon = icon;
      return this;
    }

    /** The height for a toolbar or a header instead of a row. */
    public Options toolbar() {
      this.toolbar = true;
      return this;
    }

    /** Makes the whole badge a button that runs {@code onPress}. */
    public Options onPress(Runnable onPress) {
      this.onPress = onPress;
      return this;
    }

    /** Puts a cross after the text that runs {@code onRemove}, as for a filter. */
    public Options onRemove(Runnable onRemove) {
      this.onRemove = onRemove;
      return this;
    }
  }

  public static Options options() {
    return new Options();
  }

  /** A quiet neutral badge for a row. */
  public static El badge(String text) {
    return badge(text, options());
  }

  /** A quiet badge of a kind for a row. */
  public static El badge(Kind kind, String text) {
    return badge(text, options().kind(kind));
  }

  /**
   * @param text what the badge says, also its label
   * @param options its kind, look, height and handlers
   */
  public static El badge(String text, Options options) {
    Theme theme = Theme.CURRENT.get();
    Theme.Badges b = theme.badge();
    Theme.Tone tone = switch (options.kind) {
      case NEUTRAL -> b.neutral();
      case ACCENT -> b.accent();
      case SUCCESS -> b.success();
      case WARNING -> b.warning();
      case DANGER -> b.danger();
    };
    int fill = options.strong ? tone.strong() : tone.soft();
    int ink = options.strong ? tone.strongText() : tone.softText();
    float height = options.toolbar ? b.toolbar() : b.row();
    float side =
        options.toolbar ? theme.space().m() : theme.space().s() + theme.space().xs();
    El badge = row()
        .label(text)
        .h(height)
        .shrink(0)
        .align(Style.Align.CENTER)
        .gap(theme.space().xs())
        .padding(0, options.onRemove == null ? side : theme.space().xs(), 0, side)
        .radius(theme.radius().small())
        .fill(fill)
        .motion(theme.motion().feedback());
    if (options.icon != null) {
      badge.child(Icon.Picture.color(
          Icon.icon(options.icon, Icon.Variant.MUTED, b.text().size()), ink));
    }
    badge.child(
        El.text(text).font(b.text().font().get()).fontSize(b.text().size()).color(ink));
    if (options.onRemove != null) {
      float cross = height - 2 * theme.space().xs();
      badge.child(el().key("remove")
          .role("button")
          .label(Texts.CURRENT.get().remove(text))
          .size(cross, cross)
          .shrink(0)
          .center()
          .radius(theme.radius().small())
          .focusable()
          .keepFocus()
          .focusRing(theme.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(options.onRemove)
          .whenHovered(e -> e.fill(theme.surface().hover()))
          .whenPressed(e -> e.fill(theme.surface().pressed()))
          .child(Icon.icon(Symbol.DISMISS, Icon.Variant.MUTED, b.text().size()).color(ink)));
    }
    if (options.onPress != null) {
      int hover = options.strong ? over(fill, theme.surface().hover()) : deeper(fill, HOVER);
      int pressed = options.strong ? over(fill, theme.surface().pressed()) : deeper(fill, PRESSED);
      badge
          .role("button")
          .focusable()
          .keepFocus()
          .focusRing(theme.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(options.onPress)
          .whenHovered(e -> e.fill(hover))
          .whenPressed(e -> e.fill(pressed));
    }
    return badge;
  }

  // A pressable quiet badge deepens its tint under the pointer and while pressed, rather than
  // laying the hover over it, which would need a second fill.
  static final double HOVER = 1.4;
  static final double PRESSED = 1.8;

  /** A translucent color with its alpha times {@code factor}, at most opaque. */
  static int deeper(int argb, double factor) {
    int alpha = (int) Math.min(255, Math.round((argb >>> 24) * factor));
    return alpha << 24 | argb & 0xFFFFFF;
  }
}
