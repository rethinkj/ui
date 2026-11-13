package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.kit.Theme;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Holds the showcase's proposal to the rules its comment states. Needs no native library. */
class ProposalTest {

  static Stream<Theme> proposal() {
    return Stream.of(Showcase.PROPOSAL_DARK, Showcase.PROPOSAL_LIGHT);
  }

  @ParameterizedTest
  @MethodSource("proposal")
  void everyPairTheKitDrawsHasItsContrast(Theme t) {
    List<String> failures = Contrast.checks(t).stream().filter(c -> !c.passes())
        .map(c -> String.format(Locale.ROOT, "%s: %.2f, needs %.1f", c.what(), c.ratio(),
            c.needs()))
        .toList();
    assertEquals(List.of(), failures);
  }

  @ParameterizedTest
  @MethodSource("proposal")
  void disabledTextIsLegibleButBelowMuted(Theme t) {
    Theme.Surfaces s = t.surface();
    for (int surface : new int[] {s.window(), s.panel(), s.raised()}) {
      double ratio = Contrast.ratio(t.text().disabled(), surface);
      assertTrue(ratio >= 2.5 && ratio <= 3.5, String.format("%.2f on %08X", ratio, surface));
    }
  }

  /**
   * Hover and pressed look alike whether a component mixes them with Theme.over or draws them
   * translucent: within 3 L* on each surface, hover 4 L* or more from the surface, pressed 3 L*
   * or more beyond hover.
   */
  @ParameterizedTest
  @MethodSource("proposal")
  void hoverAndPressedAgreeHoweverTheyAreMixed(Theme t) {
    Theme.Surfaces s = t.surface();
    for (int surface : new int[] {s.window(), s.panel(), s.raised()}) {
      double base = lightness(surface);
      double hover = lightness(Theme.over(surface, s.hover()));
      double pressed = lightness(Theme.over(surface, s.pressed()));
      String at = String.format("on %08X", surface);
      assertTrue(Math.abs(lightness(Contrast.drawn(surface, s.hover())) - hover) <= 3, at);
      assertTrue(Math.abs(lightness(Contrast.drawn(surface, s.pressed())) - pressed) <= 3, at);
      assertTrue(Math.abs(hover - base) >= 4, at);
      assertTrue(Math.abs(pressed - base) >= Math.abs(hover - base) + 3, at);
    }
  }

  /** A selection without the focus is told apart from a row under the pointer. */
  @ParameterizedTest
  @MethodSource("proposal")
  void anInactiveSelectionIsNotAHover(Theme t) {
    double hovered = lightness(Theme.over(t.grid().row(), t.surface().hover()));
    assertTrue(Math.abs(lightness(t.grid().selectedInactive()) - hovered) >= 4);
  }

  /** CIELAB L* of an opaque colour. */
  private static double lightness(int argb) {
    double y = Contrast.luminance(argb);
    return y > 216.0 / 24389 ? 116 * Math.cbrt(y) - 16 : y * 24389 / 27;
  }
}
