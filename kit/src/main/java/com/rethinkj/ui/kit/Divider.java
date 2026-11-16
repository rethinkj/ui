package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/**
 * A hairline between parts of a view, in {@link Theme.Borders#divider} and {@link
 * Theme.Sizes#stroke} wide: across a column, upright in a row, or with a label in its middle, as
 * {@code or} between two ways of signing in. It takes the whole width of a column, or the whole
 * height of a row, and no input. In a tree dump it is {@code role=separator}, with its label if it
 * has one.
 */
public final class Divider {

  private Divider() {}

  /** A line across a column. */
  public static El divider(Theme theme) {
    return line(theme).role("separator");
  }

  /** A line across a column with a label in muted text in its middle. */
  public static El divider(Theme theme, String label) {
    return row()
        .role("separator")
        .label(label)
        .gap(theme.space().m())
        .align(Style.Align.CENTER)
        .children(
            line(theme).grow(1),
            label(theme, Label.Variant.MUTED, label),
            line(theme).grow(1));
  }

  /** An upright line in a row, as high as the row. */
  public static El vertical(Theme theme) {
    return el().role("separator")
        .w(theme.size().stroke())
        .shrink(0)
        .fill(theme.border().divider())
        .alignSelf(Style.Align.STRETCH);
  }

  private static El line(Theme theme) {
    return el().h(theme.size().stroke()).shrink(0).fill(theme.border().divider());
  }
}
