package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Theme;

/** The window around an example of the documentation: its background and some room. */
final class Frame {

  private Frame() {}

  static El of(Theme t, El... content) {
    return col().wFull().hFull().fill(t.surface().window()).padding(t.space().l())
        .gap(t.space().m()).align(Style.Align.START).children(content);
  }
}
