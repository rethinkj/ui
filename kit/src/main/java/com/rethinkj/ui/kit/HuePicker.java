package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * A choice of one of the theme's {@link Theme.Hue}s, such as the color that marks a connection:
 * a round swatch per hue in {@link Radio#swatches}, filled with the hue's {@link
 * Theme.Accent#edge} of the theme, the color a tab's mark has ({@link Tabs.Tab#hue}), which
 * holds 3:1 on the surfaces in both default themes. A choice of no color, when the app allows
 * it, comes first: {@link Symbol#PROHIBITED} in muted text. In a tree dump it is {@code
 * role=radiogroup}, each swatch {@code role=radio} labeled by the hue's name as a person reads
 * it ({@code Red}, {@code Violet}) and {@code checked} or {@code checked=false}.
 *
 * <pre>{@code
 * // None first, then the ten hues
 * HuePicker.huePicker(state.hue, "None", hue -> state.hue = hue)
 * }</pre>
 *
 * <p>Where a row of eleven swatches takes too much room, as in a form, {@link #button} shows only
 * the chosen one and opens the row in a {@link Popover}.
 */
public final class HuePicker {

  private HuePicker() {}

  /**
   * The ten hues.
   *
   * @param selected the chosen hue; null for none
   * @param onSelect told the hue the user chooses, only when it changes; null disables the picker
   */
  public static El huePicker(Theme.Hue selected, Consumer<Theme.Hue> onSelect) {
    return huePicker(selected, null, onSelect);
  }

  /**
   * The ten hues after a choice of no color.
   *
   * @param selected the chosen hue; null for the choice of no color, or for none where there is
   *     none
   * @param none the name of the choice of no color, such as {@code None}; null for no such choice
   * @param onSelect told the hue the user chooses, null for no color, only when it changes; null
   *     disables the picker
   */
  public static El huePicker(Theme.Hue selected, String none, Consumer<Theme.Hue> onSelect) {
    return swatches(selected, none, onSelect, null);
  }

  /**
   * A square button as high as a text field that shows the chosen hue's swatch, or the symbol of
   * no color, and opens the {@link #huePicker(Theme.Hue, String, Consumer) row} in a {@link
   * Popover} below it, which fits beside a text field in a {@link Form#row}. A press on a swatch
   * chooses it and closes the popover; the arrow keys choose as in the row, and Enter or Escape
   * close it. The button has no label, so that its {@link Form#field} names it; in a tree dump it
   * is {@code role=button} with the name of the choice as its value and {@code expanded} or {@code
   * expanded=false}, and the open popover holds the {@code role=radiogroup}:
   *
   * <pre>{@code
   * Form.field("Color", HuePicker.button(state.hue, "None", state.huePopover, state::hue))
   * }</pre>
   *
   * @param selected the chosen hue; null for the choice of no color, or for none where there is
   *     none
   * @param none the name of the choice of no color, such as {@code None}; null for no such choice
   * @param state whether the popover is open; the app keeps it
   * @param onSelect told the hue the user chooses, null for no color, only when it changes; null
   *     disables the button
   */
  public static El button(
      Theme.Hue selected, String none, Popover.State state, Consumer<Theme.Hue> onSelect) {
    Theme theme = Theme.CURRENT.get();
    if (onSelect == null) {
      state.close();
    }
    El button = Button.showing(
            selected == null ? noColor(theme) : swatch(selected),
            onSelect == null ? null : state::toggle)
        .value(selected == null ? none : name(selected))
        .expanded(state.isOpen());
    if (!state.isOpen()) {
      return button;
    }
    // Around the row, whose own key handler chooses with the arrows.
    El row = el().child(swatches(selected, none, onSelect, state::close)).onKey(k -> {
      if (!k.name().equals("Enter") || k.modifiers() != 0) {
        return false;
      }
      if (!k.repeat()) { // the held Enter that opened it does not close it
        state.close();
      }
      return true;
    });
    // Holds the focus in the row, also after a press on the panel around it; in one group with
    // the button, whose press closes it once, also in a dialog.
    return button
        .dismissGroup(state)
        .overlay(Popover.panel(state, Texts.CURRENT.get().color(), row)
            .keepFocus()
            .holdFocus()
            .dismissGroup(state)
            .side(Side.BOTTOM, Style.Align.START, theme.space().xs()));
  }

  /** A picker of the value of a {@link Prop}; a prop that cannot be written disables it. */
  public static El huePicker(Prop<Theme.Hue> selected, String none) {
    Theme theme = Theme.CURRENT.get();
    return Prop.shown(theme, huePicker(selected.get(), none, selected.onChange()), selected);
  }

  /** A button of the value of a {@link Prop}; a prop that cannot be written disables it. */
  public static El button(Prop<Theme.Hue> selected, String none, Popover.State state) {
    Theme theme = Theme.CURRENT.get();
    return Prop.shown(theme, button(selected.get(), none, state, selected.onChange()), selected);
  }

  /**
   * The hue's name as a person reads it, in the {@link Texts} where this is called: {@code Red}
   * for {@link Theme.Hue#RED}.
   */
  public static String name(Theme.Hue hue) {
    return Texts.CURRENT.get().hue(hue);
  }

  /** A swatch of the hue, as large as a checkbox's box, for a menu or a list too. */
  public static El swatch(Theme.Hue hue) {
    Theme theme = Theme.CURRENT.get();
    float side = theme.size().checkbox();
    return el().size(side, side)
        .shrink(0)
        .radius(side / 2)
        .fill(theme.withAccent(hue).accent().edge());
  }

  private static El swatches(
      Theme.Hue selected, String none, Consumer<Theme.Hue> onSelect, Runnable onPressed) {
    List<Theme.Hue> hues = new ArrayList<>();
    if (none != null) {
      hues.add(null);
    }
    hues.addAll(Arrays.asList(Theme.Hue.values()));
    List<Radio.Option> options = new ArrayList<>(hues.size());
    for (Theme.Hue hue : hues) {
      options.add(option(hue, none));
    }
    return Radio.swatches(
        options, hues.indexOf(selected), Choices.handler(hues, onSelect), onPressed);
  }

  private static Radio.Option option(Theme.Hue hue, String none) {
    return hue == null
        ? Radio.option(none, () -> noColor(Theme.CURRENT.get()))
        : Radio.option(name(hue), () -> swatch(hue));
  }

  private static El noColor(Theme theme) {
    return Icon.icon(Symbol.PROHIBITED, Icon.Variant.MUTED, theme.size().checkbox());
  }
}
