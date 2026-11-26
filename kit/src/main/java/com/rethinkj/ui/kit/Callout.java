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
 * title, a text that wraps and optional actions at the right, on a tint of the kind's color with a
 * bar in that color along its leading edge ({@link Theme#callout}). The actions stand beside the
 * text while that leaves the text at least {@link Theme.Sizes#field} wide, and below it at the right
 * otherwise, as in a narrow dialog, under each other where they do not fit in a row there. In a
 * tree dump it is {@code role=note} with the title as its label; the bar has no role.
 */
public final class Callout {

  /** What the note is about, which sets its icon and color. */
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
   *     beside it the text would be narrower than {@link Theme.Sizes#field}, and under each other
   *     where below it they do not fit in a row
   */
  public static El callout(Kind kind, String title, String text, El... actions) {
    Theme theme = Theme.CURRENT.get();
    El body = text == null
        ? null
        : label(Label.Variant.MUTED, text).color(theme.callout().text()).wrapText();
    return frame(kind, symbol(kind), title, body, actions);
  }

  /**
   * The callout's frame — bar, icon, title, optional body element and optional actions — shared
   * with {@link Markdown} alerts.
   */
  static El frame(Kind kind, Icon symbol, String title, El body, El... actions) {
    Theme theme = Theme.CURRENT.get();
    Theme.CalloutTone tone = theme.callout().of(kind);
    El words = col().grow(1).gap(theme.space().xs()).child(label(title).wrapText());
    words.style().minWidth = Len.ZERO;
    if (body != null) {
      words.child(body);
    }
    float bar = theme.callout().bar();
    El callout = row()
        .role("note")
        .label(title)
        .padding(
            theme.space().m(),
            theme.space().m(),
            theme.space().m(),
            theme.space().s())
        .gap(theme.space().m())
        .align(Style.Align.START)
        .radius(theme.radius().medium())
        .fill(tone.fill())
        .child(
            el().w(bar).shrink(0).alignSelf(Style.Align.STRETCH).radius(bar / 2).fill(tone.mark()))
        .child(icon(symbol).color(tone.mark()));
    if (actions.length == 0) {
      return callout.child(words);
    }
    // The text and the actions in a row that wraps: the text's basis is the least it may have
    // beside them, so the actions go below it, at the right, exactly when it would get less.
    // Below it, too narrow for them in a row, they shrink and wrap under each other.
    El buttons = row()
        .wrap()
        .justify(Style.Content.END)
        .gap(theme.space().m())
        .align(Style.Align.CENTER)
        .alignSelf(Style.Align.CENTER)
        .children(actions);
    buttons.style().marginLeft = Len.AUTO;
    return callout.child(row()
        .wrap()
        .grow(1)
        .minSize(0, 0)
        .gap(theme.space().m())
        .children(words.basis(theme.size().field()), buttons));
  }

  /** The icon of a kind, also a {@link Tooltip}'s of that kind. */
  static Symbol symbol(Kind kind) {
    return switch (kind) {
      case INFO -> Symbol.INFO;
      case WARNING -> Symbol.WARNING;
      case DANGER -> Symbol.ERROR_CIRCLE;
      case SUCCESS -> Symbol.CHECKMARK_CIRCLE;
    };
  }
}
