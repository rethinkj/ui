package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import java.util.List;

/**
 * One stop for Tab among several elements, as WAI-ARIA's toolbar has it: Tab reaches the element
 * the arrows last went to, Arrow Left and Right go to the previous and the next, around the ends,
 * Home and End to the first and the last. The others pass Tab by ({@link El#skipTab}); the one
 * a key went to takes the focus with {@link El#autofocus(Object)}, keyed by the count of moves, in
 * the build after the key alone, so that later builds leave the focus alone, also where a change of
 * the elements gives the stop another identity. Kept in a component's state between builds.
 */
final class Roving {

  private int active;
  private int moves;
  private boolean moved; // a key moved to the active stop, which has yet to take the focus

  /** Gives {@code stops}, in their order, one stop for Tab, and {@code around} the keys. */
  void apply(El around, List<El> stops) {
    if (stops.isEmpty()) {
      return;
    }
    int count = stops.size();
    active = Math.clamp(active, 0, count - 1);
    for (int i = 0; i < count; i++) {
      if (i != active) {
        stops.get(i).skipTab();
      }
    }
    stops.get(active).autofocus(moved ? moves : null);
    moved = false;
    around.onKey(k -> {
      if (k.modifiers() != 0) {
        return false;
      }
      int to = switch (k.name()) {
        case "ArrowRight" -> (active + 1) % count;
        case "ArrowLeft" -> (active + count - 1) % count;
        case "Home" -> 0;
        case "End" -> count - 1;
        default -> -1;
      };
      if (to < 0) {
        return false;
      }
      active = to;
      moves++;
      moved = true;
      return true;
    });
  }

  /** Makes {@code index} the stop Tab reaches, without moving the focus. */
  void at(int index) {
    active = index;
  }
}
