package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    return tooltip(t, anchor, text, false);
  }

  /**
   * A tooltip with the whole text of an anchor whose text may be cut short ({@link El#ellipsis}):
   * as {@link #tooltip}, but it shows only while a text of the anchor ends in an ellipsis.
   */
  public static El whenShortened(Theme t, El anchor, String text) {
    return tooltip(t, anchor, text, true);
  }

  private static El tooltip(Theme t, El anchor, String text, boolean shortened) {
    Consumer<El> show = e -> show(t, e, text, shortened);
    return anchor
        .whenHoveredFor(t.motion().tooltipDelay(), show)
        .whenFocusVisibleFor(t.motion().tooltipDelay(), show);
  }

  /**
   * A hover-only tooltip whose text is read when the delay is due; null means none. A virtual
   * row can decide from its laid-out text then, without an overlay or a focus look on every row.
   */
  static El whenHovered(Theme t, El anchor, Supplier<String> text) {
    return anchor.whenHoveredFor(t.motion().tooltipDelay(), e -> {
      String tip = text.get();
      if (tip != null) {
        show(t, e, tip, false);
      }
    });
  }

  private static void show(Theme t, El anchor, String text, boolean shortened) {
    if (shortened) {
      if (anchor.overlays().stream()
          .noneMatch(o -> WHOLE.equals(o.key()) || "tooltip".equals(o.key()))) {
        anchor.overlay(panel(t, text).key(WHOLE).whenShortened());
      }
      return;
    }
    if (anchor.overlays().stream()
        .noneMatch(o -> "tooltip".equals(o.key()))) { // one for both looks
      // A tooltip the view gives stands in place of the one with the anchor's whole text.
      anchor.overlays().stream().filter(o -> WHOLE.equals(o.key())).forEach(o -> o.opacity(0));
      anchor.overlay(panel(t, text));
    }
  }

  private static final String WHOLE = "whole-text"; // the key of a cut text's tooltip

  /** The tooltip's overlay, for components that decide in their own look whether it shows. */
  static El panel(Theme t, String text) {
    return row()
        .key("tooltip")
        .role("tooltip")
        .label(text)
        .padding(t.space().xs(), t.space().s())
        .radius(t.radius().small())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .side(Side.BOTTOM, Style.Align.CENTER, t.space().xs())
        .child(label(t, text));
  }
}
