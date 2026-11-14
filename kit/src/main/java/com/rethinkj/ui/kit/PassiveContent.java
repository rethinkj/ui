package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/** The content inside a menu item or list option; the component supplies its text and marks. */
final class PassiveContent {

  private PassiveContent() {}

  static int text(Theme t, boolean enabled) {
    return enabled ? t.text().primary() : t.text().disabled();
  }

  static int muted(Theme t, boolean enabled) {
    return enabled ? t.text().muted() : t.text().disabled();
  }

  static El leading(Theme t, El child) {
    return el().w(t.size().icon()).shrink(0).center().child(child);
  }

  /** A filter entry's custom element may be wider than an icon. */
  static El flexibleLeading(Theme t, El child) {
    return el().minSize(t.size().icon(), 0).shrink(0).center().child(child);
  }

  static El row(Theme t, El leading, El text, El... trailing) {
    return El.row()
        .wFull()
        .hFull()
        .padding(0, t.space().m())
        .gap(t.space().m())
        .align(Style.Align.CENTER)
        .children(leading, text.grow(1))
        .children(trailing);
  }
}
