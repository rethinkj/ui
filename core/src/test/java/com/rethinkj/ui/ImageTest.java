package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.render.Scene;
import org.junit.jupiter.api.Test;

/** Images: their levels, the element that shows one, its layout, its line in a dump, its scene. */
class ImageTest {

  @Test
  void levelsHalveDownToOnePixelAndKeepOpaqueBytes() {
    Pixels photo = Examples.photo(37, 23);
    Image image = Image.of(photo);
    assertEquals(6, image.levels(), "37, 18, 9, 4, 2, 1");
    assertEquals(18, image.levelWidth(1));
    assertEquals(11, image.levelHeight(1));
    assertEquals(1, image.levelWidth(5));
    assertEquals(1, image.levelHeight(5));
    assertArrayEquals(photo.rgba(), image.level(0), "opaque pixels keep their bytes");
    long bytes = 0;
    for (int k = 0; k < image.levels(); k++) {
      bytes += 4L * image.levelWidth(k) * image.levelHeight(k);
      assertEquals(4 * image.levelWidth(k) * image.levelHeight(k), image.level(k).length);
    }
    assertEquals(bytes, image.bytes());
  }

  @Test
  void translucentTexelsArePremultipliedInLinearLight() {
    // Half transparent white: half the light, which sRGB encodes as 188, not 128.
    Image white =
        Image.of(new Pixels(1, 1, new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 128}));
    assertEquals(188, white.level(0)[0] & 0xFF);
    assertEquals(128, white.level(0)[3] & 0xFF);
    // A transparent pixel has no colour.
    Image clear = Image.of(new Pixels(1, 1, new byte[] {(byte) 255, 0, 0, 0}));
    assertArrayEquals(new byte[4], clear.level(0));
  }

  @Test
  void aLevelAveragesInLinearLightAndOddSidesCountWhatTheyCover() {
    // Black and white side by side: half the light, 188 in sRGB.
    Image pair = Image.of(new Pixels(
        2, 1, new byte[] {0, 0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255}));
    assertEquals(188, pair.level(1)[0] & 0xFF);
    // Three pixels, white, black, white, into one: two thirds of the light.
    Image three = Image.of(new Pixels(3, 1, new byte[] {
      (byte) 255,
      (byte) 255,
      (byte) 255,
      (byte) 255,
      0,
      0,
      0,
      (byte) 255,
      (byte) 255,
      (byte) 255,
      (byte) 255,
      (byte) 255
    }));
    assertEquals(1, three.levelWidth(1));
    assertEquals(213, three.level(1)[0] & 0xFF);
    // Opaque red beside transparent: the level is red at half alpha, not darkened.
    Image edge = Image.of(new Pixels(2, 1, new byte[] {(byte) 255, 0, 0, (byte) 255, 0, 0, 0, 0}));
    assertEquals(188, edge.level(1)[0] & 0xFF, "half the light of red, premultiplied");
    assertEquals(128, edge.level(1)[3] & 0xFF);
  }

  @Test
  void anImageTooLargeIsHalvedAndKeepsItsLogicalSize() {
    Image wide =
        Image.of(new Pixels(Image.MAX_SIZE * 2 + 1, 1, new byte[(Image.MAX_SIZE * 2 + 1) * 4]), 2);
    assertEquals(Image.MAX_SIZE, wide.width());
    assertEquals(1, wide.height());
    assertEquals(
        1, wide.scale(), "16384 pixels at scale 1: the logical size of 32769 at 2, nearly");
  }

  @Test
  void refusesWhatIsNoImage() {
    assertThrows(IllegalArgumentException.class, () -> Image.of(new Pixels(0, 3, new byte[0])));
    assertThrows(IllegalArgumentException.class, () -> Image.of(Examples.photo(2, 2), 0));
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Image.decode("GIF89a".getBytes()));
    assertTrue(e.getMessage().contains("47 49 46 38"), e.getMessage());
    assertEquals(9, Image.decode(Png.encode(Examples.photo(9, 4))).width());
  }

  @Test
  void anElementIsAsLargeAsItsImageInLogicalPixelsAndKeepsItsAspect() {
    Image image = Image.of(Examples.photo(80, 40), 2);
    El natural = El.image(image).label("A photo").key("photo");
    El wide = El.image(image).w(120);
    El tall = El.image(image).h(60);
    El both = El.image(image).size(30, 30);
    El root = el().size(400, 300).align(Style.Align.START).children(natural, wide, tall, both);
    Flex.layout(root, 400, 300);
    assertEquals(40, natural.width());
    assertEquals(20, natural.height());
    assertEquals(60, wide.height());
    assertEquals(120, tall.width());
    assertEquals(30, both.width());
    assertEquals(30, both.height());
    assertEquals(
        "image #photo role=img label=\"A photo\" 0,0 40x20",
        root.dump().lines().toList().get(1).strip());
  }

  @Test
  void theSceneMapsTheTexelsOntoTheFittedRectangle() {
    Image image = Image.of(Examples.photo(40, 20));
    El fill = El.image(image).absolute(0, 0).size(40, 40);
    El contain = El.image(image).absolute(50, 0).size(40, 40).fit(Image.Fit.CONTAIN);
    El cover = El.image(image).absolute(100, 0).size(40, 40).fit(Image.Fit.COVER);
    El root = el().size(160, 50).children(fill, contain, cover);
    Flex.layout(root, 160, 50);
    Scene scene = Scene.of(root, 1);
    assertEquals(3, scene.count());
    assertEquals(1, scene.images().size(), "one image, drawn three times");
    assertRect(scene, 0, 0, 0, 40, 40);
    assertRect(scene, 1, 50, 10, 40, 20);
    assertRect(scene, 2, 80, 0, 80, 40);
    assertEquals(0, scene.clipOf(1), "contained: nothing reaches beyond the box");
    assertEquals(100, scene.clipGet(scene.clipOf(2), Scene.X), "covering: cut by the box");
    assertEquals(40, scene.clipGet(scene.clipOf(2), Scene.WIDTH));
    assertEquals(Scene.IMAGE, scene.getInt(2, Scene.KIND));
    assertEquals(40 | 20 << 16, scene.getInt(2, Scene.IMAGE_SIZE));
    assertEquals(image.levels(), scene.getInt(2, Scene.LEVELS));
  }

  private static void assertRect(Scene scene, int i, float x, float y, float width, float height) {
    assertEquals(x, scene.get(i, Scene.X), "x of " + i);
    assertEquals(y, scene.get(i, Scene.Y), "y of " + i);
    assertEquals(width, scene.get(i, Scene.WIDTH), "width of " + i);
    assertEquals(height, scene.get(i, Scene.HEIGHT), "height of " + i);
  }

  @Test
  void oneToOneDrawsThePixelsAsTheyAre() {
    for (float scale : new float[] {1, 1.5f, 2}) {
      Pixels photo = Examples.photo(30, 18);
      El root = el().size(40, 30).children(El.image(Image.of(photo, scale)).absolute(2, 4));
      Flex.layout(root, 40, 30);
      Pixels drawn = CpuRenderer.render(
          Scene.of(root, scale), Math.round(40 * scale), Math.round(30 * scale), scale);
      int left = Math.round(2 * scale);
      int top = Math.round(4 * scale);
      for (int y = 0; y < photo.height(); y++) {
        for (int x = 0; x < photo.width(); x++) {
          assertEquals(
              photo.argb(x, y),
              drawn.argb(left + x, top + y),
              "pixel " + x + "," + y + " at scale " + scale);
        }
      }
    }
  }

  /**
   * Prints what the CPU renderer takes for a frame of 1920 by 1080 without an image, with a full
   * HD image one to one and with it at a quarter of its width, encoding included.
   */
  @Test
  void printsTheTimeOfAFullHdImage() {
    long made = System.nanoTime();
    Image photo = Image.of(Examples.photo(1920, 1080));
    System.out.printf(
        "Image.of: the levels of 1920x1080 in %.1f ms%n", (System.nanoTime() - made) / 1e6);
    for (float size : new float[] {0, 1920, 480}) {
      El root = el().size(1920, 1080)
          .fill(0xFF000000)
          .children(El.image(photo).absolute(0, 0).size(size, size * 9 / 16));
      Flex.layout(root, 1920, 1080);
      Scene scene = Scene.of(root, 1);
      long best = Long.MAX_VALUE;
      for (int run = 0; run < 5; run++) {
        long start = System.nanoTime();
        CpuRenderer.render(scene, 1920, 1080, 1);
        best = Math.min(best, System.nanoTime() - start);
      }
      System.out.printf(
          "CPU: a 1920x1080 image at %.0f wide in a frame of 1920x1080, best of 5:" + " %.1f ms%n",
          size, best / 1e6);
    }
  }
}
