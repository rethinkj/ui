package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Locale;

/**
 * A menu of items in an overlay: each with its text, an optional shortcut at the right, a check
 * mark when it is checked, greyed out when it is disabled, and separators between groups. An item
 * may open a submenu beside it, one level deep, after the pointer rests on it ({@link
 * Theme.Motion#submenuDelay}) or with Arrow Right. As a dropdown it hangs below a button, as a
 * context menu it opens where the secondary button was pressed:
 *
 * <pre>{@code
 * El file = Menu.dropdown(t, button(t, SECONDARY, "File", s.file::toggle), s.file, List.of(
 *     Menu.item("New query", s::newQuery).shortcut("Ctrl+N"),
 *     Menu.separator(),
 *     Menu.submenu("Export", List.of(Menu.item("CSV", s::csv), Menu.item("JSON", s::json))),
 *     Menu.item("Word wrap", s::toggleWrap).checked(s.wrap),
 *     Menu.item("Close", null)));                            // disabled
 * El tables = Menu.contextMenu(t, list, s.tableMenu, items); // opens at a right press
 * }</pre>
 *
 * <p>The app keeps a {@link State} for each menu in its state: whether it is open, where, and
 * which items the pointer or the keys highlight. The menu's handlers change it, and the app's own
 * handlers open it ({@link State#toggle}, {@link State#openAt}). Choosing an item closes the menu
 * and then runs the item's handler.
 *
 * <p>The menu takes the focus when it opens and gives it back when it closes. Up and Down move the
 * highlight over the items, around at the ends and past separators, Home and End go to the first
 * and the last, Enter and Space choose, Arrow Right opens the highlighted item's submenu and
 * Arrow Left or Escape close it again, Escape and Tab close the menu, and a letter or digit goes
 * to the next item whose text starts with it. The pointer highlights the item it is over; a press
 * outside the menu closes it and does nothing else.
 *
 * <p>In a tree dump a menu is {@code role=menu} and its items are {@code role=menuitem} with
 * their text as the label and the keys {@code item-<index>}, or {@code subitem-<index>} in the
 * submenu, counted with the separators.
 */
public final class Menu {

  /** The mark of an item that opens a submenu. */
  public static final String SUBMENU = "▶";

  /** The mark of a checked item. */
  public static final String CHECKED = "✓";

  private Menu() {}

  /**
   * One entry of a menu: an item, or a separator when its text is null.
   *
   * @param label the text, also the item's label; null for a separator
   * @param shortcut shown at the right in muted text, such as {@code Ctrl+C}; null for none
   * @param checked whether the item shows a check mark
   * @param onChoose runs when the item is chosen; null disables an item without a submenu
   * @param submenu the items of the submenu the item opens, or null for none
   */
  public record Item(
      String label, String shortcut, boolean checked, Runnable onChoose, List<Item> submenu) {

    /** This item with a shortcut shown at its right. */
    public Item shortcut(String text) {
      return new Item(label, text, checked, onChoose, submenu);
    }

    /** This item with a check mark, or without. */
    public Item checked(boolean on) {
      return new Item(label, shortcut, on, onChoose, submenu);
    }

    public boolean isSeparator() {
      return label == null;
    }

    /** Whether the item can be chosen or opens a submenu with items. */
    public boolean isEnabled() {
      return onChoose != null || submenu != null && !submenu.isEmpty();
    }
  }

  /** An item that runs {@code onChoose} when it is chosen; null disables it. */
  public static Item item(String label, Runnable onChoose) {
    return new Item(label, null, false, onChoose, null);
  }

  /** An item that opens a submenu with these items beside it. */
  public static Item submenu(String label, List<Item> items) {
    return new Item(label, null, false, null, List.copyOf(items));
  }

  /** A line between groups of items. */
  public static Item separator() {
    return new Item(null, null, false, null, null);
  }

  /**
   * What the app keeps of a menu: whether it is open, the point it opened at, and the item the
   * pointer or the keys highlight in it and in its submenu.
   */
  public static final class State {
    private boolean open;
    private float x = Float.NaN;
    private float y = Float.NaN;
    int active = -1; // the highlighted item, -1 for none
    int sub = -1; // the item whose submenu is open
    int subActive = -1; // the highlighted item of the submenu

    public boolean isOpen() {
      return open;
    }

    /** Opens the menu with no item highlighted. */
    public void open() {
      openAt(Float.NaN, Float.NaN);
    }

    /**
     * Opens the menu at a point of the window, for a context menu, with no item highlighted; an
     * {@link El.PointHandler}, so that {@code onSecondaryPress(state::openAt)} opens it.
     */
    public void openAt(float x, float y) {
      close();
      this.open = true;
      this.x = x;
      this.y = y;
    }

    public void close() {
      open = false;
      active = -1;
      sub = -1;
      subActive = -1;
    }

    /** Opens the menu if it is closed and closes it if it is open, for a menu button. */
    public void toggle() {
      if (open) {
        close();
      } else {
        open();
      }
    }

    /** The highlighted item of the menu, -1 for none. */
    public int active() {
      return active;
    }

    /** The item whose submenu is open, -1 for none. */
    public int submenu() {
      return sub;
    }
  }

  /**
   * Attaches the menu below {@code anchor}, at its start, while {@code state} is open; where there
   * is no room below, above it. The anchor is usually a button whose press toggles the state.
   *
   * @return the anchor
   */
  public static El dropdown(Theme t, El anchor, State state, List<Item> items) {
    return anchor.overlay(state.isOpen()
        ? menu(t, state, items).side(Side.BOTTOM, Style.Align.START, t.space().xs())
        : null);
  }

  /**
   * Attaches the menu to {@code target} at the point it opened at while {@code state} is open, and
   * opens it there with the secondary button, unless the target has a handler for that already:
   * the rows of a list, for example, can each open it after selecting themselves.
   *
   * @return the target
   */
  public static El contextMenu(Theme t, El target, State state, List<Item> items) {
    if (target.onSecondaryPress() == null) {
      target.onSecondaryPress(state::openAt);
    }
    return target.overlay(state.isOpen() ? menu(t, state, items).atPoint(state.x, state.y) : null);
  }

  /**
   * The menu's panel, an overlay to attach to an element with {@link El#overlay} and place, as
   * {@link #dropdown} and {@link #contextMenu} do; it is dismissed with {@link State#close}.
   */
  public static El menu(Theme t, State state, List<Item> items) {
    return panel(t, state, items, false).onDismiss(state::close);
  }

  private static El panel(Theme t, State state, List<Item> items, boolean sub) {
    El panel = col().key(sub ? "submenu" : "menu").role("menu").focusable()
        .padding(t.space().s())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().subtle())
        .scroll()
        .scrollbars(t.scrollbars())
        .onKey(k -> key(k, state, items, sub));
    for (int i = 0; i < items.size(); i++) {
      panel.child(items.get(i).isSeparator()
          ? el().h(t.size().stroke()).shrink(0).margin(t.space().xs(), 0, t.space().xs(), 0)
              .fill(t.border().subtle())
          : item(t, state, items, i, sub));
    }
    return panel;
  }

  private static El item(Theme t, State state, List<Item> items, int i, boolean sub) {
    Item item = items.get(i);
    boolean enabled = item.isEnabled();
    boolean lit = i == (sub ? state.subActive : state.active);
    int text = !enabled ? t.text().disabled() : lit ? t.text().onAccent() : t.text().primary();
    int quiet = !enabled ? t.text().disabled() : lit ? t.text().onAccent() : t.text().muted();
    El mark = el().w(t.size().checkbox()).shrink(0).center();
    if (item.checked()) {
      mark.child(label(t, CHECKED).color(text));
    }
    El content = row().wFull().hFull().padding(0, t.space().m()).gap(t.space().m())
        .align(Style.Align.CENTER)
        .children(mark, label(t, item.label()).color(text).grow(1));
    boolean opens = item.submenu() != null && !item.submenu().isEmpty();
    if (opens && !sub) {
      Theme.TextStyle mono = t.type().mono();
      content.child(El.text(SUBMENU).font(mono.font().get()).fontSize(mono.size()).color(quiet));
    } else if (item.shortcut() != null) {
      content.child(label(t, item.shortcut()).color(quiet).margin(0, 0, 0, t.space().l()));
    }
    if (!sub) { // after a rest, the item's submenu opens and any other closes
      content.onHoverFor(t.motion().submenuDelay(), () -> {
        if (state.sub != (opens ? i : -1)) {
          state.sub = opens ? i : -1;
          state.subActive = -1;
        }
      });
    }
    int fill = !lit ? 0
        : enabled ? t.accent().color()
        : over(t.surface().raised(), t.surface().hover());
    El el = row().key((sub ? "subitem-" : "item-") + i).role("menuitem").label(item.label())
        .h(t.size().control()).shrink(0).radius(t.radius().small()).fill(fill)
        .onHoverFor(0, () -> highlight(state, sub, i))
        .child(content);
    if (enabled) {
      el.onPress(() -> choose(state, items, i, sub));
    }
    if (opens && !sub && state.sub == i) {
      el.overlay(panel(t, state, item.submenu(), true)
          .side(Side.RIGHT, Style.Align.START, t.space().s() + t.size().stroke())
          .onDismiss(() -> {
            state.sub = -1;
            state.subActive = -1;
          }));
    }
    return el;
  }

  private static void highlight(State state, boolean sub, int i) {
    if (sub) {
      state.subActive = i;
    } else {
      state.active = i;
    }
  }

  /** Chooses an item: opens its submenu, or closes the menu and runs its handler. */
  private static void choose(State state, List<Item> items, int i, boolean sub) {
    Item item = items.get(i);
    if (item.submenu() != null) {
      if (!sub && item.isEnabled()) {
        state.active = i;
        state.sub = i;
      }
    } else if (item.onChoose() != null) {
      state.close();
      item.onChoose().run();
    }
  }

  /** What a key does in the menu or, with {@code sub}, in its submenu; whether it took it. */
  private static boolean key(Key k, State state, List<Item> items, boolean sub) {
    if ((k.modifiers() & ~Key.SHIFT) != 0) {
      return false;
    }
    int active = sub ? state.subActive : state.active;
    int next = switch (k.name()) {
      case "ArrowDown" -> step(items, active, 1);
      case "ArrowUp" -> step(items, active < 0 ? items.size() : active, -1);
      case "Home" -> step(items, -1, 1);
      case "End" -> step(items, items.size(), -1);
      default -> -2;
    };
    if (next != -2) {
      highlight(state, sub, next);
      return true;
    }
    switch (k.name()) {
      case "Enter", "Space" -> {
        if (active >= 0) {
          choose(state, items, active, sub);
          if (!sub && state.sub == active) {
            state.subActive = step(items.get(active).submenu(), -1, 1);
          }
        }
        return true;
      }
      case "ArrowRight" -> {
        if (!sub && active >= 0 && items.get(active).submenu() != null
            && items.get(active).isEnabled()) {
          state.sub = active;
          state.subActive = step(items.get(active).submenu(), -1, 1);
        }
        return true;
      }
      case "ArrowLeft", "Escape" -> {
        if (sub) {
          state.sub = -1;
          state.subActive = -1;
        } else if (k.name().equals("Escape")) {
          state.close();
        }
        return true;
      }
      case "Tab" -> {
        if (sub) {
          return false; // the menu closes
        }
        state.close();
        return true;
      }
      default -> {
        String name = k.name();
        if (name.length() == 1 && Character.isLetterOrDigit(name.charAt(0))) {
          int found = startingWith(items, active, name);
          if (found >= 0) {
            highlight(state, sub, found);
          }
          return true;
        }
        return false;
      }
    }
  }

  /** The next item from {@code from} in this direction, around at the ends; -1 if there is none. */
  private static int step(List<Item> items, int from, int direction) {
    int n = items.size();
    for (int i = 1; i <= n + 1; i++) {
      int at = Math.floorMod(from + direction * i, n + 1);
      if (at < n && !items.get(at).isSeparator()) {
        return at;
      }
    }
    return -1;
  }

  /** The next item after {@code from} whose text starts with {@code letter}; -1 for none. */
  private static int startingWith(List<Item> items, int from, String letter) {
    String start = letter.toLowerCase(Locale.ROOT);
    int n = items.size();
    for (int i = 1; i <= n; i++) {
      int at = Math.floorMod(from + i, n);
      Item item = items.get(at);
      if (!item.isSeparator() && item.label().toLowerCase(Locale.ROOT).startsWith(start)) {
        return at;
      }
    }
    return -1;
  }
}
