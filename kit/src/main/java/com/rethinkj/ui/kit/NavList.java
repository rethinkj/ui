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
import java.util.List;
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
 * navList(t, List.of(
 *     NavList.item(Symbol.SETTINGS, "General"),
 *     NavList.item(Symbol.DATABASE, "Connections").count("3"),
 *     NavList.heading("Help"),
 *     NavList.item(Symbol.INFO, "About")), s.page, i -> s.page = i)
 *     .label("Settings")
 * }</pre>
 *
 * <p>Given a count and a function in place of a list, it is a virtual list ({@link El#rows}) that
 * builds only the items in view, the general list of one selection among many. Items are {@link
 * Theme.Sizes#control} high with {@link Theme.Spacing#xs} above and below; the selected one has
 * the fill of the grid's selection, {@link Theme.Grid#selected} while the list has the focus, and a
 * mark of {@link Theme.Accent#edge} at its left, which stands out from the surfaces also
 * where the inactive selection's fill does not. Like any scroll area, the list needs a height of
 * its own, room to grow, or no more items than fit.
 *
 * <p>In a tree dump the list is {@code role=listbox}, its items {@code role=option} with their
 * text as the label, {@code selected} and their position among all rows, headings counted, and
 * the keys {@code item-<index>}; a heading is a row without a role.
 */
public final class NavList {

  /**
   * An entry of the list: an item, or a heading over the items after it.
   *
   * @param label the text, also the item's label
   * @param icon shown before the text; null for none
   * @param count shown at the right in muted text, such as the number of things on the page; null
   *     for none
   * @param heading whether this is a heading, which cannot be selected
   */
  public record Item(String label, Symbol icon, String count, boolean heading) {

    /** This item with a count at its right. */
    public Item count(String text) {
      return new Item(label, icon, text, heading);
    }
  }

  private NavList() {}

  public static Item item(String label) {
    return new Item(label, null, null, false);
  }

  public static Item item(Symbol icon, String label) {
    return new Item(label, icon, null, false);
  }

  /** A heading over the items that follow it. */
  public static Item heading(String label) {
    return new Item(label, null, null, true);
  }

  /**
   * @param selected the index of the selected item, -1 for none
   * @param onSelect told the index of the item the user selects, only when it changes; null
   *     disables the list
   */
  public static El navList(Theme t, List<Item> items, int selected, IntConsumer onSelect) {
    Rows rows = new Rows(t, items.size(), items::get, selected, onSelect);
    El list = col().scroll();
    for (int i = 0; i < items.size(); i++) {
      El r = rows.build(i).h(rows.height).shrink(0);
      list.child(items.get(i).heading() ? r : r.position(i + 1, items.size()));
    }
    return rows.list(list);
  }

  /**
   * A virtual list of {@code count} items, of which only those in view and a few more are built.
   *
   * @param item the entry at an index; asked for the rows in view and, by the keys, for those
   *     beside the selection
   */
  public static El navList(
      Theme t, int count, IntFunction<Item> item, int selected, IntConsumer onSelect) {
    Rows rows = new Rows(t, count, item, selected, onSelect);
    return rows.list(col().rows(count, rows.height, rows::build));
  }

  /** What one rebuild of the list needs to build its rows and to take its keys. */
  private static final class Rows {
    final Theme t;
    final int count;
    final IntFunction<Item> item;
    final int selected;
    final IntConsumer onSelect;
    final float height;
    final boolean[] focused = {false}; // set by the list's focus look, read by the rows
    El selectedPill; // of a list built at once, which the focus look reaches after the rows

    Rows(Theme t, int count, IntFunction<Item> item, int selected, IntConsumer onSelect) {
      this.t = t;
      this.count = count;
      this.item = item;
      this.selected = selected;
      this.onSelect = onSelect;
      this.height = t.size().control() + 2 * t.space().xs();
    }

    El list(El list) {
      // Rounded, so that the focus ring is, without clipping the items' rounded fills.
      list.role("listbox").radius(t.radius().medium()).scrollbars(t.scrollbars())
          .revealRow(selected);
      if (onSelect == null) {
        return list.disabled(true);
      }
      return list.focusable().focusRing(t.accent().edge())
          .whenFocused(e -> {
            focused[0] = true;
            if (selectedPill != null) {
              selectedPill.fill(t.grid().selected());
            }
          })
          .onKey(k -> key(k, list));
    }

    El build(int i) {
      Item entry = item.apply(i);
      boolean enabled = onSelect != null;
      if (entry.heading()) {
        return row().key("item-" + i).align(Style.Align.CENTER)
            .padding(0, t.space().m(), 0, 2 * t.space().xs() + t.space().s())
            .child(label(t, Label.Variant.MUTED, entry.label()));
      }
      boolean on = i == selected;
      float mark = t.size().icon();
      El pill = row().grow(1).gap(t.space().s()).align(Style.Align.CENTER)
          .padding(0, t.space().m(), 0, t.space().xs())
          .radius(t.radius().medium())
          .fill(on ? focused[0] ? t.grid().selected() : t.grid().selectedInactive() : 0)
          .motion(t.motion().feedback())
          .child(el().size(t.space().xs(), mark).radius(t.space().xs() / 2)
              .fill(on ? t.accent().edge() : 0));
      if (on) {
        selectedPill = pill;
      }
      if (entry.icon() != null) {
        pill.child(icon(t, entry.icon(), enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
      }
      pill.child(label(t, entry.label()).color(enabled ? t.text().primary() : t.text().disabled())
          .grow(1).ellipsis().minSize(0, 0));
      if (entry.count() != null) {
        pill.child(label(t, Label.Variant.MUTED, entry.count())
            .color(enabled ? t.text().muted() : t.text().disabled()));
      }
      El r = row().key("item-" + i).role("option").label(entry.label()).selected(on)
          .padding(t.space().xs(), 0).child(pill);
      if (!enabled) {
        return r;
      }
      r.cursor(Cursor.POINTER).onPress(() -> {
        if (!on) {
          onSelect.accept(i);
        }
      });
      if (!on) {
        r.whenHovered(e -> pill.fill(t.surface().hover()))
            .whenPressed(e -> pill.fill(t.surface().pressed()));
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
        case "PageUp" -> item(Math.max(0, from - page), 1);
        case "PageDown" -> item(Math.min(count - 1, from + page), -1);
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
     * The first item from {@code i} on in the direction of {@code step} that is no heading, else
     * the nearest one the other way; -1 when there is none, or {@code selected} at an end.
     */
    int item(int i, int step) {
      for (int j = i; j >= 0 && j < count; j += step) {
        if (!item.apply(j).heading()) {
          return j;
        }
      }
      if (selected >= 0) {
        return selected;
      }
      for (int j = i; j >= 0 && j < count; j -= step) {
        if (!item.apply(j).heading()) {
          return j;
        }
      }
      return -1;
    }
  }
}
