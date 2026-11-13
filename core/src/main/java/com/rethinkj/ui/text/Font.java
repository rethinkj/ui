package com.rethinkj.ui.text;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import com.rethinkj.ui.natives.Natives;
import com.rethinkj.ui.text.ffi.FreeType;
import com.rethinkj.ui.text.ffi.HarfBuzz;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A TrueType or OpenType font, shaped with HarfBuzz and rasterised with FreeType. The
 * font's bytes are copied into native memory and stay there, with the native objects made from
 * them, until {@link #close}; using a closed font throws {@link IllegalStateException}.
 *
 * <p>{@link #sans()} and {@link #mono()} and their bold weights are the default fonts, Inter and
 * JetBrains Mono, which come in the artifact {@code com.rethinkj.ui:rethinkj-ui-fonts}, and
 * {@link #icons()} is the default icon font, which comes in {@code rethinkj-ui-icons}. They are
 * loaded on first use and stay open while the process runs.
 *
 * <p>A font may be used from several threads; its calls take turns.
 */
public final class Font implements AutoCloseable {

  private static final String DEFAULTS = "META-INF/rethinkj-ui/";
  private static final String ICONS = "icons/";
  private static final String[] DEFAULT_FILES = {
    "fonts/Inter-Regular.ttf", "fonts/Inter-Bold.ttf", "fonts/JetBrainsMono-Regular.ttf",
    "fonts/JetBrainsMono-Bold.ttf", ICONS + "FluentSystemIcons-Regular.ttf"
  };
  private static final Font[] DEFAULT_FONTS = new Font[DEFAULT_FILES.length];

  /** Shaped runs kept per font, the least recently used dropped first. */
  // shortcut: a fixed number of runs per font; make it configurable when a view shows more
  // distinct strings per frame than this.
  private static final int CACHED_RUNS = 1024;

  /** Applied before the caller's features, so that {@code +calt} turns contextual forms on. */
  private static final String DEFAULT_FEATURES = "-calt";

  private static MemorySegment library;

  private final String name;
  private final boolean shared;
  private final boolean icons;
  private final Arena arena;
  private final MemorySegment face;
  private final MemorySegment blob;
  private final MemorySegment hbFace;
  private final MemorySegment hbFont;
  private final int unitsPerEm;
  private final float ascent;
  private final float descent;
  private final float lineGap;
  private final Map<Key, GlyphRun> runs = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Key, GlyphRun> eldest) {
      return size() > CACHED_RUNS;
    }
  };
  private boolean closed;
  private long rasterSize; // the size last set on the FreeType face, in 1/64 pixels

  /**
   * A rasterised glyph: its coverage, one byte per pixel from 0 (none) to 255 (all), rows from the
   * top, and where the bitmap lies relative to the glyph's origin on the baseline.
   *
   * @param left how far the left edge lies right of the origin, in pixels
   * @param top how far the top edge lies above the baseline
   */
  record Bitmap(int left, int top, int width, int height, byte[] coverage) {}

  private record Key(String text, float pixelSize, boolean quarter, List<String> features) {}

  private Font(byte[] bytes, boolean shared, boolean icons) {
    if (bytes.length == 0) {
      throw new IllegalArgumentException("The font data is empty.");
    }
    // Loads both libraries with an explanation if one is missing, before any binding is used.
    Natives.lookup("freetype");
    Natives.lookup("harfbuzz");
    this.shared = shared;
    this.icons = icons;
    arena = Arena.ofShared();
    MemorySegment data = arena.allocateFrom(JAVA_BYTE, bytes);
    try {
      face = openFace(data);
    } catch (RuntimeException e) {
      arena.close();
      throw e;
    }
    name = string(face.get(ADDRESS, FreeType.FACE_FAMILY_NAME)) + " "
        + string(face.get(ADDRESS, FreeType.FACE_STYLE_NAME));
    blob = HarfBuzz.blobCreate(data, bytes.length, HarfBuzz.MEMORY_MODE_READONLY);
    hbFace = HarfBuzz.faceCreate(blob, 0);
    hbFont = HarfBuzz.fontCreate(hbFace);
    if (HarfBuzz.faceGetGlyphCount(hbFace) == 0) {
      destroy();
      throw new IllegalArgumentException(
          "HarfBuzz cannot read " + name + ": only TrueType and OpenType fonts can be shaped.");
    }
    unitsPerEm = HarfBuzz.faceGetUpem(hbFace);
    try (Arena call = Arena.ofConfined()) {
      MemorySegment extents = call.allocate(HarfBuzz.FONT_EXTENTS);
      HarfBuzz.fontGetHExtents(hbFont, extents);
      // The font's own scale is its units per em, so these are font units.
      ascent = extents.get(JAVA_INT, HarfBuzz.EXTENTS_ASCENDER) / (float) unitsPerEm;
      descent = -extents.get(JAVA_INT, HarfBuzz.EXTENTS_DESCENDER) / (float) unitsPerEm;
      // An icon's drawing fills the em square, which is its line: a gap would move it off its box.
      lineGap = icons ? 0 : extents.get(JAVA_INT, HarfBuzz.EXTENTS_LINE_GAP) / (float) unitsPerEm;
    }
  }

  /**
   * A font from the bytes of a TrueType ({@code .ttf}) or OpenType ({@code .otf}) file; of a
   * collection, the first font. The bytes are copied.
   *
   * @throws IllegalArgumentException if the bytes are not such a font
   * @throws UnsatisfiedLinkError if FreeType or HarfBuzz cannot be loaded
   */
  public static Font of(byte[] data) {
    return new Font(data, false, false);
  }

  /**
   * A font from a resource, found as {@link Class#getResourceAsStream} finds it: relative to
   * {@code owner}'s package, or from the root with a leading {@code /}.
   *
   * @throws IllegalArgumentException if there is no such resource, or it is not a font
   */
  public static Font resource(Class<?> owner, String name) {
    try (InputStream in = owner.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalArgumentException(
            "There is no resource " + name + " next to " + owner.getName() + ".");
      }
      return of(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the font " + name, e);
    }
  }

  /** Inter Regular, the default font for user interfaces. */
  public static Font sans() {
    return builtIn(0);
  }

  /** Inter Bold. */
  public static Font sansBold() {
    return builtIn(1);
  }

  /** JetBrains Mono Regular, the default monospaced font. */
  public static Font mono() {
    return builtIn(2);
  }

  /** JetBrains Mono Bold. */
  public static Font monoBold() {
    return builtIn(3);
  }

  /**
   * Fluent UI System Icons Regular, the default icon font, from the artifact {@code
   * com.rethinkj.ui:rethinkj-ui-icons}. An icon is drawn anew for several sizes in pixels (most
   * for 16, 20 and 24, many also for 12, 28, 32 or 48), and each drawing is a glyph at a code point
   * of its own in the private use area, sharpest at its font size. Its line is the em square, which the drawings fill,
   * so a text element of one icon is as wide and as high as its font size, and its glyphs are
   * placed on {@link #wholePixels whole pixels}.
   */
  public static Font icons() {
    return builtIn(4);
  }

  private static synchronized Font builtIn(int index) {
    if (DEFAULT_FONTS[index] == null) {
      DEFAULT_FONTS[index] = builtIn(DEFAULT_FILES[index], Font.class.getClassLoader());
    }
    return DEFAULT_FONTS[index];
  }

  /**
   * A default font from {@code loader}, such as {@code "fonts/Inter-Regular.ttf"}; for the tests
   * that leave the fonts and icons modules out.
   */
  static Font builtIn(String file, ClassLoader loader) {
    String path = DEFAULTS + file;
    boolean icons = file.startsWith(ICONS);
    try (InputStream in = loader.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException(icons
            ? "The default icon font is in the artifact com.rethinkj.ui:rethinkj-ui-icons, which"
                + " is not on the class path: " + path + " is missing. Add that artifact as a"
                + " dependency (on the module path, also --add-modules com.rethinkj.ui.icons)."
            : "The default fonts are in the artifact com.rethinkj.ui:rethinkj-ui-fonts, which is"
                + " not on the class path: " + path + " is missing. Add that artifact as a"
                + " dependency (on the module path, also --add-modules com.rethinkj.ui.fonts), or"
                + " load a font of your own with Font.of or Font.resource.");
      }
      return new Font(in.readAllBytes(), true, icons);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the default font " + path, e);
    }
  }

  /**
   * Shapes {@code text} with HarfBuzz into glyphs with positions, in physical pixels, the same
   * glyphs for the same arguments: the font keeps recent results.
   *
   * <p>Text is shaped at the physical size, {@code size * scale}. Advances and offsets are then
   * rounded: to whole physical pixels below scale 1.5, where glyphs sit on whole pixels, and to
   * quarter pixels from scale 1.5 on; those of a font with {@link #wholePixels} to whole pixels at
   * every scale.
   *
   * <p>Kerning is on. Contextual alternates ({@code calt}) are off, because in JetBrains Mono and
   * similar fonts they draw {@code !=} or {@code ->} as one sign; {@code "+calt"} turns them back
   * on. Text is shaped left to right in the script of its first letters, without bidirectional
   * reordering, and only with this font: characters it lacks get its "not defined" glyph, 0.
   *
   * @param size the font size in logical pixels
   * @param scale physical pixels per logical pixel
   * @param features OpenType features in HarfBuzz's syntax, applied in order: {@code "-kern"}
   *     turns kerning off, {@code "+calt"} or {@code "calt"} turns contextual alternates on,
   *     {@code "tnum"} gives digits of one width
   * @throws IllegalArgumentException if a size is not above 0 or a feature cannot be parsed
   * @throws IllegalStateException if the font is closed
   */
  public GlyphRun shape(String text, float size, float scale, String... features) {
    Objects.requireNonNull(text, "text");
    if (!(size > 0 && size < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("size must be above 0, was " + size);
    }
    if (!(scale > 0 && scale < Float.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException("scale must be above 0, was " + scale);
    }
    Key key = new Key(text, size * scale, scale >= 1.5f && !icons, List.of(features));
    synchronized (this) {
      checkOpen();
      GlyphRun run = runs.get(key);
      if (run == null) {
        run = shape(key);
        runs.put(key, run);
      }
      return run;
    }
  }

  private GlyphRun shape(Key key) {
    String text = key.text;
    try (Arena call = Arena.ofConfined()) {
      int count = 1 + key.features.size();
      MemorySegment features = call.allocate(HarfBuzz.FEATURE, count);
      for (int i = 0; i < count; i++) {
        String feature = i == 0 ? DEFAULT_FEATURES : key.features.get(i - 1);
        if (!HarfBuzz.featureFromString(call.allocateFrom(feature), -1,
            features.asSlice(i * HarfBuzz.FEATURE.byteSize(), HarfBuzz.FEATURE))) {
          throw new IllegalArgumentException(
              "\"" + feature + "\" is not an OpenType feature in HarfBuzz's syntax, such as"
                  + " \"-kern\", \"+calt\" or \"ss01\".");
        }
      }
      MemorySegment buffer = HarfBuzz.bufferCreate();
      try {
        HarfBuzz.bufferAddUtf16(buffer, call.allocateFrom(JAVA_CHAR, text.toCharArray()),
            text.length(), 0, text.length());
        // shortcut: one left-to-right run in one script. Bidirectional text (UAX #9) and
        // mixed scripts need the text split into runs first.
        HarfBuzz.bufferSetDirection(buffer, HarfBuzz.DIRECTION_LTR);
        HarfBuzz.bufferGuessSegmentProperties(buffer);
        HarfBuzz.shape(hbFont, buffer, features, count);
        if (!HarfBuzz.bufferAllocationSuccessful(buffer)) {
          throw new OutOfMemoryError("HarfBuzz could not allocate memory to shape the text.");
        }
        MemorySegment length = call.allocate(JAVA_INT);
        MemorySegment infos = HarfBuzz.bufferGetGlyphInfos(buffer, length);
        int n = length.get(JAVA_INT, 0);
        infos = infos.reinterpret(n * HarfBuzz.GLYPH_INFO.byteSize());
        MemorySegment positions = HarfBuzz.bufferGetGlyphPositions(buffer, length)
            .reinterpret(n * HarfBuzz.GLYPH_POSITION.byteSize());
        return run(key, infos, positions, n);
      } finally {
        HarfBuzz.bufferDestroy(buffer);
      }
    }
  }

  /** Scales HarfBuzz's font units to physical pixels and rounds them to the grid. */
  private GlyphRun run(Key key, MemorySegment infos, MemorySegment positions, int n) {
    // shortcut: HarfBuzz shapes in font units, which are then scaled here. The same as setting
    // the scale on the font, minus its rounding to 1/64 pixels, and the font stays unchanged.
    double perUnit = key.pixelSize / unitsPerEm;
    double grid = key.quarter ? 4 : 1;
    int[] glyphs = new int[n];
    int[] clusters = new int[n];
    float[] pen = new float[n + 1];
    float[] dx = new float[n];
    float[] dy = new float[n];
    for (int i = 0; i < n; i++) {
      long info = i * HarfBuzz.GLYPH_INFO.byteSize();
      long position = i * HarfBuzz.GLYPH_POSITION.byteSize();
      glyphs[i] = infos.get(JAVA_INT, info + HarfBuzz.INFO_GLYPH);
      clusters[i] = infos.get(JAVA_INT, info + HarfBuzz.INFO_CLUSTER);
      pen[i + 1] = pen[i]
          + round(positions.get(JAVA_INT, position + HarfBuzz.POSITION_X_ADVANCE), perUnit, grid);
      dx[i] = round(positions.get(JAVA_INT, position + HarfBuzz.POSITION_X_OFFSET), perUnit, grid);
      // HarfBuzz's y grows upwards.
      dy[i] = round(-positions.get(JAVA_INT, position + HarfBuzz.POSITION_Y_OFFSET), perUnit,
          grid);
    }
    return new GlyphRun(this, key.pixelSize, glyphs, clusters, pen, dx, dy);
  }

  /**
   * Rasterises a glyph with FreeType into greyscale coverage, moved right by {@code quarter}
   * quarter pixels after hinting, so that a glyph on a quarter-pixel position is drawn from a
   * bitmap at a whole one.
   *
   * @param pixelSize the size in physical pixels, as {@link GlyphRun#pixelSize}
   * @param quarter 0 to 3
   * @throws IllegalArgumentException if FreeType cannot load or render the glyph
   */
  Bitmap rasterize(int glyph, float pixelSize, TextRendering.Hinting hinting, int quarter) {
    synchronized (this) {
      checkOpen();
      long size = Math.round(pixelSize * 64.0);
      if (size != rasterSize) {
        check(FreeType.setCharSize(face, 0, size, 72, 72), "set the size " + pixelSize + " on");
        rasterSize = size;
      }
      try (Arena call = Arena.ofConfined()) {
        MemorySegment delta = call.allocate(FreeType.VECTOR);
        FreeType.setLong(delta, 0, quarter * 16L);
        FreeType.setLong(delta, FreeType.C_LONG.byteSize(), 0);
        FreeType.setTransform(face, MemorySegment.NULL, delta); // copies the vector
      }
      int flags = FreeType.LOAD_NO_BITMAP | switch (hinting) {
        case LIGHT -> FreeType.LOAD_FORCE_AUTOHINT | FreeType.LOAD_TARGET_LIGHT;
        case NATIVE -> FreeType.LOAD_DEFAULT;
      };
      check(FreeType.loadGlyph(face, glyph, flags), "load glyph " + glyph + " of");
      MemorySegment slot =
          face.get(ADDRESS, FreeType.FACE_GLYPH).reinterpret(FreeType.GLYPH_SLOT.byteSize());
      check(FreeType.renderGlyph(slot, FreeType.RENDER_MODE_NORMAL),
          "render glyph " + glyph + " of");
      long bitmap = FreeType.SLOT_BITMAP;
      int rows = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_ROWS);
      int width = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_WIDTH);
      int pitch = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_PITCH);
      byte[] coverage = new byte[width * rows];
      if (width > 0 && rows > 0) {
        if (slot.get(JAVA_BYTE, bitmap + FreeType.BITMAP_PIXEL_MODE) != FreeType.PIXEL_MODE_GRAY
            || pitch < width) {
          throw new IllegalStateException("FreeType rendered glyph " + glyph + " of " + name
              + " in an unexpected format: pitch " + pitch + " for width " + width);
        }
        MemorySegment pixels = slot.get(ADDRESS, bitmap + FreeType.BITMAP_BUFFER)
            .reinterpret((long) pitch * rows);
        for (int y = 0; y < rows; y++) {
          MemorySegment.copy(pixels, JAVA_BYTE, (long) y * pitch, coverage, y * width, width);
        }
      }
      return new Bitmap(slot.get(JAVA_INT, FreeType.SLOT_BITMAP_LEFT),
          slot.get(JAVA_INT, FreeType.SLOT_BITMAP_TOP), width, rows, coverage);
    }
  }

  private void check(int error, String what) {
    if (error != 0) {
      throw new IllegalArgumentException(
          "FreeType could not " + what + " " + name + ": error " + error + ".");
    }
  }

  private static float round(int units, double perUnit, double grid) {
    return (float) (Math.round(units * perUnit * grid) / grid);
  }

  /**
   * Whether the glyphs of this font are placed on whole physical pixels at every scale, not on
   * quarter pixels from scale 1.5 on: true for {@link #icons()}, whose drawings are made for the
   * pixel grid, which a glyph between two pixels would blur.
   */
  public boolean wholePixels() {
    return icons;
  }

  /** The height above the baseline that the font reserves, as a fraction of the font size. */
  public float ascent() {
    return ascent;
  }

  /** The depth below the baseline that the font reserves, as a positive fraction of the size. */
  public float descent() {
    return descent;
  }

  /** The extra space between lines that the font asks for, as a fraction of the size. */
  public float lineGap() {
    return lineGap;
  }

  /**
   * Frees the native objects and the copy of the font data. Afterwards every call but this one
   * and {@link #toString} throws {@link IllegalStateException}. A second close does nothing.
   *
   * @throws UnsupportedOperationException for a default font, which other code may be using
   */
  @Override
  public void close() {
    if (shared) {
      throw new UnsupportedOperationException(
          name + " is a default font, which stays open while the process runs.");
    }
    synchronized (this) {
      if (!closed) {
        closed = true;
        runs.clear();
        destroy();
      }
    }
  }

  private void destroy() {
    HarfBuzz.fontDestroy(hbFont);
    HarfBuzz.faceDestroy(hbFace);
    HarfBuzz.blobDestroy(blob);
    synchronized (Font.class) {
      FreeType.doneFace(face);
    }
    arena.close();
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException(name + " is closed: this Font cannot be used any more.");
    }
  }

  /** The family and style, such as {@code Inter Regular}. */
  @Override
  public String toString() {
    return name;
  }

  /** Opens the FreeType face; FreeType wants its library used by one thread at a time. */
  private static synchronized MemorySegment openFace(MemorySegment data) {
    try (Arena call = Arena.ofConfined()) {
      if (library == null) {
        MemorySegment out = call.allocate(ADDRESS);
        int error = FreeType.initFreeType(out);
        if (error != 0) {
          throw new IllegalStateException("FreeType could not start: error " + error + ".");
        }
        library = out.get(ADDRESS, 0);
      }
      MemorySegment out = call.allocate(ADDRESS);
      int error = FreeType.newMemoryFace(library, data, data.byteSize(), 0, out);
      if (error != 0) {
        throw new IllegalArgumentException(
            "These bytes are not a font that FreeType can read: FreeType error " + error + ".");
      }
      MemorySegment face = out.get(ADDRESS, 0).reinterpret(FreeType.FACE.byteSize());
      if ((FreeType.getLong(face, FreeType.FACE_FACE_FLAGS) & FreeType.FACE_FLAG_SCALABLE) == 0
          || face.get(JAVA_SHORT, FreeType.FACE_UNITS_PER_EM) == 0) {
        FreeType.doneFace(face);
        throw new IllegalArgumentException(
            "The font has no outlines, only bitmaps: it cannot be drawn at any size.");
      }
      return face;
    }
  }

  private static String string(MemorySegment pointer) {
    return pointer.equals(MemorySegment.NULL) ? "?"
        : pointer.reinterpret(Long.MAX_VALUE).getString(0);
  }
}
