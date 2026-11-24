package com.rethinkj.ui.kit;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

/** The measured widths and fitting of a row whose overflow goes into a menu. */
final class Fitting {

  private float width = Float.NaN;
  private final Map<String, Float> widths = new HashMap<>();
  private int shown = Integer.MAX_VALUE; // how many items the last fit showed

  /** Fills the width but stays a control high, also in a column with room to spare. */
  El row(Theme theme) {
    return El.row()
        .h(theme.size().control())
        .grow(1)
        .minSize(0, theme.size().control())
        .maxSize(Float.POSITIVE_INFINITY, theme.size().control())
        .gap(theme.space().xs())
        .align(Style.Align.CENTER)
        .clip()
        .onResize((w, h) -> width = w);
  }

  /** Caches the width layout gives an item for later builds. */
  El.SizeHandler measure(String id) {
    return (w, h) -> widths.put(id, w);
  }

  /** The row's last measured width, or NaN until layout has measured it. */
  float width() {
    return width;
  }

  /** An item's last measured width, or NaN until layout has measured it. */
  float width(String id) {
    return widths.getOrDefault(id, Float.NaN);
  }

  /** Caches the width layout gives an item under its id and under its place in the row. */
  El.SizeHandler measure(String id, int place) {
    return (w, h) -> {
      widths.put(id, w);
      widths.put("#" + place, w);
    };
  }

  /**
   * An item's last measured width, else, for an item that was shown, the width last measured at its
   * place: layout tells no new width to an item whose new id, as a changed label, comes with the
   * same size. A hidden item with a new id has no width yet, so the next fit shows it to measure it.
   */
  float width(String id, int place) {
    Float w = widths.get(id);
    if (w == null && place < shown) {
      w = widths.get("#" + place);
    }
    return w != null ? w : Float.NaN;
  }

  /**
   * How many items from the start fit, with room for More when some are hidden. Shows everything
   * until the row and every item have been measured. A null item width is a flexible spacer: it
   * contributes neither width nor gap.
   */
  int fit(Theme theme, int count, IntFunction<Float> itemWidth) {
    shown = fitted(theme, count, itemWidth);
    return shown;
  }

  private int fitted(Theme theme, int count, IntFunction<Float> itemWidth) {
    if (Float.isNaN(width)) {
      return count;
    }
    float gap = theme.space().xs();
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
      costs[i] = w + (i == 0 ? 0 : gap);
      total += costs[i];
    }
    if (total <= width) {
      return count;
    }
    float room = width - widths.getOrDefault("more", theme.size().control()) - gap;
    int visible = count;
    while (visible > 0 && total > room) {
      total -= costs[--visible];
    }
    return visible;
  }
}
