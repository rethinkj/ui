package com.rethinkj.ui.kit;

import static com.rethinkj.ui.kit.Theme.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Shadow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The default themes against the rules their comment in {@link Theme} states. Contrast by WCAG 2:
 * at least 4.5:1 for text on every background it is used on, and 3:1 for the parts a control is
 * recognized by and for the focus ring. Disabled text is exempt (WCAG 1.4.3) but has to stay
 * readable, between 2.5:1 and 3.5:1, below muted text.
 */
class ThemeTest {

  static Theme theme(String name) {
    return name.equals("dark") ? Theme.DARK : Theme.LIGHT;
  }

  /** Each theme with each hue's accent: the default's, which is blue, and the others. */
  static Stream<Arguments> themesAndHues() {
    return Stream.of("dark", "light")
        .flatMap(name -> Arrays.stream(Theme.Hue.values()).map(hue -> Arguments.of(name, hue)));
  }

  @ParameterizedTest
  @MethodSource("themesAndHues")
  void textAndControlsHaveEnoughContrast(String name, Theme.Hue hue) {
    Theme t = theme(name).withAccent(hue);
    Theme.Surfaces s = t.surface();
    Map<String, Integer> surfaces =
        Map.of("window", s.window(), "panel", s.panel(), "raised", s.raised(), "band", s.band());
    Map<String, Integer> controls = Map.of(
        "raised", s.raised(),
        "raised hovered", over(s.raised(), s.hover()),
        "raised pressed", over(s.raised(), s.pressed()),
        "window hovered", over(s.window(), s.hover()),
        "window pressed", over(s.window(), s.pressed()));
    Map<String, Integer> accents = Map.of(
        "accent",
        t.accent().color(),
        "accent hover",
        t.accent().hover(),
        "accent pressed",
        t.accent().pressed());

    List<String> failures = new ArrayList<>();
    check(failures, "primary text", t.text().primary(), surfaces, 4.5);
    check(failures, "primary text", t.text().primary(), controls, 4.5);
    check(failures, "muted text", t.text().muted(), surfaces, 4.5);
    check(failures, "danger", t.status().danger(), surfaces, 4.5);
    check(failures, "warning", t.status().warning(), surfaces, 4.5);
    check(failures, "success", t.status().success(), surfaces, 4.5);
    // The icon of a button with a status's or the accent's tone, on a control's fills and in a
    // toolbar's group, whose tint lies under the button's.
    int group = over(s.window(), s.hover());
    Map<String, Integer> iconOn = new HashMap<>(controls);
    iconOn.putAll(Map.of(
        "group", group,
        "group hovered", over(group, s.hover()),
        "group pressed", over(group, s.pressed())));
    check(failures, "success icon", t.status().success(), iconOn, 3);
    check(failures, "warning icon", t.status().warning(), iconOn, 3);
    check(failures, "accent icon", t.accent().edge(), iconOn, 3);
    check(failures, "text on accent", t.text().onAccent(), accents, 4.5);
    check(failures, "strong border", t.border().strong(), surfaces, 3);
    // A field's bottom edge, which identifies it, also against its own fill at rest and hovered,
    // where the whole frame takes the strong border.
    check(
        failures,
        "field edge",
        t.border().strong(),
        Map.of("field", s.raised(), "field hovered", over(s.raised(), s.hover())),
        3);
    check(failures, "accent edge", t.accent().edge(), surfaces, 3);
    // A primary button's ring lies a gap away from its accent, on the surfaces, as every ring.
    // What is on: a checked box, a switch.
    check(
        failures,
        "accent",
        t.accent().color(),
        Map.of("panel", s.panel(), "raised", s.raised()),
        3);
    check(failures, "knob", t.text().onAccent(), Map.of("track off", t.border().strong()), 3);
    // A hue picker: each hue's swatch, and the ring around the chosen swatch, which the surface
    // separates from it, on the surfaces a picker lies on.
    Map<String, Integer> picker = Map.of("panel", s.panel(), "raised", s.raised());
    for (Theme.Hue each : Theme.Hue.values()) {
      check(failures, "swatch " + each, theme(name).withAccent(each).accent().edge(), picker, 3);
    }
    check(failures, "chosen ring", t.accent().edge(), picker, 3);
    // A progress bar's part done, in each of its states, on its track, and a running line, which
    // has no track, on the surfaces it lies on; a spinner's arc on its ring.
    Map<String, Integer> track = Map.of("progress track", t.border().control());
    check(failures, "progress", t.accent().edge(), track, 3);
    check(failures, "progress failed", t.status().danger(), track, 3);
    check(failures, "progress done", t.status().success(), track, 3);
    check(failures, "progress paused", t.text().muted(), track, 3);
    check(failures, "progress line", t.accent().edge(), surfaces, 3);
    // A spinner's ring shows faintly on whatever it lies on, and its arc stands out from it: on
    // the surfaces, a status bar, a tree's or a list's selected row with and without the focus.
    Map.of(
            "window",
            s.window(),
            "panel",
            s.panel(),
            "raised",
            s.raised(),
            "band",
            s.band(),
            "selected",
            t.grid().selected(),
            "selected inactive",
            t.grid().selectedInactive())
        .forEach((on, background) -> {
          int ring = over(background, t.border().track());
          check(failures, "spinner ring", ring, Map.of(on, background), 1.3);
          check(failures, "spinner arc", t.accent().edge(), Map.of("its ring on " + on, ring), 3);
        });
    // A busy primary button's spinner: its ring shows on the fill, the arc in the text's color
    // stands out from it, on the accent's fills and a dangerous button's.
    Theme.Danger danger = t.danger();
    Map.of(
            "accent", t.accent().color(),
            "accent hover", t.accent().hover(),
            "accent pressed", t.accent().pressed(),
            "danger", danger.color(),
            "danger hover", danger.hover(),
            "danger pressed", danger.pressed())
        .forEach((on, fill) -> {
          int ring = over(fill, t.border().trackOnAccent());
          int arc = on.startsWith("danger") ? danger.onColor() : t.text().onAccent();
          check(failures, "spinner ring", ring, Map.of(on, fill), 1.3);
          check(failures, "spinner arc", arc, Map.of("its ring on " + on, ring), 3);
        });
    // Code in an editor: on the field's raised surface, hovered and on the caret's line; the
    // brackets at the caret on their background too, and every color 3:1 under the selection.
    Theme.Code code = t.code();
    int line = over(s.raised(), code.line());
    Map<String, Integer> field = Map.of(
        "raised", s.raised(),
        "raised hovered", over(s.raised(), s.hover()),
        "caret line", line,
        "caret line hovered", over(over(s.raised(), s.hover()), code.line()));
    Map<String, Integer> codeColors = Map.of(
        "keyword",
        code.keyword(),
        "string",
        code.string(),
        "number",
        code.number(),
        "comment",
        code.comment(),
        "operator",
        code.operator(),
        "property",
        code.property());
    codeColors.forEach((token, color) -> {
      check(failures, token, color, field, 4.5);
      check(
          failures,
          token,
          color,
          Map.of(
              "selection",
              over(s.raised(), t.accent().selection()),
              "selection on the caret line",
              over(line, t.accent().selection()),
              "selection on the caret line hovered",
              over(over(over(s.raised(), s.hover()), code.line()), t.accent().selection())),
          3);
    });
    check(failures, "bracket", code.operator(), Map.of("bracket", over(line, code.bracket())), 4.5);
    // The matches of a find, the current one, and the occurrences of the word at the caret, also
    // on the caret's line; under the selection 3:1, as all code.
    Map<String, Integer> marked = new java.util.HashMap<>();
    Map<String, Integer> selectedMatch = new java.util.HashMap<>();
    Map.of("", s.raised(), " on the caret line", line).forEach((where, under) -> {
      marked.put("match" + where, over(under, code.match()));
      marked.put("current match" + where, over(under, code.current()));
      marked.put("occurrence" + where, over(under, code.occurrence()));
      selectedMatch.put(
          "selected current match" + where,
          over(over(under, code.current()), t.accent().selection()));
    });
    Map<String, Integer> text = new java.util.HashMap<>(codeColors);
    text.put("text", t.text().primary());
    text.forEach((token, color) -> {
      check(failures, token, color, marked, 4.5);
      check(failures, token, color, selectedMatch, 3);
    });
    // Diff tints: line tints on the raised surface, word tints over their line tints, and filler.
    Theme.Diff diff = t.diff();
    int addedLine = over(s.raised(), diff.added());
    int removedLine = over(s.raised(), diff.removed());
    int modifiedLine = over(s.raised(), diff.modified());
    Map<String, Integer> diffBg = Map.of(
        "diff added",
        addedLine,
        "diff added word",
        over(addedLine, diff.addedWord()),
        "diff removed",
        removedLine,
        "diff removed word",
        over(removedLine, diff.removedWord()),
        "diff modified",
        modifiedLine,
        "diff modified word",
        over(modifiedLine, diff.modifiedWord()));
    text.forEach((token, color) -> check(failures, token, color, diffBg, 4.5));
    // The gutter's rail, also in a marked block: its numbers muted, the caret's as text, and its
    // markers 3:1, as icons.
    Map<String, Integer> rail =
        Map.of("rail", s.band(), "rail in block", over(s.band(), code.block()));
    check(failures, "line number", t.text().muted(), rail, 4.5);
    check(failures, "caret's line number", t.text().primary(), rail, 4.5);
    check(failures, "run marker", t.accent().edge(), rail, 3);
    check(failures, "error marker", t.status().danger(), rail, 3);
    check(failures, "warning marker", t.status().warning(), rail, 3);
    // A document on a card: text, quotes, links and inline code on the panel, under the pointer
    // and on inline code's background; code blocks on the raised surface, as above.
    Theme.Prose prose = t.prose();
    Map<String, Integer> page = Map.of(
        "panel", s.panel(),
        "inline code", over(s.panel(), prose.code()),
        "link hovered", over(s.panel(), s.hover()),
        "table header", s.band());
    check(failures, "document text", t.text().primary(), page, 4.5);
    check(
        failures,
        "selected document text",
        t.text().primary(),
        Map.of("selected", over(s.panel(), t.accent().selection())),
        4.5);
    check(
        failures,
        "selected link",
        prose.link(),
        Map.of("selected", over(s.panel(), t.accent().selection())),
        3);
    check(
        failures,
        "quoted text",
        t.text().muted(),
        Map.of("panel", s.panel(), "inline code", over(s.panel(), prose.code())),
        4.5);
    check(failures, "link", prose.link(), page, 4.5);
    check(
        failures,
        "pressed link",
        t.text().primary(),
        Map.of("panel", s.panel(), "inline code", over(s.panel(), prose.code())),
        4.5);
    check(failures, "quote's line", prose.quote(), Map.of("panel", s.panel()), 1.5);
    check(failures, "disabled text", t.text().disabled(), surfaces, 2);
    check(
        failures,
        "status",
        t.status().danger(),
        Map.of("grid selected", t.grid().selected()),
        4.5);
    check(
        failures,
        "status",
        t.status().warning(),
        Map.of("grid selected", t.grid().selected()),
        4.5);
    check(
        failures,
        "status",
        t.status().success(),
        Map.of("grid selected", t.grid().selected()),
        4.5);
    // A menu highlights a disabled item with the hover overlay on the popup.
    check(
        failures,
        "disabled text",
        t.text().disabled(),
        Map.of("popup hovered", over(s.popup(), s.hover())),
        2);
    // Selected text keeps its color over the translucent selection.
    check(
        failures,
        "selected text",
        t.text().primary(),
        Map.of(
            "selection on raised", over(s.raised(), t.accent().selection()),
            "selection on raised hovered",
                over(over(s.raised(), s.hover()), t.accent().selection())),
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
    // colors a column gives them, also on the selection and under the pointer.
    Map.of(
            "normal icon",
            t.text().primary(),
            "muted icon",
            t.text().muted(),
            "danger icon",
            t.status().danger(),
            "warning icon",
            t.status().warning(),
            "success icon",
            t.status().success())
        .forEach((what, color) -> check(failures, what, color, grid, 3));
    // The cursor's outline on the rows, under the pointer, and on the selection, which fills
    // selected cells as it fills selected rows.
    check(
        failures,
        "grid cursor",
        g.cursor(),
        Map.of(
            "grid row", g.row(),
            "grid row hovered", over(g.row(), s.hover()),
            "grid stripe", g.stripe(),
            "grid stripe hovered", over(g.stripe(), s.hover()),
            "grid selected", g.selected(),
            "grid header", g.header()),
        3);
    // Changes not yet written: a tint over a changed cell, a new row, a deleted row (in muted
    // text) and a failed cell, on rows and stripes, also under the pointer, where the selection
    // shows alone; the marks at the start of their rows, and a failed cell's edge, on the same.
    Map<String, Integer> rows = Map.of(
        "grid row",
        g.row(),
        "grid stripe",
        g.stripe(),
        "grid row hovered",
        over(g.row(), s.hover()),
        "grid stripe hovered",
        over(g.stripe(), s.hover()));
    Map<String, Integer> tints = Map.of(
        "changed", g.changed(), "new", g.added(), "deleted", g.deleted(), "failed", g.error());
    Map<String, Integer> marks = Map.of(
        "changed",
        t.accent().edge(),
        "new",
        t.status().success(),
        "deleted",
        t.status().danger(),
        "failed",
        t.status().danger());
    tints.forEach((what, tint) -> {
      Map<String, Integer> tinted = new java.util.HashMap<>();
      rows.forEach((under, fill) -> tinted.put(what + " on " + under, over(fill, tint)));
      check(failures, "primary text", t.text().primary(), tinted, 4.5);
      check(failures, "muted text", t.text().muted(), tinted, 4.5);
      check(failures, what + " mark", marks.get(what), tinted, 3);
      check(
          failures,
          what + " mark",
          marks.get(what),
          Map.of("grid selected", g.selected(), "grid selected inactive", g.selectedInactive()),
          3);
    });
    // The tree: no fill of its own, so its hovered rows lay the overlay over what it is on.
    // Labels in primary text; marks, details and loading rows in muted text, also on the
    // selected row, focused or not, which has the grid's selection.
    Map<String, Integer> tree = Map.of(
        "panel hovered", over(s.panel(), s.hover()),
        "window hovered", over(s.window(), s.hover()),
        "tree selected", t.grid().selected(),
        "tree selected inactive", t.grid().selectedInactive());
    check(failures, "primary text", t.text().primary(), tree, 4.5);
    check(failures, "muted text", t.text().muted(), tree, 4.5);
    // A row whose children could not be read: its icon in the danger color, on the same rows
    // and on the surfaces a tree lies on, and its action in the link color, also under the
    // pointer.
    Map<String, Integer> trees = new java.util.HashMap<>(tree);
    trees.putAll(Map.of("panel", s.panel(), "window", s.window()));
    check(failures, "tree danger icon", t.status().danger(), trees, 3);
    check(
        failures,
        "tree action",
        t.prose().link(),
        Map.of(
            "panel", s.panel(),
            "window", s.window(),
            "panel hovered", over(s.panel(), s.hover()),
            "window hovered", over(s.window(), s.hover())),
        4.5);
    // Scrollbar thumbs lie over the surfaces and the grid's rows, in each of their colors.
    Scrollbars b = t.scrollbars();
    Map<String, Integer> thumbs = Map.of(
        "thumb", b.thumb(), "thumb hovered", b.thumbHovered(), "thumb dragged", b.thumbDragged());
    thumbs.forEach((what, color) -> {
      check(failures, what, color, surfaces, 3);
      check(failures, what, color, grid, 3);
    });
    // Tabs on their band: another tab in muted text, in primary under the pointer and pressed,
    // where its cross can be hovered and pressed too; the active one's line in the accent's edge,
    // and in an inactive group its muted edge, visible on the band yet quieter than the edge.
    int tabHovered = over(s.band(), s.hover());
    int mutedLine = over(s.band(), t.accent().edgeMuted());
    check(failures, "muted tab line", mutedLine, Map.of("band", s.band()), 1.5);
    if (contrast(mutedLine, s.band()) >= contrast(t.accent().edge(), s.band())) {
      failures.add("muted tab line is not quieter than accent edge on band");
    }
    check(failures, "muted text", t.text().muted(), Map.of("band", s.band()), 4.5);
    check(
        failures,
        "primary text",
        t.text().primary(),
        Map.of(
            "tab hovered", tabHovered,
            "tab pressed", over(s.band(), s.pressed()),
            "cross hovered", over(tabHovered, s.hover()),
            "cross pressed", over(tabHovered, s.pressed())),
        4.5);
    // A selected card's edge, and the mark of a navigation list's selected item on its fill.
    check(
        failures,
        "selected mark",
        t.accent().edge(),
        Map.of("grid selected", g.selected(), "grid selected inactive", g.selectedInactive()),
        3);
    // The cross of a title bar's close button under the pointer, on the system's red.
    check(
        failures,
        "close cross",
        t.caption().onClose(),
        Map.of(
            "close hovered",
            t.caption().closeHover(),
            "close pressed",
            t.caption().closePressed()),
        4.5);
    // An invalid field's edge, on the field's raised fill.
    check(failures, "invalid edge", t.status().danger(), Map.of("raised", s.raised()), 3);
    // A group's rows are pressed on a raised card, and a description stays readable under the
    // pointer; a navigation list lays the overlays over the panel.
    // The details of a status bar lie on the window, which a part that takes a press hovers.
    check(
        failures,
        "muted text",
        t.text().muted(),
        Map.of(
            "raised hovered", over(s.raised(), s.hover()),
            "window hovered", over(s.window(), s.hover())),
        4.5);
    check(
        failures,
        "primary text",
        t.text().primary(),
        Map.of(
            "panel hovered", over(s.panel(), s.hover()),
            "panel pressed", over(s.panel(), s.pressed())),
        4.5);
    // Badges: a quiet one's text on its tint over the surfaces and the grid's rows, selected or
    // not, and under the pointer where it is pressable; a strong one's text on its fill.
    Theme.Badges badges = t.badge();
    Map<String, Integer> under = new java.util.HashMap<>(surfaces);
    under.putAll(grid);
    under.put("panel hovered", over(s.panel(), s.hover()));
    for (Theme.Tone tone : List.of(
        badges.neutral(), badges.accent(), badges.success(), badges.warning(), badges.danger())) {
      Map<String, Integer> tinted = new java.util.HashMap<>();
      under.forEach((what, color) -> tinted.put(what, over(color, tone.soft())));
      // The fill is a quiet step off the surface, but one that shows.
      surfaces.forEach((what, color) -> {
        double step = contrast(over(color, tone.soft()), color);
        if (step < 1.08) {
          failures.add(String.format(
              "badge fill %s on %s: %.3f:1, needs 1.08:1", Colors.toHex(tone.soft()), what, step));
        }
      });
      surfaces.forEach((what, color) -> { // a pressable badge lies on a surface
        tinted.put(what + " hovered", over(color, Badge.deeper(tone.soft(), Badge.HOVER)));
        tinted.put(what + " pressed", over(color, Badge.deeper(tone.soft(), Badge.PRESSED)));
      });
      check(
          failures,
          "badge text " + Integer.toHexString(tone.softText()),
          tone.softText(),
          tinted,
          4.5);
      check(failures, "strong badge text", tone.strongText(), Map.of("strong", tone.strong()), 4.5);
    }
    // Callouts: the title and the text below it on each kind's fill, the bar and the icon on it,
    // plus prose links and inline code for Markdown alerts; a ghost button's text under its
    // overlays over the fill (a secondary one has its raised fill, checked above), the outline of a
    // secondary one as far from the fill as its edge is from the surfaces it stands on elsewhere,
    // and the focus ring of either.
    Theme.Callouts callouts = t.callout();
    double edgeOnSurfaces = surfaces.values().stream()
        .mapToDouble(surface -> contrast(t.border().control(), surface))
        .min()
        .orElseThrow();
    for (Callout.Kind kind : Callout.Kind.values()) {
      int fill = callouts.of(kind).fill();
      int hovered = over(fill, s.hover());
      int pressed = over(fill, s.pressed());
      int inlineCode = over(fill, prose.code());
      Map<String, Integer> on = Map.of(kind + " callout", fill);
      check(failures, "callout title", t.text().primary(), on, 4.5);
      check(
          failures,
          "callout text",
          callouts.text(),
          Map.of(kind + " callout", fill, kind + " callout inline code", inlineCode),
          4.5);
      check(failures, "callout mark", callouts.of(kind).mark(), on, 3);
      check(
          failures,
          "callout link",
          prose.link(),
          Map.of(
              kind + " callout", fill,
              kind + " callout hovered", hovered,
              kind + " callout inline code", inlineCode),
          4.5);
      check(
          failures,
          "callout pressed link",
          t.text().primary(),
          Map.of(kind + " callout", fill, kind + " callout inline code", inlineCode),
          4.5);
      check(
          failures,
          "ghost button text",
          t.text().primary(),
          Map.of(kind + " callout hovered", hovered, kind + " callout pressed", pressed),
          4.5);
      // A secondary button keeps its raised fill: on a tint its outline is its edge or its fill,
      // whichever stands out more, as on a raised surface it is its edge alone.
      double outline = Math.max(contrast(t.border().control(), fill), contrast(s.raised(), fill));
      if (outline < edgeOnSurfaces) {
        failures.add(String.format(
            "secondary button on %s callout: %.2f:1, needs %.2f:1", kind, outline, edgeOnSurfaces));
      }
      check(failures, "focus ring", t.accent().edge(), on, 3);
    }
    // Avatars: each fill 3:1 on the surfaces, its initials 4.5:1 on it.
    for (int fill : badges.avatars()) {
      check(failures, "avatar " + Integer.toHexString(fill), fill, surfaces, 3);
      check(failures, "avatar initials", badges.onAvatar(), Map.of("avatar", fill), 4.5);
    }
    // Lanes: each color 3:1 on the window, the panel and the selected row.
    Map<String, Integer> laneBg = Map.of(
        "window", s.window(),
        "panel", s.panel(),
        "grid selected", g.selected(),
        "grid selected inactive", g.selectedInactive());
    for (int color : t.lanes().colors()) {
      check(failures, "lane " + Colors.toHex(color), color, laneBg, 3);
    }
    // Series: text keeps 4.5:1 on each fill.
    for (int color : t.series().colors()) {
      check(
          failures,
          "series text",
          t.series().text(),
          Map.of("series " + Colors.toHex(color), color),
          4.5);
    }
    // Chat user bubbles: primary, muted and selected text on the bubble fill.
    int bubble = t.chat().bubble();
    Map<String, Integer> bubbleBg = Map.of("chat bubble", bubble);
    check(failures, "chat bubble text", t.text().primary(), bubbleBg, 4.5);
    check(failures, "chat bubble muted text", t.text().muted(), bubbleBg, 4.5);
    check(
        failures,
        "chat bubble selected text",
        t.text().primary(),
        Map.of("chat bubble selected", over(bubble, t.accent().selection())),
        4.5);
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void disabledTextIsLegibleButBelowMuted(String name) {
    Theme t = theme(name);
    Theme.Surfaces s = t.surface();
    for (int surface : new int[] {s.window(), s.panel(), s.raised(), s.popup()}) {
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
    for (int surface :
        new int[] {s.window(), s.panel(), s.raised(), s.band(), t.grid().row()}) {
      double base = lightness(surface);
      double hover = lightness(over(surface, s.hover()));
      double pressed = lightness(over(surface, s.pressed()));
      String at = String.format("on %08X: %.1f, %.1f, %.1f", surface, base, hover, pressed);
      assertTrue(Math.abs(hover - base) >= 4, at);
      assertTrue(Math.abs(pressed - base) >= Math.abs(hover - base) + 3, at);
    }
  }

  /**
   * A popup in the dark theme is lighter than the controls below it, and the item it highlights
   * stands apart from it, with text that reads on it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void aPopupsHighlightStandsApartAndKeepsItsText(String name) {
    Theme t = theme(name);
    Theme.Surfaces s = t.surface();
    int highlight = s.highlight();
    Map<String, Integer> on = Map.of("popup", s.popup(), "highlight", highlight);
    List<String> failures = new ArrayList<>();
    check(failures, "primary text", t.text().primary(), on, 4.5);
    check(failures, "muted text", t.text().muted(), on, 4.5);
    check(failures, "danger text", t.danger().text(), on, 4.5);
    check(failures, "disabled text", t.text().disabled(), Map.of("highlight", highlight), 2);
    check(failures, "divider", t.border().divider(), Map.of("popup", s.popup()), 1.2);
    assertTrue(failures.isEmpty(), String.join("\n", failures));
    assertTrue(Math.abs(lightness(highlight) - lightness(s.popup())) >= 6, "stands apart");
    assertTrue(name.equals("light") || lightness(s.popup()) > lightness(s.raised()), "lighter");
  }

  /** A selection without the focus is told apart from a row under the pointer. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void anInactiveSelectionIsNotAHover(String name) {
    Theme t = theme(name);
    double hovered = lightness(over(t.grid().row(), t.surface().hover()));
    assertTrue(Math.abs(lightness(t.grid().selectedInactive()) - hovered) >= 4);
  }

  /**
   * A selection without the focus stands out from every surface it lies on, a tree's or a
   * navigation list's on a card or on the window and a grid's on its rows, at least as much as
   * the dark theme's cards stand out from its window.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void anInactiveSelectionStandsOutFromWhatItLiesOn(String name) {
    Theme t = theme(name);
    double least = contrast(Theme.DARK.surface().panel(), Theme.DARK.surface().window());
    int selection = t.grid().selectedInactive();
    for (int surface :
        new int[] {t.surface().window(), t.surface().panel(), t.grid().row()}) {
      double ratio = contrast(selection, surface);
      assertTrue(
          ratio >= least, String.format("%.3f:1 on %08X, needs %.3f:1", ratio, surface, least));
    }
  }

  /**
   * A divider shows on every surface it is drawn on, also the raised surface of a menu or a
   * dialog, where the subtle hairline almost vanishes.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void aDividerShowsOnEverySurface(String name) {
    Theme t = theme(name);
    Theme.Surfaces s = t.surface();
    List<String> failures = new ArrayList<>();
    check(
        failures,
        "divider",
        t.border().divider(),
        Map.of("window", s.window(), "panel", s.panel(), "raised", s.raised(), "band", s.band()),
        1.2);
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  /** The focused selection keeps primary text at 9:1 or more. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void aSelectionIsCalm(String name) {
    Theme t = theme(name);
    assertTrue(contrast(t.text().primary(), t.grid().selected()) >= 9);
  }

  /**
   * A title bar's project color glow keeps the title, inactive muted text, and caption button
   * glyphs readable at its strongest point, including under hover and pressed fills, for the
   * theme's hues and for app colors far lighter or darker than those.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void aTitleBarGlowKeepsTextAndButtonsReadable(String name) {
    Theme t = theme(name);
    Theme.Surfaces s = t.surface();
    List<Integer> colors = new ArrayList<>(List.of(0xFFFDD835, 0xFFFFFFFF, 0xFF000000, 0xFF0D1B4B));
    for (Theme.Hue hue : Theme.Hue.values()) {
      colors.add(t.withAccent(hue).accent().color());
    }
    List<String> failures = new ArrayList<>();
    for (int color : colors) {
      int glow =
          over(s.window(), Theme.CURRENT.with(t, () -> TitleBar.glow(color)).inner());
      int hovered = over(glow, s.hover());
      int pressed = over(glow, s.pressed());
      String on = Colors.toHex(color) + " ";
      check(
          failures,
          on + "primary text",
          t.text().primary(),
          Map.of("glow", glow, "glow hovered", hovered, "glow pressed", pressed),
          4.5);
      check(failures, on + "muted text", t.text().muted(), Map.of("glow", glow), 4.5);
      check(
          failures,
          on + "muted button glyph",
          t.text().muted(),
          Map.of("glow hovered", hovered, "glow pressed", pressed),
          3);
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  /** Hover darker than the accent, pressed darker than hover, by 2 L* or more each. */
  @ParameterizedTest
  @MethodSource("themesAndHues")
  void anAccentDarkensUnderThePointerAndMoreWhilePressed(String name, Theme.Hue hue) {
    Theme.Accent a = theme(name).withAccent(hue).accent();
    String at = String.format(
        "%s %s: %.1f, %.1f, %.1f",
        name, hue, lightness(a.color()), lightness(a.hover()), lightness(a.pressed()));
    assertTrue(lightness(a.color()) - lightness(a.hover()) >= 2, at);
    assertTrue(lightness(a.hover()) - lightness(a.pressed()) >= 2, at);
  }

  /**
   * The colors of a button that destroys something ({@link Button.Tone#DANGER}): its text on
   * each fill of a primary one, its red text on the surfaces and the controls' hover and pressed
   * of a secondary or ghost one, its edge and focus ring on the surfaces; hover and pressed darker
   * as an accent's.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void aDangerousButtonHasEnoughContrast(String name) {
    Theme t = theme(name);
    Theme.Surfaces s = t.surface();
    Theme.Danger d = t.danger();
    Map<String, Integer> surfaces =
        Map.of("window", s.window(), "panel", s.panel(), "raised", s.raised(), "band", s.band());
    Map<String, Integer> controls = Map.of(
        "raised hovered", over(s.raised(), s.hover()),
        "raised pressed", over(s.raised(), s.pressed()),
        "panel hovered", over(s.panel(), s.hover()),
        "panel pressed", over(s.panel(), s.pressed()),
        "window hovered", over(s.window(), s.hover()),
        "window pressed", over(s.window(), s.pressed()));
    List<String> failures = new ArrayList<>();
    check(
        failures,
        "text on danger",
        d.onColor(),
        Map.of("fill", d.color(), "hover", d.hover(), "pressed", d.pressed()),
        4.5);
    check(failures, "danger text", d.text(), surfaces, 4.5);
    check(failures, "danger text", d.text(), controls, 4.5);
    check(failures, "danger edge", d.edge(), surfaces, 3);
    check(failures, "danger ring", d.ring(), surfaces, 3);
    assertTrue(failures.isEmpty(), String.join("\n", failures));
    String at = String.format(
        "%s: %.1f, %.1f, %.1f",
        name, lightness(d.color()), lightness(d.hover()), lightness(d.pressed()));
    assertTrue(lightness(d.color()) - lightness(d.hover()) >= 2, at);
    assertTrue(lightness(d.hover()) - lightness(d.pressed()) >= 2, at);
  }

  /** Code colors made without a color for members' names color them as strings. */
  @Test
  void codeColorsWithoutAPropertyColorColorNamesAsStrings() {
    Theme.Code c = Theme.Code.DARK;
    Theme.Code older = new Theme.Code(
        c.keyword(),
        c.string(),
        c.number(),
        c.comment(),
        c.operator(),
        c.line(),
        c.bracket(),
        c.match(),
        c.current(),
        c.occurrence(),
        c.block(),
        c.guide());
    assertEquals(c.string(), older.property());
  }

  /** A theme made without danger colors takes those of its lightness. */
  @Test
  void aThemeWithoutDangerColorsTakesThoseOfItsLightness() {
    for (Theme t : List.of(Theme.DARK, Theme.LIGHT)) {
      Theme older = new Theme(
          t.surface(),
          t.text(),
          t.border(),
          t.accent(),
          t.status(),
          t.space(),
          t.radius(),
          t.type(),
          t.size(),
          t.motion(),
          t.grid(),
          t.scrollbars(),
          t.elevation(),
          t.code(),
          t.caption(),
          t.prose(),
          t.badge());
      assertEquals(t, older);
      Theme shortest = new Theme(
          t.surface(),
          t.text(),
          t.border(),
          t.accent(),
          t.status(),
          t.space(),
          t.radius(),
          t.type(),
          t.size(),
          t.motion(),
          t.grid(),
          t.scrollbars(),
          t.elevation());
      assertEquals(t, shortest);
    }
    assertEquals(Theme.Caption.DARK, Theme.DARK.caption());
    assertEquals(Theme.Caption.LIGHT, Theme.LIGHT.caption());
    assertEquals(Theme.Danger.DARK, Theme.DARK.danger());
    assertEquals(Theme.Danger.LIGHT, Theme.LIGHT.danger());
    assertEquals(Theme.Diff.DARK, Theme.DARK.diff());
    assertEquals(Theme.Diff.LIGHT, Theme.LIGHT.diff());
    assertEquals(Theme.Lanes.DARK, Theme.DARK.lanes());
    assertEquals(Theme.Lanes.LIGHT, Theme.LIGHT.lanes());
    assertEquals(Theme.Chat.DARK, Theme.DARK.chat());
    assertEquals(Theme.Chat.LIGHT, Theme.LIGHT.chat());
    assertEquals(Theme.Series.DARK, Theme.DARK.series());
    assertEquals(Theme.Series.LIGHT, Theme.LIGHT.series());
  }

  /** Blue is the default themes' accent, and a theme takes the accent of its lightness. */
  @Test
  void aHueGivesTheAccentOfTheThemesLightness() {
    assertEquals(Theme.DARK, Theme.DARK.withAccent(Theme.Hue.BLUE));
    assertEquals(Theme.LIGHT, Theme.LIGHT.withAccent(Theme.Hue.BLUE));
    assertEquals(Theme.Hue.TEAL.dark(), Theme.DARK.withAccent(Theme.Hue.TEAL).accent());
    assertEquals(Theme.Hue.TEAL.light(), Theme.LIGHT.withAccent(Theme.Hue.TEAL).accent());
    for (Theme.Hue hue : Theme.Hue.values()) {
      assertTrue(lightness(hue.dark().edge()) > lightness(hue.dark().color()), hue + "'s edge");
      assertEquals(hue.light().color(), hue.light().edge(), hue + "'s light edge");
    }
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
    List<List<Shadow>> dark = List.of(
        Theme.DARK.elevation().card(),
        Theme.DARK.elevation().popup(),
        Theme.DARK.elevation().dialog());
    List<List<Shadow>> light = List.of(
        Theme.LIGHT.elevation().card(),
        Theme.LIGHT.elevation().popup(),
        Theme.LIGHT.elevation().dialog());
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

  /** A card lies lower than a popup: its wide shadow falls shorter and is less blurred. */
  @Test
  void aCardCastsLessShadowThanAPopup() {
    for (Theme t : List.of(Theme.DARK, Theme.LIGHT)) {
      Shadow card = t.elevation().card().get(1);
      Shadow popup = t.elevation().popup().get(1);
      assertTrue(card.y() < popup.y() && card.blur() < popup.blur(), card + " below " + popup);
    }
  }

  private static void check(
      List<String> failures, String what, int color, Map<String, Integer> on, double minimum) {
    on.forEach((name, background) -> {
      double ratio = contrast(color, background);
      if (ratio < minimum) {
        failures.add(String.format("%s on %s: %.2f:1, needs %.1f:1", what, name, ratio, minimum));
      }
    });
  }

  /** CIELAB L* of an opaque color. */
  static double lightness(int argb) {
    return Colors.lightness(argb);
  }

  static double contrast(int a, int b) {
    return Colors.contrast(a, b);
  }
}
