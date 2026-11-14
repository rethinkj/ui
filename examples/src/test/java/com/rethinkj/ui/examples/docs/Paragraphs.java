package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.text;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Theme;

/** The example of a link inside a read-only paragraph, for the page about text fields. */
final class Paragraphs {

  static final String NOTE =
      "The orders table has no index on customer_id. See the query plan" + " before you add one.";

  final Theme t = Theme.DARK;
  String opened = "";

  static El view(Paragraphs s) {
    Theme t = s.t;
    // docs:start range
    int from = NOTE.indexOf("the query plan");
    El paragraph = text(NOTE)
        .editable(v -> {}) // read-only below: it selects and copies, and takes no edits
        .readOnly()
        .multiline()
        .wrapText()
        .fitLines() // as high as its rows: it never scrolls
        .skipTab() // Tab goes to the link, not to the paragraph
        .key("note")
        .color(t.text().primary())
        .wFull()
        .child(el().key("plan")
            .role("link")
            .label("the query plan")
            .range(from, from + "the query plan".length()) // over these characters
            .focusable()
            .cursor(Cursor.POINTER)
            .onPress(() -> s.opened = "plan")
            .whenHovered(link -> link.fill(t.surface().hover())));
    // docs:end range
    return Frame.of(t, paragraph.w(260));
  }
}
