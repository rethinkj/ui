package com.rethinkj.ui.render;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

import com.rethinkj.ui.Image;
import com.rethinkj.ui.gpu.BindGroup;
import com.rethinkj.ui.gpu.Buffer;
import com.rethinkj.ui.gpu.BufferUsage;
import com.rethinkj.ui.gpu.Color;
import com.rethinkj.ui.gpu.CommandEncoder;
import com.rethinkj.ui.gpu.Device;
import com.rethinkj.ui.gpu.GpuObject;
import com.rethinkj.ui.gpu.RenderPass;
import com.rethinkj.ui.gpu.RenderPipeline;
import com.rethinkj.ui.gpu.Texture;
import com.rethinkj.ui.gpu.TextureFormat;
import com.rethinkj.ui.gpu.TextureUsage;
import com.rethinkj.ui.gpu.TextureView;
import com.rethinkj.ui.text.Atlas;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Draws a {@link Scene} on the GPU, through wgpu, with the formulas of {@link CpuRenderer}: for
 * rectangles the same signed distance, the same coverage ramp one physical pixel wide and the
 * border inside the rectangle; for glyphs the same texel for each physical pixel and the same
 * coverage correction; for shadows the same blur, with the same approximation of {@code erf}; for
 * all the coverage of the clip and the clips along its chain multiplied in, and blending in linear
 * light.
 * The result matches the CPU renderer's within a small tolerance per channel, not bit for bit.
 *
 * <p>The instances and the clip table go to the GPU as they are, as two read-only storage
 * buffers that the shader reads as {@code array<Instance>} and {@code array<Clip>} in the layout
 * {@link Scene} documents; one instanced draw call draws all instances in order, rectangles,
 * glyphs and shadows alike, one quad each. The colors stay linear and premultiplied: the shader
 * returns premultiplied linear color, the pipeline blends with {@code one, one-minus-src-alpha},
 * and the target has an sRGB format, so the GPU blends in linear light and encodes to sRGB as it
 * stores.
 *
 * <p>The atlas pages of the scene ({@link Scene#pages}) are the layers of one {@code r8unorm}
 * texture array, page {@code i} in layer {@code i}, and the color atlas pages ({@link
 * Scene#colorPages}) are the layers of a second {@code rgba8unorm} texture array, both read by the
 * shader with {@code textureLoad}: exact texels, no sampler, no filtering. One array per kind
 * rather than a texture per page keeps the single draw call, since WGSL cannot pick one of several
 * bound textures by an index that varies per instance. Before each draw the renderer writes into
 * each layer what {@link com.rethinkj.ui.text.Atlas.Page#takeChanged} reports for its page, and
 * nothing for a page that did not change, so a frame without new glyphs uploads nothing; a page
 * the renderer has not seen in its layer before is written whole, once. The renderer is meant to
 * be the only one that takes the changes of an atlas's pages. When a scene has more pages than an
 * array has layers, or pages of another size, that array is replaced by one with the next power of
 * two of layers, and every page of that kind is written whole again.
 *
 * <p>Each image ({@link Scene#images}) is a texture of its own, format {@code rgba8unorm} decoded
 * from sRGB in the shader, with the levels the image computed as its mip levels, uploaded when it
 * is first drawn on the device and kept for the scenes after, by the image's identity, as long as
 * the images on the GPU fit into the scene's {@link Scene#imageMemory}: beyond it the images drawn
 * least recently are given up, never one of the scene being drawn nor one that another window on
 * the same device shows. The texture is bound as group 1, which only image instances read,
 * so the instances are drawn in order in one draw call per run that one image texture is bound
 * for: a scene without images is one draw call, a list of the same icon too, and a page of 30
 * photos at most 31. A texture per image rather than an atlas of images keeps each image's levels
 * where the GPU's mip levels are, needs no packing or padding, and gives up an image's memory
 * whole. The shader filters the texels itself with {@code textureLoad}, as {@link Scene} says, so
 * that the GPU reads what the CPU reads, without a sampler. An image larger than the device's
 * largest texture leaves out its largest levels.
 *
 * <p>Buffers and the atlas texture grow with the scenes and are kept for the next frame; once
 * their size suffices, drawing allocates no GPU memory beyond what every WebGPU frame needs (a
 * command encoder, a pass and a command buffer). Like everything of the GPU layer, a renderer
 * belongs to the thread that created it. The renderers of a thread's windows ({@link
 * GpuPresenter}) share one device, the shader, a pipeline per target format and the images; each
 * keeps its buffers and its atlas texture, since each app has an atlas of its own. A renderer
 * from {@link #create} has a device of its own.
 *
 * <pre>{@code
 * try (GpuRenderer gpu = GpuRenderer.create()) {
 *   Pixels pixels = gpu.render(Scene.of(root), 400, 240, 2);
 * }
 * }</pre>
 */
public final class GpuRenderer implements AutoCloseable {

  /**
   * The shader. Every line of the fragment shader has its counterpart in {@link CpuRenderer}; keep
   * the two in step.
   */
  static final String WGSL = """
      struct Globals {
        // The target's size in physical pixels.
        size: vec2f,
        // Physical pixels per logical pixel.
        scale: f32,
        unused: f32,
      }

      // The layouts of Scene's Javadoc: 96 bytes per instance, 48 per clip. An instance is a
      // rounded rectangle, a glyph, a shadow, an image, a line (whose ends are in rect and width
      // in shape.x), a squiggle (x, mid y, width, amplitude in rect, wavelength and stroke in
      // shape, phase in more.x), a color glyph (a glyph whose page lies in color_atlas and whose
      // fill holds its opacity), a rectangle with a gradient, or that gradient's stops (the ends
      // in rect, linear or radial in shape.x, opacity in shape.y, from in fill and to in more). The
      // slots in which they differ are declared as u32
      // and bitcast to f32 where they hold floats: the bits of a float survive that, while a small
      // integer read as a float is a subnormal, which a GPU may flush to zero.
      struct Instance {
        rect: vec4f,
        // A rectangle's top left radius and top border width, f32; a glyph's atlas and
        // atlas_size; a shadow's top left radius and sigma, f32.
        shape: vec2u,
        clip: u32,
        kind: u32,
        // A rectangle's fill; a glyph's or a shadow's color.
        fill: vec4f,
        // A rectangle's border color, f32; a glyph's page, and as f32 its light_share,
        // dark_gamma and light_gamma.
        more: vec4u,
        // A rectangle's and a shadow's radii at the top right, bottom right and bottom left; a
        // rectangle's arc start.
        radii: vec4f,
        // A rectangle's border widths at the right, the bottom and the left; its arc gap.
        borders: vec4f,
      }

      const GLYPH: u32 = 1u;
      const SHADOW: u32 = 2u;
      const IMAGE: u32 = 3u;
      const LINE: u32 = 4u;
      const SQUIGGLE: u32 = 5u;
      const COLOR_GLYPH: u32 = 6u;
      const GRADIENT: u32 = 7u;
      const STOPS: u32 = 8u;
      const RADIAL: u32 = 1u;

      struct Clip {
        rect: vec4f,
        // Top left, top right, bottom right, bottom left.
        radii: vec4f,
        // The entry whose coverage this one's is taken with, an earlier one, or 0.
        parent: u32,
      }

      @group(0) @binding(0) var<uniform> globals: Globals;
      @group(0) @binding(1) var<storage, read> instances: array<Instance>;
      @group(0) @binding(2) var<storage, read> clips: array<Clip>;
      // The atlas pages, one layer each: 8-bit coverage, read texel by texel, never filtered.
      @group(0) @binding(3) var atlas: texture_2d_array<f32>;
      // The color atlas pages, one layer each: premultiplied sRGB color and linear alpha
      // (rgba8unorm), read texel by texel, never filtered.
      @group(0) @binding(4) var color_atlas: texture_2d_array<f32>;
      // The image of the image instances drawn now: its levels, premultiplied color encoded to
      // sRGB and linear alpha (rgba8unorm), read texel by texel, decoded and filtered here, as on
      // the CPU.
      @group(1) @binding(0) var image: texture_2d<f32>;

      struct Varyings {
        @builtin(position) position: vec4f,
        @location(0) @interpolate(flat) index: u32,
      }

      // The physical pixel of a glyph's top left texel: x * scale and y * scale, rounded half up.
      fn glyph_origin(instance: Instance) -> vec2f {
        return floor(instance.rect.xy * globals.scale + 0.5);
      }

      // A glyph's size in texels, which is its size in physical pixels.
      fn glyph_size(instance: Instance) -> vec2f {
        return vec2f(f32(instance.shape.y & 0xFFFFu), f32(instance.shape.y >> 16u));
      }

      // A shadow's deviation with the pixel's added: a box one physical pixel wide has the
      // variance 1/12 of a Gaussian.
      fn deviation(instance: Instance) -> f32 {
        let sigma = bitcast<f32>(instance.shape.y);
        return sqrt(sigma * sigma + 1.0 / (12.0 * globals.scale * globals.scale));
      }

      // A quad, in physical pixels, within the instance's clip and one pixel beyond it. For a
      // rectangle, over the rectangle and one pixel larger on every side: the smoothed edge
      // reaches half a pixel beyond the shape. For a shadow, the same over its shape grown by
      // three deviations. For a line, over the box around its ends grown by half its width. For a
      // glyph, exactly over its texels.
      @vertex
      fn vs_main(@builtin(vertex_index) vertex: u32,
          @builtin(instance_index) index: u32) -> Varyings {
        let instance = instances[index];
        let clip = clips[instance.clip];
        var low: vec2f;
        var high: vec2f;
        var empty = false;
        if instance.kind == GLYPH || instance.kind == COLOR_GLYPH {
          let origin = glyph_origin(instance);
          low = max(origin, clip.rect.xy * globals.scale - 1.0);
          high = min(origin + glyph_size(instance),
              (clip.rect.xy + clip.rect.zw) * globals.scale + 1.0);
        } else {
          let rect = instance.rect;
          var start = rect.xy;
          var end = rect.xy + rect.zw;
          empty = !(rect.z > 0.0 && rect.w > 0.0) || instance.kind == STOPS;
          if instance.kind == SHADOW {
            let reach = 3.0 * deviation(instance);
            start -= reach;
            end += reach;
          }
          if instance.kind == LINE {
            // shortcut: a long diagonal line covers a large box mostly empty; a quad along the
            // line would save those fragments where many long diagonals need it.
            let half = bitcast<f32>(instance.shape.x) / 2.0;
            start = min(rect.xy, rect.zw) - half;
            end = max(rect.xy, rect.zw) + half;
            empty = !(half > 0.0);
          }
          if instance.kind == SQUIGGLE {
            let wavelength = bitcast<f32>(instance.shape.x);
            let stroke = bitcast<f32>(instance.shape.y);
            let reach = rect.w + stroke / 2.0;
            start = vec2f(rect.x, rect.y - reach);
            end = vec2f(rect.x + rect.z, rect.y + reach);
            empty = !(rect.z > 0.0 && rect.w >= 0.0 && wavelength > 0.0 && stroke > 0.0);
          }
          let pad = 1.0 / globals.scale;
          low = (max(start, clip.rect.xy) - pad) * globals.scale;
          high = (min(end, clip.rect.xy + clip.rect.zw) + pad) * globals.scale;
        }
        // No further than a pixel beyond the target: the fragments are the same, and a quad that
        // reaches a billion pixels out, as a scrim's, is one a software rasterizer may drop.
        low = max(low, vec2f(-1.0));
        high = min(high, globals.size + 1.0);
        let corner = vec2f(f32(vertex & 1u), f32(vertex >> 1u));
        var pixel = mix(low, high, corner);
        // Nothing to draw: an empty rectangle, or one its clip leaves nothing of.
        if empty || !all(high > low) {
          pixel = vec2f(0.0);
        }
        var out: Varyings;
        out.position = vec4f(pixel / globals.size * vec2f(2.0, -2.0) + vec2f(-1.0, 1.0), 0.0, 1.0);
        out.index = index;
        return out;
      }

      // Signed distance to a rounded rectangle centered on the origin; negative inside.
      fn distance(p: vec2f, half_size: vec2f, radius: f32) -> f32 {
        let q = abs(p) - half_size + radius;
        return min(max(q.x, q.y), 0.0) + length(max(q, vec2f(0.0))) - radius;
      }

      // The corner on the side of p: 0 top left, 1 top right, 2 bottom right, 3 bottom left.
      fn corner(p: vec2f) -> u32 {
        if p.x > 0.0 {
          return select(1u, 2u, p.y > 0.0);
        }
        return select(0u, 3u, p.y > 0.0);
      }

      // The same, with the corner on p's side r.x across and r.y from top to bottom: an
      // elliptic corner's is the first-order distance.
      fn distance_elliptic(p: vec2f, half_size: vec2f, r: vec2f) -> f32 {
        if r.x == r.y {
          return distance(p, half_size, r.x);
        }
        let e = abs(p) - half_size;
        var q = e + r;
        if r.x > 0.0 && r.y > 0.0 && q.x > 0.0 && q.y > 0.0 {
          q /= r;
          let k = sqrt(q.x * q.x + q.y * q.y);
          let g = q / r;
          return (k - 1.0) * k / sqrt(g.x * g.x + g.y * g.y);
        }
        let outside = max(e, vec2f(0.0));
        return min(max(e.x, e.y), 0.0) + sqrt(outside.x * outside.x + outside.y * outside.y);
      }

      // A rectangle's or a shadow's radii, top left, top right, bottom right, bottom left, each
      // clamped to half the shorter side.
      fn corner_radii(instance: Instance, half_size: vec2f) -> vec4f {
        return clamp(vec4f(bitcast<f32>(instance.shape.x), instance.radii.xyz), vec4f(0.0),
            vec4f(min(half_size.x, half_size.y)));
      }

      // How much of a pixel the shape covers: a ramp one physical pixel wide across the edge.
      fn coverage(distance: f32) -> f32 {
        return clamp(0.5 - distance * globals.scale, 0.0, 1.0);
      }

      // The sine and cosine of t turns: brought into a quarter turn around 0, then the terms of
      // their series up to x^11 and x^12, by arithmetic alone, as on the CPU.
      fn turn(t: f32) -> vec2f {
        var r = t - floor(t + 0.5);
        var flip = 1.0;
        if r > 0.25 {
          r = 0.5 - r;
          flip = -1.0;
        } else if r < -0.25 {
          r = -0.5 - r;
          flip = -1.0;
        }
        let x = r * 6.283185307179586;
        let x2 = x * x;
        let s = x * (1.0 - x2 / 6.0 * (1.0 - x2 / 20.0 * (1.0 - x2 / 42.0 * (1.0 - x2 / 72.0
            * (1.0 - x2 / 110.0)))));
        let c = 1.0 - x2 / 2.0 * (1.0 - x2 / 12.0 * (1.0 - x2 / 30.0 * (1.0 - x2 / 56.0
            * (1.0 - x2 / 90.0 * (1.0 - x2 / 132.0)))));
        return vec2f(s, flip * c);
      }

      // The coverage of an arc of at most half a turn at a point q.x beside its bisector, 0 or
      // more, and q.y along it, with the sine and cosine of half its angle in h.
      fn narrow(q: vec2f, half: vec2f) -> f32 {
        // At half a turn the cosine is 0 up to rounding, whose sign would pick the near edge.
        let h = vec2f(half.x, max(half.y, 0.0));
        let r = sqrt(q.x * q.x + q.y * q.y);
        let near = select(-r, -q.x * h.y + q.y * h.x, q.x * h.x + q.y * h.y >= 0.0);
        let far = select(r, q.x * h.y + q.y * h.x, q.y * h.y - q.x * h.x >= 0.0);
        return max(coverage(-near) + coverage(-far) - 1.0, 0.0);
      }

      // The color of a gradient's stops at a point, before the fill under it: t along the line,
      // or the elliptical distance from the center, eased with smoothstep.
      fn gradient(stops: Instance, point: vec2f) -> vec4f {
        let a = stops.rect.xy;
        let b = stops.rect.zw;
        var t = 0.0;
        if stops.shape.x == RADIAL {
          let u = (point - a) / b;
          t = min(sqrt(dot(u, u)), 1.0);
        } else {
          let d = b - a;
          let span = dot(d, d);
          if span > 0.0 {
            t = clamp(dot(point - a, d) / span, 0.0, 1.0);
          }
        }
        let s = t * t * (3.0 - 2.0 * t);
        return stops.fill + (bitcast<vec4f>(stops.more) - stops.fill) * s;
      }

      // How much of the pixel at p from a rectangle's center its arc lets through.
      fn arc(p: vec2f, start: f32, gap: f32) -> f32 {
        let sweep = 1.0 - gap;
        let b = turn(start + sweep / 2.0);
        let h = turn(sweep / 2.0);
        let q = vec2f(abs(p.x * b.y + p.y * b.x), p.x * b.x - p.y * b.y);
        var cover: f32;
        if gap >= 0.5 {
          cover = narrow(q, h);
        } else {
          cover = 1.0 - narrow(vec2f(q.x, -q.y), vec2f(h.x, -h.y));
        }
        // Near the center, where the edges meet: the sweep up to half a pixel away, cover from
        // sqrt(1/2) on, as far as the pixels around a center on their corners lie.
        let ramp = (2.0 * length(p) * globals.scale - 1.0) / 0.41421356237309515;
        return sweep + (cover - sweep) * clamp(ramp, 0.0, 1.0);
      }

      // How much of the pixel centered at point, in logical pixels, the clip lets through: the
      // least that it and each of its parents does by itself. A parent that does not come before
      // its entry ends the chain.
      fn clip_coverage(index: u32, point: vec2f) -> f32 {
        var cover = own_clip_coverage(index, point);
        var entry = index;
        loop {
          let parent = clips[entry].parent;
          if parent == 0u || parent >= entry {
            break;
          }
          cover = min(cover, own_clip_coverage(parent, point));
          entry = parent;
        }
        return cover;
      }

      // How much of the pixel the clip's own rounded rectangle lets through.
      fn own_clip_coverage(index: u32, point: vec2f) -> f32 {
        let clip = clips[index];
        let half_size = clip.rect.zw / 2.0;
        let p = point - (clip.rect.xy + half_size);
        let radius = clamp(clip.radii[corner(p)], 0.0, min(half_size.x, half_size.y));
        return coverage(distance(p, half_size, radius));
      }

      // The error function as Abramowitz and Stegun's 7.1.28 has it, within 3e-7: arithmetic
      // only, as on the CPU.
      fn erf(x: f32) -> f32 {
        let a = min(abs(x), 4.0);
        var p = 1.0 + a * (0.0705230784 + a * (0.0422820123 + a * (0.0092705272
            + a * (0.0001520143 + a * (0.0002765672 + a * 0.0000430638)))));
        p *= p;
        p *= p;
        p *= p;
        p *= p;
        return sign(x) * (1.0 - 1.0 / p);
      }

      // How much of a Gaussian of deviation sigma around 0 lies below t.
      fn below(t: f32, sigma: f32) -> f32 {
        return 0.5 + 0.5 * erf(t * (0.7071067811865476 / sigma));
      }

      // The Gaussian's mass from -half to half, seen from t.
      fn across(t: f32, half: f32, sigma: f32) -> f32 {
        return below(t + half, sigma) - below(t - half, sigma);
      }

      // The blur of the quarter disk of radius r at the origin towards +x, +y, seen from u, v:
      // within three deviations of v, in eight slices of equal height, each the Gaussian's exact
      // mass across the slice times its mass along the disk at the slice's middle.
      fn quarter(u: f32, v: f32, r: f32, sigma: f32) -> f32 {
        let reach = 3.0 * sigma;
        let low = max(v - reach, 0.0);
        let high = min(v + reach, r);
        if !(high > low) || u < -reach || u > r + reach {
          return 0.0;
        }
        let step = (high - low) / 8.0;
        let inside = below(-u, sigma);
        var before = below(low - v, sigma);
        var sum = 0.0;
        for (var i = 1u; i <= 8u; i++) {
          let top = low + step * f32(i);
          let after = below(top - v, sigma);
          let middle = top - step / 2.0;
          let extent = sqrt(max(r * r - middle * middle, 0.0));
          sum += (below(extent - u, sigma) - inside) * (after - before);
          before = after;
        }
        return sum;
      }

      // How much of a Gaussian of deviation sigma around p lies in a rounded rectangle centered on
      // the origin: two crossed boxes, whose blur is exact, and the quarter disks at their
      // corners.
      fn shadow(p: vec2f, half_size: vec2f, radius: f32, sigma: f32) -> f32 {
        let straight = half_size - radius;
        let wide = across(p.x, half_size.x, sigma);
        let narrow = across(p.x, straight.x, sigma);
        let tall = across(p.y, half_size.y, sigma);
        let flat = across(p.y, straight.y, sigma);
        var cover = wide * flat + narrow * tall - narrow * flat;
        if radius > 0.0 {
          cover += quarter(p.x - straight.x, p.y - straight.y, radius, sigma)
              + quarter(-p.x - straight.x, p.y - straight.y, radius, sigma)
              + quarter(p.x - straight.x, -p.y - straight.y, radius, sigma)
              + quarter(-p.x - straight.x, -p.y - straight.y, radius, sigma);
        }
        return min(cover, 1.0);
      }

      // The same with a radius per corner: the box, less each rounded corner's square, plus its
      // quarter disk; the crossed boxes where all four are equal.
      fn shadow_corners(p: vec2f, half_size: vec2f, radii: vec4f, sigma: f32) -> f32 {
        if all(radii == vec4f(radii.x)) {
          return shadow(p, half_size, radii.x, sigma);
        }
        var cover = across(p.x, half_size.x, sigma) * across(p.y, half_size.y, sigma);
        for (var c = 0u; c < 4u; c++) {
          let r = radii[c];
          if r > 0.0 {
            let side = vec2f(select(-p.x, p.x, c == 1u || c == 2u), select(-p.y, p.y, c >= 2u));
            let straight_y = half_size.y - r;
            cover += quarter(side.x - (half_size.x - r), side.y - straight_y, r, sigma)
                - (below(half_size.x - side.x, sigma) - below(half_size.x - r - side.x, sigma))
                * (below(half_size.y - side.y, sigma) - below(straight_y - side.y, sigma));
          }
        }
        return min(cover, 1.0);
      }

      // A premultiplied sRGB texel in linear light: its color decoded from sRGB, its alpha as it is.
      fn linear_texel(t: vec4f) -> vec4f {
        return vec4f(select(t.rgb / 12.92, pow((t.rgb + 0.055) / 1.055, vec3f(2.4)),
            t.rgb > vec3f(0.04045)), t.a);
      }

      // A texel of the image in linear light: its color decoded from sRGB, its alpha as it is.
      fn image_texel(texel: vec2i, level: i32) -> vec4f {
        return linear_texel(textureLoad(image, texel, level));
      }

      // Level k of the image at the point uv of level 0 (of size texels), from its four texels
      // around it. A level the texture lacks, because the GPU's limit left out the largest, is
      // taken from its first.
      fn image_level(instance: Instance, uv: vec2f, size: vec2f, k: u32) -> vec4f {
        let skip = i32(instance.shape.y) - i32(textureNumLevels(image));
        let level = max(i32(k) - skip, 0);
        let dims = vec2i(textureDimensions(image, level));
        let p = uv * vec2f(dims) / size - 0.5;
        let f = p - floor(p);
        let low = clamp(vec2i(floor(p)), vec2i(0), dims - 1);
        let high = clamp(vec2i(floor(p)) + 1, vec2i(0), dims - 1);
        let over = mix(image_texel(low, level), image_texel(vec2i(high.x, low.y), level), f.x);
        let under = mix(image_texel(vec2i(low.x, high.y), level), image_texel(high, level), f.x);
        return mix(over, under, f.y);
      }

      // The image's sample at the pixel centered at pixel, in physical pixels: one texel to one
      // pixel, or trilinear from its levels, as Scene's Javadoc says.
      fn image_sample(instance: Instance, pixel: vec2f) -> vec4f {
        let size = vec2f(f32(instance.shape.x & 0xFFFFu), f32(instance.shape.x >> 16u));
        let origin = instance.rect.xy * globals.scale;
        let extent = instance.rect.zw * globals.scale;
        let snapped = floor(origin + 0.5);
        if all(abs(extent - size) < vec2f(0.001)) && all(abs(origin - snapped) < vec2f(0.001))
            && instance.shape.y == textureNumLevels(image) {
          let texel = clamp(vec2i(floor(pixel) - snapped), vec2i(0), vec2i(size) - 1);
          return image_texel(texel, 0);
        }
        let ratio = size / extent;
        let uv = (pixel - origin) * ratio;
        let lod = log2(max(ratio.x, ratio.y));
        if !(lod > 0.0) {
          return image_level(instance, uv, size, 0u);
        }
        let last = max(instance.shape.y, 1u) - 1u;
        let k = floor(lod);
        if k >= f32(last) {
          return image_level(instance, uv, size, last);
        }
        return mix(image_level(instance, uv, size, u32(k)),
            image_level(instance, uv, size, u32(k) + 1u), lod - k);
      }

      @fragment
      fn fs_main(in: Varyings) -> @location(0) vec4f {
        let instance = instances[in.index];
        // The pixel's center, in logical pixels; the position is at the center already.
        let point = in.position.xy / globals.scale;
        if instance.kind == GLYPH {
          // One texel to one physical pixel: the texel of this pixel, read as it is.
          let texel = vec2i(floor(in.position.xy) - glyph_origin(instance));
          if any(texel < vec2i(0)) || any(texel >= vec2i(glyph_size(instance))) {
            discard;
          }
          let corner = vec2i(vec2u(instance.shape.x & 0xFFFFu, instance.shape.x >> 16u));
          let a = textureLoad(atlas, corner + texel, instance.more.x, 0).r;
          if a == 0.0 {
            discard;
          }
          let dark = 1.0 - pow(1.0 - a, bitcast<f32>(instance.more.z));
          let light = pow(a, bitcast<f32>(instance.more.w));
          var glyph_cover = dark + (light - dark) * bitcast<f32>(instance.more.y);
          if instance.clip != 0u {
            glyph_cover *= clip_coverage(instance.clip, point);
          }
          if glyph_cover <= 0.0 {
            discard;
          }
          return instance.fill * glyph_cover;
        }
        if instance.kind == COLOR_GLYPH {
          // One texel to one physical pixel: the texel of this pixel, decoded from sRGB.
          let texel = vec2i(floor(in.position.xy) - glyph_origin(instance));
          if any(texel < vec2i(0)) || any(texel >= vec2i(glyph_size(instance))) {
            discard;
          }
          let corner = vec2i(vec2u(instance.shape.x & 0xFFFFu, instance.shape.x >> 16u));
          let t = textureLoad(color_atlas, corner + texel, instance.more.x, 0);
          if t.a == 0.0 {
            discard;
          }
          var glyph_cover = 1.0;
          if instance.clip != 0u {
            glyph_cover = clip_coverage(instance.clip, point);
            if glyph_cover <= 0.0 {
              discard;
            }
          }
          return linear_texel(t) * instance.fill * glyph_cover;
        }
        if instance.kind == IMAGE {
          let half_size = instance.rect.zw / 2.0;
          var image_cover = coverage(distance(point - (instance.rect.xy + half_size), half_size,
              0.0));
          if instance.clip != 0u {
            image_cover *= clip_coverage(instance.clip, point);
          }
          if image_cover <= 0.0 {
            discard;
          }
          return image_sample(instance, in.position.xy) * instance.fill * image_cover;
        }
        if instance.kind == LINE {
          let a = instance.rect.xy;
          let b = instance.rect.zw - a;
          let half = bitcast<f32>(instance.shape.x) / 2.0;
          let squared = dot(b, b);
          // From the end nearer the pixel, so a far end costs no precision near the other.
          let u = point - a;
          let v = point - instance.rect.zw;
          let near = dot(u, u) <= dot(v, v);
          var t = 0.0;
          if squared > 0.0 {
            t = clamp(select(-dot(v, b), dot(u, b), near) / squared, 0.0, 1.0);
          }
          let d = length(select(v + b * t, u - b * t, near));
          var line_cover = coverage(d - half) - coverage(d + half);
          if instance.clip != 0u {
            line_cover *= clip_coverage(instance.clip, point);
          }
          if line_cover <= 0.0 {
            discard;
          }
          return instance.fill * line_cover;
        }
        if instance.kind == SQUIGGLE {
          let wavelength = bitcast<f32>(instance.shape.x);
          let stroke = bitcast<f32>(instance.shape.y);
          let half_w = instance.rect.z / 2.0;
          let amplitude = instance.rect.w;
          let half = stroke / 2.0;
          let phase = bitcast<f32>(instance.more.x);
          let end_cover = coverage(abs(point.x - (instance.rect.x + half_w)) - half_w);
          let sc = turn((point.x - instance.rect.x) / wavelength + phase);
          let wave_y = instance.rect.y + amplitude * sc.x;
          let slope = 6.283185307179586 * amplitude / wavelength * sc.y;
          let d = max(abs(point.y - wave_y) / sqrt(1.0 + slope * slope),
              abs(point.y - instance.rect.y) - amplitude);
          var squiggle_cover = (coverage(d - half) - coverage(d + half)) * end_cover;
          if instance.clip != 0u {
            squiggle_cover *= clip_coverage(instance.clip, point);
          }
          if !(squiggle_cover > 0.0) {
            discard;
          }
          return instance.fill * squiggle_cover;
        }
        if instance.kind == SHADOW {
          let half_size = instance.rect.zw / 2.0;
          var shadow_cover = shadow_corners(point - (instance.rect.xy + half_size), half_size,
              corner_radii(instance, half_size), deviation(instance));
          if instance.clip != 0u {
            shadow_cover *= clip_coverage(instance.clip, point);
          }
          if shadow_cover <= 0.0 {
            discard;
          }
          return instance.fill * shadow_cover;
        }
        let half_size = instance.rect.zw / 2.0;
        let center = instance.rect.xy + half_size;
        let radii = corner_radii(instance, half_size);
        // Top, right, bottom, left.
        let borders = max(vec4f(bitcast<f32>(instance.shape.y), instance.borders.xyz), vec4f(0.0));
        let p = point - center;
        var cover = coverage(distance(p, half_size, radii[corner(p)]));
        // Entry 0 covers everything; it is skipped like on the CPU.
        if instance.clip != 0u {
          cover *= clip_coverage(instance.clip, point);
        }
        let gap = instance.borders.w;
        if gap >= 1.0 {
          discard;
        }
        if gap > 0.0 {
          cover *= arc(p, instance.radii.w, gap);
        }
        if cover <= 0.0 {
          discard;
        }
        // The border lies inside the rectangle, and a side without one moves the fill's edge out
        // by a physical pixel; what remains for the fill may be empty.
        var fill_share = 1.0;
        if any(borders > vec4f(0.0)) {
          let inset = select(vec4f(-1.0 / globals.scale), borders, borders > vec4f(0.0));
          let inner = half_size - (inset.wx + inset.yz) / 2.0;
          fill_share = 0.0;
          if inner.x > 0.0 && inner.y > 0.0 {
            let q = point - (center + (inset.wx - inset.yz) / 2.0);
            let c = corner(q);
            let r = vec2f(
                min(max(radii[c] - select(borders.y, borders.w, c == 0u || c == 3u), 0.0),
                    inner.x),
                min(max(radii[c] - select(borders.z, borders.x, c < 2u), 0.0), inner.y));
            fill_share = coverage(distance_elliptic(q, inner, r));
          }
        }
        var fill = instance.fill;
        if instance.kind == GRADIENT {
          let g = gradient(instances[in.index + 1u], point);
          fill = g * bitcast<f32>(instances[in.index + 1u].shape.y) + fill * (1.0 - g.a);
        }
        // Premultiplied: the blend state adds the destination times one minus this alpha.
        let edge = bitcast<vec4f>(instance.more);
        return (edge + (fill - edge) * fill_share) * cover;
      }
      """;

  /** The format of the textures {@link #render} draws into. */
  static final TextureFormat TEXTURE_FORMAT = TextureFormat.RGBA8_UNORM_SRGB;

  /** The atlas texture's format: a texel is a page's byte, read as a float from 0 to 1. */
  private static final TextureFormat ATLAS_FORMAT = TextureFormat.R8_UNORM;

  /**
   * The color atlas texture's format: four bytes per texel, premultiplied color encoded to sRGB
   * and linear alpha, decoded in the shader like {@link #IMAGE_FORMAT}.
   */
  private static final TextureFormat COLOR_ATLAS_FORMAT = TextureFormat.RGBA8_UNORM;

  /**
   * An image texture's format: the bytes of {@link Image}'s levels, premultiplied color encoded
   * to sRGB and linear alpha, which the shader decodes with sRGB's formula. Not {@code
   * rgba8unorm-srgb}: a GPU may decode that with an error of up to half a step, which filtering
   * then shows; on an NVIDIA GTX 1070 with Vulkan, halving each of the 256 values after the GPU's
   * decoding came out a step off for 33 of them, after the shader's for 9, as the target's own
   * encoding does.
   */
  static final TextureFormat IMAGE_FORMAT = TextureFormat.RGBA8_UNORM;

  /** Bytes of the uniform block: size, scale and padding, four floats. */
  private static final int GLOBALS_BYTES = 4 * Float.BYTES;
  /** Room for this many instances and clips at first; both double when a scene needs more. */
  private static final long FIRST_INSTANCES = 256;

  private static final long FIRST_CLIPS = 16;
  /** {@code copyTextureToBuffer} needs rows that are a multiple of this many bytes. */
  private static final int ROW_ALIGNMENT = 256;

  /** The device, shader, pipelines and images, shared with the renderers of a thread's windows. */
  private final GpuContext context;
  /** Whether the context is the renderer's own, which closing the renderer closes. */
  private final boolean ownsContext;

  private final Device device;
  private Buffer globals;
  private final MemorySegment globalsData = Arena.ofAuto().allocate(GLOBALS_BYTES, Float.BYTES);
  private Buffer instances;
  private Buffer clips;
  /**
   * The atlas pages, one layer each, in a texture array as large as a page: before the first page
   * one layer of 1 by 1, since the shader needs a texture bound. Replaced, larger, when a scene
   * has more pages than layers, or pages of another size.
   */
  private Texture atlas;

  private TextureView atlasView;
  /**
   * The page whose texels each layer holds, by index; null where that is not known, so that the
   * layer is written whole the next time.
   */
  private Atlas.Page[] resident;
  /** The color atlas pages, one layer each; 1 by 1 before the first color page. */
  private Texture colorAtlas;

  private TextureView colorAtlasView;
  private Atlas.Page[] colorResident;
  /** Writes into the atlas texture, and the texels they carried, for the tests. */
  private long atlasWrites;

  private long atlasTexels;
  private BindGroup group;
  /** What {@link #render} draws into and reads back from; replaced when the size changes. */
  private Texture texture;

  private TextureView textureView;
  private Buffer readback;
  /** The images of the last scene drawn, which other renderers on the device do not give up. */
  private final Set<Image> shown = Collections.newSetFromMap(new IdentityHashMap<>());

  private long imageUploads;
  private boolean closed;

  /**
   * A renderer with a device of its own, on the platform's backend: Direct3D 12 on Windows, Vulkan
   * on Linux.
   *
   * @throws UnsatisfiedLinkError if wgpu-native cannot be loaded, for example because the natives
   *     module of the platform is not on the class path
   * @throws com.rethinkj.ui.gpu.GpuException if there is no adapter or wgpu refuses a device
   */
  public static GpuRenderer create() {
    return create(WGSL);
  }

  /** With another shader, for the test that checks that a broken formula is noticed. */
  static GpuRenderer create(String wgsl) {
    GpuContext context = GpuContext.alone(wgsl);
    try {
      return new GpuRenderer(context, true);
    } catch (RuntimeException | Error e) {
      context.release();
      throw e;
    }
  }

  /**
   * A renderer on a context that others share, such as that of a thread's windows, whose device
   * is made. Closing the renderer closes what it made on the device, not the context.
   */
  GpuRenderer(GpuContext context) {
    this(context, false);
  }

  private GpuRenderer(GpuContext context, boolean ownsContext) {
    this.context = context;
    this.ownsContext = ownsContext;
    device = context.device(null);
    try {
      globals = device.createBuffer(
          "scene globals", GLOBALS_BYTES, BufferUsage.UNIFORM, BufferUsage.COPY_DST);
      replace(1, 1, 1, 1, FIRST_INSTANCES * Scene.STRIDE, FIRST_CLIPS * Scene.CLIP_STRIDE);
    } catch (RuntimeException | Error e) {
      closeOwn(); // on a shared device they would stay until its last window closes
      throw e;
    }
    context.add(this);
  }

  /** What the renderer draws with, as wgpu describes the adapter. */
  public String adapter() {
    return context.info().toString();
  }

  /**
   * Draws {@code scene} into a new image, as {@link CpuRenderer#render} does: the image starts
   * fully transparent, and comes back in sRGB with straight alpha. Where the image is translucent,
   * the color is divided by alpha after reading back, so a pixel with little alpha has coarse
   * color; an opaque pixel is exactly what the GPU stored.
   *
   * @param width the width of the image in physical pixels
   * @param height the height of the image in physical pixels
   * @param scale physical pixels per logical pixel
   */
  public Pixels render(Scene scene, int width, int height, float scale) {
    return straight(renderStored(scene, width, height, scale), width, height);
  }

  /** The image of {@link #renderStored}, in sRGB with straight alpha. */
  static Pixels straight(byte[] stored, int width, int height) {
    byte[] rgba = new byte[width * height * 4];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int from = y * bytesPerRow(width) + x * 4;
        int to = (y * width + x) * 4;
        int alpha = stored[from + 3] & 0xFF;
        if (alpha == 0) {
          continue; // as the CPU renderer leaves it: all zero
        }
        for (int c = 0; c < 3; c++) {
          int value = stored[from + c] & 0xFF;
          rgba[to + c] = alpha == 255
              ? (byte) value
              : (byte) CpuRenderer.encode(Math.clamp(Scene.linear(value) * 255.0 / alpha, 0, 1));
        }
        rgba[to + 3] = (byte) alpha;
      }
    }
    return new Pixels(width, height, rgba);
  }

  /**
   * Draws {@code scene} into the renderer's texture and returns the bytes as the GPU stored them:
   * sRGB-encoded premultiplied color and linear alpha, {@link #bytesPerRow} bytes a row.
   */
  byte[] renderStored(Scene scene, int width, int height, float scale) {
    checkOpen();
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException(
          "Cannot render an image of " + width + "x" + height + " pixels.");
    }
    if (texture == null || texture.width() != width || texture.height() != height) {
      resize(width, height);
    }
    upload(scene, width, height, scale);
    try (CommandEncoder encoder = device.createCommandEncoder("scene image")) {
      recordPass(encoder, textureView, scene);
      encoder.copyTextureToBuffer(texture, readback, 0, bytesPerRow(width));
      device.queue().submit(encoder.finish());
    }
    return readback.read(0, (int) readback.size());
  }

  /**
   * Replaces the image texture, its view and the readback buffer with ones of this size. The size
   * is checked and the new objects made before the old ones are closed, so that a size the device
   * refuses leaves the renderer drawing at the size it had.
   */
  private void resize(int width, int height) {
    int most = device.limits().maxTextureDimension2D();
    long bytes = (long) bytesPerRow(Math.min(width, most)) * height;
    if (width > most || height > most || bytes > device.limits().maxBufferSize()) {
      throw new IllegalArgumentException("Cannot render an image of " + width + "x" + height
          + " pixels: each side must be at most " + most + " and the image at most "
          + device.limits().maxBufferSize() + " bytes, the device's limits.");
    }
    Texture newTexture = device.createTexture(
        "scene image",
        width,
        height,
        TEXTURE_FORMAT,
        TextureUsage.RENDER_ATTACHMENT,
        TextureUsage.COPY_SRC);
    TextureView newView = null;
    Buffer newReadback;
    try {
      newView = newTexture.createView("scene image");
      newReadback =
          device.createBuffer("scene readback", bytes, BufferUsage.MAP_READ, BufferUsage.COPY_DST);
    } catch (RuntimeException | Error e) {
      if (newView != null) {
        newView.close();
      }
      newTexture.close();
      throw e;
    }
    if (texture != null) {
      readback.close();
      textureView.close();
      texture.close();
    }
    texture = newTexture;
    textureView = newView;
    readback = newReadback;
  }

  /** The bytes a row of an image of this width takes in {@link #renderStored}. */
  static int bytesPerRow(int width) {
    return Math.ceilDiv(width * 4, ROW_ALIGNMENT) * ROW_ALIGNMENT;
  }

  /**
   * Uploads {@code scene} and submits the commands that clear {@code target} to transparent and
   * draw the scene into it. The target's format must be an sRGB format, or blending does not
   * happen in linear light. The target must be of the context's current device.
   *
   * @param scale physical pixels per logical pixel; the target's size is in physical pixels
   */
  void draw(Scene scene, TextureView target, float scale) {
    checkOpen();
    upload(scene, target.width(), target.height(), scale);
    try (CommandEncoder encoder = device.createCommandEncoder("scene")) {
      recordPass(encoder, target, scene);
      device.queue().submit(encoder.finish());
    }
  }

  /**
   * Writes the globals, the instances, the clip table and what changed in the atlas pages, growing
   * the buffers and the atlas texture as needed.
   */
  private void upload(Scene scene, int width, int height, float scale) {
    if (!(scale > 0 && Float.isFinite(scale))) {
      throw new IllegalArgumentException("The scale is " + scale + "; it must be above 0.");
    }
    MemorySegment data = scene.data();
    MemorySegment table = scene.clips();
    List<Atlas.Page> pages = scene.pages();
    List<Atlas.Page> colorPages = scene.colorPages();
    boolean grow = data.byteSize() > instances.size() || table.byteSize() > clips.size();
    boolean newAtlas = !pages.isEmpty()
        && (pages.getFirst().size() != atlas.width() || pages.size() > atlas.layers());
    boolean newColorAtlas = !colorPages.isEmpty()
        && (colorPages.getFirst().size() != colorAtlas.width()
            || colorPages.size() > colorAtlas.layers());
    if (grow || newAtlas || newColorAtlas) {
      // Checked before anything is made, so that the renderer can still draw a smaller scene.
      checkFits("instances", data.byteSize());
      checkFits("clips", table.byteSize());
      replace(
          newAtlas ? pages.getFirst().size() : 0,
          newAtlas ? layers(pages.size()) : 0,
          newColorAtlas ? colorPages.getFirst().size() : 0,
          newColorAtlas ? layers(colorPages.size()) : 0,
          data.byteSize() > instances.size() ? grown(instances.size(), data.byteSize()) : 0,
          table.byteSize() > clips.size() ? grown(clips.size(), table.byteSize()) : 0);
    }
    globalsData.set(JAVA_FLOAT, 0, width);
    globalsData.set(JAVA_FLOAT, 4, height);
    globalsData.set(JAVA_FLOAT, 8, scale);
    device.queue().writeBuffer(globals, 0, globalsData);
    device.queue().writeBuffer(instances, 0, data);
    device.queue().writeBuffer(clips, 0, table);
    writePages(pages, atlas, resident);
    writePages(colorPages, colorAtlas, colorResident);
    writeImages(scene);
  }

  /**
   * Uploads the scene's images that are not on the GPU yet, each once, after giving up as many of
   * those drawn least recently as the scene's {@link Scene#imageMemory} needs, never one of this
   * scene nor of the last scene of another renderer on the device; a scene whose images alone
   * take more keeps them all. A scene without images applies its memory too, to the images that
   * earlier scenes left.
   */
  private void writeImages(Scene scene) {
    List<Image> drawn = scene.images();
    shown.clear();
    imageUploads += context.writeImages(drawn, scene.imageMemory(), this);
    shown.addAll(drawn);
  }

  /** Whether the last scene drawn shows {@code image}. */
  boolean shows(Image image) {
    return shown.contains(image);
  }

  /**
   * How many images went to the GPU for this renderer's scenes: each once, when it is first drawn
   * on the device, and again only when it is drawn after it was given up.
   */
  long imageUploads() {
    return imageUploads;
  }

  /** How many images are on the GPU of the renderer's device, and the bytes they take. */
  int imagesResident() {
    return context.imagesResident();
  }

  long imageBytes() {
    return context.imageBytes();
  }

  /**
   * Writes into each page's layer what changed in the page since the renderer last took it: the
   * region of {@link Atlas.Page#takeChanged}, which is the whole page after the atlas emptied it,
   * and nothing at all for a page that did not change. A page that the layer does not hold yet is
   * written whole, once: what the layer held before belongs to another page or to nothing, and
   * what the page held before its changed region is not known.
   */
  private void writePages(List<Atlas.Page> pages, Texture target, Atlas.Page[] resident) {
    for (int i = 0; i < pages.size(); i++) {
      Atlas.Page page = pages.get(i);
      Atlas.Region region = page.takeChanged();
      if (resident[i] != page) {
        region = new Atlas.Region(0, 0, page.size(), page.size());
      }
      if (region.isEmpty()) {
        continue;
      }
      int stride = page.bytesPerTexel();
      resident[i] = null; // the layer is not known should the write fail
      device
          .queue()
          .writeTexture(
              target,
              region.x(),
              region.y(),
              i,
              region.width(),
              region.height(),
              page.pixels(),
              ((long) region.y() * page.size() + region.x()) * stride,
              page.size() * stride);
      resident[i] = page;
      atlasWrites++;
      atlasTexels += (long) region.width() * region.height();
    }
  }

  /** The layers of an atlas texture for this many pages: the next power of two, within limits. */
  private int layers(int pages) {
    int limit = device.limits().maxTextureArrayLayers();
    if (pages > limit) {
      throw new IllegalArgumentException("The scene's glyphs lie in " + pages + " atlas pages,"
          + " more than the device's limit of " + limit + " layers of a texture array.");
    }
    int layers = Integer.highestOneBit(pages);
    return Math.min(layers < pages ? layers * 2 : layers, limit);
  }

  /**
   * Replaces the atlas textures with empty ones of {@code layers} / {@code colorLayers} pages of
   * {@code size} / {@code colorSize} texels (keeps each for 0 layers), whose every layer is then
   * written whole the next time; the instance and clip buffers with ones of these sizes (keeps
   * each for 0 bytes); and the bind group. All that is new is made before anything old is closed,
   * so that where the device refuses one, the renderer keeps what it had and still draws a scene
   * that fits.
   */
  private void replace(
      int size, int layers, int colorSize, int colorLayers, long instanceBytes, long clipBytes) {
    Texture newAtlas = null;
    TextureView newAtlasView = null;
    Texture newColorAtlas = null;
    TextureView newColorAtlasView = null;
    Buffer newInstances = null;
    Buffer newClips = null;
    BindGroup newGroup;
    try {
      if (layers > 0) {
        newAtlas = device.createTextureArray(
            "glyph atlas",
            size,
            size,
            layers,
            ATLAS_FORMAT,
            TextureUsage.TEXTURE_BINDING,
            TextureUsage.COPY_DST);
        newAtlasView = newAtlas.createView("glyph atlas");
      }
      if (colorLayers > 0) {
        newColorAtlas = device.createTextureArray(
            "color glyph atlas",
            colorSize,
            colorSize,
            colorLayers,
            COLOR_ATLAS_FORMAT,
            TextureUsage.TEXTURE_BINDING,
            TextureUsage.COPY_DST);
        newColorAtlasView = newColorAtlas.createView("color glyph atlas");
      }
      if (instanceBytes > 0) {
        newInstances = storage("scene instances", instanceBytes);
      }
      if (clipBytes > 0) {
        newClips = storage("scene clips", clipBytes);
      }
      newGroup = bindGroup(
          newInstances != null ? newInstances : instances,
          newClips != null ? newClips : clips,
          newAtlasView != null ? newAtlasView : atlasView,
          newColorAtlasView != null ? newColorAtlasView : colorAtlasView);
    } catch (RuntimeException | Error e) {
      for (GpuObject made : new GpuObject[] {
        newClips, newInstances, newColorAtlasView, newColorAtlas, newAtlasView, newAtlas
      }) {
        if (made != null) {
          made.close();
        }
      }
      throw e;
    }
    if (group != null) {
      group.close();
    }
    group = newGroup;
    if (newAtlas != null) {
      if (atlas != null) {
        atlasView.close();
        atlas.close();
      }
      atlas = newAtlas;
      atlasView = newAtlasView;
      resident = new Atlas.Page[layers];
    }
    if (newColorAtlas != null) {
      if (colorAtlas != null) {
        colorAtlasView.close();
        colorAtlas.close();
      }
      colorAtlas = newColorAtlas;
      colorAtlasView = newColorAtlasView;
      colorResident = new Atlas.Page[colorLayers];
    }
    if (newInstances != null) {
      if (instances != null) {
        instances.close();
      }
      instances = newInstances;
    }
    if (newClips != null) {
      if (clips != null) {
        clips.close();
      }
      clips = newClips;
    }
  }

  /** How many times a region of an atlas page was written to the GPU. */
  long atlasWrites() {
    return atlasWrites;
  }

  /** How many texels those writes carried. */
  long atlasTexels() {
    return atlasTexels;
  }

  /**
   * Records the pass that clears {@code target} and draws the scene's instances in order: in one
   * draw call, or where images are, in one per run of instances that the same image texture is
   * bound for. Instances that are not images read nothing of it.
   */
  private void recordPass(CommandEncoder encoder, TextureView target, Scene scene) {
    RenderPipeline pipeline = context.pipeline(target.format());
    int count = scene.count();
    try (RenderPass pass = encoder.beginRenderPass("scene", target, new Color(0, 0, 0, 0))) {
      if (count > 0) {
        pass.setPipeline(pipeline);
        pass.setBindGroup(0, group);
        BindGroup bound = context.blank();
        pass.setBindGroup(1, bound);
        int start = 0;
        List<Image> drawn = scene.images();
        for (int i = 0; i < count && !drawn.isEmpty(); i++) {
          if (scene.getInt(i, Scene.KIND) != Scene.IMAGE) {
            continue;
          }
          BindGroup group = context.imageGroup(drawn.get(scene.getInt(i, Scene.IMAGE_INDEX)));
          if (group != bound) {
            if (i > start) {
              pass.draw(4, i - start, 0, start);
            }
            pass.setBindGroup(1, group);
            bound = group;
            start = i;
          }
        }
        // One quad per instance, a triangle strip of four vertices: instances in order.
        pass.draw(4, count - start, 0, start);
      }
    }
  }

  private void checkFits(String what, long size) {
    long limit = storageLimit();
    if (size > limit) {
      throw new IllegalArgumentException("The scene's " + what + " take " + size + " bytes, more"
          + " than the device's limit of " + limit + " bytes for a storage buffer.");
    }
  }

  private Buffer storage(String label, long size) {
    return device.createBuffer(label, size, BufferUsage.STORAGE, BufferUsage.COPY_DST);
  }

  /** Doubles {@code size} until it holds {@code needed}, or just {@code needed} if too much. */
  private long grown(long size, long needed) {
    long grown = size;
    while (grown < needed) {
      grown *= 2;
    }
    return grown <= storageLimit() ? grown : needed;
  }

  private long storageLimit() {
    return Math.min(
        device.limits().maxStorageBufferBindingSize(), device.limits().maxBufferSize());
  }

  private BindGroup bindGroup(
      Buffer instances, Buffer clips, TextureView atlasView, TextureView colorAtlasView) {
    return device.createBindGroup(
        "scene",
        context.layout(),
        BindGroup.Entry.buffer(0, globals),
        BindGroup.Entry.buffer(1, instances),
        BindGroup.Entry.buffer(2, clips),
        BindGroup.Entry.texture(3, atlasView),
        BindGroup.Entry.texture(4, colorAtlasView));
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("The GPU renderer is closed.");
    }
  }

  /**
   * Closes what the renderer made, newest first, and, if it made its own device with {@link
   * #create}, the device too; a shared device stays for the others. Closing it again does nothing.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    context.remove(this);
    if (ownsContext) {
      context.release();
    } else if (!context.isClosed()) {
      closeOwn();
    }
  }

  /** Closes the renderer's own objects on the device, newest first. */
  private void closeOwn() {
    for (GpuObject object : new GpuObject[] {
      readback,
      textureView,
      texture,
      group,
      colorAtlasView,
      colorAtlas,
      atlasView,
      atlas,
      clips,
      instances,
      globals
    }) {
      if (object != null && !object.isClosed()) {
        object.close();
      }
    }
  }
}
