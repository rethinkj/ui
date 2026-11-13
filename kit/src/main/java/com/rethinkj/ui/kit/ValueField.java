package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A text field for a value of a type of the app's, such as a {@code BigDecimal}, a {@code
 * LocalDate} or an enum: the {@link TextField#textField} of the value's text in a {@link Format},
 * which hands the app values instead of strings.
 *
 * <pre>{@code
 * class State {
 *   BigDecimal amount = new BigDecimal("12.50");
 *   final ValueField.Draft amountDraft = new ValueField.Draft();
 * }
 *
 * valueField(t, Format.DECIMAL, s.amount, s.amountDraft, v -> s.amount = v).w(120)
 * }</pre>
 *
 * <p>Between two values the user types texts that are none, such as {@code 12e} or a date half
 * typed. The field keeps such a text in a {@link Draft}, which the app keeps in its state as it
 * keeps a {@link Menu.State}: the field shows the draft, has the danger border ({@link
 * TextField#invalid}), is {@code invalid} in a tree dump with the format's message as {@code
 * error="..."} ({@link El#errorMessage}), and {@link Form#field} shows that message below it.
 * The app's value stays the last valid one meanwhile; {@link Draft#error} says whether the text
 * stands for it, which a Save button checks.
 *
 * <ul>
 *   <li>The handler runs on every edit whose text is valid, as a text field's does on every
 *       edit, so the state always holds the value the field shows and a button that saves needs
 *       nothing to happen first. An edit to a text that {@link Format#allows} refuses, such as a
 *       letter in a number, is refused: the field stays as it was.
 *   <li>The empty text is what the format says: {@link Format#REQUIRED} as a message for a format
 *       as it is, null handed on for an {@link Format#optional} one, the empty string for {@link
 *       Format#TEXT}.
 *   <li>A valid text stays as typed while the value is the one it stands for: {@code 007} in a
 *       field of integers stays {@code 007} and the app has 7. A value the app changes from
 *       outside, one other than the last handed on, replaces the draft with its text; so does a
 *       value the handler stores in place of the one it was given, or refuses to store.
 * </ul>
 *
 * <p>{@link NumberField} stays the field for an {@code int} in a range that the arrows and its
 * buttons step through; this field is for any format, and steps through nothing.
 */
public final class ValueField {

  private ValueField() {}

  /**
   * The text of a {@link ValueField} while it is not its value's: kept in the app's state, one per
   * field, so that a text that is no value yet survives the rebuilds of the view.
   */
  public static final class Draft {
    private boolean started; // whether the field was built with it
    private Object value; // the app's value the text belongs to: shown, or last handed on
    private String text;
    private String error;

    /** The text the field shows. */
    public String text() {
      return text;
    }

    /** What is wrong with the text, from its format; null when it stands for the app's value. */
    public String error() {
      return error;
    }

    /** Forgets the text, so that the field shows the app's value again, as after a reset. */
    public void clear() {
      started = false;
      text = null;
      error = null;
    }

    /** Takes the app's value, unless it is the one the text belongs to. */
    <T> void show(Format<T> format, T value) {
      if (!started || !Objects.equals(value, this.value)) {
        started = true;
        this.value = value;
        text = format.text(value);
        error = null;
      }
    }

    @Override
    public String toString() {
      return "Draft[" + text + (error == null ? "" : ", " + error) + "]";
    }
  }

  /** A field without placeholder; give it a label with {@code .label("...")}. */
  public static <T> El valueField(
      Theme t, Format<T> format, T value, Draft draft, Consumer<? super T> onChange) {
    return valueField(t, format, value, null, draft, onChange);
  }

  /**
   * @param format how the value is written and read
   * @param value the value to show, from the app's state; null for none
   * @param placeholder shown while the text is empty, and the label unless {@code .label(...)}
   *     sets another; null for none
   * @param draft the field's draft, from the app's state
   * @param onChange receives the value of each edit whose text is valid; null disables the field
   * @return the field, so that a view goes on with {@code .key}, {@code .w} or {@code .maxLength}
   */
  public static <T> El valueField(
      Theme t,
      Format<T> format,
      T value,
      String placeholder,
      Draft draft,
      Consumer<? super T> onChange) {
    Objects.requireNonNull(format, "format");
    draft.show(format, value);
    Consumer<String> edit = onChange == null
        ? null
        : text -> {
          if (!format.allows(text)) {
            return; // refused: the field shows the draft as it was
          }
          Format.Parsed<T> parsed = format.parse(text);
          draft.text = text;
          draft.error = parsed.error();
          if (parsed.valid()) {
            draft.value = parsed.value();
            onChange.accept(parsed.value());
          }
        };
    El field = TextField.textField(t, draft.text, placeholder, edit);
    if (draft.error != null) {
      TextField.invalid(t, field).errorMessage(draft.error);
    }
    return field;
  }
}
