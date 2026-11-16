package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Theme;

/** The window around an example of the documentation: its background and some room. */
final class Frame {

  private Frame() {}

  static El of(El... content) {
    Theme theme = Theme.CURRENT.get();
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .padding(theme.space().l())
        .gap(theme.space().m())
        .align(Style.Align.START)
        .children(content);
  }
}
