package com.rethinkj.ui.kit;

import com.rethinkj.ui.Headless;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The gallery in both themes at scale 1 and 2, compared with the stored images and trees. */
class GalleryTest {

  @ParameterizedTest
  @CsvSource({"dark, 1", "dark, 2", "light, 1", "light, 2"})
  void looksAsStored(String name, int scale) throws IOException {
    TextLibraries.assumeLoaded();
    Theme theme = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    String image = "gallery-" + name + "-" + scale + "x";
    Headless.Frame frame = Gallery.draw(theme, scale, Path.of("target/gallery", image));
    Golden.assertMatches(image, frame.pixels(), frame.tree());
  }
}
