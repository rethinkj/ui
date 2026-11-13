package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.List;

/**
 * The bar along the bottom of a window that says how things stand: the connection, the caret's
 * place, the encoding. It lies on the window's own surface, as a title bar does, so it frames the
 * cards above it without being one. Its parts are quiet, in muted text: {@link #text} for a
 * text, {@link #item} for one with an icon that may be pressed, as the encoding that opens a menu
 * is, and {@link #status} for one with a coloured dot before it, as a connection's.
 *
 * <p>In a tree dump the bar is {@code role=status} with the label {@code Status bar}; a part
 * that can be pressed is {@code role=button} with its text as the label, and a {@link #status}
 * part says its state as its {@code value}, such as {@code value="connected"}.
 */
public final class StatusBar {

  private StatusBar() {}

  /** How the thing a {@link #status} part stands for is doing, shown as the colour of its dot. */
  public enum State {
    /** Up and usable: {@link Theme.Status#success}. */
    CONNECTED,
    /** On its way, or working: a {@link Progress#spinner} in place of the dot. */
    BUSY,
    /** Not in use: the disabled text's colour. */
    IDLE,
    /** Failed: {@link Theme.Status#danger}. */
    ERROR
  }

  /** The height of the bar, for what has to stay clear of it, as a {@link Toast}. */
  public static float height(Theme t) {
    return t.size().control() + t.space().s();
  }

  /**
   * The bar: {@code start} at the left, which gives way and is cut when the window is narrow, and
   * {@code end} at the right, which keeps its size. It is as wide as what it is in and {@link
   * Theme.Sizes#control} plus {@link Theme.Spacing#s} high.
   *
   * @param start the parts at the left, in the order shown; null parts are left out
   * @param end the parts at the right, in the order shown; null parts are left out
   */
  public static El statusBar(Theme t, List<El> start, List<El> end) {
    El left = row()
        .gap(t.space().xs())
        .align(Style.Align.CENTER)
        .grow(1)
        .shrink(1)
        .minSize(0, 0)
        .clip();
    for (El part : start) {
      if (part != null) {
        left.child(part);
      }
    }
    El bar = row()
        .role("status")
        .label("Status bar")
        .wFull()
        .shrink(0)
        .h(height(t))
        .padding(0, t.space().m())
        .gap(t.space().xs())
        .align(Style.Align.CENTER)
        .fill(t.surface().window())
        .child(left);
    for (El part : end) {
      if (part != null) {
        bar.child(part.shrink(0));
      }
    }
    return bar;
  }

  /** A text of the bar, such as "1,000,000 rows". */
  public static El text(Theme t, String text) {
    return part(t, null, text, null);
  }

  /**
   * A part with an icon before its text.
   *
   * @param icon before the text; null for none
   * @param onPress runs when the part is pressed, as a part that opens a menu or a dialog; null
   *     for a part that only shows
   */
  public static El item(Theme t, Symbol icon, String text, Runnable onPress) {
    return part(t, icon == null ? null : Icon.icon(t, icon, Icon.Variant.MUTED), text, onPress);
  }

  /**
   * A part with a dot in the colour of {@code state} before its text, such as a connection's
   * name.
   *
   * @param onPress runs when the part is pressed; null for a part that only shows
   */
  public static El status(Theme t, State state, String text, Runnable onPress) {
    float side = t.space().m();
    int colour = switch (state) {
      case CONNECTED -> t.status().success();
      case BUSY -> 0; // a spinner instead
      case IDLE -> t.text().disabled();
      case ERROR -> t.status().danger();
    };
    // In a box as wide as an icon, so that texts after dots and after icons line up; what is on
    // its way turns, as everything that waits does.
    El dot = state == State.BUSY
        ? Progress.spinner(t)
        : el().size(t.size().icon(), t.size().icon())
            .center()
            .shrink(0)
            .child(el().size(side, side).radius(side / 2).fill(colour));
    return part(t, dot, text, onPress).value(state.name().toLowerCase(java.util.Locale.ROOT));
  }

  private static El part(Theme t, El before, String text, Runnable onPress) {
    El part = row()
        .label(text)
        .h(t.size().control())
        .padding(0, t.space().s())
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .radius(t.radius().small())
        .minSize(0, 0);
    if (before != null) {
      part.child(before);
    }
    part.child(Label.label(t, Label.Variant.MUTED, text).ellipsis().minSize(0, 0));
    if (onPress == null) {
      return part;
    }
    return part.role("button")
        .focusable()
        .keepFocus() // a press leaves the focus in the editor or grid the bar is about
        .focusRing(t.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .motion(t.motion().feedback())
        .whenHovered(e -> e.fill(t.surface().hover()))
        .whenPressed(e -> e.fill(t.surface().pressed()));
  }
}
