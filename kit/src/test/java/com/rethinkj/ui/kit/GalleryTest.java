package com.rethinkj.ui.kit;

import com.rethinkj.ui.Headless;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The gallery in both themes at scale 2, compared with the stored images and trees. */
class GalleryTest {

  @ParameterizedTest
  @CsvSource({"dark, 2", "light, 2"})
  void looksAsStored(String name, int scale) {
    TextLibraries.assumeLoaded();
    Theme theme = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    String image = "gallery-" + name + "-" + scale + "x";
    Path out = Path.of("target/gallery", image);
    Headless.Frame frame = Theme.CURRENT.with(theme, () -> {
      try {
        return Gallery.draw(theme, scale, out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    Golden.assertMatches(image, frame.pixels(), frame.tree());
  }
}
