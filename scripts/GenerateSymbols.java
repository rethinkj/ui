import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the kit's {@code Symbol} enum, the names of the icons in the default icon font, from the
 * font itself.
 *
 * <p>Usage, from the repository root: {@code java scripts/GenerateSymbols.java}. It needs only the
 * JDK.
 *
 * <p>Fluent UI System Icons names each glyph {@code ic_fluent_<name>_<size>_regular} in the
 * font's {@code post} table, and its {@code cmap} gives each glyph a code point of its own. The
 * script reads both from {@code FluentSystemIcons-Regular.ttf} in the icons module and writes a
 * constant for each name in {@link #NAMES}, with the code points of its drawings for the sizes in
 * {@link #SIZES}. To name another icon, add its Fluent name to the list and run the script again.
 * Where Fluent gives two names to one glyph, the font has only one of them; the script says so.
 * The list is short on purpose: the enum's static initialiser would outgrow the 64 KB a method may
 * have long before the font's 4000 names.
 */
public class GenerateSymbols {

  static final Path FONT =
      Path.of("icons/src/main/resources/META-INF/rethinkj-ui/icons/FluentSystemIcons-Regular.ttf");
  static final Path OUT = Path.of("kit/src/main/java/com/rethinkj/ui/kit/Symbol.java");
  static final int[] SIZES = {12, 16, 20, 24, 28, 32, 48};

  /** The Fluent names, without prefix, size and style. */
  static final String NAMES = """
      add alert arrow_clockwise arrow_down arrow_download arrow_import arrow_left arrow_maximize
      arrow_export_ltr arrow_minimize arrow_redo arrow_right arrow_sort arrow_sort_down
      arrow_sort_up arrow_sync arrow_undo arrow_up arrow_upload braces branch calendar_ltr checkmark
      checkmark_circle chevron_down chevron_left chevron_right chevron_up circle
      clipboard_paste clock code column_single copy cube cut database delete dismiss
      dismiss_circle document document_table edit error_circle eye eye_off filter
      filter_dismiss folder folder_open history info key key_multiple link lock_closed lock_open
      math_formula maximize more_horizontal more_vertical navigation number_symbol open
      panel_left panel_right pause people person pin play plug_connected plug_disconnected
      question_circle record_stop save search server settings shield split_horizontal
      split_vertical square_multiple star stop subtract table table_simple tag text_sort_ascending
      text_sort_descending text_t warning window
      """;

  public static void main(String[] args) throws IOException {
    ByteBuffer font = ByteBuffer.wrap(Files.readAllBytes(FONT));
    Map<String, Integer> tables = tables(font);
    String[] glyphNames = glyphNames(font, tables.get("post"));
    Map<Integer, Integer> codePoints = codePoints(font, tables.get("cmap"));
    // Name -> size -> code point.
    Map<String, Map<Integer, Integer>> drawings = new HashMap<>();
    // A drawing with words in it has a glyph per language; _en is the default.
    Pattern name = Pattern.compile("ic_fluent_(.+)_(\\d+)_regular(_en)?");
    for (Map.Entry<Integer, Integer> e : codePoints.entrySet()) {
      Matcher m = name.matcher(glyphNames[e.getKey()]);
      if (m.matches()) {
        drawings.computeIfAbsent(m.group(1), k -> new TreeMap<>())
            .merge(Integer.parseInt(m.group(2)), e.getValue(),
                (a, b) -> m.group(3) == null ? b : a);
      }
    }
    StringBuilder constants = new StringBuilder();
    List<String> wanted = new ArrayList<>(List.of(NAMES.strip().split("\\s+")));
    wanted.sort(null);
    List<String> missing = wanted.stream().filter(n -> !drawings.containsKey(n)).toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("The font has no glyph named " + missing + ". A glyph"
          + " has one name in the font, which may differ from the one on Fluent's site, such as"
          + " arrow_export_ltr for arrow_export.");
    }
    for (String fluent : wanted) {
      Map<Integer, Integer> sizes = drawings.get(fluent);
      constants.append("  ").append(fluent.toUpperCase(Locale.ROOT)).append('(');
      for (int i = 0; i < SIZES.length; i++) {
        Integer cp = sizes.get(SIZES[i]);
        constants.append(i == 0 ? "" : ", ")
            .append(cp == null ? "0" : String.format("0x%04X", cp));
      }
      constants.append("),\n");
    }
    constants.setLength(constants.length() - 2);
    String sizes = String.join(", ",
        java.util.Arrays.stream(SIZES).mapToObj(String::valueOf).toList());
    String source = TEMPLATE.replace("$SIZES", sizes).replace("$CONSTANTS", constants)
        .replace("$COUNT", String.valueOf(wanted.size()))
        .replace("$UNDRAWN", wanted.stream().filter(n -> !drawings.get(n).containsKey(16))
            .map(n -> "{@link #" + n.toUpperCase(Locale.ROOT) + "}")
            .collect(java.util.stream.Collectors.joining(", ")));
    Files.writeString(OUT, source);
    System.out.println("Wrote " + wanted.size() + " symbols to " + OUT);
  }

  /** The offset of each table, by tag. */
  static Map<String, Integer> tables(ByteBuffer f) {
    Map<String, Integer> tables = new HashMap<>();
    int count = Short.toUnsignedInt(f.getShort(4));
    for (int i = 0; i < count; i++) {
      byte[] tag = new byte[4];
      f.get(12 + 16 * i, tag);
      tables.put(new String(tag, StandardCharsets.US_ASCII), f.getInt(12 + 16 * i + 8));
    }
    return tables;
  }

  /** The glyph names of a {@code post} table of format 2. */
  static String[] glyphNames(ByteBuffer f, int post) {
    if (f.getInt(post) != 0x20000) {
      throw new IllegalArgumentException("The post table has no glyph names: format "
          + Integer.toHexString(f.getInt(post)));
    }
    int glyphs = Short.toUnsignedInt(f.getShort(post + 32));
    int[] index = new int[glyphs];
    for (int i = 0; i < glyphs; i++) {
      index[i] = Short.toUnsignedInt(f.getShort(post + 34 + 2 * i));
    }
    List<String> strings = new ArrayList<>();
    int at = post + 34 + 2 * glyphs;
    int highest = java.util.Arrays.stream(index).max().orElse(0);
    while (strings.size() < highest - 257) {
      int length = Byte.toUnsignedInt(f.get(at));
      byte[] bytes = new byte[length];
      f.get(at + 1, bytes);
      strings.add(new String(bytes, StandardCharsets.US_ASCII));
      at += 1 + length;
    }
    String[] names = new String[glyphs];
    for (int i = 0; i < glyphs; i++) {
      // Indexes below 258 are the standard Macintosh names, which no icon has.
      names[i] = index[i] < 258 ? "" : strings.get(index[i] - 258);
    }
    return names;
  }

  /** Glyph -> its lowest code point, from the Windows Unicode subtable of format 12 or 4. */
  static Map<Integer, Integer> codePoints(ByteBuffer f, int cmap) {
    Map<Integer, Integer> byGlyph = new TreeMap<>();
    int count = Short.toUnsignedInt(f.getShort(cmap + 2));
    int full = -1;
    int bmp = -1;
    for (int i = 0; i < count; i++) {
      int platform = f.getShort(cmap + 4 + 8 * i);
      int encoding = f.getShort(cmap + 6 + 8 * i);
      int offset = cmap + f.getInt(cmap + 8 + 8 * i);
      if (platform == 3 && encoding == 10) {
        full = offset;
      } else if (platform == 3 && encoding == 1) {
        bmp = offset;
      }
    }
    if (full >= 0 && f.getShort(full) == 12) {
      int groups = f.getInt(full + 12);
      for (int g = 0; g < groups; g++) {
        int start = f.getInt(full + 16 + 12 * g);
        int end = f.getInt(full + 20 + 12 * g);
        int glyph = f.getInt(full + 24 + 12 * g);
        for (int cp = start; cp <= end; cp++) {
          byGlyph.putIfAbsent(glyph + cp - start, cp);
        }
      }
      return byGlyph;
    }
    if (bmp < 0 || f.getShort(bmp) != 4) {
      throw new IllegalArgumentException("The font has no Unicode cmap of format 12 or 4.");
    }
    int segments = Short.toUnsignedInt(f.getShort(bmp + 6)) / 2;
    int ends = bmp + 14;
    int starts = ends + 2 * segments + 2;
    int deltas = starts + 2 * segments;
    int ranges = deltas + 2 * segments;
    for (int s = 0; s < segments; s++) {
      int start = Short.toUnsignedInt(f.getShort(starts + 2 * s));
      int end = Short.toUnsignedInt(f.getShort(ends + 2 * s));
      int delta = f.getShort(deltas + 2 * s);
      int range = Short.toUnsignedInt(f.getShort(ranges + 2 * s));
      for (int cp = start; cp <= end && cp != 0xFFFF; cp++) {
        int glyph;
        if (range == 0) {
          glyph = (cp + delta) & 0xFFFF;
        } else {
          int at = ranges + 2 * s + range + 2 * (cp - start);
          glyph = Short.toUnsignedInt(f.getShort(at));
          glyph = glyph == 0 ? 0 : (glyph + delta) & 0xFFFF;
        }
        if (glyph != 0) {
          byGlyph.putIfAbsent(glyph, cp);
        }
      }
    }
    return byGlyph;
  }

  static final String TEMPLATE = """
      package com.rethinkj.ui.kit;

      // Generated by scripts/GenerateSymbols.java from FluentSystemIcons-Regular.ttf in the icons
      // module. Do not edit: add a name to the script's list and run it again.

      /**
       * The icons of the default icon font, {@link com.rethinkj.ui.text.Font#icons()} (Fluent
       * UI System Icons), that the kit names: $COUNT of them, for a database tool and the parts
       * of a window. {@link Icon#icon} shows one.
       *
       * <p>Each symbol is Fluent's icon of the same name: {@link #CHEVRON_RIGHT} is {@code
       * ic_fluent_chevron_right_<size>_regular}. Fluent draws an icon anew for some of the sizes
       * $SIZES pixels, and each drawing has a code point of its own;
       * {@link #codePoint} picks the one for a size. All have a drawing for 16 but these:
       * $UNDRAWN.
       */
      public enum Symbol {
      $CONSTANTS;

        private static final int[] SIZES = {$SIZES};

        /** The code points of the drawings for {@link #SIZES}, 0 where there is none. */
        private final int[] codePoints;

        Symbol(int... codePoints) {
          this.codePoints = codePoints;
        }

        /**
         * The code point of the drawing for {@code size} in logical pixels: the drawing made for
         * that size, else the one of the nearest size, the smaller of two as near. Only the
         * first is sharp: another is scaled.
         */
        public int codePoint(float size) {
          int best = -1;
          for (int i = 0; i < SIZES.length; i++) {
            if (codePoints[i] != 0
                && (best < 0 || Math.abs(SIZES[i] - size) < Math.abs(SIZES[best] - size))) {
              best = i;
            }
          }
          return codePoints[best];
        }
      }
      """;
}
