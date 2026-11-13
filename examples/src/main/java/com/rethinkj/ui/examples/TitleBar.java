package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.kit.Theme;

/**
 * The title bar of a frameless window: a coloured mark, the title, the app's own controls if it
 * has some, and minimize, maximize and close buttons. The system moves the window by the bar and
 * acts on its buttons; the app's controls take their presses, as they have handlers.
 */
final class TitleBar {

  private TitleBar() {}

  static El titleBar(Theme t, String title, int mark, El... controls) {
    int glyph = t.text().primary();
    El bar = row().wFull().h(32).shrink(0).align(Style.Align.CENTER)
        .fill(t.surface().panel())
        .windowPart(WindowPart.DRAG)
        .child(el().size(12, 12).radius(6).fill(mark).margin(0, 0, 0, 10))
        .child(label(t, title).margin(0, 0, 0, t.space().m()))
        .child(el().grow(1));
    if (controls.length > 0) {
      bar.child(row().gap(t.space().l()).margin(0, t.space().l(), 0, 0)
          .align(Style.Align.CENTER).children(controls));
    }
    return bar.child(button(WindowPart.MINIMIZE, "Minimize", el().size(10, 1).fill(glyph)))
        .child(button(WindowPart.MAXIMIZE, "Maximize", el().size(10, 10).border(1, glyph)))
        .child(button(WindowPart.CLOSE, "Close", cross(glyph)));
  }

  private static El button(WindowPart part, String label, El glyph) {
    return el().role("button").label(label).size(46, 32).center().windowPart(part).child(glyph);
  }

  /** An X, from two diagonals of small squares. */
  private static El cross(int colour) {
    El cross = el().size(10, 10);
    for (int i = 0; i <= 8; i++) {
      cross.child(el().size(2, 2).absolute(i, i).fill(colour))
          .child(el().size(2, 2).absolute(8 - i, i).fill(colour));
    }
    return cross;
  }
}
