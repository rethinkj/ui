package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.El;
import com.rethinkj.ui.render.Pixels;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The kit's {@link Image}: the image with the theme's radius, and its placeholder. */
class ImageTest {

  @Test
  void showsTheImageWithTheThemesRadiusAndThePlaceholderWithoutOne() {
    TextLibraries.assumeLoaded();
    for (Theme t : List.of(Theme.DARK, Theme.LIGHT)) {
      com.rethinkj.ui.Image picture =
          com.rethinkj.ui.Image.of(new Pixels(80, 40, new byte[80 * 40 * 4]), 2);
      El image = Image.image(t, picture, "A chart");
      El placeholder = Image.image(t, null, "A chart").w(200);
      El root = col()
          .size(300, 200)
          .align(com.rethinkj.ui.Style.Align.START)
          .children(image, placeholder);
      com.rethinkj.ui.Headless.render(root, 300, 200, 1);
      List<String> lines = root.dump().lines().map(String::strip).toList();
      assertEquals("image role=img label=\"A chart\" 0,0 40x20", lines.get(1));
      assertEquals(t.radius().medium(), image.radii()[0]);
      assertTrue(lines.get(2).startsWith("el role=img label=\"A chart\" 0,20 200x"), lines.get(2));
      assertEquals(t.surface().band(), placeholder.fill());
      assertTrue(lines.get(3).startsWith("text label=\"image\""), "the icon: " + lines.get(3));
      assertTrue(lines.get(4).startsWith("text \"A chart\""), lines.get(4));
    }
  }
}
