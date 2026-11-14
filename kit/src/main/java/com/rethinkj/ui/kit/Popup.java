package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;

/** The common popup look; each owner supplies its content, placement and input behavior. */
final class Popup {

  private Popup() {}

  static El frame(Theme t, El panel) {
    return surface(t, panel).motion(t.motion().fade());
  }

  /** The surface alone, for a popup whose surrounding element carries the fade. */
  static El surface(Theme t, El panel) {
    return panel
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().popup());
  }
}
