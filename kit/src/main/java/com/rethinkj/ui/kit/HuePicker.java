package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A choice of one of the theme's {@link Theme.Hue}s, such as the colour that marks a connection:
 * a round swatch per hue in {@link Radio#swatches}, filled with the hue's {@link
 * Theme.Accent#edge} of the theme, the colour a tab's mark has ({@link Tabs.Tab#hue}), which
 * holds 3:1 on the surfaces in both default themes. A choice of no colour, when the app allows
 * it, comes first: {@link Symbol#PROHIBITED} in muted text. In a tree dump it is {@code
 * role=radiogroup}, each swatch {@code role=radio} labelled by the hue's name as a person reads
 * it ({@code Red}, {@code Violet}) and {@code checked} or {@code checked=false}.
 *
 * <pre>{@code
 * HuePicker.huePicker(t, s.hue, "None", hue -> s.hue = hue) // None first, then the ten hues
 * }</pre>
 */
public final class HuePicker {

  private HuePicker() {}

  /**
   * The ten hues.
   *
   * @param selected the chosen hue; null for none
   * @param onSelect told the hue the user chooses, only when it changes; null disables the picker
   */
  public static El huePicker(Theme t, Theme.Hue selected, Consumer<Theme.Hue> onSelect) {
    return huePicker(t, selected, null, onSelect);
  }

  /**
   * The ten hues after a choice of no colour.
   *
   * @param selected the chosen hue; null for the choice of no colour, or for none where there is
   *     none
   * @param none the name of the choice of no colour, such as {@code None}; null for no such choice
   * @param onSelect told the hue the user chooses, null for no colour, only when it changes; null
   *     disables the picker
   */
  public static El huePicker(
      Theme t, Theme.Hue selected, String none, Consumer<Theme.Hue> onSelect) {
    List<Theme.Hue> hues = new ArrayList<>();
    if (none != null) {
      hues.add(null);
    }
    hues.addAll(Arrays.asList(Theme.Hue.values()));
    return Radio.swatches(t, hues, selected, hue -> option(hue, none), onSelect);
  }

  /** A picker of the value of a {@link Prop}; a prop that cannot be written disables it. */
  public static El huePicker(Theme t, Prop<Theme.Hue> selected, String none) {
    return Prop.shown(t, huePicker(t, selected.get(), none, selected.onChange()), selected);
  }

  /** The hue's name as a person reads it: {@code Red} for {@link Theme.Hue#RED}. */
  public static String name(Theme.Hue hue) {
    String n = hue.name();
    return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT);
  }

  /** A swatch of the hue, as large as a checkbox's box, for a menu or a list too. */
  public static El swatch(Theme t, Theme.Hue hue) {
    float side = t.size().checkbox();
    return el().size(side, side)
        .shrink(0)
        .radius(side / 2)
        .fill(t.withAccent(hue).accent().edge());
  }

  private static Radio.Option option(Theme.Hue hue, String none) {
    return hue == null
        ? Radio.option(
            none,
            t -> Icon.icon(t, Symbol.PROHIBITED, Icon.Variant.MUTED, t.size().checkbox()))
        : Radio.option(name(hue), t -> swatch(t, hue));
  }
}
