package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;

/**
 * An on/off switch, with an optional text beside it. A press on the switch or on its text flips
 * it, and so do Enter and Space while it has the focus. Tab reaches it; a press leaves the focus
 * where it is, as a {@link Button}'s does.
 */
public final class Toggle {

  private Toggle() {}

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
    El toggle =
        row().role("switch")
            .label(text)
            .checked(on)
            .disabled(!enabled)
            .gap(t.space().m())
            // Room for the focus ring around the text, taken back by the margin, so that the
            // row lines up with what is above and below it.
            .padding(t.space().xs(), t.space().s())
            .margin(-t.space().xs(), -t.space().s(), -t.space().xs(), -t.space().s())
            .align(Style.Align.CENTER)
            .radius(height / 2 + t.space().xs())
            .child(track);
    // Only as wide as the control and its text, so that a column that stretches its children
    // stretches neither the press area nor the ring; one that centres them still centres it.
    toggle.style().width = Len.FIT_CONTENT;
    if (text != null) {
      toggle.child(label(t, text).color(enabled ? t.text().primary() : t.text().disabled()));
    }
    if (!enabled) {
      return toggle;
    }
    int hover = on ? t.accent().hover() : over(fill, t.surface().hover());
    int pressed = on ? t.accent().pressed() : over(fill, t.surface().pressed());
    // The whole row takes the press, text included, and its looks restyle the track.
    return toggle.focusable()
        .keepFocus() // as a button: Tab reaches it, a press leaves the focus where it is
        .focusRing(t.border().focus())
        .cursor(Cursor.POINTER)
        .onPress(onToggle)
        .whenHovered(e -> track.fill(hover))
        .whenPressed(e -> track.fill(pressed));
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
        .child(
            el().size(knob, knob)
                .radius(knob / 2)
                .fill(enabled ? t.text().onAccent() : t.text().disabled())
                .absolute(on ? width - inset - knob : inset, inset)
                .motion(t.motion().move()));
  }

  private static int fill(Theme t, boolean on, boolean enabled) {
    return !enabled ? t.border().subtle() : on ? t.accent().color() : t.border().strong();
  }
}
