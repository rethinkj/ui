package com.rethinkj.ui.render;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * What to draw, as a flat list of instances in draw order. The list lives in native memory in a
 * fixed layout, so the same bytes can be uploaded to the GPU; every renderer reads this format.
 *
 * <p>There is one primitive: a rounded rectangle with a fill and a border. An instance is sixteen
 * floats: {@code x, y, width, height} in logical pixels, {@code radius, borderWidth} and two unused
 * floats, then the fill and the border colour as {@code r, g, b, a}.
 *
 * <p>Colours are linear and premultiplied by alpha. Blending happens in linear light, as in rune:
 * that is what an sRGB render target does on the GPU, and it keeps translucent edges from
 * darkening.
 */
public final class Scene {

  /** Bytes per instance. */
  public static final int STRIDE = 16 * Float.BYTES;

  public static final int X = 0;
  public static final int Y = 4;
  public static final int WIDTH = 8;
  public static final int HEIGHT = 12;
  public static final int RADIUS = 16;
  public static final int BORDER_WIDTH = 20;
  public static final int FILL = 32;
  public static final int BORDER = 48;

  private static final float[] SRGB_TO_LINEAR = new float[256];

  static {
    for (int i = 0; i < 256; i++) {
      double c = i / 255.0;
      // StrictMath: Math.pow may differ in the last bit between platforms.
      SRGB_TO_LINEAR[i] =
          (float) (c <= 0.04045 ? c / 12.92 : StrictMath.pow((c + 0.055) / 1.055, 2.4));
    }
  }

  private MemorySegment data = Arena.ofAuto().allocate(64L * STRIDE, Float.BYTES);
  private int count;

  /** The scene of a tree that layout has placed. Elements that were not placed are left out. */
  public static Scene of(El root) {
    Scene scene = new Scene();
    scene.add(root, 0, 0);
    return scene;
  }

  private void add(El el, float parentX, float parentY) {
    if (!el.isPlaced()) {
      return;
    }
    float x = parentX + el.x();
    float y = parentY + el.y();
    // shortcut: one border width for all sides, taken from the top; per-side widths need a wider
    // instance.
    Len border = el.style().borderTop;
    float borderWidth = border.kind() == Len.Kind.PX ? border.value() : 0;
    boolean hasBorder = borderWidth > 0 && el.borderColor() >>> 24 != 0;
    if (el.fill() >>> 24 != 0 || hasBorder) {
      rect(x, y, el.width(), el.height(), el.radius(), hasBorder ? borderWidth : 0, el.fill(),
          el.borderColor());
    }
    for (El child : el.children()) {
      add(child, x, y);
    }
  }

  /**
   * Appends a rounded rectangle.
   *
   * @param fill the fill colour as 0xAARRGGBB
   * @param border the border colour as 0xAARRGGBB
   */
  public void rect(
      float x, float y, float width, float height, float radius, float borderWidth, int fill,
      int border) {
    long offset = (long) count * STRIDE;
    if (offset == data.byteSize()) {
      MemorySegment grown = Arena.ofAuto().allocate(data.byteSize() * 2, Float.BYTES);
      grown.copyFrom(data);
      data = grown;
    }
    put(offset + X, x);
    put(offset + Y, y);
    put(offset + WIDTH, width);
    put(offset + HEIGHT, height);
    put(offset + RADIUS, radius);
    put(offset + BORDER_WIDTH, borderWidth);
    put(offset + 24, 0);
    put(offset + 28, 0);
    colour(offset + FILL, fill);
    colour(offset + BORDER, border);
    count++;
  }

  private void colour(long offset, int argb) {
    float alpha = (argb >>> 24) / 255f;
    put(offset, SRGB_TO_LINEAR[argb >> 16 & 0xFF] * alpha);
    put(offset + 4, SRGB_TO_LINEAR[argb >> 8 & 0xFF] * alpha);
    put(offset + 8, SRGB_TO_LINEAR[argb & 0xFF] * alpha);
    put(offset + 12, alpha);
  }

  private void put(long offset, float value) {
    data.set(ValueLayout.JAVA_FLOAT, offset, value);
  }

  public int count() {
    return count;
  }

  /** The instances, {@link #STRIDE} bytes each, in the byte order of the machine. */
  public MemorySegment data() {
    return data.asSlice(0, (long) count * STRIDE);
  }

  /** One float of one instance; {@code field} is one of the offsets above, plus 4 per channel. */
  public float get(int instance, int field) {
    return data.get(ValueLayout.JAVA_FLOAT, (long) instance * STRIDE + field);
  }
}
