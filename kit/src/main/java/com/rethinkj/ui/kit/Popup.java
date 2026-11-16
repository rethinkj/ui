package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;

/** The common popup look; each owner supplies its content, placement and input behavior. */
final class Popup {

  private Popup() {}

  static El frame(Theme theme, El panel) {
    return surface(theme, panel).motion(theme.motion().fade());
  }

  /** The surface alone, for a popup whose surrounding element carries the fade. */
  static El surface(Theme theme, El panel) {
    return panel
        .radius(theme.radius().medium())
        .fill(theme.surface().popup())
        .border(theme.size().stroke(), theme.border().control())
        .shadow(theme.elevation().popup());
  }
}
