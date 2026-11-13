package com.rethinkj.ui.examples;

import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
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
      "window", "panel", "raised", "hover¹", "hover²", "grid row", "stripe", "header",
      "selected", "inactive");

  /**
   * The colours of {@link #BACKGROUNDS}. A component lays the hover over a surface in one of two
   * ways: as an opaque colour from {@link Theme#over}, which mixes sRGB values (hover¹, over
   * raised: secondary buttons, fields, selects), or as a translucent fill, which the renderer mixes
   * in linear light (hover², over panel: ghost buttons, tabs, the tree's rows).
   */
  static List<Integer> backgrounds(Theme t) {
    Theme.Surfaces s = t.surface();
    Theme.Grid g = t.grid();
    return List.of(s.window(), s.panel(), s.raised(), Theme.over(s.raised(), s.hover()),
        drawn(s.panel(), s.hover()), g.row(), g.stripe(), g.header(), g.selected(),
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
        new Foreground("status danger", t.status().danger(), TEXT, "xxx..xxx.."),
        new Foreground("status warning", t.status().warning(), TEXT, "xxx..xxx.."),
        new Foreground("status success", t.status().success(), TEXT, "xxx..xxx.."),
        // The edge of a focused field, the fill of a checked box and of a switch that is on.
        new Foreground("accent (edge)", t.accent().color(), MARK, "xxx......."),
        new Foreground("border strong", t.border().strong(), MARK, "xxxx......"),
        new Foreground("border focus", t.border().focus(), MARK, "xxxxxxxxxx"),
        new Foreground("grid cursor", t.grid().cursor(), MARK, ".....xx.x."),
        new Foreground("scrollbar thumb", t.scrollbars().thumb(), MARK, "xxx..xxxxx"),
        new Foreground("border subtle", t.border().subtle(), 0, ".........."));
  }

  /** A pair the kit draws and what it has to reach. */
  record Check(String what, int foreground, int background, double needs) {

    double ratio() {
      return Contrast.ratio(foreground, background);
    }

    boolean passes() {
      return ratio() >= needs;
    }
  }

  /** Every pair of the showcase's contrast section that has a minimum. */
  static List<Check> checks(Theme t) {
    List<Check> checks = new ArrayList<>();
    List<Integer> backs = backgrounds(t);
    for (Foreground f : foregrounds(t)) {
      for (int b = 0; b < backs.size(); b++) {
        if (f.usedOn(b)) {
          checks.add(new Check(f.name() + " on " + BACKGROUNDS.get(b), f.argb(), backs.get(b),
              f.needs()));
        }
      }
    }
    Theme.Accent a = t.accent();
    for (int fill : new int[] {a.color(), a.hover(), a.pressed()}) {
      checks.add(new Check("text on the accent", t.text().onAccent(), fill, TEXT));
    }
    // The ring lies against a primary button's fill; pressed and hovered pass as they can.
    checks.add(new Check("focus ring on the accent", t.border().focus(), a.color(), MARK));
    checks.add(new Check("selected text in a field", t.text().primary(),
        drawn(t.surface().raised(), a.selection()), TEXT));
    checks.add(new Check("grid cursor on the selection", t.grid().cursor(), t.grid().selected(),
        MARK));
    return checks;
  }

  /**
   * The ratio from 1 to 21 between two colours, 0xAARRGGBB. A translucent foreground is laid over
   * the background first, as {@link #drawn}; the background must be opaque.
   */
  static double ratio(int foreground, int background) {
    double a = luminance(drawn(background, foreground));
    double b = luminance(background);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  /**
   * The colour a translucent {@code overlay} gives over an opaque {@code base} as the renderers
   * draw it: mixed in linear light, unlike {@link Theme#over}, which mixes the sRGB values.
   */
  static int drawn(int base, int overlay) {
    double alpha = (overlay >>> 24) / 255.0;
    int argb = 0xFF;
    for (int shift = 16; shift >= 0; shift -= 8) {
      double mixed = linear(overlay >>> shift & 0xFF) * alpha
          + linear(base >>> shift & 0xFF) * (1 - alpha);
      double srgb = mixed <= 0.0031308 ? mixed * 12.92 : 1.055 * Math.pow(mixed, 1 / 2.4) - 0.055;
      argb = argb << 8 | (int) Math.round(srgb * 255);
    }
    return argb;
  }

  /** The relative luminance of an opaque colour, from 0 for black to 1 for white. */
  static double luminance(int argb) {
    return 0.2126 * linear(argb >>> 16 & 0xFF) + 0.7152 * linear(argb >>> 8 & 0xFF)
        + 0.0722 * linear(argb & 0xFF);
  }

  private static double linear(int channel) {
    double c = channel / 255.0;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }
}
