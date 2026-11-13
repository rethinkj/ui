package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.function.Consumer;

/**
 * A short text that shows below an element, centred on it, once the pointer has rested on the
 * element for {@link Theme.Motion#tooltipDelay}, and goes when the pointer leaves or presses; and
 * likewise once the keyboard has given the element the focus, with its ring, for as long, until
 * the focus leaves or a key goes down. It needs no state: the app's clock carries the delay
 * ({@link El#whenHoveredFor}, {@link El#whenFocusVisibleFor}), and an idle window wakes for it
 * without drawing frames meanwhile. Where there is no room below, it shows above, and where it
 * would cover the element, beside it. In a tree dump it is an overlay of the element, {@code
 * role=tooltip} with its text as the label.
 */
public final class Tooltip {

  private Tooltip() {}

  /**
   * Gives {@code anchor} a tooltip, in place of any other {@link El#whenHoveredFor} and {@link
   * El#whenFocusVisibleFor} look it has.
   *
   * @return the anchor
   */
  public static El tooltip(Theme t, El anchor, String text) {
    Consumer<El> show = e -> {
      if (e.overlays().stream().noneMatch(o -> "tooltip".equals(o.key()))) { // one for both looks
        e.overlay(panel(t, text));
      }
    };
    return anchor.whenHoveredFor(t.motion().tooltipDelay(), show)
        .whenFocusVisibleFor(t.motion().tooltipDelay(), show);
  }

  /** The tooltip's overlay, for components that decide in their own look whether it shows. */
  static El panel(Theme t, String text) {
    return row().key("tooltip").role("tooltip").label(text)
        .padding(t.space().xs(), t.space().s())
        .radius(t.radius().small())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().subtle())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .side(Side.BOTTOM, Style.Align.CENTER, t.space().xs())
        .child(label(t, text));
  }
}
