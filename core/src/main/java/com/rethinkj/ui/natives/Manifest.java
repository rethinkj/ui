package com.rethinkj.ui.natives;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The list of native libraries in a natives module, read from its {@code manifest.txt}.
 *
 * <p>The file is text in UTF-8. Blank lines and lines that start with {@code #} are ignored; the
 * other lines are words separated by spaces:
 *
 * <pre>
 * rethinkj-ui-natives 1
 * platform windows-x64
 * library sdl3 3.4.20 SDL3.dll 66844f10…
 * library wgpu_native 29.0.1.1 wgpu_native.dll 5e26249c…
 * </pre>
 *
 * <p>The first line gives the format; a reader rejects other formats. Each {@code library} line
 * has the logical name, the version, the file name and the SHA-256 of the file in lower-case hex.
 * The lines are in load order: a library comes after the libraries it needs. Lines with another
 * first word are ignored, so later versions of format 1 can add some. One such line is {@code built
 * <name> <sha256>}, which says that the library was built from the source archive with that hash
 * on the machine that wrote the manifest, so that its own hash is pinned nowhere.
 */
record Manifest(String platform, List<Library> libraries) {

  static final String FORMAT = "rethinkj-ui-natives";
  static final int VERSION = 1;

  private static final Pattern WORDS = Pattern.compile("[ \t]+");
  private static final Pattern PLATFORM = Pattern.compile("[a-z0-9]+-[a-z0-9]+");
  private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
  private static final Pattern VERSION_TEXT = Pattern.compile("[A-Za-z0-9._+-]+");
  // A plain file name: no folders, so nothing is written outside the folder it is unpacked into.
  private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_+-][A-Za-z0-9._+-]*");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  record Library(String name, String version, String file, String sha256) {}

  Manifest {
    libraries = List.copyOf(libraries);
  }

  /** Reads the manifest, naming {@code source} in what it throws. */
  static Manifest parse(String text, String source) {
    String platform = null;
    List<Library> libraries = new ArrayList<>();
    Set<String> names = new HashSet<>();
    Set<String> files = new HashSet<>();
    boolean header = false;
    // An editor may have put a byte order mark in front.
    String[] lines = text.replaceFirst("^\\x{FEFF}", "").split("\r?\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String at = source + ", line " + (i + 1) + ": ";
      String[] words = WORDS.split(line);
      if (!header) {
        if (words.length != 2 || !words[0].equals(FORMAT)) {
          throw new IllegalArgumentException(
              at + "expected '" + FORMAT + " " + VERSION + "', not a manifest of native libraries");
        }
        if (!words[1].equals(String.valueOf(VERSION))) {
          throw new IllegalArgumentException(at + "the manifest has format " + words[1]
              + ", this rethinkj-ui-core reads format " + VERSION
              + ". Use the same version of rethinkj-ui-core and of the natives module.");
        }
        header = true;
        continue;
      }
      switch (words[0]) {
        case "platform" -> {
          if (words.length != 2 || !PLATFORM.matcher(words[1]).matches() || platform != null) {
            throw new IllegalArgumentException(at + "expected one 'platform <os>-<arch>'");
          }
          platform = words[1];
        }
        case "library" -> {
          if (words.length != 5
              || !NAME.matcher(words[1]).matches()
              || !VERSION_TEXT.matcher(words[2]).matches()
              || !FILE.matcher(words[3]).matches()
              || !SHA256.matcher(words[4]).matches()) {
            throw new IllegalArgumentException(
                at + "expected 'library <name> <version> <file> <sha256>', got '" + line + "'");
          }
          if (!names.add(words[1]) || !files.add(words[3].toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(at + "library " + words[1] + " or file " + words[3]
                + " is listed twice");
          }
          libraries.add(new Library(words[1], words[2], words[3], words[4]));
        }
        default -> {} // added by a later version of format 1
      }
    }
    if (!header) {
      throw new IllegalArgumentException(source + " is empty");
    }
    if (platform == null || libraries.isEmpty()) {
      throw new IllegalArgumentException(source + " names no platform or no library");
    }
    return new Manifest(platform, libraries);
  }

  Library library(String name) {
    for (Library library : libraries) {
      if (library.name().equals(name)) {
        return library;
      }
    }
    return null;
  }

  List<String> names() {
    return libraries.stream().map(Library::name).toList();
  }

  /**
   * The name of the cache folder for these files, such as {@code windows-x64-0123456789abcdef}:
   * the platform and the start of a hash over the file names and their hashes. Other files get
   * another folder, so versions never mix.
   */
  String folderName() {
    MessageDigest digest = sha256();
    for (Library library : libraries) {
      String line = library.file() + " " + library.sha256() + "\n";
      digest.update(line.getBytes(StandardCharsets.UTF_8));
    }
    return platform + "-" + HexFormat.of().formatHex(digest.digest(), 0, 8);
  }

  static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("every Java runtime has SHA-256", e);
    }
  }
}
