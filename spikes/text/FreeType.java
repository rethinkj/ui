import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/** Handwritten FFM bindings for the few FreeType functions the spike needs. */
final class FreeType {

  private FreeType() {}

  static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
  static final Linker LINKER = Linker.nativeLinker();

  /** FT_Long, FT_Pos, FT_Fixed and FT_F26Dot6 are C longs: 64 bits on Linux, 32 on Windows. */
  static final ValueLayout C_LONG = (ValueLayout) LINKER.canonicalLayouts().get("long");

  static final SymbolLookup LIB =
      SymbolLookup.libraryLookup(
          System.getProperty("freetype", WINDOWS ? "freetype.dll" : "libfreetype.so.6"),
          Arena.global());

  // FreeType's public structs, laid out with the platform's C long. Only the leading fields that
  // the spike reads are declared for FT_FaceRec and FT_GlyphSlotRec: they are never allocated here.
  static final StructLayout GENERIC = struct(ADDRESS.withName("data"), ADDRESS.withName("fin"));
  static final StructLayout VECTOR = struct(C_LONG.withName("x"), C_LONG.withName("y"));
  static final StructLayout BITMAP =
      struct(
          JAVA_INT.withName("rows"),
          JAVA_INT.withName("width"),
          JAVA_INT.withName("pitch"),
          ADDRESS.withName("buffer"),
          JAVA_SHORT.withName("num_grays"),
          JAVA_BYTE.withName("pixel_mode"),
          JAVA_BYTE.withName("palette_mode"),
          ADDRESS.withName("palette"));
  static final StructLayout FACE =
      struct(
          C_LONG.withName("num_faces"),
          C_LONG.withName("face_index"),
          C_LONG.withName("face_flags"),
          C_LONG.withName("style_flags"),
          C_LONG.withName("num_glyphs"),
          ADDRESS.withName("family_name"),
          ADDRESS.withName("style_name"),
          JAVA_INT.withName("num_fixed_sizes"),
          ADDRESS.withName("available_sizes"),
          JAVA_INT.withName("num_charmaps"),
          ADDRESS.withName("charmaps"),
          GENERIC.withName("generic"),
          MemoryLayout.sequenceLayout(4, C_LONG).withName("bbox"),
          JAVA_SHORT.withName("units_per_EM"),
          JAVA_SHORT.withName("ascender"),
          JAVA_SHORT.withName("descender"),
          JAVA_SHORT.withName("height"),
          JAVA_SHORT.withName("max_advance_width"),
          JAVA_SHORT.withName("max_advance_height"),
          JAVA_SHORT.withName("underline_position"),
          JAVA_SHORT.withName("underline_thickness"),
          ADDRESS.withName("glyph"));
  static final StructLayout GLYPH_SLOT =
      struct(
          ADDRESS.withName("library"),
          ADDRESS.withName("face"),
          ADDRESS.withName("next"),
          JAVA_INT.withName("glyph_index"),
          GENERIC.withName("generic"),
          MemoryLayout.sequenceLayout(8, C_LONG).withName("metrics"),
          C_LONG.withName("linearHoriAdvance"),
          C_LONG.withName("linearVertAdvance"),
          VECTOR.withName("advance"),
          JAVA_INT.withName("format"),
          BITMAP.withName("bitmap"),
          JAVA_INT.withName("bitmap_left"),
          JAVA_INT.withName("bitmap_top"));

  static final long FACE_UNITS_PER_EM = offset(FACE, "units_per_EM");
  static final long FACE_ASCENDER = offset(FACE, "ascender");
  static final long FACE_DESCENDER = offset(FACE, "descender");
  static final long FACE_GLYPH = offset(FACE, "glyph");
  static final long SLOT_BITMAP = offset(GLYPH_SLOT, "bitmap");
  static final long SLOT_LEFT = offset(GLYPH_SLOT, "bitmap_left");
  static final long SLOT_TOP = offset(GLYPH_SLOT, "bitmap_top");
  static final long BITMAP_ROWS = offset(BITMAP, "rows");
  static final long BITMAP_WIDTH = offset(BITMAP, "width");
  static final long BITMAP_PITCH = offset(BITMAP, "pitch");
  static final long BITMAP_BUFFER = offset(BITMAP, "buffer");
  static final long BITMAP_PIXEL_MODE = offset(BITMAP, "pixel_mode");

  static final int LOAD_NO_HINTING = 1 << 1;
  static final int LOAD_NO_BITMAP = 1 << 3;
  static final int LOAD_FORCE_AUTOHINT = 1 << 5;
  static final int LOAD_TARGET_LIGHT = 1 << 16;
  static final int LOAD_TARGET_LCD = 3 << 16;
  static final int RENDER_MODE_NORMAL = 0;
  static final int RENDER_MODE_LCD = 3;
  static final int PIXEL_MODE_GRAY = 2;
  static final int PIXEL_MODE_LCD = 5;
  static final int LCD_FILTER_DEFAULT = 1;

  static final FunctionDescriptor INIT_FREETYPE = FunctionDescriptor.of(JAVA_INT, ADDRESS);
  static final FunctionDescriptor LIBRARY_VERSION =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor NEW_MEMORY_FACE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, C_LONG, C_LONG, ADDRESS);
  static final FunctionDescriptor SET_CHAR_SIZE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, C_LONG, C_LONG, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor SET_TRANSFORM =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor LOAD_GLYPH =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor RENDER_GLYPH = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor LIBRARY_SET_LCD_FILTER =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
  static final FunctionDescriptor PROPERTY_SET =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

  static final MethodHandle FT_INIT_FREETYPE = handle("FT_Init_FreeType", INIT_FREETYPE);
  static final MethodHandle FT_LIBRARY_VERSION = handle("FT_Library_Version", LIBRARY_VERSION);
  static final MethodHandle FT_NEW_MEMORY_FACE = handle("FT_New_Memory_Face", NEW_MEMORY_FACE);
  static final MethodHandle FT_SET_CHAR_SIZE = handle("FT_Set_Char_Size", SET_CHAR_SIZE);
  static final MethodHandle FT_SET_TRANSFORM = handle("FT_Set_Transform", SET_TRANSFORM);
  static final MethodHandle FT_LOAD_GLYPH = handle("FT_Load_Glyph", LOAD_GLYPH);
  static final MethodHandle FT_RENDER_GLYPH = handle("FT_Render_Glyph", RENDER_GLYPH);
  static final MethodHandle FT_LIBRARY_SET_LCD_FILTER =
      handle("FT_Library_SetLcdFilter", LIBRARY_SET_LCD_FILTER);
  static final MethodHandle FT_PROPERTY_SET = handle("FT_Property_Set", PROPERTY_SET);

  /** A rendered glyph: one coverage byte per pixel, or three (R, G, B) for LCD. */
  record Bitmap(int left, int top, int width, int height, int channels, byte[] coverage) {}

  static MemorySegment init() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      check((int) call(FT_INIT_FREETYPE, out), "FT_Init_FreeType");
      return out.get(ADDRESS, 0);
    }
  }

  static String version(MemorySegment library) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment v = arena.allocate(JAVA_INT, 3);
      call(FT_LIBRARY_VERSION, library, v, v.asSlice(4), v.asSlice(8));
      return v.getAtIndex(JAVA_INT, 0) + "." + v.getAtIndex(JAVA_INT, 1) + "." + v.getAtIndex(JAVA_INT, 2);
    }
  }

  /** Returns FreeType's error code: 0, or 7 (unimplemented) when built without ClearType-style filtering. */
  static int setLcdFilter(MemorySegment library, int filter) {
    return (int) call(FT_LIBRARY_SET_LCD_FILTER, library, filter);
  }

  static int setProperty(MemorySegment library, String module, String property, int value) {
    try (Arena arena = Arena.ofConfined()) {
      return (int) call(FT_PROPERTY_SET, library, arena.allocateFrom(module),
          arena.allocateFrom(property), arena.allocateFrom(JAVA_INT, value));
    }
  }

  static int setProperty(MemorySegment library, String module, String property, boolean value) {
    try (Arena arena = Arena.ofConfined()) {
      return (int) call(FT_PROPERTY_SET, library, arena.allocateFrom(module),
          arena.allocateFrom(property), arena.allocateFrom(JAVA_BYTE, (byte) (value ? 1 : 0)));
    }
  }

  /** The font data has to stay alive as long as the face. */
  static MemorySegment newFace(MemorySegment library, MemorySegment data) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      check((int) call(FT_NEW_MEMORY_FACE, library, data, cLong(data.byteSize()), cLong(0), out),
          "FT_New_Memory_Face");
      return out.get(ADDRESS, 0).reinterpret(FACE.byteSize());
    }
  }

  /** Sets the size in 1/64 pixels; 72 dpi makes points equal pixels. */
  static void setPixelSize(MemorySegment face, int size26) {
    check((int) call(FT_SET_CHAR_SIZE, face, cLong(0), cLong(size26), 72, 72), "FT_Set_Char_Size");
  }

  /** Loads and renders one glyph, shifted right by {@code dx26} 1/64 pixels after hinting. */
  static Bitmap render(MemorySegment face, int glyph, int loadFlags, boolean lcd, int dx26) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment delta = arena.allocate(VECTOR);
      setCLong(delta, 0, dx26);
      setCLong(delta, C_LONG.byteSize(), 0);
      call(FT_SET_TRANSFORM, face, MemorySegment.NULL, delta);
    }
    check((int) call(FT_LOAD_GLYPH, face, glyph, loadFlags | LOAD_NO_BITMAP), "FT_Load_Glyph");
    MemorySegment slot = face.get(ADDRESS, FACE_GLYPH).reinterpret(GLYPH_SLOT.byteSize());
    check((int) call(FT_RENDER_GLYPH, slot, lcd ? RENDER_MODE_LCD : RENDER_MODE_NORMAL),
        "FT_Render_Glyph");

    int rows = slot.get(JAVA_INT, SLOT_BITMAP + BITMAP_ROWS);
    int width = slot.get(JAVA_INT, SLOT_BITMAP + BITMAP_WIDTH);
    int pitch = slot.get(JAVA_INT, SLOT_BITMAP + BITMAP_PITCH);
    int mode = slot.get(JAVA_BYTE, SLOT_BITMAP + BITMAP_PIXEL_MODE);
    if (rows > 0 && (pitch < width || mode != (lcd ? PIXEL_MODE_LCD : PIXEL_MODE_GRAY))) {
      throw new IllegalStateException("unexpected bitmap: mode " + mode + ", pitch " + pitch);
    }
    int channels = lcd ? 3 : 1;
    byte[] coverage = new byte[width * rows];
    MemorySegment buffer =
        slot.get(ADDRESS, SLOT_BITMAP + BITMAP_BUFFER).reinterpret((long) pitch * rows);
    for (int y = 0; y < rows; y++) {
      MemorySegment.copy(buffer, JAVA_BYTE, (long) y * pitch, coverage, y * width, width);
    }
    return new Bitmap(slot.get(JAVA_INT, SLOT_LEFT), slot.get(JAVA_INT, SLOT_TOP),
        width / channels, rows, channels, coverage);
  }

  static void check(int error, String function) {
    if (error != 0) {
      throw new IllegalStateException(function + " failed with FreeType error " + error);
    }
  }

  static Object cLong(long value) {
    return C_LONG.byteSize() == 8 ? (Object) value : (Object) Math.toIntExact(value);
  }

  static void setCLong(MemorySegment segment, long offset, long value) {
    if (C_LONG.byteSize() == 8) {
      segment.set(JAVA_LONG, offset, value);
    } else {
      segment.set(JAVA_INT, offset, Math.toIntExact(value));
    }
  }

  static Object call(MethodHandle handle, Object... args) {
    try {
      return handle.invokeWithArguments(args);
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  static MethodHandle handle(String name, FunctionDescriptor descriptor) {
    return handle(LIB, name, descriptor);
  }

  static MethodHandle handle(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(lib.findOrThrow(name), descriptor);
  }

  /** A C struct: inserts the padding a C compiler would, so field offsets follow the platform. */
  static StructLayout struct(MemoryLayout... fields) {
    List<MemoryLayout> laid = new ArrayList<>();
    long size = 0;
    long align = 1;
    for (MemoryLayout field : fields) {
      long pad = (field.byteAlignment() - size % field.byteAlignment()) % field.byteAlignment();
      if (pad > 0) {
        laid.add(MemoryLayout.paddingLayout(pad));
      }
      laid.add(field);
      size += pad + field.byteSize();
      align = Math.max(align, field.byteAlignment());
    }
    if (size % align != 0) {
      laid.add(MemoryLayout.paddingLayout(align - size % align));
    }
    return MemoryLayout.structLayout(laid.toArray(MemoryLayout[]::new));
  }

  static long offset(StructLayout layout, String field) {
    return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
  }
}
