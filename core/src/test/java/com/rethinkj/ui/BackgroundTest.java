package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Font;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Gradient and image backgrounds ({@link Background}, {@link El#background(Background)}): linear
 * and radial formulas, premultiplied mixing in linear light, image fitting, and layering inside
 * borders and under children. The docs examples' stored images cover how they look.
 */
class BackgroundTest {

  @Test
  void linearAndRadialMixPremultipliedInLinearLightWithSmoothstep() {
    // Linear from red at x=10 to transparent at x=90 over a white fill.
    int red = 0xFFFF0000;
    int white = 0xFFFFFFFF;
    El linearEl = el().size(100, 20)
        .fill(white)
        .background(
            Background.linear(Len.px(10), Len.ZERO, Len.px(90), Len.ZERO, red, Colors.TRANSPARENT));
    Pixels linearPx = Headless.render(linearEl, 100, 20, 1).pixels();

    // Clamped before x=10 -> pure red; clamped past x=90 -> pure white (transparent over white).
    assertEquals(red, linearPx.argb(4, 10), "before start is clamped to from");
    assertEquals(white, linearPx.argb(95, 10), "past end is clamped to to");

    // At x=30 (pixel 29 -> center 29.5? use x=30.5 -> pixel 30 -> t = (30.5 - 10) / 80 = 0.25625).
    double t = (30.5 - 10.0) / 80.0;
    double s = t * t * (3 - 2 * t);
    // Premultiplied linear red*(1-s) blended over opaque white:
    // R = 1*(1-s) + 1*s = 1; G = B = 0*(1-s) + 1*s = s (in linear light).
    int expectedGB = (int) Math.round(Colors.srgb(s) * 255);
    int sample = linearPx.argb(30, 10);
    assertEquals(255, Colors.red(sample), "red channel stays full (premultiplied, no dark fringe)");
    assertEquals(expectedGB, Colors.green(sample), 1, "smoothstep linear green");
    assertEquals(expectedGB, Colors.blue(sample), 1, "smoothstep linear blue");

    // Coincident endpoints paint 'from' everywhere.
    El degenerate = el().size(20, 20)
        .background(
            Background.linear(Len.px(10), Len.px(10), Len.px(10), Len.px(10), red, 0xFF0000FF));
    assertEquals(red, Headless.render(degenerate, 20, 20, 1).pixels().argb(15, 15));

    // Elliptical radial gradient centered at (50%, 50%) with rx=40px, ry=20px.
    El radialEl = el().size(100, 60)
        .fill(0xFF000000)
        .background(Background.radial(
            Len.percent(0.5f),
            Len.percent(0.5f),
            Len.px(40),
            Len.px(20),
            0xFFFFFFFF,
            Colors.TRANSPARENT));
    Pixels radialPx = Headless.render(radialEl, 100, 60, 1).pixels();
    assertEquals(0xFFFFFFFF, radialPx.argb(50, 30), "center is inner");
    assertEquals(0xFF000000, radialPx.argb(5, 30), "outside ellipse along x is outer");
    assertEquals(0xFF000000, radialPx.argb(50, 5), "outside ellipse along y is outer");
    // Elliptical: (50 + 20, 30) is at distance 0.5 along x, same as (50, 30 + 10) along y.
    int alongX = radialPx.argb(70, 30);
    int alongY = radialPx.argb(50, 40);
    assertTrue(
        Colors.red(alongX) > 100 && Colors.red(alongX) < 250,
        "mid-falloff along x: " + Colors.toHex(alongX));
    assertEquals(Colors.red(alongX), Colors.red(alongY), 6, "elliptical distance matches radii");
  }

  @Test
  void paintsInsideBorderClippedToRadiiUnderChildrenAndDoesNotAffectLayoutOrInput() {
    int border = 0xFF22C55E;
    int childFill = 0xFFF59E0B;
    AtomicInteger clicks = new AtomicInteger();

    App<Object> app = new App<>(
        new Object(),
        s -> el().size(100, 60)
            .fill(0xFF101014)
            .child(el().key("card")
                .absolute(10, 10)
                .size(80, 40)
                .radius(12)
                .fill(0xFF1E293B)
                .border(3, border)
                .background(Background.linear(
                    Len.percent(0),
                    Len.percent(0),
                    Len.percent(1),
                    Len.percent(0),
                    0xFFFF0000,
                    0xFF0000FF))
                .onPress(clicks::incrementAndGet)
                .child(el().absolute(30, 12).size(20, 16).fill(childFill))));
    app.resize(100, 60, 1);
    app.frame();

    Pixels pixels = app.render().pixels();
    // Border stays visible around the gradient.
    assertEquals(border, pixels.argb(50, 11), "top border stays visible");
    assertEquals(border, pixels.argb(50, 48), "bottom border stays visible");
    assertEquals(border, pixels.argb(11, 30), "left border stays visible");
    assertEquals(border, pixels.argb(88, 30), "right border stays visible");
    // Outside the rounded corner is still the canvas background, not gradient.
    assertEquals(0xFF101014, pixels.argb(11, 11), "clipped to rounded corner");
    // Inside the border shows the gradient, not the flat fill.
    assertNotEquals(0xFF1E293B, pixels.argb(18, 30));
    assertTrue(Colors.red(pixels.argb(18, 30)) > 200, "left side near from (red)");
    assertTrue(Colors.blue(pixels.argb(81, 30)) > 200, "right side near to (blue)");
    // Child paints over the gradient.
    assertEquals(childFill, pixels.argb(50, 30), "child paints over gradient");

    // Emits a single GRADIENT instance for the card.
    Scene scene = app.scene();
    int gradients = 0;
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.GRADIENT) {
        gradients++;
      }
    }
    assertEquals(1, gradients, "one GRADIENT instance per element");

    // Layout and hit testing are unaffected.
    El card = app.tree().children().getFirst();
    assertEquals(80, card.width());
    assertEquals(40, card.height());
    app.pointerDown(20, 30);
    app.pointerUp(20, 30);
    app.frame();
    assertEquals(1, clicks.get(), "click hits the element normally");
  }

  @Test
  void leavesTheBorderWholeAndDrawsWhereTheFillWould() {
    Background opaque =
        Background.linear(Len.ZERO, Len.ZERO, Len.percent(1), Len.ZERO, 0xFFFF0000, 0xFF0000FF);
    // Sides of different widths round their inner corners as ellipses.
    Pixels bare = render(card().radius(20).border(10, 2, 2, 2, 0xFF22C55E));
    Pixels painted = render(card().radius(20).border(10, 2, 2, 2, 0xFF22C55E).background(opaque));
    for (int y = 0; y < 50; y++) {
      for (int x = 0; x < 75; x++) {
        if (bare.argb(x, y) == 0xFF22C55E) {
          assertEquals(0xFF22C55E, painted.argb(x, y), "border at " + x + ", " + y);
        }
      }
    }
    // A white background draws as a white fill would: no edge smoothed twice, no seam inside the
    // border.
    Background white =
        Background.linear(Len.ZERO, Len.ZERO, Len.percent(1), Len.ZERO, 0xFFFFFFFF, 0xFFFFFFFF);
    for (float border : new float[] {0, 2}) {
      Pixels fill = render(card().border(border, 0xFF22C55E).fill(0xFFFFFFFF));
      Pixels over = render(card().border(border, 0xFF22C55E).background(white));
      for (int y = 0; y < 50; y++) {
        for (int x = 0; x < 75; x++) {
          assertEquals(fill.argb(x, y), over.argb(x, y), border + " at " + x + ", " + y);
        }
      }
    }
    // An opaque background hides the fill also at half the opacity.
    Pixels half = render(card().opacity(0.5f).fill(0xFFFFFFFF).background(white));
    Pixels flat = render(card().opacity(0.5f).fill(0xFFFFFFFF));
    assertEquals(flat.argb(30, 20), half.argb(30, 20));
    Pixels base = render(card().opacity(0.5f).fill(0xFF000000).background(white));
    assertEquals(flat.argb(30, 20), base.argb(30, 20), "the fill under it shows through");
  }

  @Test
  void imageBackgroundFitsCoverAndContainInsideBorderUnderChildrenWithNoLayoutEffect() {
    // 20x10 image: left half opaque red, right half transparent (so fill shows through).
    byte[] rgba = new byte[20 * 10 * 4];
    for (int y = 0; y < 10; y++) {
      for (int x = 0; x < 10; x++) {
        int at = (y * 20 + x) * 4;
        rgba[at] = (byte) 0xFF;
        rgba[at + 3] = (byte) 0xFF;
      }
    }
    Image halfRed = Image.of(new Pixels(20, 10, rgba));

    int canvas = 0xFF101014;
    int fill = 0xFF1E293B;
    int border = 0xFF22C55E;
    int childFill = 0xFFF59E0B;
    int red = 0xFFFF0000;
    AtomicInteger clicks = new AtomicInteger();

    // Two rounded, bordered, padded cards: COVER on the left, CONTAIN on the right, plus an
    // unsized element to confirm Background.image adds no intrinsic size.
    App<Object> app = new App<>(
        new Object(),
        s -> el().size(180, 60)
            .fill(canvas)
            .child(el().key("cover")
                .absolute(10, 10)
                .size(70, 40)
                .padding(8)
                .radius(12)
                .fill(fill)
                .border(3, border)
                .background(Background.image(halfRed, Image.Fit.COVER))
                .onPress(clicks::incrementAndGet)
                .child(el().absolute(25, 12).size(14, 14).fill(childFill)))
            .child(el().key("contain")
                .absolute(94, 10)
                .size(70, 40)
                .padding(8)
                .radius(12)
                .fill(fill)
                .border(3, border)
                .borderColors(border, 0xFF38BDF8, border, 0xFF38BDF8)
                .background(Background.image(halfRed, Image.Fit.CONTAIN)))
            .child(el().key("unsized")
                .alignSelf(Style.Align.START)
                .background(Background.image(halfRed, Image.Fit.COVER))));
    app.resize(180, 60, 1);
    app.frame();

    Pixels pixels = app.render().pixels();
    // COVER card: border stays visible, outside rounded corner is canvas, padding area is covered
    // by the red left half, right half is transparent so fill shows, child paints over it.
    assertEquals(border, pixels.argb(45, 11), "cover top border stays visible");
    assertEquals(border, pixels.argb(45, 48), "cover bottom border stays visible");
    assertEquals(canvas, pixels.argb(11, 11), "cover clipped to rounded corner");
    assertEquals(red, pixels.argb(16, 30), "cover fills padding area with opaque image half");
    assertEquals(fill, pixels.argb(72, 30), "transparent image pixels show fill");
    assertEquals(childFill, pixels.argb(40, 28), "child paints over image background");

    // CONTAIN card: padding box is 64x34, 20x10 image fits to 64x32 centered at y=14..46.
    // Top/bottom uncovered strip (y=13) shows fill; left middle shows red; right middle shows fill.
    assertEquals(border, pixels.argb(129, 11), "contain top border stays visible");
    assertEquals(0xFF38BDF8, pixels.argb(95, 30), "contain left per-side border stays visible");
    assertEquals(fill, pixels.argb(110, 13), "uncovered top strip shows fill");
    assertEquals(red, pixels.argb(110, 30), "contained left half shows red");
    assertEquals(fill, pixels.argb(148, 30), "contained transparent right half shows fill");

    // Per-side borderColors emits only one IMAGE instance for the contain card (two total, since
    // the unsized element has an empty box).
    Scene scene = app.scene();
    int images = 0;
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.IMAGE) {
        images++;
      }
    }
    assertEquals(2, images, "one IMAGE per visible image background, even with borderColors");

    // No intrinsic size from Background.image, and hit testing works normally.
    El unsized = app.tree().children().get(2);
    assertEquals(0, unsized.width());
    assertEquals(0, unsized.height());
    app.pointerDown(20, 30);
    app.pointerUp(20, 30);
    app.frame();
    assertEquals(1, clicks.get(), "click hits the element normally");
  }

  @Test
  void textOverAnImageBackgroundGetsTheCorrectionForAnUnknownBackdrop() {
    Image banner = Image.of(new Pixels(4, 1, new byte[16]));
    // Text over an image background does not inherit the flat white fill as its luminance hint.
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError _) {
      return;
    }
    int midGray = 0xFFC0C0C0; // CIE L* ~ 78 -> unknown backdrop share > 0, on white share == 0
    App<Object> withWhite = new App<>(
        new Object(),
        s -> el().size(80, 30).fill(0xFFFFFFFF).child(El.text("Hi").color(midGray)));
    withWhite.resize(80, 30, 1);
    withWhite.frame();
    App<Object> withImage = new App<>(
        new Object(),
        s -> el().size(80, 30)
            .fill(0xFFFFFFFF)
            .background(Background.image(banner, Image.Fit.COVER))
            .child(El.text("Hi").color(midGray)));
    withImage.resize(80, 30, 1);
    withImage.frame();
    float flatShare = firstGlyphLightShare(withWhite.scene());
    float imageShare = firstGlyphLightShare(withImage.scene());
    assertEquals(0f, flatShare, "on flat white, gray text is dark");
    assertTrue(imageShare > 0.5f, "over image background, gray text uses unknown-backdrop share");
  }

  @Test
  void stripesRejectANonPositiveWidthOrGapAndANonFiniteAngle() {
    assertThrows(IllegalArgumentException.class, () -> Background.stripes(0xFFFFFFFF, 0, 5, 0));
    assertThrows(IllegalArgumentException.class, () -> Background.stripes(0xFFFFFFFF, 1, -2, 0));
    assertThrows(
        IllegalArgumentException.class, () -> Background.stripes(0xFFFFFFFF, 1, 5, Float.NaN));
  }

  @Test
  void stripesAnchorToPaddingBoxAndDrawCrispAcrossScales() {
    int fill = 0xFF1E293B;
    int stripe = 0xFFFFFFFF;
    // Horizontal 0-degree 1px stripes with 5px gap (period 6) at 1x and 2x: row 0 inside the
    // padding box is pure stripe, rows 1..5 are pure fill, row 6 is pure stripe again.
    El shifted = el().size(40, 40)
        .fill(0xFF000000)
        .child(el().absolute(7, 9)
            .size(26, 22)
            .border(2, 0xFF22C55E)
            .fill(fill)
            .background(Background.stripes(stripe, 1, 5, 0)));
    Pixels at1x = Headless.render(shifted, 40, 40, 1).pixels();
    // Padding box top is y = 9 + 2 = 11.
    assertEquals(stripe, at1x.argb(15, 11), "row 0 of padding box is a full stripe at 1x");
    assertEquals(fill, at1x.argb(15, 12), "row 1 is gap at 1x");
    assertEquals(fill, at1x.argb(15, 16), "row 5 is gap at 1x");
    assertEquals(stripe, at1x.argb(15, 17), "row 6 starts the next stripe at 1x");

    Pixels at2x = Headless.render(shifted, 40, 40, 2).pixels();
    // At 2x, physical rows 22 and 23 cover logical [11, 12) -> both pure stripe; 24..33 -> pure
    // fill.
    assertEquals(stripe, at2x.argb(30, 22), "first physical row of stripe at 2x");
    assertEquals(stripe, at2x.argb(30, 23), "second physical row of stripe at 2x");
    assertEquals(fill, at2x.argb(30, 24), "first physical row of gap at 2x");
    assertEquals(stripe, at2x.argb(30, 34), "next stripe at 2x");

    // Golden images at 1x and 1.25x: a rounded, bordered track with -45 degree stripes and a
    // rounded, bordered box with horizontal 0 degree stripes.
    El sample = el().size(200, 92)
        .fill(0xFF0F172A)
        .children(
            el().key("diagonal")
                .absolute(12, 12)
                .size(176, 26)
                .radius(13)
                .fill(0xFF1E293B)
                .border(2, 0xFF64748B)
                .background(Background.stripes(0xFF94A3B8, 1, 5, -45)),
            el().key("horizontal")
                .absolute(12, 48)
                .size(176, 32)
                .radius(8)
                .fill(0xFF1E293B)
                .border(2, 0xFF64748B)
                .background(Background.stripes(0xFF94A3B8, 1, 5, 0)));
    Headless.Frame frame1x = Headless.render(sample, 200, 92, 1);
    Golden.assertMatches("striped-background-1x", frame1x.pixels(), frame1x.tree());
    Headless.Frame frame125x = Headless.render(sample, 200, 92, 1.25f);
    Golden.assertMatches("striped-background-1.25x", frame125x.pixels(), frame125x.tree());
  }

  private static float firstGlyphLightShare(Scene scene) {
    for (int i = 0; i < scene.count(); i++) {
      if (scene.getInt(i, Scene.KIND) == Scene.GLYPH) {
        return scene.get(i, Scene.LIGHT_SHARE);
      }
    }
    throw new AssertionError("no glyph in scene");
  }

  private static Pixels render(El card) {
    return Headless.render(card, 60, 40, 1.25f).pixels();
  }

  private static El card() {
    return el().size(60, 40).radius(12);
  }
}
