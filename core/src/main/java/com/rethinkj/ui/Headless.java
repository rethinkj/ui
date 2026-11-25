package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.TextRendering;
import java.util.List;

/**
 * Renders a view without a window or a GPU, and without native libraries unless the view has text,
 * which needs FreeType and HarfBuzz. Tests and agents use this to look at what a view produces:
 * the image, and the tree with every element's rectangle. Overlays are laid out and placed as in
 * an {@link App}, in tree order.
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
    El.fix(root);
    El.scaleText(root, scale);
    Flex.layout(root, width, height);
    Overlays.layout(root, width, height);
    El.measureScrolls(root);
    float far = Float.POSITIVE_INFINITY;
    fillRows(root, 0, 0, -far, far, width, height, scale);
    Overlays.place(root, width, height);
    for (El overlay : root.shownOverlays()) {
      fillRows(overlay, 0, 0, -far, far, width, height, scale);
    }
    El.placeBars(root);
    El.cycles(root, 0, width, height, false); // phase 0, as nothing has a clock here
    Scene scene = Scene.of(root, null, 0, new Atlas(), textRendering, scale);
    Pixels pixels =
        CpuRenderer.render(scene, Math.round(width * scale), Math.round(height * scale), scale);
    return new Frame(pixels, root.dump());
  }

  /**
   * Builds the rows of the virtual lists ({@link El#rows}) that show at their offset of 0, and
   * measures how far each scroll area could scroll, for its bars. A row whose lists, as high as
   * their rows, grew with rows they measured is laid out again, as {@link App} does.
   *
   * @return whether such a list at or below {@code el} grew and no list around it took that yet
   */
  private static boolean fillRows(
      El el,
      float parentX,
      float parentY,
      float clipLeft,
      float clipRight,
      float windowWidth,
      float windowHeight,
      float scale) {
    if (!el.isPlaced()) {
      return false;
    }
    el.measureScroll();
    float left = parentX + el.x();
    float top = parentY + el.y();
    buildRows(
        el,
        el.wantedRows(left, top, clipLeft, clipRight, windowWidth, windowHeight),
        windowWidth,
        windowHeight,
        scale);
    if (el.style().overflowX != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(clipLeft, left);
      clipRight = Math.min(clipRight, left + el.width());
    }
    boolean regrown = el.rowsRegrown();
    for (int round = 0; ; round++) {
      boolean relaid = false;
      List<El> children = el.children();
      for (int i = 0; i < children.size(); i++) {
        boolean grew = fillRows(
            children.get(i), left, top, clipLeft, clipRight, windowWidth, windowHeight, scale);
        if (grew && el.isVirtualList() && el.measuresRows() && round < 3) {
          el.layoutRow(i, scale, false);
          el.remeasureRow(i);
          settle(children.get(i));
          relaid = true;
        }
        regrown |= grew && !el.isVirtualList();
      }
      if (!relaid) {
        return regrown;
      }
      buildRows(
          el,
          el.wantedRows(left, top, clipLeft, clipRight, windowWidth, windowHeight),
          windowWidth,
          windowHeight,
          scale);
    }
  }

  private static void buildRows(
      El el, int[] range, float windowWidth, float windowHeight, float scale) {
    if (range.length != 0) {
      el.makeRows(range[0], range[1], row -> true); // layout placed the rows it has
      el.layoutRows(scale, false);
      for (El row : el.children()) {
        Overlays.layout(row, windowWidth, windowHeight);
      }
      el.measureRowScrolls();
    }
  }

  /** Takes the heights of the lists in a row laid out again as they measured. */
  private static void settle(El el) {
    if (el.rowsRegrown()) {
      el.rowsSettled();
    }
    for (El child : el.children()) {
      settle(child);
    }
  }
}
