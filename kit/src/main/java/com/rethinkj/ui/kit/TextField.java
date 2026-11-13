package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Style;
import java.util.function.Consumer;

/**
 * A single-line text field: its text in a frame, a placeholder while it is empty, and the caret
 * and selection while it has the focus; and the text area, a field of several lines in the same
 * frame ({@link #textArea}, {@link #codeArea}). The string belongs to the app, which passes the current
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
 *
 * <p>A text area for a query, six lines high and as wide as its column:
 *
 * <pre>{@code
 * codeArea(t, s.query, "SQL", v -> s.query = v).key("query").lines(6).wFull()
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
  public static El textField(Theme t, String value, String placeholder, Consumer<String> onChange) {
    return frame(t, El.text(value).editable(onChange), t.type().body(), placeholder, onChange)
        .h(t.size().control())
        .padding(0, t.space().m());
  }

  /**
   * A field of a {@link Prop}: its string, written back on each edit, with the error of its field
   * when the prop comes from an {@link Editing}. A prop that cannot be written disables it.
   */
  public static El textField(Theme t, Prop<String> value) {
    return textField(t, value, null);
  }

  /** A field of a {@link Prop} with a placeholder, as {@link #textField(Theme, Prop)}. */
  public static El textField(Theme t, Prop<String> value, String placeholder) {
    String text = value.get();
    return Prop.shown(
        t, textField(t, text == null ? "" : text, placeholder, value.onChange()), value);
  }

  /**
   * A text area: a field of several lines ({@link El#multiline}) in the frame of {@link
   * #textField}, in the body type, that wraps its lines at its width and is four lines high, with
   * the theme's scrollbars. Enter breaks a line; Tab moves the focus. The result is the field, so a
   * view goes on with {@code .lines(n)} for another height, {@code .h(...)} or {@code .grow(1)} to
   * let layout size it, {@code .wrapText(false)}, {@code .readOnly()} or {@code .w(...)}.
   *
   * @param value the string to show, from the app's state, with a line feed for each line break
   * @param placeholder shown while the string is empty, and the label unless {@code .label(...)}
   *     sets another; null for none
   * @param onChange receives the new string after each edit; null disables the area
   */
  public static El textArea(Theme t, String value, String placeholder, Consumer<String> onChange) {
    return area(t, t.type().body(), value, placeholder, onChange).wrapText();
  }

  /**
   * A text area for code, such as an SQL statement: {@link #textArea} in the mono type, whose
   * lines do not wrap, so that it scrolls sideways too, and in which Tab and Shift+Tab indent by
   * four spaces ({@link El#tabIndents}); Escape and then Tab, or Control+M, let Tab move the
   * focus. Words are those of code ({@link El#codeWords}): a double press on {@code orders} in
   * {@code shop.public.orders} selects that name alone.
   */
  public static El codeArea(Theme t, String value, String placeholder, Consumer<String> onChange) {
    El area =
        area(t, t.type().mono(), value, placeholder, onChange).tabIndents(true).codeWords();
    // Below the last line, the room of the bar along the bottom, which shows for a long line.
    Scrollbars bars = t.scrollbars();
    area.style().paddingBottom = Len.px(t.space().s() + bars.thickness() + bars.margin());
    return area;
  }

  private static El area(
      Theme t, Theme.TextStyle style, String value, String placeholder, Consumer<String> onChange) {
    return frame(t, El.text(value).editable(onChange).multiline(), style, placeholder, onChange)
        .lines(4)
        .padding(t.space().s(), t.space().m())
        .scrollbars(t.scrollbars());
  }

  /** The look a field and an area share: type, colours, frame and the looks of their states. */
  private static El frame(
      Theme t, El field, Theme.TextStyle style, String placeholder, Consumer<String> onChange) {
    boolean enabled = onChange != null;
    int fill = t.surface().raised();
    field
        .focusRing(t.accent().edge())
        .label(placeholder)
        .font(style.font().get())
        .fontSize(style.size())
        .color(enabled ? t.text().primary() : t.text().disabled())
        .selectionColor(t.accent().selection())
        .w(t.size().field())
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
   * Gives a field of this class the look of a value that is not accepted, such as a form's field
   * with an error: the danger colour's border, also while it has the focus, and {@code invalid} in
   * a tree dump ({@link El#invalid}).
   *
   * @return the field
   */
  public static El invalid(Theme t, El field) {
    return field.invalid(true).border(t.size().stroke(), t.status().danger()).whenFocused(null);
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

  /** A password field of a {@link Prop}, as {@link #textField(Theme, Prop, String)}. */
  public static El passwordField(Theme t, Prop<String> value, String placeholder) {
    return textField(t, value, placeholder).masked();
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
   * El#keepFocus}), so typing goes on where it was. Tab reaches it after the field, and Enter and
   * Space press it there; meanwhile the field's frame keeps the accent border of the focus ({@link
   * El#whenFocusWithin}), as the button is part of it, or the danger border of an {@link #invalid}
   * field. It is disabled with the field and without a
   * handler; a read-only field shows its password too.
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
    El button = el().role("button")
        .label(revealed ? "Hide password" : "Show password")
        .disabled(!enabled)
        .size(size, size)
        .center()
        .radius(t.radius().small())
        .motion(t.motion().feedback())
        .child(icon(
            t,
            revealed ? Symbol.EYE_OFF : Symbol.EYE,
            enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    Style at = button.style();
    at.position = Style.Position.ABSOLUTE;
    at.right = Len.px(inset);
    at.top = Len.px(inset);
    if (enabled) {
      button
          .focusable()
          .keepFocus()
          .focusRing(t.accent().edge())
          .cursor(Cursor.POINTER)
          .onPress(onToggle)
          .whenHovered(e -> e.fill(t.surface().hover()))
          .whenPressed(e -> e.fill(t.surface().pressed()));
    }
    El box = el().children(field, button);
    box.style().width = Len.FIT_CONTENT;
    if (field.onEdit() != null) { // the frame keeps its focused look while the button has the focus
      box.whenFocusWithin(e -> {
        // An invalid field (#invalid, also set after this by a form) keeps its danger border.
        if (field.borderColor() != t.status().danger()) {
          field.border(t.size().stroke(), t.accent().color());
        }
      });
    }
    return box;
  }

  /**
   * Puts an icon at the left inside the frame of a field of this class, in muted text, such as a
   * magnifier before a filter. The result is a box with the field and the icon, as wide as the
   * field, so chain what concerns the field ({@code .key}, {@code .label}, {@code .w}) onto the
   * field before. The icon takes no press: one on it reaches the box, not the field.
   *
   * @param field a field of {@link #textField}
   */
  public static El withIcon(Theme t, El field, Symbol symbol) {
    float side = t.size().control();
    field.style().paddingLeft = Len.px(side); // the text starts after the icon
    El mark = icon(t, symbol, field.onEdit() != null ? Icon.Variant.MUTED : Icon.Variant.DISABLED);
    Style at = mark.style();
    at.position = Style.Position.ABSOLUTE;
    at.left = Len.px((side - t.size().icon()) / 2);
    at.top = Len.px((side - t.size().icon()) / 2);
    El box = el().children(field, mark);
    box.style().width = Len.FIT_CONTENT;
    return box;
  }
}
