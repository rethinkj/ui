package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * A list of items of which one is selected, such as the pages at the side of a settings window:
 * each item with an optional icon, its text and an optional count at the right, and optional
 * headings between groups of items. The app keeps the selected index; a press on an item selects
 * it, and while the list has the focus Up and Down move the selection past the headings, Home and
 * End go to the first and the last item, and Page Up and Page Down by the items in view. The list
 * is one stop for Tab and follows the selection ({@link El#revealRow}).
 *
 * <pre>{@code
 * navList(List.of(
 *     NavList.item(Symbol.SETTINGS, "General"),
 *     NavList.item(Symbol.DATABASE, "Connections").count("3"),
 *     NavList.heading("Help"),
 *     NavList.item(Symbol.INFO, "About")), state.page, index -> state.page = index)
 *     .label("Settings")
 * }</pre>
 *
 * <p>Given a count and a function in place of a list, it is a virtual list ({@link El#rows}) that
 * builds only the items in view, the general list of one selection among many. Items are {@link
 * Theme.Sizes#control} high with {@link Theme.Spacing#xs} above and below; the selected one has
 * the fill of the grid's selection, {@link Theme.Grid#selected} while the list has the focus, a
 * mark of {@link Theme.Accent#edge} at its left, which stands out from the surfaces also where
 * the inactive selection's fill does not, its icon in that color and its text in {@link
 * Theme.Type#strong}. A count is a quiet {@link Badge}; a heading is {@link Theme.Type#small} at
 * the bottom of its row, so that the room above it sets the group apart. Like any scroll area,
 * the list needs a height of its own, room to grow, or no more items than fit.
 *
 * <p>An item can stand in by steps, as the headings of an outline under the one above them
 * ({@link Item#indent}), and can be disabled ({@link Item#disabled}): drawn in the disabled colors,
 * passed by the keys and deaf to presses.
 *
 * <p>In a tree dump the list is {@code role=listbox}, its items {@code role=option} with their
 * text as the label, {@code selected} or {@code disabled}, and their position among all rows,
 * headings counted, and the keys {@code item-<index>} or the item's own ({@link Item#key}); a
 * heading is a row without a role.
 */
public final class NavList {

  /** An entry of the list: an {@link Item}, or a {@link Heading} over the items after it. */
  public sealed interface Entry permits Item, Heading {}

  /**
   * An item, which the user can select.
   *
   * @param label the text, also the item's label
   * @param icon shown before the text; null for none
   * @param count shown at the right in a quiet {@link Badge}, such as the number of things on the
   *     page; null for none
   * @param indent by how many steps of {@link Theme.Spacing#xl} its icon and text stand in, 0 or
   *     more
   * @param key its key in a tree dump, which a script names as {@code #<key>}, in place of {@code
   *     item-<index>}, which changes when entries come or go above it; one of its own in the list,
   *     not of that form; null for that
   * @param disabled whether it cannot be selected
   */
  public record Item(
      String label, Icon icon, String count, int indent, String key, boolean disabled)
      implements Entry {

    public Item {
      if (indent < 0) {
        throw new IllegalArgumentException("indent " + indent + " is below 0");
      }
    }

    /** This item with a count at its right. */
    public Item count(String text) {
      return new Item(label, icon, text, indent, key, disabled);
    }

    /** This item standing in by {@code levels} steps, as a heading of an outline under another. */
    public Item indent(int levels) {
      return new Item(label, icon, count, levels, key, disabled);
    }

    /** This item with its own key in place of {@code item-<index>}. */
    public Item key(String name) {
      return new Item(label, icon, count, indent, name, disabled);
    }

    /** This item, disabled or not. */
    public Item disabled(boolean off) {
      return new Item(label, icon, count, indent, key, off);
    }
  }

  /**
   * A heading over the items after it, which cannot be selected.
   *
   * @param label the text
   */
  public record Heading(String label) implements Entry {}

  private NavList() {}

  public static Item item(String label) {
    return new Item(label, null, null, 0, null, false);
  }

  public static Item item(Icon icon, String label) {
    return new Item(label, icon, null, 0, null, false);
  }

  /** A heading over the items that follow it. */
  public static Heading heading(String label) {
    return new Heading(label);
  }

  /**
   * @param selected the index of the selected item, -1 for none
   * @param onSelect told the index of the item the user selects, only when it changes; null
   *     disables the list
   */
  public static El navList(List<? extends Entry> entries, int selected, IntConsumer onSelect) {
    Theme theme = Theme.CURRENT.get();
    Rows rows = new Rows(theme, entries.size(), entries::get, selected, onSelect);
    El list = col().scroll();
    for (int i = 0; i < entries.size(); i++) {
      El r = rows.build(i).h(rows.height).shrink(0);
      list.child(entries.get(i) instanceof Heading ? r : r.position(i + 1, entries.size()));
    }
    return rows.list(list);
  }

  /**
   * A list of values of any type, such as the constants of an enum, each shown as the item {@code
   * item} makes of it, without headings.
   *
   * @param selected the selected value, found by {@code equals}; null, or a value not among them,
   *     for none
   * @param onSelect told the value the user selects, only when it changes; null disables the list
   */
  public static <T> El navList(
      List<T> values, T selected, Function<? super T, Item> item, Consumer<? super T> onSelect) {
    return navList(values, selected, item, v -> null, onSelect);
  }

  /**
   * A list of values with headings: a heading wherever a value's group differs from the one
   * before it, none for a null group. The headings count as rows, as in the form with indexes.
   *
   * <pre>{@code
   * navList(List.of(Page.values()), state.page,
   *     page -> NavList.item(page.icon, page.title), Page::group,
   *     page -> state.page = page)
   * }</pre>
   *
   * @param item the item of a value
   * @param group the heading over a value and those after it with an equal group, or null
   */
  public static <T> El navList(
      List<T> values,
      T selected,
      Function<? super T, Item> item,
      Function<? super T, String> group,
      Consumer<? super T> onSelect) {
    List<T> once = Choices.distinct(values);
    List<Entry> items = new ArrayList<>(once.size());
    int[] value = new int[once.size() * 2]; // the index of the value of each row
    int chosen = Choices.index(once, selected);
    int selectedRow = -1;
    String last = null;
    for (int i = 0; i < once.size(); i++) {
      String g = group.apply(once.get(i));
      if (g != null && !g.equals(last)) {
        value[items.size()] = -1;
        items.add(heading(g));
      }
      last = g;
      if (i == chosen) {
        selectedRow = items.size();
      }
      value[items.size()] = i;
      items.add(item.apply(once.get(i)));
    }
    return navList(
        items, selectedRow, onSelect == null ? null : r -> onSelect.accept(once.get(value[r])));
  }

  /**
   * A virtual list of {@code count} items, of which only those in view and a few more are built.
   *
   * @param entry the entry at an index; asked for the rows in view and, by the keys, for those
   *     beside the selection
   */
  public static El navList(
      int count, IntFunction<? extends Entry> entry, int selected, IntConsumer onSelect) {
    Theme theme = Theme.CURRENT.get();
    Rows rows = new Rows(theme, count, entry, selected, onSelect);
    return rows.list(col().rows(count, rows.height, rows::build));
  }

  /** What one rebuild of the list needs to build its rows and to take its keys. */
  private static final class Rows {
    final Theme theme;
    final int count;
    final IntFunction<? extends Entry> entries;
    final int selected;
    final IntConsumer onSelect;
    final float height;
    final boolean[] focused = {false}; // set by the list's focus look, read by the rows
    El selectedPill; // of a list built at once, which the focus look reaches after the rows

    Rows(
        Theme theme,
        int count,
        IntFunction<? extends Entry> entries,
        int selected,
        IntConsumer onSelect) {
      this.theme = theme;
      this.count = count;
      this.entries = entries;
      this.selected = selected;
      this.onSelect = onSelect;
      this.height = theme.size().control() + 2 * theme.space().xs();
    }

    El list(El list) {
      // Rounded, so that the focus ring is, without clipping the items' rounded fills.
      list.role("listbox")
          .radius(theme.radius().medium())
          .scrollbars(theme.scrollbars())
          .revealRow(selected);
      if (onSelect == null) {
        return list.disabled(true);
      }
      return list.focusable()
          .focusRing(theme.accent().edge())
          .whenFocused(e -> {
            focused[0] = true;
            if (selectedPill != null) {
              selectedPill.fill(theme.grid().selected());
            }
          })
          .onKey(k -> key(k, list));
    }

    El build(int i) {
      Entry at = entries.apply(i);
      if (at instanceof Heading heading) {
        // At the bottom of its row, so that the room above sets it apart from the items before.
        return row()
            .key("item-" + i)
            .align(Style.Align.END)
            .padding(
                0,
                theme.space().m(),
                theme.space().xs(),
                2 * theme.space().xs() + theme.space().s())
            .child(label(Label.Variant.SMALL, heading.label()));
      }
      Item entry = (Item) at;
      boolean enabled = onSelect != null && !entry.disabled();
      boolean on = i == selected;
      float mark = theme.size().icon();
      El pill = row()
          .grow(1)
          .gap(theme.space().s())
          .align(Style.Align.CENTER)
          .padding(0, theme.space().m(), 0, theme.space().xs())
          .radius(theme.radius().medium())
          .fill(on ? focused[0] ? theme.grid().selected() : theme.grid().selectedInactive() : 0)
          .motion(theme.motion().feedback())
          .child(el().size(theme.space().xs(), mark)
              .shrink(0)
              .margin(0, entry.indent() * theme.space().xl(), 0, 0)
              .radius(theme.space().xs() / 2)
              .fill(on ? theme.accent().edge() : 0));
      if (on) {
        selectedPill = pill;
      }
      if (entry.icon() != null) {
        El symbol = icon(entry.icon(), enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED);
        pill.child(on && enabled ? Icon.Picture.color(symbol, theme.accent().edge()) : symbol);
      }
      pill.child(label(on ? Label.Variant.STRONG : Label.Variant.NORMAL, entry.label())
          .color(enabled ? theme.text().primary() : theme.text().disabled())
          .grow(1)
          .ellipsis()
          .minSize(0, 0));
      if (entry.count() != null) {
        pill.child(
            enabled
                ? Badge.badge(entry.count())
                : label(Label.Variant.SMALL, entry.count()).color(theme.text().disabled()));
      }
      El r = row()
          .key(entry.key() != null ? entry.key() : "item-" + i)
          .role("option")
          .label(entry.label())
          .selected(on)
          .padding(theme.space().xs(), 0)
          .child(pill);
      if (!enabled) {
        return entry.disabled() ? r.disabled(true) : r;
      }
      r.cursor(Cursor.POINTER).onPress(() -> {
        if (!on) {
          onSelect.accept(i);
        }
      });
      if (!on) {
        r.whenHovered(e -> pill.fill(theme.surface().hover()))
            .whenPressed(e -> pill.fill(theme.surface().pressed()));
      }
      return r;
    }

    /** What a key does to the list with the focus; whether the list took it. */
    boolean key(Key k, El list) {
      if (k.modifiers() != 0 || count == 0) {
        return false;
      }
      int page = Math.max(1, (int) (list.height() / height));
      int from = selected;
      int next = switch (k.name()) {
        case "ArrowUp" -> from < 0 ? item(count - 1, -1) : item(from - 1, -1);
        case "ArrowDown" -> from < 0 ? item(0, 1) : item(from + 1, 1);
        case "PageUp" -> beyond(item(Math.max(0, from - page), 1), -1);
        case "PageDown" -> beyond(item(Math.min(count - 1, from + page), -1), 1);
        case "Home" -> item(0, 1);
        case "End" -> item(count - 1, -1);
        default -> -2;
      };
      if (next == -2) {
        return false;
      }
      if (next >= 0 && next != from) {
        onSelect.accept(next);
      }
      return true;
    }

    /**
     * The first item from {@code i} on in the direction of {@code step} that can be selected, else
     * the nearest one the other way; -1 when there is none, or {@code selected} at an end.
     */
    int item(int i, int step) {
      for (int j = i; j >= 0 && j < count; j += step) {
        if (selectable(j)) {
          return j;
        }
      }
      if (selected >= 0) {
        return selected;
      }
      for (int j = i; j >= 0 && j < count; j -= step) {
        if (selectable(j)) {
          return j;
        }
      }
      return -1;
    }

    /**
     * {@code found}, or, where it does not move past the selection in the direction of {@code
     * step}, as when a page holds only entries that cannot be selected, the next item that way.
     */
    int beyond(int found, int step) {
      return selected < 0 || (found - selected) * step > 0 ? found : item(selected + step, step);
    }

    /** Whether the entry at {@code i} is an item that is not disabled. */
    boolean selectable(int i) {
      return entries.apply(i) instanceof Item entry && !entry.disabled();
    }
  }
}
