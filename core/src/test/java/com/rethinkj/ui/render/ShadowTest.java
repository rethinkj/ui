package com.rethinkj.ui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The shadow's formula ({@link CpuRenderer#shadow}) against a brute-force Gaussian blur of the
 * rounded rectangle: a sum over cells of a fine grid, which needs no {@code erf}. Square corners
 * are exact; the slices of the quarter discs are what is approximate.
 */
class ShadowTest {

  /** A rounded rectangle of half size {@code halfW, halfH}, blurred with deviation sigma. */
  static Stream<Arguments> shapes() {
    return Stream.of(
        Arguments.of("radius 0", 20, 12, 0, 4),
        Arguments.of("small radius, blur above it", 20, 12, 2, 3),
        Arguments.of("radius of half the height", 20, 12, 12, 4),
        Arguments.of("blur below the radius", 20, 12, 8, 2),
        Arguments.of("blur above the radius", 20, 12, 8, 12),
        // A 40 by 24 box with radius 8 and a spread of 6: 52 by 36, radius 14.
        Arguments.of("a spread", 26, 18, 14, 6),
        // A menu's tight shadow: 160 by 120, radius 4, blur 3.
        Arguments.of("a menu's tight layer", 80, 60, 4, 1.5));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shapes")
  void theBlurIsWithinAHundredthOfTheTrueGaussian(String name, double halfW, double halfH,
      double radius, double sigma) {
    double worst = 0;
    String where = "";
    // A grid over a quarter of the shadow, which is symmetric, and a line across the corner.
    double reachW = halfW + 3 * sigma;
    double reachH = halfH + 3 * sigma;
    for (int j = 0; j <= 12; j++) {
      for (int i = 0; i <= 12; i++) {
        double x = reachW * i / 12;
        double y = reachH * j / 12;
        double error = Math.abs(CpuRenderer.shadow(x, y, halfW, halfH, radius, sigma)
            - brute(x, y, halfW, halfH, radius, sigma));
        if (error > worst) {
          worst = error;
          where = x + "," + y;
        }
      }
    }
    double corner = (radius + 3 * sigma) * Math.sqrt(0.5);
    for (int i = -20; i <= 20; i++) {
      double x = halfW - radius + corner * i / 20;
      double y = halfH - radius + corner * i / 20;
      double error = Math.abs(CpuRenderer.shadow(x, y, halfW, halfH, radius, sigma)
          - brute(x, y, halfW, halfH, radius, sigma));
      if (error > worst) {
        worst = error;
        where = x + "," + y;
      }
    }
    System.out.printf(Locale.ROOT, "Shadow of %s: at most %.5f from the true blur, at %s%n", name, worst,
        where);
    assertTrue(worst < (radius == 0 ? 1e-4 : 0.01), name + ": " + worst + " at " + where);
  }

  /**
   * The blur of the shape at {@code x, y} by brute force: the Gaussian's density at the centre of
   * each cell inside the shape, times the cell's area, over cells of a sixty-fourth of the
   * deviation whose edges lie on the shape's straight edges, within five deviations of the point.
   */
  private static double brute(double x, double y, double halfW, double halfH, double radius,
      double sigma) {
    double h = sigma / 64;
    int columns = (int) Math.ceil(2 * halfW / h);
    int rows = (int) Math.ceil(2 * halfH / h);
    double cellW = 2 * halfW / columns;
    double cellH = 2 * halfH / rows;
    double reach = 5 * sigma;
    int left = Math.max((int) ((x - reach + halfW) / cellW), 0);
    int right = Math.min((int) ((x + reach + halfW) / cellW) + 1, columns);
    int top = Math.max((int) ((y - reach + halfH) / cellH), 0);
    int bottom = Math.min((int) ((y + reach + halfH) / cellH) + 1, rows);
    double[] across = new double[columns];
    for (int i = left; i < right; i++) {
      double cx = -halfW + (i + 0.5) * cellW - x;
      across[i] = Math.exp(-cx * cx / (2 * sigma * sigma));
    }
    double sum = 0;
    for (int j = top; j < bottom; j++) {
      double cy = -halfH + (j + 0.5) * cellH;
      double down = Math.exp(-(cy - y) * (cy - y) / (2 * sigma * sigma));
      double corner = Math.max(Math.abs(cy) - (halfH - radius), 0);
      double half = halfW - radius + Math.sqrt(Math.max(radius * radius - corner * corner, 0));
      double row = 0;
      for (int i = left; i < right; i++) {
        if (Math.abs(-halfW + (i + 0.5) * cellW) <= half) {
          row += across[i];
        }
      }
      sum += row * down;
    }
    return sum * cellW * cellH / (2 * Math.PI * sigma * sigma);
  }

  /** Rounded rectangles whose corners differ: top left, top right, bottom right, bottom left. */
  static Stream<Arguments> corners() {
    return Stream.of(
        Arguments.of("four radii", 20, 12, new double[] {0, 6, 12, 3}, 3),
        Arguments.of("rounded at the top", 20, 12, new double[] {8, 8, 0, 0}, 2),
        Arguments.of("a leaf, blur above the radius", 16, 12, new double[] {12, 0, 12, 0}, 4));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("corners")
  void theBlurOfCornersOfTheirOwnIsWithinAHundredthOfTheTrueGaussian(String name, double halfW,
      double halfH, double[] radii, double sigma) {
    double worst = 0;
    String where = "";
    double reachW = halfW + 3 * sigma;
    double reachH = halfH + 3 * sigma;
    for (int j = -10; j <= 10; j++) {
      for (int i = -10; i <= 10; i++) {
        double x = reachW * i / 10;
        double y = reachH * j / 10;
        double error = Math.abs(CpuRenderer.shadow(x, y, halfW, halfH, radii, sigma)
            - brute(x, y, halfW, halfH, radii, sigma));
        if (error > worst) {
          worst = error;
          where = x + "," + y;
        }
      }
    }
    System.out.printf(Locale.ROOT, "Shadow of %s: at most %.5f from the true blur, at %s%n", name,
        worst, where);
    assertTrue(worst < 0.01, name + ": " + worst + " at " + where);
  }

  /** As the other brute force, with a radius per corner and cells tested one by one. */
  private static double brute(double x, double y, double halfW, double halfH, double[] radii,
      double sigma) {
    double h = sigma / 32;
    int columns = (int) Math.ceil(2 * halfW / h);
    int rows = (int) Math.ceil(2 * halfH / h);
    double cellW = 2 * halfW / columns;
    double cellH = 2 * halfH / rows;
    double reach = 5 * sigma;
    int left = Math.max((int) ((x - reach + halfW) / cellW), 0);
    int right = Math.min((int) ((x + reach + halfW) / cellW) + 1, columns);
    int top = Math.max((int) ((y - reach + halfH) / cellH), 0);
    int bottom = Math.min((int) ((y + reach + halfH) / cellH) + 1, rows);
    double sum = 0;
    for (int j = top; j < bottom; j++) {
      double cy = -halfH + (j + 0.5) * cellH;
      double down = Math.exp(-(cy - y) * (cy - y) / (2 * sigma * sigma));
      for (int i = left; i < right; i++) {
        double cx = -halfW + (i + 0.5) * cellW;
        double r = radii[cx > 0 ? cy > 0 ? 2 : 1 : cy > 0 ? 3 : 0];
        double qx = Math.abs(cx) - (halfW - r);
        double qy = Math.abs(cy) - (halfH - r);
        if (qx <= 0 || qy <= 0 || qx * qx + qy * qy <= r * r) {
          sum += Math.exp(-(cx - x) * (cx - x) / (2 * sigma * sigma)) * down;
        }
      }
    }
    return sum * cellW * cellH / (2 * Math.PI * sigma * sigma);
  }

  @Test
  void erfIsWithinItsBound() {
    // Values of erf to ten places.
    double[][] known = {{0, 0}, {0.1, 0.1124629160}, {0.5, 0.5204998778}, {1, 0.8427007929},
        {1.5, 0.9661051465}, {2, 0.9953222650}, {3, 0.9999779095}, {5, 1}};
    for (double[] k : known) {
      assertEquals(k[1], CpuRenderer.erf(k[0]), 3e-7, "erf " + k[0]);
      assertEquals(-k[1], CpuRenderer.erf(-k[0]), 3e-7, "erf -" + k[0]);
    }
  }

  @Test
  void theDeviationAddsAPixelsShare() {
    assertEquals(Math.sqrt(1 / 12.0), CpuRenderer.deviation(0, 1), 1e-12);
    assertEquals(Math.sqrt(16 + 1 / 48.0), CpuRenderer.deviation(4, 2), 1e-12);
  }
}
