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
 * El filter = Button.button(t, SECONDARY, Symbol.FILTER, "Filter", s.filter::toggle);
 * Popover.popover(t, filter, s.filter, "Filter", content)
 * }</pre>
 *
 * <p>As it opens, the focus goes to its first {@link El#autofocus} or focusable element, or to
 * the popover itself where it has none, as one that only shows information; when it closes, the focus goes back to where it was, such as the button that opened it (see the focus
 * rules of overlays in core). A press outside it and Escape close it; Tab walks it right after
 * its anchor. It has the look of a menu: the raised surface, the control border, the medium
 * radius, {@code elevation().popup()} and the fade. It has no arrow to its anchor, since the core
 * draws no triangles. In a tree dump it is an overlay {@code bottom start} on its anchor, {@code
 * role=dialog} with its label.
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
   * is open; where there is no room below, above it.
   *
   * @param label what the popover is, its label in a tree dump
   * @return the anchor
   */
  public static El popover(Theme t, El anchor, State state, String label, El content) {
    return popover(t, anchor, state, Side.BOTTOM, Style.Align.START, label, content);
  }

  /**
   * Attaches {@code content} in a popover on a side of {@code anchor}, aligned along it, while
   * {@code state} is open.
   *
   * @return the anchor
   */
  public static El popover(
      Theme t, El anchor, State state, Side side, Style.Align align, String label, El content) {
    return anchor.overlay(
        state.isOpen()
            ? panel(t, state, label, content).side(side, align, t.space().xs())
            : null);
  }

  /**
   * A popover that is a small form: the title as a heading, the content and the buttons at the
   * bottom right, as a {@link Dialog} has them, below {@code anchor}.
   *
   * @param title shown at the top, also the label
   * @param buttons the actions, in the order shown, the main one usually last
   * @return the anchor
   */
  public static El form(
      Theme t, El anchor, State state, String title, El content, List<El> buttons) {
    return popover(
        t,
        anchor,
        state,
        title,
        col()
            .gap(t.space().l())
            .children(
                label(t, Label.Variant.STRONG, title).role("heading").level(3),
                content,
                row()
                    .gap(t.space().m())
                    .justify(Style.Content.END)
                    .children(buttons.toArray(El[]::new))));
  }

  /**
   * The popover's panel, an overlay to attach to an element with {@link El#overlay} and place, as
   * {@link #popover} does; a press outside it and Escape call {@link State#close}. Where nothing
   * in the content takes the focus, the panel takes it, so that Escape reaches it.
   */
  public static El panel(Theme t, State state, String label, El content) {
    El panel = col()
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
        })
        .padding(t.space().l())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .child(content);
    if (!takesFocus(content)) {
      panel.focusable().focusRing(t.accent().edge());
    }
    return panel;
  }

  private static boolean takesFocus(El el) {
    if (el.isFocusable()) {
      return true;
    }
    for (El child : el.children()) {
      if (takesFocus(child)) {
        return true;
      }
    }
    return false;
  }
}
