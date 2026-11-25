package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.List;

/**
 * A panel with any content beside an anchor, as a menu without items: a filter's editor at a
 * column, the details of a connection at the status bar, a small form. Whether it is open is the
 * app's, as for a {@link Menu.State}: the anchor's handler toggles a {@link State}, and the view
 * attaches the panel while it is open:
 *
 * <pre>{@code
 * El filter = Button.button(SECONDARY, Symbol.FILTER, "Filter", state.filter::toggle);
 * Popover.popover(filter, state.filter, "Filter", content)
 * }</pre>
 *
 * <p>As it opens, the focus goes to its first {@link El#autofocus} or focusable element, or to
 * the popover itself where it has none, as one that only shows information; when it closes, the
 * focus goes back to where it was, such as the button that opened it, unless Tab took it elsewhere
 * (see the focus rules of overlays in core). A press outside it, Escape and Tab or Shift+Tab out
 * of it close it; Tab walks it right after its anchor. It has the look of a menu: the popup
 * surface, the control border, the medium radius, {@code elevation().popup()} and the fade. It has
 * no arrow to its anchor, since the core draws no triangles. Use {@link #flush} when dividers or
 * section backgrounds need to reach the panel's edges without inner padding. In a tree dump the
 * anchor is {@code expanded} while open and {@code expanded=false} while closed, and the panel is
 * an overlay {@code bottom start} on its anchor, {@code role=dialog} with its label.
 */
public final class Popover {

  private Popover() {}

  /** Whether a popover is open, which the app keeps in its state. */
  public static final class State {
    private boolean open;

    public boolean isOpen() {
      return open;
    }

    public void open() {
      open = true;
    }

    public void close() {
      open = false;
    }

    /** Opens it if it is closed and closes it if it is open, for the button that opens it. */
    public void toggle() {
      open = !open;
    }
  }

  /**
   * Attaches {@code content} in a popover below {@code anchor}, at its start, while {@code state}
   * is open; where there is no room below, above it, and where there is none at its start, in the
   * window or the dialog it is in, at its end, if that fits, else moved into them (see {@link
   * El#side}).
   *
   * @param label what the popover is, its label in a tree dump
   * @return the anchor
   */
  public static El popover(El anchor, State state, String label, El content) {
    return popover(anchor, state, Side.BOTTOM, Style.Align.START, label, content);
  }

  /**
   * Attaches {@code content} in a popover on a side of {@code anchor}, aligned along it, while
   * {@code state} is open, and marks {@code anchor} {@code expanded} while open and {@code
   * expanded=false} while closed. The side and the alignment are what it prefers: it moves where
   * it does not fit, as {@link El#side} says.
   *
   * @return the anchor
   */
  public static El popover(
      El anchor, State state, Side side, Style.Align align, String label, El content) {
    return attach(anchor, state, side, align, label, content, false);
  }

  /**
   * Attaches {@code content} in a popover on a side of {@code anchor}, aligned along it, without
   * inner padding, so dividers and section backgrounds reach the panel's edges. The content fills
   * the panel up to its border, which lies over it, and is clipped to its rounded corners; an
   * element that takes the focus right at the edge has its focus ring inside its box, as in a
   * scroll area, so give it room of its own where the ring should lie outside. Placement, look, focus rules
   * and {@code expanded} / {@code expanded=false} on {@code anchor} are the same as {@link
   * #popover(El, State, Side, Style.Align, String, El)}.
   *
   * @return the anchor
   */
  public static El flush(
      El anchor, State state, Side side, Style.Align align, String label, El content) {
    return attach(anchor, state, side, align, label, content, true);
  }

  private static El attach(
      El anchor,
      State state,
      Side side,
      Style.Align align,
      String label,
      El content,
      boolean flush) {
    Theme theme = Theme.CURRENT.get();
    return anchor
        .expanded(state.isOpen())
        .overlay(
            state.isOpen()
                ? panel(state, label, content, flush)
                    .side(side, align, theme.space().xs())
                : null);
  }

  /**
   * A popover that is a small form: the title as a heading, the content and the buttons at the
   * bottom right, as a {@link Dialog} has them, below {@code anchor}. Where the window is too low
   * for it, the content scrolls between the heading and the buttons, as a dialog's does.
   *
   * @param title shown at the top, also the label
   * @param buttons the actions, in the order shown, the main one usually last
   * @return the anchor
   */
  public static El form(El anchor, State state, String title, El content, List<El> buttons) {
    Theme theme = Theme.CURRENT.get();
    return popover(
        anchor,
        state,
        title,
        col()
            .gap(theme.space().l())
            .minSize(0, 0)
            .children(
                label(Label.Variant.STRONG, title).role("heading").level(3).shrink(0),
                Dialog.scrolling(theme, content), // scrolls where the window is too low for it
                row()
                    .gap(theme.space().m())
                    .shrink(0)
                    .justify(Style.Content.END)
                    .children(buttons.toArray(El[]::new))));
  }

  /**
   * The popover's panel, an overlay to attach to an element with {@link El#overlay} and place, as
   * {@link #popover} does; a press outside it, Escape and Tab out of it call {@link State#close}. Where nothing
   * in the content takes the focus ({@link El#hasFocusable}: disabled, invisible and fields
   * without an edit handler do not), the panel takes it, so that Escape reaches it; where Tab
   * reaches something in it, a press on its blank space focuses the panel, so Escape closes it
   * then too.
   */
  public static El panel(State state, String label, El content) {
    return panel(state, label, content, false);
  }

  private static El panel(State state, String label, El content, boolean flush) {
    Theme theme = Theme.CURRENT.get();
    El panel = Popup.frame(theme, col())
        .key("popover")
        .role("dialog")
        .label(label)
        .onDismiss(state::close)
        .onKey(k -> {
          if (!k.name().equals("Escape") || k.modifiers() != 0) {
            return false;
          }
          state.close();
          return true;
        });
    if (flush) {
      // the border lies over the content, so that what reaches the edge meets it at any scale
      panel
          .border(0, 0)
          .clip()
          .child(content)
          .child(El.el()
              .absolute(0, 0)
              .wFull()
              .hFull()
              .radius(theme.radius().medium())
              .border(theme.size().stroke(), theme.border().control())
              .inert());
    } else {
      panel.padding(theme.space().l()).child(content);
    }
    if (content.hasTabStop()) {
      // a press on its blank space keeps the focus in it, so Escape still closes it
      panel.focusable().skipTab();
    } else if (!content.hasFocusable()) {
      panel.focusable().focusRing(theme.accent().edge());
    }
    return panel;
  }
}
