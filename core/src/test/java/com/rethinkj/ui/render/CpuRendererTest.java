package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.layout.Flex;
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
  void aClipCutsAChildThatSticksOutWithASmoothEdge() {
    El child = el().fill(RED);
    El parent = el().clip().child(child);
    parent.place(0, 0, 4.5f, 4);
    child.place(0, 0, 8, 4);

    Pixels image = CpuRenderer.render(Scene.of(parent), 8, 4, 1);

    assertEquals(RED, image.argb(3, 1));
    assertEquals(0x80FF0000, image.argb(4, 1), "the clip's edge runs through the pixel");
    assertEquals(0, image.argb(5, 1));
  }

  @Test
  void aRoundedClipCutsLikeARoundedRectangle() {
    El square = el().fill(RED);
    El parent = el().clip().radius(4).child(square);
    parent.place(0, 0, 8, 8);
    square.place(0, 0, 8, 8);
    Scene rounded = new Scene();
    rounded.rect(0, 0, 8, 8, 4, 0, RED, 0);

    Pixels clipped = CpuRenderer.render(Scene.of(parent), 8, 8, 2);

    assertEquals(0, clipped.argb(0, 0));
    assertArrayEquals(CpuRenderer.render(rounded, 8, 8, 2).rgba(), clipped.rgba());
  }

  @Test
  void nestedClipsDrawOnlyWhereBothLetThrough() {
    El child = el().fill(RED);
    El inner = el().clip().child(child);
    El outer = el().clip().child(inner);
    outer.place(0, 0, 6, 6);
    inner.place(3, 3, 6, 6);
    child.place(-3, -3, 9, 9);

    Pixels image = CpuRenderer.render(Scene.of(outer), 8, 8, 1);

    assertEquals(0, image.argb(2, 4), "left of inner");
    assertEquals(RED, image.argb(3, 3));
    assertEquals(RED, image.argb(5, 5));
    assertEquals(0, image.argb(6, 4), "right of outer");
    assertEquals(0, image.argb(4, 6), "below outer");
  }

  @Test
  void aModalScrimIsDrawnAtEveryScale() {
    // The scrim reaches a billion logical pixels beyond the window on each side, beyond what an
    // int holds in physical pixels from scale 2.15 on.
    for (float scale : new float[] {1, 1.25f, 1.5f, 2, 2.5f, 3, 4}) {
      App<Object> app = new App<>(
          new Object(),
          s -> el().size(20, 20)
              .fill(0xFFFFFFFF)
              .overlay(el().size(4, 4).centerInWindow().modal().scrim(0x80000000)));
      app.resize(20, 20, scale);
      app.frame();
      int size = Math.round(20 * scale);

      Pixels image = CpuRenderer.render(app.scene(), size, size, scale);

      assertEquals(0xFFBBBBBB, image.argb(0, 0), "0x80 black over white at " + scale);
      assertEquals(0xFFBBBBBB, image.argb(size - 1, size - 1), "at " + scale);
    }
  }

  @Test
  void shapesImagesAndShadowsFarBeyondTheImageAreDrawn() {
    float far = 1e9f;
    Image white = Image.of(new Pixels(1, 1, new byte[] {-1, -1, -1, -1}));
    El image =
        el().size(8, 8).clip().child(El.image(white).absolute(-far, -far).size(2 * far, 2 * far));
    El shadow = el().size(8, 8)
        .clip()
        .child(
            el().absolute(-far, -far).size(2 * far, 2 * far).shadow(new Shadow(0, 0, 4, 0, RED)));
    Flex.layout(image, 8, 8);
    Flex.layout(shadow, 8, 8);
    for (float scale : new float[] {2.5f, 3}) {
      assertEquals(
          0xFFFFFFFF, CpuRenderer.render(Scene.of(image), 20, 20, scale).argb(10, 10));
      assertEquals(RED, CpuRenderer.render(Scene.of(shadow), 20, 20, scale).argb(10, 10));
    }
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
