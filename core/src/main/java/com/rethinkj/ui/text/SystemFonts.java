package com.rethinkj.ui.text;

import java.io.IOException;
import java.lang.Character.UnicodeScript;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The fonts installed on the system, for the characters that the fonts of a text lack ({@link
 * Font#withSystemFonts}). Found without a native library of the platform: the first lookup walks
 * the system's font folders, maps each TrueType or OpenType file in turn and asks FreeType for the
 * family, style and characters of each of its faces. That index stays for the process; a file
 * stays mapped only while it is read, and while a face of it is used as a {@link Font}.
 *
 * <p>Faces without outlines are left out, as are colour fonts whose glyphs have none (CBDT
 * bitmaps, COLR version 1): text draws coverage of outlines, in one colour. A colour font with
 * outlines for its base glyphs, as COLR version 0 has, draws those, in the text colour.
 *
 * <p>For a cluster of characters, the first face that has all of them wins, in this order: the
 * families the platform prefers for the cluster's script, in order, then other families (on Linux
 * those named {@code Noto Sans} and a script before others), each with the style nearest the
 * text's font (bold, italic, a plain style name), then by name and path. For Han characters, which
 * Chinese, Japanese and Korean draw differently, the order of the CJK families follows the default
 * locale; Hangul prefers the Korean family and kana the Japanese one.
 */
final class SystemFonts {

  private SystemFonts() {}

  /**
   * A face of a font file, as the scan keeps it.
   *
   * @param ranges the characters it has, as first and last of each run of consecutive ones
   */
  record Face(Path path, int index, String family, String style, int styleFlags, int[] ranges) {

    boolean has(int c) {
      int lo = 0;
      int hi = ranges.length / 2 - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        if (c < ranges[2 * mid]) {
          hi = mid - 1;
        } else if (c > ranges[2 * mid + 1]) {
          lo = mid + 1;
        } else {
          return true;
        }
      }
      return false;
    }
  }

  private static final Set<String> EXTENSIONS = Set.of("ttf", "otf", "ttc", "otc");
  private static final Set<String> PLAIN = Set.of("regular", "book", "normal", "roman", "bold",
      "italic", "oblique", "bold italic", "bold oblique");

  private static List<Face> faces; // null until the first lookup
  private static final AtomicBoolean prepared = new AtomicBoolean();
  private static long scanNanos;
  private static int files;
  private static int lookups;
  private static final Map<String, Font> found = new HashMap<>(); // cluster and style; null: none
  private static final Map<Face, Font> opened = new HashMap<>();

  /**
   * The system's font for a grapheme cluster, opened, or null where no face has all its characters
   * that are drawn, or where one of them is a private-use or unassigned character, whose look only
   * the font that it was meant for knows.
   *
   * @param styleFlags FreeType's bold and italic flags of the text's font
   */
  static synchronized Font find(String cluster, int styleFlags) {
    lookups++;
    String key = cluster + '\u0000' + styleFlags;
    if (found.containsKey(key)) {
      return found.get(key);
    }
    int[] chars = cluster.codePoints().filter(c -> !Runs.hidden(c)).toArray();
    Font font = null;
    if (chars.length > 0 && Arrays.stream(chars).allMatch(SystemFonts::meaningful)) {
      List<String> preferred = preferred(cluster, windows(), Locale.getDefault());
      List<Face> candidates = new ArrayList<>();
      for (Face face : faces()) {
        if (Arrays.stream(chars).allMatch(face::has)) {
          candidates.add(face);
        }
      }
      candidates.sort(Comparator.<Face>comparingInt(f -> rank(f.family, preferred))
          .thenComparingInt(f -> styleDistance(f, styleFlags))
          .thenComparing(Face::family).thenComparing(Face::style)
          .thenComparing(Face::path).thenComparingInt(Face::index));
      for (Face face : candidates) {
        font = open(face);
        if (font != null) {
          break;
        }
      }
    }
    found.put(key, font);
    return font;
  }

  private static Font open(Face face) {
    if (opened.containsKey(face)) {
      return opened.get(face);
    }
    Font font;
    try {
      font = Font.file(face.path, face.index);
    } catch (RuntimeException e) {
      font = null; // changed or removed since the scan
    }
    opened.put(face, font);
    return font;
  }

  private static boolean meaningful(int c) {
    int type = Character.getType(c);
    return type != Character.PRIVATE_USE && type != Character.UNASSIGNED
        && type != Character.SURROGATE;
  }

  /**
   * Starts {@link #faces} on a virtual thread of its own, once per process. What the scan throws,
   * as when FreeType cannot be loaded, is dropped there: the first lookup scans again and throws
   * it on its own thread.
   */
  static void prepare() {
    if (prepared.compareAndSet(false, true)) {
      Thread.ofVirtual().name("rethinkj-ui system fonts").start(() -> {
        try {
          faces();
        } catch (RuntimeException | LinkageError e) {
          // see above
        }
      });
    }
  }

  /** The faces of the system's fonts, scanned on the first call. */
  static synchronized List<Face> faces() {
    if (faces == null) {
      long start = System.nanoTime();
      List<Face> all = new ArrayList<>();
      List<Path> paths = fontFiles(folders());
      for (Path path : paths) {
        all.addAll(inspect(path));
      }
      faces = List.copyOf(all);
      files = paths.size();
      scanNanos = System.nanoTime() - start;
    }
    return faces;
  }

  /** How long the scan took, in nanoseconds, and how many files it read; 0 before it. */
  static synchronized long scanNanos() {
    return scanNanos;
  }

  static synchronized int files() {
    return files;
  }

  /** How often {@link #find} was asked, for tests that check that it was not. */
  static synchronized int lookups() {
    return lookups;
  }

  private static List<Face> inspect(Path path) {
    try (Arena arena = Arena.ofConfined(); FileChannel channel = FileChannel.open(path)) {
      MemorySegment data = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
      return Font.inspect(path, data);
    } catch (IOException | RuntimeException e) {
      return List.of(); // unreadable, or not a font
    }
  }

  static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
  }

  /** The folders the system's fonts are in; those that do not exist are skipped. */
  static List<Path> folders() {
    String home = System.getProperty("user.home");
    if (windows()) {
      String windir = Objects.requireNonNullElse(System.getenv("WINDIR"), "C:\\Windows");
      List<Path> folders = new ArrayList<>(List.of(Path.of(windir, "Fonts")));
      String local = System.getenv("LOCALAPPDATA");
      if (local != null) {
        folders.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
      }
      return folders;
    }
    String data = System.getenv("XDG_DATA_HOME");
    return List.of(Path.of("/usr/share/fonts"), Path.of("/usr/local/share/fonts"),
        data != null && !data.isEmpty() ? Path.of(data, "fonts")
            : Path.of(home, ".local", "share", "fonts"),
        Path.of(home, ".fonts"));
  }

  /** The TrueType and OpenType files under the folders, each once, sorted. */
  private static List<Path> fontFiles(List<Path> folders) {
    Set<Path> paths = new TreeSet<>();
    for (Path folder : folders) {
      if (!Files.isDirectory(folder)) {
        continue;
      }
      try {
        Files.walkFileTree(folder, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 16,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                int dot = name.lastIndexOf('.');
                if (attributes.isRegularFile() && dot >= 0
                    && EXTENSIONS.contains(name.substring(dot + 1))) {
                  try {
                    paths.add(file.toRealPath());
                  } catch (IOException e) {
                    // gone meanwhile
                  }
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE; // unreadable, or a loop of links
              }
            });
      } catch (IOException e) {
        // the folder went away
      }
    }
    return List.copyOf(paths);
  }

  /**
   * How early a family comes for a cluster: its place in the preferred families, a name before a
   * pattern, else after them all. Patterns leave out monospaced and CJK families.
   */
  private static int rank(String family, List<String> preferred) {
    int index = preferred.indexOf(family);
    for (int i = 0; i < preferred.size() && index < 0; i++) {
      String p = preferred.get(i);
      if (p.endsWith("*") && family.startsWith(p.substring(0, p.length() - 1))
          && !family.contains(" Mono") && !family.contains(" CJK")) {
        index = i;
      }
    }
    return index >= 0 ? index : preferred.size();
  }

  private static int styleDistance(Face face, int styleFlags) {
    int distance = face.styleFlags == styleFlags ? 0 : face.styleFlags == 0 ? 2 : 4;
    return distance + (PLAIN.contains(face.style.toLowerCase(Locale.ROOT)) ? 0 : 1);
  }

  /**
   * The families to try first for a cluster, in order; a name that ends in {@code *} stands for
   * every family that starts with the rest.
   */
  static List<String> preferred(String cluster, boolean windows, Locale locale) {
    UnicodeScript script = Runs.script(cluster);
    int first = cluster.codePointAt(0);
    boolean emoji = Character.isEmojiPresentation(first) || cluster.indexOf('\uFE0F') >= 0;
    // CJK fonts have most symbols too, drawn wide: they come first only for CJK scripts.
    List<String> cjk = cjk(script, windows, locale);
    boolean cjkFirst = script == UnicodeScript.HAN || script == UnicodeScript.HIRAGANA
        || script == UnicodeScript.KATAKANA || script == UnicodeScript.HANGUL
        || script == UnicodeScript.BOPOMOFO;
    List<String> order = new ArrayList<>();
    if (emoji) {
      order.addAll(windows ? List.of("Segoe UI Emoji") : List.of("Noto Emoji", "Noto Color Emoji"));
    }
    if (cjkFirst) {
      order.addAll(cjk);
    }
    if (windows) {
      order.addAll(List.of("Segoe UI", "Segoe UI Symbol", "Segoe UI Emoji", "Leelawadee UI",
          "Nirmala UI", "Segoe UI Historic"));
    } else {
      order.addAll(List.of("Noto Sans", "Noto Sans Symbols", "Noto Sans Symbols2",
          "Noto Sans Symbols 2", "Noto Sans *", "DejaVu Sans", "Noto Emoji", "Noto Color Emoji",
          "Symbola"));
    }
    order.addAll(cjk);
    return List.copyOf(new LinkedHashSet<>(order));
  }

  /** The CJK families in the order the locale and the script ask for. */
  private static List<String> cjk(UnicodeScript script, boolean windows, Locale locale) {
    String country = locale.getCountry();
    boolean traditional = locale.getScript().equals("Hant") || country.equals("TW")
        || country.equals("HK") || country.equals("MO");
    List<String> order = new ArrayList<>(switch (locale.getLanguage()) {
      case "ko" -> List.of("KR", "JP", "SC", "TC");
      case "zh" -> traditional ? List.of("TC", "SC", "JP", "KR") : List.of("SC", "TC", "JP", "KR");
      default -> List.of("JP", "SC", "TC", "KR");
    });
    if (script == UnicodeScript.HANGUL) {
      order.remove("KR");
      order.addFirst("KR");
    } else if (script == UnicodeScript.HIRAGANA || script == UnicodeScript.KATAKANA) {
      order.remove("JP");
      order.addFirst("JP");
    }
    if (!windows) { // Noto Sans CJK has Hong Kong's forms too
      order.add(order.indexOf("TC") + (country.equals("HK") || country.equals("MO") ? 0 : 1),
          "HK");
    }
    return order.stream().map(code -> windows ? switch (code) {
      case "JP" -> "Yu Gothic UI";
      case "SC" -> "Microsoft YaHei UI";
      case "TC" -> "Microsoft JhengHei UI";
      default -> "Malgun Gothic";
    } : "Noto Sans CJK " + code).toList();
  }
}
