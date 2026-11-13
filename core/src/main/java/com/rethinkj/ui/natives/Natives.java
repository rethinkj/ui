package com.rethinkj.ui.natives;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.rethinkj.ui.natives.Manifest.Library;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Finds and loads the native libraries that core calls through the Foreign Function and Memory
 * API, by their logical names such as {@code sdl3} and {@code wgpu_native}.
 *
 * <p>The libraries of a platform such as {@code windows-x64} come in the JAR {@code
 * rethinkj-ui-natives-<platform>}, under {@code META-INF/rethinkj-ui/natives/<platform>/}, with a
 * {@code manifest.txt} that lists them in load order with their versions and SHA-256 hashes (see
 * {@link Manifest}). On first use they are unpacked into a cache folder per user, in a subfolder
 * named after their hashes, and loaded from there by absolute path. A cached file with the right
 * hash is used as it is; one with another hash is replaced. Each file is written to a temporary
 * file and moved into place in one step, so processes that start at the same time do not disturb
 * each other.
 *
 * <p>Two system properties change this:
 *
 * <ul>
 *   <li>{@value #DIR_PROPERTY} names a folder that holds the libraries and their {@code
 *       manifest.txt}, as the natives JAR has them. They are loaded from there, without checking
 *       their hashes; nothing is unpacked and nothing is read from the class path. This is for
 *       packaged apps and GraalVM native image.
 *   <li>{@value #CACHE_PROPERTY} names the cache folder, instead of {@code
 *       %LOCALAPPDATA%\rethinkj-ui\natives} on Windows and {@code
 *       $XDG_CACHE_HOME/rethinkj-ui/natives} (by default {@code ~/.cache/rethinkj-ui/natives}) on
 *       Linux.
 * </ul>
 *
 * <p>Loaded libraries stay loaded for the life of the process. Nothing is read or loaded before
 * the first {@link #lookup}, so rendering without a window never touches a library. Every failure
 * is an {@link UnsatisfiedLinkError} whose message says what is wrong and what to do.
 */
public final class Natives {

  public static final String DIR_PROPERTY = "rethinkj.ui.natives.dir";
  public static final String CACHE_PROPERTY = "rethinkj.ui.natives.cache";

  static final String RESOURCES = "META-INF/rethinkj-ui/natives/";
  static final String MANIFEST = "manifest.txt";
  static final List<String> PLATFORMS = List.of("windows-x64", "linux-x64");

  /** {@code void *dlopen(const char *file, int mode)} and {@code char *dlerror(void)}. */
  private static final FunctionDescriptor DLOPEN =
      FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT);

  private static final FunctionDescriptor DLERROR = FunctionDescriptor.of(ADDRESS);
  private static final int RTLD_LAZY = 1;

  private static Natives system;

  private final String osName;
  private final String osArch;
  private final String platform;
  private final Path dir;
  private final Path cache;
  private final ClassLoader resources;

  private final Map<String, SymbolLookup> loaded = new HashMap<>();
  private Manifest manifest;
  private Path folder;

  /**
   * @param dir the folder to load from instead of the class path, or null
   * @param cache the cache folder to unpack into, or null if there is none
   * @param resources where to find the natives module
   */
  Natives(String osName, String osArch, Path dir, Path cache, ClassLoader resources) {
    this.osName = osName;
    this.osArch = osArch;
    this.platform = platform(osName, osArch);
    this.dir = dir == null ? null : dir.toAbsolutePath();
    this.cache = cache == null ? null : cache.toAbsolutePath();
    this.resources = resources;
  }

  /**
   * Returns the lookup for the library with the given logical name. On first use, this loads it
   * and every library listed before it in the manifest.
   *
   * @throws UnsatisfiedLinkError if the library cannot be found, unpacked or loaded
   */
  public static SymbolLookup lookup(String name) {
    return system().find(name);
  }

  private static synchronized Natives system() {
    if (system == null) {
      String osName = System.getProperty("os.name", "");
      String osArch = System.getProperty("os.arch", "");
      Path cache = property(CACHE_PROPERTY);
      if (cache == null) {
        String home = System.getProperty("user.home");
        cache = defaultCache(platform(osName, osArch), System::getenv, home);
      }
      Path dir = property(DIR_PROPERTY);
      system = new Natives(osName, osArch, dir, cache, Natives.class.getClassLoader());
    }
    return system;
  }

  synchronized SymbolLookup find(String name) {
    SymbolLookup lookup = loaded.get(name);
    if (lookup != null) {
      return lookup;
    }
    Path from = prepare();
    if (manifest.library(name) == null) {
      throw error(
          "There is no native library '" + name + "'. The manifest in " + from + " lists "
              + String.join(", ", manifest.names()) + ".",
          null);
    }
    for (Library library : manifest.libraries()) {
      if (!loaded.containsKey(library.name())) {
        loaded.put(library.name(), load(from.resolve(library.file())));
      }
      if (library.name().equals(name)) {
        break;
      }
    }
    return loaded.get(name);
  }

  /** Returns the folder the libraries are loaded from, after unpacking them there if needed. */
  synchronized Path prepare() {
    if (folder != null) {
      return folder;
    }
    if (dir != null) {
      Manifest read = fromFolder();
      manifest = read;
      folder = dir;
      return folder;
    }
    if (platform == null) {
      throw error(
          "rethinkj UI has no native libraries for " + osName + " on " + osArch
              + "; there are some for " + String.join(" and ", PLATFORMS)
              + ". To use libraries built"
              + " for this system, set -D" + DIR_PROPERTY
              + "=<folder> to a folder that holds them and"
              + " a manifest.txt.",
          null);
    }
    Manifest read = fromClassPath();
    if (cache == null) {
      throw error(
          "There is no folder to unpack the native libraries into, because neither"
              + " LOCALAPPDATA, XDG_CACHE_HOME nor user.home is set. Set -D" + CACHE_PROPERTY
              + "=<folder>, or unpack them yourself and set -D" + DIR_PROPERTY + "=<folder>.",
          null);
    }
    Path target = cache.resolve(read.folderName());
    unpack(read, target);
    manifest = read;
    folder = target;
    return folder;
  }

  private Manifest fromFolder() {
    Path file = dir.resolve(MANIFEST);
    String hint = " Copy all files under META-INF/rethinkj-ui/natives/<platform>/ in the natives"
        + " JAR into that folder, " + MANIFEST + " included, or unset -D" + DIR_PROPERTY + ".";
    if (!Files.isRegularFile(file)) {
      throw error(
          "-D" + DIR_PROPERTY + " names " + dir + ", which has no " + MANIFEST + "." + hint, null);
    }
    Manifest read;
    try {
      read = Manifest.parse(Files.readString(file), file.toString());
    } catch (IOException e) {
      throw error("Cannot read " + file + ": " + e, e);
    } catch (IllegalArgumentException e) {
      throw error(e.getMessage(), e);
    }
    if (platform != null && !read.platform().equals(platform)) {
      throw error(
          file + " lists libraries for " + read.platform() + ", but this is " + platform + "."
              + hint,
          null);
    }
    for (Library library : read.libraries()) {
      if (!Files.isRegularFile(dir.resolve(library.file()))) {
        throw error(
            dir + " has no " + library.file() + ", which its " + MANIFEST + " lists." + hint, null);
      }
    }
    return read;
  }

  private Manifest fromClassPath() {
    String resource = RESOURCES + platform + "/" + MANIFEST;
    String text;
    try (InputStream in = resources == null ? null : resources.getResourceAsStream(resource)) {
      if (in == null) {
        throw error(
            "No native libraries for " + platform + " on the class path. Add the artifact"
                + " com.rethinkj.ui:" + artifact()
                + ", in the same version as rethinkj-ui-core, to the"
                + " class path (on the module path, also pass --add-modules "
                + "com.rethinkj.ui.natives." + platform.replace('-', '.') + "), or set -D"
                + DIR_PROPERTY + "=<folder> to a folder that holds the libraries and their "
                + MANIFEST + ".",
            null);
      }
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw error("Cannot read " + resource + " from " + artifact() + ": " + e, e);
    }
    Manifest read;
    try {
      read = Manifest.parse(text, resource);
    } catch (IllegalArgumentException e) {
      throw error(e.getMessage(), e);
    }
    if (!read.platform().equals(platform)) {
      throw damaged(
          resource + " lists libraries for " + read.platform() + ", not " + platform + ".");
    }
    return read;
  }

  private void unpack(Manifest read, Path target) {
    try {
      Files.createDirectories(target);
    } catch (IOException e) {
      throw notWritable(target, e);
    }
    for (Library library : read.libraries()) {
      Path file = target.resolve(library.file());
      if (hasHash(file, library.sha256())) {
        continue;
      }
      Path temporary;
      try {
        temporary = Files.createTempFile(target, library.file() + ".", ".tmp");
      } catch (IOException e) {
        throw notWritable(target, e);
      }
      try {
        write(library, temporary, target);
        move(temporary, file, library, target);
      } finally {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException e) {
          // Left behind; nothing loads a temporary file.
        }
      }
    }
  }

  /** Copies the library from the class path to {@code temporary} and checks its hash. */
  private void write(Library library, Path temporary, Path target) {
    String resource = RESOURCES + platform + "/" + library.file();
    MessageDigest digest = Manifest.sha256();
    try (InputStream in = resources.getResourceAsStream(resource)) {
      if (in == null) {
        throw damaged(resource + " is listed in its " + MANIFEST + " but missing.");
      }
      try (OutputStream out = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[1 << 16];
        for (int n; (n = in.read(buffer)) > 0; ) {
          digest.update(buffer, 0, n);
          out.write(buffer, 0, n);
        }
      }
    } catch (IOException e) {
      throw notWritable(target, e);
    }
    String actual = HexFormat.of().formatHex(digest.digest());
    if (!actual.equals(library.sha256())) {
      throw damaged(resource + " has the SHA-256 " + actual + ", but its " + MANIFEST + " says "
          + library.sha256() + ".");
    }
  }

  /** Moves {@code temporary} to {@code file} in one step, replacing a file with another hash. */
  private static void move(Path temporary, Path file, Library library, Path target) {
    for (int attempt = 1; ; attempt++) {
      try {
        // Replaces an existing file, on Windows as well as on Linux.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        return;
      } catch (IOException e) {
        // Another process may have put the same file there first and loaded it, which on Windows
        // keeps it from being replaced. A virus scanner may hold either file for a moment.
        if (hasHash(file, library.sha256())) {
          return;
        }
        if (attempt == 5) {
          throw error(
              "Cannot replace " + file + ", which differs from the one in the natives"
                  + " module: " + e
                  + ". Another program may be using it. Close the programs that use"
                  + " rethinkj UI and delete " + target + ".",
              e);
        }
      }
      try {
        Thread.sleep(20L * attempt);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw error("Interrupted while unpacking " + file, e);
      }
    }
  }

  private SymbolLookup load(Path file) {
    if (osName.startsWith("Windows")) {
      return Kernel32.load(file);
    }
    try {
      return SymbolLookup.libraryLookup(file, Arena.global());
    } catch (IllegalArgumentException e) {
      if (!osName.equals("Linux")) {
        throw error(
            "Cannot load " + file + ": " + e.getMessage() + ". The file may be damaged, or"
                + " the system lacks a library it needs.",
            e);
      }
      String reason = dlerror(file);
      throw error(
          "Cannot load " + file + ": " + e.getMessage()
              + (reason == null ? "" : " (" + reason + ")")
              + ". The file may be damaged, the system"
              + " may lack a library it needs or have an older glibc than it needs, or the folder may"
              + " be on a file system mounted noexec; then choose another with -D" + CACHE_PROPERTY
              + "=<folder>, or unpack the libraries yourself and set -D" + DIR_PROPERTY
              + "=<folder>.",
          e);
    }
  }

  /**
   * Why the dynamic linker of Linux cannot load {@code file}, such as a glibc version it lacks;
   * the JDK's message leaves that out. Null if it does not say.
   */
  private static String dlerror(Path file) {
    Linker linker = Linker.nativeLinker();
    SymbolLookup libc = linker.defaultLookup();
    try (Arena arena = Arena.ofConfined()) {
      MethodHandle dlopen = linker.downcallHandle(libc.findOrThrow("dlopen"), DLOPEN);
      MethodHandle dlerror = linker.downcallHandle(libc.findOrThrow("dlerror"), DLERROR);
      MemorySegment handle =
          (MemorySegment) dlopen.invokeExact(arena.allocateFrom(file.toString()), RTLD_LAZY);
      MemorySegment message = (MemorySegment) dlerror.invokeExact();
      if (!handle.equals(MemorySegment.NULL) || message.equals(MemorySegment.NULL)) {
        return null;
      }
      return message.reinterpret(Integer.MAX_VALUE).getString(0);
    } catch (Throwable t) {
      return null; // only for the message
    }
  }

  /**
   * Loads libraries on Windows with {@code LoadLibraryExW}, which takes the path in UTF-16, and
   * finds their symbols with {@code GetProcAddress}. {@code SymbolLookup.libraryLookup(Path, ...)}
   * hands the path to Windows in the ANSI code page, which cannot name every folder: under code
   * page 1252 it failed for a cache in a folder named in Japanese, as a user profile can be, where
   * the files were in place.
   */
  private static final class Kernel32 {

    /** {@code HMODULE LoadLibraryExW(LPCWSTR lpLibFileName, HANDLE hFile, DWORD dwFlags)}. */
    static final FunctionDescriptor LOAD_LIBRARY_EX_W =
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT);

    /** {@code FARPROC GetProcAddress(HMODULE hModule, LPCSTR lpProcName)}. */
    static final FunctionDescriptor GET_PROC_ADDRESS =
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);

    /**
     * {@code DWORD FormatMessageW(DWORD dwFlags, LPCVOID lpSource, DWORD dwMessageId, DWORD
     * dwLanguageId, LPWSTR lpBuffer, DWORD nSize, va_list *Arguments)}.
     */
    static final FunctionDescriptor FORMAT_MESSAGE_W = FunctionDescriptor.of(
        JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);

    private static final int FORMAT_MESSAGE_IGNORE_INSERTS = 0x200;
    private static final int FORMAT_MESSAGE_FROM_SYSTEM = 0x1000;
    private static final int MESSAGE_CHARS = 512;

    /** The error {@code LoadLibraryExW} leaves, taken right after the call. */
    private static final Linker.Option LAST_ERROR = Linker.Option.captureCallState("GetLastError");

    private static final VarHandle ERROR = Linker.Option.captureStateLayout()
        .varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

    private static final MethodHandle LOAD_LIBRARY;
    private static final MethodHandle GET_PROC;
    private static final MethodHandle FORMAT_MESSAGE;

    static {
      // A name without a path: Windows takes the loaded system library.
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
      Linker linker = Linker.nativeLinker();
      LOAD_LIBRARY = linker.downcallHandle(
          kernel32.findOrThrow("LoadLibraryExW"), LOAD_LIBRARY_EX_W, LAST_ERROR);
      GET_PROC = linker.downcallHandle(kernel32.findOrThrow("GetProcAddress"), GET_PROC_ADDRESS);
      FORMAT_MESSAGE =
          linker.downcallHandle(kernel32.findOrThrow("FormatMessageW"), FORMAT_MESSAGE_W);
    }

    private Kernel32() {}

    /** Loads {@code file} for the life of the process, with the system's search for what it needs. */
    static SymbolLookup load(Path file) {
      MemorySegment module;
      int code;
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
        // Ends in a null character, two zero bytes.
        MemorySegment path = arena.allocateFrom(file.toString(), StandardCharsets.UTF_16LE);
        module = (MemorySegment) LOAD_LIBRARY.invokeExact(state, path, MemorySegment.NULL, 0);
        code = (int) ERROR.get(state, 0L);
      } catch (Throwable t) {
        throw error("Cannot load " + file + ": " + t, t);
      }
      if (module.equals(MemorySegment.NULL)) {
        throw error(
            "Cannot load " + file + ": Windows error " + code + reason(code)
                + ". The file may be damaged, or the system lacks a library it needs.",
            null);
      }
      return name -> {
        try (Arena arena = Arena.ofConfined()) {
          MemorySegment symbol = (MemorySegment)
              GET_PROC.invokeExact(module, arena.allocateFrom(name, StandardCharsets.US_ASCII));
          return symbol.equals(MemorySegment.NULL) ? Optional.empty() : Optional.of(symbol);
        } catch (Throwable t) {
          throw new IllegalStateException("GetProcAddress failed for " + name, t);
        }
      };
    }

    /** The system's text for an error, such as " (The specified module could not be found.)". */
    private static String reason(int code) {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment buffer = arena.allocate(JAVA_CHAR, MESSAGE_CHARS);
        int chars = (int) FORMAT_MESSAGE.invokeExact(
            FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
            MemorySegment.NULL,
            code,
            0,
            buffer,
            MESSAGE_CHARS,
            MemorySegment.NULL);
        if (chars <= 0) {
          return "";
        }
        return " (" + new String(buffer.asSlice(0, 2L * chars).toArray(JAVA_CHAR)).strip() + ")";
      } catch (Throwable t) {
        return ""; // only for the message
      }
    }
  }

  private static boolean hasHash(Path file, String sha256) {
    if (!Files.isRegularFile(file)) {
      return false;
    }
    MessageDigest digest = Manifest.sha256();
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[1 << 16];
      for (int n; (n = in.read(buffer)) > 0; ) {
        digest.update(buffer, 0, n);
      }
    } catch (IOException e) {
      return false;
    }
    return HexFormat.of().formatHex(digest.digest()).equals(sha256);
  }

  /** {@code windows-x64} or {@code linux-x64}, or null on any other system. */
  static String platform(String osName, String osArch) {
    String os = osName.startsWith("Windows") ? "windows" : osName.equals("Linux") ? "linux" : null;
    String arch = switch (osArch) {
      case "amd64", "x86_64" -> "x64";
      default -> null;
    };
    return os == null || arch == null ? null : os + "-" + arch;
  }

  /**
   * The cache folder of the platform: {@code %LOCALAPPDATA%\rethinkj-ui\natives} on Windows, {@code
   * $XDG_CACHE_HOME/rethinkj-ui/natives} or {@code ~/.cache/rethinkj-ui/natives} on Linux, or null
   * if none can be found.
   */
  static Path defaultCache(String platform, Function<String, String> env, String userHome) {
    if (platform == null) {
      return null;
    }
    Path home = absolute(userHome);
    Path base;
    if (platform.startsWith("windows-")) {
      base = absolute(env.apply("LOCALAPPDATA"));
      if (base == null && home != null) {
        base = home.resolve("AppData").resolve("Local");
      }
    } else {
      // The XDG base directory specification says to ignore a relative path.
      base = absolute(env.apply("XDG_CACHE_HOME"));
      if (base == null && home != null) {
        base = home.resolve(".cache");
      }
    }
    return base == null ? null : base.resolve("rethinkj-ui").resolve("natives");
  }

  private static Path absolute(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    try {
      Path result = Path.of(path);
      return result.isAbsolute() ? result : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }

  private static Path property(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Path.of(value);
    } catch (InvalidPathException e) {
      throw error("-D" + name + "=" + value + " is not a valid path: " + e.getMessage(), e);
    }
  }

  private String artifact() {
    return "rethinkj-ui-natives-" + platform;
  }

  private UnsatisfiedLinkError damaged(String what) {
    return error(
        "The natives module " + artifact() + " is damaged: " + what
            + " Delete the JAR from the Maven or Gradle cache and build again.",
        null);
  }

  private static UnsatisfiedLinkError notWritable(Path target, IOException e) {
    return error(
        "Cannot unpack the native libraries into " + target + ": " + e + ". Make that"
            + " folder writable, choose another one with -D" + CACHE_PROPERTY
            + "=<folder>, or unpack"
            + " them yourself and set -D" + DIR_PROPERTY + "=<folder>.",
        e);
  }

  private static UnsatisfiedLinkError error(String message, Throwable cause) {
    UnsatisfiedLinkError error = new UnsatisfiedLinkError(message);
    if (cause != null) {
      error.initCause(cause);
    }
    return error;
  }
}
