import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Spike: is text shaped by HarfBuzz and rasterised by FreeType sharp enough at small sizes?
 * Renders labelled contact sheets of the variants that decide sharpness.
 *
 * <p>From the repository root, after {@code mvn -q -pl core package -DskipTests}:
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp core/target/classes spikes/text/TextSpike.java OUT
 * </pre>
 *
 * <p>{@code -Dfreetype=...} and {@code -Dharfbuzz=...} name the libraries if they are not found by
 * their usual names. Missing fonts are downloaded into {@code spikes/text/fonts}.
 *
 * <p>On Windows, with prebuilt DLLs in the ignored {@code spikes/text/lib/win64}, the official
 * HarfBuzz build needs its GLib and libintl loaded first:
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -Dfreetype=spikes/text/lib/win64/freetype.dll
 *   -Dharfbuzz.deps=spikes/text/lib/win64/libintl-8.dll;spikes/text/lib/win64/libglib-2.0-0.dll
 *   -Dharfbuzz=spikes/text/lib/win64/libharfbuzz-0.dll
 *   -cp core/target/classes spikes/text/TextSpike.java OUT
 * </pre>
 *
 * <p>The native reference is pango-view on Linux and DirectWrite with Direct2D, and GDI, on
 * Windows; on Windows the spike also prints measurements against it. The shader-rule sheets show
 * the coverage correction decided from the text colour alone.
 */
public class TextSpike {

  // ---------------------------------------------------------------- what is drawn

  record Theme(String name, int fg, int bg, int rule) {}

  static final Theme LIGHT = new Theme("dark on light", 0x1A1A1A, 0xFFFFFF, 0xD8D8D8);
  static final Theme DARK = new Theme("light on dark", 0xDCDCDC, 0x1E1F22, 0x3A3B3E);
  static final List<Theme> THEMES = List.of(LIGHT, DARK);
  static final double[] SIZES = {11, 12, 13, 14};

  static final String[][] TABLE = {
    {"id", "kunde", "ort", "saldo €"},
    {"1042", "Müller, Jürgen", "Köln", "1.234,56"},
    {"1043", "Größe & Söhne GmbH", "Düsseldorf", "-98.765,43"},
    {"1044", "Ölmühle Bärental", "Zürich", "0,00"},
  };
  /** Left edges of the first three columns in em; the last column is right-aligned at WIDTH. */
  static final double[] COLUMNS = {0, 3.2, 15.4};
  static final double WIDTH = 30;
  static final String[] SQL = {
    "SELECT k.id, k.name, SUM(b.betrag) AS umsatz",
    "  FROM kunden k JOIN buchung b ON b.kd = k.id",
    " WHERE b.tag >= '2024-01-01' AND ort <> 'Köln'",
    "   AND b.status != 'storno' -- Ä Ö Ü ä ö ü ß",
  };
  static final String[] MENU_BAR = {"Datei", "Bearbeiten", "Ansicht", "Abfrage", "Hilfe"};
  static final String[][] MENU_ITEMS = {{"Neue Verbindung…", "Strg+N"}, {"Tabelle öffnen…", "F5"}};

  // ---------------------------------------------------------------- fonts

  record Font(String name, String family, Path file, MemorySegment data, MemorySegment hbFace,
      double ascender, double descender) {}

  static final Path FONTS = Path.of(System.getProperty("fonts", "spikes/text/fonts"));
  static Font inter;
  static Font mono;

  static Font load(String name, String family, Path file) throws IOException {
    MemorySegment data = Arena.global().allocateFrom(JAVA_BYTE, Files.readAllBytes(file));
    MemorySegment face = FreeType.newFace(library(Engine.DEFAULT), data);
    double unitsPerEm = face.get(JAVA_SHORT, FreeType.FACE_UNITS_PER_EM) & 0xFFFF;
    return new Font(name, family, file, data, HarfBuzz.face(data),
        face.get(JAVA_SHORT, FreeType.FACE_ASCENDER) / unitsPerEm,
        -face.get(JAVA_SHORT, FreeType.FACE_DESCENDER) / unitsPerEm);
  }

  /** Downloads the release archive and extracts the named entries if the first one is missing. */
  static void fetch(String url, Path dir, String... entries) throws Exception {
    Path first = dir.resolve(Path.of(entries[0]).getFileName());
    if (Files.exists(first)) {
      return;
    }
    Files.createDirectories(dir);
    System.out.println("downloading " + url);
    Path zip = Files.createTempFile(dir, "download", ".zip");
    HttpResponse<Path> response =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            .send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofFile(zip));
    if (response.statusCode() != 200) {
      throw new IOException(url + ": HTTP " + response.statusCode());
    }
    try (ZipFile archive = new ZipFile(zip.toFile())) {
      for (String entry : entries) {
        Files.copy(archive.getInputStream(archive.getEntry(entry)),
            dir.resolve(Path.of(entry).getFileName()));
      }
    } finally {
      Files.delete(zip);
    }
  }

  // ---------------------------------------------------------------- rasterising

  /** FreeType libraries configured differently: properties are per library. */
  enum Engine { DEFAULT, V35, DARKEN }

  enum Hint {
    NONE("unhinted", FreeType.LOAD_NO_HINTING),
    AUTO_LIGHT("light: auto-hinter, vertical only (= hintslight)",
        FreeType.LOAD_FORCE_AUTOHINT | FreeType.LOAD_TARGET_LIGHT),
    AUTO_NORMAL("auto-hinter, normal (snaps both axes)", FreeType.LOAD_FORCE_AUTOHINT),
    NATIVE("full: the font's TrueType hints, interpreter v40", 0),
    NATIVE_V35("full: the font's TrueType hints, interpreter v35", 0);

    final String label;
    final int flags;

    Hint(String label, int flags) {
      this.label = label;
      this.flags = flags;
    }
  }

  /**
   * Whether the coverage correction thickens (text darker than its background) or thins (text
   * lighter). Only the CPU knows the background; a GPU shader blending with fixed-function
   * hardware knows only the text colour. The text-only rules thicken below lightness {@code from}
   * (CIE L*, 0 to 100), thin above {@code to} and mix the two between.
   */
  enum Correction {
    /** Per channel, from the text and the background value. */
    BACKGROUND(0, 0, ""),
    TEXT(50, 50, "direction from text colour only (L* > 50: thin)"),
    TEXT_GRADED(25, 75, "graded by text colour only (L* 25…75)"),
    TEXT_BIASED(60, 80, "graded by text colour only (L* 60…80)");

    final double from;
    final double to;
    final String label;

    Correction(double from, double to, String label) {
      this.from = from;
      this.to = to;
      this.label = label;
    }

    /** 0 thickens, 1 thins. */
    double thinning(double lightness) {
      return lightness <= from ? 0 : lightness > to ? 1 : (lightness - from) / (to - from);
    }
  }

  /**
   * One combination of the settings that decide sharpness.
   *
   * @param snap whole-pixel glyph positions (advances rounded) instead of quarter-pixel ones
   * @param linear blend in linear light instead of on the sRGB values
   * @param contrast exponent of the coverage correction applied when blending linearly; 1 is none
   * @param thin the exponent for text lighter than its background, if different
   * @param darken FreeType's stem darkening, which only the auto-hinter implements
   * @param correction what decides whether the coverage correction thickens or thins
   */
  record Style(Hint hint, boolean lcd, boolean snap, boolean linear, double contrast,
      double thin, boolean darken, Correction correction) {

    Engine engine() {
      return hint == Hint.NATIVE_V35 ? Engine.V35 : darken ? Engine.DARKEN : Engine.DEFAULT;
    }

    int loadFlags() {
      boolean lcdHinting = lcd && hint != Hint.NONE && hint != Hint.AUTO_LIGHT;
      return hint.flags | (lcdHinting ? FreeType.LOAD_TARGET_LCD : 0);
    }

    Style hint(Hint h) {
      return new Style(h, lcd, snap, linear, contrast, thin, darken, correction);
    }

    Style raster(boolean lcd, boolean snap) {
      return new Style(hint, lcd, snap, linear, contrast, thin, darken, correction);
    }

    Style blend(boolean linear, double contrast, boolean darken) {
      return new Style(darken ? Hint.AUTO_LIGHT : hint, lcd, snap, linear, contrast, contrast,
          darken, correction);
    }

    Style thin(double exponent) {
      return new Style(hint, lcd, snap, linear, contrast, exponent, darken, correction);
    }

    Style correction(Correction c) {
      return new Style(hint, lcd, snap, linear, contrast, thin, darken, c);
    }

    String label() {
      String blend = !linear ? "blend on sRGB values (gamma space)"
          : contrast == 1 && thin == 1 ? "blend in linear light"
          : "blend in linear light, coverage γ " + number(contrast)
              + (thin == contrast ? "" : ",\nlight text γ " + number(thin))
              + (correction == Correction.BACKGROUND ? "" : ",\n" + correction.label);
      return hint.label + "\n" + (lcd ? "LCD subpixel" : "greyscale") + ", "
          + (snap ? "whole-pixel positions" : "¼-pixel positions") + "\n" + blend
          + (darken ? " + stem darkening" : "");
    }
  }

  static final Map<Engine, MemorySegment> LIBRARIES = new EnumMap<>(Engine.class);
  static final Map<String, MemorySegment> FT_FACES = new HashMap<>();
  static final Map<String, MemorySegment> HB_FONTS = new HashMap<>();

  record GlyphKey(Engine engine, String font, int size26, int glyph, int flags, boolean lcd,
      int dx26) {}

  /** shortcut: unbounded, keyed by quarter-pixel offset; a real cache needs eviction. */
  static final Map<GlyphKey, FreeType.Bitmap> GLYPHS = new HashMap<>();

  static MemorySegment library(Engine engine) {
    return LIBRARIES.computeIfAbsent(engine, e -> {
      MemorySegment lib = FreeType.init();
      int lcd = FreeType.setLcdFilter(lib, FreeType.LCD_FILTER_DEFAULT);
      int property = switch (e) {
        case DEFAULT -> 0;
        case V35 -> FreeType.setProperty(lib, "truetype", "interpreter-version", 35);
        case DARKEN -> FreeType.setProperty(lib, "autofitter", "no-stem-darkening", false);
      };
      System.out.printf("FreeType %s library %s: LCD filter %s, property %s%n",
          FreeType.version(lib), e, lcd == 0 ? "ok" : "error " + lcd,
          property == 0 ? "ok" : "error " + property);
      return lib;
    });
  }

  static MemorySegment ftFace(Engine engine, Font font, int size26) {
    return FT_FACES.computeIfAbsent(engine + font.name + size26, k -> {
      MemorySegment face = FreeType.newFace(library(engine), font.data);
      FreeType.setPixelSize(face, size26);
      return face;
    });
  }

  static MemorySegment hbFont(Font font, int size26) {
    return HB_FONTS.computeIfAbsent(font.name + size26, k -> HarfBuzz.font(font.hbFace, size26));
  }

  // ---------------------------------------------------------------- drawing

  interface Text {
    double width(Font font, double px, String s);

    void draw(Canvas canvas, Font font, double px, String s, double x, int baseline, Theme theme);
  }

  /** This spike's renderer: HarfBuzz positions, FreeType bitmaps, blended by {@link Canvas}. */
  record Ours(Style style, String... features) implements Text {

    @Override
    public double width(Font font, double px, String s) {
      return layout(null, font, px, s, 0, 0, null);
    }

    @Override
    public void draw(Canvas canvas, Font font, double px, String s, double x, int baseline,
        Theme theme) {
      layout(canvas, font, px, s, x, baseline, theme);
    }

    double layout(Canvas canvas, Font font, double px, String s, double x, int baseline,
        Theme theme) {
      int size26 = (int) Math.round(px * 64);
      HarfBuzz.Run run = HarfBuzz.shape(hbFont(font, size26), s, features);
      long start = style.snap ? Math.round(x) * 64L : Math.round(x * 64);
      long pen = start;
      for (int i = 0; i < run.glyphs().length; i++) {
        int advance = run.xAdvance()[i];
        int offset = run.xOffset()[i];
        if (style.snap) {
          advance = (int) Math.round(advance / 64.0) * 64;
          offset = (int) Math.round(offset / 64.0) * 64;
        }
        if (canvas != null) {
          long gx = pen + offset;
          long ix = Math.floorDiv(gx, 64);
          int quarter = (int) Math.round((gx - ix * 64) / 16.0);
          if (quarter == 4) {
            ix++;
            quarter = 0;
          }
          FreeType.Bitmap glyph = glyph(font, size26, run.glyphs()[i], quarter * 16);
          canvas.blit(glyph, (int) ix + glyph.left(),
              baseline - Math.round(run.yOffset()[i] / 64f) - glyph.top(), theme.fg, style);
        }
        pen += advance;
      }
      return (pen - start) / 64.0;
    }

    FreeType.Bitmap glyph(Font font, int size26, int glyph, int dx26) {
      GlyphKey key = new GlyphKey(style.engine(), font.name, size26, glyph, style.loadFlags(),
          style.lcd, dx26);
      return GLYPHS.computeIfAbsent(key, k -> FreeType.render(ftFace(k.engine(), font, size26),
          glyph, k.flags(), k.lcd(), dx26));
    }
  }

  static final double[] TO_LINEAR = new double[256];

  static {
    for (int i = 0; i < 256; i++) {
      double c = i / 255.0;
      TO_LINEAR[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
  }

  /** CIE lightness L* (0 to 100) of an sRGB colour. */
  static double lightness(int rgb) {
    double y = 0.2126 * TO_LINEAR[rgb >> 16 & 0xFF] + 0.7152 * TO_LINEAR[rgb >> 8 & 0xFF]
        + 0.0722 * TO_LINEAR[rgb & 0xFF];
    return y > 216 / 24389.0 ? 116 * Math.cbrt(y) - 16 : y * 24389 / 27.0;
  }

  static int toSrgb(double linear) {
    double c = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
    return (int) Math.max(0, Math.min(255, Math.round(c * 255)));
  }

  static final class Canvas {
    final int width;
    final int height;
    final byte[] rgba;

    Canvas(int width, int height, int bg) {
      this.width = width;
      this.height = height;
      rgba = new byte[width * height * 4];
      fill(0, 0, width, height, bg);
    }

    void fill(int x, int y, int w, int h, int rgb) {
      for (int py = Math.max(0, y); py < Math.min(height, y + h); py++) {
        for (int px = Math.max(0, x); px < Math.min(width, x + w); px++) {
          set(px, py, rgb);
        }
      }
    }

    void set(int x, int y, int rgb) {
      if (x < 0 || y < 0 || x >= width || y >= height) {
        return;
      }
      int i = (y * width + x) * 4;
      rgba[i] = (byte) (rgb >> 16);
      rgba[i + 1] = (byte) (rgb >> 8);
      rgba[i + 2] = (byte) rgb;
      rgba[i + 3] = (byte) 0xFF;
    }

    int get(int x, int y) {
      int i = (y * width + x) * 4;
      return (rgba[i] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8 | (rgba[i + 2] & 0xFF);
    }

    void blit(FreeType.Bitmap glyph, int x0, int y0, int fg, Style style) {
      double lightness = lightness(fg);
      for (int y = 0; y < glyph.height(); y++) {
        for (int x = 0; x < glyph.width(); x++) {
          int px = x0 + x;
          int py = y0 + y;
          if (px < 0 || py < 0 || px >= width || py >= height) {
            continue;
          }
          for (int channel = 0; channel < 3; channel++) {
            int index = (y * glyph.width() + x) * glyph.channels()
                + (glyph.channels() == 3 ? channel : 0);
            int coverage = glyph.coverage()[index] & 0xFF;
            if (coverage == 0) {
              continue;
            }
            int i = (py * width + px) * 4 + channel;
            int f = fg >> (16 - 8 * channel) & 0xFF;
            rgba[i] = (byte) blend(rgba[i] & 0xFF, f, coverage / 255.0, style, lightness);
          }
        }
      }
    }

    Pixels pixels() {
      return new Pixels(width, height, rgba);
    }
  }

  /**
   * The coverage correction pulls linear blending back towards the look of gamma-space blending:
   * at exponent 2.2 it matches it for black and white. Text darker than its background is
   * thickened, lighter text thinned; {@code lightness} is the text colour's L*.
   */
  static int blend(int bg, int fg, double a, Style style, double lightness) {
    if (!style.linear) {
      return (int) Math.round(bg + (fg - bg) * a);
    }
    if (style.contrast > 1 || style.thin > 1) {
      double thin = Math.pow(a, style.thin);
      double thicken = 1 - Math.pow(1 - a, style.contrast);
      double t = style.correction == Correction.BACKGROUND ? (fg > bg ? 1 : 0)
          : style.correction.thinning(lightness);
      a = thicken + (thin - thicken) * t;
    }
    return toSrgb(TO_LINEAR[bg] + (TO_LINEAR[fg] - TO_LINEAR[bg]) * a);
  }

  // ---------------------------------------------------------------- the native reference

  /** Text drawn by pango-view (Pango with cairo and FreeType), as GTK draws it on this desktop. */
  record Pango(String... options) implements Text {

    static final Map<String, int[]> IMAGES = new HashMap<>();
    static Path work;

    @Override
    public double width(Font font, double px, String s) {
      return image(font, px, s, LIGHT)[0];
    }

    @Override
    public void draw(Canvas canvas, Font font, double px, String s, double x, int baseline,
        Theme theme) {
      int[] image = image(font, px, s, theme);
      int left = (int) Math.round(x);
      int top = baseline - baseline(font, px, theme);
      for (int y = 0; y < image[1]; y++) {
        for (int ix = 0; ix < image[0]; ix++) {
          int rgb = image[2 + y * image[0] + ix];
          if (rgb != theme.bg) {
            canvas.set(left + ix, top + y, rgb);
          }
        }
      }
    }

    /** Pango places the baseline by its own metrics: find it below the ink of an H. */
    int baseline(Font font, double px, Theme theme) {
      int[] image = image(font, px, "H", theme);
      for (int y = image[1] - 1; y >= 0; y--) {
        for (int x = 0; x < image[0]; x++) {
          if (image[2 + y * image[0] + x] != theme.bg) {
            return y + 1;
          }
        }
      }
      throw new IllegalStateException("pango drew no H");
    }

    /** Width, height, then the pixels as 0xRRGGBB. */
    int[] image(Font font, double px, String s, Theme theme) {
      List<String> command = new ArrayList<>(List.of("pango-view", "-q", "--pixels",
          "--margin=0", "--font=" + font.family + " " + number(px),
          "--foreground=" + hex(theme.fg), "--background=" + hex(theme.bg)));
      command.addAll(List.of(options));
      return IMAGES.computeIfAbsent(String.join(" ", command) + "|" + s, k -> {
        Path png = work.resolve("run.png");
        List<String> render = new ArrayList<>(command);
        render.addAll(List.of("-o", png.toString(), "--text=" + s));
        run(render);
        return ppm(run(List.of("convert", png.toString(), "-depth", "8", "ppm:-")));
      });
    }

    static boolean available(Path out) throws IOException {
      try {
        run(List.of("pango-view", "--version"));
        run(List.of("convert", "--version"));
      } catch (RuntimeException e) {
        return false;
      }
      // Our own fontconfig setup: just the two fonts, no rules that would override the options.
      work = Files.createDirectories(out.resolve("pango"));
      Files.writeString(work.resolve("fonts.conf"), "<?xml version=\"1.0\"?>\n<fontconfig><dir>"
          + FONTS.toAbsolutePath() + "</dir><cachedir>" + work.resolve("cache")
          + "</cachedir></fontconfig>\n");
      return true;
    }

    static byte[] run(List<String> command) {
      try {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (work != null) {
          builder.environment().put("FONTCONFIG_FILE", work.resolve("fonts.conf").toString());
        }
        Process process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
          throw new IllegalStateException("failed: " + command);
        }
        return output;
      } catch (IOException e) {
        throw new IllegalStateException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    static int[] ppm(byte[] ppm) {
      int pos = 0;
      String[] header = new String[4];
      for (int t = 0; t < 4; t++) {
        while (Character.isWhitespace(ppm[pos])) {
          pos++;
        }
        int start = pos;
        while (!Character.isWhitespace(ppm[pos])) {
          pos++;
        }
        header[t] = new String(ppm, start, pos - start, StandardCharsets.US_ASCII);
      }
      pos++;
      int w = Integer.parseInt(header[1]);
      int h = Integer.parseInt(header[2]);
      if (!header[0].equals("P6") || !header[3].equals("255")) {
        throw new IllegalStateException("unexpected PPM header " + String.join(" ", header));
      }
      int[] image = new int[2 + w * h];
      image[0] = w;
      image[1] = h;
      for (int i = 0; i < w * h; i++, pos += 3) {
        image[2 + i] = (ppm[pos] & 0xFF) << 16 | (ppm[pos + 1] & 0xFF) << 8 | (ppm[pos + 2] & 0xFF);
      }
      return image;
    }
  }

  /** Text drawn by Windows itself (see {@link DirectWrite}), over the theme's background. */
  enum Windows implements Text {
    CLEARTYPE, GREYSCALE, GDI_CLEARTYPE;

    static final Map<String, DirectWrite.Drawn> IMAGES = new HashMap<>();

    @Override
    public double width(Font font, double px, String s) {
      return this == GDI_CLEARTYPE ? DirectWrite.gdiWidth(font.family, (int) Math.round(px), 5, s)
          : DirectWrite.width(font.family, px, s);
    }

    /** DirectWrite keeps the fraction of x, in 1/64 pixels; GDI starts on a whole pixel. */
    @Override
    public void draw(Canvas canvas, Font font, double px, String s, double x, int baseline,
        Theme theme) {
      boolean gdi = this == GDI_CLEARTYPE;
      int left = gdi ? (int) Math.round(x) : (int) Math.floor(x);
      int fraction64 = (int) Math.round((x - left) * 64);
      DirectWrite.Drawn image = IMAGES.computeIfAbsent(
          this + "|" + font.family + "|" + px + "|" + fraction64 + "|" + theme.fg + "|" + theme.bg
              + "|" + s,
          k -> gdi
              ? DirectWrite.gdiDraw(font.family, (int) Math.round(px), 5, s, theme.fg, theme.bg)
              : DirectWrite.draw(font.family, px, s, fraction64 / 64.0, theme.fg, theme.bg,
                  this == CLEARTYPE ? DirectWrite.ANTIALIAS_CLEARTYPE
                      : DirectWrite.ANTIALIAS_GRAYSCALE));
      int x0 = left - DirectWrite.MARGIN;
      int y0 = baseline - DirectWrite.BASELINE;
      for (int y = 0; y < DirectWrite.HEIGHT; y++) {
        for (int ix = 0; ix < image.width(); ix++) {
          int rgb = image.rgb()[y * image.width() + ix];
          if (rgb != theme.bg) {
            canvas.set(x0 + ix, y0 + y, rgb);
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------- sheets

  record Row(String label, Text text) {}

  static int lineHeight(double px) {
    return (int) Math.round(px * 1.45);
  }

  static int baselineOffset(Font font, double px) {
    int lh = lineHeight(px);
    return (int) Math.round((lh - (font.ascender + font.descender) * px) / 2 + font.ascender * px);
  }

  /** Ten lines and two half-line gaps. */
  static int sampleHeight(double px) {
    return lineHeight(px) * 11;
  }

  static int sampleWidth(double px) {
    return (int) Math.ceil(WIDTH * px);
  }

  static void sample(Canvas c, Text t, Font f, double px, int x0, int top, Theme theme) {
    int lh = lineHeight(px);
    int y = top + baselineOffset(f, px);
    for (String[] row : TABLE) {
      for (int col = 0; col < 3; col++) {
        t.draw(c, f, px, row[col], x0 + COLUMNS[col] * px, y, theme);
      }
      t.draw(c, f, px, row[3], x0 + WIDTH * px - t.width(f, px, row[3]), y, theme);
      y += lh;
    }
    y += lh / 2;
    for (String line : SQL) {
      t.draw(c, f, px, line, x0, y, theme);
      y += lh;
    }
    y += lh / 2;
    double x = x0;
    for (String item : MENU_BAR) {
      t.draw(c, f, px, item, x, y, theme);
      x += t.width(f, px, item) + 1.5 * px;
    }
    y += lh;
    for (int i = 0; i < MENU_ITEMS.length; i++) {
      double left = x0 + i * 16 * px;
      t.draw(c, f, px, MENU_ITEMS[i][0], left, y, theme);
      String key = MENU_ITEMS[i][1];
      t.draw(c, f, px, key, left + 14 * px - t.width(f, px, key), y, theme);
    }
  }

  static Text labels;

  static void label(Canvas c, String text, double px, int x, int top, Theme theme) {
    int y = top + baselineOffset(inter, px);
    for (String line : text.split("\n")) {
      labels.draw(c, inter, px, line, x, y, theme);
      y += lineHeight(px);
    }
  }

  /** Rows of variants, a column per size, both themes; everything at the given scale. */
  static Pixels sheet(String title, Font font, double scale, List<Row> rows) {
    int pad = (int) Math.round(12 * scale);
    double labelPx = 12 * scale;
    int labelWidth = (int) Math.round(300 * scale);
    int[] columns = new int[SIZES.length];
    int x = labelWidth;
    for (int i = 0; i < SIZES.length; i++) {
      columns[i] = x;
      x += sampleWidth(SIZES[i] * scale) + 2 * pad;
    }
    int header = 2 * pad + 2 * lineHeight(labelPx);
    int rowHeight = Math.max(sampleHeight(SIZES[SIZES.length - 1] * scale),
        5 * lineHeight(labelPx)) + 2 * pad;
    Canvas c = new Canvas(x, header + rowHeight * rows.size() * THEMES.size(), LIGHT.bg);

    label(c, title + "  ·  " + font.family + "  ·  scale " + scale, labelPx, pad, pad, LIGHT);
    for (int i = 0; i < SIZES.length; i++) {
      String size = scale == 1 ? number(SIZES[i]) + " px"
          : number(SIZES[i]) + " px × " + scale + " = " + number(SIZES[i] * scale) + " px";
      label(c, size, labelPx, columns[i] + pad, pad + lineHeight(labelPx), LIGHT);
    }
    int y = header;
    for (Theme theme : THEMES) {
      for (Row row : rows) {
        c.fill(0, y, c.width, rowHeight, theme.bg);
        c.fill(0, y, c.width, 1, theme.rule);
        label(c, row.label + "\n(" + theme.name + ")", labelPx, pad, y + pad, theme);
        for (int i = 0; i < SIZES.length; i++) {
          sample(c, row.text, font, SIZES[i] * scale, columns[i] + pad, y + pad, theme);
        }
        y += rowHeight;
      }
    }
    return c.pixels();
  }

  /** One line per row at 12 px, magnified four times so single pixels can be compared. */
  static Pixels zoom(List<Row> rows) {
    int zoom = 4;
    int pad = 12;
    int cropWidth = 200;
    int cropHeight = lineHeight(12);
    Font[] fonts = {inter, mono};
    String[] lines = {"Müller, Jürgen  Köln  1.234,56", "WHERE b.tag >= '2024-01-01'"};
    int labelLines = rows.stream().mapToInt(r -> (int) r.label.lines().count()).max().orElse(3);
    int labelHeight = Math.max(3, labelLines) * lineHeight(12) + pad;
    int cell = labelHeight + cropHeight * zoom + pad;
    int width = 2 * (cropWidth * zoom + 2 * pad);
    Canvas c = new Canvas(width, cell * rows.size() * THEMES.size(), LIGHT.bg);
    int y = 0;
    for (Theme theme : THEMES) {
      for (Row row : rows) {
        c.fill(0, y, width, cell, theme.bg);
        c.fill(0, y, width, 1, theme.rule);
        for (int f = 0; f < fonts.length; f++) {
          int x0 = f * (cropWidth * zoom + 2 * pad) + pad;
          label(c, row.label + " (" + theme.name + ", " + fonts[f].family + ")", 12, x0,
              y + pad, theme);
          Canvas line = new Canvas(cropWidth, cropHeight, theme.bg);
          row.text.draw(line, fonts[f], 12, lines[f], 2, baselineOffset(fonts[f], 12), theme);
          for (int py = 0; py < cropHeight * zoom; py++) {
            for (int px = 0; px < cropWidth * zoom; px++) {
              c.set(x0 + px, y + labelHeight + py, line.get(px / zoom, py / zoom));
            }
          }
        }
        y += cell;
      }
    }
    return c.pixels();
  }

  /** Kerning and ligatures: printed glyph by glyph, and drawn. */
  static Pixels shaping(Style style) {
    String kerning = "AVATAR Tabelle Wäsche Torte Yoga P.T. 7,1";
    String ligatures = "a != b >= c <= d <> e -> f => g == h -- /* x */ www ::";
    int size26 = 14 * 64;
    report(inter, size26, kerning, new String[0]);
    report(inter, size26, kerning, new String[] {"-kern"});
    report(mono, size26, ligatures, new String[0]);
    report(mono, size26, ligatures, new String[] {"-calt"});
    report(mono, size26, ligatures, new String[] {"-liga"});

    record Line(String label, Font font, String text, String... features) {}
    List<Line> lines = List.of(
        new Line("Inter, default features", inter, kerning),
        new Line("Inter, -kern", inter, kerning, "-kern"),
        new Line("JetBrains Mono, default features", mono, ligatures),
        new Line("JetBrains Mono, -calt", mono, ligatures, "-calt"),
        new Line("JetBrains Mono, -liga", mono, ligatures, "-liga"));
    int pad = 12;
    int labelWidth = 260;
    Canvas c = new Canvas(labelWidth + 1000, pad + lines.size() * (lineHeight(14) + lineHeight(28) + pad),
        LIGHT.bg);
    int y = pad;
    for (Line line : lines) {
      label(c, line.label, 12, pad, y, LIGHT);
      Text text = new Ours(style, line.features);
      text.draw(c, line.font, 14, line.text, labelWidth, y + baselineOffset(line.font, 14), LIGHT);
      y += lineHeight(14);
      text.draw(c, line.font, 28, line.text, labelWidth, y + baselineOffset(line.font, 28), LIGHT);
      y += lineHeight(28) + pad;
    }
    return c.pixels();
  }

  static void report(Font font, int size26, String text, String[] features) {
    MemorySegment hb = hbFont(font, size26);
    HarfBuzz.Run run = HarfBuzz.shape(hb, text, features);
    StringBuilder glyphs = new StringBuilder();
    int total = 0;
    for (int i = 0; i < run.glyphs().length; i++) {
      total += run.xAdvance()[i];
      glyphs.append(' ').append(HarfBuzz.glyphName(hb, run.glyphs()[i]))
          .append('@').append(run.clusters()[i]).append('+').append(run.xAdvance()[i]);
    }
    System.out.printf("%s %s %s: %d glyphs, %.2f px%n %s%n", font.name, String.join(",", features),
        text, run.glyphs().length, total / 64.0, glyphs);
  }

  // ---------------------------------------------------------------- measurements

  /**
   * Apparent coverage of a pixel: how far it moved from the background towards the text colour
   * in sRGB values, 0 to 1; coloured ClearType edges are projected onto that direction.
   */
  static double coverage(int rgb, int fg, int bg) {
    double moved = 0;
    double full = 0;
    for (int shift = 0; shift <= 16; shift += 8) {
      int b = bg >> shift & 0xFF;
      int f = (fg >> shift & 0xFF) - b;
      moved += ((rgb >> shift & 0xFF) - b) * f;
      full += f * f;
    }
    return Math.max(0, Math.min(1, moved / full));
  }

  /**
   * @param ink the summed coverage, in pixels
   * @param solid the share of inked pixels (coverage at least 0.1) that are at least 0.9 covered
   */
  record Ink(double ink, double solid) {}

  static Ink ink(Text text, Font font, double px, Theme theme) {
    Canvas c = new Canvas(sampleWidth(px) + 40, sampleHeight(px), theme.bg);
    sample(c, text, font, px, 20, 0, theme);
    double ink = 0;
    int inked = 0;
    int solid = 0;
    for (int y = 0; y < c.height; y++) {
      for (int x = 0; x < c.width; x++) {
        double a = coverage(c.get(x, y), theme.fg, theme.bg);
        ink += a;
        inked += a >= 0.1 ? 1 : 0;
        solid += a >= 0.9 ? 1 : 0;
      }
    }
    return new Ink(ink, inked == 0 ? 0 : solid / (double) inked);
  }

  /** Rows above the baseline in which a run of x reaches at least half coverage. */
  static int xHeight(Text text, Font font, double px, Theme theme) {
    int baseline = (int) Math.ceil(2 * px);
    Canvas c = new Canvas((int) (10 * px), baseline + (int) px, theme.bg);
    text.draw(c, font, px, "xxxxxxxx", 4, baseline, theme);
    int rows = 0;
    for (int y = 0; y < baseline; y++) {
      for (int x = 0; x < c.width; x++) {
        if (coverage(c.get(x, y), theme.fg, theme.bg) >= 0.5) {
          rows++;
          break;
        }
      }
    }
    return rows;
  }

  /** The advances of every string in the sample, added up. */
  static double width(Text text, Font font, double px) {
    double width = 0;
    for (String[] row : TABLE) {
      for (String cell : row) {
        width += text.width(font, px, cell);
      }
    }
    for (String line : SQL) {
      width += text.width(font, px, line);
    }
    for (String item : MENU_BAR) {
      width += text.width(font, px, item);
    }
    return width;
  }

  /** Prints ink, solid share, width and x-height per row; ink and width relative to row ref. */
  static void measure(String title, List<Row> rows, int ref, double scale) {
    System.out.printf(Locale.ROOT, "%n%s, scale %s. Per size: ink and width relative to \"%s\", "
        + "the share of inked pixels that are solid, x-height in pixels.%n", title, number(scale),
        rows.get(ref).label.lines().findFirst().orElseThrow());
    for (Font font : new Font[] {inter, mono}) {
      for (Theme theme : THEMES) {
        System.out.printf("%s, %s:%n", font.family, theme.name);
        for (Row row : rows) {
          StringBuilder line = new StringBuilder("  ");
          for (double size : SIZES) {
            double px = size * scale;
            Ink ink = ink(row.text, font, px, theme);
            Ink reference = ink(rows.get(ref).text, font, px, theme);
            line.append(String.format(Locale.ROOT,
                "  %s px: ink %3.0f%% solid %2.0f%% width %5.1f%% x %d", number(px),
                100 * ink.ink / reference.ink, 100 * ink.solid,
                100 * width(row.text, font, px) / width(rows.get(ref).text, font, px),
                xHeight(row.text, font, px, theme)));
          }
          System.out.println(line + "   " + row.label.replace("\n", " / "));
        }
      }
    }
  }

  // ---------------------------------------------------------------- the GPU's coverage rule

  /** Colour pairs for the coverage correction that only knows the text colour. */
  static final List<Theme> CASES = List.of(LIGHT, DARK,
      new Theme("mid-grey on dark", 0x8C8C8C, 0x1E1F22, 0x1E1F22),
      new Theme("mid-grey on white", 0x8C8C8C, 0xFFFFFF, 0xFFFFFF),
      new Theme("mid-grey on amber", 0x808080, 0xFFE08A, 0xFFE08A),
      new Theme("mid-grey on blue", 0x8C8C8C, 0x2F65CA, 0x2F65CA));

  /** Two lines in every colour pair at 12 px, at 100 % or magnified, a row per variant. */
  static Pixels cases(List<Row> rows, int zoom) {
    int pad = 12;
    int labelWidth = 330;
    int cropWidth = zoom == 1 ? 250 : 64;
    int lh = lineHeight(12);
    String[] lines = zoom == 1
        ? new String[] {"1043  Größe & Söhne GmbH  -98.765,43", "WHERE b.tag >= '2024-01-01'"}
        : new String[] {"Größe & Söhne", "b.tag >= '20"};
    Font[] fonts = {inter, mono};
    int cell = cropWidth * zoom + 2 * pad;
    int header = pad + 2 * lh;
    int rowHeight = Math.max(4 * lh, 2 * lh * zoom) + 2 * pad;
    Canvas c = new Canvas(labelWidth + cell * CASES.size(), header + rowHeight * rows.size(),
        LIGHT.bg);
    for (int i = 0; i < CASES.size(); i++) {
      Theme t = CASES.get(i);
      label(c, t.name + "\n" + hex(t.fg) + " on " + hex(t.bg) + ", L* " + number(lightness(t.fg))
          + " on " + number(lightness(t.bg)), 12, labelWidth + i * cell + pad, pad, LIGHT);
    }
    int y = header;
    for (Row row : rows) {
      c.fill(0, y, c.width, 1, LIGHT.rule);
      label(c, row.label, 12, pad, y + pad, LIGHT);
      for (int i = 0; i < CASES.size(); i++) {
        Theme t = CASES.get(i);
        Canvas line = new Canvas(cropWidth, 2 * lh, t.bg);
        for (int f = 0; f < fonts.length; f++) {
          row.text.draw(line, fonts[f], 12, lines[f], 2, f * lh + baselineOffset(fonts[f], 12), t);
        }
        int x0 = labelWidth + i * cell;
        c.fill(x0, y + 1, cell, rowHeight - 1, t.bg);
        for (int py = 0; py < line.height * zoom; py++) {
          for (int px = 0; px < line.width * zoom; px++) {
            c.set(x0 + pad + px, y + pad + py, line.get(px / zoom, py / zoom));
          }
        }
      }
      y += rowHeight;
    }
    return c.pixels();
  }

  /** Ink of every variant in every colour pair, against the background-aware rule. */
  static void measureCases(List<Row> rows, int ref) {
    System.out.printf("%nCoverage rule without the background: ink at 11 to 14 px, relative to "
        + "\"%s\"%n", rows.get(ref).label.replace("\n", " / "));
    for (Font font : new Font[] {inter, mono}) {
      System.out.println(font.family + ":");
      for (Row row : rows) {
        StringBuilder line = new StringBuilder("  ");
        for (Theme t : CASES) {
          double ink = 0;
          double reference = 0;
          for (double size : SIZES) {
            ink += ink(row.text, font, size, t).ink;
            reference += ink(rows.get(ref).text, font, size, t).ink;
          }
          line.append(String.format(Locale.ROOT, "  %s %4.0f%%", t.name, 100 * ink / reference));
        }
        System.out.println(line + "   " + row.label.replace("\n", " / "));
      }
    }
  }

  // ---------------------------------------------------------------- main

  static void write(Path out, String name, Pixels pixels) throws IOException {
    Files.write(out.resolve(name + ".png"), Png.encode(pixels));
    System.out.printf("%s.png  %dx%d%n", name, pixels.width(), pixels.height());
  }

  static String number(double value) {
    return value == Math.rint(value) ? String.valueOf((long) value)
        : String.format(Locale.ROOT, "%.1f", value);
  }

  static String hex(int rgb) {
    return String.format("#%06x", rgb);
  }

  /** The baseline the dimension sheets vary from: change one setting at a time. */
  static final Style BASE =
      new Style(Hint.AUTO_LIGHT, false, false, true, 1.8, 1.8, false, Correction.BACKGROUND);

  public static void main(String[] args) throws Exception {
    Path out = Files.createDirectories(Path.of(args.length > 0 ? args[0]
        : System.getProperty("java.io.tmpdir") + "/rethinkj-text-spike"));
    fetch("https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip",
        FONTS.resolve("inter"), "extras/ttf/Inter-Regular.ttf", "LICENSE.txt");
    fetch("https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip",
        FONTS.resolve("jbm"), "fonts/ttf/JetBrainsMono-Regular.ttf", "OFL.txt");
    inter = load("inter", "Inter", FONTS.resolve("inter/Inter-Regular.ttf"));
    mono = load("mono", "JetBrains Mono", FONTS.resolve("jbm/JetBrainsMono-Regular.ttf"));
    System.out.println("HarfBuzz " + HarfBuzz.version());
    if (FreeType.WINDOWS) {
      DirectWrite.init(inter.file, mono.file);
      DirectWrite.settings().forEach(System.out::println);
    }
    labels = new Ours(BASE);
    long started = System.nanoTime();

    for (double scale : new double[] {1, 1.5}) {
      String suffix = scale == 1 ? "100" : "150";
      for (Font font : new Font[] {inter, mono}) {
        List<Row> hinting = new ArrayList<>();
        for (Hint hint : Hint.values()) {
          hinting.add(row(BASE.hint(hint)));
        }
        write(out, "hinting-" + font.name + "-" + suffix,
            sheet("Hinting", font, scale, hinting));

        List<Row> raster = new ArrayList<>();
        for (boolean lcd : new boolean[] {false, true}) {
          for (boolean snap : new boolean[] {true, false}) {
            raster.add(row(BASE.raster(lcd, snap)));
          }
        }
        write(out, "aa-positions-" + font.name + "-" + suffix,
            sheet("Anti-aliasing and glyph positions", font, scale, raster));

        write(out, "blending-" + font.name + "-" + suffix, sheet("Blending", font, scale, List.of(
            row(BASE.blend(false, 1, false)),
            row(BASE.blend(true, 1, false)),
            row(BASE.blend(true, 1.45, false)),
            row(BASE.blend(true, 1.8, false)),
            row(BASE.blend(true, 1, true)),
            row(BASE.blend(true, 1.45, true)))));
      }
    }

    List<Row> finalists = new ArrayList<>();
    if (FreeType.WINDOWS) {
      finalists.add(new Row("Windows: DirectWrite and Direct2D, ClearType\n"
          + "(system settings; most desktop apps)", Windows.CLEARTYPE));
      finalists.add(new Row("Windows: DirectWrite and Direct2D, greyscale\n"
          + "(XAML / WinUI apps, much of the shell)", Windows.GREYSCALE));
      finalists.add(new Row("Windows: GDI ExtTextOutW, ClearType\n"
          + "(classic Win32; whole-pixel sizes,\nno kerning or ligatures)",
          Windows.GDI_CLEARTYPE));
    } else if (Pango.available(out)) {
      finalists.add(new Row("native: pango-view, LCD subpixel (rgb), hintslight,\n"
          + "hint metrics on (this desktop's settings, GTK 3)",
          new Pango("--antialias=subpixel", "--subpixel-order=rgb", "--hinting=slight",
              "--hint-metrics=on")));
      finalists.add(new Row("native: pango-view, greyscale, hintslight,\n"
          + "hint metrics on (desktop settings, no LCD)",
          new Pango("--antialias=gray", "--hinting=slight", "--hint-metrics=on")));
      finalists.add(new Row("ours, set up like the desktop: auto-hinter light,\n"
          + "LCD, whole-pixel positions, gamma-space blend",
          new Ours(new Style(Hint.AUTO_LIGHT, true, true, false, 1, 1, false,
              Correction.BACKGROUND))));
    } else {
      System.out.println("pango-view or convert not found: no native reference");
    }
    finalists.add(row(BASE));
    finalists.add(row(BASE.raster(false, true)));
    finalists.add(row(BASE.raster(true, false)));
    for (double scale : new double[] {1, 1.5}) {
      for (Font font : new Font[] {inter, mono}) {
        write(out, "native-" + font.name + "-" + (scale == 1 ? "100" : "150"),
            sheet("Best variants against the native reference", font, scale, finalists));
      }
    }
    write(out, "zoom", zoom(finalists));
    write(out, "shaping", shaping(BASE));

    Style whole = BASE.raster(false, true);
    List<Row> rule = new ArrayList<>();
    if (FreeType.WINDOWS) {
      rule.add(new Row("Windows: Direct2D, greyscale (its correction\n"
          + "also knows only the text colour)", Windows.GREYSCALE));
    }
    rule.add(row(whole));
    for (Correction correction : List.of(Correction.TEXT, Correction.TEXT_GRADED,
        Correction.TEXT_BIASED)) {
      rule.add(row(whole.correction(correction)));
    }
    rule.add(row(whole.blend(true, 1, false)));
    rule.add(row(whole.blend(false, 1, false)));
    write(out, "shader-rule", cases(rule, 1));
    write(out, "shader-rule-zoom", cases(rule, 4));

    if (FreeType.WINDOWS) {
      for (double scale : new double[] {1, 1.5}) {
        List<Row> rows = windows(finalists, scale);
        for (Font font : new Font[] {inter, mono}) {
          write(out, "windows-" + font.name + "-" + (scale == 1 ? "100" : "150"),
              sheet("Windows against the Linux recommendation and a Windows tuning", font, scale,
                  rows));
        }
        if (scale == 1) {
          write(out, "windows-zoom", zoom(rows));
        }
        measure("Windows against our candidates", rows, 1, scale);
      }
      List<Row> tuning = new ArrayList<>(List.of(finalists.get(1), finalists.get(0),
          finalists.get(2)));
      for (Hint hint : Hint.values()) {
        tuning.add(row(whole.hint(hint)));
      }
      Style hinted = whole.hint(Hint.NATIVE);
      tuning.addAll(List.of(row(hinted.thin(1.4)), row(hinted.thin(1)),
          row(hinted.blend(true, 2.2, false).thin(1)), row(hinted.raster(false, false).thin(1))));
      measure("Tuning against Windows", tuning, 0, 1);
    }
    measureCases(rule, FreeType.WINDOWS ? 1 : 0);

    long bytes = GLYPHS.values().stream().mapToLong(g -> g.coverage().length).sum();
    System.out.printf("%d glyph bitmaps cached, %d KiB; %.1f s%n", GLYPHS.size(), bytes / 1024,
        (System.nanoTime() - started) / 1e9);
  }

  static Row row(Style style) {
    return new Row(style.label(), new Ours(style));
  }

  /**
   * Windows' three renderers, then the Linux recommendation and the setting tuned for Windows:
   * whole-pixel positions at scale 1, quarter pixels above.
   */
  static List<Row> windows(List<Row> finalists, double scale) {
    Style linux = BASE.raster(false, scale == 1);
    Style tuned = linux.hint(Hint.NATIVE).thin(1.4);
    return List.of(finalists.get(0), finalists.get(1), finalists.get(2),
        new Row("ours, the Linux recommendation:\n" + linux.label(), new Ours(linux)),
        new Row("ours, tuned for Windows:\n" + tuned.label(), new Ours(tuned)));
  }
}
