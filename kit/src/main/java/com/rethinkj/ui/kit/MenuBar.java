package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A row of titles, File, Edit, View and so on, each opening its {@link Menu} below it. The menus
 * are {@link Menu#submenu submenu items} whose text is the title, with an ampersand before the
 * letter that opens the menu with Alt (its mnemonic): {@code "&File"} shows {@code File} and opens
 * with Alt+F; {@code "&&"} is an ampersand. A title without one has no mnemonic.
 *
 * <pre>{@code
 * List<Menu.Item> menus = List.of(
 *     Menu.submenu("&File", List.of(
 *         Menu.item("New query", s::newQuery).icon(Symbol.ADD).shortcut(Key.CONTROL, "N"),
 *         Menu.item("Save", s::save).icon(Symbol.SAVE).shortcut(Key.CONTROL, "S"))),
 *     Menu.submenu("&Edit", List.of(
 *         Menu.item("Undo", s::undo).shortcut(Key.CONTROL, "Z"),
 *         Menu.item("Copy", s::copy).shortcut(Key.CONTROL, "C"))));
 * El root = col().children(
 *         titleBar.child(MenuBar.menuBar(t, s.bar, menus)),
 *         editor)
 *     .onKey(MenuBar.keys(s.bar, menus))       // Alt, F10, Alt+F and Ctrl+N, Ctrl+S, ...
 *     .onKeyUp(MenuBar.keysUp(s.bar, menus));  // Alt alone gives the bar the keys
 * }</pre>
 *
 * <p>The pointer: a press on a title opens its menu and a press on the open menu's title closes
 * it; while a menu is open, a press on another title switches to its menu with one click, and so
 * does moving the pointer onto it. The titles leave the keyboard focus where it is ({@link
 * El#keepFocus}), so that the menu gives it back to the editor when it closes, and they lie in one
 * {@link El#dismissGroup} with the open menu. The space between the titles takes no press: in a
 * title bar marked {@link El#windowPart} it still moves the window.
 *
 * <p>The keys: Alt pressed and released alone or F10 gives the bar the keys ({@link
 * El#holdFocus}), with the first title highlighted; Left and Right move between the titles, Down,
 * Up, Enter and Space open the highlighted menu, and a letter opens the menu with that mnemonic.
 * Alt with a mnemonic's letter opens that menu at once, from anywhere the keys reach the root. In
 * an open menu, Left and Right go to the previous and the next menu, except where Right opens an
 * item's submenu and Left closes a submenu. Escape steps back one level: a submenu, then the menu,
 * then the bar, which gives the focus back to where it was before. Alt or F10 again leaves the
 * bar, and so does a press outside it. The mnemonics are underlined while Alt is held or the bar
 * has the keys.
 *
 * <p>The items' {@linkplain Menu.Item#shortcut(int, String) key combinations} act through {@link
 * #keys}, whether a menu is open or not: they close the bar's menu and run the item, unless it is
 * disabled. They reach the root only when no element on the focus path took the key: a focused
 * text field keeps Control+C, Control+V, Control+Z and the keys that type, a data grid its arrows
 * and Control+C. Two items with one combination, or one that is Alt with a title's mnemonic, are
 * refused with an exception when the view is built. What the system and SDL keep from the app:
 * Alt+F4 closes the window and Alt+Tab, Alt+Esc, the Windows key and its combinations, Ctrl+Esc,
 * Ctrl+Alt+Del and Print Screen belong to Windows; on Linux the desktop takes Super and its
 * combinations, Alt+Tab, Alt+F4 (and on many desktops Alt+F1, Alt+F2, Alt+F7, Alt+F8, Alt+F10 and
 * Alt+Space), Ctrl+Alt+Delete, Ctrl+Alt+F1 to F12 and Print Screen, before the window sees them.
 * F10 alone reaches the app on both.
 *
 * <p>The app keeps a {@link State}: which menu is open, which title has the keys, and the open
 * menu's highlights. In a tree dump the bar is {@code role=menubar} and its titles {@code
 * role=menuitem} with the keys {@code title-<index>}, their text as the label, and {@code
 * expanded} or {@code expanded=false}; the open menu is an overlay of its title.
 */
public final class MenuBar {

  private MenuBar() {}

  /** What the app keeps of a menu bar. */
  public static final class State {
    private final Menu.State menu = new Menu.State();
    private int open = -1; // the title whose menu is open, while menu is open
    private int title = -1; // the title highlighted while the bar has the keys, -1 when it has not
    private boolean alt; // Alt is held: the mnemonics show
    private boolean armed; // Alt went down and no other key since

    /** The title whose menu is open, -1 for none. */
    public int open() {
      return menu.isOpen() ? open : -1;
    }

    /** The highlighted title while the bar has the keys from Alt or F10, else -1. */
    public int title() {
      return title;
    }

    /** The open menu's highlights, as a menu of its own keeps them. */
    public Menu.State menu() {
      return menu;
    }

    /** Closes the open menu and gives up the keys. */
    public void close() {
      menu.close();
      title = -1;
    }

    /** Opens the menu of a title, with its first item highlighted if the keyboard opened it. */
    void open(int index, List<Menu.Item> menus, boolean keyboard) {
      menu.open();
      open = index;
      if (keyboard) {
        title = index;
        menu.highlight(0, Menu.step(menus.get(index).submenu(), -1, 1));
      }
    }

    /** Goes to the next or the previous title that can open, around at the ends. */
    void step(List<Menu.Item> menus, int direction) {
      int from = menu.isOpen() ? open : title;
      for (int i = 1; i <= menus.size(); i++) {
        int at = Math.floorMod(from + direction * i, menus.size());
        if (menus.get(at).isEnabled()) {
          if (menu.isOpen()) {
            open(at, menus, title >= 0);
          } else {
            title = at;
          }
          return;
        }
      }
    }
  }

  /**
   * The bar: a row of the titles of {@code menus}, each a {@link Menu#submenu} whose items are the
   * menu below it. A title with no items is disabled.
   *
   * @throws IllegalArgumentException if two items have one key combination, or one is Alt with a
   *     title's mnemonic
   */
  public static El menuBar(Theme t, State state, List<Menu.Item> menus) {
    table(menus); // refuses conflicts when the view is built
    boolean marks = state.alt || state.title >= 0;
    El bar = row().key("menubar").role("menubar").align(Style.Align.CENTER).keepFocus();
    Menu.Bar owner = new Menu.Bar(d -> state.step(menus, d), () -> state.title = -1, state);
    for (int i = 0; i < menus.size(); i++) {
      bar.child(title(t, state, menus, i, marks, owner));
    }
    if (state.title >= 0) { // the keyboard has the bar
      bar.focusable().holdFocus().onDismiss(state::close).onKey(k -> barKey(k, state, menus));
    }
    return bar;
  }

  private static El title(
      Theme t, State state, List<Menu.Item> menus, int i, boolean marks, Menu.Bar owner) {
    Menu.Item menu = menus.get(i);
    String text = text(menu.label());
    int mnemonic = mnemonic(menu.label());
    boolean enabled = menu.isEnabled();
    boolean open = state.open() == i;
    boolean lit = open || state.title == i && !state.menu.isOpen();
    El label = label(t, text).color(enabled ? t.text().primary() : t.text().disabled());
    if (marks && mnemonic >= 0) {
      label.underline(mnemonic, text.offsetByCodePoints(mnemonic, 1));
    }
    El title = row().key("title-" + i).role("menuitem").label(text)
        .h(t.size().control()).padding(0, t.space().m()).align(Style.Align.CENTER)
        .radius(t.radius().small())
        .fill(lit ? t.surface().pressed() : 0)
        .disabled(!enabled)
        .child(label);
    if (!enabled) {
      return title;
    }
    title.expanded(open)
        .dismissGroup(state)
        .onPress(() -> {
          if (open) {
            state.close();
          } else {
            state.open(i, menus, false);
          }
        })
        // While a menu is open, the pointer on another title switches to its menu at once.
        .onHoverFor(0, () -> {
          if (state.menu.isOpen() && state.open != i) {
            state.open(i, menus, state.title >= 0);
          }
        });
    if (!lit) {
      title.whenHovered(e -> e.fill(t.surface().hover()));
    }
    if (open) {
      title.overlay(Menu.menu(t, state.menu, menu.submenu(), owner)
          .side(Side.BOTTOM, Style.Align.START, t.space().xs()));
    }
    return title;
  }

  /** The keys of the bar itself, while it has them and no menu is open; whether it took one. */
  private static boolean barKey(Key k, State state, List<Menu.Item> menus) {
    if (state.menu.isOpen() || (k.modifiers() & ~Key.SHIFT) != 0) {
      return false;
    }
    switch (k.name()) {
      case "ArrowLeft" -> state.step(menus, -1);
      case "ArrowRight" -> state.step(menus, 1);
      case "Home" -> {
        state.title = -1;
        state.step(menus, 1);
      }
      case "End" -> {
        state.title = 0;
        state.step(menus, -1);
      }
      case "ArrowDown", "Enter", "Space" -> state.open(state.title, menus, true);
      case "ArrowUp" -> {
        state.open(state.title, menus, true);
        List<Menu.Item> items = menus.get(state.title).submenu();
        state.menu.highlight(0, Menu.step(items, items.size(), -1));
      }
      case "Escape", "Tab" -> state.close();
      default -> {
        int found = byMnemonic(menus, k.name());
        if (found < 0) {
          return false;
        }
        state.open(found, menus, true);
      }
    }
    return true;
  }

  /**
   * The key handler for the root ({@link El#onKey}) that gives the bar Alt, F10, Alt with a
   * mnemonic's letter, and the key combinations of the menus' items; combine it with others of
   * the root with {@link Predicate#or}. It sees the keys that no element on the focus path took.
   *
   * @throws IllegalArgumentException if two items have one key combination, or one is Alt with a
   *     title's mnemonic
   */
  public static Predicate<Key> keys(State state, List<Menu.Item> menus) {
    Map<Key, Menu.Item> table = table(menus);
    return k -> {
      if (k.name().equals("Alt")) {
        if (!k.repeat()) {
          state.alt = true;
          state.armed = (k.modifiers() & ~Key.ALT) == 0;
        }
        return true;
      }
      state.armed = false;
      if (Menu.run(table, k)) { // before the mnemonics, as accelerators come first in Windows
        state.close();
        return true;
      }
      if (k.name().equals("F10") && k.modifiers() == 0) {
        toggle(state, menus);
        return true;
      }
      if (k.modifiers() == Key.ALT) {
        int found = byMnemonic(menus, k.name());
        if (found >= 0) {
          state.open(found, menus, true);
          return true;
        }
      }
      return false;
    };
  }

  /**
   * The key handler for the root ({@link El#onKeyUp}) that notices Alt released without another
   * key since it went down, which gives the bar the keys or takes them away again.
   */
  public static Predicate<Key> keysUp(State state, List<Menu.Item> menus) {
    return k -> {
      if (!k.name().equals("Alt")) {
        return false;
      }
      boolean alone = state.armed;
      state.armed = false;
      state.alt = false;
      if (alone) {
        toggle(state, menus);
      }
      return true;
    };
  }

  /** F10, or Alt alone: the bar takes the keys, or gives them up. */
  private static void toggle(State state, List<Menu.Item> menus) {
    if (state.title >= 0 || state.menu.isOpen()) {
      state.close();
    } else {
      state.title = -1;
      state.step(menus, 1);
    }
  }

  /** The text of a title without its ampersands. */
  static String text(String label) {
    return label.replaceAll("&(.)", "$1").replaceAll("&$", "");
  }

  /** Where the mnemonic of a title is in its text, -1 for none. */
  static int mnemonic(String label) {
    int removed = 0;
    for (int i = 0; i < label.length() - 1; i++) {
      if (label.charAt(i) == '&') {
        if (label.charAt(i + 1) != '&') {
          return i - removed;
        }
        removed++;
        i++;
      }
    }
    return -1;
  }

  /** The title whose mnemonic is this key, -1 for none. */
  private static int byMnemonic(List<Menu.Item> menus, String key) {
    for (int i = 0; i < menus.size(); i++) {
      String label = menus.get(i).label();
      int at = mnemonic(label);
      if (at >= 0 && menus.get(i).isEnabled()
          && text(label).substring(at, text(label).offsetByCodePoints(at, 1))
              .toUpperCase(Locale.ROOT).equals(key)) {
        return i;
      }
    }
    return -1;
  }

  /** The items' key combinations; refuses two of one, and one that is Alt with a mnemonic. */
  private static Map<Key, Menu.Item> table(List<Menu.Item> menus) {
    Map<Key, Menu.Item> table = new HashMap<>();
    for (Menu.Item menu : menus) {
      if (menu.submenu() != null) {
        Menu.collect(menu.submenu(), table);
      }
    }
    for (Menu.Item menu : menus) {
      int at = mnemonic(menu.label());
      if (at < 0) {
        continue;
      }
      String text = text(menu.label());
      Key alt = new Key(text.substring(at, text.offsetByCodePoints(at, 1))
          .toUpperCase(Locale.ROOT), Key.ALT, false);
      Menu.Item taken = table.get(alt);
      if (taken != null) {
        throw new IllegalArgumentException(Menu.shortcutText(alt) + " is the shortcut of \""
            + taken.label() + "\" and opens the menu \"" + text + "\"");
      }
    }
    return table;
  }
}
