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
 * group(
 *     switchRow("Word wrap", "Wrap long lines at the edge of the editor",
 *         state.wrap, state::flipWrap),
 *     row("Tab size", null,
 *         numberField(state.tabSize, 1, 16, 1, value -> state.tabSize = value)),
 *     linkRow(Symbol.KEY, "Keymap", null, "Default", state::showKeymap),
 *     expander("Advanced", "Options most people never change",
 *         state.advanced, state::flipAdvanced,
 *         switchRow("Insert spaces", null, state.spaces, state::flipSpaces)))
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
  public static El group(El... rows) {
    Theme theme = Theme.CURRENT.get();
    El group = Card.card().padding(Scene.RING).gap(0);
    lines(theme, group, Section.present(rows));
    return group;
  }

  /** A row with a title, an optional description and an optional control at the right. */
  public static El row(String title, String description, El control) {
    return row(null, title, description, control);
  }

  /**
   * @param icon before the title; null for none
   * @param description below the title in muted text, wrapping; null for none
   * @param control at the right, such as a select, a text field or a button; null for none
   */
  public static El row(Icon icon, String title, String description, El control) {
    Theme theme = Theme.CURRENT.get();
    // A control that says what is wrong with its value ({@link El#errorMessage}) has that in
    // place of the description, in the danger color, as a form's field has.
    El target = control == null ? null : Form.target(control);
    String error = target == null ? null : target.errorMessage();
    El row = base(theme, icon, title, error != null ? error : description, true);
    if (error != null) {
      row.children().getLast().children().getLast().color(theme.status().danger());
    }
    if (control != null) {
      row.child(Form.named(control, title).shrink(0));
    }
    return row;
  }

  /** A row that is a switch, with the switch at the right. */
  public static El switchRow(String title, String description, boolean on, Runnable onToggle) {
    return switchRow(null, title, description, on, onToggle);
  }

  /**
   * A row that is a switch: a press anywhere on it flips the switch at its right, and so do Enter
   * and Space while it has the focus.
   *
   * @param onToggle runs when the row is pressed; null disables it
   */
  public static El switchRow(
      Icon icon, String title, String description, boolean on, Runnable onToggle) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onToggle != null;
    El row = base(theme, icon, title, description, enabled)
        .role("switch")
        .label(title)
        .checked(on)
        .disabled(!enabled)
        .child(Toggle.track(theme, on, enabled).shrink(0));
    return press(theme, row, onToggle);
  }

  /** A row that leads somewhere, with its current value and a chevron at the right. */
  public static El linkRow(String title, String description, String value, Runnable onPress) {
    return linkRow(null, title, description, value, onPress);
  }

  /**
   * A row that leads to more, such as a page or a dialog: its current value in muted text and a
   * chevron at the right, and a press anywhere on it runs {@code onPress}.
   *
   * @param value what is set there now, such as {@code Default}; null for none
   * @param onPress runs when the row is pressed; null disables it
   */
  public static El linkRow(
      Icon icon, String title, String description, String value, Runnable onPress) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onPress != null;
    El row = base(theme, icon, title, description, enabled)
        .role("button")
        .label(title)
        .value(value)
        .disabled(!enabled);
    if (value != null) {
      row.child(label(Label.Variant.MUTED, value)
          .color(enabled ? theme.text().muted() : theme.text().disabled())
          .shrink(0));
    }
    row.child(icon(Symbol.CHEVRON_RIGHT, enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    return press(theme, row, onPress);
  }

  /** A row that opens to show more rows below it. */
  public static El expander(
      String title, String description, boolean open, Runnable onToggle, El... content) {
    return expander(null, title, description, open, onToggle, content);
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
      Icon icon, String title, String description, boolean open, Runnable onToggle, El... content) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onToggle != null;
    El head = base(theme, icon, title, description, enabled)
        .role("button")
        .label(title)
        .expanded(open)
        .disabled(!enabled)
        .child(icon(
            open ? Symbol.CHEVRON_UP : Symbol.CHEVRON_DOWN,
            enabled ? Icon.Variant.MUTED : Icon.Variant.DISABLED));
    El expander = col().child(press(theme, head, onToggle));
    if (open && content.length > 0) {
      El nested = col();
      nested.style().paddingLeft = Len.px(theme.space().xl());
      lines(theme, nested, Section.present(content));
      expander.child(line(theme)).child(nested);
    }
    return expander;
  }

  /**
   * Anything in a group or an expander, as wide as a row and with a row's padding, such as a
   * radio group or a paragraph: its children stacked {@link Theme.Spacing#m} apart.
   */
  public static El box(El... content) {
    Theme theme = Theme.CURRENT.get();
    return col()
        .padding(theme.space().m() - Scene.RING, theme.space().l() - Scene.RING)
        .gap(theme.space().m())
        .children(content);
  }

  /** A row's icon and text, with the padding, radius and height every row has. */
  private static El base(
      Theme theme, Icon icon, String title, String description, boolean enabled) {
    El text = col()
        .grow(1)
        .gap(theme.space().xs())
        .child(label(title)
            .color(enabled ? theme.text().primary() : theme.text().disabled())
            .wrapText());
    text.style().minWidth = Len.ZERO;
    if (description != null) {
      text.child(label(Label.Variant.MUTED, description)
          .color(enabled ? theme.text().muted() : theme.text().disabled())
          .wrapText());
    }
    El row = El.row()
        .padding(theme.space().m() - Scene.RING, theme.space().l() - Scene.RING)
        .gap(theme.space().l())
        .align(Style.Align.CENTER)
        // Concentric with the card's corner, so that the ring follows it in the first row.
        .radius(theme.radius().large() - theme.size().stroke() - Scene.RING)
        .motion(theme.motion().feedback());
    row.style().minHeight = Len.px(theme.size().control() + 2 * (theme.space().m() - Scene.RING));
    if (icon != null) {
      row.child(icon(icon, enabled ? Icon.Variant.NORMAL : Icon.Variant.DISABLED));
    }
    return row.child(text);
  }

  /** Makes a row a press target, unless the handler is null. */
  private static El press(Theme theme, El row, Runnable onPress) {
    if (onPress == null) {
      return row;
    }
    return row.focusable()
        .keepFocus()
        .focusRing(theme.accent().edge())
        .cursor(Cursor.POINTER)
        .onPress(onPress)
        .whenHovered(e -> e.fill(theme.surface().hover()))
        .whenPressed(e -> e.fill(theme.surface().pressed()));
  }

  /** Adds the elements with a hairline between each two. */
  private static void lines(Theme theme, El parent, El... children) {
    for (int i = 0; i < children.length; i++) {
      if (i > 0) {
        parent.child(line(theme));
      }
      parent.child(children[i]);
    }
  }

  /**
   * A hairline across the whole card, past the band that its parent leaves for the rows' rings,
   * with such a band above and below it.
   */
  private static El line(Theme theme) {
    return el().h(theme.size().stroke())
        .shrink(0)
        .fill(theme.border().divider())
        .margin(Scene.RING, -Scene.RING, Scene.RING, -Scene.RING);
  }
}
