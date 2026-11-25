package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Scene;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Gradient backgrounds ({@link Background}, {@link El#background(Background)}): the linear and
 * radial formulas, premultiplied mixing in linear light, and layering inside borders and under
 * children. The docs example's stored images cover how both look.
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
  void leavesTheBorderWholeAndSmoothsARoundedEdgeOnce() {
    Background opaque =
        Background.linear(Len.ZERO, Len.ZERO, Len.percent(1), Len.ZERO, 0xFFFF0000, 0xFF0000FF);
    // Sides of different widths round their inner corners as ellipses.
    Pixels bare =
        Headless.render(card().border(10, 2, 2, 2, 0xFF22C55E), 60, 40, 1.25f).pixels();
    Pixels painted = Headless.render(
            card().border(10, 2, 2, 2, 0xFF22C55E).background(opaque), 60, 40, 1.25f)
        .pixels();
    for (int y = 0; y < 50; y++) {
      for (int x = 0; x < 75; x++) {
        if (bare.argb(x, y) == 0xFF22C55E) {
          assertEquals(0xFF22C55E, painted.argb(x, y), "border at " + x + ", " + y);
        }
      }
    }
    // Without a border, an opaque background over the fill has the edge of the fill alone.
    Pixels fill = Headless.render(card().fill(0xFFFFFFFF), 60, 40, 1.25f).pixels();
    Pixels over = Headless.render(
            card()
                .fill(0xFFFFFFFF)
                .background(Background.linear(
                    Len.ZERO, Len.ZERO, Len.percent(1), Len.ZERO, 0xFFFFFFFF, 0xFFFFFFFF)),
            60,
            40,
            1.25f)
        .pixels();
    for (int y = 0; y < 50; y++) {
      for (int x = 0; x < 75; x++) {
        assertEquals(fill.argb(x, y), over.argb(x, y), "edge at " + x + ", " + y);
      }
    }
  }

  private static El card() {
    return el().size(60, 40).radius(12);
  }
}
