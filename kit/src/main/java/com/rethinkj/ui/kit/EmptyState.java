package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/** What a list or a page shows while it has nothing to show. */
public final class EmptyState {

  private EmptyState() {}

  /**
   * What a list shows when it has nothing to show, centred in the room it is given: a large muted
   * icon, a title, a text and an optional action below, such as "No connections match" with a
   * button that clears the filter. Title and text break into lines where the room is narrower than
   * they are, as in a side panel, each line in the middle. In a tree dump it is {@code role=status} with the title as its
   * label.
   *
   * @param text below the title; null for none
   * @param action a button below the text; null for none
   */
  public static El emptyState(Theme t, Symbol symbol, String title, String text, El action) {
    El empty = col()
        .role("status")
        .label(title)
        .grow(1)
        .center()
        .padding(t.space().xxl())
        .gap(t.space().m())
        .child(icon(t, symbol, Icon.Variant.MUTED, 2 * t.size().control()))
        .child(lines(label(t, Label.Variant.STRONG, title)));
    if (text != null) {
      empty.child(lines(label(t, Label.Variant.MUTED, text)));
    }
    if (action != null) {
      empty.child(action.margin(t.space().s(), 0, 0, 0));
    }
    return empty;
  }

  /** As wide as the room there is, so that the text breaks there, each line in the middle. */
  private static El lines(El text) {
    return text.wFull().wrapText().textAlign(Style.Align.CENTER);
  }
}
