package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Choices of which one is chosen, one below the other, each a round mark and its text. A press on
 * a choice or its text chooses it. The group is one stop for Tab, with the ring around it: the
 * arrow keys choose the choice before or after the current one, around at the ends, Home and End
 * the first and the last, and Space the first while none is chosen. The app keeps the index. In a
 * tree dump the group is {@code role=radiogroup} and each choice {@code role=radio} with its text
 * as the label and {@code checked} or {@code checked=false}; a group without handler is {@code
 * disabled}.
 */
public final class Radio {

  private Radio() {}

  /**
   * Choices among values of any type, such as the constants of an enum, as {@link
   * #radioGroup(Theme, List, int, IntConsumer)} is among texts.
   *
   * @param selected the chosen value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param text the text a value shows
   * @param onSelect told the value the user chooses, only when it changes; null disables the
   *     group
   */
  public static <T> El radioGroup(
      Theme t,
      List<T> values,
      T selected,
      Function<? super T, String> text,
      Consumer<? super T> onSelect) {
    values = Choices.distinct(values);
    return radioGroup(
        t,
        Choices.texts(values, text),
        Choices.index(values, selected),
        Choices.handler(values, onSelect));
  }

  /** Choices of the value of a {@link Prop}; a prop that cannot be written disables them. */
  public static <T> El radioGroup(
      Theme t, List<T> values, Prop<T> selected, Function<? super T, String> text) {
    return Prop.shown(
        t, radioGroup(t, values, selected.get(), text, selected.onChange()), selected);
  }

  /**
   * @param selected the index of the chosen choice, -1 for none
   * @param onSelect told the index of the choice the user makes, only when it changes; null
   *     disables the group
   */
  public static El radioGroup(Theme t, List<String> choices, int selected, IntConsumer onSelect) {
    boolean enabled = onSelect != null;
    El group = col()
        .role("radiogroup")
        .disabled(!enabled)
        .gap(t.space().s())
        // Room for the ring around the choices, taken back by the margin, as a toggle has it.
        .padding(t.space().xs(), t.space().s())
        .margin(-t.space().xs(), -t.space().s(), -t.space().xs(), -t.space().s())
        .radius(t.radius().medium());
    group.style().width = Len.FIT_CONTENT;
    for (int i = 0; i < choices.size(); i++) {
      group.child(radio(t, choices.get(i), i == selected, enabled, choose(onSelect, selected, i)));
    }
    return keys(t, group, choices.size(), selected, onSelect);
  }

  private static El radio(Theme t, String text, boolean on, boolean enabled, Runnable onPress) {
    Theme.Surfaces s = t.surface();
    float side = t.size().checkbox();
    int fill = on ? (enabled ? t.accent().color() : t.border().subtle()) : s.raised();
    int edge =
        !enabled ? t.border().subtle() : on ? t.accent().pressed() : t.border().strong();
    El mark = el().size(side, side)
        .shrink(0)
        .center()
        .radius(side / 2)
        .fill(fill)
        .border(t.size().stroke(), edge)
        .motion(t.motion().feedback());
    if (on) {
      float dot = Math.round(side * 3 / 8);
      mark.child(el().size(dot, dot)
          .radius(dot / 2)
          .fill(enabled ? t.text().onAccent() : t.text().disabled()));
    }
    El radio = row()
        .role("radio")
        .label(text)
        .checked(on)
        .gap(t.space().m())
        .align(Style.Align.CENTER)
        .children(
            mark, label(t, text).color(enabled ? t.text().primary() : t.text().disabled()));
    if (!enabled) {
      return radio;
    }
    int hover = on ? t.accent().hover() : over(fill, s.hover());
    int pressed = on ? t.accent().pressed() : over(fill, s.pressed());
    return radio
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> mark.fill(hover))
        .whenPressed(e -> mark.fill(pressed));
  }

  /** Chooses {@code i} unless it is chosen already. */
  static Runnable choose(IntConsumer onSelect, int selected, int i) {
    return () -> {
      if (i != selected) {
        onSelect.accept(i);
      }
    };
  }

  /**
   * Makes a group of choices one stop for Tab whose arrow keys choose the one before or after,
   * around at the ends, Home and End the first and the last, and Space the first while none is
   * chosen.
   */
  static El keys(Theme t, El group, int count, int selected, IntConsumer onSelect) {
    if (onSelect == null || count == 0) {
      return group;
    }
    return group.focusable().keepFocus().focusRing(t.accent().edge()).onKey(k -> {
      int next = next(k, count, selected);
      if (next == -2) {
        return false;
      }
      choose(onSelect, selected, next).run();
      return true;
    });
  }

  /** The choice a key goes to from {@code selected}, or -2 for a key that chooses nothing. */
  private static int next(Key k, int count, int selected) {
    if (k.modifiers() != 0) {
      return -2;
    }
    return switch (k.name()) {
      case "ArrowUp", "ArrowLeft" -> selected <= 0 ? count - 1 : selected - 1;
      case "ArrowDown", "ArrowRight" -> selected < 0 || selected == count - 1 ? 0 : selected + 1;
      case "Home" -> 0;
      case "End" -> count - 1;
      // WAI-ARIA's radio group: with none checked, Space checks the first
      case "Space" -> Math.max(selected, 0);
      default -> -2;
    };
  }
}
