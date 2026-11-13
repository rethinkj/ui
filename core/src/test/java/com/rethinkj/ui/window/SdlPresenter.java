package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.rethinkj.ui.natives.Natives;
import com.rethinkj.ui.render.Scene;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Draws a {@link Scene} with SDL's own 2D renderer, until the GPU renderer exists: rounded
 * rectangles as runs of one-pixel rows, without anti-aliasing, and borders only of square or
 * filled ones. For tests and the demo; it is not part of the library.
 */
final class SdlPresenter implements WindowLoop.Presenter, AutoCloseable {

  private static final int BLENDMODE_BLEND = 1;

  static final FunctionDescriptor SDL_CREATE_RENDERER =
      FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_DESTROY_RENDERER = FunctionDescriptor.ofVoid(ADDRESS);
  static final FunctionDescriptor SDL_GET_RENDERER_NAME = FunctionDescriptor.of(ADDRESS, ADDRESS);
  static final FunctionDescriptor SDL_SET_RENDER_VSYNC =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT);
  static final FunctionDescriptor SDL_SET_RENDER_DRAW_BLEND_MODE =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT);
  static final FunctionDescriptor SDL_SET_RENDER_DRAW_COLOR =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_BYTE, JAVA_BYTE, JAVA_BYTE, JAVA_BYTE);
  static final FunctionDescriptor SDL_RENDER_CLEAR = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);
  static final FunctionDescriptor SDL_RENDER_FILL_RECTS =
      FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, JAVA_INT);
  static final FunctionDescriptor SDL_RENDER_PRESENT = FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS);

  private static final class H {
    static final MethodHandle CREATE_RENDERER = bind("SDL_CreateRenderer", SDL_CREATE_RENDERER);
    static final MethodHandle DESTROY_RENDERER = bind("SDL_DestroyRenderer", SDL_DESTROY_RENDERER);
    static final MethodHandle GET_RENDERER_NAME =
        bind("SDL_GetRendererName", SDL_GET_RENDERER_NAME);
    static final MethodHandle SET_RENDER_VSYNC = bind("SDL_SetRenderVSync", SDL_SET_RENDER_VSYNC);
    static final MethodHandle SET_RENDER_DRAW_BLEND_MODE =
        bind("SDL_SetRenderDrawBlendMode", SDL_SET_RENDER_DRAW_BLEND_MODE);
    static final MethodHandle SET_RENDER_DRAW_COLOR =
        bind("SDL_SetRenderDrawColor", SDL_SET_RENDER_DRAW_COLOR);
    static final MethodHandle RENDER_CLEAR = bind("SDL_RenderClear", SDL_RENDER_CLEAR);
    static final MethodHandle RENDER_FILL_RECTS = bind("SDL_RenderFillRects", SDL_RENDER_FILL_RECTS);
    static final MethodHandle RENDER_PRESENT = bind("SDL_RenderPresent", SDL_RENDER_PRESENT);

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
      return Linker.nativeLinker()
          .downcallHandle(Natives.lookup("sdl3").find(name).orElseThrow(), descriptor);
    }
  }

  private final MemorySegment renderer;
  private final String name;
  private MemorySegment rects = Arena.ofAuto().allocate(16L * 256, 4);
  private int count;
  private int presents;

  SdlPresenter(Window window) {
    try {
      renderer = (MemorySegment) H.CREATE_RENDERER.invokeExact(window.sdlWindow(),
          MemorySegment.NULL);
      if (renderer.equals(MemorySegment.NULL)) {
        throw new IllegalStateException("SDL_CreateRenderer failed: " + Sdl.getError());
      }
      name = Sdl.string((MemorySegment) H.GET_RENDERER_NAME.invokeExact(renderer));
      boolean ignored = (boolean) H.SET_RENDER_VSYNC.invokeExact(renderer, 1);
      ignored = (boolean) H.SET_RENDER_DRAW_BLEND_MODE.invokeExact(renderer, BLENDMODE_BLEND);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  String name() {
    return name;
  }

  int presents() {
    return presents;
  }

  @Override
  public void present(Scene scene, float width, float height, float scale) {
    try {
      color(0xFF000000);
      boolean ignored = (boolean) H.RENDER_CLEAR.invokeExact(renderer);
      for (int i = 0; i < scene.count(); i++) {
        float x = scene.get(i, Scene.X) * scale;
        float y = scene.get(i, Scene.Y) * scale;
        float w = scene.get(i, Scene.WIDTH) * scale;
        float h = scene.get(i, Scene.HEIGHT) * scale;
        float r = scene.get(i, Scene.RADIUS) * scale;
        float b = scene.get(i, Scene.BORDER_WIDTH) * scale;
        int fill = argb(scene, i, Scene.FILL);
        int border = argb(scene, i, Scene.BORDER);
        if (b > 0 && fill >>> 24 == 0) {
          color(border);
          rect(x, y, w, b);
          rect(x, y + h - b, w, b);
          rect(x, y + b, b, h - 2 * b);
          rect(x + w - b, y + b, b, h - 2 * b);
          flush();
          continue;
        }
        if (b > 0) {
          color(border);
          rounded(x, y, w, h, r);
          flush();
          x += b;
          y += b;
          w -= 2 * b;
          h -= 2 * b;
          r = Math.max(0, r - b);
        }
        color(fill);
        rounded(x, y, w, h, r);
        flush();
      }
      ignored = (boolean) H.RENDER_PRESENT.invokeExact(renderer);
      presents++;
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }

  /** A rounded rectangle as rows of one pixel where the corners curve, and one rectangle between. */
  private void rounded(float x, float y, float w, float h, float r) {
    r = Math.clamp(r, 0, Math.min(w, h) / 2);
    int rows = (int) Math.ceil(r);
    if (rows == 0) {
      rect(x, y, w, h);
      return;
    }
    for (int i = 0; i < rows; i++) {
      float dy = r - (i + 0.5f);
      float inset = r - (float) Math.sqrt(Math.max(0, r * r - dy * dy));
      rect(x + inset, y + i, w - 2 * inset, 1);
      rect(x + inset, y + h - 1 - i, w - 2 * inset, 1);
    }
    rect(x, y + rows, w, h - 2 * rows);
  }

  private void rect(float x, float y, float w, float h) {
    if (w <= 0 || h <= 0) {
      return;
    }
    if ((count + 1) * 16L > rects.byteSize()) {
      MemorySegment grown = Arena.ofAuto().allocate(rects.byteSize() * 2, 4);
      grown.copyFrom(rects);
      rects = grown;
    }
    long offset = count * 16L;
    rects.set(JAVA_FLOAT, offset, x);
    rects.set(JAVA_FLOAT, offset + 4, y);
    rects.set(JAVA_FLOAT, offset + 8, w);
    rects.set(JAVA_FLOAT, offset + 12, h);
    count++;
  }

  private void flush() throws Throwable {
    if (count > 0) {
      boolean ignored = (boolean) H.RENDER_FILL_RECTS.invokeExact(renderer, rects, count);
      count = 0;
    }
  }

  private void color(int argb) throws Throwable {
    boolean ignored = (boolean) H.SET_RENDER_DRAW_COLOR.invokeExact(renderer,
        (byte) (argb >> 16), (byte) (argb >> 8), (byte) argb, (byte) (argb >>> 24));
  }

  /** A colour of the scene, which is linear and premultiplied, as sRGB 0xAARRGGBB. */
  private static int argb(Scene scene, int instance, int field) {
    float a = scene.get(instance, field + 12);
    if (a <= 0) {
      return 0;
    }
    int argb = Math.round(Math.min(a, 1) * 255) << 24;
    for (int channel = 0; channel < 3; channel++) {
      double c = Math.clamp(scene.get(instance, field + 4 * channel) / a, 0, 1);
      double srgb = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
      argb |= (int) Math.round(srgb * 255) << (16 - 8 * channel);
    }
    return argb;
  }

  @Override
  public void close() {
    try {
      H.DESTROY_RENDERER.invokeExact(renderer);
    } catch (Throwable t) {
      throw Sdl.failed(t);
    }
  }
}
