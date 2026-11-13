package com.rethinkj.ui.natives;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.natives.Manifest.Library;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Loads the real libraries from the natives module of the platform the tests run on, and calls a
 * version function of each. Skipped where that module is not on the class path: on a platform
 * that has none yet, or when the build runs without its profile.
 */
class NativesLoadTest {

  /** {@code int SDL_GetVersion(void)}: major * 1000000 + minor * 1000 + micro. */
  private static final FunctionDescriptor SDL_GET_VERSION = FunctionDescriptor.of(JAVA_INT);

  /** {@code uint32_t wgpuGetVersion(void)}: the four parts of the release tag, a byte each. */
  private static final FunctionDescriptor WGPU_GET_VERSION = FunctionDescriptor.of(JAVA_INT);

  /** {@code FT_Error FT_Init_FreeType(FT_Library *)}, and {@code FT_Done_FreeType(FT_Library)}. */
  private static final FunctionDescriptor FT_INIT_FREETYPE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);

  private static final FunctionDescriptor FT_DONE_FREETYPE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);

  /** {@code void FT_Library_Version(FT_Library, FT_Int *major, FT_Int *minor, FT_Int *patch)}. */
  private static final FunctionDescriptor FT_LIBRARY_VERSION =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);

  /** {@code FT_TrueTypeEngineType FT_Get_TrueType_Engine_Type(FT_Library)}. */
  private static final FunctionDescriptor FT_GET_TRUETYPE_ENGINE_TYPE =
      FunctionDescriptor.of(JAVA_INT, ADDRESS);
  /** What it returns when FreeType has the TrueType bytecode interpreter. */
  private static final int FT_TRUETYPE_ENGINE_TYPE_PATENTED = 2;

  /** {@code FT_Module FT_Get_Module(FT_Library, const char *name)}. */
  private static final FunctionDescriptor FT_GET_MODULE =
      FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);

  /** {@code const char *hb_version_string(void)}. */
  private static final FunctionDescriptor HB_VERSION_STRING = FunctionDescriptor.of(ADDRESS);

  /**
   * Folders for the tests' own caches. Libraries stay loaded until the JVM exits, and Windows
   * cannot delete a loaded library, so these are left for {@code mvn clean}.
   */
  private static final Path WORK = Path.of("target", "natives-test");

  private final String osName = System.getProperty("os.name");
  private final String osArch = System.getProperty("os.arch");
  private Manifest manifest;

  @BeforeEach
  void needsTheNativesModule() throws IOException {
    String platform = Natives.platform(osName, osArch);
    assumeTrue(
        platform != null,
        "Skipped: rethinkj UI has no native libraries for " + osName + " on " + osArch);
    String resource = Natives.RESOURCES + platform + "/" + Natives.MANIFEST;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      assumeTrue(
          in != null,
          "Skipped: rethinkj-ui-natives-" + platform + " is not on the test"
              + " class path. It is built only on " + platform + ", by the profile natives-"
              + platform
              + ", and not every platform has one yet.");
      manifest = Manifest.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
    }
  }

  @Test
  void loadsBothLibrariesAndTheirVersionsMatchTheManifest() {
    // The instance for the whole process, which unpacks into the cache the build configures.
    assertVersions(Natives::lookup);
  }

  @Test
  void theFolderPropertyLoadsFromTheFolderAndUnpacksNothing() throws IOException {
    // A folder as a packager makes it: the platform's files from the natives JAR.
    Path dir = Files.createDirectories(fresh("dir"));
    copy(Natives.MANIFEST, dir);
    for (Library library : manifest.libraries()) {
      copy(library.file(), dir);
    }
    Path cache = fresh("cache");

    Natives natives = new Natives(osName, osArch, dir, cache, new URLClassLoader(new URL[0], null));
    assertVersions(natives::find);

    assertFalse(Files.exists(cache));
  }

  @Test
  void replacesADamagedLibraryInTheCache() throws IOException {
    Path cache = fresh("cache");
    Path folder = Files.createDirectories(cache.resolve(manifest.folderName()));
    Library sdl = manifest.library("sdl3");
    Files.writeString(folder.resolve(sdl.file()), "not a library");

    assertVersions(natives(cache)::find);

    assertEquals(sdl.sha256(), sha256(folder.resolve(sdl.file())));
  }

  @Test
  void concurrentFirstUseWithAnEmptyCache() throws Exception {
    Path cache = fresh("cache");
    int threads = 8;
    Natives shared = natives(cache);
    CyclicBarrier start = new CyclicBarrier(threads);
    List<Future<?>> runs = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        // Half of the threads share one instance; each of the others has its own, like a process.
        Natives natives = i % 2 == 0 ? shared : natives(cache);
        runs.add(pool.submit(() -> {
          start.await();
          assertVersions(natives::find);
          return null;
        }));
      }
      for (Future<?> run : runs) {
        run.get(1, TimeUnit.MINUTES);
      }
    }
    Set<String> files = manifest.libraries().stream().map(Library::file).collect(toSet());
    try (Stream<Path> unpacked = Files.list(cache.resolve(manifest.folderName()))) {
      assertEquals(files, unpacked.map(file -> file.getFileName().toString()).collect(toSet()));
    }
  }

  @Test
  void onLinuxAFailedLoadGivesTheReasonOfTheDynamicLinker() throws IOException {
    assumeTrue(osName.equals("Linux"), "Skipped: the reason comes from Linux's dynamic linker");
    Path dir = Files.createDirectories(fresh("junk"));
    Files.writeString(dir.resolve("junk.so"), "not a library");
    Files.writeString(
        dir.resolve(Natives.MANIFEST),
        "rethinkj-ui-natives 1\nplatform linux-x64\n" + "library junk 1 junk.so " + "0".repeat(64)
            + "\n");
    Natives natives = new Natives(osName, osArch, dir, null, new URLClassLoader(new URL[0], null));

    UnsatisfiedLinkError error =
        assertThrows(UnsatisfiedLinkError.class, () -> natives.find("junk"));

    // The dynamic linker's reason, in the language of the system: "<file>: file too short".
    String message = error.getMessage();
    assertTrue(message.contains("(" + dir.resolve("junk.so") + ": "), message);
    assertTrue(message.contains("mounted noexec"), message);
  }

  private void assertVersions(Function<String, SymbolLookup> lookup) {
    int sdl = (int) call(lookup.apply("sdl3"), "SDL_GetVersion", SDL_GET_VERSION);
    assertEquals(
        manifest.library("sdl3").version(),
        sdl / 1_000_000 + "." + sdl / 1000 % 1000 + "." + sdl % 1000,
        "SDL_GetVersion");

    int wgpu = (int) call(lookup.apply("wgpu_native"), "wgpuGetVersion", WGPU_GET_VERSION);
    assertEquals(
        manifest.library("wgpu_native").version(),
        (wgpu >>> 24) + "." + (wgpu >>> 16 & 0xFF) + "." + (wgpu >>> 8 & 0xFF) + "."
            + (wgpu & 0xFF),
        "wgpuGetVersion");

    assertEquals(
        manifest.library("freetype").version(),
        freetype(lookup.apply("freetype")),
        "FT_Library_Version");

    MemorySegment harfbuzz =
        (MemorySegment) call(lookup.apply("harfbuzz"), "hb_version_string", HB_VERSION_STRING);
    assertEquals(
        manifest.library("harfbuzz").version(),
        harfbuzz.reinterpret(Integer.MAX_VALUE).getString(0),
        "hb_version_string");
  }

  /**
   * The version that FreeType's {@code FT_Library_Version} reports, after checking that this build
   * has the TrueType bytecode interpreter and the auto-hinter.
   */
  private static String freetype(SymbolLookup freetype) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ADDRESS);
      assertEquals(0, call(freetype, "FT_Init_FreeType", FT_INIT_FREETYPE, out));
      MemorySegment library = out.get(ADDRESS, 0);
      try {
        assertEquals(
            FT_TRUETYPE_ENGINE_TYPE_PATENTED,
            call(freetype, "FT_Get_TrueType_Engine_Type", FT_GET_TRUETYPE_ENGINE_TYPE, library),
            "FreeType without the TrueType bytecode interpreter");
        MemorySegment autofitter = arena.allocateFrom("autofitter");
        assertNotEquals(
            MemorySegment.NULL,
            call(freetype, "FT_Get_Module", FT_GET_MODULE, library, autofitter),
            "FreeType without the auto-hinter");
        MemorySegment version = arena.allocate(JAVA_INT, 3);
        call(
            freetype,
            "FT_Library_Version",
            FT_LIBRARY_VERSION,
            library,
            version,
            version.asSlice(4),
            version.asSlice(8));
        return version.getAtIndex(JAVA_INT, 0) + "." + version.getAtIndex(JAVA_INT, 1) + "."
            + version.getAtIndex(JAVA_INT, 2);
      } finally {
        call(freetype, "FT_Done_FreeType", FT_DONE_FREETYPE, library);
      }
    }
  }

  private static Object call(
      SymbolLookup library, String function, FunctionDescriptor descriptor, Object... arguments) {
    MethodHandle handle =
        Linker.nativeLinker().downcallHandle(library.find(function).orElseThrow(), descriptor);
    try {
      return handle.invokeWithArguments(arguments);
    } catch (Throwable t) {
      throw new AssertionError(function + " failed", t);
    }
  }

  private Natives natives(Path cache) {
    return new Natives(osName, osArch, null, cache, getClass().getClassLoader());
  }

  private void copy(String file, Path dir) throws IOException {
    String resource = Natives.RESOURCES + manifest.platform() + "/" + file;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      Files.copy(in, dir.resolve(file));
    }
  }

  private static Path fresh(String name) {
    return WORK.resolve(name + "-" + UUID.randomUUID()).toAbsolutePath();
  }

  private static String sha256(Path file) throws IOException {
    return HexFormat.of().formatHex(Manifest.sha256().digest(Files.readAllBytes(file)));
  }
}
