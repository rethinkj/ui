package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;

/**
 * A note inside a view, such as "Restart to apply" below the settings it concerns: an icon, a
 * title, a text that wraps and optional actions at the right, on the raised surface with an edge
 * in the kind's colour. The actions stand beside the text while that leaves the text at least
 * {@link Theme.Sizes#field} wide, and below it at the right otherwise, as in a narrow dialog. In a
 * tree dump it is {@code role=note} with the title as its label.
 */
public final class Callout {

  /** What the note is about, which sets its icon and colour. */
  public enum Kind {
    /** Something to know, in the text colour. */
    INFO,
    /** Something that needs attention, in {@link Theme.Status#warning}. */
    WARNING,
    /** A failure, in {@link Theme.Status#danger}. */
    DANGER,
    /** Something that went well, in {@link Theme.Status#success}. */
    SUCCESS
  }

  private Callout() {}

  /**
   * @param text below the title, wrapping; null for none
   * @param actions buttons at the right, in the order shown: beside the text, or below it where
   *     beside it the text would be narrower than {@link Theme.Sizes#field}
   */
  public static El callout(Theme t, Kind kind, String title, String text, El... actions) {
    int edge = switch (kind) {
      case INFO -> t.border().strong();
      case WARNING -> t.status().warning();
      case DANGER -> t.status().danger();
      case SUCCESS -> t.status().success();
    };
    El words = col().grow(1).gap(t.space().xs()).child(label(t, title).wrapText());
    words.style().minWidth = Len.ZERO;
    if (text != null) {
      words.child(label(t, Label.Variant.MUTED, text).wrapText());
    }
    El callout = row()
        .role("note")
        .label(title)
        .padding(t.space().m(), t.space().l())
        .gap(t.space().m())
        .align(Style.Align.START)
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), edge)
        .child(icon(
            t,
            switch (kind) {
              case INFO -> Symbol.INFO;
              case WARNING -> Symbol.WARNING;
              case DANGER -> Symbol.ERROR_CIRCLE;
              case SUCCESS -> Symbol.CHECKMARK_CIRCLE;
            },
            switch (kind) {
              case INFO -> Icon.Variant.NORMAL;
              case WARNING -> Icon.Variant.WARNING;
              case DANGER -> Icon.Variant.DANGER;
              case SUCCESS -> Icon.Variant.SUCCESS;
            }));
    if (actions.length == 0) {
      return callout.child(words);
    }
    // The text and the actions in a row that wraps: the text's basis is the least it may have
    // beside them, so the actions go below it, at the right, exactly when it would get less.
    El buttons = row()
        .gap(t.space().m())
        .align(Style.Align.CENTER)
        .shrink(0)
        .alignSelf(Style.Align.CENTER)
        .children(actions);
    buttons.style().marginLeft = Len.AUTO;
    return callout.child(row()
        .wrap()
        .grow(1)
        .minSize(0, 0)
        .gap(t.space().m())
        .children(words.basis(t.size().field()), buttons));
  }
}
