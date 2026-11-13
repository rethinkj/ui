package com.rethinkj.ui.render;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Examples;
import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Scrollbars;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Draws scenes on the GPU and on the CPU and compares them pixel by pixel: rectangles and text, the
 * views behind the stored images and scenes that stress one formula each. They cannot be equal bit
 * for bit, so each channel may differ by the {@link #tolerance} and what the GPU's rounding of
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

  /**
   * A scene to draw, with the size of the image in physical pixels and the scale.
   *
   * @param stored the stored image that the scene is the view behind, which the CPU's image of the
   *     scene then equals; null for none
   */
  record Case(String name, Scene scene, int width, int height, float scale, Path stored) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> cases() throws IOException {
    List<Case> cases = new ArrayList<>();
    // The views behind the stored images, built as their tests and scripts build them; all but
    // the badge have text.
    cases.addAll(script("switch.txt", () -> new App<>(new Lights(), Examples::lights)));
    cases.addAll(script("focus.txt", () -> new App<>(new Lights(), Examples::lights)));
    cases.addAll(script("hover.txt", () -> new App<>(new Lights(), Examples::lights)));
    cases.addAll(script("list.txt", () -> new App<>(new Object(), s -> Examples.list())));
    // Scrollbars: thumbs over the rows at both ends, in their colours at rest and dragged.
    cases.addAll(script("scrollbars.txt", () -> new App<>(new Object(), s -> Examples.bars())));
    // Text fields: the selection and the caret are rectangles, the line under a composition too,
    // and the glyphs are moved by the field's scroll and clipped to it.
    cases.addAll(script("field.txt", () -> new App<>(new Examples.Form(), Examples::form)));
    cases.addAll(script("field-rows.txt",
        () -> new App<>(new Examples.Names(), Examples::fieldRows)));
    // The kit's data grid, which Examples.grid builds as the kit does: first the snaps of kit's
    // script behind its stored images grid-dark-*, then at the other scales. Header, lines and
    // cells on whole pixels or not, clipped cells, rows a million down, the ring and the accent.
    List<Case> grid = script(Files.readString(KIT.resolve("scripts/grid.txt")), "grid-dark-",
        KIT.resolve("golden"), GpuRendererTest::grid);
    if (grid.size() != 5) {
      throw new AssertionError("Expected the 5 snaps of kit's grid.txt, found " + grid);
    }
    cases.addAll(grid);
    for (float scale : new float[] {2, 1.5f}) {
      String at = at(scale);
      cases.add(grid("grid-focused" + at, scale, "key Tab\nkey ArrowDown\nkey ArrowDown\n"));
      cases.add(grid("grid-far-sideways" + at, scale,
          "scroll #rows row 765432\nframe\nwheel #rows 300 0\n"));
      cases.add(grid("grid-resizing" + at, scale, "down #resize-1\nmove 252,19\n"));
    }
    // Overlays, drawn after the tree with clips of their own: the stored images of overlay.txt,
    // then a menu across the rounded clip of a scrolled list with its submenu, and a dialog over a
    // scrim, at every scale.
    cases.addAll(script("overlay.txt", () -> new App<>(new Examples.Menus(), Examples::overlays)));
    for (float scale : new float[] {1, 1.5f, 2}) {
      String at = at(scale);
      cases.add(overlay("overlay-nested" + at, scale, "press #export\n"));
      cases.add(overlay("overlay-scrim" + at, scale, "press #delete\n"));
    }
    for (int frames = 2; frames <= 5; frames++) {
      cases.add(switchMoving(frames));
    }
    cases.addAll(script(BADGE, "badge-", GpuRendererTest::badge));
    for (int scale = 1; scale <= 2; scale++) {
      cases.add(text("text-sizes-" + scale + "x", Examples.textSizes(), 720, 176, scale,
          TextRendering.LINUX, new Atlas(), true));
    }
    cases.add(text("text-grey-on-light", Examples.greyOnLight(), 480, 104, 2,
        TextRendering.LINUX, new Atlas(), true));
    cases.add(text("text-opacity", Examples.textOpacity(), 336, 76, 2, TextRendering.LINUX,
        new Atlas(), true));
    for (float scale : new float[] {1, 2, 1.5f}) { // lines shortened with an ellipsis
      boolean stored = scale != 1.5f;
      cases.add(text(stored ? "text-ellipsis-" + (int) scale + "x" : "text-ellipsis" + at(scale),
          Examples.ellipsisText(), 360, 236, scale, TextRendering.LINUX, new Atlas(), stored));
    }
    cases.add(textScrolled());
    // Scenes that stress one formula each.
    for (float scale : new float[] {1, 2, 1.5f}) {
      String at = at(scale);
      cases.add(scene("edges" + at, edges(), 160, 100, scale));
      cases.add(scene("borders" + at, borders(), 160, 100, scale));
      cases.add(scene("radii" + at, radii(), 160, 100, scale));
      cases.add(scene("overlaps" + at, overlaps(), 160, 100, scale));
      cases.add(view("clips" + at, clips(), 160, 100, scale));
      cases.add(bars("bars-both" + at, scale, false));
      cases.add(bars("bars-both-hovered" + at, scale, true));
      cases.add(scene("transparent" + at, transparent(), 160, 100, scale));
    }
    // Text: 11 to 14 pixels in both fonts, dark on light, light on dark and grey on light, with
    // both renderings, under opacity, cut by rounded clips, overlapping translucent boxes and
    // other text, and spread over the pages of a small atlas; and text fields, selected and
    // composing. At the scales of the stored images in the views above, and at the others here.
    for (float scale : new float[] {1, 2, 1.5f}) {
      String at = at(scale);
      if (scale == 1.5f) {
        cases.add(text("text-sizes-linux" + at, Examples.textSizes(), 720, 176, scale));
      }
      cases.add(text("text-sizes-windows" + at, Examples.textSizes(), 720, 176, scale,
          TextRendering.WINDOWS, new Atlas(), false));
      if (scale != 2) {
        cases.add(text("text-grey-on-light" + at, Examples.greyOnLight(), 480, 104, scale));
        cases.add(text("text-opacity" + at, Examples.textOpacity(), 336, 76, scale));
      }
      cases.add(text("text-clipped" + at, clippedText(), 240, 120, scale));
      cases.add(text("text-overlaps" + at, overlappingText(), 240, 120, scale));
      cases.add(textPages(scale));
      if (scale != 2) {
        cases.add(field("field-selected" + at, scale,
            "press #name\ntype \"SELECT name FROM customers\"\nkey ArrowLeft control shift\n"));
        cases.add(field("field-composing" + at, scale,
            "press #name\ntype \"SELECT name\"\nkey ArrowLeft shift\ncompose \"ni\"\n"));
      }
    }
    // Fallback fonts: glyphs of four fonts on one line and in a field selected across them,
    // Hebrew right to left, Thai with its marks, Inter's "not defined" glyph.
    for (float scale : new float[] {1, 2, 1.5f}) {
      boolean stored = scale != 1.5f;
      cases.add(text(stored ? "fallback-" + (int) scale + "x" : "fallback" + at(scale),
          Examples.fallbackText(), 360, 128, scale, TextRendering.LINUX, new Atlas(), stored));
      cases.add(fallbackField(scale == 2 ? "fallback-field" : "fallback-field" + at(scale), scale));
    }
    return cases.stream();
  }

  /** The field of the stored image fallback-field, at a scale. */
  private static Case fallbackField(String name, float scale) throws IOException {
    App<Examples.Form> app = new App<>(new Examples.Form(), Examples::fallbackField);
    app.textRendering(TextRendering.LINUX);
    Script.run("size 240 80 scale " + scale + "\nframe\n" + Examples.FALLBACK_FIELD + "frame\n",
        app, Path.of("target/gpu-vs-cpu-scripts"));
    return scene(name, app.scene(), 240, 80, scale, scale == 2);
  }

  private static String at(float scale) {
    return "@" + (scale == (int) scale ? String.valueOf((int) scale) : "1.5");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theGpuDrawsWhatTheCpuDraws(Case c) throws IOException {
    if (c.stored() != null) {
      Pixels stored = Png.decode(Files.readAllBytes(c.stored()));
      assertArrayEquals(stored.rgba(),
          CpuRenderer.render(c.scene(), c.width(), c.height(), c.scale()).rgba(),
          "the case is not the view behind the stored image " + c.name());
    }
    Comparison comparison = compare(gpu, c);
    System.out.println("GPU vs CPU " + comparison);
    if (comparison.beyond() > 0) {
      fail(comparison + ". See " + FAILED.toAbsolutePath());
    }
  }

  /**
   * Changes to the glyph shader that draw text only a little wrong, each of which the comparison
   * has to notice: the texel mapping, the reading of texels, the coverage correction and the
   * clip.
   */
  static Stream<Arguments> textBreaks() {
    String texel = "let texel = vec2i(floor(in.position.xy) - glyph_origin(instance));";
    String read = "let a = textureLoad(atlas, corner + texel, instance.more.x, 0).r;";
    String dark = "let dark = 1.0 - pow(1.0 - a, bitcast<f32>(instance.more.z));";
    String mix = "var glyph_cover = dark + (light - dark) * bitcast<f32>(instance.more.y);";
    return Stream.of(
        Arguments.of("every texel one pixel to the right", texel,
            "let texel = vec2i(floor(in.position.xy) - glyph_origin(instance)) - vec2i(1, 0);"),
        Arguments.of("texels filtered half a texel off", read,
            "let a = (textureLoad(atlas, corner + texel, instance.more.x, 0).r"
                + " + textureLoad(atlas, corner + texel + vec2i(1, 0), instance.more.x, 0).r)"
                + " / 2.0;"),
        Arguments.of("dark exponent 1.6 instead of 1.8", dark,
            "let dark = 1.0 - pow(1.0 - a, bitcast<f32>(instance.more.z) - 0.2);"),
        Arguments.of("every glyph corrected as dark text", mix, "var glyph_cover = dark;"),
        Arguments.of("no correction", mix, "var glyph_cover = a;"),
        Arguments.of("the clip left out", "glyph_cover *= clip_coverage(instance.clip, point);",
            "glyph_cover *= 1.0;"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("textBreaks")
  void aWrongGlyphShaderIsNoticed(String what, String line, String broken) {
    String wgsl = GpuRenderer.WGSL.replace(line, broken);
    assertTrue(!wgsl.equals(GpuRenderer.WGSL), "the shader has no line " + line);
    try (GpuRenderer wrong = GpuRenderer.create(wgsl)) {
      for (Case c : List.of(text("text-sizes-broken", Examples.textSizes(), 720, 176, 1.5f),
          text("text-clipped-broken", clippedText(), 240, 120, 2))) {
        Comparison comparison = compare(wrong, c, false);
        System.out.println("GPU with " + what + " vs CPU " + comparison);
        if (!what.equals("the clip left out") || c.name().startsWith("text-clipped")) {
          assertTrue(comparison.beyond() > 50, what + ": " + comparison);
        }
      }
    }
  }

  @Test
  void aPageIsWrittenWholeWhenItAppearsAndThenOnlyWhereItChanged() {
    Atlas atlas = new Atlas();
    long writes = gpu.atlasWrites();
    long texels = gpu.atlasTexels();
    Case first = text("upload-first", label("Kunde Müller", 0xFF1A1A1A, 8), 200, 40, 1,
        TextRendering.LINUX, atlas, false);
    assertEquals(0, compare(gpu, first).beyond());
    assertEquals(1, gpu.atlasWrites() - writes, "the new page");
    assertEquals((long) Atlas.PAGE_SIZE * Atlas.PAGE_SIZE, gpu.atlasTexels() - texels, "whole");

    // The same glyphs in another colour and place, twice: nothing to write.
    writes = gpu.atlasWrites();
    Case recoloured = text("upload-recoloured", label("Kunde Müller", 0xFF2563EB, 13), 200, 40,
        1, TextRendering.LINUX, atlas, false);
    assertEquals(0, compare(gpu, recoloured).beyond());
    gpu.render(recoloured.scene(), 200, 40, 1);
    assertEquals(writes, gpu.atlasWrites(), "no change, no upload");

    // New glyphs: only the region they went to.
    texels = gpu.atlasTexels();
    Case more = text("upload-more", label("Kunde Müller, Köln: 1.234,56 €", 0xFF1A1A1A, 8),
        200, 40, 1, TextRendering.LINUX, atlas, false);
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
    Case upper = text("pages-upper", paragraph(UPPER), 300, 120, 2, TextRendering.LINUX, atlas,
        false);
    int pages = atlas.pages().size();
    assertTrue(pages >= 3, pages + " pages");
    assertEquals(0, compare(gpu, upper).beyond());

    Case lower = text("pages-lower", paragraph(LOWER), 300, 120, 2, TextRendering.LINUX, atlas,
        false);
    List<Atlas.Page> emptied = atlas.pages().subList(0, pages).stream()
        .filter(page -> page.changed().equals(new Atlas.Region(0, 0, 64, 64))).toList();
    assertTrue(!emptied.isEmpty(), "a page of the first frame was emptied for the second");
    long texels = gpu.atlasTexels();
    assertEquals(0, compare(gpu, lower).beyond());
    assertTrue(gpu.atlasTexels() - texels >= emptied.size() * 64L * 64);

    Case again = text("pages-upper-again", paragraph(UPPER), 300, 120, 2, TextRendering.LINUX,
        atlas, false);
    assertEquals(0, compare(gpu, again).beyond());
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
   * @param maxSlack the most steps that the rounding of alpha added to the tolerance at a pixel
   */
  record Comparison(Case c, int[] max, int differing, int beyond, int layers, int maxAtTwo,
      int maxSlack, String first) {
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
    return linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
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
    float[] frame = CpuRenderer.renderLinear(c.scene, c.width, c.height, c.scale,
        (image, at, alpha) -> {
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
          expected[channel] = channel == 3 ? (int) Math.round(value * 255)
              : CpuRenderer.encode(value);
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
            first = String.format("at %d,%d, %d layers: CPU %02X%02X%02X%02X, GPU %s"
                + " (premultiplied), %d allowed", x, y, n, expected[0], expected[1], expected[2],
                expected[3], hex(stored, gpuAt), tolerance);
          }
        }
        // Black where equal, grey to light grey within the tolerance, red beyond it.
        int shade = worst == 0 ? 0 : out ? -1 : 0x50 + 0x50 * Math.min(worst, tolerance)
            / tolerance;
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
        x1 = Math.min(x1, (int) Math.ceil(
            (s.clipGet(clip, Scene.X) + s.clipGet(clip, Scene.WIDTH)) * c.scale) + 1);
        y1 = Math.min(y1, (int) Math.ceil(
            (s.clipGet(clip, Scene.Y) + s.clipGet(clip, Scene.HEIGHT)) * c.scale) + 1);
        x0 = Math.max(x0, 0);
        y0 = Math.max(y0, 0);
        x1 = Math.min(x1, c.width);
        y1 = Math.min(y1, c.height);
      } else {
        double left = Math.max(s.get(i, Scene.X), s.clipGet(clip, Scene.X));
        double top = Math.max(s.get(i, Scene.Y), s.clipGet(clip, Scene.Y));
        double right = Math.min(s.get(i, Scene.X) + s.get(i, Scene.WIDTH),
            s.clipGet(clip, Scene.X) + s.clipGet(clip, Scene.WIDTH));
        double bottom = Math.min(s.get(i, Scene.Y) + s.get(i, Scene.HEIGHT),
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

  // ---- the views behind the stored images ---------------------------------------------------

  private static final Path STORED = Path.of("src/test/resources/golden");
  private static final Path SCRIPTS = Path.of("src/test/resources/scripts");

  private static Case view(String name, El root, float width, float height, float scale) {
    Flex.layout(root, width, height);
    return scene(name, Scene.of(root), width, height, scale);
  }

  /** A case of a scene laid out for this size in logical pixels. */
  private static Case scene(String name, Scene scene, float width, float height, float scale) {
    return scene(name, scene, width, height, scale, false);
  }

  private static Case scene(String name, Scene scene, float width, float height, float scale,
      boolean stored) {
    return scene(name, scene, width, height, scale, stored ? STORED.resolve(name + ".png") : null);
  }

  private static Case scene(String name, Scene scene, float width, float height, float scale,
      Path stored) {
    return new Case(name, scene, Math.round(width * scale), Math.round(height * scale), scale,
        stored);
  }

  /** The script of {@code PresenceTest} that makes the stored images badge-*. */
  private static final String BADGE = """
      size 200 120 scale 2
      frame
      snap on
      press #switch
      frame
      advance 0.06
      frame
      snap leaving-060ms
      advance 0.06
      frame
      snap leaving-120ms
      press #switch
      frame
      advance 0.06
      frame
      snap back-060ms
      advance 1
      frame
      snap back
      press #switch
      frame
      advance 1
      frame
      snap gone
      """;

  /** The badge example after its first frame, as {@code PresenceTest} starts it. */
  private static App<?> badge() {
    Lights lights = new Lights();
    lights.on = true;
    App<Lights> app = new App<>(lights, Examples::badged);
    app.resize(200, 120, 1);
    app.frame();
    return app;
  }

  private static List<Case> script(String file, Supplier<App<?>> apps) throws IOException {
    return script(Files.readString(SCRIPTS.resolve(file)), "", apps);
  }

  /**
   * The snaps of a script that are stored images, named {@code prefix} and the snap's name: for
   * each, a new app runs the script up to that snap, with the text rendering of the stored images.
   */
  private static List<Case> script(String script, String prefix, Supplier<App<?>> apps)
      throws IOException {
    return script(script, prefix, STORED, apps);
  }

  /** As {@link #script(String, String, Supplier)}, with the stored images in {@code stored}. */
  private static List<Case> script(String script, String prefix, Path stored,
      Supplier<App<?>> apps) throws IOException {
    List<String> lines = script.lines().toList();
    List<Case> cases = new ArrayList<>();
    float[] size = new float[3];
    for (int i = 0; i < lines.size(); i++) {
      String[] words = lines.get(i).strip().split("\\s+");
      String name = words.length > 1 ? prefix + words[1] : "";
      if (words[0].equals("size")) {
        size[0] = Float.parseFloat(words[1]);
        size[1] = Float.parseFloat(words[2]);
        size[2] = words.length > 4 && words[3].equals("scale") ? Float.parseFloat(words[4]) : 1;
      } else if (words[0].equals("snap") && !SNAPPED.contains(name)
          && Files.exists(stored.resolve(name + ".png"))) {
        App<?> app = apps.get();
        app.textRendering(TextRendering.LINUX);
        Script.run(String.join("\n", lines.subList(0, i + 1)), app,
            Path.of("target/gpu-vs-cpu-scripts"));
        cases.add(scene(name, app.scene(), size[0], size[1], size[2],
            stored.resolve(name + ".png")));
        SNAPPED.add(name);
      }
    }
    return cases;
  }

  /** Snaps that two scripts have, such as switch-on: compared once. */
  private static final Set<String> SNAPPED = new HashSet<>();

  /**
   * As the stored images switch-moving-*, made by {@code MotionTest}: pressed, then 40 ms later
   * for each frame, at scale 2.
   */
  private static Case switchMoving(int frames) {
    App<Lights> app = new App<>(new Lights(), Examples::lights);
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 120, 2);
    app.frame();
    press(app, 58, 60);
    app.frame();
    for (int i = 0; i < frames; i++) {
      app.advance(0.04);
      app.frame();
    }
    return scene(String.format("switch-moving-%03dms", 40 * frames), app.scene(), 200, 120, 2,
        true);
  }

  /** As the stored image text-scrolled: 13.25 pixels down, lines cut by the rounded clip. */
  private static Case textScrolled() {
    App<Object> app = new App<>(new Object(), s -> Examples.scrolledText());
    app.textRendering(TextRendering.LINUX);
    app.resize(200, 90, 2);
    app.frame();
    app.wheel(100, 45, 0, 13.25f);
    app.frame();
    return scene("text-scrolled", app.scene(), 200, 90, 2, true);
  }

  /** Kit's test resources, for its data grid's script and stored images. */
  private static final Path KIT = Path.of("../kit/src/test/resources");

  /** The data grid as kit's {@code Orders.app} starts it, in the dark theme. */
  private static App<?> grid() {
    App<Examples.Grid> app = new App<>(new Examples.Grid(), Examples::grid);
    return app;
  }

  /** The grid after these steps of a script and a frame, in a window of 560 by 240. */
  private static Case grid(String name, float scale, String steps) throws IOException {
    App<?> app = grid();
    app.textRendering(TextRendering.LINUX);
    Script.run("size 560 240 scale " + scale + "\nframe\n" + steps + "frame\n", app,
        Path.of("target/gpu-vs-cpu-scripts"));
    return scene(name, app.scene(), 560, 240, scale);
  }

  /**
   * The menus of overlay.txt with the list scrolled by 13.25 pixels and the context menu open on
   * row 3, after these steps of a script and a frame.
   */
  private static Case overlay(String name, float scale, String steps) throws IOException {
    App<Examples.Menus> app = new App<>(new Examples.Menus(), Examples::overlays);
    app.textRendering(TextRendering.LINUX);
    Script.run("size 240 200 scale " + scale + "\nframe\nwheel #list 0 13.25\nframe\n"
        + "press #row-3 right\nframe\n" + steps + "frame\n", app,
        Path.of("target/gpu-vs-cpu-scripts"));
    assertTrue(!app.tree().shownOverlays().isEmpty(), "an overlay shows");
    return scene(name, app.scene(), 240, 200, scale);
  }

  /** The text field of the stored images field-*, after these steps of a script and a frame. */
  private static Case field(String name, float scale, String steps) throws IOException {
    App<Examples.Form> app = new App<>(new Examples.Form(), Examples::form);
    app.textRendering(TextRendering.LINUX);
    Script.run("size 240 80 scale " + scale + "\nframe\n" + steps + "frame\n", app,
        Path.of("target/gpu-vs-cpu-scripts"));
    assertTrue(app.focused().text().caret() != null, "the caret shows");
    return scene(name, app.scene(), 240, 80, scale);
  }

  /** A view with text, laid out for the scale, its glyphs in an atlas of its own. */
  private static Case text(String name, El root, float width, float height, float scale) {
    return text(name, root, width, height, scale, TextRendering.LINUX, new Atlas(), false);
  }

  /** A view with text, laid out for the scale, its glyphs in {@code atlas}. */
  private static Case text(String name, El root, float width, float height, float scale,
      TextRendering rendering, Atlas atlas, boolean stored) {
    Examples.laidOut(root, width, height, scale);
    return scene(name, Scene.of(root, null, 0, atlas, rendering), width, height, scale, stored);
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
  }

  // ---- text that stresses the glyph shader --------------------------------------------------

  private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ ÄÖÜ 0123456789 €$§%&@#";
  private static final String LOWER = "abcdefghijklmnopqrstuvwxyz äöüß ()[]{}<>/|+-*=?!;:,.";

  /** One line of text on white, at {@code x} in a window of 200 by 40. */
  private static El label(String text, int colour, float x) {
    return el().wFull().hFull().fill(0xFFFFFFFF)
        .child(El.text(text).color(colour).absolute(x, 10));
  }

  /** A text in Inter at 14 and JetBrains Mono at 12 pixels, wrapped, dark on white. */
  private static El paragraph(String text) {
    return col().wFull().hFull().padding(6).gap(4).fill(0xFFFFFFFF).children(
        El.text(text).fontSize(14).color(0xFF1A1A1A).wrapText(),
        El.text(text).font(Font.mono()).fontSize(12).color(0xFF1A1A1A).wrapText());
  }

  /**
   * Two hundred different glyphs, in an atlas of pages of 64 texels with room for one page only:
   * the frame needs several, and the atlas grows for it.
   */
  private static Case textPages(float scale) {
    Atlas atlas = new Atlas(64, 64 * 64, 4096);
    Case c = text("text-pages" + at(scale), paragraph(UPPER + " " + LOWER), 300, 160, scale,
        TextRendering.LINUX, atlas, false);
    assertTrue(atlas.pages().size() > 2, atlas.pages().size() + " pages");
    return c;
  }

  /**
   * Text cut by rounded clips: by their curved corners and straight edges, nested, at fractional
   * positions, dark on light and light on dark, and a clip whose corner cuts through a glyph.
   */
  private static El clippedText() {
    El inner = el().absolute(118.4f, 46.3f).size(100, 50).radius(16).clip().fill(0xFFF8FAFC)
        .child(El.text("Größe & Söhne").fontSize(14).color(0xFF111827).absolute(-10, -5))
        .child(El.text("Kunde Müller 1.234,56 €").fontSize(13).color(0xFF1F2937)
            .absolute(-20.3f, 17))
        .child(El.text("Köln: AVATAR").font(Font.mono()).fontSize(12).color(0xFF6B6B6B)
            .absolute(44, 36));
    El outer = el().absolute(10.5f, 8.25f).size(200, 100).radius(24).clip().fill(0xFF1E1F22)
        .child(El.text("Zeile 1: Größe & Söhne GmbH, Köln").fontSize(14).color(0xFFE4E4E7)
            .absolute(-6, -4))
        .child(El.text("SELECT id FROM kunden; -- ÄÖÜ").font(Font.mono()).fontSize(13)
            .color(0xFFDCDCDC).absolute(4.6f, 26))
        .child(El.text("am runden Rand entlang geschnitten").fontSize(12).color(0xFF9CA3AF)
            .absolute(-30, 86))
        .child(inner);
    return el().wFull().hFull().fill(0xFF101014).child(outer);
  }

  /**
   * Text under translucent boxes, translucent boxes under text, and text over text: blending of
   * glyphs with rectangles in both orders, in one draw call.
   */
  private static El overlappingText() {
    return el().wFull().hFull().fill(0xFFF5F5F4).children(
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
        El.text("SELECT id").font(Font.mono()).fontSize(12).color(0xFFE4E4E7).absolute(166, 26),
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

  /**
   * A rounded scroll area with both bars, scrolled to fractional offsets so that the thumbs lie
   * between pixels, translucent over boxes that pass under them and cut by the rounded clip at the
   * corners; with {@code hovered}, the vertical thumb under the pointer in its other colour.
   */
  private static Case bars(String name, float scale, boolean hovered) {
    Scrollbars look = new Scrollbars(7, 2.5f, 3.5f, 20, 0xB0A1A1AA, 0xE0D4D4D8, 0xFFFFFFFF);
    App<Object> app = new App<>(new Object(), s -> {
      El area = col().key("area").size(140, 80).scrollBoth().radius(14).padding(4).gap(3)
          .fill(0xFF1F2937).scrollbars(look);
      for (int i = 0; i < 12; i++) {
        area.child(el().size(250, 13).radius(4).fill(i % 2 == 0 ? 0xFF3B82F6 : 0xFFF59E0B));
      }
      return col().wFull().hFull().padding(10).fill(BACKGROUND).child(area);
    });
    app.resize(160, 100, scale);
    app.frame();
    app.wheel(80, 50, 37.3f, 23.6f);
    app.frame();
    if (hovered) {
      Scrollbars.Bar bar = app.tree().children().getFirst().verticalBar();
      app.pointerMove(bar.thumbX() + 3, bar.thumbY() + 5);
      app.frame();
    }
    return scene(name, app.scene(), 160, 100, scale);
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
