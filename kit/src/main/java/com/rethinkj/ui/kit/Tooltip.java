package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;

/**
 * A short text that shows below an element, centred on it, once the pointer has rested on the
 * element for {@link Theme.Motion#tooltipDelay}, and goes when the pointer leaves or presses. It
 * needs no state: the app's clock carries the delay ({@link El#whenHoveredFor}), and an idle
 * window wakes for it without drawing frames meanwhile. Where there is no room below, it shows
 * above. In a tree dump it is an overlay of the element, {@code role=tooltip} with its text as the
 * label.
 */
public final class Tooltip {

  private Tooltip() {}

  /**
   * Gives {@code anchor} a tooltip, in place of any other {@link El#whenHoveredFor} look it has.
   *
   * @return the anchor
   */
  public static El tooltip(Theme t, El anchor, String text) {
    return anchor.whenHoveredFor(t.motion().tooltipDelay(), e -> e.overlay(
        row().key("tooltip").role("tooltip").label(text)
            .padding(t.space().xs(), t.space().s())
            .radius(t.radius().small())
            .fill(t.surface().raised())
            .border(t.size().stroke(), t.border().subtle())
            .shadow(t.elevation().popup())
            .side(Side.BOTTOM, Style.Align.CENTER, t.space().xs())
            .child(label(t, text))));
  }
}
