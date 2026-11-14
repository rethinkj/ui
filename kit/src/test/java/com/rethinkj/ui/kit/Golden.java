package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Compares an image and its tree with the files stored under {@code src/test/resources/golden}, as
 * core's tests do; core's helper is in its test sources, which this module does not see. Run with
 * {@code -Dgolden.update=true} to write the files anew, and look at them before committing.
 */
final class Golden {

  private static final Path STORED = Path.of("src/test/resources/golden");
  private static final Path FAILED = Path.of("target/golden");

  private Golden() {}

  /**
   * Compares the snaps among a script's files with the stored images {@code <prefix><snap>}: those
   * named in {@code stored}, each of which the script has to have written, or all where none is
   * named. Other files, such as clipboards, are left alone.
   */
  static void assertSnaps(String prefix, List<Path> files, String... stored) throws IOException {
    List<String> snaps = files.stream()
        .map(f -> f.getFileName().toString())
        .filter(f -> f.endsWith(".png"))
        .map(f -> f.substring(0, f.length() - ".png".length()))
        .toList();
    List<String> wanted = stored.length == 0 ? snaps : List.of(stored);
    for (String snap : wanted) {
      assertTrue(snaps.contains(snap), "no snap " + snap + " among " + snaps);
      Path image = files.stream()
          .filter(f -> f.getFileName().toString().equals(snap + ".png"))
          .findFirst()
          .orElseThrow();
      assertMatches(
          prefix + snap,
          Png.decode(Files.readAllBytes(image)),
          Files.readString(image.resolveSibling(snap + ".tree.txt")));
    }
  }

  static void assertMatches(String name, Pixels pixels, String tree) {
    Path image = STORED.resolve(name + ".png");
    Path text = STORED.resolve(name + ".tree.txt");
    try {
      if (Boolean.getBoolean("golden.update")) {
        Files.createDirectories(STORED);
        Files.write(image, Png.encode(pixels));
        Files.writeString(text, tree);
        return;
      }
      if (!Files.exists(image) || !Files.exists(text)) {
        fail("No stored result for '" + name + "'. Run with -Dgolden.update=true to create it.");
      }
      assertEquals(Files.readString(text), tree, "tree of '" + name + "'");
      Pixels expected = Png.decode(Files.readAllBytes(image));
      if (expected.width() == pixels.width()
          && expected.height() == pixels.height()
          && Arrays.equals(expected.rgba(), pixels.rgba())) {
        return;
      }
      Files.createDirectories(FAILED);
      Files.write(FAILED.resolve(name + ".expected.png"), Png.encode(expected));
      Files.write(FAILED.resolve(name + ".actual.png"), Png.encode(pixels));
      fail("Image of '" + name + "' changed. See " + FAILED.toAbsolutePath());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
