package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A button that shows the current choice and opens a {@link Menu} of all choices below it, at
 * least as wide as the button, with the current one checked and highlighted. A press on the
 * button, Enter, Space, Arrow Down and Arrow Up open it; the menu's keys and pointer choose. The
 * app keeps the choice and the menu's {@link Menu.State}. In a tree dump it is {@code
 * role=combobox} with the current choice as the label, and its menu's items are the choices.
 */
public final class Select {

  /** The mark at the right of the button. */
  public static final String OPENS = "▼";

  private Select() {}

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
    Theme.TextStyle mono = t.type().mono();
    int fill = t.surface().raised();
    El select = row().role("combobox").label(text)
        .disabled(!enabled)
        .expanded(state.isOpen())
        .w(t.size().field())
        .h(t.size().control())
        .padding(0, t.space().m())
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .radius(t.radius().medium())
        .fill(fill)
        .border(t.size().stroke(), enabled ? t.border().strong() : t.border().subtle())
        .motion(t.motion().feedback())
        .children(
            label(t, text).color(enabled ? t.text().primary() : t.text().disabled()).grow(1),
            El.text(OPENS).font(mono.font().get()).fontSize(mono.size())
                .color(enabled ? t.text().muted() : t.text().disabled()));
    if (!enabled) {
      return select;
    }
    Runnable open = () -> {
      state.open();
      state.active = selected;
    };
    List<Menu.Item> items = new ArrayList<>();
    for (int i = 0; i < choices.size(); i++) {
      int choice = i;
      items.add(Menu.item(choices.get(i), () -> onSelect.accept(choice)).checked(i == selected));
    }
    return select.focusable().focusRing(t.border().focus())
        .cursor(Cursor.POINTER)
        .onPress(open)
        .onKey(k -> {
          if (k.modifiers() != 0 || state.isOpen()
              || !k.name().equals("ArrowDown") && !k.name().equals("ArrowUp")) {
            return false;
          }
          open.run();
          return true;
        })
        .whenHovered(e -> e.fill(over(fill, t.surface().hover())))
        .overlay(state.isOpen()
            ? Menu.menu(t, state, items).side(Side.BOTTOM, Style.Align.STRETCH, t.space().xs())
            : null);
  }
}
