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
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>A font may fall back to other fonts for the characters it lacks: {@link #withFallback} names
 * them, {@link #withSystemFonts} adds the fonts installed on the system after those. Such a font
 * owns no native objects of its own; it uses the fonts it was made of.
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
  // The font that owns the native objects and draws: this, or the font withFallback was called
  // on. For a font of fallbacks, the fields of native objects below are null.
  private final Font base;
  private final List<Font> fallback; // fonts with native objects, tried in order after base
  private final boolean system; // whether the system's fonts come after those
  private final Map<List<Object>, Font> derived = new HashMap<>(); // of a base: its chains
  private final int styleFlags; // FreeType's bold and italic flags
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
    this(copy(bytes), 0, shared, icons);
  }

  private static Map.Entry<Arena, MemorySegment> copy(byte[] bytes) {
    if (bytes.length == 0) {
      throw new IllegalArgumentException("The font data is empty.");
    }
    // Loads both libraries with an explanation if one is missing, before any binding is used.
    Natives.lookup("freetype");
    Natives.lookup("harfbuzz");
    Arena arena = Arena.ofShared();
    return Map.entry(arena, arena.allocateFrom(JAVA_BYTE, bytes));
  }

  /** A font of the data, which {@code data}'s arena holds; the font closes the arena. */
  private Font(
      Map.Entry<Arena, MemorySegment> data, int index, boolean shared, boolean icons) {
    this.shared = shared;
    this.icons = icons;
    base = this;
    fallback = List.of();
    system = false;
    arena = data.getKey();
    try {
      face = openFace(data.getValue(), index);
    } catch (RuntimeException e) {
      arena.close();
      throw e;
    }
    name = string(face.get(ADDRESS, FreeType.FACE_FAMILY_NAME)) + " "
        + string(face.get(ADDRESS, FreeType.FACE_STYLE_NAME));
    styleFlags = (int) (FreeType.getLong(face, FreeType.FACE_STYLE_FLAGS)
        & (FreeType.STYLE_FLAG_BOLD | FreeType.STYLE_FLAG_ITALIC));
    blob = HarfBuzz.blobCreate(data.getValue(), Math.toIntExact(data.getValue().byteSize()),
        HarfBuzz.MEMORY_MODE_READONLY);
    hbFace = HarfBuzz.faceCreate(blob, index);
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

  /** A font that falls back from {@code base} to other fonts; see {@link #withFallback}. */
  private Font(Font base, List<Font> fallback, boolean system) {
    StringBuilder chain = new StringBuilder(base.name);
    for (Font font : fallback) {
      chain.append(", ").append(font.name);
    }
    name = chain + (system ? ", system fonts" : "");
    shared = true;
    this.base = base;
    this.fallback = fallback;
    this.system = system;
    styleFlags = base.styleFlags;
    icons = base.icons;
    arena = null;
    face = null;
    blob = null;
    hbFace = null;
    hbFont = null;
    unitsPerEm = base.unitsPerEm;
    ascent = base.ascent;
    descent = base.descent;
    lineGap = base.lineGap;
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

  /**
   * A font of a file, face {@code index} of a collection: mapped into memory, not read, and
   * unmapped when the font is closed. For the system's fonts, which stay open.
   */
  static Font file(Path path, int index) {
    Natives.lookup("freetype");
    Natives.lookup("harfbuzz");
    Arena arena = Arena.ofShared();
    MemorySegment data;
    try (FileChannel channel = FileChannel.open(path)) {
      data = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
    } catch (IOException | RuntimeException e) {
      arena.close();
      throw new IllegalArgumentException("Cannot map the font " + path + ": " + e, e);
    }
    return new Font(Map.entry(arena, data), index, true, false);
  }

  /**
   * This font, falling back to {@code fonts} in order for the characters it lacks: each grapheme
   * cluster of a text is drawn by the first of these fonts that has all its characters, at the
   * same size and on the same baseline; see {@link #shape}. Metrics, and so the height of a line,
   * stay this font's. A font in {@code fonts} that falls back itself brings its fallbacks along,
   * after it. The result is the same object for the same arguments, so that it keeps the shaped
   * runs of earlier calls; it owns nothing and closing it does nothing.
   *
   * @throws IllegalStateException if this font is closed
   */
  public Font withFallback(Font... fonts) {
    List<Font> chain = new ArrayList<>(fallback);
    boolean withSystem = system;
    for (Font font : fonts) {
      Objects.requireNonNull(font, "fonts");
      chain.add(font.base);
      chain.addAll(font.fallback);
      withSystem |= font.system;
    }
    chain = new ArrayList<>(new LinkedHashSet<>(chain));
    chain.remove(base);
    return base.derive(List.copyOf(chain), withSystem);
  }

  /**
   * This font and its fallbacks, and then the fonts installed on the system for the characters
   * that none of them has. The first character that needs them looks through the system's font
   * folders, once per process; see {@code SystemFonts}. Images drawn with it depend on the
   * machine, which is why {@code App} uses it only in a window unless told otherwise.
   */
  public Font withSystemFonts() {
    return base.derive(fallback, true);
  }

  private Font derive(List<Font> fallback, boolean system) {
    if (fallback.isEmpty() && !system) {
      return this;
    }
    synchronized (this) {
      checkOpen();
      return derived.computeIfAbsent(List.of(fallback, system),
          k -> new Font(this, fallback, system));
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
   * on.
   *
   * <p>The text is split into runs of one font and one script ({@code Runs}): each grapheme cluster
   * goes to the first font of this one and its {@link #withFallback fallbacks} that has it, and
   * neutral characters between two clusters of one fallback font stay with it. A cluster that no
   * font has gets this font's "not defined" glyph, 0. Each run is shaped with HarfBuzz in its
   * script and direction, with the text around it as context, and the runs follow each other from
   * left to right in the order of the text: a run of right-to-left text, such as Arabic or Hebrew,
   * has its letters joined and in their order, but a line is not reordered as the bidirectional
   * algorithm would, and such a run is one cluster, all its glyphs at the index of its first
   * character.
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

  /** For tests: whether the text of a cached run, at any size, contains {@code part}. */
  synchronized boolean shaped(String part) {
    return runs.keySet().stream().anyMatch(key -> key.text.contains(part));
  }

  private GlyphRun shape(Key key) {
    String text = key.text;
    List<Font> chain = chain(text);
    List<Runs.Run> runs = Runs.of(text, chain.size(), (f, c) -> chain.get(f).has(c));
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
      MemorySegment utf16 = call.allocateFrom(JAVA_CHAR, text.toCharArray());
      List<GlyphRun> parts = new ArrayList<>();
      for (Runs.Run run : runs) {
        parts.add(chain.get(run.font()).shapeRun(key, utf16, text.length(), run, features, count));
      }
      return GlyphRun.join(this, key.pixelSize, parts);
    }
  }

  /** This font and its fallbacks, then the system's fonts for clusters that none of them has. */
  private List<Font> chain(String text) {
    List<Font> chain = new ArrayList<>(1 + fallback.size());
    chain.add(base);
    chain.addAll(fallback);
    if (!system) {
      return chain;
    }
    Runs.Coverage coverage = (f, c) -> chain.get(f).has(c);
    List<Integer> bounds = Runs.clusters(text);
    for (int k = 0; k + 1 < bounds.size(); k++) {
      String cluster = text.substring(bounds.get(k), bounds.get(k + 1));
      boolean covered = false;
      for (int f = 0; f < chain.size() && !covered; f++) {
        covered = Runs.covers(cluster, f, coverage);
      }
      Font found = covered ? null : SystemFonts.find(cluster, styleFlags);
      if (found != null && !chain.contains(found)) {
        chain.add(found);
      }
    }
    return chain;
  }

  /** Whether the font has a glyph for the character, in its Unicode charmap. */
  boolean has(int codePoint) {
    synchronized (this) {
      checkOpen();
      return FreeType.getCharIndex(face, codePoint) != 0;
    }
  }

  /** Shapes one run of the text with this font, which has native objects. */
  private GlyphRun shapeRun(Key key, MemorySegment utf16, int length, Runs.Run run,
      MemorySegment features, int count) {
    synchronized (this) {
      checkOpen();
      try (Arena call = Arena.ofConfined()) {
        MemorySegment buffer = HarfBuzz.bufferCreate();
        try {
          // The whole text is context, for letters that join across the edges of the run.
          HarfBuzz.bufferAddUtf16(buffer, utf16, length, run.from(), run.to() - run.from());
          HarfBuzz.bufferSetDirection(buffer,
              run.rtl() ? HarfBuzz.DIRECTION_RTL : HarfBuzz.DIRECTION_LTR);
          HarfBuzz.bufferGuessSegmentProperties(buffer); // the script of the run's letters
          HarfBuzz.shape(hbFont, buffer, features, count);
          if (!HarfBuzz.bufferAllocationSuccessful(buffer)) {
            throw new OutOfMemoryError("HarfBuzz could not allocate memory to shape the text.");
          }
          MemorySegment out = call.allocate(JAVA_INT);
          MemorySegment infos = HarfBuzz.bufferGetGlyphInfos(buffer, out);
          int n = out.get(JAVA_INT, 0);
          infos = infos.reinterpret(n * HarfBuzz.GLYPH_INFO.byteSize());
          MemorySegment positions = HarfBuzz.bufferGetGlyphPositions(buffer, out)
              .reinterpret(n * HarfBuzz.GLYPH_POSITION.byteSize());
          return run(key, infos, positions, n, run.rtl() ? run.from() : -1);
        } finally {
          HarfBuzz.bufferDestroy(buffer);
        }
      }
    }
  }

  /**
   * Scales HarfBuzz's font units to physical pixels and rounds them to the grid. A right-to-left
   * run's glyphs come in visual order, their clusters falling; they all get {@code cluster}.
   */
  private GlyphRun run(Key key, MemorySegment infos, MemorySegment positions, int n,
      int cluster) {
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
      clusters[i] = cluster >= 0 ? cluster : infos.get(JAVA_INT, info + HarfBuzz.INFO_CLUSTER);
      pen[i + 1] = pen[i]
          + round(positions.get(JAVA_INT, position + HarfBuzz.POSITION_X_ADVANCE), perUnit, grid);
      dx[i] = round(positions.get(JAVA_INT, position + HarfBuzz.POSITION_X_OFFSET), perUnit, grid);
      // HarfBuzz's y grows upwards.
      dy[i] = round(-positions.get(JAVA_INT, position + HarfBuzz.POSITION_Y_OFFSET), perUnit,
          grid);
    }
    return new GlyphRun(this, key.pixelSize, glyphs, clusters, pen, dx, dy, null);
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
   * <p>A font made by {@link #withFallback} or {@link #withSystemFonts} owns nothing, and closing
   * it does nothing; close the fonts it was made of.
   *
   * @throws UnsupportedOperationException for a default font, which other code may be using, and
   *     a font of the system's
   */
  @Override
  public void close() {
    if (base != this) {
      return;
    }
    if (shared) {
      throw new UnsupportedOperationException(
          name + " is a default font or one of the system's, which stay open while the process"
              + " runs.");
    }
    synchronized (this) {
      if (!closed) {
        closed = true;
        runs.clear();
        derived.clear();
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
    if (base.closed) {
      throw new IllegalStateException(name + " is closed: this Font cannot be used any more.");
    }
  }

  /**
   * The family and style, such as {@code Inter Regular}; for a font that falls back, those of its
   * fonts in order, as {@code Inter Regular, JetBrains Mono Regular, system fonts}.
   */
  @Override
  public String toString() {
    return name;
  }

  /** Opens the FreeType face; FreeType wants its library used by one thread at a time. */
  private static synchronized MemorySegment openFace(MemorySegment data, int index) {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment out = call.allocate(ADDRESS);
      int error = FreeType.newMemoryFace(library(), data, data.byteSize(), index, out);
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

  private static MemorySegment library() {
    if (library == null) {
      try (Arena call = Arena.ofConfined()) {
        MemorySegment out = call.allocate(ADDRESS);
        int error = FreeType.initFreeType(out);
        if (error != 0) {
          throw new IllegalStateException("FreeType could not start: error " + error + ".");
        }
        library = out.get(ADDRESS, 0);
      }
    }
    return library;
  }

  /**
   * For {@link SystemFonts}: the faces of a font file that draw outlines, each with its family,
   * style and characters. A colour face counts only if its glyph for a sample character, an emoji
   * where it has one, renders as an outline.
   */
  static synchronized List<SystemFonts.Face> inspect(Path path, MemorySegment data) {
    Natives.lookup("freetype");
    List<SystemFonts.Face> faces = new ArrayList<>();
    try (Arena call = Arena.ofConfined()) {
      MemorySegment out = call.allocate(ADDRESS);
      MemorySegment glyph = call.allocate(JAVA_INT);
      int count = 1;
      for (int i = 0; i < count; i++) {
        if (FreeType.newMemoryFace(library(), data, data.byteSize(), i, out) != 0) {
          continue;
        }
        MemorySegment face = out.get(ADDRESS, 0).reinterpret(FreeType.FACE.byteSize());
        try {
          count = (int) Math.min(FreeType.getLong(face, FreeType.FACE_NUM_FACES), 256);
          long flags = FreeType.getLong(face, FreeType.FACE_FACE_FLAGS);
          if ((flags & FreeType.FACE_FLAG_SCALABLE) == 0
              || face.get(JAVA_SHORT, FreeType.FACE_UNITS_PER_EM) == 0) {
            continue;
          }
          int[] ranges = ranges(face, glyph);
          if (ranges.length == 0
              || (flags & FreeType.FACE_FLAG_COLOR) != 0 && !outlined(face, ranges)) {
            continue;
          }
          faces.add(new SystemFonts.Face(path, i, string(face.get(ADDRESS,
              FreeType.FACE_FAMILY_NAME)), string(face.get(ADDRESS, FreeType.FACE_STYLE_NAME)),
              (int) (FreeType.getLong(face, FreeType.FACE_STYLE_FLAGS)
                  & (FreeType.STYLE_FLAG_BOLD | FreeType.STYLE_FLAG_ITALIC)),
              ranges));
        } finally {
          FreeType.doneFace(face);
        }
      }
    }
    return faces;
  }

  /** The characters of a face's charmap, as first and last of each run of consecutive ones. */
  private static int[] ranges(MemorySegment face, MemorySegment glyph) {
    int[] ranges = new int[16];
    int n = 0;
    for (long c = FreeType.getFirstChar(face, glyph); glyph.get(JAVA_INT, 0) != 0;
        c = FreeType.getNextChar(face, c, glyph)) {
      int code = (int) c;
      if (n > 0 && ranges[n - 1] == code - 1) {
        ranges[n - 1] = code;
      } else {
        if (n == ranges.length) {
          ranges = Arrays.copyOf(ranges, n * 2);
        }
        ranges[n++] = code;
        ranges[n++] = code;
      }
    }
    return Arrays.copyOf(ranges, n);
  }

  /** Whether a colour face has an outline for U+1F600, or else its first character from U+2000. */
  private static boolean outlined(MemorySegment face, int[] ranges) {
    int sample = ranges[0];
    for (int r = 0; r < ranges.length; r += 2) {
      if (ranges[r] <= 0x1F600 && 0x1F600 <= ranges[r + 1]) {
        sample = 0x1F600;
        break;
      }
      if (sample < 0x2000 && ranges[r + 1] >= 0x2000) {
        sample = Math.max(ranges[r], 0x2000);
      }
    }
    int index = FreeType.getCharIndex(face, sample);
    if (index == 0 || FreeType.setCharSize(face, 0, 32 * 64, 72, 72) != 0
        || FreeType.loadGlyph(face, index, FreeType.LOAD_NO_BITMAP) != 0) {
      return false;
    }
    MemorySegment slot =
        face.get(ADDRESS, FreeType.FACE_GLYPH).reinterpret(FreeType.GLYPH_SLOT.byteSize());
    return FreeType.renderGlyph(slot, FreeType.RENDER_MODE_NORMAL) == 0
        && slot.get(JAVA_INT, FreeType.SLOT_BITMAP + FreeType.BITMAP_WIDTH) > 0
        && slot.get(JAVA_INT, FreeType.SLOT_BITMAP + FreeType.BITMAP_ROWS) > 0;
  }

  private static String string(MemorySegment pointer) {
    return pointer.equals(MemorySegment.NULL) ? "?"
        : pointer.reinterpret(Long.MAX_VALUE).getString(0);
  }
}
