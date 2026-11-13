package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Measure;
import java.util.List;
import java.util.function.Consumer;

/**
 * A button with text, an icon before its text, or an icon alone. A press runs its handler, and so
 * do Enter and Space while it is focused. Tab reaches it, but a press leaves the keyboard focus
 * where it is ({@link El#keepFocus}), so that the editor or grid a toolbar button acts on keeps it.
 * The text is in {@link Theme.Type#strong}, the medium weight.
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

  /** The colours of a button: its fill at rest, hovered and pressed, its edge and its text. */
  private record Look(int fill, int hover, int pressed, int edge, int text) {}

  private Button() {}

  /**
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El button(Theme t, Variant variant, String text, Runnable onPress) {
    return button(t, variant, null, text, onPress);
  }

  /**
   * A button with an icon before its text, in the text's colour.
   *
   * @param icon before the text; null for none
   * @param text what the button says, also its label
   * @param onPress runs when the button is pressed; null disables the button
   */
  public static El button(Theme t, Variant variant, Symbol icon, String text, Runnable onPress) {
    return button(t, variant, icon, text, false, onPress);
  }

  /**
   * A button that may be busy: while its action runs, a {@link Progress#spinner} in the text's
   * colour stands in place of its icon, or, on a button without one, over the middle of its text,
   * which keeps its place unseen so that the button keeps its width; and it takes no press, but
   * keeps its colours, its place in the order of Tab and the focus, so that nothing jumps when the
   * work ends. In a tree dump it is then {@code busy}, WAI-ARIA's {@code aria-busy}, which a script
   * selects as {@code role=button busy}.
   *
   * @param icon before the text; null for none
   * @param busy whether its action runs
   * @param onPress runs when the button is pressed; null disables the button, busy or not
   */
  public static El button(
      Theme t, Variant variant, Symbol icon, String text, boolean busy, Runnable onPress) {
    Look look = look(t, variant, onPress != null);
    El button = frame(t, variant, look, text, onPress)
        .padding(0, t.space().l())
        .gap(t.space().s());
    if (busy && onPress != null) {
      El spinner = Progress.spinner(
          t,
          t.size().icon(),
          look.text(),
          variant == Variant.PRIMARY ? t.accent().pressed() : t.border().track());
      Theme.TextStyle style = t.type().strong();
      El label = El.text(text).font(style.font().get()).fontSize(style.size()).color(look.text());
      if (icon != null) { // in the icon's place
        button.child(spinner).child(label);
      } else { // over the text's middle, which keeps its place unseen: the width stays
        float width = label
            .text()
            .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT)
            .width();
        float size = t.size().icon();
        button
            .child(label.opacity(0))
            .child(spinner.absolute(
                Math.round(t.space().l() + (width - size) / 2),
                Math.round((t.size().control() - size) / 2) - t.size().stroke()));
      }
      return button
          .busy(true)
          .focusable()
          .keepFocus()
          .focusRing(t.accent().edge())
          .focusRingGap(variant == Variant.PRIMARY ? t.size().ringGap() : 0);
    }
    if (icon != null) {
      button.child(Icon.icon(t, icon).color(look.text()));
    }
    Theme.TextStyle style = t.type().strong();
    button.child(El.text(text).font(style.font().get()).fontSize(style.size()).color(look.text()));
    return enable(t, variant, look, button, onPress);
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
    Look look = look(t, variant, onPress != null);
    El button = frame(t, variant, look, label, onPress)
        .w(t.size().control())
        .child(Icon.icon(t, icon)
            .color(
                variant == Variant.PRIMARY || onPress == null
                    ? look.text()
                    : t.text().muted()));
    return Tooltip.tooltip(t, enable(t, variant, look, button, onPress), label);
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
    Look look = look(t, Variant.GHOST, true);
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
        frame(t, Variant.GHOST, look, label, press)
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
   * opens below the whole.
   *
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
    boolean any = items.stream().anyMatch(Menu.Item::isEnabled);
    El main = button(t, variant, icon, text, onPress)
        .key("main")
        .radius(t.radius().medium(), 0, 0, t.radius().medium());
    Look look = look(t, variant, any);
    El more = frame(t, variant, look, "More " + text, any ? state::toggle : null)
        .key("arrow")
        .w(t.size().control() - t.space().xs())
        .radius(0, t.radius().medium(), t.radius().medium(), 0)
        .child(Icon.icon(t, Select.OPENS).color(look.text()));
    if (variant == Variant.SECONDARY) { // the main half's right edge lies between the two
      more.border(t.size().stroke(), t.size().stroke(), t.size().stroke(), 0, look.edge());
    }
    more = enable(t, variant, look, more, any ? state::toggle : null);
    El whole = row().key("split").shrink(0).child(main);
    if (variant == Variant.PRIMARY) {
      whole.child(el().w(t.size().stroke()).hFull().fill(t.accent().pressed()));
    }
    whole.child(more);
    return Menu.dropdown(t, whole, state, items);
  }

  /** A button's box: role, label, height, corners, fill and edge. */
  private static El frame(Theme t, Variant variant, Look look, String label, Runnable onPress) {
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
        .focusRing(t.accent().edge())
        // Around the accent's fill, the surface behind the button shows between fill and ring.
        .focusRingGap(variant == Variant.PRIMARY ? t.size().ringGap() : 0)
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(look.hover()))
        .whenPressed(e -> e.fill(look.pressed()));
  }

  private static Look look(Theme t, Variant variant, boolean enabled) {
    Theme.Surfaces s = t.surface();
    if (!enabled) {
      return variant == Variant.GHOST
          ? new Look(0, 0, 0, 0, t.text().disabled())
          : new Look(
              s.raised(), s.raised(), s.raised(), t.border().control(), t.text().disabled());
    }
    return switch (variant) {
      case PRIMARY ->
        new Look(
            t.accent().color(),
            t.accent().hover(),
            t.accent().pressed(),
            0,
            t.text().onAccent());
      case SECONDARY ->
        new Look(
            s.raised(),
            over(s.raised(), s.hover()),
            over(s.raised(), s.pressed()),
            t.border().control(),
            t.text().primary());
      // The overlays are translucent, so a ghost button shows whatever it lies on.
      case GHOST -> new Look(0, s.hover(), s.pressed(), 0, t.text().primary());
    };
  }
}
