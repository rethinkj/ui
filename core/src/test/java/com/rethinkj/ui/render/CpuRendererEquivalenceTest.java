package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Examples;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.natives.Natives;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link CpuRenderer} draws the same bits as {@link ReferenceCpuRenderer}, the plain loop it was
 * before it skipped what it knows: the same image, the same floats before encoding and the same
 * blends seen by {@link CpuRenderer.Blends}, in the same order. On the scenes of {@code
 * GpuRendererTest}, shadows of the shapes of {@code ShadowTest}, and random scenes.
 */
class CpuRendererEquivalenceTest {

  /**
   * The views behind the stored images and the scenes that stress one formula each: rectangles,
   * borders, radii, clips, translucency, opacity, text in both fonts with both renderings at three
   * scales, scrolled and in fields, shadows, the data grid and overlays.
   */
  @Test
  void drawsTheScenesOfTheGpuTestAsBefore() throws Exception {
    TextLibraries.assumeLoaded();
    for (GpuRendererTest.Case c : GpuRendererTest.cases().toList()) {
      assertSame(c.name(), c.scene(), c.width(), c.height(), c.scale());
    }
  }

  /** The shapes of {@code ShadowTest}, at three scales, clipped by a rounded box or not. */
  @Test
  void drawsShadowsAsBefore() {
    float[][] shapes = {{20, 12, 0, 4}, {20, 12, 2, 3}, {20, 12, 12, 4}, {20, 12, 8, 2},
        {20, 12, 8, 12}, {26, 18, 14, 6}, {80, 60, 4, 1.5f}};
    for (float scale : new float[] {1, 1.5f, 2}) {
      for (float[] s : shapes) {
        for (boolean clipped : new boolean[] {false, true}) {
          float width = 2 * s[0] + 6 * s[3] + 20;
          float height = 2 * s[1] + 6 * s[3] + 20;
          El shadow = el().absolute(3 * s[3] + 10.3f, 3 * s[3] + 9.6f).size(2 * s[0], 2 * s[1])
              .radius(s[2]).fill(0xFFFFFFFF).shadow(new Shadow(0.5f, 1.25f, 2 * s[3], 0,
                  0xB3203040), new Shadow(-1, 0, s[3], 1.5f, 0x660000FF));
          El parent = clipped
              ? el().absolute(5.5f, 4.25f).size(width - 14, height - 9).radius(9).clip()
                  .child(shadow.absolute(3 * s[3] + 4.8f, 3 * s[3] + 5.35f))
              : shadow;
          El root = el().size(width, height).fill(0xFF2A2A2A).child(parent);
          Flex.layout(root, width, height);
          assertSame("shadow " + Arrays.toString(s) + (clipped ? " clipped" : "") + " @"
                  + scale, Scene.of(root), Math.round(width * scale), Math.round(height * scale),
              scale);
        }
      }
    }
  }

  /**
   * Thousands of small scenes of random instances: rectangles, shadows and, where the text
   * libraries load, glyphs, at fractional and whole positions and sizes, with radii from below 0
   * to beyond half the size, alike or each corner its own, borders up to beyond it, alike or each
   * side its own, translucent and opaque colours, the clips of
   * nested rounded boxes, and now and then a value that is not finite.
   */
  @Test
  void drawsRandomScenesAsBefore() {
    boolean text = textLoads();
    Random random = new Random(20261113);
    for (int n = 0; n < 3000; n++) {
      float scale = new float[] {1, 1.5f, 2, 1.25f, 0.75f, 3}[random.nextInt(6)];
      float width = 8 + random.nextInt(40);
      float height = 8 + random.nextInt(40);
      Scene scene = base(width, height, scale, text && n % 3 == 0);
      List<int[]> glyphs = new ArrayList<>();
      for (int i = 0; i < scene.count(); i++) {
        if (scene.getInt(i, Scene.KIND) == Scene.GLYPH) {
          glyphs.add(new int[] {scene.getInt(i, Scene.ATLAS), scene.getInt(i, Scene.ATLAS_SIZE),
              scene.getInt(i, Scene.PAGE)});
        }
      }
      int count = 1 + random.nextInt(14);
      for (int i = 0; i < count; i++) {
        addRandom(scene, random, width, height, glyphs);
      }
      assertSame("random scene " + n, scene, Math.round(width * scale),
          Math.round(height * scale), scale);
    }
  }

  /**
   * Frames in which one channel takes many values and the others few, so that pixels that differ
   * in that channel alone meet in the encoder's slots; then every channel at random. With values
   * beyond 0 to 1, both zeros and NaN.
   */
  @Test
  void encodesAsBefore() {
    Random random = new Random(42);
    int width = 512;
    int height = 256;
    for (int varied = 0; varied <= 4; varied++) {
      float[] frame = new float[width * height * 4];
      for (int at = 0; at < frame.length; at += 4) {
        for (int c = 0; c < 4; c++) {
          frame[at + c] = c == varied || varied == 4 ? value(random)
              : new float[] {0.2f, 0.75f, 1}[random.nextInt(3)];
        }
      }
      byte[] image = CpuRenderer.encode(frame, width, height).rgba();
      byte[] reference = ReferenceCpuRenderer.encode(frame, width, height).rgba();
      for (int i = 0; i < image.length; i++) {
        if (image[i] != reference[i]) {
          fail("channel " + varied + " varied: byte " + i % 4 + " of pixel " + i / 4 + " is "
              + (image[i] & 0xFF) + ", was " + (reference[i] & 0xFF));
        }
      }
    }
  }

  private static float value(Random random) {
    return switch (random.nextInt(20)) {
      case 0 -> 0;
      case 1 -> -0f;
      case 2 -> Float.NaN;
      case 3 -> 1.5f;
      case 4 -> -0.25f;
      case 5 -> random.nextInt(64) / 64f;
      default -> random.nextFloat();
    };
  }

  /** A scene whose clip table has rounded and square clips, nested, and text if asked. */
  private static Scene base(float width, float height, float scale, boolean text) {
    El nested = el().absolute(3.5f, -2.25f).size(width / 2, height)
        .radius(height / 3, 0, 2.5f, height / 2).clip()
        .child(el().absolute(1, 1).size(4, 4).fill(0x40FF0000));
    El rounded = el().absolute(1.25f, 2.5f).size(width * 0.7f, height * 0.6f).radius(5.5f).clip()
        .child(el().absolute(0.5f, 0.5f).size(3, 2).fill(0x8000FF00)).child(nested);
    El square = el().absolute(width * 0.4f, height * 0.35f).size(width * 0.5f, height * 0.55f)
        .clip().child(el().absolute(-1, -1).size(5, 5).fill(0xFF0000FF));
    El root = el().size(width, height).fill(0xFFF0F0F0).child(rounded).child(square);
    if (text) {
      rounded.child(El.text("Ag é").fontSize(9).color(0xFF101010).absolute(0.3f, 0.6f));
      square.child(El.text("W1").font(Font.mono()).fontSize(11).color(0xFFFFFFFF)
          .absolute(-2.5f, 1));
      Examples.laidOut(root, width, height, scale);
    } else {
      Flex.layout(root, width, height);
    }
    return Scene.of(root);
  }

  private static void addRandom(Scene scene, Random random, float width, float height,
      List<int[]> glyphs) {
    scene.rect(0, 0, 1, 1, 0, 0, 0, 0);
    long at = (long) (scene.count() - 1) * Scene.STRIDE;
    MemorySegment data = scene.data();
    int kind = random.nextInt(10) < 6 ? Scene.RECT
        : random.nextBoolean() || glyphs.isEmpty() ? Scene.SHADOW : Scene.GLYPH;
    float w = length(random, width);
    float h = length(random, height);
    put(data, at + Scene.X, position(random, width));
    put(data, at + Scene.Y, position(random, height));
    put(data, at + Scene.WIDTH, w);
    put(data, at + Scene.HEIGHT, h);
    data.set(ValueLayout.JAVA_INT, at + Scene.CLIP,
        random.nextInt(3) == 0 ? 0 : random.nextInt(scene.clipCount()));
    data.set(ValueLayout.JAVA_INT, at + Scene.KIND, kind);
    colour(data, at + Scene.FILL, random);
    if (kind == Scene.GLYPH) {
      int[] glyph = glyphs.get(random.nextInt(glyphs.size()));
      data.set(ValueLayout.JAVA_INT, at + Scene.ATLAS, glyph[0]);
      data.set(ValueLayout.JAVA_INT, at + Scene.ATLAS_SIZE, glyph[1]);
      data.set(ValueLayout.JAVA_INT, at + Scene.PAGE, glyph[2]);
      put(data, at + Scene.LIGHT_SHARE, random.nextBoolean() ? random.nextInt(2)
          : random.nextFloat());
      put(data, at + Scene.DARK_GAMMA, new float[] {1, 1.8f, 1 + random.nextFloat()}[
          random.nextInt(3)]);
      put(data, at + Scene.LIGHT_GAMMA, new float[] {1, 1.4f, 1.8f}[random.nextInt(3)]);
      return;
    }
    float half = Math.min(Math.abs(w), Math.abs(h)) / 2;
    // As often all four corners alike as each its own, and so for the sides.
    boolean alike = random.nextBoolean();
    float radius = radius(random, half);
    for (int field : new int[] {Scene.RADIUS, Scene.RADIUS_TOP_RIGHT, Scene.RADIUS_BOTTOM_RIGHT,
        Scene.RADIUS_BOTTOM_LEFT}) {
      put(data, at + field, alike ? radius : radius(random, half));
    }
    if (kind == Scene.SHADOW) {
      put(data, at + Scene.SIGMA, random.nextInt(4) == 0 ? 0 : random.nextFloat() * 6);
      return;
    }
    alike = random.nextBoolean();
    float border = border(random, half);
    for (int field : new int[] {Scene.BORDER_WIDTH, Scene.BORDER_RIGHT, Scene.BORDER_BOTTOM,
        Scene.BORDER_LEFT}) {
      put(data, at + field, alike ? border : border(random, half));
    }
    colour(data, at + Scene.BORDER, random);
  }

  /** A corner's radius: 0, half the shorter side, beyond it, below 0 or between. */
  private static float radius(Random random, float half) {
    return switch (random.nextInt(5)) {
      case 0 -> 0;
      case 1 -> half;
      case 2 -> half + random.nextFloat() * 3;
      case 3 -> -random.nextFloat();
      default -> random.nextFloat() * half;
    };
  }

  /** A side's border: mostly none, in halves, or up to beyond half the box. */
  private static float border(Random random, float half) {
    return switch (random.nextInt(4)) {
      case 0, 1 -> 0;
      case 2 -> random.nextInt(5) * 0.5f;
      default -> random.nextFloat() * (half + 1);
    };
  }

  /** Whole, half, quarter or any position, now and then not finite. */
  private static float position(Random random, float extent) {
    return switch (random.nextInt(50)) {
      case 0 -> Float.NaN;
      case 1 -> random.nextBoolean() ? 1e9f : -1e9f;
      default -> {
        float x = -6 + random.nextFloat() * (extent + 12);
        float steps = new float[] {1, 2, 4}[random.nextInt(3)];
        yield random.nextBoolean() ? Math.round(x * steps) / steps : x;
      }
    };
  }

  /** A width or a height: mostly up to the scene's, sometimes 0, below or infinite. */
  private static float length(Random random, float extent) {
    return switch (random.nextInt(40)) {
      case 0 -> 0;
      case 1 -> -3;
      case 2 -> Float.POSITIVE_INFINITY;
      case 3 -> Float.NaN;
      default -> random.nextBoolean() ? random.nextInt(1, 4 * (int) extent) / 4f
          : random.nextFloat() * extent;
    };
  }

  /** Premultiplied: opaque, translucent or transparent. */
  private static void colour(MemorySegment data, long at, Random random) {
    float alpha = switch (random.nextInt(4)) {
      case 0, 1 -> 1;
      case 2 -> 0;
      default -> random.nextFloat();
    };
    for (int c = 0; c < 3; c++) {
      put(data, at + c * 4, alpha * random.nextFloat());
    }
    put(data, at + 12, alpha);
  }

  private static void put(MemorySegment data, long at, float value) {
    data.set(ValueLayout.JAVA_FLOAT, at, value);
  }

  private static boolean textLoads() {
    try {
      Natives.lookup("freetype");
      Natives.lookup("harfbuzz");
      return true;
    } catch (UnsatisfiedLinkError e) {
      return false;
    }
  }

  /**
   * The image, the floats before it and the blends are those of the reference. A NaN equals a NaN
   * whatever its bits, which Java leaves to the processor and the compiler's order of operands;
   * every NaN encodes to 0.
   */
  private static void assertSame(String name, Scene scene, int width, int height, float scale) {
    Recorder expected = new Recorder();
    Recorder actual = new Recorder();
    float[] before = ReferenceCpuRenderer.renderLinear(scene, width, height, scale,
        expected::blend);
    float[] after = CpuRenderer.renderLinear(scene, width, height, scale, actual::blend);
    for (int i = 0; i < before.length; i++) {
      if (Float.floatToIntBits(before[i]) != Float.floatToIntBits(after[i])) {
        int pixel = i / 4;
        fail(name + ": channel " + i % 4 + " of pixel " + pixel % width + "," + pixel / width
            + " is " + after[i] + ", was " + before[i]);
      }
    }
    assertEquals(expected.count, actual.count, name + ": blends");
    assertEquals(expected.hash, actual.hash, name + ": what the blends saw");
    byte[] image = CpuRenderer.render(scene, width, height, scale).rgba();
    byte[] reference = referenceEncoding(before);
    for (int i = 0; i < image.length; i++) {
      if (image[i] != reference[i]) {
        int pixel = i / 4;
        fail(name + ": byte " + i % 4 + " of pixel " + pixel % width + "," + pixel / width
            + " is " + (image[i] & 0xFF) + ", was " + (reference[i] & 0xFF));
      }
    }
  }

  /** A pixel's floats by their bits. */
  private record Floats(int red, int green, int blue, int alpha) {}

  /**
   * What the reference encodes the frame to, sooner: it encodes each pixel by itself, so it is
   * given each distinct pixel once, as an image one pixel high.
   */
  private static byte[] referenceEncoding(float[] frame) {
    Map<Floats, Integer> distinct = new HashMap<>();
    int[] index = new int[frame.length / 4];
    for (int at = 0; at < frame.length; at += 4) {
      index[at / 4] = distinct.computeIfAbsent(new Floats(Float.floatToRawIntBits(frame[at]),
          Float.floatToRawIntBits(frame[at + 1]), Float.floatToRawIntBits(frame[at + 2]),
          Float.floatToRawIntBits(frame[at + 3])), key -> distinct.size());
    }
    float[] pixels = new float[distinct.size() * 4];
    distinct.forEach((key, i) -> {
      pixels[i * 4] = Float.intBitsToFloat(key.red());
      pixels[i * 4 + 1] = Float.intBitsToFloat(key.green());
      pixels[i * 4 + 2] = Float.intBitsToFloat(key.blue());
      pixels[i * 4 + 3] = Float.intBitsToFloat(key.alpha());
    });
    byte[] encoded = ReferenceCpuRenderer.encode(pixels, distinct.size(), 1).rgba();
    byte[] rgba = new byte[frame.length];
    for (int pixel = 0; pixel < index.length; pixel++) {
      System.arraycopy(encoded, index[pixel] * 4, rgba, pixel * 4, 4);
    }
    return rgba;
  }

  /** Every blend in order: the pixel, its value before and the alpha, hashed. */
  private static final class Recorder {
    long count;
    long hash;

    void blend(float[] frame, int at, double alpha) {
      count++;
      hash = mix(hash, at);
      hash = mix(hash, Double.doubleToLongBits(alpha));
      for (int c = 0; c < 4; c++) {
        hash = mix(hash, Float.floatToIntBits(frame[at + c]));
      }
    }

    private static long mix(long hash, long value) {
      long h = (hash ^ value) * 0x9E3779B97F4A7C15L;
      return h ^ (h >>> 29);
    }
  }
}
