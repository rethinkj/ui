package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Theme.over;

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
 * revealable(theme,
 *     passwordField(theme, state.password, null, value -> state.password = value)
 *         .label("Password"),
 *     state.revealed, () -> state.revealed = !state.revealed)
 * }</pre>
 *
 * <p>A text area for a query, six lines high and as wide as its column:
 *
 * <pre>{@code
 * codeArea(theme, state.query, "SQL", value -> state.query = value)
 *     .key("query").lines(6).wFull()
 * }</pre>
 */
public final class TextField {

  private TextField() {}

  /** A field without placeholder; give it a label with {@code .label("...")}. */
  public static El textField(Theme theme, String value, Consumer<String> onChange) {
    return textField(theme, value, null, onChange);
  }

  /**
   * @param value the string to show, from the app's state
   * @param placeholder shown while the string is empty, and the label unless {@code .label(...)}
   *     sets another; null for none
   * @param onChange receives the new string after each edit; null disables the field
   */
  public static El textField(
      Theme theme, String value, String placeholder, Consumer<String> onChange) {
    float m = theme.space().m();
    return frame(
            theme, El.text(value).editable(onChange), theme.type().body(), placeholder, onChange)
        .h(theme.size().control())
        .padding(0, m, theme.size().stroke(), m); // the bottom's padding stands in for its border
  }

  /**
   * A field of a {@link Prop}: its string, written back on each edit, with the error of its field
   * when the prop comes from an {@link Editing}. A prop that cannot be written disables it.
   */
  public static El textField(Theme theme, Prop<String> value) {
    return textField(theme, value, null);
  }

  /** A field of a {@link Prop} with a placeholder, as {@link #textField(Theme, Prop)}. */
  public static El textField(Theme theme, Prop<String> value, String placeholder) {
    String text = value.get();
    return Prop.shown(
        theme, textField(theme, text == null ? "" : text, placeholder, value.onChange()), value);
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
  public static El textArea(
      Theme theme, String value, String placeholder, Consumer<String> onChange) {
    return area(theme, theme.type().body(), value, placeholder, onChange).wrapText();
  }

  /**
   * A text area for code, such as an SQL statement: {@link #textArea} in the mono type, whose
   * lines do not wrap, so that it scrolls sideways too, and in which Tab and Shift+Tab indent by
   * four spaces ({@link El#tabIndents}); Escape and then Tab, or Control+M, let Tab move the
   * focus. Words are those of code ({@link El#codeWords}): a double press on {@code orders} in
   * {@code shop.public.orders} selects that name alone.
   */
  public static El codeArea(
      Theme theme, String value, String placeholder, Consumer<String> onChange) {
    El area = area(theme, theme.type().mono(), value, placeholder, onChange)
        .tabIndents(true)
        .codeWords();
    // Below the last line, the room of the bar along the bottom, which shows for a long line.
    Scrollbars bars = theme.scrollbars();
    area.style().paddingBottom =
        Len.px(theme.space().s() + theme.size().stroke() + bars.thickness() + bars.margin());
    return area;
  }

  private static El area(
      Theme theme,
      Theme.TextStyle style,
      String value,
      String placeholder,
      Consumer<String> onChange) {
    return frame(theme, El.text(value).editable(onChange).multiline(), style, placeholder, onChange)
        .lines(4)
        .padding(
            theme.space().s(),
            theme.space().m(),
            theme.space().s() + theme.size().stroke(),
            theme.space().m())
        .scrollbars(theme.scrollbars());
  }

  /** The look a field and an area share: type, colors, frame and the looks of their states. */
  private static El frame(
      Theme theme, El field, Theme.TextStyle style, String placeholder, Consumer<String> onChange) {
    boolean enabled = onChange != null;
    int fill = theme.surface().raised();
    field
        .focusRing(theme.accent().edge())
        .label(placeholder)
        .font(style.font().get())
        .fontSize(style.size())
        .color(enabled ? theme.text().primary() : theme.text().disabled())
        .selectionColor(theme.accent().selection())
        .w(theme.size().field())
        .radius(theme.radius().medium())
        .fill(fill)
        .motion(theme.motion().feedback());
    if (enabled) {
      edge(theme, field, theme.border().control(), theme.border().strong());
    } else {
      edge(theme, field, theme.border().divider(), theme.border().divider());
    }
    if (placeholder != null) {
      field.placeholder(
          placeholder, enabled ? theme.text().muted() : theme.text().disabled());
    }
    if (!enabled) {
      return field;
    }
    int hover = over(fill, theme.surface().hover());
    return field
        .whenHovered(e -> {
          e.fill(hover);
          if (e.borderColor() != theme.status().danger()) { // an invalid field keeps its frame
            edge(theme, e, theme.border().strong(), theme.border().strong());
          }
        })
        .whenFocused(e -> edge(theme, e, theme.accent().color(), theme.accent().color()));
  }

  /**
   * The frame of a field: {@code frame} on top and at the sides, and {@code edge} along the
   * bottom, the line a field is recognised by. The edge is a sharp shadow one stroke below the
   * box, so that it follows the lower corners; a field has no bottom border and pads its bottom
   * by a stroke instead, which keeps its content where a frame all round would.
   */
  static El edge(Theme theme, El field, int frame, int edge) {
    float w = theme.size().stroke();
    return field.border(w, w, 0, w, frame).shadow(Theme.fieldEdge(w, edge));
  }

  /**
   * Gives a field of this class the look of a value that is not accepted, such as a form's field
   * with an error: its whole frame in the danger color, also under the pointer and while it has
   * the focus, and {@code invalid} in a tree dump ({@link El#invalid}).
   *
   * @return the field
   */
  public static El invalid(Theme theme, El field) {
    int danger = theme.status().danger();
    return edge(theme, field.invalid(true), danger, danger).whenFocused(null);
  }

  /**
   * A field for a password: {@link #textField}, {@link El#masked masked}, so that it shows a bullet
   * for each character and copies nothing. The result is the field itself, as of {@code
   * textField}; {@link #revealable} adds a button that shows the password.
   */
  public static El passwordField(
      Theme theme, String value, String placeholder, Consumer<String> onChange) {
    return textField(theme, value, placeholder, onChange).masked();
  }

  /** A password field of a {@link Prop}, as {@link #textField(Theme, Prop, String)}. */
  public static El passwordField(Theme theme, Prop<String> value, String placeholder) {
    return textField(theme, value, placeholder).masked();
  }

  /**
   * Puts a button at the right inside the frame of a field of this class that shows and hides its
   * string on the screen. The field is {@link El#masked masked} also while it shows, and then {@link
   * El#revealed revealed}: it is drawn, copied and edited as an ordinary field, but the tree dump,
   * selectors and error messages see only the mask, with the states {@code password revealed}.
   * Whether it shows is the app's state, which {@code onToggle} changes. The result is a box with the field and the button, as wide as the
   * field, so chain what concerns the field ({@code .key}, {@code .label}, {@code .w}, {@code
   * .readOnly()}) onto the field before.
   *
   * <p>The button ({@code role=button}, {@code label="Show password"} or {@code "Hide password"},
   * the eye or the crossed eye) leaves the focus in the field when it is pressed ({@link
   * El#keepFocus}), so typing goes on where it was. Tab reaches it after the field, and Enter and
   * Space press it there; meanwhile the field's frame keeps the accent color of the focus ({@link
   * El#whenFocusWithin}), as the button is part of it, or the danger color of an {@link #invalid}
   * field. It is disabled with the field and without a
   * handler; a read-only field shows its password too.
   *
   * @param field a field of {@link #textField} or {@link #passwordField}
   * @param revealed whether the string shows, from the app's state
   * @param onToggle runs on a press of the button; the app flips its state. Null disables it
   */
  public static El revealable(Theme theme, El field, boolean revealed, Runnable onToggle) {
    boolean enabled = onToggle != null && (field.onEdit() != null || field.isReadOnly());
    field.masked().revealed(revealed);
    El button = FieldActions.button(
        theme,
        revealed ? Symbol.EYE_OFF : Symbol.EYE,
        revealed ? "Hide password" : "Show password",
        enabled ? onToggle : null);
    if (enabled) {
      button.focusable().focusRing(theme.accent().edge());
    }
    El box = FieldActions.embed(theme, field, button, theme.size().control());
    if (field.onEdit() != null) { // the frame keeps its focused look while the button has the focus
      box.whenFocusWithin(e -> {
        // An invalid field (#invalid, also set after this by a form) keeps its danger border.
        if (field.borderColor() != theme.status().danger()) {
          edge(theme, field, theme.accent().color(), theme.accent().color());
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
  public static El withIcon(Theme theme, El field, Symbol symbol) {
    Len height = field.style().height; // the icon in a square as high as the field
    float side = height.kind() == Len.Kind.PX ? height.value() : theme.size().control();
    field.style().paddingLeft = Len.px(side); // the text starts after the icon
    El mark =
        icon(theme, symbol, field.onEdit() != null ? Icon.Variant.MUTED : Icon.Variant.DISABLED);
    Style at = mark.style();
    at.position = Style.Position.ABSOLUTE;
    at.left = Len.px((side - theme.size().icon()) / 2);
    at.top = Len.px((side - theme.size().icon()) / 2);
    El box = el().children(field, mark);
    box.style().width = Len.FIT_CONTENT;
    return box;
  }
}
