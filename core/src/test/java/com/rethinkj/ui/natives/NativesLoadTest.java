package com.rethinkj.ui.natives;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.natives.Manifest.Library;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
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
    assumeTrue(platform != null,
        "Skipped: rethinkj UI has no native libraries for " + osName + " on " + osArch);
    String resource = Natives.RESOURCES + platform + "/" + Natives.MANIFEST;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      assumeTrue(in != null, "Skipped: rethinkj-ui-natives-" + platform + " is not on the test"
          + " class path. It is built only on " + platform + ", by the profile natives-" + platform
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

  private void assertVersions(Function<String, SymbolLookup> lookup) {
    int sdl = call(lookup.apply("sdl3"), "SDL_GetVersion", SDL_GET_VERSION);
    assertEquals(manifest.library("sdl3").version(),
        sdl / 1_000_000 + "." + sdl / 1000 % 1000 + "." + sdl % 1000, "SDL_GetVersion");

    int wgpu = call(lookup.apply("wgpu_native"), "wgpuGetVersion", WGPU_GET_VERSION);
    assertEquals(manifest.library("wgpu_native").version(),
        (wgpu >>> 24) + "." + (wgpu >>> 16 & 0xFF) + "." + (wgpu >>> 8 & 0xFF) + "."
            + (wgpu & 0xFF),
        "wgpuGetVersion");
  }

  private static int call(SymbolLookup library, String function, FunctionDescriptor descriptor) {
    MethodHandle handle =
        Linker.nativeLinker().downcallHandle(library.find(function).orElseThrow(), descriptor);
    try {
      return (int) handle.invokeExact();
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
