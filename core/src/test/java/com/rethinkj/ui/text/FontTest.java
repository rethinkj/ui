package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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

  /**
   * A view that shapes more texts than the cache holds finds them again in its next frame, and
   * they go once two frames have not used them.
   */
  @Test
  void keepsTheRunsOfTheLastFrameBeyondTheCacheSize() {
    TextLibraries.assumeLoaded();
    Font font = Font.sans();
    GlyphRun[] lines = new GlyphRun[2000];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = font.shape("kept line " + i, 14, 1);
    }
    Font.nextFrame();
    for (int i = 0; i < lines.length; i++) {
      assertSame(lines[i], font.shape("kept line " + i, 14, 1), "line " + i);
    }
    for (int frame = 0; frame < 2; frame++) {
      Font.nextFrame();
      for (int i = 0; i < lines.length; i++) {
        font.shape("other line " + frame + " " + i, 14, 1);
      }
    }
    assertFalse(font.shaped("kept line"));
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
  void glyphsSitOnQuarterPixelsAtEveryScale() {
    TextLibraries.assumeLoaded();
    String sample = "Kunde Müller, Köln: 1.234,56 € (AVATAR) Wäsche";
    for (Font font : new Font[] {Font.sans(), Font.mono(), Font.sansBold()}) {
      for (float size : new float[] {11, 12, 13, 14, 15.5f}) {
        GlyphRun at1 = font.shape(sample, size, 1);
        GlyphRun at2 = font.shape(sample, size, 2);
        for (int i = 0; i < at1.count(); i++) {
          assertEquals(Math.rint(at1.x(i) * 4), at1.x(i) * 4, font + " " + size + " " + i);
          assertEquals(Math.rint(at1.advance(i) * 4), at1.advance(i) * 4);
          assertEquals(Math.rint(at2.x(i) * 4), at2.x(i) * 4, font + " " + size + " " + i);
        }
        assertEquals(Math.rint(at1.width() * 4), at1.width() * 4);
        assertEquals(size, at1.pixelSize());
        assertEquals(2 * size, at2.pixelSize());
      }
    }
    // Even at scale 1 and 1.25, Inter has advances between whole pixels.
    GlyphRun at100 = Font.sans().shape(sample, 13, 1);
    GlyphRun at125 = Font.sans().shape(sample, 13, 1.25f);
    assertNotEquals(Math.rint(at100.width()), at100.width());
    assertNotEquals(Math.rint(at125.width()), at125.width());
  }

  @Test
  void bundledInterFacesApplySizeDependentTracking() throws IOException {
    TextLibraries.assumeLoaded();
    String sample = "Headings and body text";
    try (Font plainInter = Font.of(defaultFontBytes("Inter-Regular.ttf"))) {
      // Same pixel size (24 px), different logical sizes: 24 px at 1x tightens, 12 px at 2x
      // loosens.
      GlyphRun heading = Font.sans().shape(sample, 24, 1);
      GlyphRun smallScaled = Font.sans().shape(sample, 12, 2);
      GlyphRun untracked = plainInter.shape(sample, 24, 1);
      assertTrue(heading.width() < untracked.width(), heading.width() + " vs " + untracked.width());
      assertTrue(
          smallScaled.width() > heading.width(), smallScaled.width() + " vs " + heading.width());
      // A combining mark stays over its letter: the tracking comes after it.
      GlyphRun marked = Font.sans().shape("\u0430\u0301", 48, 2);
      GlyphRun plainMarked = plainInter.shape("\u0430\u0301", 48, 2);
      assertEquals(2, marked.count());
      assertEquals(plainMarked.x(1) - plainMarked.x(0), marked.x(1) - marked.x(0), "the accent");
      assertEquals(
          Font.sans().shape("\u043c\u043e\u0440\u0435", 14, 1).width(),
          Font.sans().shape("\u043c\u043e\u0301\u0440\u0435", 14, 1).width(),
          "the accent takes no room");
      assertEquals(0, Font.sans().shape("\u043c\u043e\u0301\u0440\u0435", 32, 1).advance(2));
      // A zero-width character of its own keeps no width.
      GlyphRun spaced = Font.sans().shape("A\u200BB", 24, 1);
      assertEquals(0, spaced.advance(1), "the zero-width space");
      // Fallback chain: tracked only for runs shaped by a tracked face.
      Font chain = Font.sans().withFallback(Examples.fallbackChain());
      GlyphRun mixed = chain.shape("AB שלום", 24, 1);
      GlyphRun hebrewAlone = Examples.fallbackChain().shape("שלום", 24, 1);
      float hebrewWidth = 0;
      for (int i = 0; i < mixed.count(); i++) {
        if (mixed.font(i) != Font.sans()) {
          hebrewWidth += mixed.advance(i);
        }
      }
      assertEquals(hebrewAlone.width(), hebrewWidth, "fallback run is not tracked");
    }
    // Non-Inter bundled faces (JetBrains Mono) are not tracked: same pixelSize matches across
    // scales.
    assertEquals(
        Font.mono().shape(sample, 24, 1).width(),
        Font.mono().shape(sample, 12, 2).width());
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
    assertEquals(
        "JetBrains Mono Bold is closed: this Font cannot be used any more.", e.getMessage());
    assertEquals(6, before.count(), "runs shaped before stay readable");
    font.close(); // a second close does nothing
    assertThrows(UnsupportedOperationException.class, () -> Font.sans().close());
  }

  @Test
  void bytesThatAreNotAFontAreRejected() {
    TextLibraries.assumeLoaded();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Font.of(new byte[] {1, 2, 3, 4}));
    assertTrue(
        e.getMessage().startsWith("These bytes are not a font that FreeType can read"),
        e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> Font.of(new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> Font.resource(FontTest.class, "none.ttf"));
  }

  @Test
  void theDefaultFontsNeedTheFontsModule() {
    // Needs no native library: the resource is looked for first.
    ClassLoader empty = new ClassLoader(null) {};
    IllegalStateException e = assertThrows(
        IllegalStateException.class, () -> Font.builtIn("fonts/Inter-Regular.ttf", empty));
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
    assertEquals("Inter Medium", Font.sansMedium().toString());
    assertEquals("Inter SemiBold", Font.sansSemibold().toString());
    assertEquals("Inter Italic", Font.sansItalic().toString());
    assertEquals("Inter Bold Italic", Font.sansBoldItalic().toString());
    assertEquals("JetBrains Mono Regular", Font.mono().toString());
    assertEquals("JetBrains Mono Bold", Font.monoBold().toString());
    assertSame(Font.mono(), Font.mono());
    // Inter: 1984 and 494 of 2048 units per em, no line gap.
    assertEquals(1984 / 2048f, Font.sans().ascent());
    assertEquals(494 / 2048f, Font.sans().descent());
    assertEquals(0, Font.sans().lineGap());
  }

  /** ic_fluent_add_16_regular, a cross of two strokes of one pixel at 16 pixels. */
  private static final String ADD = "\uF108";

  @Test
  void theIconFontsLineIsItsEmSquareAndItsGlyphsSitOnWholePixelsAtEveryScale() throws IOException {
    TextLibraries.assumeLoaded();
    Font icons = Font.icons();
    assertEquals("FluentSystemIcons-Regular Regular", icons.toString());
    assertSame(icons, Font.icons());
    assertEquals(1, icons.ascent());
    assertEquals(0, icons.descent());
    assertEquals(0, icons.lineGap(), "the font asks for 0.09, which would move icons down");
    assertTrue(icons.wholePixels());
    assertFalse(Font.sans().wholePixels());
    // At 13 and scale 1.5 an icon is 19.5 pixels: 20 on whole pixels, not 19.5 on quarters.
    GlyphRun run = icons.shape(ADD + ADD, 13, 1.5f);
    assertNotEquals(0, run.glyph(0));
    assertEquals(20, run.x(1));
    assertEquals(40, run.width());
    try (InputStream in = Font.class
            .getClassLoader()
            .getResourceAsStream("META-INF/rethinkj-ui/icons/FluentSystemIcons-Regular.ttf");
        Font plain = Font.of(in.readAllBytes())) {
      assertEquals(19.5f, plain.shape(ADD, 13, 1.5f).width(), "the same font as any other");
      assertEquals(0.09f, plain.lineGap(), 0.001f);
    }
  }

  /** ic_fluent_arrow_left_16_regular: a shaft of one pixel across the middle. */
  private static final String ARROW_LEFT = "";

  @Test
  void iconsAreHintedInBothDirectionsWhateverTheTextsHinting() {
    TextLibraries.assumeLoaded();
    Font icons = Font.icons();
    int glyph = icons.shape(ARROW_LEFT, 16, 1).glyph(0);
    Font.Bitmap light = icons.rasterize(glyph, 16, TextRendering.Hinting.LIGHT, 0);
    Font.Bitmap nativeHints = icons.rasterize(glyph, 16, TextRendering.Hinting.NATIVE, 0);
    Font.Bitmap none = icons.rasterize(glyph, 16, TextRendering.Hinting.NONE, 0);
    assertEquals(light.left(), nativeHints.left());
    assertEquals(light.top(), nativeHints.top());
    assertArrayEquals(light.pixels(), nativeHints.pixels(), "the same drawing under both");
    assertArrayEquals(light.pixels(), none.pixels(), "the same drawing under NONE");
    // In the middle, the shaft is one row of whole pixels, and no row is half covered.
    int full = 0;
    int half = 0;
    for (int y = 0; y < light.height(); y++) {
      int middle = light.pixels()[y * light.width() + light.width() / 2] & 0xFF;
      full += middle >= 230 ? 1 : 0;
      half += middle > 25 && middle < 230 ? 1 : 0;
    }
    assertEquals(1, full, "rows in which the shaft is solid");
    assertEquals(0, half, "rows in which it is half covered");
  }

  @Test
  void theDefaultIconFontNeedsTheIconsModule() {
    ClassLoader empty = new ClassLoader(null) {};
    IllegalStateException e = assertThrows(
        IllegalStateException.class,
        () -> Font.builtIn("icons/FluentSystemIcons-Regular.ttf", empty));
    assertEquals(
        "The default icon font is in the artifact com.rethinkj.ui:rethinkj-ui-icons, which is not"
            + " on the class path: META-INF/rethinkj-ui/icons/FluentSystemIcons-Regular.ttf is"
            + " missing. Add that artifact as a dependency (on the module path, also --add-modules"
            + " com.rethinkj.ui.icons).",
        e.getMessage());
  }

  @Test
  void aColrV0FontWithoutBaseOutlinesIsAcceptedByInspect() throws IOException {
    TextLibraries.assumeLoaded();
    byte[] bytes;
    try (InputStream in = FontTest.class.getResourceAsStream("/fonts/TwemojiMozilla-Subset.ttf")) {
      bytes = in.readAllBytes();
    }
    try (Arena arena = Arena.ofConfined()) {
      List<SystemFonts.Face> faces = Font.inspect(
          Path.of("TwemojiMozilla-Subset.ttf"), arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes));
      assertEquals(1, faces.size());
      assertTrue(faces.getFirst().has(0x1F600));
      assertTrue(faces.getFirst().color(), "ranked first for emoji");
    }
  }

  @Test
  void aCbdtFontWithoutOutlinesIsAcceptedAndScalesColorBitmaps() throws IOException {
    TextLibraries.assumeLoaded();
    byte[] bytes;
    try (InputStream in = FontTest.class.getResourceAsStream("/fonts/NotoColorEmoji-Subset.ttf")) {
      bytes = in.readAllBytes();
    }
    try (Arena arena = Arena.ofConfined()) {
      List<SystemFonts.Face> faces = Font.inspect(
          Path.of("NotoColorEmoji-Subset.ttf"), arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes));
      assertEquals(1, faces.size());
      assertTrue(faces.getFirst().has(0x1F600));
      assertTrue(faces.getFirst().color(), "ranked first for emoji");
    }
    Font cbdt = Examples.cbdtEmoji();
    assertTrue(cbdt.ascent() > 0.8f && cbdt.descent() > 0.2f);
    GlyphRun run = cbdt.shape("😀", 16, 1);
    assertEquals(1, run.count());
    assertNotEquals(0, run.glyph(0));
    assertTrue(run.advance(0) >= 18 && run.advance(0) <= 22, "advance " + run.advance(0));
    Font.Bitmap small = cbdt.rasterize(run.glyph(0), 16, TextRendering.Hinting.LIGHT, 0);
    assertTrue(small.color(), "CBDT glyph renders as a color bitmap");
    assertTrue(small.width() >= 18 && small.width() <= 22, small.toString());
    assertTrue(small.height() >= 17 && small.height() <= 21, small.toString());
    assertEquals(small.width() * small.height() * 4, small.pixels().length);
    Font.Bitmap shifted = cbdt.rasterize(run.glyph(0), 16, TextRendering.Hinting.LIGHT, 2);
    assertFalse(Arrays.equals(small.pixels(), shifted.pixels()), "quarter-pixel shift applied");
    Font.Bitmap large = cbdt.rasterize(run.glyph(0), 140, TextRendering.Hinting.LIGHT, 0);
    assertTrue(large.color());
    assertTrue(large.width() > 136 && large.height() > 128, "upscaled above 109 px: " + large);
  }

  static byte[] defaultFontBytes(String file) throws IOException {
    try (InputStream in =
        Font.class.getClassLoader().getResourceAsStream("META-INF/rethinkj-ui/fonts/" + file)) {
      return in.readAllBytes();
    }
  }
}
