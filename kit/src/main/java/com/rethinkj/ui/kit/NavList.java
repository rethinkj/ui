package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.render.Scene;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * A list of items of which one is selected, such as the pages at the side of a settings window or a
 * sidebar of sessions: each item with an optional icon, its text, an optional second line ({@link
 * Item#detail}), an optional count at the right, an optional trailing element ({@link
 * Item#trailing}), and optional actions ({@link Item#actions}) that show while the item is selected
 * or under the pointer, and optional headings between groups of items. The app keeps the selected
 * index; a press on an item selects it, while pressing an action button runs only that button's
 * handler without changing the selection. While the list has the focus Up and Down move the
 * selection past the headings, Home and End go to the first and the last item, and Page Up and Page
 * Down by the items in view. The list is one stop for Tab and follows the selection ({@link
 * El#revealRow}).
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
 * builds only the items in view, the general list of one selection among many. All rows, headings
 * included, share one height so the virtual form stays virtual: {@link Theme.Sizes#control} high
 * with {@link Theme.Spacing#xs} above and below, or two lines high when items have a {@link
 * Item#detail} (in the list form when any item has a detail; in the virtual form when the first
 * item entry has a detail). The selected item has the fill of the grid's selection, {@link
 * Theme.Grid#selected} while the list has the focus, a mark of {@link Theme.Accent#edge} at its
 * left, which stands out from the surfaces also where the inactive selection's fill does not, its
 * icon in that color and its text in {@link Theme.Type#strong}. A count is a quiet {@link Badge}; a
 * heading is {@link Theme.Type#small} at the bottom of its row, so that the room above it sets the
 * group apart, with an optional leading element ({@link Heading#leading}), quiet count ({@link
 * Heading#count}) and always-visible action buttons at its right end ({@link Heading#actions}).
 * {@link #group(String)} draws a heading as a group header on a quiet filled surface ({@link
 * Theme.Surfaces#band} inside {@link Theme.Borders#subtle}) with {@link Label.Variant#STRONG} text
 * and the chevron at the far right when collapsible. With {@link Heading#open}, a heading shows a
 * chevron ({@link Tree#OPEN} or {@link Tree#CLOSED}) and becomes a button that runs its toggle
 * handler on press, Enter, or Space; its actions sit as siblings of that button so pressing one
 * never toggles the group and Tab moves from the toggle to its actions. The app keeps which groups
 * are open and leaves a closed group's items out of the entries (in both the list form and the
 * virtual form) so they are not in the list and the keys pass them. {@code NavList} does not hide
 * items itself. Like any scroll area, the list needs a height of its own, room to grow, or no more
 * items than fit.
 *
 * <p>An item can stand in by steps, as the headings of an outline under the one above them ({@link
 * Item#indent}), can carry a context menu ({@link Item#contextMenu(Menu.State, List)}), and can be
 * disabled ({@link Item#disabled}): drawn in the disabled colors, passed by the keys and deaf to
 * presses. A secondary press on a row opens that row's context menu without changing the selection,
 * and Shift+F10 or the Menu key while the list has the focus opens the selected row's menu; one
 * {@link Menu.State} may be shared by all items of a list, and disabled items and a disabled list
 * open no menu.
 *
 * <p>In a tree dump the list is {@code role=listbox}, its items {@code role=option} with their text
 * as the label, {@code selected} or {@code disabled}, and their position among all rows, headings
 * counted, and the keys {@code item-<index>} or the item's own ({@link Item#key}), with detail
 * text, trailing element and actions as children. An item's open context menu is an overlay on that
 * row, so scripts can open it with {@code press <row> right}, or on the list when a key opened it. A plain
 * heading has no role; a collapsible heading is {@code role=button} with its text as the label and
 * {@code expanded} or {@code expanded=false}, with any heading actions as siblings after it.
 */
public final class NavList {

  /** An entry of the list: an {@link Item}, or a {@link Heading} over the items after it. */
  public sealed interface Entry permits Item, Heading {}

  /**
   * An item, which the user can select.
   *
   * @param label the text, also the item's label
   * @param icon shown before the text; null for none
   * @param detail a second line in muted small type under the label, shortened with an ellipsis,
   *     shown where the rows are two lines high (see above); null for none
   * @param count shown at the right in a quiet {@link Badge}, such as the number of things on the
   *     page; null for none
   * @param trailing an element placed at the right after the count without shrinking; null for none
   * @param actions elements placed at the far right, shown while the item is selected, under the
   *     pointer or one of them has a menu open, not at all while the item is disabled; empty for
   *     none
   * @param indent by how many steps of {@link Theme.Spacing#xl} its icon and text stand in, 0 or
   *     more
   * @param key its key in a tree dump, which a script names as {@code #<key>}, in place of {@code
   *     item-<index>}, which changes when entries come or go above it; one of its own in the list,
   *     not of that form; null for that
   * @param disabled whether it cannot be selected
   * @param menu the state of the item's context menu, which may be shared by all items of a list;
   *     null for none
   * @param contextMenu the items of the context menu shown on a secondary press, or with Shift+F10
   *     or the Menu key while selected and the list has the focus, not at all while disabled; empty
   *     for none
   */
  public record Item(
      String label,
      Icon icon,
      String detail,
      String count,
      El trailing,
      List<El> actions,
      int indent,
      String key,
      boolean disabled,
      Menu.State menu,
      List<Menu.Item> contextMenu)
      implements Entry {

    public Item {
      if (indent < 0) {
        throw new IllegalArgumentException("indent " + indent + " is below 0");
      }
      actions = actions == null ? List.of() : List.copyOf(actions);
      contextMenu = contextMenu == null ? List.of() : List.copyOf(contextMenu);
    }

    /** This item with a second line in muted small type under its label. */
    public Item detail(String text) {
      return new Item(
          label, icon, text, count, trailing, actions, indent, key, disabled, menu, contextMenu);
    }

    /** This item with a count at its right. */
    public Item count(String text) {
      return new Item(
          label, icon, detail, text, trailing, actions, indent, key, disabled, menu, contextMenu);
    }

    /** This item with a trailing element placed at its right after the count. */
    public Item trailing(El element) {
      return new Item(
          label, icon, detail, count, element, actions, indent, key, disabled, menu, contextMenu);
    }

    /**
     * This item with action elements at its far right, shown while selected or under the pointer.
     */
    public Item actions(El... buttons) {
      return new Item(
          label,
          icon,
          detail,
          count,
          trailing,
          buttons == null ? List.of() : List.of(buttons),
          indent,
          key,
          disabled,
          menu,
          contextMenu);
    }

    /** This item standing in by {@code levels} steps, as a heading of an outline under another. */
    public Item indent(int levels) {
      return new Item(
          label, icon, detail, count, trailing, actions, levels, key, disabled, menu, contextMenu);
    }

    /** This item with its own key in place of {@code item-<index>}. */
    public Item key(String name) {
      return new Item(
          label, icon, detail, count, trailing, actions, indent, name, disabled, menu, contextMenu);
    }

    /** This item, disabled or not. */
    public Item disabled(boolean off) {
      return new Item(
          label, icon, detail, count, trailing, actions, indent, key, off, menu, contextMenu);
    }

    /**
     * This item with a context menu shown on a secondary press, or with Shift+F10 or the Menu key
     * while selected and the list has the focus; not while disabled. One {@link Menu.State} may be
     * shared by all items of a list. Pass null for {@code state} or null or an empty list for
     * {@code items} for none. While the selected item has a menu, the list answers the secondary
     * presses on its other rows and its free room, for the keys, so a {@link Menu#contextMenu}
     * around the whole list opens only where the selected item has none: give the other rows
     * menus of their own.
     */
    public Item contextMenu(Menu.State state, List<Menu.Item> items) {
      return new Item(
          label, icon, detail, count, trailing, actions, indent, key, disabled, state, items);
    }
  }

  /**
   * A heading over the items after it, which cannot be selected. With {@link #open}, it opens and
   * closes the group below it: the app keeps which groups are open and leaves a closed group's
   * items out of the entries, so they are not in the list and the keys pass them. {@link
   * NavList#group(String)} draws it as a group header on a quiet filled surface with {@link
   * Label.Variant#STRONG} text and the collapse chevron at the far right.
   *
   * @param label the text, also the button's label when collapsible
   * @param count quiet count shown at the right; null for none
   * @param open whether the group below it is open; ignored when {@code onToggle} is null
   * @param onToggle runs when the user opens or closes the group; null for a plain heading
   * @param leading passive ({@link El#inert}) element shown before the text, such as an avatar;
   *     null for none
   * @param actions elements always shown at the right end of the heading, placed as siblings of the
   *     toggle button and left disabled and inert when the list is disabled; icon-sized, such as
   *     {@link Button#icon}, as a collapsible heading places them by their set width and height
   *     ({@link El#w}, {@link El#h}), else those of one {@link Theme.Sizes#control}; empty for none
   * @param group whether to draw as a group header on a quiet filled surface
   */
  public record Heading(
      String label,
      String count,
      boolean open,
      Runnable onToggle,
      El leading,
      List<El> actions,
      boolean group)
      implements Entry {

    public Heading {
      actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** A plain heading without a count, collapse toggle, leading element or actions. */
    public Heading(String label) {
      this(label, null, false, null, null, List.of(), false);
    }

    /** This heading with a quiet count at its right. */
    public Heading count(String text) {
      return new Heading(label, text, open, onToggle, leading, actions, group);
    }

    /**
     * Makes this heading collapsible; a press, or Enter or Space while focused, runs {@code
     * onToggle}. The app keeps which groups are open and leaves a closed group's items out of the
     * entries.
     */
    public Heading open(boolean open, Runnable onToggle) {
      return new Heading(label, count, open, onToggle, leading, actions, group);
    }

    /**
     * This heading with a passive ({@link El#inert}) element before its text, such as an avatar.
     */
    public Heading leading(El element) {
      return new Heading(label, count, open, onToggle, element, actions, group);
    }

    /**
     * This heading with icon-sized action elements, such as {@link Button#icon}, always visible at
     * its right end, placed as siblings of the toggle button so pressing one never toggles the
     * group.
     */
    public Heading actions(El... buttons) {
      return new Heading(
          label,
          count,
          open,
          onToggle,
          leading,
          buttons == null ? List.of() : List.of(buttons),
          group);
    }
  }

  private NavList() {}

  public static Item item(String label) {
    return new Item(label, null, null, null, null, List.of(), 0, null, false, null, List.of());
  }

  public static Item item(Icon icon, String label) {
    return new Item(label, icon, null, null, null, List.of(), 0, null, false, null, List.of());
  }

  /** A heading over the items that follow it. */
  public static Heading heading(String label) {
    return new Heading(label);
  }

  /**
   * A heading drawn as a group header on a quiet filled surface over the items that follow it, with
   * {@link Label.Variant#STRONG} text and its collapse chevron at the far right.
   */
  public static Heading group(String label) {
    return new Heading(label, null, false, null, null, List.of(), true);
  }

  /**
   * @param selected the index of the selected item, -1 for none
   * @param onSelect told the index of the item the user selects, only when it changes; null
   *     disables the list
   */
  public static El navList(List<? extends Entry> entries, int selected, IntConsumer onSelect) {
    return navList(entries, selected, onSelect, false);
  }

  /**
   * A table of contents, such as the headings of a document: a {@link #navList} whose {@code
   * current} item marks the heading in view, and which tells every press, also one on the current
   * item, so that a press goes back to its heading.
   *
   * @param current the index of the item to mark, -1 for none
   * @param onPress told the index of the item the user presses or moves to with the keyboard, and
   *     of the current item for Enter or Space while the list has the focus
   */
  public static El outline(List<? extends Entry> entries, int current, IntConsumer onPress) {
    return navList(entries, current, Objects.requireNonNull(onPress, "onPress"), true);
  }

  private static El navList(
      List<? extends Entry> entries, int selected, IntConsumer onSelect, boolean again) {
    Theme theme = Theme.CURRENT.get();
    boolean twoLine = false;
    for (Entry entry : entries) {
      if (entry instanceof Item item && item.detail() != null) {
        twoLine = true;
        break;
      }
    }
    Rows rows = new Rows(theme, entries.size(), entries::get, selected, onSelect, twoLine);
    rows.again = again;
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
    boolean twoLine = false;
    for (int i = 0; i < count; i++) {
      if (entry.apply(i) instanceof Item item) {
        twoLine = item.detail() != null;
        break;
      }
    }
    Rows rows = new Rows(theme, count, entry, selected, onSelect, twoLine);
    return rows.list(col().rows(count, rows.height, rows::build));
  }

  /**
   * Identifies the row a shared {@link Menu.State} is open for, and whether a key opened it, in
   * which case the menu hangs from the list, also while the row is out of view.
   */
  private static final class MenuAnchor {
    final String key;
    final String labelAtIndex;
    final boolean byKey;
    boolean shown; // set once a key-opened menu has appeared, so a later reappearance closes it

    MenuAnchor(String key, String labelAtIndex, boolean byKey) {
      this.key = key;
      this.labelAtIndex = labelAtIndex;
      this.byKey = byKey;
    }

    String key() {
      return key;
    }

    String labelAtIndex() {
      return labelAtIndex;
    }

    boolean byKey() {
      return byKey;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof MenuAnchor that
          && byKey == that.byKey
          && Objects.equals(key, that.key)
          && Objects.equals(labelAtIndex, that.labelAtIndex);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, labelAtIndex, byKey);
    }
  }

  /** What one rebuild of the list needs to build its rows and to take its keys. */
  private static final class Rows {
    final Theme theme;
    final int count;
    final IntFunction<? extends Entry> entries;
    final int selected;
    final IntConsumer onSelect;
    final float height;
    final boolean twoLine;
    final Item selectedMenu; // the selected item when it is enabled and has a context menu
    final MenuAnchor keyAnchor; // of the selected item's menu when a key opens it
    final boolean[] focused = {false}; // set by the list's focus look, read by the rows
    El selectedPill; // of a list built at once, which the focus look reaches after the rows
    boolean again; // a press on the selected item tells it again, as in an outline
    boolean menuKey; // the Menu key or Shift+F10 came, which the list's secondary press answers

    Rows(
        Theme theme,
        int count,
        IntFunction<? extends Entry> entries,
        int selected,
        IntConsumer onSelect,
        boolean twoLine) {
      this.theme = theme;
      this.count = count;
      this.entries = entries;
      this.selected = selected;
      this.onSelect = onSelect;
      this.twoLine = twoLine;
      float single = theme.size().control() + 2 * theme.space().xs();
      this.height =
          twoLine ? single + theme.type().small().size() + theme.space().s() : single;
      Item menuItem = null;
      if (onSelect != null
          && selected >= 0
          && selected < count
          && entries.apply(selected) instanceof Item item
          && !item.disabled()
          && hasMenu(item)) {
        menuItem = item;
      }
      this.selectedMenu = menuItem;
      this.keyAnchor = menuItem == null ? null : anchor(rowKey(selected, menuItem), menuItem, true);
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
      if (selectedMenu != null) {
        Menu.State menu = selectedMenu.menu();
        // Only for the keys: a secondary press on the list's own room opens no row's menu.
        list.onSecondaryPress((x, y) -> {
          if (menuKey) {
            menuKey = false;
            menu.openAt(x, y);
            menu.anchor = anchor(keyAnchor.key, selectedMenu, true);
          }
        });
        if (menu.isOpen() && menu.anchor instanceof MenuAnchor open && keyAnchor.equals(open)) {
          Menu.contextMenu(list, menu, selectedMenu.contextMenu());
          // A key-opened menu hangs from the list: if a build dropped it (such as when the app
          // selected another row while this row was out of view) and it appears again, close it.
          list.overlays().getLast().onShownFor(0, () -> {
            if (menu.anchor != open) {
              return;
            }
            if (open.shown) {
              menu.close();
            } else {
              open.shown = true;
            }
          });
        }
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
        return heading(i, heading);
      }
      Item entry = (Item) at;
      boolean enabled = onSelect != null && !entry.disabled();
      boolean on = i == selected;
      float mark = theme.size().icon();
      El pill = row()
          .grow(1)
          .minSize(0, 0)
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
      El title = label(on ? Label.Variant.STRONG : Label.Variant.NORMAL, entry.label())
          .color(enabled ? theme.text().primary() : theme.text().disabled())
          .ellipsis()
          .minSize(0, 0);
      if (entry.detail() == null || !twoLine) {
        pill.child(title.grow(1));
      } else {
        El detail = label(Label.Variant.SMALL, entry.detail())
            .color(enabled ? theme.text().muted() : theme.text().disabled())
            .ellipsis()
            .minSize(0, 0);
        pill.child(col().grow(1).minSize(0, 0).gap(theme.space().xs()).children(title, detail));
      }
      if (entry.count() != null) {
        pill.child(
            enabled
                ? Badge.badge(entry.count())
                : label(Label.Variant.SMALL, entry.count()).color(theme.text().disabled()));
      }
      if (entry.trailing() != null) {
        pill.child(entry.trailing().shrink(0));
      }
      El actions = null;
      if (enabled && !entry.actions().isEmpty()) {
        // Takes the presses its buttons leave, such as of a disabled one, from the row.
        actions =
            row().gap(theme.space().xs()).align(Style.Align.CENTER).shrink(0).onPress(() -> {});
        boolean opened = false;
        for (El button : entry.actions()) {
          actions.child(button.shrink(0));
          opened |= opened(button);
        }
        // Out of the layout until shown, so that it keeps no room and no stops for Tab; shown
        // while one of them has a menu open, which the pointer leaves the row for.
        actions.style().display = on || opened ? Style.Display.FLEX : Style.Display.NONE;
        pill.child(actions);
      }
      String rowKey = rowKey(i, entry);
      El r = row()
          .key(rowKey)
          .role("option")
          .label(entry.label())
          .selected(on)
          .padding(theme.space().xs(), 0)
          .child(pill);
      if (!enabled) {
        closeAnchored(rowKey, entry);
        return entry.disabled() ? r.disabled(true) : r;
      }
      if (hasMenu(entry)) {
        MenuAnchor anchor = anchor(rowKey, entry, false);
        Menu.State menu = entry.menu();
        if (menu.isOpen()
            && menu.anchor instanceof MenuAnchor open
            && open.byKey()
            && !open.equals(keyAnchor)) {
          menu.close(); // a key opened it for a row the selection has left
        }
        r.onSecondaryPress((x, y) -> {
          menu.openAt(x, y);
          menu.anchor = anchor;
        });
        if (menu.isOpen() && anchor.equals(menu.anchor)) {
          Menu.contextMenu(r, menu, entry.contextMenu());
        }
      } else {
        closeAnchored(rowKey, entry);
      }
      r.cursor(Cursor.POINTER).onPress(() -> {
        if (!on || again) {
          onSelect.accept(i);
        }
      });
      if (!on) {
        El reveal = actions;
        r.whenHovered(e -> {
              pill.fill(theme.surface().hover());
              if (reveal != null) {
                reveal.style().display = Style.Display.FLEX;
              }
            })
            .whenPressed(e -> {
              pill.fill(theme.surface().pressed());
              if (reveal != null) {
                // Inert while the row has the press, which may end where the strip shows up.
                reveal.inert().style().display = Style.Display.FLEX;
              }
            });
      }
      return r;
    }

    private El heading(int i, Heading heading) {
      boolean enabled = onSelect != null;
      boolean collapsible = heading.onToggle() != null;
      boolean hasActions = !heading.actions().isEmpty();
      if (!heading.group() && !collapsible && heading.leading() == null && !hasActions) {
        // At the bottom of its row, so that the room above sets it apart from the items before.
        El r = row()
            .key("item-" + i)
            .align(Style.Align.END)
            .padding(
                0,
                theme.space().m(),
                theme.space().xs(),
                2 * theme.space().xs() + theme.space().s());
        if (heading.count() == null) {
          return r.child(label(Label.Variant.SMALL, heading.label()));
        }
        return r.gap(theme.space().s())
            .child(
                label(Label.Variant.SMALL, heading.label()).grow(1).ellipsis().minSize(0, 0))
            .child(label(Label.Variant.SMALL, heading.count()).shrink(0));
      }

      float chevronSize = theme.type().small().size();
      float barHeight = heading.group() && twoLine
          ? theme.size().controlLarge()
          : theme.size().control();
      float padLeft = heading.group()
          ? theme.space().m() - Scene.RING
          : 2 * theme.space().xs() + theme.space().s() - Scene.RING;
      float padRight = theme.space().m() - Scene.RING;
      float stroke = heading.group() ? theme.size().stroke() : 0;

      El bar = row()
          .key("item-" + i)
          .grow(1)
          .minSize(0, 0)
          .h(barHeight)
          .gap(theme.space().s())
          .align(Style.Align.CENTER)
          .padding(
              0,
              !heading.group() && collapsible && hasActions
                  ? padRight + actionsWidth(heading.actions()) + theme.space().s()
                  : padRight,
              0,
              padLeft)
          .radius(theme.radius().medium());

      if (heading.group()) {
        bar.fill(theme.surface().band()).border(stroke, theme.border().subtle());
      }
      if (collapsible) {
        bar.role("button")
            .label(heading.label())
            .expanded(heading.open())
            .motion(theme.motion().feedback());
        if (!enabled) {
          bar.disabled(true);
        } else {
          int hover = heading.group()
              ? Theme.over(theme.surface().band(), theme.surface().hover())
              : theme.surface().hover();
          int pressed = heading.group()
              ? Theme.over(theme.surface().band(), theme.surface().pressed())
              : theme.surface().pressed();
          bar.focusable()
              .keepFocus()
              .focusRing(theme.accent().edge())
              .cursor(Cursor.POINTER)
              .onPress(heading.onToggle())
              .whenHovered(e -> e.fill(hover))
              .whenPressed(e -> e.fill(pressed));
        }
      }

      if (!heading.group() && collapsible) {
        bar.child(icon(heading.open() ? Tree.OPEN : Tree.CLOSED, Icon.Variant.MUTED, chevronSize)
            .shrink(0));
      }
      if (heading.leading() != null) {
        bar.child(heading.leading().inert().shrink(0));
      }
      El title = label(
              heading.group() ? Label.Variant.STRONG : Label.Variant.SMALL, heading.label())
          .grow(1)
          .ellipsis()
          .minSize(0, 0);
      bar.child(title);
      if (heading.count() != null) {
        // Shrinks with the label where room runs out, so that the chevron stays in the bar.
        bar.child(label(Label.Variant.SMALL, heading.count()).ellipsis().minSize(0, 0));
      }
      if (!collapsible && hasActions) {
        bar.child(headingActions(heading.actions(), enabled));
      }
      if (heading.group() && collapsible) {
        El chevron = icon(heading.open() ? Tree.OPEN : Tree.CLOSED, Icon.Variant.MUTED, chevronSize)
            .shrink(0);
        if (hasActions) {
          // Leave room before the chevron for the sibling actions strip so the label ellipsizes
          // before the actions while the chevron stays at the far right.
          chevron.margin(
              0, 0, 0, actionsWidth(heading.actions()) + theme.space().s());
        }
        bar.child(chevron);
      }

      // At the bottom of its row, so that the room above sets it apart from the items before,
      // with room for the focus ring inside the list's scroll clip.
      El r =
          row().align(Style.Align.END).padding(theme.space().xs(), Scene.RING).child(bar);
      if (collapsible && hasActions) {
        float right = heading.group()
            ? Scene.RING + stroke + padRight + chevronSize + theme.space().s()
            : Scene.RING + padRight;
        right += actionsWidth(heading.actions());
        // Each its own sibling after the toggle, centered on the bar, so that the bar's room
        // around and between them stays the toggle's.
        for (El button : heading.actions()) {
          float w = size(button.style().width);
          right -= w;
          Style at = button.shrink(0).style();
          at.position = Style.Position.ABSOLUTE;
          at.right = Len.px(right);
          at.bottom = Len.px(theme.space().xs() + (barHeight - size(at.height)) / 2);
          r.child(enabled ? button : button.disabled(true).inert());
          right -= theme.space().xs();
        }
      }
      return r;
    }

    private El headingActions(List<El> buttons, boolean enabled) {
      El strip = row().gap(theme.space().xs()).align(Style.Align.CENTER).shrink(0);
      for (El button : buttons) {
        strip.child(enabled ? button.shrink(0) : button.shrink(0).disabled(true));
      }
      return enabled ? strip.keepFocus().onPress(() -> {}) : strip.inert();
    }

    private float actionsWidth(List<El> buttons) {
      float width = (buttons.size() - 1) * theme.space().xs();
      for (El button : buttons) {
        width += size(button.style().width);
      }
      return width;
    }

    /** An action's set size, else that of one control. */
    private float size(Len set) {
      return set.kind() == Len.Kind.PX ? set.value() : theme.size().control();
    }

    /** Whether the element or one inside it has an overlay, such as an open menu. */
    private static boolean opened(El element) {
      return !element.overlays().isEmpty() || element.children().stream().anyMatch(Rows::opened);
    }

    private static boolean hasMenu(Item item) {
      return item.menu() != null && !item.contextMenu().isEmpty();
    }

    private static String rowKey(int index, Item item) {
      return item.key() != null ? item.key() : "item-" + index;
    }

    private static MenuAnchor anchor(String rowKey, Item item, boolean byKey) {
      return new MenuAnchor(rowKey, item.key() == null ? item.label() : null, byKey);
    }

    private static void closeAnchored(String rowKey, Item entry) {
      if (entry.menu() != null
          && entry.menu().isOpen()
          && entry.menu().anchor instanceof MenuAnchor open
          && open.key().equals(rowKey)
          && Objects.equals(open.labelAtIndex(), anchor(rowKey, entry, false).labelAtIndex())) {
        entry.menu().close();
      }
    }

    /** What a key does to the list with the focus; whether the list took it. */
    boolean key(Key k, El list) {
      if (selectedMenu != null
          && selectedMenu.menu().isOpen()
          && keyAnchor.equals(selectedMenu.menu().anchor)) {
        return false; // the menu the keys opened keeps them, and the row it hangs from
      }
      // Left to the App, which answers them with the list's secondary press at the selected row.
      menuKey = selectedMenu != null
          && list.isFocused()
          && (k.name().equals("ContextMenu") && k.modifiers() == 0
              || k.name().equals("F10") && k.modifiers() == Key.SHIFT);
      if (k.modifiers() != 0 || count == 0) {
        return false;
      }
      if (again && list.isFocused() && (k.name().equals("Enter") || k.name().equals("Space"))) {
        if (selected < 0 || selected >= count || !selectable(selected)) {
          return false;
        }
        if (!k.repeat()) { // as a press on the current item of an outline
          onSelect.accept(selected);
        }
        return true;
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
