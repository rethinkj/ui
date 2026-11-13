package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowPart;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;

/**
 * The title bar of a frameless window: a coloured mark, the title, the app's own controls if it
 * has some, and minimize, maximize and close buttons. The system moves the window by the bar and
 * acts on its buttons; the app's controls take their presses, as they have handlers. Minimize and
 * close say what they do in a tooltip; maximize has none, since Windows 11 shows its snap layouts
 * there.
 */
final class TitleBar {

  private TitleBar() {}

  static El titleBar(Theme t, String title, int mark, El... controls) {
    int glyph = t.text().primary();
    El bar = row().wFull().h(32).shrink(0).align(Style.Align.CENTER)
        .fill(t.surface().window())
        .windowPart(WindowPart.DRAG)
        .child(el().size(10, 10).radius(3).fill(mark).margin(0, 0, 0, 12))
        .child(label(t, Label.Variant.STRONG, title).margin(0, 0, 0, t.space().m()))
        .child(el().grow(1));
    if (controls.length > 0) {
      bar.child(row().gap(t.space().l()).margin(0, t.space().l(), 0, 0)
          .align(Style.Align.CENTER).children(controls));
    }
    return bar.child(Tooltip.tooltip(t,
            button(WindowPart.MINIMIZE, "Minimize", el().size(10, 1).fill(glyph)), "Minimize"))
        .child(button(WindowPart.MAXIMIZE, "Maximize", el().size(10, 10).border(1, glyph)))
        .child(Tooltip.tooltip(t, close(t, glyph), "Close"));
  }

  private static El button(WindowPart part, String label, El glyph) {
    return el().role("button").label(label).size(46, 32).center().windowPart(part).child(glyph);
  }

  /** The close button, red with a white cross under the pointer, as the system's is. */
  private static El close(Theme t, int glyph) {
    El cross = el().size(10, 10);
    for (int i = 0; i <= 8; i++) {
      cross.child(el().size(2, 2).absolute(i, i).fill(glyph))
          .child(el().size(2, 2).absolute(8 - i, i).fill(glyph));
    }
    return button(WindowPart.CLOSE, "Close", cross).whenHovered(b -> {
      b.fill(t.caption().closeHover());
      for (El square : cross.children()) {
        square.fill(t.caption().onClose());
      }
    });
  }
}
