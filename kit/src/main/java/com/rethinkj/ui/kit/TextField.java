package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.El;
import java.util.function.Consumer;

/**
 * A single-line text field: its text in a frame, a placeholder while it is empty, and the caret
 * and selection while it has the focus. The string belongs to the app, which passes the current
 * one and stores each new one its handler receives; {@link El#editable} says how editing, the
 * keys and the pointer work. The result is the field itself, so a view can go on with {@code
 * .key("name")}, {@code .maxLength(64)}, {@code .readOnly()} or {@code .w(...)}.
 */
public final class TextField {

  private TextField() {}

  /** A field without placeholder; give it a label with {@code .label("...")}. */
  public static El textField(Theme t, String value, Consumer<String> onChange) {
    return textField(t, value, null, onChange);
  }

  /**
   * @param value the string to show, from the app's state
   * @param placeholder shown while the string is empty, and the label unless {@code .label(...)}
   *     sets another; null for none
   * @param onChange receives the new string after each edit; null disables the field
   */
  public static El textField(
      Theme t, String value, String placeholder, Consumer<String> onChange) {
    boolean enabled = onChange != null;
    Theme.TextStyle style = t.type().body();
    int fill = t.surface().raised();
    El field =
        El.text(value)
            .editable(onChange)
            .focusRing(t.border().focus())
            .label(placeholder)
            .font(style.font().get())
            .fontSize(style.size())
            .color(enabled ? t.text().primary() : t.text().disabled())
            .selectionColor(t.accent().selection())
            .w(t.size().field())
            .h(t.size().control())
            .padding(0, t.space().m())
            .radius(t.radius().medium())
            .fill(fill)
            .border(t.size().stroke(), enabled ? t.border().strong() : t.border().subtle())
            .motion(t.motion().feedback());
    if (placeholder != null) {
      field.placeholder(placeholder, enabled ? t.text().muted() : t.text().disabled());
    }
    if (!enabled) {
      return field;
    }
    int hover = over(fill, t.surface().hover());
    return field
        .whenHovered(e -> e.fill(hover))
        .whenFocused(e -> e.border(t.size().stroke(), t.accent().color()));
  }
}
