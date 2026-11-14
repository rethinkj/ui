package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;

/** Runs an example's {@code main} on a script and compares its snaps with the stored ones. */
public final class Snaps {

  private static final Path SCRIPTS = Path.of("src/test/resources/scripts");
  private static final Path STORED = Path.of("src/test/resources/golden");
  private static final Path FAILED = Path.of("target/golden");

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
    assumeText();
    Path out = Path.of("target", name);
    if (Files.exists(out)) { // snaps of an earlier run that the script no longer takes
      try (var old = Files.walk(out)) {
        old.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
    main.run(
        new String[] {"--script", SCRIPTS.resolve(name + ".txt").toString(), "--out", out.toString()
        });
    compare(out, STORED);
  }

  /** Skips the test where FreeType and HarfBuzz cannot be loaded, since the examples have text. */
  static void assumeText() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }

  /** The text of {@code src/test/resources/scripts/<name>.txt}. */
  static String script(String name) throws IOException {
    return Files.readString(SCRIPTS.resolve(name + ".txt"));
  }

  /**
   * Compares each file in {@code out} with the one of the same name in {@code stored}, or with
   * {@code -Dgolden.update=true} stores it there.
   */
  private static void compare(Path out, Path stored) throws IOException {
    try (var files = Files.list(out)) {
      for (Path written : files.sorted().toList()) {
        Path kept = stored.resolve(written.getFileName());
        if (Boolean.getBoolean("golden.update")) {
          Files.createDirectories(stored);
          Files.copy(written, kept, StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        assertTrue(Files.exists(kept), "No stored " + kept + "; run with -Dgolden.update=true");
        if (written.toString().endsWith(".png")) {
          image(written, kept);
        } else {
          assertEquals(
              Files.readString(kept),
              Files.readString(written),
              written.toAbsolutePath() + " differs from " + kept);
        }
      }
    }
  }

  /**
   * Compares the image {@code written} with the stored one {@code kept}, a file under {@code
   * src/test/resources/golden}, or with {@code -Dgolden.update=true} stores it there. Where they
   * differ, the expected image, the actual one and their difference are left under {@code
   * target/golden}.
   */
  public static void image(Path written, Path kept) throws IOException {
    if (Boolean.getBoolean("golden.update")) {
      Files.createDirectories(kept.getParent());
      Files.copy(written, kept, StandardCopyOption.REPLACE_EXISTING);
      return;
    }
    assertTrue(Files.exists(kept), "No stored " + kept + "; run with -Dgolden.update=true");
    Pixels expected = Png.decode(Files.readAllBytes(kept));
    Pixels actual = Png.decode(Files.readAllBytes(written));
    boolean sameSize = expected.width() == actual.width() && expected.height() == actual.height();
    if (sameSize && Arrays.equals(expected.rgba(), actual.rgba())) {
      return;
    }
    String name = STORED.relativize(kept).toString().replaceFirst("\\.png$", "");
    Files.createDirectories(FAILED.resolve(name).getParent());
    Files.write(FAILED.resolve(name + ".expected.png"), Png.encode(expected));
    Files.write(FAILED.resolve(name + ".actual.png"), Png.encode(actual));
    String difference = expected.width() + "x" + expected.height() + " stored, " + actual.width()
        + "x" + actual.height() + " rendered";
    if (sameSize) {
      byte[] diff = new byte[actual.rgba().length];
      int count = 0;
      for (int i = 0; i < diff.length; i += 4) {
        if (!Arrays.equals(expected.rgba(), i, i + 4, actual.rgba(), i, i + 4)) {
          diff[i] = (byte) 0xFF;
          diff[i + 3] = (byte) 0xFF;
          count++;
        }
      }
      Files.write(
          FAILED.resolve(name + ".diff.png"),
          Png.encode(new Pixels(actual.width(), actual.height(), diff)));
      difference = count + " pixels differ";
    }
    fail("Image " + kept + " changed: " + difference + ". See " + FAILED.toAbsolutePath());
  }

  /** The first line that contains {@code part} in the tree of a snap that {@link #check} wrote. */
  static String line(String name, String snap, String part) throws IOException {
    return Files.readAllLines(Path.of("target", name, snap + ".tree.txt")).stream()
        .filter(l -> l.contains(part))
        .findFirst()
        .orElseThrow()
        .strip();
  }
}
