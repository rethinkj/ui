package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Theme.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Shadow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The default themes against the rules their comment in {@link Theme} states. Contrast by WCAG 2:
 * at least 4.5:1 for text on every background it is used on, and 3:1 for the parts a control is
 * recognised by and for the focus ring. Disabled text is exempt (WCAG 1.4.3) but has to stay
 * readable, between 2.5:1 and 3.5:1, below muted text.
 */
class ThemeTest {

  static Theme theme(String name) {
    return name.equals("dark") ? Theme.DARK : Theme.LIGHT;
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void textAndControlsHaveEnoughContrast(String name) {
    Theme t = theme(name);
    Theme.Surfaces s = t.surface();
    Map<String, Integer> surfaces = Map.of("window", s.window(), "panel", s.panel(),
        "raised", s.raised(), "band", s.band());
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
    check(failures, "accent edge", t.accent().edge(), surfaces, 3);
    // A primary button's ring lies a gap away from its accent, on the surfaces, as every ring.
    // What is on: a checked box, a switch, the chosen choice of a segmented control.
    check(failures, "accent", t.accent().color(),
        Map.of("panel", s.panel(), "raised", s.raised()), 3);
    check(failures, "knob", t.text().onAccent(), Map.of("track off", t.border().strong()), 3);
    // A progress bar's part done, in each of its states, on its track, and a running line, which
    // has no track, on the surfaces it lies on; a spinner's arc on its ring.
    Map<String, Integer> track = Map.of("progress track", t.border().control());
    check(failures, "progress", t.accent().edge(), track, 3);
    check(failures, "progress failed", t.status().danger(), track, 3);
    check(failures, "progress done", t.status().success(), track, 3);
    check(failures, "progress paused", t.text().muted(), track, 3);
    check(failures, "progress line", t.accent().edge(), surfaces, 3);
    // Code in an editor: on the field's raised surface, hovered and on the caret's line; the
    // brackets at the caret on their background too, and every colour 3:1 under the selection.
    Theme.Code code = t.code();
    int line = over(s.raised(), code.line());
    Map<String, Integer> field = Map.of(
        "raised", s.raised(),
        "raised hovered", over(s.raised(), s.hover()),
        "caret line", line,
        "caret line hovered", over(over(s.raised(), s.hover()), code.line()));
    Map<String, Integer> codeColours = Map.of("keyword", code.keyword(), "string", code.string(),
        "number", code.number(), "comment", code.comment(), "operator", code.operator());
    codeColours.forEach((token, colour) -> {
      check(failures, token, colour, field, 4.5);
      check(failures, token, colour, Map.of("selection", over(s.raised(), t.accent().selection()),
          "selection on the caret line", over(line, t.accent().selection())), 3);
    });
    check(failures, "bracket", code.operator(), Map.of("bracket", over(line, code.bracket())), 4.5);
    // The matches of a find, the current one, and the occurrences of the word at the caret, also
    // on the caret's line; under the selection 3:1, as all code.
    Map<String, Integer> marked = Map.of(
        "match", over(s.raised(), code.match()),
        "match on the caret line", over(line, code.match()),
        "current match", over(s.raised(), code.current()),
        "current match on the caret line", over(line, code.current()),
        "occurrence", over(s.raised(), code.occurrence()),
        "occurrence on the caret line", over(line, code.occurrence()));
    Map<String, Integer> selectedMatch = Map.of(
        "selected current match", over(over(line, code.current()), t.accent().selection()));
    Map<String, Integer> text = new java.util.HashMap<>(codeColours);
    text.put("text", t.text().primary());
    text.forEach((token, colour) -> {
      check(failures, token, colour, marked, 4.5);
      check(failures, token, colour, selectedMatch, 3);
    });
    check(failures, "line number", t.text().muted(), Map.of("raised", s.raised()), 4.5);
    // A document on a card: text, quotes, links and inline code on the panel, under the pointer
    // and on inline code's background; code blocks on the raised surface, as above.
    Theme.Prose prose = t.prose();
    Map<String, Integer> page = Map.of(
        "panel", s.panel(),
        "inline code", over(s.panel(), prose.code()),
        "link hovered", over(s.panel(), s.hover()),
        "link pressed", over(s.panel(), s.pressed()),
        "table header", s.band());
    check(failures, "document text", t.text().primary(), page, 4.5);
    check(failures, "selected document text", t.text().primary(),
        Map.of("selected", over(s.panel(), t.accent().selection())), 4.5);
    check(failures, "selected link", prose.link(),
        Map.of("selected", over(s.panel(), t.accent().selection())), 3);
    check(failures, "quoted text", t.text().muted(), Map.of("panel", s.panel(),
        "inline code", over(s.panel(), prose.code())), 4.5);
    check(failures, "link", prose.link(), page, 4.5);
    check(failures, "quote's line", prose.quote(), Map.of("panel", s.panel()), 1.5);
    check(failures, "disabled text", t.text().disabled(), surfaces, 2);
    check(failures, "status", t.status().danger(), Map.of("grid selected", t.grid().selected()),
        4.5);
    check(failures, "status", t.status().warning(), Map.of("grid selected", t.grid().selected()),
        4.5);
    check(failures, "status", t.status().success(), Map.of("grid selected", t.grid().selected()),
        4.5);
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
    // Icons in the header and the cells, graphics that say something (WCAG 1.4.11), in the
    // colours a column gives them, also on the selection and under the pointer.
    Map.of("normal icon", t.text().primary(), "muted icon", t.text().muted(),
        "danger icon", t.status().danger(), "warning icon", t.status().warning(),
        "success icon", t.status().success())
        .forEach((what, colour) -> check(failures, what, colour, grid, 3));
    // The cursor's outline on the rows, under the pointer, and on the selection, which fills
    // selected cells as it fills selected rows.
    check(failures, "grid cursor", g.cursor(), Map.of(
        "grid row", g.row(),
        "grid row hovered", over(g.row(), s.hover()),
        "grid stripe", g.stripe(),
        "grid stripe hovered", over(g.stripe(), s.hover()),
        "grid selected", g.selected(),
        "grid header", g.header()), 3);
    // Changes not yet written: a tint over a changed cell, a new row, a deleted row (in muted
    // text) and a failed cell, on rows and stripes, also under the pointer, where the selection
    // shows alone; the marks at the start of their rows, and a failed cell's edge, on the same.
    Map<String, Integer> rows = Map.of(
        "grid row", g.row(), "grid stripe", g.stripe(),
        "grid row hovered", over(g.row(), s.hover()),
        "grid stripe hovered", over(g.stripe(), s.hover()));
    Map<String, Integer> tints = Map.of("changed", g.changed(), "new", g.added(),
        "deleted", g.deleted(), "failed", g.error());
    Map<String, Integer> marks = Map.of("changed", t.accent().edge(),
        "new", t.status().success(), "deleted", t.status().danger(), "failed", t.status().danger());
    tints.forEach((what, tint) -> {
      Map<String, Integer> tinted = new java.util.HashMap<>();
      rows.forEach((under, fill) -> tinted.put(what + " on " + under, over(fill, tint)));
      check(failures, "primary text", t.text().primary(), tinted, 4.5);
      check(failures, "muted text", t.text().muted(), tinted, 4.5);
      check(failures, what + " mark", marks.get(what), tinted, 3);
      check(failures, what + " mark", marks.get(what), Map.of("grid selected", g.selected(),
          "grid selected inactive", g.selectedInactive()), 3);
    });
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
    // Tabs on their band: another tab in muted text, in primary under the pointer and pressed,
    // where its cross can be hovered and pressed too; the active one's line in the accent's edge.
    int tabHovered = over(s.band(), s.hover());
    check(failures, "muted text", t.text().muted(), Map.of("band", s.band()), 4.5);
    check(failures, "primary text", t.text().primary(), Map.of(
        "tab hovered", tabHovered,
        "tab pressed", over(s.band(), s.pressed()),
        "cross hovered", over(tabHovered, s.hover()),
        "cross pressed", over(tabHovered, s.pressed())), 4.5);
    // A selected card's edge, and the mark of a navigation list's selected item on its fill.
    check(failures, "selected mark", t.accent().edge(), Map.of(
        "grid selected", g.selected(), "grid selected inactive", g.selectedInactive()), 3);
    // The cross of a title bar's close button under the pointer, on the system's red.
    check(failures, "close cross", t.caption().onClose(),
        Map.of("close hovered", t.caption().closeHover(),
            "close pressed", t.caption().closePressed()), 4.5);
    // An invalid field's edge, on the field's raised fill.
    check(failures, "invalid edge", t.status().danger(), Map.of("raised", s.raised()), 3);
    // A group's rows are pressed on a raised card, and a description stays readable under the
    // pointer; a segmented control and a navigation list lay the overlays over the panel.
    check(failures, "muted text", t.text().muted(), Map.of(
        "raised hovered", over(s.raised(), s.hover())), 4.5);
    check(failures, "primary text", t.text().primary(), Map.of(
        "panel hovered", over(s.panel(), s.hover()),
        "panel pressed", over(s.panel(), s.pressed())), 4.5);
    // Badges: a quiet one's text on its tint over the surfaces and the grid's rows, selected or
    // not, and under the pointer where it is pressable; a strong one's text on its fill.
    Theme.Badges badges = t.badge();
    Map<String, Integer> under = new java.util.HashMap<>(surfaces);
    under.putAll(grid);
    under.put("panel hovered", over(s.panel(), s.hover()));
    for (Theme.Tone tone : List.of(badges.neutral(), badges.accent(), badges.success(),
        badges.warning(), badges.danger())) {
      Map<String, Integer> tinted = new java.util.HashMap<>();
      under.forEach((what, colour) -> tinted.put(what, over(colour, tone.soft())));
      surfaces.forEach((what, colour) -> { // a pressable badge lies on a surface
        tinted.put(what + " hovered", over(colour, Badge.deeper(tone.soft(), Badge.HOVER)));
        tinted.put(what + " pressed", over(colour, Badge.deeper(tone.soft(), Badge.PRESSED)));
      });
      check(failures, "badge text " + Integer.toHexString(tone.softText()), tone.softText(),
          tinted, 4.5);
      check(failures, "strong badge text", tone.strongText(), Map.of("strong", tone.strong()),
          4.5);
    }
    // Avatars: each fill 3:1 on the surfaces, its initials 4.5:1 on it.
    for (int fill : badges.avatars()) {
      check(failures, "avatar " + Integer.toHexString(fill), fill, surfaces, 3);
      check(failures, "avatar initials", badges.onAvatar(), Map.of("avatar", fill), 4.5);
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void disabledTextIsLegibleButBelowMuted(String name) {
    Theme t = theme(name);
    Theme.Surfaces s = t.surface();
    for (int surface : new int[] {s.window(), s.panel(), s.raised()}) {
      double ratio = contrast(t.text().disabled(), surface);
      assertTrue(ratio >= 2.5 && ratio <= 3.5, String.format("%.2f on %08X", ratio, surface));
      assertTrue(ratio < contrast(t.text().muted(), surface));
    }
  }

  /** Hover 4 L* or more from each surface, pressed 3 L* or more beyond hover. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void hoverAndPressedStandOut(String name) {
    Theme t = theme(name);
    Theme.Surfaces s = t.surface();
    for (int surface : new int[] {s.window(), s.panel(), s.raised(), s.band(), t.grid().row()}) {
      double base = lightness(surface);
      double hover = lightness(over(surface, s.hover()));
      double pressed = lightness(over(surface, s.pressed()));
      String at = String.format("on %08X: %.1f, %.1f, %.1f", surface, base, hover, pressed);
      assertTrue(Math.abs(hover - base) >= 4, at);
      assertTrue(Math.abs(pressed - base) >= Math.abs(hover - base) + 3, at);
    }
  }

  /** A selection without the focus is told apart from a row under the pointer. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void anInactiveSelectionIsNotAHover(String name) {
    Theme t = theme(name);
    double hovered = lightness(over(t.grid().row(), t.surface().hover()));
    assertTrue(Math.abs(lightness(t.grid().selectedInactive()) - hovered) >= 4);
  }

  /** The focused selection keeps primary text at 9:1 or more. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void aSelectionIsCalm(String name) {
    Theme t = theme(name);
    assertTrue(contrast(t.text().primary(), t.grid().selected()) >= 9);
  }

  /** {@link Theme#over} mixes in linear light, as the renderer blends a translucent fill. */
  @Test
  void overMixesInLinearLight() {
    assertEquals(0xFFBBBBBB, over(0xFFFFFFFF, 0x80000000)); // sRGB values would give 0x7F
    assertEquals(0xFFFFFFFF, over(0xFF123456, 0xFFFFFFFF));
    assertEquals(0xFF123456, over(0xFF123456, 0x00FFFFFF));
  }

  @Test
  void theDarkThemesShadowsAreStrongerAndTighter() {
    List<List<Shadow>> dark = List.of(Theme.DARK.elevation().card(),
        Theme.DARK.elevation().popup(), Theme.DARK.elevation().dialog());
    List<List<Shadow>> light = List.of(Theme.LIGHT.elevation().card(),
        Theme.LIGHT.elevation().popup(), Theme.LIGHT.elevation().dialog());
    for (int level = 0; level < 3; level++) {
      assertEquals(2, dark.get(level).size(), "a tight and a wide shadow");
      assertEquals(2, light.get(level).size(), "a tight and a wide shadow");
      for (int layer = 0; layer < 2; layer++) {
        Shadow d = dark.get(level).get(layer);
        Shadow l = light.get(level).get(layer);
        assertTrue(d.color() >>> 24 > l.color() >>> 24, d + " is stronger than " + l);
        assertTrue(d.blur() <= l.blur() && d.y() <= l.y(), d + " is tighter than " + l);
      }
    }
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

  /** CIELAB L* of an opaque colour. */
  static double lightness(int argb) {
    double y = luminance(argb);
    return y > 216.0 / 24389 ? 116 * Math.cbrt(y) - 16 : y * 24389 / 27;
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
