package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Fonts and shaping, with the default fonts. Skipped without FreeType and HarfBuzz. */
class FontTest {

  @Test
  void shapesAKnownStringIntoGlyphsWhoseAdvancesMakeTheWidth() {
    TextLibraries.assumeLoaded();
    GlyphRun run = Font.sans().shape("Hello", 14, 1);
    assertEquals(5, run.count());
    float sum = 0;
    for (int i = 0; i < run.count(); i++) {
      assertNotEquals(0, run.glyph(i));
      assertEquals(i, run.cluster(i));
      assertEquals(sum, run.x(i));
      assertEquals(0, run.y(i));
      sum += run.advance(i);
    }
    assertEquals(sum, run.width());
    assertTrue(run.width() > 20 && run.width() < 50, "width " + run.width());
    assertEquals(run.glyph(2), run.glyph(3), "both l");
    assertEquals(14, run.pixelSize());
    assertSame(Font.sans(), run.font());
    assertSame(run, Font.sans().shape("Hello", 14, 1), "the font keeps recent runs");
  }

  @Test
  void kerningIsOnAndNarrowsAPairThatKerns() {
    TextLibraries.assumeLoaded();
    Font inter = Font.sans();
    float kerned = inter.shape("AV", 28, 1).width();
    float apart = inter.shape("A", 28, 1).width() + inter.shape("V", 28, 1).width();
    assertTrue(kerned < apart, kerned + " is not less than " + apart);
    assertEquals(apart, inter.shape("AV", 28, 1, "-kern").width());
  }

  /**
   * JetBrains Mono's ligatures keep one glyph per cell: with {@code calt} the first character
   * becomes an empty spacer and the last one the ligature, drawn over both cells. FreeTypeTest
   * checks the ink.
   */
  @Test
  void contextualAlternatesAreOffUnlessAsked() {
    TextLibraries.assumeLoaded();
    Font mono = Font.mono();
    GlyphRun plain = mono.shape("!=", 14, 1);
    GlyphRun ligature = mono.shape("!=", 14, 1, "+calt");
    assertEquals(2, plain.count());
    assertEquals(mono.shape("!", 14, 1).glyph(0), plain.glyph(0));
    assertEquals(mono.shape("=", 14, 1).glyph(0), plain.glyph(1));
    assertEquals(2, ligature.count());
    assertNotEquals(plain.glyph(0), ligature.glyph(0));
    assertNotEquals(plain.glyph(1), ligature.glyph(1));
    assertEquals(plain.width(), ligature.width(), "the ligature keeps the cells");
    assertEquals(ligature.glyph(0), mono.shape("->", 14, 1, "calt").glyph(0), "the spacer");
  }

  @Test
  void glyphsSitOnWholePixelsBelowScaleOneAndAHalfAndOnQuartersFromThere() {
    TextLibraries.assumeLoaded();
    String sample = "Kunde Müller, Köln: 1.234,56 € (AVATAR) Wäsche";
    for (Font font : new Font[] {Font.sans(), Font.mono(), Font.sansBold()}) {
      for (float size : new float[] {11, 12, 13, 14, 15.5f}) {
        GlyphRun whole = font.shape(sample, size, 1);
        GlyphRun finer = font.shape(sample, size, 2);
        for (int i = 0; i < whole.count(); i++) {
          assertEquals(Math.rint(whole.x(i)), whole.x(i), font + " " + size + " " + i);
          assertEquals(Math.rint(whole.advance(i)), whole.advance(i));
          assertEquals(Math.rint(finer.x(i) * 4), finer.x(i) * 4, font + " " + size + " " + i);
        }
        assertEquals(Math.rint(whole.width()), whole.width());
        assertEquals(size, whole.pixelSize());
        assertEquals(2 * size, finer.pixelSize());
      }
    }
    // At 1.25 still whole pixels, from 1.5 on quarters: Inter at 13 px has advances between.
    GlyphRun at125 = Font.sans().shape(sample, 13, 1.25f);
    GlyphRun at150 = Font.sans().shape(sample, 13, 1.5f);
    assertEquals(Math.rint(at125.width()), at125.width());
    assertNotEquals(Math.rint(at150.width()), at150.width());
  }

  @Test
  void aCharacterTheFontLacksBecomesItsNotDefinedGlyph() {
    TextLibraries.assumeLoaded();
    GlyphRun run = Font.sans().shape("a中b", 14, 1);
    assertEquals(3, run.count());
    assertEquals(0, run.glyph(1));
    assertTrue(run.advance(1) > 0);
  }

  @Test
  void anUnknownFeatureIsRejected() {
    TextLibraries.assumeLoaded();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Font.sans().shape("a", 14, 1, "+"));
    assertEquals(
        "\"+\" is not an OpenType feature in HarfBuzz's syntax, such as \"-kern\", \"+calt\" or"
            + " \"ss01\".",
        e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> Font.sans().shape("a", 0, 1));
    assertThrows(IllegalArgumentException.class, () -> Font.sans().shape("a", 14, Float.NaN));
  }

  @Test
  void aClosedFontFailsWithAMessageInsteadOfCrashing() throws IOException {
    TextLibraries.assumeLoaded();
    Font font = Font.of(defaultFontBytes("JetBrainsMono-Bold.ttf"));
    assertEquals("JetBrains Mono Bold", font.toString());
    GlyphRun before = font.shape("select", 13, 1);
    font.close();
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> font.shape("select", 13, 1));
    assertEquals("JetBrains Mono Bold is closed: this Font cannot be used any more.",
        e.getMessage());
    assertEquals(6, before.count(), "runs shaped before stay readable");
    font.close(); // a second close does nothing
    assertThrows(UnsupportedOperationException.class, () -> Font.sans().close());
  }

  @Test
  void bytesThatAreNotAFontAreRejected() {
    TextLibraries.assumeLoaded();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Font.of(new byte[] {1, 2, 3, 4}));
    assertTrue(e.getMessage().startsWith("These bytes are not a font that FreeType can read"),
        e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> Font.of(new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> Font.resource(FontTest.class, "none.ttf"));
  }

  @Test
  void theDefaultFontsNeedTheFontsModule() {
    // Needs no native library: the resource is looked for first.
    ClassLoader empty = new ClassLoader(null) {};
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> Font.builtIn("Inter-Regular.ttf", empty));
    assertEquals(
        "The default fonts are in the artifact com.rethinkj.ui:rethinkj-ui-fonts, which is not on"
            + " the class path: META-INF/rethinkj-ui/fonts/Inter-Regular.ttf is missing. Add that"
            + " artifact as a dependency (on the module path, also --add-modules"
            + " com.rethinkj.ui.fonts), or load a font of your own with Font.of or Font.resource.",
        e.getMessage());
  }

  @Test
  void theDefaultFontsAreInterAndJetBrainsMono() {
    TextLibraries.assumeLoaded();
    assertEquals("Inter Regular", Font.sans().toString());
    assertEquals("Inter Bold", Font.sansBold().toString());
    assertEquals("JetBrains Mono Regular", Font.mono().toString());
    assertEquals("JetBrains Mono Bold", Font.monoBold().toString());
    assertSame(Font.mono(), Font.mono());
    // Inter: 1984 and 494 of 2048 units per em, no line gap.
    assertEquals(1984 / 2048f, Font.sans().ascent());
    assertEquals(494 / 2048f, Font.sans().descent());
    assertEquals(0, Font.sans().lineGap());
  }

  static byte[] defaultFontBytes(String file) throws IOException {
    try (InputStream in =
        Font.class.getClassLoader().getResourceAsStream("META-INF/rethinkj-ui/fonts/" + file)) {
      return in.readAllBytes();
    }
  }
}
