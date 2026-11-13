import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fetches the native libraries of a natives module, so that the module can package them.
 *
 * <p>Usage: {@code java scripts/FetchNatives.java <module-folder>}, for example {@code java
 * scripts/FetchNatives.java natives-windows-x64}. The module's build runs it by itself.
 *
 * <p>It reads {@code <module-folder>/natives.properties}, which pins each library: its version,
 * the URL of the archive it comes from, the SHA-256 of that archive and the library's path inside
 * it. It downloads each archive into {@code fetched/archives/}, unless one with the pinned hash is
 * already there, unpacks the libraries into {@code
 * fetched/resources/META-INF/rethinkj-ui/natives/<platform>/} and writes {@code manifest.txt}
 * beside them. When {@code fetched/} already matches {@code natives.properties}, it does nothing.
 *
 * <p>A library with a {@code <name>.cmake} line comes from a source archive instead: the tool
 * builds it with cmake and make in {@code fetched/builds/<name>/}, with those options, and takes
 * the entry from the build folder. A build is reused while the archive, the options and the entry
 * stay the same. It needs cmake, make, a C compiler and pkg-config, and fails with what to install
 * when one is missing; a library in C++ needs a C++ compiler too. A source archive may also be a
 * {@code .tar.xz} file, which {@code tar} unpacks.
 *
 * <p>The manifest lists the libraries in load order with their versions and SHA-256 hashes. A
 * library built from source gets a {@code built <name> <archive sha256>} line as well: its own
 * hash differs from machine to machine and is pinned nowhere. {@code
 * com.rethinkj.ui.natives.Manifest} in core reads the manifest and ignores that line.
 *
 * <p>Needs only a JDK 25 and runs the same on Windows and Linux; a build from source, only on
 * Linux.
 */
final class FetchNatives {

  private static final String RESOURCES = "META-INF/rethinkj-ui/natives/";
  private static final String MANIFEST = "manifest.txt";
  private static final String FORMAT = "rethinkj-ui-natives 1";

  private static final Pattern PLATFORM = Pattern.compile("[a-z0-9]+-[a-z0-9]+");
  private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._+-]+");
  private static final Pattern ENTRY = Pattern.compile("([A-Za-z0-9._+-]+/)*[A-Za-z0-9._+-]+");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern CMAKE_OPTION = Pattern.compile("-D[A-Za-z0-9_]+=[A-Za-z0-9._+-]*");
  // How SDL's cmake reports an option: "--   SDL_X11   (Wanted: ON): OFF".
  private static final Pattern CMAKE_RESULT =
      Pattern.compile("--\\s+(\\S+)\\s+\\(Wanted: ON\\): (\\S+)");

  /** {@code cmake} is null for a release archive, else the options to build the source with. */
  record Library(String name, String version, URI archive, String archiveSha256, String entry,
      List<String> cmake) {

    boolean built() {
      return cmake != null;
    }

    String file() {
      return entry.substring(entry.lastIndexOf('/') + 1);
    }

    String archiveName() {
      String path = archive.getPath();
      return path.substring(path.lastIndexOf('/') + 1);
    }
  }

  /** A failure whose message says what to do about it. */
  static final class Failure extends Exception {
    Failure(String message) {
      super(message);
    }
  }

  private static HttpClient client;

  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length != 1) {
      System.err.println("Usage: java scripts/FetchNatives.java <module-folder>");
      System.exit(2);
    }
    // Lets the HTTP client use the proxy configured in the operating system.
    if (System.getProperty("java.net.useSystemProxies") == null) {
      System.setProperty("java.net.useSystemProxies", "true");
    }
    try {
      fetch(Path.of(args[0]).toAbsolutePath().normalize());
    } catch (Failure e) {
      System.err.println();
      System.err.println(e.getMessage());
      System.err.println();
      System.exit(1);
    }
  }

  static void fetch(Path module) throws IOException, InterruptedException, Failure {
    String label = module.getFileName().toString();
    Path config = module.resolve("natives.properties");
    if (!Files.isRegularFile(config)) {
      throw new Failure(config + " does not exist. Pass the folder of a natives module, such as"
          + " natives-windows-x64.");
    }
    byte[] configBytes = Files.readAllBytes(config);
    Properties properties = new Properties();
    properties.load(new StringReader(new String(configBytes, StandardCharsets.UTF_8)));

    String platform = value(properties, config, "platform", PLATFORM);
    List<Library> libraries = new ArrayList<>();
    for (String name : value(properties, config, "libraries", null).split("\\s+")) {
      if (!NAME.matcher(name).matches()) {
        throw new Failure(config + ": '" + name + "' in libraries is not a library name"
            + " (lower-case letters, digits and _).");
      }
      List<String> cmake = null;
      if (properties.getProperty(name + ".cmake") != null) {
        cmake = List.of(value(properties, config, name + ".cmake", null).split("\\s+"));
        for (String option : cmake) {
          if (!CMAKE_OPTION.matcher(option).matches()) {
            throw new Failure(config + ": '" + option + "' in " + name + ".cmake is not an option"
                + " -D<name>=<value>.");
          }
        }
      }
      Library library = new Library(
          name,
          value(properties, config, name + ".version", TOKEN),
          uri(config, name + ".archive", value(properties, config, name + ".archive", null),
              cmake != null),
          value(properties, config, name + ".archive.sha256", SHA256),
          value(properties, config, name + ".entry", ENTRY),
          cmake);
      if (library.file().equals(MANIFEST)) {
        throw new Failure(config + ": a library cannot be called " + MANIFEST + ".");
      }
      libraries.add(library);
    }

    Path fetched = module.resolve("fetched");
    Path resources = fetched.resolve("resources");
    Path folder = resources.resolve(RESOURCES + platform);
    // The hash of the natives.properties that fetched/ was made from.
    Path stamp = fetched.resolve("natives.properties.sha256");
    String source = hex(digest().digest(configBytes));
    if (upToDate(stamp, source, folder, libraries)) {
      System.out.println(label + ": native libraries are up to date");
      return;
    }

    Path archives = fetched.resolve("archives");
    Files.createDirectories(archives);
    for (Library library : libraries) {
      download(label, library, archives);
    }

    Path staging = Files.createTempDirectory(fetched, "resources-");
    try {
      Path out = staging.resolve(RESOURCES + platform);
      Files.createDirectories(out);
      StringBuilder manifest = new StringBuilder()
          .append("# The native libraries for ").append(platform).append(", in load order.\n")
          .append("# library <name> <version> <file> <sha256>\n");
      if (libraries.stream().anyMatch(Library::built)) {
        manifest.append("# built <name> <sha256 of the source archive>: built from source by the"
            + " machine that wrote\n# this file, so the library's own hash is pinned nowhere.\n");
      }
      manifest.append(FORMAT).append('\n')
          .append("platform ").append(platform).append('\n');
      for (Library library : libraries) {
        Path file = out.resolve(library.file());
        Path archive = archives.resolve(library.archiveName());
        if (library.built()) {
          Files.copy(build(label, config, library, archive, fetched.resolve("builds")), file);
        } else {
          try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(library.entry());
            if (entry == null || entry.isDirectory()) {
              throw new Failure(config + ": " + library.archiveName() + " has no file "
                  + library.entry() + ". Correct " + library.name() + ".entry.");
            }
            try (InputStream in = zip.getInputStream(entry)) {
              Files.copy(in, file);
            }
          }
        }
        String soname = soname(file);
        if (soname != null && !soname.equals(library.file())) {
          throw new Failure(config + ": " + library.file() + " has the SONAME " + soname
              + ". Correct " + library.name() + ".entry so that its file name is " + soname
              + "; a library that needs it looks for that name.");
        }
        String sha256 = sha256(file);
        manifest.append("library ").append(library.name()).append(' ').append(library.version())
            .append(' ').append(library.file()).append(' ').append(sha256).append('\n');
        if (library.built()) {
          manifest.append("built ").append(library.name()).append(' ')
              .append(library.archiveSha256()).append('\n');
        }
        System.out.printf(Locale.ROOT, "%s: %s %s, %s, %,d bytes, sha256 %s%n", label,
            library.name(), library.version(), library.file(), Files.size(file), sha256);
      }
      Files.writeString(out.resolve(MANIFEST), manifest);

      Files.deleteIfExists(stamp);
      deleteTree(resources);
      Files.move(staging, resources);
      staging = null;
      Files.writeString(stamp, source + "\n");
    } finally {
      if (staging != null) {
        deleteTree(staging);
      }
    }
  }

  private static boolean upToDate(Path stamp, String source, Path folder, List<Library> libraries)
      throws IOException {
    Path manifest = folder.resolve(MANIFEST);
    if (!Files.isRegularFile(stamp)
        || !Files.readString(stamp).trim().equals(source)
        || !Files.isRegularFile(manifest)) {
      return false;
    }
    Map<String, String> hashes = new HashMap<>();
    for (String line : Files.readAllLines(manifest)) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length == 5 && fields[0].equals("library")) {
        hashes.put(fields[3], fields[4]);
      }
    }
    for (Library library : libraries) {
      Path file = folder.resolve(library.file());
      String hash = hashes.get(library.file());
      if (hash == null || !Files.isRegularFile(file) || !hash.equals(sha256(file))) {
        return false;
      }
    }
    return true;
  }

  private static void download(String label, Library library, Path archives)
      throws IOException, InterruptedException, Failure {
    Path archive = archives.resolve(library.archiveName());
    if (Files.isRegularFile(archive)) {
      if (sha256(archive).equals(library.archiveSha256())) {
        return;
      }
      System.out.println(label + ": " + archive + " lacks the pinned SHA-256, replacing it");
    }
    System.out.println(label + ": downloading " + library.archive());
    Path part = Files.createTempFile(archives, library.archiveName() + ".", ".part");
    try {
      HttpRequest request = HttpRequest.newBuilder(library.archive())
          .timeout(Duration.ofMinutes(2))
          .header("User-Agent", "FetchNatives")
          .build();
      // GitHub's download servers fail now and then with a 5xx status; those are tried again.
      for (int attempt = 1; ; attempt++) {
        String failure;
        try {
          HttpResponse<Path> response = client().send(request, HttpResponse.BodyHandlers.ofFile(
              part, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
          if (response.statusCode() == 200) {
            break;
          }
          failure = "HTTP status " + response.statusCode() + " from " + response.uri();
          if (response.statusCode() < 500) {
            throw cannotDownload(label, library, archive, failure);
          }
        } catch (IOException e) {
          failure = describe(e);
        }
        if (attempt == 3) {
          throw cannotDownload(label, library, archive, failure);
        }
        System.out.println(label + ": " + failure + ", trying again");
        Thread.sleep(attempt * 2000L);
      }
      String actual = sha256(part);
      if (!actual.equals(library.archiveSha256())) {
        throw new Failure(label + ": the archive downloaded from " + library.archive() + "\n"
            + "has the SHA-256 " + actual + ",\n"
            + "but natives.properties pins " + library.archiveSha256() + ".\n"
            + "Either the download was damaged, then build again, or the release was replaced."
            + " Change the pinned value only after checking the new archive.");
      }
      Files.move(part, archive, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(part);
    }
  }

  /**
   * Builds the library from its source archive with cmake and make in {@code builds/<name>/} and
   * returns the built file. A build of the same archive, options and entry is reused.
   */
  private static Path build(String label, Path config, Library library, Path archive, Path builds)
      throws IOException, InterruptedException, Failure {
    Path work = builds.resolve(library.name());
    Path built = work.resolve(library.file());
    Path stamp = work.resolve("inputs.txt");
    String inputs = library.archiveSha256() + "\n" + String.join(" ", library.cmake()) + "\n"
        + library.entry() + "\n";
    if (Files.isRegularFile(built) && Files.isRegularFile(stamp)
        && Files.readString(stamp).equals(inputs)) {
      System.out.println(label + ": " + library.name() + " is already built");
      return built;
    }
    String cc = System.getenv("CC") == null || System.getenv("CC").isBlank()
        ? "cc" : System.getenv("CC").trim().split("\\s+")[0];
    require(label, library, "cmake", "CMake 3.16 or later", "cmake");
    require(label, library, "make", "make", "make");
    require(label, library, cc, "a C compiler", "build-essential");
    require(label, library, "pkg-config", "pkg-config", "pkg-config");

    deleteTree(work);
    Path source = Files.createDirectories(work.resolve("source"));
    Path tree = work.resolve("build");
    Path log = work.resolve("build.log");
    long start = System.nanoTime();
    System.out.println(label + ": building " + library.name() + " " + library.version() + " from "
        + library.archiveName() + ", log in " + log);
    if (library.archiveName().endsWith(".tar.xz")) {
      // The JDK cannot read xz. GNU tar strips a leading / and refuses names that contain "..".
      run(label, library,
          List.of("tar", "-xJf", archive.toString(), "-C", source.toString()), log);
    } else {
      unzip(archive, source);
    }
    // A source archive usually holds one folder with everything in it.
    Path root = source;
    try (Stream<Path> children = Files.list(source)) {
      List<Path> list = children.toList();
      if (!Files.exists(source.resolve("CMakeLists.txt")) && list.size() == 1) {
        root = list.getFirst();
      }
    }
    List<String> configure = new ArrayList<>(List.of(
        "cmake", "-S", root.toString(), "-B", tree.toString(), "-G", "Unix Makefiles"));
    configure.addAll(library.cmake());
    run(label, library, configure, log);
    List<String> off = new ArrayList<>();
    for (String line : read(log)) {
      Matcher result = CMAKE_RESULT.matcher(line.strip());
      if (result.matches() && !result.group(2).equals("ON")
          && library.cmake().contains("-D" + result.group(1) + "=ON")) {
        off.add(result.group(1));
      }
    }
    if (!off.isEmpty()) {
      throw new Failure(label + ": cmake turned off " + String.join(", ", off) + ", which "
          + library.name() + ".cmake sets ON, because development files are missing.\n"
          + "The log says which: " + log + "\n"
          + config + " names the packages to install on Debian and Ubuntu.\n"
          + "Install them and build again, or build without the native libraries:"
          + " mvn verify -P '!" + label + "'");
    }
    run(label, library, List.of(
        "make", "-C", tree.toString(), "-j" + Runtime.getRuntime().availableProcessors()), log);
    Path entry = tree.resolve(library.entry());
    if (!Files.isRegularFile(entry)) {
      throw new Failure(config + ": the build of " + library.name() + " made no file "
          + library.entry() + ". Correct " + library.name() + ".entry.");
    }
    Files.copy(entry, built); // follows a symbolic link such as libSDL3.so.0
    deleteTree(source);
    deleteTree(tree);
    Files.writeString(stamp, inputs);
    System.out.printf(Locale.ROOT, "%s: built %s in %d s%n",
        label, library.name(), (System.nanoTime() - start) / 1_000_000_000L);
    return built;
  }

  /** Fails with what to install if {@code tool --version} cannot be run. */
  private static void require(String label, Library library, String tool, String what,
      String debian) throws InterruptedException, Failure {
    try {
      Process process = new ProcessBuilder(tool, "--version")
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start();
      if (process.waitFor() == 0) {
        return;
      }
    } catch (IOException e) {
      // not installed, or not on the PATH
    }
    throw new Failure(label + ": building " + library.name() + " needs " + what + ", but '"
        + tool + " --version' does not run.\n"
        + "Install it (on Debian and Ubuntu: sudo apt install " + debian + ") and build again,\n"
        + "or build without the native libraries: mvn verify -P '!" + label + "'");
  }

  /** Runs {@code command}, appending its output to {@code log}. */
  private static void run(String label, Library library, List<String> command, Path log)
      throws IOException, InterruptedException, Failure {
    Files.writeString(log, "$ " + String.join(" ", command) + "\n",
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    Process process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        .start();
    int status = process.waitFor();
    if (status != 0) {
      List<String> lines = read(log);
      throw new Failure(label + ": building " + library.name() + " failed: " + command.getFirst()
          + " ended with status " + status + ". The end of " + log + ":\n\n  "
          + String.join("\n  ", lines.subList(Math.max(0, lines.size() - 20), lines.size()))
          + "\n\nA missing development package is the usual cause; natives.properties names the"
          + " packages.\nInstall them and build again, or build without the native libraries:"
          + " mvn verify -P '!" + label + "'");
    }
  }

  private static List<String> read(Path log) throws IOException {
    // Compilers write in the user's locale; a malformed byte must not hide the message.
    return new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
  }

  /** Unpacks the zip archive into {@code target}, refusing entries that point outside it. */
  private static void unzip(Path archive, Path target) throws IOException, Failure {
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      for (ZipEntry entry : Collections.list(zip.entries())) {
        Path path = target.resolve(entry.getName()).normalize();
        if (!path.startsWith(target) || path.equals(target)) {
          throw new Failure(archive + " has an entry outside its folder: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(path);
        } else {
          Files.createDirectories(path.getParent());
          try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, path);
          }
        }
      }
    }
  }

  /** The SONAME of a 64-bit ELF library, or null for a file without one, such as a DLL. */
  static String soname(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    ByteBuffer elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (bytes.length < 64 || elf.getInt(0) != 0x464c457f || elf.get(4) != 2 || elf.get(5) != 1) {
      return null;
    }
    long sections = elf.getLong(0x28);
    int size = Short.toUnsignedInt(elf.getShort(0x3a));
    int count = Short.toUnsignedInt(elf.getShort(0x3c));
    for (int i = 0; i < count; i++) {
      int header = Math.toIntExact(sections + (long) i * size);
      if (elf.getInt(header + 4) != 6) { // SHT_DYNAMIC
        continue;
      }
      int linked = Math.toIntExact(sections + (long) elf.getInt(header + 0x28) * size);
      int strings = Math.toIntExact(elf.getLong(linked + 0x18));
      int end = Math.toIntExact(elf.getLong(header + 0x18) + elf.getLong(header + 0x20));
      for (int at = Math.toIntExact(elf.getLong(header + 0x18)); at + 16 <= end; at += 16) {
        long tag = elf.getLong(at);
        if (tag == 0) { // DT_NULL
          break;
        }
        if (tag == 14) { // DT_SONAME
          int from = strings + Math.toIntExact(elf.getLong(at + 8));
          int to = from;
          while (bytes[to] != 0) {
            to++;
          }
          return new String(bytes, from, to - from, StandardCharsets.UTF_8);
        }
      }
    }
    return null;
  }

  private static Failure cannotDownload(
      String label, Library library, Path archive, String reason) {
    return new Failure(label + ": cannot download " + library.archive() + "\n"
        + "  (" + reason + ")\n"
        + "\n"
        + "The build needs each archive once and works offline afterwards. Either\n"
        + "- fix the network access and build again. Behind a proxy, set HTTPS_PROXY, or run\n"
        + "    java -Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port> scripts/FetchNatives.java "
        + label + "\n"
        + "- or download the archive yourself, save it as\n"
        + "    " + archive + "\n"
        + "  and build again,\n"
        + "- or build without the native libraries: mvn verify -P '!" + label + "'");
  }

  /** The exception and its causes, as the HTTP client's exceptions often have no message. */
  private static String describe(Throwable failure) {
    List<String> parts = new ArrayList<>();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      String part = t.getMessage() == null || t.getMessage().isBlank()
          ? t.getClass().getName()
          : t.getClass().getName() + ": " + t.getMessage();
      if (parts.isEmpty() || !parts.getLast().equals(part)) {
        parts.add(part);
      }
    }
    return String.join(", caused by ", parts);
  }

  private static HttpClient client() {
    if (client == null) {
      client = HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(30))
          .proxy(proxy())
          .build();
    }
    return client;
  }

  /** The proxy from -Dhttps.proxyHost, else from HTTPS_PROXY, else the operating system's. */
  private static ProxySelector proxy() {
    if (System.getProperty("https.proxyHost") == null) {
      String variable = System.getenv("HTTPS_PROXY");
      if (variable == null || variable.isBlank()) {
        variable = System.getenv("https_proxy");
      }
      if (variable != null && !variable.isBlank()) {
        variable = variable.trim();
        URI uri = URI.create(variable.contains("://") ? variable : "http://" + variable);
        if (uri.getHost() != null) {
          int port = uri.getPort() != -1 ? uri.getPort() : 80;
          return ProxySelector.of(new InetSocketAddress(uri.getHost(), port));
        }
      }
    }
    return ProxySelector.getDefault();
  }

  private static String value(Properties properties, Path config, String key, Pattern pattern)
      throws Failure {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new Failure(config + " has no value for " + key + ".");
    }
    value = value.trim();
    if (pattern != null && !pattern.matcher(value).matches()) {
      throw new Failure(config + ": '" + value + "' is not a valid value for " + key + ".");
    }
    return value;
  }

  /** {@code source}: the archive holds source to build, which may also come as {@code .tar.xz}. */
  private static URI uri(Path config, String key, String value, boolean source) throws Failure {
    try {
      URI uri = new URI(value);
      String path = uri.getPath();
      if ("https".equals(uri.getScheme()) && path != null
          && (path.endsWith(".zip") || source && path.endsWith(".tar.xz"))) {
        return uri;
      }
    } catch (java.net.URISyntaxException e) {
      // reported below
    }
    throw new Failure(config + ": " + key + " must be the https URL of a .zip file"
        + (source ? " or a .tar.xz file." : "."));
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest = digest();
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[1 << 16];
      for (int n; (n = in.read(buffer)) > 0; ) {
        digest.update(buffer, 0, n);
      }
    }
    return hex(digest.digest());
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("every JDK has SHA-256", e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
