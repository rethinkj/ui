package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;

/**
 * A panel against an edge of the window, as long as the window along that edge, with a title, a
 * close button and content that scrolls: a detail inspector or a side form without rebuilding the
 * main layout around a {@link Split}. The app attaches it while its state says it is open, usually
 * to the root, and closes it in its dismiss handler:
 *
 * <pre>{@code
 * root.overlay(state.inspecting
 *     ? Drawer.drawer("Order details", details, state::close, Drawer.options().modal(false))
 *     : null);
 * }</pre>
 *
 * <p>By default it is modal at the right edge, over a scrim that darkens everything else: the
 * focus goes to the first element of its content that Tab reaches, or to its close button where
 * there is none (as in a drawer that shows only {@link Properties}); Tab and Shift+Tab go around
 * inside it, nothing outside it takes the pointer, the wheel or the keys, and Escape and a press
 * on the scrim call {@code onDismiss}. When it closes, the focus goes back to where it was.
 *
 * <p>With {@link Options#modal(boolean) modal(false)} it has no scrim and leaves the main view
 * usable: presses and the wheel outside it go through, and clicking another row or control updates
 * the view without closing the drawer. Escape while the focus is inside the drawer still calls
 * {@code onDismiss}, and closing while the focus is inside returns it to where it was.
 *
 * <p>Along the left or right edge it is {@link Theme.Sizes#drawer} wide unless {@code content}
 * sets a pixel width of its own ({@link El#w}); along the top or bottom edge it fits {@code
 * content}'s height. In every direction it is at most as large as the window, and content that
 * does not fit scrolls inside {@code #drawer-content} while the head stays in view. The title, in
 * {@link Theme.Type#dialog}, is {@code role=heading} at {@code level=2} and ends in an ellipsis
 * with the whole in a tooltip where the head has no room for it. The head comes last in the tree,
 * drawn at the top, so Tab goes through the content and then to the close button.
 *
 * <p>In a tree dump it is an overlay {@code edge right stretch} (or the side chosen), with {@code
 * modal scrim} when modal, key {@code drawer}, {@code role=dialog} and its title as the label.
 */
public final class Drawer {

  private Drawer() {}

  /**
   * A modal drawer at the right edge of the window.
   *
   * @param title shown at the top, also the label
   * @param content what the drawer shows or asks
   * @param onDismiss runs on Escape, on the close button and on a press on the scrim
   */
  public static El drawer(String title, El content, Runnable onDismiss) {
    return drawer(title, content, onDismiss, options());
  }

  /** What a drawer changes from a modal right-edge one; {@link #options()} is the default. */
  public static final class Options {
    private Side side = Side.RIGHT;
    private boolean modal = true;

    private Options() {}

    /** Which edge of the window the drawer lies against; {@link Side#RIGHT} by default. */
    public Options side(Side side) {
      this.side = Objects.requireNonNull(side, "side");
      return this;
    }

    /**
     * Whether the drawer is modal over a scrim ({@code true}, the default) or non-modal so that
     * the main view stays usable beside it.
     */
    public Options modal(boolean modal) {
      this.modal = modal;
      return this;
    }
  }

  public static Options options() {
    return new Options();
  }

  /**
   * A drawer with the edge and modality of {@code options}.
   *
   * @param title shown at the top, also the label
   * @param content what the drawer shows or asks
   * @param onDismiss runs on Escape inside the drawer, on the close button and, when modal, on a
   *     press on the scrim
   */
  public static El drawer(String title, El content, Runnable onDismiss, Options options) {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(onDismiss, "onDismiss");
    Objects.requireNonNull(options, "options");
    Theme theme = Theme.CURRENT.get();
    Texts texts = Texts.CURRENT.get();
    El close = Button.icon(Button.Variant.GHOST, Symbol.DISMISS, texts.close(), onDismiss)
        .key("close");
    El head = Dialog.head(theme, title, null, List.of(close));
    El body = Dialog.scrolling(theme, content).key("drawer-content").grow(1);
    El drawer = col()
        .key("drawer")
        .role("dialog")
        .label(title)
        .atWindowEdge(options.side, Style.Align.STRETCH, 0)
        .onKey(k -> {
          if (k.name().equals("Escape") && k.modifiers() == 0) {
            onDismiss.run();
            return true;
          }
          return false;
        })
        .justify(Style.Content.END) // at the top, in reverse
        .padding(theme.space().xl())
        .gap(theme.space().l())
        .fill(theme.surface().raised())
        .shadow(theme.elevation().dialog())
        .motion(theme.motion().fade())
        .children(body, head);
    // The head comes last in the tree, so that the focus opens in the content and Tab reaches the
    // close button after the content; drawn at the top all the same.
    drawer.style().direction = Style.Direction.COLUMN_REVERSE;
    float stroke = theme.size().stroke();
    int border = theme.border().control();
    switch (options.side) {
      case RIGHT -> drawer.border(0, 0, 0, stroke, border);
      case LEFT -> drawer.border(0, stroke, 0, 0, border);
      case TOP -> drawer.border(0, 0, stroke, 0, border);
      case BOTTOM -> drawer.border(stroke, 0, 0, 0, border);
    }
    if (options.side == Side.LEFT || options.side == Side.RIGHT) {
      // Set either way, so that a long title gives way instead of widening the drawer.
      Len own = content == null ? null : content.style().width;
      drawer.w(
          own != null && own.kind() == Len.Kind.PX
              ? own.value() + 2 * theme.space().xl() + stroke
              : theme.size().drawer());
    }
    if (options.modal) {
      drawer.modal().scrim(theme.surface().scrim()).onDismiss(onDismiss);
    }
    return drawer;
  }
}
