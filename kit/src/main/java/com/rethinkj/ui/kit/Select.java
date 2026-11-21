package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * A button that shows the current choice and opens a {@link Menu} of all choices below it, at
 * least as wide as the button, with the current one checked and highlighted. A press on the
 * button, Enter, Space, Alt+Arrow Down and Alt+Arrow Up open it; the menu's keys and pointer
 * choose, and Alt+Arrow Down or Up close it again. Tab reaches it; a press leaves the focus where
 * it is, as a {@link Button}'s does, and the menu gives it back there. While it is closed and has the focus, Arrow
 * Down and Arrow Up choose the next and the previous choice, Home and End the first and the last,
 * and a letter or digit the next choice that starts with it, as an open menu goes to it. The app
 * keeps the choice and the menu's {@link Menu.State}. In a tree dump it is {@code role=combobox}
 * with the current choice as the label, and its menu's items are the choices. A long choice ends
 * in an ellipsis before the chevron; the label keeps the full text.
 *
 * <p>It looks as a secondary {@link Button} does, with the chevron muted: raised with the control's
 * shadow, lifted under the pointer, and pressed in while its menu is open.
 */
public final class Select {

  /** The mark at the right of the button. */
  public static final Symbol OPENS = Symbol.CHEVRON_DOWN;

  private Select() {}

  /**
   * A select of values of any type, such as the constants of an enum:
   *
   * <pre>{@code
   * select(List.of(Level.values()), state.level, Level::label, state.levelMenu,
   *     value -> state.level = value)
   * }</pre>
   *
   * @param values the values to choose from
   * @param selected the current value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param text the text a value shows
   * @param state whether the list is open; the app keeps it
   * @param onSelect told the value chosen; null disables the select
   */
  public static <T> El select(
      List<T> values,
      T selected,
      Function<? super T, String> text,
      Menu.State state,
      Consumer<? super T> onSelect) {
    values = Choices.distinct(values);
    return select(
        Choices.texts(values, text),
        Choices.index(values, selected),
        state,
        Choices.handler(values, onSelect));
  }

  /**
   * A select of the value of a {@link Prop}, as {@link #select(List, Object, Function,
   * Menu.State, Consumer)}; a prop that cannot be written disables it.
   */
  public static <T> El select(
      List<T> values, Prop<T> selected, Function<? super T, String> text, Menu.State state) {
    Theme theme = Theme.CURRENT.get();
    return Prop.shown(
        theme, select(values, selected.get(), text, state, selected.onChange()), selected);
  }

  /**
   * @param choices the texts to choose from
   * @param selected the index of the current choice, -1 for none
   * @param state whether the list is open; the app keeps it
   * @param onSelect told the index of the choice made; null disables the select
   */
  public static El select(
      List<String> choices, int selected, Menu.State state, IntConsumer onSelect) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onSelect != null;
    String text = selected >= 0 && selected < choices.size() ? choices.get(selected) : "";
    Button.Look look = Button.look(theme, Button.Variant.SECONDARY, Button.Tone.NEUTRAL, enabled);
    El select = row()
        .role("combobox")
        .label(text)
        .disabled(!enabled)
        .w(theme.size().field())
        .h(theme.size().control())
        .shrink(0)
        // As a button's: room for the content to move down while pressed, in the same box.
        .padding(theme.size().stroke(), theme.space().m())
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .radius(theme.radius().medium())
        .fill(look.fill())
        .border(theme.size().stroke(), look.edge())
        .shadow(look.rest())
        .motion(theme.motion().feedback())
        .expanded(state.isOpen())
        .children(
            label(text).color(look.text()).grow(1).minSize(0, 0).ellipsis(),
            Icon.icon(OPENS, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    if (!enabled) {
      return select;
    }
    Runnable open = () -> {
      state.open();
      state.highlight(0, selected);
    };
    List<Menu.Item> items = new ArrayList<>();
    for (int i = 0; i < choices.size(); i++) {
      int choice = i;
      items.add(Menu.item(choices.get(i), () -> onSelect.accept(choice)).checked(i == selected));
    }
    // As a button: a press leaves the focus where it is, and its menu gives it back there.
    Button.enable(theme, look, select, open);
    if (state.isOpen()) {
      Button.held(theme, select, look);
    }
    return select
        .keepFocus()
        .onKey(k -> {
          boolean arrow = k.name().equals("ArrowDown") || k.name().equals("ArrowUp");
          if (arrow && k.modifiers() == Key.ALT) {
            if (state.isOpen()) {
              state.close();
            } else {
              open.run();
            }
            return true;
          }
          if (k.modifiers() != 0 || state.isOpen() || choices.isEmpty()) {
            return false;
          }
          int last = choices.size() - 1;
          int next = switch (k.name()) {
            case "ArrowDown" -> Math.min(selected + 1, last);
            case "ArrowUp" -> Math.max(selected - 1, 0);
            case "Home" -> 0;
            case "End" -> last;
            default ->
              k.name().length() == 1 && Character.isLetterOrDigit(k.name().charAt(0))
                  ? Menu.startingWith(items, selected, k.name())
                  : -2;
          };
          if (next == -2) {
            return false;
          }
          if (next >= 0 && next != selected) {
            onSelect.accept(next);
          }
          return true;
        })
        .overlay(
            state.isOpen()
                ? Menu.menu(state, items)
                    .side(Side.BOTTOM, Style.Align.STRETCH, theme.space().xs())
                : null);
  }
}
