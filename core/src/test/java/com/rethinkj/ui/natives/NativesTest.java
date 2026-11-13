package com.rethinkj.ui.natives;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.natives.Manifest.Library;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules for finding and unpacking the libraries, with natives modules made up by the test.
 * Nothing is loaded, so these run on every platform; {@link NativesLoadTest} loads real ones.
 */
class NativesTest {

  private static final String HEADER = "rethinkj-ui-natives 1\nplatform windows-x64\n";
  private static final String A = "a".repeat(64);
  private static final String B = "b".repeat(64);
  private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);

  /** Two made-up libraries; the second is large, so that concurrent writes overlap. */
  private static final byte[] FIRST = "the first library".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SECOND = new byte[4 << 20];

  static {
    new Random(1).nextBytes(SECOND);
  }

  @TempDir Path temp;

  @Test
  void readsTheManifestInLoadOrder() {
    Manifest manifest = Manifest.parse("""
        # The native libraries for windows-x64, in load order.
        rethinkj-ui-natives 1
        platform windows-x64

        library sdl3 3.4.20 SDL3.dll %s
        added-later by a later version of format 1
        library wgpu_native 29.0.1.1 wgpu_native.dll %s
        """.formatted(A, B), "test");

    assertEquals("windows-x64", manifest.platform());
    assertEquals(
        List.of(
            new Library("sdl3", "3.4.20", "SDL3.dll", A),
            new Library("wgpu_native", "29.0.1.1", "wgpu_native.dll", B)),
        manifest.libraries());
    assertTrue(manifest.folderName().matches("windows-x64-[0-9a-f]{16}"), manifest.folderName());

    Manifest other = Manifest.parse(HEADER
        + "library sdl3 3.4.20 SDL3.dll " + B + "\n"
        + "library wgpu_native 29.0.1.1 wgpu_native.dll " + B, "test");
    assertNotEquals(manifest.folderName(), other.folderName());

    // Written on Windows by an editor that puts a byte order mark first and ends lines with CR LF.
    Manifest edited = Manifest.parse(BYTE_ORDER_MARK + HEADER.replace("\n", "\r\n")
        + "library sdl3 3.4.20 SDL3.dll " + B + "\r\n", "test");
    assertEquals(List.of(new Library("sdl3", "3.4.20", "SDL3.dll", B)), edited.libraries());
  }

  @Test
  void rejectsManifestsItCannotRead() {
    assertMessage(() -> Manifest.parse("rethinkj-ui-natives 2\nplatform windows-x64\n", "m"),
        "m, line 1: the manifest has format 2", "same version of rethinkj-ui-core");
    assertMessage(() -> Manifest.parse("platform windows-x64\n", "m"),
        "expected 'rethinkj-ui-natives 1'");
    assertMessage(() -> Manifest.parse(HEADER + "library a 1 ../a.dll " + A, "m"),
        "line 3: expected 'library <name> <version> <file> <sha256>'");
    assertMessage(() -> Manifest.parse(HEADER + "library a 1 a.dll 0123", "m"), "line 3");
    assertMessage(
        () -> Manifest.parse(HEADER + "library a 1 a.dll " + A + "\nlibrary a 1 b.dll " + B, "m"),
        "line 4: library a or file b.dll is listed twice");
    assertMessage(() -> Manifest.parse(HEADER, "m"), "m names no platform or no library");
    assertMessage(() -> Manifest.parse("# nothing\n", "m"), "m is empty");
  }

  @Test
  void namesThePlatform() {
    assertEquals("windows-x64", Natives.platform("Windows 11", "amd64"));
    assertEquals("linux-x64", Natives.platform("Linux", "amd64"));
    assertEquals("linux-x64", Natives.platform("Linux", "x86_64"));
    assertNull(Natives.platform("Linux", "aarch64"));
    assertNull(Natives.platform("Mac OS X", "aarch64"));
  }

  @Test
  void findsTheCacheFolderOfThePlatform() {
    String home = temp.resolve("home").toString();
    Map<String, String> env = Map.of(
        "LOCALAPPDATA", temp.resolve("local").toString(),
        "XDG_CACHE_HOME", temp.resolve("xdg").toString());
    Map<String, String> none = Map.of();

    assertEquals(temp.resolve("local/rethinkj-ui/natives"),
        Natives.defaultCache("windows-x64", env::get, home));
    assertEquals(temp.resolve("home/AppData/Local/rethinkj-ui/natives"),
        Natives.defaultCache("windows-x64", none::get, home));
    assertEquals(temp.resolve("xdg/rethinkj-ui/natives"),
        Natives.defaultCache("linux-x64", env::get, home));
    // A relative XDG_CACHE_HOME is ignored, as the XDG base directory specification says.
    assertEquals(temp.resolve("home/.cache/rethinkj-ui/natives"),
        Natives.defaultCache("linux-x64", Map.of("XDG_CACHE_HOME", "cache")::get, home));
    assertNull(Natives.defaultCache("linux-x64", none::get, null));
    assertNull(Natives.defaultCache(null, env::get, home));
  }

  @Test
  void anUnsupportedPlatformSaysWhichOnesAreSupported() {
    Natives natives = new Natives("Mac OS X", "aarch64", null, temp, nothing());

    assertMessage(() -> natives.find("sdl3"),
        "no native libraries for Mac OS X on aarch64", "windows-x64 and linux-x64",
        Natives.DIR_PROPERTY);
  }

  @Test
  void aMissingNativesModuleNamesTheArtifactToAdd() {
    Path cache = temp.resolve("cache");
    Natives natives = new Natives("Windows 11", "amd64", null, cache, nothing());

    assertMessage(() -> natives.find("sdl3"),
        "No native libraries for windows-x64 on the class path",
        "Add the artifact com.rethinkj.ui:rethinkj-ui-natives-windows-x64",
        "--add-modules com.rethinkj.ui.natives.windows.x64",
        "-D" + Natives.DIR_PROPERTY);
    assertFalse(Files.exists(cache));
  }

  @Test
  void unpacksIntoAFolderNamedAfterTheHashes() throws IOException {
    ClassLoader module = module(temp.resolve("jar"));
    Path cache = temp.resolve("cache");

    Path folder = natives(module, cache).prepare();

    assertEquals(cache.resolve(manifest(temp.resolve("jar")).folderName()), folder);
    assertUnpacked(folder);
  }

  @Test
  void reusesACachedFileWithTheRightHash() throws IOException {
    ClassLoader module = module(temp.resolve("jar"));
    Path cache = temp.resolve("cache");
    Path folder = natives(module, cache).prepare();
    FileTime written = FileTime.fromMillis(946_684_800_000L);
    Files.setLastModifiedTime(folder.resolve("second.so"), written);

    // A new instance stands for the next start of the program.
    assertEquals(folder, natives(module, cache).prepare());

    assertEquals(written, Files.getLastModifiedTime(folder.resolve("second.so")));
    assertUnpacked(folder);
  }

  @Test
  void replacesACachedFileWithTheWrongHash() throws IOException {
    ClassLoader module = module(temp.resolve("jar"));
    Path cache = temp.resolve("cache");
    Path folder = natives(module, cache).prepare();
    Files.writeString(folder.resolve("first.so"), "damaged");
    Files.write(folder.resolve("second.so"), new byte[] {1, 2, 3});

    natives(module, cache).prepare();

    assertUnpacked(folder);
  }

  @Test
  void concurrentFirstUseWithAnEmptyCacheUnpacksEachFileIntact() throws Exception {
    ClassLoader module = module(temp.resolve("jar"));
    Path cache = temp.resolve("cache");
    Path expected = cache.resolve(manifest(temp.resolve("jar")).folderName());
    int threads = 16;
    Natives shared = natives(module, cache);
    CyclicBarrier start = new CyclicBarrier(threads);
    List<Future<Path>> runs = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        // Half of the threads share one instance; each of the others has its own, like a process.
        Natives natives = i % 2 == 0 ? shared : natives(module, cache);
        runs.add(pool.submit(() -> {
          start.await();
          return natives.prepare();
        }));
      }
      for (Future<Path> run : runs) {
        assertEquals(expected, run.get(1, TimeUnit.MINUTES));
      }
    }
    assertUnpacked(expected);
  }

  @Test
  void theFolderPropertyWinsAndNothingIsUnpacked() throws IOException {
    module(temp.resolve("jar"));
    // The folder as the natives JAR has it, manifest included.
    Path dir = temp.resolve("jar").resolve(Natives.RESOURCES + "linux-x64");
    // Its files are not checked against their hashes: a packager may sign them.
    Files.writeString(dir.resolve("first.so"), "signed by the packager");
    Path cache = temp.resolve("cache");

    Natives natives = new Natives("Linux", "amd64", dir, cache, nothing());

    assertEquals(dir, natives.prepare());
    assertFalse(Files.exists(cache));
  }

  @Test
  void theFolderPropertySaysWhatTheFolderLacks() throws IOException {
    Path empty = Files.createDirectories(temp.resolve("empty"));
    Natives natives = new Natives("Linux", "amd64", empty, temp.resolve("cache"), nothing());

    assertMessage(natives::prepare,
        "-D" + Natives.DIR_PROPERTY + " names " + empty + ", which has no manifest.txt",
        "META-INF/rethinkj-ui/natives/<platform>/");

    module(temp.resolve("jar"));
    Path dir = temp.resolve("jar").resolve(Natives.RESOURCES + "linux-x64");
    Files.delete(dir.resolve("second.so"));
    Natives incomplete = new Natives("Linux", "amd64", dir, temp.resolve("cache"), nothing());
    assertMessage(incomplete::prepare, "has no second.so, which its manifest.txt lists");
  }

  @Test
  void aDamagedNativesModuleIsReported() throws IOException {
    ClassLoader module = module(temp.resolve("jar"));
    Path jar = temp.resolve("jar").resolve(Natives.RESOURCES + "linux-x64");
    Files.writeString(jar.resolve("second.so"), "cut");
    Path cache = temp.resolve("cache");

    assertMessage(() -> natives(module, cache).prepare(),
        "rethinkj-ui-natives-linux-x64 is damaged",
        "second.so has the SHA-256",
        "Delete the JAR");
    // Nothing is left in place of the damaged file, not even a temporary one.
    Path folder = cache.resolve(manifest(temp.resolve("jar")).folderName());
    assertEquals(Set.of("first.so"), names(folder));
  }

  @Test
  void anUnwritableCacheIsReported() throws IOException {
    ClassLoader module = module(temp.resolve("jar"));
    Path cache = temp.resolve("cache");
    Files.writeString(cache, "a file where the cache folder belongs");

    assertMessage(() -> natives(module, cache).prepare(),
        "Cannot unpack the native libraries into " + cache,
        "-D" + Natives.CACHE_PROPERTY + "=<folder>",
        "-D" + Natives.DIR_PROPERTY + "=<folder>");
  }

  @Test
  void anUnknownLibraryIsReportedWithTheKnownOnes() throws IOException {
    Natives natives = natives(module(temp.resolve("jar")), temp.resolve("cache"));

    assertMessage(() -> natives.find("vulkan"),
        "There is no native library 'vulkan'", "lists first, second");
  }

  private static Natives natives(ClassLoader module, Path cache) {
    return new Natives("Linux", "amd64", null, cache, module);
  }

  /** Writes a natives module for linux-x64 with two libraries into {@code root}. */
  private static ClassLoader module(Path root) throws IOException {
    Path folder = Files.createDirectories(root.resolve(Natives.RESOURCES + "linux-x64"));
    Files.write(folder.resolve("first.so"), FIRST);
    Files.write(folder.resolve("second.so"), SECOND);
    Files.writeString(folder.resolve(Natives.MANIFEST), "rethinkj-ui-natives 1\n"
        + "platform linux-x64\n"
        + "library first 1.0 first.so " + sha256(FIRST) + "\n"
        + "library second 2.0 second.so " + sha256(SECOND) + "\n");
    return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
  }

  private static Manifest manifest(Path root) throws IOException {
    Path file = root.resolve(Natives.RESOURCES + "linux-x64/" + Natives.MANIFEST);
    return Manifest.parse(Files.readString(file), file.toString());
  }

  /** A class loader that finds nothing: no natives module. */
  private static ClassLoader nothing() {
    return new URLClassLoader(new URL[0], null);
  }

  private static void assertUnpacked(Path folder) throws IOException {
    assertArrayEquals(FIRST, Files.readAllBytes(folder.resolve("first.so")));
    assertArrayEquals(SECOND, Files.readAllBytes(folder.resolve("second.so")));
    assertEquals(Set.of("first.so", "second.so"), names(folder), "no temporary files are left");
  }

  private static Set<String> names(Path folder) throws IOException {
    try (Stream<Path> files = Files.list(folder)) {
      return files.map(file -> file.getFileName().toString()).collect(Collectors.toSet());
    }
  }

  private static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(Manifest.sha256().digest(bytes));
  }

  private static void assertMessage(Executable executable, String... parts) {
    Throwable thrown = assertThrows(Throwable.class, executable);
    assertTrue(thrown instanceof UnsatisfiedLinkError || thrown instanceof IllegalArgumentException,
        thrown::toString);
    for (String part : parts) {
      assertTrue(thrown.getMessage().contains(part),
          () -> "'" + part + "' is missing from: " + thrown.getMessage());
    }
  }
}
