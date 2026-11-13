package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;

/**
 * A short message at the bottom of the window, in the middle, with an optional action such as
 * Undo, that closes itself after {@link Theme.Motion#toast} seconds of the app's clock. The app
 * attaches it while its state holds the message, usually to the root, and its close handler
 * clears that:
 *
 * <pre>{@code
 * root.overlay(s.dropped == null ? null
 *     : Toast.toast(t, "Dropped " + s.dropped, "Undo", s::restore, () -> s.dropped = null));
 * }</pre>
 *
 * <p>It leaves the keyboard focus where it is ({@link El#keepFocus}), also when its action is
 * pressed, so that typing goes on; Tab reaches the action after everything else, and Escape there
 * closes it. A press elsewhere does not close it. An idle window waits for the time to pass
 * without drawing frames ({@link El#onShownFor}). In a tree dump it is an overlay {@code edge
 * bottom center}, {@code role=status} with the message as the label.
 */
public final class Toast {

  private Toast() {}

  /**
   * @param message what happened
   * @param action the text of the button that undoes or follows it up; null for none
   * @param onAction runs when the action is pressed, before {@code onClose}
   * @param onClose runs when the time is up, the action was pressed or Escape closes it
   */
  public static El toast(
      Theme t, String message, String action, Runnable onAction, Runnable onClose) {
    El toast = row().key("toast").role("status").label(message)
        .atWindowEdge(Side.BOTTOM, Style.Align.CENTER, t.space().xl())
        .keepFocus()
        .onShownFor(t.motion().toast(), onClose)
        .onKey(k -> {
          if (!k.name().equals("Escape") || k.modifiers() != 0) {
            return false;
          }
          onClose.run();
          return true;
        })
        .padding(t.space().xs(), t.space().xs(), t.space().xs(), t.space().l())
        .gap(t.space().m())
        .align(Style.Align.CENTER)
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().subtle())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .child(label(t, message));
    if (action != null) {
      toast.child(button(t, Button.Variant.GHOST, action, () -> {
        onAction.run();
        onClose.run();
      }).key("action"));
    }
    return toast;
  }
}
