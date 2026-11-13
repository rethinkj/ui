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
 * on a choice chooses it, the group is one stop for Tab, and the arrow keys, Home and End choose.
 * It is {@link Theme.Sizes#control} high, on the panel's surface with the strong border, each
 * choice as wide as its text and {@link Theme.Spacing#l} on both sides. In a tree dump it is
 * {@code role=radiogroup} and each choice {@code role=radio} with its text as the label and {@code
 * checked} or {@code checked=false}.
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
      Theme t,
      List<T> values,
      T selected,
      Function<? super T, String> text,
      Consumer<? super T> onSelect) {
    return segmented(
        t,
        Choices.texts(values, text),
        Choices.index(values, selected),
        Choices.handler(values, onSelect));
  }

  /**
   * @param selected the index of the chosen choice, -1 for none
   * @param onSelect told the index of the choice the user makes, only when it changes; null
   *     disables the control
   */
  public static El segmented(Theme t, List<String> choices, int selected, IntConsumer onSelect) {
    boolean enabled = onSelect != null;
    int fill = t.surface().panel();
    El group = row()
        .role("radiogroup")
        .disabled(!enabled)
        .h(t.size().control())
        .padding(t.space().xs())
        .gap(t.space().xs())
        .radius(t.radius().medium())
        .fill(fill)
        .border(t.size().stroke(), enabled ? t.border().strong() : t.border().subtle());
    group.style().width = Len.FIT_CONTENT;
    for (int i = 0; i < choices.size(); i++) {
      boolean on = i == selected;
      int back = on ? (enabled ? t.accent().color() : t.border().subtle()) : 0;
      int text =
          !enabled ? t.text().disabled() : on ? t.text().onAccent() : t.text().primary();
      El choice = row()
          .role("radio")
          .label(choices.get(i))
          .checked(on)
          .padding(0, t.space().l())
          .align(Style.Align.CENTER)
          .radius(t.radius().small())
          .fill(back)
          .motion(t.motion().feedback())
          .child(label(t, choices.get(i)).color(text));
      if (enabled) {
        int hover = on ? t.accent().hover() : over(fill, t.surface().hover());
        int pressed = on ? t.accent().pressed() : over(fill, t.surface().pressed());
        choice
            .cursor(Cursor.POINTER)
            .onPress(Radio.choose(onSelect, selected, i))
            .whenHovered(e -> e.fill(hover))
            .whenPressed(e -> e.fill(pressed));
      }
      group.child(choice);
    }
    return Radio.keys(t, group, choices.size(), selected, onSelect);
  }
}
