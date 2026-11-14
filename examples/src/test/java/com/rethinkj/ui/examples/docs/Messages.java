package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;

import com.rethinkj.ui.El;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Theme;
import java.util.List;
import java.util.stream.IntStream;

/** The example of rows as high as their content, for the page about lists of many rows. */
final class Messages {

  final Theme t = Theme.DARK;
  final List<String> log = IntStream.range(0, 10_000)
      .mapToObj(i -> i % 7 == 0
          ? "Query " + i + " took 4.2 s: the planner chose a sequential scan of orders, which"
              + " has no index on customer_id"
          : "Query " + i + " took 12 ms")
      .toList();

  // docs:start row-heights
  // In the state: estimated from the length of the text until a row is built, then measured.
  final RowHeights heights = new RowHeights(i -> 20 + 16 * Math.floor(log.get(i).length() / 40.0));
  // docs:end row-heights

  static El view(Messages s) {
    Theme t = s.t;
    // docs:start row-heights-list
    El list = col()
        .key("log")
        .role("list")
        .label("Log")
        .wFull()
        .grow(1)
        .scrollbars(t.scrollbars())
        .rows(
            s.log.size(),
            s.heights, // the same object on every build
            i -> col()
                .key("row-" + i)
                .role("listitem")
                .wFull()
                .padding(t.space().xs(), t.space().m())
                .child(Label.label(t, s.log.get(i)).wrapText()));
    // docs:end row-heights-list
    return Frame.of(t, list);
  }
}
