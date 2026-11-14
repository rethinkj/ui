package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;

/**
 * A box with a check mark, and an optional text beside it. A press on the box or on its text
 * checks or clears it, and so do Enter and Space while it has the focus. Tab reaches it; a press
 * leaves the focus where it is, as a {@link Button}'s does.
 */
public final class Checkbox {

  private Checkbox() {}

  /**
   * A checkbox of a {@link Prop}, which a press flips; a null value shows as off. A prop that cannot
   * be written disables it.
   *
   * @param text shown beside it, also its label; null for none
   */
  public static El checkbox(Theme t, Prop<Boolean> checked, String text) {
    boolean now = Boolean.TRUE.equals(checked.get());
    return Prop.shown(
        t, checkbox(t, now, text, checked.writes() ? () -> checked.set(!now) : null), checked);
  }

  /** A checkbox without text; give it a label with {@code .label("...")}. */
  public static El checkbox(Theme t, boolean checked, Runnable onToggle) {
    return checkbox(t, checked, null, onToggle);
  }

  /**
   * @param text shown beside the box, also its label; null for none
   * @param onToggle runs when the checkbox is pressed; null disables it
   */
  public static El checkbox(Theme t, boolean checked, String text, Runnable onToggle) {
    boolean enabled = onToggle != null;
    Theme.Surfaces s = t.surface();
    int fill = checked ? (enabled ? t.accent().color() : t.border().divider()) : s.raised();
    int edge = !enabled
        ? t.border().divider()
        : checked ? t.accent().pressed() : t.border().strong();
    float side = t.size().checkbox();
    El box = el().size(side, side)
        .center()
        .radius(t.radius().small())
        .fill(fill)
        .border(t.size().stroke(), edge)
        .motion(t.motion().feedback());
    if (checked) {
      box.child(label(t, "✓").color(enabled ? t.text().onAccent() : t.text().disabled()));
    }
    return ControlRow.controlRow(
        t, "checkbox", checked, text, onToggle, box, t.radius().medium(), fill);
  }
}
