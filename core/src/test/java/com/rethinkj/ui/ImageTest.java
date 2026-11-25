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
    // A transparent pixel has no color.
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

  @Test
  void anImageDoesNotCoverAnUnevenRoundedBorder() {
    int border = 0xFF0000FF;
    int red = 0xFFFF0000;
    int green = 0xFF00FF00;
    Image square = Image.of(new Pixels(1, 1, new byte[] {(byte) 255, 0, 0, (byte) 255}));
    byte[] wide = new byte[16];
    for (int i = 0; i < 16; i += 4) {
      wide[i] = (byte) 255;
      wide[i + 3] = (byte) 255;
    }
    Image banner = Image.of(new Pixels(4, 1, wide));
    // Radius, borders top, right, bottom, left, padding, scale, and whether the image covers.
    float[][] cases = {
      {12, 10, 2, 2, 2, 0, 1.25f, 0},
      {20, 12, 2, 12, 2, 0, 1, 0},
      {20, 12, 2, 12, 2, 0, 1.25f, 0},
      {20, 19, 1, 2, 1, 2, 1, 0},
      {13, 12, 2, 12, 2, 0, 1, 1}
    };
    for (float[] c : cases) {
      float scale = c[6];
      int size = Math.round(40 * scale);
      El plain = el().size(40, 40)
          .child(el().size(40, 40)
              .radius(c[0])
              .border(c[1], c[2], c[3], c[4], border)
              .fill(green));
      El withImage = el().size(40, 40)
          .child(El.image(c[7] > 0 ? banner : square)
              .size(40, 40)
              .radius(c[0])
              .border(c[1], c[2], c[3], c[4], border)
              .padding(c[5])
              .fill(green)
              .fit(c[7] > 0 ? Image.Fit.COVER : Image.Fit.FILL));
      Flex.layout(plain, 40, 40);
      Flex.layout(withImage, 40, 40);

      Pixels borderOnly = CpuRenderer.render(Scene.of(plain, scale), size, size, scale);
      Pixels drawn = CpuRenderer.render(Scene.of(withImage, scale), size, size, scale);

      // Outside the content box, on pure border and outside the element the image adds nothing.
      float left = (c[4] + c[5]) * scale;
      float top = (c[1] + c[5]) * scale;
      float right = size - (c[2] + c[5]) * scale;
      float bottom = size - (c[3] + c[5]) * scale;
      int borderPixels = 0;
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          int expected = borderOnly.argb(x, y);
          borderPixels += expected == border ? 1 : 0;
          boolean outside = x + 1 <= left || x >= right || y + 1 <= top || y >= bottom;
          if (outside || expected == border || expected == 0) {
            assertEquals(expected, drawn.argb(x, y), "pixel " + x + "," + y + " of " + c[0]);
          }
        }
      }
      assertTrue(borderPixels > 0);
      assertEquals(
          red, drawn.argb((int) (left + right) / 2, (int) (top + bottom) / 2), "image interior");
    }
  }

  @Test
  void anImageFollowsTheFillOfTheBorderTheRectangleDraws() {
    int border = 0xFF0000FF;
    Image fill = Image.of(new Pixels(1, 1, new byte[] {(byte) 255, 0, 0, (byte) 255}));
    // Transparent sides are no border to the rectangle, nor to the image's clip.
    El colors =
        el().size(40, 40).radius(20).border(4, 4, 0, 4, border).borderColors(0, border, 0, border);
    El colorsImage = El.image(fill)
        .size(40, 40)
        .radius(20)
        .border(4, 4, 0, 4, border)
        .borderColors(0, border, 0, border)
        .fit(Image.Fit.FILL);
    Flex.layout(colors, 40, 40);
    Flex.layout(colorsImage, 40, 40);
    assertEquals(
        CpuRenderer.render(Scene.of(colors, 1), 40, 40, 1).argb(7, 34),
        CpuRenderer.render(Scene.of(colorsImage, 1), 40, 40, 1).argb(7, 34));
    // A scene without a scale does not know the renderer's outset by a side without a border.
    El open = el().size(80, 80).radius(40).border(4, 15, 0, 15, border);
    El openImage =
        El.image(fill).size(80, 80).radius(40).border(4, 15, 0, 15, border).fit(Image.Fit.FILL);
    Flex.layout(open, 80, 80);
    Flex.layout(openImage, 80, 80);
    assertEquals(
        CpuRenderer.render(Scene.of(open), 160, 160, 2).argb(57, 153),
        CpuRenderer.render(Scene.of(openImage), 160, 160, 2).argb(57, 153));
  }

  @Test
  void paddingInsideARoundedBorderKeepsCornersClearOfTheBorder() {
    Image fill = Image.of(new Pixels(1, 1, new byte[] {(byte) 255, 0, 0, (byte) 255}));
    El even = El.image(fill)
        .size(40, 40)
        .radius(16)
        .border(2, 0xFF0000FF)
        .padding(12, 8)
        .fit(Image.Fit.FILL);
    Flex.layout(even, 40, 40);
    assertEquals(0xFFFF0000, CpuRenderer.render(Scene.of(even, 1), 40, 40, 1).argb(12, 14));

    // Uneven border with padding: top-left content corner (6, 14) lies inside the border's inner
    // ellipse (half axes 14 and 6 from (2, 10)), so the image keeps it.
    El uneven = El.image(fill)
        .size(48, 48)
        .radius(16)
        .border(10, 2, 2, 2, 0xFF0000FF)
        .padding(4)
        .fit(Image.Fit.FILL);
    Flex.layout(uneven, 48, 48);
    assertEquals(0xFFFF0000, CpuRenderer.render(Scene.of(uneven, 1), 48, 48, 1).argb(6, 14));

    // Where a side has no border, the fill reaches a pixel beyond it, and so does the image.
    El open = El.image(fill)
        .size(80, 80)
        .radius(40)
        .border(4, 15, 0, 15, 0xFF0000FF)
        .fill(0xFF00FF00)
        .fit(Image.Fit.FILL);
    Flex.layout(open, 80, 80);
    assertEquals(0xFFFF0000, CpuRenderer.render(Scene.of(open, 1), 80, 80, 1).argb(30, 77));
  }
}
