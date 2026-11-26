package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.Background;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.ScrollState;
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
 * <p>By default the rail sits on the window surface ({@link Theme.Surfaces#window}) and is as wide
 * as a large control ({@link Theme.Sizes#controlLarge}) plus padding and an inner hairline ({@link
 * #width()}). Items are square ghost icon buttons ({@link Button#toggleIcon}). The open item
 * shows the tinted tile and
 * accent-edge icon of a pressed-in toggle button. A nonempty {@link Count} draws a pill at the
 * button's top right, taking the place of any {@link Dot}. The button's {@code value} in a tree
 * dump and its tooltip use the dot's text if it is nonempty, otherwise the count's text if
 * nonempty. Tooltips open toward the window's inside (right of a {@link Side#LEFT} rail,
 * left of a {@link Side#RIGHT} rail), with any shortcut on keycaps. When the height runs out, the
 * rail scrolls with the wheel and as the keys move, with an indicator at each edge with more
 * entries. The secondary button, Menu key, and Shift+F10 open the entry's context menu, if any.
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

  /** The size of the rail's square buttons. */
  public enum Size {
    /** {@link Theme.Sizes#controlLarge} high, by default. */
    LARGE,
    /** {@link Theme.Sizes#control} high, for a rail inside another component. */
    REGULAR
  }

  /** The fill behind the rail's buttons. */
  public enum Surface {
    /** The window's own fill, by default. */
    WINDOW,
    /** The theme's band fill, for a rail beside content inside a component. */
    BAND,
    /** Lets the background behind the rail show through. */
    TRANSPARENT
  }

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
   * A compact pill at the top right of an item or action, dimmed when it is disabled.
   *
   * @param kind picks the pill's colors from {@link Theme.Badges}
   * @param text the count to show; empty hides the pill
   */
  public record Count(Badge.Kind kind, String text) {
    public Count {
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
   * @param count a compact pill at the button's top right; null for none
   * @param contextMenu items of the context menu, copied; null or empty for none
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
      double progress,
      Count count,
      List<Menu.Item> contextMenu)
      implements Entry {

    public Item {
      Objects.requireNonNull(icon, "icon");
      Objects.requireNonNull(label, "label");
      contextMenu = contextMenu == null ? List.of() : List.copyOf(contextMenu);
    }

    /** An item without a count or context menu. */
    public Item(
        Icon icon,
        String label,
        Key shortcut,
        Dot dot,
        String description,
        String key,
        boolean disabled,
        boolean busy,
        double progress) {
      this(icon, label, shortcut, dot, description, key, disabled, busy, progress, null, null);
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
          progress,
          count,
          contextMenu);
    }

    /**
     * This item with a small colored circle at its top right. A nonempty {@code text} shows in its
     * tooltip and as its {@code value} in a tree dump.
     */
    public Item dot(Badge.Kind kind, String text) {
      return new Item(
          icon,
          label,
          shortcut,
          new Dot(kind, text),
          description,
          key,
          disabled,
          busy,
          progress,
          count,
          contextMenu);
    }

    /** This item with a neutral count at its top right. */
    public Item count(String text) {
      return count(Badge.Kind.NEUTRAL, text);
    }

    /** This item with a count pill. A nonempty count takes the place of its dot visually. */
    public Item count(Badge.Kind kind, String text) {
      return new Item(
          icon,
          label,
          shortcut,
          dot,
          description,
          key,
          disabled,
          busy,
          progress,
          new Count(kind, text),
          contextMenu);
    }

    /** This item with a secondary-press menu while enabled. Pass null or an empty list for none. */
    public Item contextMenu(List<Menu.Item> items) {
      return new Item(
          icon, label, shortcut, dot, description, key, disabled, busy, progress, count, items);
    }

    /**
     * This item with secondary tooltip text. When both the description and the dot's text are
     * nonempty, the tooltip shows the description followed by an em dash and the dot's text. If
     * the dot has no text, the count's text is used instead.
     */
    public Item description(String text) {
      return new Item(
          icon, label, shortcut, dot, text, key, disabled, busy, progress, count, contextMenu);
    }

    /** This item with a custom key for scripts and for keeping its Tab stop and context menu. */
    public Item key(String name) {
      return new Item(
          icon,
          label,
          shortcut,
          dot,
          description,
          name,
          disabled,
          busy,
          progress,
          count,
          contextMenu);
    }

    /** This item disabled or enabled. The rail's selection handler is also required to enable it. */
    public Item disabled(boolean off) {
      return new Item(
          icon, label, shortcut, dot, description, key, off, busy, progress, count, contextMenu);
    }

    /** This item busy or ready. While enabled and busy it takes focus and ignores presses. */
    public Item busy(boolean busy) {
      return new Item(
          icon,
          label,
          shortcut,
          dot,
          description,
          key,
          disabled,
          busy,
          progress,
          count,
          contextMenu);
    }

    /** This item with a progress ring while busy. NaN shows the spinner. */
    public Item progress(double fraction) {
      return new Item(
          icon,
          label,
          shortcut,
          dot,
          description,
          key,
          disabled,
          busy,
          fraction,
          count,
          contextMenu);
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
   * @param count a compact pill at the button's top right; null for none
   * @param contextMenu items of the context menu, copied; null or empty for none
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
      double progress,
      Count count,
      List<Menu.Item> contextMenu)
      implements Entry {

    public Action {
      Objects.requireNonNull(icon, "icon");
      Objects.requireNonNull(label, "label");
      contextMenu = contextMenu == null ? List.of() : List.copyOf(contextMenu);
    }

    /** An action without a count or context menu. */
    public Action(
        Icon icon,
        String label,
        Runnable onPress,
        Key shortcut,
        Dot dot,
        String description,
        String key,
        boolean disabled,
        boolean busy,
        double progress) {
      this(
          icon,
          label,
          onPress,
          shortcut,
          dot,
          description,
          key,
          disabled,
          busy,
          progress,
          null,
          null);
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
          progress,
          count,
          contextMenu);
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
          progress,
          count,
          contextMenu);
    }

    /** This action with a neutral count at its top right. */
    public Action count(String text) {
      return count(Badge.Kind.NEUTRAL, text);
    }

    /** This action with a count pill. A nonempty count takes the place of its dot visually. */
    public Action count(Badge.Kind kind, String text) {
      return new Action(
          icon,
          label,
          onPress,
          shortcut,
          dot,
          description,
          key,
          disabled,
          busy,
          progress,
          new Count(kind, text),
          contextMenu);
    }

    /** This action with a secondary-press menu while enabled. Pass null or an empty list for none. */
    public Action contextMenu(List<Menu.Item> items) {
      return new Action(
          icon,
          label,
          onPress,
          shortcut,
          dot,
          description,
          key,
          disabled,
          busy,
          progress,
          count,
          items);
    }

    /** This action with secondary tooltip text, as in {@link Item#description(String)}. */
    public Action description(String text) {
      return new Action(
          icon,
          label,
          onPress,
          shortcut,
          dot,
          text,
          key,
          disabled,
          busy,
          progress,
          count,
          contextMenu);
    }

    /** This action with a custom key for scripts and for keeping its Tab stop and context menu. */
    public Action key(String name) {
      return new Action(
          icon,
          label,
          onPress,
          shortcut,
          dot,
          description,
          name,
          disabled,
          busy,
          progress,
          count,
          contextMenu);
    }

    /** This action disabled or enabled. A press handler is also required to enable it. */
    public Action disabled(boolean off) {
      return new Action(
          icon,
          label,
          onPress,
          shortcut,
          dot,
          description,
          key,
          off,
          busy,
          progress,
          count,
          contextMenu);
    }

    /** This action busy or ready. While enabled and busy it takes focus and ignores presses. */
    public Action busy(boolean busy) {
      return new Action(
          icon,
          label,
          onPress,
          shortcut,
          dot,
          description,
          key,
          disabled,
          busy,
          progress,
          count,
          contextMenu);
    }

    /** This action with a progress ring while busy. NaN shows the spinner. */
    public Action progress(double fraction) {
      return new Action(
          icon,
          label,
          onPress,
          shortcut,
          dot,
          description,
          key,
          disabled,
          busy,
          fraction,
          count,
          contextMenu);
    }
  }

  /**
   * What a rail keeps between builds, which the app holds in its state, one per rail: the button
   * that Tab reaches, the scroll offset, and the open context menu.
   */
  public static final class State {
    final Roving keys = new Roving(true);
    final ScrollState scroll = new ScrollState();
    private final Menu.State menu = new Menu.State();
    private MenuAnchor menuAnchor;

    /** The shared context menu state, which the app can inspect or close. */
    public Menu.State contextMenu() {
      return menu;
    }
  }

  // An index can be reused by another entry when the app doesn't give its entries keys.
  private record MenuAnchor(String key, String labelAtIndex) {}

  /** Rail behavior beyond its entries and handler. */
  public static final class Options {
    private boolean closable = true;
    private Size size = Size.LARGE;
    private Surface surface = Surface.WINDOW;
    private boolean indicator;

    private Options() {}

    /**
     * Whether pressing the open item closes it (the default). Pass false to keep the selection
     * when that item is pressed again.
     */
    public Options closable(boolean closable) {
      this.closable = closable;
      return this;
    }

    /** Sizes the buttons and the rail. The default is {@link Size#LARGE}. */
    public Options size(Size size) {
      this.size = Objects.requireNonNull(size, "size");
      return this;
    }

    /** Sets the rail's surface. The default is {@link Surface#WINDOW}. */
    public Options surface(Surface surface) {
      this.surface = Objects.requireNonNull(surface, "surface");
      return this;
    }

    /** Draws a mark along the rail's outer edge beside the open item when true. Off by default. */
    public Options indicator(boolean indicator) {
      this.indicator = indicator;
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

  /** The width of a large rail in the current theme. */
  public static float width() {
    return width(Size.LARGE);
  }

  /** The rail's width at this size in the current theme, including padding and the inner hairline. */
  public static float width(Size size) {
    Theme theme = Theme.CURRENT.get();
    return control(theme, Objects.requireNonNull(size, "size"))
        + 2 * theme.space().s()
        + theme.size().stroke();
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
   * @param options the size, surface, closability, and optional active item indicator
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
    Objects.requireNonNull(options, "options");

    Theme theme = Theme.CURRENT.get();
    float pad = theme.space().s();
    float stroke = theme.size().stroke();
    Side tipSide = side == Side.LEFT ? Side.RIGHT : Side.LEFT;
    int fill = switch (options.surface) {
      case WINDOW -> theme.surface().window();
      case BAND -> theme.surface().band();
      case TRANSPARENT -> Colors.TRANSPARENT;
    };

    El rail = col()
        .role("toolbar")
        .label(label)
        .w(width(options.size))
        .shrink(0)
        .alignSelf(Style.Align.STRETCH) // the window's height, also in a row that aligns at the top
        .fill(fill);
    rail.style().minHeight = Len.ZERO;
    if (side == Side.LEFT) {
      rail.border(0, stroke, 0, 0, theme.border().divider());
    } else {
      rail.border(0, 0, 0, stroke, theme.border().divider());
    }

    // Keep the fades outside the scroll area so they stay over its edges as the entries move.
    El items = col()
        .key("rail-items")
        .grow(1)
        .minSize(0, 0)
        .align(Style.Align.CENTER)
        .padding(pad)
        .gap(pad)
        .scroll()
        .scrollState(state.scroll);
    rail.child(items);

    if (!state.menu.isOpen()) {
      state.menuAnchor = null;
    }
    boolean menuFound = false;
    List<El> stops = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    int itemIndex = 0;
    int actionIndex = 0;
    for (int group = 0; group < 2; group++) {
      List<? extends Entry> entries = group == 0 ? top : bottom;
      boolean pin = group == 1;
      for (Entry entry : entries) {
        El child = switch (Objects.requireNonNull(entry, "entry")) {
          case Separator _ -> separator(theme, options.size);
          case Item item -> toggle(theme, item, itemIndex++, open, tipSide, onSelect, options);
          case Action action -> command(theme, action, actionIndex++, tipSide, options.size);
        };
        // An auto margin takes the free height without adding a second gap when the rail scrolls.
        if (pin) {
          child.style().marginTop = Len.AUTO;
          pin = false;
        }
        if (!(entry instanceof Separator)) {
          El button = child.children().getFirst();
          if (button.isFocusable()) {
            stops.add(button);
            ids.add(button.key());
            menuFound |= switch (entry) {
              case Item item -> contextMenu(state, button, item.contextMenu(), item.key());
              case Action action -> contextMenu(state, button, action.contextMenu(), action.key());
              case Separator _ -> false;
            };
          }
        }
        items.child(child);
      }
    }
    if (!menuFound && state.menu.isOpen()) {
      state.menu.close();
      state.menuAnchor = null;
    }
    if (!state.scroll.atStart()) {
      rail.child(overflow(theme, rail, true));
    }
    if (!state.scroll.atEnd()) {
      rail.child(overflow(theme, rail, false));
    }
    state.keys.apply(rail, stops, ids);
    return rail.disabled(stops.isEmpty());
  }

  private static boolean contextMenu(
      State state, El button, List<Menu.Item> menu, String customKey) {
    if (menu.isEmpty()) {
      return false;
    }
    MenuAnchor anchor = new MenuAnchor(button.key(), customKey == null ? button.label() : null);
    button.onSecondaryPress((x, y) -> {
      state.menuAnchor = anchor;
      state.menu.openAt(x, y);
    });
    if (!anchor.equals(state.menuAnchor)) {
      return false;
    }
    Menu.contextMenu(button, state.menu, menu);
    return true;
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
      Options railOptions) {
    boolean enabled = onSelect != null && !item.disabled();
    boolean closable = railOptions.closable;
    Consumer<Boolean> onToggle = enabled
        ? show -> {
          if (show || closable) {
            onSelect.accept(show ? index : -1);
          }
        }
        : null;
    String status = statusText(item.dot(), item.count());
    Button.Options options = buttonOptions(
        railOptions.size,
        tipSide,
        item.shortcut(),
        item.description(),
        status,
        enabled && item.busy(),
        item.progress());
    boolean active = open == index;
    El button = Button.toggleIcon(item.icon(), item.label(), active, onToggle, options)
        .key(item.key() != null ? item.key() : "item-" + index)
        .value(status);
    if (active && railOptions.indicator) {
      addIndicator(theme, button, railOptions.size, tipSide == Side.RIGHT ? Side.LEFT : Side.RIGHT);
    }
    return entryFrame(theme, button, railOptions.size, item.dot(), item.count());
  }

  private static El command(Theme theme, Action action, int index, Side tipSide, Size size) {
    Runnable onPress = action.disabled() ? null : action.onPress();
    String status = statusText(action.dot(), action.count());
    Button.Options options = buttonOptions(
        size,
        tipSide,
        action.shortcut(),
        action.description(),
        status,
        onPress != null && action.busy(),
        action.progress());
    El button = Button.icon(Button.Variant.GHOST, action.icon(), action.label(), onPress, options)
        .key(action.key() != null ? action.key() : "action-" + index)
        .value(status);
    return entryFrame(theme, button, size, action.dot(), action.count());
  }

  private static String statusText(Dot dot, Count count) {
    if (dot != null && !dot.text().isEmpty()) {
      return dot.text();
    }
    return count != null && !count.text().isEmpty() ? count.text() : null;
  }

  private static Button.Options buttonOptions(
      Size size,
      Side tipSide,
      Key shortcut,
      String description,
      String status,
      boolean busy,
      double progress) {
    Button.Options options = Button.options()
        .size(size == Size.REGULAR ? Button.Size.REGULAR : Button.Size.LARGE)
        .shortcut(shortcut)
        .tipSide(tipSide);
    if (busy) {
      options.state(Button.State.BUSY).progress(progress);
    }
    if (status != null) {
      description =
          description == null || description.isEmpty() ? status : description + " — " + status;
    }
    return options.tipDescription(
        description == null || description.isEmpty() ? null : description);
  }

  private static float control(Theme theme, Size size) {
    return size == Size.REGULAR ? theme.size().control() : theme.size().controlLarge();
  }

  private static El separator(Theme theme, Size size) {
    return Divider.divider()
        .w(control(theme, size) - 2 * theme.space().s())
        .margin(theme.space().xs(), 0, theme.space().xs(), 0);
  }

  private static El entryFrame(Theme theme, El button, Size buttonSize, Dot dot, Count count) {
    // Lay the pill over the button's focus ring, within the scroll area's clip.
    El frame =
        el().key("entry-" + button.key()).motion(theme.motion().feedback()).child(button);
    if (count != null && !count.text().isEmpty()) {
      frame.child(count(theme, button.isFocusable(), buttonSize, count));
    } else if (dot != null) {
      button.child(dot(theme, button.isFocusable(), buttonSize, dot));
    }
    return frame;
  }

  private static El dot(Theme theme, boolean enabled, Size buttonSize, Dot dot) {
    float size = theme.size().dot();
    Len inset = Len.px(
        buttonSize == Size.REGULAR
            ? theme.space().xs()
            : theme.space().s() + theme.size().ringGap());
    El mark = el().size(size, size)
        .radius(size / 2)
        .fill(enabled ? Badge.tone(theme, dot.kind()).strong() : theme.text().disabled())
        .inert();
    Style at = mark.style();
    at.position = Style.Position.ABSOLUTE;
    at.top = inset;
    at.right = inset;
    return mark;
  }

  private static El count(Theme theme, boolean enabled, Size size, Count count) {
    Theme.Tone tone = Badge.tone(theme, count.kind());
    Theme.TextStyle text = theme.badge().text();
    float padding = theme.space().xs();
    float height = size == Size.REGULAR
        ? theme.space().l()
        : theme.space().l() + theme.space().xs();
    float fontSize = Math.max(
        text.size() / 2,
        text.size() - (size == Size.REGULAR ? theme.space().s() : theme.space().xs()));
    El mark = el().key("count")
        .h(height)
        .minSize(height, 0)
        .center()
        .padding(0, padding)
        .radius(height / 2)
        .fill(enabled ? tone.strong() : theme.badge().neutral().soft())
        .clip()
        .inert()
        .child(El.text(count.text())
            .font(text.font().get())
            .fontSize(fontSize)
            .fontFeatures("+tnum")
            .color(enabled ? tone.strongText() : theme.text().disabled())
            .ellipsis()
            .minSize(0, 0));
    Style at = mark.style();
    at.position = Style.Position.ABSOLUTE;
    Len offset = size == Size.REGULAR ? Len.px(-padding) : Len.ZERO;
    at.top = offset;
    at.right = offset;
    at.maxWidth = Len.px(control(theme, size));
    return mark;
  }

  private static void addIndicator(Theme theme, El button, Size size, Side side) {
    float width = theme.size().indicator();
    float height = size == Size.REGULAR
        ? theme.size().icon() - theme.space().s()
        : theme.size().icon();
    float pad = theme.space().s();
    float stroke = theme.size().stroke();
    El mark = el().key("indicator")
        .size(width, height)
        .radius(width / 2)
        .fill(button.isFocusable() ? theme.accent().edge() : theme.text().disabled())
        .inert();
    Style at = mark.style();
    at.position = Style.Position.ABSOLUTE;
    at.top = Len.px((control(theme, size) - height) / 2 - stroke);
    if (side == Side.LEFT) {
      at.left = Len.px(-pad - stroke);
    } else {
      at.right = Len.px(-pad - stroke);
    }
    button.child(mark);
  }

  private static El overflow(Theme theme, El rail, boolean top) {
    El edge = el().key(top ? "overflow-top" : "overflow-bottom").inert();
    Style at = edge.style();
    at.position = Style.Position.ABSOLUTE;
    at.left = Len.ZERO;
    at.right = Len.ZERO;
    at.maxHeight = Len.percent(1);
    if (top) {
      at.top = Len.ZERO;
    } else {
      at.bottom = Len.ZERO;
    }
    // The app may set the rail's fill or background after rail(...) returns.
    return edge.measure(new Measure() {
      private boolean solid() {
        return Colors.alpha(rail.fill()) == 255 && rail.background() == null;
      }

      @Override
      public Measure.Size measure(
          float knownWidth, float knownHeight, float availableWidth, float availableHeight) {
        return new Measure.Size(0, solid() ? theme.space().l() : theme.size().stroke());
      }

      @Override
      public void placed(float width, float height) {
        if (solid()) {
          int fill = rail.fill();
          Len end = Len.percent(1);
          edge.background(Background.linear(
              Len.ZERO,
              top ? Len.ZERO : end,
              Len.ZERO,
              top ? end : Len.ZERO,
              fill,
              Colors.alpha(fill, 0)));
        } else {
          edge.fill(theme.border().divider());
        }
      }
    });
  }
}
