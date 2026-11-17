package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Locale;

/**
 * The bar along the bottom of a window that says how things stand: the connection, the caret's
 * place, the encoding. It is a band in the cards' color with a hairline along its top, as a card's
 * footer is, and its parts are quiet, in muted text: {@link #text} for a text, {@link #item} for
 * one with an icon that may be pressed or tinted, and {@link #status} for one with a mark of its
 * state before it, as a connection's. {@link #group} keeps parts that belong together; a short
 * upright hairline stands between the parts and groups of each end. {@link #menu} makes a part
 * open a menu, with a chevron after its text, and a message has the room between the ends.
 *
 * <p>In a tree dump the bar is {@code role=status} with the label {@code Status bar}; a group is
 * {@code role=group} with its label, a hairline {@code role=separator}; a part that can be
 * pressed is {@code role=button} with its text as the label, one that opens a menu says {@code
 * expanded} or {@code expanded=false}, and a {@link #status} part says its state as its {@code value},
 * such as {@code value="ok"}.
 */
public final class StatusBar {

  private StatusBar() {}

  /** How the thing a {@link #status} part stands for is doing, shown by the mark before it. */
  public enum State {
    /** Up and usable: a dot in {@link Theme.Status#success}. */
    OK,
    /** On its way, or working: a {@link Progress#spinner}. */
    BUSY,
    /** Not in use: a ring in the disabled text's color. */
    IDLE,
    /** Usable, but needs care: a warning sign, the part tinted as a warning {@link Badge}. */
    WARNING,
    /** Failed: an error sign, the part tinted as a danger {@link Badge}. */
    ERROR
  }

  /** The height of the bar, its hairline included, for what has to stay clear of it. */
  public static float height() {
    Theme theme = Theme.CURRENT.get();
    return theme.size().control() + theme.space().s() + theme.size().stroke();
  }

  /** The bar without a message: {@link #statusBar(List, El, List)} with none. */
  public static El statusBar(List<El> start, List<El> end) {
    return statusBar(start, null, end);
  }

  /**
   * The bar: {@code start} at the left, then the message in the room that is left, and {@code
   * end} at the right, which keeps its size. When the window is narrow the message is cut first,
   * then the start. It is as wide as what it is in and {@link #height} high.
   *
   * @param start the parts and groups at the left, in the order shown; null ones are left out
   * @param message a part that says what just happened, such as {@code text("Saved")}; null for
   *     none
   * @param end the parts and groups at the right, in the order shown; null ones are left out
   */
  public static El statusBar(List<El> start, El message, List<El> end) {
    Theme theme = Theme.CURRENT.get();
    El left = end(theme, start).shrink(1).minSize(0, 0).clip();
    // Grows from nothing into the room the ends leave, so it never pushes them, and is cut first.
    El room = row()
        .grow(1)
        .basis(0)
        .minSize(0, 0)
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .clip();
    if (message != null) {
      if (!left.children().isEmpty()) {
        room.child(separator(theme));
      }
      room.child(message.shrink(1).minSize(0, 0));
    }
    return row()
        .role("status")
        .label("Status bar")
        .wFull()
        .shrink(0)
        .h(height())
        .padding(0, theme.space().m())
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .fill(theme.surface().panel())
        .border(theme.size().stroke(), 0, 0, 0, theme.border().divider())
        .children(left, room, end(theme, end).shrink(0));
  }

  /**
   * Parts that belong together, such as a connection and the server's version, with no hairline
   * between them.
   *
   * @param label what they are, the group's label in a tree dump
   * @param parts in the order shown; null parts are left out, and a group of none is not shown
   */
  public static El group(String label, El... parts) {
    Theme theme = Theme.CURRENT.get();
    El group = row()
        .role("group")
        .label(label)
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .minSize(0, 0);
    for (El part : parts) {
      if (part != null) {
        group.child(part);
      }
    }
    return group;
  }

  /** A text of the bar, such as "1,000,000 rows". */
  public static El text(String text) {
    Theme theme = Theme.CURRENT.get();
    return part(theme, null, null, text, null);
  }

  /**
   * A part with an icon before its text.
   *
   * @param icon before the text; null for none
   * @param onPress runs when the part is pressed, as a part that opens a dialog; null for a part
   *     that only shows
   */
  public static El item(Symbol icon, String text, Runnable onPress) {
    return item(null, icon, text, onPress);
  }

  /**
   * A part tinted as a {@link Badge} of {@code tone}, which stands out of the quiet bar, such as
   * the changes not saved in the accent's tone or the warnings in the warning's.
   *
   * @param tone whose quiet tint and text it takes; null for the bar's muted text
   * @param icon before the text; null for none
   * @param onPress runs when the part is pressed; null for a part that only shows
   */
  public static El item(Badge.Kind tone, Symbol icon, String text, Runnable onPress) {
    Theme theme = Theme.CURRENT.get();
    Theme.Tone t = tone(theme, tone);
    El before = icon == null ? null : Icon.icon(icon, Icon.Variant.MUTED);
    if (before != null && t != null) {
      before.color(t.softText());
    }
    return part(theme, t, before, text, onPress);
  }

  /**
   * A part with the mark of {@code state} before its text, such as a connection's name: a dot, a
   * ring, a spinner or a sign; a warning and an error tint the part too.
   *
   * @param onPress runs when the part is pressed; null for a part that only shows
   */
  public static El status(State state, String text, Runnable onPress) {
    Theme theme = Theme.CURRENT.get();
    Theme.Tone tone = switch (state) {
      case WARNING -> theme.badge().warning();
      case ERROR -> theme.badge().danger();
      default -> null;
    };
    float icon = theme.size().icon();
    float dot = theme.space().m();
    // In a box as wide as an icon, so that texts after marks and after icons line up.
    El mark = switch (state) {
      case OK ->
        box(theme)
            .child(el().size(
                    dot + 2 * theme.space().xs(), dot + 2 * theme.space().xs())
                .radius(dot)
                .center()
                .fill(Colors.alpha(theme.status().success(), 0.12f))
                .child(el().size(dot, dot).radius(dot / 2).fill(theme.status().success())));
      case BUSY -> Progress.spinner(icon); // what is on its way turns, as everything that waits
      case IDLE ->
        box(theme)
            .child(el().size(dot, dot)
                .radius(dot / 2)
                .border(theme.size().indicator(), theme.text().disabled()));
      case WARNING -> Icon.icon(Symbol.WARNING, Icon.Variant.MUTED).color(tone.softText());
      case ERROR -> Icon.icon(Symbol.ERROR_CIRCLE, Icon.Variant.MUTED).color(tone.softText());
    };
    return part(theme, tone, mark, text, onPress).value(state.name().toLowerCase(Locale.ROOT));
  }

  /**
   * Makes {@code part} open a menu, above it at the window's bottom: a chevron after its text, and
   * whether the menu is open in a tree dump. A press on the part, by the pointer or by Enter,
   * Space or the arrows, toggles the menu through {@code part}'s own handler, so give it one that
   * does, as {@code state::toggle}.
   *
   * <pre>{@code
   * StatusBar.menu(StatusBar.item(null, "UTF-8", s.encoding::toggle), s.encoding, encodings)
   * }</pre>
   *
   * @return the part
   */
  public static El menu(El part, Menu.State state, List<Menu.Item> items) {
    Theme theme = Theme.CURRENT.get();
    part.child(Icon.icon(Select.OPENS, Icon.Variant.MUTED, theme.type().small().size())
            .shrink(0))
        .expanded(state.isOpen());
    return Menu.dropdown(part, state, items);
  }

  private static Theme.Tone tone(Theme theme, Badge.Kind kind) {
    return kind == null
        ? null
        : switch (kind) {
          case NEUTRAL -> theme.badge().neutral();
          case ACCENT -> theme.badge().accent();
          case SUCCESS -> theme.badge().success();
          case WARNING -> theme.badge().warning();
          case DANGER -> theme.badge().danger();
        };
  }

  private static El box(Theme theme) {
    return el().size(theme.size().icon(), theme.size().icon()).center().shrink(0);
  }

  /** One end of the bar: its parts and groups with a hairline between each two. */
  private static El end(Theme theme, List<El> entries) {
    El end = row().gap(theme.space().xs()).align(Style.Align.CENTER);
    for (El entry : entries) {
      if (entry == null || "group".equals(entry.role()) && entry.children().isEmpty()) {
        continue;
      }
      if (!end.children().isEmpty()) {
        end.child(separator(theme));
      }
      end.child(entry);
    }
    return end;
  }

  private static El separator(Theme theme) {
    return Divider.vertical()
        .h(theme.size().control() - 2 * theme.space().s())
        .alignSelf(Style.Align.CENTER)
        .margin(0, theme.space().xs(), 0, theme.space().xs());
  }

  private static El part(Theme theme, Theme.Tone tone, El before, String text, Runnable onPress) {
    El label = Label.label(Label.Variant.SMALL, text).ellipsis().minSize(0, 0);
    El part = row()
        .label(text)
        .h(theme.size().control())
        .padding(0, theme.space().s())
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .radius(theme.radius().small())
        .minSize(0, 0);
    if (tone != null) {
      part.fill(tone.soft());
      label.color(tone.softText());
    }
    if (before != null) {
      part.child(before.shrink(0));
    }
    part.child(label);
    // The whole text in a tooltip while it is cut short.
    Tooltip.whenShortened(part, text);
    if (onPress == null) {
      return part;
    }
    int hover = tone == null ? theme.surface().hover() : Badge.deeper(tone.soft(), Badge.HOVER);
    int pressed =
        tone == null ? theme.surface().pressed() : Badge.deeper(tone.soft(), Badge.PRESSED);
    return part.role("button")
        .focusable()
        .keepFocus() // a press leaves the focus in the editor or grid the bar is about
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .motion(theme.motion().feedback())
        .whenHovered(e -> e.fill(hover))
        .whenPressed(e -> e.fill(pressed));
  }
}
