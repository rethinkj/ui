package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Icon.Variant;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextRendering;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Icons: their box and place beside text, the drawing picked for a size, and a sheet of every
 * symbol with its name, at the theme's size and at 20, compared with the stored images.
 */
class IconTest {

  private static final Theme T = Theme.DARK;
  private static final int CELL = 200;
  private static final int COLUMNS = 4;
  private static final int ROW = 28;

  @Test
  void everySymbolIsAGlyphOfTheFontAsWideAsItsSize() {
    TextLibraries.assumeLoaded();
    for (Symbol symbol : Symbol.values()) {
      for (int size : new int[] {12, 16, 20, 24}) {
        GlyphRun run = Font.icons().shape(Character.toString(symbol.codePoint(size)), size, 1);
        assertEquals(1, run.count(), symbol + " at " + size);
        assertNotEquals(0, run.glyph(0), symbol + " at " + size);
        assertEquals(size, run.width(), symbol + " at " + size);
      }
    }
  }

  @Test
  void aSizeGetsItsOwnDrawingElseTheNearestTheSmallerOfTwo() {
    int[] sizes = {12, 16, 20, 24, 28, 32, 48};
    for (int i = 1; i < sizes.length; i++) {
      assertNotEquals(Symbol.ADD.codePoint(sizes[i - 1]), Symbol.ADD.codePoint(sizes[i]));
    }
    assertEquals(Symbol.ADD.codePoint(16), Symbol.ADD.codePoint(18));
    assertEquals(Symbol.ADD.codePoint(20), Symbol.ADD.codePoint(18.5f));
    assertEquals(Symbol.ADD.codePoint(48), Symbol.ADD.codePoint(100));
    // Drawn for 20 and up only.
    assertEquals(Symbol.MORE_HORIZONTAL.codePoint(20), Symbol.MORE_HORIZONTAL.codePoint(12));
  }

  /**
   * An icon is as wide and high as its size at every scale, also where the text before it ends
   * between two pixels, and centred on the line of the text beside it. Core's tests check that
   * the scene draws it on whole pixels.
   */
  @ParameterizedTest
  @CsvSource({"1", "1.25", "1.5", "2"})
  void anIconIsItsSizeOnWholePixelsAndLinesUpWithTheTextBesideIt(float scale) {
    TextLibraries.assumeLoaded();
    El before = label(T, "orders_2024");
    El small = icon(T, Symbol.TABLE);
    El large = icon(T, Symbol.TABLE, Variant.NORMAL, 20);
    El after = label(T, "orders");
    El line = row().gap(T.space().s()).align(Style.Align.CENTER)
        .children(before, small, large, after);
    Headless.render(col().padding(3.25f).child(line), 300, 60, scale, TextRendering.LINUX);
    assertEquals(16, small.width());
    assertEquals(16, small.height());
    assertEquals(20, large.width());
    assertEquals(20, large.height());
    // Within the half pixel by which layout may round the text's box.
    assertEquals(small.y() + 8, after.y() + after.height() / 2, 0.5f, "centred");
  }

  @Test
  void theVariantsTakeTheThemesColours() {
    TextLibraries.assumeLoaded();
    for (Theme t : new Theme[] {Theme.DARK, Theme.LIGHT}) {
      int[] colours = {t.text().primary(), t.text().muted(), t.text().disabled(),
          t.status().danger(), t.status().warning(), t.status().success()};
      for (Variant variant : Variant.values()) {
        assertEquals(colours[variant.ordinal()],
            icon(t, Symbol.WARNING, variant).text().color(), variant.name());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void looksAsStored(String name, int scale) {
    TextLibraries.assumeLoaded();
    Theme t = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    int rows = 1 + (Symbol.values().length + COLUMNS - 1) / COLUMNS;
    Headless.Frame frame = Headless.render(
        sheet(t), CELL * COLUMNS, rows * ROW, scale, TextRendering.LINUX);
    Golden.assertMatches("icons-" + name + "-" + scale + "x", frame.pixels(), frame.tree());
  }

  /**
   * The colour variants of one symbol, then every symbol at the theme's size and at 20 with its
   * name, row by row.
   */
  private static El sheet(Theme t) {
    El variants = cell(t).w(CELL * COLUMNS).child(label(t, Label.Variant.MUTED, "variants"));
    for (Variant variant : Variant.values()) {
      variants.children(icon(t, Symbol.WARNING, variant),
          label(t, variant.name().toLowerCase(Locale.ROOT)));
    }
    El sheet = row().wrap().wFull().hFull().fill(t.surface().window()).child(variants);
    for (Symbol symbol : Symbol.values()) {
      sheet.child(cell(t).children(icon(t, symbol), icon(t, symbol, Variant.NORMAL, 20),
          label(t, symbol.name().toLowerCase(Locale.ROOT))));
    }
    return sheet;
  }

  private static El cell(Theme t) {
    return row().w(CELL).h(ROW).padding(0, t.space().m()).gap(t.space().m())
        .align(Style.Align.CENTER);
  }
}
