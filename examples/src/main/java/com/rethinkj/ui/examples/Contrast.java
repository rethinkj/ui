package com.rethinkj.ui.examples;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.kit.Theme;
import java.util.List;

/** WCAG 2's contrast ratio between colours, as the showcase's colour section shows it. */
final class Contrast {

  /** Text and its background: 4.5:1 (WCAG AA for text below 18.66 px bold or 24 px). */
  static final double TEXT = 4.5;

  /** The edges of controls, focus rings and other marks: 3:1 (WCAG 2.1 SC 1.4.11). */
  static final double MARK = 3;

  private Contrast() {}

  /** The backgrounds of the showcase's matrix, in its order; see {@link #backgrounds}. */
  static final List<String> BACKGROUNDS = List.of(
      "window",
      "panel",
      "raised",
      "band",
      "hover r.",
      "hover p.",
      "grid row",
      "header",
      "selected",
      "inactive");

  /**
   * The colours of {@link #BACKGROUNDS}: the hover over raised (buttons, fields, selects) and over
   * panel (ghost buttons, tabs, the tree's rows).
   */
  static List<Integer> backgrounds(Theme t) {
    Theme.Surfaces s = t.surface();
    Theme.Grid g = t.grid();
    return List.of(
        s.window(),
        s.panel(),
        s.raised(),
        s.band(),
        Theme.over(s.raised(), s.hover()),
        Theme.over(s.panel(), s.hover()),
        g.row(),
        g.header(),
        g.selected(),
        g.selectedInactive());
  }

  /**
   * A colour drawn on the backgrounds, and on which of them the kit draws it, one character per
   * background of {@link #BACKGROUNDS}: {@code x} where it needs {@code needs}, {@code .} where
   * the kit does not draw it there.
   */
  record Foreground(String name, int argb, double needs, String where) {

    boolean usedOn(int background) {
      return needs > 0 && where.charAt(background) == 'x';
    }
  }

  /** The rows of the matrix. */
  static List<Foreground> foregrounds(Theme t) {
    return List.of(
        new Foreground("text primary", t.text().primary(), TEXT, "xxxxxxxxxx"),
        new Foreground("text muted", t.text().muted(), TEXT, "xxxxxxxxxx"),
        new Foreground("text disabled", t.text().disabled(), 0, ".........."),
        // Text on the surfaces, icons (3:1, which they meet where text does) in selected rows.
        new Foreground("status danger", t.status().danger(), TEXT, "xxxx..xxx."),
        new Foreground("status warning", t.status().warning(), TEXT, "xxxx..xxx."),
        new Foreground("status success", t.status().success(), TEXT, "xxxx..xxx."),
        // The fill of a checked box and of a switch that is on.
        new Foreground("accent", t.accent().color(), MARK, ".xx......."),
        new Foreground("accent edge", t.accent().edge(), MARK, "xxxxxxxxxx"),
        new Foreground("border strong", t.border().strong(), MARK, "xxxxx....."),
        new Foreground("grid cursor", t.grid().cursor(), MARK, "......x.x."),
        new Foreground("scrollbar thumb", t.scrollbars().thumb(), MARK, "xxx...xxxx"),
        new Foreground("border control", t.border().control(), 0, ".........."),
        new Foreground("border subtle", t.border().subtle(), 0, ".........."));
  }

  /**
   * The ratio from 1 to 21 between two colours, 0xAARRGGBB. A translucent foreground is laid over
   * the background first, as {@link Colors#over}; the background must be opaque.
   */
  static double ratio(int foreground, int background) {
    return Colors.contrast(Colors.over(background, foreground), background);
  }
}
