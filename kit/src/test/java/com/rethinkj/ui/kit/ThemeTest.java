package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Theme.over;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Scrollbars;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The contrast of the default themes, by WCAG 2: at least 4.5:1 for text on every background it is
 * used on, and 3:1 for the parts a control is recognised by and for the focus ring. Disabled text
 * is exempt (WCAG 1.4.3) but has to stay readable, so it gets 2:1.
 */
class ThemeTest {

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void textAndControlsHaveEnoughContrast(String name) {
    Theme t = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    Theme.Surfaces s = t.surface();
    Map<String, Integer> surfaces =
        Map.of("window", s.window(), "panel", s.panel(), "raised", s.raised());
    Map<String, Integer> controls = Map.of(
        "raised", s.raised(),
        "raised hovered", over(s.raised(), s.hover()),
        "raised pressed", over(s.raised(), s.pressed()),
        "window hovered", over(s.window(), s.hover()),
        "window pressed", over(s.window(), s.pressed()));
    Map<String, Integer> accents = Map.of(
        "accent", t.accent().color(), "accent hover", t.accent().hover(),
        "accent pressed", t.accent().pressed());

    List<String> failures = new ArrayList<>();
    check(failures, "primary text", t.text().primary(), surfaces, 4.5);
    check(failures, "primary text", t.text().primary(), controls, 4.5);
    check(failures, "muted text", t.text().muted(), surfaces, 4.5);
    check(failures, "danger", t.status().danger(), surfaces, 4.5);
    check(failures, "warning", t.status().warning(), surfaces, 4.5);
    check(failures, "success", t.status().success(), surfaces, 4.5);
    check(failures, "text on accent", t.text().onAccent(), accents, 4.5);
    check(failures, "strong border", t.border().strong(), surfaces, 3);
    check(failures, "focus ring", t.border().focus(), surfaces, 3);
    // The ring lies right outside a primary button, with no gap.
    check(failures, "focus ring", t.border().focus(), Map.of("accent", t.accent().color()), 3);
    check(failures, "knob", t.text().onAccent(), Map.of("track off", t.border().strong()), 3);
    check(failures, "disabled text", t.text().disabled(), surfaces, 2);
    // A menu highlights a disabled item with the hover overlay on its raised surface.
    check(failures, "disabled text", t.text().disabled(),
        Map.of("raised hovered", over(s.raised(), s.hover())), 2);
    // Selected text keeps its colour over the translucent selection.
    check(failures, "selected text", t.text().primary(), Map.of(
        "selection on raised", over(s.raised(), t.accent().selection()),
        "selection on raised hovered", over(over(s.raised(), s.hover()), t.accent().selection())),
        4.5);
    // The data grid: cells in primary text, NULL and the sort marker in muted text.
    Theme.Grid g = t.grid();
    Map<String, Integer> grid = Map.of(
        "grid header", g.header(),
        "grid header hovered", over(g.header(), s.hover()),
        "grid header pressed", over(g.header(), s.pressed()),
        "grid row", g.row(),
        "grid row hovered", over(g.row(), s.hover()),
        "grid stripe", g.stripe(),
        "grid stripe hovered", over(g.stripe(), s.hover()),
        "grid selected", g.selected(),
        "grid selected inactive", g.selectedInactive());
    check(failures, "primary text", t.text().primary(), grid, 4.5);
    check(failures, "muted text", t.text().muted(), grid, 4.5);
    // The tree: no fill of its own, so its hovered rows lay the overlay over what it is on.
    // Labels in primary text, marks and loading rows in muted text; selected rows as the grid's.
    Map<String, Integer> tree = Map.of(
        "panel hovered", over(s.panel(), s.hover()),
        "window hovered", over(s.window(), s.hover()));
    check(failures, "primary text", t.text().primary(), tree, 4.5);
    check(failures, "muted text", t.text().muted(), tree, 4.5);
    // Scrollbar thumbs lie over the surfaces and the grid's rows, in each of their colours.
    Scrollbars b = t.scrollbars();
    Map<String, Integer> thumbs = Map.of(
        "thumb", b.thumb(), "thumb hovered", b.thumbHovered(), "thumb dragged", b.thumbDragged());
    thumbs.forEach((what, colour) -> {
      check(failures, what, colour, surfaces, 3);
      check(failures, what, colour, grid, 3);
    });
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  private static void check(
      List<String> failures, String what, int colour, Map<String, Integer> on, double minimum) {
    on.forEach((name, background) -> {
      double ratio = contrast(colour, background);
      if (ratio < minimum) {
        failures.add(String.format(
            "%s on %s: %.2f:1, needs %.1f:1", what, name, ratio, minimum));
      }
    });
  }

  static double contrast(int a, int b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double luminance(int argb) {
    return 0.2126 * channel(argb >> 16) + 0.7152 * channel(argb >> 8) + 0.0722 * channel(argb);
  }

  private static double channel(int value) {
    double c = (value & 0xFF) / 255.0;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }
}
