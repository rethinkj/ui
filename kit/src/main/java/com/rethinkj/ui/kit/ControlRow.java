package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;

/** The labelled, focusable row shared by checkboxes and switches. */
final class ControlRow {

  private ControlRow() {}

  static El controlRow(
      Theme t,
      String role,
      boolean checked,
      String text,
      Runnable onToggle,
      El control,
      float radius,
      int fill) {
    boolean enabled = onToggle != null;
    El row = row()
        .role(role)
        .label(text)
        .checked(checked)
        .disabled(!enabled)
        .gap(t.space().m())
        // Room for the focus ring around the text, taken back by the margin, so that the
        // row lines up with what is above and below it.
        .padding(t.space().xs(), t.space().s())
        .margin(-t.space().xs(), -t.space().s(), -t.space().xs(), -t.space().s())
        .align(Style.Align.CENTER)
        .radius(radius)
        .child(control);
    // Only as wide as the control and its text, so that a column that stretches its children
    // stretches neither the press area nor the ring; one that centres them still centres it.
    row.style().width = Len.FIT_CONTENT;
    if (text != null) {
      row.child(label(t, text).color(enabled ? t.text().primary() : t.text().disabled()));
    }
    if (!enabled) {
      return row;
    }
    int hover = checked ? t.accent().hover() : over(fill, t.surface().hover());
    int pressed = checked ? t.accent().pressed() : over(fill, t.surface().pressed());
    // The whole row takes the press, text included, and its looks restyle the control.
    return row.focusable()
        .keepFocus() // as a button: Tab reaches it, a press leaves the focus where it is
        .focusRing(t.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onToggle)
        .whenHovered(e -> control.fill(hover))
        .whenPressed(e -> control.fill(pressed));
  }
}
