package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/**
 * A box with a check mark, and an optional text beside it. A press on the box or on its text
 * checks or clears it, and so do Enter and Space while it has the focus.
 */
public final class Checkbox {

  private Checkbox() {}

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
    int fill = checked ? (enabled ? t.accent().color() : t.border().subtle()) : s.raised();
    int edge = !enabled ? t.border().subtle() : checked ? t.accent().pressed() : t.border().strong();
    float side = t.size().checkbox();
    El box =
        el().size(side, side)
            .center()
            .radius(t.radius().small())
            .fill(fill)
            .border(t.size().stroke(), edge)
            .motion(t.motion().feedback());
    if (checked) {
      box.child(label(t, "✓").color(enabled ? t.text().onAccent() : t.text().disabled()));
    }
    El checkbox =
        row().role("checkbox")
            .label(text)
            .gap(t.space().m())
            // Room for the focus ring around the text, taken back by the margin, so that the
            // row lines up with what is above and below it.
            .padding(t.space().xs(), t.space().s())
            .margin(-t.space().xs(), -t.space().s(), -t.space().xs(), -t.space().s())
            .align(Style.Align.CENTER)
            .radius(t.radius().medium())
            .child(box);
    if (text != null) {
      checkbox.child(label(t, text).color(enabled ? t.text().primary() : t.text().disabled()));
    }
    if (!enabled) {
      return checkbox;
    }
    int hover = checked ? t.accent().hover() : over(fill, s.hover());
    int pressed = checked ? t.accent().pressed() : over(fill, s.pressed());
    // The whole row takes the press, text included, and its looks restyle the box.
    return checkbox.focusable()
        .cursor(Cursor.POINTER)
        .onPress(onToggle)
        .whenHovered(e -> box.fill(hover))
        .whenPressed(e -> box.fill(pressed));
  }
}
