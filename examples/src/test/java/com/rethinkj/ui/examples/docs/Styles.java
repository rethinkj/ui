package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.El.text;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Theme;

/** The example of the layout page's Style and Len, beyond the chained calls. */
final class Styles {

  final Theme t = Theme.DARK;

  static El view(Styles s) {
    Theme t = s.t;
    // docs:start style
    El bar = el().key("bar").h(8).fill(t.accent().color());
    bar.style().width = Len.percent(0.4f); // 40 % of the parent's content box: 1 is all of it

    El thumb = el().key("thumb").w(96).fill(t.surface().raised());
    thumb.style().aspectRatio = 16 / 9f; // the height follows the width

    El tags = row().key("tags").wrap().wFull();
    tags.style().rowGap = Len.px(4); // between the lines that wrap
    tags.style().columnGap = Len.px(12); // between the children of a line

    El latest = col().key("latest").h(60).clip();
    latest.style().direction = Style.Direction.COLUMN_REVERSE; // the first child at the bottom
    latest.style().overflowY = Style.Overflow.HIDDEN; // clipped, and the wheel does not scroll

    El baseline = row().key("baseline").align(Style.Align.BASELINE); // text lines up on its line

    El inset = el().key("inset").w(120).h(24).padding(4);
    inset.style().boxSizing = Style.BoxSizing.CONTENT_BOX; // 120 wide inside the padding
    // docs:end style
    for (String tag : new String[] {"orders", "customers", "order_items", "invoices"}) {
      tags.child(text(tag).color(t.text().muted()));
    }
    for (int i = 1; i <= 5; i++) {
      latest.child(text("Query " + i).color(t.text().primary()));
    }
    baseline
        .child(text("Rows").fontSize(20).color(t.text().primary()))
        .child(text("1 000 000").color(t.text().muted()));
    return Frame.of(
        t,
        col().wFull().h(16).child(bar),
        thumb,
        tags,
        latest,
        baseline,
        inset.fill(t.surface().raised()),
        sparkline(t));
  }

  static El sparkline(Theme t) {
    // docs:start measure
    // A leaf that sizes itself: as wide as there is room for, at most 200, and 24 high.
    return el().key("sparkline")
        .fill(t.surface().band())
        .measure((knownWidth, knownHeight, availableWidth, availableHeight) -> {
          float width = Float.isNaN(knownWidth)
              ? Float.isFinite(availableWidth) ? Math.min(availableWidth, 200) : 200
              : knownWidth;
          return new Measure.Size(width, 24);
        });
    // docs:end measure
  }
}
