package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;

/** A button with text. A press runs its handler, and so do Enter and Space while it is focused. */
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
    Look look = look(t, variant, onPress != null);
    El button =
        row().role("button")
            .label(text)
            .h(t.size().control())
            .padding(0, t.space().l())
            .center()
            .radius(t.radius().medium())
            .fill(look.fill())
            .border(t.size().stroke(), look.edge())
            .motion(t.motion().feedback())
            .child(label(t, text).color(look.text()));
    if (onPress == null) {
      return button;
    }
    return button.focusable()
        .focusRing(t.border().focus())
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
          : new Look(s.raised(), s.raised(), s.raised(), t.border().subtle(), t.text().disabled());
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
              t.border().subtle(),
              t.text().primary());
      // The overlays are translucent, so a ghost button shows whatever it lies on.
      case GHOST -> new Look(0, s.hover(), s.pressed(), 0, t.text().primary());
    };
  }
}
