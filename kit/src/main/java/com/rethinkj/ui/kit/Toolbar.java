package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A row of icon buttons, buttons, toggle buttons, split buttons, groups and a space that grows, as
 * above a grid or an editor. A group gathers tools that belong together on a tinted, rounded
 * surface of their own. What does not fit moves, from the end, into a menu behind a More button
 * ({@link Symbol#MORE_HORIZONTAL}), as the same actions: a toggle as a checked item, a split button
 * as a submenu, a group between lines. The toolbar learns its width and the width of each item
 * from the layout ({@link El#onResize}) and keeps them in its {@link State}, so it settles a frame
 * after its width changes.
 *
 * <pre>{@code
 * Toolbar.toolbar(state.toolbar, "Query", List.of(
 *     Toolbar.split(
 *         Button.Variant.PRIMARY, Symbol.PLAY, "Run", state::run, state.runMenu, runItems),
 *     Toolbar.group(
 *         Toolbar.toggle(Symbol.PIN, "Keep results", state.keep, state::keep),
 *         Toolbar.button(Symbol.ARROW_CLOCKWISE, "Refresh", state::refresh)),
 *     Toolbar.spacer(),
 *     Toolbar.text(Symbol.ARROW_EXPORT_LTR, "Export", state::export)))
 * }</pre>
 *
 * <p>The keys follow WAI-ARIA's toolbar: the toolbar is one stop for Tab, at the item it last had
 * (the first at first); Arrow Left and Right go to the previous and the next item, around the
 * ends, also through groups, Home and End to the first and the last, and Enter and Space press the
 * item. A press with the pointer leaves the focus where it was, as on any button. In a tree dump it
 * is {@code role=toolbar} with its label; its items are buttons with their labels, a toggle {@code
 * checked} or {@code checked=false}, a group {@code role=group} around its items, the More button
 * {@code label="More"}.
 */
public final class Toolbar {

  private Toolbar() {}

  private enum Kind {
    BUTTON,
    TEXT,
    TOGGLE,
    SPLIT,
    GROUP,
    SPACER
  }

  /** One part of a toolbar; made by the static methods of {@link Toolbar}. */
  public static final class Item {
    final Kind kind;
    final String label;
    final Symbol icon;
    final Button.Variant variant;
    final boolean on;
    final Runnable onPress;
    final Consumer<Boolean> onToggle;
    final Menu.State menu;
    final List<Menu.Item> items;
    final List<Item> members;
    final Button.Options options;

    private Item(
        Kind kind,
        String label,
        Symbol icon,
        Button.Variant variant,
        boolean on,
        Runnable onPress,
        Consumer<Boolean> onToggle,
        Menu.State menu,
        List<Menu.Item> items,
        List<Item> members,
        Button.Options options) {
      this.kind = kind;
      this.label = label;
      this.icon = icon;
      this.variant = variant;
      this.on = on;
      this.onPress = onPress;
      this.onToggle = onToggle;
      this.menu = menu;
      this.items = items;
      this.members = members;
      this.options = options;
    }

    /**
     * This button or split button with a button's options: its state, such as {@link
     * Button.State#BUSY}, its tone and shortcut. Its icon is the item's and its size the
     * toolbar's, whatever the options say. In the More menu, an item that is busy or off cannot be
     * chosen, a split button that is off has no submenu, and the tone and shortcut show as a menu
     * shows them.
     *
     * @throws IllegalArgumentException for a toggle, a group or a spacer
     */
    public Item options(Button.Options options) {
      if (kind != Kind.BUTTON && kind != Kind.TEXT && kind != Kind.SPLIT) {
        throw new IllegalArgumentException(
            "a " + kind.name().toLowerCase(Locale.ROOT) + " takes no button options");
      }
      return new Item(
          kind, label, icon, variant, on, onPress, onToggle, menu, items, members, options.copy());
    }

    /** The item's options, or plain ones, with this icon and the toolbar's size. */
    private Button.Options with(Symbol icon) {
      return (options == null ? Button.options() : options.copy())
          .icon(icon)
          .size(Button.Size.REGULAR);
    }
  }

  /**
   * What a toolbar keeps between builds, which the app holds in its state, one per toolbar: the
   * widths layout gave it and its items, the menu of what does not fit and the item that Tab
   * reaches.
   */
  public static final class State {
    final Menu.State more = new Menu.State();
    final Fitting fitting = new Fitting();
    final Roving keys = new Roving();

    /** The menu of the items that do not fit. */
    public Menu.State more() {
      return more;
    }
  }

  /** A ghost button with an icon alone; its label shows in a tooltip. */
  public static Item button(Symbol icon, String label, Runnable onPress) {
    return new Item(
        Kind.BUTTON,
        label,
        Objects.requireNonNull(icon, "icon"),
        Button.Variant.GHOST,
        false,
        onPress,
        null,
        null,
        null,
        null,
        null);
  }

  /** A ghost button with an icon, if any, and text. */
  public static Item text(Symbol icon, String text, Runnable onPress) {
    return text(Button.Variant.GHOST, icon, text, onPress);
  }

  /** A button of a variant with an icon, if any, and text. */
  public static Item text(Button.Variant variant, Symbol icon, String text, Runnable onPress) {
    return new Item(Kind.TEXT, text, icon, variant, false, onPress, null, null, null, null, null);
  }

  /** A toggle button with an icon alone ({@link Button#toggleIcon}). */
  public static Item toggle(Symbol icon, String label, boolean on, Consumer<Boolean> onToggle) {
    return new Item(
        Kind.TOGGLE,
        label,
        Objects.requireNonNull(icon, "icon"),
        Button.Variant.GHOST,
        on,
        null,
        onToggle,
        null,
        null,
        null,
        null);
  }

  /** A split button ({@link Button#split}): the main action and a menu of related ones. */
  public static Item split(
      Button.Variant variant,
      Symbol icon,
      String text,
      Runnable onPress,
      Menu.State menu,
      List<Menu.Item> items) {
    return new Item(
        Kind.SPLIT,
        text,
        icon,
        variant,
        false,
        onPress,
        null,
        Objects.requireNonNull(menu, "menu"),
        List.copyOf(items),
        null,
        null);
  }

  /**
   * Tools that belong together, such as Wrap and Format, on a tinted, rounded surface of their
   * own, a little apart from the items beside it; the tools stand lower by the surface's padding.
   * In the More menu, lines set them apart from the other items.
   *
   * @param members icon buttons, buttons and toggles: no split button, spacer or group
   * @throws IllegalArgumentException for no members or one that a group cannot hold
   */
  public static Item group(Item... members) {
    if (members.length == 0) {
      throw new IllegalArgumentException("a group holds at least one item");
    }
    for (Item member : members) {
      if (member.kind != Kind.BUTTON && member.kind != Kind.TEXT && member.kind != Kind.TOGGLE) {
        throw new IllegalArgumentException("a group holds buttons and toggles, not a "
            + member.kind.name().toLowerCase(Locale.ROOT));
      }
    }
    return new Item(
        Kind.GROUP, null, null, null, false, null, null, null, null, List.of(members), null);
  }

  /** A space that takes the room left over, which pushes the items after it to the end. */
  public static Item spacer() {
    return new Item(Kind.SPACER, null, null, null, false, null, null, null, null, null, null);
  }

  /**
   * @param label what the toolbar is for, its label in a tree dump
   */
  public static El toolbar(State state, String label, List<Item> items) {
    Theme theme = Theme.CURRENT.get();
    // The items with each group's members in its place, and the place of each one's group in
    // items, or -1: the members fit one by one, the first carrying its group's padding and margins.
    List<Item> flat = new ArrayList<>();
    List<Integer> groups = new ArrayList<>();
    for (int g = 0; g < items.size(); g++) {
      Item item = items.get(g);
      for (Item member : item.kind == Kind.GROUP ? item.members : List.of(item)) {
        flat.add(member);
        groups.add(item.kind == Kind.GROUP ? g : -1);
      }
    }
    int shown = state.fitting.fit(
        theme,
        flat.size(),
        i -> flat.get(i).kind == Kind.SPACER
            ? null // takes what is left, nothing when nothing is
            : state.fitting.width(id(flat, i))
                + (opens(groups, i) ? 2 * theme.space().xs() + margins(theme, i) : 0));
    El bar = state.fitting.row(theme).role("toolbar").label(label);
    List<El> stops = new ArrayList<>();
    El group = null;
    for (int i = 0; i < shown; i++) {
      Item item = flat.get(i);
      El el = build(item, stops);
      if (item.kind == Kind.SPACER) {
        bar.child(el);
        continue;
      }
      el.onResize(state.fitting.measure(id(flat, i)));
      if (groups.get(i) < 0) {
        bar.child(el);
        continue;
      }
      if (opens(groups, i)) {
        group = group(theme, i);
        bar.child(group);
      }
      group.child(el.h(theme.size().control() - 2 * theme.space().xs())
          .radius(theme.radius().small()));
    }
    if (shown < flat.size()) {
      List<Menu.Item> hidden = new ArrayList<>();
      Integer previous = null; // the group of the last entry
      for (int i = shown; i < flat.size(); i++) {
        Menu.Item entry = entry(flat.get(i));
        if (entry == null) {
          continue;
        }
        int in = groups.get(i);
        if (previous != null && in != previous && (in >= 0 || previous >= 0)) {
          hidden.add(Menu.separator());
        }
        hidden.add(entry);
        previous = in;
      }
      El more = Button.icon(
              Button.Variant.GHOST, Symbol.MORE_HORIZONTAL, "More", state.more::toggle)
          .key("more")
          .onResize(state.fitting.measure("more"));
      stops.add(more);
      bar.child(Menu.dropdown(more, state.more, hidden));
    }
    state.keys.apply(bar, stops);
    return bar;
  }

  /** Whether the item is the first of its group. */
  private static boolean opens(List<Integer> groups, int i) {
    return groups.get(i) >= 0 && (i == 0 || !groups.get(i - 1).equals(groups.get(i)));
  }

  /** A group's margins: apart from the items before and after it, none at the start. */
  private static float margins(Theme theme, int first) {
    return (first == 0 ? 0 : theme.space().s()) + theme.space().s();
  }

  /** The surface of a group whose first item has this place, which its items go into. */
  private static El group(Theme theme, int first) {
    return El.row()
        .role("group")
        .h(theme.size().control())
        .shrink(0)
        .margin(0, theme.space().s(), 0, first == 0 ? 0 : theme.space().s())
        .padding(theme.space().xs())
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .radius(theme.radius().medium())
        .fill(theme.surface().hover());
  }

  /** The key of an item's width in the state: its label, or its kind and place. */
  private static String id(List<Item> items, int i) {
    Item item = items.get(i);
    return item.label != null ? item.label : item.kind + "-" + i;
  }

  /** Builds an item; the elements Tab and the arrows reach go into {@code stops}. */
  private static El build(Item item, List<El> stops) {
    return switch (item.kind) {
      case BUTTON ->
        stop(
            stops, Button.icon(item.variant, item.icon, item.label, item.onPress, item.with(null)));
      case TEXT ->
        stop(stops, Button.button(item.variant, item.label, item.onPress, item.with(item.icon)));
      case TOGGLE -> stop(stops, Button.toggleIcon(item.icon, item.label, item.on, item.onToggle));
      case SPLIT -> {
        El split = Button.split(
            item.variant, item.label, item.onPress, item.menu, item.items, item.with(item.icon));
        stop(stops, split.children().getFirst());
        stop(stops, split.children().getLast());
        yield split;
      }
      case SPACER -> el().grow(1);
      case GROUP -> throw new IllegalStateException("a group's members are built in its place");
    };
  }

  /** Adds an element to the stops of the keys if it takes the focus: not one that is off. */
  private static El stop(List<El> stops, El el) {
    if (el.isFocusable()) {
      stops.add(el);
    }
    return el;
  }

  /** The menu entry of an item that does not fit, or null for a spacer. */
  private static Menu.Item entry(Item item) {
    return switch (item.kind) {
      case BUTTON, TEXT -> Button.entry(item.label, item.icon, item.onPress, item.with(item.icon));
      case TOGGLE ->
        Menu.item(item.label, item.onToggle == null ? null : () -> item.onToggle.accept(!item.on))
            .checked(item.on);
      case SPLIT -> {
        item.menu.close(); // off the bar, its menu would else open again when it comes back
        if (item.with(item.icon).off()) { // as the split button, its entry opens no menu
          yield Button.entry(item.label, item.icon, null, item.with(item.icon));
        }
        List<Menu.Item> all = new ArrayList<>();
        all.add(Button.entry(item.label, item.icon, item.onPress, item.with(item.icon)));
        all.add(Menu.separator());
        all.addAll(item.items);
        yield Menu.submenu(item.label, all);
      }
      case SPACER, GROUP -> null;
    };
  }
}
