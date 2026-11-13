package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.TextRendering;

/**
 * Renders a view without a window or a GPU, and without native libraries unless the view has text,
 * which needs FreeType and HarfBuzz. Tests and agents use this to look at what a view produces:
 * the image, and the tree with every element's rectangle.
 */
public final class Headless {

  /**
   * @param pixels the rendered image
   * @param tree the laid-out tree as text, see {@link El#dump}
   */
  public record Frame(Pixels pixels, String tree) {}

  private Headless() {}

  /**
   * @param width the width of the window in logical pixels
   * @param height the height of the window in logical pixels
   * @param scale physical pixels per logical pixel, for example 2 on a high-density display
   */
  public static Frame render(El root, float width, float height, float scale) {
    return render(root, width, height, scale, TextRendering.platform());
  }

  /**
   * Renders with the given text rendering instead of the platform's, as tests do so that their
   * images are the same everywhere.
   */
  public static Frame render(
      El root, float width, float height, float scale, TextRendering textRendering) {
    El.scaleText(root, scale);
    Flex.layout(root, width, height);
    fillRows(root, 0, height, scale);
    Scene scene = Scene.of(root, null, 0, new Atlas(), textRendering);
    Pixels pixels =
        CpuRenderer.render(scene, Math.round(width * scale), Math.round(height * scale), scale);
    return new Frame(pixels, root.dump());
  }

  /** Builds the rows of the virtual lists ({@link El#rows}) that show at their offset of 0. */
  private static void fillRows(El el, float parentY, float windowHeight, float scale) {
    if (!el.isPlaced()) {
      return;
    }
    float top = parentY + el.y();
    int[] range = el.wantedRows(top, windowHeight);
    if (range != null) {
      el.makeRows(range[0], range[1]);
      el.layoutRows(scale);
    }
    for (El child : el.children()) {
      fillRows(child, top, windowHeight, scale);
    }
  }
}
