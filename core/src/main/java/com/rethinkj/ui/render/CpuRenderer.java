package com.rethinkj.ui.render;

/**
 * Draws a {@link Scene} on the CPU. This is the reference the GPU renderer is checked against, so
 * it is written to be obviously right, not fast, and its output is the same bit for bit on every
 * platform: plain IEEE arithmetic, and {@code StrictMath} wherever {@code Math} is allowed to
 * differ.
 */
public final class CpuRenderer {

  private CpuRenderer() {}

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
    // Starts fully transparent.
    float[] frame = new float[width * height * 4];
    for (int i = 0; i < scene.count(); i++) {
      rect(scene, i, frame, width, height, scale);
    }
    return frame;
  }

  private static void rect(Scene scene, int i, float[] frame, int width, int height, double scale) {
    double w = scene.get(i, Scene.WIDTH);
    double h = scene.get(i, Scene.HEIGHT);
    if (!(w > 0 && h > 0)) {
      return;
    }
    double halfW = w / 2;
    double halfH = h / 2;
    double centerX = scene.get(i, Scene.X) + halfW;
    double centerY = scene.get(i, Scene.Y) + halfH;
    double radius = Math.clamp(scene.get(i, Scene.RADIUS), 0, Math.min(halfW, halfH));
    double border = Math.max(scene.get(i, Scene.BORDER_WIDTH), 0);
    // The border lies inside the rectangle; what remains for the fill may be empty.
    double innerHalfW = halfW - border;
    double innerHalfH = halfH - border;
    double innerRadius = Math.max(radius - border, 0);

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
    double clipRadius =
        Math.clamp(scene.clipGet(clip, Scene.RADIUS), 0, Math.min(clipHalfW, clipHalfH));
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
        double coverage = coverage(distance(x, y, halfW, halfH, radius), scale);
        if (clip != 0) {
          double cx = (px + 0.5) / scale - clipX;
          double cy = (py + 0.5) / scale - clipY;
          coverage *= coverage(distance(cx, cy, clipHalfW, clipHalfH, clipRadius), scale);
        }
        if (coverage <= 0) {
          continue;
        }
        double fillShare = 1;
        if (border > 0) {
          fillShare =
              innerHalfW > 0 && innerHalfH > 0
                  ? coverage(distance(x, y, innerHalfW, innerHalfH, innerRadius), scale)
                  : 0;
        }
        int at = (py * width + px) * 4;
        // Alpha is the last channel, so it is still the old value while the colours are blended.
        double alpha = mix(scene, i, 3, fillShare) * coverage;
        for (int c = 0; c < 4; c++) {
          double source = mix(scene, i, c, fillShare) * coverage;
          frame[at + c] = (float) (source + frame[at + c] * (1 - alpha));
        }
      }
    }
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

  private static Pixels encode(float[] frame, int width, int height) {
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
