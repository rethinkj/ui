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

  static float size(Theme theme) {
    return theme.size().control() - 2 * theme.space().xs();
  }

  static El button(Theme theme, Icon icon, String label, Runnable onPress) {
    return button(theme, icon, label, null, onPress);
  }

  /** A button that clears a field or choice, leaving the focus where it is. */
  static El clearButton(Theme theme, Runnable onClear) {
    return button(theme, Symbol.DISMISS, Texts.CURRENT.get().clear(), onClear);
  }

  /** A button, or a switch when {@code checked} is not null; the caller decides Tab stops. */
  static El button(Theme theme, Icon icon, String label, Boolean checked, Runnable onPress) {
    boolean enabled = onPress != null;
    boolean on = checked != null && checked;
    float size = size(theme);
    El button = el().role("button")
        .label(label)
        .disabled(!enabled)
        .size(size, size)
        .center()
        .radius(theme.radius().small())
        .motion(theme.motion().feedback())
        .child(icon(
            icon,
            !enabled ? Icon.Variant.DISABLED : on ? Icon.Variant.NORMAL : Icon.Variant.MUTED));
    if (checked != null) {
      button.checked(on);
    }
    if (on) {
      button
          .fill(theme.grid().selected())
          .border(theme.size().stroke(), theme.accent().edge());
    }
    if (!enabled) {
      return button;
    }
    int rest = on ? theme.grid().selected() : 0;
    return button
        .keepFocus()
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(
            on ? Theme.over(rest, theme.surface().hover()) : theme.surface().hover()))
        .whenPressed(e -> e.fill(
            on ? Theme.over(rest, theme.surface().pressed()) : theme.surface().pressed()));
  }

  /** Places the actions inside the top right of the frame and reserves their text padding. */
  static El embed(Theme theme, El field, El actions, float paddingRight) {
    El box = el().child(field);
    box.style().width = Len.FIT_CONTENT;
    return place(theme, box, field, actions, paddingRight);
  }

  /** Adds {@code actions} inside the top right of {@code field}'s box and reserves their padding. */
  static El place(Theme theme, El box, El field, El actions, float paddingRight) {
    field.style().paddingRight = Len.px(paddingRight);
    float inset = theme.space().xs();
    Style at = actions.style();
    at.position = Style.Position.ABSOLUTE;
    at.right = Len.px(inset);
    at.top = Len.px(inset);
    return box.child(actions);
  }
}
