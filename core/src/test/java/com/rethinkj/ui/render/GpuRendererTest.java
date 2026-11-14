package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Examples;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.gpu.GpuException;
import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Draws scenes on the GPU and on the CPU and compares them pixel by pixel: scenes that stress one
 * formula each, text, fields, overlays and images. They cannot be equal bit for bit, so each channel may differ by the {@link #tolerance} and what the GPU's rounding of
 * alpha adds to it ({@link #ALPHA_STEP}); see there for why. A scene that differs by more leaves
 * the CPU's image, the GPU's and their difference in {@code target/gpu-vs-cpu}. Skipped without
 * the natives module or an adapter.
 */
class GpuRendererTest {

  /**
   * How far a channel of the GPU's image may lie from the CPU's, in steps of 8 bits, at a pixel
   * that {@code layers} instances draw into, before the {@link #ALPHA_STEP rounding of alpha} in
   * blending is added: 1 step, and half a step more for every layer above the first, rounded down.
   * One instance over an opaque background: 1. Three: 2.
   *
   * <p>Both compute the same formulas, but the GPU in 32-bit floats where the CPU computes in
   * doubles, and it may fuse a multiply and an add or take a square root, a power or a logarithm a
   * last bit apart. That moves a value by far less than a step, but now and then across the middle
   * between two steps, so that both round to neighbours: 1 step. A glyph's texel is read exactly,
   * as its byte divided by 255. Then, the GPU stores every draw into the 8-bit target, rounding it
   * by up to half a step, and the next draw that overlaps blends with that rounded value, where
   * the CPU keeps floats to the end. A layer above attenuates the error beneath it, in steps as
   * well as in light, since sRGB steps are never wider for darker values; but on an anti-aliased
   * edge, where coverage leaves a layer almost transparent, hardly at all. So each layer adds up
   * to half a step. A wrong formula, even an edge moved by a twentieth of a pixel, differs by
   * several times as much.
   */
  static int tolerance(int layers) {
    return Math.max(1, (layers + 1) / 2);
  }

  /**
   * How far the GPU may round the alpha of a source when it blends, as a fraction of the light
   * under it: one step of the target's 8-bit alpha. The blend keeps {@code 1 - alpha} of what lies
   * under the source, and the GPU takes that alpha at the precision of the target's alpha channel,
   * 8 bits, where the CPU keeps it exact; Direct3D lets a conversion to 8 bits be 0.6 of a step
   * off, and this allows a whole one. Measured on an NVIDIA RTX 2000 with Direct3D 12: 0.56 of a
   * step. So a layer lets up to 1/255 more or less of the light under it through. Over a dark
   * pixel that is nothing; under nearly opaque dark text on white it moves the result, near black
   * where sRGB steps are narrowest in light, by several steps: {@link #compare} adds what it can
   * be at each pixel, in light, to the tolerance.
   */
  static final double ALPHA_STEP = 1 / 255.0;

  private static final Path FAILED = Path.of("target/gpu-vs-cpu");

  private static GpuRenderer gpu;

  @BeforeAll
  static void open() {
    gpu = openOrSkip(GpuRenderer::create);
    System.out.println("GPU renderer on " + gpu.adapter());
    // In the same natives module as wgpu-native, so this skips only if that one is incomplete.
    TextLibraries.assumeLoaded();
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
      assumeTrue(
          false,
          "Skipped: wgpu-native cannot be loaded (no natives module for this"
              + " platform on the class path?). " + e.getMessage());
    } catch (GpuException e) {
      if (e.kind() == GpuException.Kind.ADAPTER_UNAVAILABLE) {
        assumeTrue(false, "Skipped: no GPU adapter on this machine. " + e.wgpuMessage());
      }
      throw e;
    }
    throw new AssertionError("unreachable");
  }

  /**
   * A scene to draw, with the size of the image in physical pixels and the scale.
   */
  record Case(String name, Scene scene, int width, int height, float scale) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> cases() throws IOException {
    if (cases == null) {
      cases = build();
    }
    return cases.stream();
  }

  /** The cases, built once. */
  private static List<Case> cases;

  /** Each scene once, at a scale between whole pixels where that is what stresses it. */
  private static List<Case> build() throws IOException {
    List<Case> cases = new ArrayList<>();
    float s = 1.5f;
    // Scenes that stress one formula each.
    cases.add(scene("edges", edges(), 160, 100, s));
    cases.add(scene("borders", borders(), 160, 100, s));
    cases.add(scene("radii", radii(), 160, 100, s));
    cases.add(scene("sides", sides(), 160, 100, s));
    cases.add(scene("overlaps", overlaps(), 160, 100, s));
    cases.add(view("clips", clips(), 160, 100, s));
    cases.add(scene("transparent", transparent(), 160, 100, s));
    cases.add(nestedClips(s));
    cases.add(bars("bars-hovered", s));
    cases.add(text("shadows", shadows(), 240, 160, s));
    cases.add(shapes(s));
    // A scrim reaches a billion pixels beyond the window, beyond an int from scale 2.15 on.
    cases.add(scrim(2.5f));
    // Thousands of instances of a pixel each.
    cases.add(scene("many", many(), 80, 80, 1.75f));
    // A million rows: those that exist, placed from the first one, at offsets in the millions.
    cases.add(scripted(
        "rows-far",
        new App<>(new Object(), x -> Examples.rows()),
        280,
        220,
        2,
        "scroll #rows row 765432\n"));
    // Overlays, drawn after the tree with clips of their own: a menu across the rounded clip of a
    // scrolled list with its submenu, and a dialog over a scrim.
    cases.add(overlay("overlay-nested", s, "press #export\n"));
    cases.add(overlay("overlay-scrim", s, "press #delete\n"));
    // Text in both fonts at 9 to 25 pixels at quarter positions, both renderings, grey on light,
    // under opacity, cut by rounded clips, overlapping translucent boxes, over several pages of a
    // small atlas, and in colours and fonts of a highlighter.
    cases.add(scattered(1.25f));
    cases.add(text("text-sizes", Examples.textSizes(), 720, 176, s));
    cases.add(text(
        "text-sizes-windows",
        Examples.textSizes(),
        720,
        176,
        1,
        TextRendering.WINDOWS,
        new Atlas()));
    cases.add(text("text-grey-on-light", Examples.greyOnLight(), 480, 104, 1));
    cases.add(text("text-opacity", Examples.textOpacity(), 336, 76, s));
    cases.add(text("text-clipped", clippedText(), 240, 120, 2));
    cases.add(text("text-overlaps", overlappingText(), 240, 120, s));
    cases.add(textPages(s));
    cases.add(text("text-highlighted", highlighted(), 280, 72, s));
    // Text fields: the selection, the caret and the line under a composition are rectangles.
    cases.add(marked("area-marked", s));
    cases.add(scripted(
        "field-composing",
        new App<>(new Examples.Form(), Examples::form),
        240,
        80,
        s,
        "press #name\ntype \"SELECT name\"\nkey ArrowLeft shift\ncompose \"ni\"\n"));
    // Images: one to one, magnified, made smaller, with alpha, clipped, at half opacity.
    cases.add(text("images", Examples.images(s), 320, 220, s));
    // Fallback fonts: glyphs of four fonts on one line and in a field selected across them.
    cases.add(text("fallback", Examples.fallbackText(), 360, 128, s));
    cases.add(scripted(
        "fallback-field",
        new App<>(new Examples.Form(), Examples::fallbackField),
        240,
        80,
        s,
        Examples.FALLBACK_FIELD));
    return cases;
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
  void anImageGoesToTheGpuOnceAndIsGivenUpBeyondTheMemory() {
    Image a = Image.of(Examples.photo(64, 48));
    Image b = Image.of(Examples.photo(48, 64));
    Image c = Image.of(Examples.disc(40));
    long uploads = gpu.imageUploads();
    Scene first = scene(
        El.image(a).absolute(0, 0),
        El.image(b).absolute(70, 0),
        El.image(a).absolute(0, 50).size(32, 24));
    gpu.render(first, 120, 120, 1);
    assertEquals(2, gpu.imageUploads() - uploads, "two images, the first drawn twice");
    gpu.render(first, 120, 120, 1);
    gpu.render(scene(El.image(b).absolute(4, 4)), 120, 120, 1);
    assertEquals(2, gpu.imageUploads() - uploads, "nothing new, nothing uploaded");

    // Room for a and c only: b, drawn least recently, is given up, and comes back when drawn.
    Scene tight = scene(El.image(a).absolute(0, 0), El.image(c).absolute(70, 0))
        .imageMemory(a.bytes() + c.bytes());
    assertEquals(0, compare(gpu, scene("tight", tight, 120, 120, 1)).beyond());
    assertEquals(3, gpu.imageUploads() - uploads);
    assertTrue(gpu.imageBytes() <= a.bytes() + c.bytes(), gpu.imageBytes() + " bytes");
    Scene back = scene(El.image(b).absolute(0, 0)).imageMemory(0);
    assertEquals(0, compare(gpu, scene("back", back, 120, 120, 1)).beyond());
    assertEquals(4, gpu.imageUploads() - uploads, "b again");
    assertEquals(1, gpu.imagesResident(), "only the scene's own, though beyond the memory");

    // A scene without images gives up those beyond its memory too.
    gpu.render(scene().imageMemory(0), 120, 120, 1);
    assertEquals(0, gpu.imagesResident(), "no scene shows b any more");
    assertEquals(0, gpu.imageBytes());
  }

  /** A scene of these elements on a light background, laid out for 120 by 120. */
  private static Scene scene(El... children) {
    El root = el().size(120, 120).fill(0xFFF5F5F4).children(children);
    Flex.layout(root, 120, 120);
    return Scene.of(root, 1);
  }

  @Test
  void aPageIsWrittenWholeWhenItAppearsAndThenOnlyWhereItChanged() {
    Atlas atlas = new Atlas();
    long writes = gpu.atlasWrites();
    long texels = gpu.atlasTexels();
    Case first = text(
        "upload-first",
        label("Kunde Müller", 0xFF1A1A1A, 8),
        200,
        40,
        1,
        TextRendering.LINUX,
        atlas);
    assertEquals(0, compare(gpu, first).beyond());
    assertEquals(1, gpu.atlasWrites() - writes, "the new page");
    assertEquals((long) Atlas.PAGE_SIZE * Atlas.PAGE_SIZE, gpu.atlasTexels() - texels, "whole");

    // The same glyphs in another colour and place, twice: nothing to write.
    writes = gpu.atlasWrites();
    Case recoloured = text(
        "upload-recoloured",
        label("Kunde Müller", 0xFF2563EB, 13),
        200,
        40,
        1,
        TextRendering.LINUX,
        atlas);
    assertEquals(0, compare(gpu, recoloured).beyond());
    gpu.render(recoloured.scene(), 200, 40, 1);
    assertEquals(writes, gpu.atlasWrites(), "no change, no upload");

    // New glyphs: only the region they went to.
    texels = gpu.atlasTexels();
    Case more = text(
        "upload-more",
        label("Kunde Müller, Köln: 1.234,56 €", 0xFF1A1A1A, 8),
        200,
        40,
        1,
        TextRendering.LINUX,
        atlas);
    Atlas.Region changed = atlas.pages().getFirst().changed();
    assertEquals(0, compare(gpu, more).beyond());
    assertEquals(writes + 1, gpu.atlasWrites());
    assertEquals((long) changed.width() * changed.height(), gpu.atlasTexels() - texels);
    assertTrue(gpu.atlasTexels() - texels < 64 * 64, changed + " is a few glyphs");
  }

  @Test
  void aPageThatTheAtlasEmptiedIsWrittenWholeAgain() {
    // Room for one page of 64 texels: each frame needs more, so the atlas grows for it, and
    // empties pages of the frame before for the next.
    Atlas atlas = new Atlas(64, 64 * 64, 4096);
    Case upper = text("pages-upper", paragraph(UPPER), 300, 120, 2, TextRendering.LINUX, atlas);
    int pages = atlas.pages().size();
    assertTrue(pages >= 3, pages + " pages");
    assertEquals(0, compare(gpu, upper).beyond());

    Case lower = text("pages-lower", paragraph(LOWER), 300, 120, 2, TextRendering.LINUX, atlas);
    List<Atlas.Page> emptied = atlas.pages().subList(0, pages).stream()
        .filter(page -> page.changed().equals(new Atlas.Region(0, 0, 64, 64)))
        .toList();
    assertTrue(!emptied.isEmpty(), "a page of the first frame was emptied for the second");
    long texels = gpu.atlasTexels();
    assertEquals(0, compare(gpu, lower).beyond());
    assertTrue(gpu.atlasTexels() - texels >= emptied.size() * 64L * 64);

    Case again =
        text("pages-upper-again", paragraph(UPPER), 300, 120, 2, TextRendering.LINUX, atlas);
    assertEquals(0, compare(gpu, again).beyond());
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

  @Test
  void aSizeTheDeviceRefusesLeavesTheRendererDrawing() {
    Scene small = new Scene();
    small.rect(8, 8, 16, 16, 4, 0, 0xFF3B82F6, 0);
    assertEquals(0, compare(gpu, scene("before-refused", small, 32, 32, 1)).beyond());
    // Beyond every device's largest texture, which is 8192 or more.
    for (int[] size : new int[][] {{Integer.MAX_VALUE, 8}, {8, 100_000}}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> gpu.render(small, size[0], size[1], 1),
          size[0] + "x" + size[1]);
      assertEquals(0, compare(gpu, scene("after-refused", small, 32, 32, 1)).beyond());
    }
    assertEquals(0, compare(gpu, scene("another-size", small, 40, 40, 1)).beyond());
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
   * @param maxSlack the most steps that the rounding of alpha added to the tolerance at a pixel
   */
  record Comparison(
      Case c,
      int[] max,
      int differing,
      int beyond,
      int layers,
      int maxAtTwo,
      int maxSlack,
      String first) {
    @Override
    public String toString() {
      return c.name + " (" + c.width + "x" + c.height + " @" + c.scale + ", " + c.scene.count()
          + " instances, up to " + layers + " in a pixel): largest difference r " + max[0]
          + " g " + max[1] + " b " + max[2] + " a " + max[3] + ", " + maxAtTwo
          + " where at most two draw; " + differing + " pixels differ, " + beyond
          + " beyond the tolerance, which the rounding of alpha raised by up to " + maxSlack
          + (first.isEmpty() ? "" : "; first " + first);
    }
  }

  /**
   * The steps of 8 bits that a channel's value, in light, moves when it moves by {@code slack}
   * either way: in sRGB for a colour, linearly for alpha. Rounded to the nearest step, so that the
   * slack a blend over a dark pixel adds, a fraction of a step, adds nothing; the {@link
   * #ALPHA_STEP} of a whole step, nearly twice what was measured, leaves room for that.
   */
  static int steps(double value, double slack, boolean alpha) {
    if (alpha) {
      return (int) Math.round(slack * 255);
    }
    double up = srgb(Math.min(value + slack, 1)) - srgb(value);
    double down = srgb(value) - srgb(Math.max(value - slack, 0));
    return (int) Math.round(Math.max(up, down) * 255);
  }

  /** A linear value as sRGB, from 0 to 1, not rounded. */
  private static double srgb(double linear) {
    return Colors.srgb(linear);
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
    // In light, per channel: how far rounding the alpha of every blend may move the GPU's value.
    double[] slack = new double[c.width * c.height * 4];
    float[] frame =
        CpuRenderer.renderLinear(c.scene, c.width, c.height, c.scale, (image, at, alpha) -> {
          for (int channel = 0; channel < 4; channel++) {
            slack[at + channel] = slack[at + channel] * (1 - alpha + ALPHA_STEP)
                + Math.abs(image[at + channel]) * ALPHA_STEP;
          }
        });
    byte[] stored = renderer.renderStored(c.scene, c.width, c.height, c.scale);
    int[] layers = layers(c);
    int[] max = new int[4];
    int maxLayers = 0;
    int maxAtTwo = 0;
    int maxSlack = 0;
    int differing = 0;
    int beyond = 0;
    String first = "";
    byte[] diff = new byte[c.width * c.height * 4];
    for (int y = 0; y < c.height; y++) {
      for (int x = 0; x < c.width; x++) {
        int at = (y * c.width + x) * 4;
        int gpuAt = y * GpuRenderer.bytesPerRow(c.width) + x * 4;
        int n = layers[y * c.width + x];
        int[] expected = new int[4];
        int worst = 0;
        int tolerance = tolerance(n);
        boolean out = false;
        for (int channel = 0; channel < 4; channel++) {
          double value = Math.clamp(frame[at + channel], 0, 1);
          expected[channel] =
              channel == 3 ? (int) Math.round(value * 255) : CpuRenderer.encode(value);
          int d = Math.abs((stored[gpuAt + channel] & 0xFF) - expected[channel]);
          int allowed = tolerance(n) + steps(value, slack[at + channel], channel == 3);
          max[channel] = Math.max(max[channel], d);
          worst = Math.max(worst, d);
          tolerance = Math.max(tolerance, allowed);
          maxSlack = Math.max(maxSlack, allowed - tolerance(n));
          out |= d > allowed;
        }
        maxLayers = Math.max(maxLayers, n);
        if (n <= 2) {
          maxAtTwo = Math.max(maxAtTwo, worst);
        }
        if (worst > 0) {
          differing++;
        }
        if (out) {
          if (beyond++ == 0) {
            first = String.format(
                "at %d,%d, %d layers: CPU %02X%02X%02X%02X, GPU %s"
                    + " (premultiplied), %d allowed",
                x,
                y,
                n,
                expected[0],
                expected[1],
                expected[2],
                expected[3],
                hex(stored, gpuAt),
                tolerance);
          }
        }
        // Black where equal, grey to light grey within the tolerance, red beyond it.
        int shade =
            worst == 0 ? 0 : out ? -1 : 0x50 + 0x50 * Math.min(worst, tolerance) / tolerance;
        diff[at] = (byte) (shade < 0 ? 0xFF : shade);
        diff[at + 1] = (byte) (shade < 0 ? 0 : shade);
        diff[at + 2] = (byte) (shade < 0 ? 0 : shade);
        diff[at + 3] = (byte) 0xFF;
      }
    }
    Comparison comparison =
        new Comparison(c, max, differing, beyond, maxLayers, maxAtTwo, maxSlack, first);
    if (beyond > 0 && writeImages) {
      try {
        Files.createDirectories(FAILED);
        Files.write(
            FAILED.resolve(c.name + ".cpu.png"),
            Png.encode(CpuRenderer.render(c.scene, c.width, c.height, c.scale)));
        Files.write(
            FAILED.resolve(c.name + ".gpu.png"),
            Png.encode(GpuRenderer.straight(stored, c.width, c.height)));
        Files.write(
            FAILED.resolve(c.name + ".diff.png"), Png.encode(new Pixels(c.width, c.height, diff)));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return comparison;
  }

  /**
   * How many instances draw into each pixel, at most: those whose pixels, bounded as the CPU
   * renderer bounds them, include it. A rectangle reaches a pixel beyond its edges; a glyph covers
   * exactly the pixels of its texels.
   */
  private static int[] layers(Case c) {
    int[] layers = new int[c.width * c.height];
    Scene s = c.scene;
    for (int i = 0; i < s.count(); i++) {
      if (!(s.get(i, Scene.WIDTH) > 0 && s.get(i, Scene.HEIGHT) > 0)) {
        continue;
      }
      int clip = s.clipOf(i);
      int x0;
      int y0;
      int x1;
      int y1;
      if (s.getInt(i, Scene.KIND) == Scene.GLYPH) {
        int size = s.getInt(i, Scene.ATLAS_SIZE);
        x0 = (int) Math.round(s.get(i, Scene.X) * (double) c.scale);
        y0 = (int) Math.round(s.get(i, Scene.Y) * (double) c.scale);
        x1 = x0 + (size & 0xFFFF);
        y1 = y0 + (size >>> 16);
        x0 = Math.max(x0, (int) Math.floor(s.clipGet(clip, Scene.X) * c.scale) - 1);
        y0 = Math.max(y0, (int) Math.floor(s.clipGet(clip, Scene.Y) * c.scale) - 1);
        x1 = Math.min(
            x1,
            (int) Math.ceil((s.clipGet(clip, Scene.X) + s.clipGet(clip, Scene.WIDTH)) * c.scale)
                + 1);
        y1 = Math.min(
            y1,
            (int) Math.ceil((s.clipGet(clip, Scene.Y) + s.clipGet(clip, Scene.HEIGHT)) * c.scale)
                + 1);
        x0 = Math.max(x0, 0);
        y0 = Math.max(y0, 0);
        x1 = Math.min(x1, c.width);
        y1 = Math.min(y1, c.height);
      } else {
        // A shadow reaches three deviations beyond its shape.
        double reach = s.getInt(i, Scene.KIND) != Scene.SHADOW
            ? 0
            : 3 * CpuRenderer.deviation(s.get(i, Scene.SIGMA), c.scale);
        double left = Math.max(s.get(i, Scene.X) - reach, s.clipGet(clip, Scene.X));
        double top = Math.max(s.get(i, Scene.Y) - reach, s.clipGet(clip, Scene.Y));
        double right = Math.min(
            s.get(i, Scene.X) + s.get(i, Scene.WIDTH) + reach,
            s.clipGet(clip, Scene.X) + s.clipGet(clip, Scene.WIDTH));
        double bottom = Math.min(
            s.get(i, Scene.Y) + s.get(i, Scene.HEIGHT) + reach,
            s.clipGet(clip, Scene.Y) + s.clipGet(clip, Scene.HEIGHT));
        x0 = Math.max((int) Math.floor(left * c.scale) - 1, 0);
        y0 = Math.max((int) Math.floor(top * c.scale) - 1, 0);
        x1 = Math.min((int) Math.ceil(right * c.scale) + 1, c.width);
        y1 = Math.min((int) Math.ceil(bottom * c.scale) + 1, c.height);
      }
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

  // ---- building cases ----------------------------------------------------------------------

  private static Case view(String name, El root, float width, float height, float scale) {
    Flex.layout(root, width, height);
    return scene(name, Scene.of(root, scale), width, height, scale);
  }

  /** A case of a scene laid out for this size in logical pixels. */
  private static Case scene(String name, Scene scene, float width, float height, float scale) {
    return new Case(name, scene, Math.round(width * scale), Math.round(height * scale), scale);
  }

  /** The app after its first frame, these steps of a script and a frame. */
  private static Case scripted(
      String name, App<?> app, int width, int height, float scale, String steps)
      throws IOException {
    app.textRendering(TextRendering.LINUX);
    Script.run(
        "size " + width + " " + height + " scale " + scale + "\nframe\n" + steps + "frame\n",
        app,
        Path.of("target/gpu-vs-cpu-scripts"));
    return scene(name, app.scene(), width, height, scale);
  }

  /**
   * The menus of overlay.txt with the list scrolled by 13.25 pixels and the context menu open on
   * row 3, after these steps of a script and a frame.
   */
  private static Case overlay(String name, float scale, String steps) throws IOException {
    return scripted(
        name,
        new App<>(new Examples.Menus(), Examples::overlays),
        240,
        200,
        scale,
        "wheel #list 0 13.25\nframe\npress #row-3 right\nframe\n" + steps);
  }

  /** Forty lines of text in both fonts at sizes from 9 to 25, at fractional positions. */
  private static Case scattered(float scale) {
    App<Object> app = new App<>(new Object(), s -> {
      El root = el().fill(0xFFFFFFFF);
      for (int i = 0; i < 40; i++) {
        root.child(El.text("AVgy fi 0123456789 quarter pixels")
            .font(i % 2 == 0 ? Font.sans() : Font.mono())
            .fontSize(9 + i % 17)
            .absolute(i % 5 * 31.3f, i * 5.7f));
      }
      return root;
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(300, 260, scale);
    app.frame();
    return scene("scattered@" + scale, app.scene(), 300, 260, scale);
  }

  /** 5000 squares of a logical pixel each over a white one. */
  private static Scene many() {
    Scene scene = new Scene();
    scene.rect(0, 0, 80, 80, 0, 0, 0xFFFFFFFF, 0);
    for (int i = 0; i < 5000; i++) {
      scene.rect(i % 80, i / 80, 1, 1, 0, 0, 0xFF123456, 0);
    }
    return scene;
  }

  @Test
  void randomShapesDrawAlikeAtScalesBetweenAndBeyond() {
    Random random = new Random(8212026);
    float[] scales = {1, 1.25f, 1.75f, 2.5f, 3};
    for (int k = 0; k < 40; k++) {
      Scene scene = new Scene();
      scene.rect(0, 0, 40, 40, 0, 0, 0xFFFFFFFF, 0);
      for (int i = 0; i < 3; i++) {
        float[] radii = new float[4];
        float[] borders = new float[4];
        for (int c = 0; c < 4; c++) {
          radii[c] = random.nextFloat() * 100;
          borders[c] = random.nextFloat() * 40;
        }
        scene.rect(
            random.nextFloat() * 50 - 20,
            random.nextFloat() * 50 - 20,
            random.nextFloat() * 50,
            random.nextFloat() * 50,
            radii,
            borders,
            random.nextInt(),
            random.nextInt());
      }
      float scale = scales[k % scales.length];
      Comparison comparison = compare(gpu, scene("random-" + k, scene, 40, 40, scale), false);
      assertEquals(0, comparison.beyond(), comparison.toString());
    }
  }

  /** {@link Examples#nestedClips}, its list scrolled by 13.25 pixels, at this scale. */
  static Case nestedClips(float scale) {
    App<Object> app = new App<>(new Object(), s -> Examples.nestedClips());
    app.textRendering(TextRendering.LINUX);
    app.resize(260, 180, scale);
    app.frame();
    app.wheel(60, 80, 0, 13.25f);
    app.frame();
    return scene("nested-clips", app.scene(), 260, 180, scale);
  }

  /** A modal box over a white window, with its scrim. */
  private static Case scrim(float scale) {
    App<Object> app = new App<>(
        new Object(),
        s -> el().size(20, 20)
            .fill(0xFFFFFFFF)
            .overlay(el().size(4, 4).centerInWindow().modal().scrim(0x80000000)));
    app.resize(20, 20, scale);
    app.frame();
    return scene("scrim@" + scale, app.scene(), 20, 20, scale);
  }

  /** {@link Examples#shapes}, focused, at this scale. */
  private static Case shapes(float scale) throws IOException {
    return scripted(
        "shapes",
        new App<>(new Object(), s -> Examples.shapes()),
        240,
        152,
        scale,
        "focus #ringed\n");
  }

  /** A view with text, laid out for the scale, its glyphs in an atlas of its own. */
  private static Case text(String name, El root, float width, float height, float scale) {
    return text(name, root, width, height, scale, TextRendering.LINUX, new Atlas());
  }

  /** A view with text, laid out for the scale, its glyphs in {@code atlas}. */
  private static Case text(
      String name,
      El root,
      float width,
      float height,
      float scale,
      TextRendering rendering,
      Atlas atlas) {
    Examples.laidOut(root, width, height, scale);
    return scene(name, Scene.of(root, null, 0, atlas, rendering, scale), width, height, scale);
  }

  // ---- text that stresses the glyph shader --------------------------------------------------

  private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ ÄÖÜ 0123456789 €$§%&@#";
  private static final String LOWER = "abcdefghijklmnopqrstuvwxyz äöüß ()[]{}<>/|+-*=?!;:,.";

  /**
   * Glyphs of one run in several colours, as a highlighter colours code: dark on light and light
   * on dark, so that each glyph gets its own correction, with a bold range shaped on its own.
   */
  private static El highlighted() {
    com.rethinkj.ui.Highlighter code = (line, state, out) -> {
      out.color(0, 6, 0xFF7C3AED).font(0, 6, Font.monoBold()); // SELECT
      out.color(10, 13, 0xFF15803D); // 'x'
      out.color(21, 26, 0xFF94A3B8); // -- 42
      return null;
    };
    String sql = "SELECT id, 'x' FROM t -- 42";
    return col()
        .wFull()
        .hFull()
        .children(
            el().wFull()
                .padding(6)
                .fill(0xFFFFFFFF)
                .child(El.text(sql)
                    .font(Font.mono())
                    .fontSize(13)
                    .color(0xFF1A1A1A)
                    .highlight(1, code)),
            el().wFull()
                .padding(6)
                .fill(0xFF1E1E2E)
                .child(El.text(sql)
                    .font(Font.mono())
                    .fontSize(13)
                    .color(0xFFE0E0E0)
                    .highlight(1, code)));
  }

  /**
   * A multi-line field with the highlighter of {@link #highlighted}, the background of its second
   * line, a range behind brackets, a line under an error and a selection over them, scrolled.
   */
  private static Case marked(String name, float scale) throws IOException {
    String[] value = {"SELECT id, 'x' FROM t -- 42\nWHERE (a = 1)\n  AND b\nLIMIT 5\n-- end"};
    com.rethinkj.ui.Highlighter code = (line, state, out) -> {
      out.color(0, Math.min(6, line.length()), 0xFF7C3AED).font(0, 5, Font.monoBold());
      return null;
    };
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .padding(8)
            .fill(0xFFFFFFFF)
            .child(El.text(value[0])
                .editable(v -> value[0] = v)
                .multiline()
                .key("area")
                .font(Font.mono())
                .fontSize(13)
                .color(0xFF1A1A1A)
                .w(220)
                .lines(3)
                .padding(4)
                .fill(0xFFF4F4F5)
                .highlight(1, code)
                .decorate((line, start, text, out) -> {
                  if (line == 1) {
                    out.lineBackground(0xFFE0E7FF)
                        .background(6, 7, 0xFFFDE68A)
                        .background(12, 13, 0xFFFDE68A);
                  }
                  if (line == 2) {
                    out.underline(6, 7, 0xFFDC2626);
                  }
                })));
    app.textRendering(TextRendering.LINUX);
    Script.run(
        "size 240 80 scale " + scale + "\nframe\nfocus #area\nkey ArrowDown\n"
            + "key ArrowDown shift\nkey End shift\nwheel #area 0 9\nframe\n",
        app,
        Path.of("target/gpu-vs-cpu-scripts"));
    return scene(name, app.scene(), 240, 80, scale);
  }

  /** One line of text on white, at {@code x} in a window of 200 by 40. */
  private static El label(String text, int colour, float x) {
    return el().wFull()
        .hFull()
        .fill(0xFFFFFFFF)
        .child(El.text(text).color(colour).absolute(x, 10));
  }

  /** A text in Inter at 14 and JetBrains Mono at 12 pixels, wrapped, dark on white. */
  private static El paragraph(String text) {
    return col()
        .wFull()
        .hFull()
        .padding(6)
        .gap(4)
        .fill(0xFFFFFFFF)
        .children(
            El.text(text).fontSize(14).color(0xFF1A1A1A).wrapText(),
            El.text(text).font(Font.mono()).fontSize(12).color(0xFF1A1A1A).wrapText());
  }

  /**
   * Two hundred different glyphs, in an atlas of pages of 64 texels with room for one page only:
   * the frame needs several, and the atlas grows for it.
   */
  private static Case textPages(float scale) {
    Atlas atlas = new Atlas(64, 64 * 64, 4096);
    Case c = text(
        "text-pages", paragraph(UPPER + " " + LOWER), 300, 160, scale, TextRendering.LINUX, atlas);
    assertTrue(atlas.pages().size() > 2, atlas.pages().size() + " pages");
    return c;
  }

  /**
   * Text cut by rounded clips: by their curved corners and straight edges, nested, at fractional
   * positions, dark on light and light on dark, and a clip whose corner cuts through a glyph.
   */
  private static El clippedText() {
    El inner = el().absolute(118.4f, 46.3f)
        .size(100, 50)
        .radius(16)
        .clip()
        .fill(0xFFF8FAFC)
        .child(El.text("Größe & Söhne").fontSize(14).color(0xFF111827).absolute(-10, -5))
        .child(
            El.text("Kunde Müller 1.234,56 €").fontSize(13).color(0xFF1F2937).absolute(-20.3f, 17))
        .child(El.text("Köln: AVATAR")
            .font(Font.mono())
            .fontSize(12)
            .color(0xFF6B6B6B)
            .absolute(44, 36));
    El outer = el().absolute(10.5f, 8.25f)
        .size(200, 100)
        .radius(24)
        .clip()
        .fill(0xFF1E1F22)
        .child(El.text("Zeile 1: Größe & Söhne GmbH, Köln")
            .fontSize(14)
            .color(0xFFE4E4E7)
            .absolute(-6, -4))
        .child(El.text("SELECT id FROM kunden; -- ÄÖÜ")
            .font(Font.mono())
            .fontSize(13)
            .color(0xFFDCDCDC)
            .absolute(4.6f, 26))
        .child(El.text("am runden Rand entlang geschnitten")
            .fontSize(12)
            .color(0xFF9CA3AF)
            .absolute(-30, 86))
        .child(inner);
    return el().wFull().hFull().fill(0xFF101014).child(outer);
  }

  /**
   * Text under translucent boxes, translucent boxes under text, and text over text: blending of
   * glyphs with rectangles in both orders, in one draw call.
   */
  private static El overlappingText() {
    return el().wFull()
        .hFull()
        .fill(0xFFF5F5F4)
        .children(
            // Text, then a translucent box over part of it.
            El.text("Kunde Müller, Köln").fontSize(14).color(0xFF1A1A1A).absolute(8, 8),
            el().absolute(60.5f, 4.25f).size(70, 26).radius(6).fill(0x803B82F6),
            // A translucent dark box, then light text over it.
            el().absolute(8, 40).size(150, 34).radius(8).fill(0xB0111827),
            El.text("1.234,56 € AVATAR").fontSize(13).color(0xFFF9FAFB).absolute(14.3f, 48),
            // Translucent text over translucent text.
            El.text("Größe").fontSize(14).color(0xC0DC2626).absolute(8, 84),
            El.text("Söhne").fontSize(14).color(0xA02563EB).absolute(14.5f, 88),
            // Light text on a dark panel, under a translucent white bar.
            el().absolute(160, 8).size(72, 104).fill(0xFF1E1F22),
            El.text("SELECT id")
                .font(Font.mono())
                .fontSize(12)
                .color(0xFFE4E4E7)
                .absolute(166, 26),
            el().absolute(150, 30).size(90, 12).fill(0x60FFFFFF),
            // Grey text under its translucent white background box, light text fading out.
            El.text("Zeile 2").fontSize(12).color(0xFF999999).absolute(170, 60).opacity(0.5f),
            el().absolute(166, 58).size(60, 20).radius(4).fill(0x40FFFFFF));
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
    El inner = el().absolute(30.5f, 12.25f)
        .size(70, 40)
        .radius(12)
        .clip()
        .fill(0xFF1F2937)
        .border(1.5f, 0xFF9CA3AF)
        .child(el().absolute(-10, -6).size(40, 30).radius(6).fill(0xFF3B82F6))
        .child(el().absolute(52, 20).size(40, 40).radius(20).fill(0xC0F43F5E))
        .child(el().absolute(10.3f, 30.6f).size(50, 8).border(2, 0xFFFDE047));
    El outer = el().absolute(8.5f, 8.5f)
        .size(110, 80)
        .radius(20)
        .clip()
        .fill(0xFF0F172A)
        .child(el().absolute(-15, 50).size(60, 60).radius(8).fill(0xFF22C55E))
        .child(el().absolute(80, -10).size(60, 30).fill(0xFFF59E0B))
        .child(inner)
        .child(el().absolute(5, 60)
            .size(100, 30)
            .opacity(0.5f)
            .clip()
            .radius(6)
            .child(el().absolute(-4, 4).size(60, 30).radius(4).fill(0xFFFFFFFF))
            .child(el().absolute(40, -8).size(80, 20).radius(10).fill(0xFFA855F7)));
    El thin = el().absolute(124.25f, 10)
        .size(28, 80)
        .radius(14)
        .clip()
        .fill(0xFF334155)
        .child(el().absolute(-6, 20.5f).size(40, 0.7f).fill(0xFFFFFFFF))
        .child(el().absolute(10, -5).size(8, 100).radius(4).fill(0x8038BDF8));
    return col().wFull().hFull().fill(BACKGROUND).child(outer).child(thin);
  }

  /**
   * A rounded scroll area with both bars, scrolled to fractional offsets so that the thumbs lie
   * between pixels, translucent over boxes that pass under them and cut by the rounded clip at the
   * corners, and the vertical thumb under the pointer in its other colour.
   */
  private static Case bars(String name, float scale) {
    Scrollbars look = new Scrollbars(7, 2.5f, 3.5f, 20, 0xB0A1A1AA, 0xE0D4D4D8, 0xFFFFFFFF);
    App<Object> app = new App<>(new Object(), s -> {
      El area = col()
          .key("area")
          .size(140, 80)
          .scrollBoth()
          .radius(14)
          .padding(4)
          .gap(3)
          .fill(0xFF1F2937)
          .scrollbars(look);
      for (int i = 0; i < 12; i++) {
        area.child(el().size(250, 13).radius(4).fill(i % 2 == 0 ? 0xFF3B82F6 : 0xFFF59E0B));
      }
      return col().wFull().hFull().padding(10).fill(BACKGROUND).child(area);
    });
    app.resize(160, 100, scale);
    app.frame();
    app.wheel(80, 50, 37.3f, 23.6f);
    app.frame();
    Scrollbars.Bar bar = app.tree().children().getFirst().verticalBar();
    app.pointerMove(bar.thumbX() + 3, bar.thumbY() + 5);
    app.frame();
    return scene(name, app.scene(), 160, 100, scale);
  }

  /**
   * Shadows, on light at the left and on dark at the right: a card lifted by a tight and a wide
   * shadow, text over a shadow, translucent boxes over a shadow and with one of their own, a panel
   * whose rounded clip cuts the shadows of its children, a card at half opacity with a third,
   * sharp shadow, two cards whose shadows overlap, a wide blur, a sharp one, a coloured glow and a
   * pill.
   */
  private static El shadows() {
    Shadow tight = new Shadow(0, 1, 3, 0, 0x33000000);
    Shadow wide = new Shadow(0, 6, 16, 0, 0x29000000);
    El light = el().absolute(0, 0)
        .size(120, 160)
        .fill(0xFFF5F5F4)
        .children(
            el().absolute(10.5f, 8.25f)
                .size(46, 30)
                .radius(6)
                .fill(0xFFFFFFFF)
                .shadow(tight, wide)
                .child(El.text("Karte").fontSize(12).color(0xFF1A1A1A).absolute(6, 8)),
            El.text("unter Text").fontSize(13).color(0xFF1F2937).absolute(8, 38),
            el().absolute(66, 8)
                .size(44, 30)
                .radius(15)
                .fill(0x803B82F6)
                .shadow(new Shadow(2, 3, 8, 1, 0x40000000)),
            el().absolute(76, 30).size(36, 18).radius(4).fill(0x60EF4444),
            el().absolute(8, 62)
                .size(104, 40)
                .radius(14)
                .clip()
                .fill(0xFFE7E5E4)
                .children(
                    el().absolute(-6, 12.3f)
                        .size(40, 20)
                        .radius(5)
                        .fill(0xFFFFFFFF)
                        .shadow(new Shadow(0, 4, 12, 2, 0x4D000000)),
                    el().absolute(70, 18)
                        .size(40, 30)
                        .radius(8)
                        .fill(0xFFFAFAF9)
                        .shadow(tight, wide)),
            el().absolute(8, 112)
                .size(40, 36)
                .radius(8)
                .fill(0xFFFFFFFF)
                .opacity(0.5f)
                .shadow(tight, wide, new Shadow(2.3f, 2.7f, 0, 0, 0x59000000)),
            el().absolute(56, 110).size(34, 26).radius(6).fill(0xFFFFFFFF).shadow(tight, wide),
            el().absolute(74.5f, 124.5f)
                .size(34, 26)
                .radius(6)
                .fill(0xFFFFFFFF)
                .shadow(tight, wide));
    El dark = el().absolute(120, 0)
        .size(120, 160)
        .fill(0xFF1B1C1F)
        .children(
            el().absolute(12, 10)
                .size(48, 34)
                .radius(8)
                .fill(0xFF2D2E33)
                .shadow(new Shadow(0, 2, 4, 0, 0x80000000), new Shadow(0, 8, 24, 0, 0x66000000)),
            El.text("SELECT 1").font(Font.mono()).fontSize(12).color(0xFFE4E5E9).absolute(14, 44),
            el().absolute(72, 12)
                .size(36, 30)
                .fill(0xFF2D2E33)
                .shadow(new Shadow(3.3f, 2.6f, 0, 0, 0xFF000000)),
            el().absolute(14, 70)
                .size(40, 30)
                .radius(10)
                .fill(0xFF26262E)
                .shadow(new Shadow(0, 0, 18, 2, 0xB33B82F6)),
            el().absolute(66, 64)
                .size(44, 44)
                .radius(6)
                .fill(0xFF2D2E33)
                .shadow(new Shadow(0, 10, 60, 0, 0x99000000)),
            el().absolute(10, 122)
                .size(100, 22)
                .radius(11)
                .fill(0xFF3A3B42)
                .shadow(new Shadow(0, 3, 6, -1, 0x99000000), new Shadow(-4, 0, 10, 0, 0x4022C55E)));
    return el().wFull().hFull().children(light, dark);
  }

  /**
   * Borders per side and radii per corner at fractional places and sizes: one side alone, two
   * unequal ones meeting at a rounded corner, wider than the radius, a border as wide as the box,
   * translucent over each other.
   */
  private static Scene sides() {
    Scene scene = new Scene();
    scene.rect(0, 0, 160, 100, 0, 0, BACKGROUND, 0);
    float[][] borders = {
      {0, 0, 1, 0}, {1.5f, 0, 0, 3}, {2, 4, 2, 4}, {0.5f, 1, 2.5f, 6}, {5, 0, 5, 0}, {12, 12, 12, 0}
    };
    float[][] radii = {
      {0, 0, 0, 0}, {6, 6, 6, 6}, {10, 0, 4, 2.5f}, {3.3f, 14, 0, 8}, {12, 12, 0, 0}, {20, 1, 20, 1}
    };
    for (int i = 0; i < borders.length; i++) {
      float x = 4 + i * 26.3f;
      scene.rect(x, 4.25f, 23, 26, radii[i], borders[i], 0xFF2563EB, 0xFFF8FAFC);
      scene.rect(
          x + 0.4f,
          34.5f,
          22,
          26,
          radii[(i + 2) % radii.length],
          borders[i],
          0xFF1E293B,
          0xFFFACC15);
      scene.rect(
          x,
          64.75f,
          23,
          30,
          radii[(i + 4) % radii.length],
          borders[(i + 3) % 6],
          0x80F97316,
          0xC0FFFFFF);
    }
    return scene;
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
