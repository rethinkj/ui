package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import java.util.function.IntConsumer;

/**
 * A whole number in a {@link TextField text field} with a button to decrease it and one to
 * increase it inside the frame at the right. The field takes digits, and a minus sign first where
 * the range has negative numbers; while it has the focus, Up and Down step by {@code step}, Page
 * Up and Page Down by ten steps, each kept inside the range, and the buttons do the same. Typing
 * hands each whole number to the handler, also one outside the range, which the field then shows
 * as invalid ({@link TextField#invalid}) until it is back in range: so that typing {@code 14} in a
 * field from 10 to 24 can pass {@code 1} on the way.
 *
 * <p>A text between two numbers, such as a lone minus sign before {@code -5} or the empty field,
 * needs a {@link ValueField.Draft} to be kept in, as a {@link ValueField}'s does: with one, the
 * field shows such a text as invalid and the app keeps its last number meanwhile. A field of a
 * {@link Prop} of an {@link Editing} uses the editing's draft. Without one, an edit that leaves no
 * number is refused, so to type a new number select the old one and type over it.
 *
 * <p>The field is the one stop for Tab; the buttons keep the focus in it when pressed ({@link
 * El#keepFocus}), also where they are disabled at the ends of the range. The result is a box with the
 * field and the buttons, half as wide as a text field unless the view sets a width with {@code
 * .w(...)} on it. In a tree dump the field is {@code role=spinbutton} with its number as its
 * string, and the buttons are {@code role=button} labeled {@code Decrease} and {@code Increase}.
 * The field's label is the one {@link #numberField(String, int, int, int, int,
 * IntConsumer)} gives it, or the title of the {@link Form} field or {@link Group} row it is in;
 * {@code .label(...)} on the result would label the box, not the field.
 */
public final class NumberField {

  private NumberField() {}

  /**
   * A number field whose field has no label of its own, for a {@link Form} field or a {@link
   * Group} row, which give it theirs.
   *
   * @param value the number to show, from the app's state
   * @param min the smallest number the keys and buttons go to
   * @param max the largest number the keys and buttons go to
   * @param step how far a key or a button moves the number
   * @param onChange told each new number; null disables the field
   */
  public static El numberField(int value, int min, int max, int step, IntConsumer onChange) {
    return numberField(null, value, min, max, step, onChange);
  }

  /**
   * A number field of a {@link Prop}; a null value shows as {@code min}, and a prop that cannot be
   * written disables it. A prop of an {@link Editing} keeps the field's draft there.
   */
  public static El numberField(Prop<Integer> value, int min, int max, int step) {
    Theme theme = Theme.CURRENT.get();
    Integer now = value.get();
    return Prop.shown(
        theme,
        numberField(
            null,
            now == null ? min : now,
            min,
            max,
            step,
            value.keepsDraft() ? value.draft() : null,
            value.writes() ? value::set : null),
        value);
  }

  /**
   * @param label the field's label, which a tree dump shows on the {@code role=spinbutton} and a
   *     script selects it by, as {@code press role=spinbutton label="Fetch size"}; null for none
   * @param value the number to show, from the app's state
   * @param min the smallest number the keys and buttons go to
   * @param max the largest number the keys and buttons go to
   * @param step how far a key or a button moves the number
   * @param onChange told each new number; null disables the field
   */
  public static El numberField(
      String label, int value, int min, int max, int step, IntConsumer onChange) {
    return numberField(label, value, min, max, step, null, onChange);
  }

  /**
   * A number field that keeps a text between two numbers, such as {@code -} on the way to {@code
   * -5} or the empty field, in {@code draft}.
   *
   * @param label the field's label; null for none, as in a {@link Form} field
   * @param value the number to show, from the app's state
   * @param min the smallest number the keys and buttons go to
   * @param max the largest number the keys and buttons go to
   * @param step how far a key or a button moves the number
   * @param draft the field's draft, from the app's state; null to refuse such a text
   * @param onChange told each new number; null disables the field
   */
  public static El numberField(
      String label,
      int value,
      int min,
      int max,
      int step,
      ValueField.Draft draft,
      IntConsumer onChange) {
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    if (min > max || step < 1) {
      throw new IllegalArgumentException(
          "Needs min <= max and a step of 1 or more, was " + min + ", " + max + ", " + step);
    }
    boolean enabled = onChange != null;
    String text = Integer.toString(value);
    if (draft != null) {
      draft.show(Format.INTEGER, value);
      text = draft.text();
    }
    El field = TextField.textField(
            text, enabled ? s -> edit(texts, s, min, value, draft, onChange) : null)
        .role("spinbutton")
        .label(label);
    if (draft != null && draft.error() != null && draft.showsError()) {
      TextField.invalid(field).errorMessage(draft.error());
    } else if (value < min || value > max) {
      // The message a form or a group's row shows below it, and a dump as error="...".
      TextField.invalid(field)
          .errorMessage(texts.range(Integer.toString(min), Integer.toString(max)));
    }
    float inset = theme.space().xs();
    float size = FieldActions.size(theme);
    El buttons = row()
        .gap(theme.space().xs())
        // Even a disabled arrow keeps the focus in the field; neither is a Tab stop.
        .child(FieldActions.button(
                theme,
                Symbol.SUBTRACT,
                texts.decrease(),
                enabled && value > min ? () -> onChange.accept(step(value, -step, min, max)) : null)
            .keepFocus())
        .child(FieldActions.button(
                theme,
                Symbol.ADD,
                texts.increase(),
                enabled && value < max ? () -> onChange.accept(step(value, step, min, max)) : null)
            .keepFocus());
    if (enabled) {
      field.onKey(k -> {
        long by = k.modifiers() != 0
            ? 0
            : switch (k.name()) {
              case "ArrowUp" -> step;
              case "ArrowDown" -> -step;
              case "PageUp" -> 10L * step;
              case "PageDown" -> -10L * step;
              default -> 0;
            };
        if (by == 0) {
          return false;
        }
        int next = step(value, by, min, max);
        if (next != value) {
          onChange.accept(next);
        }
        return true;
      });
    }
    return FieldActions.embed(
            theme, field.wFull(), buttons, 2 * size + inset + theme.space().xs())
        .w(theme.size().field() / 2);
  }

  /**
   * Hands a typed whole number on; keeps a text on the way to one in the draft, if there is one,
   * and refuses anything else, so the field stays as it was.
   */
  private static void edit(
      Texts texts, String s, int min, int value, ValueField.Draft draft, IntConsumer onChange) {
    if (draft != null) {
      if (s.matches(min < 0 ? "-?\\d*" : "\\d*")) {
        draft.edit(texts, Format.INTEGER, s, onChange::accept);
      }
      return;
    }
    if (!s.matches(min < 0 ? "-?\\d+" : "\\d+")) {
      return;
    }
    int typed;
    try {
      typed = Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return; // beyond an int
    }
    if (typed != value || !s.equals(Integer.toString(typed))) {
      onChange.accept(typed);
    }
  }

  /** The value moved by {@code by} and kept in the range. */
  private static int step(int value, long by, int min, int max) {
    return (int) Math.clamp(value + by, min, max);
  }
}
