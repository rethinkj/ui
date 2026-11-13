package com.rethinkj.ui.examples.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the examples of the documentation site (docs/), whose code the pages show: each on the
 * script {@code src/test/resources/docs/<name>.txt}, comparing the trees of its snaps with
 * {@code src/test/resources/golden/docs/<name>}, which the pages show too. With {@code
 * -Dgolden.update=true} it stores them anew.
 */
class DocsExamplesTest {

  private static final Path SCRIPTS = Path.of("src/test/resources/docs");
  private static final Path STORED = Path.of("src/test/resources/golden/docs");

  /** Each example by the name of its script. */
  static Map<String, Supplier<App<?>>> examples() {
    Map<String, Supplier<App<?>>> all = new LinkedHashMap<>();
    all.put("lights", () -> new App<>(new Lights(), Lights::view));
    return all;
  }

  @TestFactory
  Stream<DynamicTest> eachExampleRunsAndShowsTheStoredTrees() throws IOException {
    try (var files = Files.list(SCRIPTS)) {
      var scripts = files.map(p -> p.getFileName().toString().replace(".txt", "")).sorted().toList();
      assertEquals(scripts, examples().keySet().stream().sorted().toList(),
          "every script in " + SCRIPTS + " has an example, and every example a script");
    }
    return examples().entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
      assumeText();
      App<?> app = e.getValue().get();
      app.textRendering(TextRendering.LINUX); // the same trees on every platform
      Path out = Path.of("target/docs", e.getKey());
      Script.run(Files.readString(SCRIPTS.resolve(e.getKey() + ".txt")), app, out);
      compare(out, STORED.resolve(e.getKey()));
    }));
  }

  private static void compare(Path out, Path stored) throws IOException {
    try (var files = Files.list(out)) {
      for (Path written : files.filter(p -> p.toString().endsWith(".tree.txt")).sorted().toList()) {
        Path kept = stored.resolve(written.getFileName());
        if (Boolean.getBoolean("golden.update")) {
          Files.createDirectories(stored);
          Files.copy(written, kept, StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        assertTrue(Files.exists(kept), "No stored " + kept + "; run with -Dgolden.update=true");
        assertEquals(Files.readString(kept), Files.readString(written),
            written.toAbsolutePath() + " differs from " + kept);
      }
    }
  }

  private static void assumeText() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }
}
