package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * A stack of short notifications at the bottom center of the window: several show at once with the
 * newest nearest the bottom edge, up to three visible while more wait in a queue and appear as
 * visible ones close. The app keeps one in its state, adds messages from any handler on the app's
 * thread (such as the done and failure handlers of {@link com.rethinkj.ui.App#background}), and
 * attaches {@link #view()} as an overlay on its root every build:
 *
 * <pre>{@code
 * root.overlay(state.toasts.view());
 * }</pre>
 *
 * <p>A {@link Callout.Kind} sets the toast's icon and color, and {@code null} shows a neutral
 * message without an icon. Each toast has a close button, closes on Escape while focused inside
 * it, leaves the keyboard focus where it is ({@link El#keepFocus}), closes itself after {@link
 * Theme.Motion#toast} seconds of the app's clock ({@link El#onShownFor}), and pauses that timer
 * while the pointer is over it ({@link El#pauseShownOnHover}). A message wraps onto two lines at
 * most and then ends in an ellipsis, with the whole in a tooltip.
 *
 * <p>{@link #progress(String)} opens a toast with a {@link Progress} bar that does not time out
 * while running; its {@link Handle} updates the progress or message and turns it into a result
 * toast with {@link Handle#finish}, which then times out normally, or removes it with {@link
 * Handle#close}.
 *
 * <p>In a tree dump the stack is an overlay {@code edge bottom center}, and each toast is {@code
 * role=status} with its message as the label. For a single neutral message owned by a field in the
 * app's state, see {@link Toast}.
 */
public final class Toasts {

  private static final int MAX_VISIBLE = 3;

  private final List<Entry> visible = new ArrayList<>();
  private final Deque<Entry> queue = new ArrayDeque<>();
  private long nextId = 1;

  /**
   * A handle to a running progress toast, returned by {@link #progress(String)} and {@link
   * #progress(String, double)}. Call its methods on the app's thread; once the toast has finished
   * or closed, further calls do nothing.
   */
  public final class Handle {
    private final Entry entry;

    private Handle(Entry entry) {
      this.entry = entry;
    }

    /** Updates the message shown above the progress bar. */
    public void message(String message) {
      Objects.requireNonNull(message, "message");
      if (!entry.closed && entry.progress) {
        entry.message = message;
      }
    }

    /**
     * Sets how far the work has come, from 0 to 1, or {@link Double#NaN} for an indeterminate
     * running bar.
     */
    public void progress(double fraction) {
      if (!entry.closed && entry.progress) {
        entry.fraction = fraction;
      }
    }

    /**
     * Turns the progress toast into a result toast with this kind (null for neutral) and message,
     * which then closes after {@link Theme.Motion#toast} seconds.
     */
    public void finish(Callout.Kind kind, String message) {
      finish(kind, message, null, null);
    }

    /**
     * Turns the progress toast into a result toast with an optional action button, which then
     * closes after {@link Theme.Motion#toast} seconds.
     *
     * @param kind sets the icon and color; null for neutral
     * @param message what happened
     * @param action the text of the action button; null for none
     * @param onAction runs when the action is pressed, before closing; required when {@code action}
     *     is not null
     */
    public void finish(Callout.Kind kind, String message, String action, Runnable onAction) {
      Objects.requireNonNull(message, "message");
      if (action != null) {
        Objects.requireNonNull(onAction, "onAction");
      }
      if (!entry.closed && entry.progress) {
        entry.progress = false;
        entry.kind = kind;
        entry.message = message;
        entry.action = action;
        entry.onAction = onAction;
      }
    }

    /** Closes the toast immediately, removing it whether visible or waiting in the queue. */
    public void close() {
      Toasts.this.close(entry);
    }
  }

  private static final class Entry {
    final long id;
    Callout.Kind kind;
    String message;
    String action;
    Runnable onAction;
    boolean progress;
    double fraction;
    boolean closed;

    Entry(
        long id,
        Callout.Kind kind,
        String message,
        String action,
        Runnable onAction,
        boolean progress,
        double fraction) {
      this.id = id;
      this.kind = kind;
      this.message = message;
      this.action = action;
      this.onAction = onAction;
      this.progress = progress;
      this.fraction = fraction;
    }
  }

  /** Shows a neutral toast without an icon or action. */
  public void show(String message) {
    show(null, message, null, null);
  }

  /**
   * Shows a toast of the given kind without an action.
   *
   * @param kind sets the icon and color; null for neutral
   * @param message what happened
   */
  public void show(Callout.Kind kind, String message) {
    show(kind, message, null, null);
  }

  /**
   * Shows a toast of the given kind with an optional action button.
   *
   * @param kind sets the icon and color; null for neutral
   * @param message what happened
   * @param action the text of the ghost action button; null for none
   * @param onAction runs when the action is pressed, before closing; required when {@code action}
   *     is not null
   */
  public void show(Callout.Kind kind, String message, String action, Runnable onAction) {
    Objects.requireNonNull(message, "message");
    if (action != null) {
      Objects.requireNonNull(onAction, "onAction");
    }
    add(new Entry(nextId++, kind, message, action, onAction, false, Double.NaN));
  }

  /**
   * Shows a progress toast with an indeterminate bar that stays until {@link Handle#finish} or
   * {@link Handle#close}.
   */
  public Handle progress(String message) {
    return progress(message, Double.NaN);
  }

  /**
   * Shows a progress toast that stays until {@link Handle#finish} or {@link Handle#close}.
   *
   * @param message what is running
   * @param fraction initial progress from 0 to 1, or {@link Double#NaN} for indeterminate
   */
  public Handle progress(String message, double fraction) {
    Objects.requireNonNull(message, "message");
    Entry entry = new Entry(nextId++, null, message, null, null, true, fraction);
    add(entry);
    return new Handle(entry);
  }

  /** Whether no toast is currently visible or waiting in the queue. */
  public boolean isEmpty() {
    return visible.isEmpty() && queue.isEmpty();
  }

  /**
   * The overlay holding the visible toasts at the bottom center of the window, or {@code null}
   * when nothing shows. Attach to the root with {@link El#overlay}.
   */
  public El view() {
    return view(0);
  }

  /**
   * The overlay holding the visible toasts, staying clear of what lies along the bottom of the
   * window, such as a {@link StatusBar} ({@link StatusBar#height}) or a card's footer ({@link
   * Card#footerHeight}): {@code above} plus the theme's gap above the window's bottom edge, or
   * {@code null} when nothing shows.
   *
   * @param above how high, in logical pixels, what it must not cover reaches up from the bottom
   */
  public El view(float above) {
    if (visible.isEmpty()) {
      return null;
    }
    Theme theme = Theme.CURRENT.get();
    El stack = Toast.atBottom(theme, col(), above)
        .key("toasts")
        .motion(theme.motion().fade())
        .gap(theme.space().m())
        .align(Style.Align.STRETCH)
        .margin(0, theme.space().xl(), 0, theme.space().xl());
    for (Entry entry : visible) {
      stack.child(item(theme, entry));
    }
    return stack;
  }

  private void add(Entry entry) {
    if (visible.size() < MAX_VISIBLE) {
      visible.add(entry);
    } else {
      queue.addLast(entry);
    }
  }

  private void close(Entry entry) {
    if (entry.closed) {
      return;
    }
    entry.closed = true;
    if (visible.remove(entry)) {
      if (!queue.isEmpty() && visible.size() < MAX_VISIBLE) {
        visible.add(queue.removeFirst());
      }
    } else {
      queue.remove(entry);
    }
  }

  private El item(Theme theme, Entry entry) {
    Runnable onClose = () -> close(entry);
    El close = Button.icon(
            Button.Variant.GHOST, Symbol.DISMISS, Texts.CURRENT.get().close(), onClose)
        .key("close-" + entry.id);
    // Close lies in a keyed row in both layouts, so that it keeps the focus as a progress toast
    // finishes into a result.
    El buttons =
        row().key("buttons").align(Style.Align.CENTER).gap(theme.space().xs()).shrink(0);
    if (entry.progress) {
      El header = row() // a control high, beside Close
          .minSize(0, theme.size().control())
          .align(Style.Align.CENTER)
          .gap(theme.space().m())
          .child(message(entry));
      if (!Double.isNaN(entry.fraction)) {
        Theme.TextStyle body = theme.type().body();
        header.child(El.text(Progress.percent(entry.fraction))
            .font(body.font().get())
            .fontSize(body.size())
            .fontFeatures("+tnum")
            .color(theme.text().muted())
            .shrink(0));
      }
      El bar = (Double.isNaN(entry.fraction)
              ? Progress.indeterminate()
              : Progress.bar(entry.fraction))
          .key("progress")
          .wFull();
      bar.style().minWidth = Len.px(theme.size().field());
      return Toast.frame(theme, row(), entry.message, onClose)
          .key("toast-" + entry.id)
          .padding(
              theme.space().xs(),
              theme.space().xs(),
              theme.space().m(),
              theme.space().l())
          .gap(theme.space().m())
          .child(col().grow(1).minSize(0, 0).gap(theme.space().s()).children(header, bar))
          .child(buttons.alignSelf(Style.Align.START).child(close));
    }
    El content = row().align(Style.Align.CENTER).gap(theme.space().m()).grow(1).minSize(0, 0);
    if (entry.kind != null) {
      content.child(Icon.icon(Callout.symbol(entry.kind))
          .color(theme.callout().of(entry.kind).mark())
          .shrink(0));
    }
    content.child(message(entry));
    if (entry.action != null) {
      buttons.child(Toast.action(entry.action, entry.onAction, onClose));
    }
    buttons.child(close);
    return Toast.frame(theme, row(), entry.message, onClose)
        .key("toast-" + entry.id)
        .onShownFor(theme.motion().toast(), onClose)
        .pauseShownOnHover()
        .padding(
            theme.space().xs(),
            theme.space().xs(),
            theme.space().xs(),
            theme.space().l())
        .gap(theme.space().m())
        .align(Style.Align.CENTER)
        .child(content)
        .child(buttons);
  }

  /**
   * The message, wrapping onto at most two lines and then ending in an ellipsis with the whole in a
   * tooltip, so that three toasts fit also in a small window.
   */
  private static El message(Entry entry) {
    return Tooltip.whenShortened(
        label(entry.message).wrapText().maxLines(2).ellipsis().grow(1).minSize(0, 0),
        entry.message);
  }
}
