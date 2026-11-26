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
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * A narrow vertical strip of icon-only buttons along the left or right edge of a window, as IDEs
 * use for tool windows: a top group, an optional bottom group pinned to the bottom, and optional
 * separators between groups. Pressing a closed item calls {@code onSelect} with its index; by
 * default, pressing the open item closes it by calling {@code onSelect} with {@code -1} (unless
 * {@link Options#closable} is {@code false}). Indices count only the {@link Item}s of {@code top}
 * followed by those of {@code bottom}; {@link Separator}s and {@link Action}s are not counted, and
 * each {@link Action} runs its own handler independently of the panel selection.
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
 *     List.of(Rail.action(Symbol.SETTINGS, "Settings", state::settings)),
 *     state.panel,
 *     state::selectPanel)
 * }</pre>
 *
 * <p>The band sits on the window surface ({@link Theme.Surfaces#window}) with a hairline ({@link
 * Theme.Borders#divider}) along its inner edge only, as wide as a large control ({@link
 * Theme.Sizes#controlLarge}) plus padding and the hairline ({@link #width()}). Items are square
 * ghost icon buttons ({@link Button#toggleIcon}). The open item shows the tinted tile and
 * accent-edge icon of a pressed-in toggle button. An optional {@link Dot} draws a small circle
 * at a button's top right and places its nonempty text in the tooltip and as the button's
 * {@code value}. Tooltips open toward the window's inside (right of a {@link Side#LEFT} rail,
 * left of a {@link Side#RIGHT} rail), with any shortcut on keycaps. When the height runs out, the
 * rail scrolls with the wheel and as the keys move, without a scrollbar.
 *
 * <p>The keys follow WAI-ARIA's vertical toolbar: the rail is one Tab stop, at the button the
 * keyboard last reached (initially the first); Arrow Up and Arrow Down wrap across the enabled
 * buttons across groups; Home and End jump to the first and last; and Enter and Space press the
 * focused button. A pointer press leaves keyboard focus where it was, as on any button. In a tree
 * dump the rail is {@code role=toolbar} (marked {@code disabled} when it has no enabled buttons),
 * separators are {@code role=separator}, and items and actions are {@code role=button} with keys
 * {@code #item-<index>} / {@code #action-<index>} (or custom keys) and {@code checked} /
 * {@code checked=false} on items only.
 */
public final class Rail {

  private Rail() {}

  /** One entry of a rail: an {@link Item}, an {@link Action}, or a {@link Separator}. */
  public sealed interface Entry permits Item, Action, Separator {}

  /** A horizontal hairline between groups of items; made by {@link #separator()}. */
  public record Separator() implements Entry {}

  /**
   * A small colored mark at the top right of an item or action, dimmed when it is disabled.
   *
   * @param kind picks the circle's color from {@link Theme.Badges}
   * @param text describes the dot in the tooltip and tree dump, or empty for a visual mark only
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
   * @param description secondary tooltip text; null for none
   * @param key a unique element key and Tab stop ID; null for {@code item-<index>}
   * @param disabled whether the item is disabled even if the rail has a selection handler
   * @param busy whether to show a spinner or progress ring and ignore presses while enabled
   * @param progress the fraction done while busy, clamped to 0–1; NaN shows a spinner
   */
  public record Item(
      Icon icon,
      String label,
      Key shortcut,
      Dot dot,
      String description,
      String key,
      boolean disabled,
      boolean busy,
      double progress)
      implements Entry {

    public Item {
      Objects.requireNonNull(icon, "icon");
      Objects.requireNonNull(label, "label");
    }

    /** An item with default description, key, and state. */
    public Item(Icon icon, String label, Key shortcut, Dot dot) {
      this(icon, label, shortcut, dot, null, null, false, false, Double.NaN);
    }

    /**
     * This item with a key combination shown on keycaps in its tooltip ({@link Menu#shortcutText}).
     * The app binds the key itself.
     *
     * @param modifiers a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT}
     *     and {@link Key#META}
     * @param key the key's name, as {@link Key} lists them: {@code 1}, {@code F}, {@code B}
     */
    public Item shortcut(int modifiers, String key) {
      return new Item(
          icon,
          label,
          Menu.Item.combination(modifiers, key),
          dot,
          description,
          this.key,
          disabled,
          busy,
          progress);
    }

    /**
     * This item with a small colored circle at its top right. A nonempty {@code text} shows in its
     * tooltip and as its {@code value} in a tree dump.
     */
    public Item dot(Badge.Kind kind, String text) {
      return new Item(
          icon, label, shortcut, new Dot(kind, text), description, key, disabled, busy, progress);
    }

    /**
     * This item with secondary tooltip text. When both the description and the dot's text are
     * nonempty, the tooltip shows the description followed by an em dash and the dot's text.
     */
    public Item description(String text) {
      return new Item(icon, label, shortcut, dot, text, key, disabled, busy, progress);
    }

    /** This item with a custom key for scripts and for keeping its Tab stop across rebuilds. */
    public Item key(String name) {
      return new Item(icon, label, shortcut, dot, description, name, disabled, busy, progress);
    }

    /** This item disabled or enabled. The rail's selection handler is also required to enable it. */
    public Item disabled(boolean off) {
      return new Item(icon, label, shortcut, dot, description, key, off, busy, progress);
    }

    /** This item busy or ready. While enabled and busy it takes focus and ignores presses. */
    public Item busy(boolean busy) {
      return new Item(icon, label, shortcut, dot, description, key, disabled, busy, progress);
    }

    /** This item with a progress ring while busy. NaN shows the spinner. */
    public Item progress(double fraction) {
      return new Item(icon, label, shortcut, dot, description, key, disabled, busy, fraction);
    }
  }

  /**
   * An icon button that runs a command independently of the rail's panel selection.
   *
   * @param icon the action's icon
   * @param label what the action does, shown in its tooltip and in a tree dump
   * @param onPress runs when pressed; null disables the action
   * @param shortcut shown as keycaps in the tooltip; null for none
   * @param dot a small mark at the button's top right; null for none
   * @param description secondary tooltip text; null for none
   * @param key a unique element key and Tab stop ID; null for {@code action-<index>}
   * @param disabled whether the action is disabled even if it has a press handler
   * @param busy whether to show a spinner or progress ring and ignore presses while enabled
   * @param progress the fraction done while busy, clamped to 0–1; NaN shows a spinner
   */
  public record Action(
      Icon icon,
      String label,
      Runnable onPress,
      Key shortcut,
      Dot dot,
      String description,
      String key,
      boolean disabled,
      boolean busy,
      double progress)
      implements Entry {

    public Action {
      Objects.requireNonNull(icon, "icon");
      Objects.requireNonNull(label, "label");
    }

    /** This action with a key combination shown on keycaps, as in {@link Item#shortcut(int, String)}. */
    public Action shortcut(int modifiers, String key) {
      return new Action(
          icon,
          label,
          onPress,
          Menu.Item.combination(modifiers, key),
          dot,
          description,
          this.key,
          disabled,
          busy,
          progress);
    }

    /** This action with a status dot, as in {@link Item#dot(Badge.Kind, String)}. */
    public Action dot(Badge.Kind kind, String text) {
      return new Action(
          icon,
          label,
          onPress,
          shortcut,
          new Dot(kind, text),
          description,
          key,
          disabled,
          busy,
          progress);
    }

    /** This action with secondary tooltip text, as in {@link Item#description(String)}. */
    public Action description(String text) {
      return new Action(icon, label, onPress, shortcut, dot, text, key, disabled, busy, progress);
    }

    /** This action with a custom key for scripts and for keeping its Tab stop across rebuilds. */
    public Action key(String name) {
      return new Action(
          icon, label, onPress, shortcut, dot, description, name, disabled, busy, progress);
    }

    /** This action disabled or enabled. A press handler is also required to enable it. */
    public Action disabled(boolean off) {
      return new Action(icon, label, onPress, shortcut, dot, description, key, off, busy, progress);
    }

    /** This action busy or ready. While enabled and busy it takes focus and ignores presses. */
    public Action busy(boolean busy) {
      return new Action(
          icon, label, onPress, shortcut, dot, description, key, disabled, busy, progress);
    }

    /** This action with a progress ring while busy. NaN shows the spinner. */
    public Action progress(double fraction) {
      return new Action(
          icon, label, onPress, shortcut, dot, description, key, disabled, busy, fraction);
    }
  }

  /**
   * What a rail keeps between builds, which the app holds in its state, one per rail: the button
   * that Tab reaches.
   */
  public static final class State {
    final Roving keys = new Roving(true);
  }

  /** Rail behavior beyond its entries and handler. */
  public static final class Options {
    private boolean closable = true;

    private Options() {}

    /**
     * Whether pressing the open item closes it (the default). Pass false to keep the selection
     * when that item is pressed again.
     */
    public Options closable(boolean closable) {
      this.closable = closable;
      return this;
    }
  }

  private static final Separator SEPARATOR = new Separator();

  /** An item of the rail with an icon and its label. */
  public static Item item(Icon icon, String label) {
    return new Item(icon, label, null, null);
  }

  /** A ghost icon button with its own handler. A null handler disables it. */
  public static Action action(Icon icon, String label, Runnable onPress) {
    return new Action(icon, label, onPress, null, null, null, null, false, false, Double.NaN);
  }

  /** A horizontal hairline between groups of items. */
  public static Separator separator() {
    return SEPARATOR;
  }

  /** The default rail options. */
  public static Options options() {
    return new Options();
  }

  /** The rail's width in the current theme, including its padding and inner hairline. */
  public static float width() {
    Theme theme = Theme.CURRENT.get();
    return theme.size().controlLarge() + 2 * theme.space().s() + theme.size().stroke();
  }

  /** A rail with a top group and no bottom group. */
  public static El rail(
      State state,
      String label,
      Side side,
      List<? extends Entry> top,
      int open,
      IntConsumer onSelect) {
    return rail(state, label, side, top, open, onSelect, options());
  }

  /** A rail with a top group and the given options. */
  public static El rail(
      State state,
      String label,
      Side side,
      List<? extends Entry> top,
      int open,
      IntConsumer onSelect,
      Options options) {
    return rail(state, label, side, top, List.of(), open, onSelect, options);
  }

  /** A rail with top and bottom groups and the default options. */
  public static El rail(
      State state,
      String label,
      Side side,
      List<? extends Entry> top,
      List<? extends Entry> bottom,
      int open,
      IntConsumer onSelect) {
    return rail(state, label, side, top, bottom, open, onSelect, options());
  }

  /**
   * Builds a vertical icon rail along the left or right edge of a window.
   *
   * @param state kept between builds by the app
   * @param label what the rail is for, its label in a tree dump
   * @param side {@link Side#LEFT} or {@link Side#RIGHT}; determines which inner edge gets the
   *     hairline and which way tooltips open
   * @param top entries at the top of the rail
   * @param bottom entries pinned to the bottom of the rail when there is room
   * @param open the index of the open item (counting only {@link Item}s of {@code top} then
   *     {@code bottom}), or {@code -1} when none is open
   * @param onSelect told the index of a closed item when pressed, or {@code -1} when the open item
   *     is pressed and closable; null disables the toggle items
   * @param options whether pressing the open item closes it
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
      IntConsumer onSelect,
      Options options) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(label, "label");
    if (side != Side.LEFT && side != Side.RIGHT) {
      throw new IllegalArgumentException("a rail goes on the left or right, not " + side);
    }
    Objects.requireNonNull(top, "top");
    Objects.requireNonNull(bottom, "bottom");
    boolean closable = Objects.requireNonNull(options, "options").closable;

    Theme theme = Theme.CURRENT.get();
    float pad = theme.space().s();
    float stroke = theme.size().stroke();
    Side tipSide = side == Side.LEFT ? Side.RIGHT : Side.LEFT;

    El rail = col()
        .role("toolbar")
        .label(label)
        .w(width())
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
    List<String> ids = new ArrayList<>();
    int itemIndex = 0;
    int actionIndex = 0;
    for (int group = 0; group < 2; group++) {
      List<? extends Entry> entries = group == 0 ? top : bottom;
      boolean pin = group == 1;
      for (Entry entry : entries) {
        El child = switch (Objects.requireNonNull(entry, "entry")) {
          case Separator _ -> separator(theme);
          case Item item -> toggle(theme, item, itemIndex++, open, tipSide, onSelect, closable);
          case Action action -> command(theme, action, actionIndex++, tipSide);
        };
        // An auto margin takes the free height without adding a second gap when the rail scrolls.
        if (pin) {
          child.style().marginTop = Len.AUTO;
          pin = false;
        }
        if (child.isFocusable()) {
          stops.add(child);
          ids.add(child.key());
        }
        rail.child(child);
      }
    }
    state.keys.apply(rail, stops, ids);
    return rail.disabled(stops.isEmpty());
  }

  /** A top group of toggle items for typed values, with the default options. */
  public static <T> El rail(
      State state,
      String label,
      Side side,
      List<T> values,
      T open,
      Function<? super T, Item> item,
      Consumer<? super T> onSelect) {
    return rail(state, label, side, values, open, item, onSelect, options());
  }

  /**
   * A top group of toggle items for typed values. Equal values share the item of their first
   * occurrence. A null or missing open value leaves the rail closed. When closable, pressing the
   * open item passes null to {@code onSelect}. A null handler disables all items.
   */
  public static <T> El rail(
      State state,
      String label,
      Side side,
      List<T> values,
      T open,
      Function<? super T, Item> item,
      Consumer<? super T> onSelect,
      Options options) {
    Objects.requireNonNull(item, "item");
    List<T> once = Choices.distinct(values);
    List<Item> items = new ArrayList<>(once.size());
    for (T value : once) {
      items.add(Objects.requireNonNull(item.apply(value), "item"));
    }
    IntConsumer select =
        onSelect == null ? null : index -> onSelect.accept(index < 0 ? null : once.get(index));
    return rail(
        state, label, side, items, open == null ? -1 : Choices.index(once, open), select, options);
  }

  private static El toggle(
      Theme theme,
      Item item,
      int index,
      int open,
      Side tipSide,
      IntConsumer onSelect,
      boolean closable) {
    boolean enabled = onSelect != null && !item.disabled();
    Consumer<Boolean> onToggle = enabled
        ? show -> {
          if (show || closable) {
            onSelect.accept(show ? index : -1);
          }
        }
        : null;
    Button.Options options = buttonOptions(
        tipSide,
        item.shortcut(),
        item.description(),
        item.dot(),
        enabled && item.busy(),
        item.progress());
    El button = Button.toggleIcon(item.icon(), item.label(), open == index, onToggle, options)
        .key(item.key() != null ? item.key() : "item-" + index);
    return withDot(theme, button, item.dot());
  }

  private static El command(Theme theme, Action action, int index, Side tipSide) {
    Runnable onPress = action.disabled() ? null : action.onPress();
    Button.Options options = buttonOptions(
        tipSide,
        action.shortcut(),
        action.description(),
        action.dot(),
        onPress != null && action.busy(),
        action.progress());
    El button = Button.icon(Button.Variant.GHOST, action.icon(), action.label(), onPress, options)
        .key(action.key() != null ? action.key() : "action-" + index);
    return withDot(theme, button, action.dot());
  }

  private static Button.Options buttonOptions(
      Side tipSide, Key shortcut, String description, Dot dot, boolean busy, double progress) {
    Button.Options options =
        Button.options().size(Button.Size.LARGE).shortcut(shortcut).tipSide(tipSide);
    if (busy) {
      options.state(Button.State.BUSY).progress(progress);
    }
    if (dot != null && !dot.text().isEmpty()) {
      description = description == null || description.isEmpty()
          ? dot.text()
          : description + " — " + dot.text();
    }
    return options.tipDescription(
        description == null || description.isEmpty() ? null : description);
  }

  private static El separator(Theme theme) {
    return Divider.divider()
        .w(theme.size().controlLarge() - 2 * theme.space().s())
        .margin(theme.space().xs(), 0, theme.space().xs(), 0);
  }

  private static El withDot(Theme theme, El button, Dot dot) {
    if (dot == null) {
      return button;
    }
    if (!dot.text().isEmpty()) {
      button.value(dot.text());
    }
    float size = theme.size().dot();
    Len inset = Len.px(theme.space().s() + theme.size().ringGap());
    El mark = el().size(size, size)
        .radius(size / 2)
        .fill(
            button.isFocusable()
                ? Badge.tone(theme, dot.kind()).strong()
                : theme.text().disabled())
        .inert();
    Style at = mark.style();
    at.position = Style.Position.ABSOLUTE;
    at.top = inset;
    at.right = inset;
    return button.child(mark);
  }
}
