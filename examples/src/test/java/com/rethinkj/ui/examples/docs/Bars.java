package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;

// docs:start resize
/** A bar that shows its buttons' texts while it is wide enough, else only their icons. */
final class Bars {

  Theme t = Theme.DARK;
  float width; // the bar's width as onResize told it last; 0 before the first layout

  static El view(Bars s) {
    Theme t = s.t;
    boolean roomy = s.width == 0 || s.width >= 300; // the texts until the first layout tells
    El bar = row()
        .key("bar")
        .wFull()
        .gap(t.space().s())
        .onResize((width, height) -> s.width = width) // after layout; the view is built again
        .child(button(t, roomy, Symbol.PLAY, "Run"))
        .child(button(t, roomy, Symbol.COPY, "Copy"))
        .child(button(t, roomy, Symbol.SAVE, "Save"));
    return Frame.of(t, bar);
  }

  static El button(Theme t, boolean roomy, Symbol icon, String text) {
    return roomy
        ? Button.button(t, SECONDARY, icon, text, () -> {})
        : Button.icon(t, SECONDARY, icon, text, () -> {}); // the text is its label and tooltip
  }
}
// docs:end resize
