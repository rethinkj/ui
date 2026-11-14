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
  public static El toggle(Theme t, Prop<Boolean> on, String text) {
    boolean now = Boolean.TRUE.equals(on.get());
    return Prop.shown(t, toggle(t, now, text, on.writes() ? () -> on.set(!now) : null), on);
  }

  /** A switch without text; give it a label with {@code .label("...")}. */
  public static El toggle(Theme t, boolean on, Runnable onToggle) {
    return toggle(t, on, null, onToggle);
  }

  /**
   * @param text shown beside the switch, also its label; null for none
   * @param onToggle runs when the switch is pressed; null disables it
   */
  public static El toggle(Theme t, boolean on, String text, Runnable onToggle) {
    boolean enabled = onToggle != null;
    float height = t.size().switchHeight();
    int fill = fill(t, on, enabled);
    El track = track(t, on, enabled);
    return ControlRow.controlRow(
        t, "switch", on, text, onToggle, track, height / 2 + t.space().xs(), fill);
  }

  /** The track with its knob, which takes no input: the element around it does. */
  static El track(Theme t, boolean on, boolean enabled) {
    float width = t.size().switchWidth();
    float height = t.size().switchHeight();
    float inset = t.space().xs();
    float knob = height - 2 * inset;
    return el().size(width, height)
        .radius(height / 2)
        .fill(fill(t, on, enabled))
        .motion(t.motion().feedback())
        .child(el().size(knob, knob)
            .radius(knob / 2)
            .fill(enabled ? t.text().onAccent() : t.text().disabled())
            .absolute(on ? width - inset - knob : inset, inset)
            .motion(t.motion().move()));
  }

  private static int fill(Theme t, boolean on, boolean enabled) {
    return !enabled
        ? t.border().divider()
        : on ? t.accent().color() : t.border().strong();
  }
}
