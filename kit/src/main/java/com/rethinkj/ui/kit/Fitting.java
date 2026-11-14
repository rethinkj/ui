package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

/** The measured widths and fitting of a row whose overflow goes into a menu. */
final class Fitting {

  enum Direction {
    PREFIX,
    SUFFIX
  }

  private float width = Float.NaN;
  private final Map<String, Float> widths = new HashMap<>();

  /** Fills the width but stays a control high, also in a column with room to spare. */
  El row(Theme t) {
    return El.row()
        .h(t.size().control())
        .grow(1)
        .minSize(0, t.size().control())
        .maxSize(Float.POSITIVE_INFINITY, t.size().control())
        .gap(t.space().xs())
        .align(Style.Align.CENTER)
        .clip()
        .onResize((w, h) -> width = w);
  }

  /** Caches the width layout gives an item for later builds. */
  El.SizeHandler measure(String id) {
    return (w, h) -> widths.put(id, w);
  }

  /** An item's last measured width, or NaN until layout has measured it. */
  float width(String id) {
    return widths.getOrDefault(id, Float.NaN);
  }

  /**
   * How many items of the prefix or suffix fit, keeping at least {@code mandatory}, with room
   * for More when some are hidden. Shows everything until the row and every item have been
   * measured. A null item width is a flexible spacer: it contributes neither width nor gap.
   */
  int fit(Theme t, int count, Direction direction, int mandatory, IntFunction<Float> itemWidth) {
    if (Float.isNaN(width)) {
      return count;
    }
    float gap = t.space().xs();
    float[] costs = new float[count];
    float total = 0;
    for (int i = 0; i < count; i++) {
      Float w = itemWidth.apply(i);
      if (w == null) {
        continue;
      }
      if (Float.isNaN(w)) {
        return count;
      }
      // The gap stays with its item, also at the start of a suffix after More.
      costs[i] = w + (i == 0 ? 0 : gap);
      total += costs[i];
    }
    if (total <= width) {
      return count;
    }
    float room = width - widths.getOrDefault("more", t.size().control()) - gap;
    int shown = count;
    while (shown > mandatory && total > room) {
      total -= costs[direction == Direction.PREFIX ? shown - 1 : count - shown];
      shown--;
    }
    return shown;
  }
}
