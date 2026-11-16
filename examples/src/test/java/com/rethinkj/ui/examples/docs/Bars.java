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

  Theme theme = Theme.DARK;
  float width; // the bar's width as onResize told it last; 0 before the first layout

  void resize(float width, float height) {
    this.width = width;
  }

  static El view(Bars state) {
    Theme theme = state.theme;
    // The texts until the first layout tells.
    boolean roomy = state.width == 0 || state.width >= 300;
    El bar = row()
        .key("bar")
        .wFull()
        .gap(theme.space().s())
        .onResize(state::resize) // after layout; the view is built again
        .child(button(theme, roomy, Symbol.PLAY, "Run"))
        .child(button(theme, roomy, Symbol.COPY, "Copy"))
        .child(button(theme, roomy, Symbol.SAVE, "Save"));
    return Frame.of(theme, bar);
  }

  static El button(Theme theme, boolean roomy, Symbol icon, String text) {
    return roomy
        ? Button.button(theme, SECONDARY, icon, text, () -> {})
        : Button.icon(theme, SECONDARY, icon, text, () -> {}); // the text is its label and tooltip
  }
}
// docs:end resize
