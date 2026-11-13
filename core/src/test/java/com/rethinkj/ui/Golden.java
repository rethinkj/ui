package com.rethinkj.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Compares a rendered image and its tree with the files stored under {@code
 * src/test/resources/golden}. Run the tests with {@code -Dgolden.update=true} to write those files
 * anew; look at the result before committing it.
 */
final class Golden {

  private static final Path STORED = Path.of("src/test/resources/golden");
  private static final Path FAILED = Path.of("target/golden");

  private Golden() {}

  /** @param tree the tree to compare, or null to compare the image only */
  static void assertMatches(String name, Pixels pixels, String tree) {
    Path image = STORED.resolve(name + ".png");
    Path text = STORED.resolve(name + ".tree.txt");
    try {
      if (Boolean.getBoolean("golden.update")) {
        Files.createDirectories(STORED);
        Files.write(image, Png.encode(pixels));
        if (tree != null) {
          Files.writeString(text, tree);
        }
        return;
      }
      if (!Files.exists(image) || !Files.exists(text)) {
        fail("No stored result for '" + name + "'. Run with -Dgolden.update=true to create it.");
      }
      if (tree != null) {
        assertEquals(Files.readString(text), tree, "tree of '" + name + "'");
      }

      Pixels expected = Png.decode(Files.readAllBytes(image));
      if (expected.width() == pixels.width()
          && expected.height() == pixels.height()
          && Arrays.equals(expected.rgba(), pixels.rgba())) {
        return;
      }
      Files.createDirectories(FAILED);
      Files.write(FAILED.resolve(name + ".expected.png"), Png.encode(expected));
      Files.write(FAILED.resolve(name + ".actual.png"), Png.encode(pixels));
      String difference = expected.width() + "x" + expected.height() + " stored, "
          + pixels.width() + "x" + pixels.height() + " rendered";
      if (expected.width() == pixels.width() && expected.height() == pixels.height()) {
        byte[] diff = new byte[pixels.rgba().length];
        int count = 0;
        for (int i = 0; i < diff.length; i += 4) {
          if (!Arrays.equals(expected.rgba(), i, i + 4, pixels.rgba(), i, i + 4)) {
            diff[i] = (byte) 0xFF;
            diff[i + 3] = (byte) 0xFF;
            count++;
          }
        }
        Files.write(
            FAILED.resolve(name + ".diff.png"),
            Png.encode(new Pixels(pixels.width(), pixels.height(), diff)));
        difference = count + " pixels differ";
      }
      fail("Image of '" + name + "' changed: " + difference + ". See " + FAILED.toAbsolutePath());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
