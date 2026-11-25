package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A narrow vertical strip of icon-only buttons along the left or right edge of a window, as IDEs
 * use for tool windows: a top group, a bottom group pinned to the bottom, and optional separators
 * between groups. Pressing a closed item calls {@code onSelect} with its index; pressing the open
 * item closes it by calling {@code onSelect} with {@code -1}. Indices count the {@link Item}s of
 * {@code top} followed by those of {@code bottom}; {@link Separator}s are not counted.
 *
 * <pre>{@code
 * Rail.rail(
 *     state.rail,
 *     "Tool windows",
 *     Side.LEFT,
 *     List.of(
 *         Rail.item(Symbol.FOLDER, "Explorer").shortcut(Key.CONTROL, "1"),
 *         Rail.item(Symbol.SEARCH, "Search").shortcut(Key.CONTROL, "2"),
 *         Rail.separator(),
 *         Rail.item(Symbol.WARNING, "Problems").dot(Badge.Kind.DANGER, "3 errors")),
 *     List.of(Rail.item(Symbol.SETTINGS, "Settings")),
 *     state.panel,
 *     state::selectPanel)
 * }</pre>
 *
 * <p>The band sits on the window surface ({@link Theme.Surfaces#window}) with a hairline ({@link
 * Theme.Borders#divider}) along its inner edge only, as wide as a large control ({@link
 * Theme.Sizes#controlLarge}) plus padding. Items are square ghost icon buttons ({@link
 * Button#toggleIcon}); the open item shows the tinted tile and accent-edge icon of a pressed-in
 * toggle button. An optional {@link Item#dot} draws a small circle in its {@link Badge.Kind} color,
 * ringed in the window's color, at the button's top right, and places its text in the tooltip and as
 * the button's {@code value}. Tooltips open toward the window's inside (right of a {@link
 * Side#LEFT} rail, left of a {@link Side#RIGHT} rail), with any shortcut on keycaps. When the
 * height runs out, the rail scrolls, with the wheel and as the keys move, without a scrollbar,
 * which would lie over the buttons.
 *
 * <p>The keys follow WAI-ARIA's vertical toolbar: the rail is one stop for Tab, at the item it last
 * had (the first at first); Arrow Up and Arrow Down move to the previous and next item across
 * separators and groups, wrapping around the ends; Home and End jump to the first and last item;
 * and Enter and Space press the focused item. A press with the pointer leaves keyboard focus where
 * it was, as on any button. In a tree dump the rail is {@code role=toolbar} with its label,
 * separators are {@code role=separator}, and items are {@code role=button} with {@code checked} or
 * {@code checked=false}, their label, and any dot's text as {@code value}.
 */
public final class Rail {

  private Rail() {}

  /** One entry of a rail: an {@link Item}, or a {@link Separator} between groups. */
  public sealed interface Entry permits Item, Separator {}

  /** A horizontal hairline between groups of items; made by {@link #separator()}. */
  public record Separator() implements Entry {}

  /**
   * A small colored mark at an item's top right.
   *
   * @param kind picks the circle's color from {@link Theme.Badges}
   * @param text describes what the dot means in the item's tooltip and tree dump
   */
  public record Dot(Badge.Kind kind, String text) {
    public Dot {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * An item of a rail: an icon button that opens or closes its panel.
   *
   * @param icon the item's icon
   * @param label what the item opens, shown in its tooltip and in a tree dump
   * @param shortcut shown as keycaps in the tooltip; null for none
   * @param dot a small mark at the button's top right; null for none
   */
  public record Item(Icon icon, String label, Key shortcut, Dot dot) implements Entry {

    public Item {
      Objects.requireNonNull(icon, "icon");
      Objects.requireNonNull(label, "label");
    }

    /**
     * This item with a key combination shown on keycaps in its tooltip ({@link Menu#shortcutText}).
     * It only shows: the app binds the key where it binds its other keys.
     *
     * @param modifiers a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT}
     *     and {@link Key#META}
     * @param key the key's name, as {@link Key} lists them: {@code 1}, {@code F}, {@code B}
     */
    public Item shortcut(int modifiers, String key) {
      return new Item(icon, label, Menu.Item.combination(modifiers, key), dot);
    }

    /**
     * This item with a small colored circle at its top right; {@code text} shows in its tooltip and
     * as its {@code value} in a tree dump.
     */
    public Item dot(Badge.Kind kind, String text) {
      return new Item(icon, label, shortcut, new Dot(kind, text));
    }
  }

  /**
   * What a rail keeps between builds, which the app holds in its state, one per rail: the item that
   * Tab reaches.
   */
  public static final class State {
    final Roving keys = new Roving(true);
  }

  private static final Separator SEPARATOR = new Separator();

  /** An item of the rail with an icon and its label. */
  public static Item item(Icon icon, String label) {
    return new Item(icon, label, null, null);
  }

  /** A horizontal hairline between groups of items. */
  public static Separator separator() {
    return SEPARATOR;
  }

  /**
   * Builds a vertical icon rail along the left or right edge of a window.
   *
   * @param state kept between builds by the app
   * @param label what the rail is for, its label in a tree dump
   * @param side {@link Side#LEFT} or {@link Side#RIGHT}; determines which inner edge gets the
   *     hairline and which way tooltips open
   * @param top entries at the top of the rail, items and optional separators
   * @param bottom entries pinned to the bottom of the rail, items and optional separators
   * @param open the index of the open item (counting {@link Item}s of {@code top} then {@code
   *     bottom}, separators not counted), or {@code -1} when none is open
   * @param onSelect told the index of a closed item when pressed, or {@code -1} when the open item
   *     is pressed; null disables the items
   * @throws IllegalArgumentException if {@code side} is neither {@link Side#LEFT} nor {@link
   *     Side#RIGHT}
   */
  public static El rail(
      State state,
      String label,
      Side side,
      List<? extends Entry> top,
      List<? extends Entry> bottom,
      int open,
      IntConsumer onSelect) {
    Objects.requireNonNull(state, "state");
    if (side != Side.LEFT && side != Side.RIGHT) {
      throw new IllegalArgumentException("a rail goes on the left or right, not " + side);
    }
    Objects.requireNonNull(top, "top");
    Objects.requireNonNull(bottom, "bottom");

    Theme theme = Theme.CURRENT.get();
    float pad = theme.space().s();
    float stroke = theme.size().stroke();
    Side tipSide = side == Side.LEFT ? Side.RIGHT : Side.LEFT;

    El rail = col()
        .role("toolbar")
        .label(label)
        .w(theme.size().controlLarge() + 2 * pad + stroke)
        .shrink(0)
        .align(Style.Align.CENTER)
        .padding(pad)
        .gap(pad)
        .alignSelf(Style.Align.STRETCH) // the window's height, also in a row that aligns at the top
        .fill(theme.surface().window())
        .scroll(); // no scrollbar: it would lie over the buttons; the wheel and the keys scroll
    rail.style().minHeight = Len.ZERO;
    if (side == Side.LEFT) {
      rail.border(0, stroke, 0, 0, theme.border().divider());
    } else {
      rail.border(0, 0, 0, stroke, theme.border().divider());
    }

    List<El> stops = new ArrayList<>();
    int index = addEntries(theme, rail, stops, top, 0, open, tipSide, onSelect);
    if (!bottom.isEmpty()) {
      rail.child(el().basis(0).grow(1));
      addEntries(theme, rail, stops, bottom, index, open, tipSide, onSelect);
    }
    state.keys.apply(rail, stops);
    return rail;
  }

  private static int addEntries(
      Theme theme,
      El rail,
      List<El> stops,
      List<? extends Entry> entries,
      int index,
      int open,
      Side tipSide,
      IntConsumer onSelect) {
    for (Entry entry : entries) {
      switch (entry) {
        case Separator _ -> rail.child(separator(theme));
        case Item item -> {
          int itemIndex = index++;
          boolean isOpen = itemIndex == open;
          Button.Options options =
              Button.options().size(Button.Size.LARGE).shortcut(item.shortcut()).tipSide(tipSide);
          if (item.dot() != null && !item.dot().text().isEmpty()) {
            options.tipDescription(item.dot().text());
          }
          Consumer<Boolean> onToggle =
              onSelect == null ? null : _ -> onSelect.accept(isOpen ? -1 : itemIndex);
          // keyed by place, so that a separator that comes or goes leaves the focus where it is
          El button = Button.toggleIcon(item.icon(), item.label(), isOpen, onToggle, options)
              .key("item " + itemIndex);
          if (item.dot() != null) {
            if (!item.dot().text().isEmpty()) {
              button.value(item.dot().text());
            }
            button.child(dot(theme, item.dot()));
          }
          if (button.isFocusable()) {
            stops.add(button);
          }
          rail.child(button);
        }
      }
    }
    return index;
  }

  private static El separator(Theme theme) {
    return Divider.divider()
        .w(theme.size().controlLarge() - 2 * theme.space().s())
        .margin(theme.space().xs(), 0, theme.space().xs(), 0);
  }

  private static El dot(Theme theme, Dot dot) {
    float size = theme.size().dot();
    float ring = theme.size().ringGap();
    float outer = size + 2 * ring;
    El mark = el().size(outer, outer)
        .radius(outer / 2)
        .fill(Badge.tone(theme, dot.kind()).strong())
        .border(ring, theme.surface().window())
        .inert();
    Style at = mark.style();
    at.position = Style.Position.ABSOLUTE;
    at.top = Len.px(theme.space().s());
    at.right = Len.px(theme.space().s());
    return mark;
  }
}
