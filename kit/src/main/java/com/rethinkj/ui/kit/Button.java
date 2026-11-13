package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;

/**
 * A button with text, an icon before its text, or an icon alone. A press runs its handler, and so
 * do Enter and Space while it is focused. Tab reaches it, but a press leaves the keyboard focus
 * where it is ({@link El#keepFocus}), so that the editor or grid a toolbar button acts on keeps it.
 * The text is in {@link Theme.Type#strong}, the medium weight.
 */
public final class Button {

  public enum Variant {
    /** The main action of a view, in the accent colour. */
    PRIMARY,
    /** Any other action, on a raised surface with an edge. */
    SECONDARY,
    /** A quiet action that shows its surface only under the pointer, as in toolbars. */
    GHOST
  }

  /** The colours of a button: its fill at rest, hovered and pressed, its edge and its text. */
  private record Look(int fill, int hover, int pressed, int edge, int text) {}

  private Button() {}

  /**
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El button(Theme t, Variant variant, String text, Runnable onPress) {
    return button(t, variant, null, text, onPress);
  }

  /**
   * A button with an icon before its text, in the text's colour.
   *
   * @param icon before the text; null for none
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El button(
      Theme t, Variant variant, Symbol icon, String text, Runnable onPress) {
    Look look = look(t, variant, onPress != null);
    El button = frame(t, variant, look, text, onPress).padding(0, t.space().l())
        .gap(t.space().s());
    if (icon != null) {
      button.child(Icon.icon(t, icon).color(look.text()));
    }
    Theme.TextStyle style = t.type().strong();
    button.child(El.text(text).font(style.font().get()).fontSize(style.size())
        .color(look.text()));
    return enable(t, variant, look, button, onPress);
  }

  /**
   * A square button with an icon alone, such as a toolbar's or a card's header's. The label names
   * it in a tree dump and in a tooltip that shows after the theme's delay under the pointer or the
   * keyboard's focus.
   *
   * @param label what the button does, such as "Add connection"
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El icon(Theme t, Variant variant, Symbol icon, String label, Runnable onPress) {
    Look look = look(t, variant, onPress != null);
    El button = frame(t, variant, look, label, onPress).w(t.size().control())
        .child(Icon.icon(t, icon).color(variant == Variant.PRIMARY || onPress == null
            ? look.text() : t.text().muted()));
    return Tooltip.tooltip(t, enable(t, variant, look, button, onPress), label);
  }

  /** A button's box: role, label, height, corners, fill and edge. */
  private static El frame(Theme t, Variant variant, Look look, String label, Runnable onPress) {
    return row().role("button")
        .label(label)
        .disabled(onPress == null)
        .h(t.size().control())
        .shrink(0)
        .center()
        .radius(t.radius().medium())
        .fill(look.fill())
        .border(t.size().stroke(), look.edge())
        .motion(t.motion().feedback());
  }

  /** Makes a button pressable, unless {@code onPress} is null. */
  private static El enable(
      Theme t, Variant variant, Look look, El button, Runnable onPress) {
    if (onPress == null) {
      return button;
    }
    return button.focusable()
        .keepFocus() // a press leaves the focus in the editor or grid the button acts on
        .focusRing(t.accent().edge())
        // Around the accent's fill, the surface behind the button shows between fill and ring.
        .focusRingGap(variant == Variant.PRIMARY ? t.size().ringGap() : 0)
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(look.hover()))
        .whenPressed(e -> e.fill(look.pressed()));
  }

  private static Look look(Theme t, Variant variant, boolean enabled) {
    Theme.Surfaces s = t.surface();
    if (!enabled) {
      return variant == Variant.GHOST
          ? new Look(0, 0, 0, 0, t.text().disabled())
          : new Look(s.raised(), s.raised(), s.raised(), t.border().control(),
              t.text().disabled());
    }
    return switch (variant) {
      case PRIMARY ->
          new Look(
              t.accent().color(), t.accent().hover(), t.accent().pressed(), 0, t.text().onAccent());
      case SECONDARY ->
          new Look(
              s.raised(),
              over(s.raised(), s.hover()),
              over(s.raised(), s.pressed()),
              t.border().control(),
              t.text().primary());
      // The overlays are translucent, so a ghost button shows whatever it lies on.
      case GHOST -> new Look(0, s.hover(), s.pressed(), 0, t.text().primary());
    };
  }
}
