package com.rethinkj.ui.text.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import com.rethinkj.ui.natives.Natives;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * Raw bindings for the part of FreeType 2.15 that text needs: a face opened from memory, its
 * characters, and glyphs loaded and rendered at a size and a sub-pixel offset. Constants, struct
 * layouts written by hand and the descriptor of every function as a {@code static final}
 * constant, and one static method per function that takes and returns raw handles and error codes.
 * Nothing here checks anything, and nothing here is thread-safe: FreeType wants a library and its
 * faces used by one thread at a time.
 *
 * <p>FreeType's {@code FT_Long}, {@code FT_Pos}, {@code FT_Fixed} and {@code FT_F26Dot6} are C
 * longs: 64 bits on Linux, 32 on Windows. That is handled here and only here: {@link #C_LONG} is
 * the platform's layout, which the descriptors and struct layouts use; the method handles take a
 * Java {@code long} for it on every platform; {@link #getLong} and {@link #setLong} read and write
 * it in structs.
 *
 * <p>The library is loaded by the nested holder of the method handles, on the first call of a
 * function. Callers load it through {@link Natives} before that, so that a missing library is an
 * {@link UnsatisfiedLinkError} with an explanation rather than a failed class initialisation.
 */
public final class FreeType {

  private FreeType() {}

  /** A C {@code long}. The name tells {@code Fn.bind} which parameters to widen to a Java long. */
  public static final ValueLayout C_LONG =
      ((ValueLayout) Linker.nativeLinker().canonicalLayouts().get("long")).withName("C long");

  // ---- constants ---------------------------------------------------------------------------

  public static final long FACE_FLAG_SCALABLE = 1L << 0;
  /** {@code FT_FACE_FLAG_COLOR}: the face has colour glyphs (COLR, CBDT, sbix or SVG tables). */
  public static final long FACE_FLAG_COLOR = 1L << 14;

  public static final long STYLE_FLAG_ITALIC = 1L << 0;
  public static final long STYLE_FLAG_BOLD = 1L << 1;

  public static final int LOAD_DEFAULT = 0;
  public static final int LOAD_NO_HINTING = 1 << 1;
  public static final int LOAD_NO_BITMAP = 1 << 3;
  /** {@code FT_LOAD_FORCE_AUTOHINT}: FreeType's auto-hinter instead of the font's own hints. */
  public static final int LOAD_FORCE_AUTOHINT = 1 << 5;
  /** {@code FT_LOAD_TARGET_LIGHT}: hinted vertically only, which keeps the advances. */
  public static final int LOAD_TARGET_LIGHT = 1 << 16;

  public static final int RENDER_MODE_NORMAL = 0;
  public static final int RENDER_MODE_LIGHT = 1;

  public static final int PIXEL_MODE_GRAY = 2;

  // ---- structs -----------------------------------------------------------------------------

  public static final StructLayout GENERIC =
      struct(ADDRESS.withName("data"), ADDRESS.withName("finalizer"));

  public static final StructLayout VECTOR = struct(C_LONG.withName("x"), C_LONG.withName("y"));

  public static final StructLayout BITMAP = struct(
      JAVA_INT.withName("rows"),
      JAVA_INT.withName("width"),
      JAVA_INT.withName("pitch"),
      ADDRESS.withName("buffer"),
      JAVA_SHORT.withName("num_grays"),
      JAVA_BYTE.withName("pixel_mode"),
      JAVA_BYTE.withName("palette_mode"),
      ADDRESS.withName("palette"));

  /**
   * {@code FT_FaceRec} up to {@code charmap}: the public fields. The rest is FreeType's own; a face
   * is never allocated here, only read.
   */
  public static final StructLayout FACE = struct(
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
      ADDRESS.withName("glyph"),
      ADDRESS.withName("size"),
      ADDRESS.withName("charmap"));

  /** {@code FT_GlyphSlotRec} up to {@code bitmap_top}, the fields a rasteriser reads. */
  public static final StructLayout GLYPH_SLOT = struct(
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

  public static final long FACE_NUM_FACES = offset(FACE, "num_faces");
  public static final long FACE_FACE_FLAGS = offset(FACE, "face_flags");
  public static final long FACE_STYLE_FLAGS = offset(FACE, "style_flags");
  public static final long FACE_NUM_GLYPHS = offset(FACE, "num_glyphs");
  public static final long FACE_FAMILY_NAME = offset(FACE, "family_name");
  public static final long FACE_STYLE_NAME = offset(FACE, "style_name");
  public static final long FACE_UNITS_PER_EM = offset(FACE, "units_per_EM");
  public static final long FACE_GLYPH = offset(FACE, "glyph");
  public static final long SLOT_BITMAP = offset(GLYPH_SLOT, "bitmap");
  public static final long SLOT_BITMAP_LEFT = offset(GLYPH_SLOT, "bitmap_left");
  public static final long SLOT_BITMAP_TOP = offset(GLYPH_SLOT, "bitmap_top");
  public static final long BITMAP_ROWS = offset(BITMAP, "rows");
  public static final long BITMAP_WIDTH = offset(BITMAP, "width");
  public static final long BITMAP_PITCH = offset(BITMAP, "pitch");
  public static final long BITMAP_BUFFER = offset(BITMAP, "buffer");
  public static final long BITMAP_PIXEL_MODE = offset(BITMAP, "pixel_mode");

  // ---- functions ---------------------------------------------------------------------------

  /** {@code FT_Error FT_Init_FreeType(FT_Library *alibrary)}. */
  public static final FunctionDescriptor INIT_FREETYPE = FunctionDescriptor.of(JAVA_INT, ADDRESS);

  /**
   * {@code FT_Error FT_New_Memory_Face(FT_Library, const FT_Byte *file_base, FT_Long file_size,
   * FT_Long face_index, FT_Face *aface)}.
   */
  public static final FunctionDescriptor NEW_MEMORY_FACE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, C_LONG, C_LONG, ADDRESS);

  /** {@code FT_Error FT_Done_Face(FT_Face)}. */
  public static final FunctionDescriptor DONE_FACE = FunctionDescriptor.of(JAVA_INT, ADDRESS);

  /**
   * {@code FT_Error FT_Set_Char_Size(FT_Face, FT_F26Dot6 char_width, FT_F26Dot6 char_height,
   * FT_UInt horz_resolution, FT_UInt vert_resolution)}.
   */
  public static final FunctionDescriptor SET_CHAR_SIZE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, C_LONG, C_LONG, JAVA_INT, JAVA_INT);

  /** {@code void FT_Set_Transform(FT_Face, FT_Matrix *matrix, FT_Vector *delta)}. */
  public static final FunctionDescriptor SET_TRANSFORM =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

  /** {@code FT_Error FT_Load_Glyph(FT_Face, FT_UInt glyph_index, FT_Int32 load_flags)}. */
  public static final FunctionDescriptor LOAD_GLYPH =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);

  /** {@code FT_Error FT_Render_Glyph(FT_GlyphSlot, FT_Render_Mode)}. */
  public static final FunctionDescriptor RENDER_GLYPH =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

  /** {@code FT_UInt FT_Get_Char_Index(FT_Face, FT_ULong charcode)}: 0 where the face lacks it. */
  public static final FunctionDescriptor GET_CHAR_INDEX =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, C_LONG);

  /** {@code FT_ULong FT_Get_First_Char(FT_Face, FT_UInt *agindex)}. */
  public static final FunctionDescriptor GET_FIRST_CHAR =
      FunctionDescriptor.of(C_LONG, ADDRESS, ADDRESS);

  /** {@code FT_ULong FT_Get_Next_Char(FT_Face, FT_ULong char_code, FT_UInt *agindex)}. */
  public static final FunctionDescriptor GET_NEXT_CHAR =
      FunctionDescriptor.of(C_LONG, ADDRESS, C_LONG, ADDRESS);

  /** The method handles; initialising this class loads the library. */
  private static final class Fn {
    private static final SymbolLookup LIBRARY = Natives.lookup("freetype");

    static final MethodHandle INIT_FREETYPE = bind("FT_Init_FreeType", FreeType.INIT_FREETYPE);
    static final MethodHandle NEW_MEMORY_FACE =
        bind("FT_New_Memory_Face", FreeType.NEW_MEMORY_FACE);
    static final MethodHandle DONE_FACE = bind("FT_Done_Face", FreeType.DONE_FACE);
    static final MethodHandle SET_CHAR_SIZE = bind("FT_Set_Char_Size", FreeType.SET_CHAR_SIZE);
    static final MethodHandle SET_TRANSFORM = bind("FT_Set_Transform", FreeType.SET_TRANSFORM);
    static final MethodHandle LOAD_GLYPH = bind("FT_Load_Glyph", FreeType.LOAD_GLYPH);
    static final MethodHandle RENDER_GLYPH = bind("FT_Render_Glyph", FreeType.RENDER_GLYPH);
    static final MethodHandle GET_CHAR_INDEX = bind("FT_Get_Char_Index", FreeType.GET_CHAR_INDEX);
    static final MethodHandle GET_FIRST_CHAR = bind("FT_Get_First_Char", FreeType.GET_FIRST_CHAR);
    static final MethodHandle GET_NEXT_CHAR = bind("FT_Get_Next_Char", FreeType.GET_NEXT_CHAR);

    /** A downcall whose C longs are Java longs, also where a C long has 32 bits. */
    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
      MemorySegment symbol = LIBRARY
          .find(name)
          .orElseThrow(() -> new UnsatisfiedLinkError("The freetype library has no function " + name
              + ". The natives module does not" + " match this version of rethinkj-ui-core."));
      MethodHandle handle = Linker.nativeLinker().downcallHandle(symbol, descriptor);
      MethodType type = handle.type();
      List<MemoryLayout> arguments = descriptor.argumentLayouts();
      for (int i = 0; i < arguments.size(); i++) {
        if (arguments.get(i).equals(C_LONG)) {
          type = type.changeParameterType(i, long.class);
        }
      }
      if (descriptor.returnLayout().filter(C_LONG::equals).isPresent()) {
        type = type.changeReturnType(long.class);
      }
      // Narrows long to int where C_LONG is 32 bits, and widens the result; every value fits.
      return MethodHandles.explicitCastArguments(handle, type);
    }
  }

  public static int initFreeType(MemorySegment libraryOut) {
    try {
      return (int) Fn.INIT_FREETYPE.invokeExact(libraryOut);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** The bytes have to stay valid until {@link #doneFace}. */
  public static int newMemoryFace(
      MemorySegment library, MemorySegment data, long size, long faceIndex, MemorySegment faceOut) {
    try {
      return (int) Fn.NEW_MEMORY_FACE.invokeExact(library, data, size, faceIndex, faceOut);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static int doneFace(MemorySegment face) {
    try {
      return (int) Fn.DONE_FACE.invokeExact(face);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Sizes in 1/64 points; at 72 dots per inch a point is a pixel. */
  public static int setCharSize(
      MemorySegment face, long width26, long height26, int horizontalDpi, int verticalDpi) {
    try {
      return (int)
          Fn.SET_CHAR_SIZE.invokeExact(face, width26, height26, horizontalDpi, verticalDpi);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** NULL for the identity matrix or a zero delta. The delta is in 1/64 pixels. */
  public static void setTransform(MemorySegment face, MemorySegment matrix, MemorySegment delta) {
    try {
      Fn.SET_TRANSFORM.invokeExact(face, matrix, delta);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static int loadGlyph(MemorySegment face, int glyph, int loadFlags) {
    try {
      return (int) Fn.LOAD_GLYPH.invokeExact(face, glyph, loadFlags);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static int renderGlyph(MemorySegment slot, int renderMode) {
    try {
      return (int) Fn.RENDER_GLYPH.invokeExact(slot, renderMode);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static int getCharIndex(MemorySegment face, long charCode) {
    try {
      return (int) Fn.GET_CHAR_INDEX.invokeExact(face, charCode);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** The face's first character in its charmap; the glyph goes to {@code glyphOut}, 0 if none. */
  public static long getFirstChar(MemorySegment face, MemorySegment glyphOut) {
    try {
      return (long) Fn.GET_FIRST_CHAR.invokeExact(face, glyphOut);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** The character after {@code charCode}; the glyph goes to {@code glyphOut}, 0 after the last. */
  public static long getNextChar(MemorySegment face, long charCode, MemorySegment glyphOut) {
    try {
      return (long) Fn.GET_NEXT_CHAR.invokeExact(face, charCode, glyphOut);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  // ---- C long in structs -------------------------------------------------------------------

  public static long getLong(MemorySegment struct, long offset) {
    return C_LONG.byteSize() == 8 ? struct.get(JAVA_LONG, offset) : struct.get(JAVA_INT, offset);
  }

  public static void setLong(MemorySegment struct, long offset, long value) {
    if (C_LONG.byteSize() == 8) {
      struct.set(JAVA_LONG, offset, value);
    } else {
      struct.set(JAVA_INT, offset, Math.toIntExact(value));
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  /** A C struct: inserts the padding a C compiler would, so that offsets follow the platform. */
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

  /** A downcall throws only what the FFM API throws; this keeps its type where it can. */
  static RuntimeException rethrow(Throwable t) {
    if (t instanceof RuntimeException e) {
      return e;
    }
    if (t instanceof Error e) {
      throw e;
    }
    return new IllegalStateException(t);
  }
}
