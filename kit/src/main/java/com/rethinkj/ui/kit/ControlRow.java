package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;

/** The labeled, focusable row shared by checkboxes and switches. */
final class ControlRow {

  private ControlRow() {}

  static El controlRow(
      Theme theme,
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
        .gap(theme.space().m())
        // Room for the focus ring around the text, taken back by the margin, so that the
        // row lines up with what is above and below it.
        .padding(theme.space().xs(), theme.space().s())
        .margin(
            -theme.space().xs(),
            -theme.space().s(),
            -theme.space().xs(),
            -theme.space().s())
        .align(Style.Align.CENTER)
        .radius(radius)
        .child(control);
    // Only as wide as the control and its text, so that a column that stretches its children
    // stretches neither the press area nor the ring; one that centers them still centers it.
    row.style().width = Len.FIT_CONTENT;
    if (text != null) {
      row.child(label(theme, text)
          .color(enabled ? theme.text().primary() : theme.text().disabled()));
    }
    if (!enabled) {
      return row;
    }
    int hover = checked ? theme.accent().hover() : over(fill, theme.surface().hover());
    int pressed =
        checked ? theme.accent().pressed() : over(fill, theme.surface().pressed());
    // The whole row takes the press, text included, and its looks restyle the control.
    return row.focusable()
        .keepFocus() // as a button: Tab reaches it, a press leaves the focus where it is
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onToggle)
        .whenHovered(e -> control.fill(hover))
        .whenPressed(e -> control.fill(pressed));
  }
}
