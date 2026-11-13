package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/**
 * A hairline between parts of a view, in {@link Theme.Borders#subtle} and {@link
 * Theme.Sizes#stroke} wide: across a column, upright in a row, or with a label in its middle, as
 * {@code or} between two ways of signing in. It takes the whole width of a column, or the whole
 * height of a row, and no input. In a tree dump it is {@code role=separator}, with its label if it
 * has one.
 */
public final class Divider {

  private Divider() {}

  /** A line across a column. */
  public static El divider(Theme t) {
    return line(t).role("separator");
  }

  /** A line across a column with a label in muted text in its middle. */
  public static El divider(Theme t, String label) {
    return row()
        .role("separator")
        .label(label)
        .gap(t.space().m())
        .align(Style.Align.CENTER)
        .children(line(t).grow(1), label(t, Label.Variant.MUTED, label), line(t).grow(1));
  }

  /** An upright line in a row, as high as the row. */
  public static El vertical(Theme t) {
    return el().role("separator")
        .w(t.size().stroke())
        .shrink(0)
        .fill(t.border().subtle())
        .alignSelf(Style.Align.STRETCH);
  }

  private static El line(Theme t) {
    return el().h(t.size().stroke()).shrink(0).fill(t.border().subtle());
  }
}
