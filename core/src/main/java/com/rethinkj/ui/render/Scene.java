package com.rethinkj.ui.render;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Shadow;
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
 * <p>An instance is {@link #STRIDE} bytes, twenty-four 4-byte slots, and is one of three kinds,
 * told apart by the u32 at {@link #KIND}: a rounded rectangle ({@link #RECT}, 0), a glyph ({@link
 * #GLYPH}, 1) or a shadow ({@link #SHADOW}, 2). All kinds share one ordered list, so boxes, text
 * and shadows overlap in the order of the tree. The byte offsets are the constants below. A
 * rounded rectangle with a fill and a border:
 *
 * <pre>
 *  0  x            f32   left edge of the rectangle
 *  4  y            f32   top edge
 *  8  width        f32
 * 12  height       f32
 * 16  radius       f32   the top left corner's radius; renderers clamp each corner's to half the
 *                        shorter side
 * 20  borderWidth  f32   the top side's border, inside the rectangle, 0 for none
 * 24  clip         u32   index into the clip table, 0 for not clipped (an integer, not a float)
 * 28  kind         u32   0, RECT
 * 32  fill         f32 x 4   r, g, b, a
 * 48  border       f32 x 4   r, g, b, a
 * 64  radii        f32 x 3   the top right, bottom right and bottom left corners' radii
 * 76  unused       f32   0
 * 80  borders      f32 x 3   the right, bottom and left sides' borders
 * 92  unused       f32   0
 * </pre>
 *
 * <p>As a WGSL storage buffer that is {@code array<Instance>} with {@code struct Instance { rect:
 * vec4f, radius: f32, border_width: f32, clip: u32, kind: u32, fill: vec4f, border: vec4f, radii:
 * vec4f, borders: vec4f }}. The instance was 64 bytes until corners and sides got values of their
 * own: six more floats do not fit into the sixteen slots, which a rectangle fills, and a table of
 * shapes beside the clips would cost a binding, an index and a second read per pixel to save 32
 * bytes per instance, a few hundred kilobytes in a frame of thousands. The first 64 bytes kept their
 * meaning, so a glyph has nothing after them.
 *
 * <p>A rectangle's coverage of a pixel is that of its rounded rectangle, the radius of the corner
 * on the pixel's side of the centre ({@code x > 0} right, {@code y > 0} below) standing for all
 * four. The fill shows inside the rectangle moved in by each side's border; a side of width 0 moves
 * it out by one physical pixel instead, so that the smoothed edge of a side without a border shows
 * no border colour. That inner rectangle's corners have a radius across of {@code max(0, r -
 * left or right)} and one from top to bottom of {@code max(0, r - top or bottom)}, with the
 * borders' widths, 0 for a side without one, as CSS has it, each at most half the inner
 * rectangle's side; a corner whose two differ is a quarter of an
 * ellipse, whose distance renderers take as {@code (k - 1) k / |q / r|} with {@code q} the point
 * from the ellipse's centre over its radii and {@code k = |q|}, the first-order distance. With
 * every border as wide, the inner rectangle is the outer one moved in by that width and its corners
 * are circles.
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
 * <p>A shadow ({@link El#shadow}): a rounded rectangle blurred with a Gaussian, as one layer of
 * CSS's {@code box-shadow}. The slots are a rectangle's, with the deviation in place of the border
 * width and no border:
 *
 * <pre>
 *  0  x            f32   left edge of the shape: the element's border box moved by the shadow's
 *                        offset and grown by its spread
 *  4  y            f32   top edge
 *  8  width        f32
 * 12  height       f32
 * 16  radius       f32   the top left corner's radius; renderers clamp each to half the shorter
 *                        side
 * 20  sigma        f32   the Gaussian's standard deviation, half the blur radius, 0 or more
 * 24  clip         u32   as for a rectangle
 * 28  kind         u32   2, SHADOW
 * 32  colour       f32 x 4   r, g, b, a
 * 48  unused       f32 x 4   0
 * 64  radii        f32 x 3   the other corners' radii, as for a rectangle
 * 76  unused       f32 x 5   0
 * </pre>
 *
 * <p>Its coverage of a pixel is the Gaussian around the pixel's centre integrated over the shape,
 * with the pixel's own extent added to the blur: a box one physical pixel wide has the variance
 * 1/12 of a Gaussian, so the deviation is {@code s = sqrt(sigma^2 + 1 / (12 scale^2))}, and a
 * shadow without blur gets an edge about as soft as a rectangle's. With {@code P(t) = (1 + erf(t /
 * (s sqrt 2))) / 2}, the Gaussian's mass below {@code t}, the mass of a band of half width {@code
 * a} around the shape's centre seen from {@code t} is {@code M(t, a) = P(t + a) - P(t - a)}. For a
 * pixel at {@code x, y} from the centre of a shape of half size {@code w, h} and radius {@code r},
 * the shape is two boxes crossed, of half sizes {@code w, h - r} and {@code w - r, h}, whose blur
 * is exact, and four quarter discs at their corners:
 *
 * <pre>
 * coverage = M(x, w) M(y, h - r) + M(x, w - r) M(y, h) - M(x, w - r) M(y, h - r)
 *          + Q(x - (w - r), y - (h - r)) + Q(-x - (w - r), y - (h - r))
 *          + Q(x - (w - r), -y - (h - r)) + Q(-x - (w - r), -y - (h - r)),  at most 1
 * </pre>
 *
 * <p>{@code Q(u, v)} is the blur of the quarter disc of radius {@code r} at the origin towards
 * {@code +x, +y}, from the point {@code u, v}: 0 when the point is more than {@code 3 s} from the
 * disc's square, else the part of its height from {@code max(v - 3 s, 0)} to {@code min(v + 3 s,
 * r)} cut into eight slices of equal height, each the Gaussian's exact mass across the slice times
 * its mass along the disc's extent at the slice's middle height {@code m}: {@code (P(top - v) -
 * P(bottom - v)) (P(sqrt(r^2 - m^2) - u) - P(-u))}. {@code erf} is Abramowitz and Stegun's 7.1.28,
 * {@code 1 - (1 + 0.0705230784 a + 0.0422820123 a^2 + 0.0092705272 a^3 + 0.0001520143 a^4 +
 * 0.0002765672 a^5 + 0.0000430638 a^6)^-16} for {@code a = min(|x|, 4)}, with the sign of {@code
 * x}: within 3e-7 of it, and with nothing but arithmetic, so that the renderers need no {@code
 * exp}. Square corners are exact; the slices put rounded ones within 0.006 of the true blur in the
 * cases {@code ShadowTest} measures. Renderers draw a shadow over its shape grown by {@code 3 s} on
 * every side and leave out the rest, at most 0.14 % of its colour.
 *
 * <p>A shape whose corners have different radii is the whole box with each rounded corner's square
 * of side {@code r} taken away and its quarter disc added back, in the order top left, top right,
 * bottom right, bottom left: {@code M(x, w) M(y, h) + sum over the corners with r > 0 of (Q(u, v) -
 * (P(w - x') - P(w - r - x')) (P(h - y') - P(h - r - y')))}, where {@code x', y'} is the point
 * mirrored into the corner's quadrant ({@code x' = -x} at the left, {@code y' = -y} at the top),
 * {@code u = x' - (w - r)} and {@code v = y' - (h - r)}; at most 1. That is the same blur as the
 * crossed boxes, which renderers keep for four equal radii.
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
 * 16  radii    f32 x 4   top left, top right, bottom right, bottom left, clamped like an
 *                        instance's
 * </pre>
 *
 * <p>As WGSL: {@code array<Clip>} with {@code struct Clip { rect: vec4f, radii: vec4f }}. The table always has an entry 0, which stands for no clip:
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
 * (see {@link com.rethinkj.ui.text.Font#shape}), whole pixels at every scale for a font such as
 * the icons that is placed on {@link com.rethinkj.ui.text.Font#wholePixels whole pixels}. So text
 * in a box at a fractional position, moving or scrolled, is drawn from the same bitmaps as at a
 * whole one.
 *
 * <p>Which correction a glyph gets depends on the background behind its text: the colour set
 * with {@link El#textBackground}, or else the fills of the text element and its ancestors, each
 * over the ones before, as far as they reach an opaque one. With the background known, text with
 * a higher luminance than it is light, otherwise dark. Without it, the text colour decides, biased
 * towards thickening: dark up to a CIE lightness L* of 60, light from 80, and a blend between.
 *
 * <p>A text field ({@link El#editable}) draws its text moved left by its {@link Text#scroll} and
 * clipped to its content box across and to its padding box from top to bottom, without rounded
 * corners; a {@link El#multiline multi-line} field, whose rows the text has already moved up by its
 * vertical scroll, to its content box both ways. Its selection is drawn as rectangles before the glyphs, and the line under a
 * composition and the caret after them, all ordinary rectangles with the field's clip and their
 * edges on whole physical pixels.
 *
 * <p>The thumbs of a scroll area's bars ({@link El#scrollbars}) are rounded rectangles too, drawn
 * after the area's children, where {@link El#horizontalBar} and {@link El#verticalBar} put them,
 * with the area's clip.
 *
 * <p>The focus ring is an ordinary instance too: a border {@link #RING} pixels wide just outside
 * the focused element's border box, or the element's {@link El#focusRingGap} beyond it, with each
 * rounded corner's radius grown by as much, drawn after the element and its children and clipped
 * like the element; the gap is the ring's transparent fill. Where the clips around the element or
 * the edges of the window (the root's box) would cut that ring, as around a grid that fills a
 * pane, the ring lies just inside the border box instead, with the element's radius, so that it
 * shows whole wherever the element does. It is still clipped: an element scrolled half out of its
 * area shows half a ring, never one over what lies beside the area.
 *
 * <p>An element's shadows come right before its own rectangle, the last of them first, so that the
 * first lies on top, as in CSS; with the element's clip and opacity.
 *
 * <p>A scene built for a scale ({@link #of(El, float)}) puts the boxes at rest on the physical
 * pixel grid: each edge of a border box at {@code round(x * scale)} physical pixels, halves up;
 * each side's border, the focus ring, its gap and sides of at most {@link #HAIRLINE} logical
 * pixels {@code max(1, floor(width * scale))} physical pixels wide; an edge on the content box of a parent with a
 * border on the inner edge of that border as drawn. Clips, shadows, thumbs (across their bar) and
 * the content boxes of text follow the snapped boxes. A box that a spring moves ({@link
 * El#isMoving}), and what is inside it, keeps its place between pixels. At scales 1 and 2 layout's
 * whole logical pixels are whole physical ones, and nothing moves.
 *
 * <p>Overlays ({@link El#overlay}) come after the whole tree, in the order of the root's {@link
 * El#shownOverlays}, each drawn as a tree of its own: from its position in the window, without
 * the clips or the opacity of its anchor's ancestors, with clips of its own. Before an overlay
 * with a {@link El#scrim}, a rectangle as large as clip entry 0 fills the window with it, at the
 * overlay's opacity. That is order and clip indices only, no other kind of instance.
 */
public final class Scene {

  /** Bytes per instance. */
  public static final int STRIDE = 24 * Float.BYTES;

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
  public static final int RADIUS_TOP_RIGHT = 64;
  public static final int RADIUS_BOTTOM_RIGHT = 68;
  public static final int RADIUS_BOTTOM_LEFT = 72;
  public static final int BORDER_RIGHT = 80;
  public static final int BORDER_BOTTOM = 84;
  public static final int BORDER_LEFT = 88;

  /** The {@link #KIND} of a rounded rectangle. */
  public static final int RECT = 0;
  /** The {@link #KIND} of a glyph. */
  public static final int GLYPH = 1;
  /** The {@link #KIND} of a shadow. */
  public static final int SHADOW = 2;

  /** The slot of a shadow that differs from a rectangle's: the Gaussian's deviation. */
  public static final int SIGMA = 20;

  // The slots of a glyph that differ from a rectangle's.
  public static final int ATLAS = 16;
  public static final int ATLAS_SIZE = 20;
  public static final int COLOUR = 32;
  public static final int PAGE = 48;
  public static final int LIGHT_SHARE = 52;
  public static final int DARK_GAMMA = 56;
  public static final int LIGHT_GAMMA = 60;

  /**
   * Bytes per entry of the clip table. Its fields are at {@link #X} to {@link #RADIUS}, and the
   * other corners' radii at {@code RADIUS + 4}, {@code + 8} and {@code + 12}.
   */
  public static final int CLIP_STRIDE = 8 * Float.BYTES;

  /** The width of the focus ring in logical pixels. */
  public static final float RING = 2;

  /**
   * The largest extent in logical pixels that a box's side is snapped as a hairline: as wide as
   * a border of that width (see {@link Scene}), not from edge to edge.
   */
  public static final float HAIRLINE = 2;

  private static final float NO_CLIP = 1e9f;
  private static final float[] NO_BORDER = new float[4];

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
  private float windowWidth = NO_CLIP; // the root's box, which the window shows
  private float windowHeight = NO_CLIP;
  private final Atlas atlas;
  private final TextRendering rendering;
  private float scale = Float.NaN; // physical pixels per logical one; NaN draws boxes unsnapped

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
   * The scene of a tree that layout has placed, for drawing at {@code scale}: its boxes at rest
   * are snapped to the physical pixel grid, see {@link Scene}.
   */
  public static Scene of(El root, float scale) {
    return of(root, null, 0, new Atlas(), TextRendering.platform(), scale);
  }

  /**
   * The scene of a tree with a focus ring around one of its elements, with glyphs in an atlas of
   * its own and the platform's {@link TextRendering}.
   *
   * @param ring the element with the ring, or null for none
   * @param ringColour the colour of the ring as 0xAARRGGBB, unless the element sets its own ({@link
   *     El#focusRing(int)})
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
   * @param ringColour the colour of the ring as 0xAARRGGBB, unless the element sets its own ({@link
   *     El#focusRing(int)})
   */
  public static Scene of(
      El root, El ring, int ringColour, Atlas atlas, TextRendering rendering) {
    return of(root, ring, ringColour, atlas, rendering, Float.NaN);
  }

  /**
   * The same, for drawing at {@code scale} physical pixels per logical pixel: the boxes at rest
   * are snapped to the physical pixel grid, as {@link Scene} says. Without a scale (NaN), every
   * box is drawn where layout put it.
   */
  public static Scene of(El root, El ring, int ringColour, Atlas atlas, TextRendering rendering,
      float scale) {
    Scene scene = new Scene(atlas, rendering);
    scene.scale = scale > 0 && Float.isFinite(scale) ? scale : Float.NaN;
    scene.ring = ring;
    scene.ringColour = ringColour;
    scene.windowWidth = root.x() + root.width();
    scene.windowHeight = root.y() + root.height();
    atlas.nextFrame();
    scene.add(root, 0, 0, 1, 0, Float.NaN, false, null);
    for (El overlay : root.shownOverlays()) {
      if (overlay.scrim() >>> 24 != 0 && overlay.opacity() > 0) {
        scene.rect(-NO_CLIP, -NO_CLIP, 2 * NO_CLIP, 2 * NO_CLIP, 0, 0, overlay.scrim(), 0,
            overlay.opacity(), 0);
      }
      scene.add(overlay, 0, 0, 1, 0, Float.NaN, false, null);
    }
    return scene;
  }

  /**
   * @param behind the luminance of what lies behind the element, NaN where it is not known
   * @param moving whether a spring moves an ancestor's box, so that this one is not snapped
   * @param box the parent's content box as layout put it and as drawn, null for a parent
   *     without a border, whose content box needs no mapping of its own
   */
  private void add(El el, float parentX, float parentY, float parentOpacity, int clip,
      float behind, boolean moving, ContentBox box) {
    // shortcut: opacity is multiplied into each instance, so overlapping descendants of a
    // translucent element show through each other; true group opacity needs an off-screen layer.
    float opacity = parentOpacity * el.opacity();
    if (!el.isPlaced() || opacity == 0) {
      return;
    }
    float x = parentX + el.x(); // where layout put it, which the children's positions add up from
    float y = parentY + el.y();
    boolean free = moving || el.isMoving();
    // The box as drawn: its edges on whole physical pixels, unless a spring moves it.
    float left = x;
    float top = y;
    float width = el.width();
    float height = el.height();
    if (!free) {
      left = edgeX(x, box);
      top = edgeY(y, box);
      width = width > 0 && width <= HAIRLINE ? stroke(width) : edgeX(x + width, box) - left;
      height = height > 0 && height <= HAIRLINE ? stroke(height) : edgeY(y + height, box) - top;
    }
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    float clipRight = clipX + clipGet(clip, WIDTH);
    float clipBottom = clipY + clipGet(clip, HEIGHT);
    Style s = el.style();
    float[] borders = {stroke(px(s.borderTop)), stroke(px(s.borderRight)),
        stroke(px(s.borderBottom)), stroke(px(s.borderLeft))};
    boolean hasBorder = (borders[0] > 0 || borders[1] > 0 || borders[2] > 0 || borders[3] > 0)
        && el.borderColor() >>> 24 != 0;
    float[] radii = el.radii();
    boolean inside =
        left < clipRight && left + width > clipX && top < clipBottom && top + height > clipY;
    List<Shadow> shadows = el.shadows();
    for (int i = shadows.size() - 1; i >= 0; i--) {
      shadow(shadows.get(i), left, top, width, height, radii, opacity, clip);
    }
    if ((el.fill() >>> 24 != 0 || hasBorder) && inside) {
      rect(left, top, width, height, radii, hasBorder ? borders : NO_BORDER, el.fill(),
          el.borderColor(), opacity, clip);
    }
    int inner = clip;
    if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
      float l = Math.max(left, clipX);
      float t = Math.max(top, clipY);
      float w = Math.min(left + width, clipRight) - l;
      float h = Math.min(top + height, clipBottom) - t;
      // shortcut: an entry has one radius, the innermost clip's, so where an inner clip sticks
      // out of a rounded outer one, the corners of the intersection take the inner radius instead
      // of following the outer curve; give each entry its parent's index when that shows.
      inner = w > 0 && h > 0 ? clip(l, t, w, h, radii) : -1;
    }
    if (inner >= 0) { // else everything below is clipped away
      float content = over(el.fill(), opacity, behind);
      if (el.text() != null) {
        text(el, left, top, opacity, inner, content);
      }
      ContentBox own = free ? null : contentBox(el, x, y, left, top, width, height);
      for (El child : el.children()) {
        add(child, x, y, opacity, inner, content, free, own);
      }
      if (el.scrollbars() != null) {
        thumb(el.horizontalBar(), false, el.scrollbars().radius(), opacity, inner, free);
        thumb(el.verticalBar(), true, el.scrollbars().radius(), opacity, inner, free);
      }
    }
    int ringed = el != ring ? 0 : el.focusRing() != null ? el.focusRing() : ringColour;
    if (ringed >>> 24 != 0) {
      float ringWidth = stroke(RING);
      float out = ringWidth + stroke(el.focusRingGap());
      float l = left - out;
      float t = top - out;
      float r = left + width + out;
      float b = top + height + out;
      float[] ringRadii = new float[4];
      for (int c = 0; c < 4; c++) {
        ringRadii[c] = radii[c] > 0 ? radii[c] + out : 0;
      }
      if (l < Math.max(clipX, 0) || t < Math.max(clipY, 0)
          || r > Math.min(clipRight, windowWidth) || b > Math.min(clipBottom, windowHeight)) {
        // A clip or the window would cut the ring outside, as a pane does around a grid in it.
        l = left;
        t = top;
        r = left + width;
        b = top + height;
        ringRadii = radii;
      }
      if (l < clipRight && r > clipX && t < clipBottom && b > clipY) {
        rect(l, t, r - l, b - t, ringRadii, new float[] {ringWidth, ringWidth, ringWidth,
            ringWidth}, 0, ringed, opacity, clip);
      }
    }
  }

  /**
   * An edge at logical coordinate {@code v} on the physical pixel boundary nearest to it, halves
   * up, so that two boxes that share an edge in layout share it on screen.
   */
  private float edge(float v) {
    return Float.isNaN(scale) ? v : (float) (Math.floor(v * (double) scale + 0.5) / scale);
  }

  /**
   * The content box of an element with a border, as layout put it and as drawn: within its box
   * snapped to whole pixels, inside its borders of {@link #stroke} widths. Children whose edges lie
   * on it are snapped onto it, so that what fills a bordered box meets its border with no gap.
   */
  private record ContentBox(float left, float top, float right, float bottom, float snappedLeft,
      float snappedTop, float snappedRight, float snappedBottom) {}

  private ContentBox contentBox(El el, float x, float y, float left, float top, float width,
      float height) {
    Style s = el.style();
    float l = px(s.borderLeft);
    float t = px(s.borderTop);
    float r = px(s.borderRight);
    float b = px(s.borderBottom);
    if (Float.isNaN(scale) || l == 0 && t == 0 && r == 0 && b == 0) {
      return null;
    }
    return new ContentBox(x + l, y + t, x + el.width() - r, y + el.height() - b, left + stroke(l),
        top + stroke(t), left + width - stroke(r), top + height - stroke(b));
  }

  /** {@link #edge} across, onto the parent's drawn content box where it lies on it. */
  private float edgeX(float v, ContentBox box) {
    if (box != null) {
      if (Math.abs(v - box.left) < 1e-3f) {
        return box.snappedLeft;
      }
      if (Math.abs(v - box.right) < 1e-3f) {
        return box.snappedRight;
      }
    }
    return edge(v);
  }

  /** {@link #edge} from top to bottom, onto the parent's drawn content box where it lies on it. */
  private float edgeY(float v, ContentBox box) {
    if (box != null) {
      if (Math.abs(v - box.top) < 1e-3f) {
        return box.snappedTop;
      }
      if (Math.abs(v - box.bottom) < 1e-3f) {
        return box.snappedBottom;
      }
    }
    return edge(v);
  }

  /**
   * The width of a border, the focus ring or a hairline: whole physical pixels, rounded down as
   * browsers round border widths, and at least one when it is more than 0.
   */
  private float stroke(float width) {
    if (Float.isNaN(scale) || !(width > 0)) {
      return width;
    }
    return (float) (Math.max(1, Math.floor(width * (double) scale + 1e-3)) / scale);
  }

  /**
   * Appends the shadow of the rounded rectangle at {@code x, y}, unless it is empty, transparent
   * or outside its clip. The spread grows the radius as CSS's does: less where the radius is
   * smaller than the spread, so that a square corner stays square.
   */
  private void shadow(Shadow shadow, float x, float y, float width, float height, float[] radii,
      float opacity, int clip) {
    float spread = shadow.spread();
    float w = width + 2 * spread;
    float h = height + 2 * spread;
    if (shadow.color() >>> 24 == 0 || !(w > 0 && h > 0)) {
      return;
    }
    // shortcut: the shadow shows under a translucent fill, where CSS cuts the element's own shape
    // out of it; carry the element's rectangle in the unused slots and take its coverage away in
    // both renderers when an app needs that.
    float[] grown = new float[4];
    for (int c = 0; c < 4; c++) {
      float r = Math.clamp(radii[c], 0, Math.min(width, height) / 2);
      float ratio = r / spread - 1;
      grown[c] = spread > r ? r + spread * (1 + ratio * ratio * ratio) : Math.max(r + spread, 0);
    }
    float left = x + shadow.x() - spread;
    float top = y + shadow.y() - spread;
    float sigma = shadow.blur() / 2;
    // What the renderers draw: 3 deviations beyond the shape, and the pixel's share of the
    // deviation and a pixel's margin, under 4 logical pixels at scales from 0.5.
    float reach = 3 * sigma + 4;
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    if (left - reach >= clipX + clipGet(clip, WIDTH) || left + w + reach <= clipX
        || top - reach >= clipY + clipGet(clip, HEIGHT) || top + h + reach <= clipY) {
      return;
    }
    long offset = next();
    put(offset + X, left);
    put(offset + Y, top);
    put(offset + WIDTH, w);
    put(offset + HEIGHT, h);
    radii(offset, grown);
    put(offset + SIGMA, sigma);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    data.set(ValueLayout.JAVA_INT, offset + KIND, SHADOW);
    colour(offset + FILL, shadow.color(), opacity);
    colour(offset + BORDER, 0, opacity);
    count++;
  }

  /**
   * The thumb of a scroll area's bar, if it has one, in the area's clip. Its sides along the bar
   * are snapped, its ends are not: where it is along the bar and how long it is measure the scroll
   * offset, and they move with it as smoothly as a box that a spring moves.
   */
  private void thumb(Scrollbars.Bar bar, boolean vertical, float radius, float opacity, int clip,
      boolean free) {
    if (bar == null || bar.color() >>> 24 == 0) {
      return;
    }
    float x = free || !vertical ? bar.thumbX() : edge(bar.thumbX());
    float y = free || vertical ? bar.thumbY() : edge(bar.thumbY());
    float width =
        free || !vertical ? bar.thumbWidth() : edge(bar.thumbX() + bar.thumbWidth()) - x;
    float height =
        free || vertical ? bar.thumbHeight() : edge(bar.thumbY() + bar.thumbHeight()) - y;
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    if (x < clipX + clipGet(clip, WIDTH) && x + width > clipX
        && y < clipY + clipGet(clip, HEIGHT) && y + height > clipY) {
      rect(x, y, width, height, radius, 0, bar.color(), 0, opacity, clip);
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
    if (colour >>> 24 == 0 && !text.isField() && !text.highlighted()) {
      return;
    }
    double scale = text.scale();
    Style s = el.style();
    // shortcut: padding and border in pixels only, not in percent, as for scrolling; take the
    // edges layout resolved when percentages need it.
    double left = (x + px(s.paddingLeft) + px(s.borderLeft)) * scale;
    double top = (y + px(s.paddingTop) + px(s.borderTop)) * scale;
    double grid = scale >= 1.5 && !text.font().wholePixels() ? 4 : 1;
    left = Math.round(left * grid) / grid;
    top = Math.round(top);
    float background = text.background() != 0 ? luminance(text.background()) : behind;
    float share = lightShare(colour, background);
    double scroll = 0;
    if (text.isField()) {
      clip = fieldClip(el, x, y, clip);
      if (clip < 0) {
        return; // clipped away
      }
      scroll = text.scroll();
      for (Text.Mark mark : text.marks()) {
        box(mark.box(), left, top, scale, mark.color(), opacity, clip);
      }
      for (Text.Box box : text.selection()) {
        box(box, left, top, scale, text.selectionColor(), opacity, clip);
      }
    }
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    float clipRight = clipX + clipGet(clip, WIDTH);
    float clipBottom = clipY + clipGet(clip, HEIGHT);
    boolean drawn = colour >>> 24 != 0 || text.highlighted();
    int shareOf = colour; // the colour `share` is for: a highlighter colours glyphs one by one
    for (Text.Line line : drawn ? text.lines() : List.<Text.Line>of()) {
      GlyphRun run = line.glyphs();
      int[] colours = text.placeholderShown() ? null : line.colors();
      for (int i = 0; i < run.count(); i++) {
        int glyphColour = colours == null ? colour : colours[i];
        if (glyphColour >>> 24 == 0) {
          continue;
        }
        if (glyphColour != shareOf) {
          shareOf = glyphColour;
          share = lightShare(glyphColour, background);
        }
        // On the quarter grid, or whole below 1.5, as the line's start is.
        double origin = left + line.x() + run.x(i) - scroll;
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
          glyph(glyphX, glyphY, width, height, slot, glyphColour, opacity, clip, share);
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
   * The clip of a field's text: its content box across, its padding box from top to bottom, or
   * its content box both ways for a multi-line field, whose rows scroll by under its padding,
   * within {@code clip}; -1 when nothing is left of it.
   */
  private int fieldClip(El el, float x, float y, int clip) {
    Style s = el.style();
    boolean rows = el.text().multiline();
    // Out to whole physical pixels, so that the clip cuts no glyph that the box holds.
    float left = Math.max(outward(x + px(s.borderLeft) + px(s.paddingLeft), -1), clipGet(clip, X));
    float top = Math.max(outward(y + px(s.borderTop) + (rows ? px(s.paddingTop) : 0), -1),
        clipGet(clip, Y));
    float right = Math.min(outward(x + el.width() - px(s.borderRight) - px(s.paddingRight), 1),
        clipGet(clip, X) + clipGet(clip, WIDTH));
    float bottom = Math.min(outward(y + el.height() - px(s.borderBottom)
        - (rows ? px(s.paddingBottom) : 0), 1), clipGet(clip, Y) + clipGet(clip, HEIGHT));
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

  /** {@code v} on the next physical pixel boundary in the direction of {@code sign}. */
  private float outward(float v, int sign) {
    if (Float.isNaN(scale)) {
      return v;
    }
    double p = v * (double) scale;
    return (float) ((sign < 0 ? Math.floor(p + 1e-3) : Math.ceil(p - 1e-3)) / scale);
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
    return clip(x, y, width, height, new float[] {radius, radius, radius, radius});
  }

  private int clip(float x, float y, float width, float height, float[] radii) {
    long offset = (long) clipCount * CLIP_STRIDE;
    if (offset == clips.byteSize()) {
      clips = grow(clips);
    }
    clips.set(ValueLayout.JAVA_FLOAT, offset + X, x);
    clips.set(ValueLayout.JAVA_FLOAT, offset + Y, y);
    clips.set(ValueLayout.JAVA_FLOAT, offset + WIDTH, width);
    clips.set(ValueLayout.JAVA_FLOAT, offset + HEIGHT, height);
    for (int c = 0; c < 4; c++) {
      clips.set(ValueLayout.JAVA_FLOAT, offset + RADIUS + 4 * c, radii[c]);
    }
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

  /**
   * Appends a rounded rectangle with a radius per corner and a border per side.
   *
   * @param radii top left, top right, bottom right, bottom left
   * @param borders top, right, bottom, left
   * @param fill the fill colour as 0xAARRGGBB
   * @param border the border colour as 0xAARRGGBB
   */
  public void rect(float x, float y, float width, float height, float[] radii, float[] borders,
      int fill, int border) {
    rect(x, y, width, height, radii, borders, fill, border, 1, 0);
  }

  private void rect(
      float x, float y, float width, float height, float radius, float borderWidth, int fill,
      int border, float opacity, int clip) {
    rect(x, y, width, height, new float[] {radius, radius, radius, radius},
        new float[] {borderWidth, borderWidth, borderWidth, borderWidth}, fill, border, opacity,
        clip);
  }

  private void rect(float x, float y, float width, float height, float[] radii, float[] borders,
      int fill, int border, float opacity, int clip) {
    long offset = next();
    put(offset + X, x);
    put(offset + Y, y);
    put(offset + WIDTH, width);
    put(offset + HEIGHT, height);
    radii(offset, radii);
    put(offset + BORDER_WIDTH, borders[0]);
    put(offset + BORDER_RIGHT, borders[1]);
    put(offset + BORDER_BOTTOM, borders[2]);
    put(offset + BORDER_LEFT, borders[3]);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    data.set(ValueLayout.JAVA_INT, offset + KIND, RECT);
    colour(offset + FILL, fill, opacity);
    colour(offset + BORDER, border, opacity);
    count++;
  }

  /** The radii of the corners: top left, top right, bottom right, bottom left. */
  private void radii(long offset, float[] radii) {
    put(offset + RADIUS, radii[0]);
    put(offset + RADIUS_TOP_RIGHT, radii[1]);
    put(offset + RADIUS_BOTTOM_RIGHT, radii[2]);
    put(offset + RADIUS_BOTTOM_LEFT, radii[3]);
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
