package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.function.Consumer;

/**
 * A single-line text field: its text in a frame, a placeholder while it is empty, and the caret
 * and selection while it has the focus. The string belongs to the app, which passes the current
 * one and stores each new one its handler receives; {@link El#editable} says how editing, the
 * keys and the pointer work. The result is the field itself, so a view can go on with {@code
 * .key("name")}, {@code .maxLength(64)}, {@code .readOnly()} or {@code .w(...)}.
 *
 * <p>A field for a password is {@link #passwordField}, and {@link #revealable} puts a button
 * inside its frame that shows and hides the password:
 *
 * <pre>{@code
 * revealable(t, passwordField(t, s.password, null, v -> s.password = v).label("Password"),
 *     s.revealed, () -> s.revealed = !s.revealed)
 * }</pre>
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

  /**
   * A field for a password: {@link #textField}, {@link El#masked masked}, so that it shows a bullet
   * for each character and copies nothing. The result is the field itself, as of {@code
   * textField}; {@link #revealable} adds a button that shows the password.
   */
  public static El passwordField(
      Theme t, String value, String placeholder, Consumer<String> onChange) {
    return textField(t, value, placeholder, onChange).masked();
  }

  /**
   * Puts a button at the right inside the frame of a field of this class that shows and hides its
   * string, and masks the field while it is hidden. Whether it shows is the app's state, which
   * {@code onToggle} changes. The result is a box with the field and the button, as wide as the
   * field, so chain what concerns the field ({@code .key}, {@code .label}, {@code .w}, {@code
   * .readOnly()}) onto the field before.
   *
   * <p>The button ({@code role=button}, {@code label="Show password"} or {@code "Hide password"},
   * the eye or the crossed eye) leaves the focus in the field when it is pressed ({@link
   * El#keepFocusOnPress}), so typing goes on where it was. Tab reaches it after the field, and
   * Enter and Space press it there. It is disabled with the field and without a handler; a
   * read-only field shows its password too.
   *
   * @param field a field of {@link #textField} or {@link #passwordField}
   * @param revealed whether the string shows, from the app's state
   * @param onToggle runs on a press of the button; the app flips its state. Null disables it
   */
  public static El revealable(Theme t, El field, boolean revealed, Runnable onToggle) {
    boolean enabled = onToggle != null && (field.onEdit() != null || field.isReadOnly());
    float inset = t.space().xs();
    float size = t.size().control() - 2 * inset;
    Style pad = field.masked(!revealed).style();
    pad.paddingRight = Len.px(t.size().control()); // the text stops before the button
    El button =
        el().role("button")
            .label(revealed ? "Hide password" : "Show password")
            .disabled(!enabled)
            .size(size, size)
            .center()
            .radius(t.radius().small())
            .motion(t.motion().feedback())
            .child(icon(t, revealed ? Symbol.EYE_OFF : Symbol.EYE,
                enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    Style at = button.style();
    at.position = Style.Position.ABSOLUTE;
    at.right = Len.px(inset);
    at.top = Len.px(inset);
    if (enabled) {
      button.focusable()
          .keepFocusOnPress()
          .focusRing(t.border().focus())
          .cursor(Cursor.POINTER)
          .onPress(onToggle)
          .whenHovered(e -> e.fill(t.surface().hover()))
          .whenPressed(e -> e.fill(t.surface().pressed()));
    }
    El box = el().children(field, button);
    box.style().width = Len.FIT_CONTENT;
    return box;
  }
}
