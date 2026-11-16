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

  float width; // the bar's width as onResize told it last; 0 before the first layout

  void resize(float width, float height) {
    this.width = width;
  }

  static El view(Bars state) {
    Theme theme = Theme.CURRENT.get();
    // The texts until the first layout tells.
    boolean roomy = state.width == 0 || state.width >= 300;
    El bar = row()
        .key("bar")
        .wFull()
        .gap(theme.space().s())
        .onResize(state::resize) // after layout; the view is built again
        .child(button(roomy, Symbol.PLAY, "Run"))
        .child(button(roomy, Symbol.COPY, "Copy"))
        .child(button(roomy, Symbol.SAVE, "Save"));
    return Frame.of(bar);
  }

  static El button(boolean roomy, Symbol icon, String text) {
    return roomy
        ? Button.button(SECONDARY, icon, text, () -> {})
        : Button.icon(SECONDARY, icon, text, () -> {}); // the text is its label and tooltip
  }
}
// docs:end resize
