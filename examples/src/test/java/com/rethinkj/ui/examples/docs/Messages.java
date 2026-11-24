package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;

import com.rethinkj.ui.El;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.ScrollState;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Theme;
import java.util.List;
import java.util.stream.IntStream;

/** The example of rows as high as their content, for the page about lists of many rows. */
final class Messages {

  final List<String> log = IntStream.range(0, 10_000)
      .mapToObj(number -> number % 7 == 0
          ? "Query " + number + " took 4.2 s: the planner chose a sequential scan of orders, which"
              + " has no index on customer_id"
          : "Query " + number + " took 12 ms")
      .toList();

  // docs:start row-heights
  // In the state: estimated from the length of the text until a row is built, then measured.
  final RowHeights heights =
      new RowHeights(row -> 20 + 16 * Math.floor(log.get(row).length() / 40.0));
  // docs:end row-heights

  static El view(Messages state) {
    Theme theme = Theme.CURRENT.get();
    // docs:start row-heights-list
    El list = col()
        .key("log")
        .role("list")
        .label("Log")
        .wFull()
        .grow(1)
        .scrollbars(theme.scrollbars())
        .rows(
            state.log.size(),
            state.heights, // the same object on every build
            row -> col()
                .key("row-" + row)
                .role("listitem")
                .wFull()
                .padding(theme.space().xs(), theme.space().m())
                .child(Label.label(state.log.get(row)).wrapText()));
    // docs:end row-heights-list
    return Frame.of(list);
  }

  // docs:start follow-end
  final ScrollState transcript = new ScrollState().followEnd();

  void onScroll() {} // rebuilds the view so the button appears when scrolling away

  static El following(Messages state) {
    Theme theme = Theme.CURRENT.get();
    El list = col()
        .key("transcript")
        .wFull()
        .grow(1)
        .scrollbars(theme.scrollbars())
        .scrollState(state.transcript)
        .onUserScroll(state::onScroll)
        .rows(
            state.log.size(),
            state.heights,
            row -> col()
                .key("row-" + row)
                .wFull()
                .padding(theme.space().xs(), theme.space().m())
                .child(Label.label(state.log.get(row)).wrapText()));
    El root = col().wFull().hFull().child(list);
    if (!state.transcript.atEnd()) {
      root.child(
          Button.button(Button.Variant.SECONDARY, "Jump to newest", state.transcript::scrollToEnd));
    }
    return root;
  }
  // docs:end follow-end
}
