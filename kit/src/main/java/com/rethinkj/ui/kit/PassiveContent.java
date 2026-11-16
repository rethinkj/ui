package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/** The content inside a menu item or list option; the component supplies its text and marks. */
final class PassiveContent {

  private PassiveContent() {}

  static int text(Theme theme, boolean enabled) {
    return enabled ? theme.text().primary() : theme.text().disabled();
  }

  static int muted(Theme theme, boolean enabled) {
    return enabled ? theme.text().muted() : theme.text().disabled();
  }

  static El leading(Theme theme, El child) {
    return el().w(theme.size().icon()).shrink(0).center().child(child);
  }

  /** A filter entry's custom element may be wider than an icon. */
  static El flexibleLeading(Theme theme, El child) {
    return el().minSize(theme.size().icon(), 0).shrink(0).center().child(child);
  }

  static El row(Theme theme, El leading, El text, El... trailing) {
    return El.row()
        .wFull()
        .hFull()
        .padding(0, theme.space().m())
        .gap(theme.space().m())
        .align(Style.Align.CENTER)
        .children(leading, text.grow(1))
        .children(trailing);
  }
}
