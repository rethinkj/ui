package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Theme.over;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/** The frame of a closed combo box: the current choice and a chevron in a text field's edge. */
final class SelectionFrame {

  private SelectionFrame() {}

  static El frame(Theme theme, String text, El lead, boolean enabled) {
    El box = row()
        .role("combobox")
        .label(text)
        .disabled(!enabled)
        .w(theme.size().field())
        .h(theme.size().control())
        .padding(0, theme.space().m(), 0, theme.space().m())
        .gap(theme.space().s())
        .align(Style.Align.CENTER)
        .radius(theme.radius().medium())
        .fill(theme.surface().raised())
        .motion(theme.motion().feedback());
    if (enabled) {
      TextField.edge(theme, box, theme.border().control(), theme.border().strong());
      int accent = theme.accent().color();
      box.whenFocused(e -> TextField.edge(theme, e, accent, accent));
    } else {
      TextField.edge(theme, box, theme.border().divider(), theme.border().divider());
    }
    if (lead != null) {
      box.child(leading(theme, lead));
    }
    return box.children(
        label(text)
            .color(enabled ? theme.text().primary() : theme.text().disabled())
            .grow(1)
            .minSize(0, 0)
            .ellipsis(),
        Icon.icon(Select.OPENS, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
  }

  /** The look under the pointer of an enabled frame: the hover fill and the whole frame strong. */
  static void hovered(Theme theme, El box) {
    int strong = theme.border().strong();
    TextField.edge(
        theme, box.fill(over(theme.surface().raised(), theme.surface().hover())), strong, strong);
  }

  /** An item's own element in an icon's square, which takes no input. */
  static El leading(Theme theme, El lead) {
    return el().key("lead")
        .size(theme.size().icon(), theme.size().icon())
        .shrink(0)
        .center()
        .child(lead.shrink(0));
  }
}
