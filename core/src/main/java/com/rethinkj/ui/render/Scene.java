package com.rethinkj.ui.render;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Text;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextRendering;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * What to draw, as a flat list of instances in draw order and a table of clips. Both live in
 * native memory in a fixed layout, so the same bytes can be uploaded to the GPU; every renderer
 * reads this format. All numbers are 32 bits wide, in the byte order of the machine, and all
 * positions are window coordinates in logical pixels, x to the right and y down.
 *
 * <p>An instance is {@link #STRIDE} bytes, sixteen 4-byte slots, and is one of two kinds, told
 * apart by the u32 at {@link #KIND}: a rounded rectangle ({@link #RECT}, 0) or a glyph ({@link
 * #GLYPH}, 1). Both kinds share one ordered list, so boxes and text overlap in the order of the
 * tree. The byte offsets are the constants below. A rounded rectangle with a fill and a border:
 *
 * <pre>
 *  0  x            f32   left edge of the rectangle
 *  4  y            f32   top edge
 *  8  width        f32
 * 12  height       f32
 * 16  radius       f32   corner radius; renderers clamp it to half the shorter side
 * 20  borderWidth  f32   border inside the rectangle, 0 for none
 * 24  clip         u32   index into the clip table, 0 for not clipped (an integer, not a float)
 * 28  kind         u32   0, RECT
 * 32  fill         f32 x 4   r, g, b, a
 * 48  border       f32 x 4   r, g, b, a
 * </pre>
 *
 * <p>As a WGSL storage buffer that is {@code array<Instance>} with {@code struct Instance { rect:
 * vec4f, radius: f32, border_width: f32, clip: u32, kind: u32, fill: vec4f, border: vec4f }}.
 *
 * <p>A glyph: a bitmap of coverage in an atlas page, drawn one texel to one physical pixel.
 *
 * <pre>
 *  0  x            f32   left edge of the bitmap on screen; x * scale is a whole number
 *  4  y            f32   top edge; y * scale is a whole number
 *  8  width        f32   the bitmap's width in texels / scale
 * 12  height       f32   its height in texels / scale
 * 16  atlas        u32   the bitmap's top left texel in its page: x | y &lt;&lt; 16
 * 20  atlasSize    u32   the bitmap's size in texels: width | height &lt;&lt; 16
 * 24  clip         u32   as for a rectangle
 * 28  kind         u32   1, GLYPH
 * 32  colour       f32 x 4   r, g, b, a of the text
 * 48  page         u32   index into {@link #pages}
 * 52  lightShare   f32   0 to 1, how the coverage is corrected, see below
 * 56  darkGamma    f32   the exponent for text darker than its background, 1 or more
 * 60  lightGamma   f32   the exponent for text lighter than its background, 1 or more
 * </pre>
 *
 * <p>As WGSL: {@code struct Glyph { rect: vec4f, atlas: u32, atlas_size: u32, clip: u32, kind:
 * u32, colour: vec4f, page: u32, light_share: f32, dark_gamma: f32, light_gamma: f32 }}, also 64
 * bytes; one shader can read both kinds from one buffer as {@code Instance} and {@code bitcast} the
 * slots that differ. {@code scale} is the physical pixels per logical pixel the renderer draws at.
 * The text was shaped and placed for it, so {@code x * scale} and {@code y * scale} round to the
 * physical pixel of the bitmap's top left texel, and the size in texels equals the size times the
 * scale: texel {@code (atlas.x + i, atlas.y + j)} covers physical pixel {@code (round(x * scale) +
 * i, round(y * scale) + j)}. It is read as it is, never filtered or resampled, and every texel
 * outside the bitmap but next to it is 0.
 *
 * <p>A texel holds coverage {@code a} from 0 to 1 ({@code r8unorm}). Text darker than its
 * background is thickened and lighter text thinned, as {@link TextRendering} explains; {@code
 * lightShare} says which, 0 for darker, 1 for lighter, and between for a blend of the two when the
 * background is not known:
 *
 * <pre>
 * dark     = 1 - pow(1 - a, darkGamma)
 * light    = pow(a, lightGamma)
 * coverage = mix(dark, light, lightShare) * clip coverage
 * output   = colour * coverage, blended as a rectangle's fill
 * </pre>
 *
 * <p>Colours are linear and premultiplied by alpha, the opacity of the element and its ancestors
 * included. Blending happens in linear light, as in rune: that is what an sRGB render target does
 * on the GPU, and it keeps translucent edges from darkening. A pixel becomes {@code source + target
 * * (1 - source.a)}.
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
 * <p>A renderer computes how much of a pixel a rectangle covers, from 0 to 1, as the signed
 * distance from the pixel's centre to the rectangle turned into a ramp one physical pixel wide
 * across the edge, and multiplies that by the same coverage of its clip's rounded rectangle; see
 * {@link CpuRenderer}. So the edge of a clip is smoothed like any other.
 *
 * <p>An element whose overflow is not visible ({@link El#clip}, {@link El#scroll}) clips its
 * descendants, not itself, and a text element its text. When clips are nested, an entry holds the
 * intersection of the element's border box with the clip of its ancestors, so one entry per
 * instance is enough. Instances that lie entirely outside their clip are left out.
 *
 * <p>A text element's glyphs come after its own fill and border. Its content box is snapped to
 * the physical pixel grid before the glyphs are placed: vertically to whole pixels, horizontally
 * to the grid of the glyph positions, whole pixels below scale 1.5 and quarter pixels from there
 * (see {@link com.rethinkj.ui.text.Font#shape}). So text in a box at a fractional position, moving
 * or scrolled, is drawn from the same bitmaps as at a whole one.
 *
 * <p>Which correction a glyph gets depends on the background behind its text: the colour set
 * with {@link El#textBackground}, or else the fills of the text element and its ancestors, each
 * over the ones before, as far as they reach an opaque one. With the background known, text with
 * a higher luminance than it is light, otherwise dark. Without it, the text colour decides, biased
 * towards thickening: dark up to a CIE lightness L* of 60, light from 80, and a blend between.
 *
 * <p>A text field ({@link El#editable}) draws its text moved left by its {@link Text#scroll} and
 * clipped to its content box across and to its padding box from top to bottom, without rounded
 * corners. Its selection is drawn as rectangles before the glyphs, and the line under a
 * composition and the caret after them, all ordinary rectangles with the field's clip and their
 * edges on whole physical pixels.
 *
 * <p>The thumbs of a scroll area's bars ({@link El#scrollbars}) are rounded rectangles too, drawn
 * after the area's children, where {@link El#horizontalBar} and {@link El#verticalBar} put them,
 * with the area's clip.
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
  public static final int KIND = 28;
  public static final int FILL = 32;
  public static final int BORDER = 48;

  /** The {@link #KIND} of a rounded rectangle. */
  public static final int RECT = 0;
  /** The {@link #KIND} of a glyph. */
  public static final int GLYPH = 1;

  // The slots of a glyph that differ from a rectangle's.
  public static final int ATLAS = 16;
  public static final int ATLAS_SIZE = 20;
  public static final int COLOUR = 32;
  public static final int PAGE = 48;
  public static final int LIGHT_SHARE = 52;
  public static final int DARK_GAMMA = 56;
  public static final int LIGHT_GAMMA = 60;

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
  private final Atlas atlas;
  private final TextRendering rendering;

  /** An empty scene, for {@link #rect}; without text, so with an atlas of its own. */
  public Scene() {
    this(new Atlas(), TextRendering.platform());
  }

  private Scene(Atlas atlas, TextRendering rendering) {
    this.atlas = atlas;
    this.rendering = rendering;
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
   * The scene of a tree with a focus ring around one of its elements, with glyphs in an atlas of
   * its own and the platform's {@link TextRendering}.
   *
   * @param ring the element with the ring, or null for none
   * @param ringColour the colour of the ring as 0xAARRGGBB
   */
  public static Scene of(El root, El ring, int ringColour) {
    return of(root, ring, ringColour, new Atlas(), TextRendering.platform());
  }

  /**
   * The scene of a tree, with its glyphs in {@code atlas}, rasterised as {@code rendering} says.
   * This starts a {@link Atlas#nextFrame frame} of the atlas, so the glyphs of an older scene of
   * the same atlas may be gone.
   *
   * @param ring the element with the ring, or null for none
   * @param ringColour the colour of the ring as 0xAARRGGBB
   */
  public static Scene of(
      El root, El ring, int ringColour, Atlas atlas, TextRendering rendering) {
    Scene scene = new Scene(atlas, rendering);
    scene.ring = ring;
    scene.ringColour = ringColour;
    atlas.nextFrame();
    scene.add(root, 0, 0, 1, 0, Float.NaN);
    return scene;
  }

  /** @param behind the luminance of what lies behind the element, NaN where it is not known */
  private void add(
      El el, float parentX, float parentY, float parentOpacity, int clip, float behind) {
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
      float content = over(el.fill(), opacity, behind);
      if (el.text() != null) {
        text(el, x, y, opacity, inner, content);
      }
      for (El child : el.children()) {
        add(child, x, y, opacity, inner, content);
      }
      if (el.scrollbars() != null) {
        thumb(el.horizontalBar(), el.scrollbars().radius(), opacity, inner);
        thumb(el.verticalBar(), el.scrollbars().radius(), opacity, inner);
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

  /** The thumb of a scroll area's bar, if it has one, in the area's clip. */
  private void thumb(Scrollbars.Bar bar, float radius, float opacity, int clip) {
    if (bar == null || bar.color() >>> 24 == 0) {
      return;
    }
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    if (bar.thumbX() < clipX + clipGet(clip, WIDTH) && bar.thumbX() + bar.thumbWidth() > clipX
        && bar.thumbY() < clipY + clipGet(clip, HEIGHT) && bar.thumbY() + bar.thumbHeight() > clipY) {
      rect(bar.thumbX(), bar.thumbY(), bar.thumbWidth(), bar.thumbHeight(), radius, 0, bar.color(),
          0, opacity, clip);
    }
  }

  /** The luminance behind an element's content: its fill over what lies behind the element. */
  private static float over(int fill, float opacity, float behind) {
    float alpha = (fill >>> 24) / 255f * opacity;
    if (alpha == 0) {
      return behind;
    }
    // shortcut: only the fill of the element itself and its ancestors count; text over an earlier
    // sibling, an absolutely placed box, a border or outside a rounded corner gets the background
    // further up, or none. Paint the scene into a coarse grid of colours when that shows.
    float luminance = luminance(fill);
    return alpha == 1 ? luminance : luminance * alpha + behind * (1 - alpha);
  }

  /** Relative luminance of an sRGB colour, ignoring its alpha: 0 for black, 1 for white. */
  private static float luminance(int argb) {
    return 0.2126f * SRGB_TO_LINEAR[argb >> 16 & 0xFF]
        + 0.7152f * SRGB_TO_LINEAR[argb >> 8 & 0xFF]
        + 0.0722f * SRGB_TO_LINEAR[argb & 0xFF];
  }

  /**
   * How far text of this colour is corrected as light text: by its luminance against the
   * background's where that is known, else by its CIE lightness, biased towards thickening.
   */
  private static float lightShare(int colour, float behind) {
    float luminance = luminance(colour);
    if (!Float.isNaN(behind)) {
      return luminance > behind ? 1 : 0;
    }
    double lightness = luminance > 216 / 24389.0
        ? 116 * StrictMath.cbrt(luminance) - 16
        : luminance * 24389 / 27.0;
    return (float) Math.clamp((lightness - 60) / 20, 0, 1);
  }

  /**
   * Appends the glyphs of a text element, with the top left of its content box snapped to the grid
   * of its glyph positions.
   */
  private void text(El el, float x, float y, float opacity, int clip, float behind) {
    Text text = el.text();
    int colour = text.isField() && text.placeholderShown() ? text.placeholderColor() : text.color();
    if (colour >>> 24 == 0 && !text.isField()) {
      return;
    }
    double scale = text.scale();
    Style s = el.style();
    // shortcut: padding and border in pixels only, not in percent, as for scrolling; take the
    // edges layout resolved when percentages need it.
    double left = (x + px(s.paddingLeft) + px(s.borderLeft)) * scale;
    double top = (y + px(s.paddingTop) + px(s.borderTop)) * scale;
    double grid = scale >= 1.5 ? 4 : 1;
    left = Math.round(left * grid) / grid;
    top = Math.round(top);
    float share = lightShare(colour,
        text.background() != 0 ? luminance(text.background()) : behind);
    double scroll = 0;
    if (text.isField()) {
      clip = fieldClip(el, x, y, clip);
      if (clip < 0) {
        return; // clipped away
      }
      scroll = text.scroll();
      for (Text.Box box : text.selection()) {
        box(box, left, top, scale, text.selectionColor(), opacity, clip);
      }
    }
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    float clipRight = clipX + clipGet(clip, WIDTH);
    float clipBottom = clipY + clipGet(clip, HEIGHT);
    for (Text.Line line : colour >>> 24 == 0 ? List.<Text.Line>of() : text.lines()) {
      GlyphRun run = line.glyphs();
      for (int i = 0; i < run.count(); i++) {
        double origin = left + run.x(i) - scroll; // on the quarter grid, or whole below 1.5
        double whole = Math.floor(origin);
        Atlas.Slot slot = atlas.glyph(run.font(i), run.pixelSize(), rendering.hinting(),
            run.glyph(i), (int) ((origin - whole) * 4));
        if (slot.isEmpty()) {
          continue;
        }
        float glyphX = (float) ((whole + slot.left()) / scale);
        float glyphY =
            (float) ((Math.round(top + line.baseline() + run.y(i)) - slot.top()) / scale);
        float width = (float) (slot.width() / scale);
        float height = (float) (slot.height() / scale);
        if (glyphX < clipRight && glyphX + width > clipX && glyphY < clipBottom
            && glyphY + height > clipY) {
          glyph(glyphX, glyphY, width, height, slot, colour, opacity, clip, share);
        }
      }
    }
    if (text.composition() != null) {
      box(text.composition(), left, top, scale, text.color(), opacity, clip);
    }
    if (text.caret() != null) {
      box(text.caret(), left, top, scale, text.caretColor(), opacity, clip);
    }
  }

  /**
   * The clip of a field's text: its content box across, its padding box from top to bottom,
   * within {@code clip}; -1 when nothing is left of it.
   */
  private int fieldClip(El el, float x, float y, int clip) {
    Style s = el.style();
    float left = Math.max(x + px(s.borderLeft) + px(s.paddingLeft), clipGet(clip, X));
    float top = Math.max(y + px(s.borderTop), clipGet(clip, Y));
    float right = Math.min(x + el.width() - px(s.borderRight) - px(s.paddingRight),
        clipGet(clip, X) + clipGet(clip, WIDTH));
    float bottom = Math.min(y + el.height() - px(s.borderBottom),
        clipGet(clip, Y) + clipGet(clip, HEIGHT));
    return right > left && bottom > top ? clip(left, top, right - left, bottom - top, 0) : -1;
  }

  /**
   * A rectangle of a field, in physical pixels from the content box at {@code left, top}, with its
   * edges rounded to whole physical pixels; left out when its clip leaves nothing of it.
   */
  private void box(Text.Box box, double left, double top, double scale, int argb, float opacity,
      int clip) {
    if (argb >>> 24 == 0 || !(box.width() > 0 && box.height() > 0)) {
      return;
    }
    double x0 = Math.round(left + box.x());
    double y0 = Math.round(top + box.y());
    double x1 = Math.max(Math.round(left + box.x() + box.width()), x0 + 1);
    double y1 = Math.max(Math.round(top + box.y() + box.height()), y0 + 1);
    float x = (float) (x0 / scale);
    float y = (float) (y0 / scale);
    float width = (float) ((x1 - x0) / scale);
    float height = (float) ((y1 - y0) / scale);
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    if (x < clipX + clipGet(clip, WIDTH) && x + width > clipX && y < clipY + clipGet(clip, HEIGHT)
        && y + height > clipY) {
      rect(x, y, width, height, 0, 0, argb, 0, opacity, clip);
    }
  }

  private static float px(Len len) {
    return len.kind() == Len.Kind.PX ? len.value() : 0;
  }

  private void glyph(float x, float y, float width, float height, Atlas.Slot slot, int colour,
      float opacity, int clip, float lightShare) {
    long offset = next();
    put(offset + X, x);
    put(offset + Y, y);
    put(offset + WIDTH, width);
    put(offset + HEIGHT, height);
    data.set(ValueLayout.JAVA_INT, offset + ATLAS, slot.x() | slot.y() << 16);
    data.set(ValueLayout.JAVA_INT, offset + ATLAS_SIZE, slot.width() | slot.height() << 16);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    data.set(ValueLayout.JAVA_INT, offset + KIND, GLYPH);
    colour(offset + COLOUR, colour, opacity);
    data.set(ValueLayout.JAVA_INT, offset + PAGE, slot.page());
    put(offset + LIGHT_SHARE, lightShare);
    put(offset + DARK_GAMMA, rendering.darkGamma());
    put(offset + LIGHT_GAMMA, rendering.lightGamma());
    count++;
  }

  /** The offset of the next instance, with room for it. */
  private long next() {
    long offset = (long) count * STRIDE;
    if (offset == data.byteSize()) {
      data = grow(data);
    }
    return offset;
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
    long offset = next();
    put(offset + X, x);
    put(offset + Y, y);
    put(offset + WIDTH, width);
    put(offset + HEIGHT, height);
    put(offset + RADIUS, radius);
    put(offset + BORDER_WIDTH, borderWidth);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    data.set(ValueLayout.JAVA_INT, offset + KIND, RECT);
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

  /** An 8-bit sRGB value in linear light, as the colours of a scene are converted. */
  static float linear(int srgb) {
    return SRGB_TO_LINEAR[srgb];
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
   * not one of the u32 slots.
   */
  public float get(int instance, int field) {
    return data.get(ValueLayout.JAVA_FLOAT, (long) instance * STRIDE + field);
  }

  /**
   * One u32 slot of one instance: {@link #CLIP} or {@link #KIND}, and of a glyph {@link #ATLAS},
   * {@link #ATLAS_SIZE} or {@link #PAGE}.
   */
  public int getInt(int instance, int field) {
    return data.get(ValueLayout.JAVA_INT, (long) instance * STRIDE + field);
  }

  /** The atlas pages that glyphs refer to by their {@link #PAGE}; see {@link Atlas.Page}. */
  public List<Atlas.Page> pages() {
    return atlas.pages();
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
