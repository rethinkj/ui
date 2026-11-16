package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Two to five short choices side by side in one frame, such as Light, Dark and System, of which
 * the chosen one is filled with the accent. It is a {@link Radio} group in another shape: a press
 * on a choice chooses it, the group is one stop for Tab, the arrow keys, Home and End choose, and
 * Space chooses the first while none is. It is {@link Theme.Sizes#control} high, on the panel's
 * surface with the strong border, each choice as wide as its text and {@link Theme.Spacing#l} on
 * both sides. In a tree dump it is {@code role=radiogroup} and each choice {@code role=radio} with
 * its text as the label and {@code checked} or {@code checked=false}.
 */
public final class Segmented {

  private Segmented() {}

  /**
   * Choices among values of any type, such as the constants of an enum, as {@link
   * #segmented(Theme, List, int, IntConsumer)} is among texts.
   *
   * @param selected the chosen value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param text the text a value shows
   * @param onSelect told the value the user chooses, only when it changes; null disables the
   *     control
   */
  public static <T> El segmented(
      Theme theme,
      List<T> values,
      T selected,
      Function<? super T, String> text,
      Consumer<? super T> onSelect) {
    values = Choices.distinct(values);
    return segmented(
        theme,
        Choices.texts(values, text),
        Choices.index(values, selected),
        Choices.handler(values, onSelect));
  }

  /** Choices of the value of a {@link Prop}; a prop that cannot be written disables them. */
  public static <T> El segmented(
      Theme theme, List<T> values, Prop<T> selected, Function<? super T, String> text) {
    return Prop.shown(
        theme, segmented(theme, values, selected.get(), text, selected.onChange()), selected);
  }

  /**
   * @param selected the index of the chosen choice, -1 for none
   * @param onSelect told the index of the choice the user makes, only when it changes; null
   *     disables the control
   */
  public static El segmented(
      Theme theme, List<String> choices, int selected, IntConsumer onSelect) {
    boolean enabled = onSelect != null;
    int fill = theme.surface().panel();
    El group = row()
        .role("radiogroup")
        .disabled(!enabled)
        .h(theme.size().control())
        .padding(theme.space().xs())
        .gap(theme.space().xs())
        .radius(theme.radius().medium())
        .fill(fill)
        .border(
            theme.size().stroke(),
            enabled ? theme.border().strong() : theme.border().divider());
    group.style().width = Len.FIT_CONTENT;
    for (int i = 0; i < choices.size(); i++) {
      boolean on = i == selected;
      int back = on ? (enabled ? theme.accent().color() : theme.border().divider()) : 0;
      int text = !enabled
          ? theme.text().disabled()
          : on ? theme.text().onAccent() : theme.text().primary();
      El choice = row()
          .role("radio")
          .label(choices.get(i))
          .checked(on)
          .padding(0, theme.space().l())
          .align(Style.Align.CENTER)
          .radius(theme.radius().small())
          .fill(back)
          .motion(theme.motion().feedback())
          .child(label(theme, choices.get(i)).color(text));
      if (enabled) {
        int hover = on ? theme.accent().hover() : over(fill, theme.surface().hover());
        int pressed = on ? theme.accent().pressed() : over(fill, theme.surface().pressed());
        choice
            .cursor(Cursor.POINTER)
            .onPress(Radio.choose(onSelect, selected, i))
            .whenHovered(e -> e.fill(hover))
            .whenPressed(e -> e.fill(pressed));
      }
      group.child(choice);
    }
    return Radio.keys(theme, group, choices.size(), selected, onSelect);
  }
}
