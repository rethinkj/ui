package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.Icon.icon;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;

/** The compact icon buttons inside a field and the room its text leaves for them. */
final class FieldActions {

  private FieldActions() {}

  static float size(Theme t) {
    return t.size().control() - 2 * t.space().xs();
  }

  static El button(Theme t, Symbol symbol, String label, Runnable onPress) {
    return button(t, symbol, label, null, onPress);
  }

  /** A button, or a switch when {@code checked} is not null; the caller decides Tab stops. */
  static El button(Theme t, Symbol symbol, String label, Boolean checked, Runnable onPress) {
    boolean enabled = onPress != null;
    boolean on = checked != null && checked;
    float size = size(t);
    El button = el().role("button")
        .label(label)
        .disabled(!enabled)
        .size(size, size)
        .center()
        .radius(t.radius().small())
        .motion(t.motion().feedback())
        .child(icon(
            t,
            symbol,
            !enabled ? Icon.Variant.DISABLED : on ? Icon.Variant.NORMAL : Icon.Variant.MUTED));
    if (checked != null) {
      button.checked(on);
    }
    if (on) {
      button.fill(t.grid().selected()).border(t.size().stroke(), t.accent().edge());
    }
    if (!enabled) {
      return button;
    }
    int rest = on ? t.grid().selected() : 0;
    return button
        .keepFocus()
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(
            e -> e.fill(on ? Theme.over(rest, t.surface().hover()) : t.surface().hover()))
        .whenPressed(e ->
            e.fill(on ? Theme.over(rest, t.surface().pressed()) : t.surface().pressed()));
  }

  /** Places the actions inside the top right of the frame and reserves their text padding. */
  static El embed(Theme t, El field, El actions, float paddingRight) {
    field.style().paddingRight = Len.px(paddingRight);
    float inset = t.space().xs();
    Style at = actions.style();
    at.position = Style.Position.ABSOLUTE;
    at.right = Len.px(inset);
    at.top = Len.px(inset);
    El box = el().children(field, actions);
    box.style().width = Len.FIT_CONTENT;
    return box;
  }
}
