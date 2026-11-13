package com.rethinkj.ui.text.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextLibraries;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The bindings that rasterising will use, on a real face: the struct layouts have to put the
 * bitmap where FreeType does. Skipped without FreeType and HarfBuzz.
 */
class FreeTypeTest {

  /** A rendered glyph's bitmap, from the face's glyph slot. */
  private record Bitmap(int left, int top, int width, int rows, int pitch, int mode, int inked) {}

  private final Arena arena = Arena.ofConfined();
  private MemorySegment face;

  @AfterEach
  void close() {
    if (face != null) {
      assertEquals(0, FreeType.doneFace(face));
    }
    arena.close();
  }

  @Test
  void layoutsHaveTheSizesOfTheHeaderOnX64() {
    long cLong = FreeType.C_LONG.byteSize();
    assertEquals(System.getProperty("os.name").startsWith("Windows") ? 4 : 8, cLong);
    assertEquals(2 * cLong, FreeType.VECTOR.byteSize());
    assertEquals(40, FreeType.BITMAP.byteSize());
    assertEquals(20, HarfBuzz.GLYPH_INFO.byteSize());
    assertEquals(20, HarfBuzz.GLYPH_POSITION.byteSize());
    assertEquals(16, HarfBuzz.FEATURE.byteSize());
    assertEquals(48, HarfBuzz.FONT_EXTENTS.byteSize());
    if (cLong == 8) {
      assertEquals(136, FreeType.FACE_UNITS_PER_EM);
      assertEquals(152, FreeType.SLOT_BITMAP);
      assertEquals(196, FreeType.SLOT_BITMAP_TOP);
    }
  }

  @Test
  void rendersAGlyphAtASizeAndAQuarterPixelOffset() throws IOException {
    TextLibraries.assumeLoaded();
    open("Inter-Regular.ttf", 14);
    assertEquals(2048, face.get(JAVA_SHORT, FreeType.FACE_UNITS_PER_EM));
    assertTrue(FreeType.getLong(face, FreeType.FACE_NUM_GLYPHS) > 1000);
    MemorySegment delta = arena.allocate(FreeType.VECTOR);
    FreeType.setLong(delta, 0, 16); // a quarter pixel to the right
    FreeType.setTransform(face, MemorySegment.NULL, delta);

    Bitmap h = render(Font.sans().shape("H", 14, 1).glyph(0));
    // Inter's capitals are 0.727 em tall: about 10 pixels at 14.
    assertEquals(FreeType.PIXEL_MODE_GRAY, h.mode);
    assertTrue(h.rows >= 10 && h.rows <= 12, h.toString());
    assertTrue(h.top >= 10 && h.top <= 12, h.toString());
    assertTrue(h.width >= 8 && h.width <= 12 && h.pitch >= h.width, h.toString());
    assertTrue(h.inked > 2 * h.rows, "an H has two stems: " + h);
  }

  /** {@code !=} with {@code calt} on draws one sign over both cells; see FontTest. */
  @Test
  void contextualAlternatesDrawNotEqualsAsOneSign() throws IOException {
    TextLibraries.assumeLoaded();
    open("JetBrainsMono-Regular.ttf", 28);
    GlyphRun ligature = Font.mono().shape("!=", 28, 1, "+calt");
    Bitmap spacer = render(ligature.glyph(0));
    Bitmap sign = render(ligature.glyph(1));
    assertEquals(0, spacer.width, "the first cell is empty");
    assertTrue(sign.left < 0 && sign.left + sign.width > 0, "the sign reaches back: " + sign);
    assertTrue(sign.width > ligature.advance(1), "and spans more than its cell: " + sign);
  }

  private void open(String file, int pixels) throws IOException {
    byte[] bytes;
    try (InputStream in = Font.class.getClassLoader()
        .getResourceAsStream("META-INF/rethinkj-ui/fonts/" + file)) {
      bytes = in.readAllBytes();
    }
    MemorySegment out = arena.allocate(ADDRESS);
    // shortcut: each test starts a FreeType library of its own and leaves it; there is no
    // binding for FT_Done_FreeType yet.
    assertEquals(0, FreeType.initFreeType(out));
    MemorySegment library = out.get(ADDRESS, 0);
    MemorySegment data = arena.allocateFrom(JAVA_BYTE, bytes);
    assertEquals(0, FreeType.newMemoryFace(library, data, bytes.length, 0, out));
    face = out.get(ADDRESS, 0).reinterpret(FreeType.FACE.byteSize());
    assertEquals(0, FreeType.setCharSize(face, 0, pixels * 64L, 72, 72));
  }

  private Bitmap render(int glyph) {
    assertEquals(0, FreeType.loadGlyph(face, glyph,
        FreeType.LOAD_TARGET_LIGHT | FreeType.LOAD_NO_BITMAP));
    MemorySegment slot = face.get(ADDRESS, FreeType.FACE_GLYPH)
        .reinterpret(FreeType.GLYPH_SLOT.byteSize());
    assertEquals(0, FreeType.renderGlyph(slot, FreeType.RENDER_MODE_LIGHT));
    long bitmap = FreeType.SLOT_BITMAP;
    int rows = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_ROWS);
    int width = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_WIDTH);
    int pitch = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_PITCH);
    int inked = 0;
    if (rows > 0) {
      MemorySegment pixels = slot.get(ADDRESS, bitmap + FreeType.BITMAP_BUFFER)
          .reinterpret((long) pitch * rows);
      for (int y = 0; y < rows; y++) {
        for (int x = 0; x < width; x++) {
          inked += (pixels.get(JAVA_BYTE, (long) y * pitch + x) & 0xFF) > 128 ? 1 : 0;
        }
      }
    }
    return new Bitmap(slot.get(JAVA_INT, FreeType.SLOT_BITMAP_LEFT),
        slot.get(JAVA_INT, FreeType.SLOT_BITMAP_TOP), width, rows, pitch,
        slot.get(JAVA_BYTE, bitmap + FreeType.BITMAP_PIXEL_MODE), inked);
  }
}
