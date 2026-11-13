package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Runs an example's {@code main} on a script and compares its snaps with the stored ones. */
final class Snaps {

  private static final Path SCRIPTS = Path.of("src/test/resources/scripts");
  private static final Path STORED = Path.of("src/test/resources/golden");

  /** An example's {@code main}. */
  interface Main {
    void run(String[] args) throws IOException;
  }

  private Snaps() {}

  /**
   * Runs {@code main} with {@code --script src/test/resources/scripts/<name>.txt --out
   * target/<name>} and compares each file it wrote with the one of the same name in {@code
   * src/test/resources/golden}. With {@code -Dgolden.update=true} it stores the snaps anew; look
   * at them before committing. Skipped where FreeType and HarfBuzz cannot be loaded, since the
   * examples have text.
   */
  static void check(String name, Main main) throws IOException {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
    Path out = Path.of("target", name);
    main.run(new String[] {
        "--script", SCRIPTS.resolve(name + ".txt").toString(), "--out", out.toString()});

    try (var files = Files.list(out)) {
      for (Path written : files.sorted().toList()) {
        Path stored = STORED.resolve(written.getFileName());
        if (Boolean.getBoolean("golden.update")) {
          Files.createDirectories(STORED);
          Files.copy(written, stored, StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        assertTrue(Files.exists(stored), "No stored " + stored + "; run with -Dgolden.update=true");
        String failure = written.toAbsolutePath() + " differs from " + stored;
        if (written.toString().endsWith(".png")) {
          Pixels expected = Png.decode(Files.readAllBytes(stored));
          Pixels actual = Png.decode(Files.readAllBytes(written));
          assertEquals(expected.width() + "x" + expected.height(),
              actual.width() + "x" + actual.height(), failure);
          assertTrue(Arrays.equals(expected.rgba(), actual.rgba()), failure);
        } else {
          assertEquals(Files.readString(stored), Files.readString(written), failure);
        }
      }
    }
  }

  /** The first line that contains {@code part} in the tree of a snap that {@link #check} wrote. */
  static String line(String name, String snap, String part) throws IOException {
    return Files.readAllLines(Path.of("target", name, snap + ".tree.txt")).stream()
        .filter(l -> l.contains(part)).findFirst().orElseThrow().strip();
  }
}
