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
  public static El checkbox(Prop<Boolean> checked, String text) {
    Theme theme = Theme.CURRENT.get();
    boolean now = Boolean.TRUE.equals(checked.get());
    return Prop.shown(
        theme, checkbox(now, text, checked.writes() ? () -> checked.set(!now) : null), checked);
  }

  /** A checkbox without text; give it a label with {@code .label("...")}. */
  public static El checkbox(boolean checked, Runnable onToggle) {
    return checkbox(checked, null, onToggle);
  }

  /**
   * @param text shown beside the box, also its label; null for none
   * @param onToggle runs when the checkbox is pressed; null disables it
   */
  public static El checkbox(boolean checked, String text, Runnable onToggle) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onToggle != null;
    Theme.Surfaces s = theme.surface();
    int fill = checked ? (enabled ? theme.accent().color() : theme.border().divider()) : s.raised();
    int edge = !enabled
        ? theme.border().divider()
        : checked ? theme.accent().pressed() : theme.border().strong();
    float side = theme.size().checkbox();
    El box = el().size(side, side)
        .center()
        .radius(theme.radius().small())
        .fill(fill)
        .border(theme.size().stroke(), edge)
        .motion(theme.motion().feedback());
    if (checked) {
      box.child(
          label("✓").color(enabled ? theme.text().onAccent() : theme.text().disabled()));
    }
    return ControlRow.controlRow(
        theme, "checkbox", checked, text, onToggle, box, theme.radius().medium(), fill);
  }
}
