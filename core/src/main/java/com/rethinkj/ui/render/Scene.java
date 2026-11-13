package com.rethinkj.ui.render;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * What to draw, as a flat list of instances in draw order and a table of clips. Both live in
 * native memory in a fixed layout, so the same bytes can be uploaded to the GPU; every renderer
 * reads this format. All numbers are 32 bits wide, in the byte order of the machine, and all
 * positions are window coordinates in logical pixels, x to the right and y down.
 *
 * <p>There is one primitive: a rounded rectangle with a fill and a border. An instance is {@link
 * #STRIDE} bytes, sixteen 4-byte slots; the byte offsets are the constants below:
 *
 * <pre>
 *  0  x            f32   left edge of the rectangle
 *  4  y            f32   top edge
 *  8  width        f32
 * 12  height       f32
 * 16  radius       f32   corner radius; renderers clamp it to half the shorter side
 * 20  borderWidth  f32   border inside the rectangle, 0 for none
 * 24  clip         u32   index into the clip table, 0 for not clipped (an integer, not a float)
 * 28  unused       f32   0
 * 32  fill         f32 x 4   r, g, b, a
 * 48  border       f32 x 4   r, g, b, a
 * </pre>
 *
 * <p>As a WGSL storage buffer that is {@code array<Instance>} with {@code struct Instance { rect:
 * vec4f, radius: f32, border_width: f32, clip: u32, unused: f32, fill: vec4f, border: vec4f }}.
 *
 * <p>Colours are linear and premultiplied by alpha. Blending happens in linear light, as in rune:
 * that is what an sRGB render target does on the GPU, and it keeps translucent edges from
 * darkening.
 *
 * <p>The clip table is {@link #clips()}, {@link #CLIP_STRIDE} bytes per entry: a rounded rectangle
 * outside of which an instance that refers to it is not drawn.
 *
 * <pre>
 *  0  x        f32
 *  4  y        f32
 *  8  width    f32   0 or more; 0 clips everything away
 * 12  height   f32
 * 16  radius   f32   clamped like an instance's radius
 * 20  unused   f32 x 3   0
 * </pre>
 *
 * <p>As WGSL: {@code array<Clip>} with {@code struct Clip { rect: vec4f, radius: f32 }}, whose
 * size rounds up to the same 32 bytes. The table always has an entry 0, which stands for no clip:
 * a rectangle from -1e9 to 1e9 on both axes with radius 0, so that applying it changes nothing
 * and a renderer may skip it or not.
 *
 * <p>A renderer computes how much of a pixel an instance covers, from 0 to 1, as the signed
 * distance from the pixel's centre to the instance's rounded rectangle turned into a ramp one
 * physical pixel wide across the edge, and multiplies that by the same coverage of its clip's
 * rounded rectangle; see {@link CpuRenderer}. So the edge of a clip is smoothed like any other.
 *
 * <p>An element whose overflow is not visible ({@link El#clip}, {@link El#scroll}) clips its
 * descendants, not itself. When clips are nested, an entry holds the intersection of the element's
 * border box with the clip of its ancestors, so one entry per instance is enough. Instances that
 * lie entirely outside their clip are left out.
 *
 * <p>The focus ring is an ordinary instance too: a border {@link #RING} pixels wide just outside
 * the focused element's border box, with its corner radius grown by as much, drawn after the
 * element and its children and clipped like the element.
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
  public static final int CLIP = 24;
  public static final int FILL = 32;
  public static final int BORDER = 48;

  /** Bytes per entry of the clip table. Its fields are at {@link #X} to {@link #RADIUS}. */
  public static final int CLIP_STRIDE = 8 * Float.BYTES;

  /** The width of the focus ring in logical pixels. */
  public static final float RING = 2;

  private static final float NO_CLIP = 1e9f;

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
  private MemorySegment clips = Arena.ofAuto().allocate(8L * CLIP_STRIDE, Float.BYTES);
  private int clipCount;
  private El ring;
  private int ringColour;

  public Scene() {
    clip(-NO_CLIP, -NO_CLIP, 2 * NO_CLIP, 2 * NO_CLIP, 0);
  }

  /**
   * The scene of a tree that layout has placed. Elements that were not placed, or whose opacity
   * with that of their ancestors is 0, are left out, and so is what their ancestors clip away.
   */
  public static Scene of(El root) {
    return of(root, null, 0);
  }

  /**
   * The scene of a tree with a focus ring around one of its elements.
   *
   * @param ring the element with the ring, or null for none
   * @param ringColour the colour of the ring as 0xAARRGGBB
   */
  public static Scene of(El root, El ring, int ringColour) {
    Scene scene = new Scene();
    scene.ring = ring;
    scene.ringColour = ringColour;
    scene.add(root, 0, 0, 1, 0);
    return scene;
  }

  private void add(El el, float parentX, float parentY, float parentOpacity, int clip) {
    // shortcut: opacity is multiplied into each instance, so overlapping descendants of a
    // translucent element show through each other; true group opacity needs an off-screen layer.
    float opacity = parentOpacity * el.opacity();
    if (!el.isPlaced() || opacity == 0) {
      return;
    }
    float x = parentX + el.x();
    float y = parentY + el.y();
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    float clipRight = clipX + clipGet(clip, WIDTH);
    float clipBottom = clipY + clipGet(clip, HEIGHT);
    // shortcut: one border width for all sides, taken from the top; per-side widths need a wider
    // instance.
    Len border = el.style().borderTop;
    float borderWidth = border.kind() == Len.Kind.PX ? border.value() : 0;
    boolean hasBorder = borderWidth > 0 && el.borderColor() >>> 24 != 0;
    boolean inside =
        x < clipRight && x + el.width() > clipX && y < clipBottom && y + el.height() > clipY;
    if ((el.fill() >>> 24 != 0 || hasBorder) && inside) {
      rect(x, y, el.width(), el.height(), el.radius(), hasBorder ? borderWidth : 0, el.fill(),
          el.borderColor(), opacity, clip);
    }
    int inner = clip;
    Style s = el.style();
    if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
      float left = Math.max(x, clipX);
      float top = Math.max(y, clipY);
      float width = Math.min(x + el.width(), clipRight) - left;
      float height = Math.min(y + el.height(), clipBottom) - top;
      // shortcut: an entry has one radius, the innermost clip's, so where an inner clip sticks
      // out of a rounded outer one, the corners of the intersection take the inner radius instead
      // of following the outer curve; give each entry its parent's index when that shows.
      inner = width > 0 && height > 0 ? clip(left, top, width, height, el.radius()) : -1;
    }
    if (inner >= 0) { // else everything below is clipped away
      for (El child : el.children()) {
        add(child, x, y, opacity, inner);
      }
    }
    if (el == ring && ringColour >>> 24 != 0) {
      float left = x - RING;
      float top = y - RING;
      float right = x + el.width() + RING;
      float bottom = y + el.height() + RING;
      if (left < clipRight && right > clipX && top < clipBottom && bottom > clipY) {
        rect(left, top, right - left, bottom - top, el.radius() > 0 ? el.radius() + RING : 0, RING,
            0, ringColour, opacity, clip);
      }
    }
  }

  private int clip(float x, float y, float width, float height, float radius) {
    long offset = (long) clipCount * CLIP_STRIDE;
    if (offset == clips.byteSize()) {
      clips = grow(clips);
    }
    clips.set(ValueLayout.JAVA_FLOAT, offset + X, x);
    clips.set(ValueLayout.JAVA_FLOAT, offset + Y, y);
    clips.set(ValueLayout.JAVA_FLOAT, offset + WIDTH, width);
    clips.set(ValueLayout.JAVA_FLOAT, offset + HEIGHT, height);
    clips.set(ValueLayout.JAVA_FLOAT, offset + RADIUS, radius);
    return clipCount++;
  }

  private static MemorySegment grow(MemorySegment segment) {
    MemorySegment grown = Arena.ofAuto().allocate(segment.byteSize() * 2, Float.BYTES);
    grown.copyFrom(segment);
    return grown;
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
    rect(x, y, width, height, radius, borderWidth, fill, border, 1, 0);
  }

  private void rect(
      float x, float y, float width, float height, float radius, float borderWidth, int fill,
      int border, float opacity, int clip) {
    long offset = (long) count * STRIDE;
    if (offset == data.byteSize()) {
      data = grow(data);
    }
    put(offset + X, x);
    put(offset + Y, y);
    put(offset + WIDTH, width);
    put(offset + HEIGHT, height);
    put(offset + RADIUS, radius);
    put(offset + BORDER_WIDTH, borderWidth);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    put(offset + 28, 0);
    colour(offset + FILL, fill, opacity);
    colour(offset + BORDER, border, opacity);
    count++;
  }

  private void colour(long offset, int argb, float opacity) {
    float alpha = (argb >>> 24) / 255f * opacity;
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

  /**
   * One float of one instance; {@code field} is one of the offsets above, plus 4 per channel, but
   * not {@link #CLIP}.
   */
  public float get(int instance, int field) {
    return data.get(ValueLayout.JAVA_FLOAT, (long) instance * STRIDE + field);
  }

  /** The index of the instance's entry in the clip table, 0 when it is not clipped. */
  public int clipOf(int instance) {
    return data.get(ValueLayout.JAVA_INT, (long) instance * STRIDE + CLIP);
  }

  /** The number of entries in the clip table, entry 0 included. */
  public int clipCount() {
    return clipCount;
  }

  /** The clip table, {@link #CLIP_STRIDE} bytes per entry, in the byte order of the machine. */
  public MemorySegment clips() {
    return clips.asSlice(0, (long) clipCount * CLIP_STRIDE);
  }

  /** One float of one clip; {@code field} is one of {@link #X} to {@link #RADIUS}. */
  public float clipGet(int clip, int field) {
    return clips.get(ValueLayout.JAVA_FLOAT, (long) clip * CLIP_STRIDE + field);
  }
}
