package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A button with text, an icon before its text, or an icon alone. A press runs its handler, and so
 * do Enter and Space while it is focused. Tab reaches it, but a press leaves the keyboard focus
 * where it is ({@link El#keepFocus}), so that the editor or grid a toolbar button acts on keeps it.
 * The text is in {@link Theme.Type#strong}, the medium weight.
 *
 * <p>What a button is beyond its variant, text and handler goes into {@link #options()}:
 *
 * <pre>{@code
 * Button.button(t, PRIMARY, "Run", s::run, Button.options().icon(Symbol.PLAY).busy(s.running))
 * Button.button(t, PRIMARY, "Drop table", s::drop, Button.options().tone(Button.Tone.DANGER))
 * Button.button(t, SECONDARY, "Save", s::save, Button.options().enabled(s.changed()))
 * }</pre>
 *
 * <p>A button is off when its handler is null or {@link Options#enabled} is false, both alike: it
 * takes no focus, shows no pointer cursor, draws in the disabled colours, a press on it goes to
 * its ancestors, and a tree dump says {@code disabled}. {@link El#disabled(boolean)} on the element
 * a button returns changes only the dump, not what it does.
 */
public final class Button {

  public enum Variant {
    /** The main action of a view, in the accent colour. */
    PRIMARY,
    /** Any other action, on a raised surface with an edge. */
    SECONDARY,
    /** A quiet action that shows its surface only under the pointer, as in toolbars. */
    GHOST
  }

  /** What a button's action does to what it acts on, which picks its colours with the variant. */
  public enum Tone {
    /** Any action, in the accent's and the surfaces' colours. */
    NEUTRAL,
    /**
     * An action that destroys something, as Drop table or Discard, in {@link Theme.Danger}'s
     * colours: a primary button filled red with white text, for the confirmation in a dialog; a
     * secondary one with red text and a red edge on the raised surface, beside other actions; a
     * ghost one with red text alone, in a toolbar or a row. Its focus ring is {@link
     * Theme.Danger#ring}. Off, it looks as any button that is off.
     */
    DANGER
  }

  /** The colours of a button: its fill at rest, hovered and pressed, its edge, text and ring. */
  private record Look(int fill, int hover, int pressed, int edge, int text, int ring) {}

  private Button() {}

  /** What a button is beyond its variant, text and handler; {@link #options()} is a plain one. */
  public static final class Options {
    private Symbol icon;
    private boolean busy;
    private boolean enabled = true;
    private Tone tone = Tone.NEUTRAL;

    private Options() {}

    /**
     * An icon before the text, in the text's colour; null for none. Not for {@link #icon(Theme,
     * Variant, Symbol, String, Runnable, Options)}, whose symbol is a parameter of its own.
     */
    public Options icon(Symbol icon) {
      this.icon = icon;
      return this;
    }

    /**
     * Whether its action runs. A busy button shows a {@link Progress#spinner} in the text's colour
     * in place of its icon, or, without one, over the middle of its text, which keeps its place
     * unseen so that the button keeps its width; an icon button shows it in place of its icon. It
     * takes no press, but keeps its colours, its place in the order of Tab and the focus, so that
     * nothing jumps when the work ends; Enter and Space on it do nothing and reach no other
     * handler. A tree dump says {@code busy}, WAI-ARIA's {@code aria-busy}, which a script selects
     * as {@code role=button busy}. A button that is off is not busy.
     */
    public Options busy(boolean busy) {
      this.busy = busy;
      return this;
    }

    /**
     * False turns the button off while the view keeps its handler, exactly as a null handler
     * does (see {@link Button}).
     */
    public Options enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Options tone(Tone tone) {
      this.tone = Objects.requireNonNull(tone, "tone");
      return this;
    }
  }

  public static Options options() {
    return new Options();
  }

  /**
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El button(Theme t, Variant variant, String text, Runnable onPress) {
    return button(t, variant, text, onPress, options());
  }

  /**
   * A button with an icon before its text, in the text's colour.
   *
   * @param icon before the text; null for none
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El button(Theme t, Variant variant, Symbol icon, String text, Runnable onPress) {
    return button(t, variant, text, onPress, options().icon(icon));
  }

  /**
   * A button that may be busy, as {@link Options#busy} says.
   *
   * @param icon before the text; null for none
   * @param busy whether its action runs
   * @param onPress runs when the button is pressed; null disables the button, busy or not
   */
  public static El button(
      Theme t, Variant variant, Symbol icon, String text, boolean busy, Runnable onPress) {
    return button(t, variant, text, onPress, options().icon(icon).busy(busy));
  }

  /**
   * A button with its options.
   *
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   * @param o its icon, whether it is busy or off, and its tone
   */
  public static El button(Theme t, Variant variant, String text, Runnable onPress, Options o) {
    Runnable press = o.enabled ? onPress : null;
    Look look = look(t, variant, o.tone, press != null);
    El button =
        frame(t, look, text, press).padding(0, t.space().l()).gap(t.space().s());
    Theme.TextStyle style = t.type().strong();
    El label = El.text(text).font(style.font().get()).fontSize(style.size()).color(look.text());
    if (o.busy && press != null) {
      El spinner = spinner(t, variant, look);
      if (o.icon != null) { // in the icon's place
        button.child(spinner).child(label);
      } else { // over the middle of the text, which keeps its place unseen: the width stays
        // Out of the flow and without a place of its own, it lies where the button centres its
        // content, whatever size the view gives the button.
        spinner.style().position = Style.Position.ABSOLUTE;
        button.child(label.opacity(0)).child(spinner);
      }
      return busy(t, variant, look, button);
    }
    if (o.icon != null) {
      button.child(Icon.icon(t, o.icon).color(look.text()));
    }
    return enable(t, variant, look, button.child(label), press);
  }

  /**
   * A square button with an icon alone, such as a toolbar's or a card's header's. The label names
   * it in a tree dump and in a tooltip that shows after the theme's delay under the pointer or the
   * keyboard's focus.
   *
   * @param label what the button does, such as "Add connection"
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El icon(Theme t, Variant variant, Symbol icon, String label, Runnable onPress) {
    return icon(t, variant, icon, label, onPress, options());
  }

  /**
   * A square button with an icon alone and its options: busy, a spinner stands in place of the
   * icon; with {@link Tone#DANGER} the icon has the tone's colour, else it is muted unless the
   * button is primary.
   *
   * @param label what the button does, such as "Add connection"
   * @param onPress runs when the button is pressed; null disables the button
   * @param o whether it is busy or off, and its tone; no icon, which is {@code icon}
   */
  public static El icon(
      Theme t, Variant variant, Symbol icon, String label, Runnable onPress, Options o) {
    if (o.icon != null) {
      throw new IllegalArgumentException(
          "an icon button's symbol is its parameter, not an option: " + o.icon);
    }
    Runnable press = o.enabled ? onPress : null;
    Look look = look(t, variant, o.tone, press != null);
    El button = frame(t, look, label, press).w(t.size().control());
    if (o.busy && press != null) {
      return Tooltip.tooltip(
          t, busy(t, variant, look, button.child(spinner(t, variant, look))), label);
    }
    button.child(Icon.icon(t, icon)
        .color(
            variant == Variant.PRIMARY || press == null || o.tone == Tone.DANGER
                ? look.text()
                : t.text().muted()));
    return Tooltip.tooltip(t, enable(t, variant, look, button, press), label);
  }

  /** The spinner of a busy button, in its text's colour. */
  private static El spinner(Theme t, Variant variant, Look look) {
    return Progress.spinner(
        t,
        t.size().icon(),
        look.text(),
        variant == Variant.PRIMARY ? look.pressed() : t.border().track());
  }

  /** Makes a button busy: focusable, but Enter, Space and the pointer press nothing. */
  private static El busy(Theme t, Variant variant, Look look, El button) {
    return button
        .busy(true)
        // Enter and Space do nothing, and go no further: to a dialog's default button, say.
        .onKey(k -> k.modifiers() == 0 && (k.name().equals("Enter") || k.name().equals("Space")))
        .focusable()
        .keepFocus()
        .focusRing(look.ring())
        .focusRingGap(variant == Variant.PRIMARY ? t.size().ringGap() : 0);
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
   * success colour and a tooltip that says Copied, for {@code motion().confirm()} seconds of the
   * app's clock; null for no confirmation.
   */
  public static El copy(Theme t, Clipboard clipboard, String text, String label, Copied copied) {
    boolean done = copied != null && copied.shows(label, text);
    String what = label + '\n' + text;
    Look look = look(t, Variant.GHOST, Tone.NEUTRAL, true);
    Runnable press = () -> {
      clipboard.write(text);
      if (copied != null) {
        copied.what = what;
      }
    };
    El button = enable(
        t,
        Variant.GHOST,
        look,
        frame(t, look, label, press)
            .w(t.size().control())
            .child(Icon.icon(
                t,
                done ? Symbol.CHECKMARK : Symbol.COPY,
                done ? Icon.Variant.SUCCESS : Icon.Variant.MUTED)),
        press);
    if (!done) {
      return Tooltip.tooltip(t, button, label);
    }
    // The confirmation is an overlay, whose time on the app's clock ends it.
    return button.overlay(Tooltip.panel(t, "Copied")
        .key("copied")
        .keepFocus()
        .onShownFor(t.motion().confirm(), () -> {
          if (what.equals(copied.what)) {
            copied.what = null;
          }
        }));
  }

  /**
   * An overlay that shows nothing and ends a copy's confirmation after its time, for copy buttons
   * in the rows of a list, whose overlays keep no time; null while nothing is confirmed.
   */
  static El ending(Theme t, Copied copied) {
    String what = copied.what;
    return what == null
        ? null
        : el().key("copied-" + what.hashCode())
            .keepFocus()
            .onShownFor(t.motion().confirm(), () -> {
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
  public static El toggleIcon(
      Theme t, Symbol icon, String label, boolean on, Consumer<Boolean> onToggle) {
    El button = icon(
            t, Variant.GHOST, icon, label, onToggle == null ? null : () -> onToggle.accept(!on))
        .checked(on);
    return on ? pressedIn(t, button, onToggle != null) : button;
  }

  /**
   * A toggle button with an icon, if any, and text, which looks as {@link #toggleIcon} does.
   *
   * @param icon before the text; null for none
   * @param onToggle told the new state; null disables the button
   */
  public static El toggle(
      Theme t, Symbol icon, String text, boolean on, Consumer<Boolean> onToggle) {
    El button = button(
            t, Variant.GHOST, icon, text, onToggle == null ? null : () -> onToggle.accept(!on))
        .checked(on);
    return on ? pressedIn(t, button, onToggle != null) : button;
  }

  /** A toggle button that is on: the tint of a selection, its icon in the accent's edge. */
  private static El pressedIn(Theme t, El button, boolean enabled) {
    int fill = t.grid().selected();
    button.fill(fill);
    for (El child : button.children()) {
      if (child.text() != null && child.label() != null) { // the icon, which has its name
        child.color(enabled ? t.accent().edge() : t.text().disabled());
      }
    }
    return enabled
        ? button
            .whenHovered(e -> e.fill(over(fill, t.surface().hover())))
            .whenPressed(e -> e.fill(over(fill, t.surface().pressed())))
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
      Theme t,
      Variant variant,
      Symbol icon,
      String text,
      Runnable onPress,
      Menu.State state,
      List<Menu.Item> items) {
    return split(t, variant, text, onPress, state, items, options().icon(icon));
  }

  /**
   * A split button with its options. Busy, the main half is busy as a {@link #button} is, and the
   * chevron still opens the menu, whose items the app enables as it sees fit (a Cancel, say). Off
   * ({@link Options#enabled}), both halves are off and there is no menu: one that was open closes.
   * The tone colours both halves.
   *
   * @param o the main half's icon, whether it is busy, whether the split button is off, its tone
   */
  public static El split(
      Theme t,
      Variant variant,
      String text,
      Runnable onPress,
      Menu.State state,
      List<Menu.Item> items,
      Options o) {
    boolean any = o.enabled && items.stream().anyMatch(Menu.Item::isEnabled);
    El main = button(t, variant, text, onPress, o)
        .key("main")
        .radius(t.radius().medium(), 0, 0, t.radius().medium());
    Look look = look(t, variant, o.tone, any);
    Runnable toggle = any ? state::toggle : null;
    El more = frame(t, look, "More " + text, toggle)
        .key("arrow")
        .w(t.size().control() - t.space().xs())
        .radius(0, t.radius().medium(), t.radius().medium(), 0)
        .child(Icon.icon(t, Select.OPENS).color(look.text()));
    if (variant == Variant.SECONDARY) { // the main half's right edge lies between the two
      more.border(t.size().stroke(), t.size().stroke(), t.size().stroke(), 0, look.edge());
    }
    more = enable(t, variant, look, more, toggle);
    if (any) {
      more.expanded(state.isOpen());
      if (state.isOpen()) {
        more.fill(look.pressed());
      }
      more.onKey(Menu.opener(state, more.onKey(), toggle));
    }
    El whole = row().key("split").shrink(0).child(main);
    if (variant == Variant.PRIMARY) {
      whole.child(
          el().w(t.size().stroke()).hFull().fill(look(t, variant, o.tone, true).pressed()));
    }
    whole.child(more);
    if (!o.enabled) { // off, it has no menu, or one left open would still choose
      state.close();
      return whole;
    }
    return Menu.dropdown(t, whole, state, items);
  }

  /** A button's box: role, label, height, corners, fill and edge. */
  private static El frame(Theme t, Look look, String label, Runnable onPress) {
    return row()
        .role("button")
        .label(label)
        .disabled(onPress == null)
        .h(t.size().control())
        .shrink(0)
        .center()
        .radius(t.radius().medium())
        .fill(look.fill())
        .border(t.size().stroke(), look.edge())
        .motion(t.motion().feedback());
  }

  /** Makes a button pressable, unless {@code onPress} is null. */
  private static El enable(Theme t, Variant variant, Look look, El button, Runnable onPress) {
    if (onPress == null) {
      return button;
    }
    return button
        .focusable()
        .keepFocus() // a press leaves the focus in the editor or grid the button acts on
        .focusRing(look.ring())
        // Around the accent's fill, the surface behind the button shows between fill and ring.
        .focusRingGap(variant == Variant.PRIMARY ? t.size().ringGap() : 0)
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(look.hover()))
        .whenPressed(e -> e.fill(look.pressed()));
  }

  private static Look look(Theme t, Variant variant, Tone tone, boolean enabled) {
    Theme.Surfaces s = t.surface();
    if (!enabled) {
      return variant == Variant.GHOST
          ? new Look(0, 0, 0, 0, t.text().disabled(), 0)
          : new Look(
              s.raised(), s.raised(), s.raised(), t.border().control(), t.text().disabled(), 0);
    }
    if (tone == Tone.DANGER) {
      Theme.Danger d = t.danger();
      return switch (variant) {
        case PRIMARY -> new Look(d.color(), d.hover(), d.pressed(), 0, d.onColor(), d.ring());
        case SECONDARY ->
          new Look(
              s.raised(),
              over(s.raised(), s.hover()),
              over(s.raised(), s.pressed()),
              d.edge(),
              d.text(),
              d.ring());
        case GHOST -> new Look(0, s.hover(), s.pressed(), 0, d.text(), d.ring());
      };
    }
    int ring = t.accent().edge();
    return switch (variant) {
      case PRIMARY ->
        new Look(
            t.accent().color(),
            t.accent().hover(),
            t.accent().pressed(),
            0,
            t.text().onAccent(),
            ring);
      case SECONDARY ->
        new Look(
            s.raised(),
            over(s.raised(), s.hover()),
            over(s.raised(), s.pressed()),
            t.border().control(),
            t.text().primary(),
            ring);
      // The overlays are translucent, so a ghost button shows whatever it lies on.
      case GHOST -> new Look(0, s.hover(), s.pressed(), 0, t.text().primary(), ring);
    };
  }
}
