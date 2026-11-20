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
import com.rethinkj.ui.render.Scene;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Choices of which one is chosen, one below the other, each a round mark and its text. A press on
 * a choice or its text chooses it. The group is one stop for Tab, with the ring around it: the
 * arrow keys choose the choice before or after the current one, around at the ends, Home and End
 * the first and the last, and Space the first while none is chosen. The app keeps the index. In a
 * tree dump the group is {@code role=radiogroup} and each choice {@code role=radio} with its text
 * as the label and {@code checked} or {@code checked=false}; a group without handler is {@code
 * disabled}.
 *
 * <p>{@link #swatches} is the same group for choices that show an element rather than a text,
 * such as colors ({@link HuePicker}).
 */
public final class Radio {

  private Radio() {}

  /**
   * A choice of {@link #swatches}: its name, the label of its radio and its tooltip, and what it
   * shows.
   *
   * @param look builds the element shown, at most {@link Theme.Sizes#checkbox} square
   */
  public record Option(String label, Supplier<El> look) {}

  /** A choice of {@link #swatches} named {@code label} that shows what {@code look} builds. */
  public static Option option(String label, Supplier<El> look) {
    return new Option(label, look);
  }

  /**
   * Choices among values that show an element each, as {@link #swatches(List, int,
   * IntConsumer)} among options; null is a value like any other, such as a choice of no color.
   *
   * @param selected the chosen value, found by {@code equals}; a value not among them for none
   * @param option the name and the look of a value
   * @param onSelect told the value the user chooses, only when it changes; null disables the
   *     group
   */
  public static <T> El swatches(
      List<T> values,
      T selected,
      Function<? super T, Option> option,
      Consumer<? super T> onSelect) {
    values = Choices.distinct(values);
    List<Option> options = new java.util.ArrayList<>(values.size());
    for (T v : values) {
      options.add(option.apply(v));
    }
    return swatches(options, Choices.index(values, selected), Choices.handler(values, onSelect));
  }

  /** Swatches of the value of a {@link Prop}; a prop that cannot be written disables them. */
  public static <T> El swatches(
      List<T> values, Prop<T> selected, Function<? super T, Option> option) {
    Theme theme = Theme.CURRENT.get();
    return Prop.shown(
        theme, swatches(values, selected.get(), option, selected.onChange()), selected);
  }

  /**
   * Choices that show an element each, such as colors, in a row that wraps, {@link
   * Theme.Spacing#s} apart, each with its name in a tooltip. It is a radio group in every other
   * way: the same roles, states and keys, one stop for Tab.
   *
   * <p>The chosen option has a round ring in {@link Theme.Accent#edge} around its element, {@link
   * Theme.Sizes#ringGap} away, with the surface between. The keyboard's focus is not the core's
   * ring around the whole group, as with texts, but a rounded square in the same color around
   * the option the arrow keys go on from: the chosen one, or the first while none is. So the two
   * differ by their shape, a circle for the choice and a square around it for the focus, and
   * both show around a focused choice. Each option is that square, {@link Theme.Sizes#checkbox}
   * plus twice both rings and their gaps (32 in the default themes), which takes the press, with
   * the hover and pressed overlays. A disabled group keeps what its options show and draws the
   * chosen ring in disabled text.
   *
   * @param selected the index of the chosen option, -1 for none
   * @param onSelect told the index of the option the user chooses, only when it changes; null
   *     disables the group
   */
  public static El swatches(List<Option> options, int selected, IntConsumer onSelect) {
    return swatches(options, selected, onSelect, null);
  }

  /** Swatches that also run {@code onPressed} after any press on an option, a chosen one too. */
  static El swatches(List<Option> options, int selected, IntConsumer onSelect, Runnable onPressed) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onSelect != null;
    float gap = theme.size().ringGap();
    float side = theme.size().checkbox() + 2 * (gap + Scene.RING);
    El group =
        row().role("radiogroup").disabled(!enabled).wrap().gap(theme.space().s());
    group.style().width = Len.FIT_CONTENT;
    El[] frames = new El[options.size()];
    for (int i = 0; i < options.size(); i++) {
      Option o = options.get(i);
      boolean on = i == selected;
      El chosen = el().size(side, side)
          .shrink(0)
          .center()
          .radius(side / 2)
          .padding(gap)
          .border(
              Scene.RING,
              !on ? 0 : enabled ? theme.accent().edge() : theme.text().disabled())
          .child(o.look().get().shrink(0));
      El option = el().role("radio")
          .label(o.label())
          .checked(on)
          .size(side + 2 * (gap + Scene.RING), side + 2 * (gap + Scene.RING))
          .shrink(0)
          .center()
          .radius(theme.radius().medium())
          .padding(gap)
          .border(Scene.RING, 0)
          .motion(theme.motion().feedback())
          .child(chosen);
      frames[i] = option;
      if (enabled) {
        option
            .cursor(Cursor.POINTER)
            .onPress(pressed(choose(onSelect, selected, i), onPressed))
            .whenHovered(e -> e.fill(theme.surface().hover()))
            .whenPressed(e -> e.fill(theme.surface().pressed()));
      }
      group.child(Tooltip.tooltip(option, o.label()));
    }
    keys(theme, group, options.size(), selected, onSelect);
    if (enabled && frames.length > 0) {
      El cursor = frames[Math.max(selected, 0)];
      group
          .focusRing(0)
          .whenFocused(e -> cursor.border(Scene.RING, theme.accent().edge()));
    }
    return group;
  }

  /**
   * Choices among values of any type, such as the constants of an enum, as {@link
   * #radioGroup(List, int, IntConsumer)} is among texts.
   *
   * @param selected the chosen value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param text the text a value shows
   * @param onSelect told the value the user chooses, only when it changes; null disables the
   *     group
   */
  public static <T> El radioGroup(
      List<T> values, T selected, Function<? super T, String> text, Consumer<? super T> onSelect) {
    values = Choices.distinct(values);
    return radioGroup(
        Choices.texts(values, text),
        Choices.index(values, selected),
        Choices.handler(values, onSelect));
  }

  /** Choices of the value of a {@link Prop}; a prop that cannot be written disables them. */
  public static <T> El radioGroup(
      List<T> values, Prop<T> selected, Function<? super T, String> text) {
    Theme theme = Theme.CURRENT.get();
    return Prop.shown(
        theme, radioGroup(values, selected.get(), text, selected.onChange()), selected);
  }

  /**
   * @param selected the index of the chosen choice, -1 for none
   * @param onSelect told the index of the choice the user makes, only when it changes; null
   *     disables the group
   */
  public static El radioGroup(List<String> choices, int selected, IntConsumer onSelect) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onSelect != null;
    El group = col()
        .role("radiogroup")
        .disabled(!enabled)
        .gap(theme.space().s())
        // Room for the ring around the choices, taken back by the margin, as a toggle has it.
        .padding(theme.space().xs(), theme.space().s())
        .margin(
            -theme.space().xs(),
            -theme.space().s(),
            -theme.space().xs(),
            -theme.space().s())
        .radius(theme.radius().medium());
    group.style().width = Len.FIT_CONTENT;
    for (int i = 0; i < choices.size(); i++) {
      group.child(
          radio(theme, choices.get(i), i == selected, enabled, choose(onSelect, selected, i)));
    }
    return keys(theme, group, choices.size(), selected, onSelect);
  }

  private static El radio(Theme theme, String text, boolean on, boolean enabled, Runnable onPress) {
    Theme.Surfaces s = theme.surface();
    float side = theme.size().checkbox();
    int fill = on ? (enabled ? theme.accent().color() : theme.border().divider()) : s.raised();
    int edge = !enabled
        ? theme.border().divider()
        : on ? theme.accent().pressed() : theme.border().strong();
    El mark = el().size(side, side)
        .shrink(0)
        .center()
        .radius(side / 2)
        .fill(fill)
        .border(theme.size().stroke(), edge)
        .motion(theme.motion().feedback());
    if (on) {
      float dot = Math.round(side * 3 / 8);
      mark.child(el().size(dot, dot)
          .radius(dot / 2)
          .fill(enabled ? theme.text().onAccent() : theme.text().disabled()));
    }
    El radio = row()
        .role("radio")
        .label(text)
        .checked(on)
        .gap(theme.space().m())
        .align(Style.Align.CENTER)
        .children(
            mark,
            label(text).color(enabled ? theme.text().primary() : theme.text().disabled()));
    if (!enabled) {
      return radio;
    }
    int hover = on ? theme.accent().hover() : over(fill, s.hover());
    int pressed = on ? theme.accent().pressed() : over(fill, s.pressed());
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
  static El keys(Theme theme, El group, int count, int selected, IntConsumer onSelect) {
    if (onSelect == null || count == 0) {
      return group;
    }
    return group.focusable().keepFocus().focusRing(theme.accent().edge()).onKey(k -> {
      int next = next(k, count, selected);
      if (next == -2) {
        return false;
      }
      choose(onSelect, selected, next).run();
      return true;
    });
  }

  private static Runnable pressed(Runnable choose, Runnable onPressed) {
    return onPressed == null
        ? choose
        : () -> {
          choose.run();
          onPressed.run();
        };
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
