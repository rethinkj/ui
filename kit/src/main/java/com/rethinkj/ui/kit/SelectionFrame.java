package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;

/** The shared frame of a select and a closed combo box; each keeps its own input behaviour. */
final class SelectionFrame {

  private SelectionFrame() {}

  static El frame(Theme t, String text, El lead, boolean enabled) {
    El box = row()
        .role("combobox")
        .label(text)
        .disabled(!enabled)
        .w(t.size().field())
        .h(t.size().control())
        .padding(0, t.space().m())
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), enabled ? t.border().strong() : t.border().divider())
        .motion(t.motion().feedback());
    if (lead != null) {
      box.child(leading(t, lead));
    }
    return box.children(
        label(t, text)
            .color(enabled ? t.text().primary() : t.text().disabled())
            .grow(1)
            .minSize(0, 0)
            .ellipsis(),
        Icon.icon(t, Select.OPENS, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
  }

  /** An item's own element in an icon's square, which takes no input. */
  static El leading(Theme t, El lead) {
    return el().key("lead")
        .size(t.size().icon(), t.size().icon())
        .shrink(0)
        .center()
        .child(lead.shrink(0));
  }
}
