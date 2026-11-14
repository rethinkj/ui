package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
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
 */
public final class Select {

  /** The mark at the right of the button. */
  public static final Symbol OPENS = Symbol.CHEVRON_DOWN;

  private Select() {}

  /**
   * A select of values of any type, such as the constants of an enum:
   *
   * <pre>{@code
   * select(t, List.of(Level.values()), s.level, Level::label, s.levelMenu, v -> s.level = v)
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
      Theme t,
      List<T> values,
      T selected,
      Function<? super T, String> text,
      Menu.State state,
      Consumer<? super T> onSelect) {
    values = Choices.distinct(values);
    return select(
        t,
        Choices.texts(values, text),
        Choices.index(values, selected),
        state,
        Choices.handler(values, onSelect));
  }

  /**
   * A select of the value of a {@link Prop}, as {@link #select(Theme, List, Object, Function,
   * Menu.State, Consumer)}; a prop that cannot be written disables it.
   */
  public static <T> El select(
      Theme t,
      List<T> values,
      Prop<T> selected,
      Function<? super T, String> text,
      Menu.State state) {
    return Prop.shown(
        t, select(t, values, selected.get(), text, state, selected.onChange()), selected);
  }

  /**
   * @param choices the texts to choose from
   * @param selected the index of the current choice, -1 for none
   * @param state whether the list is open; the app keeps it
   * @param onSelect told the index of the choice made; null disables the select
   */
  public static El select(
      Theme t, List<String> choices, int selected, Menu.State state, IntConsumer onSelect) {
    boolean enabled = onSelect != null;
    String text = selected >= 0 && selected < choices.size() ? choices.get(selected) : "";
    int fill = t.surface().raised();
    El select = SelectionFrame.frame(t, text, null, enabled).expanded(state.isOpen());
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
    return select
        .focusable()
        .keepFocus()
        .focusRing(t.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(open)
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
        .whenHovered(e -> e.fill(over(fill, t.surface().hover())))
        .overlay(
            state.isOpen()
                ? Menu.menu(t, state, items)
                    .side(Side.BOTTOM, Style.Align.STRETCH, t.space().xs())
                : null);
  }
}
