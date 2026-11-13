package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Text drawn: glyph instances in the scene, the coverage correction, snapping to the pixel grid
 * and stored images. Every image pins {@link TextRendering#LINUX}. Skipped without FreeType and
 * HarfBuzz.
 */
class TextRenderTest {

  private static final TextRendering PINNED = TextRendering.LINUX;
  private static final String SANS_SAMPLE = Examples.SANS_SAMPLE;

  @BeforeEach
  void needsTheTextLibraries() {
    TextLibraries.assumeLoaded();
  }

  @Test
  void elevenToFourteenPixelsInBothFontsOnLightAndDark() {
    for (int scale = 1; scale <= 2; scale++) {
      Headless.Frame frame = Headless.render(Examples.textSizes(), 720, 176, scale, PINNED);
      Golden.assertMatches("text-sizes-" + scale + "x", frame.pixels(), frame.tree());
    }
  }

  /** See {@link Examples#greyOnLight}. */
  @Test
  void greyTextOnALightBackground() {
    Headless.Frame frame = Headless.render(Examples.greyOnLight(), 480, 104, 2, PINNED);
    Golden.assertMatches("text-grey-on-light", frame.pixels(), frame.tree());
  }

  @Test
  void textInAScrollAreaIsClippedByItsRoundedEdge() {
    App<Object> app = new App<>(new Object(), s -> Examples.scrolledText());
    app.textRendering(PINNED);
    app.resize(200, 90, 2);
    app.frame();
    app.wheel(100, 45, 0, 13.25f);
    app.frame();
    Headless.Frame frame = app.render();
    Golden.assertMatches("text-scrolled", frame.pixels(), frame.tree());
    Scene scene = app.scene();
    int glyphs = 0;
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.GLYPH) {
        glyphs++;
        assertEquals(1, scene.getInt(i, Scene.CLIP), "clipped by the list");
        assertTrue(scene.get(i, Scene.Y) < 80 && scene.get(i, Scene.Y) > 0);
      }
    }
    assertTrue(glyphs > 30 && glyphs < 8 * 27, glyphs + ": the rows out of sight are left out");
  }

  @Test
  void textUnderOpacity() {
    Headless.Frame frame = Headless.render(Examples.textOpacity(), 336, 76, 2, PINNED);
    Golden.assertMatches("text-opacity", frame.pixels(), frame.tree());
  }

  /** See {@link Examples#alignedText}. */
  @Test
  void linesAlignInTheirBoxes() {
    for (int scale = 1; scale <= 2; scale++) {
      Headless.Frame frame = Headless.render(Examples.alignedText(), 360, 172, scale, PINNED);
      Golden.assertMatches("text-align-" + scale + "x", frame.pixels(), frame.tree());
    }
  }

  /** See {@link Examples#ellipsisText}. */
  @Test
  void aLineTooWideForItsBoxEndsInAnEllipsis() {
    for (int scale = 1; scale <= 2; scale++) {
      El root = Examples.ellipsisText();
      Headless.Frame frame = Headless.render(root, 360, 236, scale, PINNED);
      Golden.assertMatches("text-ellipsis-" + scale + "x", frame.pixels(), frame.tree());
      List<El> texts = new ArrayList<>();
      collectTexts(root, texts);
      assertEquals(22, texts.size());
      for (El text : texts) {
        Text t = text.text();
        boolean fits = t.string().equals("Köln");
        assertEquals(!fits, t.shortened(), t.string() + " in " + text.width());
        Text.Line line = t.lines().getFirst();
        assertTrue(
            line.width() <= text.width() * scale + 0.01f || text.width() < 10,
            line.width() + " in " + text.width() * scale);
      }
      assertTrue(frame.tree().contains(El.quote(Examples.SANS_SAMPLE)), "the whole string");
    }
  }

  private static void collectTexts(El el, List<El> into) {
    if (el.text() != null) {
      into.add(el);
    }
    el.children().forEach(child -> collectTexts(child, into));
  }

  /** See {@link Examples#fallbackText}. */
  @Test
  void fallbackFontsDrawWhatTheFontLacks() {
    for (int scale = 1; scale <= 2; scale++) {
      Headless.Frame frame = Headless.render(Examples.fallbackText(), 360, 128, scale, PINNED);
      Golden.assertMatches("fallback-" + scale + "x", frame.pixels(), frame.tree());
    }
  }

  /**
   * A field in the fonts of {@link Examples#fallbackChain}: Inter, JetBrains Mono, Thai and
   * Hebrew, with a selection from Inter into JetBrains Mono and the caret after it.
   */
  @Test
  void aFieldWithFallbackFontsSelectsAcrossThem() throws IOException {
    App<Examples.Form> app = new App<>(new Examples.Form(), Examples::fallbackField);
    app.textRendering(PINNED);
    Script.run(
        "size 240 80 scale 2\nframe\n" + Examples.FALLBACK_FIELD + "frame\n",
        app,
        Path.of("target/fallback-field"));
    Headless.Frame frame = app.render();
    Golden.assertMatches("fallback-field", frame.pixels(), frame.tree());
  }

  // ---- the coverage correction against blending in gamma space ------------------------------

  /**
   * Blending in linear light makes dark text on a light background thinner and light text on a
   * dark one heavier than blending the sRGB values, which is what text is designed for and what
   * platforms do. The correction pulls the ink back towards gamma space. Ink is the sum over the
   * pixels of how far each moved from the background towards the text colour, in sRGB values: in
   * gamma space that is exactly the summed coverage. Eight lines of Inter and JetBrains Mono at 11
   * to 14 pixels, scale 1.
   *
   * <p>The correction is an exponent on the coverage, the same at every contrast. For black and
   * white, where linear and gamma-space blending differ most, it comes within 2 % (measured: dark
   * on light 0.983, light on dark 0.990, against 0.736 and 1.232 without it), so 5 % is allowed.
   * As the contrast falls, the two ways of blending draw closer while the correction stays, so
   * grey text ends up heavier than in gamma space: #999999 on white 1.149 (#6B6B6B 1.10, #AAAAAA
   * 1.16). That is the side the rule prefers; corrected by the text colour alone, as light text,
   * it would lose 29 % (0.709). So grey on light may be up to 20 % heavier, not lighter, and has
   * to be nearer than that wrong correction.
   */
  @Test
  void theCorrectedInkIsCloseToGammaSpaceBlending() {
    TextRendering linear = new TextRendering(TextRendering.Hinting.LIGHT, 1, 1);
    double darkOnLight = inkRatio(0xFF1A1A1A, 0xFFFFFFFF, PINNED, 0);
    assertEquals(1, darkOnLight, 0.05, "dark on light");
    assertEquals(0.74, inkRatio(0xFF1A1A1A, 0xFFFFFFFF, linear, 0), 0.05, "without correction");

    double lightOnDark = inkRatio(0xFFDCDCDC, 0xFF1E1F22, PINNED, 0);
    assertEquals(1, lightOnDark, 0.05, "light on dark");
    assertEquals(1.23, inkRatio(0xFFDCDCDC, 0xFF1E1F22, linear, 0), 0.05, "without correction");

    double greyOnLight = inkRatio(0xFF999999, 0xFFFFFFFF, PINNED, 0);
    double thinned = inkRatio(0xFF999999, 0xFFFFFFFF, PINNED, 0xFF000000); // told it is on black
    assertTrue(greyOnLight >= 1 && greyOnLight < 1.2, "grey on light: " + greyOnLight);
    assertTrue(Math.abs(thinned - 1) > greyOnLight - 1, "thinned: " + thinned);
    System.out.printf(
        Locale.ROOT,
        "Ink against gamma space: dark on light %.3f, light on dark %.3f, grey on light %.3f"
            + " (thinned %.3f)%n",
        darkOnLight,
        lightOnDark,
        greyOnLight,
        thinned);
  }

  /**
   * The ink of rendered text divided by that of the same glyphs blended in gamma space.
   *
   * @param told the background to state with {@code textBackground}, or 0
   */
  private static double inkRatio(int text, int background, TextRendering rendering, int told) {
    El root = col().w(320).h(150).padding(4).fill(background);
    for (Font font : new Font[] {Font.sans(), Font.mono()}) {
      for (int size = 11; size <= 14; size++) {
        root.child(El.text(SANS_SAMPLE).font(font).fontSize(size).color(text).textBackground(told));
      }
    }
    El.scaleText(root, 1);
    Flex.layout(root, 320, 150);
    Scene scene = Scene.of(root, null, 0, new Atlas(), rendering);
    Pixels pixels = CpuRenderer.render(scene, 320, 150, 1);

    double bg = (background & 0xFF) / 255.0;
    double fg = (text & 0xFF) / 255.0;
    double[] gamma = new double[320 * 150];
    Arrays.fill(gamma, bg);
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) != Scene.GLYPH) {
        continue;
      }
      Atlas.Page page = scene.pages().get(scene.getInt(i, Scene.PAGE));
      int atlas = scene.getInt(i, Scene.ATLAS);
      int size = scene.getInt(i, Scene.ATLAS_SIZE);
      int left = Math.round(scene.get(i, Scene.X));
      int top = Math.round(scene.get(i, Scene.Y));
      for (int y = 0; y < size >>> 16; y++) {
        for (int x = 0; x < (size & 0xFFFF); x++) {
          double a = page.coverage((atlas & 0xFFFF) + x, (atlas >>> 16) + y) / 255.0;
          int at = (top + y) * 320 + left + x;
          gamma[at] += (fg - gamma[at]) * a;
        }
      }
    }
    double rendered = 0;
    double reference = 0;
    for (int y = 0; y < 150; y++) {
      for (int x = 0; x < 320; x++) {
        rendered += ((pixels.argb(x, y) & 0xFF) / 255.0 - bg) / (fg - bg);
        reference += (gamma[y * 320 + x] - bg) / (fg - bg);
      }
    }
    return rendered / reference;
  }

  // ---- the scene -----------------------------------------------------------------------------

  @Test
  void glyphsAreInstancesBetweenTheBoxesInTreeOrder() {
    El label = El.text("Hi").color(0x80FFFFFF);
    El root = row()
        .padding(4)
        .fill(0xFF000000)
        .children(el().size(10, 10).fill(0xFFFF0000), label, el().size(10, 10).fill(0xFF00FF00));
    Headless.render(root, 100, 30, 2, PINNED);
    Scene scene = Scene.of(root, null, 0, new Atlas(), PINNED);
    assertEquals(5, scene.count(), "three boxes and two glyphs");
    int[] kinds = new int[5];
    for (int i = 0; i < 5; i++) {
      kinds[i] = scene.getInt(i, Scene.KIND);
    }
    assertArrayEquals(new int[] {0, 0, 1, 1, 0}, kinds);
    assertEquals(0f, scene.get(0, Scene.KIND), "0 is also the float 0 of a rectangle");
    int h = 2;
    float x = scene.get(h, Scene.X);
    float y = scene.get(h, Scene.Y);
    assertEquals(Math.rint(x * 2), x * 2, "on whole physical pixels");
    assertEquals(Math.rint(y * 2), y * 2);
    int size = scene.getInt(h, Scene.ATLAS_SIZE);
    assertEquals(scene.get(h, Scene.WIDTH) * 2, size & 0xFFFF);
    assertEquals(scene.get(h, Scene.HEIGHT) * 2, size >>> 16);
    assertTrue(x >= label.x() - 1 && x < label.x() + 2, "H starts at the label: " + x);
    assertEquals(0, scene.getInt(h, Scene.PAGE));
    assertEquals(1, scene.pages().size());
    assertEquals(128 / 255f, scene.get(h, Scene.COLOUR), "white, premultiplied by its alpha");
    assertEquals(128 / 255f, scene.get(h, Scene.COLOUR + 12));
    assertEquals(1, scene.get(h, Scene.LIGHT_SHARE), "lighter than the black around it");
    assertEquals(1.8f, scene.get(h, Scene.DARK_GAMMA));
    assertEquals(1.8f, scene.get(h, Scene.LIGHT_GAMMA));
    Scene windows = Scene.of(root, null, 0, new Atlas(), TextRendering.WINDOWS);
    assertEquals(1.4f, windows.get(h, Scene.LIGHT_GAMMA));
  }

  @Test
  void theCorrectionFollowsTheBackgroundOrElseTheBiasedRule() {
    assertEquals(0, share(el().fill(0xFFFFFFFF), 0xFF999999), "grey on white thickens");
    assertEquals(1, share(el().fill(0xFF1E1F22), 0xFF999999), "grey on near black thins");
    assertEquals(
        1,
        share(el().fill(0xFF000000).child(el().fill(0x40FFFFFF)), 0xFFA0A0A0),
        "a quarter of white over black is still darker than the text");
    assertEquals(0, share(el().fill(0xFF000000).child(el().fill(0xC0FFFFFF)), 0xFFA0A0A0));
    // Unknown: an empty root, or a fill that is translucent over it.
    assertEquals(0, share(el(), 0xFF000000));
    assertEquals(0, share(el(), 0xFF808080), "L* 53.6, below 60: thickened");
    assertEquals(0.161f, share(el(), 0xFF999999), 0.001f, "L* 63.2: mostly thickened");
    assertEquals(0.481f, share(el(), 0xFFAAAAAA), 0.001f, "L* 69.6: half and half");
    assertEquals(1, share(el(), 0xFFDCDCDC), "L* 87.8, above 80: thinned");
    assertEquals(0.161f, share(el().fill(0x80FFFFFF), 0xFF999999), 0.001f);
    assertEquals(0.161f, share(el().fill(0xFFFFFFFF).opacity(0.99f), 0xFF999999), 0.001f);
    // Stated.
    El told = el();
    assertEquals(0, share(told, El.text("Hi").color(0xFFDCDCDC).textBackground(0xFFFFFFFF)));
  }

  private static float share(El parent, int colour) {
    return share(parent, El.text("Hi").color(colour));
  }

  private static float share(El parent, El text) {
    El innermost = parent;
    while (!innermost.children().isEmpty()) {
      innermost = innermost.children().getFirst();
    }
    innermost.child(text);
    Headless.render(parent, 50, 30, 1, PINNED);
    Scene scene = Scene.of(parent, null, 0, new Atlas(), PINNED);
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.GLYPH) {
        return scene.get(i, Scene.LIGHT_SHARE);
      }
    }
    throw new AssertionError("no glyph");
  }

  // ---- the pixel grid ------------------------------------------------------------------------

  /** A box between pixels, as during a movement or scrolled, draws its text as if it were on one. */
  @Test
  void textInABoxAtAFractionalPositionIsDrawnFromTheSameBitmaps() {
    for (float scale : new float[] {1, 1.25f, 2}) {
      // At 1 and 1.25 whole physical pixels; at 2 quarters horizontally: 10.3 * 2 = 20.6 is 20.5.
      float snappedX = scale == 2 ? 10.25f : Math.round(10.3f * scale) / scale;
      float snappedY = Math.round(5.6f * scale) / scale;
      Pixels between = textAt(10.3f, 5.6f, scale);
      Pixels on = textAt(snappedX, snappedY, scale);
      assertArrayEquals(on.rgba(), between.rgba(), "at scale " + scale);
      assertNotEquals(0, ink(on), "something is drawn");
    }
  }

  private static int ink(Pixels pixels) {
    int sum = 0;
    for (int i = 3; i < pixels.rgba().length; i += 4) {
      sum += pixels.rgba()[i] & 0xFF;
    }
    return sum;
  }

  private static Pixels textAt(float x, float y, float scale) {
    El box = el().child(El.text("Größe 1,25").color(0xFF000000));
    El root = el().child(box);
    El.scaleText(root, scale);
    Flex.layout(root, 120, 40);
    box.place(x, y, box.width(), box.height());
    Scene scene = Scene.of(root, null, 0, new Atlas(), PINNED);
    return CpuRenderer.render(scene, Math.round(120 * scale), Math.round(40 * scale), scale);
  }

  // ---- motion --------------------------------------------------------------------------------

  @Test
  void theColourOfTextSpringsAndLeavingTextFadesOut() {
    boolean[] on = {false};
    App<boolean[]> app = new App<>(on, s -> {
      El root = col()
          .fill(0xFF000000)
          .child(El.text("Status")
              .key("status")
              .motion(Spring.SNAP)
              .color(s[0] ? 0xFFFFFFFF : 0xFF808080));
      if (!s[0]) {
        root.child(El.text("Bye").key("bye").motion(Spring.SNAP).color(0xFFFFFFFF));
      }
      return root;
    });
    app.textRendering(PINNED);
    app.resize(100, 40, 1);
    app.frame();
    on[0] = true;
    app.focusRing(0); // a change that rebuilds the view
    app.frame();
    app.advance(0.05);
    app.frame();
    El status = app.tree().children().getFirst();
    int grey = status.text().color() & 0xFF;
    assertTrue(grey > 0x80 && grey < 0xFF, "between grey and white: " + grey);
    El bye = app.tree().children().get(1);
    assertTrue(bye.isExiting());
    Scene scene = app.scene();
    float alpha = 0;
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.GLYPH) {
        alpha = scene.get(i, Scene.COLOUR + 12); // the last glyph is one of "Bye"
      }
    }
    assertTrue(alpha > 0 && alpha < 1, "fading: " + alpha);
    app.advance(1);
    app.frame();
    assertEquals(0xFFFFFFFF, status.text().color());
    assertEquals(1, app.tree().children().size(), "gone");
  }
}
