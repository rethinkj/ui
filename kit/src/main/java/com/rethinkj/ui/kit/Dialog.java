package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A modal panel in the middle of the window, over a scrim that darkens everything else: a title,
 * the content and a row of buttons at the bottom right. The app attaches it while its state says
 * it is open, usually to the root, and closes it in its handlers:
 *
 * <pre>{@code
 * El rename = button(t, PRIMARY, "Rename", s.name.isBlank() ? null : s::rename);
 * root.overlay(s.renaming ? Dialog.dialog(t, "Rename table", field,
 *     List.of(button(t, SECONDARY, "Cancel", s::cancel), rename), rename, s::cancel) : null);
 * }</pre>
 *
 * <p>As it opens, the focus goes to its first focusable element; Tab and Shift+Tab go around
 * inside it, and nothing outside it takes the pointer, the wheel or the keys. Escape and a press
 * on the scrim call {@code onDismiss}. Enter in a field presses the default button, if the dialog
 * has one and it is enabled; Enter on a focused button presses that button, and a multi-line field
 * keeps Enter for its line breaks. When it closes, the focus goes back to where it was, such as
 * the button that opened it. In a tree dump it is an overlay {@code center modal scrim}, {@code
 * role=dialog} with its title as the label; the title is {@code role=heading} at {@code level=2}
 * with the title as its label.
 *
 * <p>{@link #options()} add an element before the title (a colour mark, an icon), elements at the
 * head's end (a badge, a help or close button) and at the start of the row of buttons (a secondary
 * action, a status). The title ends in an ellipsis where the head has no room for it, with the
 * whole in a tooltip. The head comes last in the tree, drawn at the top: Tab goes through the
 * content, the buttons and then the head, and the dialog opens in its content.
 *
 * <p>A view sizes a dialog by sizing its content, as {@code field.w(320)}.
 *
 * <p>Its key is {@code dialog}. Two dialogs open at once, as a question over a dialog, are
 * siblings among the root's overlays and need keys of their own: chain {@code .key("discard")}
 * onto the second, which then lies over the first with its own scrim.
 */
public final class Dialog {

  private Dialog() {}

  /**
   * A dialog without a default button: Enter in a field does nothing.
   *
   * @param title shown at the top, also the label
   * @param content what the dialog asks or shows
   * @param buttons the actions, in the order shown, the main one usually last
   * @param onDismiss runs on Escape and on a press outside the dialog
   */
  public static El dialog(Theme t, String title, El content, List<El> buttons, Runnable onDismiss) {
    return dialog(t, title, content, buttons, onDismiss, options());
  }

  /**
   * A dialog whose default button Enter presses while the focus is in a field of the dialog, or
   * anywhere else in it that does not take Enter itself (see {@link #enterPresses}).
   *
   * @param title shown at the top, also the label
   * @param content what the dialog asks or shows
   * @param buttons the actions, in the order shown, the main one usually last; an empty list
   *     leaves their row out, for a dialog whose content has its own
   * @param defaultButton one of {@code buttons}, usually the main one; null for none
   * @param onDismiss runs on Escape and on a press outside the dialog
   * @throws IllegalArgumentException if {@code defaultButton} is not one of {@code buttons}
   */
  public static El dialog(
      Theme t, String title, El content, List<El> buttons, El defaultButton, Runnable onDismiss) {
    return dialog(t, title, content, buttons, onDismiss, options().defaultButton(defaultButton));
  }

  /** What a dialog has beyond its title, content and buttons; {@link #options()} is a plain one. */
  public static final class Options {
    private El leading;
    private List<El> trailing = List.of();
    private List<El> start = List.of();
    private El defaultButton;

    private Options() {}

    /**
     * An element before the title, such as a colour mark, an icon or an avatar of what the dialog
     * edits, centred on the title's line and {@code space().m()} before it; it keeps its size.
     * Null for none.
     */
    public Options leading(El leading) {
      this.leading = leading;
      return this;
    }

    /**
     * Elements at the end of the head, as a badge ("Read only") or a help or close button, in the
     * order shown; they keep their size and the title gives way before them. In the order of Tab
     * they come after the buttons (see {@link Dialog}), so the dialog still opens in its content.
     */
    public Options trailing(El... trailing) {
      this.trailing = List.of(trailing);
      return this;
    }

    /**
     * Elements at the start of the row of buttons, in the order shown, as a secondary action
     * ("Test connection") or a status: they lie at the left and the buttons at the right. With no
     * buttons, the row is there for them alone.
     */
    public Options start(El... start) {
      this.start = List.of(start);
      return this;
    }

    /**
     * The button that Enter presses while the focus is in a field of the dialog, or anywhere else
     * in it that does not take Enter itself (see {@link #enterPresses}); one of the dialog's
     * buttons, null for none.
     */
    public Options defaultButton(El defaultButton) {
      this.defaultButton = defaultButton;
      return this;
    }
  }

  public static Options options() {
    return new Options();
  }

  /**
   * A dialog with what {@code options} add: an element before the title, elements at the end of
   * the head and at the start of the row of buttons, and a default button.
   *
   * @param title shown at the top, also the label
   * @param content what the dialog asks or shows
   * @param buttons the actions, in the order shown, the main one usually last; an empty list
   *     leaves their row out unless the options give its start
   * @param onDismiss runs on Escape and on a press outside the dialog
   * @throws IllegalArgumentException if the default button is not one of {@code buttons}
   */
  public static El dialog(
      Theme t, String title, El content, List<El> buttons, Runnable onDismiss, Options options) {
    El defaultButton = options.defaultButton;
    if (defaultButton != null && buttons.stream().noneMatch(b -> b == defaultButton)) {
      throw new IllegalArgumentException(
          "The default button must be one of the dialog's buttons, the same El.");
    }
    Predicate<Key> enter = defaultButton == null ? k -> false : enterPresses(defaultButton);
    El head = row().gap(t.space().m()).align(Style.Align.CENTER).shrink(0);
    if (options.leading != null) {
      head.child(options.leading.shrink(0));
    }
    head.child(Tooltip.whenShortened(
        t,
        label(t, Label.Variant.HEADING, title)
            .role("heading")
            .level(2)
            .label(title)
            .ellipsis()
            .minSize(0, 0),
        title));
    if (!options.trailing.isEmpty()) {
      head.child(El.el().grow(1));
    }
    for (El e : options.trailing) {
      head.child(e.shrink(0));
    }
    El body = col()
        .gap(t.space().l())
        .minSize(0, 0)
        .children(
            content,
            buttons.isEmpty() && options.start.isEmpty() // no row, so no gap for it
                ? null
                : buttons(t, options.start, buttons));
    El dialog = col()
        .key("dialog")
        .role("dialog")
        .label(title)
        .centerInWindow()
        .modal()
        .scrim(t.surface().scrim())
        .onDismiss(onDismiss)
        .onKey(k -> {
          if (k.name().equals("Escape") && k.modifiers() == 0) {
            onDismiss.run();
            return true;
          }
          return enter.test(k);
        })
        .justify(Style.Content.END) // at the top, in reverse
        .padding(t.space().xl())
        .gap(t.space().l())
        .radius(t.radius().large())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().dialog())
        .motion(t.motion().fade())
        .children(body, head);
    // The head comes last in the tree, so that the focus opens in the content and Tab reaches a
    // close button in the head after the buttons; drawn at the top all the same.
    dialog.style().direction = Style.Direction.COLUMN_REVERSE;
    return dialog;
  }

  private static El buttons(Theme t, List<El> start, List<El> buttons) {
    El row = row().gap(t.space().m()).align(Style.Align.CENTER).shrink(0);
    if (start.isEmpty()) {
      return row.justify(Style.Content.END).children(buttons.toArray(El[]::new));
    }
    start.forEach(row::child);
    return row.child(El.el().grow(1)).children(buttons.toArray(El[]::new));
  }

  /**
   * A key handler that presses {@code button} on Enter without modifiers, for the element around a
   * form that has a default button, such as the root of a window that is a dialog itself: {@code
   * root.onKey(Dialog.enterPresses(connect))}. Keys reach it from the focused element up, after
   * the focused element: a focused button, checkbox or select presses itself on Enter, and a
   * multi-line field takes Enter for a line break, so only Enter in a single-line field, or with
   * the focus elsewhere, reaches it. A repeat of a held Enter presses nothing again; a disabled
   * button (without a handler) is not pressed and the handler does not take the key.
   *
   * @param button the button to press, from the same build of the view as the handler
   */
  public static Predicate<Key> enterPresses(El button) {
    Objects.requireNonNull(button, "button");
    return k -> {
      if (!k.name().equals("Enter") || k.modifiers() != 0 || button.onPress() == null) {
        return false;
      }
      if (!k.repeat()) {
        button.onPress().press(1, 0, button.width() / 2.0, button.height() / 2.0);
      }
      return true;
    };
  }
}
