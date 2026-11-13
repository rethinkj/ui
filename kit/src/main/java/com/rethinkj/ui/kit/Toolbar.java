package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A row of icon buttons, buttons, toggle buttons, split buttons, separators and a space that
 * grows, as above a grid or an editor. What does not fit moves, from the end, into a menu behind
 * a More button ({@link Symbol#MORE_HORIZONTAL}), as the same actions: a toggle as a checked item,
 * a split button as a submenu. The toolbar learns its width and the width of each item from the
 * layout ({@link El#onResize}) and keeps them in its {@link State}, so it settles a frame after
 * its width changes.
 *
 * <pre>{@code
 * Toolbar.toolbar(t, s.toolbar, "Query", List.of(
 *     Toolbar.button(Symbol.ARROW_CLOCKWISE, "Refresh", s::refresh),
 *     Toolbar.split(Button.Variant.PRIMARY, Symbol.PLAY, "Run", s::run, s.runMenu, runItems),
 *     Toolbar.separator(),
 *     Toolbar.toggle(Symbol.PIN, "Keep results", s.keep, on -> s.keep = on),
 *     Toolbar.spacer(),
 *     Toolbar.text(Symbol.ARROW_EXPORT_LTR, "Export", s::export)))
 * }</pre>
 *
 * <p>The keys follow WAI-ARIA's toolbar: the toolbar is one stop for Tab, at the item it last had
 * (the first at first); Arrow Left and Right go to the previous and the next item, around the
 * ends, Home and End to the first and the last, and Enter and Space press the item. A press with
 * the pointer leaves the focus where it was, as on any button. In a tree dump it is {@code
 * role=toolbar} with its label; its items are buttons with their labels, a toggle {@code checked}
 * or {@code checked=false}, the More button {@code label="More"}.
 */
public final class Toolbar {

  private Toolbar() {}

  private enum Kind { BUTTON, TEXT, TOGGLE, SPLIT, SEPARATOR, SPACER }

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

    private Item(Kind kind, String label, Symbol icon, Button.Variant variant, boolean on,
        Runnable onPress, Consumer<Boolean> onToggle, Menu.State menu, List<Menu.Item> items) {
      this.kind = kind;
      this.label = label;
      this.icon = icon;
      this.variant = variant;
      this.on = on;
      this.onPress = onPress;
      this.onToggle = onToggle;
      this.menu = menu;
      this.items = items;
    }
  }

  /**
   * What a toolbar keeps between builds, which the app holds in its state, one per toolbar: the
   * widths layout gave it and its items, the menu of what does not fit and the item that Tab
   * reaches.
   */
  public static final class State {
    final Menu.State more = new Menu.State();
    float width = Float.NaN;
    final Map<String, Float> widths = new HashMap<>();
    final Roving keys = new Roving();

    /** The menu of the items that do not fit. */
    public Menu.State more() {
      return more;
    }
  }

  /** A ghost button with an icon alone; its label shows in a tooltip. */
  public static Item button(Symbol icon, String label, Runnable onPress) {
    return new Item(Kind.BUTTON, label, Objects.requireNonNull(icon, "icon"),
        Button.Variant.GHOST, false, onPress, null, null, null);
  }

  /** A ghost button with an icon, if any, and text. */
  public static Item text(Symbol icon, String text, Runnable onPress) {
    return text(Button.Variant.GHOST, icon, text, onPress);
  }

  /** A button of a variant with an icon, if any, and text. */
  public static Item text(Button.Variant variant, Symbol icon, String text, Runnable onPress) {
    return new Item(Kind.TEXT, text, icon, variant, false, onPress, null, null, null);
  }

  /** A toggle button with an icon alone ({@link Button#toggleIcon}). */
  public static Item toggle(Symbol icon, String label, boolean on, Consumer<Boolean> onToggle) {
    return new Item(Kind.TOGGLE, label, Objects.requireNonNull(icon, "icon"),
        Button.Variant.GHOST, on, null, onToggle, null, null);
  }

  /** A split button ({@link Button#split}): the main action and a menu of related ones. */
  public static Item split(Button.Variant variant, Symbol icon, String text, Runnable onPress,
      Menu.State menu, List<Menu.Item> items) {
    return new Item(Kind.SPLIT, text, icon, variant, false, onPress, null,
        Objects.requireNonNull(menu, "menu"), List.copyOf(items));
  }

  /** A hairline between groups of items. */
  public static Item separator() {
    return new Item(Kind.SEPARATOR, null, null, null, false, null, null, null, null);
  }

  /** A space that takes the room left over, which pushes the items after it to the end. */
  public static Item spacer() {
    return new Item(Kind.SPACER, null, null, null, false, null, null, null, null);
  }

  /**
   * @param label what the toolbar is for, its label in a tree dump
   */
  public static El toolbar(Theme t, State state, String label, List<Item> items) {
    float gap = t.space().xs();
    int shown = fit(t, state, items, gap);
    El bar = row().role("toolbar").label(label).h(t.size().control()).grow(1)
        .minSize(0, t.size().control()).gap(gap).align(Style.Align.CENTER).clip()
        .onResize((w, h) -> state.width = w);
    List<El> stops = new ArrayList<>();
    for (int i = 0; i < shown; i++) {
      Item item = items.get(i);
      if (item.kind == Kind.SEPARATOR && (i == shown - 1 || i == 0)) {
        continue; // none at an end
      }
      El el = build(t, item, stops);
      String id = id(items, i);
      bar.child(item.kind == Kind.SEPARATOR || item.kind == Kind.SPACER ? el
          : el.onResize((w, h) -> state.widths.put(id, w)));
    }
    if (shown < items.size()) {
      List<Menu.Item> hidden = new ArrayList<>();
      for (int i = shown; i < items.size(); i++) {
        Menu.Item entry = entry(items.get(i));
        boolean separator = entry != null && entry.isSeparator();
        if (entry != null && !(separator && (hidden.isEmpty() || hidden.getLast().isSeparator()))) {
          hidden.add(entry);
        }
      }
      if (!hidden.isEmpty() && hidden.getLast().isSeparator()) {
        hidden.removeLast();
      }
      El more = Button.icon(t, Button.Variant.GHOST, Symbol.MORE_HORIZONTAL, "More",
          state.more::toggle).key("more").onResize((w, h) -> state.widths.put("more", w));
      stops.add(more);
      bar.child(Menu.dropdown(t, more, state.more, hidden));
    }
    state.keys.apply(bar, stops);
    return bar;
  }

  /** The key of an item's width in the state: its label, or its kind and place. */
  private static String id(List<Item> items, int i) {
    Item item = items.get(i);
    return item.label != null ? item.label : item.kind + "-" + i;
  }

  /**
   * How many items, from the start, fit the toolbar's width as the last layout gave it, with the
   * More button after them if they are not all; all of them until every width is known.
   */
  private static int fit(Theme t, State state, List<Item> items, float gap) {
    if (Float.isNaN(state.width)) {
      return items.size();
    }
    float[] widths = new float[items.size()];
    float total = 0;
    for (int i = 0; i < items.size(); i++) {
      Kind kind = items.get(i).kind;
      if (kind == Kind.SPACER) {
        continue; // takes what is left, nothing when nothing is
      }
      Float w = kind == Kind.SEPARATOR
          ? Float.valueOf(separator(t)) : state.widths.get(id(items, i));
      if (w == null) {
        return items.size();
      }
      widths[i] = w + (i == 0 ? 0 : gap);
      total += widths[i];
    }
    if (total <= state.width) {
      return items.size();
    }
    float room = state.width - state.widths.getOrDefault("more", t.size().control()) - gap;
    int shown = items.size();
    while (shown > 0 && total > room) {
      shown--;
      total -= widths[shown];
    }
    return shown;
  }

  /** Builds an item; the elements Tab and the arrows reach go into {@code stops}. */
  private static El build(Theme t, Item item, List<El> stops) {
    return switch (item.kind) {
      case BUTTON -> stop(stops, Button.icon(t, item.variant, item.icon, item.label,
          item.onPress), item.onPress != null);
      case TEXT -> stop(stops, Button.button(t, item.variant, item.icon, item.label,
          item.onPress), item.onPress != null);
      case TOGGLE -> stop(stops, Button.toggleIcon(t, item.icon, item.label, item.on,
          item.onToggle), item.onToggle != null);
      case SPLIT -> {
        El split = Button.split(t, item.variant, item.icon, item.label, item.onPress,
            item.menu, item.items);
        stop(stops, split.children().getFirst(), item.onPress != null);
        El more = split.children().getLast();
        stop(stops, more, more.onPress() != null);
        yield split;
      }
      case SEPARATOR -> el().w(t.size().stroke()).h(t.size().control() - 2 * t.space().s())
          .shrink(0).margin(0, t.space().xs(), 0, t.space().xs()).fill(t.border().subtle())
          .role("separator");
      case SPACER -> el().grow(1);
    };
  }

  /** The width a separator takes, its margins included. */
  private static float separator(Theme t) {
    return t.size().stroke() + 2 * t.space().xs();
  }

  private static El stop(List<El> stops, El el, boolean enabled) {
    if (enabled) {
      stops.add(el);
    }
    return el;
  }

  /** The menu entry of an item that does not fit, or null for a spacer. */
  private static Menu.Item entry(Item item) {
    return switch (item.kind) {
      case BUTTON, TEXT -> {
        Menu.Item entry = Menu.item(item.label, item.onPress);
        yield item.icon == null ? entry : entry.icon(item.icon);
      }
      case TOGGLE -> Menu.item(item.label, item.onToggle == null ? null
          : () -> item.onToggle.accept(!item.on)).checked(item.on);
      case SPLIT -> {
        List<Menu.Item> all = new ArrayList<>();
        all.add(item.icon == null ? Menu.item(item.label, item.onPress)
            : Menu.item(item.label, item.onPress).icon(item.icon));
        all.add(Menu.separator());
        all.addAll(item.items);
        yield Menu.submenu(item.label, all);
      }
      case SEPARATOR -> Menu.separator();
      case SPACER -> null;
    };
  }
}
