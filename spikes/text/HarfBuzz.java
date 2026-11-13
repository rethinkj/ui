import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** Handwritten FFM bindings for the few HarfBuzz functions the spike needs. */
final class HarfBuzz {

  private HarfBuzz() {}

  static final SymbolLookup LIB =
      SymbolLookup.libraryLookup(
          System.getProperty("harfbuzz", FreeType.WINDOWS ? "harfbuzz.dll" : "libharfbuzz.so.0"),
          Arena.global());

  static final int MEMORY_MODE_READONLY = 1;
  /** sizeof(hb_glyph_info_t) and sizeof(hb_glyph_position_t): five 32-bit fields each. */
  static final int RECORD = 20;
  /** sizeof(hb_feature_t): tag, value, start, end. */
  static final int FEATURE = 16;

  static final FunctionDescriptor VERSION_STRING = FunctionDescriptor.of(ADDRESS);
  static final FunctionDescriptor BLOB_CREATE =
      FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
  static final FunctionDescriptor FACE_CREATE = FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT);
  static final FunctionDescriptor FONT_CREATE = FunctionDescriptor.of(ADDRESS, ADDRESS);
  static final FunctionDescriptor FONT_SET_SCALE =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor BUFFER_CREATE = FunctionDescriptor.of(ADDRESS);
  static final FunctionDescriptor BUFFER_CLEAR_CONTENTS = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor BUFFER_ADD_UTF16 =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT);
  static final FunctionDescriptor BUFFER_GUESS_SEGMENT_PROPERTIES =
      FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor SHAPE =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
  static final FunctionDescriptor BUFFER_GET_RECORDS = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor FEATURE_FROM_STRING =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);

  static final MethodHandle HB_VERSION_STRING = handle("hb_version_string", VERSION_STRING);
  static final MethodHandle HB_BLOB_CREATE = handle("hb_blob_create", BLOB_CREATE);
  static final MethodHandle HB_FACE_CREATE = handle("hb_face_create", FACE_CREATE);
  static final MethodHandle HB_FONT_CREATE = handle("hb_font_create", FONT_CREATE);
  static final MethodHandle HB_FONT_SET_SCALE = handle("hb_font_set_scale", FONT_SET_SCALE);
  static final MethodHandle HB_BUFFER_CREATE = handle("hb_buffer_create", BUFFER_CREATE);
  static final MethodHandle HB_BUFFER_CLEAR_CONTENTS =
      handle("hb_buffer_clear_contents", BUFFER_CLEAR_CONTENTS);
  static final MethodHandle HB_BUFFER_ADD_UTF16 = handle("hb_buffer_add_utf16", BUFFER_ADD_UTF16);
  static final MethodHandle HB_BUFFER_GUESS_SEGMENT_PROPERTIES =
      handle("hb_buffer_guess_segment_properties", BUFFER_GUESS_SEGMENT_PROPERTIES);
  static final MethodHandle HB_SHAPE = handle("hb_shape", SHAPE);
  static final MethodHandle HB_BUFFER_GET_GLYPH_INFOS =
      handle("hb_buffer_get_glyph_infos", BUFFER_GET_RECORDS);
  static final MethodHandle HB_BUFFER_GET_GLYPH_POSITIONS =
      handle("hb_buffer_get_glyph_positions", BUFFER_GET_RECORDS);
  static final MethodHandle HB_FEATURE_FROM_STRING =
      handle("hb_feature_from_string", FEATURE_FROM_STRING);
  static final FunctionDescriptor FONT_GET_GLYPH_NAME =
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);
  static final MethodHandle HB_FONT_GET_GLYPH_NAME =
      handle("hb_font_get_glyph_name", FONT_GET_GLYPH_NAME);

  // shortcut: one buffer for the whole program, so shaping is single-threaded.
  static final MemorySegment BUFFER = (MemorySegment) FreeType.call(HB_BUFFER_CREATE);

  /** Shaped glyphs; positions are in the font's scale (1/64 pixels here). */
  record Run(int[] glyphs, int[] clusters, int[] xAdvance, int[] xOffset, int[] yOffset) {}

  static String version() {
    return ((MemorySegment) FreeType.call(HB_VERSION_STRING)).reinterpret(64).getString(0);
  }

  /** The font data has to stay alive as long as the face. */
  static MemorySegment face(MemorySegment data) {
    MemorySegment blob = (MemorySegment) FreeType.call(HB_BLOB_CREATE, data,
        Math.toIntExact(data.byteSize()), MEMORY_MODE_READONLY, MemorySegment.NULL,
        MemorySegment.NULL);
    return (MemorySegment) FreeType.call(HB_FACE_CREATE, blob, 0);
  }

  /** A font whose positions come out in 1/64 pixels; HarfBuzz's own OpenType metrics, unhinted. */
  static MemorySegment font(MemorySegment face, int size26) {
    MemorySegment font = (MemorySegment) FreeType.call(HB_FONT_CREATE, face);
    FreeType.call(HB_FONT_SET_SCALE, font, size26, size26);
    return font;
  }

  /** Features use HarfBuzz's syntax, for example "-calt" or "-kern". */
  static Run shape(MemorySegment font, String text, String... features) {
    try (Arena arena = Arena.ofConfined()) {
      FreeType.call(HB_BUFFER_CLEAR_CONTENTS, BUFFER);
      FreeType.call(HB_BUFFER_ADD_UTF16, BUFFER, arena.allocateFrom(JAVA_CHAR, text.toCharArray()),
          text.length(), 0, text.length());
      FreeType.call(HB_BUFFER_GUESS_SEGMENT_PROPERTIES, BUFFER);
      MemorySegment parsed = arena.allocate((long) FEATURE * Math.max(1, features.length), 4);
      for (int i = 0; i < features.length; i++) {
        int ok = (int) FreeType.call(HB_FEATURE_FROM_STRING, arena.allocateFrom(features[i]), -1,
            parsed.asSlice((long) FEATURE * i, FEATURE));
        if (ok == 0) {
          throw new IllegalArgumentException("not a HarfBuzz feature: " + features[i]);
        }
      }
      FreeType.call(HB_SHAPE, font, BUFFER, parsed, features.length);

      MemorySegment length = arena.allocate(JAVA_INT);
      MemorySegment infos = (MemorySegment) FreeType.call(HB_BUFFER_GET_GLYPH_INFOS, BUFFER, length);
      int n = length.get(JAVA_INT, 0);
      infos = infos.reinterpret((long) RECORD * n);
      MemorySegment positions = ((MemorySegment)
          FreeType.call(HB_BUFFER_GET_GLYPH_POSITIONS, BUFFER, length)).reinterpret((long) RECORD * n);
      Run run = new Run(new int[n], new int[n], new int[n], new int[n], new int[n]);
      for (int i = 0; i < n; i++) {
        run.glyphs[i] = infos.get(JAVA_INT, (long) RECORD * i);
        run.clusters[i] = infos.get(JAVA_INT, (long) RECORD * i + 8);
        run.xAdvance[i] = positions.get(JAVA_INT, (long) RECORD * i);
        run.xOffset[i] = positions.get(JAVA_INT, (long) RECORD * i + 8);
        run.yOffset[i] = positions.get(JAVA_INT, (long) RECORD * i + 12);
      }
      return run;
    }
  }

  static String glyphName(MemorySegment font, int glyph) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment name = arena.allocate(64);
      int ok = (int) FreeType.call(HB_FONT_GET_GLYPH_NAME, font, glyph, name, 64);
      return ok != 0 ? name.getString(0) : "#" + glyph;
    }
  }

  static MethodHandle handle(String name, FunctionDescriptor descriptor) {
    return FreeType.handle(LIB, name, descriptor);
  }
}
