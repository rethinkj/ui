import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Windows reference: text laid out by DirectWrite and drawn by Direct2D, as current Windows
 * applications draw it, plus GDI for classic Win32 controls. Both draw into one GDI bitmap that is
 * read back. The fonts are loaded privately, not installed.
 *
 * <p>COM over FFM: an object is a pointer to its table of method addresses, and a method takes
 * the object as its first argument. The slots are the methods' positions in the Windows SDK
 * headers, base interfaces first. There is one handle per descriptor; the address comes with the
 * call.
 */
final class DirectWrite {

  private DirectWrite() {}

  static final Linker LINKER = Linker.nativeLinker();
  static final Arena GLOBAL = Arena.global();
  static final SymbolLookup DWRITE = SymbolLookup.libraryLookup("dwrite.dll", GLOBAL);
  static final SymbolLookup D2D1 = SymbolLookup.libraryLookup("d2d1.dll", GLOBAL);
  static final SymbolLookup GDI32 = SymbolLookup.libraryLookup("gdi32.dll", GLOBAL);
  static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", GLOBAL);

  static final StructLayout POINT_2F = MemoryLayout.structLayout(JAVA_FLOAT.withName("x"),
      JAVA_FLOAT.withName("y"));

  // Exported functions.
  static final FunctionDescriptor DWRITE_CREATE_FACTORY =
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor D2D1_CREATE_FACTORY =
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor P_P = FunctionDescriptor.of(ADDRESS, ADDRESS);
  static final FunctionDescriptor P_PP = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor CREATE_DIB_SECTION =
      FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);
  static final FunctionDescriptor I_V = FunctionDescriptor.of(JAVA_INT);
  static final FunctionDescriptor I_PI = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor I_PIP =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
  static final FunctionDescriptor I_IIPI =
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor CREATE_FONT = FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT,
      JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
      JAVA_INT, JAVA_INT, ADDRESS);
  static final FunctionDescriptor EXT_TEXT_OUT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT,
      JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
  static final FunctionDescriptor GET_TEXT_EXTENT =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);

  static final MethodHandle DWRITE_CREATE_FACTORY_H =
      handle(DWRITE, "DWriteCreateFactory", DWRITE_CREATE_FACTORY);
  static final MethodHandle D2D1_CREATE_FACTORY_H =
      handle(D2D1, "D2D1CreateFactory", D2D1_CREATE_FACTORY);
  static final MethodHandle CREATE_COMPATIBLE_DC = handle(GDI32, "CreateCompatibleDC", P_P);
  static final MethodHandle CREATE_DIB_SECTION_H =
      handle(GDI32, "CreateDIBSection", CREATE_DIB_SECTION);
  static final MethodHandle SELECT_OBJECT = handle(GDI32, "SelectObject", P_PP);
  static final MethodHandle GDI_FLUSH = handle(GDI32, "GdiFlush", I_V);
  static final MethodHandle ADD_FONT_RESOURCE_EX = handle(GDI32, "AddFontResourceExW", I_PIP);
  static final MethodHandle CREATE_FONT_W = handle(GDI32, "CreateFontW", CREATE_FONT);
  static final MethodHandle SET_TEXT_COLOR = handle(GDI32, "SetTextColor", I_PI);
  static final MethodHandle SET_BK_MODE = handle(GDI32, "SetBkMode", I_PI);
  static final MethodHandle SET_TEXT_ALIGN = handle(GDI32, "SetTextAlign", I_PI);
  static final MethodHandle EXT_TEXT_OUT_W = handle(GDI32, "ExtTextOutW", EXT_TEXT_OUT);
  static final MethodHandle GET_TEXT_EXTENT_W =
      handle(GDI32, "GetTextExtentPoint32W", GET_TEXT_EXTENT);
  static final MethodHandle SYSTEM_PARAMETERS_INFO =
      handle(USER32, "SystemParametersInfoW", I_IIPI);

  // COM methods, receiver first.
  static final FunctionDescriptor COM_I_P = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor COM_I_PP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor COM_I_PPP =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor COM_I_PPPP =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor COM_I_PI = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor COM_F_P = FunctionDescriptor.of(JAVA_FLOAT, ADDRESS);
  static final FunctionDescriptor COM_V_P = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor COM_V_PI = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);
  static final FunctionDescriptor COM_V_PP = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
  static final FunctionDescriptor COM_CREATE_TEXT_FORMAT = FunctionDescriptor.of(JAVA_INT, ADDRESS,
      ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, ADDRESS, ADDRESS);
  static final FunctionDescriptor COM_CREATE_TEXT_LAYOUT = FunctionDescriptor.of(JAVA_INT, ADDRESS,
      ADDRESS, JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, ADDRESS);
  static final FunctionDescriptor COM_GET_LINE_METRICS =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
  static final FunctionDescriptor COM_DRAW_TEXT_LAYOUT =
      FunctionDescriptor.ofVoid(ADDRESS, POINT_2F, ADDRESS, ADDRESS, JAVA_INT);

  static final MethodHandle I_P = LINKER.downcallHandle(COM_I_P);
  static final MethodHandle I_PP = LINKER.downcallHandle(COM_I_PP);
  static final MethodHandle I_PPP = LINKER.downcallHandle(COM_I_PPP);
  static final MethodHandle I_PPPP = LINKER.downcallHandle(COM_I_PPPP);
  static final MethodHandle I_PI_COM = LINKER.downcallHandle(COM_I_PI);
  static final MethodHandle F_P = LINKER.downcallHandle(COM_F_P);
  static final MethodHandle V_P = LINKER.downcallHandle(COM_V_P);
  static final MethodHandle V_PI = LINKER.downcallHandle(COM_V_PI);
  static final MethodHandle V_PP = LINKER.downcallHandle(COM_V_PP);
  static final MethodHandle CREATE_TEXT_FORMAT = LINKER.downcallHandle(COM_CREATE_TEXT_FORMAT);
  static final MethodHandle CREATE_TEXT_LAYOUT = LINKER.downcallHandle(COM_CREATE_TEXT_LAYOUT);
  static final MethodHandle GET_LINE_METRICS = LINKER.downcallHandle(COM_GET_LINE_METRICS);
  static final MethodHandle DRAW_TEXT_LAYOUT = LINKER.downcallHandle(COM_DRAW_TEXT_LAYOUT);

  // Vtable slots. IUnknown is 0 to 2.
  static final int RELEASE = 2;
  static final int QUERY_INTERFACE = 0;
  // IDWriteFactory 3-23, IDWriteFactory1 24-25, IDWriteFactory2 26-30, IDWriteFactory3 31-39,
  // IDWriteFactory4 40-42, IDWriteFactory5 43-47.
  static final int FACTORY_CREATE_FONT_FILE_REFERENCE = 7;
  static final int FACTORY_CREATE_RENDERING_PARAMS = 10;
  static final int FACTORY_CREATE_TEXT_FORMAT = 15;
  static final int FACTORY_CREATE_TEXT_LAYOUT = 18;
  static final int FACTORY3_CREATE_FONT_COLLECTION_FROM_FONT_SET = 37;
  static final int FACTORY5_CREATE_FONT_SET_BUILDER = 43;
  // IDWriteFontSetBuilder 3-6, IDWriteFontSetBuilder1 7.
  static final int BUILDER_CREATE_FONT_SET = 6;
  static final int BUILDER1_ADD_FONT_FILE = 7;
  static final int COLLECTION_FIND_FAMILY_NAME = 5;
  // IDWriteTextFormat 3-27, IDWriteTextLayout 28-66.
  static final int FORMAT_SET_WORD_WRAPPING = 5;
  static final int LAYOUT_GET_LINE_METRICS = 59;
  static final int LAYOUT_GET_METRICS = 60;
  // IDWriteRenderingParams 3-7, IDWriteRenderingParams1 8, IDWriteRenderingParams2 9.
  static final int PARAMS_GET_GAMMA = 3;
  static final int PARAMS_GET_ENHANCED_CONTRAST = 4;
  static final int PARAMS_GET_CLEARTYPE_LEVEL = 5;
  static final int PARAMS_GET_PIXEL_GEOMETRY = 6;
  static final int PARAMS_GET_RENDERING_MODE = 7;
  static final int PARAMS1_GET_GRAYSCALE_ENHANCED_CONTRAST = 8;
  static final int PARAMS2_GET_GRID_FIT_MODE = 9;
  // ID2D1Factory 3-16.
  static final int D2D_FACTORY_CREATE_DC_RENDER_TARGET = 16;
  // ID2D1Resource 3, ID2D1RenderTarget 4-56, ID2D1DCRenderTarget 57.
  static final int RT_CREATE_SOLID_COLOR_BRUSH = 8;
  static final int RT_DRAW_TEXT_LAYOUT = 28;
  static final int RT_SET_TEXT_ANTIALIAS_MODE = 34;
  static final int RT_CLEAR = 47;
  static final int RT_BEGIN_DRAW = 48;
  static final int RT_END_DRAW = 49;
  static final int RT_BIND_DC = 57;

  static final MemorySegment IID_IDWRITE_FACTORY5 = guid("958DB99A-BE2A-4F09-AF7D-65189803D1D3");
  static final MemorySegment IID_ID2D1_FACTORY = guid("06152247-6f50-465a-9245-118bfd3b6007");
  static final MemorySegment IID_IDWRITE_RENDERING_PARAMS2 =
      guid("F9D711C3-9777-40AE-87E8-3E5AF9BF0948");

  static final int ANTIALIAS_CLEARTYPE = 1;
  static final int ANTIALIAS_GRAYSCALE = 2;

  /** The bitmap every string is drawn into; the baseline is at {@link #BASELINE}. */
  static final int WIDTH = 2048;
  static final int HEIGHT = 160;
  static final int BASELINE = 100;
  /** Room left of the origin for ink that reaches past it. */
  static final int MARGIN = 8;

  static MemorySegment factory;
  static MemorySegment collection;
  static MemorySegment target;
  static MemorySegment dc;
  static MemorySegment bits;
  static final Map<String, MemorySegment> FORMATS = new HashMap<>();
  static final Map<Integer, MemorySegment> BRUSHES = new HashMap<>();
  static final Map<String, MemorySegment> GDI_FONTS = new HashMap<>();

  /** Loads the fonts privately, for DirectWrite and for GDI, and sets up the bitmap. */
  static void init(Path... fonts) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      check((int) call(DWRITE_CREATE_FACTORY_H, 0, IID_IDWRITE_FACTORY5, out),
          "DWriteCreateFactory");
      factory = object(out);

      check(com(factory, FACTORY5_CREATE_FONT_SET_BUILDER, I_PP, out), "CreateFontSetBuilder");
      MemorySegment builder = object(out);
      for (Path font : fonts) {
        String file = font.toAbsolutePath().toString();
        check(com(factory, FACTORY_CREATE_FONT_FILE_REFERENCE, I_PPPP, wide(arena, file),
            MemorySegment.NULL, out), "CreateFontFileReference");
        MemorySegment fontFile = object(out);
        check(com(builder, BUILDER1_ADD_FONT_FILE, I_PP, fontFile), "AddFontFile");
        release(fontFile);
        int added = (int) call(ADD_FONT_RESOURCE_EX, wide(arena, file), 0x10, MemorySegment.NULL);
        if (added == 0) {
          throw new IllegalStateException("AddFontResourceExW failed for " + font);
        }
      }
      check(com(builder, BUILDER_CREATE_FONT_SET, I_PP, out), "CreateFontSet");
      MemorySegment set = object(out);
      check(com(factory, FACTORY3_CREATE_FONT_COLLECTION_FROM_FONT_SET, I_PPP, set, out),
          "CreateFontCollectionFromFontSet");
      collection = object(out);
      release(set);
      release(builder);

      check((int) call(D2D1_CREATE_FACTORY_H, 0, IID_ID2D1_FACTORY, MemorySegment.NULL, out),
          "D2D1CreateFactory");
      MemorySegment d2d = object(out);
      // Software rendering, B8G8R8A8 with alpha ignored (ClearType needs an opaque target), 96 dpi.
      MemorySegment props = arena.allocate(JAVA_INT, 7);
      props.setAtIndex(JAVA_INT, 0, 1);
      props.setAtIndex(JAVA_INT, 1, 87);
      props.setAtIndex(JAVA_INT, 2, 3);
      props.setAtIndex(JAVA_FLOAT, 3, 96);
      props.setAtIndex(JAVA_FLOAT, 4, 96);
      check(com(d2d, D2D_FACTORY_CREATE_DC_RENDER_TARGET, I_PPP, props, out),
          "CreateDCRenderTarget");
      target = object(out);

      dc = (MemorySegment) call(CREATE_COMPATIBLE_DC, MemorySegment.NULL);
      MemorySegment info = arena.allocate(44);
      info.set(JAVA_INT, 0, 40);
      info.set(JAVA_INT, 4, WIDTH);
      info.set(JAVA_INT, 8, -HEIGHT);
      info.set(JAVA_SHORT, 12, (short) 1);
      info.set(JAVA_SHORT, 14, (short) 32);
      MemorySegment bitsOut = arena.allocate(ADDRESS);
      MemorySegment bitmap = (MemorySegment) call(CREATE_DIB_SECTION_H, dc, info, 0, bitsOut,
          MemorySegment.NULL, 0);
      if (bitmap.equals(MemorySegment.NULL)) {
        throw new IllegalStateException("CreateDIBSection failed");
      }
      bits = bitsOut.get(ADDRESS, 0).reinterpret((long) WIDTH * HEIGHT * 4);
      call(SELECT_OBJECT, dc, bitmap);
      MemorySegment rect = arena.allocate(JAVA_INT, 4);
      rect.setAtIndex(JAVA_INT, 2, WIDTH);
      rect.setAtIndex(JAVA_INT, 3, HEIGHT);
      check(com(target, RT_BIND_DC, I_PPP, dc, rect), "BindDC");
    }
  }

  /** The system's text settings, as DirectWrite and GDI see them. */
  static List<String> settings() {
    List<String> lines = new ArrayList<>();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      check(com(factory, FACTORY_CREATE_RENDERING_PARAMS, I_PP, out), "CreateRenderingParams");
      MemorySegment params = object(out);
      String line = String.format(Locale.ROOT,
          "DirectWrite default rendering params: gamma %.2f, enhanced contrast %.2f, "
              + "ClearType level %.2f, pixel geometry %d (0 flat, 1 RGB, 2 BGR), rendering mode %d",
          (float) com(params, PARAMS_GET_GAMMA, F_P),
          (float) com(params, PARAMS_GET_ENHANCED_CONTRAST, F_P),
          (float) com(params, PARAMS_GET_CLEARTYPE_LEVEL, F_P),
          (int) com(params, PARAMS_GET_PIXEL_GEOMETRY, I_P),
          (int) com(params, PARAMS_GET_RENDERING_MODE, I_P));
      if ((int) com(params, QUERY_INTERFACE, I_PPP, IID_IDWRITE_RENDERING_PARAMS2, out) == 0) {
        MemorySegment params2 = object(out);
        line += String.format(Locale.ROOT,
            ", greyscale enhanced contrast %.2f, grid fit mode %d (0 default)",
            (float) com(params2, PARAMS1_GET_GRAYSCALE_ENHANCED_CONTRAST, F_P),
            (int) com(params2, PARAMS2_GET_GRID_FIT_MODE, I_P));
        release(params2);
      }
      release(params);
      lines.add(line);

      MemorySegment value = arena.allocate(JAVA_INT);
      String gdi = "GDI: font smoothing ";
      call(SYSTEM_PARAMETERS_INFO, 0x004A, 0, value, 0);
      gdi += value.get(JAVA_INT, 0) != 0 ? "on" : "off";
      call(SYSTEM_PARAMETERS_INFO, 0x200A, 0, value, 0);
      gdi += ", type " + value.get(JAVA_INT, 0) + " (1 greyscale, 2 ClearType)";
      call(SYSTEM_PARAMETERS_INFO, 0x200C, 0, value, 0);
      gdi += ", ClearType contrast " + value.get(JAVA_INT, 0);
      call(SYSTEM_PARAMETERS_INFO, 0x2012, 0, value, 0);
      gdi += ", orientation " + value.get(JAVA_INT, 0) + " (0 BGR, 1 RGB)";
      lines.add(gdi);
    }
    return lines;
  }

  /** A string drawn by DirectWrite; the layout box's metrics come with it. */
  record Drawn(int[] rgb, int width, double advance) {}

  /** The advance of the whole string in pixels, trailing spaces included. */
  static double width(String family, double px, String text) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment layout = layout(arena, family, px, text);
      MemorySegment metrics = arena.allocate(36);
      check(com(layout, LAYOUT_GET_METRICS, I_PP, metrics), "GetMetrics");
      release(layout);
      return metrics.get(JAVA_FLOAT, 12);
    }
  }

  /**
   * Draws the string with its origin {@code fraction} pixels right of {@link #MARGIN} and its
   * baseline on row {@link #BASELINE}, over {@code bg}; returns {@code width} columns of the
   * bitmap, 0xRRGGBB.
   */
  static Drawn draw(String family, double px, String text, double fraction, int fg, int bg,
      int antialias) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment layout = layout(arena, family, px, text);
      MemorySegment metrics = arena.allocate(36);
      check(com(layout, LAYOUT_GET_METRICS, I_PP, metrics), "GetMetrics");
      double advance = metrics.get(JAVA_FLOAT, 12);
      MemorySegment line = arena.allocate(24);
      MemorySegment count = arena.allocate(JAVA_INT);
      check(com(layout, LAYOUT_GET_LINE_METRICS, GET_LINE_METRICS, line, 1, count),
          "GetLineMetrics");
      float lineBaseline = line.get(JAVA_FLOAT, 16);

      com(target, RT_BEGIN_DRAW, V_P);
      com(target, RT_SET_TEXT_ANTIALIAS_MODE, V_PI, antialias);
      com(target, RT_CLEAR, V_PP, color(arena, bg));
      MemorySegment origin = arena.allocate(POINT_2F);
      origin.set(JAVA_FLOAT, 0, (float) (MARGIN + fraction));
      origin.set(JAVA_FLOAT, 4, BASELINE - lineBaseline);
      com(target, RT_DRAW_TEXT_LAYOUT, DRAW_TEXT_LAYOUT, origin, layout, brush(fg), 0);
      check(com(target, RT_END_DRAW, I_PPP, MemorySegment.NULL, MemorySegment.NULL), "EndDraw");
      call(GDI_FLUSH);
      release(layout);
      int width = Math.min(WIDTH, (int) Math.ceil(advance + fraction) + 2 * MARGIN + (int) px);
      return new Drawn(read(width), width, advance);
    }
  }

  /** GDI's advance of the whole string at a whole-pixel em size. */
  static int gdiWidth(String family, int px, int quality, String text) {
    try (Arena arena = Arena.ofConfined()) {
      call(SELECT_OBJECT, dc, gdiFont(family, px, quality));
      MemorySegment size = arena.allocate(JAVA_INT, 2);
      call(GET_TEXT_EXTENT_W, dc, wide(arena, text), text.length(), size);
      return size.get(JAVA_INT, 0);
    }
  }

  /** Draws with GDI's ExtTextOutW: whole pixels only, no OpenType features. */
  static Drawn gdiDraw(String family, int px, int quality, String text, int fg, int bg) {
    int advance = gdiWidth(family, px, quality, text);
    int width = Math.min(WIDTH, advance + 2 * MARGIN + px);
    int pixel = 0xFF000000 | bg;
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < width; x++) {
        bits.setAtIndex(JAVA_INT, (long) y * WIDTH + x, pixel);
      }
    }
    try (Arena arena = Arena.ofConfined()) {
      call(SET_TEXT_COLOR, dc, (fg & 0xFF) << 16 | (fg & 0xFF00) | (fg >> 16 & 0xFF));
      call(SET_BK_MODE, dc, 1);
      call(SET_TEXT_ALIGN, dc, 24);
      call(EXT_TEXT_OUT_W, dc, MARGIN, BASELINE, 0, MemorySegment.NULL, wide(arena, text),
          text.length(), MemorySegment.NULL);
      call(GDI_FLUSH);
    }
    return new Drawn(read(width), width, advance);
  }

  static MemorySegment gdiFont(String family, int px, int quality) {
    return GDI_FONTS.computeIfAbsent(family + px + "/" + quality, k -> {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment font = (MemorySegment) call(CREATE_FONT_W, -px, 0, 0, 0, 400, 0, 0, 0, 1, 0,
            0, quality, 0, wide(arena, family));
        if (font.equals(MemorySegment.NULL)) {
          throw new IllegalStateException("CreateFontW failed for " + family);
        }
        return font;
      }
    });
  }

  static int[] read(int width) {
    int[] rgb = new int[width * HEIGHT];
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < width; x++) {
        rgb[y * width + x] = bits.getAtIndex(JAVA_INT, (long) y * WIDTH + x) & 0xFFFFFF;
      }
    }
    return rgb;
  }

  static MemorySegment layout(Arena arena, String family, double px, String text) {
    MemorySegment out = arena.allocate(ADDRESS);
    check(com(factory, FACTORY_CREATE_TEXT_LAYOUT, CREATE_TEXT_LAYOUT, wide(arena, text),
        text.length(), format(family, px), 100_000f, 1_000f, out), "CreateTextLayout");
    return object(out);
  }

  static MemorySegment format(String family, double px) {
    return FORMATS.computeIfAbsent(family + "/" + px, k -> {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment index = arena.allocate(JAVA_INT);
        MemorySegment exists = arena.allocate(JAVA_INT);
        check(com(collection, COLLECTION_FIND_FAMILY_NAME, I_PPPP, wide(arena, family), index,
            exists), "FindFamilyName");
        if (exists.get(JAVA_INT, 0) == 0) {
          throw new IllegalStateException("DirectWrite does not find the family " + family);
        }
        MemorySegment out = arena.allocate(ADDRESS);
        check(com(factory, FACTORY_CREATE_TEXT_FORMAT, CREATE_TEXT_FORMAT, wide(arena, family),
            collection, 400, 0, 5, (float) px, wide(arena, "en-us"), out), "CreateTextFormat");
        MemorySegment format = object(out);
        check(com(format, FORMAT_SET_WORD_WRAPPING, I_PI_COM, 1), "SetWordWrapping");
        return format;
      }
    });
  }

  static MemorySegment brush(int rgb) {
    return BRUSHES.computeIfAbsent(rgb, k -> {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(com(target, RT_CREATE_SOLID_COLOR_BRUSH, I_PPPP, color(arena, rgb),
            MemorySegment.NULL, out), "CreateSolidColorBrush");
        return object(out);
      }
    });
  }

  /** D2D1_COLOR_F: the sRGB components as floats, then alpha. */
  static MemorySegment color(Arena arena, int rgb) {
    return arena.allocateFrom(JAVA_FLOAT, (rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f,
        (rgb & 0xFF) / 255f, 1f);
  }

  static MemorySegment wide(Arena arena, String s) {
    MemorySegment w = arena.allocate(JAVA_CHAR, s.length() + 1L);
    w.copyFrom(MemorySegment.ofArray(s.toCharArray()));
    return w;
  }

  static MemorySegment guid(String text) {
    String hex = text.replace("-", "");
    MemorySegment g = GLOBAL.allocate(16, 4);
    g.set(JAVA_INT, 0, (int) Long.parseLong(hex.substring(0, 8), 16));
    g.set(JAVA_SHORT, 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
    g.set(JAVA_SHORT, 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
    for (int i = 0; i < 8; i++) {
      g.set(JAVA_BYTE, 8 + i,
          (byte) Integer.parseInt(hex.substring(16 + 2 * i, 18 + 2 * i), 16));
    }
    return g;
  }

  static MemorySegment object(MemorySegment out) {
    MemorySegment object = out.get(ADDRESS, 0);
    if (object.equals(MemorySegment.NULL)) {
      throw new IllegalStateException("null COM object");
    }
    return object.reinterpret(8);
  }

  static void release(MemorySegment object) {
    com(object, RELEASE, I_P);
  }

  /** Calls the method in the object's vtable slot. */
  static Object com(MemorySegment object, int slot, MethodHandle handle, Object... args) {
    MemorySegment vtable = object.get(ADDRESS, 0).reinterpret(8L * (slot + 1));
    Object[] all = new Object[args.length + 2];
    all[0] = vtable.getAtIndex(ADDRESS, slot);
    all[1] = object;
    System.arraycopy(args, 0, all, 2, args.length);
    return call(handle, all);
  }

  static int check(Object hresult, String function) {
    int hr = (int) hresult;
    if (hr < 0) {
      throw new IllegalStateException(function + " failed with HRESULT 0x"
          + Integer.toHexString(hr));
    }
    return hr;
  }

  static Object call(MethodHandle handle, Object... args) {
    return FreeType.call(handle, args);
  }

  static MethodHandle handle(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
    return FreeType.handle(lib, name, descriptor);
  }
}
