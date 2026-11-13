package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The scene snaps the boxes at rest to the physical pixel grid: measured from the pixels the CPU
 * draws at fractional scales, and from the scene's numbers.
 */
class PixelGridTest {

  private static final int RED = 0xFFFF0000;
  private static final int BLACK = 0xFF000000;
  private static final int WHITE = 0xFFFFFFFF;
  private static final int GREY = 0xFF808080;
  private static final int BLUE = 0xFF2040C0;

  private static int edge(float v, float scale) {
    return (int) Math.floor(v * (double) scale + 0.5);
  }

  private static int stroke(float width, float scale) {
    return (int) Math.max(1, Math.floor(width * (double) scale + 1e-3));
  }

  /** Boxes of 7 logical pixels side by side on red, from x = 3: no red shows between them. */
  @ParameterizedTest
  @ValueSource(floats = {1.25f, 1.5f, 1.75f, 2.5f, 3})
  void neighboursShareTheirEdges(float scale) {
    El strip = row().padding(3, 0, 0, 3).fill(RED).wFull().hFull();
    for (int i = 0; i < 9; i++) {
      strip.child(el().size(7, 9).fill(i % 2 == 0 ? BLACK : WHITE));
    }
    Pixels pixels = Headless.render(strip, 80, 16, scale).pixels();
    int y = edge(7, scale);
    int left = edge(3, scale);
    int right = edge(3 + 9 * 7, scale);
    List<Integer> widths = new ArrayList<>();
    int run = 0;
    for (int x = left; x < right; x++) {
      int pixel = pixels.argb(x, y);
      assertTrue(
          pixel == BLACK || pixel == WHITE,
          "pixel " + x + " at " + scale + " is " + Integer.toHexString(pixel));
      if (x > left && pixel != pixels.argb(x - 1, y)) {
        widths.add(run);
        run = 0;
      }
      run++;
    }
    widths.add(run);
    assertEquals(9, widths.size());
    // Each box 7 * scale wide, rounded one way or the other.
    for (int width : widths) {
      assertTrue(Math.abs(width - 7 * scale) < 1, widths + " at " + scale);
    }
    assertEquals(RED, pixels.argb(left - 1, y));
    assertEquals(RED, pixels.argb(right, y));
  }

  /**
   * A border of 1 and a hairline of 1 at every position from 10 to 13: whole pixels, as many as
   * {@link #stroke} says, of their colour and nothing between.
   */
  @ParameterizedTest
  @ValueSource(floats = {1.25f, 1.5f, 1.75f, 2.5f, 3})
  void bordersAndHairlinesAreWholePixels(float scale) {
    for (int at = 10; at < 14; at++) {
      El root = el().wFull()
          .hFull()
          .fill(WHITE)
          .children(
              el().absolute(at, at).size(40, 20).fill(WHITE).border(1, GREY),
              el().absolute(at, at + 30).size(40, 1).fill(BLACK),
              el().absolute(at + 50, at).size(1, 40).fill(BLACK));
      Pixels pixels = Headless.render(root, 100, 80, scale).pixels();
      int ring = stroke(1, scale);
      int x = edge(at + 20, scale);
      // The border's top edge, from the white outside to the white inside.
      assertColumn(pixels, x, edge(at, scale), ring, GREY, WHITE, "border at " + at);
      assertColumn(pixels, x, edge(at + 20, scale) - ring, ring, GREY, WHITE, "bottom at " + at);
      assertColumn(pixels, x, edge(at + 30, scale), ring, BLACK, WHITE, "hairline at " + at);
      int y = edge(at + 20, scale);
      int line = edge(at + 50, scale);
      for (int i = -1; i <= ring; i++) {
        assertEquals(
            i >= 0 && i < ring ? BLACK : WHITE,
            pixels.argb(line + i, y),
            "upright hairline at " + at + ", pixel " + i);
      }
    }
  }

  /** From y - 1 to y + run: the outside, {@code run} pixels of {@code colour}, the inside. */
  private static void assertColumn(
      Pixels pixels, int x, int y, int run, int colour, int around, String what) {
    for (int i = -1; i <= run; i++) {
      assertEquals(
          Integer.toHexString(i >= 0 && i < run ? colour : around),
          Integer.toHexString(pixels.argb(x, y + i)),
          what + ", pixel " + i);
    }
  }

  /**
   * A rounded box with a border that clips a child filling its content box: along its straight
   * edges, the background, the border and the child, each whole pixels, nothing between.
   */
  @ParameterizedTest
  @ValueSource(floats = {1.25f, 1.5f, 1.75f, 2.5f, 3})
  void aChildFillingARoundedClipShowsNoFringe(float scale) {
    for (int at = 10; at < 14; at++) {
      El root = el().wFull()
          .hFull()
          .fill(WHITE)
          .child(el().absolute(at, at)
              .size(41, 23)
              .radius(8)
              .border(1, GREY)
              .clip()
              .child(el().wFull().hFull().fill(BLUE)));
      Pixels pixels = Headless.render(root, 80, 60, scale).pixels();
      int ring = stroke(1, scale);
      int middleX = edge(at + 20, scale);
      int middleY = edge(at + 11, scale);
      int top = edge(at, scale);
      int bottom = edge(at + 23, scale);
      int left = edge(at, scale);
      int right = edge(at + 41, scale);
      for (int i = -1; i <= ring; i++) {
        int expected = i < 0 ? WHITE : i < ring ? GREY : BLUE;
        String what = "at " + at + ", pixel " + i + " of the ";
        assertEquals(hex(expected), hex(pixels.argb(middleX, top + i)), what + "top");
        assertEquals(hex(expected), hex(pixels.argb(middleX, bottom - 1 - i)), what + "bottom");
        assertEquals(hex(expected), hex(pixels.argb(left + i, middleY)), what + "left");
        assertEquals(hex(expected), hex(pixels.argb(right - 1 - i, middleY)), what + "right");
      }
    }
  }

  /**
   * A scroll area scrolled by the wheel by 1 and by 0.3 logical pixels, fractions of a physical
   * pixel at these scales: the borders inside it are still whole pixels of their colour, at the
   * edges the scrolled position snaps to.
   */
  @ParameterizedTest
  @ValueSource(floats = {1.25f, 1.5f, 1.75f})
  void scrolledContentStaysOnThePixels(float scale) {
    App<Object> app = new App<>(new Object(), s -> {
      El list = El.col().key("list").scroll().absolute(10, 10).size(60, 50).fill(WHITE);
      for (int i = 0; i < 10; i++) {
        list.child(el().h(11).shrink(0).fill(WHITE).border(1, GREY));
      }
      return el().wFull().hFull().fill(WHITE).child(list);
    });
    app.resize(100, 80, scale);
    app.frame();
    float offset = 0;
    for (float delta : new float[] {1, 0.3f, 1, 0.3f}) {
      app.wheel(40, 30, 0, delta);
      app.frame();
      offset += delta;
      Pixels pixels = app.render().pixels();
      int ring = stroke(1, scale);
      int x = edge(40, scale);
      for (int i = 1; i < 4; i++) { // the tops of the rows in view
        int top = edge(10 + 11 * i - offset, scale);
        assertColumn(
            pixels,
            x,
            top - ring,
            2 * ring,
            GREY,
            WHITE,
            "rows " + (i - 1) + " and " + i + " scrolled by " + offset);
      }
    }
  }

  private static String hex(int argb) {
    return String.format("%08X", argb);
  }

  /**
   * Text and an icon move with their box by whole pixels: wherever the box is, each is drawn from
   * the same bitmaps at the same place from the top left of its own box, which is on a pixel.
   */
  @ParameterizedTest
  @ValueSource(floats = {1.25f, 1.5f, 1.75f, 2.5f, 3})
  void textKeepsItsPlaceInItsBox(float scale) {
    TextLibraries.assumeLoaded();
    List<int[]> first = null;
    for (int at = 10; at < 14; at++) {
      El text = El.text("Save").color(BLACK);
      El icon = El.text("\uF108").font(Font.icons()).fontSize(16).color(BLACK);
      El root = el().wFull()
          .hFull()
          .fill(WHITE)
          .child(row().absolute(at, 10).padding(3, 7).fill(GREY).gap(5).children(text, icon));
      Pixels pixels = Headless.render(root, 100, 40, scale, TextRendering.LINUX).pixels();
      List<int[]> crops = new ArrayList<>();
      for (El el : List.of(text, icon)) {
        float x = at + el.x();
        float y = 10 + el.y();
        int left = edge(x, scale);
        int top = edge(y, scale);
        int width = (int) (el.width() * scale) - 1;
        int height = (int) (el.height() * scale) - 1;
        int[] crop = new int[width * height];
        for (int j = 0; j < height; j++) {
          for (int i = 0; i < width; i++) {
            crop[j * width + i] = pixels.argb(left + i, top + j);
          }
        }
        crops.add(crop);
      }
      if (first == null) {
        first = crops;
      } else {
        for (int i = 0; i < 2; i++) {
          assertArrayEquals(first.get(i), crops.get(i), (i == 0 ? "text" : "icon") + " at " + at);
        }
      }
    }
  }

  /** At scales 1 and 2, where layout's whole logical pixels are whole physical ones, nothing moves. */
  @Test
  void scalesOneAndTwoDrawWhatLayoutSays() {
    for (float scale : new float[] {1, 2}) {
      El list = El.col()
          .absolute(16, 16)
          .size(120, 90)
          .radius(8)
          .border(1, GREY)
          .clip()
          .scroll()
          .shadow(new Shadow(0, 2, 6, 0, 0x40000000));
      for (int i = 0; i < 9; i++) {
        list.children(
            el().h(21).shrink(0).fill(i % 2 == 0 ? WHITE : BLUE),
            el().h(1).shrink(0).fill(GREY));
      }
      El root = el().wFull().hFull().fill(WHITE).child(list);
      Flex.layout(root, 240, 200);
      El.measureScrolls(root);
      list.scrollTo(0, 37);
      El.placeBars(root);
      Scene plain = Scene.of(root);
      Scene snapped = Scene.of(root, scale);
      assertEquals(plain.count(), snapped.count());
      assertArrayEquals(
          plain.data().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
          snapped.data().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
          "at " + scale);
    }
  }

  /**
   * Rows 10.5 high, each filled by a child: what fills a row ends where the next row's begins, as
   * when the rows had been laid out with the list, also with the list scrolled by a fraction.
   */
  @ParameterizedTest
  @ValueSource(floats = {1, 1.5f, 2})
  void rowsOfAFractionalHeightShareTheirEdges(float scale) {
    App<Object> app = new App<>(
        new Object(),
        s -> El.col()
            .size(100, 42)
            .rows(40, 10.5f, i -> El.col().child(el().wFull().hFull().fill(BLACK))));
    app.resize(100, 100, scale);
    app.frame();
    for (double offset : new double[] {0, 21.25, 115.5}) {
      app.scrollTo(app.tree(), 0, offset);
      app.frame();
      Scene scene = app.scene();
      assertTrue(scene.count() >= 4, "the rows in view");
      for (int i = 1; i < scene.count(); i++) {
        assertEquals(
            scene.get(i - 1, Scene.Y) + scene.get(i - 1, Scene.HEIGHT),
            scene.get(i, Scene.Y),
            1e-4,
            "rows " + (i - 1) + " and " + i + " at " + offset);
      }
    }
  }

  /** At 1.5 a box at 11, 11 by 11 is drawn from 17 to 33 physical pixels, its border 1 wide. */
  @Test
  void theSceneHoldsSnappedNumbers() {
    El box = el().absolute(11, 11).size(11, 11).border(1, GREY).fill(WHITE);
    El root = el().wFull().hFull().child(box);
    Flex.layout(root, 40, 40);
    Scene scene = Scene.of(root, 1.5f);
    assertEquals(1, scene.count());
    assertEquals(17, scene.get(0, Scene.X) * 1.5f, 1e-4);
    assertEquals(17, scene.get(0, Scene.Y) * 1.5f, 1e-4);
    assertEquals(16, scene.get(0, Scene.WIDTH) * 1.5f, 1e-4); // 16.5 to 33
    assertEquals(1, scene.get(0, Scene.BORDER_WIDTH) * 1.5f, 1e-4);
    Scene plain = Scene.of(root);
    assertEquals(11, plain.get(0, Scene.X));
    assertEquals(1, plain.get(0, Scene.BORDER_WIDTH));
  }

  /**
   * While a spring moves the switch's knob it is drawn where it is, between pixels, so that it
   * glides; at rest it is snapped.
   */
  @Test
  void aMovingBoxIsNotSnappedUntilItRests() {
    Examples.Lights lights = new Examples.Lights();
    App<Examples.Lights> app = new App<>(lights, Examples::switchAlone);
    app.resize(200, 120, 1.5f);
    app.frame();
    app.pointerDown(100, 60);
    app.pointerUp(100, 60);
    app.frame();
    app.advance(0.04);
    app.frame();
    El knob = find(app.tree(), "knob");
    assertTrue(knob.isMoving());
    Set<Float> moving = knobXs(app.scene(), knob);
    assertTrue(moving.stream().anyMatch(x -> x * 1.5f % 1 != 0), "between pixels: " + moving);
    app.advance(1);
    app.frame();
    knob = find(app.tree(), "knob");
    assertTrue(!knob.isMoving());
    for (float x : knobXs(app.scene(), knob)) {
      assertEquals(Math.round(x * 1.5f), x * 1.5f, 1e-4, "on a pixel at rest");
    }
  }

  /** The x of the scene's rectangles as large as the knob. */
  private static Set<Float> knobXs(Scene scene, El knob) {
    Set<Float> xs = new TreeSet<>();
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.RECT
          && Math.abs(scene.get(i, Scene.HEIGHT) - knob.height()) < 1) {
        xs.add(scene.get(i, Scene.X));
      }
    }
    assertNotEquals(0, xs.size());
    return xs;
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
