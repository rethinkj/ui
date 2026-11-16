package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;

/**
 * An on/off switch, with an optional text beside it. A press on the switch or on its text flips
 * it, and so do Enter and Space while it has the focus. Tab reaches it; a press leaves the focus
 * where it is, as a {@link Button}'s does.
 */
public final class Toggle {

  private Toggle() {}

  /**
   * A toggle of a {@link Prop}, which a press flips; a null value shows as off. A prop that cannot
   * be written disables it.
   *
   * @param text shown beside it, also its label; null for none
   */
  public static El toggle(Prop<Boolean> on, String text) {
    Theme theme = Theme.CURRENT.get();
    boolean now = Boolean.TRUE.equals(on.get());
    return Prop.shown(theme, toggle(now, text, on.writes() ? () -> on.set(!now) : null), on);
  }

  /** A switch without text; give it a label with {@code .label("...")}. */
  public static El toggle(boolean on, Runnable onToggle) {
    return toggle(on, null, onToggle);
  }

  /**
   * @param text shown beside the switch, also its label; null for none
   * @param onToggle runs when the switch is pressed; null disables it
   */
  public static El toggle(boolean on, String text, Runnable onToggle) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onToggle != null;
    float height = theme.size().switchHeight();
    int fill = fill(theme, on, enabled);
    El track = track(theme, on, enabled);
    return ControlRow.controlRow(
        theme, "switch", on, text, onToggle, track, height / 2 + theme.space().xs(), fill);
  }

  /** The track with its knob, which takes no input: the element around it does. */
  static El track(Theme theme, boolean on, boolean enabled) {
    float width = theme.size().switchWidth();
    float height = theme.size().switchHeight();
    float inset = theme.space().xs();
    float knob = height - 2 * inset;
    return el().size(width, height)
        .radius(height / 2)
        .fill(fill(theme, on, enabled))
        .motion(theme.motion().feedback())
        .child(el().size(knob, knob)
            .radius(knob / 2)
            .fill(enabled ? theme.text().onAccent() : theme.text().disabled())
            .absolute(on ? width - inset - knob : inset, inset)
            .motion(theme.motion().move()));
  }

  private static int fill(Theme theme, boolean on, boolean enabled) {
    return !enabled
        ? theme.border().divider()
        : on ? theme.accent().color() : theme.border().strong();
  }
}
