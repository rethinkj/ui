package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;

/**
 * A note inside a view, such as "Restart to apply" below the settings it concerns: an icon, a
 * title, a text that wraps and optional actions at the right, on a tint of the kind's colour with a
 * bar in that colour along its leading edge ({@link Theme#callout}). The actions stand beside the
 * text while that leaves the text at least {@link Theme.Sizes#field} wide, and below it at the right
 * otherwise, as in a narrow dialog. In a tree dump it is {@code role=note} with the title as its
 * label; the bar has no role.
 */
public final class Callout {

  /** What the note is about, which sets its icon and colour. */
  public enum Kind {
    /** Something to know, in the theme's blue. */
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
    Theme.CalloutTone tone = t.callout().of(kind);
    El words = col().grow(1).gap(t.space().xs()).child(label(t, title).wrapText());
    words.style().minWidth = Len.ZERO;
    if (text != null) {
      words.child(label(t, Label.Variant.MUTED, text).color(t.callout().text()).wrapText());
    }
    float bar = t.callout().bar();
    El callout = row()
        .role("note")
        .label(title)
        .padding(t.space().m(), t.space().m(), t.space().m(), t.space().s())
        .gap(t.space().m())
        .align(Style.Align.START)
        .radius(t.radius().medium())
        .fill(tone.fill())
        .child(
            el().w(bar).shrink(0).alignSelf(Style.Align.STRETCH).radius(bar / 2).fill(tone.mark()))
        .child(icon(
                t,
                switch (kind) {
                  case INFO -> Symbol.INFO;
                  case WARNING -> Symbol.WARNING;
                  case DANGER -> Symbol.ERROR_CIRCLE;
                  case SUCCESS -> Symbol.CHECKMARK_CIRCLE;
                })
            .color(tone.mark()));
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
