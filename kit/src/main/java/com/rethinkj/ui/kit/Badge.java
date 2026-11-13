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
 * colour as text on a tint of it, and strong on request, filled with its kind's colour. It may
 * have an icon before its text, a cross after it that removes it, as a filter that the user takes
 * away, and it may be pressable. Two heights: {@link Theme.Badges#row} fits a row of a grid, a
 * tree or a list without making it higher, {@link Theme.Badges#toolbar} stands beside the
 * controls of a toolbar or a header.
 *
 * <pre>{@code
 * Badge.badge(t, "bigint")
 * Badge.badge(t, Badge.Kind.ACCENT, "PK")
 * Badge.badge(t, "status = paid", Badge.options().toolbar().onRemove(() -> s.filters.remove(f)))
 * }</pre>
 *
 * <p>In a tree dump a badge that only shows has no role and its text as its label; a pressable
 * one is {@code role=button}, and the cross is {@code role=button} with the label {@code
 * Remove <text>}. Both keep the focus where it is when pressed ({@link El#keepFocus}), as they act
 * on something else, and Tab reaches them.
 */
public final class Badge {

  /** What a badge says about its subject, which picks its colours from {@link Theme.Badges}. */
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
    private Symbol icon;
    private boolean toolbar;
    private Runnable onPress;
    private Runnable onRemove;

    private Options() {}

    public Options kind(Kind kind) {
      this.kind = Objects.requireNonNull(kind, "kind");
      return this;
    }

    /** Filled with the kind's colour instead of a tint of it. */
    public Options strong() {
      this.strong = true;
      return this;
    }

    /** An icon before the text, in the text's colour. */
    public Options icon(Symbol icon) {
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
  public static El badge(Theme t, String text) {
    return badge(t, text, options());
  }

  /** A quiet badge of a kind for a row. */
  public static El badge(Theme t, Kind kind, String text) {
    return badge(t, text, options().kind(kind));
  }

  /**
   * @param text what the badge says, also its label
   * @param o its kind, look, height and handlers
   */
  public static El badge(Theme t, String text, Options o) {
    Theme.Badges b = t.badge();
    Theme.Tone tone = switch (o.kind) {
      case NEUTRAL -> b.neutral();
      case ACCENT -> b.accent();
      case SUCCESS -> b.success();
      case WARNING -> b.warning();
      case DANGER -> b.danger();
    };
    int fill = o.strong ? tone.strong() : tone.soft();
    int ink = o.strong ? tone.strongText() : tone.softText();
    float height = o.toolbar ? b.toolbar() : b.row();
    float side = o.toolbar ? t.space().m() : t.space().s() + t.space().xs();
    El badge = row()
        .label(text)
        .h(height)
        .shrink(0)
        .align(Style.Align.CENTER)
        .gap(t.space().xs())
        .padding(0, o.onRemove == null ? side : t.space().xs(), 0, side)
        .radius(t.radius().small())
        .fill(fill)
        .motion(t.motion().feedback());
    if (o.icon != null) {
      badge.child(Icon.icon(t, o.icon, Icon.Variant.MUTED, b.text().size()).color(ink));
    }
    badge.child(
        El.text(text).font(b.text().font().get()).fontSize(b.text().size()).color(ink));
    if (o.onRemove != null) {
      float cross = height - 2 * t.space().xs();
      badge.child(el().key("remove")
          .role("button")
          .label("Remove " + text)
          .size(cross, cross)
          .shrink(0)
          .center()
          .radius(t.radius().small())
          .focusable()
          .keepFocus()
          .focusRing(t.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(o.onRemove)
          .whenHovered(e -> e.fill(t.surface().hover()))
          .whenPressed(e -> e.fill(t.surface().pressed()))
          .child(
              Icon.icon(t, Symbol.DISMISS, Icon.Variant.MUTED, b.text().size()).color(ink)));
    }
    if (o.onPress != null) {
      int hover = o.strong ? over(fill, t.surface().hover()) : deeper(fill, HOVER);
      int pressed = o.strong ? over(fill, t.surface().pressed()) : deeper(fill, PRESSED);
      badge
          .role("button")
          .focusable()
          .keepFocus()
          .focusRing(t.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(o.onPress)
          .whenHovered(e -> e.fill(hover))
          .whenPressed(e -> e.fill(pressed));
    }
    return badge;
  }

  // A pressable quiet badge deepens its tint under the pointer and while pressed, rather than
  // laying the hover over it, which would need a second fill.
  static final double HOVER = 1.4;
  static final double PRESSED = 1.8;

  /** A translucent colour with its alpha times {@code factor}, at most opaque. */
  static int deeper(int argb, double factor) {
    int alpha = (int) Math.min(255, Math.round((argb >>> 24) * factor));
    return alpha << 24 | argb & 0xFFFFFF;
  }
}
