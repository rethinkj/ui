package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import java.util.ArrayList;
import java.util.List;

/**
 * One stop for Tab among several elements, as WAI-ARIA's toolbar has it: Tab reaches the element
 * the arrows last went to, Arrow Left and Right (or Up and Down in vertical mode) go to the
 * previous and the next, around the ends, Home and End to the first and the last. The others pass
 * Tab by ({@link El#skipTab}); the one a key went to takes the focus with {@link
 * El#autofocus(Object)}, keyed by the count of moves, in the build after the key alone, so that
 * later builds leave the focus alone, also where a change of the elements gives the stop another
 * identity. Kept in a component's state between builds.
 */
final class Roving {

  private final boolean vertical;
  private int active;
  private int moves;
  private boolean moved; // a key moved to the active stop, which has yet to take the focus
  private final List<Object> visited = new ArrayList<>(); // the stops' ids, the latest first

  /** Horizontal roving: Arrow Left and Right move between stops. */
  Roving() {
    this(false);
  }

  /** Horizontal ({@code false}) or vertical ({@code true}, Arrow Up and Down) roving. */
  Roving(boolean vertical) {
    this.vertical = vertical;
  }

  /** Gives {@code stops}, in their order, one stop for Tab, and {@code around} the keys. */
  void apply(El around, List<El> stops) {
    apply(around, stops, null);
  }

  /**
   * Like {@link #apply(El, List)}, and keeps the stop at the element of the same id while others
   * come and go. Where it goes, the stop is the one of those it was at that still is, the latest,
   * else the first, as the focus finds a place when the focused element leaves ({@link
   * com.rethinkj.ui.App#focused}).
   *
   * @param ids one per stop, compared with {@link Object#equals}; null keeps the stop at its place
   *     in the order instead
   */
  void apply(El around, List<El> stops, List<?> ids) {
    if (stops.isEmpty()) {
      return;
    }
    int count = stops.size();
    active = ids == null ? Math.clamp(active, 0, count - 1) : latest(ids);
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
      String next = vertical ? "ArrowDown" : "ArrowRight";
      String previous = vertical ? "ArrowUp" : "ArrowLeft";
      int to = k.name().equals(next)
          ? (active + 1) % count
          : k.name().equals(previous)
              ? (active + count - 1) % count
              : switch (k.name()) {
                case "Home" -> 0;
                case "End" -> count - 1;
                default -> -1;
              };
      if (to < 0) {
        return false;
      }
      active = to;
      if (ids != null) {
        visit(ids.get(to));
      }
      moves++;
      moved = true;
      return true;
    });
  }

  /** The id of the stop Tab reaches, as the last build had it; null before a build with ids. */
  Object active() {
    return visited.isEmpty() ? null : visited.getFirst();
  }

  /** Makes the stop of {@code id} the one Tab reaches where a build has it, without the focus. */
  void at(Object id) {
    visit(id);
  }

  /** The place of the latest stop of {@code ids} it was at, else 0, which is now the latest. */
  private int latest(List<?> ids) {
    int at =
        visited.stream().mapToInt(ids::indexOf).filter(i -> i >= 0).findFirst().orElse(0);
    visit(ids.get(at));
    return at;
  }

  private void visit(Object id) {
    visited.remove(id);
    visited.addFirst(id);
  }
}
