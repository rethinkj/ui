package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shaping with fallback fonts named by the app. Skipped without FreeType and HarfBuzz. */
class FallbackTest {

  @BeforeEach
  void needsTheTextLibraries() {
    TextLibraries.assumeLoaded();
  }

  @Test
  void aCharacterTheFontLacksComesFromTheFirstFallbackThatHasIt() {
    Font chain = Font.sans().withFallback(Font.mono());
    assertTrue(!Font.sans().has('├') && Font.mono().has('├'));
    GlyphRun run = chain.shape("a├b", 14, 1);
    assertEquals(3, run.count());
    assertSame(chain, run.font());
    assertSame(Font.sans(), run.font(0));
    assertSame(Font.mono(), run.font(1));
    assertSame(Font.sans(), run.font(2));
    assertEquals(Font.mono().shape("├", 14, 1).glyph(0), run.glyph(1));
    assertNotEquals(0, run.glyph(1));
    GlyphRun alone = Font.sans().shape("a", 14, 1);
    assertEquals(alone.width(), run.pen(1), "the runs follow each other");
    assertEquals(alone.width() + Font.mono().shape("├", 14, 1).width(), run.pen(2));
    assertEquals(0, Font.sans().shape("a├b", 14, 1).glyph(1), "without the fallback: glyph 0");
    assertSame(run, chain.shape("a├b", 14, 1), "the chain keeps its runs");
  }

  @Test
  void aCharacterNoFontHasIsTheFirstFontsNotDefinedGlyph() {
    GlyphRun run = Font.sans().withFallback(Font.mono()).shape("a中b", 14, 1);
    assertEquals(0, run.glyph(1));
    assertSame(Font.sans(), run.font(1));
  }

  @Test
  void chainsAreTheSameObjectForTheSameFontsAndFlatten() {
    Font chain = Font.sans().withFallback(Font.mono(), Font.monoBold());
    assertSame(chain, Font.sans().withFallback(Font.mono(), Font.monoBold()));
    assertSame(chain, Font.sans().withFallback(Font.mono().withFallback(Font.monoBold())));
    assertSame(chain, Font.sans().withFallback(Font.mono()).withFallback(Font.monoBold()));
    assertSame(Font.sans(), Font.sans().withFallback(), "no fallback: the font itself");
    assertSame(Font.sans(), Font.sans().withFallback(Font.sans()));
    assertNotSame(chain, chain.withSystemFonts());
    assertSame(chain.withSystemFonts(), chain.withSystemFonts());
    assertEquals("Inter Regular, JetBrains Mono Regular, JetBrains Mono Bold", chain.toString());
    assertEquals(Font.sans().ascent(), chain.ascent(), "the metrics are the first font's");
    chain.close(); // owns nothing
    assertEquals(3, chain.shape("a├b", 14, 1).count());
  }

  @Test
  void hebrewIsShapedRightToLeftAsOneCluster() {
    Font chain = Font.sans().withFallback(Examples.hebrew());
    String text = "ab שלום";
    GlyphRun run = chain.shape(text, 14, 1);
    assertEquals(7, run.count());
    GlyphRun hebrew = Examples.hebrew().shape("ם", 14, 1);
    assertEquals(hebrew.glyph(0), run.glyph(3), "the last letter, a final mem, comes first");
    assertEquals(Examples.hebrew().shape("ש", 14, 1).glyph(0), run.glyph(6));
    for (int i = 3; i < 7; i++) {
      assertSame(Examples.hebrew(), run.font(i));
      assertEquals(3, run.cluster(i), "every glyph of the run at its first character");
    }
    assertSame(Font.sans(), run.font(2), "the space before the run is Inter's");
  }

  @Test
  void aThaiClusterKeepsItsMarksInItsFont() {
    Font chain = Font.sans().withFallback(Examples.thai());
    String text = "a ไม้";
    GlyphRun run = chain.shape(text, 14, 1);
    int base = -1;
    for (int i = 0; i < run.count(); i++) {
      if (run.cluster(i) == 3) {
        assertSame(Examples.thai(), run.font(i));
        base = base < 0 ? i : base;
      }
    }
    assertTrue(base > 0 && run.count() - base == 2, "ม and its tone mark: " + run.count());
    assertEquals(0, run.advance(run.count() - 1), "the mark takes no room");
  }

  @Test
  void slicesKeepTheFontsOfTheirGlyphs() {
    GlyphRun run = Font.sans().withFallback(Font.mono()).shape("a├b", 14, 1).slice(1, 3);
    assertSame(Font.mono(), run.font(0));
    assertSame(Font.sans(), run.font(1));
    assertEquals(0, run.pen(0));
  }
}
