package com.rethinkj.ui.render;

import com.rethinkj.ui.Image;
import com.rethinkj.ui.text.Atlas;

// CpuRenderer as it was before it was made fast, but for its name and encode's visibility, with
// the radii per corner and borders per side added as plain loops too: what
// CpuRendererEquivalenceTest compares the fast one with, byte for byte.
/**
 * Draws a {@link Scene} on the CPU. This is the reference the GPU renderer is checked against, so
 * it is written to be obviously right, not fast, and its output is the same bit for bit on every
 * platform: plain IEEE arithmetic, and {@code StrictMath} wherever {@code Math} is allowed to
 * differ.
 */
final class ReferenceCpuRenderer {

  private ReferenceCpuRenderer() {}

  /**
   * @param width the width of the image in physical pixels
   * @param height the height of the image in physical pixels
   * @param scale physical pixels per logical pixel
   */
  public static Pixels render(Scene scene, int width, int height, float scale) {
    return encode(renderLinear(scene, width, height, scale), width, height);
  }

  /**
   * The image before it is encoded: linear light, premultiplied alpha, four floats per pixel in the
   * order red, green, blue, alpha. What an sRGB render target holds is this, encoded channel by
   * channel.
   */
  static float[] renderLinear(Scene scene, int width, int height, float scale) {
    return renderLinear(scene, width, height, scale, null);
  }

  /**
   * Sees every blend before it happens, for the test that bounds how far the GPU's blending may
   * lie from this renderer's. It changes nothing.
   */
  interface Blends {
    /**
     * @param frame the image so far, with the pixel as it is before the blend
     * @param at the index of the pixel's red value in {@code frame}
     * @param alpha the alpha of the source, which keeps {@code 1 - alpha} of the pixel
     */
    void blend(float[] frame, int at, double alpha);
  }

  /** As {@link #renderLinear(Scene, int, int, float)}, telling {@code blends} of each blend. */
  static float[] renderLinear(Scene scene, int width, int height, float scale, Blends blends) {
    // Starts fully transparent.
    float[] frame = new float[width * height * 4];
    for (int i = 0; i < scene.count(); i++) {
      int kind = scene.getInt(i, Scene.KIND);
      if (kind == Scene.GLYPH) {
        glyph(scene, i, frame, width, height, scale, blends);
      } else if (kind == Scene.SHADOW) {
        shadow(scene, i, frame, width, height, scale, blends);
      } else if (kind == Scene.IMAGE) {
        image(scene, i, frame, width, height, scale, blends);
      } else {
        rect(scene, i, frame, width, height, scale, blends);
      }
    }
    return frame;
  }

  private static void rect(
      Scene scene, int i, float[] frame, int width, int height, double scale, Blends blends) {
    double w = scene.get(i, Scene.WIDTH);
    double h = scene.get(i, Scene.HEIGHT);
    if (!(w > 0 && h > 0)) {
      return;
    }
    double halfW = w / 2;
    double halfH = h / 2;
    double centerX = scene.get(i, Scene.X) + halfW;
    double centerY = scene.get(i, Scene.Y) + halfH;
    double[] radii = radii(scene, i, halfW, halfH);
    double[] borders = {
      Math.max(scene.get(i, Scene.BORDER_WIDTH), 0),
      Math.max(scene.get(i, Scene.BORDER_RIGHT), 0),
      Math.max(scene.get(i, Scene.BORDER_BOTTOM), 0),
      Math.max(scene.get(i, Scene.BORDER_LEFT), 0)
    };
    boolean border = borders[0] > 0 || borders[1] > 0 || borders[2] > 0 || borders[3] > 0;
    // The border lies inside the rectangle, and a side without one moves the fill's edge out by a
    // physical pixel; what remains for the fill may be empty.
    double[] in = new double[4];
    for (int s = 0; s < 4; s++) {
      in[s] = borders[s] > 0 ? borders[s] : -1 / scale;
    }
    double innerHalfW = halfW - (in[3] + in[1]) / 2;
    double innerHalfH = halfH - (in[0] + in[2]) / 2;
    double innerX = centerX + (in[3] - in[1]) / 2;
    double innerY = centerY + (in[0] - in[2]) / 2;

    // One extra pixel on each side: the smoothed edge reaches half a pixel beyond the shape.
    int left = Math.max((int) Math.floor((centerX - halfW) * scale) - 1, 0);
    int top = Math.max((int) Math.floor((centerY - halfH) * scale) - 1, 0);
    int right = Math.min((int) Math.ceil((centerX + halfW) * scale) + 1, width);
    int bottom = Math.min((int) Math.ceil((centerY + halfH) * scale) + 1, height);

    // The clip's coverage multiplies the instance's. Entry 0 covers everything, so it is skipped.
    int clip = scene.clipOf(i);
    double clipHalfW = scene.clipGet(clip, Scene.WIDTH) / 2;
    double clipHalfH = scene.clipGet(clip, Scene.HEIGHT) / 2;
    double clipX = scene.clipGet(clip, Scene.X) + clipHalfW;
    double clipY = scene.clipGet(clip, Scene.Y) + clipHalfH;
    if (clip != 0) {
      left = Math.max(left, (int) Math.floor((clipX - clipHalfW) * scale) - 1);
      top = Math.max(top, (int) Math.floor((clipY - clipHalfH) * scale) - 1);
      right = Math.min(right, (int) Math.ceil((clipX + clipHalfW) * scale) + 1);
      bottom = Math.min(bottom, (int) Math.ceil((clipY + clipHalfH) * scale) + 1);
    }

    for (int py = top; py < bottom; py++) {
      for (int px = left; px < right; px++) {
        // Distances are measured from the pixel's centre, in logical pixels.
        double x = (px + 0.5) / scale - centerX;
        double y = (py + 0.5) / scale - centerY;
        double radius = radii[corner(x, y)];
        double coverage = coverage(distance(x, y, halfW, halfH, radius, radius), scale);
        if (clip != 0) {
          coverage *= clipCoverage(scene, clip, (px + 0.5) / scale, (py + 0.5) / scale, scale);
        }
        if (coverage <= 0) {
          continue;
        }
        double fillShare = 1;
        if (border) {
          fillShare = 0;
          if (innerHalfW > 0 && innerHalfH > 0) {
            double ix = (px + 0.5) / scale - innerX;
            double iy = (py + 0.5) / scale - innerY;
            int c = corner(ix, iy);
            double across =
                Math.min(Math.max(radii[c] - borders[c == 0 || c == 3 ? 3 : 1], 0), innerHalfW);
            double down = Math.min(Math.max(radii[c] - borders[c < 2 ? 0 : 2], 0), innerHalfH);
            fillShare = coverage(distance(ix, iy, innerHalfW, innerHalfH, across, down), scale);
          }
        }
        int at = (py * width + px) * 4;
        // Alpha is the last channel, so it is still the old value while the colours are blended.
        double alpha = mix(scene, i, 3, fillShare) * coverage;
        if (blends != null) {
          blends.blend(frame, at, alpha);
        }
        for (int c = 0; c < 4; c++) {
          double source = mix(scene, i, c, fillShare) * coverage;
          frame[at + c] = (float) (source + frame[at + c] * (1 - alpha));
        }
      }
    }
  }

  /** Draws a glyph's bitmap one texel to one physical pixel, with its coverage corrected. */
  private static void glyph(
      Scene scene, int i, float[] frame, int width, int height, double scale, Blends blends) {
    int atlas = scene.getInt(i, Scene.ATLAS);
    int size = scene.getInt(i, Scene.ATLAS_SIZE);
    Atlas.Page page = scene.pages().get(scene.getInt(i, Scene.PAGE));
    int left = (int) Math.round(scene.get(i, Scene.X) * scale);
    int top = (int) Math.round(scene.get(i, Scene.Y) * scale);
    double share = scene.get(i, Scene.LIGHT_SHARE);
    double darkGamma = scene.get(i, Scene.DARK_GAMMA);
    double lightGamma = scene.get(i, Scene.LIGHT_GAMMA);
    int clip = scene.getInt(i, Scene.CLIP);
    for (int ty = 0; ty < size >>> 16; ty++) {
      int py = top + ty;
      for (int tx = 0; tx < (size & 0xFFFF); tx++) {
        int px = left + tx;
        if (px < 0 || py < 0 || px >= width || py >= height) {
          continue;
        }
        double a = page.coverage((atlas & 0xFFFF) + tx, (atlas >>> 16) + ty) / 255.0;
        if (a == 0) {
          continue;
        }
        double dark = 1 - StrictMath.pow(1 - a, darkGamma);
        double light = StrictMath.pow(a, lightGamma);
        double coverage = dark + (light - dark) * share;
        if (clip != 0) {
          coverage *= clipCoverage(scene, clip, (px + 0.5) / scale, (py + 0.5) / scale, scale);
        }
        if (coverage <= 0) {
          continue;
        }
        int at = (py * width + px) * 4;
        double alpha = scene.get(i, Scene.COLOUR + 12) * coverage;
        if (blends != null) {
          blends.blend(frame, at, alpha);
        }
        for (int c = 0; c < 4; c++) {
          double source = scene.get(i, Scene.COLOUR + c * 4) * coverage;
          frame[at + c] = (float) (source + frame[at + c] * (1 - alpha));
        }
      }
    }
  }

  /** Draws an image: its texels sampled, with the coverage of its square rectangle and its clip. */
  private static void image(
      Scene scene, int i, float[] frame, int width, int height, double scale, Blends blends) {
    double w = scene.get(i, Scene.WIDTH);
    double h = scene.get(i, Scene.HEIGHT);
    if (!(w > 0 && h > 0)) {
      return;
    }
    double halfW = w / 2;
    double halfH = h / 2;
    double centerX = scene.get(i, Scene.X) + halfW;
    double centerY = scene.get(i, Scene.Y) + halfH;
    int left = Math.max((int) Math.floor((centerX - halfW) * scale) - 1, 0);
    int top = Math.max((int) Math.floor((centerY - halfH) * scale) - 1, 0);
    int right = Math.min((int) Math.ceil((centerX + halfW) * scale) + 1, width);
    int bottom = Math.min((int) Math.ceil((centerY + halfH) * scale) + 1, height);
    int clip = scene.clipOf(i);
    if (clip != 0) {
      double clipHalfW = scene.clipGet(clip, Scene.WIDTH) / 2;
      double clipHalfH = scene.clipGet(clip, Scene.HEIGHT) / 2;
      double clipX = scene.clipGet(clip, Scene.X) + clipHalfW;
      double clipY = scene.clipGet(clip, Scene.Y) + clipHalfH;
      left = Math.max(left, (int) Math.floor((clipX - clipHalfW) * scale) - 1);
      top = Math.max(top, (int) Math.floor((clipY - clipHalfH) * scale) - 1);
      right = Math.min(right, (int) Math.ceil((clipX + clipHalfW) * scale) + 1);
      bottom = Math.min(bottom, (int) Math.ceil((clipY + clipHalfH) * scale) + 1);
    }
    double[] sample = new double[4];
    for (int py = top; py < bottom; py++) {
      for (int px = left; px < right; px++) {
        double x = (px + 0.5) / scale - centerX;
        double y = (py + 0.5) / scale - centerY;
        double coverage = coverage(distance(x, y, halfW, halfH, 0, 0), scale);
        if (clip != 0) {
          coverage *= clipCoverage(scene, clip, (px + 0.5) / scale, (py + 0.5) / scale, scale);
        }
        if (coverage <= 0) {
          continue;
        }
        sample(scene, i, px, py, scale, sample);
        int at = (py * width + px) * 4;
        double alpha = scene.get(i, Scene.FILL + 12) * sample[3] * coverage;
        if (blends != null) {
          blends.blend(frame, at, alpha);
        }
        for (int c = 0; c < 4; c++) {
          double source = scene.get(i, Scene.FILL + c * 4) * sample[c] * coverage;
          frame[at + c] = (float) (source + frame[at + c] * (1 - alpha));
        }
      }
    }
  }

  /**
   * The image's sample at the pixel whose top left corner is {@code px, py}: one texel to one
   * pixel, or trilinear from its levels, as {@link Scene} says.
   */
  private static void sample(Scene scene, int i, int px, int py, double scale, double[] out) {
    Image image = scene.images().get(scene.getInt(i, Scene.IMAGE_INDEX));
    int size = scene.getInt(i, Scene.IMAGE_SIZE);
    int width0 = size & 0xFFFF;
    int height0 = size >>> 16;
    int levels = Math.clamp(scene.getInt(i, Scene.LEVELS), 1, image.levels());
    double originX = scene.get(i, Scene.X) * scale;
    double originY = scene.get(i, Scene.Y) * scale;
    double extentW = scene.get(i, Scene.WIDTH) * scale;
    double extentH = scene.get(i, Scene.HEIGHT) * scale;
    if (Math.abs(extentW - width0) < 1e-3
        && Math.abs(extentH - height0) < 1e-3
        && Math.abs(originX - Math.floor(originX + 0.5)) < 1e-3
        && Math.abs(originY - Math.floor(originY + 0.5)) < 1e-3) {
      int w = image.levelWidth(0);
      int x = Math.clamp(px - (long) Math.floor(originX + 0.5), 0, w - 1);
      int y = Math.clamp(py - (long) Math.floor(originY + 0.5), 0, image.levelHeight(0) - 1);
      for (int c = 0; c < 4; c++) {
        out[c] = value(image.level(0)[(y * w + x) * 4 + c], c);
      }
      return;
    }
    double ratioX = width0 / extentW;
    double ratioY = height0 / extentH;
    double u = (px + 0.5 - originX) * ratioX;
    double v = (py + 0.5 - originY) * ratioY;
    double lod = StrictMath.log(Math.max(ratioX, ratioY)) / StrictMath.log(2);
    if (!(lod > 0)) {
      bilinear(image, 0, width0, height0, u, v, out);
    } else if (Math.floor(lod) >= levels - 1) {
      bilinear(image, levels - 1, width0, height0, u, v, out);
    } else {
      int k = (int) Math.floor(lod);
      double[] finer = new double[4];
      bilinear(image, k, width0, height0, u, v, finer);
      bilinear(image, k + 1, width0, height0, u, v, out);
      for (int c = 0; c < 4; c++) {
        out[c] = finer[c] + (out[c] - finer[c]) * (lod - Math.floor(lod));
      }
    }
  }

  /** Level {@code k} at the point {@code u, v} of level 0, from its four texels around it. */
  private static void bilinear(
      Image image, int k, int width0, int height0, double u, double v, double[] out) {
    int w = image.levelWidth(k);
    int h = image.levelHeight(k);
    double fx = u * w / width0 - 0.5;
    double fy = v * h / height0 - 0.5;
    int left = Math.clamp((long) Math.floor(fx), 0, w - 1);
    int right = Math.clamp((long) Math.floor(fx) + 1, 0, w - 1);
    int upper = Math.clamp((long) Math.floor(fy), 0, h - 1);
    int lower = Math.clamp((long) Math.floor(fy) + 1, 0, h - 1);
    byte[] texels = image.level(k);
    for (int c = 0; c < 4; c++) {
      double a = value(texels[(upper * w + left) * 4 + c], c);
      double b = value(texels[(upper * w + right) * 4 + c], c);
      double d = value(texels[(lower * w + left) * 4 + c], c);
      double e = value(texels[(lower * w + right) * 4 + c], c);
      double over = a + (b - a) * (fx - Math.floor(fx));
      double under = d + (e - d) * (fx - Math.floor(fx));
      out[c] = over + (under - over) * (fy - Math.floor(fy));
    }
  }

  /** A texel's channel in linear light: colour decoded from sRGB, alpha divided by 255. */
  private static double value(byte texel, int channel) {
    return channel == 3 ? (texel & 0xFF) / 255.0 : Scene.linear(texel & 0xFF);
  }

  /** Draws a shadow: its shape blurred, over the shape grown by three deviations. */
  private static void shadow(
      Scene scene, int i, float[] frame, int width, int height, double scale, Blends blends) {
    double w = scene.get(i, Scene.WIDTH);
    double h = scene.get(i, Scene.HEIGHT);
    if (!(w > 0 && h > 0)) {
      return;
    }
    double halfW = w / 2;
    double halfH = h / 2;
    double centerX = scene.get(i, Scene.X) + halfW;
    double centerY = scene.get(i, Scene.Y) + halfH;
    double[] radii = radii(scene, i, halfW, halfH);
    double sigma = deviation(scene.get(i, Scene.SIGMA), scale);
    // The blur reaches three deviations beyond the shape, and one pixel more as for a rectangle.
    // shortcut: what lies beyond, at most 0.14 % of the colour, is cut off, which a light glow on
    // black shows as a faint edge; reach four deviations if glows need it.
    double reachW = halfW + 3 * sigma;
    double reachH = halfH + 3 * sigma;
    int left = Math.max((int) Math.floor((centerX - reachW) * scale) - 1, 0);
    int top = Math.max((int) Math.floor((centerY - reachH) * scale) - 1, 0);
    int right = Math.min((int) Math.ceil((centerX + reachW) * scale) + 1, width);
    int bottom = Math.min((int) Math.ceil((centerY + reachH) * scale) + 1, height);
    int clip = scene.clipOf(i);
    if (clip != 0) {
      double clipX = scene.clipGet(clip, Scene.X);
      double clipY = scene.clipGet(clip, Scene.Y);
      left = Math.max(left, (int) Math.floor(clipX * scale) - 1);
      top = Math.max(top, (int) Math.floor(clipY * scale) - 1);
      right =
          Math.min(right, (int) Math.ceil((clipX + scene.clipGet(clip, Scene.WIDTH)) * scale) + 1);
      bottom = Math.min(
          bottom, (int) Math.ceil((clipY + scene.clipGet(clip, Scene.HEIGHT)) * scale) + 1);
    }
    for (int py = top; py < bottom; py++) {
      for (int px = left; px < right; px++) {
        double x = (px + 0.5) / scale - centerX;
        double y = (py + 0.5) / scale - centerY;
        double coverage = shadow(x, y, halfW, halfH, radii, sigma);
        if (clip != 0) {
          coverage *= clipCoverage(scene, clip, (px + 0.5) / scale, (py + 0.5) / scale, scale);
        }
        if (coverage <= 0) {
          continue;
        }
        int at = (py * width + px) * 4;
        double alpha = scene.get(i, Scene.FILL + 12) * coverage;
        if (blends != null) {
          blends.blend(frame, at, alpha);
        }
        for (int c = 0; c < 4; c++) {
          double source = scene.get(i, Scene.FILL + c * 4) * coverage;
          frame[at + c] = (float) (source + frame[at + c] * (1 - alpha));
        }
      }
    }
  }

  /**
   * A shadow's deviation with the pixel's added: a box one physical pixel wide has the variance
   * 1/12 of a Gaussian.
   */
  static double deviation(double sigma, double scale) {
    return StrictMath.sqrt(sigma * sigma + 1 / (12 * scale * scale));
  }

  /** Slices of a quarter disc in {@link #quarter}. */
  private static final int SLICES = 8;

  /**
   * How much of a Gaussian of deviation {@code sigma} around the point {@code x, y} lies in a
   * rounded rectangle centred on the origin: two crossed boxes, whose blur is exact, and the
   * quarter discs at their corners.
   */
  static double shadow(
      double x, double y, double halfW, double halfH, double radius, double sigma) {
    double straightW = halfW - radius;
    double straightH = halfH - radius;
    double wide = across(x, halfW, sigma);
    double narrow = across(x, straightW, sigma);
    double tall = across(y, halfH, sigma);
    double flat = across(y, straightH, sigma);
    double coverage = wide * flat + narrow * tall - narrow * flat;
    if (radius > 0) {
      coverage += quarter(x - straightW, y - straightH, radius, sigma)
          + quarter(-x - straightW, y - straightH, radius, sigma)
          + quarter(x - straightW, -y - straightH, radius, sigma)
          + quarter(-x - straightW, -y - straightH, radius, sigma);
    }
    return Math.min(coverage, 1);
  }

  /**
   * With a radius per corner: the box, less each rounded corner's square, plus its quarter disc;
   * as the crossed boxes where all four are equal.
   */
  static double shadow(
      double x, double y, double halfW, double halfH, double[] radii, double sigma) {
    if (radii[1] == radii[0] && radii[2] == radii[0] && radii[3] == radii[0]) {
      return shadow(x, y, halfW, halfH, radii[0], sigma);
    }
    double coverage = across(x, halfW, sigma) * across(y, halfH, sigma);
    for (int c = 0; c < 4; c++) {
      double r = radii[c];
      if (r > 0) {
        double sideX = c == 1 || c == 2 ? x : -x;
        double sideY = c >= 2 ? y : -y;
        double straightH = halfH - r;
        coverage += quarter(sideX - (halfW - r), sideY - straightH, r, sigma)
            - (below(halfW - sideX, sigma) - below(halfW - r - sideX, sigma))
                * (below(halfH - sideY, sigma) - below(straightH - sideY, sigma));
      }
    }
    return Math.min(coverage, 1);
  }

  /** The Gaussian's mass from {@code -half} to {@code half}, seen from {@code t}. */
  private static double across(double t, double half, double sigma) {
    return below(t + half, sigma) - below(t - half, sigma);
  }

  /**
   * The blur of the quarter disc of radius {@code r} at the origin towards {@code +x, +y}, seen
   * from {@code u, v}: within three deviations of {@code v}, in slices of equal height, each the
   * Gaussian's exact mass across the slice times its mass along the disc at the slice's middle.
   */
  private static double quarter(double u, double v, double r, double sigma) {
    double reach = 3 * sigma;
    double low = Math.max(v - reach, 0);
    double high = Math.min(v + reach, r);
    if (!(high > low) || u < -reach || u > r + reach) {
      return 0;
    }
    double step = (high - low) / SLICES;
    double inside = below(-u, sigma);
    double before = below(low - v, sigma);
    double sum = 0;
    for (int i = 1; i <= SLICES; i++) {
      double top = low + step * i;
      double after = below(top - v, sigma);
      double middle = top - step / 2;
      double extent = StrictMath.sqrt(Math.max(r * r - middle * middle, 0));
      sum += (below(extent - u, sigma) - inside) * (after - before);
      before = after;
    }
    return sum;
  }

  /** How much of a Gaussian of deviation {@code sigma} around 0 lies below {@code t}. */
  private static double below(double t, double sigma) {
    return 0.5 + 0.5 * erf(t * (0.7071067811865476 / sigma));
  }

  /**
   * The error function as Abramowitz and Stegun's 7.1.28 has it, within 3e-7: arithmetic only, so
   * that the shader computes the same.
   */
  static double erf(double x) {
    double a = Math.min(Math.abs(x), 4);
    double p = 1
        + a
            * (0.0705230784
                + a
                    * (0.0422820123
                        + a
                            * (0.0092705272
                                + a * (0.0001520143 + a * (0.0002765672 + a * 0.0000430638)))));
    p *= p;
    p *= p;
    p *= p;
    p *= p;
    return Math.signum(x) * (1 - 1 / p);
  }

  /** How much of the pixel centred at {@code x, y}, in logical pixels, the clip lets through. */
  private static double clipCoverage(Scene scene, int clip, double x, double y, double scale) {
    double halfW = scene.clipGet(clip, Scene.WIDTH) / 2;
    double halfH = scene.clipGet(clip, Scene.HEIGHT) / 2;
    double dx = x - (scene.clipGet(clip, Scene.X) + halfW);
    double dy = y - (scene.clipGet(clip, Scene.Y) + halfH);
    double radius = Math.clamp(
        scene.clipGet(clip, Scene.RADIUS + 4 * corner(dx, dy)), 0, Math.min(halfW, halfH));
    return coverage(distance(dx, dy, halfW, halfH, radius, radius), scale);
  }

  /** The radii of the corners, top left, top right, bottom right, bottom left, clamped. */
  private static double[] radii(Scene scene, int i, double halfW, double halfH) {
    double most = Math.min(halfW, halfH);
    return new double[] {
      Math.clamp(scene.get(i, Scene.RADIUS), 0, most),
      Math.clamp(scene.get(i, Scene.RADIUS_TOP_RIGHT), 0, most),
      Math.clamp(scene.get(i, Scene.RADIUS_BOTTOM_RIGHT), 0, most),
      Math.clamp(scene.get(i, Scene.RADIUS_BOTTOM_LEFT), 0, most)
    };
  }

  /** The corner on the side of {@code x, y}: 0 top left, 1 top right, 2 bottom right, 3 bottom left. */
  private static int corner(double x, double y) {
    return x > 0 ? y > 0 ? 2 : 1 : y > 0 ? 3 : 0;
  }

  /**
   * Signed distance to a rounded rectangle centred on the origin whose corner on the point's side
   * has the radius {@code rx} across and {@code ry} from top to bottom; negative inside.
   */
  private static double distance(
      double x, double y, double halfW, double halfH, double rx, double ry) {
    if (rx == ry) {
      return distance(x, y, halfW, halfH, rx);
    }
    double ex = Math.abs(x) - halfW;
    double ey = Math.abs(y) - halfH;
    double u = ex + rx;
    double v = ey + ry;
    if (rx > 0 && ry > 0 && u > 0 && v > 0) {
      u /= rx;
      v /= ry;
      double k = StrictMath.sqrt(u * u + v * v);
      double gx = u / rx;
      double gy = v / ry;
      return (k - 1) * k / StrictMath.sqrt(gx * gx + gy * gy);
    }
    double outsideX = Math.max(ex, 0);
    double outsideY = Math.max(ey, 0);
    return Math.min(Math.max(ex, ey), 0)
        + StrictMath.sqrt(outsideX * outsideX + outsideY * outsideY);
  }

  /** One channel of the instance's colour: the border colour, or the fill where the fill shows. */
  private static double mix(Scene scene, int i, int channel, double fillShare) {
    double fill = scene.get(i, Scene.FILL + channel * 4);
    double edge = scene.get(i, Scene.BORDER + channel * 4);
    return edge + (fill - edge) * fillShare;
  }

  /** Signed distance to a rounded rectangle centred on the origin; negative inside. */
  private static double distance(double x, double y, double halfW, double halfH, double radius) {
    double qx = Math.abs(x) - halfW + radius;
    double qy = Math.abs(y) - halfH + radius;
    double outsideX = Math.max(qx, 0);
    double outsideY = Math.max(qy, 0);
    return Math.min(Math.max(qx, qy), 0)
        + StrictMath.sqrt(outsideX * outsideX + outsideY * outsideY)
        - radius;
  }

  /** How much of a pixel the shape covers: a ramp one physical pixel wide across the edge. */
  private static double coverage(double distance, double scale) {
    return Math.clamp(0.5 - distance * scale, 0, 1);
  }

  /** A linear value from 0 to 1 as 8-bit sRGB, from 0 to 255. */
  static int encode(double linear) {
    double srgb =
        linear <= 0.0031308 ? linear * 12.92 : 1.055 * StrictMath.pow(linear, 1 / 2.4) - 0.055;
    return (int) Math.round(srgb * 255);
  }

  static Pixels encode(float[] frame, int width, int height) {
    byte[] rgba = new byte[frame.length];
    for (int at = 0; at < frame.length; at += 4) {
      double alpha = Math.clamp(frame[at + 3], 0, 1);
      if (alpha == 0) {
        continue;
      }
      for (int c = 0; c < 3; c++) {
        rgba[at + c] = (byte) encode(Math.clamp(frame[at + c] / alpha, 0, 1));
      }
      rgba[at + 3] = (byte) Math.round(alpha * 255);
    }
    return new Pixels(width, height, rgba);
  }
}
