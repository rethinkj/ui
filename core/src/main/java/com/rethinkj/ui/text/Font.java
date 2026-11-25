package com.rethinkj.ui.text;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import com.rethinkj.ui.Colors;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A TrueType or OpenType font, shaped with HarfBuzz and rasterized with FreeType. The
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
    "fonts/Inter-Regular.ttf",
    "fonts/Inter-Bold.ttf",
    "fonts/JetBrainsMono-Regular.ttf",
    "fonts/JetBrainsMono-Bold.ttf",
    ICONS + "FluentSystemIcons-Regular.ttf",
    "fonts/Inter-Medium.ttf",
    "fonts/Inter-SemiBold.ttf",
    "fonts/Inter-Italic.ttf",
    "fonts/Inter-BoldItalic.ttf"
  };
  private static final Font[] DEFAULT_FONTS = new Font[DEFAULT_FILES.length];

  /**
   * Shaped runs kept per font, the least recently used dropped first, but none that the last two
   * frames that shaped text used: a view that shapes more texts than this finds them again in its
   * next rebuild. Runs are dropped when a new one is added.
   */
  private static final int CACHED_RUNS = 1024;

  /** Shaped runs kept per font at most, even of the last two frames: a bound on the memory. */
  private static final int MAX_RUNS = 16_384;

  /** The frame of the caches of shaped runs, counted from the first shape after each start. */
  // shortcut: one count for every app, so another window that shapes text in two frames between
  // two rebuilds of this one lets this one's runs go; count per app when several windows shape
  // large views at once.
  // Twice the frame, plus 1 once a frame has started: one value, so that both change at once.
  private static final AtomicLong FRAME = new AtomicLong();

  /** Applied before the caller's features, so that {@code +calt} turns contextual forms on. */
  private static final String DEFAULT_FEATURES = "-calt";

  private static MemorySegment library;

  /**
   * The FreeType library of {@link #inspect}, and its lock: the scan of the system's fonts runs
   * while the first frame opens its fonts, and with the library and lock of those it held them up.
   */
  private static final Object SCAN = new Object();

  private static MemorySegment scanLibrary;

  private final String name;
  private final boolean shared;
  private final boolean icons;
  private final boolean colorGlyphs; // FT_FACE_FLAG_COLOR: rasterized with FT_LOAD_COLOR
  private final boolean bitmapColor; // color bitmaps without outlines (CBDT, sbix): FT_Select_Size
  private final float[] strikes; // bitmapColor strike sizes in ppem; empty for scalable faces
  private final boolean color; // whether its emoji draw in color, which fallback prefers for them
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
  private final Map<Key, Shaped> runs = new LinkedHashMap<>(16, 0.75f, true);
  private boolean closed;
  private long rasterSize; // the size last set on the FreeType face, in 1/64 pixels
  private int selectedStrike = -1; // the strike last selected on a bitmapColor face

  /**
   * A rasterized glyph, rows from the top, and where the bitmap lies relative to the glyph's
   * origin on the baseline: either 8-bit coverage (one byte per pixel from 0 to 255, {@code color}
   * false) or premultiplied RGBA (four bytes per pixel, red, green and blue premultiplied by alpha
   * in linear light and encoded to 8-bit sRGB, and linear alpha from 0 to 255, {@code color} true).
   *
   * @param left how far the left edge lies right of the origin, in pixels
   * @param top how far the top edge lies above the baseline
   * @param pixels {@code width * height} bytes of coverage, or {@code width * height * 4} bytes of
   *     premultiplied RGBA when {@code color} is true
   */
  record Bitmap(int left, int top, int width, int height, byte[] pixels, boolean color) {}

  private record Key(String text, float pixelSize, boolean quarter, List<String> features) {}

  /** A cached run and the frame it was last used in. */
  private static final class Shaped {
    final GlyphRun run;
    long used;

    Shaped(GlyphRun run, long used) {
      this.run = run;
      this.used = used;
    }
  }

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
  private Font(Map.Entry<Arena, MemorySegment> data, int index, boolean shared, boolean icons) {
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
    long flags = FreeType.getLong(face, FreeType.FACE_FACE_FLAGS);
    colorGlyphs = (flags & FreeType.FACE_FLAG_COLOR) != 0;
    bitmapColor = colorBitmaps(face, flags);
    strikes = bitmapColor ? strikeSizes(face) : new float[0];
    color = colorGlyphs && drawsColor(face, bitmapColor);
    styleFlags = (int) (FreeType.getLong(face, FreeType.FACE_STYLE_FLAGS)
        & (FreeType.STYLE_FLAG_BOLD | FreeType.STYLE_FLAG_ITALIC));
    blob = HarfBuzz.blobCreate(
        data.getValue(),
        Math.toIntExact(data.getValue().byteSize()),
        HarfBuzz.MEMORY_MODE_READONLY);
    hbFace = HarfBuzz.faceCreate(blob, index);
    hbFont = HarfBuzz.fontCreate(hbFace);
    if (HarfBuzz.faceGetGlyphCount(hbFace) == 0 || HarfBuzz.faceGetUpem(hbFace) == 0) {
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
    colorGlyphs = base.colorGlyphs;
    bitmapColor = base.bitmapColor;
    strikes = base.strikes;
    color = base.color;
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
   * Starts reading the system's fonts for {@link #withSystemFonts} on a virtual thread of its
   * own, unless that started already, and returns: the first character that needs one of them then
   * finds the index ready, or waits only for the rest of the scan. Calling it is never needed; an
   * {@code App} does when it is told to use the system's fonts, as a window's loop tells it.
   */
  public static void prepareSystemFonts() {
    SystemFonts.prepare();
  }

  /**
   * This font and its fallbacks, and then the fonts installed on the system for the characters
   * that none of them has. The first character that needs them looks through the system's font
   * folders, once per process, unless {@link #prepareSystemFonts} did that already; see {@code
   * SystemFonts}. Images drawn with it depend on the machine, which is why {@code App} uses it only
   * in a window unless told otherwise.
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
      return derived.computeIfAbsent(
          List.of(fallback, system), k -> new Font(this, fallback, system));
    }
  }

  /** Inter Regular, the default font for user interfaces. */
  public static Font sans() {
    return builtIn(0);
  }

  /** Inter Medium (weight 500), for column titles, buttons and strong text. */
  public static Font sansMedium() {
    return builtIn(5);
  }

  /** Inter SemiBold (weight 600), for headings and titles. */
  public static Font sansSemibold() {
    return builtIn(6);
  }

  /** Inter Bold. */
  public static Font sansBold() {
    return builtIn(1);
  }

  /** Inter Italic, for emphasis. */
  public static Font sansItalic() {
    return builtIn(7);
  }

  /** Inter Bold Italic, for strong emphasis inside emphasis. */
  public static Font sansBoldItalic() {
    return builtIn(8);
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
        throw new IllegalStateException(
            icons
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
      long now = frame(); // in the monitor, so that the runs' frames grow in their LRU order
      Shaped shaped = runs.get(key);
      if (shaped == null) {
        shaped = new Shaped(shape(key), now);
        runs.put(key, shaped);
        trim(now);
      }
      shaped.used = now;
      return shaped.run;
    }
  }

  /**
   * Starts a frame of the caches of shaped runs: runs not used since the frame before this one
   * may be dropped from now on. {@link Atlas#nextFrame} calls this.
   */
  static void nextFrame() {
    FRAME.getAndUpdate(frame -> frame | 1);
  }

  /** The current frame, moved on by the first shape after {@link #nextFrame}, not by idle frames. */
  private static long frame() {
    return FRAME.updateAndGet(frame -> frame + (frame & 1)) >> 1;
  }

  /**
   * Drops the least recently used runs beyond {@link #CACHED_RUNS}, but below {@link #MAX_RUNS}
   * none used in this frame or the one before.
   */
  private void trim(long now) {
    Iterator<Shaped> eldest = runs.values().iterator();
    while (runs.size() > CACHED_RUNS) {
      if (eldest.next().used >= now - 1 && runs.size() <= MAX_RUNS) {
        return; // so is every run after it
      }
      eldest.remove();
    }
  }

  /** For tests: whether the text of a cached run, at any size, contains {@code part}. */
  synchronized boolean shaped(String part) {
    return runs.keySet().stream().anyMatch(key -> key.text.contains(part));
  }

  private GlyphRun shape(Key key) {
    String text = key.text;
    int[] bounds = Runs.clusters(text);
    List<Font> chain = chain(text, bounds);
    List<Runs.Run> runs = Runs.of(text, bounds, chain.size(), coverage(chain));
    try (Arena call = Arena.ofConfined()) {
      int count = 1 + key.features.size();
      MemorySegment features = call.allocate(HarfBuzz.FEATURE, count);
      for (int i = 0; i < count; i++) {
        String feature = i == 0 ? DEFAULT_FEATURES : key.features.get(i - 1);
        if (!HarfBuzz.featureFromString(
            call.allocateFrom(feature),
            -1,
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
  private List<Font> chain(String text, int[] bounds) {
    List<Font> chain = new ArrayList<>(1 + fallback.size());
    chain.add(base);
    chain.addAll(fallback);
    if (!system) {
      return chain;
    }
    Runs.Coverage coverage = coverage(chain);
    for (int k = 0; k + 1 < bounds.length; k++) {
      int from = bounds[k];
      int to = bounds[k + 1];
      // An emoji looks further for a font with color glyphs than the ones that only have its
      // outline.
      boolean emoji = Runs.emoji(text, from, to);
      boolean covered = false;
      for (int f = 0; f < chain.size() && !covered; f++) {
        covered = (!emoji || chain.get(f).color) && Runs.covers(text, from, to, f, coverage);
      }
      Font found = covered ? null : SystemFonts.find(text.substring(from, to), styleFlags);
      if (found != null && !chain.contains(found)) {
        chain.add(found);
      }
    }
    return chain;
  }

  /**
   * Whether a face with color glyphs draws its emoji in color: a sample renders as a BGRA color
   * bitmap (COLR version 0 layers or CBDT/sbix bitmaps), not as an outline or nothing (COLR
   * version 1).
   */
  private static boolean drawsColor(MemorySegment face, boolean bitmap) {
    try (Arena call = Arena.ofConfined()) {
      int[] ranges = ranges(face, call.allocate(JAVA_INT));
      return ranges.length > 0 && sampleMode(face, ranges, bitmap) == FreeType.PIXEL_MODE_BGRA;
    }
  }

  private static Runs.Coverage coverage(List<Font> chain) {
    return new Runs.Coverage() {
      @Override
      public boolean has(int font, int codePoint) {
        return chain.get(font).has(codePoint);
      }

      @Override
      public boolean color(int font) {
        return chain.get(font).color;
      }
    };
  }

  /** Whether the font has a glyph for the character, in its Unicode charmap. */
  boolean has(int codePoint) {
    synchronized (this) {
      checkOpen();
      return FreeType.getCharIndex(face, codePoint) != 0;
    }
  }

  /** Shapes one run of the text with this font, which has native objects. */
  private GlyphRun shapeRun(
      Key key, MemorySegment utf16, int length, Runs.Run run, MemorySegment features, int count) {
    synchronized (this) {
      checkOpen();
      try (Arena call = Arena.ofConfined()) {
        MemorySegment buffer = HarfBuzz.bufferCreate();
        try {
          // The whole text is context, for letters that join across the edges of the run.
          HarfBuzz.bufferAddUtf16(buffer, utf16, length, run.from(), run.to() - run.from());
          HarfBuzz.bufferSetDirection(
              buffer, run.rtl() ? HarfBuzz.DIRECTION_RTL : HarfBuzz.DIRECTION_LTR);
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
  private GlyphRun run(Key key, MemorySegment infos, MemorySegment positions, int n, int cluster) {
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
      dy[i] = round(-positions.get(JAVA_INT, position + HarfBuzz.POSITION_Y_OFFSET), perUnit, grid);
    }
    return new GlyphRun(this, key.pixelSize, glyphs, clusters, pen, dx, dy, null);
  }

  /**
   * How {@link #icons()} are hinted, whatever the text's {@link TextRendering}: FreeType's
   * auto-hinter in both directions, which puts the straight strokes it finds on whole pixels, as
   * the drawings are made for the pixel grid. The light auto-hinter moves outlines vertically only
   * and left an arrow's shaft half covered on two rows of pixels at scale 1; the font has no
   * instructions, so its own hints would mean the same auto-hinter as here.
   */
  private static final int ICON_HINTING = FreeType.LOAD_FORCE_AUTOHINT;

  /**
   * Rasterizes a glyph with FreeType into grayscale coverage, or premultiplied RGBA for a color
   * glyph of a color face, moved right by {@code quarter} quarter pixels after hinting, so that a
   * glyph on a quarter-pixel position is drawn from a bitmap at a whole one. The glyphs of {@link
   * #icons()} are hinted alike for every {@code hinting} ({@link #ICON_HINTING}).
   *
   * @param pixelSize the size in physical pixels, as {@link GlyphRun#pixelSize}
   * @param quarter 0 to 3
   * @throws IllegalArgumentException if FreeType cannot load or render the glyph
   */
  Bitmap rasterize(int glyph, float pixelSize, TextRendering.Hinting hinting, int quarter) {
    synchronized (this) {
      checkOpen();
      if (bitmapColor) {
        return rasterizeBitmap(glyph, pixelSize, quarter);
      }
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
      int flags = FreeType.LOAD_NO_BITMAP
          | (colorGlyphs ? FreeType.LOAD_COLOR | FreeType.LOAD_NO_SVG : 0)
          | (icons
              ? ICON_HINTING
              : switch (hinting) {
                case LIGHT -> FreeType.LOAD_FORCE_AUTOHINT | FreeType.LOAD_TARGET_LIGHT;
                case NATIVE -> FreeType.LOAD_DEFAULT;
              });
      check(FreeType.loadGlyph(face, glyph, flags), "load glyph " + glyph + " of");
      MemorySegment slot =
          face.get(ADDRESS, FreeType.FACE_GLYPH).reinterpret(FreeType.GLYPH_SLOT.byteSize());
      check(
          FreeType.renderGlyph(slot, FreeType.RENDER_MODE_NORMAL), "render glyph " + glyph + " of");
      long bitmap = FreeType.SLOT_BITMAP;
      int rows = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_ROWS);
      int width = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_WIDTH);
      int pitch = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_PITCH);
      int left = slot.get(JAVA_INT, FreeType.SLOT_BITMAP_LEFT);
      int top = slot.get(JAVA_INT, FreeType.SLOT_BITMAP_TOP);
      if (width == 0 || rows == 0) {
        return new Bitmap(left, top, width, rows, new byte[0], false);
      }
      int mode = slot.get(JAVA_BYTE, bitmap + FreeType.BITMAP_PIXEL_MODE) & 0xFF;
      if (colorGlyphs && mode == FreeType.PIXEL_MODE_BGRA) {
        if (pitch < width * 4) {
          throw new IllegalStateException("FreeType rendered color glyph " + glyph + " of " + name
              + " in an unexpected format: pitch " + pitch + " for width " + width);
        }
        MemorySegment pixels =
            slot.get(ADDRESS, bitmap + FreeType.BITMAP_BUFFER).reinterpret((long) pitch * rows);
        return new Bitmap(left, top, width, rows, bgraToRgba(pixels, width, rows, pitch), true);
      }
      if (mode != FreeType.PIXEL_MODE_GRAY || pitch < width) {
        throw new IllegalStateException("FreeType rendered glyph " + glyph + " of " + name
            + " in an unexpected format: mode " + mode + ", pitch " + pitch + " for width "
            + width);
      }
      byte[] coverage = new byte[width * rows];
      MemorySegment pixels =
          slot.get(ADDRESS, bitmap + FreeType.BITMAP_BUFFER).reinterpret((long) pitch * rows);
      for (int y = 0; y < rows; y++) {
        MemorySegment.copy(pixels, JAVA_BYTE, (long) y * pitch, coverage, y * width, width);
      }
      return new Bitmap(left, top, width, rows, coverage, false);
    }
  }

  /**
   * Loads one glyph from a fixed-size color bitmap strike (such as CBDT) and scales it to {@code
   * pixelSize} in premultiplied linear light, with the quarter-pixel horizontal shift folded into
   * the resampling so one atlas texel covers one physical pixel.
   */
  private Bitmap rasterizeBitmap(int glyph, float pixelSize, int quarter) {
    int strike = bestStrike(strikes, pixelSize);
    if (strike < 0) {
      throw new IllegalArgumentException(name + " has no usable bitmap strike.");
    }
    if (strike != selectedStrike) {
      check(FreeType.selectSize(face, strike), "select strike " + strike + " on");
      selectedStrike = strike;
    }
    check(
        FreeType.loadGlyph(face, glyph, FreeType.LOAD_COLOR | FreeType.LOAD_NO_SVG),
        "load glyph " + glyph + " of");
    MemorySegment slot =
        face.get(ADDRESS, FreeType.FACE_GLYPH).reinterpret(FreeType.GLYPH_SLOT.byteSize());
    check(FreeType.renderGlyph(slot, FreeType.RENDER_MODE_NORMAL), "render glyph " + glyph + " of");
    long bitmap = FreeType.SLOT_BITMAP;
    int rows = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_ROWS);
    int width = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_WIDTH);
    int pitch = slot.get(JAVA_INT, bitmap + FreeType.BITMAP_PITCH);
    int left = slot.get(JAVA_INT, FreeType.SLOT_BITMAP_LEFT);
    int top = slot.get(JAVA_INT, FreeType.SLOT_BITMAP_TOP);
    if (width == 0 || rows == 0) {
      return new Bitmap(0, 0, 0, 0, new byte[0], false);
    }
    int mode = slot.get(JAVA_BYTE, bitmap + FreeType.BITMAP_PIXEL_MODE) & 0xFF;
    if (mode != FreeType.PIXEL_MODE_BGRA || pitch < width * 4) {
      throw new IllegalStateException("FreeType rendered bitmap glyph " + glyph + " of " + name
          + " in an unexpected format: mode " + mode + ", pitch " + pitch + " for width " + width);
    }
    MemorySegment pixels =
        slot.get(ADDRESS, bitmap + FreeType.BITMAP_BUFFER).reinterpret((long) pitch * rows);
    double scale = pixelSize / (double) strikes[strike];
    double shiftX = quarter * 0.25;
    if (Math.abs(scale - 1.0) < 1e-4 && shiftX == 0.0) {
      return new Bitmap(left, top, width, rows, bgraToRgba(pixels, width, rows, pitch), true);
    }
    return scaleBgra(pixels, width, rows, pitch, left, top, scale, shiftX);
  }

  private static byte[] bgraToRgba(MemorySegment pixels, int width, int rows, int pitch) {
    byte[] rgba = new byte[width * rows * 4];
    for (int y = 0; y < rows; y++) {
      long row = (long) y * pitch;
      int to = y * width * 4;
      for (int x = 0; x < width; x++) {
        long from = row + (long) x * 4;
        int a = pixels.get(JAVA_BYTE, from + 3) & 0xFF;
        if (a == 0) {
          continue;
        }
        int b = pixels.get(JAVA_BYTE, from) & 0xFF;
        int g = pixels.get(JAVA_BYTE, from + 1) & 0xFF;
        int r = pixels.get(JAVA_BYTE, from + 2) & 0xFF;
        int at = to + x * 4;
        if (a == 255) {
          rgba[at] = (byte) r;
          rgba[at + 1] = (byte) g;
          rgba[at + 2] = (byte) b;
          rgba[at + 3] = (byte) 255;
        } else {
          rgba[at] = premulSrgb(r, a);
          rgba[at + 1] = premulSrgb(g, a);
          rgba[at + 2] = premulSrgb(b, a);
          rgba[at + 3] = (byte) a;
        }
      }
    }
    return rgba;
  }

  /**
   * Scales a FreeType premultiplied-sRGB BGRA bitmap in premultiplied linear light (exact box
   * average when downscaling, bilinear when upscaling) and encodes it back to 8-bit premultiplied
   * sRGB RGBA matching {@link Bitmap}.
   */
  private static Bitmap scaleBgra(
      MemorySegment pixels,
      int srcW,
      int srcH,
      int pitch,
      int srcLeft,
      int srcTop,
      double scale,
      double shiftX) {
    double[] linear = new double[srcW * srcH * 4];
    for (int y = 0; y < srcH; y++) {
      long row = (long) y * pitch;
      int to = y * srcW * 4;
      for (int x = 0; x < srcW; x++) {
        long from = row + (long) x * 4;
        int a = pixels.get(JAVA_BYTE, from + 3) & 0xFF;
        if (a == 0) {
          continue;
        }
        int b = pixels.get(JAVA_BYTE, from) & 0xFF;
        int g = pixels.get(JAVA_BYTE, from + 1) & 0xFF;
        int r = pixels.get(JAVA_BYTE, from + 2) & 0xFF;
        int at = to + x * 4;
        if (a == 255) {
          linear[at] = Colors.linear(r / 255.0);
          linear[at + 1] = Colors.linear(g / 255.0);
          linear[at + 2] = Colors.linear(b / 255.0);
          linear[at + 3] = 1.0;
        } else {
          double alpha = a / 255.0;
          linear[at] = Colors.linear(Math.min(r / (double) a, 1.0)) * alpha;
          linear[at + 1] = Colors.linear(Math.min(g / (double) a, 1.0)) * alpha;
          linear[at + 2] = Colors.linear(Math.min(b / (double) a, 1.0)) * alpha;
          linear[at + 3] = alpha;
        }
      }
    }
    int minX = (int) Math.floor(srcLeft * scale + shiftX + 1e-5);
    int maxX = Math.max(minX + 1, (int) Math.ceil((srcLeft + srcW) * scale + shiftX - 1e-5));
    int minY = (int) Math.floor(-srcTop * scale + 1e-5);
    int maxY = Math.max(minY + 1, (int) Math.ceil((-srcTop + srcH) * scale - 1e-5));
    int dstW = maxX - minX;
    int dstH = maxY - minY;
    byte[] rgba = new byte[dstW * dstH * 4];
    if (scale <= 1.0) {
      double[] horiz = new double[srcH * dstW * 4];
      for (int dx = 0; dx < dstW; dx++) {
        double x0 = (minX + dx - shiftX) / scale - srcLeft;
        double x1 = (minX + dx + 1 - shiftX) / scale - srcLeft;
        double invSpan = 1.0 / (x1 - x0);
        int ix0 = Math.max(0, (int) Math.floor(x0));
        int ix1 = Math.min(srcW - 1, (int) Math.ceil(x1) - 1);
        for (int sy = 0; sy < srcH; sy++) {
          double r = 0;
          double g = 0;
          double b = 0;
          double a = 0;
          int rowBase = sy * srcW * 4;
          for (int ix = ix0; ix <= ix1; ix++) {
            double w = Math.min(x1, ix + 1.0) - Math.max(x0, ix);
            if (w <= 0) {
              continue;
            }
            int at = rowBase + ix * 4;
            r += linear[at] * w;
            g += linear[at + 1] * w;
            b += linear[at + 2] * w;
            a += linear[at + 3] * w;
          }
          int out = (sy * dstW + dx) * 4;
          horiz[out] = r * invSpan;
          horiz[out + 1] = g * invSpan;
          horiz[out + 2] = b * invSpan;
          horiz[out + 3] = a * invSpan;
        }
      }
      for (int dy = 0; dy < dstH; dy++) {
        double y0 = (minY + dy) / scale + srcTop;
        double y1 = (minY + dy + 1) / scale + srcTop;
        double invSpan = 1.0 / (y1 - y0);
        int iy0 = Math.max(0, (int) Math.floor(y0));
        int iy1 = Math.min(srcH - 1, (int) Math.ceil(y1) - 1);
        for (int dx = 0; dx < dstW; dx++) {
          double r = 0;
          double g = 0;
          double b = 0;
          double a = 0;
          for (int iy = iy0; iy <= iy1; iy++) {
            double w = Math.min(y1, iy + 1.0) - Math.max(y0, iy);
            if (w <= 0) {
              continue;
            }
            int at = (iy * dstW + dx) * 4;
            r += horiz[at] * w;
            g += horiz[at + 1] * w;
            b += horiz[at + 2] * w;
            a += horiz[at + 3] * w;
          }
          encodePremulSrgb(
              rgba, (dy * dstW + dx) * 4, r * invSpan, g * invSpan, b * invSpan, a * invSpan);
        }
      }
    } else {
      for (int dy = 0; dy < dstH; dy++) {
        double sy = (minY + dy + 0.5) / scale + srcTop - 0.5;
        int iy0 = (int) Math.floor(sy);
        double fy = sy - iy0;
        for (int dx = 0; dx < dstW; dx++) {
          double sx = (minX + dx + 0.5 - shiftX) / scale - srcLeft - 0.5;
          int ix0 = (int) Math.floor(sx);
          double fx = sx - ix0;
          double r = 0;
          double g = 0;
          double b = 0;
          double a = 0;
          for (int ky = 0; ky <= 1; ky++) {
            int iy = iy0 + ky;
            if (iy < 0 || iy >= srcH) {
              continue;
            }
            double wy = ky == 0 ? 1.0 - fy : fy;
            for (int kx = 0; kx <= 1; kx++) {
              int ix = ix0 + kx;
              if (ix < 0 || ix >= srcW) {
                continue;
              }
              double w = (kx == 0 ? 1.0 - fx : fx) * wy;
              int at = (iy * srcW + ix) * 4;
              r += linear[at] * w;
              g += linear[at + 1] * w;
              b += linear[at + 2] * w;
              a += linear[at + 3] * w;
            }
          }
          encodePremulSrgb(rgba, (dy * dstW + dx) * 4, r, g, b, a);
        }
      }
    }
    return new Bitmap(minX, -minY, dstW, dstH, rgba, true);
  }

  private static void encodePremulSrgb(
      byte[] rgba, int at, double r, double g, double b, double a) {
    int alpha = (int) Math.round(Math.clamp(a, 0.0, 1.0) * 255.0);
    if (alpha == 0) {
      return;
    }
    rgba[at] = (byte) Math.round(Colors.srgb(Math.clamp(r, 0.0, 1.0)) * 255.0);
    rgba[at + 1] = (byte) Math.round(Colors.srgb(Math.clamp(g, 0.0, 1.0)) * 255.0);
    rgba[at + 2] = (byte) Math.round(Colors.srgb(Math.clamp(b, 0.0, 1.0)) * 255.0);
    rgba[at + 3] = (byte) alpha;
  }

  /**
   * One channel of a premultiplied sRGB pixel from FreeType, converted to linear light,
   * premultiplied by {@code alpha}, and encoded back to 8-bit sRGB (matching {@code Image}).
   */
  private static byte premulSrgb(int channel, int alpha) {
    if (channel == 0) {
      return 0;
    }
    double straight = Math.min(channel / (double) alpha, 1.0);
    double linear = Colors.linear(straight) * (alpha / 255.0);
    return (byte) Math.round(Colors.srgb(linear) * 255.0);
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
      long flags = FreeType.getLong(face, FreeType.FACE_FACE_FLAGS);
      boolean scalable = (flags & FreeType.FACE_FLAG_SCALABLE) != 0
          && face.get(JAVA_SHORT, FreeType.FACE_UNITS_PER_EM) != 0;
      if (!scalable && !colorBitmaps(face, flags)) {
        FreeType.doneFace(face);
        throw new IllegalArgumentException(
            "The font has no outlines or color bitmaps: it cannot be drawn at any size.");
      }
      return face;
    }
  }

  /** Whether {@code face} has color bitmap strikes (such as CBDT or sbix) and no outlines. */
  private static boolean colorBitmaps(MemorySegment face, long flags) {
    return (flags & FreeType.FACE_FLAG_SCALABLE) == 0
        && (flags & FreeType.FACE_FLAG_COLOR) != 0
        && (flags & FreeType.FACE_FLAG_FIXED_SIZES) != 0
        && face.get(JAVA_INT, FreeType.FACE_NUM_FIXED_SIZES) > 0;
  }

  /** The fixed-size bitmap strikes of {@code face}, each in pixels per em. */
  private static float[] strikeSizes(MemorySegment face) {
    int count = face.get(JAVA_INT, FreeType.FACE_NUM_FIXED_SIZES);
    if (count <= 0) {
      return new float[0];
    }
    long stride = FreeType.BITMAP_SIZE.byteSize();
    MemorySegment sizes =
        face.get(ADDRESS, FreeType.FACE_AVAILABLE_SIZES).reinterpret(count * stride);
    float[] ppem = new float[count];
    for (int i = 0; i < count; i++) {
      long base = i * stride;
      float y = FreeType.getLong(sizes, base + FreeType.BITMAP_SIZE_Y_PPEM) / 64f;
      ppem[i] = y > 0 ? y : sizes.get(JAVA_SHORT, base + FreeType.BITMAP_SIZE_HEIGHT);
    }
    return ppem;
  }

  /** Smallest strike {@code >= pixelSize}, else the largest strike; -1 if none is positive. */
  private static int bestStrike(float[] strikes, float pixelSize) {
    int best = -1;
    for (int i = 0; i < strikes.length; i++) {
      float s = strikes[i];
      if (s <= 0) {
        continue;
      }
      if (best < 0) {
        best = i;
      } else {
        float cur = strikes[best];
        boolean sFits = s >= pixelSize;
        boolean curFits = cur >= pixelSize;
        if (sFits != curFits ? sFits : (sFits ? s < cur : s > cur)) {
          best = i;
        }
      }
    }
    return best;
  }

  private static MemorySegment library() {
    if (library == null) {
      library = newLibrary();
    }
    return library;
  }

  private static MemorySegment newLibrary() {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment out = call.allocate(ADDRESS);
      int error = FreeType.initFreeType(out);
      if (error != 0) {
        throw new IllegalStateException("FreeType could not start: error " + error + ".");
      }
      return out.get(ADDRESS, 0);
    }
  }

  /**
   * For {@link SystemFonts}: the faces of a font file that draw outlines or color glyphs (COLR
   * version 0 or CBDT/sbix bitmaps), each with its family, style and characters. A color face
   * counts only if its glyph for a sample character, an emoji where it has one, renders as a BGRA
   * color bitmap or (for a scalable face) as an outline.
   */
  static List<SystemFonts.Face> inspect(Path path, MemorySegment data) {
    Natives.lookup("freetype");
    List<SystemFonts.Face> faces = new ArrayList<>();
    synchronized (SCAN) {
      if (scanLibrary == null) {
        scanLibrary = newLibrary();
      }
      inspect(path, data, faces);
    }
    return faces;
  }

  private static void inspect(Path path, MemorySegment data, List<SystemFonts.Face> faces) {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment out = call.allocate(ADDRESS);
      MemorySegment glyph = call.allocate(JAVA_INT);
      int count = 1;
      for (int i = 0; i < count; i++) {
        if (FreeType.newMemoryFace(scanLibrary, data, data.byteSize(), i, out) != 0) {
          continue;
        }
        MemorySegment face = out.get(ADDRESS, 0).reinterpret(FreeType.FACE.byteSize());
        try {
          count = (int) Math.min(FreeType.getLong(face, FreeType.FACE_NUM_FACES), 256);
          long flags = FreeType.getLong(face, FreeType.FACE_FACE_FLAGS);
          boolean scalable = (flags & FreeType.FACE_FLAG_SCALABLE) != 0
              && face.get(JAVA_SHORT, FreeType.FACE_UNITS_PER_EM) != 0;
          boolean bitmap = colorBitmaps(face, flags);
          if (!scalable && !bitmap) {
            continue;
          }
          int[] ranges = ranges(face, glyph);
          int mode = ranges.length == 0
              ? -1
              : (flags & FreeType.FACE_FLAG_COLOR) != 0
                  ? sampleMode(face, ranges, bitmap)
                  : FreeType.PIXEL_MODE_GRAY;
          if (mode < 0) {
            continue;
          }
          faces.add(new SystemFonts.Face(
              path,
              i,
              string(face.get(ADDRESS, FreeType.FACE_FAMILY_NAME)),
              string(face.get(ADDRESS, FreeType.FACE_STYLE_NAME)),
              (int) (FreeType.getLong(face, FreeType.FACE_STYLE_FLAGS)
                  & (FreeType.STYLE_FLAG_BOLD | FreeType.STYLE_FLAG_ITALIC)),
              ranges,
              mode == FreeType.PIXEL_MODE_BGRA));
        } finally {
          FreeType.doneFace(face);
        }
      }
    }
  }

  /** The characters of a face's charmap, as first and last of each run of consecutive ones. */
  private static int[] ranges(MemorySegment face, MemorySegment glyph) {
    int[] ranges = new int[16];
    int n = 0;
    for (long c = FreeType.getFirstChar(face, glyph);
        glyph.get(JAVA_INT, 0) != 0;
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

  /**
   * The pixel mode a color face renders U+1F600 in, or else its first character from U+2000 that
   * has ink (not a space or a variation selector): BGRA for COLR version 0 layers or CBDT/sbix
   * color bitmaps, gray for an outline on a scalable face; -1 where it renders neither.
   */
  private static int sampleMode(MemorySegment face, int[] ranges, boolean bitmap) {
    int sample = ranges[0];
    boolean inked = false;
    for (int r = 0; r < ranges.length; r += 2) {
      if (ranges[r] <= 0x1F600 && 0x1F600 <= ranges[r + 1]) {
        sample = 0x1F600;
        break;
      }
      for (int c = Math.max(ranges[r], 0x2000); !inked && c <= ranges[r + 1]; c++) {
        inked = !Runs.hidden(c) && !Character.isSpaceChar(c);
        sample = inked ? c : sample;
      }
    }
    int index = FreeType.getCharIndex(face, sample);
    if (index == 0) {
      return -1;
    }
    if (bitmap) {
      int strike = bestStrike(strikeSizes(face), 32f);
      if (strike < 0
          || FreeType.selectSize(face, strike) != 0
          || FreeType.loadGlyph(face, index, FreeType.LOAD_COLOR | FreeType.LOAD_NO_SVG) != 0) {
        return -1;
      }
    } else if (FreeType.setCharSize(face, 0, 32L * 64, 72, 72) != 0
        || FreeType.loadGlyph(
                face, index, FreeType.LOAD_NO_BITMAP | FreeType.LOAD_COLOR | FreeType.LOAD_NO_SVG)
            != 0) {
      return -1;
    }
    MemorySegment slot =
        face.get(ADDRESS, FreeType.FACE_GLYPH).reinterpret(FreeType.GLYPH_SLOT.byteSize());
    if (FreeType.renderGlyph(slot, FreeType.RENDER_MODE_NORMAL) != 0
        || slot.get(JAVA_INT, FreeType.SLOT_BITMAP + FreeType.BITMAP_WIDTH) <= 0
        || slot.get(JAVA_INT, FreeType.SLOT_BITMAP + FreeType.BITMAP_ROWS) <= 0) {
      return -1;
    }
    int mode = slot.get(JAVA_BYTE, FreeType.SLOT_BITMAP + FreeType.BITMAP_PIXEL_MODE) & 0xFF;
    if (bitmap) {
      return mode == FreeType.PIXEL_MODE_BGRA ? mode : -1;
    }
    return mode == FreeType.PIXEL_MODE_GRAY || mode == FreeType.PIXEL_MODE_BGRA ? mode : -1;
  }

  private static String string(MemorySegment pointer) {
    return pointer.equals(MemorySegment.NULL)
        ? "?"
        : pointer.reinterpret(Long.MAX_VALUE).getString(0);
  }
}
