package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.function.IntConsumer;

/**
 * A whole number in a {@link TextField text field} with a button to decrease it and one to
 * increase it inside the frame at the right. The field takes digits, and a minus sign first where
 * the range has negative numbers; while it has the focus, Up and Down step by {@code step}, Page
 * Up and Page Down by ten steps, each kept inside the range, and the buttons do the same. Typing
 * hands each whole number to the handler, also one outside the range, which the field then shows
 * as invalid ({@link TextField#invalid}) until it is back in range: so that typing {@code 14} in a
 * field from 10 to 24 can pass {@code 1} on the way. Edits that leave no number are refused, so to
 * type a new number select the old one and type over it.
 *
 * <p>The field is the one stop for Tab; the buttons keep the focus in it when pressed ({@link
 * El#keepFocus}) and are disabled at the ends of the range. The result is a box with the
 * field and the buttons, half as wide as a text field unless the view sets a width with {@code
 * .w(...)} on it. In a tree dump the field is {@code role=spinbutton} with its number as its
 * string, and the buttons are {@code role=button} labelled {@code Decrease} and {@code Increase}.
 * The field's label is the one {@link #numberField(Theme, String, int, int, int, int,
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
  public static El numberField(
      Theme t, int value, int min, int max, int step, IntConsumer onChange) {
    return numberField(t, null, value, min, max, step, onChange);
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
      Theme t, String label, int value, int min, int max, int step, IntConsumer onChange) {
    if (min > max || step < 1) {
      throw new IllegalArgumentException(
          "Needs min <= max and a step of 1 or more, was " + min + ", " + max + ", " + step);
    }
    boolean enabled = onChange != null;
    El field = TextField.textField(
            t, Integer.toString(value), enabled ? s -> edit(s, min, value, onChange) : null)
        .role("spinbutton")
        .label(label);
    if (value < min || value > max) {
      TextField.invalid(t, field);
    }
    float inset = t.space().xs();
    float size = t.size().control() - 2 * inset;
    field.wFull().style().paddingRight = Len.px(2 * size + inset + t.space().xs());
    El buttons = row()
        .gap(t.space().xs())
        .child(button(
            t,
            Symbol.SUBTRACT,
            "Decrease",
            enabled && value > min,
            () -> onChange.accept(step(value, -step, min, max))))
        .child(button(
            t,
            Symbol.ADD,
            "Increase",
            enabled && value < max,
            () -> onChange.accept(step(value, step, min, max))));
    Style at = buttons.style();
    at.position = Style.Position.ABSOLUTE;
    at.right = Len.px(inset);
    at.top = Len.px(inset);
    if (enabled) {
      field.onKey(k -> {
        int by = k.modifiers() != 0
            ? 0
            : switch (k.name()) {
              case "ArrowUp" -> step;
              case "ArrowDown" -> -step;
              case "PageUp" -> 10 * step;
              case "PageDown" -> -10 * step;
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
    return el().w(t.size().field() / 2).children(field, buttons);
  }

  /** Hands a typed whole number on; refuses anything else, so the field stays as it was. */
  private static void edit(String s, int min, int value, IntConsumer onChange) {
    if (!s.matches(min < 0 ? "-?\\d{1,9}" : "\\d{1,9}")) {
      return;
    }
    int typed = Integer.parseInt(s);
    if (typed != value || !s.equals(Integer.toString(typed))) {
      onChange.accept(typed);
    }
  }

  /** The value moved by {@code by} and kept in the range. */
  private static int step(int value, int by, int min, int max) {
    return (int) Math.clamp((long) value + by, min, max);
  }

  private static El button(
      Theme t, Symbol symbol, String label, boolean enabled, Runnable onPress) {
    float size = t.size().control() - 2 * t.space().xs();
    El button = el().role("button")
        .label(label)
        .disabled(!enabled)
        .size(size, size)
        .center()
        .radius(t.radius().small())
        .motion(t.motion().feedback())
        .child(icon(t, symbol, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    if (!enabled) {
      return button;
    }
    return button
        .keepFocus()
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(t.surface().hover()))
        .whenPressed(e -> e.fill(t.surface().pressed()));
  }
}
