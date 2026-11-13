import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
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
 * the URL of the release archive it comes from, the SHA-256 of that archive and the library's path
 * inside it. It downloads each archive into {@code fetched/archives/}, unless one with the pinned
 * hash is already there, unpacks the libraries into {@code
 * fetched/resources/META-INF/rethinkj-ui/natives/<platform>/} and writes {@code manifest.txt}
 * beside them. When {@code fetched/} already matches {@code natives.properties}, it does nothing.
 *
 * <p>The manifest lists the libraries in load order with their versions and SHA-256 hashes and
 * says nothing about where they came from, so a module whose files come from a local build writes
 * the same format. {@code com.rethinkj.ui.natives.Manifest} in core reads it.
 *
 * <p>Needs only a JDK 25 and runs the same on Windows and Linux.
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

  record Library(String name, String version, URI archive, String archiveSha256, String entry) {

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
      Library library = new Library(
          name,
          value(properties, config, name + ".version", TOKEN),
          uri(config, name + ".archive", value(properties, config, name + ".archive", null)),
          value(properties, config, name + ".archive.sha256", SHA256),
          value(properties, config, name + ".entry", ENTRY));
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
          .append("# library <name> <version> <file> <sha256>\n")
          .append(FORMAT).append('\n')
          .append("platform ").append(platform).append('\n');
      for (Library library : libraries) {
        Path file = out.resolve(library.file());
        try (ZipFile zip = new ZipFile(archives.resolve(library.archiveName()).toFile())) {
          ZipEntry entry = zip.getEntry(library.entry());
          if (entry == null || entry.isDirectory()) {
            throw new Failure(config + ": " + library.archiveName() + " has no file "
                + library.entry() + ". Correct " + library.name() + ".entry.");
          }
          try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, file);
          }
        }
        String sha256 = sha256(file);
        manifest.append("library ").append(library.name()).append(' ').append(library.version())
            .append(' ').append(library.file()).append(' ').append(sha256).append('\n');
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

  private static URI uri(Path config, String key, String value) throws Failure {
    try {
      URI uri = new URI(value);
      String path = uri.getPath();
      if ("https".equals(uri.getScheme()) && path != null && path.endsWith(".zip")) {
        return uri;
      }
    } catch (java.net.URISyntaxException e) {
      // reported below
    }
    throw new Failure(config + ": " + key + " must be the https URL of a .zip file.");
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
