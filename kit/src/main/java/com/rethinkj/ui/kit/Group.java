package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.render.Scene;

/**
 * Rows in a card with a hairline between each two, as a page of settings lists them: each row a
 * title with an optional icon before it and a description below it, and one control at the right.
 * A row that is a switch, a link to more, or opens to show more rows is one press target and one
 * stop for Tab; a row with any other control leaves the presses to the control.
 *
 * <pre>{@code
 * group(t,
 *     switchRow(t, "Word wrap", "Wrap long lines at the edge of the editor", s.wrap, s::flipWrap),
 *     row(t, "Tab size", null, numberField(t, s.tabSize, 1, 16, 1, v -> s.tabSize = v)),
 *     linkRow(t, Symbol.KEY, "Keymap", null, "Default", s::showKeymap),
 *     expander(t, "Advanced", "Options most people never change", s.advanced, s::flipAdvanced,
 *         switchRow(t, "Insert spaces", null, s.spaces, s::flipSpaces)))
 * }</pre>
 *
 * <p>The rows share one height rule, so that a column of them lines up: the text starts {@link
 * Theme.Spacing#l} in from the card's edge, a row is at least as high as a control and {@link
 * Theme.Spacing#m} above and below it, and a description that wraps makes it higher. The control
 * keeps its width when the group narrows; the title and the description wrap instead. A row's
 * hover and pressed looks and its focus ring lie inside the card, the ring in a band of {@link
 * Scene#RING} around the row.
 *
 * <p>A row's control is named after the row: a text field, or a control that has no label of its
 * own, gets the title as its label, so that {@code role=spinbutton label="Tab size"} finds the
 * field; a select keeps its choice as its label. In a tree dump a switch row is {@code
 * role=switch} with the title as its label and {@code checked} or {@code checked=false}, a link row
 * {@code role=button} with its value as {@code value}, an expander {@code role=button} with {@code
 * expanded} or {@code expanded=false}, and each is {@code disabled} without a handler.
 */
public final class Group {

  private Group() {}

  /**
   * A card with these rows, or any elements of this class, and a hairline between each two; null
   * rows are left out, so that a view can write {@code advanced ? row(...) : null}.
   */
  public static El group(Theme t, El... rows) {
    El group = Card.card(t).padding(Scene.RING).gap(0);
    lines(t, group, Section.present(rows));
    return group;
  }

  /** A row with a title, an optional description and an optional control at the right. */
  public static El row(Theme t, String title, String description, El control) {
    return row(t, null, title, description, control);
  }

  /**
   * @param icon before the title; null for none
   * @param description below the title in muted text, wrapping; null for none
   * @param control at the right, such as a select, a text field or a button; null for none
   */
  public static El row(Theme t, Symbol icon, String title, String description, El control) {
    El row = base(t, icon, title, description, true);
    if (control != null) {
      row.child(Form.named(control, title).shrink(0));
    }
    return row;
  }

  /** A row that is a switch, with the switch at the right. */
  public static El switchRow(
      Theme t, String title, String description, boolean on, Runnable onToggle) {
    return switchRow(t, null, title, description, on, onToggle);
  }

  /**
   * A row that is a switch: a press anywhere on it flips the switch at its right, and so do Enter
   * and Space while it has the focus.
   *
   * @param onToggle runs when the row is pressed; null disables it
   */
  public static El switchRow(
      Theme t, Symbol icon, String title, String description, boolean on, Runnable onToggle) {
    boolean enabled = onToggle != null;
    El row = base(t, icon, title, description, enabled)
        .role("switch")
        .label(title)
        .checked(on)
        .disabled(!enabled)
        .child(Toggle.track(t, on, enabled).shrink(0));
    return press(t, row, onToggle);
  }

  /** A row that leads somewhere, with its current value and a chevron at the right. */
  public static El linkRow(
      Theme t, String title, String description, String value, Runnable onPress) {
    return linkRow(t, null, title, description, value, onPress);
  }

  /**
   * A row that leads to more, such as a page or a dialog: its current value in muted text and a
   * chevron at the right, and a press anywhere on it runs {@code onPress}.
   *
   * @param value what is set there now, such as {@code Default}; null for none
   * @param onPress runs when the row is pressed; null disables it
   */
  public static El linkRow(
      Theme t, Symbol icon, String title, String description, String value, Runnable onPress) {
    boolean enabled = onPress != null;
    El row = base(t, icon, title, description, enabled)
        .role("button")
        .label(title)
        .value(value)
        .disabled(!enabled);
    if (value != null) {
      row.child(label(t, Label.Variant.MUTED, value)
          .color(enabled ? t.text().muted() : t.text().disabled())
          .shrink(0));
    }
    row.child(icon(t, Symbol.CHEVRON_RIGHT, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    return press(t, row, onPress);
  }

  /** A row that opens to show more rows below it. */
  public static El expander(
      Theme t, String title, String description, boolean open, Runnable onToggle, El... content) {
    return expander(t, null, title, description, open, onToggle, content);
  }

  /**
   * A row that opens to show more below it, indented by {@link Theme.Spacing#xl} with a hairline
   * between each two: rows of this class, or {@link #box} for anything else. Whether it is open is
   * the app's state; a press on the row, and Enter and Space while it has the focus, run {@code
   * onToggle}. The chevron at its right points down while it is closed and up while it is open.
   *
   * @param onToggle runs when the row is pressed; null disables it
   */
  public static El expander(
      Theme t,
      Symbol icon,
      String title,
      String description,
      boolean open,
      Runnable onToggle,
      El... content) {
    boolean enabled = onToggle != null;
    El head = base(t, icon, title, description, enabled)
        .role("button")
        .label(title)
        .expanded(open)
        .disabled(!enabled)
        .child(icon(
            t,
            open ? Symbol.CHEVRON_UP : Symbol.CHEVRON_DOWN,
            enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    El expander = col().child(press(t, head, onToggle));
    if (open && content.length > 0) {
      El nested = col();
      nested.style().paddingLeft = Len.px(t.space().xl());
      lines(t, nested, Section.present(content));
      expander.child(line(t)).child(nested);
    }
    return expander;
  }

  /**
   * Anything in a group or an expander, as wide as a row and with a row's padding, such as a
   * radio group or a paragraph: its children stacked {@link Theme.Spacing#m} apart.
   */
  public static El box(Theme t, El... content) {
    return col()
        .padding(t.space().m() - Scene.RING, t.space().l() - Scene.RING)
        .gap(t.space().m())
        .children(content);
  }

  /** A row's icon and text, with the padding, radius and height every row has. */
  private static El base(Theme t, Symbol icon, String title, String description, boolean enabled) {
    El text = col()
        .grow(1)
        .gap(t.space().xs())
        .child(label(t, title)
            .color(enabled ? t.text().primary() : t.text().disabled())
            .wrapText());
    text.style().minWidth = Len.ZERO;
    if (description != null) {
      text.child(label(t, Label.Variant.MUTED, description)
          .color(enabled ? t.text().muted() : t.text().disabled())
          .wrapText());
    }
    El row = El.row()
        .padding(t.space().m() - Scene.RING, t.space().l() - Scene.RING)
        .gap(t.space().l())
        .align(Style.Align.CENTER)
        // Concentric with the card's corner, so that the ring follows it in the first row.
        .radius(t.radius().large() - t.size().stroke() - Scene.RING)
        .motion(t.motion().feedback());
    row.style().minHeight = Len.px(t.size().control() + 2 * (t.space().m() - Scene.RING));
    if (icon != null) {
      row.child(icon(t, icon, enabled ? Icon.Variant.NORMAL : Icon.Variant.DISABLED));
    }
    return row.child(text);
  }

  /** Makes a row a press target, unless the handler is null. */
  private static El press(Theme t, El row, Runnable onPress) {
    if (onPress == null) {
      return row;
    }
    return row.focusable()
        .keepFocus()
        .focusRing(t.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(t.surface().hover()))
        .whenPressed(e -> e.fill(t.surface().pressed()));
  }

  /** Adds the elements with a hairline between each two. */
  private static void lines(Theme t, El parent, El... children) {
    for (int i = 0; i < children.length; i++) {
      if (i > 0) {
        parent.child(line(t));
      }
      parent.child(children[i]);
    }
  }

  /**
   * A hairline across the whole card, past the band that its parent leaves for the rows' rings,
   * with such a band above and below it.
   */
  private static El line(Theme t) {
    return el().h(t.size().stroke())
        .shrink(0)
        .fill(t.border().subtle())
        .margin(Scene.RING, -Scene.RING, Scene.RING, -Scene.RING);
  }
}
