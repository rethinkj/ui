package com.rethinkj.ui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CpuRendererTest {

  private static final int RED = 0xFFFF0000;
  private static final int BLUE = 0xFF0000FF;

  @Test
  void fillsPixelsInsideAndLeavesTheRestTransparent() {
    Scene scene = new Scene();
    scene.rect(2, 2, 4, 4, 0, 0, RED, 0);

    Pixels image = CpuRenderer.render(scene, 8, 8, 1);

    assertEquals(RED, image.argb(3, 3));
    assertEquals(RED, image.argb(2, 2));
    assertEquals(0, image.argb(1, 3));
    assertEquals(0, image.argb(6, 6));
  }

  @Test
  void anEdgeThroughAPixelCoversItByHalf() {
    Scene scene = new Scene();
    scene.rect(0.5f, 0, 4, 4, 0, 0, RED, 0);

    Pixels image = CpuRenderer.render(scene, 8, 4, 1);

    assertEquals(0x80FF0000, image.argb(0, 1));
    assertEquals(RED, image.argb(1, 1));
  }

  @Test
  void roundsCorners() {
    Scene scene = new Scene();
    scene.rect(0, 0, 8, 8, 4, 0, RED, 0);

    Pixels image = CpuRenderer.render(scene, 8, 8, 1);

    assertEquals(0, image.argb(0, 0));
    assertEquals(RED, image.argb(4, 4));
    assertEquals(image.argb(1, 0), image.argb(0, 1));
    assertEquals(image.argb(1, 0), image.argb(7, 6));
  }

  @Test
  void drawsTheBorderInsideTheRectangle() {
    Scene scene = new Scene();
    scene.rect(0, 0, 8, 8, 0, 2, RED, BLUE);

    Pixels image = CpuRenderer.render(scene, 8, 8, 1);

    assertEquals(BLUE, image.argb(0, 4));
    assertEquals(BLUE, image.argb(1, 4));
    assertEquals(RED, image.argb(2, 4));
    assertEquals(BLUE, image.argb(7, 7));
  }

  @Test
  void scaleMultipliesThePixels() {
    Scene scene = new Scene();
    scene.rect(1, 1, 2, 2, 0, 0, RED, 0);

    Pixels image = CpuRenderer.render(scene, 8, 8, 2);

    assertEquals(0, image.argb(1, 1));
    assertEquals(RED, image.argb(2, 2));
    assertEquals(RED, image.argb(5, 5));
    assertEquals(0, image.argb(6, 6));
  }

  @Test
  void blendsLaterInstancesOverEarlierOnesInLinearLight() {
    Scene scene = new Scene();
    scene.rect(0, 0, 2, 2, 0, 0, 0xFF000000, 0);
    scene.rect(0, 0, 2, 2, 0, 0, 0x80FFFFFF, 0);

    Pixels image = CpuRenderer.render(scene, 2, 2, 1);

    // Half white over black is 50 % light, which sRGB encodes as 188, not as 128.
    assertEquals(0xFFBCBCBC, image.argb(0, 0));
  }
}
