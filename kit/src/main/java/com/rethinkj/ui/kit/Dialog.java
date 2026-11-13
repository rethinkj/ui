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
 * role=dialog} with its title as the label.
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
    return dialog(t, title, content, buttons, null, onDismiss);
  }

  /**
   * A dialog whose default button Enter presses while the focus is in a field of the dialog, or
   * anywhere else in it that does not take Enter itself (see {@link #enterPresses}).
   *
   * @param title shown at the top, also the label
   * @param content what the dialog asks or shows
   * @param buttons the actions, in the order shown, the main one usually last
   * @param defaultButton one of {@code buttons}, usually the main one; null for none
   * @param onDismiss runs on Escape and on a press outside the dialog
   * @throws IllegalArgumentException if {@code defaultButton} is not one of {@code buttons}
   */
  public static El dialog(
      Theme t, String title, El content, List<El> buttons, El defaultButton, Runnable onDismiss) {
    if (defaultButton != null && buttons.stream().noneMatch(b -> b == defaultButton)) {
      throw new IllegalArgumentException(
          "The default button must be one of the dialog's buttons, the same El.");
    }
    Predicate<Key> enter = defaultButton == null ? k -> false : enterPresses(defaultButton);
    return col()
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
        .padding(t.space().xl())
        .gap(t.space().l())
        .radius(t.radius().large())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().dialog())
        .motion(t.motion().fade())
        .children(
            label(t, Label.Variant.HEADING, title),
            content,
            row()
                .gap(t.space().m())
                .justify(Style.Content.END)
                .children(buttons.toArray(El[]::new)));
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
