package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Examples;
import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.gpu.GpuException;
import com.rethinkj.ui.layout.Flex;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Draws scenes on the GPU and on the CPU and compares them pixel by pixel. They cannot be equal bit
 * for bit, so each channel may differ by the {@link #tolerance}; see there for why. A scene that
 * differs by more leaves the CPU's image, the GPU's and their difference in {@code
 * target/gpu-vs-cpu}. Skipped without the natives module or an adapter.
 */
class GpuRendererTest {

  /**
   * How far a channel of the GPU's image may lie from the CPU's, in steps of 8 bits, at a pixel
   * that {@code layers} instances draw into: 1 step, and half a step more for every layer above
   * the first, rounded down. One instance over an opaque background: 1. Three: 2.
   *
   * <p>Both compute the same formulas, but the GPU in 32-bit floats where the CPU computes in
   * doubles, and it may fuse a multiply and an add or take a square root a last bit apart. That
   * moves a value by far less than a step, but now and then across the middle between two steps,
   * so that both round to neighbours: 1 step. Then, the GPU stores every draw into the 8-bit
   * target, rounding it by up to half a step, and the next draw that overlaps blends with that
   * rounded value, where the CPU keeps floats to the end. A layer above attenuates the error
   * beneath it, in steps as well as in light, since sRGB steps are never wider for darker values;
   * but on an anti-aliased edge, where coverage leaves a layer almost transparent, hardly at all.
   * So each layer adds up to half a step. A wrong formula, even an edge moved by a twentieth of a
   * pixel, differs by several times as much.
   */
  static int tolerance(int layers) {
    return Math.max(1, (layers + 1) / 2);
  }

  private static final Path FAILED = Path.of("target/gpu-vs-cpu");

  private static GpuRenderer gpu;

  @BeforeAll
  static void open() {
    gpu = openOrSkip(GpuRenderer::create);
    System.out.println("GPU renderer on " + gpu.adapter());
  }

  @AfterAll
  static void close() {
    if (gpu != null) {
      gpu.close();
    }
  }

  /** A renderer, or the test is skipped with a message that says what is missing. */
  static GpuRenderer openOrSkip(Supplier<GpuRenderer> create) {
    try {
      return create.get();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: wgpu-native cannot be loaded (no natives module for this"
          + " platform on the class path?). " + e.getMessage());
    } catch (GpuException e) {
      if (e.kind() == GpuException.Kind.ADAPTER_UNAVAILABLE) {
        assumeTrue(false, "Skipped: no GPU adapter on this machine. " + e.wgpuMessage());
      }
      throw e;
    }
    throw new AssertionError("unreachable");
  }

  /** A scene to draw, with the size of the image in physical pixels and the scale. */
  record Case(String name, Scene scene, int width, int height, float scale) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> cases() {
    List<Case> cases = new ArrayList<>();
    // The views behind the stored images, in the sizes and scales of their tests.
    cases.add(view("switch-off", Examples.lightSwitch(false), 200, 120, 1));
    cases.add(view("switch-on", Examples.lightSwitch(true), 200, 120, 2));
    cases.add(switchMoving(0.04));
    cases.add(switchMoving(0.12));
    cases.add(badgeLeaving(0.06));
    cases.add(badgeLeaving(0.12));
    cases.add(listScrolled(2));
    cases.add(listScrolled(1));
    // Scenes that stress one formula each.
    for (float scale : new float[] {1, 2, 1.5f}) {
      String at = "@" + (scale == (int) scale ? String.valueOf((int) scale) : "1.5");
      cases.add(scene("edges" + at, edges(), 160, 100, scale));
      cases.add(scene("borders" + at, borders(), 160, 100, scale));
      cases.add(scene("radii" + at, radii(), 160, 100, scale));
      cases.add(scene("overlaps" + at, overlaps(), 160, 100, scale));
      cases.add(view("clips" + at, clips(), 160, 100, scale));
      cases.add(scene("transparent" + at, transparent(), 160, 100, scale));
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theGpuDrawsWhatTheCpuDraws(Case c) {
    Comparison comparison = compare(gpu, c);
    System.out.println("GPU vs CPU " + comparison);
    if (comparison.beyond() > 0) {
      fail(comparison + ". See " + FAILED.toAbsolutePath());
    }
  }

  @Test
  void aWrongFormulaInTheShaderIsNoticed() {
    // The coverage ramp half a pixel wide instead of one at scale 2: edges are only a little
    // sharper.
    String broken = GpuRenderer.WGSL.replace(
        "clamp(0.5 - distance * globals.scale, 0.0, 1.0)",
        "clamp(0.5 - distance * globals.scale * 1.25, 0.0, 1.0)");
    assertTrue(!broken.equals(GpuRenderer.WGSL), "the shader still has the coverage formula");
    try (GpuRenderer wrong = GpuRenderer.create(broken)) {
      Comparison comparison = compare(wrong, scene("edges-broken", edges(), 160, 100, 2), false);
      System.out.println("GPU with a broken shader vs CPU " + comparison);
      assertTrue(comparison.beyond() > 100, comparison.toString());
    }
  }

  @Test
  void buffersGrowWithTheSceneAndShrinkingScenesDrawWhatTheyHave() {
    Scene big = new Scene();
    big.rect(0, 0, 64, 64, 0, 0, 0xFF000000, 0);
    for (int i = 0; i < 1000; i++) {
      big.rect(i % 64, i / 64 * 4, 1, 4, 0, 0, 0xFF000000 | i * 2654435 & 0xFFFFFF, 0);
    }
    assertEquals(0, compare(gpu, scene("many", big, 64, 64, 1)).beyond());
    Scene small = new Scene();
    small.rect(8, 8, 16, 16, 4, 0, 0xFF3B82F6, 0);
    assertEquals(0, compare(gpu, scene("few", small, 64, 64, 1)).beyond());
    Scene empty = new Scene();
    Pixels nothing = gpu.render(empty, 8, 8, 1);
    assertEquals(new Pixels(8, 8, new byte[256]).argb(3, 3), nothing.argb(3, 3));
  }

  @Test
  void refusesEmptyImagesAndBadScalesAndClosesOnce() {
    assertThrows(IllegalArgumentException.class, () -> gpu.render(new Scene(), 0, 8, 1));
    assertThrows(IllegalArgumentException.class, () -> gpu.render(new Scene(), 8, 8, 0));
    GpuRenderer other = openOrSkip(GpuRenderer::create);
    other.close();
    other.close();
    assertThrows(IllegalStateException.class, () -> other.render(new Scene(), 8, 8, 1));
  }

  // ---- comparing ---------------------------------------------------------------------------

  /**
   * How the GPU's image of a case differs from the CPU's.
   *
   * @param max the largest difference in red, green, blue and alpha
   * @param differing pixels with any difference
   * @param beyond pixels with a channel beyond the tolerance
   * @param layers the most instances that draw into one pixel
   * @param maxAtTwo the largest difference where at most two instances draw
   */
  record Comparison(Case c, int[] max, int differing, int beyond, int layers, int maxAtTwo,
      String first) {
    @Override
    public String toString() {
      return c.name + " (" + c.width + "x" + c.height + " @" + c.scale + ", " + c.scene.count()
          + " instances, up to " + layers + " in a pixel): largest difference r " + max[0]
          + " g " + max[1] + " b " + max[2] + " a " + max[3] + ", " + maxAtTwo
          + " where at most two draw; " + differing + " pixels differ, " + beyond
          + " beyond the tolerance" + (first.isEmpty() ? "" : ", first " + first);
    }
  }

  static Comparison compare(GpuRenderer renderer, Case c) {
    return compare(renderer, c, true);
  }

  /**
   * Compares in what the GPU stores: premultiplied colour encoded to sRGB, and alpha. The CPU's
   * image has straight alpha, rounded to 8 bits before the colour is divided by it; premultiplying
   * that again would lose too much where alpha is small. So the CPU's frame before it is encoded
   * is encoded here as an sRGB target stores it, channel by channel. Where the CPU's image is
   * opaque, that is the CPU's image.
   */
  static Comparison compare(GpuRenderer renderer, Case c, boolean writeImages) {
    float[] frame = CpuRenderer.renderLinear(c.scene, c.width, c.height, c.scale);
    byte[] stored = renderer.renderStored(c.scene, c.width, c.height, c.scale);
    int[] layers = layers(c);
    int[] max = new int[4];
    int maxLayers = 0;
    int maxAtTwo = 0;
    int differing = 0;
    int beyond = 0;
    String first = "";
    byte[] diff = new byte[c.width * c.height * 4];
    for (int y = 0; y < c.height; y++) {
      for (int x = 0; x < c.width; x++) {
        int at = (y * c.width + x) * 4;
        int gpuAt = y * GpuRenderer.bytesPerRow(c.width) + x * 4;
        int[] expected = new int[4];
        int worst = 0;
        for (int channel = 0; channel < 4; channel++) {
          double value = Math.clamp(frame[at + channel], 0, 1);
          expected[channel] = channel == 3 ? (int) Math.round(value * 255)
              : CpuRenderer.encode(value);
          int d = Math.abs((stored[gpuAt + channel] & 0xFF) - expected[channel]);
          max[channel] = Math.max(max[channel], d);
          worst = Math.max(worst, d);
        }
        int n = layers[y * c.width + x];
        int tolerance = tolerance(n);
        maxLayers = Math.max(maxLayers, n);
        if (n <= 2) {
          maxAtTwo = Math.max(maxAtTwo, worst);
        }
        if (worst > 0) {
          differing++;
        }
        if (worst > tolerance) {
          if (beyond++ == 0) {
            first = String.format("at %d,%d, %d layers: CPU %02X%02X%02X%02X, GPU %s"
                + " (premultiplied)", x, y, n, expected[0], expected[1], expected[2], expected[3],
                hex(stored, gpuAt));
          }
        }
        // Black where equal, grey to light grey within the tolerance, red beyond it.
        int shade = worst == 0 ? 0 : worst > tolerance ? -1 : 0x50 + 0x50 * worst / tolerance;
        diff[at] = (byte) (shade < 0 ? 0xFF : shade);
        diff[at + 1] = (byte) (shade < 0 ? 0 : shade);
        diff[at + 2] = (byte) (shade < 0 ? 0 : shade);
        diff[at + 3] = (byte) 0xFF;
      }
    }
    Comparison comparison =
        new Comparison(c, max, differing, beyond, maxLayers, maxAtTwo, first);
    if (beyond > 0 && writeImages) {
      try {
        Files.createDirectories(FAILED);
        Files.write(FAILED.resolve(c.name + ".cpu.png"),
            Png.encode(CpuRenderer.render(c.scene, c.width, c.height, c.scale)));
        Files.write(FAILED.resolve(c.name + ".gpu.png"),
            Png.encode(GpuRenderer.straight(stored, c.width, c.height)));
        Files.write(FAILED.resolve(c.name + ".diff.png"),
            Png.encode(new Pixels(c.width, c.height, diff)));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return comparison;
  }

  /**
   * How many instances draw into each pixel, at most: those whose pixels, bounded as the CPU
   * renderer bounds them, include it.
   */
  private static int[] layers(Case c) {
    int[] layers = new int[c.width * c.height];
    Scene s = c.scene;
    for (int i = 0; i < s.count(); i++) {
      if (!(s.get(i, Scene.WIDTH) > 0 && s.get(i, Scene.HEIGHT) > 0)) {
        continue;
      }
      int clip = s.clipOf(i);
      double left = Math.max(s.get(i, Scene.X), s.clipGet(clip, Scene.X));
      double top = Math.max(s.get(i, Scene.Y), s.clipGet(clip, Scene.Y));
      double right = Math.min(s.get(i, Scene.X) + s.get(i, Scene.WIDTH),
          s.clipGet(clip, Scene.X) + s.clipGet(clip, Scene.WIDTH));
      double bottom = Math.min(s.get(i, Scene.Y) + s.get(i, Scene.HEIGHT),
          s.clipGet(clip, Scene.Y) + s.clipGet(clip, Scene.HEIGHT));
      int x0 = Math.max((int) Math.floor(left * c.scale) - 1, 0);
      int y0 = Math.max((int) Math.floor(top * c.scale) - 1, 0);
      int x1 = Math.min((int) Math.ceil(right * c.scale) + 1, c.width);
      int y1 = Math.min((int) Math.ceil(bottom * c.scale) + 1, c.height);
      for (int y = y0; y < y1; y++) {
        for (int x = x0; x < x1; x++) {
          layers[y * c.width + x]++;
        }
      }
    }
    return layers;
  }

  private static String hex(byte[] rgba, int at) {
    return String.format("%02X%02X%02X%02X", rgba[at], rgba[at + 1], rgba[at + 2], rgba[at + 3]);
  }

  // ---- the views behind the stored images ---------------------------------------------------

  private static Case view(String name, El root, float width, float height, float scale) {
    Flex.layout(root, width, height);
    return scene(name, Scene.of(root), width, height, scale);
  }

  /** A case of a scene laid out for this size in logical pixels. */
  private static Case scene(String name, Scene scene, float width, float height, float scale) {
    return new Case(name, scene, Math.round(width * scale), Math.round(height * scale), scale);
  }

  /** As the stored images switch-moving-*: pressed, then {@code seconds} later, at scale 2. */
  private static Case switchMoving(double seconds) {
    App<Lights> app = new App<>(new Lights(), Examples::lights);
    app.resize(200, 120, 2);
    app.frame();
    press(app, 90, 60);
    app.frame();
    app.advance(seconds);
    app.frame();
    return of(app, "switch-moving-" + Math.round(seconds * 1000) + "ms", 200, 120, 2);
  }

  /** As the stored images badge-leaving-*: the badge fades out, at scale 1. */
  private static Case badgeLeaving(double seconds) {
    Lights lights = new Lights();
    lights.on = true;
    App<Lights> app = new App<>(lights, Examples::badged);
    app.resize(200, 120, 1);
    app.frame();
    El toggle = find(app.tree(), "switch");
    press(app, toggle.x() + toggle.width() / 2, toggle.y() + toggle.height() / 2);
    app.frame();
    app.advance(seconds);
    app.frame();
    return of(app, "badge-leaving-" + Math.round(seconds * 1000) + "ms", 200, 120, 1);
  }

  /** As the stored image list-scrolled: 90 pixels down, rows cut by the rounded clip. */
  private static Case listScrolled(float scale) {
    App<Object> app = new App<>(new Object(), s -> Examples.list());
    app.resize(240, 200, scale);
    app.frame();
    app.wheel(120, 100, 0, 90);
    app.frame();
    return of(app, "list-scrolled@" + (int) scale, 240, 200, scale);
  }

  private static Case of(App<?> app, String name, float width, float height, float scale) {
    return scene(name, app.scene(), width, height, scale);
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
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

  // ---- scenes that stress the formulas ------------------------------------------------------

  private static final int BACKGROUND = 0xFF15161C;

  private static Scene background() {
    Scene scene = new Scene();
    scene.rect(0, 0, 160, 100, 0, 0, BACKGROUND, 0);
    return scene;
  }

  /** Edges that cover a pixel in part: fractional positions and sizes, hairlines. */
  private static Scene edges() {
    Scene scene = background();
    for (int i = 0; i < 8; i++) {
      float shift = i / 8f;
      scene.rect(4 + i * 19 + shift, 4 + shift, 13.3f + shift, 9.6f, 0, 0, 0xFFFFFFFF, 0);
      scene.rect(4 + i * 19 + shift, 18 + shift / 2, 0.25f + i * 0.25f, 30, 0, 0, 0xFFE0F0FF, 0);
      scene.rect(10 + i * 19, 18 + shift, 9, 0.2f + i * 0.2f, 0, 0, 0xFF60A5FA, 0);
      scene.rect(4 + i * 19.37f, 54 + i * 0.37f, 15.5f, 15.5f, 3.3f, 0, 0xFFF97316, 0);
    }
    // A shape whose edges lie on pixel centres at every scale tested, and a huge one.
    scene.rect(4.5f, 76.5f, 40, 20, 0, 0, 0xFF22C55E, 0);
    scene.rect(52.25f, 76.75f, 1e4f, 1e4f, 0, 0, 0xFFA855F7, 0);
    return scene;
  }

  /** Borders: hairline to thick, wider than half the rectangle, translucent, without a fill. */
  private static Scene borders() {
    Scene scene = background();
    float[] widths = {0.5f, 1, 1.5f, 3, 6.5f};
    for (int i = 0; i < widths.length; i++) {
      float x = 4 + i * 31;
      scene.rect(x, 4, 26, 20, 0, widths[i], 0xFF2563EB, 0xFFF8FAFC);
      scene.rect(x + 0.3f, 28.4f, 26, 20, 8, widths[i], 0xFF1E293B, 0xFFFACC15);
      // The radius smaller than the border: the inside has square corners.
      scene.rect(x, 52, 26, 20, 2, widths[i] + 1, 0xFF0EA5E9, 0xFFEF4444);
      // Translucent border over an opaque fill, and a border with nothing inside.
      scene.rect(x, 76, 26, 20, 5, widths[i], 0xFF334155, 0x8022C55E);
      scene.rect(x + 13, 80, 12, 12, 6, widths[i], 0x00000000, 0xC0FFFFFF);
    }
    // A border wider than half of the rectangle leaves no fill.
    scene.rect(150, 4, 8, 8, 2, 5, 0xFFFF00FF, 0xFF00FFFF);
    return scene;
  }

  /** Corner radii from none to more than half the side, and rectangles smaller than a pixel. */
  private static Scene radii() {
    Scene scene = background();
    float[] radii = {-3, 0, 0.25f, 0.75f, 2, 5.5f, 11, 100};
    for (int i = 0; i < radii.length; i++) {
      float x = 3 + i * 19.5f;
      scene.rect(x, 4, 16, 16, radii[i], 0, 0xFFE2E8F0, 0);
      scene.rect(x, 24, 17.5f, 9, radii[i], 0, 0xFF38BDF8, 0);
      scene.rect(x + 0.4f, 37.4f, 6.4f, 30, radii[i], 0, 0xFFFB7185, 0);
    }
    float[] tiny = {0.1f, 0.4f, 0.5f, 0.8f, 1, 1.5f, 2.2f};
    for (int i = 0; i < tiny.length; i++) {
      float x = 4 + i * 12.3f;
      scene.rect(x, 74, tiny[i], tiny[i], tiny[i] / 2, 0, 0xFFFFFFFF, 0);
      scene.rect(x, 80, tiny[i], 12, 0, 0, 0xFFFDE047, 0);
      scene.rect(x + 3, 84.3f, 6, tiny[i], 0, 0, 0xFFA3E635, 0);
    }
    return scene;
  }

  /** Translucent rectangles over each other, up to six deep. */
  private static Scene overlaps() {
    Scene scene = background();
    int[] colours = {0x803B82F6, 0x60EF4444, 0xA022C55E, 0x40FACC15, 0xC0A855F7, 0x30FFFFFF};
    for (int i = 0; i < colours.length; i++) {
      scene.rect(8 + i * 11.5f, 6 + i * 7.25f, 60, 40, 9, 0, colours[i], 0);
    }
    for (int i = 0; i < 6; i++) {
      scene.rect(100.5f, 10, 50, 80, 25, 0, 0x33FFFFFF, 0);
      scene.rect(106 + i * 2, 20 + i * 3, 20, 30, 4, 1.5f, 0x2010B981, 0x60F59E0B);
    }
    scene.rect(4, 70, 90, 26, 13, 2, 0x40000000, 0x80FFFFFF);
    return scene;
  }

  /** Rounded clips, nested and cutting through rectangles, borders and translucent groups. */
  private static El clips() {
    El inner = el().absolute(30.5f, 12.25f).size(70, 40).radius(12).clip().fill(0xFF1F2937)
        .border(1.5f, 0xFF9CA3AF)
        .child(el().absolute(-10, -6).size(40, 30).radius(6).fill(0xFF3B82F6))
        .child(el().absolute(52, 20).size(40, 40).radius(20).fill(0xC0F43F5E))
        .child(el().absolute(10.3f, 30.6f).size(50, 8).border(2, 0xFFFDE047));
    El outer = el().absolute(8.5f, 8.5f).size(110, 80).radius(20).clip().fill(0xFF0F172A)
        .child(el().absolute(-15, 50).size(60, 60).radius(8).fill(0xFF22C55E))
        .child(el().absolute(80, -10).size(60, 30).fill(0xFFF59E0B))
        .child(inner)
        .child(el().absolute(5, 60).size(100, 30).opacity(0.5f).clip().radius(6)
            .child(el().absolute(-4, 4).size(60, 30).radius(4).fill(0xFFFFFFFF))
            .child(el().absolute(40, -8).size(80, 20).radius(10).fill(0xFFA855F7)));
    El thin = el().absolute(124.25f, 10).size(28, 80).radius(14).clip().fill(0xFF334155)
        .child(el().absolute(-6, 20.5f).size(40, 0.7f).fill(0xFFFFFFFF))
        .child(el().absolute(10, -5).size(8, 100).radius(4).fill(0x8038BDF8));
    return col().wFull().hFull().fill(BACKGROUND).child(outer).child(thin);
  }

  /** Translucent shapes over nothing: the image keeps their alpha. */
  private static Scene transparent() {
    Scene scene = new Scene();
    scene.rect(10.5f, 10.5f, 60, 40, 12, 0, 0x803B82F6, 0);
    scene.rect(40.25f, 30.25f, 60, 40, 20, 3, 0x40EF4444, 0xFFFFFFFF);
    scene.rect(110, 10, 40, 80, 20, 0, 0x08FFFFFF, 0);
    scene.rect(4, 80, 100, 0.5f, 0, 0, 0xFF22C55E, 0);
    scene.rect(4, 90.3f, 100, 6, 3, 1, 0x00000000, 0xFFFACC15);
    return scene;
  }
}
