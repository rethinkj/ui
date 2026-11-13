package com.rethinkj.ui.text.ffi;

import static com.rethinkj.ui.text.ffi.FreeType.offset;
import static com.rethinkj.ui.text.ffi.FreeType.rethrow;
import static com.rethinkj.ui.text.ffi.FreeType.struct;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.rethinkj.ui.natives.Natives;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Raw bindings for the part of HarfBuzz 15 that shaping needs, with HarfBuzz's own OpenType font
 * functions: the library is built without FreeType. In the style of {@link FreeType}: constants,
 * struct layouts and descriptors as {@code static final} constants, one static method per function
 * on raw handles, no checks. HarfBuzz has no C {@code long} in these signatures.
 *
 * <p>HarfBuzz objects are reference counted; each {@code create} here is matched by one {@code
 * destroy}. A font that is no longer changed may shape on several threads at once; a buffer
 * belongs to one thread.
 */
public final class HarfBuzz {

  private HarfBuzz() {}

  // ---- constants ---------------------------------------------------------------------------

  /** {@code HB_MEMORY_MODE_READONLY}: HarfBuzz reads the bytes in place and never writes them. */
  public static final int MEMORY_MODE_READONLY = 1;

  /** {@code HB_DIRECTION_LTR}. */
  public static final int DIRECTION_LTR = 4;

  /** {@code HB_DIRECTION_RTL}. */
  public static final int DIRECTION_RTL = 5;

  // ---- structs -----------------------------------------------------------------------------

  /** {@code hb_glyph_info_t}. */
  public static final StructLayout GLYPH_INFO = struct(
      JAVA_INT.withName("codepoint"),
      JAVA_INT.withName("mask"),
      JAVA_INT.withName("cluster"),
      JAVA_INT.withName("var1"),
      JAVA_INT.withName("var2"));

  /** {@code hb_glyph_position_t}, in the font's scale. */
  public static final StructLayout GLYPH_POSITION = struct(
      JAVA_INT.withName("x_advance"),
      JAVA_INT.withName("y_advance"),
      JAVA_INT.withName("x_offset"),
      JAVA_INT.withName("y_offset"),
      JAVA_INT.withName("var"));

  /** {@code hb_feature_t}. */
  public static final StructLayout FEATURE = struct(
      JAVA_INT.withName("tag"),
      JAVA_INT.withName("value"),
      JAVA_INT.withName("start"),
      JAVA_INT.withName("end"));

  /** {@code hb_font_extents_t}: three positions and nine reserved ones. */
  public static final StructLayout FONT_EXTENTS = struct(
      JAVA_INT.withName("ascender"),
      JAVA_INT.withName("descender"),
      JAVA_INT.withName("line_gap"),
      MemoryLayout.sequenceLayout(9, JAVA_INT).withName("reserved"));

  /** The glyph id: after shaping, {@code codepoint} holds the glyph, 0 for a missing one. */
  public static final long INFO_GLYPH = offset(GLYPH_INFO, "codepoint");

  public static final long INFO_CLUSTER = offset(GLYPH_INFO, "cluster");
  public static final long POSITION_X_ADVANCE = offset(GLYPH_POSITION, "x_advance");
  public static final long POSITION_X_OFFSET = offset(GLYPH_POSITION, "x_offset");
  public static final long POSITION_Y_OFFSET = offset(GLYPH_POSITION, "y_offset");
  public static final long EXTENTS_ASCENDER = offset(FONT_EXTENTS, "ascender");
  public static final long EXTENTS_DESCENDER = offset(FONT_EXTENTS, "descender");
  public static final long EXTENTS_LINE_GAP = offset(FONT_EXTENTS, "line_gap");

  // ---- functions ---------------------------------------------------------------------------

  /** {@code hb_blob_t *hb_blob_create(const char *data, unsigned int length, hb_memory_mode_t
   * mode, void *user_data, hb_destroy_func_t destroy)}. */
  public static final FunctionDescriptor BLOB_CREATE =
      FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);

  /** {@code hb_face_t *hb_face_create(hb_blob_t *blob, unsigned int index)}. */
  public static final FunctionDescriptor FACE_CREATE =
      FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT);

  /** {@code unsigned int hb_face_get_upem(const hb_face_t *)} and {@code
   * hb_face_get_glyph_count}. */
  public static final FunctionDescriptor FACE_GET_UNSIGNED =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);

  /** {@code hb_font_t *hb_font_create(hb_face_t *face)}. */
  public static final FunctionDescriptor FONT_CREATE = FunctionDescriptor.of(ADDRESS, ADDRESS);

  /** {@code hb_bool_t hb_font_get_h_extents(hb_font_t *, hb_font_extents_t *)}. */
  public static final FunctionDescriptor FONT_GET_H_EXTENTS =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);

  /** {@code hb_buffer_t *hb_buffer_create(void)}. */
  public static final FunctionDescriptor BUFFER_CREATE = FunctionDescriptor.of(ADDRESS);

  /** {@code void hb_buffer_add_utf16(hb_buffer_t *, const uint16_t *text, int text_length,
   * unsigned int item_offset, int item_length)}. */
  public static final FunctionDescriptor BUFFER_ADD_UTF16 =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT);

  /** {@code void hb_buffer_set_direction(hb_buffer_t *, hb_direction_t)}. */
  public static final FunctionDescriptor BUFFER_SET_DIRECTION =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);

  /** {@code hb_bool_t hb_buffer_allocation_successful(hb_buffer_t *)}. */
  public static final FunctionDescriptor BUFFER_ALLOCATION_SUCCESSFUL =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);

  /** {@code hb_glyph_info_t *hb_buffer_get_glyph_infos(hb_buffer_t *, unsigned int *length)} and
   * {@code hb_buffer_get_glyph_positions}. */
  public static final FunctionDescriptor BUFFER_GET_GLYPHS =
      FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);

  /** {@code void hb_shape(hb_font_t *, hb_buffer_t *, const hb_feature_t *features, unsigned int
   * num_features)}. */
  public static final FunctionDescriptor SHAPE =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT);

  /** {@code hb_bool_t hb_feature_from_string(const char *str, int len, hb_feature_t *feature)}. */
  public static final FunctionDescriptor FEATURE_FROM_STRING =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);

  /** {@code void hb_*_destroy(hb_*_t *)} and {@code hb_buffer_guess_segment_properties}. */
  public static final FunctionDescriptor VOID_HANDLE = FunctionDescriptor.ofVoid(ADDRESS);

  /** The method handles; initialising this class loads the library. */
  private static final class Fn {
    private static final SymbolLookup LIBRARY = Natives.lookup("harfbuzz");

    static final MethodHandle BLOB_CREATE = bind("hb_blob_create", HarfBuzz.BLOB_CREATE);
    static final MethodHandle BLOB_DESTROY = bind("hb_blob_destroy", VOID_HANDLE);
    static final MethodHandle FACE_CREATE = bind("hb_face_create", HarfBuzz.FACE_CREATE);
    static final MethodHandle FACE_GET_UPEM = bind("hb_face_get_upem", FACE_GET_UNSIGNED);
    static final MethodHandle FACE_GET_GLYPH_COUNT =
        bind("hb_face_get_glyph_count", FACE_GET_UNSIGNED);
    static final MethodHandle FACE_DESTROY = bind("hb_face_destroy", VOID_HANDLE);
    static final MethodHandle FONT_CREATE = bind("hb_font_create", HarfBuzz.FONT_CREATE);
    static final MethodHandle FONT_GET_H_EXTENTS =
        bind("hb_font_get_h_extents", HarfBuzz.FONT_GET_H_EXTENTS);
    static final MethodHandle FONT_DESTROY = bind("hb_font_destroy", VOID_HANDLE);
    static final MethodHandle BUFFER_CREATE = bind("hb_buffer_create", HarfBuzz.BUFFER_CREATE);
    static final MethodHandle BUFFER_ADD_UTF16 =
        bind("hb_buffer_add_utf16", HarfBuzz.BUFFER_ADD_UTF16);
    static final MethodHandle BUFFER_SET_DIRECTION =
        bind("hb_buffer_set_direction", HarfBuzz.BUFFER_SET_DIRECTION);
    static final MethodHandle BUFFER_GUESS_SEGMENT_PROPERTIES =
        bind("hb_buffer_guess_segment_properties", VOID_HANDLE);
    static final MethodHandle BUFFER_ALLOCATION_SUCCESSFUL =
        bind("hb_buffer_allocation_successful", HarfBuzz.BUFFER_ALLOCATION_SUCCESSFUL);
    static final MethodHandle BUFFER_GET_GLYPH_INFOS =
        bind("hb_buffer_get_glyph_infos", BUFFER_GET_GLYPHS);
    static final MethodHandle BUFFER_GET_GLYPH_POSITIONS =
        bind("hb_buffer_get_glyph_positions", BUFFER_GET_GLYPHS);
    static final MethodHandle BUFFER_DESTROY = bind("hb_buffer_destroy", VOID_HANDLE);
    static final MethodHandle SHAPE = bind("hb_shape", HarfBuzz.SHAPE);
    static final MethodHandle FEATURE_FROM_STRING =
        bind("hb_feature_from_string", HarfBuzz.FEATURE_FROM_STRING);

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
      MemorySegment symbol = LIBRARY
          .find(name)
          .orElseThrow(() -> new UnsatisfiedLinkError("The harfbuzz library has no function " + name
              + ". The natives module does not" + " match this version of rethinkj-ui-core."));
      return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }
  }

  /** No destroy callback: the caller keeps the bytes valid until the blob and its face are gone. */
  public static MemorySegment blobCreate(MemorySegment data, int length, int mode) {
    try {
      return (MemorySegment)
          Fn.BLOB_CREATE.invokeExact(data, length, mode, MemorySegment.NULL, MemorySegment.NULL);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static void blobDestroy(MemorySegment blob) {
    voidHandle(Fn.BLOB_DESTROY, blob);
  }

  public static MemorySegment faceCreate(MemorySegment blob, int index) {
    try {
      return (MemorySegment) Fn.FACE_CREATE.invokeExact(blob, index);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static int faceGetUpem(MemorySegment face) {
    return getUnsigned(Fn.FACE_GET_UPEM, face);
  }

  /** 0 for data HarfBuzz cannot read as a font. */
  public static int faceGetGlyphCount(MemorySegment face) {
    return getUnsigned(Fn.FACE_GET_GLYPH_COUNT, face);
  }

  public static void faceDestroy(MemorySegment face) {
    voidHandle(Fn.FACE_DESTROY, face);
  }

  /** A font at the scale of the face's units per em, with HarfBuzz's OpenType font functions. */
  public static MemorySegment fontCreate(MemorySegment face) {
    try {
      return (MemorySegment) Fn.FONT_CREATE.invokeExact(face);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static int fontGetHExtents(MemorySegment font, MemorySegment extents) {
    try {
      return (int) Fn.FONT_GET_H_EXTENTS.invokeExact(font, extents);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static void fontDestroy(MemorySegment font) {
    voidHandle(Fn.FONT_DESTROY, font);
  }

  /** Never NULL: on failure HarfBuzz hands out an inert buffer; see {@link
   * #bufferAllocationSuccessful}. */
  public static MemorySegment bufferCreate() {
    try {
      return (MemorySegment) Fn.BUFFER_CREATE.invokeExact();
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Cluster values are indexes of UTF-16 code units in {@code text}. */
  public static void bufferAddUtf16(
      MemorySegment buffer, MemorySegment text, int textLength, int itemOffset, int itemLength) {
    try {
      Fn.BUFFER_ADD_UTF16.invokeExact(buffer, text, textLength, itemOffset, itemLength);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  public static void bufferSetDirection(MemorySegment buffer, int direction) {
    try {
      Fn.BUFFER_SET_DIRECTION.invokeExact(buffer, direction);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Fills in what is not set yet of direction, script and language, from the text. */
  public static void bufferGuessSegmentProperties(MemorySegment buffer) {
    voidHandle(Fn.BUFFER_GUESS_SEGMENT_PROPERTIES, buffer);
  }

  public static boolean bufferAllocationSuccessful(MemorySegment buffer) {
    try {
      return (int) Fn.BUFFER_ALLOCATION_SUCCESSFUL.invokeExact(buffer) != 0;
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** The records stay valid until the buffer changes or is destroyed. */
  public static MemorySegment bufferGetGlyphInfos(MemorySegment buffer, MemorySegment lengthOut) {
    return getGlyphs(Fn.BUFFER_GET_GLYPH_INFOS, buffer, lengthOut);
  }

  public static MemorySegment bufferGetGlyphPositions(
      MemorySegment buffer, MemorySegment lengthOut) {
    return getGlyphs(Fn.BUFFER_GET_GLYPH_POSITIONS, buffer, lengthOut);
  }

  public static void bufferDestroy(MemorySegment buffer) {
    voidHandle(Fn.BUFFER_DESTROY, buffer);
  }

  public static void shape(
      MemorySegment font, MemorySegment buffer, MemorySegment features, int count) {
    try {
      Fn.SHAPE.invokeExact(font, buffer, features, count);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Parses HarfBuzz's feature syntax, such as {@code -calt} or {@code ss01}; false if invalid. */
  public static boolean featureFromString(MemorySegment text, int length, MemorySegment feature) {
    try {
      return (int) Fn.FEATURE_FROM_STRING.invokeExact(text, length, feature) != 0;
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static int getUnsigned(MethodHandle handle, MemorySegment object) {
    try {
      return (int) handle.invokeExact(object);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  private static MemorySegment getGlyphs(
      MethodHandle handle, MemorySegment buffer, MemorySegment lengthOut) {
    try {
      return (MemorySegment) handle.invokeExact(buffer, lengthOut);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  private static void voidHandle(MethodHandle handle, MemorySegment object) {
    try {
      handle.invokeExact(object);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }
}
