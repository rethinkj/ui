package com.rethinkj.ui.render;

import com.rethinkj.ui.Background;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Scrollbars;
import com.rethinkj.ui.Segment;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Text;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextRendering;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What to draw, as a flat list of instances in draw order and a table of clips. Both live in
 * native memory in a fixed layout, so the same bytes can be uploaded to the GPU; every renderer
 * reads this format. All numbers are 32 bits wide, in the byte order of the machine, and all
 * positions are window coordinates in logical pixels, x to the right and y down.
 *
 * <p>An instance is {@link #STRIDE} bytes, twenty-four 4-byte slots, and is one of nine kinds,
 * told apart by the u32 at {@link #KIND}: a rounded rectangle ({@link #RECT}, 0), a glyph ({@link
 * #GLYPH}, 1), a shadow ({@link #SHADOW}, 2), an image ({@link #IMAGE}, 3), a line segment ({@link
 * #LINE}, 4), a squiggly underline ({@link #SQUIGGLE}, 5), a color glyph ({@link #COLOR_GLYPH}, 6),
 * a rounded rectangle with a gradient ({@link #GRADIENT}, 7) or that gradient's stops ({@link
 * #STOPS}, 8). All kinds share one ordered list, so boxes, text, shadows, images, lines and
 * squiggles overlap in the order of the tree. The byte offsets are the constants below. A rounded rectangle with a fill and a border:
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
 * 76  arcStart     f32   where the arc starts, in turns clockwise from the top, from 0 to 1
 * 80  borders      f32 x 3   the right, bottom and left sides' borders
 * 92  arcGap       f32   the part of a turn the arc leaves out, 0 for none, 1 for all
 * </pre>
 *
 * <p>As a WGSL storage buffer that is {@code array<Instance>} with {@code struct Instance { rect:
 * vec4f, radius: f32, border_width: f32, clip: u32, kind: u32, fill: vec4f, border: vec4f, radii:
 * vec4f, borders: vec4f }}, the arc's start in {@code radii.w} and its gap in {@code borders.w}.
 * The instance was 64 bytes until corners and sides got values of their own: six more floats do
 * not fit into the sixteen slots, which a rectangle fills, and a table of shapes beside the clips
 * would cost a binding, an index and a second read per pixel to save 32 bytes per instance, a few
 * hundred kilobytes in a frame of thousands. The first 64 bytes kept their meaning, so a glyph has
 * nothing after them.
 *
 * <p>A rectangle's coverage of a pixel is that of its rounded rectangle, the radius of the corner
 * on the pixel's side of the center ({@code x > 0} right, {@code y > 0} below) standing for all
 * four. The fill shows inside the rectangle moved in by each side's border; a side of width 0 moves
 * it out by one physical pixel instead, so that the smoothed edge of a side without a border shows
 * no border color. That inner rectangle's corners have a radius across of {@code max(0, r -
 * left or right)} and one from top to bottom of {@code max(0, r - top or bottom)}, with the
 * borders' widths, 0 for a side without one, as CSS has it, each at most half the inner
 * rectangle's side; a corner whose two differ is a quarter of an
 * ellipse, whose distance renderers take as {@code (k - 1) k / |q / r|} with {@code q} the point
 * from the ellipse's center over its radii and {@code k = |q|}, the first-order distance. With
 * every border as wide, the inner rectangle is the outer one moved in by that width and its corners
 * are circles.
 *
 * <p>A rectangle with a gap above 0 ({@link El#arc}) shows only within an arc around its center:
 * its coverage is multiplied by the arc's, so a round one with a border is a ring cut off square at
 * both ends, and one with a fill a pie. A gap of 1 or more draws nothing. The arc has its bisector
 * at {@code b = (start + sweep / 2)} turns and half the angle {@code h = sweep / 2} turns, with
 * {@code sweep = 1 - gap}. For a pixel at {@code p} from the center, in logical pixels, {@code x =
 * |p.x cos(b) + p.y sin(b)|} is how far it lies to either side of the bisector and {@code y = p.x
 * sin(b) - p.y cos(b)} how far along it, {@code sin} and {@code cos} of turns. With {@code s =
 * sin(h)} and {@code c = max(cos(h), 0)}, for an arc of at most half a turn ({@code gap >= 0.5},
 * which every renderer decides on the stored gap, not on {@code cos(h)}, whose sign at half a turn
 * may round either way):
 *
 * <pre>
 * near     = -x c + y s   when x s + y c &gt;= 0, else -|p|   the edge on the pixel's side
 * far      =  x c + y s   when y c - x s &gt;= 0, else |p|    the other edge
 * edge(d)  = clamp(0.5 + d scale, 0, 1)
 * arc      = max(edge(near) + edge(far) - 1, 0)
 * </pre>
 *
 * <p>A wider arc is 1 less the coverage of its gap, which is narrower: the same with {@code -y} for
 * {@code y} and {@code max(-cos(h), 0)} for {@code c}. So each edge is smoothed as a straight one,
 * and an arc or a gap thinner than a pixel takes about as much of a pixel as it lies over, so that
 * a ring at 1 % shows a faint sliver and one at 99 % a faint gap, not a line of half coverage.
 * {@code sin} and {@code cos} are the angle brought into a quarter turn around 0 and then the terms
 * of their series up to {@code x^11} and {@code x^12}: arithmetic only, as for {@code erf} below,
 * so that every renderer computes the same edges far from the center. Near the center, where the
 * ramps of the edges meet and neither formula holds, the coverage is {@code sweep} up to half a
 * physical pixel away and the formula's from {@code sqrt(1/2)} of one on, as far as the four pixels
 * around a center on their corners lie: {@code sweep + (arc - sweep) clamp((2 |p| scale - 1) /
 * (sqrt(2) - 1), 0, 1)}. So it changes continuously with the pixel and with the sweep, also where
 * an arc becomes wider than half a turn, and a pie of an even size in physical pixels gets its
 * edges' coverage at its center, not a blend towards the sweep.
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
 * 32  color       f32 x 4   r, g, b, a of the text
 * 48  page         u32   index into {@link #pages}
 * 52  lightShare   f32   0 to 1, how the coverage is corrected, see below
 * 56  darkGamma    f32   the exponent for text darker than its background, 1 or more
 * 60  lightGamma   f32   the exponent for text lighter than its background, 1 or more
 * </pre>
 *
 * <p>As WGSL: {@code struct Glyph { rect: vec4f, atlas: u32, atlas_size: u32, clip: u32, kind:
 * u32, color: vec4f, page: u32, light_share: f32, dark_gamma: f32, light_gamma: f32 }}, also 64
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
 * output   = color * coverage, blended as a rectangle's fill
 * </pre>
 *
 * <p>A color glyph: a premultiplied RGBA bitmap in a color atlas page ({@link #colorPages}), drawn
 * one texel to one physical pixel in its own colors, with the alpha of the text color times the
 * element's opacity and no coverage correction. It shares the 64-byte layout of {@link #GLYPH}:
 *
 * <pre>
 *  0  x            f32   left edge of the bitmap on screen; x * scale is a whole number
 *  4  y            f32   top edge; y * scale is a whole number
 *  8  width        f32   the bitmap's width in texels / scale
 * 12  height       f32   its height in texels / scale
 * 16  atlas        u32   the bitmap's top left texel in its page: x | y &lt;&lt; 16
 * 20  atlasSize    u32   the bitmap's size in texels: width | height &lt;&lt; 16
 * 24  clip         u32   as for a rectangle
 * 28  kind         u32   6, COLOR_GLYPH
 * 32  color       f32 x 4   the alpha of the text color times the opacity, as o, o, o, o
 * 48  page         u32   index into {@link #colorPages}
 * 52  ...          as for a glyph, not read
 * </pre>
 *
 * <p>A texel is premultiplied linear color encoded to 8-bit sRGB and linear alpha ({@code
 * rgba8unorm}), as {@link Image} stores it, read as it is without filtering:
 *
 * <pre>
 * texel  = (linear(r), linear(g), linear(b), a)
 * output = texel * color * clip coverage, blended as a rectangle's fill
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
 * 32  color       f32 x 4   r, g, b, a
 * 48  unused       f32 x 4   0
 * 64  radii        f32 x 3   the other corners' radii, as for a rectangle
 * 76  unused       f32 x 5   0
 * </pre>
 *
 * <p>Its coverage of a pixel is the Gaussian around the pixel's center integrated over the shape,
 * with the pixel's own extent added to the blur: a box one physical pixel wide has the variance
 * 1/12 of a Gaussian, so the deviation is {@code s = sqrt(sigma^2 + 1 / (12 scale^2))}, and a
 * shadow without blur gets an edge about as soft as a rectangle's. With {@code P(t) = (1 + erf(t /
 * (s sqrt 2))) / 2}, the Gaussian's mass below {@code t}, the mass of a band of half width {@code
 * a} around the shape's center seen from {@code t} is {@code M(t, a) = P(t + a) - P(t - a)}. For a
 * pixel at {@code x, y} from the center of a shape of half size {@code w, h} and radius {@code r},
 * the shape is two boxes crossed, of half sizes {@code w, h - r} and {@code w - r, h}, whose blur
 * is exact, and four quarter disks at their corners:
 *
 * <pre>
 * coverage = M(x, w) M(y, h - r) + M(x, w - r) M(y, h) - M(x, w - r) M(y, h - r)
 *          + Q(x - (w - r), y - (h - r)) + Q(-x - (w - r), y - (h - r))
 *          + Q(x - (w - r), -y - (h - r)) + Q(-x - (w - r), -y - (h - r)),  at most 1
 * </pre>
 *
 * <p>{@code Q(u, v)} is the blur of the quarter disk of radius {@code r} at the origin towards
 * {@code +x, +y}, from the point {@code u, v}: 0 when the point is more than {@code 3 s} from the
 * disk's square, else the part of its height from {@code max(v - 3 s, 0)} to {@code min(v + 3 s,
 * r)} cut into eight slices of equal height, each the Gaussian's exact mass across the slice times
 * its mass along the disk's extent at the slice's middle height {@code m}: {@code (P(top - v) -
 * P(bottom - v)) (P(sqrt(r^2 - m^2) - u) - P(-u))}. {@code erf} is Abramowitz and Stegun's 7.1.28,
 * {@code 1 - (1 + 0.0705230784 a + 0.0422820123 a^2 + 0.0092705272 a^3 + 0.0001520143 a^4 +
 * 0.0002765672 a^5 + 0.0000430638 a^6)^-16} for {@code a = min(|x|, 4)}, with the sign of {@code
 * x}: within 3e-7 of it, and with nothing but arithmetic, so that the renderers need no {@code
 * exp}. Square corners are exact; the slices put rounded ones within 0.006 of the true blur in the
 * cases {@code ShadowTest} measures. Renderers draw a shadow over its shape grown by {@code 3 s} on
 * every side and leave out the rest, at most 0.14 % of its color.
 *
 * <p>A shape whose corners have different radii is the whole box with each rounded corner's square
 * of side {@code r} taken away and its quarter disk added back, in the order top left, top right,
 * bottom right, bottom left: {@code M(x, w) M(y, h) + sum over the corners with r > 0 of (Q(u, v) -
 * (P(w - x') - P(w - r - x')) (P(h - y') - P(h - r - y')))}, where {@code x', y'} is the point
 * mirrored into the corner's quadrant ({@code x' = -x} at the left, {@code y' = -y} at the top),
 * {@code u = x' - (w - r)} and {@code v = y' - (h - r)}; at most 1. That is the same blur as the
 * crossed boxes, which renderers keep for four equal radii.
 *
 * <p>An image ({@link El#image}): the texels of an {@link Image}, mapped onto a rectangle.
 *
 * <pre>
 *  0  x            f32   left edge of the rectangle the image's texels map onto
 *  4  y            f32   top edge
 *  8  width        f32
 * 12  height       f32
 * 16  size         u32   level 0's size in texels: width | height &lt;&lt; 16
 * 20  levels       u32   the number of levels, {@link Image#levels}
 * 24  clip         u32   as for a rectangle
 * 28  kind         u32   3, IMAGE
 * 32  color       f32 x 4   the opacity, o, o, o, o, that multiplies every texel
 * 48  image        u32   index into {@link #images}
 * 52  unused       f32 x 11  0
 * </pre>
 *
 * <p>As WGSL it is read as an {@code Instance} too, with the size and the levels in the slots of
 * a glyph's atlas and atlas size. A texel is premultiplied linear color, as {@link Image} stores
 * it: red, green and blue decoded from sRGB, alpha divided by 255. The image is drawn where the
 * rectangle and its clip are, with the coverage of the rectangle with square corners and of its
 * clip, as a rectangle's: {@code output = sample * color * coverage}, blended as a fill. A
 * rounded element gets a clip entry of its own for that, its content box with its corners, and so
 * does one whose image, {@link Image.Fit#COVER covering} it, reaches beyond its content box.
 *
 * <p>The sample at a pixel, with {@code (px, py)} its center in physical pixels, {@code s} the
 * scale, {@code (w0, h0)} level 0's size and {@code (W, H) = (width s, height s)} the rectangle's
 * size in physical pixels:
 *
 * <pre>
 * one to one, when |W - w0| &lt; 0.001, |H - h0| &lt; 0.001 and x s and y s lie within 0.001 of a
 * whole number: the texel (floor(px) - floor(x s + 0.5), floor(py) - floor(y s + 0.5)) of level
 * 0, read as it is, clamped into the level
 *
 * otherwise:
 * ratio    = (w0 / W, h0 / H)                       texels per physical pixel
 * (u, v)   = ((px - x s) ratio.x, (py - y s) ratio.y)  the point on level 0, in texels
 * lod      = log2(max(ratio.x, ratio.y))
 * sample   = bilinear(0)                               when lod &lt;= 0, magnified
 *          = bilinear(levels - 1)                      when floor(lod) &gt;= levels - 1
 *          = mix(bilinear(k), bilinear(k + 1), lod - k) with k = floor(lod) otherwise
 * bilinear(k): on level k of (wk, hk), the point (u wk / w0 - 0.5, v hk / h0 - 0.5), its four
 *          texels around it clamped into the level, mixed by the fractions across, then down
 * </pre>
 *
 * <p>That is trilinear filtering of levels the image computed itself (see {@link Image}), with
 * the same formula in every renderer: it is continuous in the point and in {@code lod}, so the
 * small differences between the CPU's doubles and the GPU's floats move a pixel by little. A
 * photo shown at a fraction of its size samples the levels near its texels' size, and so neither
 * flickers as it moves nor frays at its edges.
 *
 * <p>A line segment ({@link El#segments}): a capsule, the points within half its width of the
 * straight line between two points.
 *
 * <pre>
 *  0  x1           f32   one end
 *  4  y1           f32
 *  8  x2           f32   the other end
 * 12  y2           f32
 * 16  width        f32   more than 0
 * 20  unused       f32   0
 * 24  clip         u32   as for a rectangle
 * 28  kind         u32   4, LINE
 * 32  color       f32 x 4   r, g, b, a
 * 48  unused       f32 x 12  0
 * </pre>
 *
 * <p>As WGSL it is read as an {@code Instance}, its ends in {@code rect} and its width in the
 * slot of a rectangle's radius. For a pixel whose center is {@code p}, with {@code a} and {@code
 * b} the ends, {@code d} is its distance from the line between them and {@code coverage(t) =
 * clamp(0.5 - t scale, 0, 1)} a rectangle's ramp:
 *
 * <pre>
 * when |p - a| &lt;= |p - b|, from a:
 * t        = clamp(dot(p - a, b - a) / dot(b - a, b - a), 0, 1), or 0 when a = b
 * d        = |p - a - (b - a) t|
 * otherwise the same from b, so that a far end costs no precision near the other:
 * t        = clamp(dot(p - b, a - b) / dot(b - a, b - a), 0, 1), or 0 when a = b
 * d        = |p - b + (b - a) t|
 * coverage = (coverage(d - width / 2) - coverage(d + width / 2)) * clip coverage
 * output   = color * coverage, blended as a rectangle's fill
 * </pre>
 *
 * <p>That is the part of a pixel-wide window across the pixel's center that the line covers, so a
 * line thinner than a pixel takes about as much of it as it lies over, never a whole pixel at its
 * middle; from a pixel wide on, the second term is 0 and its edges are a rectangle's. Renderers
 * draw it over the box around its ends grown by half its width and a physical pixel.
 *
 * <p>A squiggly underline ({@link com.rethinkj.ui.Spans#squiggle}): a sine wave along {@code x}
 * between {@code x} and {@code x + width}, centered at {@code y}, antialiased by distance to the
 * curve.
 *
 * <pre>
 *  0  x            f32   left edge of the squiggle
 *  4  y            f32   vertical center of the wave
 *  8  width        f32   length of the squiggle, more than 0
 * 12  amplitude    f32   half the wave's height from trough to crest, 0 or more
 * 16  wavelength   f32   distance between crests, more than 0
 * 20  stroke       f32   thickness of the wave, more than 0
 * 24  clip         u32   as for a rectangle
 * 28  kind         u32   5, SQUIGGLE
 * 32  color       f32 x 4   r, g, b, a
 * 48  phase        f32   wave phase at x, in turns
 * 52  unused       f32 x 11  0
 * </pre>
 *
 * <p>As WGSL it is read as an {@code Instance}, its amplitude in {@code rect.w}, its wavelength
 * and stroke in {@code shape.xy} and its phase in {@code more.x}. For a pixel whose center is
 * {@code p}, with {@code (s, c)} the sine and cosine of {@code (p.x - x) / wavelength + phase}
 * turns (computed by the same series as an arc's) and {@code coverage(t) = clamp(0.5 - t scale, 0,
 * 1)}:
 *
 * <pre>
 * endCover = coverage(|p.x - (x + width / 2)| - width / 2)
 * waveY    = y + amplitude * s
 * slope    = 2 pi * amplitude / wavelength * c
 * d        = max(|p.y - waveY| / sqrt(1 + slope^2), |p.y - y| - amplitude)
 * coverage = (coverage(d - stroke / 2) - coverage(d + stroke / 2)) * endCover * clip coverage
 * output   = color * coverage, blended as a rectangle's fill
 * </pre>
 *
 * <p>The lower bound {@code |p.y - y| - amplitude} keeps the first-order distance valid outside
 * the wave's strip even for steep slopes, so coverage is 0 beyond {@code amplitude + stroke / 2}
 * and a physical pixel vertically and beyond {@code [x, x + width]} and a physical pixel
 * horizontally. Renderers draw it over that box.
 *
 * <p>An element's background ({@link El#background(Background)}) is the gradient of a rounded
 * rectangle, a {@link #GRADIENT}: one in the slots of a {@link #RECT}, drawn as one but for its
 * fill, which shows the gradient over the fill. Its {@link #STOPS} come right after it and are not
 * drawn by themselves; their positions are resolved on the element's padding box:
 *
 * <pre>
 *  0  x1           f32   the start (linear) or the center (radial), in logical pixels
 *  4  y1           f32
 *  8  x2           f32   the end (linear), or the horizontal radius (radial), more than 0
 * 12  y2           f32   the end (linear), or the vertical radius (radial), more than 0
 * 16  shape        u32   LINEAR, 0, or RADIAL, 1
 * 20  unused       u32   0
 * 24  clip         u32   0
 * 28  kind         u32   8, STOPS
 * 32  from        f32 x 4   r, g, b, a at the start or the center, as a fill
 * 48  to          f32 x 4   r, g, b, a at the end or beyond the radii
 * 64  unused      f32 x 8   0
 * </pre>
 *
 * <p>For the center {@code p} of a pixel, in logical pixels, the gradient's fill is:
 *
 * <pre>
 * linear: d = (x2 - x1, y2 - y1),
 *         t = clamp(dot(p - (x1, y1), d) / dot(d, d), 0, 1), or 0 when dot(d, d) &lt;= 0
 * radial: t = min(|((p.x - x1) / x2, (p.y - y1) / y2)|, 1)
 * s       = t * t * (3 - 2 * t)
 * g       = from + (to - from) * s
 * fill    = g + rectangle's fill * (1 - g.a)
 * </pre>
 *
 * <p>Colors are linear and premultiplied by alpha, the opacity of the element and its ancestors
 * included. Blending happens in linear light, as in rune: that is what an sRGB render target does
 * on the GPU, and it keeps translucent edges from darkening. A pixel becomes {@code source + target
 * * (1 - source.a)}.
 *
 * <p>The clip table is {@link #clips()}, {@link #CLIP_STRIDE} bytes per entry: a rounded rectangle
 * outside of which an instance that refers to it is not drawn, and the entry it lies in.
 *
 * <pre>
 *  0  x        f32
 *  4  y        f32
 *  8  width    f32   0 or more; 0 clips everything away
 * 12  height   f32
 * 16  radii    f32 x 4   top left, top right, bottom right, bottom left, clamped like an
 *                        instance's
 * 32  parent   u32   the entry whose coverage this one's is taken with, an earlier one, or 0
 * 36  unused   u32 x 3   0
 * </pre>
 *
 * <p>As WGSL: {@code array<Clip>} with {@code struct Clip { rect: vec4f, radii: vec4f, parent:
 * u32 }}, which WGSL pads to 48 bytes. The entry was 32 bytes, with no slot free, until it got its
 * parent. The table always has an entry 0, which stands for no clip: a rectangle from -1e9 to 1e9
 * on both axes with radius 0 and no parent, so that applying it changes nothing and a renderer may
 * skip it or not.
 *
 * <p>A renderer computes how much of a pixel a rectangle covers, from 0 to 1, as the signed
 * distance from the pixel's center to the rectangle turned into a ramp one physical pixel wide
 * across the edge, and multiplies that by the same coverage of its clip; see {@link CpuRenderer}.
 * So the edge of a clip is smoothed like any other. A clip's coverage is that of its own rounded
 * rectangle, and then, as long as an entry has a parent other than 0 that comes before it, the
 * smaller of that and the parent's own coverage: the coverage of the intersection, whose signed
 * distance is the larger of the two.
 *
 * <p>An element whose overflow is not visible ({@link El#clip}, {@link El#scroll}) clips its
 * descendants, not itself, and a text element its text. When clips are nested, an entry's
 * rectangle is the intersection of the element's border box with the rectangle of the clip of its
 * ancestors, so that one entry per instance is enough to find its pixels. The intersection of two
 * rounded rectangles is not a rounded rectangle, so where the rounded corners of a clip around it
 * reach into that rectangle, as a card's around the square list that fills it, the entry's parent
 * is that clip; where they do not, which is nearly always, it has none and costs what a single
 * clip does. A corner that a clip around it cuts off has no radius, and where the element's own
 * rounded corner still shows, an entry of its whole box comes first as the parent. Instances that
 * lie entirely outside their clip's rectangle are left out.
 *
 * <p>A text element's glyphs come after its own fill and border. Its content box is snapped to
 * the physical pixel grid before the glyphs are placed: vertically to whole pixels, horizontally
 * to the grid of the glyph positions, whole pixels below scale 1.5 and quarter pixels from there
 * (see {@link com.rethinkj.ui.text.Font#shape}), whole pixels at every scale for a font such as
 * the icons that is placed on {@link com.rethinkj.ui.text.Font#wholePixels whole pixels}. So text
 * in a box at a fractional position, moving or scrolled, is drawn from the same bitmaps as at a
 * whole one.
 *
 * <p>Which correction a glyph gets depends on the background behind its text: the color set
 * with {@link El#textBackground}, or else the fills of the text element and its ancestors, each
 * over the ones before, as far as they reach an opaque one. With the background known, text with
 * a higher luminance than it is light, otherwise dark. Without it, the text color decides, biased
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
 * <p>An element's line segments come after its rectangle and its image and before its text and
 * children, in their order, from its border box as drawn, so they move with its snapped edges;
 * with the clip it gives its children and its opacity. The ends are not snapped.
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
  public static final int ARC_START = 76;
  public static final int ARC_GAP = 92;

  /** The {@link #KIND} of a rounded rectangle. */
  public static final int RECT = 0;
  /** The {@link #KIND} of a glyph. */
  public static final int GLYPH = 1;
  /** The {@link #KIND} of a shadow. */
  public static final int SHADOW = 2;
  /** The {@link #KIND} of an image. */
  public static final int IMAGE = 3;
  /** The {@link #KIND} of a line segment. */
  public static final int LINE = 4;
  /** The {@link #KIND} of a squiggly underline. */
  public static final int SQUIGGLE = 5;
  /** The {@link #KIND} of a color glyph. */
  public static final int COLOR_GLYPH = 6;
  /**
   * The {@link #KIND} of a rounded rectangle whose fill shows a gradient over it, which the
   * {@link #STOPS} after it give.
   */
  public static final int GRADIENT = 7;
  /** The {@link #KIND} of the gradient of the {@link #GRADIENT} before it; drawn by itself never. */
  public static final int STOPS = 8;

  /** The slot of the {@link #STOPS} that says whether their gradient is linear or radial. */
  public static final int GRADIENT_SHAPE = 16;
  /** A linear gradient in {@link #GRADIENT_SHAPE}. */
  public static final int LINEAR = 0;
  /** An elliptical radial gradient in {@link #GRADIENT_SHAPE}. */
  public static final int RADIAL = 1;

  // The slots of a squiggle that differ from a rectangle's.
  public static final int AMPLITUDE = 12;
  public static final int WAVELENGTH = 16;
  public static final int STROKE = 20;
  public static final int PHASE = 48;

  // The slots of a line segment that differ from a rectangle's: its other end and its width.
  public static final int X2 = 8;
  public static final int Y2 = 12;
  public static final int LINE_WIDTH = 16;

  // The slots of an image that differ from a rectangle's.
  public static final int IMAGE_SIZE = 16;
  public static final int LEVELS = 20;
  public static final int IMAGE_INDEX = 48;

  /** The slot of a shadow that differs from a rectangle's: the Gaussian's deviation. */
  public static final int SIGMA = 20;

  // The slots of a glyph that differ from a rectangle's.
  public static final int ATLAS = 16;
  public static final int ATLAS_SIZE = 20;
  public static final int COLOR = 32;
  public static final int PAGE = 48;
  public static final int LIGHT_SHARE = 52;
  public static final int DARK_GAMMA = 56;
  public static final int LIGHT_GAMMA = 60;

  /**
   * Bytes per entry of the clip table. Its fields are at {@link #X} to {@link #RADIUS}, the other
   * corners' radii at {@code RADIUS + 4}, {@code + 8} and {@code + 12}, and the parent at {@link
   * #CLIP_PARENT}.
   */
  public static final int CLIP_STRIDE = 12 * Float.BYTES;

  /** The slot of a clip entry that holds the index of its parent, a u32. */
  public static final int CLIP_PARENT = 32;

  /** The width of the focus ring in logical pixels. */
  public static final float RING = 2;

  /**
   * The largest extent in logical pixels that a box's side is snapped as a hairline: as wide as
   * a border of that width (see {@link Scene}), not from edge to edge.
   */
  public static final float HAIRLINE = 2;

  private static final float NO_CLIP = 1e9f;
  // How far beyond the root a squiggle is drawn: within it a float holds a position to 1/500 px.
  private static final float FAR = 16384;
  private static final float[] NO_BORDER = new float[4];
  private static final float[] SQUARE = new float[4];

  private static final float[] SRGB_TO_LINEAR = new float[256];

  static {
    for (int i = 0; i < 256; i++) {
      SRGB_TO_LINEAR[i] = (float) Colors.linear(i / 255.0);
    }
  }

  private MemorySegment data = Arena.ofAuto().allocate(64L * STRIDE, Float.BYTES);
  private int count;
  private MemorySegment clips = Arena.ofAuto().allocate(8L * CLIP_STRIDE, Float.BYTES);
  private int clipCount;
  private El ring;
  private int ringColor;
  private float windowWidth = NO_CLIP; // the root's box, which the window shows
  private float windowHeight = NO_CLIP;
  private final Atlas atlas;
  private final TextRendering rendering;
  private float scale = Float.NaN; // physical pixels per logical one; NaN draws boxes unsnapped
  private final List<Image> images = new ArrayList<>();
  private final Map<Image, Integer> imageIndex = new IdentityHashMap<>();
  private long imageMemory = DEFAULT_IMAGE_MEMORY;

  /** The bytes of GPU memory a renderer keeps images in unless {@link #imageMemory} says. */
  public static final long DEFAULT_IMAGE_MEMORY = 256L << 20;

  /** An empty scene, for {@link #rect}; without text, so with an atlas of its own. */
  public Scene() {
    this(new Atlas(), TextRendering.platform());
    noClip();
  }

  private Scene(Atlas atlas, TextRendering rendering) {
    this.atlas = atlas;
    this.rendering = rendering;
  }

  private void noClip() {
    entry(-NO_CLIP, -NO_CLIP, 2 * NO_CLIP, 2 * NO_CLIP, SQUARE, 0);
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
   * @param ringColor the color of the ring as 0xAARRGGBB, unless the element sets its own ({@link
   *     El#focusRing(int)})
   */
  public static Scene of(El root, El ring, int ringColor) {
    return of(root, ring, ringColor, new Atlas(), TextRendering.platform());
  }

  /**
   * The scene of a tree, with its glyphs in {@code atlas}, rasterized as {@code rendering} says.
   * This starts a {@link Atlas#nextFrame frame} of the atlas, so the glyphs of an older scene of
   * the same atlas may be gone.
   *
   * @param ring the element with the ring, or null for none
   * @param ringColor the color of the ring as 0xAARRGGBB, unless the element sets its own ({@link
   *     El#focusRing(int)})
   */
  public static Scene of(El root, El ring, int ringColor, Atlas atlas, TextRendering rendering) {
    return of(root, ring, ringColor, atlas, rendering, Float.NaN);
  }

  /**
   * The same, for drawing at {@code scale} physical pixels per logical pixel: the boxes at rest
   * are snapped to the physical pixel grid, as {@link Scene} says. Without a scale (NaN), every
   * box is drawn where layout put it.
   */
  public static Scene of(
      El root, El ring, int ringColor, Atlas atlas, TextRendering rendering, float scale) {
    return new Scene(atlas, rendering).refill(root, ring, ringColor, scale);
  }

  /**
   * Builds the scene of {@code root} in place of what this scene held, in the buffers it already
   * has, with its atlas and {@link TextRendering}. Once those buffers are large enough, refilling
   * the scene for each frame allocates no native memory. This starts a {@link Atlas#nextFrame
   * frame} of the atlas and overwrites what {@link #data()}, {@link #clips()} and {@link
   * #images()} returned before; {@link #imageMemory()} stays as set.
   *
   * @param ring the element with the focus ring, or null for none
   * @param ringColor the color of the ring as 0xAARRGGBB, unless the element sets its own ({@link
   *     El#focusRing(int)})
   * @param scale physical pixels per logical pixel, or NaN to draw boxes unsnapped
   * @return this scene
   */
  public Scene refill(El root, El ring, int ringColor, float scale) {
    data.asSlice(0, (long) count * STRIDE).fill((byte) 0); // as zeroed as a new allocation
    clips.asSlice(0, (long) clipCount * CLIP_STRIDE).fill((byte) 0);
    count = 0;
    clipCount = 0;
    images.clear();
    imageIndex.clear();
    noClip();
    this.scale = scale > 0 && Float.isFinite(scale) ? scale : Float.NaN;
    this.ring = ring;
    this.ringColor = ringColor;
    windowWidth = root.x() + root.width();
    windowHeight = root.y() + root.height();
    atlas.nextFrame();
    add(root, 0, 0, 1, 0, Float.NaN, false, null);
    for (El overlay : root.shownOverlays()) {
      if (overlay.scrim() >>> 24 != 0 && overlay.opacity() > 0) {
        rect(
            -NO_CLIP,
            -NO_CLIP,
            2 * NO_CLIP,
            2 * NO_CLIP,
            0,
            0,
            overlay.scrim(),
            0,
            overlay.opacity(),
            0);
      }
      add(overlay, 0, 0, 1, 0, Float.NaN, false, null);
    }
    return this;
  }

  /**
   * @param behind the luminance of what lies behind the element, NaN where it is not known
   * @param moving whether a spring moves an ancestor's box, so that this one is not snapped
   * @param box the parent's content box as layout put it and as drawn, null for a parent
   *     without a border, whose content box needs no mapping of its own
   */
  private void add(
      El el,
      float parentX,
      float parentY,
      float parentOpacity,
      int clip,
      float behind,
      boolean moving,
      ContentBox box) {
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
    float[] borders = {
      stroke(el.border(Side.TOP)),
      stroke(el.border(Side.RIGHT)),
      stroke(el.border(Side.BOTTOM)),
      stroke(el.border(Side.LEFT))
    };
    boolean hasBorder = (borders[0] > 0 || borders[1] > 0 || borders[2] > 0 || borders[3] > 0)
        && el.borderColor() >>> 24 != 0;
    float[] radii = el.radii();
    boolean inside =
        left < clipRight && left + width > clipX && top < clipBottom && top + height > clipY;
    List<Shadow> shadows = el.shadows();
    for (int i = shadows.size() - 1; i >= 0; i--) {
      shadow(shadows.get(i), left, top, width, height, radii, opacity, clip);
    }
    int[] colors = el.borderColors();
    // A transparent side is no border, as one transparent color is, and all of them none at all.
    if (colors != null && (colors[0] | colors[1] | colors[2] | colors[3]) >>> 24 != 0 && inside) {
      // The whole rectangle once per side, each clipped to its part, so that every pixel is drawn
      // once: the top border's rows, the bottom border's rows and the left and right halves
      // between them, also of the corners. The cuts lie on physical pixels unless a spring moves
      // the element or the scene has no scale; shortcut: then a faint seam can show along them.
      float[] sides = borders.clone();
      for (int side = 0; side < 4; side++) {
        sides[side] = colors[side] >>> 24 != 0 ? sides[side] : 0;
      }
      float bottom = top + height;
      float split = Math.min(top + sides[0], bottom);
      float end = Math.max(bottom - sides[2], split);
      // Between the inner edges of the left and the right border, so that each stays whole.
      float middle =
          colors[1] == colors[3] ? left + width : left + (sides[3] + width - sides[1]) / 2;
      if (!free) {
        middle = edge(middle);
      }
      float[][] parts = {
        {left, top, left + width, split},
        {middle, split, left + width, end},
        {left, end, left + width, bottom},
        {left, split, middle, end}
      };
      for (int side = 0; side < 4; side++) {
        float[] part = parts[side];
        int own = clip(part[0], part[1], part[2], part[3], SQUARE, clip);
        if (own >= 0) {
          rect(left, top, width, height, radii, sides, el.fill(), colors[side], opacity, own);
          arc(el);
          stops(el, left, top, width, height, borders, opacity, free);
        }
      }
    } else if ((el.fill() >>> 24 != 0 || hasBorder || el.background() != null) && inside) {
      rect(
          left,
          top,
          width,
          height,
          radii,
          hasBorder ? borders : NO_BORDER,
          el.fill(),
          el.borderColor(),
          opacity,
          clip);
      arc(el);
      stops(el, left, top, width, height, borders, opacity, free);
    }
    if (el.image() != null && inside) {
      image(el, left, top, width, height, borders, radii, opacity, clip, free);
    }
    int inner = clip;
    if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
      inner = clip(left, top, left + width, top + height, radii, clip);
    }
    if (inner >= 0) { // else everything below is clipped away
      for (Segment segment : el.segments()) {
        segment(segment, left, top, opacity, inner);
      }
      // A fill cut to an arc lies behind none of the content, or behind a part not known here.
      float content = el.arcSweep() == 1 || el.fill() >>> 24 == 0
          ? over(el.fill(), opacity, behind)
          : el.arcSweep() == 0 ? behind : Float.NaN;
      if (el.text() != null) {
        for (El child : el.children()) {
          if (child.rangeBoxes() != null) {
            range(child, x, y, opacity, inner, clip, free);
          }
        }
        text(el, left, top, opacity, inner, content);
      }
      ContentBox own = free ? null : contentBox(el, x, y, left, top, width, height);
      Text fieldText = el.text() != null && el.text().isField() ? el.text() : null;
      int blockClip = -2;
      for (El child : el.children()) {
        if (child.rangeBoxes() == null) {
          int childClip = inner;
          if (fieldText != null && fieldText.clipsToContent(child)) {
            if (blockClip == -2) {
              blockClip = fieldClip(el, left, top, inner);
            }
            if (blockClip < 0) {
              continue;
            }
            childClip = blockClip;
          }
          add(child, x, y, opacity, childClip, content, free, own);
        }
      }
      if (fieldText != null && (!fieldText.blockSelection().isEmpty() || fieldText.hasHints())) {
        if (blockClip == -2) {
          blockClip = fieldClip(el, left, top, inner);
        }
        if (blockClip >= 0) {
          double scale = fieldText.scale();
          double boxLeft = (left + el.padding(Side.LEFT) + el.border(Side.LEFT)) * scale;
          double boxTop = (top + el.padding(Side.TOP) + el.border(Side.TOP)) * scale;
          double grid = scale >= 1.5 && !fieldText.font().wholePixels() ? 4 : 1;
          boxLeft = Math.round(boxLeft * grid) / grid;
          boxTop = Math.round(boxTop);
          for (Text.Box tint : fieldText.blockSelection()) {
            box(tint, boxLeft, boxTop, scale, fieldText.selectionColor(), opacity, blockClip);
          }
          if (fieldText.hasHints() && fieldText.caret() != null) {
            box(
                fieldText.caret(),
                boxLeft,
                boxTop,
                scale,
                fieldText.caretColor(),
                opacity,
                blockClip);
          }
        }
      }
      if (el.scrollbars() != null) {
        thumb(el.horizontalBar(), false, el.scrollbars().radius(), opacity, inner, free);
        ticks(el, opacity, inner, free);
        thumb(el.verticalBar(), true, el.scrollbars().radius(), opacity, inner, free);
      }
    }
    int ringed = el != ring ? 0 : el.focusRing() != null ? el.focusRing() : ringColor;
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
      if (l < Math.max(clipX, 0)
          || t < Math.max(clipY, 0)
          || r > Math.min(clipRight, windowWidth)
          || b > Math.min(clipBottom, windowHeight)) {
        // A clip or the window would cut the ring outside, as a pane does around a grid in it.
        l = left;
        t = top;
        r = left + width;
        b = top + height;
        ringRadii = radii;
      }
      if (l < clipRight && r > clipX && t < clipBottom && b > clipY) {
        rect(
            l,
            t,
            r - l,
            b - t,
            ringRadii,
            new float[] {ringWidth, ringWidth, ringWidth, ringWidth},
            0,
            ringed,
            opacity,
            clip);
      }
    }
  }

  /** Cuts the last instance to the element's {@link El#arc}, if it has one. */
  private void arc(El el) {
    if (el.arcSweep() < 1) {
      long last = (long) (count - 1) * STRIDE;
      put(last + ARC_START, el.arcStart() - (float) Math.floor(el.arcStart()));
      put(last + ARC_GAP, 1 - el.arcSweep());
    }
  }

  /**
   * A line of an element from its border box's top left corner at {@code left, top}, unless it is
   * transparent or lies outside its clip.
   */
  private void segment(Segment segment, float left, float top, float opacity, int clip) {
    float x1 = left + segment.x1();
    float y1 = top + segment.y1();
    float x2 = left + segment.x2();
    float y2 = top + segment.y2();
    // What the renderers draw: half the width and a pixel beyond the ends, under 2 logical
    // pixels at scales from 0.5.
    float reach = segment.width() / 2 + 2;
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    if (segment.color() >>> 24 == 0
        || Math.min(x1, x2) - reach >= clipX + clipGet(clip, WIDTH)
        || Math.max(x1, x2) + reach <= clipX
        || Math.min(y1, y2) - reach >= clipY + clipGet(clip, HEIGHT)
        || Math.max(y1, y2) + reach <= clipY) {
      return;
    }
    long offset = next();
    put(offset + X, x1);
    put(offset + Y, y1);
    put(offset + X2, x2);
    put(offset + Y2, y2);
    put(offset + LINE_WIDTH, segment.width());
    put(offset + LINE_WIDTH + 4, 0);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    data.set(ValueLayout.JAVA_INT, offset + KIND, LINE);
    color(offset + COLOR, segment.color(), opacity);
    for (int slot = COLOR + 16; slot < STRIDE; slot += 4) {
      put(offset + slot, 0);
    }
    count++;
  }

  /**
   * Turns the rectangle just added into a {@link #GRADIENT} with the element's {@link
   * El#background}, unless it has none, and adds its {@link #STOPS}: their positions resolved on
   * the padding box, snapped to the pixel grid unless a spring moves the element.
   */
  private void stops(
      El el,
      float left,
      float top,
      float width,
      float height,
      float[] borders,
      float opacity,
      boolean free) {
    float l = left + borders[3];
    float t = top + borders[0];
    float r = left + width - borders[1];
    float b = top + height - borders[2];
    if (!free) {
      l = edge(l);
      t = edge(t);
      r = edge(r);
      b = edge(b);
    }
    // Without a padding box there is nothing to resolve a percentage on, and no fill to show.
    if (el.background() == null || !(r > l && b > t)) {
      return;
    }
    float w = r - l;
    float h = b - t;
    data.set(ValueLayout.JAVA_INT, (long) (count - 1) * STRIDE + KIND, GRADIENT);
    long offset = next();
    switch (el.background()) {
      case Background.Linear linear -> {
        put(offset + X, l + resolve(linear.x1(), w));
        put(offset + Y, t + resolve(linear.y1(), h));
        put(offset + WIDTH, l + resolve(linear.x2(), w));
        put(offset + HEIGHT, t + resolve(linear.y2(), h));
        data.set(ValueLayout.JAVA_INT, offset + GRADIENT_SHAPE, LINEAR);
        color(offset + FILL, linear.from(), opacity);
        color(offset + BORDER, linear.to(), opacity);
      }
      case Background.Radial radial -> {
        put(offset + X, l + resolve(radial.x(), w));
        put(offset + Y, t + resolve(radial.y(), h));
        put(offset + WIDTH, resolve(radial.radiusX(), w));
        put(offset + HEIGHT, resolve(radial.radiusY(), h));
        data.set(ValueLayout.JAVA_INT, offset + GRADIENT_SHAPE, RADIAL);
        color(offset + FILL, radial.inner(), opacity);
        color(offset + BORDER, radial.outer(), opacity);
      }
    }
    data.set(ValueLayout.JAVA_INT, offset + GRADIENT_SHAPE + 4, 0);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, 0);
    data.set(ValueLayout.JAVA_INT, offset + KIND, STOPS);
    for (int slot = BORDER + 16; slot < STRIDE; slot += 4) {
      put(offset + slot, 0);
    }
    count++;
  }

  private static float resolve(Len len, float extent) {
    return len.kind() == Len.Kind.PERCENT ? len.value() * extent : len.value();
  }

  /**
   * An element's image in its content box: the rectangle that {@link El#fit} gives it, snapped to
   * the pixel grid unless a spring moves the element, within a clip of the content box with the
   * element's corners where the element is rounded or the image reaches beyond the box.
   */
  private void image(
      El el,
      float left,
      float top,
      float width,
      float height,
      float[] borders,
      float[] radii,
      float opacity,
      int clip,
      boolean free) {
    Image image = el.image();
    float insetLeft = borders[3] + el.padding(Side.LEFT);
    float insetTop = borders[0] + el.padding(Side.TOP);
    float insetRight = borders[1] + el.padding(Side.RIGHT);
    float insetBottom = borders[2] + el.padding(Side.BOTTOM);
    float l = left + insetLeft;
    float t = top + insetTop;
    float r = left + width - insetRight;
    float b = top + height - insetBottom;
    if (!free) {
      l = edge(l);
      t = edge(t);
      r = edge(r);
      b = edge(b);
    }
    if (!(r > l && b > t)) {
      return;
    }
    // The rectangle the texels map onto, by the fit.
    float x = l;
    float y = t;
    float w = r - l;
    float h = b - t;
    if (el.fit() != Image.Fit.FILL) {
      float k = el.fit() == Image.Fit.CONTAIN
          ? Math.min(w / image.width(), h / image.height())
          : Math.max(w / image.width(), h / image.height());
      float fitW = image.width() * k;
      float fitH = image.height() * k;
      x = l + (w - fitW) / 2;
      y = t + (h - fitH) / 2;
      w = fitW;
      h = fitH;
      if (!free) {
        w = edge(x + w) - edge(x);
        h = edge(y + h) - edge(y);
        x = edge(x);
        y = edge(y);
      }
    }
    boolean rounded = radii[0] > 0 || radii[1] > 0 || radii[2] > 0 || radii[3] > 0;
    boolean beyond = x < l || y < t || x + w > r || y + h > b;
    int own = clip;
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    float clipRight = clipX + clipGet(clip, WIDTH);
    float clipBottom = clipY + clipGet(clip, HEIGHT);
    if (rounded || beyond) {
      float[] inner = {
        innerRadius(radii[0], insetLeft, insetTop, Math.max(borders[3], borders[0])),
        innerRadius(radii[1], insetRight, insetTop, Math.max(borders[1], borders[0])),
        innerRadius(radii[2], insetRight, insetBottom, Math.max(borders[1], borders[2])),
        innerRadius(radii[3], insetLeft, insetBottom, Math.max(borders[3], borders[2]))
      };
      own = clip(l, t, r, b, inner, clip);
      if (own < 0) {
        return;
      }
      clipX = clipGet(own, X);
      clipY = clipGet(own, Y);
      clipRight = clipX + clipGet(own, WIDTH);
      clipBottom = clipY + clipGet(own, HEIGHT);
    }
    if (!(x < clipRight && x + w > clipX && y < clipBottom && y + h > clipY)) {
      return;
    }
    Integer index = imageIndex.get(image);
    if (index == null) {
      index = images.size();
      images.add(image);
      imageIndex.put(image, index);
    }
    long offset = next();
    put(offset + X, x);
    put(offset + Y, y);
    put(offset + WIDTH, w);
    put(offset + HEIGHT, h);
    data.set(ValueLayout.JAVA_INT, offset + IMAGE_SIZE, image.width() | image.height() << 16);
    data.set(ValueLayout.JAVA_INT, offset + LEVELS, image.levels());
    data.set(ValueLayout.JAVA_INT, offset + CLIP, own);
    data.set(ValueLayout.JAVA_INT, offset + KIND, IMAGE);
    color(offset + FILL, Colors.WHITE, opacity);
    data.set(ValueLayout.JAVA_INT, offset + IMAGE_INDEX, index);
    for (int slot = IMAGE_INDEX + 4; slot < STRIDE; slot += 4) {
      put(offset + slot, 0);
    }
    count++;
  }

  /**
   * An element over a {@link El#range} of its parent's text, before the glyphs: its fill on each
   * row of the range, within the text's clip, and its focus ring around each, within the clip of
   * the text element itself, so that a link at the edge of a paragraph keeps its whole ring. Its
   * children are not drawn.
   */
  private void range(
      El el,
      float parentX,
      float parentY,
      float parentOpacity,
      int clip,
      int ringClip,
      boolean moving) {
    float opacity = parentOpacity * el.opacity();
    if (!el.isPlaced() || opacity == 0) {
      return;
    }
    float x = parentX + el.x();
    float y = parentY + el.y();
    boolean free = moving || el.isMoving();
    int ringed = el != ring ? 0 : el.focusRing() != null ? el.focusRing() : ringColor;
    float ringWidth = stroke(RING);
    for (Text.Box row : el.rangeBoxes()) {
      float left = x + row.x();
      float top = y + row.y();
      float width = row.width();
      float height = row.height();
      if (!free) {
        width = edge(left + width) - edge(left);
        height = edge(top + height) - edge(top);
        left = edge(left);
        top = edge(top);
      }
      if (el.fill() >>> 24 != 0) {
        rect(left, top, width, height, el.radius(), 0, el.fill(), 0, opacity, clip);
      }
      if (ringed >>> 24 != 0) {
        rect(
            left - ringWidth,
            top - ringWidth,
            width + 2 * ringWidth,
            height + 2 * ringWidth,
            el.radius() > 0 ? el.radius() + ringWidth : 0,
            ringWidth,
            0,
            ringed,
            opacity,
            ringClip);
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
  private record ContentBox(
      float left,
      float top,
      float right,
      float bottom,
      float snappedLeft,
      float snappedTop,
      float snappedRight,
      float snappedBottom) {}

  private ContentBox contentBox(
      El el, float x, float y, float left, float top, float width, float height) {
    float l = el.border(Side.LEFT);
    float t = el.border(Side.TOP);
    float r = el.border(Side.RIGHT);
    float b = el.border(Side.BOTTOM);
    if (Float.isNaN(scale) || l == 0 && t == 0 && r == 0 && b == 0) {
      return null;
    }
    return new ContentBox(
        x + l,
        y + t,
        x + el.width() - r,
        y + el.height() - b,
        left + stroke(l),
        top + stroke(t),
        left + width - stroke(r),
        top + height - stroke(b));
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
  private void shadow(
      Shadow shadow,
      float x,
      float y,
      float width,
      float height,
      float[] radii,
      float opacity,
      int clip) {
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
    if (left - reach >= clipX + clipGet(clip, WIDTH)
        || left + w + reach <= clipX
        || top - reach >= clipY + clipGet(clip, HEIGHT)
        || top + h + reach <= clipY) {
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
    color(offset + FILL, shadow.color(), opacity);
    color(offset + BORDER, 0, opacity);
    count++;
  }

  /** The ticks of a field's marks along its vertical bar, under the thumb and snapped like it. */
  private void ticks(El el, float opacity, int clip, boolean free) {
    Scrollbars.Bar bar = el.verticalBar();
    if (bar == null) {
      return;
    }
    float x = free ? bar.trackX() : edge(bar.trackX());
    float width = free ? bar.trackWidth() : edge(bar.trackX() + bar.trackWidth()) - x;
    float clipY = clipGet(clip, Y);
    float clipBottom = clipY + clipGet(clip, HEIGHT);
    for (Scrollbars.Tick tick : el.verticalTicks()) {
      float y = free ? tick.y() : edge(tick.y());
      float height = free ? tick.height() : edge(tick.y() + tick.height()) - y;
      if (y < clipBottom && y + height > clipY) {
        rect(x, y, width, height, 0, 0, tick.color(), 0, opacity, clip);
      }
    }
  }

  /**
   * The thumb of a scroll area's bar, if it has one, in the area's clip. Its sides along the bar
   * are snapped, its ends are not: where it is along the bar and how long it is measure the scroll
   * offset, and they move with it as smoothly as a box that a spring moves.
   */
  private void thumb(
      Scrollbars.Bar bar, boolean vertical, float radius, float opacity, int clip, boolean free) {
    if (bar == null || bar.color() >>> 24 == 0) {
      return;
    }
    float x = free || !vertical ? bar.thumbX() : edge(bar.thumbX());
    float y = free || vertical ? bar.thumbY() : edge(bar.thumbY());
    float width = free || !vertical ? bar.thumbWidth() : edge(bar.thumbX() + bar.thumbWidth()) - x;
    float height =
        free || vertical ? bar.thumbHeight() : edge(bar.thumbY() + bar.thumbHeight()) - y;
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    if (x < clipX + clipGet(clip, WIDTH)
        && x + width > clipX
        && y < clipY + clipGet(clip, HEIGHT)
        && y + height > clipY) {
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
    // further up, or none. Paint the scene into a coarse grid of colors when that shows.
    float luminance = luminance(fill);
    return alpha == 1 ? luminance : luminance * alpha + behind * (1 - alpha);
  }

  /** Relative luminance of an sRGB color, ignoring its alpha: 0 for black, 1 for white. */
  private static float luminance(int argb) {
    return 0.2126f * SRGB_TO_LINEAR[argb >> 16 & 0xFF]
        + 0.7152f * SRGB_TO_LINEAR[argb >> 8 & 0xFF]
        + 0.0722f * SRGB_TO_LINEAR[argb & 0xFF];
  }

  /**
   * How far text of this color is corrected as light text: by its luminance against the
   * background's where that is known, else by its CIE lightness, biased towards thickening.
   */
  private static float lightShare(int color, float behind) {
    float luminance = luminance(color);
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
    int color = text.isField() && text.placeholderShown() ? text.placeholderColor() : text.color();
    if (color >>> 24 == 0 && !text.isField() && !text.highlighted()) {
      return;
    }
    double scale = text.scale();
    double left = (x + el.padding(Side.LEFT) + el.border(Side.LEFT)) * scale;
    double top = (y + el.padding(Side.TOP) + el.border(Side.TOP)) * scale;
    double grid = scale >= 1.5 && !text.font().wholePixels() ? 4 : 1;
    left = Math.round(left * grid) / grid;
    top = Math.round(top);
    float background = text.background() != 0 ? luminance(text.background()) : behind;
    float share = lightShare(color, background);
    double scroll = 0;
    if (text.isField()) {
      clip = fieldClip(el, x, y, clip);
      if (clip < 0) {
        return; // clipped away
      }
      scroll = text.scroll();
      double origin = (left - scroll) / scale;
      for (Text.Mark mark : text.marks()) {
        if (mark.squiggle()) {
          squiggle(mark.box(), left, top, origin, text.size(), scale, mark.color(), opacity, clip);
        } else {
          box(mark.box(), left, top, scale, mark.color(), opacity, clip);
        }
      }
      for (Text.Box box : text.selection()) {
        box(box, left, top, scale, text.selectionColor(), opacity, clip);
      }
    } else {
      for (Text.Mark mark : text.marks()) {
        box(mark.box(), left, top, scale, mark.color(), opacity, clip);
      }
    }
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    float clipRight = clipX + clipGet(clip, WIDTH);
    float clipBottom = clipY + clipGet(clip, HEIGHT);
    boolean drawn = color >>> 24 != 0 || text.highlighted();
    int shareOf = color; // the color `share` is for: a highlighter colors glyphs one by one
    for (Text.Line line : drawn ? text.lines() : List.<Text.Line>of()) {
      GlyphRun run = line.glyphs();
      int[] colors = text.placeholderShown() ? null : line.colors();
      for (int i = 0; i < run.count(); i++) {
        int glyphColor = colors == null ? color : colors[i];
        if (glyphColor >>> 24 == 0) {
          continue;
        }
        if (glyphColor != shareOf) {
          shareOf = glyphColor;
          share = lightShare(glyphColor, background);
        }
        // On the quarter grid, or whole below 1.5, as the line's start is.
        double origin = left + line.x() + run.x(i) - scroll;
        double whole = Math.floor(origin);
        Atlas.Slot slot =
            atlas.glyph(run.font(i), run.pixelSize(), rendering.hinting(), run.glyph(i), (int)
                ((origin - whole) * 4));
        if (slot.isEmpty()) {
          continue;
        }
        float glyphX = (float) ((whole + slot.left()) / scale);
        float glyphY =
            (float) ((Math.round(top + line.baseline() + run.y(i)) - slot.top()) / scale);
        float width = (float) (slot.width() / scale);
        float height = (float) (slot.height() / scale);
        if (glyphX < clipRight
            && glyphX + width > clipX
            && glyphY < clipBottom
            && glyphY + height > clipY) {
          glyph(glyphX, glyphY, width, height, slot, glyphColor, opacity, clip, share);
        }
      }
    }
    if (text.composition() != null) {
      box(text.composition(), left, top, scale, text.color(), opacity, clip);
    }
    if (text.caret() != null && !text.hasHints()) {
      box(text.caret(), left, top, scale, text.caretColor(), opacity, clip);
    }
  }

  /**
   * The clip of a field's text: its content box across, its padding box from top to bottom, or
   * its content box both ways for a multi-line field, whose rows scroll by under its padding,
   * within {@code clip}; -1 when nothing is left of it.
   */
  private int fieldClip(El el, float x, float y, int clip) {
    boolean rows = el.text().multiline();
    // Out to whole physical pixels, so that the clip cuts no glyph that the box holds.
    return clip(
        outward(x + el.border(Side.LEFT) + el.padding(Side.LEFT), -1),
        outward(y + el.border(Side.TOP) + (rows ? el.padding(Side.TOP) : 0), -1),
        outward(x + el.width() - el.border(Side.RIGHT) - el.padding(Side.RIGHT), 1),
        outward(y + el.height() - el.border(Side.BOTTOM) - (rows ? el.padding(Side.BOTTOM) : 0), 1),
        SQUARE,
        clip);
  }

  /**
   * A rectangle of a field, in physical pixels from the content box at {@code left, top}, with its
   * edges rounded to whole physical pixels; left out when its clip leaves nothing of it.
   */
  private void box(
      Text.Box box, double left, double top, double scale, int argb, float opacity, int clip) {
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
    if (x < clipX + clipGet(clip, WIDTH)
        && x + width > clipX
        && y < clipY + clipGet(clip, HEIGHT)
        && y + height > clipY) {
      rect(x, y, width, height, 0, 0, argb, 0, opacity, clip);
    }
  }

  /**
   * A squiggly underline of a field, in physical pixels from the content box at {@code left, top},
   * with its wave phase anchored to {@code origin} (the text's left edge in logical pixels) so
   * adjacent pieces join seamlessly, and kept inside the row that {@code box} reaches down to the
   * bottom of; left out when its clip leaves nothing of it.
   */
  private void squiggle(
      Text.Box box,
      double left,
      double top,
      double origin,
      float fontSize,
      double scale,
      int argb,
      float opacity,
      int clip) {
    if (argb >>> 24 == 0 || !(box.width() > 0)) {
      return;
    }
    double x0 = Math.round(left + box.x());
    double x1 = Math.max(Math.round(left + box.x() + box.width()), x0 + 1);
    float stroke = (float) Math.max(1.0 / scale, fontSize / 12.0);
    float amplitude = (float) Math.max(1.0 / scale, fontSize / 12.0);
    float wavelength = amplitude * 6;
    // On the underline, raised where the row below it is too short, so that a field's last row
    // does not clip its lower lobes.
    float mid = (float) Math.min(
        (Math.round(top + box.y()) + stroke * scale / 2) / scale,
        (top + box.y() + box.height()) / scale - amplitude - stroke / 2);
    // What the renderers draw: amplitude, half the stroke and a pixel beyond, under 2 logical
    // pixels at scales from 0.5. Cut to that around the clip, and to FAR beyond the root, so
    // that the GPU's floats measure the phase from near the pixels drawn, not from far out.
    float reach = 2;
    float clipX = clipGet(clip, X);
    float clipY = clipGet(clip, Y);
    double from = Math.max(x0 / scale, Math.max(clipX, -FAR) - reach);
    double to =
        Math.min(x1 / scale, Math.min(clipX + clipGet(clip, WIDTH), windowWidth + FAR) + reach);
    if (to <= from
        || mid - amplitude - stroke / 2 - reach >= clipY + clipGet(clip, HEIGHT)
        || mid + amplitude + stroke / 2 + reach <= clipY) {
      return;
    }
    float x = (float) from;
    float width = (float) (to - from);
    double turns = (x - origin) / wavelength;
    float phase = (float) (turns - Math.floor(turns));
    long offset = next();
    put(offset + X, x);
    put(offset + Y, mid);
    put(offset + WIDTH, width);
    put(offset + AMPLITUDE, amplitude);
    put(offset + WAVELENGTH, wavelength);
    put(offset + STROKE, stroke);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    data.set(ValueLayout.JAVA_INT, offset + KIND, SQUIGGLE);
    color(offset + COLOR, argb, opacity);
    put(offset + PHASE, phase);
    for (int slot = PHASE + 4; slot < STRIDE; slot += 4) {
      put(offset + slot, 0);
    }
    count++;
  }

  /** {@code v} on the next physical pixel boundary in the direction of {@code sign}. */
  private float outward(float v, int sign) {
    if (Float.isNaN(scale)) {
      return v;
    }
    double p = v * (double) scale;
    return (float) ((sign < 0 ? Math.floor(p + 1e-3) : Math.ceil(p - 1e-3)) / scale);
  }

  private void glyph(
      float x,
      float y,
      float width,
      float height,
      Atlas.Slot slot,
      int color,
      float opacity,
      int clip,
      float lightShare) {
    long offset = next();
    put(offset + X, x);
    put(offset + Y, y);
    put(offset + WIDTH, width);
    put(offset + HEIGHT, height);
    data.set(ValueLayout.JAVA_INT, offset + ATLAS, slot.x() | slot.y() << 16);
    data.set(ValueLayout.JAVA_INT, offset + ATLAS_SIZE, slot.width() | slot.height() << 16);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    data.set(ValueLayout.JAVA_INT, offset + KIND, slot.color() ? COLOR_GLYPH : GLYPH);
    // A color glyph keeps its own colors and takes only the alpha of the text color.
    color(offset + COLOR, slot.color() ? color | 0x00FFFFFF : color, opacity);
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

  /**
   * The radius of a circle that keeps a content corner with insets {@code a} and {@code b} inside
   * the border's inner corner: 0 once either inset clears the curve; {@code radius - max(a, b)}
   * while both insets reach past the wider border, since the circle of radius {@code radius -
   * border} lies inside that corner; else the longer half axis {@code radius - min(a, b)} of the
   * content corner's ellipse, which contains the ellipse.
   */
  private static float innerRadius(float radius, float a, float b, float border) {
    if (radius <= Math.max(a, b)) {
      return 0;
    }
    return Math.min(a, b) >= border ? radius - Math.max(a, b) : radius - Math.min(a, b);
  }

  /**
   * The clip of the rounded rectangle from {@code l, t} to {@code r, b} with these radii (top
   * left, top right, bottom right, bottom left) inside the clip {@code outer}, as an entry of the
   * table; -1 when nothing of it is left.
   *
   * <p>The entry's rectangle is the intersection of the two boxes. Where the rectangle lies
   * inside the outer box, the entry keeps its radii; a corner that the outer box cuts off gets
   * none, and where that leaves out some of a rounded corner of the rectangle, or the radii would
   * be clamped otherwise in the smaller box, an entry of the whole rectangle with its radii comes
   * first, as the entry's parent. The parent after that is the first clip out along {@code
   * outer}'s chain whose rounded corners reach into the box: the box lies inside every one of
   * them, so their straight edges cut nothing it does not cut, and the others add nothing. So an
   * entry needs no parent unless rounded corners around it cut it, and nesting costs nothing where
   * they do not.
   */
  private int clip(float l, float t, float r, float b, float[] radii, int outer) {
    float outerX = clipGet(outer, X);
    float outerY = clipGet(outer, Y);
    float outerRight = outerX + clipGet(outer, WIDTH);
    float outerBottom = outerY + clipGet(outer, HEIGHT);
    float x = Math.max(l, outerX);
    float y = Math.max(t, outerY);
    float width = Math.min(r, outerRight) - x;
    float height = Math.min(b, outerBottom) - y;
    if (!(width > 0 && height > 0)) {
      return -1;
    }
    float right = x + width;
    float bottom = y + height;
    int parent = reaching(outer, x, y, right, bottom);
    boolean cutLeft = l < outerX;
    boolean cutTop = t < outerY;
    boolean cutRight = r > outerRight;
    boolean cutBottom = b > outerBottom;
    if (!(cutLeft || cutTop || cutRight || cutBottom)) {
      return entry(x, y, width, height, radii, parent);
    }
    boolean[] cut = {
      cutLeft || cutTop, cutTop || cutRight, cutRight || cutBottom, cutBottom || cutLeft
    };
    float[] kept = new float[4];
    boolean whole = true; // whether the box with the kept radii is the rectangle within it
    float most = Math.min(r - l, b - t) / 2;
    float mostKept = Math.min(width, height) / 2;
    for (int c = 0; c < 4; c++) {
      float radius = Math.clamp(radii[c], 0, most);
      if (cut[c]) {
        whole &= !(radius != 0 && corner(l, t, r, b, c, radius, x, y, right, bottom));
      } else {
        kept[c] = radii[c];
        whole &= Math.clamp(radii[c], 0, mostKept) == radius;
      }
    }
    if (whole) {
      return entry(x, y, width, height, kept, parent);
    }
    return entry(x, y, width, height, SQUARE, entry(l, t, r - l, b - t, radii, parent));
  }

  /**
   * The first clip from {@code clip} out along its chain whose rounded corners reach into the box
   * from {@code l, t} to {@code r, b}, which lies inside each of them; 0 for none.
   */
  private int reaching(int clip, float l, float t, float r, float b) {
    for (int c = clip; c != 0; c = clipParent(c)) {
      float x = clipGet(c, X);
      float y = clipGet(c, Y);
      float right = x + clipGet(c, WIDTH);
      float bottom = y + clipGet(c, HEIGHT);
      float most = Math.min(clipGet(c, WIDTH), clipGet(c, HEIGHT)) / 2;
      for (int k = 0; k < 4; k++) {
        float radius = Math.clamp(clipGet(c, RADIUS + 4 * k), 0, most);
        if (radius != 0 && corner(x, y, right, bottom, k, radius, l, t, r, b)) {
          return c;
        }
      }
    }
    return 0;
  }

  /**
   * Whether corner {@code c} (top left, top right, bottom right, bottom left) of the rectangle
   * from {@code x0, y0} to {@code x1, y1}, the square of side {@code radius} where its rounded
   * corner lies, reaches into the box from {@code l, t} to {@code r, b} or within a physical pixel
   * of it: the box's own smoothed edge covers some of a pixel up to half a pixel beyond it. A scene
   * without a scale takes a logical pixel, which is enough at scales of 0.5 and more.
   */
  private boolean corner(
      float x0,
      float y0,
      float x1,
      float y1,
      int c,
      float radius,
      float l,
      float t,
      float r,
      float b) {
    float reach = scale > 0 ? 1 / scale : 1;
    float left = c == 0 || c == 3 ? x0 : x1 - radius;
    float top = c < 2 ? y0 : y1 - radius;
    return left < r + reach
        && left + radius > l - reach
        && top < b + reach
        && top + radius > t - reach;
  }

  /** Appends an entry to the clip table and returns its index. */
  private int entry(float x, float y, float width, float height, float[] radii, int parent) {
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
    clips.set(ValueLayout.JAVA_INT, offset + CLIP_PARENT, parent);
    for (long at = CLIP_PARENT + 4L; at < CLIP_STRIDE; at += 4) {
      clips.set(ValueLayout.JAVA_INT, offset + at, 0);
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
   * @param fill the fill color as 0xAARRGGBB
   * @param border the border color as 0xAARRGGBB
   */
  public void rect(
      float x,
      float y,
      float width,
      float height,
      float radius,
      float borderWidth,
      int fill,
      int border) {
    rect(x, y, width, height, radius, borderWidth, fill, border, 1, 0);
  }

  /**
   * Appends a rounded rectangle with a radius per corner and a border per side.
   *
   * @param radii top left, top right, bottom right, bottom left
   * @param borders top, right, bottom, left
   * @param fill the fill color as 0xAARRGGBB
   * @param border the border color as 0xAARRGGBB
   */
  public void rect(
      float x,
      float y,
      float width,
      float height,
      float[] radii,
      float[] borders,
      int fill,
      int border) {
    rect(x, y, width, height, radii, borders, fill, border, 1, 0);
  }

  private void rect(
      float x,
      float y,
      float width,
      float height,
      float radius,
      float borderWidth,
      int fill,
      int border,
      float opacity,
      int clip) {
    rect(
        x,
        y,
        width,
        height,
        new float[] {radius, radius, radius, radius},
        new float[] {borderWidth, borderWidth, borderWidth, borderWidth},
        fill,
        border,
        opacity,
        clip);
  }

  private void rect(
      float x,
      float y,
      float width,
      float height,
      float[] radii,
      float[] borders,
      int fill,
      int border,
      float opacity,
      int clip) {
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
    put(offset + ARC_START, 0);
    put(offset + ARC_GAP, 0);
    data.set(ValueLayout.JAVA_INT, offset + CLIP, clip);
    data.set(ValueLayout.JAVA_INT, offset + KIND, RECT);
    color(offset + FILL, fill, opacity);
    color(offset + BORDER, border, opacity);
    count++;
  }

  /** The radii of the corners: top left, top right, bottom right, bottom left. */
  private void radii(long offset, float[] radii) {
    put(offset + RADIUS, radii[0]);
    put(offset + RADIUS_TOP_RIGHT, radii[1]);
    put(offset + RADIUS_BOTTOM_RIGHT, radii[2]);
    put(offset + RADIUS_BOTTOM_LEFT, radii[3]);
  }

  private void color(long offset, int argb, float opacity) {
    float alpha = (argb >>> 24) / 255f * opacity;
    put(offset, SRGB_TO_LINEAR[argb >> 16 & 0xFF] * alpha);
    put(offset + 4, SRGB_TO_LINEAR[argb >> 8 & 0xFF] * alpha);
    put(offset + 8, SRGB_TO_LINEAR[argb & 0xFF] * alpha);
    put(offset + 12, alpha);
  }

  /** An 8-bit sRGB value in linear light, as the colors of a scene are converted. */
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
   * One u32 slot of one instance: {@link #CLIP} or {@link #KIND}, of a glyph {@link #ATLAS},
   * {@link #ATLAS_SIZE} or {@link #PAGE}, of an image {@link #IMAGE_SIZE}, {@link #LEVELS}
   * or {@link #IMAGE_INDEX}, and of stops {@link #GRADIENT_SHAPE}.
   */
  public int getInt(int instance, int field) {
    return data.get(ValueLayout.JAVA_INT, (long) instance * STRIDE + field);
  }

  /** The images that image instances refer to by their {@link #IMAGE_INDEX}, each once. */
  public List<Image> images() {
    return images;
  }

  /**
   * The bytes of GPU memory a renderer may keep images in, {@link #DEFAULT_IMAGE_MEMORY} unless
   * set; the GPU renderer gives up the images drawn least recently beyond it, never one of the
   * scene it draws.
   */
  public long imageMemory() {
    return imageMemory;
  }

  /** Sets {@link #imageMemory()}. */
  public Scene imageMemory(long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException(
          "The image memory is " + bytes + " bytes; it must be 0" + " or more.");
    }
    imageMemory = bytes;
    return this;
  }

  /** The atlas pages that glyphs refer to by their {@link #PAGE}; see {@link Atlas.Page}. */
  public List<Atlas.Page> pages() {
    return atlas.pages();
  }

  /** The color atlas pages that color glyphs refer to by their {@link #PAGE}; see {@link Atlas.Page}. */
  public List<Atlas.Page> colorPages() {
    return atlas.colorPages();
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

  /**
   * The entry whose coverage a clip's is taken with, an earlier one, or 0 for none; see {@link
   * #CLIP_PARENT}.
   */
  public int clipParent(int clip) {
    return clips.get(ValueLayout.JAVA_INT, (long) clip * CLIP_STRIDE + CLIP_PARENT);
  }
}
