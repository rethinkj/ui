package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A button with text, an icon before its text, or an icon alone. A press runs its handler, and so
 * do Enter and Space while it is focused. Tab reaches it, but a press leaves the keyboard focus
 * where it is ({@link El#keepFocus}), so that the editor or grid a toolbar button acts on keeps it;
 * also a press on a button that is off. The text is in {@link Theme.Type#strong}, the medium
 * weight.
 *
 * <p>Primary and secondary buttons lie a hair above their surface ({@link
 * Theme.Elevation#control}), a little higher under the pointer; pressed, a button loses its shadow,
 * its rounded corners tighten to {@link Theme.Radii#small} and its content moves down by {@link
 * Theme.Sizes#stroke}, in the same box, so that a press shows in its shape and not in its color
 * alone. The focus ring lies {@link Theme.Sizes#ringGap} apart from every button.
 *
 * <p>What a button is beyond its variant, text and handler goes into {@link #options()}:
 *
 * <pre>{@code
 * Button.button(PRIMARY, "Run", state::run,
 *     Button.options().icon(Symbol.PLAY).state(state.running ? BUSY : READY))
 * Button.button(PRIMARY, "Drop table", state::drop,
 *     Button.options().tone(Button.Tone.DANGER))
 * Button.button(SECONDARY, "Commit", state::commit,
 *     Button.options().state(state.open() ? READY : DISABLED).shortcut(Key.CONTROL, "S"))
 * }</pre>
 *
 * <p>A button is off when its handler is null or its {@link Options#state} is {@link
 * State#DISABLED}, both alike: it takes no focus, shows no pointer cursor, draws in the disabled
 * colors, a press on it goes to its ancestors, and a tree dump says {@code disabled}. {@link
 * El#disabled(boolean)} on the element a button returns changes only the dump, not what it does.
 */
public final class Button {

  public enum Variant {
    /** The main action of a view, in the accent color. */
    PRIMARY,
    /** Any other action, on a raised surface with an edge. */
    SECONDARY,
    /** A quiet action that shows its surface only while hovered or pressed, as in toolbars. */
    GHOST
  }

  /** What a button's action does to what it acts on, which picks its colors with the variant. */
  public enum Tone {
    /** Any action, in the accent's and the surfaces' colors. */
    NEUTRAL,
    /**
     * An action that destroys something, as Drop table or Discard, in {@link Theme.Danger}'s
     * colors: a primary button filled red with white text, for the confirmation in a dialog; a
     * secondary one with red text and a red edge on the raised surface, beside other actions; a
     * ghost one with red text alone, in a toolbar or a row. Its focus ring is {@link
     * Theme.Danger#ring}. Off, a primary one is filled with {@link Theme.Danger#disabled}.
     */
    DANGER,
    /**
     * An action that confirms or applies something, as Commit or Apply: the icon of a secondary or
     * ghost button is in {@link Theme.Status#success}, and the button is else a neutral one, its
     * text too; a primary button looks as {@link #NEUTRAL}. The icon alone, since a status color
     * stands out 3:1 from a control's fills, enough for a symbol but not for text.
     */
    SUCCESS,
    /**
     * An action that calls for care: the icon of a secondary or ghost button is in {@link
     * Theme.Status#warning}, and the button is else a neutral one, as with {@link #SUCCESS}.
     */
    WARNING,
    /**
     * An action that stands out beside neutral ones, as Undo: the icon of a secondary or ghost
     * button is in {@link Theme.Accent#edge}, and the button is else a neutral one, as with {@link
     * #SUCCESS}.
     */
    ACCENT
  }

  /** Whether a button takes a press now. */
  public enum State {
    /** It takes a press, if it has a handler: the default. */
    READY,
    /**
     * Its action runs. A {@link Progress#spinner} in the text's color, or a {@link Progress#ring}
     * with {@link Options#progress}, stands in place of its icon, or, without one, over the middle
     * of its text, which keeps its place unseen so that the button keeps its width; with {@link
     * Options#busyText} it stands before that text instead. It takes no press, but keeps its
     * colors, its place in the order of Tab and the focus, so that nothing jumps when the work
     * ends; Enter and Space on it do nothing and reach no other handler. A button is busy also
     * without a handler. A tree dump says {@code busy}, WAI-ARIA's {@code aria-busy}, which a
     * script selects as {@code role=button busy}.
     */
    BUSY,
    /** Off, exactly as with a null handler while the view keeps its handler (see {@link Button}). */
    DISABLED
  }

  /** How high a button is; its icon and text keep their size. */
  public enum Size {
    /**
     * {@link Theme.Sizes#controlSmall} high, with narrower padding and the small radius, for a
     * dense row.
     */
    SMALL,
    /** {@link Theme.Sizes#control} high, as a text field: the default. */
    REGULAR,
    /**
     * {@link Theme.Sizes#controlLarge} high, with wider padding, for the one action of an empty
     * view or a welcome page.
     */
    LARGE
  }

  /** The colors and shadows of a button: at rest, hovered and pressed, its edge, text and ring. */
  record Look(
      int fill,
      int hover,
      int pressed,
      int edge,
      int text,
      int ring,
      List<Shadow> rest,
      List<Shadow> lifted) {}

  private Button() {}

  /** What a button is beyond its variant, text and handler; {@link #options()} is a plain one. */
  public static final class Options {
    private Icon icon;
    private State state = State.READY;
    private String busyText;
    private double progress = Double.NaN;
    private Tone tone = Tone.NEUTRAL;
    private Size size = Size.REGULAR;
    private Key shortcut;

    private Options() {}

    /**
     * An icon before the text, muted beside the text of a neutral secondary or ghost button, in
     * the tone's color with {@link Tone#SUCCESS}, {@link Tone#WARNING} or {@link Tone#ACCENT}, else
     * in the text's color; null for none. Not for {@link #icon(Variant, Icon, String, Runnable,
     * Options)}, whose icon is a parameter of its own.
     */
    public Options icon(Icon icon) {
      this.icon = icon;
      return this;
    }

    /** Whether it takes a press, is busy or is off: {@link State}. */
    public Options state(State state) {
      this.state = Objects.requireNonNull(state, "state");
      return this;
    }

    /**
     * The text a busy button shows after its spinner, such as {@code "Saving…"}, while the tree
     * dump keeps its own text as its label; null, the default, hides the text under the spinner.
     * The button is as wide as the wider of both texts in either state, so that becoming busy moves
     * nothing beside it. An icon button, which shows no text, shows none.
     */
    public Options busyText(String text) {
      this.busyText = text;
      return this;
    }

    /**
     * How far the work of a busy button has come, from 0 to 1, clamped: a {@link Progress#ring}
     * stands in place of its spinner and fills as the value grows, as while a download runs. NaN,
     * the default, keeps the spinner, for work whose length is not known.
     */
    public Options progress(double fraction) {
      this.progress = fraction;
      return this;
    }

    public Options tone(Tone tone) {
      this.tone = Objects.requireNonNull(tone, "tone");
      return this;
    }

    public Options size(Size size) {
      this.size = Objects.requireNonNull(size, "size");
      return this;
    }

    /**
     * The key combination that runs the button's action elsewhere, shown after its text in a
     * tooltip as a menu writes it ({@link Menu#shortcutText}): {@code shortcut(Key.CONTROL |
     * Key.SHIFT, "C")} shows {@code Ctrl+Shift+C}. It only shows: the app binds the key where it
     * binds its other keys, as with {@link Menu#shortcuts}. A button with text then shows that
     * tooltip whether its text is cut short or not.
     *
     * @param modifiers a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT}
     *     and {@link Key#META}
     * @param key the key's name, as {@link Key} lists them: {@code C}, {@code F5}, {@code Delete}
     */
    public Options shortcut(int modifiers, String key) {
      this.shortcut = Menu.Item.combination(modifiers, key);
      return this;
    }

    /** A copy, for a toolbar that gives it its item's icon. */
    Options copy() {
      Options copy = new Options();
      copy.icon = icon;
      copy.state = state;
      copy.busyText = busyText;
      copy.progress = progress;
      copy.tone = tone;
      copy.size = size;
      copy.shortcut = shortcut;
      return copy;
    }

    /** Whether the options turn a button off. */
    boolean off() {
      return state == State.DISABLED;
    }

    /** The tooltip's options: the shortcut on keycaps, if there is one. */
    private Tooltip.Options tip() {
      return Tooltip.options().shortcut(shortcut);
    }
  }

  public static Options options() {
    return new Options();
  }

  /**
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El button(Variant variant, String text, Runnable onPress) {
    return button(variant, text, onPress, options());
  }

  /**
   * A button with an icon before its text.
   *
   * @param icon before the text; null for none
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El button(Variant variant, Icon icon, String text, Runnable onPress) {
    return button(variant, text, onPress, options().icon(icon));
  }

  /**
   * A button with its options.
   *
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button unless it is busy
   * @param options its icon, state, busy text, progress, tone, size and shortcut
   */
  public static El button(Variant variant, String text, Runnable onPress, Options options) {
    Theme theme = Theme.CURRENT.get();
    State state = state(options, onPress);
    Look look = look(theme, variant, options.tone, state != State.DISABLED);
    El button = frame(theme, look, options.size, text, state == State.DISABLED)
        .padding(theme.size().stroke(), padding(theme, options.size))
        .gap(theme.space().s());
    boolean busy = state == State.BUSY;
    String shown = busy && options.busyText != null ? options.busyText : text;
    El label = label(theme, look, shown);
    // The spinner stands in the icon's place.
    El lead = busy
        ? spinner(theme, variant, look, options.progress)
        : options.icon == null
            ? null
            : icon(theme, variant, options.tone, state, look, options.icon);
    if (options.busyText != null) {
      El now = row().gap(theme.space().s()).center().minSize(0, 0);
      if (lead != null) {
        now.child(lead);
      }
      // The other state's content lies unseen under this one and without height, so that the
      // button is as wide as the wider of both, at whatever scale the window draws it.
      El other = row().gap(theme.space().s()).h(0).opacity(0).minSize(0, 0);
      if (!busy) {
        other.child(el().w(theme.size().icon()).shrink(0)); // the spinner's room
      } else if (options.icon != null) {
        other.child(icon(theme, variant, options.tone, state, look, options.icon));
      }
      // Without an ellipsis: cut short by a narrow view, it would show the tooltip of a cut text.
      other.child(text(theme, busy ? text : options.busyText));
      button.child(col().minSize(0, 0).child(now.child(label)).child(other));
    } else if (busy && options.icon == null) {
      // Over the middle of the text, which keeps its place unseen: the width stays. Out of the
      // flow and without a place of its own, it lies where the button centers its content,
      // whatever size the view gives the button.
      lead.style().position = Style.Position.ABSOLUTE;
      button.child(label.opacity(0)).child(lead);
    } else {
      if (lead != null) {
        button.child(lead);
      }
      button.child(label);
    }
    El tipped = busy
        ? busy(theme, look, button)
        : enable(theme, look, button, state == State.READY ? onPress : null);
    // Where the view makes the button narrower than its text, the text ends in an ellipsis and
    // shows whole in a tooltip, which a shortcut always shows.
    return options.shortcut == null
        ? Tooltip.whenShortened(tipped, shown)
        : Tooltip.tooltip(tipped, text, options.tip());
  }

  /**
   * A square button with an icon alone, such as a toolbar's or a card's header's. The label names
   * it in a tree dump and in a tooltip that shows after the theme's delay under the pointer or the
   * keyboard's focus.
   *
   * @param label what the button does, such as "Add connection"
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El icon(Variant variant, Icon icon, String label, Runnable onPress) {
    return icon(variant, icon, label, onPress, options());
  }

  /**
   * A square button with an icon alone and its options: busy, a spinner, or a ring with a
   * progress, stands in place of the icon; on a secondary or ghost button a tone other than {@link
   * Tone#NEUTRAL} colors the icon, else it is muted unless the button is primary; a shortcut
   * follows the label in its tooltip.
   *
   * @param label what the button does, such as "Add connection"
   * @param onPress runs when the button is pressed; null disables the button unless it is busy
   * @param options its state, progress, tone, size and shortcut; no icon, which is {@code
   *     icon}; a busy text does not show
   */
  public static El icon(
      Variant variant, Icon icon, String label, Runnable onPress, Options options) {
    Theme theme = Theme.CURRENT.get();
    if (options.icon != null) {
      throw new IllegalArgumentException(
          "an icon button's icon is its parameter, not an option: " + options.icon);
    }
    State state = state(options, onPress);
    Look look = look(theme, variant, options.tone, state != State.DISABLED);
    El button = frame(theme, look, options.size, label, state == State.DISABLED)
        .w(height(theme, options.size));
    if (state == State.BUSY) {
      return Tooltip.tooltip(
          busy(theme, look, button.child(spinner(theme, variant, look, options.progress))),
          label,
          options.tip());
    }
    button.child(icon(theme, variant, options.tone, state, look, icon));
    return Tooltip.tooltip(
        enable(theme, look, button, state == State.READY ? onPress : null), label, options.tip());
  }

  /** The state a button shows: busy with or without a handler, else off without one. */
  private static State state(Options options, Runnable onPress) {
    return options.state == State.BUSY
        ? State.BUSY
        : onPress == null ? State.DISABLED : options.state;
  }

  private static float height(Theme theme, Size size) {
    return switch (size) {
      case SMALL -> theme.size().controlSmall();
      case REGULAR -> theme.size().control();
      case LARGE -> theme.size().controlLarge();
    };
  }

  private static float padding(Theme theme, Size size) {
    return switch (size) {
      case SMALL -> theme.space().m();
      case REGULAR -> theme.space().l();
      case LARGE -> theme.space().xl();
    };
  }

  private static float radius(Theme theme, Size size) {
    return size == Size.SMALL ? theme.radius().small() : theme.radius().medium();
  }

  private static El label(Theme theme, Look look, String text) {
    return text(theme, text).color(look.text()).ellipsis();
  }

  private static El text(Theme theme, String text) {
    Theme.TextStyle style = theme.type().strong();
    return El.text(text).font(style.font().get()).fontSize(style.size()).minSize(0, 0);
  }

  /**
   * An icon in the text's color on a primary button, on one that destroys and on one that is off,
   * in the tone's color with a tone of a status or the accent, else muted beside the text, which it
   * leaves the weight.
   */
  private static El icon(
      Theme theme, Variant variant, Tone tone, State state, Look look, Icon icon) {
    int color;
    if (variant == Variant.PRIMARY || state == State.DISABLED) {
      color = look.text();
    } else {
      color = switch (tone) {
        case NEUTRAL -> theme.text().muted();
        case DANGER -> look.text();
        case SUCCESS -> theme.status().success();
        case WARNING -> theme.status().warning();
        case ACCENT -> theme.accent().edge();
      };
    }
    Icon.Variant shown = state == State.DISABLED ? Icon.Variant.DISABLED : Icon.Variant.NORMAL;
    return Icon.Picture.color(Icon.icon(icon, shown), color);
  }

  /** The spinner of a busy button, or its ring with a progress, in its text's color. */
  private static El spinner(Theme theme, Variant variant, Look look, double progress) {
    int track = variant == Variant.PRIMARY
        ? theme.border().trackOnAccent()
        : theme.border().track();
    // The ring is keyed, so that an icon or a spinner in its place later is another element:
    // else its part done, which springs, would fade out over that.
    return Double.isNaN(progress)
        ? Progress.spinner(theme, theme.size().icon(), look.text(), track)
        : Progress.ring(theme, theme.size().icon(), progress, look.text(), track)
            .key("progress");
  }

  /** Makes a button busy: focusable, but Enter, Space and the pointer press nothing. */
  private static El busy(Theme theme, Look look, El button) {
    return ring(theme, look, button.busy(true))
        // Enter and Space do nothing, and go no further: to a dialog's default button, say.
        .onKey(k -> k.modifiers() == 0 && (k.name().equals("Enter") || k.name().equals("Space")));
  }

  /** What a {@link #copy} button copied last, which it confirms for a moment: the app keeps one. */
  public static final class Copied {
    private String what; // the label and text of the button that waits to be confirmed, or null

    /** Whether the button with this label and text confirms a copy now. */
    boolean shows(String label, String text) {
      return (label + '\n' + text).equals(what);
    }
  }

  /**
   * A quiet square button with the copy icon, the label in a dump and in a tooltip, that writes
   * {@code text} to the clipboard. With {@code copied}, the button then shows a check mark in the
   * success color and a tooltip that says Copied, for {@code motion().confirm()} seconds of the
   * app's clock; null for no confirmation.
   */
  public static El copy(Clipboard clipboard, String text, String label, Copied copied) {
    Theme theme = Theme.CURRENT.get();
    boolean done = copied != null && copied.shows(label, text);
    String what = label + '\n' + text;
    Look look = look(theme, Variant.GHOST, Tone.NEUTRAL, true);
    Runnable press = () -> {
      clipboard.write(text);
      if (copied != null) {
        copied.what = what;
      }
    };
    El button = enable(
        theme,
        look,
        frame(theme, look, Size.REGULAR, label, false)
            .w(theme.size().control())
            .child(Icon.icon(
                done ? Symbol.CHECKMARK : Symbol.COPY,
                done ? Icon.Variant.SUCCESS : Icon.Variant.MUTED)),
        press);
    if (!done) {
      return Tooltip.tooltip(button, label);
    }
    // The confirmation is an overlay, whose time on the app's clock ends it.
    return button.overlay(Tooltip.panel(theme, Texts.CURRENT.get().copied())
        .key("copied")
        .keepFocus()
        .onShownFor(theme.motion().confirm(), () -> {
          if (what.equals(copied.what)) {
            copied.what = null;
          }
        }));
  }

  /**
   * An overlay that shows nothing and ends a copy's confirmation after its time, for copy buttons
   * in the rows of a list, whose overlays keep their time only while their row exists; null while
   * nothing is confirmed.
   */
  static El ending(Theme theme, Copied copied) {
    String what = copied.what;
    return what == null
        ? null
        : el().key("copied-" + what.hashCode())
            .keepFocus()
            .onShownFor(theme.motion().confirm(), () -> {
              if (what.equals(copied.what)) {
                copied.what = null;
              }
            });
  }

  /**
   * A toggle button with an icon alone, as in a toolbar: pressed in while {@code on}, with the
   * selection's tint and the icon in the accent's edge, else a ghost button. In a tree dump it is
   * {@code role=button} with {@code checked} or {@code checked=false}, as WAI-ARIA's {@code
   * aria-pressed}, and its label, which a tooltip shows too.
   *
   * @param label what it turns on, such as "Wrap lines"
   * @param onToggle told the new state; null disables the button
   */
  public static El toggleIcon(Icon icon, String label, boolean on, Consumer<Boolean> onToggle) {
    Theme theme = Theme.CURRENT.get();
    El button = icon(
            Variant.GHOST, icon, label, onToggle == null ? null : () -> onToggle.accept(!on))
        .checked(on);
    return on ? pressedIn(theme, button, onToggle != null) : button;
  }

  /**
   * A toggle button with an icon, if any, and text, which looks as {@link #toggleIcon} does.
   *
   * @param icon before the text; null for none
   * @param onToggle told the new state; null disables the button
   */
  public static El toggle(Icon icon, String text, boolean on, Consumer<Boolean> onToggle) {
    Theme theme = Theme.CURRENT.get();
    El button = button(
            Variant.GHOST, icon, text, onToggle == null ? null : () -> onToggle.accept(!on))
        .checked(on);
    return on ? pressedIn(theme, button, onToggle != null) : button;
  }

  /** A toggle button that is on: the tint of a selection, its icon in the accent's edge. */
  private static El pressedIn(Theme theme, El button, boolean enabled) {
    int fill = theme.grid().selected();
    button.fill(fill);
    for (El child : button.children()) {
      if (child.text() != null && child.label() != null) { // the icon, which has its name
        child.color(enabled ? theme.accent().edge() : theme.text().disabled());
      }
    }
    return enabled
        ? button
            .whenHovered(e -> e.fill(over(fill, theme.surface().hover())))
            .whenPressed(e -> pressed(theme, e.fill(over(fill, theme.surface().pressed()))))
        : button;
  }

  /**
   * A split button: the main action, and beside it a chevron that opens a menu of related ones,
   * as Run with Run selection and Explain. Each half is a button of its own, {@code role=button}
   * with the text and with {@code "More " + text} as their labels, and Tab reaches both; the menu
   * opens below the whole. Enter, Space, Arrow Down and Arrow Up on the chevron open it with the
   * first enabled item highlighted, Arrow Up with the last, as WAI-ARIA's menu button has it.
   *
   * @param icon before the text; null for none
   * @param onPress runs the main action; null disables it, and with no enabled item the chevron
   * @param state whether the menu is open, which the chevron toggles
   * @param items the menu's items
   */
  public static El split(
      Variant variant,
      Icon icon,
      String text,
      Runnable onPress,
      Menu.State state,
      List<Menu.Item> items) {
    return split(variant, text, onPress, state, items, options().icon(icon));
  }

  /**
   * A split button with its options. Busy, the main half is busy as a {@link #button} is, and the
   * chevron still opens the menu, whose items the app enables as it sees fit (a Cancel, say). Off
   * ({@link State#DISABLED}), both halves are off and there is no menu: one that was open closes.
   * The tone and the size apply to both halves, the shortcut to the main one.
   *
   * @param options the main half's icon, state, busy text, progress and shortcut, and the tone
   *     and size of both
   */
  public static El split(
      Variant variant,
      String text,
      Runnable onPress,
      Menu.State state,
      List<Menu.Item> items,
      Options options) {
    Theme theme = Theme.CURRENT.get();
    boolean off = options.state == State.DISABLED;
    boolean any = !off && items.stream().anyMatch(Menu.Item::isEnabled);
    float radius = radius(theme, options.size);
    El main = button(variant, text, onPress, options).key("main").radius(radius, 0, 0, radius);
    Look look = look(theme, variant, options.tone, any);
    Runnable toggle = any ? state::toggle : null;
    El more = frame(theme, look, options.size, Texts.CURRENT.get().moreOf(text), !any)
        .key("arrow")
        .w(height(theme, options.size) - theme.space().xs())
        .radius(0, radius, radius, 0)
        .child(Icon.icon(Select.OPENS).color(look.text()));
    if (variant == Variant.SECONDARY) { // the main half's right edge lies between the two
      more.border(
          theme.size().stroke(), theme.size().stroke(), theme.size().stroke(), 0, look.edge());
    }
    more = enable(theme, look, more, toggle);
    if (any) {
      more.expanded(state.isOpen());
      if (state.isOpen()) {
        held(theme, more, look);
      }
      more.onKey(Menu.opener(state, more.onKey(), toggle));
    }
    El whole = row().key("split").shrink(0).child(main);
    if (variant == Variant.PRIMARY) {
      whole.child(el().w(theme.size().stroke())
          .hFull()
          .fill(look(theme, variant, options.tone, true).pressed()));
    }
    whole.child(more);
    if (off) { // off, it has no menu, or one left open would still choose
      state.close();
      return whole;
    }
    return Menu.dropdown(whole, state, items);
  }

  /**
   * A button that opens a menu below it, with a chevron after its text, as Export with CSV and
   * JSON: unlike a {@link #split} button it has no action of its own. Enter, Space, Arrow Down and
   * Arrow Up open the menu with the first enabled item highlighted, Arrow Up with the last; while
   * it is open the button stays pressed in. In a tree dump it is {@code role=button} with {@code
   * expanded} or {@code expanded=false}.
   *
   * @param state whether the menu is open, which the button toggles
   * @param items the menu's items; with none enabled the button is off
   */
  public static El menu(Variant variant, String text, Menu.State state, List<Menu.Item> items) {
    return menu(variant, text, state, items, options());
  }

  /**
   * A menu button with its options. Busy or off, it opens nothing, and a menu that was open
   * closes.
   *
   * @param options its icon, state, busy text, progress, tone, size and shortcut
   */
  public static El menu(
      Variant variant, String text, Menu.State state, List<Menu.Item> items, Options options) {
    Theme theme = Theme.CURRENT.get();
    Runnable toggle = items.stream().anyMatch(Menu.Item::isEnabled) ? state::toggle : null;
    State shown = state(options, toggle);
    Look look = look(theme, variant, options.tone, shown != State.DISABLED);
    // The chevron takes the text's color with the danger tone; a status's or the accent's tone is
    // for the button's own icon.
    Tone chevron = options.tone == Tone.DANGER ? Tone.DANGER : Tone.NEUTRAL;
    El button = button(variant, text, toggle, options)
        .child(icon(theme, variant, chevron, shown, look, Select.OPENS));
    if (shown != State.READY) {
      state.close();
      return button;
    }
    button.expanded(state.isOpen());
    if (state.isOpen()) {
      held(theme, button, look);
    }
    return Menu.dropdown(button, state, items);
  }

  /**
   * A square secondary button of the regular size that shows {@code content}, such as a swatch,
   * without a label or a tooltip, so that a {@link Form#field} names it.
   *
   * @param onPress runs when the button is pressed; null disables the button
   */
  static El showing(El content, Runnable onPress) {
    Theme theme = Theme.CURRENT.get();
    Look look = look(theme, Variant.SECONDARY, Tone.NEUTRAL, onPress != null);
    El button = frame(theme, look, Size.REGULAR, null, onPress == null)
        .w(height(theme, Size.REGULAR))
        .child(content);
    return enable(theme, look, button, onPress);
  }

  /**
   * The menu entry of a button that a toolbar has no room for: its state, tone and shortcut as a
   * menu shows them.
   */
  static Menu.Item entry(String label, Icon icon, Runnable onPress, Options options) {
    Menu.Item entry = Menu.item(label, state(options, onPress) == State.READY ? onPress : null);
    if (icon != null) {
      entry = entry.icon(icon);
    }
    if (options.tone == Tone.DANGER) {
      entry = entry.danger();
    }
    return options.shortcut == null ? entry : entry.shortcut(Menu.shortcutText(options.shortcut));
  }

  /** A button's box: role, label, height, corners, fill, edge and shadow. */
  private static El frame(Theme theme, Look look, Size size, String label, boolean off) {
    return row()
        .role("button")
        .label(label)
        .disabled(off)
        // A press leaves the focus in the editor or grid the button acts on, also on one that is
        // off, which would else pass the press to an ancestor that takes the focus.
        .keepFocus()
        .h(height(theme, size))
        .shrink(0)
        .center()
        // Room for the content to move down while pressed, in the same box.
        .padding(theme.size().stroke(), 0)
        .radius(radius(theme, size))
        .fill(look.fill())
        .border(theme.size().stroke(), look.edge())
        .shadow(look.rest())
        .motion(theme.motion().feedback());
  }

  /** Focusable by the keyboard, with its ring apart from it. */
  private static El ring(Theme theme, Look look, El button) {
    return button
        .focusable()
        // The surface behind the button shows between it and its ring.
        .focusRing(look.ring())
        .focusRingGap(theme.size().ringGap());
  }

  /** Makes a button pressable, unless {@code onPress} is null. */
  static El enable(Theme theme, Look look, El button, Runnable onPress) {
    if (onPress == null) {
      return button;
    }
    return ring(theme, look, button)
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(look.hover()).shadow(look.lifted()))
        .whenPressed(e -> pressed(theme, e.fill(look.pressed())));
  }

  /**
   * A pressed button's shape: no shadow, its rounded corners tightened to the small radius, and
   * its content one stroke lower in the same box.
   */
  private static El pressed(Theme theme, El button) {
    float[] corners = button.radii();
    float small = theme.radius().small();
    float stroke = theme.size().stroke();
    button.style().paddingTop = Len.px(2 * stroke);
    button.style().paddingBottom = Len.ZERO;
    return button
        .shadow()
        .radius(
            Math.min(corners[0], small),
            Math.min(corners[1], small),
            Math.min(corners[2], small),
            Math.min(corners[3], small));
  }

  /**
   * A button that stays pressed in while its menu is open, also under the pointer, and shows no
   * tooltip over the menu.
   */
  static void held(Theme theme, El button, Look look) {
    Consumer<El> in = e -> pressed(theme, e.fill(look.pressed()));
    in.accept(button);
    button.whenHovered(in).whenHoveredFor(0, null).whenFocusVisibleFor(0, null);
  }

  static Look look(Theme theme, Variant variant, Tone tone, boolean enabled) {
    Theme.Surfaces s = theme.surface();
    List<Shadow> rest = theme.elevation().control();
    List<Shadow> lifted = theme.elevation().controlHover();
    List<Shadow> none = List.of();
    if (!enabled) {
      int text = theme.text().disabled();
      return switch (variant) {
        // A pale tint of its fill, so that it still reads as the main action.
        case PRIMARY -> {
          int fill =
              tone == Tone.DANGER ? theme.danger().disabled() : theme.accent().selection();
          yield new Look(fill, fill, fill, 0, text, 0, none, none);
        }
        case SECONDARY ->
          new Look(
              s.raised(), s.raised(), s.raised(), theme.border().control(), text, 0, none, none);
        case GHOST -> new Look(0, 0, 0, 0, text, 0, none, none);
      };
    }
    if (tone == Tone.DANGER) {
      Theme.Danger d = theme.danger();
      return switch (variant) {
        case PRIMARY ->
          new Look(d.color(), d.hover(), d.pressed(), 0, d.onColor(), d.ring(), rest, lifted);
        case SECONDARY ->
          new Look(
              s.raised(),
              over(s.raised(), s.hover()),
              over(s.raised(), s.pressed()),
              d.edge(),
              d.text(),
              d.ring(),
              rest,
              lifted);
        case GHOST -> new Look(0, s.hover(), s.pressed(), 0, d.text(), d.ring(), none, none);
      };
    }
    int ring = theme.accent().edge();
    return switch (variant) {
      case PRIMARY ->
        new Look(
            theme.accent().color(),
            theme.accent().hover(),
            theme.accent().pressed(),
            0,
            theme.text().onAccent(),
            ring,
            rest,
            lifted);
      case SECONDARY ->
        new Look(
            s.raised(),
            over(s.raised(), s.hover()),
            over(s.raised(), s.pressed()),
            theme.border().control(),
            theme.text().primary(),
            ring,
            rest,
            lifted);
      // The overlays are translucent, so a ghost button shows whatever it lies on.
      case GHOST ->
        new Look(0, s.hover(), s.pressed(), 0, theme.text().primary(), ring, none, none);
    };
  }
}
