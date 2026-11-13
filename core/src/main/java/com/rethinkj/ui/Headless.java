package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Scene;

/**
 * Renders a view without a window, a GPU or any native library. Tests and agents use this to look
 * at what a view produces: the image, and the tree with every element's rectangle.
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
    El.scaleText(root, scale);
    Flex.layout(root, width, height);
    Pixels pixels =
        CpuRenderer.render(
            Scene.of(root), Math.round(width * scale), Math.round(height * scale), scale);
    return new Frame(pixels, root.dump());
  }
}
