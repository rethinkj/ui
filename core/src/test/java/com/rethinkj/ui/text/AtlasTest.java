package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.TextRendering.Hinting;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Rasterising and the atlas. Skipped without FreeType and HarfBuzz. */
class AtlasTest {

  private static final Hinting LIGHT = Hinting.LIGHT;

  @Test
  void aGlyphIsRasterisedForItsQuarterPixelOffsetAndHinting() {
    TextLibraries.assumeLoaded();
    Font inter = Font.sans();
    int h = glyph("H");
    Font.Bitmap whole = inter.rasterize(h, 14, LIGHT, 0);
    Font.Bitmap half = inter.rasterize(h, 14, LIGHT, 2);
    assertTrue(whole.width() > 0 && whole.height() >= 10, whole.toString());
    assertEquals(whole.height(), half.height(), "moved sideways only");
    assertEquals(whole.top(), half.top());
    assertFalse(Arrays.equals(whole.coverage(), half.coverage()), "half a pixel to the right");
    // Light hinting snaps heights only; the font's own hints differ in some glyph of a word.
    boolean differ = false;
    GlyphRun run = inter.shape("Kunde Müller, Köln", 13, 1);
    for (int i = 0; i < run.count() && !differ; i++) {
      differ = !Arrays.equals(
          inter.rasterize(run.glyph(i), 13, LIGHT, 0).coverage(),
          inter.rasterize(run.glyph(i), 13, Hinting.NATIVE, 0).coverage());
    }
    assertTrue(differ, "the two hintings give the same bitmaps");
    assertEquals(0, inter.rasterize(glyph(" "), 14, LIGHT, 0).width(), "a space has no ink");
  }

  @Test
  void aGlyphIsStoredOnceAndItsTexelsAreItsBitmap() {
    TextLibraries.assumeLoaded();
    Atlas atlas = new Atlas(64, 64 * 64, 100);
    atlas.nextFrame();
    Atlas.Slot slot = atlas.glyph(Font.sans(), 14, LIGHT, glyph("H"), 1);
    assertEquals(1, atlas.size());
    assertEquals(64 * 64, atlas.bytes());
    Atlas.Page page = atlas.pages().getFirst();
    assertEquals(
        new Atlas.Region(slot.x(), slot.y(), slot.width() + 1, slot.height() + 1),
        page.takeChanged(),
        "the bitmap and the empty texels right of it and below");
    assertSame(Atlas.Region.EMPTY, page.changed());

    Font.Bitmap bitmap = Font.sans().rasterize(glyph("H"), 14, LIGHT, 1);
    assertEquals(bitmap.left(), slot.left());
    assertEquals(bitmap.top(), slot.top());
    for (int y = 0; y <= slot.height(); y++) {
      for (int x = 0; x <= slot.width(); x++) {
        int expected = x < slot.width() && y < slot.height()
            ? bitmap.coverage()[y * slot.width() + x] & 0xFF
            : 0;
        assertEquals(expected, page.coverage(slot.x() + x, slot.y() + y), x + "," + y);
      }
    }

    atlas.nextFrame();
    assertEquals(slot, atlas.glyph(Font.sans(), 14, LIGHT, glyph("H"), 1), "reused");
    assertEquals(1, atlas.size());
    assertTrue(page.changed().isEmpty(), "nothing written");
    Atlas.Slot other = atlas.glyph(Font.sans(), 14, LIGHT, glyph("H"), 2);
    assertNotEquals(slot, other, "another offset is another bitmap");
    assertEquals(
        new Atlas.Region(other.x(), other.y(), other.width() + 1, other.height() + 1),
        page.takeChanged(),
        "only the new glyph changed");
    assertTrue(atlas.glyph(Font.sans(), 14, LIGHT, glyph(" "), 0).isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> atlas.glyph(Font.sans(), 14, LIGHT, glyph("H"), 4));
  }

  @Test
  void theLeastRecentlyUsedGlyphsGoBeyondTheBoundButNotThoseOfThisFrame() {
    TextLibraries.assumeLoaded();
    Atlas atlas = new Atlas(64, 64 * 64, 3);
    int[] glyphs = {glyph("a"), glyph("b"), glyph("c"), glyph("d")};
    for (int g : glyphs) {
      atlas.nextFrame();
      atlas.glyph(Font.sans(), 14, LIGHT, g, 0);
    }
    assertEquals(3, atlas.size(), "a, the oldest, is gone");
    atlas.nextFrame();
    atlas.glyph(Font.sans(), 14, LIGHT, glyphs[1], 0); // b is used again
    atlas.glyph(Font.sans(), 14, LIGHT, glyphs[0], 0); // a comes back: c goes
    assertEquals(3, atlas.size());

    // One frame that needs more than the bound keeps all it needs.
    atlas.nextFrame();
    for (int g : glyphs) {
      atlas.glyph(Font.sans(), 14, LIGHT, g, 1);
    }
    assertEquals(4, atlas.size(), "this frame's four, over the bound");
    atlas.nextFrame();
    atlas.glyph(Font.sans(), 14, LIGHT, glyphs[0], 0);
    assertEquals(3, atlas.size(), "back to the bound once they are old");
  }

  @Test
  void aFullAtlasEmptiesThePageUsedLeastRecentlyButNeverOneInUse() {
    TextLibraries.assumeLoaded();
    // A page of 32 texels holds one o at 40 pixels: about 21 by 23 texels, and one more each way.
    Atlas atlas = new Atlas(32, 32 * 32, 1000);
    int o = glyph("o");
    atlas.nextFrame();
    Atlas.Slot first = atlas.glyph(Font.sans(), 40, LIGHT, o, 0);
    for (int q = 1; q < 4; q++) {
      atlas.glyph(Font.sans(), 40, LIGHT, o, q);
    }
    assertEquals(4, atlas.pages().size(), "over the bound rather than evicting what is in use");
    assertEquals(4 * 32 * 32, atlas.bytes());

    atlas.nextFrame();
    assertEquals(first, atlas.glyph(Font.sans(), 40, LIGHT, o, 0), "still there");
    Atlas.Slot bigger = atlas.glyph(Font.sans(), 44, LIGHT, o, 0);
    assertEquals(4, atlas.pages().size(), "no new page beyond the bound");
    assertEquals(1, bigger.page(), "the first page not used in this frame");
    Atlas.Page emptied = atlas.pages().get(1);
    assertEquals(new Atlas.Region(0, 0, 32, 32), emptied.takeChanged(), "cleared, so all of it");
    assertEquals(0, emptied.coverage(31, 31));
    assertEquals(4, atlas.size(), "the o of the emptied page is forgotten");
    assertEquals(first, atlas.glyph(Font.sans(), 40, LIGHT, o, 0));
  }

  private static int glyph(String character) {
    return Font.sans().shape(character, 14, 1).glyph(0);
  }
}
