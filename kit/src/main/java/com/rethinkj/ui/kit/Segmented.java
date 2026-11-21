package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Two to five short choices side by side in one rounded track, such as Light, Dark and System, of
 * which the chosen one lies on a raised pill that slides to the next choice. It is a {@link Radio}
 * group in another shape: a press on a choice chooses it, the group is one stop for Tab, the arrow
 * keys, Home and End choose, and Space chooses the first while none is. The track is the band's
 * surface with the subtle border, the pill the raised surface with the control's shadow; the
 * chosen text is primary, the others muted until the pointer is over them. Each choice is as wide
 * as its text and {@link Theme.Spacing#l} on both sides. In a tree dump it is {@code
 * role=radiogroup} and each choice {@code role=radio} with its text as the label and {@code
 * checked} or {@code checked=false}.
 */
public final class Segmented {

  private Segmented() {}

  /** How high the control is; its text keeps its size. */
  public enum Size {
    /**
     * {@link Theme.Sizes#control} high with the pill {@link Theme.Spacing#xs} inside the track, as
     * a button or a text field beside it: the default, for toolbars and forms.
     */
    REGULAR,
    /**
     * {@link Theme.Sizes#controlLarge} high with the pill {@link Theme.Spacing#s} inside the track,
     * for a control that stands on its own, as on a page of settings.
     */
    LARGE
  }

  /**
   * Choices among values of any type, such as the constants of an enum, as {@link
   * #segmented(List, int, IntConsumer)} is among texts.
   *
   * @param selected the chosen value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param text the text a value shows
   * @param onSelect told the value the user chooses, only when it changes; null disables the
   *     control
   */
  public static <T> El segmented(
      List<T> values, T selected, Function<? super T, String> text, Consumer<? super T> onSelect) {
    return segmented(values, selected, text, onSelect, Size.REGULAR);
  }

  /** Choices among values of any type in this size. */
  public static <T> El segmented(
      List<T> values,
      T selected,
      Function<? super T, String> text,
      Consumer<? super T> onSelect,
      Size size) {
    values = Choices.distinct(values);
    return segmented(
        Choices.texts(values, text),
        Choices.index(values, selected),
        Choices.handler(values, onSelect),
        size);
  }

  /** Choices of the value of a {@link Prop}; a prop that cannot be written disables them. */
  public static <T> El segmented(
      List<T> values, Prop<T> selected, Function<? super T, String> text) {
    Theme theme = Theme.CURRENT.get();
    return Prop.shown(
        theme, segmented(values, selected.get(), text, selected.onChange()), selected);
  }

  /**
   * @param selected the index of the chosen choice, -1 for none
   * @param onSelect told the index of the choice the user makes, only when it changes; null
   *     disables the control
   */
  public static El segmented(List<String> choices, int selected, IntConsumer onSelect) {
    return segmented(choices, selected, onSelect, Size.REGULAR);
  }

  /** Choices among texts in this size. */
  public static El segmented(List<String> choices, int selected, IntConsumer onSelect, Size size) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onSelect != null;
    boolean large = size == Size.LARGE;
    float height = large ? theme.size().controlLarge() : theme.size().control();
    float inset = large ? theme.space().s() : theme.space().xs();
    El group = row()
        .role("radiogroup")
        .disabled(!enabled)
        .h(height)
        .padding(inset)
        .gap(inset)
        .radius(height / 2)
        .fill(theme.surface().band())
        .border(theme.size().stroke(), theme.border().subtle());
    group.style().width = Len.FIT_CONTENT;
    if (selected >= 0 && selected < choices.size()) {
      // One pill under the chosen choice, so that it slides to the next; the first child, so that
      // the choices lie over it and take the presses.
      El thumb = el().key("thumb")
          .follow("choice-" + selected)
          .absolute(0, inset)
          .radius(height / 2)
          .fill(theme.surface().raised())
          .border(theme.size().stroke(), theme.border().subtle())
          .motion(theme.motion().move());
      thumb.style().right = Len.px(0);
      thumb.style().bottom = Len.px(inset);
      if (enabled) {
        thumb.shadow(theme.elevation().control());
      }
      group.child(thumb);
    }
    for (int i = 0; i < choices.size(); i++) {
      boolean on = i == selected;
      int text = !enabled
          ? theme.text().disabled()
          : on ? theme.text().primary() : theme.text().muted();
      El label = label(choices.get(i)).color(text);
      El choice = row()
          .key("choice-" + i)
          .role("radio")
          .label(choices.get(i))
          .checked(on)
          .padding(0, theme.space().l())
          .align(Style.Align.CENTER)
          .child(label);
      if (enabled) {
        choice.cursor(Cursor.POINTER).onPress(Radio.choose(onSelect, selected, i));
        if (!on) {
          // Under the pointer the text of another choice is primary, as on a tab.
          Consumer<El> lit = e -> e.children().getFirst().color(theme.text().primary());
          choice.whenHovered(lit).whenPressed(lit);
        }
      }
      group.child(choice);
    }
    return Radio.keys(theme, group, choices.size(), selected, onSelect);
  }
}
