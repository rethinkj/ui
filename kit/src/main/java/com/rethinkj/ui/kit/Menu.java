package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * A menu of items in an overlay: each with its text, an optional icon before it, an optional
 * shortcut at the right, a check mark when it is checked, greyed out when it is disabled, and
 * separators between groups. An item may open a submenu beside it, and the items of a submenu
 * submenus of their own, to any depth, after the pointer rests on it ({@link
 * Theme.Motion#submenuDelay}) or with Arrow Right. As a dropdown it hangs below a button, as a
 * context menu it opens where the secondary button was pressed, and a {@link MenuBar} opens one
 * below each of its titles:
 *
 * <pre>{@code
 * List<Menu.Item> fileItems = List.of(
 *     Menu.item("New query", s::newQuery).icon(Symbol.ADD).shortcut(Key.CONTROL, "N"),
 *     Menu.separator(),
 *     Menu.submenu("Export", List.of(Menu.item("CSV", s::csv), Menu.item("JSON", s::json))),
 *     Menu.item("Word wrap", s::toggleWrap).checked(s.wrap),
 *     Menu.item("Close", null));                             // disabled
 * El file = Menu.dropdown(t, button(t, SECONDARY, "File", s.file::toggle), s.file, fileItems);
 * El tables = Menu.contextMenu(t, list, s.tableMenu, items); // opens at a right press
 * root.onKey(Menu.shortcuts(fileItems));                     // Ctrl+N runs New query
 * }</pre>
 *
 * <p>The app keeps a {@link State} for each menu in its state: whether it is open, where, and
 * which items the pointer or the keys highlight at each level. The menu's handlers change it, and
 * the app's own handlers open it ({@link State#toggle}, {@link State#openAt}). Choosing an item
 * closes the menu and then runs the item's handler.
 *
 * <p>The menu takes the focus when it opens and gives it back when it closes. Up and Down move the
 * highlight over the items, around at the ends and past separators, Home and End go to the first
 * and the last, Enter and Space choose, Arrow Right opens the highlighted item's submenu and
 * Arrow Left or Escape close it again, Escape and Tab close the menu, and a letter or digit goes
 * to the next item whose text starts with it. In a menu bar, Arrow Right on an item without a
 * submenu and Arrow Left in the bar's own menu go to the next and the previous menu. The pointer
 * highlights the item it is over; a press outside the menu closes it and does nothing else.
 *
 * <p>A {@linkplain Item#shortcut(int, String) shortcut} that an item declares with its keys acts:
 * {@link #shortcuts} makes a key handler for the root that runs the item's handler when no element
 * on the focus path took the key, open menu or not, unless the item is disabled. A focused text
 * field takes its own keys first, so Control+C there copies its text and does not run a menu's
 * Copy.
 *
 * <p>Marks are icons ({@link Symbol}): {@link #CHECKED} for a checked item, {@link #SUBMENU} for
 * one that opens a submenu. The column before the text holds the check mark or the item's icon,
 * the check mark first, and is there in every item, so the texts line up.
 *
 * <p>In a tree dump a menu is {@code role=menu} and its items are {@code role=menuitem} with
 * their text as the label and the keys {@code item-<index>}, or {@code subitem-<index>} in a
 * submenu, counted with the separators.
 */
public final class Menu {

  /** The mark of an item that opens a submenu. */
  public static final Symbol SUBMENU = Symbol.CHEVRON_RIGHT;

  /** The mark of a checked item. */
  public static final Symbol CHECKED = Symbol.CHECKMARK;

  private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

  private Menu() {}

  /**
   * One entry of a menu: an item, or a separator when its text is null.
   *
   * @param label the text, also the item's label; null for a separator
   * @param shortcut shown at the right in muted text, such as {@code Ctrl+C}; null for none
   * @param keys the key combination that chooses the item through {@link #shortcuts} or a {@link
   *     MenuBar}, with its modifiers; null for none
   * @param checked whether the item shows a check mark
   * @param icon shown before the text, unless the item is checked; null for none
   * @param onChoose runs when the item is chosen; null disables an item without a submenu
   * @param submenu the items of the submenu the item opens, or null for none
   */
  public record Item(String label, String shortcut, Key keys, boolean checked, Symbol icon,
      Runnable onChoose, List<Item> submenu) {

    /** This item with a text shown at its right, which only shows: no key runs the item. */
    public Item shortcut(String text) {
      return new Item(label, text, keys, checked, icon, onChoose, submenu);
    }

    /**
     * This item with a key combination that runs it, shown at its right as the platform writes it
     * ({@link #shortcutText}): {@code shortcut(Key.CONTROL | Key.SHIFT, "N")} shows {@code
     * Ctrl+Shift+N}.
     *
     * @param modifiers a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT}
     *     and {@link Key#META}
     * @param key the key's name, as {@link Key} lists them: {@code N}, {@code F5}, {@code Delete}
     */
    public Item shortcut(int modifiers, String key) {
      Key k = new Key(key, modifiers, false);
      return new Item(label, shortcutText(k), k, checked, icon, onChoose, submenu);
    }

    /** This item with a check mark, or without. */
    public Item checked(boolean on) {
      return new Item(label, shortcut, keys, on, icon, onChoose, submenu);
    }

    /** This item with an icon before its text; a check mark takes its place while it is checked. */
    public Item icon(Symbol symbol) {
      return new Item(label, shortcut, keys, checked, symbol, onChoose, submenu);
    }

    public boolean isSeparator() {
      return label == null;
    }

    /** Whether the item can be chosen or opens a submenu with items. */
    public boolean isEnabled() {
      return onChoose != null || opens();
    }

    /** Whether the item opens a submenu with items. */
    boolean opens() {
      return submenu != null && !submenu.isEmpty();
    }
  }

  /** An item that runs {@code onChoose} when it is chosen; null disables it. */
  public static Item item(String label, Runnable onChoose) {
    return new Item(label, null, null, false, null, onChoose, null);
  }

  /** An item that opens a submenu with these items beside it. */
  public static Item submenu(String label, List<Item> items) {
    return new Item(label, null, null, false, null, null, List.copyOf(items));
  }

  /** A line between groups of items. */
  public static Item separator() {
    return new Item(null, null, null, false, null, null, null);
  }

  /**
   * A key combination as the platform writes it in menus: the modifiers {@code Ctrl}, {@code Alt},
   * {@code Shift} in that order after the system key ({@code Win} on Windows, {@code Super} on
   * Linux), then the key: {@code Ctrl+Shift+N}, {@code Alt+F4}, {@code F5}. Arrows are {@code Up},
   * {@code Down}, {@code Left} and {@code Right}, Escape is {@code Esc}; Windows writes {@code
   * Del}, {@code Ins}, {@code PgUp} and {@code PgDn} where Linux writes {@code Delete}, {@code
   * Insert}, {@code Page Up} and {@code Page Down}.
   */
  public static String shortcutText(Key key) {
    return shortcutText(key, WINDOWS);
  }

  static String shortcutText(Key key, boolean windows) {
    StringBuilder text = new StringBuilder();
    int m = key.modifiers();
    if ((m & Key.META) != 0) {
      text.append(windows ? "Win+" : "Super+");
    }
    if ((m & Key.CONTROL) != 0) {
      text.append("Ctrl+");
    }
    if ((m & Key.ALT) != 0) {
      text.append("Alt+");
    }
    if ((m & Key.SHIFT) != 0) {
      text.append("Shift+");
    }
    String name = key.name();
    return text.append(switch (name) {
      case "ArrowUp" -> "Up";
      case "ArrowDown" -> "Down";
      case "ArrowLeft" -> "Left";
      case "ArrowRight" -> "Right";
      case "Escape" -> "Esc";
      case "Delete" -> windows ? "Del" : "Delete";
      case "Insert" -> windows ? "Ins" : "Insert";
      case "PageUp" -> windows ? "PgUp" : "Page Up";
      case "PageDown" -> windows ? "PgDn" : "Page Down";
      case "NumpadAdd" -> "Num +";
      case "NumpadSubtract" -> "Num -";
      case "NumpadMultiply" -> "Num *";
      case "NumpadDivide" -> "Num /";
      case "NumpadDecimal" -> "Num .";
      default -> name.startsWith("Numpad") ? "Num " + name.substring(6) : name;
    }).toString();
  }

  /**
   * A key handler for the root ({@link El#onKey}) that runs the item whose {@linkplain
   * Item#shortcut(int, String) key combination} went down, in these items and their submenus,
   * and then takes the key; a disabled item's combination does nothing and goes on. It sees only
   * keys that no element on the focus path took: a text field keeps Control+C, Control+V and the
   * other keys it edits with. Repeats run the item again, as a held Control+Z undoes step by step.
   *
   * @throws IllegalArgumentException if two items have the same combination; the message names
   *     both
   */
  public static Predicate<Key> shortcuts(List<Item> items) {
    Map<Key, Item> table = new HashMap<>();
    collect(items, table);
    return k -> run(table, k);
  }

  /** Adds the items with key combinations, those of submenus too, and refuses the same twice. */
  static void collect(List<Item> items, Map<Key, Item> into) {
    for (Item item : items) {
      if (item.keys() != null) {
        Item before = into.putIfAbsent(item.keys(), item);
        if (before != null) {
          throw new IllegalArgumentException(shortcutText(item.keys())
              + " is the shortcut of both \"" + before.label() + "\" and \"" + item.label()
              + "\"");
        }
      }
      if (item.submenu() != null) {
        collect(item.submenu(), into);
      }
    }
  }

  /** Runs the enabled item of this key combination; whether there was one. */
  static boolean run(Map<Key, Item> table, Key k) {
    Item item = table.get(new Key(k.name(), k.modifiers(), false));
    if (item == null || item.onChoose() == null) {
      return false;
    }
    item.onChoose().run();
    return true;
  }

  /**
   * What the app keeps of a menu: whether it is open, the point it opened at, and at each level,
   * the menu and the submenus open beside it, the item the pointer or the keys highlight and the
   * item whose submenu is open.
   */
  public static final class State {
    private boolean open;
    private float x = Float.NaN;
    private float y = Float.NaN;
    private int[] lit = {-1, -1, -1, -1}; // per level, from the menu: the highlighted item
    private int[] sub = {-1, -1, -1, -1}; // per level: the item whose submenu is open

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
      Arrays.fill(lit, -1);
      Arrays.fill(sub, -1);
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
      return active(0);
    }

    /** The item whose submenu is open, -1 for none. */
    public int submenu() {
      return submenu(0);
    }

    /**
     * The highlighted item at a level: 0 for the menu, 1 for the submenu open beside it, and so
     * on; -1 for none.
     */
    public int active(int level) {
      return level < lit.length ? lit[level] : -1;
    }

    /** The item whose submenu is open at a level, as {@link #active(int)} counts; -1 for none. */
    public int submenu(int level) {
      return level < sub.length ? sub[level] : -1;
    }

    void highlight(int level, int item) {
      grow(level);
      lit[level] = item;
    }

    /** Opens the submenu of an item at a level, or closes the open one with -1, and deeper ones. */
    void openSubmenu(int level, int item) {
      grow(level + 1);
      if (sub[level] != item) {
        for (int deeper = level + 1; deeper < lit.length; deeper++) {
          lit[deeper] = -1;
          sub[deeper] = -1;
        }
      }
      sub[level] = item;
    }

    private void grow(int level) {
      if (level >= lit.length) {
        int n = Math.max(level + 1, lit.length * 2);
        int from = lit.length;
        lit = Arrays.copyOf(lit, n);
        sub = Arrays.copyOf(sub, n);
        Arrays.fill(lit, from, n, -1);
        Arrays.fill(sub, from, n, -1);
      }
    }
  }

  /**
   * What a menu needs of the bar it belongs to: going to the next or the previous menu, leaving
   * the bar after an item is chosen, and the group of the bar's titles, which a press on them does
   * not dismiss.
   */
  record Bar(IntConsumer sideways, Runnable leave, Object group) {}

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
    return panel(t, state, items, 0, null).onDismiss(state::close);
  }

  /** The menu of a {@link MenuBar}'s title, dismissed with {@link State#close} and the bar's. */
  static El menu(Theme t, State state, List<Item> items, Bar bar) {
    return panel(t, state, items, 0, bar).dismissGroup(bar.group()).onDismiss(() -> {
      state.close();
      bar.leave().run();
    });
  }

  private static El panel(Theme t, State state, List<Item> items, int level, Bar bar) {
    El panel = col().key(level == 0 ? "menu" : "submenu").role("menu").focusable()
        .focusRing(t.border().focus())
        .padding(t.space().s())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().subtle())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .scroll()
        .scrollbars(t.scrollbars())
        .onKey(k -> key(k, state, items, level, bar));
    for (int i = 0; i < items.size(); i++) {
      panel.child(items.get(i).isSeparator()
          ? el().h(t.size().stroke()).shrink(0).margin(t.space().xs(), 0, t.space().xs(), 0)
              .fill(t.border().subtle())
          : item(t, state, items, i, level, bar));
    }
    return panel;
  }

  private static El item(Theme t, State state, List<Item> items, int i, int level, Bar bar) {
    Item item = items.get(i);
    boolean enabled = item.isEnabled();
    boolean lit = i == state.active(level);
    int text = !enabled ? t.text().disabled() : lit ? t.text().onAccent() : t.text().primary();
    int quiet = !enabled ? t.text().disabled() : lit ? t.text().onAccent() : t.text().muted();
    El mark = el().w(t.size().icon()).shrink(0).center();
    if (item.checked()) {
      mark.child(icon(t, CHECKED).color(text));
    } else if (item.icon() != null) {
      mark.child(icon(t, item.icon()).color(text));
    }
    El content = row().wFull().hFull().padding(0, t.space().m()).gap(t.space().m())
        .align(Style.Align.CENTER)
        .children(mark, label(t, item.label()).color(text).grow(1));
    boolean opens = item.opens();
    if (opens) {
      content.child(icon(t, SUBMENU).color(quiet));
    } else if (item.shortcut() != null) {
      content.child(label(t, item.shortcut()).color(quiet).margin(0, 0, 0, t.space().l()));
    }
    // After a rest, the item's submenu opens and any other of its level closes.
    content.onHoverFor(t.motion().submenuDelay(), () -> state.openSubmenu(level, opens ? i : -1));
    int fill = !lit ? 0
        : enabled ? t.accent().color()
        : over(t.surface().raised(), t.surface().hover());
    El el = row().key((level == 0 ? "item-" : "subitem-") + i).role("menuitem")
        .label(item.label())
        .h(t.size().control()).shrink(0).radius(t.radius().small()).fill(fill)
        .onHoverFor(0, () -> state.highlight(level, i))
        .disabled(!enabled)
        .child(content);
    if (item.checked()) {
      el.checked(true);
    }
    if (opens) {
      el.expanded(state.submenu(level) == i);
    }
    if (enabled) {
      el.onPress(() -> choose(state, items, i, level, bar));
    }
    if (opens && state.submenu(level) == i) {
      el.overlay(panel(t, state, item.submenu(), level + 1, bar)
          .side(Side.RIGHT, Style.Align.START, t.space().s() + t.size().stroke())
          .onDismiss(() -> state.openSubmenu(level, -1)));
    }
    return el;
  }

  /** Chooses an item: opens its submenu, or closes the menu and runs its handler. */
  private static void choose(State state, List<Item> items, int i, int level, Bar bar) {
    Item item = items.get(i);
    if (item.submenu() != null) {
      if (item.isEnabled()) {
        state.highlight(level, i);
        state.openSubmenu(level, i);
      }
    } else if (item.onChoose() != null) {
      state.close();
      if (bar != null) {
        bar.leave().run();
      }
      item.onChoose().run();
    }
  }

  /** What a key does in the menu at this level; whether it took it. */
  private static boolean key(Key k, State state, List<Item> items, int level, Bar bar) {
    if ((k.modifiers() & ~Key.SHIFT) != 0) {
      return false;
    }
    int active = state.active(level);
    int next = switch (k.name()) {
      case "ArrowDown" -> step(items, active, 1);
      case "ArrowUp" -> step(items, active < 0 ? items.size() : active, -1);
      case "Home" -> step(items, -1, 1);
      case "End" -> step(items, items.size(), -1);
      default -> -2;
    };
    if (next != -2) {
      state.highlight(level, next);
      return true;
    }
    switch (k.name()) {
      case "Enter", "Space" -> {
        if (active >= 0) {
          choose(state, items, active, level, bar);
          if (state.submenu(level) == active) {
            state.highlight(level + 1, step(items.get(active).submenu(), -1, 1));
          }
        }
        return true;
      }
      case "ArrowRight" -> {
        if (active >= 0 && items.get(active).opens()) {
          state.openSubmenu(level, active);
          state.highlight(level + 1, step(items.get(active).submenu(), -1, 1));
        } else if (bar != null) {
          bar.sideways().accept(1);
        }
        return true;
      }
      case "ArrowLeft", "Escape" -> {
        if (level > 0) {
          state.openSubmenu(level - 1, -1);
        } else if (k.name().equals("Escape")) {
          state.close(); // a bar that has the keys keeps them
        } else if (bar != null) {
          bar.sideways().accept(-1);
        }
        return true;
      }
      case "Tab" -> {
        if (level > 0) {
          return false; // the menu closes
        }
        state.close();
        if (bar != null) {
          bar.leave().run();
        }
        return true;
      }
      default -> {
        String name = k.name();
        if (name.length() == 1 && Character.isLetterOrDigit(name.charAt(0))) {
          int found = startingWith(items, active, name);
          if (found >= 0) {
            state.highlight(level, found);
          }
          return true;
        }
        return false;
      }
    }
  }

  /** The next item from {@code from} in this direction, around at the ends; -1 if there is none. */
  static int step(List<Item> items, int from, int direction) {
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
  static int startingWith(List<Item> items, int from, String letter) {
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
