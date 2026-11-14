package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/** A virtual list whose owner keeps the focus, supplies the rows and handles choosing them. */
final class PassiveList {

  private PassiveList() {}

  static final int UNHANDLED = -2;

  /** The scrolling view, with room beside the rows for its bar and the highlight kept in view. */
  static El view(Theme t, int count, int most, int highlight, float padding, IntFunction<El> row) {
    return col()
        .padding(padding, padding + gutter(t, count, most), padding, padding)
        .scrollbars(t.scrollbars())
        .revealRow(highlight)
        .rows(count, t.size().control(), row);
  }

  static float gutter(Theme t, int count, int most) {
    return count > most ? t.scrollbars().thickness() + t.scrollbars().margin() : 0;
  }

  /** An option's highlight and pointer handlers; its content belongs to the caller. */
  static El option(Theme t, int index, int highlight, IntConsumer light, Runnable choose) {
    boolean lit = index == highlight;
    El row = row()
        .key("option-" + index)
        .role("option")
        .selected(lit)
        .disabled(choose == null)
        .radius(t.radius().small())
        .fill(lit ? t.grid().selected() : 0);
    if (choose != null) {
      row.onHoverFor(0, () -> light.accept(index)).onPress(choose);
    }
    return row;
  }

  /** A navigation key's next row, -1 if none can be chosen, or {@link #UNHANDLED}. */
  static int move(String key, int at, int count, int page, IntPredicate choosable) {
    return switch (key) {
      case "ArrowDown" -> around(count, at, 1, choosable);
      case "ArrowUp" -> around(count, at, -1, choosable);
      case "PageDown" -> towards(count, Math.min(Math.max(at, 0) + page, count - 1), 1, choosable);
      case "PageUp" -> towards(count, Math.max(at - page, 0), -1, choosable);
      case "Home" -> step(count, -1, 1, choosable);
      case "End" -> step(count, count, -1, choosable);
      default -> UNHANDLED;
    };
  }

  /** The next row that can be chosen in this direction, or -1. */
  static int step(int count, int from, int direction, IntPredicate choosable) {
    for (int at = from + direction; at >= 0 && at < count; at += direction) {
      if (choosable.test(at)) {
        return at;
      }
    }
    return -1;
  }

  private static int around(int count, int at, int direction, IntPredicate choosable) {
    int next = at < 0 ? -1 : step(count, at, direction, choosable);
    return next >= 0 ? next : step(count, direction > 0 ? -1 : count, direction, choosable);
  }

  /** The row itself if choosable, else the nearest in this direction, else back. */
  private static int towards(int count, int row, int direction, IntPredicate choosable) {
    if (row >= 0 && row < count && choosable.test(row)) {
      return row;
    }
    int found = step(count, row, direction, choosable);
    return found >= 0 ? found : step(count, row, -direction, choosable);
  }
}
