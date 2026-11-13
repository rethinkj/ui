package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.List;

/**
 * A modal panel in the middle of the window, over a scrim that darkens everything else: a title,
 * the content and a row of buttons at the bottom right. The app attaches it while its state says
 * it is open, usually to the root, and closes it in its handlers:
 *
 * <pre>{@code
 * root.overlay(s.renaming ? Dialog.dialog(t, "Rename table", field,
 *     List.of(button(t, SECONDARY, "Cancel", s::cancel), button(t, PRIMARY, "Rename", s::rename)),
 *     s::cancel) : null);
 * }</pre>
 *
 * <p>As it opens, the focus goes to its first focusable element; Tab and Shift+Tab go around
 * inside it, and nothing outside it takes the pointer, the wheel or the keys. Escape and a press
 * on the scrim call {@code onDismiss}. When it closes, the focus goes back to where it was, such
 * as the button that opened it. In a tree dump it is an overlay {@code center modal scrim}, {@code
 * role=dialog} with its title as the label.
 */
public final class Dialog {

  private Dialog() {}

  /**
   * @param title shown at the top, also the label
   * @param content what the dialog asks or shows
   * @param buttons the actions, in the order shown, the main one usually last
   * @param onDismiss runs on Escape and on a press outside the dialog
   */
  public static El dialog(
      Theme t, String title, El content, List<El> buttons, Runnable onDismiss) {
    return col().key("dialog").role("dialog").label(title)
        .centerInWindow()
        .modal()
        .scrim(t.surface().scrim())
        .onDismiss(onDismiss)
        .onKey(k -> {
          if (!k.name().equals("Escape") || k.modifiers() != 0) {
            return false;
          }
          onDismiss.run();
          return true;
        })
        .padding(t.space().xl())
        .gap(t.space().l())
        .radius(t.radius().large())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().subtle())
        .shadow(t.elevation().dialog())
        .children(
            label(t, Label.Variant.HEADING, title),
            content,
            row().gap(t.space().m()).justify(Style.Content.END)
                .children(buttons.toArray(El[]::new)));
  }
}
